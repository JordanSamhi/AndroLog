package com.jordansamhi.androlog;

import com.jordansamhi.androspecter.TmpFolder;
import org.apache.commons.cli.CommandLine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Coordinates the existing instrumentation and report commands in isolated JVMs. */
final class CaptureWorkflow {
    private final CommandLine options;
    private final InputStream input;
    private final PrintStream out, err;
    private final CliOutput console;

    CaptureWorkflow(CommandLine options, InputStream input, PrintStream out, PrintStream err, CliOutput console) {
        this.options = options;
        this.input = input;
        this.out = out;
        this.err = err;
        this.console = console;
    }

    void run() throws Exception {
        Path apk = path(options.getOptionValue("a"));
        Path root = path(options.getOptionValue("o", TmpFolder.v().get()));
        Files.createDirectories(root);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path session = Files.createTempDirectory(root, "capture-" + stamp + "-");
        Path instrumentedDirectory = session.resolve("instrumented");
        Path instrumented = instrumentedDirectory.resolve(apk.getFileName());
        Path log = session.resolve("runtime.log");
        Path html = path(options.getOptionValue("html", session.resolve("coverage.html").toString()));
        Path json = path(options.getOptionValue("j", session.resolve("coverage.json").toString()));
        validateOutputs(apk, instrumented, log, html, json);
        String tag = options.getOptionValue("l", "ANDROLOG");

        console.begin("Capture and report", 5);
        console.context("APK", apk.toString());
        console.context("Session", session.toString());
        console.context("Log tag", tag);
        console.output("Session", session);
        try (CaptureProcesses processes = new CaptureProcesses(err, session)) {
            List<String> adb = new ArrayList<>();
            LauncherActivities.LaunchTarget[] launcher = new LauncherActivities.LaunchTarget[1];
            console.stageBlock("Prepare device and launcher", () -> {
                String executable = findAdb(options.getOptionValue("adb"), path(options.getOptionValue("p")));
                String devices = processes.run(Arrays.asList(executable, "devices", "-l"), 15, null);
                String serial = selectDevice(devices, options.getOptionValue("device"));
                adb.addAll(Arrays.asList(executable, "-s", serial));
                launcher[0] = LauncherActivities.readLaunchTarget(apk);
                validateLaunchTarget(launcher[0]);
                console.context("Device", serial);
                console.context("Launcher", launcher[0].component());
            });
            console.stageBlock("Instrument and sign APK", () -> {
                List<String> arguments = commonArguments();
                arguments.addAll(Arrays.asList("-o", instrumentedDirectory.toString()));
                runAndroLog(processes, arguments, session.resolve("instrumentation.log"));
            });
            console.output("APK", instrumented);
            console.stageBlock("Install APK", () -> {
                String installed = processes.run(adb(adb, "install", "-r", instrumented.toString()), 180, null);
                if (!installed.matches("(?s).*\\bSuccess\\b.*")) throw new IOException("ADB did not confirm installation: " + installed.trim());
            });
            console.stageBlock("Capture app execution", () -> {
                LauncherActivities.LaunchTarget target = launcher[0];
                // Restart just this app so once-per-process probes are emitted in the new capture.
                processes.run(adb(adb, "shell", "am", "force-stop", target.packageName), 15, null);
                try (AdbLogCapture capture = new AdbLogCapture(processes, adb, tag, log)) {
                    console.output("Log", log);
                    capture.awaitReady();
                    String launched = processes.run(adb(adb, "shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
                            "-c", target.category, "-n", target.component()), 60, null);
                    validateLaunchOutput(launched);
                    out.println("App launched. Exercise it on the device.");
                    if (options.hasOption("duration")) {
                        int seconds = Integer.parseInt(options.getOptionValue("duration"));
                        out.println("Capturing for " + seconds + " seconds. Ctrl+C aborts and keeps the raw log.");
                        waitForDuration(capture, seconds);
                    } else {
                        out.println("Press Enter here to stop capture and generate the reports. Ctrl+C aborts and keeps the raw log.");
                        waitForEnter(capture);
                    }
                    capture.finish();
                }
            });
            console.stageBlock("Generate coverage reports", () -> {
                List<String> arguments = commonArguments();
                arguments.addAll(Arrays.asList("-pam", log.toString(), "-html", html.toString(), "-j", json.toString()));
                runAndroLog(processes, arguments, session.resolve("report-generation.log"));
            });
            console.output("HTML", html);
            console.output("JSON", json);
            console.complete();
        }
    }

    private List<String> commonArguments() {
        List<String> result = new ArrayList<>();
        for (String flag : Arrays.asList("a", "p", "l", "pkg", "t")) {
            if (options.hasOption(flag)) result.addAll(Arrays.asList("-" + flag, options.getOptionValue(flag)));
        }
        for (String flag : Arrays.asList("c", "m", "s", "cp", "mc", "n", "v")) {
            if (options.hasOption(flag)) result.add("-" + flag);
        }
        return result;
    }

    private void runAndroLog(CaptureProcesses processes, List<String> arguments, Path diagnostics) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(Arrays.asList(java, "-cp", System.getProperty("java.class.path"), Main.class.getName()));
        command.addAll(arguments);
        boolean[] summary = {false};
        try (PrintWriter saved = new PrintWriter(Files.newBufferedWriter(diagnostics, StandardCharsets.UTF_8))) {
            processes.run(command, 0, line -> {
                saved.println(line);
                saved.flush();
                if (line.matches("\\[\\d+/\\d+].*")) {
                    summary[0] = false;
                    out.println("  " + line.replaceFirst("^\\[\\d+/\\d+]\\s*", ""));
                } else {
                    if ("Coverage summary".equals(line)) summary[0] = true;
                    if (summary[0] || options.hasOption("v")) out.println(line);
                }
            });
            if (saved.checkError()) throw new IOException("Cannot write capture diagnostics: " + diagnostics);
        }
    }

    private void waitForEnter(AdbLogCapture capture) throws Exception {
        CompletableFuture<String> entered = new CompletableFuture<>();
        Thread keyboard = new Thread(() -> {
            try { entered.complete(new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).readLine()); }
            catch (IOException error) { entered.completeExceptionally(error); }
        }, "androlog-capture-keyboard");
        keyboard.setDaemon(true);
        keyboard.start();
        while (!entered.isDone()) {
            capture.checkAlive();
            Thread.sleep(100);
        }
        if (entered.get() == null) throw new IOException("Standard input closed. Use --duration <seconds> for non-interactive capture. The raw log was kept.");
    }

    private static void waitForDuration(AdbLogCapture capture, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            capture.checkAlive();
            Thread.sleep(100);
        }
    }

    static String selectDevice(String output, String requested) throws IOException {
        Map<String, String> devices = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && Arrays.asList("device", "offline", "unauthorized", "recovery", "sideload", "bootloader", "no").contains(parts[1])) {
                devices.put(parts[0], parts[1]);
            }
        }
        if (requested != null) {
            if (!"device".equals(devices.get(requested))) {
                throw new IOException("Device " + requested + " is not ready (" + devices.getOrDefault(requested, "not connected") + "). Check adb devices.");
            }
            return requested;
        }
        List<String> online = new ArrayList<>();
        for (Map.Entry<String, String> device : devices.entrySet()) if ("device".equals(device.getValue())) online.add(device.getKey());
        if (online.isEmpty()) throw new IOException("No ready Android device. Connect and authorize a device or start an emulator. Detected: " + devices);
        if (online.size() > 1) throw new IOException("Several devices are ready: " + String.join(", ", online) + ". Select one with --device <serial>.");
        return online.get(0);
    }

    static String findAdb(String explicit, Path platforms) throws IOException {
        if (explicit != null) {
            String found = executable(explicit);
            if (found != null) return found;
            throw new IOException("ADB is not executable: " + explicit);
        }
        List<Path> roots = new ArrayList<>();
        Path ancestor = platforms.toAbsolutePath();
        for (int i = 0; i < 4 && ancestor != null; i++, ancestor = ancestor.getParent()) roots.add(ancestor);
        for (String variable : Arrays.asList("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            String root = System.getenv(variable);
            if (root != null && !root.isEmpty()) roots.add(path(root));
        }
        for (Path root : roots) {
            Path candidate = root.resolve("platform-tools").resolve(windows() ? "adb.exe" : "adb");
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toString();
        }
        String found = executable(windows() ? "adb.exe" : "adb");
        if (found != null) return found;
        throw new IOException("Cannot find adb. Install Android SDK Platform Tools or set --adb /path/to/adb.");
    }

    private static String executable(String value) {
        Path candidate = path(value);
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toString();
        if (value.contains("/") || value.contains("\\")) return null;
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            candidate = Paths.get(directory, value);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath().toString();
        }
        return null;
    }

    static void validateLaunchTarget(LauncherActivities.LaunchTarget target) throws IOException {
        // adb shell joins remote arguments: manifest-derived values must not contain shell syntax.
        if (target.packageName == null || !target.packageName.matches("[A-Za-z0-9_.$]+")
                || !target.componentName.matches("[A-Za-z0-9_.$]+")) {
            throw new IOException("Invalid package or launcher component in the manifest.");
        }
    }

    static void validateLaunchOutput(String output) throws IOException {
        if (Pattern.compile("(?im)^(?:Error(?::| type)|(?:[\\w.$]+\\.)?\\w*Exception(?::|\\s)|Status:\\h*(?:timeout|error)|Unable to resolve)")
                .matcher(output).find()) {
            throw new IOException("Cannot launch the app: " + output.trim());
        }
        if (!Pattern.compile("(?m)^Status:\\h*ok\\h*$").matcher(output).find()) {
            throw new IOException("ADB did not confirm app launch: " + output.trim());
        }
    }

    static void validateOutputs(Path apk, Path instrumented, Path log, Path html, Path json) throws IOException {
        for (Path output : Arrays.asList(html, json)) {
            for (Path input : Arrays.asList(apk, instrumented, log)) {
                if (Main.sameFile(output, input)) throw new IOException("Capture reports must be separate from APKs and the runtime log: " + output);
            }
        }
        if (Main.sameFile(html, json)) throw new IOException("HTML and JSON outputs must be separate files.");
    }

    private static List<String> adb(List<String> prefix, String... arguments) {
        List<String> result = new ArrayList<>(prefix);
        boolean shell = arguments.length > 0 && "shell".equals(arguments[0]);
        for (int i = 0; i < arguments.length; i++) {
            // adb shell passes a remote command string, including class names containing '$'.
            result.add(shell && i > 0 ? "'" + arguments[i].replace("'", "'\\''") + "'" : arguments[i]);
        }
        return result;
    }

    private static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
    private static Path path(String value) { return Paths.get(value).toAbsolutePath().normalize(); }
}
