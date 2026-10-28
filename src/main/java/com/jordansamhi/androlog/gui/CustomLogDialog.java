package com.jordansamhi.androlog.gui;

import com.jordansamhi.androlog.CodeCatalog;
import com.jordansamhi.androlog.CustomLogPlan;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import static com.jordansamhi.androlog.gui.GuiTheme.*;

/** An AndroLog code browser: a whole class, exact Jimple points, and literal user messages. */
final class CustomLogDialog extends JDialog {
    private final Path apk;
    private final GuiSettings settings;
    private final Consumer<CustomLogPlan> onApply;
    private final JPanel content = panel(new BorderLayout());
    private final JLabel status = muted("Reading the APK…");
    private final JLabel location = muted("Select a statement in the code.");
    private final JLabel validation = label(" ", 12, new Color(0xA13D43), false);
    private final JTextField search = field("", 16);
    private final JTextField message = field("", 30);
    private final JButton done = button("Use these log points", true);
    private final JButton remove = textButton("Remove log");
    private final JButton nextPoint = textButton("Next log point ↓");
    private final JTree tree = new JTree(new DefaultMutableTreeNode("APK"));
    private final CodeRows rows = new CodeRows();
    private final JTable code = new JTable(rows);
    private final LinkedHashMap<String, CustomLogPlan.Point> points = new LinkedHashMap<>();
    private final TreeMap<String, List<CodeCatalog.Method>> classes = new TreeMap<>();
    private final InstrumentationAnimation loading = new InstrumentationAnimation();
    private String initialHash, currentClass;
    private CodeCatalog catalog;
    private Row selected;
    private boolean updating;
    private volatile boolean closed;
    private volatile CodeInspectionJob inspection;

    static void open(JFrame owner, Path apk, GuiSettings settings, CustomLogPlan initial, Consumer<CustomLogPlan> onApply) {
        CustomLogDialog dialog = new CustomLogDialog(owner, apk, settings, initial, onApply);
        dialog.loadCode(); dialog.setVisible(true);
    }

    private CustomLogDialog(JFrame owner, Path apk, GuiSettings settings, CustomLogPlan initial, Consumer<CustomLogPlan> onApply) {
        super(owner, "Custom log points · AndroLog", true);
        this.apk = apk; this.settings = settings; this.onApply = onApply;
        if (initial != null) {
            initialHash = initial.apkSha256;
            for (CustomLogPlan.Point point : initial.points) {
                points.put(key(point.method, point.index), new CustomLogPlan.Point(point.method, point.index, point.statement, point.message));
            }
        }
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(940, 660)); setSize(1110, 790); setLocationRelativeTo(owner);
        JPanel root = new Background(new BorderLayout(0, 16)); root.setBorder(new EmptyBorder(20, 22, 18, 22));
        JPanel header = panel(new BorderLayout(0, 7));
        header.add(label("Put your message exactly where it matters.", 25, INK, true), BorderLayout.NORTH);
        header.add(muted("Select a code statement, then type the text to log every time execution reaches it."), BorderLayout.CENTER);
        header.add(status, BorderLayout.SOUTH); root.add(header, BorderLayout.NORTH); root.add(content, BorderLayout.CENTER);
        JPanel footer = panel(new BorderLayout(12, 0));
        footer.add(muted("Custom points are independent of the automatic logging options."), BorderLayout.CENTER);
        JPanel actions = panel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancel = button("Cancel", false); cancel.addActionListener(event -> dispose());
        done.setEnabled(false); done.addActionListener(event -> apply()); actions.add(cancel); actions.add(done);
        footer.add(actions, BorderLayout.EAST); root.add(footer, BorderLayout.SOUTH); setContentPane(root);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                closed = true; loading.setAnimationEnabled(false);
                CodeInspectionJob active = inspection;
                if (active != null) new Thread(active::cancel, "androlog-code-inspection-cancel").start();
            }
        });
    }

    private void loadCode() {
        JPanel waiting = new Card(new BorderLayout(0, 8));
        loading.setStage(1); loading.setAnimationEnabled(settings.animations); waiting.add(loading, BorderLayout.CENTER);
        JLabel explanation = muted("AndroLog is reading the app's code. The APK is not modified during this step.");
        explanation.setHorizontalAlignment(SwingConstants.CENTER); waiting.add(explanation, BorderLayout.SOUTH);
        content.removeAll(); content.add(waiting); content.revalidate(); content.repaint();
        status.setText("Reading " + apk.getFileName() + "…");
        new SwingWorker<CodeCatalog, Void>() {
            @Override protected CodeCatalog doInBackground() throws Exception {
                try (CodeInspectionJob active = new CodeInspectionJob(apk, settings, false, "")) {
                    inspection = active;
                    if (closed) active.cancel();
                    return active.run();
                } finally { inspection = null; }
            }
            @Override protected void done() {
                loading.setAnimationEnabled(false);
                if (closed) return;
                try {
                    catalog = get();
                    if (initialHash != null && !initialHash.equals(catalog.apkSha256)) {
                        points.clear(); validation.setText("The APK changed. Select the log points again for this version.");
                    }
                    for (CodeCatalog.Method method : catalog.methods) classes.computeIfAbsent(method.className, ignored -> new ArrayList<>()).add(method);
                    buildBrowser(); updateCount(); CustomLogDialog.this.done.setEnabled(true);
                } catch (Exception failure) {
                    Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
                    if (cause instanceof CancellationException) return;
                    JPanel error = new Card(new BorderLayout(0, 15));
                    JTextArea details = new JTextArea(cause.getMessage() == null ? "Could not read the APK code." : cause.getMessage());
                    details.setEditable(false); details.setOpaque(false); details.setLineWrap(true); details.setWrapStyleWord(true); details.setFont(font(14, false));
                    error.add(details, BorderLayout.CENTER);
                    JButton retry = button("Try again", false); retry.addActionListener(event -> loadCode()); error.add(retry, BorderLayout.SOUTH);
                    content.removeAll(); content.add(error); content.revalidate(); content.repaint(); status.setText("Code could not be loaded.");
                }
            }
        }.execute();
    }

    private void buildBrowser() {
        JPanel browser = panel(new BorderLayout(0, 12));
        JPanel packages = panel(new BorderLayout(0, 9));
        search.setToolTipText("Filter package or class names"); search.getAccessibleContext().setAccessibleName("Find a class");
        JPanel filter = panel(new BorderLayout(0, 5)); JLabel filterLabel = muted("Find a class"); filterLabel.setLabelFor(search);
        filter.add(filterLabel, BorderLayout.NORTH); filter.add(search, BorderLayout.CENTER); packages.add(filter, BorderLayout.NORTH);
        tree.setRootVisible(false); tree.setShowsRootHandles(true); tree.setRowHeight(26); tree.setFont(font(13, false));
        tree.setBackground(new Color(0xF5FAFC)); tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.getAccessibleContext().setAccessibleName("APK packages and classes");
        tree.addTreeSelectionListener(event -> {
            Object node = tree.getLastSelectedPathComponent();
            if (node instanceof DefaultMutableTreeNode) {
                Object item = ((DefaultMutableTreeNode) node).getUserObject();
                if (item instanceof ClassItem) showClass(((ClassItem) item).name);
            }
        });
        DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();
        renderer.setTextNonSelectionColor(INK); renderer.setTextSelectionColor(INK); renderer.setBackgroundSelectionColor(new Color(0xD6F1EB));
        tree.setCellRenderer(renderer); packages.add(scroll(tree), BorderLayout.CENTER);
        search.getDocument().addDocumentListener(change(this::filterClasses));
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13)); code.setRowHeight(27); code.setShowGrid(false);
        code.setIntercellSpacing(new Dimension(0, 0)); code.setFillsViewportHeight(true); code.setBackground(new Color(0xFAFCFE));
        code.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); code.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        code.getTableHeader().setReorderingAllowed(false); code.getTableHeader().setFont(font(12, true));
        code.getColumnModel().getColumn(0).setPreferredWidth(58); code.getColumnModel().getColumn(1).setPreferredWidth(1100);
        code.setDefaultRenderer(Object.class, new CodeRenderer());
        code.getAccessibleContext().setAccessibleName("Whole class code; select a statement to add a log");
        code.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) selectStatement(); });
        code.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "editLogMessage");
        code.getActionMap().put("editLogMessage", new AbstractAction() {
            public void actionPerformed(ActionEvent event) { if (message.isEnabled()) message.requestFocusInWindow(); }
        });
        code.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && message.isEnabled()) message.requestFocusInWindow();
            }
        });
        JPanel source = panel(new BorderLayout(0, 7));
        JPanel sourceHeading = panel(new BorderLayout());
        sourceHeading.add(muted("Class code · Jimple"), BorderLayout.WEST); sourceHeading.add(nextPoint, BorderLayout.EAST);
        nextPoint.addActionListener(event -> jumpToNextPoint()); source.add(sourceHeading, BorderLayout.NORTH); source.add(scroll(code), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, packages, source); split.setOpaque(false);
        split.setBorder(null); split.setDividerSize(10); split.setDividerLocation(250); split.setResizeWeight(.23);
        packages.setMinimumSize(new Dimension(170, 100)); source.setMinimumSize(new Dimension(300, 100));
        browser.add(split, BorderLayout.CENTER);
        JPanel editor = new Card(new BorderLayout(0, 8)); editor.setBorder(new EmptyBorder(12, 17, 13, 17));
        editor.add(location, BorderLayout.NORTH);
        JPanel entry = panel(new BorderLayout(10, 0));
        JLabel messageLabel = muted("Your message"); messageLabel.setLabelFor(message);
        JPanel text = panel(new BorderLayout(0, 5)); text.add(messageLabel, BorderLayout.NORTH); text.add(message, BorderLayout.CENTER);
        message.setToolTipText("For example: reached this point. Plain text only, no variable expressions.");
        message.getAccessibleContext().setAccessibleName("Custom log message"); message.setEnabled(false);
        message.getDocument().addDocumentListener(change(this::editMessage)); entry.add(text, BorderLayout.CENTER);
        remove.setEnabled(false); remove.addActionListener(event -> message.setText("")); entry.add(remove, BorderLayout.EAST);
        editor.add(entry, BorderLayout.CENTER); editor.add(validation, BorderLayout.SOUTH); browser.add(editor, BorderLayout.SOUTH);
        content.removeAll(); content.add(browser); filterClasses(); content.revalidate(); content.repaint();
        SwingUtilities.invokeLater(() -> search.requestFocusInWindow());
        if (classes.isEmpty()) status.setText("No instrumentable class bodies were found in this APK.");
    }

    private void filterClasses() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("APK");
        Map<String, DefaultMutableTreeNode> packages = new LinkedHashMap<>();
        DefaultMutableTreeNode first = null, preferred = null;
        for (String name : classes.keySet()) {
            if (!name.toLowerCase(Locale.ROOT).contains(query)) continue;
            int dot = name.lastIndexOf('.'); String packageName = dot < 0 ? "(default package)" : name.substring(0, dot);
            DefaultMutableTreeNode group = packages.get(packageName);
            if (group == null) { group = new DefaultMutableTreeNode(packageName); packages.put(packageName, group); root.add(group); }
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new ClassItem(name)); group.add(node);
            if (first == null) first = node;
            if (name.equals(currentClass)) preferred = node;
        }
        tree.setModel(new DefaultTreeModel(root));
        DefaultMutableTreeNode choice = preferred == null ? first : preferred;
        if (choice != null) {
            TreePath path = new TreePath(choice.getPath()); tree.setSelectionPath(path); tree.scrollPathToVisible(path);
        } else { currentClass = null; rows.items.clear(); rows.fireTableDataChanged(); selectStatement(); }
    }

    private void showClass(String name) {
        currentClass = name; selected = null; rows.items.clear(); code.clearSelection();
        for (CodeCatalog.Method method : classes.get(name)) {
            rows.items.add(new Row(method, null));
            for (CodeCatalog.Statement statement : method.statements) rows.items.add(new Row(method, statement));
        }
        FontMetrics metrics = code.getFontMetrics(new Font(Font.MONOSPACED, Font.BOLD, 13));
        int width = 700;
        for (int row = 0; row < rows.items.size(); row++) width = Math.max(width, metrics.stringWidth(rows.getValueAt(row, 1).toString()) + 32);
        code.getColumnModel().getColumn(1).setPreferredWidth(width);
        rows.fireTableDataChanged(); code.scrollRectToVisible(new Rectangle(0, 0, 1, 1)); selectStatement();
    }

    private void selectStatement() {
        int index = code.getSelectedRow(); selected = index < 0 || index >= rows.items.size() ? null : rows.items.get(index);
        boolean allowed = selected != null && selected.statement != null && selected.statement.selectable;
        updating = true;
        CustomLogPlan.Point point = allowed ? points.get(selected.key()) : null;
        message.setText(point == null ? "" : point.message); message.setEnabled(allowed);
        remove.setEnabled(point != null); updating = false;
        if (allowed) {
            String method = shortMethod(selected.method.signature);
            location.setText("Before statement " + selected.statement.index + " in " + method);
            location.setToolTipText(selected.method.signature + " · " + selected.statement.text);
        } else {
            location.setText(selected == null ? "Select a statement in the code." : selected.statement == null
                    ? "Select a statement below this method heading." : "This setup or monitor statement is not available for custom logging.");
            location.setToolTipText(null);
        }
    }

    private void editMessage() {
        if (updating || selected == null || selected.statement == null || !selected.statement.selectable) return;
        String text = message.getText();
        if (text.trim().isEmpty()) points.remove(selected.key());
        else points.put(selected.key(), new CustomLogPlan.Point(selected.method.signature, selected.statement.index, selected.statement.text, text));
        remove.setEnabled(points.containsKey(selected.key())); code.repaint(); updateCount(); validateDraft();
    }

    private boolean validateDraft() {
        try {
            if (!points.isEmpty()) plan().validate();
            validation.setText("Messages are literal text and are logged on every visit. Clear the text to remove a point.");
            validation.setForeground(MUTED); done.setEnabled(true); return true;
        } catch (IOException | IllegalArgumentException failure) {
            validation.setForeground(new Color(0xA13D43)); validation.setText(failure.getMessage()); done.setEnabled(false); return false;
        }
    }

    private CustomLogPlan plan() { return new CustomLogPlan(catalog.apkSha256, new ArrayList<>(points.values())); }
    private void apply() {
        if (catalog != null && validateDraft()) { onApply.accept(points.isEmpty() ? null : plan()); dispose(); }
    }
    private void updateCount() {
        status.setText(classes.size() + " classes  ·  " + points.size() + (points.size() == 1 ? " custom log point" : " custom log points")
                + "  ·  " + apk.getFileName()); nextPoint.setEnabled(!points.isEmpty());
    }
    private void jumpToNextPoint() {
        List<CustomLogPlan.Point> available = new ArrayList<>(points.values()); if (available.isEmpty()) return;
        int index = -1;
        if (selected != null && selected.statement != null) for (int i = 0; i < available.size(); i++) {
            if (key(available.get(i).method, available.get(i).index).equals(selected.key())) { index = i; break; }
        }
        CustomLogPlan.Point target = available.get((index + 1) % available.size());
        for (Map.Entry<String, List<CodeCatalog.Method>> entry : classes.entrySet()) for (CodeCatalog.Method method : entry.getValue()) {
            if (method.signature.equals(target.method)) {
                currentClass = entry.getKey(); search.setText(""); filterClasses(); showClass(currentClass);
                for (int row = 0; row < rows.items.size(); row++) {
                    Row item = rows.items.get(row);
                    if (item.statement != null && item.key().equals(key(target.method, target.index))) {
                        code.setRowSelectionInterval(row, row); code.scrollRectToVisible(code.getCellRect(row, 0, true)); return;
                    }
                }
            }
        }
    }

    private static JScrollPane scroll(JComponent component) {
        JScrollPane pane = new JScrollPane(component); pane.setBorder(BorderFactory.createLineBorder(LINE));
        pane.getViewport().setBackground(new Color(0xFAFCFE)); return pane;
    }
    private static DocumentListener change(Runnable action) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { action.run(); }
            public void removeUpdate(DocumentEvent event) { action.run(); }
            public void changedUpdate(DocumentEvent event) { action.run(); }
        };
    }
    private static String key(String method, int index) { return method + "\u0000" + index; }
    private static String shortMethod(String signature) {
        int colon = signature.indexOf(':'); return colon >= 0 && signature.endsWith(">") ? signature.substring(colon + 1, signature.length() - 1).trim() : signature;
    }
    private static final class ClassItem {
        final String name; ClassItem(String name) { this.name = name; }
        @Override public String toString() { return name.substring(name.lastIndexOf('.') + 1); }
    }
    private static final class Row {
        final CodeCatalog.Method method; final CodeCatalog.Statement statement;
        Row(CodeCatalog.Method method, CodeCatalog.Statement statement) { this.method = method; this.statement = statement; }
        String key() { return CustomLogDialog.key(method.signature, statement.index); }
    }
    private final class CodeRows extends AbstractTableModel {
        final List<Row> items = new ArrayList<>();
        public int getRowCount() { return items.size(); }
        public int getColumnCount() { return 2; }
        public String getColumnName(int column) { return column == 0 ? "Point" : "Statement"; }
        public Object getValueAt(int row, int column) {
            Row item = items.get(row);
            if (column == 0) return item.statement == null ? "" : (points.containsKey(item.key()) ? "● " : "") + item.statement.index;
            return item.statement == null ? shortMethod(item.method.signature) : "    " + item.statement.text;
        }
    }
    private final class CodeRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selectedRow, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selectedRow, focused, row, column);
            Row item = rows.items.get(row); boolean header = item.statement == null;
            setFont(new Font(Font.MONOSPACED, header ? Font.BOLD : Font.PLAIN, 13));
            setForeground(header ? INK : item.statement.selectable ? INK : MUTED);
            setBackground(selectedRow ? new Color(0xCDEEE8) : header ? new Color(0xE5EFF5)
                    : points.containsKey(item.key()) ? new Color(0xE5F7EF) : new Color(0xFAFCFE));
            setBorder(new EmptyBorder(3, 9, 3, 9)); setHorizontalAlignment(column == 0 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            setToolTipText(header ? item.method.signature : points.containsKey(item.key()) ? "Log: " + points.get(item.key()).message : item.statement.text);
            return this;
        }
    }
}
