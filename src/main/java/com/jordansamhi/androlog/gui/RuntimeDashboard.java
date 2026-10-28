package com.jordansamhi.androlog.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import static com.jordansamhi.androlog.gui.GuiTheme.*;

/** Local, log-only execution dashboard embedded in the main window. */
final class RuntimeDashboard extends JPanel {
    private final JFrame owner;
    private final JTextField tag = field("ANDROLOG", 10), search = field("", 16);
    private final JLabel status = muted("Choose a logcat file to explore a run."), subtitle = muted("No run loaded yet."), details = muted(" ");
    private final JButton[] values = new JButton[4];
    private final JComboBox<String> category = new JComboBox<>(RuntimeLog.TYPES.values().toArray(new String[0]));
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Observed item", "Records"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) { return column == 1 ? Long.class : String.class; }
    };
    private final JTable table = new JTable(model);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
    private final Timeline timeline = new Timeline();
    private final JButton open = button("Open log…", true);
    private final JButton reload = button("Reload", false);
    private Path file;
    private RuntimeLog data;

    RuntimeDashboard(JFrame owner) {
        super(new BorderLayout(0, 12)); this.owner = owner; setOpaque(false);
        JPanel title = panel(new BorderLayout(10, 3));
        title.add(label("EXECUTION INSIGHTS", 11, ACCENT, true), BorderLayout.NORTH);
        title.add(label("Explore your run.", 30, INK, true), BorderLayout.CENTER);
        title.add(subtitle, BorderLayout.SOUTH); add(title, BorderLayout.NORTH);
        JPanel body = panel(new BorderLayout(0, 12));
        JPanel top = panel(new BorderLayout(10, 0));
        JPanel input = panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        input.add(muted("Logcat tag")); tag.setToolTipText("Use the same tag as when the APK was instrumented."); input.add(tag);
        reload.addActionListener(e -> load(file)); input.add(reload);
        top.add(input, BorderLayout.WEST); top.add(open, BorderLayout.EAST); body.add(top, BorderLayout.NORTH);
        open.addActionListener(e -> chooseFile());
        JPanel center = panel(new BorderLayout(0, 12));
        JPanel cards = panel(new GridLayout(1, 4, 10, 0));
        String[] titles = {"Classes", "Methods", "Statements", "Custom messages"};
        int[] categoryIndexes = {0, 1, 2, 7};
        for (int i = 0; i < titles.length; i++) {
            final int index = categoryIndexes[i];
            values[i] = button(metric(titles[i], "—"), false);
            values[i].getAccessibleContext().setAccessibleName("Show " + titles[i]);
            values[i].setToolTipText("Show distinct observed " + titles[i].toLowerCase(Locale.ROOT));
            values[i].addActionListener(e -> { search.setText(""); category.setSelectedIndex(index); });
            cards.add(values[i]);
        }
        center.add(cards, BorderLayout.NORTH);
        JPanel main = panel(new BorderLayout(0, 10));
        Card activity = new Card(new BorderLayout(8, 3)); activity.setBorder(new EmptyBorder(10, 14, 8, 14));
        activity.add(label("Recorded activity", 14, INK, true), BorderLayout.NORTH); activity.add(timeline, BorderLayout.CENTER);
        activity.add(muted("Records per recorded minute · gaps omitted"), BorderLayout.SOUTH);
        activity.setPreferredSize(new Dimension(0, 130)); main.add(activity, BorderLayout.NORTH);
        Card listing = new Card(new BorderLayout(0, 8)); listing.setBorder(new EmptyBorder(12, 14, 12, 14));
        JPanel controls = panel(new BorderLayout(8, 0));
        category.setFont(font(13, false)); category.getAccessibleContext().setAccessibleName("Observation category");
        controls.add(category, BorderLayout.WEST); search.getAccessibleContext().setAccessibleName("Filter observed items");
        search.setToolTipText("Filter the selected category"); controls.add(search, BorderLayout.CENTER); listing.add(controls, BorderLayout.NORTH);
        table.setFont(font(12, false)); table.setRowHeight(25); table.setRowSorter(sorter); table.setFillsViewportHeight(true);
        table.getAccessibleContext().setAccessibleName("Observed items"); table.getColumnModel().getColumn(1).setMaxWidth(90);
        JScrollPane scroll = new JScrollPane(table); scroll.setBorder(BorderFactory.createEmptyBorder()); listing.add(scroll, BorderLayout.CENTER);
        listing.add(details, BorderLayout.SOUTH); main.add(listing, BorderLayout.CENTER); center.add(main, BorderLayout.CENTER);
        body.add(center, BorderLayout.CENTER); body.add(status, BorderLayout.SOUTH); add(body, BorderLayout.CENTER);
        category.addActionListener(e -> refreshRows());
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); } public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        details.setText("Counts show observed items, not coverage. Select a category to explore it.");
    }
    private void chooseFile() {
        FileDialog chooser = new FileDialog(owner, "Open a runtime log", FileDialog.LOAD);
        if (file != null) chooser.setDirectory(file.getParent().toString());
        chooser.setVisible(true);
        if (chooser.getFile() != null) load(Paths.get(chooser.getDirectory(), chooser.getFile()));
        chooser.dispose();
    }
    private void load(Path candidate) {
        if (candidate == null) { chooseFile(); return; }
        String selectedTag = tag.getText().trim(); open.setEnabled(false); reload.setEnabled(false); tag.setEnabled(false); status.setText("Reading " + candidate.getFileName() + "…");
        new SwingWorker<RuntimeLog, Void>() {
            protected RuntimeLog doInBackground() throws Exception { return RuntimeLog.read(candidate, selectedTag); }
            protected void done() {
                open.setEnabled(true); reload.setEnabled(true); tag.setEnabled(true);
                try {
                    RuntimeLog result = get(); data = result; file = candidate.toAbsolutePath().normalize();
                    subtitle.setText(file.getFileName() + "  ·  tag " + selectedTag); subtitle.setToolTipText(file.toString());
                    String[] keys = {"CLASS", "METHOD", "STATEMENT", "CUSTOM"};
                    for (int i = 0; i < keys.length; i++) values[i].setText(metric(RuntimeLog.TYPES.get(keys[i]), String.format(Locale.ROOT, "%,d", result.entries.get(keys[i]).size())));
                    timeline.setData(result.minutes); refreshRows();
                    status.setText(result.recognized == 0 ? "No AndroLog records found. Check the tag and log file."
                            : String.format(Locale.ROOT, "%,d matching records  ·  %,d lines read", result.recognized, result.lines));
                } catch (Exception e) {
                    Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    status.setText("Could not read the log: " + cause.getMessage());
                }
            }
        }.execute();
    }
    private void refreshRows() {
        table.setRowSorter(null); model.setRowCount(0);
        if (data == null) { table.setRowSorter(sorter); return; }
        String key = new ArrayList<>(RuntimeLog.TYPES.keySet()).get(category.getSelectedIndex());
        List<Map.Entry<String, Long>> entries = new ArrayList<>(data.entries.get(key).entrySet());
        entries.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        for (Map.Entry<String, Long> entry : entries) model.addRow(new Object[]{entry.getKey(), entry.getValue()});
        table.setRowSorter(sorter);
        if ("CUSTOM".equals(key)) details.setText("Custom message records count each occurrence in this log.");
        else if ("CALLS".equals(key)) details.setText("Raw call records; not validated edges or invocation counts. Use the report for the dynamic call graph.");
        else details.setText("Distinct observed items; record counts are not execution frequency. No APK baseline is loaded.");
        filter();
    }
    private void filter() {
        String text = search.getText().trim(); sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text), 0));
    }
    private static String metric(String title, String value) {
        return "<html><div style='text-align:center;color:#536e7d;font-size:10px'>" + html(title.toUpperCase(Locale.ROOT))
                + "<br><span style='color:#007e79;font-size:22px'>" + html(value) + "</span></div></html>";
    }
    private static final class Timeline extends JComponent {
        private Map<String, Long> minutes = Collections.emptyMap();
        Timeline() { setPreferredSize(new Dimension(100, 48)); }
        void setData(Map<String, Long> minutes) { this.minutes = new TreeMap<>(minutes); repaint(); }
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0); Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (minutes.isEmpty()) { g.setColor(MUTED); g.setFont(font(12, false)); g.drawString("No timestamped records", 2, 24); g.dispose(); return; }
            int count = minutes.size(), w = Math.max(1, getWidth()), h = Math.max(1, getHeight()-3); long max = Collections.max(minutes.values()); int i=0;
            for (long n : minutes.values()) { int x = i*w/count, next = (i+1)*w/count, bh = (int)(h * ((double)n/max));
                g.setColor(new Color(0x41AAA0)); g.fillRoundRect(x, h-bh, Math.max(1,next-x-2), Math.max(2,bh), 5, 5); i++; }
            setToolTipText(minutes.keySet().iterator().next() + " to " + ((TreeMap<String, Long>) minutes).lastKey() + " · " + count + " recorded minutes"); g.dispose();
        }
    }
}
