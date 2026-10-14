package cn.homewatch.recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.VideoView;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mobile device home and camera detail. Playback authorization is enforced by the server. */
public final class MainActivity extends Activity {
    private AppAppearance appearance;
    private AppAppearance.Palette palette=new AppAppearance.Palette(false);
    private int ACCENT=palette.accent, RED=palette.error, DARK=palette.text, MUTED=palette.muted, GREEN=palette.green, BACKGROUND=palette.background, SURFACE=palette.surface;
    private FrameLayout contentFrame;
    private TextView appearanceSummary;
    private static final int HOME=0, DETAIL=1, MY=2;
    private int page=HOME;
    private long homePreviewRevision,lastStatusPoll;
    private LinearLayout homeCards,detailBody,livePanel;
    private FrameLayout detailFrame;
    private ScrollView detailScroll;
    private Button detailTitle;
    private TextView homeSubtitle,profileDeviceCount,profileStorage,profileRetention,cameraStateText;
    private RecorderModels.Server serverInfo;
    private android.app.Dialog activeSheet;
    private final Map<String,ImageView> homeImages=new HashMap<>();
    private final java.util.LinkedHashMap<String,android.graphics.Bitmap> thumbnailCache=new java.util.LinkedHashMap<>(8,.75f,true);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(4);
    private final ExecutorService posters = Executors.newFixedThreadPool(2);
    private final Map<String, RecorderApi.RequestControl> homeRequests = new HashMap<>();
    private final Map<String, Long> thumbnailTimes = new HashMap<>();
    private boolean camerasVerified, serverBusy;
    private long lastServerPoll;
    private volatile long lastDeviceCacheWrite;
    private final List<LiveStream> streams = new ArrayList<>();
    private final Map<String, TextView> liveNames = new HashMap<>(), liveRecording = new HashMap<>();
    private final PlaybackPlanner planner = new PlaybackPlanner();
    private SessionStore store;
    private RecorderApi api;
    private RecorderModels.Session session;
    private List<RecorderModels.Camera> cameras = new ArrayList<>();
    private ZoneId zone = ZoneId.systemDefault();
    private long clockOffset, searchGeneration;
    private volatile long generation;
    private boolean visible, replay, playing, fullScreen, statusBusy, archiveBusy, updatingCameras;
    private boolean rememberSession, followsToday, waitingAtEnd, customRange;
    private float playbackSpeed = 1f;
    private MediaPlayer mediaPlayer;
    private Instant pendingSeek;
    private boolean pendingPlay, pendingBackward;
    private int layoutBeforeFullscreen = 1, orientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private Instant waitingCursor;
    private EditText loginAddress, loginFingerprint;
    private TextView loginError;
    private static final int IMPORT_CONNECTION = 120;
    private int layout = 1;
    private String cameraId = "";
    private Instant rangeFrom, rangeTo, cursor = Instant.now();
    private PlaybackPlanner.Window window;
    private RecorderPlayerView video;
    private PlaybackPlanner.Window prefetchedWindow;
    private boolean prefetchBusy;
    private long playbackRevision;
    private long lastPrefetchAttempt;
    private boolean playbackBuffering;
    private TextView clock, status, archiveSummary, playbackTime, mediaNotice;
    private Button liveButton, replayButton, playButton, rangeButton, layoutButton, speedButton, dayButton;
    private Spinner cameraSelect;
    private FrameLayout videoArea;
    private LinearLayout header, toolbar, cameraBar, playbackPanel, root, searchRow, rangeRow, playbackControls, timelineLegend;
    private LinearLayout navigationRow, playbackTimeRow;
    private String statusSummary = "", activeNotice = "";
    private boolean connectionErrorShown;
    private TimelineView timeline;
    private long lastArchiveRefresh;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (api != null && visible) {
                if (clock != null) clock.setText(format(Instant.ofEpochMilli(System.currentTimeMillis() + clockOffset), "MM-dd HH:mm:ss"));
                if (replay && video != null && window != null) {
                    if (video.isPlaying()) {
                        cursor = planner.clamp(window.start.plusMillis(video.getCurrentPosition()));
                        timeline.time(cursor); showPlaybackTime();
                        prefetchPlayback();
                    }
                }
                if (replay && !archiveBusy && System.currentTimeMillis() - lastArchiveRefresh > (followsToday || waitingAtEnd || window != null && window.recording.active ? 2000 : 15000)) queryRecordings(false);
            }
            main.postDelayed(this, 250);
        }
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() { long now=System.currentTimeMillis();if(api!=null&&visible&&now-lastStatusPoll>=(page==DETAIL?2000:10000))refreshStatus(false);main.postDelayed(this,2000); }
    };
    private final Runnable refreshSystemBars = () -> {
        if(palette!=null&&!isDestroyed())styleSystemBars(getWindow(),BACKGROUND,fullScreen);
    };
    @Override public void onCreate(Bundle saved) {
        initializeAppearance();
        super.onCreate(saved); store = new SessionStore(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        session = store.load();
        if (session == null) showLogin(store.needsHttpsMigration() ? "连接已升级为 HTTPS，请核对电脑连接指纹并登录一次" : "", false);
        else { try { connectSession(session, true); cameras=store.loadCameras(session);showConsole(); refreshStatus(true); } catch (Exception e) { showLogin(userMessage(e), false); } }
        main.post(tick); main.postDelayed(poll, 2000);
    }
    @Override protected void onResume() {
        super.onResume(); visible = true;
        if (api != null) {
            refreshStatus(false);
            if(page==HOME)requestHomePosters(false);else if(page==DETAIL&&!replay)renderLive();
            else if (page==DETAIL&&video == null) { createVideo(); if (!planner.recordings().isEmpty()) seek(cursor, false); }
        }
    }
    @Override protected void onPause() {
        visible = false; stopLive();stopHomePosters();
        if (video != null && window != null) cursor = planner.clamp(window.start.plusMillis(video.getCurrentPosition()));
        stopPlayback();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onPause();
    }
    @Override protected void onDestroy() { generation++; if (api != null) api.close(); stopLive(); stopHomePosters();stopPlayback(); SecureTransport.resetMedia(); main.removeCallbacksAndMessages(null); network.shutdownNow();posters.shutdownNow(); super.onDestroy(); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); refreshAppearanceForConfiguration();applyResponsiveLayout(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus&&palette!=null){styleSystemBars(getWindow(),BACKGROUND,fullScreen);scheduleSystemBarsRefresh();}
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT_CONNECTION || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); long current = generation;
        network.execute(() -> {
            try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("无法打开连接文件");
                org.json.JSONObject value = new org.json.JSONObject(new String(RecorderApi.read(input, 8192), java.nio.charset.StandardCharsets.UTF_8));
                if (value.getInt("version") != 1 || !value.getString("apiUrl").startsWith("https://")) throw new IllegalArgumentException("连接文件格式不正确");
                String base = RecorderApi.normalizeBase(value.getString("apiUrl")), pin = SecureTransport.normalizeFingerprint(value.getString("publicKeySha256"));
                if (pin.isEmpty()) throw new IllegalArgumentException("连接文件缺少电脑指纹");
                main.post(() -> { if (current != generation || loginAddress == null) return; loginAddress.setText(base); loginFingerprint.setText(pin); loginError.setText("连接已导入，首次登录前请与电脑核对"); });
            } catch (Exception e) { main.post(() -> { if (current == generation && loginError != null) loginError.setText("连接文件无效，请从电脑录像机重新导出"); }); }
        });
    }
    @Override public void onBackPressed() {
        if (fullScreen) { setFullScreen(false); return; }
        if(page==DETAIL||page==MY){showHome();return;}
        super.onBackPressed();
    }

    private void showLogin(String message) {
        showLogin(message, true);
    }
    private void showLogin(String message, boolean invalidateSession) {
        if(activeSheet!=null)activeSheet.dismiss();
        if (api != null) api.close(); SecureTransport.resetMedia();
        generation++; searchGeneration++; stopLive(); stopHomePosters();stopPlayback(); api = null; session = null; cameras=new ArrayList<>(); cameraId = "";
        statusBusy=false;serverBusy=false;camerasVerified=false;lastStatusPoll=0;lastServerPoll=0;lastDeviceCacheWrite=0;archiveBusy=false;replay=false;fullScreen=false;pendingSeek=null;page=HOME;serverInfo=null;thumbnailCache.clear();thumbnailTimes.clear();homeImages.clear();clock=null;header=null;
        if (invalidateSession) { store.clearToken(); SessionRenewalJob.cancel(this); }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); LinearLayout content = column(); content.setPadding(dp(24), dp(28), dp(24), dp(24));
        ImageView icon = new ImageView(this); icon.setImageResource(getResources().getIdentifier("ic_recorder", "drawable", getPackageName()));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(64), dp(64)); iconParams.gravity = Gravity.CENTER_HORIZONTAL; iconParams.bottomMargin=dp(8);
        content.addView(icon, iconParams);
        TextView title = text("录像机", 26, DARK); title.setTypeface(null, Typeface.BOLD); title.setGravity(Gravity.CENTER); content.addView(title, match(dp(44)));
        TextView intro = text("登录后查看分配给你的摄像头", 14, MUTED); intro.setGravity(Gravity.CENTER); content.addView(intro, match(dp(32)));
        EditText address = field("https://电脑IP:9079", false);
        String savedAddress = store.baseUrl();
        try { if (!savedAddress.isEmpty()) savedAddress = RecorderApi.normalizeBase(savedAddress); } catch (IllegalArgumentException ignored) { }
        address.setText(savedAddress); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText username = field("用户名", false); username.setText(store.username());
        EditText password = field("密码", true);
        LinearLayout addressLabel = row(); addressLabel.setGravity(Gravity.CENTER_VERTICAL); addressLabel.addView(label("录像机地址"), new LinearLayout.LayoutParams(0, dp(38), 1));
        Button importConnection = button("导入连接", false); addressLabel.addView(importConnection, new LinearLayout.LayoutParams(dp(92), dp(40))); content.addView(addressLabel); content.addView(address, match(dp(48)));
        importConnection.setOnClickListener(v -> { Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT); picker.addCategory(Intent.CATEGORY_OPENABLE); picker.setType("*/*");
            picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/json", "text/plain", "application/octet-stream"}); startActivityForResult(picker, IMPORT_CONNECTION); });
        TextView addressHint = text("使用 HTTPS 加密连接，默认端口 9079", 12, MUTED); content.addView(addressHint, match(dp(28)));
        EditText fingerprint = field("粘贴电脑显示的 SHA-256 公钥指纹", false); fingerprint.setText(store.fingerprint());
        fingerprint.setSingleLine(false); fingerprint.setTextSize(12); fingerprint.setTypeface(Typeface.MONOSPACE); fingerprint.setMaxLines(2);
        fingerprint.setPadding(dp(10), dp(8), dp(10), dp(8)); fingerprint.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        fingerprint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout fingerprintLabel = row(); fingerprintLabel.setGravity(Gravity.CENTER_VERTICAL); fingerprintLabel.addView(label("连接指纹"), new LinearLayout.LayoutParams(0, dp(38), 1));
        Button paste = button("粘贴", false); fingerprintLabel.addView(paste, new LinearLayout.LayoutParams(dp(62), dp(40))); content.addView(fingerprintLabel); content.addView(fingerprint, match(dp(52)));
        TextView fingerprintHint = text("电脑「远程访问」中复制；公信 HTTPS 证书可留空", 11, MUTED); content.addView(fingerprintHint, match(dp(28)));
        content.addView(label("用户名")); content.addView(username, match(dp(48)));
        content.addView(label("密码")); LinearLayout passwordRow = row();
        passwordRow.addView(password, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button showPassword = button("显示", false); showPassword.setContentDescription("显示或隐藏密码");
        showPassword.setOnClickListener(v -> { boolean hidden = password.getTransformationMethod() != null;
            password.setTransformationMethod(hidden ? null : android.text.method.PasswordTransformationMethod.getInstance());
            showPassword.setText(hidden ? "隐藏" : "显示"); password.setSelection(password.getText().length()); });
        passwordRow.addView(showPassword, new LinearLayout.LayoutParams(dp(64), dp(48))); content.addView(passwordRow);
        CheckBox remember = new CheckBox(this); remember.setText("保持登录，自动续期"); remember.setChecked(true); remember.setTextSize(14);styleCheckBox(remember);content.addView(remember,match(dp(48)));
        TextView error = text(message, 13, RED); content.addView(error, match(ViewGroup.LayoutParams.WRAP_CONTENT));
        loginAddress = address; loginFingerprint = fingerprint; loginError = error;
        paste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip().getItemCount() == 0) { error.setText("请先复制电脑上显示的连接指纹"); return; }
            CharSequence value = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
            try { fingerprint.setText(SecureTransport.normalizeFingerprint(value == null ? "" : value.toString())); error.setText(""); }
            catch (IllegalArgumentException e) { error.setText(e.getMessage()); }
        });
        Button login = button("登录", true); LinearLayout.LayoutParams loginParams=match(dp(54));loginParams.topMargin=dp(8);content.addView(login,loginParams);
        TextView hint = text("账号由电脑上的录像机软件创建。\n手机与录像机电脑需要能够互相连接。", 12, MUTED); hint.setGravity(Gravity.CENTER); hint.setPadding(0, dp(20), 0, 0); content.addView(hint);
        Button about = button("关于录像机", false); about.setBackgroundColor(Color.TRANSPARENT); about.setTextColor(MUTED);
        LinearLayout.LayoutParams aboutParams = match(dp(48)); aboutParams.topMargin = dp(12); content.addView(about, aboutParams);
        about.setOnClickListener(v -> showAbout());
        scroll.addView(content); setRoot(scroll);
        login.setOnClickListener(v -> {
            String base, pin, name = username.getText().toString().trim(), secret = password.getText().toString();
            try { base = RecorderApi.normalizeBase(address.getText().toString()); pin = SecureTransport.normalizeFingerprint(fingerprint.getText().toString()); }
            catch (IllegalArgumentException e) { error.setText(e.getMessage()); return; }
            if (address.getText().toString().trim().toLowerCase(Locale.ROOT).startsWith("http://")) { address.setText(base); addressHint.setText("HTTP 地址已升级为 HTTPS，连接不会降级"); }
            if (name.isEmpty() || secret.isEmpty()) { error.setText("请输入用户名和密码"); return; }
            if (name.length() > 64 || secret.length() > 256) { error.setText("用户名或密码过长"); return; }
            long current = generation, loginRevision = store.revision(); boolean retain = remember.isChecked();
            Runnable performLogin = () -> { login.setEnabled(false); login.setText("正在登录…"); error.setText(""); network.execute(() -> {
                try {
                    RecorderModels.Session logged = RecorderApi.login(base, name, secret, pin);
                    if (current != generation || isFinishing()) return;
                    main.post(() -> { if (current != generation || isFinishing()) return;
                        try { if(!store.save(logged, retain, loginRevision)){error.setText("登录状态已变化，请重试");login.setEnabled(true);login.setText("登录");return;}connectSession(logged, retain); password.setText(""); showConsole(); refreshStatus(true); }
                        catch (Exception e) { error.setText(userMessage(e)); login.setEnabled(true); login.setText("登录"); }
                    });
                } catch (Exception e) { main.post(() -> { if (current != generation) return; error.setText(userMessage(e)); login.setEnabled(true); login.setText("登录"); }); }
            }); };
            if (base.equals(store.baseUrl()) && pin.equals(store.fingerprint())) performLogin.run();
            else new AlertDialog.Builder(dialogContext()).setTitle("确认录像机连接")
                    .setMessage(base + "\n\n" + (pin.isEmpty() ? "将由手机系统验证 HTTPS 证书。家庭电脑自动证书请先复制电脑显示的连接指纹。" : "请核对这与你电脑录像机显示的连接指纹完全一致：\n\n" + pin + "\n\n一致后再继续，今后自动验证。"))
                    .setNegativeButton("返回核对", null).setPositiveButton("确认并登录", (d, w) -> performLogin.run()).show();
        });
    }
    private void connectSession(RecorderModels.Session value, boolean remember) throws Exception {
        session = value; rememberSession = remember; long revision = store.revision();
        api = new RecorderApi(value, renewed -> { if (remember) store.update(renewed, revision); });
        if (remember) SessionRenewalJob.schedule(this); else SessionRenewalJob.cancel(this);
    }
    private void showConsole() {
        loginAddress=null;loginFingerprint=null;loginError=null;connectionErrorShown=false;activeNotice="";
        createPlaybackPanel();showHome();
    }
    private void leavePage() {
        if(activeSheet!=null)activeSheet.dismiss();
        if(fullScreen)setFullScreen(false);
        stopLive();stopHomePosters();stopPlayback();searchGeneration++;archiveBusy=false;pendingSeek=null;waitingAtEnd=false;
        replay=false;clock=null;mediaNotice=null;activeNotice="";homePreviewRevision++;homeImages.clear();liveNames.clear();liveRecording.clear();
    }
    private void showHome() {
        leavePage();page=HOME;root=column();root.setBackgroundColor(BACKGROUND);header=null;
        LinearLayout heading=row();heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(dp(20),dp(16),dp(12),dp(2));
        TextView title=text("我的设备",26,DARK);title.setTypeface(null,Typeface.BOLD);heading.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        Button multi=iconButton(MobileIcons.GRID,"多画面",DARK);multi.setOnClickListener(v->{if(cameras.isEmpty())notice("暂时没有可查看的设备");else openCamera(cameras.get(0).id,4);});heading.addView(multi,new LinearLayout.LayoutParams(dp(48),dp(48)));
        Button refresh=iconButton(MobileIcons.REFRESH,"刷新设备",DARK);refresh.setOnClickListener(v->refreshHome());heading.addView(refresh,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(heading);
        homeSubtitle=text("正在同步设备…",13,MUTED);homeSubtitle.setPadding(dp(20),0,dp(20),dp(12));root.addView(homeSubtitle,match(dp(36)));
        status=text("",13,MUTED);status.setPadding(dp(20),dp(6),dp(20),dp(8));status.setVisibility(View.GONE);status.setOnClickListener(v->refreshHome());root.addView(status,match(-2));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setPadding(dp(16),0,dp(16),dp(12));
        homeCards=column();scroll.addView(homeCards);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        final float[] touch={0};final boolean[] fromTop={false};
        scroll.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN){touch[0]=e.getY();fromTop[0]=scroll.getScrollY()==0;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP&&fromTop[0]&&e.getY()-touch[0]>dp(96))refreshHome();return false;});
        addBottomNavigation(false);setRoot(root);renderHomeCards();
    }
    private void refreshHome() {
        notice("正在刷新设备…");refreshStatus(true);requestHomePosters(true);
    }
    private void renderHomeCards() {
        if(page!=HOME||homeCards==null)return;stopLive();stopHomePosters();homePreviewRevision++;
        homeCards.removeAllViews();homeImages.clear();liveNames.clear();liveRecording.clear();
        thumbnailCache.keySet().retainAll(cameraIds(cameras));
        thumbnailTimes.keySet().retainAll(cameraIds(cameras));
        int online=0;for(RecorderModels.Camera c:cameras)if(c.online)online++;
        boolean synchronizing=!camerasVerified;
        homeSubtitle.setText(synchronizing?(cameras.isEmpty()?"正在同步设备…":cameras.size()+" 台设备 · 状态更新中"):cameras.size()+" 台设备 · "+online+" 台在线");
        if(cameras.isEmpty()) {
            LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(64),dp(20),dp(24));
            ImageView icon=new ImageView(this);icon.setImageDrawable(new MobileIcons(this,MobileIcons.CAMERA,MUTED,64));empty.addView(icon,new LinearLayout.LayoutParams(dp(72),dp(72)));
            TextView title=text(synchronizing?"正在加载设备":"暂无可查看的设备",18,DARK);title.setGravity(Gravity.CENTER);empty.addView(title,match(dp(48)));
            TextView hint=text(synchronizing?"连接成功后，这里会显示你的摄像头":"请在录像机电脑上为这个账号分配摄像头",13,MUTED);hint.setGravity(Gravity.CENTER);hint.setMaxLines(2);empty.addView(hint,match(dp(64)));homeCards.addView(empty);return;
        }
        int coverHeight=dp(Math.min(230,Math.max(140,(getResources().getConfiguration().screenWidthDp-32)*9f/16f)));
        for(RecorderModels.Camera c:cameras) {
            LinearLayout card=column();card.setBackground(shape(SURFACE));card.setElevation(dp(1));
            LinearLayout.LayoutParams cp=match(-2);cp.bottomMargin=dp(16);homeCards.addView(card,cp);card.setContentDescription("打开摄像头："+c.name);card.setClickable(true);card.setOnClickListener(v->openCamera(c.id,1));
            FrameLayout cover=new FrameLayout(this);cover.setBackgroundColor(palette.placeholder);card.addView(cover,match(coverHeight));
            ImageView icon=new ImageView(this);icon.setImageDrawable(new MobileIcons(this,MobileIcons.CAMERA,MUTED,44));FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(52),dp(52),Gravity.CENTER);cover.addView(icon,ip);
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setContentDescription(c.name+" 最近画面");cover.addView(image,new FrameLayout.LayoutParams(-1,-1));homeImages.put(c.id,image);
            android.graphics.Bitmap cached=thumbnailCache.get(c.id);if(cached!=null)image.setImageBitmap(cached);
            TextView snapshot=text("最近画面",11,Color.WHITE);snapshot.setPadding(dp(8),dp(3),dp(8),dp(3));snapshot.setBackground(shape(0x99222e43));FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);sp.setMargins(dp(12),dp(12),0,0);cover.addView(snapshot,sp);
            ImageView play=new ImageView(this);play.setImageDrawable(new MobileIcons(this,MobileIcons.PLAY,Color.WHITE,22));play.setPadding(dp(10),dp(10),dp(10),dp(10));play.setBackground(shape(0x88222e43));FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.BOTTOM|Gravity.END);pp.setMargins(0,0,dp(12),dp(12));cover.addView(play,pp);
            LinearLayout meta=row();meta.setPadding(dp(16),dp(8),dp(12),dp(8));meta.setGravity(Gravity.CENTER_VERTICAL);card.addView(meta,match(dp(74)));
            LinearLayout labels=column();TextView name=text(c.name,17,DARK);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setTypeface(null,Typeface.BOLD);labels.addView(name,match(dp(30)));liveNames.put(c.id,name);
            TextView state=text(cameraState(c),12,c.online?GREEN:MUTED);labels.addView(state,match(dp(24)));liveRecording.put(c.id,state);meta.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            ImageView arrow=new ImageView(this);arrow.setImageDrawable(new MobileIcons(this,MobileIcons.CHEVRON,MUTED,20));meta.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(24)));
        }
        requestHomePosters(false);
    }
    private void requestHomePosters(boolean force) {
        if(page!=HOME||!visible||api==null||!camerasVerified)return;
        long current=generation,revision=homePreviewRevision,now=System.currentTimeMillis();RecorderApi requestApi=api;
        for(int index=0;index<Math.min(4,cameras.size());index++) {
            RecorderModels.Camera camera=cameras.get(index);
            if(!camera.online||homeRequests.containsKey(camera.id)||(!force&&now-thumbnailTimes.getOrDefault(camera.id,0L)<60_000))continue;
            RecorderApi.RequestControl control=new RecorderApi.RequestControl();homeRequests.put(camera.id,control);
            posters.execute(()->{
                android.graphics.Bitmap bitmap=null;Exception failure=null;
                try{if(!control.isCancelled()) {byte[] bytes=requestApi.poster(camera.id,control);bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);}}
                catch(Exception problem){failure=problem;}
                final android.graphics.Bitmap frame=bitmap;final Exception problem=failure;
                main.post(()->{
                    if(homeRequests.get(camera.id)==control)homeRequests.remove(camera.id);
                    if(control.isCancelled()||current!=generation||requestApi!=api||revision!=homePreviewRevision||page!=HOME||!visible)return;
                    if(problem instanceof RecorderApi.Failure&&((RecorderApi.Failure)problem).status==401){handle(problem);return;}
                    if(frame!=null){cacheThumbnail(camera.id,frame);ImageView target=homeImages.get(camera.id);if(target!=null&&target.isAttachedToWindow())target.setImageBitmap(thumbnailCache.get(camera.id));}
                });
            });
        }
    }
    private void stopHomePosters() {
        homePreviewRevision++;for(RecorderApi.RequestControl request:homeRequests.values())request.cancel();homeRequests.clear();
    }
    private void cacheThumbnail(String id,android.graphics.Bitmap bitmap) {
        if(bitmap.getWidth()==0||bitmap.getHeight()==0)return;
        int width=Math.min(480,bitmap.getWidth()),height=Math.max(1,Math.round(bitmap.getHeight()*width/(float)bitmap.getWidth()));
        thumbnailCache.put(id,android.graphics.Bitmap.createScaledBitmap(bitmap,width,height,true));
        thumbnailTimes.put(id,System.currentTimeMillis());
        while(thumbnailCache.size()>4){String old=thumbnailCache.keySet().iterator().next();thumbnailCache.remove(old);thumbnailTimes.remove(old);}
    }
    private String cameraState(RecorderModels.Camera camera) {
        if(!camerasVerified)return "○ 状态更新中";
        if(camera.recording)return camera.online?"● 在线 · 正在录制":"● 正在录制 · 实时画面暂不可用";
        return camera.online?"● 在线":"○ 暂时离线";
    }
    private void addBottomNavigation(boolean mine) {
        View divider=new View(this);divider.setBackgroundColor(palette.divider);root.addView(divider,match(dp(1)));
        LinearLayout nav=row();nav.setBackgroundColor(SURFACE);nav.setPadding(dp(28),dp(6),dp(28),dp(6));
        for(int i=0;i<2;i++){boolean active=(i==1)==mine;Button tab=button(i==0?"设备":"我的",false);tab.setTextSize(12);tab.setTextColor(active?ACCENT:MUTED);
            tab.setCompoundDrawables(null,new MobileIcons(this,i==0?MobileIcons.HOME:MobileIcons.USER,active?ACCENT:MUTED,22),null,null);tab.setCompoundDrawablePadding(dp(4));tab.setBackgroundColor(Color.TRANSPARENT);
            tab.setContentDescription(i==0?"设备首页":"我的账号");final int destination=i;tab.setOnClickListener(v->{if(destination==0&&page!=HOME)showHome();if(destination==1&&page!=MY)showProfile();});nav.addView(tab,new LinearLayout.LayoutParams(0,dp(56),1));}
        root.addView(nav,match(dp(68)));
    }
    private void showProfile() {
        leavePage();page=MY;root=column();root.setBackgroundColor(BACKGROUND);header=null;homeCards=null;
        TextView title=text("我的",26,DARK);title.setTypeface(null,Typeface.BOLD);title.setPadding(dp(24),dp(12),0,0);root.addView(title,match(dp(72)));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout body=column();body.setPadding(dp(20),dp(4),dp(20),dp(24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout account=row();account.setGravity(Gravity.CENTER_VERTICAL);account.setPadding(dp(18),dp(20),dp(18),dp(20));account.setBackground(shape(SURFACE));
        ImageView avatar=new ImageView(this);avatar.setImageDrawable(new MobileIcons(this,MobileIcons.USER,ACCENT,30));avatar.setPadding(dp(12),dp(12),dp(12),dp(12));avatar.setBackground(shape(palette.soft));account.addView(avatar,new LinearLayout.LayoutParams(dp(56),dp(56)));
        LinearLayout labels=column();labels.setPadding(dp(16),0,0,0);TextView username=text(session.username,21,DARK);username.setTypeface(null,Typeface.BOLD);username.setSingleLine(true);username.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(username,match(dp(32)));
        profileDeviceCount=text("",13,MUTED);labels.addView(profileDeviceCount,match(dp(24)));account.addView(labels,new LinearLayout.LayoutParams(0,-2,1));body.addView(account,match(-2));
        TextView sub=text("账号与连接",14,MUTED);sub.setPadding(dp(4),dp(18),0,dp(6));body.addView(sub,match(dp(48)));
        LinearLayout settings=column();settings.setBackground(shape(SURFACE));
        addSettingRow(settings,"连接信息","已加密连接到录像机",MobileIcons.SHIELD,()->showConnectionInfo());
        addSettingRow(settings,"保持登录",rememberSession?"自动续期已开启":"未保存登录会话",MobileIcons.USER,()->showInformation("保持登录",rememberSession?"已开启自动续期。允许录像机 App 后台联网，可减少再次登录。\n\n长期离线或禁止后台超过服务端续期有效期，或管理员撤销账号时，需要重新登录。":"本次登录没有保存会话。下次登录时勾选“保持登录，自动续期”即可。"));
        body.addView(settings,match(-2));
        TextView storageTitle=text("录像机",14,MUTED);storageTitle.setPadding(dp(4),dp(18),0,dp(6));body.addView(storageTitle,match(dp(48)));
        LinearLayout storage=column();storage.setPadding(dp(18),dp(14),dp(18),dp(14));storage.setBackground(shape(SURFACE));
        profileStorage=text("正在读取录像存储…",15,DARK);storage.addView(profileStorage,match(dp(36)));profileRetention=text("存储由录像机电脑统一管理",12,MUTED);profileRetention.setMaxLines(2);storage.addView(profileRetention,match(dp(48)));
        LinearLayout timeRow=row();timeRow.addView(text("录像机时间",12,MUTED),new LinearLayout.LayoutParams(0,dp(36),1));clock=text("",12,DARK);clock.setTypeface(Typeface.MONOSPACE);timeRow.addView(clock,new LinearLayout.LayoutParams(-2,dp(36)));storage.addView(timeRow);body.addView(storage,match(-2));
        status=text("",12,MUTED);status.setPadding(dp(4),dp(8),dp(4),dp(8));status.setVisibility(View.GONE);body.addView(status,match(-2));
        LinearLayout appSettings=column();appSettings.setBackground(shape(SURFACE));
        appearanceSummary=addSettingRow(appSettings,"外观",appearance.label(),MobileIcons.APPEARANCE,()->chooseAppearance());
        addSettingRow(appSettings,"关于录像机","版本 "+appVersion(),MobileIcons.INFO,()->showAbout());
        LinearLayout.LayoutParams appParams=match(-2);appParams.topMargin=dp(18);body.addView(appSettings,appParams);
        Button logout=button("退出登录",false);logout.setTextColor(RED);logout.setBackground(shape(palette.errorSurface));logout.setOnClickListener(v->confirmLogout());LinearLayout.LayoutParams lp=match(dp(52));lp.topMargin=dp(24);body.addView(logout,lp);
        TextView version=text("录像机 · "+appVersion(),12,MUTED);version.setGravity(Gravity.CENTER);body.addView(version,match(dp(48)));
        addBottomNavigation(true);setRoot(root);updateProfile();
    }
    private TextView addSettingRow(LinearLayout parent,String name,String subtitle,int icon,Runnable action) {
        LinearLayout item=row();item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(16),dp(8),dp(16),dp(8));item.setBackground(new AppAppearance.ThemeRipple((ACCENT&0x00ffffff)|0x22000000,null,shape(SURFACE)));item.setClickable(true);item.setContentDescription(name);item.setOnClickListener(v->action.run());
        ImageView symbol=new ImageView(this);symbol.setImageDrawable(new MobileIcons(this,icon,ACCENT,22));item.addView(symbol,new LinearLayout.LayoutParams(dp(28),dp(28)));
        LinearLayout labels=column();labels.setPadding(dp(12),0,dp(8),0);labels.addView(text(name,15,DARK),match(dp(28)));TextView detail=text(subtitle,12,MUTED);detail.setSingleLine(true);labels.addView(detail,match(dp(24)));item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow=new ImageView(this);arrow.setImageDrawable(new MobileIcons(this,MobileIcons.CHEVRON,MUTED,18));item.addView(arrow,new LinearLayout.LayoutParams(dp(20),dp(20)));parent.addView(item,match(dp(76)));return detail;
    }
    private void updateProfile() {
        if(page!=MY)return;profileDeviceCount.setText(cameras.size()+" 台已授权设备");
        if(serverInfo!=null){profileStorage.setText("录像占用  "+gb(serverInfo.usedBytes)+" / "+gb(serverInfo.maxBytes)+" GB");profileRetention.setText("保留 "+serverInfo.keepDays+" 天 · 全部摄像头共用\n保存目录和空间限制在录像机电脑上设置");}
    }
    private void showConnectionInfo() {
        showInformation("连接信息","录像机地址\n"+session.baseUrl+"\n\n连接验证\n"+(session.fingerprint.isEmpty()?"系统 HTTPS 证书验证":"已核对录像机公钥指纹\n"+session.fingerprint));
    }
    private void showInformation(String title,String message) {new AlertDialog.Builder(dialogContext()).setTitle(title).setMessage(message).setPositiveButton("知道了",null).show();}
    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(),0).versionName; }
        catch(android.content.pm.PackageManager.NameNotFoundException e) { return "未知"; }
    }
    private void showAbout() {
        if(activeSheet!=null)activeSheet.dismiss();
        ScrollView scroll=new ScrollView(this);LinearLayout content=column();content.setPadding(dp(24),dp(12),dp(24),dp(12));
        TextView info=text("录像机 Android App\n版本 "+appVersion()+"\n\n作者  千夏Tesni\nQQ  2758399324\n邮箱  yachi8000@yachiyo.cn",15,DARK);
        info.setTextIsSelectable(true);info.setLineSpacing(dp(5),1f);content.addView(info,match(-2));scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle("关于录像机").setView(scroll).setPositiveButton("关闭",null).create();
        activeSheet=dialog;dialog.setOnDismissListener(d->{if(activeSheet==dialog)activeSheet=null;});dialog.show();
    }
    private void openCamera(String id,int mode) {
        leavePage();page=DETAIL;cameraId=id;layout=mode;root=column();root.setBackgroundColor(BACKGROUND);
        header=row();header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(8),0,dp(8),0);
        Button back=iconButton(MobileIcons.BACK,"返回设备首页",DARK);back.setOnClickListener(v->showHome());header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));
        detailTitle=button(mode==4?"多画面":cameraName(),false);detailTitle.setAutoSizeTextTypeUniformWithConfiguration(14,18,1,android.util.TypedValue.COMPLEX_UNIT_SP);detailTitle.setTypeface(null,Typeface.BOLD);detailTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);detailTitle.setBackgroundColor(Color.TRANSPARENT);detailTitle.setContentDescription("切换摄像头");detailTitle.setOnClickListener(v->chooseCamera());header.addView(detailTitle,new LinearLayout.LayoutParams(0,dp(56),1));
        Button options=iconButton(MobileIcons.MORE,"摄像头选项",DARK);options.setOnClickListener(v->showOptions("摄像头",new String[]{"切换摄像头",layout==4?"单画面查看":"多画面查看","刷新画面"},index->{if(index==0)chooseCamera();else if(index==1){layout=layout==4?1:4;detailTitle.setText(layout==4?"多画面":cameraName());if(replay)switchMode(false);else renderLive();}else{refreshStatus(false);if(replay)queryRecordings(false);else renderLive();}}));header.addView(options,new LinearLayout.LayoutParams(dp(48),dp(56)));root.addView(header,match(dp(56)));
        detailFrame=new FrameLayout(this);root.addView(detailFrame,new LinearLayout.LayoutParams(-1,0,1));detailBody=column();detailBody.setPadding(0,0,0,dp(12));
        videoArea=new FrameLayout(this);videoArea.setBackground(shape(0xff111a2a));LinearLayout.LayoutParams vp=match(dp(200));vp.setMargins(dp(16),dp(4),dp(16),dp(8));detailBody.addView(videoArea,vp);
        toolbar=row();toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(16),dp(4),dp(16),dp(4));liveButton=button("实时画面",true);replayButton=button("录像回放",false);
        liveButton.setOnClickListener(v->switchMode(false));replayButton.setOnClickListener(v->switchMode(true));toolbar.addView(liveButton,new LinearLayout.LayoutParams(0,dp(44),1));toolbar.addView(replayButton,new LinearLayout.LayoutParams(0,dp(44),1));detailBody.addView(toolbar,match(dp(56)));
        cameraBar=row();layoutButton=button("多画面",false);cameraBar.addView(layoutButton);cameraBar.setVisibility(View.GONE);
        livePanel=column();livePanel.setPadding(dp(20),dp(8),dp(20),dp(8));cameraStateText=text("",14,GREEN);livePanel.addView(cameraStateText,match(dp(32)));
        LinearLayout liveTime=row();liveTime.addView(text("录像机时间",12,MUTED),new LinearLayout.LayoutParams(0,dp(32),1));clock=text("",12,MUTED);clock.setTypeface(Typeface.MONOSPACE);liveTime.addView(clock,new LinearLayout.LayoutParams(-2,dp(32)));livePanel.addView(liveTime);
        TextView tip=text("双击画面或点右下角，可全屏查看",12,MUTED);livePanel.addView(tip,match(dp(36)));detailBody.addView(livePanel,match(-2));
        if(playbackPanel.getParent()!=null)((ViewGroup)playbackPanel.getParent()).removeView(playbackPanel);detailBody.addView(playbackPanel);playbackPanel.setVisibility(View.GONE);
        status=text("",12,MUTED);status.setPadding(dp(20),dp(8),dp(20),dp(8));status.setMaxLines(3);status.setVisibility(View.GONE);detailBody.addView(status,match(-2));
        detailScroll=new ScrollView(this);detailScroll.setFillViewport(true);detailScroll.addView(detailBody);detailFrame.addView(detailScroll,new FrameLayout.LayoutParams(-1,-1));setRoot(root);applyResponsiveLayout();renderLive();updateDetailState();
    }
    private void chooseCamera() {
        List<RecorderModels.Camera> choices=new ArrayList<>(cameras);String[] names=new String[choices.size()];for(int i=0;i<names.length;i++)names[i]=choices.get(i).name;
        showOptions("选择摄像头",names,index->{if(page!=DETAIL||api==null)return;String id=choices.get(index).id;if(!cameraIds(cameras).contains(id)){notice("这台摄像头的查看权限已更新");return;}if(id.equals(cameraId)&&layout==1)return;boolean restoreReplay=replay;openCamera(id,1);if(restoreReplay)switchMode(true);});
    }
    private void updateDetailState() {
        if(page!=DETAIL)return;detailTitle.setText(layout==4&&!replay?"多画面":cameraName());
        for(RecorderModels.Camera c:cameras)if(c.id.equals(cameraId)){cameraStateText.setText(cameraState(c));cameraStateText.setTextColor(c.online||c.recording?GREEN:MUTED);}
    }
    private String cameraName(){for(RecorderModels.Camera c:cameras)if(c.id.equals(cameraId))return c.name;return "摄像头";}
    private void showOptions(String title,String[] values,java.util.function.IntConsumer action) {
        LinearLayout list=column();for(int i=0;i<values.length;i++){final int index=i;Button option=button(values[i],false);option.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);option.setPadding(dp(14),0,dp(14),0);option.setTextSize(15);option.setBackgroundColor(SURFACE);list.addView(option,match(dp(54)));option.setTag(index);}
        android.app.Dialog dialog=bottomSheet(title,list);for(int i=0;i<list.getChildCount();i++)list.getChildAt(i).setOnClickListener(v->{int index=(int)v.getTag();dialog.dismiss();action.accept(index);});dialog.show();fitSheet(dialog);
    }
    private android.app.Dialog bottomSheet(String title,View content) {
        if(activeSheet!=null)activeSheet.dismiss();
        android.app.Dialog dialog=new android.app.Dialog(dialogContext());dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);LinearLayout shell=column();shell.setPadding(dp(16),dp(10),dp(16),dp(12));shell.setBackground(shape(SURFACE));
        activeSheet=dialog;dialog.setOnDismissListener(d->{if(activeSheet==dialog)activeSheet=null;});
        View handle=new View(this);handle.setBackground(shape(palette.border));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(36),dp(4));hp.gravity=Gravity.CENTER_HORIZONTAL;shell.addView(handle,hp);
        TextView heading=text(title,20,DARK);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(dp(12),dp(12),dp(12),dp(8));shell.addView(heading,match(dp(64)));
        ScrollView scroller=new ScrollView(this){@Override protected void onMeasure(int widthSpec,int heightSpec){int cap=Math.min(dp(440),Math.max(dp(88),dp(getResources().getConfiguration().screenHeightDp*.65f-120)));super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST));}};
        scroller.addView(content);scroller.setFillViewport(false);shell.addView(scroller,match(-2));
        Button cancel=button("关闭",false);cancel.setOnClickListener(v->dialog.dismiss());shell.addView(cancel,match(dp(48)));dialog.setContentView(shell);dialog.setCanceledOnTouchOutside(true);return dialog;
    }
    private void fitSheet(android.app.Dialog dialog) {
        android.view.Window window=dialog.getWindow();if(window==null)return;
        window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.BOTTOM);window.setLayout(-1,-2);window.setDimAmount(.35f);
        styleSystemBars(window,SURFACE,false);
    }
    private Button iconButton(int icon,String description,int color) {Button button=button("",false);button.setContentDescription(description);button.setCompoundDrawables(new MobileIcons(this,icon,color,23),null,null,null);button.setPadding(dp(12),0,dp(12),0);button.setBackgroundColor(Color.TRANSPARENT);return button;}
    private AppAppearance.SquareDrawable shape(int color) {return new AppAppearance.SquareDrawable(color);}
    private void createPlaybackPanel() {
        playbackPanel=column();playbackPanel.setPadding(dp(12),dp(2),dp(12),dp(4));playbackPanel.setBackground(shape(SURFACE));
        searchRow=column();LinearLayout days=row();days.setGravity(Gravity.CENTER_VERTICAL);
        dayButton=button("选择日期",false);dayButton.setCompoundDrawables(new MobileIcons(this,MobileIcons.CALENDAR,ACCENT,18),null,null,null);dayButton.setCompoundDrawablePadding(dp(6));dayButton.setOnClickListener(v->selectDay());dayButton.setContentDescription("选择录像日期");days.addView(dayButton,new LinearLayout.LayoutParams(0,dp(44),1));
        Button filter=button("时间筛选",false);filter.setContentDescription("查询录像时间区间");filter.setOnClickListener(v->selectRange());days.addView(filter,new LinearLayout.LayoutParams(dp(92),dp(44)));searchRow.addView(days);
        rangeRow=row();rangeButton=button("",false);rangeButton.setOnClickListener(v->selectRange());rangeRow.addView(rangeButton,new LinearLayout.LayoutParams(0,dp(44),1));Button today=button("今天",false);today.setOnClickListener(v->changeToToday());rangeRow.addView(today,new LinearLayout.LayoutParams(dp(58),dp(44)));searchRow.addView(rangeRow);rangeRow.setVisibility(View.GONE);playbackPanel.addView(searchRow);
        playbackTimeRow=row();playbackTime=text("",15,DARK);playbackTime.setGravity(Gravity.CENTER);playbackTime.setTypeface(Typeface.MONOSPACE);playbackTime.setSingleLine(true);playbackTime.setContentDescription("当前回放时间");playbackTimeRow.addView(playbackTime,match(dp(36)));playbackPanel.addView(playbackTimeRow);
        timeline=new TimelineView(this,planner,new TimelineView.Listener(){
            @Override public void moved(Instant value,boolean finished){cursor=value;showPlaybackTime();if(finished)navigateTime(value,playing);else if(video!=null)video.pause();}
            @Override public void cancelled(Instant original){cursor=original;showPlaybackTime();if(video!=null&&window!=null&&playing&&visible)video.start();}
        });timeline.appearance(palette);playbackPanel.addView(timeline,match(dp(72)));
        playbackControls=row();playbackControls.setGravity(Gravity.CENTER_VERTICAL);addFlexibleControl(playbackControls,"−10秒","后退十秒",()->stepPlayback(-10));
        playButton=button("播放",true);playButton.setOnClickListener(v->togglePlayback());playbackControls.addView(playButton,new LinearLayout.LayoutParams(0,dp(48),1));addFlexibleControl(playbackControls,"+10秒","前进十秒",()->stepPlayback(10));
        speedButton=button("1×",false);speedButton.setContentDescription("选择回放速度");speedButton.setOnClickListener(v->selectSpeed());playbackControls.addView(speedButton,new LinearLayout.LayoutParams(0,dp(48),1));playbackPanel.addView(playbackControls,match(dp(56)));
        navigationRow=row();Button events=button("人员事件",false);events.setCompoundDrawables(new MobileIcons(this,MobileIcons.PERSON,ACCENT,18),null,null,null);events.setCompoundDrawablePadding(dp(6));events.setOnClickListener(v->showArchiveEvents());navigationRow.addView(events,new LinearLayout.LayoutParams(0,dp(44),1));
        Button more=button("更多回放",false);more.setOnClickListener(v->showReplayTools());navigationRow.addView(more,new LinearLayout.LayoutParams(0,dp(44),1));playbackPanel.addView(navigationRow);
        timelineLegend=row();archiveSummary=text("蓝色录像 · 橙色人员",11,MUTED);archiveSummary.setSingleLine(true);archiveSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);timelineLegend.addView(archiveSummary,new LinearLayout.LayoutParams(0,dp(36),1));
        Button less=button("−",false);less.setContentDescription("缩小时间轴");less.setOnClickListener(v->timeline.span(timeline.span()*2));timelineLegend.addView(less,new LinearLayout.LayoutParams(dp(44),dp(36)));Button moreScale=button("＋",false);moreScale.setContentDescription("放大时间轴");moreScale.setOnClickListener(v->timeline.span(timeline.span()/2));timelineLegend.addView(moreScale,new LinearLayout.LayoutParams(dp(44),dp(36)));playbackPanel.addView(timelineLegend);
    }

    private void addControl(LinearLayout parent, String label, int width, Runnable action) {
        Button control = button(label, false); control.setOnClickListener(v -> action.run()); parent.addView(control, new LinearLayout.LayoutParams(dp(width), dp(44)));
    }
    private void addFlexibleControl(LinearLayout parent, String label, String description, Runnable action) {
        Button control = button(label, false); control.setContentDescription(description); control.setOnClickListener(v -> action.run());
        parent.addView(control, new LinearLayout.LayoutParams(0, dp(44), 1));
    }
    private void clearArchive() {
        stopPlayback();
        if (rangeFrom != null && rangeTo != null) planner.update(java.util.Collections.emptyList(), rangeFrom, rangeTo);
        if (timeline != null) timeline.invalidate();
        if (replay && videoArea != null) videoArea.removeAllViews();
    }
    private void switchMode(boolean value) {
        if(api==null||page!=DETAIL||replay==value)return;
        stopLive(); stopPlayback(); searchGeneration++; archiveBusy = false; replay = value; selected(liveButton, !replay); selected(replayButton, replay);
        updateDetailState();applyResponsiveLayout();
        if (replay) { defaultRange(); clearArchive(); createVideo(); latestRecording(); } else { pendingSeek = null; renderLive(); }
    }
    private void defaultRange() {
        followsToday = true; customRange = false; timeline.constrained(false);
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis() + clockOffset);
        rangeFrom = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant(); rangeTo = rangeFrom.atZone(zone).plusDays(1).toInstant(); cursor = rangeFrom;
        updateRangeLabel();
    }
    private void changeToToday() { stopPlayback(); searchGeneration++; archiveBusy = false; pendingSeek = null; defaultRange(); clearArchive(); queryRecordings(true); }
    private void updateRangeLabel() { if (rangeButton != null && rangeFrom != null) { rangeButton.setText(customRange ? format(rangeFrom, "MM-dd HH:mm") + " — " + format(rangeTo, "MM-dd HH:mm") : "查询时间区间"); dayButton.setText(format(rangeFrom,"MM月dd日")+(customRange?" · 区间":"  ▾")); rangeRow.setVisibility(customRange ? View.VISIBLE : View.GONE); applyResponsiveLayout(); } }
    private void refreshStatus(boolean first) {
        if(api==null)return;refreshServer(first);
        long now=System.currentTimeMillis();
        if(statusBusy||(!first&&now-lastStatusPoll<(page==DETAIL?2000:10000)))return;
        statusBusy=true;lastStatusPoll=now;RecorderApi requestApi=api;long current=generation,storeRevision=store.revision();boolean retain=rememberSession;
        List<String> previousIds=cameraIds(cameras),previousLabels=cameraLabels(cameras);
        network.execute(()->{try{List<RecorderModels.Camera> next=requestApi.cameras();
            main.post(()->{if(current!=generation||requestApi!=api)return;statusBusy=false;boolean firstSync=!camerasVerified;camerasVerified=true;
                List<String> oldIds=cameraIds(cameras),oldLabels=cameraLabels(cameras);String oldId=cameraId;cameras=next;boolean found=false;int online=0;for(RecorderModels.Camera c:next){if(c.id.equals(cameraId))found=true;if(c.online)online++;}
                if(page==DETAIL&&!found){showHome();notice(next.isEmpty()?"暂无可查看的设备":"这台摄像头的查看权限已更新");return;}
                if(!found)cameraId=next.isEmpty()?"":next.get(0).id;
                for(RecorderModels.Camera c:cameras){TextView name=liveNames.get(c.id);if(name!=null)name.setText(c.name);TextView rec=liveRecording.get(c.id);if(rec!=null){if(page==HOME){rec.setText(cameraState(c));rec.setTextColor(c.online||c.recording?GREEN:MUTED);}else rec.setVisibility(c.recording?View.VISIBLE:View.GONE);}}
                boolean changed=firstSync||!oldIds.equals(cameraIds(next))||!oldLabels.equals(cameraLabels(next));statusSummary="";
                if(page==HOME){homeSubtitle.setText(cameras.size()+" 台设备 · "+online+" 台在线");if(changed)renderHomeCards();else requestHomePosters(false);}
                else if(page==MY)updateProfile();else{updateDetailState();if(!oldId.equals(cameraId)||!oldIds.equals(cameraIds(next))){if(replay){stopPlayback();searchGeneration++;archiveBusy=false;pendingSeek=null;clearArchive();createVideo();queryRecordings(true);}else renderLive();}}
                if(connectionErrorShown||activeNotice.equals("正在刷新设备…")){connectionErrorShown=false;notice("");}
            });
            if(current==generation&&retain&&(System.currentTimeMillis()-lastDeviceCacheWrite>300_000||!previousIds.equals(cameraIds(next))||!previousLabels.equals(cameraLabels(next)))) {
                try{if(store.saveCameras(requestApi.currentSession(),next,storeRevision))lastDeviceCacheWrite=System.currentTimeMillis();}catch(Exception ignored){}
            }
        }catch(Exception e){main.post(()->{if(current!=generation||requestApi!=api)return;statusBusy=false;handle(e);});}});
    }
    private void refreshServer(boolean force) {
        if(api==null||serverBusy||(!force&&System.currentTimeMillis()-lastServerPoll<10000))return;
        serverBusy=true;lastServerPoll=System.currentTimeMillis();RecorderApi requestApi=api;long current=generation;
        network.execute(()->{try{RecorderModels.Server server=requestApi.server();
            main.post(()->{if(current!=generation||requestApi!=api)return;serverBusy=false;serverInfo=server;zone=server.zone;clockOffset=server.time.toEpochMilli()-System.currentTimeMillis();if(timeline!=null)timeline.zone(zone);if(page==MY)updateProfile();});
        }catch(Exception problem){main.post(()->{if(current!=generation||requestApi!=api)return;serverBusy=false;if(problem instanceof RecorderApi.Failure&&((RecorderApi.Failure)problem).status==401)handle(problem);});}});
    }
    private void renderLive() {
        stopLive();if(api==null||page!=DETAIL||replay||videoArea==null||!visible)return;
        videoArea.removeAllViews(); liveNames.clear(); liveRecording.clear(); layoutButton.setText(layout==1?"多画面":"单画面"); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (cameras.isEmpty()) { surfaceMessage("没有分配摄像头\n请在电脑录像机中给此用户选择可看的设备"); return; }
        int selectedIndex = 0; for (int i = 0; i < cameras.size(); i++) if (cameras.get(i).id.equals(cameraId)) selectedIndex = i;
        int start = layout == 1 ? selectedIndex : selectedIndex / 4 * 4;
        LinearLayout rows = column(); videoArea.addView(rows, new FrameLayout.LayoutParams(-1, -1));
        int rowCount = layout == 1 ? 1 : 2, columnCount = layout == 1 ? 1 : 2;
        for (int row = 0; row < rowCount; row++) {
            LinearLayout cols = row(); rows.addView(cols, new LinearLayout.LayoutParams(-1, 0, 1));
            for (int col = 0; col < columnCount; col++) {
                int index = start + row * columnCount + col;
                FrameLayout tile = new FrameLayout(this); tile.setBackgroundColor(0xff15171a);
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -1, 1); tileParams.setMargins(dp(1), dp(1), dp(1), dp(1)); cols.addView(tile, tileParams);
                if (index >= cameras.size()) continue;
                RecorderModels.Camera camera = cameras.get(index);
                ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setContentDescription(camera.label() + " 实时画面"); tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
                android.graphics.Bitmap poster=thumbnailCache.get(camera.id);if(poster!=null)image.setImageBitmap(poster);
                TextView loading = text(poster==null?"正在连接…":"正在连接实时画面 · 当前为最近画面", 12, 0xffaab2be); loading.setGravity(Gravity.CENTER);loading.setBackgroundColor(poster==null?Color.TRANSPARENT:0x77000000);tile.addView(loading, new FrameLayout.LayoutParams(-1, -1));
                TextView name = text(camera.name,layout==1?13:11,Color.WHITE); name.setPadding(dp(7), dp(3), dp(7), dp(3)); name.setBackgroundColor(0x88000000);
                FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); nameParams.setMargins(dp(6), 0, dp(6), dp(6)); if(layout==4){tile.addView(name,nameParams);liveNames.put(camera.id,name);}
                TextView rec = text("● REC", 11, 0xfff46a70); FrameLayout.LayoutParams recParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); recParams.setMargins(dp(8), dp(8), dp(8), 0); tile.addView(rec, recParams); rec.setVisibility(camera.recording ? View.VISIBLE : View.GONE); liveRecording.put(camera.id, rec);
                long current=generation;final long[] posterTime={0};
                LiveStream stream = new LiveStream(api, camera.id, new LiveStream.Listener() {
                    @Override public void frame(android.graphics.Bitmap bitmap) { main.post(() -> { if (current == generation && image.isAttachedToWindow() && !replay && visible) { image.setImageBitmap(bitmap);loading.setVisibility(View.GONE);if(System.currentTimeMillis()-posterTime[0]>10000){posterTime[0]=System.currentTimeMillis();cacheThumbnail(camera.id,bitmap);} } }); }
                    @Override public void state(String text) { main.post(() -> { if (current == generation && loading.isAttachedToWindow()) { loading.setText(text + (image.getDrawable()!=null?" · 当前为最近画面":"")); loading.setVisibility(View.VISIBLE); } }); }
                    @Override public void expired() { main.post(() -> { if (current == generation && page==DETAIL && image.isAttachedToWindow() && !replay) showLogin("登录或设备权限已更新，请重新登录"); }); }
                }); streams.add(stream);
                tile.setOnClickListener(v->{if(layout==4)openCamera(camera.id,1);});
                GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public boolean onSingleTapConfirmed(MotionEvent e) { tile.performClick(); return true; }
                    @Override public boolean onDoubleTap(MotionEvent e) { if(layout==4)openCamera(camera.id,1);setFullScreen(!fullScreen); return true; }
                }); tile.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));
            }
        }
        if (layout == 4 && cameras.size() > 4) {
            Button next = button("下一组", false); next.setOnClickListener(v -> { int nextIndex = (start + 4) % cameras.size(); cameraId=cameras.get(nextIndex).id;renderLive(); });
            FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(dp(88), dp(48), Gravity.TOP | Gravity.END); videoArea.addView(next, pageParams);
        }
        addExitFullscreen();
    }
    private void stopLive(){for(LiveStream stream:streams)stream.close();streams.clear();}
    private void createVideo() {
        stopPlayback(); videoArea.removeAllViews(); video = new RecorderPlayerView(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER); videoArea.addView(video, params);

        mediaNotice = text("", 13, Color.WHITE); mediaNotice.setPadding(dp(12), dp(6), dp(12), dp(6)); mediaNotice.setGravity(Gravity.CENTER); mediaNotice.setBackgroundColor(0xaa25282d);
        FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); noticeParams.topMargin = dp(8); videoArea.addView(mediaNotice, noticeParams); mediaNotice.setVisibility(View.GONE);
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { setFullScreen(!fullScreen); return true; }
        }); video.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));
        addExitFullscreen();
    }
    private void queryRecordings(boolean positionFirst) {
        if (api == null || !replay || archiveBusy) return;
        if (rangeFrom == null || rangeTo == null) defaultRange();
        if (cameraId.isEmpty()) { archiveSummary.setText("没有授权设备"); return; }
        archiveBusy = true; if (positionFirst || planner.recordings().isEmpty()) archiveSummary.setText("正在查询录像…"); long sequence = ++searchGeneration; long current = generation;
        RecorderApi requestApi = api; String requestedCamera = cameraId; Instant from = rangeFrom, to = rangeTo;
        network.execute(() -> {
            try {
                List<RecorderModels.Recording> recordings = requestApi.recordings(requestedCamera, from, to);
                main.post(() -> {
                    if (current != generation || sequence != searchGeneration || !requestedCamera.equals(cameraId) || !replay) return;
                    archiveBusy = false; lastArchiveRefresh = System.currentTimeMillis(); planner.update(recordings, from, to); timeline.invalidate();
                    int pending = 0, persons = 0;
                    for (RecorderModels.Recording r : recordings) { persons += r.persons.size(); if ("PENDING".equals(r.personStatus) || "ANALYZING".equals(r.personStatus) || "QUEUED".equals(r.personStatus)) pending++; }
                    archiveSummary.setText(recordings.size() + " 段 · " + persons + " 人员事件" + (pending > 0 ? " · 识别中 " + pending : ""));
                    if (waitingAtEnd && playing && waitingCursor != null) {
                        PlaybackPlanner.Window available = planner.atOrAfter(waitingCursor);
                        if (available != null && usableTail(available)) { waitingAtEnd = false; seek(available.start, true); }
                    }
                    if (pendingSeek != null) {
                        Instant desired = pendingSeek; boolean autoplay = pendingPlay, backwards = pendingBackward; pendingSeek = null; pendingBackward = false;
                        if (backwards) { Instant before = planner.atOrBefore(desired); if (before != null) seek(before, autoplay); else { videoArea.removeAllViews(); surfaceMessage("所选时间之前没有录像"); addExitFullscreen(); cursor = desired; timeline.time(cursor); showPlaybackTime(); notice("此时间之前没有可回放录像"); } }
                        else seek(desired, autoplay);
                    }
                    else if (positionFirst) {
                        PlaybackPlanner.Window first = planner.atOrAfter(from);
                        cursor = first == null ? from : first.start; timeline.time(cursor); showPlaybackTime();
                        if (first == null) { stopPlayback(); videoArea.removeAllViews(); surfaceMessage("所选时间区间没有录像"); addExitFullscreen(); notice("所选时间区间没有可回放录像"); }
                        else if (visible) seek(cursor, false);
                    }
                });
            } catch (Exception e) { main.post(() -> { if (current != generation || sequence != searchGeneration) return; archiveBusy = false; lastArchiveRefresh = System.currentTimeMillis(); archiveSummary.setText("查询失败 · 点刷新重试"); handle(e); }); }
        });
    }
    private void seek(Instant time, boolean shouldPlay) {
        if (api == null || !replay || !visible) return;
        PlaybackPlanner.Window selectedWindow = planner.atOrAfter(planner.clamp(time));
        if (selectedWindow == null) { stopPlayback(); videoArea.removeAllViews(); surfaceMessage("所选时间没有录像"); addExitFullscreen(); cursor = planner.clamp(time); timeline.time(cursor); showPlaybackTime(); notice("此时间之后没有可回放录像"); return; }
        if (video == null || video.getParent()!=videoArea) createVideo();
        if (window != null && window.recording.id.equals(selectedWindow.recording.id) && !selectedWindow.start.isBefore(window.start) && selectedWindow.start.isBefore(window.end)
                && video.seekBuffered(Duration.between(window.start, selectedWindow.start).toMillis(), shouldPlay)) {
            cursor=selectedWindow.start;playing=shouldPlay;playButton.setText(shouldPlay?"暂停":"播放");timeline.time(cursor);showPlaybackTime();
            if(playing)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            notice(playing?"":"已定位 · 点播放继续");return;
        }
        final PlaybackPlanner.Window next=prefetchedWindow!=null&&prefetchedWindow.recording.id.equals(selectedWindow.recording.id)&&prefetchedWindow.start.equals(selectedWindow.start)?prefetchedWindow:selectedWindow;
        prefetchedWindow=null;prefetchBusy=false;
        playbackRevision++;video.beginSwitch();playbackBuffering=false;
        waitingAtEnd = false; waitingCursor = null; window = next; cursor = next.start; playing = shouldPlay; playButton.setText(shouldPlay ? "暂停" : "播放"); timeline.time(cursor); showPlaybackTime();
        if (!shouldPlay) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mediaPlayer = null; long current = generation; RecorderPlayerView target = video; RecorderApi requestApi = api;
        target.setOnPreparedListener(player -> {
            if (current != generation || video != target || window != next) return;
            mediaPlayer = player; applyPlaybackSpeed();
            if (current != generation || video != target || window != next) return;
            player.setOnInfoListener((mp, what, extra) -> {
                if(current!=generation||video!=target||window!=next)return false;
                if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START){playbackBuffering=true;notice("正在缓冲…");}
                if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END){playbackBuffering=false;notice(playing?"":"已定位 · 点播放继续");}
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    if (current != generation || video != target || window != next) return false;
                    playbackBuffering=false;if (!playing) { target.pause(); notice("已定位 · 点播放继续"); } else notice("");
                }
                return false;
            });
            // Streaming MP4 can have no initial duration: seeking even one millisecond may fail.
            // Decode the first frame, then pause from VIDEO_RENDERING_START for a paused seek.
            if (visible) { target.start(); if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        });
        target.setOnCompletionListener(player -> {
            if (current != generation || video != target || window != next) return;
            PlaybackPlanner.Window following = planner.atOrAfter(next.end);
            if (playing && following != null && usableTail(following)) seek(following.start, true);
            else if (playing && following != null) {
                window=null;waitingAtEnd=true;waitingCursor=following.start;cursor=next.end;lastArchiveRefresh=0;
                timeline.time(cursor);showPlaybackTime();notice("等待新录像…");queryRecordings(false);
            }
            else if (playing && !customRange && !next.end.isBefore(rangeTo) && rangeTo.isBefore(Instant.ofEpochMilli(System.currentTimeMillis() + clockOffset))) navigateTime(rangeTo, true);
            else if (playing && (followsToday || next.recording.active) && next.end.isBefore(rangeTo)) {
                window = null; waitingAtEnd = true; waitingCursor = next.end; cursor = next.end; lastArchiveRefresh = 0;
                timeline.time(cursor); showPlaybackTime(); notice("等待新录像…"); queryRecordings(false);
            } else { playing = false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); playButton.setText("播放"); cursor = next.end.minusMillis(1); timeline.time(cursor); showPlaybackTime(); notice("已到查询区间的录像末尾"); }
        });
        target.setOnErrorListener((player, what, extra) -> {
            if (current != generation || video != target || window != next) return true;
            playbackRevision++;prefetchedWindow=null;prefetchBusy=false;target.stopPlayback();mediaPlayer=null;
            playing = false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); window = null; playButton.setText("重试"); notice("回放连接中断，点重试从当前位置继续");
            network.execute(() -> { try { requestApi.verifySession(); } catch (Exception e) { main.post(() -> {
                if(current==generation&&requestApi==api&&(e instanceof RecorderApi.Failure&&((RecorderApi.Failure)e).status==401||page==DETAIL&&replay&&video==target))handle(e);
            }); } });
            return true;
        });
        notice("正在准备回放…"); network.execute(() -> {
            try { requestApi.prepareMedia(); main.post(() -> {
                if (current != generation || video != target || window != next || !visible) return;
                requestApi.installMediaSecurity(); target.setVideoURI(Uri.parse(requestApi.mediaUrl(next)), requestApi.authorizationHeaders());
            }); } catch (Exception e) { main.post(() -> { if (current == generation && video == target && window == next) {
                playbackRevision++;prefetchedWindow=null;prefetchBusy=false;playbackBuffering=false;target.stopPlayback();mediaPlayer=null;window=null;playing=false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);playButton.setText("重试");notice("回放连接失败，点重试从当前位置继续");handle(e);
            } }); }
        });
    }
    private void togglePlayback() {
        if (waitingAtEnd) { waitingAtEnd = false; playing = false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(video!=null)video.pause();playButton.setText("播放"); notice("已暂停"); return; }
        if (window == null || video == null) { seek(cursor, true); return; }
        playing = !playing; playButton.setText(playing ? "暂停" : "播放");
        if (playing) { video.start(); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        else { video.pause(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        notice(playing ? "" : "已暂停");
    }
    private boolean usableTail(PlaybackPlanner.Window available) {
        return PlaybackBufferPolicy.resumeTail(available.durationSeconds, available.recording.active && available.end.isBefore(rangeTo));
    }
    private void stopPlayback() { playbackRevision++;prefetchedWindow=null;prefetchBusy=false;playbackBuffering=false;lastPrefetchAttempt=0;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); mediaPlayer = null; playing = false; waitingAtEnd = false; waitingCursor = null; window = null; if (video != null) { video.stopPlayback(); video = null; } if (playButton != null) playButton.setText("播放"); }
    private void prefetchPlayback() {
        if(api==null||!replay||!visible||!playing||video==null||window==null||prefetchBusy||playbackBuffering)return;
        if(prefetchedWindow!=null){if(video.hasPreparedNext())return;prefetchedWindow=null;}
        if(System.currentTimeMillis()-lastPrefetchAttempt<3000)return;
        PlaybackPlanner.Window following=planner.atOrAfter(window.end);
        if(following==null||!PlaybackBufferPolicy.prefetch(PlaybackPlanner.seconds(cursor,window.end),playbackSpeed,following.durationSeconds,following.recording.active))return;
        prefetchBusy=true;lastPrefetchAttempt=System.currentTimeMillis();long revision=playbackRevision,current=generation;RecorderPlayerView target=video;RecorderApi requestApi=api;
        network.execute(()->{
            try{requestApi.prepareMedia();main.post(()->{
                if(current!=generation||revision!=playbackRevision||video!=target||api!=requestApi||!replay||!visible)return;
                prefetchBusy=false;prefetchedWindow=following;requestApi.installMediaSecurity();
                target.prepareNext(Uri.parse(requestApi.mediaUrl(following)),requestApi.authorizationHeaders());
            });}catch(Exception e){main.post(()->{if(current==generation&&revision==playbackRevision){prefetchBusy=false;if(e instanceof RecorderApi.Failure&&((RecorderApi.Failure)e).status==401)handle(e);}});}
        });
    }
    private void showPlaybackTime() { if (playbackTime != null) playbackTime.setText(format(cursor, "MM-dd HH:mm:ss")); }
    private void stepPlayback(int seconds) {
        Instant wanted = cursor.plusSeconds(seconds);
        if (seconds < 0 && !wanted.isBefore(rangeFrom) && wanted.isBefore(rangeTo)) {
            Instant available = planner.atOrBefore(wanted);
            if (available != null) wanted = available;
            else { notice("此时间之前没有可回放录像"); return; }
        }
        navigateTime(wanted, playing);
        if (pendingSeek != null) pendingBackward = seconds < 0;
    }
    private void navigateClip(boolean next) {
        Instant target = planner.recordingStart(cursor, next);
        if (target == null) notice(next ? "当前区间没有下一段录像" : "当前区间没有上一段录像"); else navigateTime(target, playing);
    }
    private void selectSpeed() {
        String[] labels = {"0.5×", "1×", "2×", "4×", "8×"}; float[] rates = {.5f, 1f, 2f, 4f, 8f};
        int selected = 1; for (int i = 0; i < rates.length; i++) if (rates[i] == playbackSpeed) selected = i;
        new AlertDialog.Builder(dialogContext()).setTitle("回放速度").setSingleChoiceItems(labels, selected, (dialog, index) -> { playbackSpeed = rates[index]; speedButton.setText(labels[index]); applyPlaybackSpeed(); dialog.dismiss(); }).setNegativeButton("取消", null).show();
    }
    private void applyPlaybackSpeed() {
        if (mediaPlayer == null) return;
        try { mediaPlayer.setPlaybackParams(new PlaybackParams().setSpeed(playbackSpeed).setPitch(1f).setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_MUTE)); if(Math.abs(mediaPlayer.getPlaybackParams().getSpeed()-playbackSpeed)>0.01f)throw new IllegalArgumentException("Native player did not apply requested speed"); if (!playing && video != null) video.pause(); }
        catch (IllegalArgumentException | IllegalStateException e) {
            playbackSpeed = 1f; speedButton.setText("1×");
            try { mediaPlayer.setPlaybackParams(new PlaybackParams().setSpeed(1f).setPitch(1f)); if(Math.abs(mediaPlayer.getPlaybackParams().getSpeed()-1f)>0.01f)throw new IllegalArgumentException("Native player did not restore normal speed"); if (!playing && video != null) video.pause(); }
            catch (IllegalArgumentException | IllegalStateException resetFailure) { stopPlayback(); playButton.setText("重试"); notice("此手机播放器暂不支持该倍速，请点重试继续"); return; }
            notice("此手机播放器暂不支持该倍速，已恢复正常速度");
        }
    }
    private void navigateTime(Instant desired, boolean autoplay) {
        if (!replay) return;
        if (customRange) { seek(planner.clamp(desired), autoplay); return; }
        java.time.LocalDate date = desired.atZone(zone).toLocalDate();
        if (date.getYear() < 1970 || date.isAfter(Instant.ofEpochMilli(System.currentTimeMillis() + clockOffset).atZone(zone).toLocalDate())) { notice("请选择已有的日期"); cursor = planner.clamp(Instant.ofEpochMilli(System.currentTimeMillis() + clockOffset)); timeline.time(cursor); showPlaybackTime(); return; }
        if (!desired.isBefore(rangeFrom) && desired.isBefore(rangeTo)) { seek(desired, autoplay); return; }
        stopPlayback(); searchGeneration++; archiveBusy = false; rangeFrom = date.atStartOfDay(zone).toInstant(); rangeTo = date.plusDays(1).atStartOfDay(zone).toInstant();
        followsToday = date.equals(Instant.ofEpochMilli(System.currentTimeMillis() + clockOffset).atZone(zone).toLocalDate());
        pendingSeek = desired; pendingPlay = autoplay; pendingBackward = false; cursor = desired; updateRangeLabel(); clearArchive(); timeline.time(cursor); showPlaybackTime(); queryRecordings(false);
    }
    private void changeDay(int days) { chooseDay(rangeFrom.atZone(zone).toLocalDate().plusDays(days)); }
    private void showDateActions() {
        new AlertDialog.Builder(dialogContext()).setTitle("录像日期与时间").setItems(new String[] {"选择日期", "查询时间区间", "回到今天", "最新录像"}, (dialog, which) -> {
            if (which == 0) selectDay(); else if (which == 1) selectRange(); else if (which == 2) changeToToday(); else latestRecording();
        }).setNegativeButton("关闭", null).show();
    }
    private void selectDay() {
        java.time.LocalDate date = rangeFrom.atZone(zone).toLocalDate();
        DatePickerDialog dialog = new DatePickerDialog(dialogContext(), (picker, year, month, day) -> chooseDay(java.time.LocalDate.of(year, month + 1, day)), date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis() + clockOffset); dialog.show();
    }
    private void chooseDay(java.time.LocalDate date) {
        if (date.getYear() < 1970 || date.isAfter(Instant.ofEpochMilli(System.currentTimeMillis() + clockOffset).atZone(zone).toLocalDate())) { notice("已是最新日期"); return; }
        stopPlayback(); searchGeneration++; archiveBusy = false; pendingSeek = null; customRange = false; timeline.constrained(false);
        rangeFrom = date.atStartOfDay(zone).toInstant(); rangeTo = date.plusDays(1).atStartOfDay(zone).toInstant(); cursor = rangeFrom;
        followsToday = date.equals(Instant.ofEpochMilli(System.currentTimeMillis() + clockOffset).atZone(zone).toLocalDate()); updateRangeLabel(); clearArchive(); queryRecordings(true);
    }
    private void latestRecording() {
        if (api == null || cameraId.isEmpty()) return;
        long current = generation, sequence = ++searchGeneration; RecorderApi requestApi = api; String requestedCamera = cameraId; archiveBusy = true;archiveSummary.setText("正在查找最近录像…");
        network.execute(() -> {
            try { Instant end = requestApi.lastRecording(requestedCamera); main.post(() -> {
                if (current != generation || sequence != searchGeneration || requestApi != api || !requestedCamera.equals(cameraId) || !replay) return;
                archiveBusy = false;
                if (end == null) {lastArchiveRefresh=System.currentTimeMillis();archiveSummary.setText("暂无录像");videoArea.removeAllViews();surfaceMessage("此摄像头还没有录像");addExitFullscreen();notice("此摄像头没有可回放录像");return; }
                customRange = false; timeline.constrained(false); chooseDay(end.minusMillis(1).atZone(zone).toLocalDate()); pendingSeek = end.minusSeconds(10).isBefore(rangeFrom) ? rangeFrom : end.minusSeconds(10); pendingPlay = false; pendingBackward = false;
            }); } catch (Exception e) { main.post(() -> { if (current == generation && sequence == searchGeneration) { archiveBusy = false; handle(e); } }); }
        });
    }
    private void showArchiveEvents() {
        List<Instant> events=planner.personStarts();LinearLayout box=column();box.setPadding(dp(12),0,dp(12),0);
        TextView info=text(events.isEmpty()?"当前区间没有已确认的人员事件。\n这不代表画面中一定无人，可继续拖动时间轴查看。":"橙色标记来自录像机识别，点时间查看对应录像。",13,MUTED);
        info.setPadding(0,dp(8),0,dp(12));box.addView(info,match(-2));
        LinearLayout jump=row();Button previous=button("上一人员",false),next=button("下一人员",false);jump.addView(previous,new LinearLayout.LayoutParams(0,dp(48),1));jump.addView(next,new LinearLayout.LayoutParams(0,dp(48),1));box.addView(jump);
        LinearLayout list=column();for(Instant value:events.subList(0,Math.min(200,events.size()))){Button entry=button(format(value,"MM-dd HH:mm:ss")+"  ·  人员事件",false);entry.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);entry.setPadding(dp(8),0,dp(8),0);entry.setBackgroundColor(SURFACE);entry.setTag(value);list.addView(entry,match(dp(52)));}box.addView(list);
        android.app.Dialog dialog=bottomSheet("人员事件 · "+events.size()+" 段",box);
        previous.setEnabled(planner.personBefore(cursor)!=null);next.setEnabled(planner.firstPersonAfter(cursor)!=null);
        previous.setAlpha(previous.isEnabled()?1f:.4f);next.setAlpha(next.isEnabled()?1f:.4f);
        previous.setOnClickListener(v->{Instant target=planner.personBefore(cursor);if(target!=null){dialog.dismiss();navigateTime(target,playing);}});
        next.setOnClickListener(v->{Instant target=planner.firstPersonAfter(cursor);if(target!=null){dialog.dismiss();navigateTime(target,playing);}});
        for(int i=0;i<list.getChildCount();i++)list.getChildAt(i).setOnClickListener(v->{dialog.dismiss();navigateTime((Instant)v.getTag(),playing);});dialog.show();fitSheet(dialog);
    }
    private void selectRange() {
        if (rangeFrom == null) defaultRange();
        LinearLayout box = column(); box.setPadding(dp(18), dp(8), dp(18), 0); Instant[] picked = {rangeFrom, rangeTo};
        Button from = button("开始  " + format(picked[0], "yyyy-MM-dd HH:mm"), false);
        Button to = button("结束  " + format(picked[1], "yyyy-MM-dd HH:mm"), false);
        from.setOnClickListener(v -> pickTime(picked[0], value -> { picked[0] = value; from.setText("开始  " + format(value, "yyyy-MM-dd HH:mm")); }));
        to.setOnClickListener(v -> pickTime(picked[1], value -> { picked[1] = value; to.setText("结束  " + format(value, "yyyy-MM-dd HH:mm")); }));
        box.addView(from, match(dp(52))); box.addView(to, match(dp(52))); TextView error = text("最长查询 31 天", 12, MUTED); box.addView(error, match(dp(40)));
        AlertDialog dialog = new AlertDialog.Builder(dialogContext()).setTitle("查询录像时间区间").setView(box).setNegativeButton("取消", null).setPositiveButton("查询", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (!picked[1].isAfter(picked[0]) || Duration.between(picked[0], picked[1]).compareTo(Duration.ofDays(31)) > 0) { error.setText("结束须晚于开始，区间最长 31 天"); error.setTextColor(RED); return; }
            stopPlayback(); searchGeneration++; archiveBusy = false; followsToday = false; customRange = true; timeline.constrained(true); pendingSeek = null; rangeFrom = picked[0]; rangeTo = picked[1]; updateRangeLabel(); clearArchive(); queryRecordings(true); dialog.dismiss();
        })); dialog.show();
    }
    private interface TimePicked { void picked(Instant value); }
    private void pickTime(Instant value, TimePicked callback) {
        ZonedDateTime local = value.atZone(zone);
        DatePickerDialog date = new DatePickerDialog(dialogContext(), (picker, year, month, day) -> {
            TimePickerDialog time = new TimePickerDialog(dialogContext(), (tp, hour, minute) -> callback.picked(ZonedDateTime.of(year, month + 1, day, hour, minute, 0, 0, zone).toInstant()), local.getHour(), local.getMinute(), true); time.show();
        }, local.getYear(), local.getMonthValue() - 1, local.getDayOfMonth()); date.show();
    }
    private void setFullScreen(boolean value) {
        if(page!=DETAIL||value==fullScreen)return;
        if(value){orientationBeforeFullscreen=getRequestedOrientation();layoutBeforeFullscreen=layout;}
        fullScreen=value;
        if(value){styleSystemBars(getWindow(),BACKGROUND,true);setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);}
        else{styleSystemBars(getWindow(),BACKGROUND,false);setRequestedOrientation(orientationBeforeFullscreen);}
        applyResponsiveLayout();addExitFullscreen();
    }
    private void applyResponsiveLayout() {
        if(api==null||page!=DETAIL||header==null||detailBody==null)return;
        boolean compact=fullScreen||getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        header.setVisibility(compact?View.GONE:View.VISIBLE);toolbar.setVisibility(fullScreen?View.GONE:View.VISIBLE);livePanel.setVisibility(replay||compact?View.GONE:View.VISIBLE);
        playbackPanel.setVisibility(replay?View.VISIBLE:View.GONE);searchRow.setVisibility(compact?View.GONE:View.VISIBLE);rangeRow.setVisibility(customRange?View.VISIBLE:View.GONE);navigationRow.setVisibility(compact?View.GONE:View.VISIBLE);timelineLegend.setVisibility(compact?View.GONE:View.VISIBLE);
        setChildHeights(playbackTimeRow,dp(compact?28:36));setChildHeights(playbackControls,dp(48));playbackTime.setTextSize(compact?13:15);
        LinearLayout.LayoutParams controls=(LinearLayout.LayoutParams)playbackControls.getLayoutParams();controls.height=dp(compact?48:56);playbackControls.setLayoutParams(controls);
        ViewGroup.LayoutParams tp=timeline.getLayoutParams();tp.height=dp(compact?64:72);timeline.setLayoutParams(tp);status.setVisibility(compact||activeNotice.isEmpty()?View.GONE:View.VISIBLE);
        if(compact){if(detailBody.getParent()==detailScroll){detailScroll.removeView(detailBody);detailFrame.removeView(detailScroll);detailFrame.addView(detailBody,new FrameLayout.LayoutParams(-1,-1));}detailBody.setPadding(0,0,0,0);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,0,1);videoArea.setLayoutParams(vp);}
        else{if(detailBody.getParent()==detailFrame){detailFrame.removeView(detailBody);detailScroll.addView(detailBody,new ScrollView.LayoutParams(-1,-2));detailFrame.addView(detailScroll,new FrameLayout.LayoutParams(-1,-1));}detailBody.setPadding(0,0,0,dp(12));LinearLayout.LayoutParams vp=match(dp(Math.max(148,(getResources().getConfiguration().screenWidthDp-32)*9f/16f)));vp.setMargins(dp(16),dp(4),dp(16),dp(8));videoArea.setLayoutParams(vp);}
        addExitFullscreen();root.requestLayout();getWindow().getDecorView().requestApplyInsets();
    }
    private void setChildHeights(LinearLayout row, int height) {
        for (int i = 0; i < row.getChildCount(); i++) { View child = row.getChildAt(i); ViewGroup.LayoutParams params = child.getLayoutParams(); params.height = height; child.setLayoutParams(params); }
    }
    private void addExitFullscreen() {
        if(page!=DETAIL||videoArea==null)return;
        View old=videoArea.findViewWithTag("exit-fullscreen");if(old!=null)videoArea.removeView(old);
        Button screen=iconButton(fullScreen?MobileIcons.BACK:MobileIcons.FULLSCREEN,fullScreen?"退出视频全屏":"视频全屏",Color.WHITE);screen.setTag("exit-fullscreen");screen.setBackground(shape(0x8830435e));screen.setOnClickListener(v->setFullScreen(!fullScreen));
        FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(48),dp(48),fullScreen?Gravity.TOP|Gravity.END:Gravity.BOTTOM|Gravity.END);fp.setMargins(dp(8),dp(8),dp(8),dp(8));videoArea.addView(screen,fp);
        View tools=videoArea.findViewWithTag("replay-tools");boolean compact=fullScreen||getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        if(tools!=null)videoArea.removeView(tools);
        if(replay&&compact){Button open=button("回放工具",false);open.setTextColor(Color.WHITE);open.setBackground(shape(0x8830435e));open.setTag("replay-tools");open.setOnClickListener(v->showReplayTools());FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(100),dp(48),Gravity.TOP|Gravity.START);p.setMargins(dp(8),dp(8),0,0);videoArea.addView(open,p);}
    }
    private void showReplayTools() {
        if(!replay||api==null)return;
        showOptions("回放工具",new String[]{"定位到时间","人员事件","上一段录像","下一段录像","选择日期","查询时间区间","最新录像","刷新录像"},which->{
            if(which==0)pickTime(cursor,value->navigateTime(value,playing));else if(which==1)showArchiveEvents();else if(which==2)navigateClip(false);else if(which==3)navigateClip(true);else if(which==4)selectDay();else if(which==5)selectRange();else if(which==6)latestRecording();else queryRecordings(false);
        });
    }
    private void confirmLogout() {
        new AlertDialog.Builder(dialogContext()).setTitle("退出登录").setMessage("当前用户：" + session.username + "\n退出后可以登录其他账号或更换录像机地址。")
                .setNegativeButton("取消", null).setPositiveButton("退出", (dialog, which) -> {
                    RecorderApi old = api; showLogin(""); if (old != null) network.execute(() -> { try { old.logout(); } catch (Exception ignored) { } });
                }).show();
    }
    private void handle(Exception e) {
        if (e instanceof RecorderApi.Failure && ((RecorderApi.Failure) e).status == 401) showLogin("登录或设备权限已更新，请重新登录");
        else { notice(userMessage(e)); connectionErrorShown = true; }
    }
    private String userMessage(Exception e) {
        if (e instanceof RecorderApi.Failure || e instanceof IllegalArgumentException) return e.getMessage();
        return ConnectionErrorMessage.describe(e);
    }
    private void notice(String text) {
        activeNotice = text; connectionErrorShown = false;
        if(status!=null&&api!=null){status.setText(text);status.setVisibility(text.isEmpty()||fullScreen?View.GONE:View.VISIBLE);}
        if (replay && mediaNotice != null && mediaNotice.isAttachedToWindow()) { mediaNotice.setText(text); mediaNotice.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE); }
    }
    private void surfaceMessage(String message) { TextView empty = text(message, 14, 0xffaab2be); empty.setGravity(Gravity.CENTER); videoArea.addView(empty, new FrameLayout.LayoutParams(-1, -1)); }
    private String cameraLabel() { for (RecorderModels.Camera c : cameras) if (c.id.equals(cameraId)) return c.label(); return ""; }
    private List<String> cameraLabels(List<RecorderModels.Camera> values) { List<String> result = new ArrayList<>(); for (RecorderModels.Camera c : values) result.add(c.label()); return result; }
    private List<String> cameraIds(List<RecorderModels.Camera> values) { List<String> result = new ArrayList<>(); for (RecorderModels.Camera c : values) result.add(c.id); return result; }
    private String format(Instant value, String pattern) { return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).withZone(zone).format(value); }
    private static String gb(long bytes) { return String.format(Locale.ROOT, "%.1f", bytes / 1073741824.0); }
    private void initializeAppearance() {
        appearance=new AppAppearance(this);palette=appearance.palette(getResources().getConfiguration());applyPaletteFields();applyActivityTheme();
    }
    private void applyPaletteFields() { ACCENT=palette.accent;RED=palette.error;DARK=palette.text;MUTED=palette.muted;GREEN=palette.green;BACKGROUND=palette.background;SURFACE=palette.surface; }
    private int appearanceStyle(String light,String dark) { return getResources().getIdentifier(palette.dark?dark:light,"style",getPackageName()); }
    private void applyActivityTheme() { int id=appearanceStyle("RecorderTheme","RecorderDarkTheme");if(id!=0)setTheme(id); }
    private Context dialogContext() { int id=appearanceStyle("RecorderDialogTheme","RecorderDarkDialogTheme");return id==0?this:new android.view.ContextThemeWrapper(this,id); }
    private void chooseAppearance() {
        String[] values={"跟随系统","浅色","深色"},modes={AppAppearance.SYSTEM,AppAppearance.LIGHT,AppAppearance.DARK};int selected=AppAppearance.LIGHT.equals(appearance.mode())?1:AppAppearance.DARK.equals(appearance.mode())?2:0;
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle("外观").setSingleChoiceItems(values,selected,(d,index)->{d.dismiss();setAppearanceMode(modes[index]);}).setNegativeButton("取消",null).create();
        activeSheet=dialog;dialog.setOnDismissListener(d->{if(activeSheet==dialog)activeSheet=null;});dialog.show();
    }
    void setAppearanceMode(String mode) { appearance.mode(mode);refreshAppearanceForConfiguration(); }
    private void refreshAppearanceForConfiguration() {
        if(appearance==null)return;AppAppearance.Palette previous=palette;palette=appearance.palette(getResources().getConfiguration());applyPaletteFields();applyActivityTheme();
        if(previous.dark!=palette.dark) {
            // Update the existing hierarchy so player surfaces, seek position and connections stay intact.
            if(activeSheet!=null)activeSheet.dismiss();
            if(contentFrame!=null)recolorView(contentFrame,previous,palette);
            if(playbackPanel!=null&&!belongsToContent(playbackPanel))recolorView(playbackPanel,previous,palette);
        }
        if(appearanceSummary!=null)appearanceSummary.setText(appearance.label());
        if(timeline!=null)timeline.appearance(palette);
        styleSystemBars(getWindow(),BACKGROUND,fullScreen);scheduleSystemBarsRefresh();if(contentFrame!=null)contentFrame.requestApplyInsets();
    }
    private void recolorDrawable(android.graphics.drawable.Drawable drawable,AppAppearance.Palette previous,AppAppearance.Palette next) {
        if(drawable==null)return;
        if(drawable instanceof AppAppearance.SquareDrawable)((AppAppearance.SquareDrawable)drawable).appearance(previous,next);
        else if(drawable instanceof android.graphics.drawable.ColorDrawable){android.graphics.drawable.ColorDrawable color=(android.graphics.drawable.ColorDrawable)drawable;color.setColor(previous.remap(color.getColor(),next));}
        else if(drawable instanceof MobileIcons)((MobileIcons)drawable).appearance(previous,next);
        else if(drawable instanceof android.graphics.drawable.InsetDrawable)recolorDrawable(((android.graphics.drawable.InsetDrawable)drawable).getDrawable(),previous,next);
        else if(drawable instanceof android.graphics.drawable.LayerDrawable){android.graphics.drawable.LayerDrawable layers=(android.graphics.drawable.LayerDrawable)drawable;for(int i=0;i<layers.getNumberOfLayers();i++)recolorDrawable(layers.getDrawable(i),previous,next);if(drawable instanceof AppAppearance.ThemeRipple)((AppAppearance.ThemeRipple)drawable).appearance(previous,next);}
    }
    private boolean belongsToContent(View view) { for(android.view.ViewParent parent=view.getParent();parent!=null;parent=parent.getParent())if(parent==contentFrame)return true;return view==contentFrame; }
    private void recolorView(View view,AppAppearance.Palette previous,AppAppearance.Palette next) {
        recolorDrawable(view.getBackground(),previous,next);
        if(view instanceof TextView){TextView text=(TextView)view;if(text.getCurrentTextColor()!=Color.WHITE)text.setTextColor(previous.remap(text.getCurrentTextColor(),next));for(android.graphics.drawable.Drawable icon:text.getCompoundDrawables())recolorDrawable(icon,previous,next);}
        if(view instanceof EditText)styleEditText((EditText)view);
        if(view instanceof CheckBox)styleCheckBox((CheckBox)view);
        if(view instanceof ImageView)recolorDrawable(((ImageView)view).getDrawable(),previous,next);
        if(view instanceof TimelineView)((TimelineView)view).appearance(next);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)recolorView(group.getChildAt(i),previous,next);}
        view.invalidate();
    }
    private void styleCheckBox(CheckBox check) {
        check.setTextColor(DARK);check.setButtonTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ACCENT,MUTED}));
    }
    private void styleEditText(EditText field) {
        field.setTextColor(DARK);field.setHintTextColor(MUTED);field.setHighlightColor((ACCENT&0x00ffffff)|0x44000000);
        if(android.os.Build.VERSION.SDK_INT>=29){android.graphics.drawable.Drawable cursor=field.getTextCursorDrawable();if(cursor!=null){cursor=cursor.mutate();cursor.setTint(ACCENT);field.setTextCursorDrawable(cursor);}android.graphics.drawable.Drawable[] handles={field.getTextSelectHandle(),field.getTextSelectHandleLeft(),field.getTextSelectHandleRight()};for(android.graphics.drawable.Drawable handle:handles)if(handle!=null)handle.mutate().setTint(ACCENT);}
    }
    private void styleSystemBars(android.view.Window window,int color,boolean immersive) {
        // PhoneWindow needs its decor before getInsetsController, including before setContentView.
        View decor=window.getDecorView();
        boolean light=!palette.dark&&!immersive;
        boolean lightNavigation=!palette.dark&&(!immersive||replay);
        window.setStatusBarColor(color);window.setNavigationBarColor(color);
        if(android.os.Build.VERSION.SDK_INT>=29){window.setNavigationBarContrastEnforced(false);window.setStatusBarContrastEnforced(false);}
        if(android.os.Build.VERSION.SDK_INT>=30){
            if(window==getWindow()){window.setDecorFitsSystemWindows(false);window.setStatusBarColor(Color.TRANSPARENT);window.setNavigationBarColor(Color.TRANSPARENT);}
            android.view.WindowInsetsController controller=window.getInsetsController();if(controller!=null){int mask=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;int appearanceFlags=(light?android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS:0)|(lightNavigation?android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS:0);controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(window==getWindow()){if(immersive)controller.hide(android.view.WindowInsets.Type.systemBars());else controller.show(android.view.WindowInsets.Type.systemBars());}controller.setSystemBarsAppearance(appearanceFlags,mask);}
        } else {
            int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if(light)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if(lightNavigation)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if(immersive)flags|=View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decor.setSystemUiVisibility(flags);
        }
    }
    private void scheduleSystemBarsRefresh() {
        View decor=getWindow().getDecorView();decor.removeCallbacks(refreshSystemBars);
        // Configuration dispatch and window focus can replace the platform's requested appearance.
        decor.postOnAnimation(refreshSystemBars);
    }
    private void setRoot(View view) {
        styleSystemBars(getWindow(),BACKGROUND,fullScreen);
        FrameLayout frame = new FrameLayout(this);contentFrame=frame;frame.setBackgroundColor(BACKGROUND);frame.addView(view,new FrameLayout.LayoutParams(-1,-1));
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard=insets.getInsets(android.view.WindowInsets.Type.ime());
                int bottom=Math.max(bars.bottom,keyboard.bottom);boolean keyboardChanged=insets.isVisible(android.view.WindowInsets.Type.ime())&&v.getPaddingBottom()!=bottom;
                v.setPadding(bars.left,bars.top,bars.right,bottom);
                if(keyboardChanged) v.postOnAnimation(()->{View focus=v.findFocus();if(focus instanceof EditText&&focus.isFocused())focus.requestRectangleOnScreen(new android.graphics.Rect(0,0,focus.getWidth(),focus.getHeight()+dp(8)),false);});
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        }); setContentView(frame); frame.requestApplyInsets();scheduleSystemBarsRefresh();
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private LinearLayout row() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.HORIZONTAL); return result; }
    private LinearLayout.LayoutParams match(int height) { return new LinearLayout.LayoutParams(-1, height); }
    private TextView text(String value, int size, int color) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setGravity(Gravity.CENTER_VERTICAL); return text; }
    private TextView label(String value) { TextView text = text(value, 13, DARK); text.setPadding(0, dp(10), 0, dp(4)); return text; }
    private EditText field(String hint, boolean password) {
        EditText field = new EditText(this); field.setTextSize(15);field.setTextColor(DARK);field.setHintTextColor(MUTED);field.setHighlightColor((ACCENT&0x00ffffff)|0x44000000);field.setSingleLine(true);field.setHint(hint);field.setPadding(dp(12),0,dp(12),0);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_NORMAL));
        AppAppearance.SquareDrawable border=shape(SURFACE);border.border(dp(1),palette.border);field.setBackground(border);styleEditText(field);return field;
    }
    private Button button(String value, boolean active) {
        Button result = new Button(this); result.setText(value); result.setTextSize(13); result.setSingleLine(true); result.setAllCaps(false); result.setMinWidth(0); result.setMinimumWidth(0); result.setMinHeight(dp(44)); result.setPadding(dp(4), 0, dp(4), 0);
        result.setAutoSizeTextTypeUniformWithConfiguration(11, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP); selected(result, active); return result;
    }
    private void selected(Button button,boolean active) {
        button.setTextColor(active?Color.WHITE:DARK);GradientDrawable bg=shape(active?palette.primary:palette.control);
        android.graphics.drawable.InsetDrawable inset=new android.graphics.drawable.InsetDrawable(bg,dp(3));
        button.setBackground(new AppAppearance.ThemeRipple(active?0x33ffffff:(ACCENT&0x00ffffff)|0x22000000,inset,null));
    }

}
