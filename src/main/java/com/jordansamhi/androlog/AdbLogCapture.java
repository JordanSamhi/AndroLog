package com.jordansamhi.androlog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Marker-delimited log capture: no buffer clearing, old records, or launch-before-logcat race. */
final class AdbLogCapture implements AutoCloseable {
    private static final String MARKER_TAG = "ANDROLOG_CAPTURE";
    private final CaptureProcesses processes;
    private final List<String> adb;
    private final Process process;
    private final Thread reader;
    private final CountDownLatch ready = new CountDownLatch(1), drained = new CountDownLatch(1);
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private final String startMarker = "start-" + UUID.randomUUID(), endMarker = "stop-" + UUID.randomUUID();
    private final StringBuilder diagnostics = new StringBuilder();

    AdbLogCapture(CaptureProcesses processes, List<String> adb, String tag, Path log) throws IOException {
        this.processes = processes;
        this.adb = new ArrayList<>(adb);
        BufferedWriter output = Files.newBufferedWriter(log, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        List<String> command = command("logcat", "-b", "main", "-v", "threadtime", "-T", "1", tag + ":V", MARKER_TAG + ":I", "*:S");
        try { process = processes.start(command); }
        catch (IOException error) { output.close(); throw error; }
        reader = new Thread(() -> {
            boolean started = false;
            try (BufferedWriter writer = output;
                 BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (!started) {
                        synchronized (diagnostics) {
                            diagnostics.append(line).append('\n');
                            if (diagnostics.length() > 4096) diagnostics.delete(0, diagnostics.length() - 4096);
                        }
                        if (!marker(line, startMarker)) continue;
                        started = true;
                        ready.countDown();
                    }
                    writer.write(line);
                    writer.newLine();
                    writer.flush(); // Keep complete records available even when the user aborts the run.
                    if (marker(line, endMarker)) { drained.countDown(); break; }
                }
            } catch (IOException error) { failure.set(error); }
        }, "androlog-logcat-reader");
        reader.setDaemon(true);
        reader.start();
    }

    void awaitReady() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            checkAlive();
            signal(startMarker);
            if (ready.await(200, TimeUnit.MILLISECONDS)) return;
        } while (System.nanoTime() < deadline);
        throw new IOException("Logcat did not acknowledge capture readiness. " + diagnostic());
    }

    void finish() throws Exception {
        checkAlive();
        signal(endMarker);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!drained.await(100, TimeUnit.MILLISECONDS)) {
            checkAlive();
            if (System.nanoTime() > deadline) throw new IOException("Logcat did not finish draining; the partial log was kept.");
        }
        reader.join(2000);
        if (failure.get() != null) throw failure.get();
    }

    void checkAlive() throws IOException {
        if (failure.get() != null) throw failure.get();
        if (!process.isAlive() || !reader.isAlive()) {
            throw new IOException("Logcat stopped unexpectedly; check the device connection. The partial log was kept. " + diagnostic());
        }
    }

    private void signal(String message) throws Exception {
        processes.run(command("shell", "log", "-p", "i", "-t", MARKER_TAG, message), 10, null);
    }

    private List<String> command(String... arguments) {
        List<String> result = new ArrayList<>(adb);
        result.addAll(Arrays.asList(arguments));
        return result;
    }

    private static boolean marker(String line, String value) {
        return line.contains(MARKER_TAG) && line.endsWith(": " + value);
    }

    private String diagnostic() { synchronized (diagnostics) { return diagnostics.toString().trim(); } }

    @Override public void close() {
        processes.stop(process);
        try { reader.join(2000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
