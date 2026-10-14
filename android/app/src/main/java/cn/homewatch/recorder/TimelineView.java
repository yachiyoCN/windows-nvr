package cn.homewatch.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** One centered cursor for the entire queried range; blue recordings and orange person intervals. */
final class TimelineView extends View {
    interface Listener {
        void moved(Instant value, boolean finished);
        default void cancelled(Instant original) { }
    }
    private static final int ACTION_ZOOM_IN = 0x01000001, ACTION_ZOOM_OUT = 0x01000002;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PlaybackPlanner planner;
    private final Listener listener;
    private final int touchSlop;
    private final ScaleGestureDetector scaleDetector;
    private AppAppearance.Palette palette=new AppAppearance.Palette(false);
    private ZoneId zone = ZoneId.systemDefault();
    private DateTimeFormatter tickFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(zone);
    private long center = System.currentTimeMillis(), span = 3600000;
    private long dragCenter;
    private float dragX, dragY;
    private boolean tracking, dragging, multiTouch, tapCandidate, cancelNotified, constrained;
    TimelineView(Context context, PlaybackPlanner planner, Listener listener) {
        super(context); this.planner = planner; this.listener = listener;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                beginPinch(); return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                if (Float.isFinite(factor) && factor > 0) span(Math.round(span / (double) factor));
                return true;
            }
        });
        // Quick-scale maps double taps to zoom; ordinary taps here locate a recording.
        scaleDetector.setQuickScaleEnabled(false); scaleDetector.setStylusScaleEnabled(false);
        setFocusable(true); setClickable(true);
        setContentDescription("连续录像时间轴，轻触或左右拖动定位，双指缩放；蓝色为录像，橙色为人员事件");
        appearance(new AppAppearance(context).palette(getResources().getConfiguration()));
    }
    void appearance(AppAppearance.Palette value) { palette=value;setBackgroundColor(value.surface);invalidate(); }
    void constrained(boolean value) { constrained = value; }
    private Instant bound(Instant value) { return constrained ? planner.clamp(value) : value; }
    void zone(ZoneId value) { zone = value; tickFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(zone); invalidate(); }
    void time(Instant value) { if (!tracking) { center = value.toEpochMilli(); invalidate(); } }
    void span(long milliseconds) { span = Math.max(60000, Math.min(86400000, milliseconds)); invalidate(); }
    long span() { return span; }
    private float x(long time) { return getWidth() / 2f + (time - center) * getWidth() / (float) span; }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float d = getResources().getDisplayMetrics().density;
        paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        Paint.FontMetrics font = paint.getFontMetrics();
        float labelBaseline = 5 * d - font.ascent;
        float labelBottom = labelBaseline + font.descent;
        float top = Math.max(29 * d, labelBottom + 7 * d);
        float bottom = Math.max(top + 6 * d, Math.min(top + 18 * d, getHeight() - 7 * d));
        paint.setColor(palette.timelineRail);canvas.drawRect(0,top,getWidth(),bottom,paint);
        for (RecorderModels.Recording r : planner.recordings()) {
            float left = x(Math.max(r.start.toEpochMilli(), planner.from().toEpochMilli()));
            float right = x(Math.min(r.end.toEpochMilli(), planner.to().toEpochMilli()));
            if (right < 0 || left > getWidth() || right <= left) continue;
            paint.setColor(r.active?palette.activeRecording:palette.recording);canvas.drawRect(left,top,right,bottom,paint);
            paint.setColor(palette.person);
            for (RecorderModels.Person p : r.persons) {
                float a = x(Math.max(planner.from().toEpochMilli(), r.start.toEpochMilli() + Math.round(p.fromSeconds * 1000)));
                float b = x(Math.min(planner.to().toEpochMilli(), r.start.toEpochMilli() + Math.round(p.toSeconds * 1000)));
                if (b > a) canvas.drawRect(a, top + (bottom - top) / 2, Math.max(a + 2 * d, b), bottom, paint);
            }
        }
        float spacing = Math.max(54 * d, paint.measureText("23:59") + 16 * d);
        long minimumStep = Math.round(span * spacing / Math.max(1, getWidth()));
        long step = 86400000;
        for (long candidate : new long[] {60000, 120000, 300000, 600000, 900000, 1800000, 3600000, 7200000, 14400000, 21600000, 43200000, 86400000}) {
            if (candidate >= minimumStep) { step = candidate; break; }
        }
        long first = Math.floorDiv(center - span / 2, step) * step;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setStrokeWidth(d);
        for (long t = first; t <= center + span / 2 + step; t += step) {
            float px=x(t);paint.setColor(palette.timelineTick);canvas.drawLine(px,top-5*d,px,bottom+4*d,paint);
            paint.setColor(palette.muted);canvas.drawText(tickFormat.format(Instant.ofEpochMilli(t)),px,labelBaseline,paint);
        }
        paint.setColor(palette.accent);paint.setStrokeWidth(2*d);canvas.drawLine(getWidth()/2f,top-5*d,getWidth()/2f,getHeight()-3*d,paint);
        canvas.drawCircle(getWidth() / 2f, top - 3 * d, 3 * d, paint);
    }
    private void disallowParent(boolean value) { if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(value); }
    private void cancelPreview() {
        center = dragCenter;
        if (!cancelNotified) { cancelNotified = true; listener.cancelled(Instant.ofEpochMilli(dragCenter)); }
        dragging = false; invalidate();
    }
    private void beginPinch() {
        if (!tracking) return;
        if (dragging) cancelPreview();
        multiTouch = true; disallowParent(true);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            tracking = tapCandidate = true; dragging = multiTouch = cancelNotified = false;
            dragX = event.getX(); dragY = event.getY(); dragCenter = center;
            setPressed(true);
        }
        if (!tracking) return super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) beginPinch();
        scaleDetector.onTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE && !multiTouch) {
            float dx = event.getX() - dragX, dy = event.getY() - dragY;
            if (Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) { tapCandidate = false; setPressed(false); }
            if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) >= Math.abs(dy)) {
                dragging = true; setPressed(false); disallowParent(true);
            }
            if (dragging) {
                center = bound(Instant.ofEpochMilli(dragCenter - Math.round(dx * span / Math.max(1, getWidth())))).toEpochMilli();
                listener.moved(Instant.ofEpochMilli(center), false); invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            // Let the listener update the centered time after it clamps to a real
            // recording, including when a tap lands in a gap between recordings.
            tracking = false; setPressed(false); disallowParent(false);
            if (action == MotionEvent.ACTION_CANCEL) cancelPreview();
            else if (!multiTouch && (dragging || tapCandidate)) {
                if (!dragging) center = bound(Instant.ofEpochMilli(dragCenter + Math.round((event.getX() - getWidth() / 2f) * span / Math.max(1, getWidth())))).toEpochMilli();
                listener.moved(Instant.ofEpochMilli(center), true);
                if (!dragging) performClick();
            }
            dragging = multiTouch = false;
            invalidate(); return true;
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setScrollable(true); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_ZOOM_IN, "放大时间轴"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_ZOOM_OUT, "缩小时间轴"));
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == ACTION_ZOOM_IN || action == ACTION_ZOOM_OUT) {
            span(action == ACTION_ZOOM_IN ? span / 2 : span * 2); return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            center = bound(Instant.ofEpochMilli(center + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1) * span / 10)).toEpochMilli();
            listener.moved(Instant.ofEpochMilli(center), true); invalidate(); return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
