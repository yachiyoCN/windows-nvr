package cn.homewatch;

import com.sun.net.httpserver.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.net.ssl.SSLParameters;

/** Two independent listeners: browser sessions on 8079, Bearer-only App API on 9079. */
public final class RemoteAccessService implements AutoCloseable {
    public static final int WEB_PORT=8079, API_PORT=9079;
    public record CameraInfo(String id,String name,int number,boolean online,boolean recording,int width,int height) { }
    public record ServerSnapshot(long maxBytes,long usedBytes,int keepDays,int segmentMinutes) { }
    public record RemoteRecording(String channelId,Path path,Instant start,double durationSeconds,long bytes,boolean active,
                                  List<PersonInterval> persons,String personStatus) {
        public RemoteRecording { persons=List.copyOf(Objects.requireNonNullElse(persons,List.of())); }
        @Override public String toString(){return "RemoteRecording[channelId="+channelId+", active="+active+"]";}
    }
    public interface Backend {
        ServerSnapshot serverSnapshot();
        List<CameraInfo> cameras();
        List<RemoteRecording> recordings(String channelId,Instant from,Instant to);
        BufferedImage latestFrame(String channelId);
        AutoCloseable acquireCamera(String channelId) throws Exception;
        AutoCloseable acquireRecording(Path path) throws Exception;
    }
    public record State(boolean webRunning,boolean apiRunning,String message) { }
    public record UserInfo(String id,String username,boolean enabled,Set<String> channelIds) {
        public UserInfo { channelIds=Set.copyOf(channelIds); }
    }
    private final Path root;
    private final int webPort,apiPort;
    private final Backend backend;
    private final Consumer<State> listener;
    private final RemoteMedia media;
    private final ExecutorService executor;
    private final ScheduledExecutorService maintenance;
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    private final Map<String,RefreshGrant> refreshGrants=new ConcurrentHashMap<>();
    private RemotePrivateStorage privateStorage;
    private RemoteTls tls;
    private volatile RemoteAuditLog audit;
    private static final long REMEMBER_MS=Duration.ofDays(30).toMillis(), ACCESS_MS=Duration.ofMinutes(15).toMillis();
    private final Map<String,Attempts> attempts=new ConcurrentHashMap<>();
    private final Semaphore streams=new Semaphore(24,true), passwords=new Semaphore(2,true), requests=new Semaphore(64,true);
    private final Map<String,JpegFrame> jpegFrames=new ConcurrentHashMap<>();
    private volatile RemoteAccessSettings settings;
    private volatile RemoteUserStore users;
    private volatile State state=new State(false,false,"远程服务未启用");
    private HttpServer webServer,apiServer;
    private volatile boolean closed;
    private volatile long accessGeneration;
    private boolean configLoadFailed;
    private final Map<Path,AtomicInteger> downloads=new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final String DUMMY_PASSWORD_HASH=RemoteAccessSettings.hashPassword(randomToken());
    public RemoteAccessService(Path root,Path assetsRoot,Backend backend,Consumer<State> listener) throws IOException {
        this(root,assetsRoot,backend,listener,WEB_PORT,API_PORT);
    }
    RemoteAccessService(Path root,Path assetsRoot,Backend backend,Consumer<State> listener,int webPort,int apiPort) throws IOException {
        this.webPort=webPort;this.apiPort=apiPort;
        this.root=root.toAbsolutePath().normalize(); this.backend=Objects.requireNonNull(backend);
        this.listener=listener==null?s->{}:listener;
        try{this.settings=RemoteAccessSettings.load(this.root);}catch(IOException invalid){this.settings=RemoteAccessSettings.defaults();configLoadFailed=true;state=new State(false,false,"远程配置无法读取，请重新保存远程服务设置");}
        try{this.users=RemoteUserStore.load(this.root);}catch(IOException invalid){this.users=new RemoteUserStore(List.of());configLoadFailed=true;state=new State(false,false,"远程用户配置无法读取，请检查用户后重新保存设置");}
        media=new RemoteMedia(assetsRoot.toAbsolutePath().normalize(),backend);
        executor=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("remote-http-",0).factory());
        maintenance=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"remote-session-cleanup");t.setDaemon(true);return t;});
        maintenance.scheduleAtFixedRate(this::cleanup,30,30,TimeUnit.SECONDS);
    }
    public RemoteAccessSettings settings(){return settings;}
    public State state(){return state;}
    public synchronized String connectionFingerprint(){return tls==null?"":tls.publicKeyPin();}
    public synchronized Path browserCertificate(){return tls==null?null:tls.publicCertificate();}
    public List<String> recentLoginRecords()throws IOException{RemoteAuditLog current=audit;return current==null?List.of():current.recent();}
    private void audit(String event,String username,HttpExchange x){RemoteAuditLog current=audit;if(current!=null)current.record(event,username,x==null?"电脑设置":x.getRemoteAddress().getAddress().getHostAddress());}
    public List<CameraInfo> availableCameras(){return List.copyOf(backend.cameras());}
    public List<UserInfo> users(){return users.users().stream().map(u->new UserInfo(u.id(),u.username(),u.enabled(),u.channelIds())).toList();}
    public synchronized void saveUser(String id,String username,String password,boolean enabled,Set<String> channelIds)throws IOException{
        if(closed)throw new IOException("远程服务已关闭。");
        if(settings.webUsername().equalsIgnoreCase(Objects.requireNonNullElse(username,"").strip()))throw new IllegalArgumentException("该用户名已用作 Web 管理员，请使用其他用户名。");
        Set<String> available=new HashSet<>();for(CameraInfo camera:backend.cameras())available.add(camera.id());
        if(!available.containsAll(channelIds))throw new IllegalArgumentException("摄像头列表已变化，请重新选择权限。");
        RemoteUserStore next=users.update(id,username,password,enabled,channelIds);next.save(root);users=next;revokeUser(id);saveAuthentication();audit(id==null||id.isBlank()?"user-created":"user-updated",username,null);
    }
    public synchronized void removeUser(String id)throws IOException{
        if(closed)throw new IOException("远程服务已关闭。");RemoteUserStore.User removed=users.find(id);RemoteUserStore next=users.remove(id);next.save(root);users=next;revokeUser(id);saveAuthentication();audit("user-deleted",removed.username(),null);
    }
    private void revokeUser(String id){if(id!=null&&!id.isBlank()){for(Session s:sessions.values())if(s.userId.equals(id))revokeSession(s,null);refreshGrants.values().removeIf(g->g.userId.equals(id));}attempts.clear();}
    private void revokeSession(Session session,HttpExchange except){sessions.remove(session.tokenHash,session);for(HttpExchange request:session.requests)if(request!=except)request.close();}
    private void revokeAccess(){accessGeneration++;for(Session session:sessions.values())revokeSession(session,null);sessions.clear();attempts.clear();}
    public synchronized void start() throws IOException { if(configLoadFailed){try{listener.accept(state);}catch(RuntimeException ignored){}return;} apply(settings); }
    public synchronized void apply(RemoteAccessSettings next) throws IOException {
        Objects.requireNonNull(next); if(closed)throw new IOException("远程服务已关闭。");
        if(next.apiEnabled()&&!next.apiConfigured()&&!users.hasEnabledUsers())throw new IllegalArgumentException("启用 App API 前请创建并启用用户，或设置管理员密钥。");
        if(next.webConfigured()&&users.users().stream().anyMatch(u->u.username().equalsIgnoreCase(next.webUsername())))throw new IllegalArgumentException("Web 管理员用户名与 App 用户重复，请使用其他用户名。");
        HttpServer newWeb=null,newApi=null;
        try {
            if(next.webEnabled()||next.apiEnabled())ensureSecurity();
            if(next.webEnabled()&&webServer==null)newWeb=prepare(webPort,false);
            if(next.apiEnabled()&&apiServer==null)newApi=prepare(apiPort,true);
            next.save(root);
        } catch(IOException failure) { discardPrepared(newWeb);discardPrepared(newApi);state=new State(webServer!=null,apiServer!=null,"加密服务未能启动，请检查端口、安全数据和目录权限");try{listener.accept(state);}catch(RuntimeException ignored){}throw new IOException("无法启用加密远程服务：请检查端口、安全数据和目录权限。",failure); }
        RemoteAccessSettings before=settings;settings=next;configLoadFailed=false;
        boolean adminChanged=!before.webUsername().equals(next.webUsername())||!before.webPasswordHash().equals(next.webPasswordHash());
        boolean webDisabled=before.webEnabled()&&!next.webEnabled(),apiDisabled=before.apiEnabled()&&!next.apiEnabled();
        if(adminChanged||webDisabled||apiDisabled||!before.apiKeyHash().equals(next.apiKeyHash()))accessGeneration++;
        for(Session s:sessions.values())if((!s.api&&(webDisabled||adminChanged&&s.administrator))||(s.api&&apiDisabled))revokeSession(s,null);
        if(apiDisabled)refreshGrants.clear();saveAuthentication();
        if(!next.webEnabled()&&webServer!=null){webServer.stop(0);webServer=null;}
        if(!next.apiEnabled()&&apiServer!=null){apiServer.stop(0);apiServer=null;}
        if(newWeb!=null){webServer=newWeb;newWeb.start();}
        if(newApi!=null){apiServer=newApi;newApi.start();}
        updateState();
    }
    public boolean isRecordingInUse(Path path){return path!=null&&(media.isRecordingInUse(path)||downloads.containsKey(path.toAbsolutePath().normalize()));}
    private HttpServer prepare(int port,boolean api) throws IOException {
        // The JDK listener handles request timeouts; remote clients never alter the camera connection settings.
        if(System.getProperty("sun.net.httpserver.maxReqTime")==null)System.setProperty("sun.net.httpserver.maxReqTime","15");
        if(System.getProperty("sun.net.httpserver.maxReqHeaders")==null)System.setProperty("sun.net.httpserver.maxReqHeaders","64");
        if(System.getProperty("sun.net.httpserver.maxReqHeaderSize")==null)System.setProperty("sun.net.httpserver.maxReqHeaderSize","16384");
        if(System.getProperty("jdk.httpserver.maxConnections")==null)System.setProperty("jdk.httpserver.maxConnections","128");
        HttpsServer server=HttpsServer.create(new InetSocketAddress("0.0.0.0",port),64);
        server.setHttpsConfigurator(new HttpsConfigurator(tls.context()){@Override public void configure(HttpsParameters parameters){SSLParameters secure=getSSLContext().getDefaultSSLParameters();secure.setProtocols(new String[]{"TLSv1.3","TLSv1.2"});parameters.setSSLParameters(secure);}});
        server.setExecutor(executor); server.createContext("/",exchange->handle(exchange,api)); return server;
    }
    private static void discardPrepared(HttpServer server){if(server==null)return; // JDK stop() before start() leaves the bound socket open.
        try{server.start();}finally{server.stop(0);}
    }
    private void updateState(){state=new State(webServer!=null,apiServer!=null,webServer!=null||apiServer!=null?"远程服务运行中 · HTTPS 加密":"远程服务未启用");try{listener.accept(state);}catch(RuntimeException ignored){}}
    private synchronized void cleanup(){long now=System.currentTimeMillis();for(Session s:sessions.values())if(s.expired(now))revokeSession(s,null);boolean removed=refreshGrants.values().removeIf(g->g.expires<=now||!constant(g.stamp,credentialStamp(g.userId)));if(removed)try{saveAuthentication();}catch(IOException ignored){}attempts.entrySet().removeIf(e->now-e.getValue().window>180_000);try{Set<String> ids=new HashSet<>();for(CameraInfo camera:backend.cameras())ids.add(camera.id());jpegFrames.keySet().removeIf(id->!ids.contains(id));}catch(RuntimeException ignored){}}
    private void handle(HttpExchange x,boolean api){
        Session tracked=null;
        if(!requests.tryAcquire()){try{error(x,429,"request_limit","请求过多，请稍后再试。");}catch(IOException ignored){}finally{x.close();}return;}
        try {
            x.getResponseHeaders().set("Cache-Control","no-store, max-age=0"); x.getResponseHeaders().set("Pragma","no-cache");
            x.getResponseHeaders().set("X-Content-Type-Options","nosniff"); x.getResponseHeaders().set("Referrer-Policy","no-referrer");
            x.getResponseHeaders().set("X-Frame-Options","DENY");
            x.getResponseHeaders().set("Strict-Transport-Security","max-age=31536000");
            x.getResponseHeaders().set("Permissions-Policy","camera=(), microphone=(), geolocation=()");
            String path=x.getRequestURI().getPath();
            if(!api&&(path.equals("/")||path.equals("/index.html")||path.equals("/app.js")||path.equals("/style.css")||path.equals("/recorder.svg"))){staticFile(x,path);return;}
            if(!path.startsWith("/api/v1/")){error(x,404,"not_found","页面不存在。");return;}
            if(!api&&path.equals("/api/v1/session")){sessionEndpoint(x);return;}
            if(api&&path.equals("/api/v1/auth/login")){appLogin(x);return;}
            if(api&&path.equals("/api/v1/auth/refresh")){appRefresh(x);return;}
            if(api&&path.equals("/api/v1/auth/logout")){String auth=x.getRequestHeaders().getFirst("Authorization");Session logoutSession=findAppSession(auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):null);appLogout(x,logoutSession);return;}
            Session session=null;long generation=accessGeneration;
            if(api){String auth=x.getRequestHeaders().getFirst("Authorization");String token=auth!=null&&auth.length()>=7&&auth.regionMatches(true,0,"Bearer ",0,7)?auth.substring(7):null;session=findAppSession(token);if(!settings.apiEnabled()||(session==null&&!settings.verifyApiKey(token))){x.getResponseHeaders().set("WWW-Authenticate","Bearer realm=\"Recorder API\"");error(x,401,"unauthorized","登录已失效或 API 密钥无效，请重新登录。");return;}}
            else {session=findSession(x);if(session==null){error(x,401,"unauthorized","请先登录录像机。");return;} if(!sameOrigin(x)){error(x,403,"origin","请求来源无效。");return;}}
            if(session!=null){tracked=session;session.requests.add(x);checkAccess(generation,session);}
            if(api&&path.equals("/api/v1/auth/me")){requireGet(x);json(x,200,map("username",session==null?"管理员":session.username,"user",userJson(session),"expiresAt",session==null?null:Instant.ofEpochMilli(session.expires).toString()));return;}
            if(!x.getRequestMethod().equals("GET")&&!x.getRequestMethod().equals("HEAD")){error(x,405,"read_only","远程服务仅提供查看功能。");return;}
            if(path.equals("/api/v1/server")){requireGet(x);ServerSnapshot s=backend.serverSnapshot();json(x,200,map("name","录像机","apiVersion","1","serverTime",Instant.now().toString(),"timezone",ZoneId.systemDefault().getId(),"maxBytes",s.maxBytes(),"usedBytes",s.usedBytes(),"keepDays",s.keepDays(),"segmentMinutes",s.segmentMinutes(),"capabilities",List.of("live-jpeg","live-mjpeg","recordings","person-events","mp4-playback","original-range","user-auth","camera-permissions","tls","refresh-auth","active-recordings","incremental-person-events","recording-calendar","recording-bounds")));return;}
            if(path.equals("/api/v1/cameras")){requireGet(x);List<Object> cameras=new ArrayList<>();for(CameraInfo c:backend.cameras()){if(!allowed(session,c.id()))continue;cameras.add(map("id",c.id(),"name",c.name(),"number",c.number(),"online",c.online(),"recording",c.recording(),"width",c.width(),"height",c.height(),"liveUrl","/api/v1/cameras/"+encode(c.id())+"/live.mjpg"));}json(x,200,map("cameras",cameras));return;}
            if(path.equals("/api/v1/recordings")){requireGet(x);recordings(x,session);return;}
            if(path.equals("/api/v1/recording-days")){requireGet(x);recordingDays(x,session,generation);return;}
            if(path.equals("/api/v1/recording-bounds")){requireGet(x);recordingBounds(x,session,generation);return;}
            String[] p=path.split("/",-1);
            if(p.length==6&&p[3].equals("cameras")&&(p[5].equals("live.jpg")||p[5].equals("live.mjpg"))){requireGet(x);live(x,p[4],p[5].equals("live.mjpg"),api,session,generation);return;}
            if(p.length==6&&p[3].equals("recordings")&&(p[5].equals("media")||p[5].equals("download"))){RemoteRecording r=lookup(p[4],session);if(r==null||r.durationSeconds()<=0||!Files.isRegularFile(r.path())){error(x,404,"recording_unavailable","录像暂无可播放数据或已清理。");return;}if(p[5].equals("media")){requireGet(x);playback(x,r,generation,session);}else download(x,r,generation,session);return;}
            error(x,404,"not_found","接口不存在。");
        }catch(HttpFailure failure){try{error(x,failure.code,failure.type,failure.getMessage());}catch(IOException ignored){}}
        catch(IllegalArgumentException failure){try{error(x,400,"invalid_request","参数格式无效，请检查时间区间。 ");}catch(IOException ignored){}}
        catch(Exception failure){try{error(x,503,"temporarily_unavailable","服务暂时不可用，请稍后重试。");}catch(IOException ignored){}}
        finally{if(tracked!=null)tracked.requests.remove(x);x.close();requests.release();}
    }
    private void staticFile(HttpExchange x,String path)throws IOException{
        requireGet(x);String file=path.equals("/")?"index.html":path.substring(1);String type=switch(file){case "app.js"->"text/javascript; charset=utf-8";case "style.css"->"text/css; charset=utf-8";case "recorder.svg"->"image/svg+xml";default->"text/html; charset=utf-8";};
        x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; media-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        try(InputStream input=RemoteAccessService.class.getResourceAsStream("/cn/homewatch/web/"+file)){if(input==null){error(x,404,"not_found","Web 页面尚未安装。");return;}byte[] bytes=input.readAllBytes();x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);}
    }
    private void sessionEndpoint(HttpExchange x)throws IOException{
        if(!sameOrigin(x)){error(x,403,"origin","请求来源无效。");return;}
        switch(x.getRequestMethod()){
            case "GET"->{Session s=findSession(x);if(s==null){json(x,401,map("authenticated",false));return;}json(x,200,webSessionJson(s));}
            case "POST"->{
                if(!settings.webEnabled()){error(x,503,"disabled","Web 服务尚未启用。");return;}
                if(!String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).toLowerCase(Locale.ROOT).startsWith("application/json")){error(x,415,"content_type","请使用 JSON 登录请求。");return;}
                String ip=x.getRemoteAddress().getAddress().getHostAddress();cleanup();if(attempts.size()>512&&!attempts.containsKey(ip)){error(x,429,"rate_limit","请求过多，请稍后再试。");return;}
                Attempts count=attempts.computeIfAbsent(ip,k->new Attempts());synchronized(count){long now=System.currentTimeMillis();if(now-count.window>60_000){count.window=now;count.failures=0;}if(count.failures>=8){x.getResponseHeaders().set("Retry-After","60");error(x,429,"rate_limit","登录失败次数过多，请稍后再试。");return;}count.failures++;}
                Map<String,Object> body=RemoteJson.object(readBody(x));String username=string(body,"username",64),password=string(body,"password",256);
                if(!passwords.tryAcquire()){error(x,429,"rate_limit","正在处理其他登录，请稍后再试。");return;}
                RemoteAccessSettings authSettings=settings;RemoteUserStore authUsers=users;long revision=accessGeneration;
                RemoteUserStore.User user=authUsers.named(username);
                boolean valid;boolean administrator=authSettings.webUsername().equals(username);
                try{valid=administrator?authSettings.verifyPassword(password):user==null?verifyUnknownPassword(password):user.verify(password);}finally{passwords.release();}
                if(!valid){audit("web-login-failed",username,x);error(x,401,"invalid_credentials","用户名或密码错误。");return;}attempts.remove(ip);cleanup();Session s;String token;
                synchronized(this){ // Do not mint an old-password session after a concurrent desktop credential change.
                    if(settings!=authSettings||users!=authUsers||revision!=accessGeneration||closed){error(x,401,"credentials_changed","登录设置已更新，请重新登录。");return;}
                    if(sessions.size()>=128){error(x,429,"sessions_full","同时登录数量已达到上限。");return;}
                    Object remember=body.getOrDefault("remember",Boolean.TRUE);if(!(remember instanceof Boolean))throw new IllegalArgumentException();
                    Session old=findSession(x);if(old!=null)revokeSession(old,x);token=randomToken();s=new Session(token,randomToken(),false,administrator?null:user,username,(Boolean)remember,"",credentialStamp(administrator?"administrator":user.id()));sessions.put(s.tokenHash,s);saveAuthentication();
                }
                audit("web-login",s.username,x);x.getResponseHeaders().set("Set-Cookie","NVR_SESSION="+token+"; Path=/; Secure; HttpOnly; SameSite=Strict"+(s.remember?"; Max-Age=2592000":""));json(x,200,webSessionJson(s));
            }
            case "DELETE"->{Session s=findSession(x);if(s==null){error(x,401,"unauthorized","请先登录。");return;}String csrf=x.getRequestHeaders().getFirst("X-CSRF-Token");if(csrf==null||!constant(csrf,s.csrf)){error(x,403,"csrf","登录状态已变化，请刷新页面。");return;}revokeSession(s,x);saveAuthentication();audit("web-logout",s.username,x);x.getResponseHeaders().set("Set-Cookie","NVR_SESSION=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0");json(x,200,map("authenticated",false));}
            default->error(x,405,"method","请求方式无效。");
        }
    }
    private Map<String,Object> webSessionJson(Session s){return map("authenticated",true,"username",s.username,"csrfToken",s.csrf,"user",userJson(s),"remember",s.remember,"expiresAt",Instant.ofEpochMilli(s.expires).toString());}
    private Session findSession(HttpExchange x){String cookie=x.getRequestHeaders().getFirst("Cookie");if(cookie==null||cookie.length()>4096)return null;for(String pair:cookie.split(";")){String[] parts=pair.strip().split("=",2);if(parts.length==2&&parts[0].equals("NVR_SESSION")){if(!parts[1].matches("[A-Za-z0-9_-]{43}"))return null;Session s=sessions.get(RemoteAccessSettings.digestKey(parts[1]));if(s!=null){if(s.api)return null;long now=System.currentTimeMillis();if(s.expired(now)||!constant(s.stamp,credentialStamp(s.userId))){revokeSession(s,null);return null;}s.used=now;}return s;}}return null;}
    private Session findAppSession(String token){if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))return null;Session s=sessions.get(RemoteAccessSettings.digestKey(token));if(s==null||!s.api)return null;long now=System.currentTimeMillis();if(s.expired(now)||!constant(s.stamp,credentialStamp(s.userId))){revokeSession(s,null);return null;}s.used=now;return s;}
    private void appLogin(HttpExchange x)throws IOException{
        if(!x.getRequestMethod().equals("POST")){error(x,405,"method","请使用 POST 请求。");return;}
        if(!settings.apiEnabled()){error(x,503,"disabled","App API 服务尚未启用。");return;}
        if(!String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).toLowerCase(Locale.ROOT).startsWith("application/json")){error(x,415,"content_type","请使用 JSON 登录请求。");return;}
        String ip=x.getRemoteAddress().getAddress().getHostAddress();cleanup();
        if(attempts.size()>512&&!attempts.containsKey(ip)){error(x,429,"rate_limit","请求过多，请稍后再试。");return;}
        Attempts count=attempts.computeIfAbsent(ip,k->new Attempts());synchronized(count){long now=System.currentTimeMillis();if(now-count.window>60_000){count.window=now;count.failures=0;}if(count.failures>=8){x.getResponseHeaders().set("Retry-After","60");error(x,429,"rate_limit","登录失败次数过多，请稍后再试。");return;}count.failures++;}
        Map<String,Object> body=RemoteJson.object(readBody(x));String username=string(body,"username",64),password=string(body,"password",256);
        if(!passwords.tryAcquire()){error(x,429,"rate_limit","正在处理其他登录，请稍后再试。");return;}
        RemoteUserStore authUsers=users;long revision=accessGeneration;RemoteUserStore.User user=authUsers.named(username);boolean valid;
        try{valid=user==null?verifyUnknownPassword(password):user.verify(password);}finally{passwords.release();}
        if(!valid){audit("app-login-failed",username,x);error(x,401,"invalid_credentials","用户名或密码错误，或用户已停用。");return;}
        Session s;String refresh;synchronized(this){
            if(users!=authUsers||revision!=accessGeneration||closed||!settings.apiEnabled()){error(x,401,"credentials_changed","登录设置已更新，请重新登录。");return;}
            cleanup();if(sessions.size()>=128){error(x,429,"sessions_full","同时登录数量已达到上限。");return;}
            if(currentFamilies()>=128){error(x,429,"sessions_full","已保存的手机登录数量达到上限，请先退出不用的账号。");return;}
            String family=randomToken();refresh=randomToken();RefreshGrant grant=new RefreshGrant(RemoteAccessSettings.digestKey(refresh),family,user.id(),credentialStamp(user.id()),System.currentTimeMillis()+REMEMBER_MS,false);
            refreshGrants.put(grant.hash,grant);s=new Session(randomToken(),"",true,user,user.username(),true,family,grant.stamp);sessions.put(s.tokenHash,s);attempts.remove(ip);saveAuthentication();
        }
        audit("app-login",s.username,x);json(x,200,appSessionJson(s,refresh));
    }
    private boolean allowed(Session session,String channelId){return session==null||session.administrator||session.channelIds.contains(channelId);}
    private Map<String,Object> appSessionJson(Session s,String refresh){return map("accessToken",s.token,"token",s.token,"refreshToken",refresh,"tokenType","Bearer","username",s.username,"expiresAt",Instant.ofEpochMilli(s.expires).toString(),"refreshExpiresAt",Instant.ofEpochMilli(System.currentTimeMillis()+REMEMBER_MS).toString(),"user",userJson(s));}
    private synchronized void appRefresh(HttpExchange x)throws IOException{
        if(!x.getRequestMethod().equals("POST")){error(x,405,"method","请使用 POST 请求。");return;}
        if(!settings.apiEnabled()){error(x,503,"disabled","App API 服务尚未启用。");return;}
        if(!String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).toLowerCase(Locale.ROOT).startsWith("application/json")){error(x,415,"content_type","请使用 JSON 续期请求。");return;}
        String supplied=string(RemoteJson.object(readBody(x)),"refreshToken",256);
        if(!supplied.matches("[A-Za-z0-9_-]{43}")){error(x,401,"invalid_refresh","保存的登录已失效，请重新登录。");return;}
        RefreshGrant grant=refreshGrants.get(RemoteAccessSettings.digestKey(supplied));long now=System.currentTimeMillis();
        if(grant==null){error(x,401,"invalid_refresh","保存的登录已失效，请重新登录。");return;}
        if(grant.spent||grant.expires<=now||!constant(grant.stamp,credentialStamp(grant.userId))){RemoteUserStore.User owner=users.find(grant.userId);audit(grant.spent?"app-refresh-replay":"app-refresh-rejected",owner==null?"":owner.username(),x);revokeFamily(grant.family,null);saveAuthentication();error(x,401,"invalid_refresh","登录或权限已变化，请重新登录。");return;}
        RemoteUserStore.User user=users.find(grant.userId);cleanup();
        Session s=sessions.values().stream().filter(v->v.api&&v.family.equals(grant.family)&&v.expires-now>30_000).findFirst().orElse(null);
        if(s==null){if(sessions.size()>=128){error(x,429,"sessions_full","同时登录数量已达到上限。");return;}s=new Session(randomToken(),"",true,user,user.username(),true,grant.family,grant.stamp);sessions.put(s.tokenHash,s);}
        // Keep one spent token per device for replay detection, and one usable rotating token.
        refreshGrants.values().removeIf(v->v.family.equals(grant.family)&&v.spent);
        refreshGrants.put(grant.hash,new RefreshGrant(grant.hash,grant.family,grant.userId,grant.stamp,now+REMEMBER_MS,true));
        String token=randomToken();RefreshGrant next=new RefreshGrant(RemoteAccessSettings.digestKey(token),grant.family,grant.userId,grant.stamp,now+REMEMBER_MS,false);
        refreshGrants.put(next.hash,next);saveAuthentication();audit("app-refresh",s.username,x);json(x,200,appSessionJson(s,token));
    }
    private synchronized void appLogout(HttpExchange x,Session session)throws IOException{
        if(!x.getRequestMethod().equals("POST")){error(x,405,"method","请使用 POST 请求。");return;}
        String raw=readBody(x),refresh=null;
        if(!raw.isBlank()){if(!String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).toLowerCase(Locale.ROOT).startsWith("application/json")){error(x,415,"content_type","请使用 JSON 退出请求。");return;}refresh=string(RemoteJson.object(raw),"refreshToken",256);}
        RefreshGrant grant=refresh==null?null:refreshGrants.get(RemoteAccessSettings.digestKey(refresh));
        if(session==null&&grant==null){String auth=x.getRequestHeaders().getFirst("Authorization");if(auth!=null&&auth.startsWith("Bearer ")&&settings.verifyApiKey(auth.substring(7))){json(x,200,map("authenticated",false));return;}error(x,401,"unauthorized","登录已结束。");return;}
        if(session!=null&&grant!=null&&!session.family.equals(grant.family)){error(x,403,"session_mismatch","退出请求与当前手机不匹配。");return;}
        RemoteUserStore.User owner=grant==null?null:users.find(grant.userId);audit("app-logout",session!=null?session.username:owner==null?"":owner.username(),x);revokeFamily(session!=null?session.family:grant.family,x);saveAuthentication();json(x,200,map("authenticated",false));
    }
    private void revokeFamily(String family,HttpExchange except){refreshGrants.values().removeIf(g->g.family.equals(family));for(Session s:sessions.values())if(s.api&&s.family.equals(family))revokeSession(s,except);}
    private long currentFamilies(){return refreshGrants.values().stream().filter(g->!g.spent&&g.expires>System.currentTimeMillis()).map(g->g.family).distinct().count();}
    private String credentialStamp(String id){
        if("administrator".equals(id))return RemoteAccessSettings.digestKey(settings.webUsername()+"\n"+settings.webPasswordHash());
        RemoteUserStore.User user=users.find(id);if(user==null||!user.enabled())return "";
        return RemoteAccessSettings.digestKey(user.id()+"\n"+user.username()+"\n"+user.passwordHash()+"\n"+String.join(",",new TreeSet<>(user.channelIds())));
    }
    private synchronized void ensureSecurity()throws IOException{
        if(tls!=null)return;RemotePrivateStorage storage=new RemotePrivateStorage(root);RemoteTls secure=new RemoteTls(storage);
        Properties values=storage.readProperties("remembered-sessions.bin");long now=System.currentTimeMillis();
        try{
            for(String hash:values.getProperty("web.order","").split(",")){
                if(!hash.matches("sha256:[0-9a-f]{64}"))continue;String p="web."+hash+".";String id=values.getProperty(p+"userId","");
                long created=Long.parseLong(values.getProperty(p+"created")),expires=Long.parseLong(values.getProperty(p+"expires"));String stamp=values.getProperty(p+"stamp","");
                if(expires<=now||expires-created>REMEMBER_MS||created>now+60_000||!constant(stamp,credentialStamp(id)))continue;
                RemoteUserStore.User user="administrator".equals(id)?null:users.find(id);if(user==null&&!"administrator".equals(id))continue;
                if(sessions.size()>=128)break;Session restored=new Session(hash,values.getProperty(p+"csrf",""),user,values.getProperty(p+"username",""),created,expires,stamp);sessions.put(hash,restored);
            }
            for(String hash:values.getProperty("refresh.order","").split(",")){
                if(!hash.matches("sha256:[0-9a-f]{64}"))continue;String p="refresh."+hash+".";String userId=values.getProperty(p+"userId","");String stamp=values.getProperty(p+"stamp","");long expires=Long.parseLong(values.getProperty(p+"expires"));String family=values.getProperty(p+"family","");
                if(expires<=now||expires>now+REMEMBER_MS+60_000||!family.matches("[A-Za-z0-9_-]{43}")||!constant(stamp,credentialStamp(userId)))continue;
                if(refreshGrants.size()>=256)break;refreshGrants.put(hash,new RefreshGrant(hash,family,userId,stamp,expires,Boolean.parseBoolean(values.getProperty(p+"spent","false"))));
            }
        }catch(RuntimeException invalid){sessions.clear();refreshGrants.clear();throw new IOException("保存的远程登录记录无效，请恢复私人备份。",invalid);}
        privateStorage=storage;tls=secure;audit=new RemoteAuditLog(storage.directory());
    }
    private synchronized void saveAuthentication()throws IOException{
        if(privateStorage==null)return;Properties values=new Properties();values.setProperty("version","1");List<String> web=new ArrayList<>(),refresh=new ArrayList<>();long now=System.currentTimeMillis();
        for(Session s:sessions.values())if(!s.api&&s.remember&&!s.expired(now)&&constant(s.stamp,credentialStamp(s.userId))){web.add(s.tokenHash);String p="web."+s.tokenHash+".";values.setProperty(p+"csrf",s.csrf);values.setProperty(p+"userId",s.userId);values.setProperty(p+"username",s.username);values.setProperty(p+"created",Long.toString(s.created));values.setProperty(p+"expires",Long.toString(s.expires));values.setProperty(p+"stamp",s.stamp);}
        for(RefreshGrant g:refreshGrants.values())if(g.expires>now&&constant(g.stamp,credentialStamp(g.userId))){refresh.add(g.hash);String p="refresh."+g.hash+".";values.setProperty(p+"family",g.family);values.setProperty(p+"userId",g.userId);values.setProperty(p+"stamp",g.stamp);values.setProperty(p+"expires",Long.toString(g.expires));values.setProperty(p+"spent",Boolean.toString(g.spent));}
        values.setProperty("web.order",String.join(",",web));values.setProperty("refresh.order",String.join(",",refresh));privateStorage.writeProperties("remembered-sessions.bin",values);
    }
    private static boolean verifyUnknownPassword(String password){RemoteAccessSettings.verifyPasswordHash(DUMMY_PASSWORD_HASH,password);return false;}
    private Map<String,Object> userJson(Session session){
        boolean admin=session==null||session.administrator;
        List<String> ids=backend.cameras().stream().filter(c->allowed(session,c.id())).map(CameraInfo::id).toList();
        return map("id",admin?"administrator":session.userId,"username",session==null?"管理员":session.username,"administrator",admin,"channelIds",ids);
    }
    private boolean sameOrigin(HttpExchange x){String site=x.getRequestHeaders().getFirst("Sec-Fetch-Site");if("cross-site".equalsIgnoreCase(site))return false;String origin=x.getRequestHeaders().getFirst("Origin");if(origin==null)return true;try{URI uri=URI.create(origin);String host=x.getRequestHeaders().getFirst("Host");return "https".equals(uri.getScheme())&&uri.getRawAuthority()!=null&&uri.getRawAuthority().equalsIgnoreCase(host)&&uri.getRawPath().isEmpty();}catch(RuntimeException invalid){return false;}}
    private void recordings(HttpExchange x,Session session)throws IOException{
        Map<String,String> q=query(x);String channel=q.get("channelId");if(channel!=null&&(camera(channel)==null||!allowed(session,channel)))throw new HttpFailure(404,"camera_not_found","设备不存在。");
        Instant now=Instant.now();Instant from=q.containsKey("from")?Instant.parse(q.get("from")):LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();Instant to=q.containsKey("to")?Instant.parse(q.get("to")):now.plusSeconds(1);
        if(!to.isAfter(from)||Duration.between(from,to).compareTo(Duration.ofDays(31))>0)throw new HttpFailure(400,"range","查询区间请设置在 31 天以内。");
        List<Object> items=new ArrayList<>();for(RemoteRecording r:backend.recordings(channel,from,to)){if(!allowed(session,r.channelId())||camera(r.channelId())==null)continue;if(items.size()>=20_000)throw new HttpFailure(413,"too_many_recordings","录像数量较多，请缩小查询区间。");String id=recordingId(r);List<Object> people=new ArrayList<>();for(PersonInterval person:r.persons())people.add(map("startSeconds",person.startSeconds(),"endSeconds",person.endSeconds()));items.add(map("id",id,"channelId",r.channelId(),"start",r.start().toString(),"end",r.start().plusMillis(Math.round(Math.max(0,r.durationSeconds())*1000)).toString(),"durationSeconds",r.durationSeconds(),"bytes",r.bytes(),"active",r.active(),"persons",people,"personStatus",Objects.requireNonNullElse(r.personStatus(),"pending"),"mediaUrl","/api/v1/recordings/"+id+"/media","downloadUrl","/api/v1/recordings/"+id+"/download"));}json(x,200,map("from",from.toString(),"to",to.toString(),"recordings",items));
    }
    private String archiveChannel(Map<String,String> q,Session session)throws HttpFailure{
        String channel=q.get("channelId"),alias=q.get("channel");
        if(channel!=null&&alias!=null&&!channel.equals(alias))throw new HttpFailure(400,"channel","摄像头参数不一致。");
        if(channel==null)channel=alias;
        if(channel==null||channel.isBlank())throw new HttpFailure(400,"channel","请选择摄像头。");
        // An unavailable and an unauthorized device have the same response, including calendar requests.
        if(camera(channel)==null||!allowed(session,channel))throw new HttpFailure(404,"camera_not_found","设备不存在。");
        return channel;
    }
    private void recordingDays(HttpExchange x,Session session,long generation)throws IOException{
        Map<String,String> q=query(x);String channel=archiveChannel(q,session),raw=q.get("month");
        if(raw==null||!raw.matches("[0-9]{4}-(0[1-9]|1[0-2])"))throw new HttpFailure(400,"month","月份格式应为 YYYY-MM。");
        YearMonth month=YearMonth.parse(raw);
        if(month.getYear()<1970||month.getYear()>9998)throw new HttpFailure(400,"month","请选择有效月份。");
        ZoneId zone=ZoneId.systemDefault();Instant from=month.atDay(1).atStartOfDay(zone).toInstant(),to=month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        Map<LocalDate,ArchiveDay> days=new TreeMap<>();int count=0;
        for(RemoteRecording r:backend.recordings(channel,from,to)){
            if(!channel.equals(r.channelId())||!allowed(session,r.channelId()))continue;
            if(++count>20_000)throw new HttpFailure(413,"too_many_recordings","本月录像数量较多，请在时间区间查询中缩小范围。");
            Instant end=archiveEnd(r);if(end==null||!end.isAfter(from)||!r.start().isBefore(to))continue;
            Instant start=maxInstant(r.start(),from),finish=minInstant(end,to);
            // The end is exclusive: a recording ending at midnight does not mark the following day.
            LocalDate last=finish.minusNanos(1).atZone(zone).toLocalDate();
            for(LocalDate day=start.atZone(zone).toLocalDate();!day.isAfter(last);day=day.plusDays(1)){
                Instant dayStart=day.atStartOfDay(zone).toInstant(),dayEnd=day.plusDays(1).atStartOfDay(zone).toInstant();
                Instant begin=maxInstant(start,dayStart),tail=minInstant(finish,dayEnd);
                ArchiveDay summary=days.computeIfAbsent(day,unused->new ArchiveDay());summary.recordings++;summary.recorded.add(new ArchiveRange(begin,tail));
                if(r.active()&&day.equals(end.minusNanos(1).atZone(zone).toLocalDate()))summary.active=true;
                for(PersonInterval person:r.persons()){
                    Instant personStart=archiveOffset(r.start(),person.startSeconds()),personEnd=archiveOffset(r.start(),person.endSeconds());
                    if(personStart==null||personEnd==null)continue;
                    Instant eventStart=maxInstant(begin,personStart),eventEnd=minInstant(tail,personEnd);
                    if(eventEnd.isAfter(eventStart))summary.persons.add(new ArchiveRange(eventStart,eventEnd));
                }
            }
        }
        List<Object> result=new ArrayList<>();
        for(var entry:days.entrySet()){
            ArchiveDay summary=entry.getValue();Instant first=summary.recorded.stream().map(ArchiveRange::from).min(Instant::compareTo).orElseThrow(),last=summary.recorded.stream().map(ArchiveRange::to).max(Instant::compareTo).orElseThrow();
            result.add(map("date",entry.getKey().toString(),"hasRecording",true,"hasPersons",!summary.persons.isEmpty(),"active",summary.active,"recordingCount",summary.recordings,"recordedSeconds",archiveSeconds(summary.recorded),"personSeconds",archiveSeconds(summary.persons),"first",first.toString(),"last",last.toString()));
        }
        checkAccess(generation,session);json(x,200,map("channelId",channel,"month",raw,"timezone",zone.getId(),"days",result));
    }
    private void recordingBounds(HttpExchange x,Session session,long generation)throws IOException{
        String channel=archiveChannel(query(x),session);Instant first=null,last=null;int count=0;
        for(RemoteRecording r:backend.recordings(channel,Instant.EPOCH,Instant.MAX)){
            if(!channel.equals(r.channelId())||!allowed(session,r.channelId()))continue;
            if(++count>100_000)throw new HttpFailure(413,"too_many_recordings","录像数量较多，请按月份查询。");
            Instant end=archiveEnd(r);if(end==null)continue;
            first=first==null?r.start():minInstant(first,r.start());last=last==null?end:maxInstant(last,end);
        }
        checkAccess(generation,session);json(x,200,map("channelId",channel,"timezone",ZoneId.systemDefault().getId(),"firstRecording",first==null?null:first.toString(),"lastRecording",last==null?null:last.toString()));
    }
    private static Instant archiveEnd(RemoteRecording r){
        if(r.start()==null||r.path()==null||!Files.isRegularFile(r.path())||!Double.isFinite(r.durationSeconds())||r.durationSeconds()<=0)return null;
        Instant end=archiveOffset(r.start(),r.durationSeconds());return end!=null&&end.isAfter(r.start())?end:null;
    }
    private static Instant archiveOffset(Instant start,double seconds){
        if(!Double.isFinite(seconds)||seconds<0||seconds>Long.MAX_VALUE/1000.0)return null;
        try{return start.plusMillis(Math.round(seconds*1000));}catch(DateTimeException|ArithmeticException invalid){return null;}
    }
    private static Instant minInstant(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static Instant maxInstant(Instant a,Instant b){return a.isAfter(b)?a:b;}
    private record ArchiveRange(Instant from,Instant to){}
    private static final class ArchiveDay{int recordings;boolean active;final List<ArchiveRange> recorded=new ArrayList<>(),persons=new ArrayList<>();}
    private static double archiveSeconds(List<ArchiveRange> ranges){
        // Sum the union so overlapping recordings/events never count the same wall-clock second twice.
        List<ArchiveRange> ordered=new ArrayList<>(ranges);ordered.sort(Comparator.comparing(ArchiveRange::from));
        Instant first=null,last=null;long milliseconds=0;
        for(ArchiveRange range:ordered){if(first==null){first=range.from();last=range.to();}else if(!range.from().isAfter(last)){last=maxInstant(last,range.to());}else{milliseconds+=Duration.between(first,last).toMillis();first=range.from();last=range.to();}}
        if(first!=null)milliseconds+=Duration.between(first,last).toMillis();return milliseconds/1000.0;
    }
    private RemoteRecording lookup(String id,Session session){if(!id.matches("[0-9a-f]{32}"))return null;for(RemoteRecording r:backend.recordings(null,Instant.EPOCH,Instant.now().plus(Duration.ofDays(1))))if(allowed(session,r.channelId())&&camera(r.channelId())!=null&&recordingId(r).equals(id))return r;return null;}
    private static String recordingId(RemoteRecording r){return RemoteAccessSettings.digestKey(r.channelId()+"\n"+r.path().toAbsolutePath().normalize()).substring(7,39);}
    private CameraInfo camera(String id){for(CameraInfo c:backend.cameras())if(c.id().equals(id))return c;return null;}
    private void live(HttpExchange x,String id,boolean multipart,boolean api,Session session,long generation)throws Exception{
        if(camera(id)==null||!allowed(session,id))throw new HttpFailure(404,"camera_not_found","设备不存在。");checkAccess(generation,session);if(!streams.tryAcquire())throw new HttpFailure(429,"viewer_limit","同时查看数量已达到上限。");
        try(AutoCloseable demand=backend.acquireCamera(id)){
            BufferedImage image=null;long waitUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(image==null&&System.nanoTime()<waitUntil&&!closed){checkAccess(generation,session);image=backend.latestFrame(id);if(image==null)Thread.sleep(100);}if(image==null)throw new HttpFailure(503,"camera_offline","摄像头暂无画面，请稍后重试。");
            checkAccess(generation,session);if(!multipart){byte[] jpeg=jpegFrames.computeIfAbsent(id,k->new JpegFrame()).bytes(image);x.getResponseHeaders().set("Content-Type","image/jpeg");x.sendResponseHeaders(200,jpeg.length);x.getResponseBody().write(jpeg);return;}
            x.getResponseHeaders().set("Content-Type","multipart/x-mixed-replace; boundary=nvrframe");x.sendResponseHeaders(200,0);OutputStream out=x.getResponseBody();long timeout=System.nanoTime()+TimeUnit.MINUTES.toNanos(30);
            while(!closed&&generation==accessGeneration&&System.nanoTime()<timeout&&(api?settings.apiEnabled():settings.webEnabled())){checkAccess(generation,session);BufferedImage frame=backend.latestFrame(id);if(frame!=null){byte[] jpeg=jpegFrames.computeIfAbsent(id,k->new JpegFrame()).bytes(frame);out.write(("--nvrframe\r\nContent-Type: image/jpeg\r\nContent-Length: "+jpeg.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(jpeg);out.write(new byte[]{13,10});out.flush();}Thread.sleep(200);}
        }finally{streams.release();}
    }
    private byte[] jpeg(BufferedImage original)throws IOException{int w=original.getWidth(),h=original.getHeight();double factor=Math.min(1,1280.0/w);BufferedImage image=new BufferedImage(Math.max(1,(int)(w*factor)),Math.max(1,(int)(h*factor)),BufferedImage.TYPE_3BYTE_BGR);Graphics2D g=image.createGraphics();try{g.drawImage(original,0,0,image.getWidth(),image.getHeight(),null);}finally{g.dispose();}ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(!ImageIO.write(image,"jpeg",bytes))throw new IOException("JPEG unavailable");return bytes.toByteArray();}
    private void playback(HttpExchange x,RemoteRecording r,long generation,Session session)throws IOException{
        Map<String,String> q=query(x);double offset=number(q,"offsetSeconds",0),duration=number(q,"durationSeconds",300);if(offset<0||offset>=r.durationSeconds()||duration<=0||duration>300)throw new HttpFailure(400,"playback_range","回放位置无效，每次回放最多 300 秒。");duration=Math.min(duration,r.durationSeconds()-offset);
        if(!streams.tryAcquire())throw new HttpFailure(429,"viewer_limit","同时查看数量已达到上限。");try(InputStream input=media.open(r,offset,duration)){byte[] buffer=new byte[64*1024];int n=input.read(buffer);if(n<0)throw new IOException("empty video");checkAccess(generation,session);x.getResponseHeaders().set("Content-Type","video/mp4");x.getResponseHeaders().set("X-Playback-Offset-Seconds",Double.toString(offset));x.getResponseHeaders().set("X-Playback-Duration-Seconds",Double.toString(duration));x.sendResponseHeaders(200,0);OutputStream output=x.getResponseBody();do{checkAccess(generation,session);output.write(buffer,0,n);output.flush();n=input.read(buffer);}while(n>=0);}finally{streams.release();}
    }
    private void download(HttpExchange x,RemoteRecording r,long generation,Session session)throws Exception{
        if(!streams.tryAcquire())throw new HttpFailure(429,"viewer_limit","同时查看数量已达到上限。");Path path=r.path().toAbsolutePath().normalize();downloads.computeIfAbsent(path,k->new AtomicInteger()).incrementAndGet();
        try(AutoCloseable protection=backend.acquireRecording(path);RandomAccessFile file=new RandomAccessFile(path.toFile(),"r")){
            long size=file.length(),start=0,end=size-1;String range=x.getRequestHeaders().getFirst("Range");int code=200;
            x.getResponseHeaders().set("Accept-Ranges","bytes");x.getResponseHeaders().set("Content-Type","video/x-matroska");x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+downloadName(r)+"\"");
            if(range!=null){if(!range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")){unsatisfied(x,size);return;}String[] parts=range.substring(6).split("-",-1);try{if(parts[0].isEmpty()){long suffix=Long.parseLong(parts[1]);if(suffix<=0){unsatisfied(x,size);return;}start=Math.max(0,size-suffix);}else{start=Long.parseLong(parts[0]);if(!parts[1].isEmpty())end=Math.min(end,Long.parseLong(parts[1]));}}catch(NumberFormatException invalid){unsatisfied(x,size);return;}if(start>=size||start>end){unsatisfied(x,size);return;}code=206;x.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+size);}
            checkAccess(generation,session);long count=Math.max(0,end-start+1);x.getResponseHeaders().set("Content-Length",Long.toString(count));if(x.getRequestMethod().equals("HEAD")){x.sendResponseHeaders(code,-1);return;}x.sendResponseHeaders(code,count==0?-1:count);file.seek(start);byte[] buffer=new byte[64*1024];OutputStream output=x.getResponseBody();while(count>0){checkAccess(generation,session);int n=file.read(buffer,0,(int)Math.min(buffer.length,count));if(n<0)break;output.write(buffer,0,n);count-=n;}
        }finally{downloads.computeIfPresent(path,(p,count)->count.decrementAndGet()<=0?null:count);streams.release();}
    }
    private String downloadName(RemoteRecording recording){
        CameraInfo device=camera(recording.channelId());int number=device==null?0:Math.max(0,device.number());
        String time=java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss",Locale.ROOT).withZone(ZoneId.systemDefault()).format(recording.start());
        // ASCII-only public metadata avoids header injection, device credentials and filesystem names.
        return String.format(Locale.ROOT,"CH%02d_%s_%s.mkv",number,time,recordingId(recording));
    }
    private void unsatisfied(HttpExchange x,long size)throws IOException{x.getResponseHeaders().set("Content-Range","bytes */"+size);error(x,416,"range_not_satisfiable","请求的录像范围无效。");}
    private static double number(Map<String,String> q,String key,double fallback){double n=q.containsKey(key)?Double.parseDouble(q.get(key)):fallback;if(!Double.isFinite(n))throw new IllegalArgumentException();return n;}
    private static String string(Map<String,Object> body,String name,int max){Object v=body.get(name);if(!(v instanceof String s)||s.length()>max)throw new IllegalArgumentException();return s;}
    private static String readBody(HttpExchange x)throws IOException{try(InputStream input=x.getRequestBody()){byte[] bytes=input.readNBytes(16_385);if(bytes.length>16_384)throw new HttpFailure(413,"body_limit","请求内容过大。");return new String(bytes,StandardCharsets.UTF_8);}}
    private static Map<String,String> query(HttpExchange x){Map<String,String> q=new HashMap<>();String raw=x.getRequestURI().getRawQuery();if(raw==null)return q;if(raw.length()>4096)throw new IllegalArgumentException();for(String part:raw.split("&")){String[] p=part.split("=",2);String key=URLDecoder.decode(p[0],StandardCharsets.UTF_8);if(q.containsKey(key))throw new IllegalArgumentException();q.put(key,p.length>1?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");}return q;}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    private static String randomToken(){byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private static boolean constant(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static Map<String,Object> map(Object... values){Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)map.put(values[i].toString(),values[i+1]);return map;}
    private static void requireGet(HttpExchange x)throws HttpFailure{if(!x.getRequestMethod().equals("GET"))throw new HttpFailure(405,"method","请使用 GET 请求。");}
    private static void json(HttpExchange x,int code,Object body)throws IOException{byte[] b=RemoteJson.encode(body).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");if(x.getRequestMethod().equals("HEAD")){x.getResponseHeaders().set("Content-Length",Integer.toString(b.length));x.sendResponseHeaders(code,-1);return;}x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);}
    private static void error(HttpExchange x,int code,String type,String message)throws IOException{json(x,code,map("error",type,"message",message));}
    private void checkGeneration(long expected)throws IOException{if(closed||expected!=accessGeneration)throw new IOException("登录或密钥已更新。");}
    private void checkAccess(long expected,Session session)throws IOException{checkGeneration(expected);if(session!=null){long now=System.currentTimeMillis();if(sessions.get(session.tokenHash)!=session||session.expired(now)||!constant(session.stamp,credentialStamp(session.userId)))throw new IOException("登录已结束。");session.used=now;}}
    @Override public synchronized void close(){if(closed)return;try{saveAuthentication();}catch(IOException ignored){}closed=true;accessGeneration++;if(webServer!=null)webServer.stop(0);if(apiServer!=null)apiServer.stop(0);webServer=null;apiServer=null;sessions.clear();refreshGrants.clear();jpegFrames.clear();media.close();maintenance.shutdownNow();executor.shutdownNow();updateState();}
    private final class JpegFrame{byte[] encoded;long created; synchronized byte[] bytes(BufferedImage frame)throws IOException{long now=System.nanoTime();if(encoded!=null&&now-created<TimeUnit.MILLISECONDS.toNanos(180))return encoded;encoded=jpeg(frame);created=now;return encoded;}}
    private static final class Session{
        final String token,tokenHash,csrf,userId,username,stamp,family;final boolean api,administrator,remember;final Set<String> channelIds;
        final Set<HttpExchange> requests=ConcurrentHashMap.newKeySet();final long created,expires;volatile long used;
        Session(String token,String csrf,boolean api,RemoteUserStore.User user,String username,boolean remember,String family,String stamp){this.token=token;this.tokenHash=RemoteAccessSettings.digestKey(token);this.csrf=csrf;this.api=api;this.administrator=user==null;this.userId=user==null?"administrator":user.id();this.username=username;this.channelIds=user==null?Set.of():user.channelIds();this.remember=remember;this.family=family;this.stamp=stamp;created=System.currentTimeMillis();used=created;expires=created+(api?ACCESS_MS:remember?REMEMBER_MS:43_200_000);}
        Session(String hash,String csrf,RemoteUserStore.User user,String username,long created,long expires,String stamp){token="";tokenHash=hash;this.csrf=csrf;api=false;administrator=user==null;userId=user==null?"administrator":user.id();this.username=username;channelIds=user==null?Set.of():user.channelIds();remember=true;family="";this.stamp=stamp;this.created=created;this.expires=expires;used=System.currentTimeMillis();}
        boolean expired(long now){return now>=expires||!api&&!remember&&now-used>1_800_000;}
    }
    private record RefreshGrant(String hash,String family,String userId,String stamp,long expires,boolean spent){}
    private static final class Attempts{long window=System.currentTimeMillis();int failures;}
    private static final class HttpFailure extends IOException{final int code;final String type;HttpFailure(int code,String type,String message){super(message);this.code=code;this.type=type;}}
}
