package com.jordansamhi.androlog;

import com.jordansamhi.androlog.utils.Constants;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Plain, consistent output for a single command-line run. */
final class CliOutput {
    interface Action { void run() throws Exception; }

    private final PrintStream out;
    private final PrintStream err;
    private final long started = System.nanoTime();
    private final Map<String, Path> outputs = new LinkedHashMap<>();
    private int stage;
    private int stages;
    private String activeStage;

    CliOutput(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    void begin(String mode, int stages) {
        this.stages = stages;
        out.println(Constants.TOOL_NAME + " " + Constants.VERSION + " | " + mode);
        out.println();
    }

    void context(String label, String value) {
        out.printf("  %-11s %s%n", label, value);
    }

    void stage(String label, Action action) throws Exception {
        stage(label, action, false);
    }

    void stageBlock(String label, Action action) throws Exception {
        stage(label, action, true);
    }

    private void stage(String label, Action action, boolean block) throws Exception {
        if (stage == 0) out.println();
        activeStage = label;
        long before = System.nanoTime();
        if (Boolean.getBoolean("androlog.gui.progress")) out.println("@@ANDROLOG_STAGE " + (stage + 1));
        out.printf(block ? "[%d/%d] %s%n" : "[%d/%d] %s ... ", ++stage, stages, label);
        out.flush();
        try {
            action.run();
            out.println(block ? "  Completed in " + duration(System.nanoTime() - before) + "." : duration(System.nanoTime() - before));
            activeStage = null;
        } catch (Exception | LinkageError error) {
            out.println("failed (" + duration(System.nanoTime() - before) + ")");
            throw error;
        }
    }

    void output(String label, Path path) {
        outputs.put(label, path.toAbsolutePath().normalize());
    }

    void complete() {
        printOutputs(out, "Outputs");
        out.println();
        out.println("Completed in " + duration(System.nanoTime() - started) + ".");
    }

    void failure(Throwable error, boolean verbose) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        err.println("Error" + (activeStage == null ? "" : " during " + activeStage.toLowerCase(Locale.ROOT))
                + ": " + message);
        printOutputs(err, "Written before failure");
        if (verbose) error.printStackTrace(err);
        else err.println("Use --verbose to include the stack trace.");
    }

    private void printOutputs(PrintStream stream, String label) {
        if (outputs.isEmpty()) return;
        stream.println();
        stream.println(label + ":");
        for (Map.Entry<String, Path> entry : outputs.entrySet()) {
            stream.printf("  %-11s %s%n", entry.getKey(), entry.getValue());
        }
    }

    private static String duration(long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        return seconds < 60 ? String.format(Locale.ROOT, "%.1f s", seconds)
                : String.format(Locale.ROOT, "%d min %.1f s", (long) seconds / 60, seconds % 60);
    }
}
