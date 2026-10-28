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

/** Runtime observations and APK-matched coverage in the main window. */
final class RuntimeDashboard extends JPanel {
    private final JFrame owner;
    private final GuiSettings settings;
    private final JTextField tag = field("ANDROLOG", 10), search = field("", 16);
    private final JLabel status = muted("Open a runtime log, then choose the original APK to calculate coverage."), subtitle = muted("Open a log and compare its original APK to see coverage."), details = muted(" ");
    private final CoverageCard[] values = new CoverageCard[8];
    private static final String[] STAT_KEYS = {"STATEMENT", "METHOD", "CLASS", "ACTIVITY", "SERVICE", "BROADCASTRECEIVER", "CONTENTPROVIDER", "CUSTOM"};
    private static final String[] REPORT_KEYS = {"statements", "methods", "classes", "activities", "services", "broadcast-receivers", "content-providers", ""};
    private static final Color[] COLORS = {new Color(0x438FE2), new Color(0xEF8058), new Color(0x25AE88), new Color(0xD8AF2C),
            new Color(0x5BA4A0), new Color(0xDC8A62), new Color(0x9E7BE6), new Color(0x6293AD)};
    private final JComboBox<String> category = new JComboBox<>(RuntimeLog.TYPES.values().toArray(new String[0]));
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Observed item", "Records"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) { return column == 1 ? Long.class : String.class; }
    };
    private final JTable table = new JTable(model);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
    private final Timeline timeline = new Timeline();
    private final JButton open = button("1. Open log…", true);
    private final JButton reload = button("Reload", false);
    private final JButton compare = button("2. Compare APK…", false);
    private Path file;
    private RuntimeLog data;
    private String loadedTag;
    private Map<String, CoverageJob.Metric> coverage = Collections.emptyMap();
    private ComparisonSetup comparison;

    RuntimeDashboard(JFrame owner, GuiSettings settings) {
        super(new BorderLayout(0, 12)); this.owner = owner; this.settings = settings; setOpaque(false);
        JPanel title = panel(new BorderLayout(10, 3));
        title.add(label("EXECUTION INSIGHTS", 11, ACCENT, true), BorderLayout.NORTH);
        title.add(label("Coverage dashboard", 29, INK, true), BorderLayout.CENTER);
        title.add(subtitle, BorderLayout.SOUTH); add(title, BorderLayout.NORTH);
        JPanel body = new ScrollBody();
        JPanel top = panel(new BorderLayout(10, 0));
        JPanel input = panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        input.add(muted("Logcat tag")); tag.setToolTipText("Use the same tag as when the APK was instrumented."); input.add(tag);
        reload.setEnabled(false); reload.addActionListener(e -> load(file));
        compare.setToolTipText("Compare this run with the original APK for coverage statistics");
        compare.addActionListener(e -> compareCoverage()); compare.setEnabled(false);
        input.add(open); input.add(reload); input.add(compare);
        top.add(input, BorderLayout.WEST); body.add(top, BorderLayout.NORTH);
        open.addActionListener(e -> chooseFile());
        JPanel center = panel(new BorderLayout(0, 12));
        JPanel cards = panel(new GridLayout(2, 4, 9, 9));
        for (int i = 0; i < STAT_KEYS.length; i++) {
            String key = STAT_KEYS[i]; String titleText = RuntimeLog.TYPES.get(key);
            final int index = new ArrayList<>(RuntimeLog.TYPES.keySet()).indexOf(key);
            values[i] = new CoverageCard(titleText, COLORS[i]);
            values[i].setToolTipText("Show distinct observed " + titleText.toLowerCase(Locale.ROOT));
            values[i].addActionListener(e -> { search.setText(""); category.setSelectedIndex(index); });
            cards.add(values[i]);
        }
        JPanel overview = panel(new BorderLayout(0, 8));
        overview.add(label("COVERAGE OVERVIEW", 11, MUTED, true), BorderLayout.NORTH); overview.add(cards, BorderLayout.CENTER);
        center.add(overview, BorderLayout.NORTH);
        JPanel main = panel(new BorderLayout(0, 10));
        Card activity = new Card(new BorderLayout(8, 3)); activity.setBorder(new EmptyBorder(10, 14, 8, 14));
        activity.add(label("Recorded activity", 14, INK, true), BorderLayout.NORTH); activity.add(timeline, BorderLayout.CENTER);
        activity.add(muted("Records per recorded minute · gaps omitted"), BorderLayout.SOUTH);
        activity.setPreferredSize(new Dimension(0, 105)); main.add(activity, BorderLayout.NORTH);
        Card listing = new Card(new BorderLayout(0, 8)); listing.setBorder(new EmptyBorder(12, 14, 12, 14));
        listing.setPreferredSize(new Dimension(0, 190));
        JPanel controls = panel(new BorderLayout(8, 0));
        category.setFont(font(13, false)); category.getAccessibleContext().setAccessibleName("Observation category");
        controls.add(category, BorderLayout.WEST); search.getAccessibleContext().setAccessibleName("Filter observed items");
        search.setToolTipText("Filter the selected category"); controls.add(search, BorderLayout.CENTER); listing.add(controls, BorderLayout.NORTH);
        table.setFont(font(12, false)); table.setRowHeight(25); table.setRowSorter(sorter); table.setFillsViewportHeight(true);
        table.getAccessibleContext().setAccessibleName("Observed items"); table.getColumnModel().getColumn(1).setMaxWidth(90);
        JScrollPane scroll = new JScrollPane(table); scroll.setBorder(BorderFactory.createEmptyBorder()); listing.add(scroll, BorderLayout.CENTER);
        listing.add(details, BorderLayout.SOUTH); main.add(listing, BorderLayout.CENTER); center.add(main, BorderLayout.CENTER);
        body.add(center, BorderLayout.CENTER); body.add(status, BorderLayout.SOUTH);
        JScrollPane dashboardScroll = new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        dashboardScroll.setBorder(BorderFactory.createEmptyBorder()); dashboardScroll.setOpaque(false); dashboardScroll.getViewport().setOpaque(false);
        dashboardScroll.getVerticalScrollBar().setUnitIncrement(18); add(dashboardScroll, BorderLayout.CENTER);
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
        String selectedTag = tag.getText().trim(); setBusy(true); status.setText("Reading " + candidate.getFileName() + "…");
        new SwingWorker<RuntimeLog, Void>() {
            protected RuntimeLog doInBackground() throws Exception { return RuntimeLog.read(candidate, selectedTag); }
            protected void done() {
                setBusy(false);
                try {
                    RuntimeLog result = get();
                    boolean recompare = comparison != null && candidate.toAbsolutePath().normalize().equals(file) && selectedTag.equals(loadedTag);
                    data = result; loadedTag = selectedTag; file = candidate.toAbsolutePath().normalize();
                    subtitle.setText(file.getFileName() + "  ·  tag " + selectedTag); subtitle.setToolTipText(file.toString());
                    coverage = Collections.emptyMap(); refreshMetrics(); compare.setEnabled(true); reload.setEnabled(true);
                    timeline.setData(result.minutes); refreshRows();
                    status.setText(result.recognized == 0 ? "No AndroLog records found. Check the tag and log file."
                            : String.format(Locale.ROOT, "%,d matching records  ·  %,d lines read", result.recognized, result.lines));
                    if (recompare) startComparison(comparison);
                    else { comparison = null; compare.setText("2. Compare APK…"); }
                } catch (Exception e) {
                    Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    status.setText("Could not read the log: " + cause.getMessage());
                }
            }
        }.execute();
    }
    private void setBusy(boolean busy) {
        open.setEnabled(!busy); reload.setEnabled(!busy && file != null); tag.setEnabled(!busy); compare.setEnabled(!busy && data != null);
    }
    private void refreshMetrics() {
        for (int i = 0; i < STAT_KEYS.length; i++) {
            CoverageJob.Metric matched = coverage.get(REPORT_KEYS[i]);
            long count = data.entries.get(STAT_KEYS[i]).size();
            if (matched == null) values[i].showObservation(count, !coverage.isEmpty());
            else values[i].showCoverage(matched);
        }
    }
    private void compareCoverage() {
        if (data == null) return;
        if (!tag.getText().trim().equals(loadedTag)) {
            JOptionPane.showMessageDialog(owner, "Reload the log with the new tag before comparing coverage."); return;
        }
        if (!GuiSettings.validPlatforms(settings.platforms)) {
            JOptionPane.showMessageDialog(owner, "Choose an Android SDK platforms folder in SDK settings first."); return;
        }
        FileDialog chooser = new FileDialog(owner, "Choose the original APK", FileDialog.LOAD);
        chooser.setFilenameFilter((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".apk"));
        chooser.setVisible(true);
        if (chooser.getFile() == null) { chooser.dispose(); return; }
        Path apk = Paths.get(chooser.getDirectory(), chooser.getFile()); chooser.dispose();
        JPanel form = panel(new GridLayout(0, 1, 0, 5));
        form.add(muted("Select the probes and scope used when this APK was instrumented."));
        String[] titles = {"Classes", "Methods", "Statements", "Android components"};
        String[] keys = {"CLASS", "METHOD", "STATEMENT", "ACTIVITY"};
        JCheckBox[] options = new JCheckBox[4];
        for (int i = 0; i < options.length; i++) {
            boolean found = !data.entries.get(keys[i]).isEmpty();
            if (i == 3) for (String key : Arrays.asList("SERVICE", "BROADCASTRECEIVER", "CONTENTPROVIDER")) found |= !data.entries.get(key).isEmpty();
            options[i] = new JCheckBox(titles[i], found); form.add(options[i]);
        }
        form.add(muted("Package prefix used for instrumentation (optional)")); JTextField prefix = field("", 20); form.add(prefix);
        JCheckBox exclude = new JCheckBox("Exclude known libraries"); form.add(exclude);
        form.add(muted("Only select probes that were enabled; a missing record does not prove a probe was disabled."));
        if (JOptionPane.showConfirmDialog(owner, form, "Compare with " + apk.getFileName(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        boolean[] probes = new boolean[4]; for (int i = 0; i < probes.length; i++) probes[i] = options[i].isSelected();
        comparison = null; compare.setText("2. Compare APK…");
        startComparison(new ComparisonSetup(apk, probes, prefix.getText().trim(), exclude.isSelected(), null));
    }
    private void startComparison(ComparisonSetup setup) {
        String selectedTag = loadedTag, hash = data.sourceHash;
        Path runtime = file;
        coverage = Collections.emptyMap(); refreshMetrics(); setBusy(true); status.setText("Analyzing the original APK and matching observations…");
        new SwingWorker<ComparisonResult, Void>() {
            protected ComparisonResult doInBackground() throws Exception {
                String apkHash = com.jordansamhi.androlog.CustomLogPlan.sha256(setup.apk);
                if (setup.apkHash != null && !setup.apkHash.equals(apkHash)) throw new java.io.IOException("The APK changed. Choose the original APK again.");
                try (CoverageJob job = new CoverageJob(setup.apk, runtime, settings, selectedTag, setup.probes, setup.scope, setup.exclude, hash)) {
                    Map<String, CoverageJob.Metric> metrics = job.run();
                    if (!apkHash.equals(com.jordansamhi.androlog.CustomLogPlan.sha256(setup.apk))) throw new java.io.IOException("The APK changed during analysis.");
                    return new ComparisonResult(metrics, apkHash);
                }
            }
            protected void done() {
                setBusy(false);
                try {
                    ComparisonResult result = get(); coverage = result.metrics;
                    comparison = new ComparisonSetup(setup.apk, setup.probes, setup.scope, setup.exclude, result.apkHash);
                    compare.setText("Change APK…"); refreshMetrics(); refreshRows();
                    subtitle.setText(runtime.getFileName() + "  ·  " + setup.apk.getFileName() + "  ·  tag " + selectedTag);
                    status.setText("Coverage compared with " + setup.apk.getFileName() + " · matching probes and scope required");
                } catch (Exception e) {
                    Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    String message = String.valueOf(cause.getMessage()).replace('\n', ' ').replace('\r', ' ');
                    status.setText("Coverage comparison failed: " + (message.length() > 180 ? message.substring(0, 180) + "…" : message));
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
        else details.setText(coverage.isEmpty() ? "Distinct log observations; record counts are not execution frequency."
                : "List shows raw logs; coverage cards count observations matched to the APK.");
        filter();
    }
    private void filter() {
        String text = search.getText().trim(); sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text), 0));
    }
    private static final class ComparisonSetup {
        final Path apk; final boolean[] probes; final String scope, apkHash; final boolean exclude;
        ComparisonSetup(Path apk, boolean[] probes, String scope, boolean exclude, String apkHash) {
            this.apk = apk; this.probes = probes.clone(); this.scope = scope; this.exclude = exclude; this.apkHash = apkHash;
        }
    }
    private static final class ComparisonResult {
        final Map<String, CoverageJob.Metric> metrics; final String apkHash;
        ComparisonResult(Map<String, CoverageJob.Metric> metrics, String apkHash) { this.metrics = metrics; this.apkHash = apkHash; }
    }
    private static final class ScrollBody extends JPanel implements Scrollable {
        ScrollBody() { super(new BorderLayout(0, 12)); setOpaque(false); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(18, visible.height - 18); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
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
