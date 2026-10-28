package com.jordansamhi.androlog.gui;

import com.jordansamhi.androlog.CodeCatalog;
import com.jordansamhi.androlog.Main;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.*;

/** Reads original APK statements in a separate JVM without modifying or signing the APK. */
final class CodeInspectionJob implements AutoCloseable {
    final Path directory;
    final Path log;
    private final Path catalog;
    private final List<String> arguments;
    private final Thread shutdown;
    private Process process;
    private volatile boolean cancelled;

    CodeInspectionJob(Path input, GuiSettings settings, boolean excludeLibraries, String packagePrefix) throws IOException {
        InstrumentationJob.validateApk(input);
        if (!GuiSettings.validPlatforms(settings.platforms)) {
            throw new IOException("Choose an Android SDK platforms folder in SDK settings before browsing code.");
        }
        if (!packagePrefix.isEmpty() && !packagePrefix.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\.?")) {
            throw new IOException("Enter a package prefix such as com.example.app, or leave it empty.");
        }
        directory = Files.createTempDirectory("androlog-code-");
        log = directory.resolve("inspection.log");
        catalog = directory.resolve("code.json");
        arguments = new ArrayList<>(Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", GuiSettings.windows() ? "java.exe" : "java").toString(),
                "-Djava.awt.headless=true", "-cp", InstrumentationJob.absoluteClasspath(), Main.class.getName(),
                "-a", input.toAbsolutePath().normalize().toString(), "-p", settings.platforms.toString(),
                "--inspect-code", catalog.toString(), "-v"));
        if (excludeLibraries) arguments.add("-n");
        if (!packagePrefix.isEmpty()) arguments.addAll(Arrays.asList("-pkg", packagePrefix));
        shutdown = new Thread(this::stop, "androlog-code-worker-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    CodeCatalog run() throws Exception {
        synchronized (this) {
            if (cancelled) throw new CancellationException();
            ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile());
            process = builder.start();
            process.getOutputStream().close();
        }
        try {
            int status = process.waitFor();
            if (cancelled) throw new CancellationException();
            if (status != 0) throw new IOException(failureMessage(status));
            if (!Files.isRegularFile(catalog)) throw new IOException("Code inspection did not produce a code listing.");
            return CodeCatalog.read(catalog);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (cancelled) throw new CancellationException();
            throw error;
        } catch (IOException error) {
            if (cancelled) throw new CancellationException();
            throw error;
        } finally { stop(); }
    }

    /** Invoke from a background task: waiting for an unresponsive JVM can take a few seconds. */
    void cancel() { cancelled = true; stop(); }

    private synchronized void stop() {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }

    private String failureMessage(int status) throws IOException {
        String details;
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            file.seek(Math.max(0, file.length() - 100000));
            byte[] bytes = new byte[(int) Math.min(100000, file.length())];
            file.readFully(bytes); details = new String(bytes, StandardCharsets.UTF_8);
        }
        Matcher errors = Pattern.compile("(?m)^Error[^\\r\\n]*").matcher(details);
        String message = "Cannot read the APK's code (exit " + status + "). Check the APK and Android SDK settings.";
        while (errors.find()) message = errors.group();
        return message.length() > 1000 ? message.substring(0, 1000) + "…" : message;
    }

    @Override public void close() throws IOException {
        cancel();
        try { Runtime.getRuntime().removeShutdownHook(shutdown); }
        catch (IllegalStateException ignored) { /* JVM shutdown already owns process cleanup. */ }
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(path);
        }
    }
}
