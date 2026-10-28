package com.jordansamhi.androlog.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.io.*;
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
    private final JLabel sdkStatus = label("", 12, new Color(0xA7CDC5), false);
    private final JLabel footnote = muted("Everything runs locally on your computer.");
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
    private final JButton logButton = button("View run log", false);
    private final JToggleButton[] probes = new JToggleButton[5];
    private final JCheckBox excludeLibraries = new JCheckBox("Exclude known libraries");
    private final JTextField packagePrefix = field("", 18);
    private final JTextField logTag = field("ANDROLOG", 12);
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
            if (args.length == 1) gui.selectApk(Paths.get(args[0]));
        });
    }

    private AndroLogGui() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1000, 760));
        frame.setSize(1110, 810);
        frame.setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout()); root.setBackground(BACKGROUND);
        root.add(sidebar(), BorderLayout.WEST);
        JPanel workspace = panel(new BorderLayout(0, 14));
        workspace.setBorder(new EmptyBorder(30, 34, 24, 34));
        pages[0] = inputPage(); pages[1] = optionsPage();
        pages[2] = progressPage(); pages[3] = resultPage();
        workspace.add(content, BorderLayout.CENTER);
        workspace.add(footer(), BorderLayout.SOUTH);
        root.add(workspace, BorderLayout.CENTER); frame.setContentPane(root);
        primary.addActionListener(event -> primaryAction());
        back.addActionListener(event -> { if (page == 3) startAnother(); else showPage(0); });
        settingsButton.addActionListener(event -> showSettings());
        browse.addActionListener(event -> chooseApk());
        logButton.addActionListener(event -> showLog());
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
            public void actionPerformed(ActionEvent event) { if (!working && !saving && !unsaved) chooseApk(); }
        });
        root.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return !working && !saving && !unsaved && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
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
        JPanel side = new JPanel(new BorderLayout()); side.setBackground(SIDEBAR);
        side.setPreferredSize(new Dimension(226, 0)); side.setBorder(new EmptyBorder(38, 24, 25, 24));
        JPanel upper = panel(new BorderLayout(0, 48));
        JPanel brand = panel(new GridLayout(0, 1, 0, 7));
        brand.add(label("AndroLog", 30, WHITE, true));
        brand.add(label("INSTRUMENTATION STUDIO", 10, new Color(0x8CB5B4), true));
        upper.add(brand, BorderLayout.NORTH);
        JPanel steps = panel(new GridLayout(0, 1, 0, 25));
        String[] names = {"Choose an APK", "Choose your logs", "Instrument", "Save your APK"};
        for (int i = 0; i < names.length; i++) {
            stepLabels[i] = label("0" + (i + 1) + "   " + names[i], 14, WHITE, false);
            steps.add(stepLabels[i]);
        }
        upper.add(steps, BorderLayout.CENTER); side.add(upper, BorderLayout.NORTH);
        JPanel bottom = panel(new GridLayout(0, 1, 0, 12));
        bottom.add(sdkStatus); bottom.add(settingsButton);
        bottom.add(label("LOCAL BY DESIGN  /  v0.1", 10, new Color(0x83A5AA), false));
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
        page.add(header("A LITTLE VISIBILITY GOES A LONG WAY", "See what your app does.",
                "Add runtime logging to an Android app. No source code needed."), BorderLayout.NORTH);
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
        page.add(header("MAKE IT YOURS", "Choose what to observe.", "Select the events you want AndroLog to record when the app runs."), BorderLayout.NORTH);
        JPanel choices = panel(new BorderLayout(0, 14));
        JPanel presets = panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton light = button("Essentials", false), all = button("Everything", false);
        light.addActionListener(event -> preset(new boolean[]{true, true, false, true, false}));
        all.addActionListener(event -> preset(new boolean[]{true, true, true, true, true}));
        presets.add(light); presets.add(all);
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
        JPanel tip = panel(new BorderLayout()); tip.setBorder(new EmptyBorder(15, 12, 15, 8));
        tip.add(new JLabel("<html><font color='#647681'>Start with Essentials for lighter logging.<br><br>Statements and call tracing add more overhead.</font></html>"));
        grid.add(tip); choices.add(grid, BorderLayout.CENTER);
        page.add(choices, BorderLayout.CENTER);
        JPanel advanced = panel(new BorderLayout(0, 10));
        JPanel fields = panel(new GridLayout(1, 2, 14, 0));
        fields.add(labeledField("Package prefix (optional)", packagePrefix)); fields.add(labeledField("Logcat tag", logTag));
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
        JPanel actions = panel(new FlowLayout(FlowLayout.LEFT, 0, 0)); actions.add(reveal);
        JButton logs = button("View run log", false); logs.addActionListener(event -> showLog()); actions.add(logs);
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
            stepLabels[i].setForeground(i == next ? new Color(0x89E2CD) : i < next ? WHITE : new Color(0x83A5AA));
            stepLabels[i].setFont(font(14, i == next));
        }
        back.setVisible(next == 1 || next == 3); back.setText(next == 3 ? "Instrument another APK" : "Back");
        settingsButton.setEnabled(!working && !saving);
        footnote.setText(next >= 2 ? "The APK uses AndroLog’s test signing key. Use a test device or emulator." : "Everything runs locally on your computer.");
        updatePrimary();
    }

    private void updatePrimary() {
        String[] labels = {apk == null ? "Choose an APK" : "Choose logging  →", "Instrument APK  →", "Cancel", "Save APK as…"};
        primary.setText(labels[page]);
        boolean any = false; for (JToggleButton probe : probes) if (probe != null && probe.isSelected()) any = true;
        primary.setEnabled(!saving && (page != 1 || any));
        if (page == 1 && !any) footnote.setText("Select at least one kind of logging to continue.");
        else if (page < 2) footnote.setText("Everything runs locally on your computer.");
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
        working = true; primary.setEnabled(false); browse.setEnabled(false); settingsButton.setEnabled(false);
        new SwingWorker<Long, Void>() {
            protected Long doInBackground() throws Exception { InstrumentationJob.validateApk(candidate); return Files.size(candidate); }
            protected void done() {
                working = false; browse.setEnabled(true); settingsButton.setEnabled(true);
                try {
                    long bytes = get(); apk = candidate.toAbsolutePath().normalize();
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

    private void instrument() {
        try { settings.validate(); }
        catch (IOException missing) { showSettings(); return; }
        boolean[] selected = new boolean[probes.length];
        for (int i = 0; i < selected.length; i++) selected[i] = probes[i].isSelected();
        String prefix = packagePrefix.getText().trim(), tag = logTag.getText().trim();
        boolean exclude = excludeLibraries.isSelected();
        working = true; cancelRequested = false; savedApk = null; unsaved = false;
        started = System.nanoTime(); elapsed.setText("0:00 elapsed"); clock.start();
        setStage(0); showPage(2);
        new SwingWorker<Void, Integer>() {
            protected Void doInBackground() throws Exception {
                if (job != null) job.close();
                job = new InstrumentationJob(apk, settings, selected, exclude, prefix, tag);
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
                        Object[] options = {"View run log", "OK"};
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
        saving = true; primary.setEnabled(false); primary.setText("Saving…"); back.setEnabled(false); settingsButton.setEnabled(false); reveal.setEnabled(false);
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
        processing.setAnimationEnabled(settings.animations);
    }

    private void showSettings() {
        JTextField platforms = field(settings.platforms == null ? "" : settings.platforms.toString(), 32);
        JTextField build = field(settings.buildTools == null ? "" : settings.buildTools.toString(), 32);
        JCheckBox animations = new JCheckBox("Animate instrumentation", settings.animations); animations.setOpaque(false);
        JPanel form = panel(new GridLayout(0, 1, 0, 12));
        form.add(muted("Select your Android SDK once. These paths stay on this computer."));
        JButton detect = button("Choose Android SDK folder…", false);
        detect.addActionListener(event -> {
            Path sdk = chooseDirectory("Choose Android SDK folder", settings.platforms == null ? null : settings.platforms.getParent());
            if (sdk != null) {
                platforms.setText(sdk.resolve("platforms").toString());
                Path tools = GuiSettings.discoverBuildTools(sdk); build.setText(tools == null ? "" : tools.toString());
            }
        });
        form.add(detect); form.add(pathRow("Platforms folder", platforms)); form.add(pathRow("Build Tools version folder", build)); form.add(animations);
        while (JOptionPane.showConfirmDialog(frame, form, "Android SDK settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            GuiSettings candidate = new GuiSettings(); candidate.platforms = GuiSettings.path(platforms.getText()); candidate.buildTools = GuiSettings.path(build.getText()); candidate.animations = animations.isSelected();
            try {
                candidate.validate(); candidate.save();
                settings.platforms = candidate.platforms; settings.buildTools = candidate.buildTools; settings.animations = candidate.animations;
                refreshSettings(); return;
            } catch (IOException failure) { error(failure.getMessage()); }
        }
    }

    private JPanel pathRow(String name, JTextField field) {
        JPanel row = panel(new BorderLayout(10, 0)); row.add(labeledField(name, field), BorderLayout.CENTER);
        JButton choose = button("Browse…", false);
        choose.addActionListener(event -> { Path path = chooseDirectory(name, GuiSettings.path(field.getText())); if (path != null) field.setText(path.toString()); });
        row.add(choose, BorderLayout.EAST); return row;
    }

    private Path chooseDirectory(String title, Path initial) {
        JFileChooser chooser = new JFileChooser(initial == null ? null : initial.toFile());
        chooser.setDialogTitle(title); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); chooser.setAcceptAllFileFilterUsed(false);
        return chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
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
