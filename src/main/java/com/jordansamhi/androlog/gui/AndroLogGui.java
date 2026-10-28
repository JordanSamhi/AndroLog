package com.jordansamhi.androlog.gui;

import com.jordansamhi.androlog.CustomLogPlan;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import static com.jordansamhi.androlog.gui.GuiTheme.*;

/** Desktop entry point. The command-line entry point and its defaults remain independent. */
public final class AndroLogGui {
    private final JFrame frame = new JFrame("AndroLog");
    private final GuiSettings settings = GuiSettings.load();
    private final JPanel content = panel(new BorderLayout());
    private final JPanel[] pages = new JPanel[4];
    private final JButton primary = button("Choose an APK", true);
    private final JButton back = button("Back", false);
    private final JButton settingsButton = button("SDK settings", false);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel steps = panel(new GridLayout(0, 1, 0, 9));
    private final JLabel sdkStatus = label("", 12, MUTED, false);
    private final JLabel footnote = muted(" ");
    private final JLabel apkName = label("Drop your APK here", 23, INK, true);
    private final JLabel apkDetail = muted("or choose a file to get started");
    private final JButton browse = button("Choose APK…", false);
    private final JLabel selectedApk = muted("");
    private final JLabel progressTitle = label("Preparing your APK", 30, INK, true);
    private final JLabel progressDetail = muted("Starting a fresh instrumentation session…");
    private final JLabel elapsed = muted("0:00 elapsed");
    private final JLabel[] stageLabels = new JLabel[4];
    private final JLabel[] stepLabels = new JLabel[4];
    private final JLabel resultTitle = label("Your APK is ready.", 32, INK, true);
    private final JLabel resultDetail = muted("Choose where to save your instrumented application.");
    private final JTextArea resultPath = new JTextArea();
    private final JButton reveal = button("Open destination folder", false);
    private final JButton logButton = textButton("Run details…");
    private final JToggleButton[] probes = new JToggleButton[5];
    private final JCheckBox excludeLibraries = new JCheckBox("Exclude known libraries");
    private final JTextField packagePrefix = field("", 18);
    private final JTextField logTag = field("ANDROLOG", 12);
    private final JLabel customLogCount = muted("Choose a point and type a message.");
    private CustomLogPlan customLogs;
    private final InstrumentationAnimation processing = new InstrumentationAnimation();
    private final InstrumentationAnimation complete = new InstrumentationAnimation();
    private final Timer clock;
    private Path apk, savedApk;
    private volatile InstrumentationJob job;
    private volatile boolean cancelRequested;
    private boolean working, saving, closing, unsaved;
    private int page;
    private long started;

    public static void main(String[] args) {
        if (args.length > 1 || (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0])))) {
            System.out.println("AndroLog GUI\nUsage: androlog-gui [path/to/application.apk]\nRequires a desktop session, Java 17+, Android SDK Platforms and Build Tools.");
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("AndroLog GUI needs a graphical desktop. Use the AndroLog command line tool on a headless machine.");
            return;
        }
        System.setProperty("apple.awt.application.name", "AndroLog");
        SwingUtilities.invokeLater(() -> {
            install();
            AndroLogGui gui = new AndroLogGui();
            gui.frame.setVisible(true);
            // A packaged macOS app may start in the background, leaving even
            // editable Swing fields without a native key window.
            if (System.getProperty("os.name").startsWith("Mac") && Desktop.isDesktopSupported()) {
                try {
                    Desktop desktop = Desktop.getDesktop();
                    if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) desktop.requestForeground(true);
                } catch (UnsupportedOperationException | SecurityException ignored) {
                    // The window remains usable on desktops without this action.
                }
                gui.frame.toFront();
                gui.frame.requestFocus();
            }
            if (args.length == 1) gui.selectApk(Paths.get(args[0]));
        });
    }

    private AndroLogGui() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1000, 760));
        frame.setSize(1110, 810);
        frame.setLocationRelativeTo(null);
        AppIcon.install(frame);
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        if (mac) {
            frame.getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            frame.getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            frame.getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
        }
        JPanel root = new Background(new BorderLayout());
        root.setBorder(new EmptyBorder(mac ? 36 : 14, 14, 14, 14));
        root.add(sidebar(), BorderLayout.WEST);
        JPanel workspace = panel(new BorderLayout(0, 14));
        workspace.setBorder(new EmptyBorder(17, 30, 10, 22));
        pages[0] = inputPage(); pages[1] = optionsPage();
        pages[2] = progressPage(); pages[3] = resultPage();
        JPanel instrumentWorkspace = panel(new BorderLayout(0, 14));
        instrumentWorkspace.add(content, BorderLayout.CENTER); instrumentWorkspace.add(footer(), BorderLayout.SOUTH);
        tabs.setUI(new WorkspaceTabs()); tabs.setOpaque(false); tabs.setFont(font(14, true));
        tabs.addTab("Instrument APK", instrumentWorkspace);
        tabs.addTab("Execution dashboard", new RuntimeDashboard(frame, settings));
        tabs.addChangeListener(event -> { steps.setVisible(tabs.getSelectedIndex() == 0); });
        workspace.add(tabs, BorderLayout.CENTER);
        root.add(workspace, BorderLayout.CENTER); frame.setContentPane(root);
        primary.addActionListener(event -> primaryAction());
        back.addActionListener(event -> { if (page == 3) startAnother(); else showPage(0); });
        settingsButton.addActionListener(event -> showSettings());
        browse.addActionListener(event -> chooseApk());
        logButton.addActionListener(event -> showLog());
        logButton.setToolTipText("Open the instrumentation log for troubleshooting");
        reveal.addActionListener(event -> revealOutput());
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { closeWindow(); }
        });
        clock = new Timer(1000, event -> {
            long seconds = (System.nanoTime() - started) / 1_000_000_000;
            elapsed.setText(String.format(Locale.ROOT, "%d:%02d elapsed", seconds / 60, seconds % 60));
        });
        InputMap keys = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_O, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()), "open");
        root.getActionMap().put("open", new AbstractAction() {
            public void actionPerformed(ActionEvent event) { if (tabs.getSelectedIndex() == 0 && !working && !saving && !unsaved) chooseApk(); }
        });
        root.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return tabs.getSelectedIndex() == 0 && !working && !saving && !unsaved && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    List<?> files = (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.size() != 1 || !(files.get(0) instanceof File)) { error("Drop one APK at a time."); return false; }
                    selectApk(((File) files.get(0)).toPath()); return true;
                } catch (Exception failure) { error(failure.getMessage()); return false; }
            }
        });
        refreshSettings(); showPage(0);
    }

    private JPanel sidebar() {
        JPanel side = new Card(new BorderLayout());
        side.setPreferredSize(new Dimension(236, 0)); side.setBorder(new EmptyBorder(26, 18, 25, 18));
        JPanel upper = panel(new BorderLayout(0, 38));
        JPanel brand = panel(new BorderLayout(0, 12));
        JLabel icon = new JLabel(AppIcon.icon(76)); icon.setHorizontalAlignment(SwingConstants.LEFT);
        icon.getAccessibleContext().setAccessibleName("AndroLog icon"); brand.add(icon, BorderLayout.NORTH);
        brand.add(label("AndroLog", 29, INK, true), BorderLayout.CENTER);
        brand.add(label("APK instrumentation", 12, MUTED, false), BorderLayout.SOUTH);
        brand.setBorder(new EmptyBorder(0, 10, 0, 0));
        upper.add(brand, BorderLayout.NORTH);
        String[] names = {"Choose an APK", "Choose your logs", "Instrument", "Save your APK"};
        for (int i = 0; i < names.length; i++) {
            stepLabels[i] = step("0" + (i + 1) + "   " + names[i]);
            steps.add(stepLabels[i]);
        }
        upper.add(steps, BorderLayout.CENTER); side.add(upper, BorderLayout.NORTH);
        JPanel bottom = panel(new BorderLayout(0, 9));
        sdkStatus.setBorder(new EmptyBorder(0, 8, 0, 0)); bottom.add(sdkStatus, BorderLayout.NORTH);
        JPanel sdkActions = panel(new BorderLayout(4, 0));
        sdkActions.add(settingsButton, BorderLayout.CENTER);
        JButton help = button("?", false); help.setBorder(new EmptyBorder(13, 15, 13, 15));
        help.setToolTipText("What is the Android SDK, and how do I set it up?");
        help.getAccessibleContext().setAccessibleName("Android SDK help");
        help.addActionListener(event -> showSdkHelp(frame)); sdkActions.add(help, BorderLayout.EAST);
        bottom.add(sdkActions, BorderLayout.CENTER);
        bottom.add(label("AndroLog 0.1", 11, MUTED, false), BorderLayout.SOUTH);
        side.add(bottom, BorderLayout.SOUTH); return side;
    }

    private JPanel header(String eyebrow, String title, String subtitle) {
        JPanel header = panel(new BorderLayout(0, 9));
        header.add(label(eyebrow, 11, ACCENT, true), BorderLayout.NORTH);
        header.add(label(title, 32, INK, true), BorderLayout.CENTER);
        header.add(muted(subtitle), BorderLayout.SOUTH);
        header.setBorder(new EmptyBorder(0, 0, 26, 0)); return header;
    }

    private JPanel inputPage() {
        JPanel page = panel(new BorderLayout(0, 20));
        page.add(header("APK INSTRUMENTATION", "See inside your app.",
                "Choose an APK, add logging, and save a signed copy."), BorderLayout.NORTH);
        Card drop = new Card(new GridBagLayout());
        JPanel center = panel(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.insets = new Insets(7, 0, 7, 0);
        InstrumentationAnimation illustration = new InstrumentationAnimation();
        illustration.setAnimationEnabled(false); illustration.setPreferredSize(new Dimension(420, 175));
        illustration.setMinimumSize(new Dimension(300, 140));
        c.gridy = 0; center.add(illustration, c);
        c.gridy++; center.add(apkName, c); c.gridy++; center.add(apkDetail, c); c.gridy++; center.add(browse, c);
        drop.add(center); page.add(drop, BorderLayout.CENTER);
        JPanel note = panel(new GridLayout(0, 1, 0, 8));
        note.add(label("Your app, with a little more insight.", 15, INK, true));
        note.add(muted("Choose what gets logged. Keep the original. Save a signed copy."));
        page.add(note, BorderLayout.SOUTH); return page;
    }

    private JPanel optionsPage() {
        JPanel page = panel(new BorderLayout(0, 16));
        page.add(header("MAKE IT YOURS", "Choose what to observe.", "Use automatic logging or add your own messages at selected code points."), BorderLayout.NORTH);
        JPanel choices = panel(new BorderLayout(0, 14));
        JPanel presets = panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton light = button("Essentials", false), all = button("Everything", false), customOnly = button("Custom only", false);
        light.addActionListener(event -> preset(new boolean[]{true, true, false, true, false}));
        all.addActionListener(event -> preset(new boolean[]{true, true, true, true, true}));
        customOnly.addActionListener(event -> { preset(new boolean[probes.length]); if (customLogs == null) chooseCustomLogs(); });
        customOnly.setToolTipText("Turn off automatic probes and use only the messages you place in the code");
        presets.add(light); presets.add(all); presets.add(customOnly);
        choices.add(presets, BorderLayout.NORTH);
        JPanel grid = panel(new GridLayout(3, 2, 12, 12));
        String[][] details = {
                {"Classes", "Which classes are reached", "C"},
                {"Methods", "Which methods are entered", "m"},
                {"Statements", "Coverage of individual statements", "≡"},
                {"Android components", "Activities, services, receivers, providers", "◈"},
                {"Dynamic call graph", "Calls and runtime dispatch targets", "↗"}};
        for (int i = 0; i < probes.length; i++) {
            probes[i] = probe(details[i][0], details[i][1], details[i][2], i != 2 && i != 4);
            probes[i].addActionListener(event -> updatePrimary()); grid.add(probes[i]);
        }
        JPanel custom = new Card(new BorderLayout(8, 0)); custom.setBorder(new EmptyBorder(13, 15, 13, 12));
        JPanel description = panel(new BorderLayout(0, 6)); description.add(label("Your own messages", 15, INK, true), BorderLayout.NORTH);
        description.add(customLogCount, BorderLayout.CENTER); custom.add(description, BorderLayout.CENTER);
        JButton choosePoints = textButton("Choose…"); choosePoints.getAccessibleContext().setAccessibleName("Choose custom log points");
        choosePoints.setToolTipText("Browse class code and type a message at a selected statement");
        choosePoints.addActionListener(event -> chooseCustomLogs()); custom.add(choosePoints, BorderLayout.EAST);
        grid.add(custom); choices.add(grid, BorderLayout.CENTER);
        page.add(choices, BorderLayout.CENTER);
        JPanel advanced = panel(new BorderLayout(0, 10));
        JPanel fields = panel(new GridLayout(1, 2, 14, 0));
        fields.add(labeledField("Automatic logging package (optional)", packagePrefix)); fields.add(labeledField("Logcat tag", logTag));
        packagePrefix.setToolTipText("Limit automatic probes to this package and its subpackages. Custom points remain explicitly selected.");
        advanced.add(fields, BorderLayout.CENTER);
        excludeLibraries.setOpaque(false); excludeLibraries.setForeground(INK);
        excludeLibraries.setToolTipText("Maps to -n. Known library packages are excluded; unrecognized libraries may still be instrumented.");
        advanced.add(excludeLibraries, BorderLayout.SOUTH); advanced.add(selectedApk, BorderLayout.NORTH);
        page.add(advanced, BorderLayout.SOUTH); return page;
    }

    private JPanel labeledField(String name, JTextField field) {
        JPanel panel = panel(new BorderLayout(0, 7)); JLabel label = muted(name); label.setLabelFor(field);
        panel.add(label, BorderLayout.NORTH); panel.add(field, BorderLayout.CENTER); return panel;
    }

    private JPanel progressPage() {
        JPanel page = panel(new BorderLayout(0, 12));
        JPanel title = panel(new BorderLayout(0, 12));
        title.add(label("A LOOK INSIDE THE APK", 11, ACCENT, true), BorderLayout.NORTH);
        title.add(progressTitle, BorderLayout.CENTER); title.add(progressDetail, BorderLayout.SOUTH);
        page.add(title, BorderLayout.NORTH);
        JPanel body = panel(new BorderLayout(0, 12)); body.add(processing, BorderLayout.CENTER);
        Card stages = new Card(new GridLayout(4, 1, 0, 13));
        String[] labels = {"Read the APK", "Add logging probes", "Rebuild the application", "Align and sign"};
        for (int i = 0; i < stageLabels.length; i++) {
            stageLabels[i] = label("○   " + labels[i], 14, MUTED, false); stages.add(stageLabels[i]);
        }
        body.add(stages, BorderLayout.SOUTH); page.add(body, BorderLayout.CENTER);
        JPanel bottom = panel(new BorderLayout()); bottom.add(elapsed, BorderLayout.WEST); bottom.add(logButton, BorderLayout.EAST);
        page.add(bottom, BorderLayout.SOUTH); return page;
    }

    private JPanel resultPage() {
        JPanel page = panel(new BorderLayout(0, 12));
        JPanel heading = panel(new BorderLayout(0, 12));
        heading.add(label("READY FOR A CLOSER LOOK", 11, ACCENT, true), BorderLayout.NORTH);
        heading.add(resultTitle, BorderLayout.CENTER); heading.add(resultDetail, BorderLayout.SOUTH);
        page.add(heading, BorderLayout.NORTH);
        complete.setStage(5); complete.setAnimationEnabled(false); page.add(complete, BorderLayout.CENTER);
        Card details = new Card(new BorderLayout(0, 12));
        details.add(label("Instrumented. Aligned. Signed.", 19, INK, true), BorderLayout.NORTH);
        resultPath.setEditable(false); resultPath.setLineWrap(true); resultPath.setWrapStyleWord(true);
        resultPath.setOpaque(false); resultPath.setFont(font(13, false)); resultPath.setForeground(MUTED);
        resultPath.setRows(3); resultPath.getAccessibleContext().setAccessibleName("Output APK location");
        details.add(resultPath, BorderLayout.CENTER);
        JPanel actions = panel(new BorderLayout()); actions.add(reveal, BorderLayout.WEST);
        JButton logs = textButton("Run details…"); logs.setToolTipText("Open the instrumentation log for troubleshooting");
        logs.addActionListener(event -> showLog()); actions.add(logs, BorderLayout.EAST);
        details.add(actions, BorderLayout.SOUTH); page.add(details, BorderLayout.SOUTH); return page;
    }

    private JPanel footer() {
        JPanel footer = panel(new BorderLayout(0, 17));
        footer.setBorder(new EmptyBorder(14, 0, 0, 0));
        JPanel buttons = panel(new BorderLayout()); buttons.add(back, BorderLayout.WEST); buttons.add(primary, BorderLayout.EAST);
        footer.add(buttons, BorderLayout.NORTH); footer.add(footnote, BorderLayout.SOUTH); return footer;
    }

    private void showPage(int next) {
        page = next;
        // Keep inactive pages out of the accessibility tree as well as out of sight.
        content.removeAll(); content.add(pages[next], BorderLayout.CENTER); content.revalidate(); content.repaint();
        processing.setAnimationEnabled(next == 2 && settings.animations);
        for (int i = 0; i < stepLabels.length; i++) {
            stepLabels[i].setForeground(i == next ? ACCENT : MUTED);
            stepLabels[i].setFont(font(13, i == next));
            stepLabels[i].putClientProperty("active", i == next); stepLabels[i].repaint();
        }
        back.setVisible(next == 1 || next == 3); back.setText(next == 3 ? "Instrument another APK" : "Back");
        tabs.setEnabledAt(1, !working && !saving);
        settingsButton.setEnabled(!working && !saving);
        footnote.setText(next == 2 || next == 3 ? "The APK uses AndroLog’s test signing key. Use a test device or emulator." : " ");
        updatePrimary();
    }

    private void updatePrimary() {
        String[] labels = {apk == null ? "Choose an APK" : "Choose logging  →", "Instrument APK  →", "Cancel", "Save APK as…"};
        primary.setText(labels[page]);
        boolean any = false; for (JToggleButton probe : probes) if (probe != null && probe.isSelected()) any = true;
        any |= customLogs != null && !customLogs.points.isEmpty();
        primary.setEnabled(!saving && (page != 1 || any));
        if (page == 1 && !any) footnote.setText("Select automatic logging or add a custom log point to continue.");
        else if (page < 2) footnote.setText(" ");
    }

    private void primaryAction() {
        if (page == 0) { if (apk == null) chooseApk(); else showPage(1); }
        else if (page == 1) instrument();
        else if (page == 2) cancel();
        else saveApk();
    }

    private void chooseApk() {
        FileDialog chooser = new FileDialog(frame, "Choose an Android APK", FileDialog.LOAD);
        chooser.setFilenameFilter((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".apk"));
        if (apk != null) chooser.setDirectory(apk.getParent().toString());
        chooser.setVisible(true);
        if (chooser.getFile() != null) selectApk(Paths.get(chooser.getDirectory(), chooser.getFile()));
        chooser.dispose();
    }

    private void selectApk(Path candidate) {
        working = true; primary.setEnabled(false); browse.setEnabled(false); settingsButton.setEnabled(false); tabs.setEnabledAt(1, false);
        new SwingWorker<Long, Void>() {
            protected Long doInBackground() throws Exception { InstrumentationJob.validateApk(candidate); return Files.size(candidate); }
            protected void done() {
                working = false; browse.setEnabled(true); settingsButton.setEnabled(true); tabs.setEnabledAt(1, true);
                try {
                    long bytes = get(); apk = candidate.toAbsolutePath().normalize(); customLogs = null; refreshCustomLogs();
                    String name = apk.getFileName().toString();
                    apkName.setText(name.length() > 40 ? name.substring(0, 37) + "…" : name); apkName.setToolTipText(name);
                    apkDetail.setText((bytes < 1048576 ? String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
                            : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)) + "  ·  Ready to instrument");
                    selectedApk.setText("APK  ·  " + name); selectedApk.setToolTipText(apk.toString()); browse.setText("Choose a different APK…");
                    showPage(0);
                } catch (Exception failure) { error(message(failure)); updatePrimary(); }
                if (closing) dispose();
            }
        }.execute();
    }

    private void preset(boolean[] selected) { for (int i = 0; i < selected.length; i++) probes[i].setSelected(selected[i]); updatePrimary(); }

    private void chooseCustomLogs() {
        if (apk == null) return;
        if (!GuiSettings.validPlatforms(settings.platforms)) { showSettings(); if (!GuiSettings.validPlatforms(settings.platforms)) return; }
        CustomLogDialog.open(frame, apk, settings, customLogs, plan -> {
            customLogs = plan; refreshCustomLogs(); updatePrimary();
        });
    }

    private void refreshCustomLogs() {
        int count = customLogs == null ? 0 : customLogs.points.size();
        customLogCount.setText(count == 0 ? "<html>Select code.<br>Type your message.</html>" : count + (count == 1 ? " log point selected" : " log points selected"));
    }

    private void instrument() {
        try { settings.validate(); }
        catch (IOException missing) { showSettings(); return; }
        boolean[] selected = new boolean[probes.length];
        for (int i = 0; i < selected.length; i++) selected[i] = probes[i].isSelected();
        String prefix = packagePrefix.getText().trim(), tag = logTag.getText().trim();
        boolean exclude = excludeLibraries.isSelected();
        CustomLogPlan logPlan = customLogs;
        working = true; cancelRequested = false; savedApk = null; unsaved = false;
        started = System.nanoTime(); elapsed.setText("0:00 elapsed"); clock.start();
        setStage(0); showPage(2);
        new SwingWorker<Void, Integer>() {
            protected Void doInBackground() throws Exception {
                if (job != null) job.close();
                job = new InstrumentationJob(apk, settings, selected, exclude, prefix, tag, logPlan);
                if (cancelRequested) job.cancel();
                job.run(stage -> publish(stage)); return null;
            }
            protected void process(List<Integer> stages) { if (!cancelRequested) setStage(stages.get(stages.size() - 1)); }
            protected void done() {
                working = false; clock.stop(); processing.setAnimationEnabled(false);
                try {
                    get();
                    if (cancelRequested) throw new CancellationException();
                    unsaved = true; reveal.setEnabled(false); resultTitle.setText("Your APK is ready.");
                    resultDetail.setText("Choose where to save your instrumented application.");
                    resultPath.setText(suggestedFilename() + "\nThe signed APK is ready to save. Your original APK is unchanged.");
                    showPage(3);
                } catch (Exception failure) {
                    showPage(1);
                    if (cancelRequested || failure instanceof CancellationException) footnote.setText("Instrumentation cancelled. Your original APK is unchanged.");
                    else {
                        Object[] options = {"Show details", "OK"};
                        int choice = JOptionPane.showOptionDialog(frame, readableMessage(message(failure)), "Could not instrument APK", JOptionPane.DEFAULT_OPTION,
                                JOptionPane.ERROR_MESSAGE, null, options, options[1]);
                        if (choice == 0) showLog();
                    }
                }
                if (closing) dispose();
            }
        }.execute();
    }

    private void setStage(int stage) {
        String[] titles = {"Preparing your APK", "Reading the APK", "Adding a little insight", "Rebuilding your APK", "Finishing touches"};
        String[] subtitles = {"Starting a fresh instrumentation session…", "Loading the app and resolving Android classes.",
                "Placing the logging probes you selected into the bytecode.", "Writing the modified application back into an APK.", "Aligning the archive and signing it with the AndroLog test key."};
        String[] names = {"Read the APK", "Add logging probes", "Rebuild the application", "Align and sign"};
        progressTitle.setText(titles[stage]); progressDetail.setText(subtitles[stage]);
        processing.setStage(stage); processing.setAnimationEnabled(settings.animations);
        for (int i = 0; i < stageLabels.length; i++) {
            stageLabels[i].setText((i + 1 < stage ? "✓   " : i + 1 == stage ? "●   " : "○   ") + names[i]);
            stageLabels[i].setForeground(i + 1 <= stage ? ACCENT : MUTED);
            stageLabels[i].setFont(font(14, i + 1 == stage));
        }
    }

    private void cancel() {
        cancelRequested = true; primary.setEnabled(false); primary.setText("Stopping…");
        progressDetail.setText("Stopping the worker and its signing tools…");
        new Thread(() -> { InstrumentationJob active = job; if (active != null) active.cancel(); }, "androlog-gui-cancel").start();
    }

    private void saveApk() {
        FileDialog chooser = new FileDialog(frame, "Save instrumented APK", FileDialog.SAVE);
        chooser.setFile(savedApk == null ? suggestedFilename() : savedApk.getFileName().toString());
        chooser.setDirectory((savedApk == null ? apk : savedApk).getParent().toString());
        chooser.setVisible(true);
        if (chooser.getFile() == null) { chooser.dispose(); return; }
        String name = chooser.getFile(); if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) name += ".apk";
        Path destination = Paths.get(chooser.getDirectory(), name); chooser.dispose();
        boolean exists = Files.exists(destination);
        if (exists && JOptionPane.showConfirmDialog(frame, "Replace “" + destination.getFileName() + "”?", "Replace existing file?",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        saving = true; primary.setEnabled(false); primary.setText("Saving…"); back.setEnabled(false); settingsButton.setEnabled(false); tabs.setEnabledAt(1, false); reveal.setEnabled(false);
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception { job.saveApk(destination, exists); return null; }
            protected void done() {
                saving = false; back.setEnabled(true);
                try {
                    get(); savedApk = destination; unsaved = false;
                    resultTitle.setText("Saved. Ready to run."); resultDetail.setText("Install this APK on your test device to start collecting observations.");
                    resultPath.setText(destination.toString());
                } catch (Exception failure) { error(message(failure)); }
                reveal.setEnabled(savedApk != null && Desktop.isDesktopSupported()); showPage(3);
            }
        }.execute();
    }

    private String suggestedFilename() { String name = apk.getFileName().toString(); return name.substring(0, name.length() - 4) + "-androlog.apk"; }

    private void startAnother() {
        if (unsaved && JOptionPane.showConfirmDialog(frame, "This instrumented APK has not been saved. Discard it and start another run?", "Unsaved APK",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        unsaved = false; savedApk = null; showPage(0);
    }

    private void revealOutput() {
        if (savedApk == null) return;
        try { Desktop.getDesktop().open(savedApk.getParent().toFile()); }
        catch (Exception failure) { error("Cannot open the folder automatically. Your APK is saved at:\n" + savedApk); }
    }

    private void refreshSettings() {
        sdkStatus.setText(settings.ready() ? "●  Android SDK ready" : "○  Android SDK setup needed");
        GuiTheme.animationsEnabled = settings.animations;
        processing.setAnimationEnabled(page == 2 && settings.animations);
    }

    private void showSettings() {
        JDialog dialog = new JDialog(frame, "Android SDK settings", true);
        JPanel root = new Background(new BorderLayout(0, 18)); root.setBorder(new EmptyBorder(25, 26, 24, 26));
        JPanel heading = panel(new BorderLayout(12, 7));
        heading.add(label("Connect your Android SDK", 24, INK, true), BorderLayout.CENTER);
        JButton help = textButton("What do I need?"); help.addActionListener(event -> showSdkHelp(dialog));
        heading.add(help, BorderLayout.EAST);
        heading.add(muted("Choose your SDK once. AndroLog remembers it on this computer."), BorderLayout.SOUTH);
        root.add(heading, BorderLayout.NORTH);
        JTextField platforms = field(settings.platforms == null ? "" : settings.platforms.toString(), 32);
        JTextField build = field(settings.buildTools == null ? "" : settings.buildTools.toString(), 32);
        platforms.setToolTipText("The platforms folder containing android-XX/android.jar");
        build.setToolTipText("A build-tools version folder containing zipalign and apksigner");
        JCheckBox animations = new JCheckBox("Animate the interface", settings.animations);
        animations.setOpaque(false); animations.setForeground(INK);
        JPanel form = new Card(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 14, 0);
        JButton detect = button("Choose Android SDK folder…", false);
        detect.addActionListener(event -> {
            Path sdk = chooseDirectory("Choose Android SDK folder", settings.platforms == null ? null : settings.platforms.getParent(), dialog);
            if (sdk != null) {
                platforms.setText(sdk.resolve("platforms").toString());
                Path tools = GuiSettings.discoverBuildTools(sdk); build.setText(tools == null ? "" : tools.toString());
            }
        });
        c.gridy = 0; form.add(detect, c);
        c.gridy++; form.add(muted("Selecting the SDK folder fills both paths below automatically."), c);
        c.gridy++; form.add(pathRow("Platforms folder", platforms, dialog), c);
        c.gridy++; form.add(pathRow("Build Tools version folder", build, dialog), c);
        c.gridy++; c.insets = new Insets(2, 0, 0, 0); form.add(animations, c);
        root.add(form, BorderLayout.CENTER);
        JPanel bottom = panel(new BorderLayout(0, 12));
        JLabel validation = label(" ", 12, new Color(0xA13D43), false);
        validation.getAccessibleContext().setAccessibleName("SDK validation result"); bottom.add(validation, BorderLayout.NORTH);
        JPanel actions = panel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancel = button("Cancel", false), save = button("Save settings", true);
        cancel.addActionListener(event -> dialog.dispose());
        save.addActionListener(event -> {
            GuiSettings candidate = new GuiSettings(); candidate.platforms = GuiSettings.path(platforms.getText());
            candidate.buildTools = GuiSettings.path(build.getText()); candidate.animations = animations.isSelected();
            try {
                candidate.validate(); candidate.save();
                settings.platforms = candidate.platforms; settings.buildTools = candidate.buildTools; settings.animations = candidate.animations;
                refreshSettings(); dialog.dispose();
            } catch (IOException failure) {
                validation.setText("<html><div style='width:480px'>" + html(failure.getMessage()) + "</div></html>");
                dialog.pack();
            }
        });
        actions.add(cancel); actions.add(save); bottom.add(actions, BorderLayout.SOUTH); root.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(root); prepareDialog(dialog, save); dialog.setVisible(true);
    }

    private void showSdkHelp(Window owner) {
        JDialog dialog = new JDialog(owner, "Android SDK help", Dialog.ModalityType.DOCUMENT_MODAL);
        JPanel root = new Background(new BorderLayout(0, 18)); root.setBorder(new EmptyBorder(26, 28, 24, 28));
        JPanel heading = panel(new BorderLayout(14, 6));
        heading.add(new JLabel(AppIcon.icon(55)), BorderLayout.WEST);
        JPanel title = panel(new BorderLayout(0, 6));
        title.add(label("A quick SDK setup guide", 24, INK, true), BorderLayout.NORTH);
        title.add(muted("The Android tools AndroLog needs to read and sign your APK."), BorderLayout.SOUTH);
        heading.add(title, BorderLayout.CENTER); root.add(heading, BorderLayout.NORTH);
        JPanel body = new Card(new BorderLayout());
        JLabel guide = new JLabel("<html><div style='width:500px; color:#183441; font-family:sans-serif; font-size:12px'>"
                + "<b>1. Install the two SDK packages</b><br>"
                + "In Android Studio, open <b>Tools → SDK Manager</b>. In <b>SDK Platforms</b>, install an Android platform. "
                + "In <b>SDK Tools</b>, install <b>Android SDK Build-Tools</b>.<br><br>"
                + "<b>2. Choose your Android SDK folder</b><br>"
                + "Copy the <b>Android SDK Location</b> shown in SDK Manager. Back in AndroLog, use "
                + "<b>Choose Android SDK folder…</b>. Both paths are filled automatically.<br><br>"
                + "<b>3. If you select the folders separately</b><br>"
                + "<b>Platforms folder:</b> choose <code>sdk/platforms</code>. It contains folders such as "
                + "<code>android-36</code>, each with an <code>android.jar</code> file.<br><br>"
                + "<b>Build Tools version folder:</b> choose a version inside <code>sdk/build-tools</code>, "
                + "for example <code>sdk/build-tools/36.1.0</code>. It must contain <code>apksigner</code> and "
                + "<code>zipalign</code>. Choose the version folder, not the parent <code>build-tools</code> folder.<br><br>"
                + "<b>Already says “Android SDK ready”?</b> You are all set. An emulator is not needed to instrument an APK."
                + "</div></html>");
        body.add(guide); root.add(body, BorderLayout.CENTER);
        JPanel actions = panel(new BorderLayout(15, 0));
        JButton docs = textButton("Android's SDK setup guide ↗");
        docs.setToolTipText("Open the official Android SDK documentation in your browser");
        docs.addActionListener(event -> {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    throw new IOException("Open developer.android.com/studio/intro/update in a browser to read the SDK setup guide.");
                }
                Desktop.getDesktop().browse(URI.create("https://developer.android.com/studio/intro/update#sdk-manager"));
            } catch (Exception failure) { JOptionPane.showMessageDialog(dialog, readableMessage(failure.getMessage()), "SDK setup guide", JOptionPane.INFORMATION_MESSAGE); }
        });
        JButton done = button("Got it", true); done.addActionListener(event -> dialog.dispose());
        actions.add(docs, BorderLayout.WEST); actions.add(done, BorderLayout.EAST); root.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(root); prepareDialog(dialog, done); dialog.setVisible(true);
    }

    private void prepareDialog(JDialog dialog, JButton defaultButton) {
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getRootPane().setDefaultButton(defaultButton);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack(); dialog.setResizable(false); dialog.setLocationRelativeTo(dialog.getOwner());
    }

    private JPanel pathRow(String name, JTextField field, Component owner) {
        JPanel row = panel(new BorderLayout(10, 0)); row.add(labeledField(name, field), BorderLayout.CENTER);
        JButton choose = button("Browse…", false);
        choose.addActionListener(event -> { Path path = chooseDirectory(name, GuiSettings.path(field.getText()), owner); if (path != null) field.setText(path.toString()); });
        JPanel action = panel(new BorderLayout()); action.add(choose, BorderLayout.SOUTH);
        row.add(action, BorderLayout.EAST); return row;
    }

    private Path chooseDirectory(String title, Path initial, Component owner) {
        JFileChooser chooser = new JFileChooser(initial == null ? null : initial.toFile());
        chooser.setDialogTitle(title); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); chooser.setAcceptAllFileFilterUsed(false);
        return chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }

    private void showLog() {
        InstrumentationJob active = job;
        if (active == null) { JOptionPane.showMessageDialog(frame, "The worker is still starting."); return; }
        JDialog dialog = new JDialog(frame, "Instrumentation log", false);
        JTextArea text = new JTextArea(24, 85); text.setEditable(false); text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        Runnable refresh = () -> { try { text.setText(active.readLog()); text.setCaretPosition(text.getDocument().getLength()); } catch (IOException failure) { text.setText(failure.getMessage()); } };
        refresh.run();
        JButton refreshButton = button("Refresh", false); refreshButton.addActionListener(event -> refresh.run());
        JButton save = button("Save full log…", false);
        save.addActionListener(event -> {
            FileDialog chooser = new FileDialog(dialog, "Save instrumentation log", FileDialog.SAVE); chooser.setFile("androlog-instrumentation.log"); chooser.setVisible(true);
            if (chooser.getFile() != null) {
                Path destination = Paths.get(chooser.getDirectory(), chooser.getFile());
                boolean replace = Files.exists(destination);
                if (!replace || JOptionPane.showConfirmDialog(dialog, "Replace the existing log file?", "Replace file?", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    try {
                        if (savedApk != null && Files.exists(destination) && Files.isSameFile(destination, savedApk)) throw new IOException("Choose a separate file for the log.");
                        InstrumentationJob.copySafely(active.log, destination, active.input, replace);
                    } catch (IOException failure) { error(failure.getMessage()); }
                }
            }
            chooser.dispose();
        });
        JPanel actions = panel(new FlowLayout(FlowLayout.RIGHT)); actions.add(refreshButton); actions.add(save);
        dialog.add(new JScrollPane(text), BorderLayout.CENTER); dialog.add(actions, BorderLayout.SOUTH);
        dialog.pack(); dialog.setLocationRelativeTo(frame); dialog.setVisible(true);
    }

    private void closeWindow() {
        if (saving) { JOptionPane.showMessageDialog(frame, "Please wait for the APK to finish saving."); return; }
        if (working) {
            if (JOptionPane.showConfirmDialog(frame, "Stop the current operation and close AndroLog?", "Operation in progress", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            closing = true; cancel(); return;
        }
        if (unsaved && JOptionPane.showConfirmDialog(frame, "Close without saving the instrumented APK?", "Unsaved APK", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        dispose();
    }

    private void dispose() {
        clock.stop(); frame.dispose();
        new Thread(() -> {
            try { if (job != null) job.close(); }
            catch (IOException failure) { System.err.println("Could not remove temporary GUI files: " + failure.getMessage()); }
        }, "androlog-gui-file-cleanup").start();
    }

    private void error(String text) { JOptionPane.showMessageDialog(frame, readableMessage(text), "AndroLog", JOptionPane.ERROR_MESSAGE); }
    private static JComponent readableMessage(String message) {
        JTextArea text = new JTextArea(message == null ? "The operation could not finish." : message, 0, 55);
        text.setFont(font(14, false)); text.setLineWrap(true); text.setWrapStyleWord(true); text.setEditable(false); text.setOpaque(false); return text;
    }
    private static String message(Exception failure) {
        Throwable cause = failure instanceof ExecutionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
