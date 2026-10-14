package cn.homewatch;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/** An in-memory view of one live frame. No image is written to the archive or disk. */
final class PersonIgnoreRegionDialog extends JDialog {
    private final RegionCanvas canvas;
    private final JLabel count = new JLabel();
    private List<PersonDetector.IgnoreRegion> accepted;

    static List<PersonDetector.IgnoreRegion> choose(Window owner, BufferedImage frame,
                                                    List<PersonDetector.IgnoreRegion> regions) {
        PersonIgnoreRegionDialog dialog = new PersonIgnoreRegionDialog(owner, frame, regions);
        dialog.setVisible(true);
        return dialog.accepted;
    }

    private PersonIgnoreRegionDialog(Window owner, BufferedImage frame, List<PersonDetector.IgnoreRegion> regions) {
        super(owner, "人员识别 · 当前摄像头的忽略区域", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getContentPane().setLayout(new BorderLayout(0, 8));
        JPanel explanation = new JPanel(new BorderLayout(0, 4));
        explanation.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
        JLabel instructions = new JLabel("拖动框住显示器、电视等区域，最多 8 个。右键点击区域可删除。");
        JLabel scope = new JLabel("红框内不生成新的人员标记，录像正常保存；只作用于当前摄像头。");
        instructions.setFont(NvrUi.FONT); scope.setFont(NvrUi.FONT);
        scope.setForeground(new Color(119, 126, 135));
        explanation.add(instructions, BorderLayout.NORTH); explanation.add(scope, BorderLayout.SOUTH);
        add(explanation, BorderLayout.NORTH);
        canvas = new RegionCanvas(frame, regions, this::updateCount);
        add(canvas, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        count.setFont(NvrUi.FONT); actions.add(count);
        JButton undo = NvrUi.button("删除最后一个", null, false, () -> {
            if (!canvas.regions.isEmpty()) { canvas.regions.removeLast(); canvas.repaint(); updateCount(); }
        });
        actions.add(undo);
        actions.add(NvrUi.button("清空", null, false, () -> { canvas.regions.clear(); canvas.repaint(); updateCount(); }));
        actions.add(NvrUi.button("取消", null, false, this::dispose));
        JButton save = NvrUi.button("保存区域", null, true, () -> { accepted = List.copyOf(canvas.regions); dispose(); });
        actions.add(save); add(actions, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setMinimumSize(new Dimension(660, 430)); setSize(920, 620); setLocationRelativeTo(owner);
        updateCount();
    }

    private void updateCount() { count.setText("已选 " + canvas.regions.size() + " / 8 个区域"); }

    static Rectangle imageBounds(int width, int height, int imageWidth, int imageHeight) {
        double scale = Math.min(width / (double) imageWidth, height / (double) imageHeight);
        int w = Math.max(1, (int) Math.round(imageWidth * scale));
        int h = Math.max(1, (int) Math.round(imageHeight * scale));
        return new Rectangle((width - w) / 2, (height - h) / 2, w, h);
    }

    static PersonDetector.IgnoreRegion regionFromDrag(Rectangle bounds, Point first, Point last) {
        double x1 = Math.max(0, Math.min(1, (first.x - bounds.x) / (double) bounds.width));
        double y1 = Math.max(0, Math.min(1, (first.y - bounds.y) / (double) bounds.height));
        double x2 = Math.max(0, Math.min(1, (last.x - bounds.x) / (double) bounds.width));
        double y2 = Math.max(0, Math.min(1, (last.y - bounds.y) / (double) bounds.height));
        if (Math.abs(x2 - x1) < 0.005 || Math.abs(y2 - y1) < 0.005) return null;
        return new PersonDetector.IgnoreRegion(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    private static final class RegionCanvas extends JPanel {
        private final BufferedImage frame;
        private final List<PersonDetector.IgnoreRegion> regions;
        private final Runnable changed;
        private Point first, last;

        RegionCanvas(BufferedImage source, List<PersonDetector.IgnoreRegion> regions, Runnable changed) {
            frame = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D copy = frame.createGraphics(); copy.drawImage(source, 0, 0, null); copy.dispose();
            this.regions = new ArrayList<>(regions); this.changed = changed;
            setBackground(new Color(22, 25, 30)); setPreferredSize(new Dimension(880, 480));
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    Rectangle bounds = videoBounds();
                    if (!bounds.contains(event.getPoint())) return;
                    if (javax.swing.SwingUtilities.isRightMouseButton(event)) {
                        double x = (event.getX() - bounds.x) / (double) bounds.width;
                        double y = (event.getY() - bounds.y) / (double) bounds.height;
                        for (int i = RegionCanvas.this.regions.size() - 1; i >= 0; i--) {
                            PersonDetector.IgnoreRegion r = RegionCanvas.this.regions.get(i);
                            if (x >= r.x() && x <= r.x() + r.width() && y >= r.y() && y <= r.y() + r.height()) {
                                RegionCanvas.this.regions.remove(i); changed.run(); repaint(); break;
                            }
                        }
                    } else if (javax.swing.SwingUtilities.isLeftMouseButton(event) && RegionCanvas.this.regions.size() < 8) {
                        first = event.getPoint(); last = first; repaint();
                    }
                }
                @Override public void mouseDragged(MouseEvent event) { if (first != null) { last = event.getPoint(); repaint(); } }
                @Override public void mouseReleased(MouseEvent event) {
                    if (first == null) return;
                    PersonDetector.IgnoreRegion r = regionFromDrag(videoBounds(), first, event.getPoint());
                    first = null; last = null;
                    if (r != null && RegionCanvas.this.regions.size() < 8) { RegionCanvas.this.regions.add(r); changed.run(); }
                    repaint();
                }
            };
            addMouseListener(mouse); addMouseMotionListener(mouse);
        }

        private Rectangle videoBounds() { return imageBounds(getWidth(), getHeight(), frame.getWidth(), frame.getHeight()); }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            Rectangle b = videoBounds(); g.drawImage(frame, b.x, b.y, b.width, b.height, null);
            int number = 1;
            for (PersonDetector.IgnoreRegion r : regions) drawRegion(g, b, r, number++);
            if (first != null && last != null) {
                PersonDetector.IgnoreRegion r = regionFromDrag(b, first, last);
                if (r != null) drawRegion(g, b, r, number);
            }
            g.dispose();
        }

        private static void drawRegion(Graphics2D g, Rectangle b, PersonDetector.IgnoreRegion r, int number) {
            int x = b.x + (int) Math.round(r.x() * b.width), y = b.y + (int) Math.round(r.y() * b.height);
            int w = (int) Math.round(r.width() * b.width), h = (int) Math.round(r.height() * b.height);
            g.setColor(new Color(202, 43, 47, 70)); g.fillRect(x, y, w, h);
            g.setColor(new Color(235, 65, 68)); g.setStroke(new BasicStroke(2)); g.drawRect(x, y, w, h);
            g.setFont(NvrUi.FONT); g.drawString("忽略 " + number, x + 5, y + Math.min(18, h));
        }
    }
}
