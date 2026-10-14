package cn.homewatch.recorder;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import java.util.HashMap;
import java.util.Map;

/** Two native players: next stream prepares without a surface, then reuses the same video surface. */
final class RecorderPlayerView extends FrameLayout implements SurfaceHolder.Callback {
    private final SurfaceView surface;
    private MediaPlayer current, standby;
    private Uri standbyUri;
    private Map<String,String> standbyHeaders;
    private boolean prepared, standbyPrepared, wantsPlay, surfaceReady;
    private int bufferedPercent;
    private MediaPlayer.OnPreparedListener onPrepared;
    private MediaPlayer.OnCompletionListener onCompletion;
    private MediaPlayer.OnErrorListener onError;

    RecorderPlayerView(Context context) {
        super(context); surface = new SurfaceView(context);
        addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)); surface.getHolder().addCallback(this);
    }
    void setOnPreparedListener(MediaPlayer.OnPreparedListener listener) { onPrepared=listener; }
    void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) { onCompletion=listener; }
    void setOnErrorListener(MediaPlayer.OnErrorListener listener) { onError=listener; }
    private MediaPlayer player(Uri uri, Map<String,String> headers) throws Exception {
        MediaPlayer p=new MediaPlayer();
        try {
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            p.setOnPreparedListener(mp -> {
                if(mp==standby){standbyPrepared=true;return;}
                if(mp!=current)return; prepared=true; fitVideo(mp.getVideoWidth(),mp.getVideoHeight());
                if(onPrepared!=null)onPrepared.onPrepared(mp);
            });
            p.setOnVideoSizeChangedListener((mp,w,h)->{if(mp==current)fitVideo(w,h);});
            p.setOnBufferingUpdateListener((mp,percent)->{if(mp==current)bufferedPercent=percent;});
            p.setOnCompletionListener(mp->{if(mp==current&&onCompletion!=null)onCompletion.onCompletion(mp);});
            p.setOnErrorListener((mp,what,extra)->{
                if(mp==standby){discardStandby();return true;}
                return mp!=current||onError==null||onError.onError(mp,what,extra);
            });
            p.setOnSeekCompleteListener(mp->{if(mp==current&&!wantsPlay){try{mp.pause();}catch(IllegalStateException ignored){}}});
            p.setDataSource(getContext(),uri,headers); return p;
        } catch(Exception e){p.release();throw e;}
    }
    void setVideoURI(Uri uri, Map<String,String> headers) {
        releaseCurrent(); wantsPlay=false; bufferedPercent=0;
        if(standby!=null&&uri.equals(standbyUri)&&headers.equals(standbyHeaders)) {
            current=standby;prepared=standbyPrepared;standby=null;standbyUri=null;standbyHeaders=null;standbyPrepared=false;
            attach(); if(prepared){fitVideo(current.getVideoWidth(),current.getVideoHeight());if(onPrepared!=null)onPrepared.onPrepared(current);} return;
        }
        discardStandby();
        try{current=player(uri,headers);attach();current.prepareAsync();}
        catch(Exception e){if(onError!=null)onError.onError(current,MediaPlayer.MEDIA_ERROR_UNKNOWN,0);releaseCurrent();}
    }
    void prepareNext(Uri uri, Map<String,String> headers) {
        if(standby!=null&&uri.equals(standbyUri)&&headers.equals(standbyHeaders))return;
        discardStandby();
        try{standbyUri=uri;standbyHeaders=new HashMap<>(headers);standby=player(uri,standbyHeaders);standby.prepareAsync();}
        catch(Exception ignored){discardStandby();}
    }
    boolean hasPreparedNext(){return standby!=null;}
    void discardStandby(){MediaPlayer old=standby;standby=null;standbyPrepared=false;standbyUri=null;standbyHeaders=null;if(old!=null)old.release();}
    private void attach(){if(current!=null&&surfaceReady)current.setDisplay(surface.getHolder());}
    private void releaseCurrent(){MediaPlayer old=current;current=null;prepared=false;if(old!=null)old.release();}
    void beginSwitch(){wantsPlay=false;bufferedPercent=0;releaseCurrent();}
    void stopPlayback(){wantsPlay=false;releaseCurrent();discardStandby();}
    void start(){wantsPlay=true;if(prepared&&current!=null&&surfaceReady)current.start();}
    void pause(){wantsPlay=false;if(prepared&&current!=null){try{current.pause();}catch(IllegalStateException ignored){}}}
    boolean isPlaying(){try{return prepared&&current!=null&&current.isPlaying();}catch(IllegalStateException e){return false;}}
    int getCurrentPosition(){try{return prepared&&current!=null?current.getCurrentPosition():0;}catch(IllegalStateException e){return 0;}}
    int getDuration(){try{return prepared&&current!=null?current.getDuration():0;}catch(IllegalStateException e){return 0;}}
    boolean seekBuffered(long position,boolean autoplay){
        if(!prepared||current==null||!PlaybackBufferPolicy.localSeek(getDuration(),bufferedPercent,position))return false;
        try{wantsPlay=autoplay;if(!autoplay)current.pause();current.seekTo(position,MediaPlayer.SEEK_CLOSEST);if(autoplay&&surfaceReady)current.start();return true;}
        catch(IllegalStateException e){return false;}
    }
    private void fitVideo(int width,int height){
        if(width<=0||height<=0||getWidth()<=0||getHeight()<=0)return;
        double factor=Math.min((double)getWidth()/width,(double)getHeight()/height);
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)surface.getLayoutParams();int w=(int)Math.round(width*factor),h=(int)Math.round(height*factor);
        if(p.width!=w||p.height!=h){p.width=w;p.height=h;p.gravity=Gravity.CENTER;surface.setLayoutParams(p);}
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(prepared&&current!=null)fitVideo(current.getVideoWidth(),current.getVideoHeight());}
    @Override public void surfaceCreated(SurfaceHolder holder){surfaceReady=true;attach();if(prepared&&wantsPlay&&current!=null)current.start();}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){attach();}
    @Override public void surfaceDestroyed(SurfaceHolder holder){surfaceReady=false;if(current!=null){try{current.setDisplay(null);if(prepared)current.pause();}catch(IllegalStateException ignored){}}}
}
