package aquanet.node;

import aquanet.node.TankModel.Snapshot;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultCaret;

public class TankNodeWindow extends JFrame implements TankNode.Ui {

    private static final Color OK_BLUE = new Color(0x2E9BEA);
    private static final Color LOW_ORANGE = new Color(0xF39C12);
    private static final Color CRIT_RED = new Color(0xD93B3B);
    private static final Color GREEN = new Color(0x1E9E55);

    private final TankNode node;
    private final TankView tankView = new TankView();
    private final JLabel connLabel = new JLabel("Reconnecting...", JLabel.CENTER);
    private final JLabel percentLabel = new JLabel("-", JLabel.LEFT);
    private final JLabel litresLabel = new JLabel("-");
    private final JLabel demandLabel = new JLabel("-");
    private final JLabel inflowLabel = new JLabel("-");
    private final JLabel pendingLabel = new JLabel("-");
    private final JLabel supplyLabel = new JLabel("-");
    private final JLabel leakLabel = new JLabel("-");
    private final JLabel replyLabel = new JLabel("-");
    private final JTextArea logArea = new JTextArea();
    private final JButton pauseBtn = new JButton("Pause demand");
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss");

    TankNodeWindow(TankNode node) {
        super("AquaNet Tank Node - " + node.config().name);
        this.node = node;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                node.shutdown();           // sends BYE, closes sockets
                dispose();
                System.exit(0);
            }
        });

        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setContentPane(root);

        //  header: connection status
        connLabel.setOpaque(true);
        connLabel.setForeground(Color.WHITE);
        connLabel.setFont(connLabel.getFont().deriveFont(Font.BOLD, 14f));
        connLabel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        setConnected(false);
        root.add(connLabel, BorderLayout.NORTH);

        // top part: tank drawing (left) + figures (right)
        JPanel top = new JPanel(new BorderLayout(10, 0));
        tankView.setPreferredSize(new Dimension(170, 250));
        top.add(tankView, BorderLayout.CENTER);

        JPanel figures = new JPanel(new GridBagLayout());
        figures.setPreferredSize(new Dimension(250, 270));
        percentLabel.setFont(percentLabel.getFont().deriveFont(Font.BOLD, 30f));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        figures.add(percentLabel, c);
        c.gridy++;
        litresLabel.setFont(litresLabel.getFont().deriveFont(Font.BOLD, 14f));
        figures.add(litresLabel, c);
        c.gridwidth = 1;
        addRow(figures, c, "Demand", demandLabel);
        addRow(figures, c, "Inflow", inflowLabel);
        addRow(figures, c, "Pending", pendingLabel);
        addRow(figures, c, "Supply valve", supplyLabel);
        addRow(figures, c, "Leak", leakLabel);
        addRow(figures, c, "Last reply", replyLabel);
        c.gridy++; c.weighty = 1; c.gridwidth = 2;
        figures.add(new JLabel(), c);                     // spacer pushes rows to the top
        top.add(figures, BorderLayout.EAST);
        top.setMinimumSize(new Dimension(300, 200));

        // --- log
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setLineWrap(true);
        ((DefaultCaret) logArea.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);   // auto-scroll
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Messages"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, logScroll);
        split.setResizeWeight(0.45);
        split.setDividerLocation(300);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);

        // --- buttons
        JPanel buttons = new JPanel(new GridLayout(1, 3, 6, 0));
        JButton leakBtn = new JButton("Toggle leak (local)");
        JButton reqBtn = new JButton("Request water now");
        leakBtn.addActionListener(e -> node.toggleLeak());
        reqBtn.addActionListener(e -> node.manualRequest());
        pauseBtn.addActionListener(e -> node.togglePause());
        for (JButton b : new JButton[] {leakBtn, reqBtn, pauseBtn}) {
            b.setMargin(new Insets(4, 2, 4, 2));
            b.setFont(b.getFont().deriveFont(11f));
            buttons.add(b);
        }
        root.add(buttons, BorderLayout.SOUTH);

        setMinimumSize(new Dimension(420, 520));
        setSize(460, 640);
        setLocationByPlatform(true);
        update(node.model().snapshot());

        // animation timer: ~25 fps smooth water movement and waves (EDT timer, so thread-safe)
        new Timer(40, e -> tankView.animate()).start();
    }

    private void addRow(JPanel p, GridBagConstraints c, String name, JLabel value) {
        c.gridy++; c.gridx = 0; c.weightx = 0; c.insets = new Insets(2, 0, 2, 8);
        JLabel n = new JLabel(name + ":");
        n.setForeground(Color.GRAY);
        p.add(n, c);
        c.gridx = 1; c.weightx = 1; c.insets = new Insets(2, 0, 2, 0);
        p.add(value, c);
    }

    private void setConnected(boolean ok) {
        connLabel.setText(ok ? "Connected to main tank" : "Reconnecting...");
        connLabel.setBackground(ok ? GREEN : LOW_ORANGE);
    }

    static Color colourFor(double fraction) {
        if (fraction < 0.10) return CRIT_RED;
        if (fraction < 0.30) return LOW_ORANGE;
        return OK_BLUE;
    }

    // TankNode.Ui (called from any thread)

    @Override public void log(final String line) {
        final String stamp;
        synchronized (timeFmt) { stamp = timeFmt.format(new Date()); }
        SwingUtilities.invokeLater(() -> {
            logArea.append(stamp + "  " + line + "\n");
            // keep the log from growing forever
            if (logArea.getLineCount() > 500) {
                try {
                    logArea.replaceRange("", 0, logArea.getLineStartOffset(100));
                } catch (javax.swing.text.BadLocationException ignored) { }
            }
        });
    }

    @Override public void update(final Snapshot s) {
        SwingUtilities.invokeLater(() -> {
            setConnected(s.connected);
            tankView.setTarget(s.fraction(), s.inflow > 0);
            percentLabel.setText(String.format(Locale.ROOT, "%.0f %%", s.fraction() * 100));
            percentLabel.setForeground(colourFor(s.fraction()));
            litresLabel.setText(String.format(Locale.ROOT, "%.0f / %.0f L", s.level, s.capacity));
            demandLabel.setText(String.format(Locale.ROOT, "%.1f L/s%s", s.demand, s.paused ? " (paused)" : ""));
            inflowLabel.setText(String.format(Locale.ROOT, "%.1f L/s", s.inflow));
            pendingLabel.setText(String.format(Locale.ROOT, "%.0f L", s.pending));
            supplyLabel.setText(s.supplyPct <= 0 ? "CLOSED" : s.supplyPct >= 100 ? "OPEN" : String.format(Locale.ROOT, "%.0f %%", s.supplyPct));
            supplyLabel.setForeground(s.supplyPct <= 0 ? CRIT_RED : s.supplyPct >= 100 ? GREEN : LOW_ORANGE);
            leakLabel.setText(s.leak ? "LEAKING (+12 L/s)" : "none");
            leakLabel.setForeground(s.leak ? CRIT_RED : Color.DARK_GRAY);
            replyLabel.setText(s.lastReply);
            replyLabel.setToolTipText(s.lastReply);
            pauseBtn.setText(s.paused ? "Resume demand" : "Pause demand");
        });
    }

    @Override public void fatal(final String message) {
        log("FATAL: " + message);
        SwingUtilities.invokeLater(() -> {
            connLabel.setText("Stopped: " + message);
            connLabel.setBackground(CRIT_RED);
            JOptionPane.showMessageDialog(this, message + "\nThe node will not retry. Close it and start with another id.",
                    "AquaNet", JOptionPane.ERROR_MESSAGE);
        });
    }

    // start dialog

    /** Small dialog shown when the program is started without arguments. Returns null if cancelled. */
    static TankNode.Config askConfig() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        String[] choices = new String[TankNode.AREAS.length + 1];
        for (int i = 0; i < TankNode.AREAS.length; i++) choices[i] = TankNode.AREAS[i][2];
        choices[choices.length - 1] = "Custom...";
        JComboBox<String> area = new JComboBox<>(choices);
        JTextField id = new JTextField("TANK1", 12);
        JTextField name = new JTextField("", 12);
        JTextField host = new JTextField("localhost", 12);
        id.setEnabled(false); name.setEnabled(false);
        area.addActionListener(e -> {
            boolean custom = area.getSelectedIndex() == choices.length - 1;
            id.setEnabled(custom); name.setEnabled(custom);
        });
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("Area:")); p.add(area);
        p.add(new JLabel("Custom id:")); p.add(id);
        p.add(new JLabel("Custom name:")); p.add(name);
        p.add(new JLabel("Main tank host:")); p.add(host);
        while (true) {
            int r = JOptionPane.showConfirmDialog(null, p, "AquaNet Tank Node", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            TankNode.Config c = new TankNode.Config();
            if (area.getSelectedIndex() < TankNode.AREAS.length) {
                TankNode.applyArea(c, TankNode.AREAS[area.getSelectedIndex()][0]);
            } else {
                c.id = id.getText().trim();
                c.name = name.getText().trim();
            }
            c.host = host.getText().trim().isEmpty() ? "localhost" : host.getText().trim();
            if (!c.id.matches("[A-Za-z0-9_]{2,16}")) {
                JOptionPane.showMessageDialog(null, "Id must be 2-16 characters: letters, digits, underscore.");
                continue;
            }
            c.name = c.name == null || c.name.isEmpty() ? c.id : c.name;
            return TankNode.validate(c);
        }
    }

    // custom-painted tank

    /** Draws the tank with animated water. animate() and setTarget() run only on the EDT. */
    private static final class TankView extends JPanel {
        private double target = 0, shown = 0, phase = 0;
        private boolean filling;

        TankView() { setOpaque(false); }

        void setTarget(double fraction, boolean filling) {
            this.target = Math.max(0, Math.min(1, fraction));
            this.filling = filling;
        }

        void animate() {
            shown += (target - shown) * 0.12;       // ease towards the real level
            phase += 0.18;
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int pad = 14;
            double tx = pad + 14, ty = pad + 10, tw = w - 2 * pad - 28, th = h - 2 * pad - 14;
            if (tw < 40 || th < 40) { g.dispose(); return; }
            RoundRectangle2D body = new RoundRectangle2D.Double(tx, ty, tw, th, 26, 26);

            // empty tank background
            g.setColor(new Color(0xEEF3F7));
            g.fill(body);

            // water, clipped to the tank shape
            Color water = colourFor(shown);
            double waterH = th * shown;
            double surfaceY = ty + th - waterH;
            java.awt.Shape oldClip = g.getClip();
            g.clip(body);
            Path2D wave = new Path2D.Double();
            wave.moveTo(tx, ty + th);
            wave.lineTo(tx, surfaceY);
            double amp = Math.min(4, waterH / 4);
            for (double x = 0; x <= tw; x += 3) {
                wave.lineTo(tx + x, surfaceY + amp * Math.sin(phase + x / 14.0));
            }
            wave.lineTo(tx + tw, ty + th);
            wave.closePath();
            g.setColor(new Color(water.getRed(), water.getGreen(), water.getBlue(), 215));
            g.fill(wave);
            g.setClip(oldClip);

            // 30 % and 10 % marks
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 4f}, 0f));
            g.setFont(getFont().deriveFont(10f));
            double[] marks = {0.30, 0.10};
            for (double m : marks) {
                int y = (int) (ty + th - th * m);
                g.setColor(m > 0.2 ? LOW_ORANGE : CRIT_RED);
                g.drawLine((int) tx, y, (int) (tx + tw), y);
                g.drawString((int) (m * 100) + "%", (int) (tx + tw + 3), y + 4);
            }

            // outline
            g.setStroke(new BasicStroke(3f));
            g.setColor(new Color(0x56616B));
            g.draw(body);

            // inlet pipe + falling drops while water is being delivered
            g.setColor(new Color(0x56616B));
            g.fillRect((int) (tx + tw / 2 - 6), 2, 12, (int) ty - 2);
            if (filling) {
                g.setColor(OK_BLUE);
                for (int i = 0; i < 4; i++) {
                    double yy = ty + ((phase * 14 + i * 22) % (surfaceY - ty + 1));
                    g.fillOval((int) (tx + tw / 2 - 3), (int) yy, 6, 8);
                }
            }
            g.dispose();
        }
    }
}
