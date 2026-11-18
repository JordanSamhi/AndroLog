package com.jordansamhi.androlog;

import com.google.gson.Gson;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Small desktop bridge for testing an already instrumented APK on a selected device. */
public final class DeviceCapture {
    private static final Gson JSON = new Gson();

    private DeviceCapture() { }

    public static void main(String[] arguments) {
        int status = execute(arguments, System.in, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    static int execute(String[] arguments, InputStream input, PrintStream out, PrintStream err) {
        try {
            Map<String, String> options = options(arguments);
            Path platforms = Paths.get(required(options, "--platforms")).toAbsolutePath().normalize();
            String executable = CaptureWorkflow.findAdb(null, platforms);
            if (options.containsKey("--list")) {
                if (options.size() != 2) throw new IOException("Device listing only accepts --platforms and --list.");
                try (CaptureProcesses processes = new CaptureProcesses(err, platforms)) {
                    String listing = processes.run(Arrays.asList(executable, "devices", "-l"), 15, null);
                    out.println("@@ANDROLOG_DEVICES " + JSON.toJson(devices(listing)));
                    out.flush();
                }
                return 0;
            }
            String serial = required(options, "--device"), tag = required(options, "--tag");
            if (serial.length() > 512 || serial.startsWith("-") || serial.matches("(?s).*\\s.*") || serial.indexOf('\0') >= 0) {
                throw new IOException("Choose a valid Android device.");
            }
            if (!tag.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) throw new IOException("Use a valid Logcat tag.");
            Path apk = Paths.get(required(options, "--apk")).toAbsolutePath().normalize();
            Path log = Paths.get(required(options, "--output")).toAbsolutePath().normalize();
            if (!Files.isRegularFile(apk) || !Files.isReadable(apk)) throw new IOException("Cannot read the instrumented APK.");
            if (!Files.isDirectory(log.getParent()) || Files.exists(log, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Choose a new log file in an existing session folder.");
            }
            state(out, "preparing");
            LauncherActivities.LaunchTarget launcher = LauncherActivities.readLaunchTarget(apk);
            CaptureWorkflow.validateLaunchTarget(launcher);
            try (CaptureProcesses processes = new CaptureProcesses(err, log.getParent())) {
                String listing = processes.run(Arrays.asList(executable, "devices", "-l"), 15, null);
                CaptureWorkflow.selectDevice(listing, serial);
                capture(processes, Arrays.asList(executable, "-s", serial), launcher, apk, tag, log, input, out);
            }
            return 0;
        } catch (Exception error) {
            String message = error.getMessage();
            err.println("Error: " + (message == null ? error.getClass().getSimpleName() : message.replace('\r', ' ').replace('\n', ' ')));
            err.flush();
            return 1;
        }
    }

    static void capture(CaptureProcesses processes, List<String> adb, LauncherActivities.LaunchTarget launcher,
                        Path apk, String tag, Path log, InputStream input, PrintStream out) throws Exception {
        state(out, "installing");
        install(processes, adb, launcher, apk, input, out);
        processes.run(CaptureWorkflow.adb(adb, "shell", "am", "force-stop", launcher.packageName), 15, null);
        try (AdbLogCapture capture = new AdbLogCapture(processes, adb, tag, log)) {
            capture.awaitReady();
            state(out, "launching");
            String launched = processes.run(CaptureWorkflow.adb(adb, "shell", "am", "start", "-W",
                    "-a", "android.intent.action.MAIN", "-c", launcher.category, "-n", launcher.component()), 60, null);
            CaptureWorkflow.validateLaunchOutput(launched);
            state(out, "recording");
            waitForStop(capture, input);
            state(out, "stopping");
            capture.finish();
        }
    }

    private static void install(CaptureProcesses processes, List<String> adb, LauncherActivities.LaunchTarget launcher,
                                Path apk, InputStream input, PrintStream out) throws Exception {
        String installed;
        try {
            installed = processes.run(CaptureWorkflow.adb(adb, "install", "-r", apk.toString()), 180, null);
        } catch (IOException error) {
            // A non-zero adb exit includes the executable on the first line and
            // its output after the newline. Only the output is evidence of a conflict.
            String message = error.getMessage();
            int output = message == null ? -1 : message.indexOf('\n');
            if (output < 0 || !signatureConflict(message.substring(output + 1))) throw error;
            installed = null;
        }
        if (installed != null && !signatureConflict(installed)) {
            if (!installed.matches("(?s).*\\bSuccess\\b.*")) throw new IOException("ADB did not confirm installation: " + installed.trim());
            return;
        }

        Map<String, String> conflict = new LinkedHashMap<>();
        conflict.put("state", "signature-conflict");
        conflict.put("packageName", launcher.packageName);
        out.println("@@ANDROLOG_CAPTURE " + JSON.toJson(conflict));
        out.flush();
        if (!"uninstall".equals(readDecision(input))) {
            throw new IOException("Installation cancelled; the existing app was not removed.");
        }

        String uninstalled = processes.run(CaptureWorkflow.adb(adb, "uninstall", launcher.packageName), 180, null);
        if (!uninstalled.matches("(?m)^Success\\s*$")) {
            throw new IOException("ADB did not confirm removal of the existing app: " + uninstalled.trim());
        }
        state(out, "installing");
        // Retry only once; a second failure must never cause another removal.
        String retry = processes.run(CaptureWorkflow.adb(adb, "install", "-r", apk.toString()), 180, null);
        if (signatureConflict(retry) || !retry.matches("(?s).*\\bSuccess\\b.*")) {
            throw new IOException("ADB did not confirm installation after removing the existing app: " + retry.trim());
        }
    }

    private static boolean signatureConflict(String output) {
        // Match the adb failure code, not a coincidental token in an APK path
        // or another diagnostic. A bare code line is accepted as well.
        return output != null && java.util.regex.Pattern.compile(
                "(?m)(?:\\bFailure\\s*\\[|^\\s*)INSTALL_FAILED_UPDATE_INCOMPATIBLE(?=[:\\]\\s]|$)")
                .matcher(output).find();
    }

    private static String readDecision(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() <= 32) {
            int next = input.read();
            if (next < 0) return ""; // EOF is never authorization, even after a partial command.
            if (next == '\n') {
                byte[] line = bytes.toByteArray();
                int length = line.length;
                if (length > 0 && line[length - 1] == '\r') length--;
                return new String(line, 0, length, java.nio.charset.StandardCharsets.US_ASCII);
            }
            if (next > 127) return "";
            bytes.write(next);
        }
        return "";
    }

    private static void waitForStop(AdbLogCapture capture, InputStream input) throws Exception {
        CompletableFuture<Boolean> stopped = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try {
                int next;
                while ((next = input.read()) != -1) {
                    if (next == '\n') { stopped.complete(true); return; }
                }
                stopped.complete(false);
            } catch (IOException error) { stopped.completeExceptionally(error); }
        }, "androlog-device-stop");
        reader.setDaemon(true);
        reader.start();
        while (!stopped.isDone()) {
            capture.checkAlive();
            Thread.sleep(100);
        }
        if (!stopped.get()) throw new IOException("Capture interrupted because its controller closed. The partial log was kept.");
        capture.checkAlive();
    }

    static List<Device> devices(String listing) {
        List<Device> result = new ArrayList<>();
        for (String line : listing.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2 || !Arrays.asList("device", "offline", "unauthorized", "recovery", "sideload", "bootloader", "no", "authorizing", "connecting", "rescue").contains(parts[1])) continue;
            String name = parts[0], state = "no".equals(parts[1]) ? "no permissions" : parts[1];
            for (String part : parts) if (part.startsWith("model:")) name = part.substring(6).replace('_', ' ');
            result.add(new Device(parts[0], name, state));
        }
        return result;
    }

    static final class Device {
        final String serial, name, state;
        Device(String serial, String name, String state) { this.serial = serial; this.name = name; this.state = state; }
    }

    private static Map<String, String> options(String[] arguments) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < arguments.length; i++) {
            String key = arguments[i];
            if (!Arrays.asList("--platforms", "--list", "--apk", "--device", "--tag", "--output").contains(key)) throw new IOException("Unknown device capture option: " + key);
            if (result.containsKey(key)) throw new IOException("Duplicate device capture option: " + key);
            if ("--list".equals(key)) { result.put(key, "true"); continue; }
            if (++i == arguments.length) throw new IOException("Missing value for " + key);
            result.put(key, arguments[i]);
        }
        return result;
    }

    private static String required(Map<String, String> options, String key) throws IOException {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) throw new IOException("Missing " + key + ".");
        return value;
    }

    private static void state(PrintStream out, String value) {
        out.println("@@ANDROLOG_CAPTURE " + JSON.toJson(Collections.singletonMap("state", value)));
        out.flush();
    }
}
