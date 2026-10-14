package cn.homewatch;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded shared MP4 transcodes; private video bytes remain in memory and disappear on stop. */
final class RemoteMedia implements AutoCloseable {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private final Path ffmpeg;
    private final RemoteAccessService.Backend backend;
    private final Map<Key, Job> jobs = new HashMap<>();
    private final Semaphore slots = new Semaphore(2, true);
    private final ConcurrentHashMap<Path, AtomicInteger> inUse = new ConcurrentHashMap<>();
    private boolean closed;
    RemoteMedia(Path assets, RemoteAccessService.Backend backend) { ffmpeg = assets.resolve("tools/ffmpeg.exe"); this.backend = backend; }
    boolean isRecordingInUse(Path path) { return inUse.containsKey(path.toAbsolutePath().normalize()); }
    synchronized Reader open(RemoteAccessService.RemoteRecording recording, double offset, double duration) throws IOException {
        if (closed) throw new IOException("播放服务已停止。");
        cleanup();
        Path source=recording.path().toAbsolutePath().normalize();
        // An open recording can acquire new clusters between identical seeks. Never reuse its older tail.
        Key key = new Key(source, Math.round(offset * 1000), Math.round(duration * 1000), recording.active() ? Files.size(source) : 0);
        Job job = jobs.get(key);
        if (job == null) {
            // Only two cached jobs are retained; idle jobs never prevent a different seek.
            Iterator<Job> it = jobs.values().iterator();
            while (it.hasNext()) { Job idle = it.next(); if (idle.readers == 0) { idle.stop(); it.remove(); } }
            if (jobs.size() >= 2 || !slots.tryAcquire()) throw new IOException("同时回放已达到上限，请关闭一个回放画面再试。");
            job = new Job(key, recording); jobs.put(key, job);
            try { job.start(); } catch (IOException failure) { jobs.remove(key); slots.release(); throw failure; }
        }
        job.readers++; job.lastUsed = System.nanoTime();
        return new Reader(job);
    }
    private void cleanup() { long now = System.nanoTime(); Iterator<Job> it = jobs.values().iterator(); while (it.hasNext()) { Job j = it.next(); if (j.readers == 0 && now-j.lastUsed > TimeUnit.SECONDS.toNanos(60)) { j.stop(); it.remove(); } } }
    @Override public synchronized void close() { closed = true; for (Job j : jobs.values()) { j.stop(); j.awaitProducer(); } jobs.clear(); }
    final class Reader extends InputStream {
        final Job job; int chunk; int position; boolean released;
        Reader(Job job) { this.job = job; }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one,0,1) < 0 ? -1 : one[0]&255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            synchronized (job) {
                while (chunk >= job.chunks.size() && !job.done && !released) {
                    try { job.wait(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("播放已取消。", interrupted); }
                    if (System.nanoTime()-job.started > TimeUnit.MINUTES.toNanos(8)) { job.stop(); throw new IOException("播放超时，请重新选择时间。"); }
                }
                if (released) throw new IOException("播放已关闭。");
                if (chunk >= job.chunks.size()) { if (job.failure != null) throw new IOException("录像转码失败，请重新选择时间。"); return -1; }
                byte[] data = job.chunks.get(chunk); int count = Math.min(len,data.length-position);
                System.arraycopy(data,position,b,off,count); position += count; if(position == data.length) { chunk++; position=0; } return count;
            }
        }
        @Override public void close() { synchronized (RemoteMedia.this) { if(released)return; released=true; job.readers--; job.lastUsed=System.nanoTime(); synchronized(job){job.notifyAll();} if(job.readers==0&&!job.done) { job.stop(); jobs.remove(job.key); } } }
    }
    private record Key(Path path, long offsetMs, long durationMs, long receivedBytes) { }
    private final class Job {
        final Key key; final RemoteAccessService.RemoteRecording recording; final List<byte[]> chunks = new ArrayList<>();
        final long started = System.nanoTime(); long lastUsed=started; long bytes; volatile boolean done; volatile String failure; int readers; Process process; Thread producer; AutoCloseable lease;
        Job(Key key, RemoteAccessService.RemoteRecording recording) { this.key=key; this.recording=recording; }
        void start() throws IOException {
            if (!Files.isRegularFile(ffmpeg) || !Files.isRegularFile(key.path)) throw new IOException("录像或播放组件暂时不可用。");
            try { lease=Objects.requireNonNull(backend.acquireRecording(key.path)); } catch(Exception unavailable) { throw new IOException("录像正在清理，请刷新后再试。"); }
            inUse.computeIfAbsent(key.path,p->new AtomicInteger()).incrementAndGet();
            List<String> command=List.of(ffmpeg.toString(),"-hide_banner","-loglevel","error","-nostdin","-ss",Double.toString(key.offsetMs/1000.0),"-i",key.path.toString(),"-t",Double.toString(key.durationMs/1000.0),"-map","0:v:0","-map","0:a:0?","-vf","scale=w='min(1280,iw)':h=-2","-c:v","libx264","-preset","veryfast","-crf","23","-maxrate","2500k","-bufsize","5000k","-pix_fmt","yuv420p","-r","20","-g","40","-c:a","aac","-b:a","96k","-movflags","+frag_keyframe+empty_moov+default_base_moof","-f","mp4","pipe:1");
            try { process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
            catch(IOException failure) { releaseLease(); throw new IOException("播放组件未能启动。"); }
            producer = new Thread(() -> {
                try(InputStream input=process.getInputStream()) { byte[] buffer=new byte[64*1024]; int n; while((n=input.read(buffer))>=0) { if(n==0)continue; synchronized(this) { if(done)break; bytes+=n; if(bytes>MAX_BYTES)throw new IOException("播放窗口过大。"); chunks.add(Arrays.copyOf(buffer,n)); notifyAll(); } } if(process.waitFor()!=0 && !done)failure="transcode"; }
                catch(Exception failureIgnored) { if(!done)failure="transcode"; }
                finally { stopProcess(); releaseLease(); synchronized(this){done=true;notifyAll();} slots.release(); }
            },"remote-playback"); producer.setDaemon(true); producer.start();
        }
        void stop() { synchronized(this){done=true;notifyAll();} stopProcess(); }
        void stopProcess() { Process p=process; if(p!=null && p.isAlive()) { p.destroy(); try { if(!p.waitFor(400,TimeUnit.MILLISECONDS)){p.destroyForcibly();p.waitFor(800,TimeUnit.MILLISECONDS);} } catch(InterruptedException interrupted){p.destroyForcibly();Thread.currentThread().interrupt();} } }
        void awaitProducer() { Thread thread=producer;if(thread!=null&&thread!=Thread.currentThread())try{thread.join(1500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();} }
        synchronized void releaseLease() { if(lease==null)return; try{lease.close();}catch(Exception ignored){} lease=null; inUse.computeIfPresent(key.path,(p,count)->count.decrementAndGet()<=0?null:count); }
    }
}
