package cn.homewatch;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.Timer;

/** A wall-clock recording ruler. All interaction callbacks run on Swing's EDT. */
public final class RecordingTimeline extends JComponent {
    public record Span(Instant start, Instant end) {
        public Span {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (end.isBefore(start)) throw new IllegalArgumentException("end precedes start");
        }
    }

    @FunctionalInterface
    public interface Listener {
        /** adjusting=true while dragging; false commits a click or the released drag. */
        void onSeek(Instant time, boolean adjusting);
    }

    private static final Color SURFACE = new Color(248, 248, 249);
    private static final Color BORDER = new Color(216, 218, 221);
    private static final Color TEXT = new Color(63, 66, 70);
    private static final Color MUTED = new Color(125, 130, 136);
    private static final Color EMPTY = new Color(223, 226, 230);
    private static final Color RECORDED = new Color(70, 135, 205);
    private static final Color PERSON = new Color(238, 151, 51);
    private static final Color RED = new Color(207, 44, 49);
    private static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_MINUTE_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final long MIN_WINDOW_MILLIS = 60_000;
    private static final long MAX_WINDOW_MILLIS = Duration.ofDays(366).toMillis();
    private static final int INSET = 18;
    private static final long[] TICK_SECONDS = {
        5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 14400, 21600,
        43200, 86400, 172800, 259200, 604800, 1209600, 2592000, 7776000
    };

    private final Listener listener;
    private ZoneId zone = ZoneId.systemDefault();
    private LocalDate day = LocalDate.now();
    private Instant dayStart;
    private Instant dayEnd;
    private Instant viewStart;
    private Instant viewEnd;
    private Instant position;
    private Instant hover;
    private List<Span> recordings = List.of();
    private List<Span> persons = List.of();
    private boolean panning;
    private boolean overviewPanning;
    private boolean panMoved;
    private int panX;
    private Instant panPosition;
    private long panLength;
    private int lastMouseX;
    private Instant rangeStart;
    private Instant rangeEnd;
    private Instant overviewDragStart;
    private Instant overviewDragEnd;
    private final Timer edgeTimer;

    public RecordingTimeline(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        setOpaque(true);
        setBackground(SURFACE);
        setFont(FONT);
        setPreferredSize(new Dimension(800, 140));
        setMinimumSize(new Dimension(320, 118));
        setFocusable(true);
        updateDayBounds();
        viewStart = dayStart;
        viewEnd = dayEnd;
        position = viewStart.plusMillis(windowMillis() / 2);
        edgeTimer = new Timer(40, event -> scrollAtEdge());
        setToolTipText("红线固定在中间；左右拖动整条时间轴定位，松开回放；滚轮以红线为中心缩放");
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { beginInteraction(e); }
            @Override public void mouseDragged(MouseEvent e) { continueInteraction(e); }
            @Override public void mouseReleased(MouseEvent e) { endInteraction(e); }
            @Override public void mouseMoved(MouseEvent e) {
                hover = onRuler(e.getY()) ? instantAtX(e.getX()) : null;
                setCursor(Cursor.getPredefinedCursor(hover == null ? Cursor.DEFAULT_CURSOR : Cursor.MOVE_CURSOR));
                repaint();
            }
            @Override public void mouseExited(MouseEvent e) {
                if (!isAdjusting()) hover = null;
                repaint();
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { zoom(e); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    public void setDay(LocalDate selectedDay) {
        long length = windowMillis();
        ZonedDateTime centre = viewStart.plusMillis(length / 2).atZone(zone);
        day = Objects.requireNonNull(selectedDay, "selectedDay");
        updateDayBounds();
        Instant selectedCentre = day.atTime(centre.toLocalTime()).atZone(zone).toInstant();
        centreView(selectedCentre, length);
        hover = null;
        finishInteraction();
        repaint();
    }

    /** Usually unnecessary; use this when camera timestamps use a different zone. */
    public void setZone(ZoneId selectedZone) {
        zone = Objects.requireNonNull(selectedZone, "selectedZone");
        day = viewStart.plusMillis(windowMillis() / 2).atZone(zone).toLocalDate();
        updateDayBounds();
        hover = null;
        finishInteraction();
        repaint();
    }

    public void setRecordings(List<Span> spans) { recordings = copySpans(spans); repaint(); }
    public void setPersons(List<Span> spans) { persons = copySpans(spans); repaint(); }

    public void setPosition(Instant time) {
        // The selected time always stays under the fixed central handle.
        if (isAdjusting()) return;
        if (time == null) { position = null; repaint(); return; }
        centreView(time, windowMillis());
        repaint();
    }

    /** Sets the visible duration around the playhead, or the current view centre. */
    public void setViewWindow(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("duration must be positive");
        long millis;
        try { millis = duration.toMillis(); }
        catch (ArithmeticException ignored) { millis = MAX_WINDOW_MILLIS; }
        Instant centre = position != null ? position : viewStart.plusMillis(windowMillis() / 2);
        centreView(centre, millis);
        repaint();
    }

    public LocalDate getDay() { return day; }
    public ZoneId getZone() { return zone; }
    public Instant getPosition() { return position; }
    public Instant getViewStart() { return viewStart; }
    public Instant getViewEnd() { return viewEnd; }
    public boolean isAdjusting() { return panning || overviewPanning; }
    public int getPlayheadX() { return INSET + (int) Math.round(plotWidth() / 2.0); }

    /** Bounds user seeks; the viewport retains padding around the fixed central handle. */
    public void setRange(Instant start, Instant end) {
        validateRange(start, end);
        finishInteraction();
        rangeStart = start;
        rangeEnd = end;
        showRange(start, end);
    }

    /** Sets the visible width and central time without enabling query bounds. */
    public void showRange(Instant start, Instant end) {
        validateRange(start, end);
        finishInteraction();
        clampView(start, Duration.between(start, end).toMillis());
        hover = null;
        repaint();
    }

    /** Returns to the continuous archive without moving the current view. */
    public void clearRange() {
        finishInteraction();
        rangeStart = rangeEnd = null;
        repaint();
    }

    public Instant getRangeStart() { return rangeStart; }
    public Instant getRangeEnd() { return rangeEnd; }

    /** Stops drag/edge timers when the owner closes, including a headless test window. */
    public void cancelInteraction() { finishInteraction(); }

    /** Mapping is exposed so the owner can position a nearby time/thumbnail popup. */
    public Instant instantAtX(int x) {
        double fraction = Math.max(0, Math.min(1, (x - INSET) / (double) plotWidth()));
        return viewStart.plusMillis(Math.round(fraction * windowMillis()));
    }

    public int xAtInstant(Instant instant) {
        return INSET + (int) Math.round(Duration.between(viewStart, instant).toMillis()
                / (double) windowMillis() * plotWidth());
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(FONT);
            g.setColor(SURFACE);
            g.fillRect(0, 0, getWidth(), getHeight());
            drawHeader(g);
            int barY = barY();
            int barHeight = 24;
            g.setColor(EMPTY);
            g.fillRoundRect(INSET, barY, plotWidth(), barHeight, 4, 4);
            Graphics2D clipped = (Graphics2D) g.create();
            clipped.clipRect(INSET, barY, plotWidth(), barHeight);
            drawSpans(clipped, recordings, RECORDED, viewStart, viewEnd, barY, barHeight);
            drawSpans(clipped, persons, PERSON, viewStart, viewEnd, barY, barHeight);
            clipped.dispose();
            g.setColor(BORDER);
            g.drawRoundRect(INSET, barY, plotWidth(), barHeight, 4, 4);
            drawTicks(g, barY, barHeight);
            drawPlayhead(g, barY, barHeight);
            drawOverview(g);
            if (hover != null) drawHover(g, barY, barHeight);
            if (!isEnabled()) {
                g.setColor(new Color(248, 248, 249, 145));
                g.fillRect(INSET, barY - 8, plotWidth(), barHeight + 47);
            }
        } finally { g.dispose(); }
    }

    private void drawHeader(Graphics2D g) {
        g.setColor(TEXT);
        LocalDate firstDay = viewStart.atZone(zone).toLocalDate();
        LocalDate lastDay = viewEnd.minusNanos(1).atZone(zone).toLocalDate();
        String dates = DAY_FORMAT.format(firstDay) + (firstDay.equals(lastDay) ? "" : " / "
                + DateTimeFormatter.ofPattern("MM-dd").format(lastDay));
        String heading = getWidth() < 480 ? DAY_FORMAT.format(day) : dates;
        g.drawString(heading, INSET, 21);
        int legendWidth = 224;
        int x = Math.max(INSET + g.getFontMetrics().stringWidth(heading) + 16, getWidth() - INSET - legendWidth);
        if (x + legendWidth <= getWidth() - 4) {
            legend(g, x, RECORDED, "录像");
            legend(g, x + 66, PERSON, "人员事件");
            legend(g, x + 160, EMPTY, "无录像");
        }
        String hint = rangeStart == null ? "红线居中 · 拖动时间轴回放" : "红线居中 · 查询区间内拖动";
        int hintX = INSET + g.getFontMetrics().stringWidth(heading) + 30;
        if (getHeight() >= 130 && hintX + g.getFontMetrics().stringWidth(hint) + 20 < x) {
            g.setColor(MUTED);
            g.drawString(hint, hintX, 21);
        }
    }

    private void legend(Graphics2D g, int x, Color color, String label) {
        g.setColor(color);
        g.fillRoundRect(x, 11, 11, 9, 2, 2);
        g.setColor(MUTED);
        g.drawString(label, x + 16, 21);
    }

    private void drawTicks(Graphics2D g, int barY, int barHeight) {
        boolean multipleDays = !viewStart.atZone(zone).toLocalDate().equals(viewEnd.minusNanos(1).atZone(zone).toLocalDate());
        double approximateSeconds = windowMillis() / 1000.0 / Math.max(1, plotWidth() / (multipleDays ? 126.0 : 82.0));
        long seconds = TICK_SECONDS[TICK_SECONDS.length - 1];
        for (long candidate : TICK_SECONDS) {
            if (candidate >= approximateSeconds) { seconds = candidate; break; }
        }
        long step = seconds * 1000;
        Instant tickOrigin = viewStart.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        long relative = Duration.between(tickOrigin, viewStart).toMillis();
        long first = Math.floorDiv(relative + step - 1, step) * step;
        FontMetrics fm = g.getFontMetrics();
        int lastLabelRight = Integer.MIN_VALUE;
        LocalDate previousTickDay = null;
        for (long offset = first; offset <= Duration.between(tickOrigin, viewEnd).toMillis(); offset += step) {
            Instant tick = tickOrigin.plusMillis(offset);
            int x = xAtInstant(tick);
            g.setColor(new Color(194, 198, 203));
            g.drawLine(x, barY + barHeight + 3, x, barY + barHeight + 8);
            LocalDate tickDay = tick.atZone(zone).toLocalDate();
            boolean showDate = multipleDays && (!tickDay.equals(previousTickDay) || seconds >= 43_200);
            DateTimeFormatter format = showDate ? (seconds < 60 ? DATE_TIME_FORMAT : DATE_MINUTE_FORMAT)
                    : (seconds < 60 ? TIME_FORMAT : MINUTE_FORMAT);
            String label = format.format(tick.atZone(zone));
            int width = fm.stringWidth(label);
            int textX = Math.max(INSET, Math.min(getWidth() - INSET - width, x - width / 2));
            if (textX > lastLabelRight + 8) {
                g.setColor(MUTED);
                g.drawString(label, textX, barY + barHeight + 24);
                lastLabelRight = textX + width;
                previousTickDay = tickDay;
            }
        }
    }

    private void drawPlayhead(Graphics2D g, int barY, int barHeight) {
        if (position == null) return;
        int x = getPlayheadX();
        g.setColor(RED);
        g.setStroke(new BasicStroke(1.5f));
        g.drawLine(x, barY - 7, x, barY + barHeight + 8);
        g.fillPolygon(new int[] {x - 5, x + 5, x}, new int[] {barY - 10, barY - 10, barY - 4}, 3);
        String label = TIME_FORMAT.format(position.atZone(zone));
        int width = g.getFontMetrics().stringWidth(label) + 18;
        int left = x - width / 2;
        int top = barY - 27;
        g.fillRoundRect(left, top, width, 22, 5, 5);
        g.setColor(Color.WHITE);
        g.drawString(label, left + 9, top + 15);
    }

    private void drawOverview(Graphics2D g) {
        Instant[] overview = overviewBounds();
        Instant overviewStart = overview[0];
        Instant overviewEnd = overview[1];
        int y = overviewY();
        g.setColor(EMPTY);
        g.fillRect(INSET, y, plotWidth(), 5);
        drawSpans(g, recordings, RECORDED, overviewStart, overviewEnd, y, 5);
        drawSpans(g, persons, PERSON, overviewStart, overviewEnd, y, 5);
        int left = Math.max(INSET, overviewX(viewStart, overviewStart, overviewEnd));
        int right = Math.min(getWidth() - INSET, overviewX(viewEnd, overviewStart, overviewEnd));
        g.setColor(new Color(99, 106, 114));
        g.setStroke(new BasicStroke(1f));
        g.drawRect(left, y - 2, Math.max(2, right - left), 8);
        if (getHeight() >= 135) {
            g.setColor(MUTED);
            g.setFont(FONT.deriveFont(10f));
            String hint = "拖动整条时间轴定位 · 滚轮居中缩放";
            g.drawString(hint, getWidth() - INSET - g.getFontMetrics().stringWidth(hint), getHeight() - 5);
            g.setFont(FONT);
        }
    }

    private void drawSpans(Graphics2D g, List<Span> spans, Color color,
            Instant start, Instant end, int y, int height) {
        long total = Duration.between(start, end).toMillis();
        g.setColor(color);
        for (Span span : spans) {
            if (!span.end().isAfter(span.start())) continue;
            if (!span.end().isAfter(start) || !span.start().isBefore(end)) continue;
            Instant clippedStart = span.start().isBefore(start) ? start : span.start();
            Instant clippedEnd = span.end().isAfter(end) ? end : span.end();
            if (rangeStart != null) {
                if (clippedStart.isBefore(rangeStart)) clippedStart = rangeStart;
                if (clippedEnd.isAfter(rangeEnd)) clippedEnd = rangeEnd;
                if (!clippedEnd.isAfter(clippedStart)) continue;
            }
            int x1 = INSET + (int) Math.floor(Duration.between(start, clippedStart).toMillis() / (double) total * plotWidth());
            int x2 = INSET + (int) Math.ceil(Duration.between(start, clippedEnd).toMillis() / (double) total * plotWidth());
            g.fillRect(x1, y, Math.max(1, x2 - x1), height);
        }
    }

    private void drawHover(Graphics2D g, int barY, int barHeight) {
        if (isAdjusting() || Math.abs(xAtInstant(hover) - getPlayheadX()) < 120) return;
        int x = isAdjusting() ? getPlayheadX() : xAtInstant(hover);
        if (!isAdjusting()) {
            g.setColor(new Color(99, 106, 114, 160));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {3, 3}, 0));
            g.drawLine(x, barY - 3, x, barY + barHeight + 6);
            g.setStroke(new BasicStroke(1f));
        }
        String label = DATE_TIME_FORMAT.format(hover.atZone(zone));
        FontMetrics fm = g.getFontMetrics();
        int width = fm.stringWidth(label) + 18;
        int left = Math.max(INSET, Math.min(getWidth() - INSET - width, x - width / 2));
        int top = barY - 27;
        g.setColor(isAdjusting() ? RED : new Color(65, 70, 76));
        g.fillRoundRect(left, top, width, 22, 5, 5);
        g.fillPolygon(new int[] {x - 4, x + 4, x}, new int[] {top + 21, top + 21, top + 26}, 3);
        g.setColor(Color.WHITE);
        g.drawString(label, left + 9, top + 15);
    }

    private void beginInteraction(MouseEvent e) {
        if (!isEnabled()) return;
        requestFocusInWindow();
        if (e.getButton() != MouseEvent.BUTTON1 && e.getButton() != MouseEvent.BUTTON3) return;
        if (Math.abs(e.getY() - overviewY()) <= 9) {
            overviewPanning = true;
            Instant[] bounds = overviewBounds();
            overviewDragStart = bounds[0];
            overviewDragEnd = bounds[1];
            moveOverview(e.getX(), true);
        } else if (onRuler(e.getY())) {
            panning = true;
            panMoved = false;
            panX = e.getX();
            panPosition = position == null ? viewStart.plusMillis(windowMillis() / 2) : position;
            panLength = windowMillis();
            hover = position;
            listener.onSeek(panPosition, true);
            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        }
        repaint();
    }

    private void continueInteraction(MouseEvent e) {
        if (!isEnabled()) return;
        if (overviewPanning) moveOverview(e.getX(), true);
        else if (panning) {
            if (Math.abs(e.getX() - panX) >= 3) panMoved = true;
            if (panMoved) dragAt(e.getX(), true);
        }
    }

    private void endInteraction(MouseEvent e) {
        boolean commitSeek = isAdjusting();
        Instant target = position;
        if (panning && !panMoved) target = instantAtX(e.getX());
        finishInteraction();
        if (commitSeek && isEnabled()) {
            // The owner may setPosition synchronously once the final seek is committed.
            seekTime(target, false);
        }
        setCursor(Cursor.getPredefinedCursor(onRuler(e.getY()) ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }

    private void seekTime(Instant target, boolean adjusting) {
        centreView(constrainSeek(target), windowMillis());
        hover = position;
        repaint();
        listener.onSeek(position, adjusting);
    }

    private void dragAt(int x, boolean adjusting) {
        lastMouseX = x;
        long delta = Math.round((x - panX) / (double) plotWidth() * panLength);
        seekTime(panPosition.minusMillis(delta), adjusting);
        if (panning && panMoved && edgeDirection() != 0) edgeTimer.start();
        else edgeTimer.stop();
    }

    private void zoom(MouseWheelEvent e) {
        if (!isEnabled() || isAdjusting()) return;
        long oldLength = windowMillis();
        double factor = Math.pow(1.35, Math.max(-12, Math.min(12, e.getPreciseWheelRotation())));
        long nextLength = Math.max(minimumWindow(), Math.min(maximumWindow(), Math.round(oldLength * factor)));
        Instant anchor = position == null ? viewStart.plusMillis(oldLength / 2) : position;
        centreView(anchor, nextLength);
        hover = onRuler(e.getY()) ? instantAtX(e.getX()) : null;
        repaint();
        e.consume();
    }

    private void moveOverview(int x, boolean adjusting) {
        double fraction = Math.max(0, Math.min(1, (x - INSET) / (double) plotWidth()));
        Instant[] bounds = overviewPanning && overviewDragStart != null
                ? new Instant[] {overviewDragStart, overviewDragEnd} : overviewBounds();
        seekTime(bounds[0].plusMillis(Math.round(Duration.between(bounds[0], bounds[1]).toMillis() * fraction)), adjusting);
    }

    private void centreView(Instant centre, long length) {
        length = Math.max(minimumWindow(), Math.min(maximumWindow(), length));
        clampView(centre.minusMillis(length / 2), length);
        position = centre;
    }

    private void clampView(Instant start, long length) {
        length = Math.max(minimumWindow(), Math.min(maximumWindow(), length));
        viewStart = start;
        viewEnd = start.plusMillis(length);
        position = viewStart.plusMillis(length / 2);
        day = viewStart.plusMillis(length / 2).atZone(zone).toLocalDate();
        updateDayBounds();
    }

    private void updateDayBounds() {
        dayStart = day.atStartOfDay(zone).toInstant();
        dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
    }

    private int plotWidth() { return Math.max(1, getWidth() - INSET * 2); }
    private int barY() { return getHeight() < 130 ? 42 : 50; }
    private int overviewY() { return getHeight() < 130 ? getHeight() - 11 : 116; }
    private boolean onRuler(int y) { return y >= barY() - 14 && y <= barY() + 49; }
    private long windowMillis() { return Math.max(1, Duration.between(viewStart, viewEnd).toMillis()); }
    private long dayMillis() { return Duration.between(dayStart, dayEnd).toMillis(); }
    private int overviewX(Instant instant, Instant start, Instant end) {
        return INSET + (int) Math.round(Duration.between(start, instant).toMillis()
                / (double) Duration.between(start, end).toMillis() * plotWidth());
    }

    private Instant[] overviewBounds() {
        if (rangeStart != null) return new Instant[] {rangeStart, rangeEnd};
        if (overviewPanning && overviewDragStart != null) return new Instant[] {overviewDragStart, overviewDragEnd};
        Instant start = viewStart.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        Instant end = viewEnd.minusNanos(1).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
        for (Span span : recordings) {
            if (span.start().isBefore(start)) start = span.start();
            if (span.end().isAfter(end)) end = span.end();
        }
        return new Instant[] {start, end};
    }

    private int edgeDirection() {
        // Pulling the strip left exposes later footage; pulling right exposes earlier footage.
        if (lastMouseX < INSET + 14) return 1;
        if (lastMouseX > getWidth() - INSET - 14) return -1;
        return 0;
    }

    private void scrollAtEdge() {
        if (!isEnabled() || !panning || !panMoved || edgeDirection() == 0) { edgeTimer.stop(); return; }
        int direction = edgeDirection();
        int beyond = direction > 0 ? INSET + 14 - lastMouseX : lastMouseX - (getWidth() - INSET - 14);
        double speed = Math.min(3, 1 + beyond / 30.0);
        long delta = Math.max(1, Math.round(windowMillis() * .008 * speed));
        Instant target = constrainSeek(position.plusMillis(direction * delta));
        if (target.equals(position)) { edgeTimer.stop(); return; }
        panPosition = panPosition.plusMillis(direction * delta);
        seekTime(target, true);
    }

    private Instant constrainSeek(Instant time) {
        if (rangeStart == null) return time;
        if (time.isBefore(rangeStart)) return rangeStart;
        Instant latest = rangeEnd.minusMillis(1);
        return time.isAfter(latest) ? latest : time;
    }

    private long minimumWindow() {
        return rangeStart == null ? MIN_WINDOW_MILLIS
                : Math.min(MIN_WINDOW_MILLIS, Duration.between(rangeStart, rangeEnd).toMillis());
    }

    private long maximumWindow() {
        return MAX_WINDOW_MILLIS;
    }

    private static void validateRange(Instant start, Instant end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start) || Duration.between(start, end).toMillis() < 1)
            throw new IllegalArgumentException("end must be after start by at least one millisecond");
    }

    private void finishInteraction() {
        edgeTimer.stop();
        panning = overviewPanning = panMoved = false;
        overviewDragStart = overviewDragEnd = null;
    }

    @Override public void removeNotify() {
        finishInteraction();
        super.removeNotify();
    }

    private static List<Span> copySpans(List<Span> spans) {
        Objects.requireNonNull(spans, "spans");
        ArrayList<Span> copy = new ArrayList<>(spans);
        copy.sort(Comparator.comparing(Span::start).thenComparing(Span::end));
        return List.copyOf(copy);
    }
}
