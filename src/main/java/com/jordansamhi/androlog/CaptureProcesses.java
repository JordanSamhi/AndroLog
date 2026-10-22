package com.jordansamhi.androlog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Owns child processes so failed commands and Ctrl+C cannot leave capture workers running. */
final class CaptureProcesses implements AutoCloseable {
    private final Set<Process> processes = Collections.synchronizedSet(new HashSet<>());
    private final Thread shutdown;

    CaptureProcesses(PrintStream err, Path session) {
        shutdown = new Thread(() -> {
            stopAll();
            err.println("\nCapture interrupted. Saved session files: " + session);
        }, "androlog-capture-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    Process start(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        processes.add(process);
        return process;
    }

    String run(List<String> command, long timeoutSeconds, Consumer<String> forward) throws Exception {
        Process process = start(command);
        StringBuilder output = new StringBuilder();
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (forward != null) forward.accept(line);
                    synchronized (output) {
                        output.append(line).append('\n');
                        if (output.length() > 65536) output.delete(0, output.length() - 65536);
                    }
                }
            } catch (IOException error) { failure.set(error); }
        }, "androlog-command-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (timeoutSeconds > 0 && !process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("Command timed out after " + timeoutSeconds + " seconds: " + command.get(0));
            }
            if (timeoutSeconds == 0) process.waitFor();
            reader.join(3000);
            if (reader.isAlive()) throw new IOException("Command output did not close: " + command.get(0));
            if (failure.get() != null) throw failure.get();
            String result;
            synchronized (output) { result = output.toString(); }
            if (process.exitValue() != 0) {
                throw new IOException("Command failed (exit " + process.exitValue() + "): " + command.get(0) + "\n" + result.trim());
            }
            return result;
        } finally { stop(process); }
    }

    void stop(Process process) {
        if (process == null) return;
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        processes.remove(process);
    }

    private void stopAll() {
        List<Process> running;
        synchronized (processes) { running = new ArrayList<>(processes); }
        for (Process process : running) stop(process);
    }

    @Override public void close() {
        stopAll();
        try { Runtime.getRuntime().removeShutdownHook(shutdown); }
        catch (IllegalStateException shutdownAlreadyStarted) { /* Cleanup is already running. */ }
    }
}
