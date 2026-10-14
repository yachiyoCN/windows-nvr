package cn.homewatch;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Offline COCO person detection using the Apache-2.0 YOLOX model and ONNX Runtime. */
public final class PersonDetector implements AutoCloseable {
    public static final int INPUT_SIZE = 416;
    public enum SearchMode { FAST, BALANCED, ACCURATE }
    /** A camera-local area excluded from person events, without changing its recording. */
    public record IgnoreRegion(double x, double y, double width, double height) {
        public IgnoreRegion {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width)
                    || !Double.isFinite(height) || x < 0 || y < 0 || x >= 1 || y >= 1
                    || width <= 0 || height <= 0) throw new IllegalArgumentException("忽略区域必须位于画面内");
            width = Math.min(width, 1 - x);
            height = Math.min(height, 1 - y);
        }
    }
    /** A clipped box in source-frame coordinates, normalized to 0..1. */
    public record PersonBox(double x, double y, double width, double height, float score, boolean strong) {}
    /** A candidate is evidence for the temporal tracker, rather than a final person event. */
    public record DetectionResult(boolean person, float score, int passes, List<PersonBox> boxes, boolean monochrome) {
        public DetectionResult { boxes = List.copyOf(boxes); }
        public DetectionResult(boolean person, float score, int passes) { this(person, score, passes, List.of(), false); }
        public boolean strongEvidence() { return boxes.stream().anyMatch(PersonBox::strong); }
    }
    private record Region(int x, int y, int width, int height) {}
    private record Geometry(int width, int height, int[] x0, int[] x1, float[] fx,
                            int[] y0, int[] y1, float[] fy) {}
    private record Inference(float score, List<PersonBox> boxes) {}
    // JNI libraries can only belong to one class loader for the lifetime of this JVM.
    private static URLClassLoader runtimeLoader;
    private final Object environment;
    private final Object session;
    private final Method tensorFactory;
    private final Method run;
    private final Method resultGet;
    private final Method tensorValue;
    private final Method tensorFloats;
    private final String inputName;
    private final float confidence;
    private final float[] input = new float[3 * INPUT_SIZE * INPUT_SIZE];
    private final FloatBuffer inputBuffer = ByteBuffer.allocateDirect(input.length * Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final Map<Region, Geometry> geometries = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Region, Geometry> eldest) { return size() > 20; }
    };
    private final AutoCloseable inputTensor;
    private volatile boolean closed;

    public PersonDetector(Path root, float confidence) throws Exception {
        if (!(confidence > 0 && confidence < 1)) throw new IllegalArgumentException("confidence");
        this.confidence = confidence;
        Path directory = root.toAbsolutePath().normalize().resolve("tools/person-detection");
        Path library = directory.resolve("onnxruntime-1.20.0.jar");
        Path model = directory.resolve("yolox_tiny.onnx");
        if (!Files.isRegularFile(library) || !Files.isRegularFile(model)) {
            throw new IOException("缺少本地人形识别模型或运行库");
        }
        URLClassLoader loader = runtimeLoader(directory, library);
        Class<?> environmentClass = Class.forName("ai.onnxruntime.OrtEnvironment", true, loader);
        Class<?> optionsClass = Class.forName("ai.onnxruntime.OrtSession$SessionOptions", true, loader);
        Class<?> sessionClass = Class.forName("ai.onnxruntime.OrtSession", true, loader);
        Class<?> tensorClass = Class.forName("ai.onnxruntime.OnnxTensor", true, loader);
        Class<?> resultClass = Class.forName("ai.onnxruntime.OrtSession$Result", true, loader);
        environment = environmentClass.getMethod("getEnvironment").invoke(null);
        Object options = optionsClass.getConstructor().newInstance();
        Object openedSession = null;
        try {
            optionsClass.getMethod("setIntraOpNumThreads", int.class).invoke(options, 2);
            optionsClass.getMethod("setInterOpNumThreads", int.class).invoke(options, 1);
            openedSession = environmentClass.getMethod("createSession", String.class, optionsClass)
                    .invoke(environment, model.toString(), options);
        } finally {
            ((AutoCloseable) options).close();
        }
        session = openedSession;
        @SuppressWarnings("unchecked")
        Set<String> inputNames = (Set<String>) sessionClass.getMethod("getInputNames").invoke(session);
        if (inputNames.size() != 1) throw new IOException("人形模型输入格式不支持");
        inputName = inputNames.iterator().next();
        tensorFactory = tensorClass.getMethod("createTensor", environmentClass, FloatBuffer.class, long[].class);
        run = sessionClass.getMethod("run", Map.class);
        resultGet = resultClass.getMethod("get", int.class);
        tensorValue = tensorClass.getMethod("getValue");
        tensorFloats = tensorClass.getMethod("getFloatBuffer");
        // A direct buffer-backed tensor is reused by all channels; detectPerson is synchronized.
        inputTensor = (AutoCloseable) invoke(tensorFactory, null, environment,
                inputBuffer, new long[] {1, 3, INPUT_SIZE, INPUT_SIZE});
    }

    private static synchronized URLClassLoader runtimeLoader(Path directory, Path library) throws IOException {
        if (runtimeLoader == null) {
            Path nativeDirectory = directory.resolve("native");
            extractWindowsNatives(library, nativeDirectory);
            // Runtime reads this property before its classes initialize. All native files stay in the project.
            System.setProperty("onnxruntime.native.path", nativeDirectory.toString());
            runtimeLoader = new URLClassLoader(new java.net.URL[] {library.toUri().toURL()},
                    PersonDetector.class.getClassLoader());
        }
        return runtimeLoader;
    }

    /** Input is a 416×416 BGR frame, letterboxed with pixel value 114. */
    public synchronized boolean containsPerson(byte[] bgr) throws Exception {
        if (closed) throw new IOException("检测器已关闭");
        if (bgr.length != input.length) throw new IllegalArgumentException("frame size");
        int pixels = INPUT_SIZE * INPUT_SIZE;
        // YOLOX uses raw 0..255 BGR channels, rather than RGB / 255 preprocessing.
        for (int i = 0, k = 0; i < pixels; i++, k += 3) {
            input[i] = bgr[k] & 255;
            input[pixels + i] = bgr[k + 1] & 255;
            input[2 * pixels + i] = bgr[k + 2] & 255;
        }
        return infer(new Region(0, 0, INPUT_SIZE, INPUT_SIZE), INPUT_SIZE, INPUT_SIZE,
                INPUT_SIZE, INPUT_SIZE, true, false).score >= confidence;
    }

    /**
     * Keeps detail before inference, then searches overlapping crops when the whole frame misses.
     * Only an actual highest-scoring COCO person prediction can qualify; movement is not a label.
     */
    public synchronized DetectionResult detectPerson(byte[] bgr, int width, int height, SearchMode mode) throws Exception {
        if (closed) throw new IOException("检测器已关闭");
        if (width <= 0 || height <= 0 || bgr.length != width * height * 3) throw new IllegalArgumentException("frame size");
        float threshold = Math.max(confidence, 0.28f);
        boolean monochrome = monochrome(bgr, width, height);
        Region whole = new Region(0, 0, width, height);
        Geometry content = prepareRegion(bgr, width, whole);
        Inference found = infer(whole, width, height, content.width, content.height, true, monochrome);
        float score = found.score;
        int passes = 1;
        List<PersonBox> boxes = new ArrayList<>(found.boxes);
        if (score >= threshold || mode == SearchMode.FAST) return result(boxes, score, passes, monochrome, threshold, bgr, width, height, mode != SearchMode.FAST);
        float best = score;
        for (Region region : regions(width, height, SearchMode.BALANCED)) {
            content = prepareRegion(bgr, width, region);
            found = infer(region, width, height, content.width, content.height, true, monochrome);
            score = found.score;
            passes++;
            best = Math.max(best, score);
            boxes.addAll(found.boxes);
            if (score >= threshold) return result(boxes, best, passes, monochrome, threshold, bgr, width, height, true);
        }
        // Expensive detail search is for uncertain person candidates. Empty backgrounds normally
        // finish after three passes; accepted detections always remain actual model predictions.
        // Monochrome sources lose the color cues used by the COCO model. Search a
        // finer real crop sooner, while keeping the person acceptance threshold intact.
        if (mode == SearchMode.ACCURATE && best >= (monochrome ? .10f : .15f)) {
            List<Region> detail = regions(width, height, SearchMode.ACCURATE);
            // Low-scoring monochrome proposals guide two real detail views. A weak
            // background must not force all six expensive views on every frame.
            if (monochrome && best < .15f && !boxes.isEmpty()) {
                PersonBox hint=boxes.stream().max(Comparator.comparingDouble(PersonBox::score)).orElseThrow();
                detail.sort(Comparator.comparingDouble(region -> cropDistance(region, width, height, hint)));
                detail=new ArrayList<>(detail.subList(0,Math.min(2,detail.size())));
            }
            for (Region region : detail) {
                content = prepareRegion(bgr, width, region);
                found = infer(region, width, height, content.width, content.height, true, monochrome);
                score = found.score;
                passes++;
                best = Math.max(best, score);
                boxes.addAll(found.boxes);
                if (score >= threshold) return result(boxes, best, passes, monochrome, threshold, bgr, width, height, true);
            }
        }
        return result(boxes, best, passes, monochrome, threshold, bgr, width, height, true);
    }

    /** Screens may display actual people: pixels cannot reliably distinguish them from the room. */
    static DetectionResult excludeRegions(DetectionResult result, List<IgnoreRegion> ignored) {
        if (ignored.isEmpty() || result.boxes().isEmpty()) return result;
        List<PersonBox> allowed = result.boxes().stream()
                .filter(box -> ignored.stream().noneMatch(region -> excluded(box, region))).toList();
        return new DetectionResult(!allowed.isEmpty(),
                allowed.stream().map(PersonBox::score).max(Float::compare).orElse(0f),
                result.passes(), allowed, result.monochrome());
    }

    private static boolean excluded(PersonBox box, IgnoreRegion region) {
        double overlapWidth = Math.max(0, Math.min(box.x + box.width, region.x + region.width) - Math.max(box.x, region.x));
        double overlapHeight = Math.max(0, Math.min(box.y + box.height, region.y + region.height) - Math.max(box.y, region.y));
        double covered = overlapWidth * overlapHeight / Math.max(1e-10, box.width * box.height);
        double centerX = box.x + box.width * .5;
        return covered >= .60 || inside(centerX, box.y + box.height * .5, region)
                && inside(centerX, box.y + box.height * .95, region);
    }

    private static boolean inside(double x, double y, IgnoreRegion region) {
        return x >= region.x && x <= region.x + region.width && y >= region.y && y <= region.y + region.height;
    }

    private static double cropDistance(Region region,int width,int height,PersonBox hint) {
        double x=(region.x+region.width*.5)/width-(hint.x+hint.width*.5);
        double y=(region.y+region.height*.5)/height-(hint.y+hint.height*.5);
        return x*x+y*y;
    }

    private DetectionResult result(List<PersonBox> boxes, float best, int passes,
                                  boolean monochrome, float threshold, byte[] source, int width, int height,
                                  boolean refine) throws Exception {
        // One focused real crop recovers detail for a distant or partly occluded person.
        // It never adds an unrelated object and does not count as another temporal frame.
        PersonBox hint = boxes.stream().filter(box -> box.score >= threshold)
                .max(Comparator.comparingDouble(PersonBox::score)).orElse(null);
        if (refine && hint != null && hint.score < .66f) {
            Region region = candidateRegion(width, height, hint);
            Geometry content = prepareRegion(source, width, region);
            Inference focused = infer(region, width, height, content.width, content.height, true, monochrome);
            passes++;
            for (PersonBox candidate : focused.boxes) if (overlap(candidate, hint) >= .20) {
                boxes.add(candidate); best = Math.max(best, candidate.score);
            }
        }
        List<PersonBox> accepted = boxes.stream().filter(box -> box.score >= threshold)
                .sorted(Comparator.comparingDouble(PersonBox::score).reversed()).toList();
        List<PersonBox> distinct = new ArrayList<>();
        for (PersonBox candidate : accepted) {
            if (distinct.stream().noneMatch(other -> overlap(candidate, other) >= .45)) distinct.add(candidate);
            if (distinct.size() == 12) break;
        }
        // A small colored target must not become "infrared" merely because most of
        // the room consists of gray walls. Inspect the candidate itself before tracking.
        if (monochrome && distinct.stream().anyMatch(box -> coloredBox(source, width, height, box))) monochrome=false;
        return new DetectionResult(!distinct.isEmpty(), best, passes, distinct, monochrome);
    }

    private static Region candidateRegion(int width, int height, PersonBox hint) {
        int cropHeight = Math.min(height, Math.max(96, (int)Math.ceil(hint.height * height * 2.2)));
        int cropWidth = Math.min(width, Math.max(cropHeight, (int)Math.ceil(hint.width * width * 2.4)));
        int x = Math.max(0, Math.min(width - cropWidth, (int)Math.round((hint.x + hint.width * .5) * width - cropWidth * .5)));
        int y = Math.max(0, Math.min(height - cropHeight, (int)Math.round((hint.y + hint.height * .5) * height - cropHeight * .5)));
        return new Region(x, y, cropWidth, cropHeight);
    }

    private static boolean coloredBox(byte[] source, int width, int height, PersonBox box) {
        int left=Math.max(0,(int)(box.x*width)), top=Math.max(0,(int)(box.y*height));
        int right=Math.min(width,(int)Math.ceil((box.x+box.width)*width));
        int bottom=Math.min(height,(int)Math.ceil((box.y+box.height)*height));
        int step=Math.max(1,Math.max(right-left,bottom-top)/48), sampled=0,colored=0;
        for(int y=top;y<bottom;y+=step)for(int x=left;x<right;x+=step) {
            int p=(y*width+x)*3,b=source[p]&255,g=source[p+1]&255,r=source[p+2]&255;
            sampled++;
            if(Math.max(b,Math.max(g,r))-Math.min(b,Math.min(g,r))>12)colored++;
        }
        return colored>Math.max(2,sampled*.025);
    }

    private static double overlap(PersonBox a, PersonBox b) {
        double intersection = Math.max(0, Math.min(a.x+a.width,b.x+b.width)-Math.max(a.x,b.x))
                * Math.max(0, Math.min(a.y+a.height,b.y+b.height)-Math.max(a.y,b.y));
        return intersection / Math.max(1e-10, a.width*a.height+b.width*b.height-intersection);
    }

    private static boolean monochrome(byte[] bgr, int width, int height) {
        int colored=0, sampled=0;
        int step=Math.max(1, width*height/2048);
        for (int pixel=0; pixel<width*height; pixel+=step) {
            int p=pixel*3, b=bgr[p]&255, g=bgr[p+1]&255, r=bgr[p+2]&255;
            if (Math.max(b,Math.max(g,r))-Math.min(b,Math.min(g,r))>9) colored++;
            sampled++;
        }
        // A small colored person against gray walls is still a color source. Only a
        // nearly achromatic frame selects the more cautious infrared confirmation profile.
        return colored < sampled*.003;
    }

    private static List<Region> regions(int width, int height, SearchMode mode) {
        List<Region> result = new ArrayList<>();
        if (mode == SearchMode.BALANCED) {
            int cropWidth = Math.min(width, Math.max((int)Math.ceil(width * 0.56), height));
            if (cropWidth==width) return result;
            result.add(new Region(0, 0, cropWidth, height));
            result.add(new Region(width - cropWidth, 0, cropWidth, height));
        } else {
            int cropWidth = (int)Math.ceil(width * 0.45);
            int cropHeight = (int)Math.ceil(height * 0.80);
            for (int y : new int[]{0, height - cropHeight}) {
                for (int x : new int[]{0, (width - cropWidth) / 2, width - cropWidth}) {
                    result.add(new Region(x, y, cropWidth, cropHeight));
                }
            }
        }
        return result;
    }

    private Geometry prepareRegion(byte[] source, int width, Region region) {
        Arrays.fill(input, 114f);
        Geometry geometry = geometries.computeIfAbsent(region, PersonDetector::geometry);
        int resizedWidth = geometry.width, resizedHeight = geometry.height;
        int pixels = INPUT_SIZE * INPUT_SIZE;
        for (int y = 0; y < resizedHeight; y++) {
            int row0 = ((region.y + geometry.y0[y])*width + region.x)*3;
            int row1 = ((region.y + geometry.y1[y])*width + region.x)*3;
            float fy=geometry.fy[y], iy=1-fy;
            for (int x = 0; x < resizedWidth; x++) {
                float fx=geometry.fx[x], ix=1-fx;
                int p00 = row0 + geometry.x0[x]*3;
                int p01 = row0 + geometry.x1[x]*3;
                int p10 = row1 + geometry.x0[x]*3;
                int p11 = row1 + geometry.x1[x]*3;
                int pixel = y * INPUT_SIZE + x;
                for (int channel = 0; channel < 3; channel++) {
                    float top = (source[p00 + channel] & 255) * ix + (source[p01 + channel] & 255) * fx;
                    float bottom = (source[p10 + channel] & 255) * ix + (source[p11 + channel] & 255) * fx;
                    input[channel * pixels + pixel] = top * iy + bottom * fy;
                }
            }
        }
        return geometry;
    }

    private static Geometry geometry(Region region) {
        double ratio=Math.min(INPUT_SIZE/(double)region.width,INPUT_SIZE/(double)region.height);
        int width=Math.max(1,Math.min(INPUT_SIZE,(int)Math.round(region.width*ratio)));
        int height=Math.max(1,Math.min(INPUT_SIZE,(int)Math.round(region.height*ratio)));
        int[] x0=new int[width],x1=new int[width],y0=new int[height],y1=new int[height];
        float[] fx=new float[width],fy=new float[height];
        for(int x=0;x<width;x++) {
            double position=(x+.5)*region.width/width-.5;
            x0[x]=Math.max(0,Math.min(region.width-1,(int)Math.floor(position)));
            x1[x]=Math.min(region.width-1,x0[x]+1);
            fx[x]=(float)Math.max(0,Math.min(1,position-x0[x]));
        }
        for(int y=0;y<height;y++) {
            double position=(y+.5)*region.height/height-.5;
            y0[y]=Math.max(0,Math.min(region.height-1,(int)Math.floor(position)));
            y1[y]=Math.min(region.height-1,y0[y]+1);
            fy[y]=(float)Math.max(0,Math.min(1,position-y0[y]));
        }
        return new Geometry(width,height,x0,x1,fx,y0,y1,fy);
    }

    private Inference infer(Region region, int sourceWidth, int sourceHeight, int contentWidth,
                            int contentHeight, boolean requireTopClass, boolean monochrome) throws Exception {
        inputBuffer.clear(); inputBuffer.put(input); inputBuffer.rewind();
        try (AutoCloseable result = (AutoCloseable) invoke(run, session, Map.of(inputName, inputTensor))) {
            Object output = invoke(resultGet, result, 0);
            Object floats = invoke(tensorFloats, output);
            if (floats instanceof java.util.Optional<?> optional) floats=optional.orElse(null);
            if (!(floats instanceof FloatBuffer rows)) throw new IOException("人形模型输出格式不支持");
            int expected = 52 * 52 + 26 * 26 + 13 * 13;
            if (rows.remaining() != expected * 85) {
                throw new IOException("人形模型不是受支持的 YOLOX-tiny 416 COCO 模型");
            }
            float best = 0;
            List<PersonBox> boxes = new ArrayList<>();
            int index = 0;
            for (int stride : new int[] {8, 16, 32}) {
                int width = INPUT_SIZE / stride;
                for (int y = 0; y < width; y++) {
                    for (int x = 0; x < width; x++, index++) {
                        int base=index*85;
                        float objectness=rows.get(base+4), personClass=rows.get(base+5);
                        float score = objectness * personClass;
                        if (!Float.isFinite(score) || score < .001f || objectness<0 || objectness>1
                                || personClass<0 || personClass>1) continue;
                        float otherClass=0;
                        if (requireTopClass) {
                            for (int category = 6; category < 85; category++) {
                                otherClass=Math.max(otherClass,rows.get(base+category));
                            }
                            // The strongest COCO label must be person with a real margin. Close
                            // chair/animal ties are uncertain scene objects, even at high objectness.
                            if (!personClassQualifies(personClass, otherClass)) continue;
                        }
                        double centerX = (rows.get(base) + x) * stride;
                        double centerY = (rows.get(base+1) + y) * stride;
                        double boxWidth = Math.exp(rows.get(base+2)) * stride;
                        double boxHeight = Math.exp(rows.get(base+3)) * stride;
                        if (!Double.isFinite(centerX) || !Double.isFinite(centerY)
                                || !Double.isFinite(boxWidth) || !Double.isFinite(boxHeight)
                                || boxWidth<4 || boxHeight<8 || boxHeight/boxWidth<.35 || boxHeight/boxWidth>8
                                || centerX<0 || centerX>=contentWidth || centerY<0 || centerY>=contentHeight
                                || boxWidth>contentWidth*1.8 || boxHeight>contentHeight*1.8) continue;
                        double left=Math.max(0,centerX-boxWidth/2), top=Math.max(0,centerY-boxHeight/2);
                        double right=Math.min(contentWidth,centerX+boxWidth/2), bottom=Math.min(contentHeight,centerY+boxHeight/2);
                        double visible=(right-left)*(bottom-top)/(boxWidth*boxHeight);
                        boolean sourceBorder=(left==0 && region.x==0)||(right==contentWidth&&region.x+region.width==sourceWidth)
                                ||(top==0&&region.y==0)||(bottom==contentHeight&&region.y+region.height==sourceHeight);
                        if (visible<(sourceBorder?.42:.65)) continue;
                        // Weak, wide proposals often describe furniture, carried objects or
                        // monitor content. Seated/partly occluded people remain available when
                        // the model has clear confidence; no motion score can bypass this test.
                        if (!plausiblePersonShape(boxWidth, boxHeight, score, sourceBorder)) continue;
                        best=Math.max(best,score);
                        // Retain only valid low-score proposals internally to guide the
                        // uncertain monochrome detail search; result() still enforces confidence.
                        if (score>=.08f) {
                            boolean cropBorder=(left<2&&region.x>0)||(right>contentWidth-2&&region.x+region.width<sourceWidth)
                                    ||(top<2&&region.y>0)||(bottom>contentHeight-2&&region.y+region.height<sourceHeight);
                            boolean strong=!cropBorder && visible>=.70 && personClass>otherClass*1.5f
                                    && score>=(monochrome?.78f:.72f);
                            boxes.add(new PersonBox((region.x+left*region.width/contentWidth)/sourceWidth,
                                    (region.y+top*region.height/contentHeight)/sourceHeight,
                                    (right-left)*region.width/contentWidth/sourceWidth,
                                    (bottom-top)*region.height/contentHeight/sourceHeight,score,strong));
                        }
                    }
                }
            }
            return new Inference(best,boxes);
        }
    }

    static boolean personClassQualifies(float personClass, float otherClass) {
        return Float.isFinite(personClass) && Float.isFinite(otherClass) && personClass >= 0 && personClass <= 1
                && otherClass >= 0 && otherClass <= 1 && personClass > otherClass * 1.30f
                && personClass - otherClass > .08f;
    }

    static boolean plausiblePersonShape(double width, double height, float score, boolean sourceBorder) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || !Float.isFinite(score)
                || width <= 0 || height <= 0) return false;
        double aspect = height / width;
        return aspect >= .90 || score >= (sourceBorder ? .64f : .58f) && aspect >= .45;
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws Exception {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }

    private static void extractWindowsNatives(Path library, Path target) throws IOException {
        Files.createDirectories(target);
        if (Files.isSymbolicLink(target)) throw new IOException("模型运行库目录不能是符号链接");
        try (JarFile jar = new JarFile(library.toFile())) {
            for (String name : new String[] {"onnxruntime.dll", "onnxruntime4j_jni.dll"}) {
                JarEntry entry = jar.getJarEntry("ai/onnxruntime/native/win-x64/" + name);
                if (entry == null) throw new IOException("运行库缺少 Windows x64 文件");
                Path destination = target.resolve(name);
                if (Files.isSymbolicLink(destination)) throw new IOException("运行库文件不能是符号链接");
                if (Files.isRegularFile(destination) && Files.size(destination) == entry.getSize()) continue;
                Path temporary = target.resolve(name + ".part");
                if (Files.isSymbolicLink(temporary)) throw new IOException("运行库临时文件不能是符号链接");
                try (InputStream source = jar.getInputStream(entry)) {
                    Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { ((AutoCloseable) session).close(); } catch (Exception ignored) {}
        try { inputTensor.close(); } catch (Exception ignored) {}
        // The shared OrtEnvironment lives until application exit; closing one detector must not invalidate it.
    }
}
