package cn.homewatch.recorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** Appearance preferences contain only a display mode, never connection or account data. */
final class AppAppearance {
    static final String SYSTEM="system", LIGHT="light", DARK="dark";
    private final SharedPreferences preferences;
    AppAppearance(Context context) { preferences=context.getSharedPreferences("recorder-appearance",Context.MODE_PRIVATE); }
    String mode() { String mode=preferences.getString("mode",SYSTEM);return LIGHT.equals(mode)||DARK.equals(mode)?mode:SYSTEM; }
    void mode(String mode) { if(!SYSTEM.equals(mode)&&!LIGHT.equals(mode)&&!DARK.equals(mode))throw new IllegalArgumentException("未知外观模式");preferences.edit().putString("mode",mode).apply(); }
    String label() { return LIGHT.equals(mode())?"浅色":DARK.equals(mode())?"深色":"跟随系统"; }
    Palette palette(Configuration configuration) { boolean dark=DARK.equals(mode())||SYSTEM.equals(mode())&&(configuration.uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;return new Palette(dark); }

    static final class Palette {
        final boolean dark;
        final int background,surface,text,muted,accent,primary,green,error,control,border,divider,soft,placeholder,errorSurface;
        final int timelineRail,timelineTick,recording,activeRecording,person;
        Palette(boolean dark) {
            this.dark=dark;
            background=dark?0xff10151d:0xfff4f7fb;
            surface=dark?0xff1b2330:0xffffffff;
            text=dark?0xffedf2fa:0xff17243a;
            muted=dark?0xffaab7ca:0xff596b83;
            accent=dark?0xff93bbff:0xff2563eb;
            primary=dark?0xff2d63cb:0xff245fd7;
            green=dark?0xff63d0aa:0xff167957;
            error=dark?0xffff9b9b:0xffbd3333;
            control=dark?0xff293649:0xffeff3f9;
            border=dark?0xff7589a4:0xff7d8ca2;
            divider=dark?0xff364357:0xffe7edf5;
            soft=dark?0xff243d64:0xffeaf1ff;
            placeholder=dark?0xff263244:0xffe8edf5;
            errorSurface=dark?0xff462c34:0xffffeeee;
            timelineRail=dark?0xff334054:0xffedf3fb;
            timelineTick=dark?0xff52637c:0xffdbe5f2;
            recording=dark?0xff5b99ec:0xff3f83dc;
            activeRecording=dark?0xffb0c4e3:0xff9dbce5;
            person=dark?0xffffb954:0xffed9e2b;
        }
        private int[] colors() { return new int[]{background,surface,text,muted,accent,primary,green,error,control,border,divider,soft,placeholder,errorSurface,timelineRail,timelineTick,recording,activeRecording,person}; }
        int remap(int color,Palette next) {
            int alpha=Color.alpha(color),opaque=color|0xff000000;int[] previous=colors(),replacement=next.colors();
            for(int i=0;i<previous.length;i++)if(opaque==previous[i])return (replacement[i]&0x00ffffff)|(alpha<<24);
            return color;
        }
    }
    /** Every app surface is rectangular; its colors can change without replacing the View. */
    static final class SquareDrawable extends GradientDrawable {
        private int color,strokeColor,strokeWidth;
        SquareDrawable(int color) { this.color=color;setShape(RECTANGLE);setColor(color); }
        void border(int width,int color) { strokeWidth=width;strokeColor=color;setStroke(width,color); }
        void appearance(Palette previous,Palette next) { color=previous.remap(color,next);setColor(color);if(strokeWidth>0){strokeColor=previous.remap(strokeColor,next);setStroke(strokeWidth,strokeColor);} }
    }
    /** RippleDrawable exposes setColor, so retain its tint instead of depending on hidden getters. */
    static final class ThemeRipple extends android.graphics.drawable.RippleDrawable {
        private int tint;
        ThemeRipple(int tint,android.graphics.drawable.Drawable content,android.graphics.drawable.Drawable mask) { super(android.content.res.ColorStateList.valueOf(tint),content,mask);this.tint=tint; }
        void appearance(Palette previous,Palette next) { if((tint|0xff000000)!=Color.WHITE)tint=previous.remap(tint,next);setColor(android.content.res.ColorStateList.valueOf(tint)); }
    }
}
