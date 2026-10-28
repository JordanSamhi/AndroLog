package com.jordansamhi.androlog.gui;

import com.google.gson.*;
import com.jordansamhi.androlog.Main;
import com.jordansamhi.androlog.CustomLogPlan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs the existing baseline-matched report in a separate headless JVM. */
final class CoverageJob implements AutoCloseable {
    private final Path directory, report, diagnostics, apk, runtimeLog;
    private final String expectedLogHash;
    private final List<String> arguments;
    private Process process;
    private final Thread shutdown;
    CoverageJob(Path apk, Path runtimeLog, GuiSettings settings, String tag, boolean[] probes, String prefix, boolean exclude, String expectedLogHash) throws IOException {
        InstrumentationJob.validateApk(apk);
        if (!Files.isRegularFile(runtimeLog) || !Files.isReadable(runtimeLog)) throw new IOException("Choose a readable runtime log.");
        if (!GuiSettings.validPlatforms(settings.platforms)) throw new IOException("Choose an Android SDK platforms folder in SDK settings first.");
        if (!tag.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) throw new IOException("Enter the logcat tag used for instrumentation.");
        if (probes.length != 4 || !(probes[0] || probes[1] || probes[2] || probes[3])) throw new IOException("Select at least one probe used during instrumentation.");
        if (!prefix.isEmpty() && !prefix.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\.?")) throw new IOException("Enter a valid package prefix or leave it empty.");
        this.apk = apk; this.runtimeLog = runtimeLog; this.expectedLogHash = expectedLogHash;
        directory = Files.createTempDirectory("androlog-coverage-"); report = directory.resolve("coverage.json"); diagnostics = directory.resolve("report.log");
        arguments = new ArrayList<>(Arrays.asList(Paths.get(System.getProperty("java.home"), "bin", GuiSettings.windows() ? "java.exe" : "java").toString(),
            "-Djava.awt.headless=true", "-cp", InstrumentationJob.absoluteClasspath(), Main.class.getName(),
            "-a", apk.toAbsolutePath().toString(), "-p", settings.platforms.toString(), "-pa", runtimeLog.toAbsolutePath().toString(),
            "-l", tag, "-j", report.toString()));
        for (int i = 0; i < probes.length; i++) if (probes[i]) arguments.add(InstrumentationJob.FLAGS[i]);
        if (!prefix.isEmpty()) { arguments.add("-pkg"); arguments.add(prefix); } if (exclude) arguments.add("-n");
        shutdown = new Thread(this::stop, "androlog-coverage-cleanup"); Runtime.getRuntime().addShutdownHook(shutdown);
    }
    Map<String, Metric> run() throws IOException, InterruptedException {
        String apkHash = CustomLogPlan.sha256(apk);
        if (!expectedLogHash.equals(CustomLogPlan.sha256(runtimeLog))) throw new IOException("The log changed. Reload it before comparing coverage.");
        synchronized (this) {
            process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(diagnostics.toFile()).start();
            process.getOutputStream().close();
        }
        int exit = process.waitFor();
        if (exit != 0) {
            String log = new String(Files.readAllBytes(diagnostics), StandardCharsets.UTF_8);
            int last = log.lastIndexOf("Error");
            throw new IOException(last >= 0 ? log.substring(last).trim() : "Coverage analysis failed (exit " + exit + "). Check the APK and SDK settings.");
        }
        if (!expectedLogHash.equals(CustomLogPlan.sha256(runtimeLog)) || !apkHash.equals(CustomLogPlan.sha256(apk)))
            throw new IOException("The APK or log changed during analysis. Reload and compare again.");
        try (Reader reader = Files.newBufferedReader(report, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("coverageSummary");
            Map<String, Metric> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                values.put(entry.getKey(), new Metric(value.get("covered").getAsInt(), value.get("total").getAsInt()));
            }
            return values;
        } catch (RuntimeException error) { throw new IOException("Coverage analysis returned an invalid report.", error); }
    }
    private synchronized void stop() {
        if (process != null && process.isAlive()) {
            process.destroy(); try { if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }
    @Override public void close() throws IOException {
        stop(); try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.deleteIfExists(path);
        }
    }
    static final class Metric {
        final int observed, total;
        Metric(int observed, int total) { this.observed = observed; this.total = total; }
        String display() { return total == 0 ? "—" : String.format(Locale.ROOT, "%.1f%%", 100.0 * observed / total); }
    }
}
