package cn.homewatch.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Small native vector icons, independent of fonts and external image services. */
final class MobileIcons extends Drawable {
    static final int CAMERA=0, HOME=1, USER=2, BACK=3, MORE=4, FULLSCREEN=5, CALENDAR=6, PERSON=7, GRID=8, REFRESH=9, SHIELD=10, CHEVRON=11, PLAY=12, INFO=13, APPEARANCE=14;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int kind, size;
    MobileIcons(Context context, int kind, int color, int dp) {
        this.kind=kind; size=Math.round(dp*context.getResources().getDisplayMetrics().density);
        paint.setColor(color);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);
        setBounds(0,0,size,size);
    }
    private void lines(Canvas c, float... points) { Path p=new Path();p.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)p.lineTo(points[i],points[i+1]);c.drawPath(p,paint); }
    @Override public void draw(Canvas c) {
        c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);
        switch(kind) {
            case CAMERA: c.drawRect(3,6,17,18,paint);lines(c,17,10,22,7,22,17,17,14);c.drawCircle(9,12,2.4f,paint);break;
            case HOME: lines(c,3,10,12,3,21,10);lines(c,5,9,5,21,10,21,10,15,14,15,14,21,19,21,19,9);break;
            case USER: c.drawCircle(12,7,4,paint);c.drawArc(4,13,20,27,180,180,false,paint);break;
            case BACK: lines(c,15,5,8,12,15,19);break;
            case MORE: paint.setStyle(Paint.Style.FILL);for(int x=5;x<=19;x+=7)c.drawCircle(x,12,1.5f,paint);paint.setStyle(Paint.Style.STROKE);break;
            case FULLSCREEN: lines(c,9,4,4,4,4,9);lines(c,15,4,20,4,20,9);lines(c,4,15,4,20,9,20);lines(c,20,15,20,20,15,20);break;
            case CALENDAR: c.drawRect(4,5,20,21,paint);lines(c,8,3,8,7);lines(c,16,3,16,7);lines(c,4,10,20,10);c.drawCircle(9,15,0.7f,paint);c.drawCircle(15,15,0.7f,paint);break;
            case PERSON: c.drawCircle(12,5,2.5f,paint);lines(c,8,22,10,13,6,15,8,10,16,10,18,15,14,13,16,22);break;
            case GRID: for(int y=4;y<=14;y+=10)for(int x=4;x<=14;x+=10)c.drawRect(x,y,x+6,y+6,paint);break;
            case REFRESH: c.drawArc(4,4,20,20,35,285,false,paint);lines(c,20,5,20,11,14,11);break;
            case SHIELD: lines(c,12,3,20,6,19,15,16,19,12,22,8,19,5,15,4,6,12,3);lines(c,8,12,11,15,16,9);break;
            case CHEVRON: lines(c,9,5,16,12,9,19);break;
            case PLAY: lines(c,9,5,19,12,9,19,9,5);break;
            case INFO: c.drawCircle(12,12,9,paint);lines(c,12,11,12,17);c.drawCircle(12,7,0.65f,paint);break;
            case APPEARANCE: c.drawCircle(12,12,8,paint);paint.setStyle(Paint.Style.FILL);c.drawArc(4,4,20,20,-90,180,true,paint);paint.setStyle(Paint.Style.STROKE);break;
        }
        c.restore();
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha);invalidateSelf(); }
    void appearance(AppAppearance.Palette previous,AppAppearance.Palette next) { if(paint.getColor()!=android.graphics.Color.WHITE)paint.setColor(previous.remap(paint.getColor(),next));invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter);invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
}
