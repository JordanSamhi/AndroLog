package com.jordansamhi.androlog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.Assert.*;

public class CaptureWorkflowTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void selectsTheOnlyReadyDeviceAndHonorsAnExplicitSerial() throws Exception {
        String devices = "List of devices attached\nemulator-5554 device product:test\nphone offline\nother unauthorized\n";
        assertEquals("emulator-5554", CaptureWorkflow.selectDevice(devices, null));
        assertEquals("emulator-5554", CaptureWorkflow.selectDevice(devices, "emulator-5554"));
        assertFailure(() -> CaptureWorkflow.selectDevice(devices, "phone"), "offline");
        assertFailure(() -> CaptureWorkflow.selectDevice(devices, "missing"), "not connected");
        assertFailure(() -> CaptureWorkflow.selectDevice("List of devices attached\n", null), "No ready Android device");
        assertFailure(() -> CaptureWorkflow.selectDevice(devices + "second device\n", null), "--device");
    }

    @Test public void discoversAdbFromTheSelectedPlatformsAndRespectsOverrides() throws Exception {
        Path sdk = temporary.newFolder("SDK with spaces").toPath();
        Path platforms = Files.createDirectories(sdk.resolve("platforms/android-30"));
        Path android = Files.createFile(platforms.resolve("android.jar"));
        Path adb = Files.createDirectories(sdk.resolve("platform-tools")).resolve("adb");
        Files.write(adb, "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(adb.toFile().setExecutable(true));
        assertEquals(adb.toString(), CaptureWorkflow.findAdb(null, android));
        assertEquals(adb.toString(), CaptureWorkflow.findAdb(adb.toString(), platforms));
        assertFailure(() -> CaptureWorkflow.findAdb(sdk.resolve("missing-adb").toString(), platforms), "not executable");
    }

    @Test public void reportOutputsCannotReplaceInputsOrEachOtherThroughSymlinks() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path apk = Files.createFile(root.resolve("original.apk"));
        Path instrumented = Files.createFile(root.resolve("instrumented.apk"));
        Path log = Files.createFile(root.resolve("runtime.log"));
        for (Path input : Arrays.asList(apk, instrumented, log)) {
            Path alias = root.resolve("alias-" + input.getFileName());
            Files.createSymbolicLink(alias, input);
            assertFailure(() -> CaptureWorkflow.validateOutputs(apk, instrumented, log, alias, root.resolve("report.json")), "separate");
        }
        assertFailure(() -> CaptureWorkflow.validateOutputs(apk, instrumented, log, root.resolve("report"), root.resolve("report")), "separate");
    }

    @Test public void zeroExitLaunchFailuresAreRejectedAndShellMetacharactersCannotBeManifestComponents() throws Exception {
        CaptureWorkflow.validateLaunchOutput("Starting: Intent\nStatus: ok\nActivity: example/.Main\nComplete\n");
        CaptureWorkflow.validateLaunchOutput("Status: ok\nActivity: example/.ExceptionActivity\n");
        assertFailure(() -> CaptureWorkflow.validateLaunchOutput("Error type 3\nActivity class does not exist."), "Cannot launch");
        assertFailure(() -> CaptureWorkflow.validateLaunchOutput("Status: timeout"), "Cannot launch");
        assertFailure(() -> CaptureWorkflow.validateLaunchOutput("Starting: Intent"), "did not confirm");
        assertFailure(() -> CaptureWorkflow.validateLaunchTarget(new LauncherActivities.LaunchTarget("example;touch /tmp/file", "example.Main", "example.Main", "android.intent.category.LAUNCHER")), "Invalid package");
        CaptureWorkflow.validateLaunchTarget(new LauncherActivities.LaunchTarget("example.app", "example.Main$Entry", "example.Main$Entry", "android.intent.category.LAUNCHER"));
    }

    @Test public void captureDiscardsOldRecordsAndDrainsFreshRecordsBeforeStopping() throws Exception {
        Path root = temporary.newFolder("capture").toPath();
        Path log = root.resolve("runtime.log");
        try (CaptureProcesses processes = new CaptureProcesses(System.err, root)) {
            try (AdbLogCapture capture = new AdbLogCapture(processes, fakeAdb(root), "TEST", log)) {
                capture.awaitReady();
                append(root, "10-22 12:00:01.000 123 123 I TEST: CLASS=fresh");
                capture.finish();
            }
        }
        String saved = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        assertFalse(saved.contains("CLASS=stale"));
        assertTrue(saved.contains("CLASS=fresh"));
        assertTrue(saved.indexOf("start-") < saved.indexOf("CLASS=fresh"));
        assertTrue(saved.indexOf("CLASS=fresh") < saved.indexOf("stop-"));
        assertTrue(Files.exists(root.resolve("stopped")));
    }

    @Test public void unexpectedLogcatExitKeepsThePartialLogAndReportsFailure() throws Exception {
        Path root = temporary.newFolder("disconnect").toPath();
        Path log = root.resolve("runtime.log");
        try (CaptureProcesses processes = new CaptureProcesses(System.err, root);
             AdbLogCapture capture = new AdbLogCapture(processes, fakeAdb(root), "TEST", log)) {
            capture.awaitReady();
            append(root, "10-22 12:00:01.000 123 123 I TEST: CLASS=before-disconnect");
            append(root, "QUIT");
            waitForFile(root.resolve("stopped"));
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (true) {
                try { capture.checkAlive(); }
                catch (IOException error) {
                    assertTrue(error.getMessage(), error.getMessage().contains("stopped unexpectedly"));
                    break;
                }
                assertTrue("Logcat exit should be detected", System.nanoTime() < deadline);
                Thread.sleep(10);
            }
        }
        assertTrue(new String(Files.readAllBytes(log), StandardCharsets.UTF_8).contains("CLASS=before-disconnect"));
    }

    @Test public void commandTimeoutTerminatesItsChild() throws Exception {
        Path root = temporary.newFolder("timeout").toPath();
        try (CaptureProcesses processes = new CaptureProcesses(System.err, root)) {
            List<String> command = fakeAdb(root);
            command.add("hang");
            assertFailure(() -> processes.run(command, 1, null), "timed out");
        }
        assertTrue(Files.exists(root.resolve("stopped")));
    }

    private List<String> fakeAdb(Path root) {
        return new ArrayList<>(Arrays.asList(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FakeAdb.class.getName(), root.toString()));
    }

    private static void append(Path root, String line) throws IOException {
        Files.write(root.resolve("bus"), (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void waitForFile(Path path) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("Expected " + path, Files.exists(path));
    }

    private interface Operation { void run() throws Exception; }
    private static void assertFailure(Operation operation, String message) throws Exception {
        try { operation.run(); fail("Expected failure containing " + message); }
        catch (IOException error) { assertTrue(error.toString(), error.getMessage().contains(message)); }
    }

    /** A real subprocess with a file-backed log buffer, avoiding dependencies on adb in unit tests. */
    public static final class FakeAdb {
        public static void main(String[] args) throws Exception {
            Path root = Paths.get(args[0]);
            if ("shell".equals(args[1])) {
                append(root, "10-22 12:00:00.000 123 123 I ANDROLOG_CAPTURE: " + args[args.length - 1]);
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Files.write(root.resolve("stopped"), new byte[]{1}); }
                catch (IOException ignored) { }
            }));
            if ("hang".equals(args[1])) { Thread.sleep(60000); return; }
            System.out.println("10-22 11:59:00.000 123 123 I TEST: CLASS=stale");
            try (RandomAccessFile bus = new RandomAccessFile(root.resolve("bus").toFile(), "rw")) {
                while (true) {
                    String line = bus.readLine();
                    if (line == null) { Thread.sleep(10); continue; }
                    if ("QUIT".equals(line)) return;
                    System.out.println(line);
                }
            }
        }
    }
}
