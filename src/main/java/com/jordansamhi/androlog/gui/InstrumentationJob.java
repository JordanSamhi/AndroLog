package com.jordansamhi.androlog.gui;

import com.jordansamhi.androlog.Main;
import com.jordansamhi.androlog.CustomLogPlan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.ZipFile;

/** Runs each APK in a fresh JVM: Soot's global state never leaks between desktop jobs. */
final class InstrumentationJob implements AutoCloseable {
    static final String[] FLAGS = {"-c", "-m", "-s", "-cp", "-mc"};
    final Path input;
    final Path directory;
    final Path output;
    final Path log;
    private final List<String> arguments;
    private final Thread shutdown;
    private Process process;
    private volatile boolean cancelled;

    InstrumentationJob(Path input, GuiSettings settings, boolean[] probes, boolean excludeLibraries,
                       String packagePrefix, String tag) throws IOException {
        this(input, settings, probes, excludeLibraries, packagePrefix, tag, null);
    }

    InstrumentationJob(Path input, GuiSettings settings, boolean[] probes, boolean excludeLibraries,
                       String packagePrefix, String tag, CustomLogPlan customLogs) throws IOException {
        validateApk(input);
        settings.validate();
        if (probes.length != FLAGS.length) throw new IllegalArgumentException("Invalid probe selection.");
        if (customLogs != null) { customLogs.validate(); customLogs.verifyApk(input); }
        if (!any(probes) && (customLogs == null || customLogs.points.isEmpty())) {
            throw new IOException("Select at least one kind of logging or add a custom log point.");
        }
        if (!tag.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) throw new IOException("The log tag can contain letters, numbers, dots, underscores and hyphens.");
        if (!packagePrefix.isEmpty() && !packagePrefix.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\.?")) {
            throw new IOException("Enter a package prefix such as com.example.app, or leave it empty.");
        }
        this.input = input.toAbsolutePath().normalize();
        directory = Files.createTempDirectory("androlog-gui-");
        output = directory.resolve("instrumented").resolve(input.getFileName());
        log = directory.resolve("instrumentation.log");
        try {
            Path config = Files.createDirectory(directory.resolve("configuration"));
            try (OutputStream stream = Files.newOutputStream(config.resolve("config.properties"))) {
                settings.signingConfiguration().store(stream, "Temporary AndroLog GUI signing configuration");
            }
            arguments = new ArrayList<>(Arrays.asList(
                    Paths.get(System.getProperty("java.home"), "bin", GuiSettings.windows() ? "java.exe" : "java").toString(),
                    "-Djava.awt.headless=true", "-Dandrolog.gui.progress=true", "-cp", config + File.pathSeparator + absoluteClasspath(), Main.class.getName(),
                    "-a", this.input.toString(), "-p", settings.platforms.toString(), "-o", output.getParent().toString(), "-l", tag, "-v"));
            for (int i = 0; i < FLAGS.length; i++) if (probes[i]) arguments.add(FLAGS[i]);
            if (excludeLibraries) arguments.add("-n");
            if (!packagePrefix.isEmpty()) arguments.addAll(Arrays.asList("-pkg", packagePrefix));
            if (customLogs != null && !customLogs.points.isEmpty()) {
                Path plan = directory.resolve("custom-logs.json");
                customLogs.write(plan);
                arguments.addAll(Arrays.asList("--custom-logs", plan.toString()));
            }
        } catch (IOException | RuntimeException error) {
            removeDirectory(directory);
            throw error;
        }
        shutdown = new Thread(this::stop, "androlog-gui-worker-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    void run(IntConsumer progress) throws Exception {
        synchronized (this) {
            if (cancelled) throw new CancellationException();
            ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
            // SDK launchers such as apksigner also need Java when the GUI is opened from Finder.
            builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
            builder.environment().put("PATH", Paths.get(System.getProperty("java.home"), "bin") + File.pathSeparator
                    + builder.environment().getOrDefault("PATH", ""));
            process = builder.start();
            process.getOutputStream().close();
        }
        StageReader stages = new StageReader(progress);
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
             Writer saved = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                saved.write(buffer, 0, count);
                saved.flush();
                stages.accept(new String(buffer, 0, count));
            }
            int status = process.waitFor();
            if (cancelled) throw new CancellationException();
            if (status != 0) throw new IOException(failureMessage(status));
            if (!Files.isRegularFile(output) || Files.size(output) == 0) throw new IOException("Instrumentation finished without producing an APK. See the run log for details.");
        } catch (IOException error) {
            if (cancelled) throw new CancellationException();
            throw error;
        } finally { stop(); }
    }

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

    void saveApk(Path destination, boolean replace) throws IOException { copySafely(output, destination, input, replace); }

    static void copySafely(Path source, Path destination, Path protectedInput, boolean replace) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Choose an existing destination folder.");
        Path resolved = parent.toRealPath().resolve(target.getFileName());
        if (resolved.equals(protectedInput.toRealPath()) || (Files.exists(target) && Files.isSameFile(target, protectedInput))) {
            throw new IOException("Choose a different filename. The original APK must be kept for coverage reports.");
        }
        if (Files.isSymbolicLink(target)) throw new IOException("Choose a regular file, rather than a symbolic link, as the destination.");
        if (Files.exists(target) && !replace) throw new FileAlreadyExistsException(target.toString());
        Path temporary = Files.createTempFile(parent, ".androlog-save-", ".apk");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (replace) {
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            } else {
                // ATOMIC_MOVE may replace existing files even without REPLACE_EXISTING.
                Files.move(temporary, target);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    static void validateApk(Path apk) throws IOException {
        if (apk == null || !Files.isRegularFile(apk) || !Files.isReadable(apk)) throw new IOException("Choose a readable APK file.");
        if (!apk.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".apk")) throw new IOException("Choose an Android application file ending in .apk.");
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            if (zip.getEntry("AndroidManifest.xml") == null || zip.getEntry("classes.dex") == null) {
                throw new IOException("This APK needs an AndroidManifest.xml and classes.dex. Choose a complete application APK, rather than a resource-only split.");
            }
        }
    }

    String readLog() throws IOException {
        if (!Files.exists(log)) return "The instrumentation worker is starting…";
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            boolean trimmed = file.length() > 100000;
            file.seek(Math.max(0, file.length() - 100000));
            byte[] tail = new byte[(int) Math.min(100000, file.length())];
            file.readFully(tail);
            return (trimmed ? "[Showing the last 100 KB; save the log for the complete output.]\n" : "") + new String(tail, StandardCharsets.UTF_8);
        }
    }

    private String failureMessage(int status) throws IOException {
        String details = readLog();
        Matcher error = Pattern.compile("(?m)^Error[^\\r\\n]*").matcher(details);
        String last = "Instrumentation could not finish (exit " + status + "). Open the run log for details.";
        while (error.find()) last = error.group();
        return last.length() > 500 ? last.substring(0, 500) + "…" : last;
    }

    static String absoluteClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator), -1))
                .map(entry -> Paths.get(entry.isEmpty() ? "." : entry).toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static boolean any(boolean[] values) { for (boolean value : values) if (value) return true; return false; }

    @Override public void close() throws IOException {
        cancel();
        try { Runtime.getRuntime().removeShutdownHook(shutdown); }
        catch (IllegalStateException ignored) { /* JVM shutdown already owns process cleanup. */ }
        removeDirectory(directory);
    }

    private static void removeDirectory(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(path);
        }
    }

    /** Reserved worker messages are newline-delimited and may arrive in arbitrary pipe chunks. */
    static final class StageReader {
        private final StringBuilder pending = new StringBuilder();
        private final IntConsumer progress;
        private int last;
        StageReader(IntConsumer progress) { this.progress = progress; }
        void accept(String chunk) {
            pending.append(chunk);
            int end;
            while ((end = pending.indexOf("\n")) >= 0) {
                String line = pending.substring(0, end).trim();
                pending.delete(0, end + 1);
                if (line.matches("@@ANDROLOG_STAGE [1-4]")) {
                    int stage = line.charAt(line.length() - 1) - '0';
                    if (stage > last) { last = stage; progress.accept(stage); }
                }
            }
            if (pending.length() > 8192) pending.delete(0, pending.length() - 8192);
        }
    }
}
