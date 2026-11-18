package com.jordansamhi.androlog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class DeviceCaptureTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void listsReadyAndUnavailableDevicesWithoutAdbDaemonNoise() {
        List<DeviceCapture.Device> devices = DeviceCapture.devices("* daemon started successfully *\nList of devices attached\n"
                + "emulator-5554 device product:sdk model:Pixel_9 device:test transport_id:1\n"
                + "phone unauthorized\noffline-phone offline\nusb no permissions (missing udev rules)\n");
        assertEquals(4, devices.size());
        assertEquals("emulator-5554", devices.get(0).serial);
        assertEquals("Pixel 9", devices.get(0).name);
        assertEquals("device", devices.get(0).state);
        assertEquals("unauthorized", devices.get(1).state);
        assertEquals("offline", devices.get(2).state);
        assertEquals("no permissions", devices.get(3).state);
        assertTrue(DeviceCapture.devices("List of devices attached\n").isEmpty());
    }

    @Test public void recordsStartupThenDrainsOnStopWithoutReinstrumentingOrClearingLogs() throws Exception {
        Path root = temporary.newFolder("normal").toPath();
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        capture(root, new ByteArrayInputStream(new byte[]{'\n'}), progress);
        String saved = read(root.resolve("runtime.log"));
        assertFalse(saved.contains("CLASS=stale"));
        assertTrue(saved.contains("CLASS=launcher"));
        assertTrue(saved.indexOf("start-") < saved.indexOf("CLASS=launcher"));
        assertTrue(saved.indexOf("CLASS=launcher") < saved.indexOf("stop-"));
        String states = progress.toString("UTF-8");
        assertTrue(states.indexOf("installing") < states.indexOf("launching"));
        assertTrue(states.indexOf("launching") < states.indexOf("recording"));
        assertTrue(states.indexOf("recording") < states.indexOf("stopping"));
        String commands = read(root.resolve("commands"));
        assertTrue(commands.contains("install|-r|"));
        assertFalse(commands.contains("uninstall"));
        assertFalse(commands.contains("logcat|-c"));
        assertTrue(Files.exists(root.resolve("stopped")));
    }

    @Test public void zeroExitLaunchFailureNeverReportsRecordingAndKeepsItsLog() throws Exception {
        Path root = temporary.newFolder("launch-failure").toPath();
        Files.createFile(root.resolve("fail-launch"));
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        assertFailure(() -> capture(root, new ByteArrayInputStream(new byte[]{'\n'}), progress), "Cannot launch");
        assertFalse(progress.toString("UTF-8").contains("recording"));
        assertTrue(Files.exists(root.resolve("runtime.log")));
        assertTrue(Files.exists(root.resolve("stopped")));
    }

    @Test public void installationFailureDoesNotLaunchOrDeleteTheExistingApp() throws Exception {
        Path root = temporary.newFolder("install-failure").toPath();
        Files.createFile(root.resolve("fail-install"));
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        assertFailure(() -> capture(root, new ByteArrayInputStream(new byte[]{'\n'}), progress), "ADB did not confirm installation");
        assertFalse(progress.toString("UTF-8").contains("recording"));
        String commands = read(root.resolve("commands"));
        assertFalse(commands.contains("uninstall"));
        assertFalse(commands.contains("force-stop"));
    }

    @Test public void approvedSignatureConflictUninstallsOnlyTheTargetThenRetriesAndCaptures() throws Exception {
        Path root = temporary.newFolder("approved-conflict").toPath();
        Files.createFile(root.resolve("conflict-until-removed"));
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        capture(root, control("uninstall\n\n"), progress);
        String states = progress.toString("UTF-8");
        assertTrue(states.contains("\"state\":\"signature-conflict\""));
        assertTrue(states.contains("\"packageName\":\"example.app\""));
        assertTrue(states.contains("\"state\":\"recording\""));
        List<String> commands = Files.readAllLines(root.resolve("commands"), StandardCharsets.UTF_8);
        int firstInstall = findCommand(commands, "install|-r|", 0);
        int uninstall = findCommand(commands, "uninstall|example.app", firstInstall + 1);
        int secondInstall = findCommand(commands, "install|-r|", uninstall + 1);
        assertTrue(firstInstall >= 0 && uninstall > firstInstall && secondInstall > uninstall);
        assertEquals(1, commands.stream().filter(command -> command.startsWith("uninstall|")).count());
        assertEquals(commands.get(firstInstall), commands.get(secondInstall));
        List<String> targeted = Files.readAllLines(root.resolve("targeted-commands"), StandardCharsets.UTF_8);
        assertTrue(targeted.contains("emulator-5554|uninstall|example.app"));
        assertTrue(targeted.stream().allMatch(command -> command.startsWith("emulator-5554|")));
        assertTrue(read(root.resolve("runtime.log")).contains("CLASS=launcher"));
    }

    @Test public void nonzeroSignatureConflictAlsoRequiresApprovalAndRetries() throws Exception {
        Path root = temporary.newFolder("nonzero-conflict").toPath();
        Files.createFile(root.resolve("conflict-until-removed"));
        Files.createFile(root.resolve("nonzero-install"));
        capture(root, control("uninstall\n\n"), new ByteArrayOutputStream());
        assertTrue(read(root.resolve("commands")).contains("uninstall|example.app"));
        assertTrue(read(root.resolve("runtime.log")).contains("CLASS=launcher"));
    }

    @Test public void refusingOrClosingTheControllerNeverRemovesTheExistingApp() throws Exception {
        for (String decision : Arrays.asList("cancel\n", "yes\n", "uninstall", "", String.join("", Collections.nCopies(6, "uninstall ")) + "\n")) {
            Path root = temporary.newFolder().toPath();
            Files.createFile(root.resolve("conflict-until-removed"));
            ByteArrayOutputStream progress = new ByteArrayOutputStream();
            assertFailure(() -> capture(root, control(decision), progress), "existing app was not removed");
            assertTrue(progress.toString("UTF-8").contains("\"state\":\"signature-conflict\""));
            assertFalse(read(root.resolve("commands")).contains("uninstall|"));
            assertFalse(Files.exists(root.resolve("runtime.log")));
        }
    }

    @Test public void unrelatedInstallErrorsDoNotRequestOrTriggerRemoval() throws Exception {
        Path root = temporary.newFolder("unrelated-failure").toPath();
        Files.createFile(root.resolve("fail-unrelated-install"));
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        assertFailure(() -> capture(root, control("uninstall\n"), progress), "INSTALL_FAILED_VERSION_DOWNGRADE");
        assertFalse(progress.toString("UTF-8").contains("signature-conflict"));
        assertFalse(read(root.resolve("commands")).contains("uninstall|"));
    }

    @Test public void conflictCodeInAnUnrelatedApkPathIsNotASignatureFailure() throws Exception {
        Path root = temporary.newFolder("marker-in-path").toPath();
        Files.createFile(root.resolve("marker-in-path"));
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        assertFailure(() -> capture(root, control("uninstall\n"), progress), "INSTALL_FAILED_VERSION_DOWNGRADE");
        assertFalse(progress.toString("UTF-8").contains("signature-conflict"));
        assertFalse(read(root.resolve("commands")).contains("uninstall|"));
    }

    @Test public void removalFailureDoesNotRetryInstallation() throws Exception {
        Path root = temporary.newFolder("uninstall-failure").toPath();
        Files.createFile(root.resolve("conflict-until-removed"));
        Files.createFile(root.resolve("fail-uninstall"));
        assertFailure(() -> capture(root, control("uninstall\n"), new ByteArrayOutputStream()), "did not confirm removal");
        List<String> commands = Files.readAllLines(root.resolve("commands"), StandardCharsets.UTF_8);
        assertEquals(1, commands.stream().filter(command -> command.startsWith("install|-r|")).count());
        assertEquals(1, commands.stream().filter(command -> command.startsWith("uninstall|")).count());
        assertFalse(Files.exists(root.resolve("runtime.log")));
    }

    @Test public void retryFailureDoesNotRemoveAgainOrLaunch() throws Exception {
        Path root = temporary.newFolder("retry-failure").toPath();
        Files.createFile(root.resolve("conflict-until-removed"));
        Files.createFile(root.resolve("fail-retry"));
        assertFailure(() -> capture(root, control("uninstall\n"), new ByteArrayOutputStream()), "after removing the existing app");
        List<String> commands = Files.readAllLines(root.resolve("commands"), StandardCharsets.UTF_8);
        assertEquals(2, commands.stream().filter(command -> command.startsWith("install|-r|")).count());
        assertEquals(1, commands.stream().filter(command -> command.startsWith("uninstall|")).count());
        assertFalse(commands.stream().anyMatch(command -> command.contains("force-stop")));
    }

    @Test public void controllerClosureIsAnAbortAndPreservesThePartialCapture() throws Exception {
        Path root = temporary.newFolder("controller-closed").toPath();
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        assertFailure(() -> capture(root, new ByteArrayInputStream(new byte[0]), progress), "controller closed");
        assertTrue(progress.toString("UTF-8").contains("recording"));
        assertFalse(progress.toString("UTF-8").contains("stopping"));
        assertTrue(read(root.resolve("runtime.log")).contains("CLASS=launcher"));
        assertTrue(Files.exists(root.resolve("stopped")));
    }

    @Test public void deviceDisconnectionEndsTheWaitAndKeepsThePartialLog() throws Exception {
        Path root = temporary.newFolder("disconnect").toPath();
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        try (PipedInputStream input = new PipedInputStream(); PipedOutputStream control = new PipedOutputStream(input)) {
            CompletableFuture<Exception> completed = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
                try { capture(root, input, progress); completed.complete(null); }
                catch (Exception error) { completed.complete(error); }
            });
            worker.setDaemon(true);
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!progress.toString("UTF-8").contains("recording") && !completed.isDone() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue("Capture should start", progress.toString("UTF-8").contains("recording"));
            append(root, "QUIT");
            Exception error = completed.get(10, TimeUnit.SECONDS);
            assertNotNull(error);
            assertTrue(error.toString(), error.getMessage().contains("stopped unexpectedly"));
        }
        assertTrue(read(root.resolve("runtime.log")).contains("CLASS=launcher"));
    }

    @Test public void malformedBridgeOptionsProduceOneErrorAndNonzeroStatus() throws Exception {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        assertEquals(1, DeviceCapture.execute(new String[]{"--unknown"}, new ByteArrayInputStream(new byte[0]), System.out, new PrintStream(error)));
        assertTrue(error.toString("UTF-8").startsWith("Error: Unknown device capture option"));
    }

    private void capture(Path root, InputStream input, ByteArrayOutputStream progress) throws Exception {
        Path apk = root.resolve("instrumented with spaces.apk");
        Files.write(apk, new byte[]{1});
        List<String> command = new ArrayList<>(Arrays.asList(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FakeAdb.class.getName(), root.toString(), "-s", "emulator-5554"));
        LauncherActivities.LaunchTarget launcher = new LauncherActivities.LaunchTarget("example.app", "example.app.Main$Entry", "example.app.Main$Entry", "android.intent.category.LAUNCHER");
        try (CaptureProcesses processes = new CaptureProcesses(System.err, root)) {
            DeviceCapture.capture(processes, command, launcher, apk, "TEST", root.resolve("runtime.log"), input, new PrintStream(progress, true, "UTF-8"));
        }
    }

    private static String read(Path file) throws IOException { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
    private static InputStream control(String commands) { return new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)); }
    private static int findCommand(List<String> commands, String prefix, int from) {
        for (int index = from; index < commands.size(); index++) if (commands.get(index).startsWith(prefix)) return index;
        return -1;
    }
    private static void append(Path root, String line) throws IOException {
        Files.write(root.resolve("bus"), (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private interface Operation { void run() throws Exception; }
    private static void assertFailure(Operation operation, String expected) throws Exception {
        try { operation.run(); fail("Expected " + expected); }
        catch (IOException error) { assertTrue(error.toString(), error.getMessage().contains(expected)); }
    }

    /** Portable subprocess fixture exercising the actual process and marker protocol. */
    public static final class FakeAdb {
        public static void main(String[] arguments) throws Exception {
            Path root = Paths.get(arguments[0]);
            String[] args = Arrays.copyOfRange(arguments, 1, arguments.length);
            String device = "";
            if (args.length >= 2 && "-s".equals(args[0])) {
                device = args[1];
                args = Arrays.copyOfRange(args, 2, args.length);
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i].startsWith("'") && args[i].endsWith("'")) args[i] = args[i].substring(1, args[i].length() - 1);
            }
            Files.write(root.resolve("commands"), (String.join("|", args) + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.write(root.resolve("targeted-commands"), (device + "|" + String.join("|", args) + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if ("install".equals(args[0])) {
                if (Files.exists(root.resolve("marker-in-path"))) {
                    System.out.println("adb: failed to install /tmp/INSTALL_FAILED_UPDATE_INCOMPATIBLE.apk: Failure [INSTALL_FAILED_VERSION_DOWNGRADE]");
                } else if (Files.exists(root.resolve("fail-unrelated-install"))) {
                    System.out.println("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]");
                } else if (Files.exists(root.resolve("fail-install"))) {
                    // Existing test exercises a generic installation failure.
                    System.out.println("Failure [INSTALL_FAILED_INVALID_APK]");
                } else if (Files.exists(root.resolve("conflict-until-removed"))
                        && (!Files.exists(root.resolve("uninstalled")) || Files.exists(root.resolve("fail-retry")))) {
                    System.out.println("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures differ]");
                    if (Files.exists(root.resolve("nonzero-install"))) System.exit(1);
                } else System.out.println("Success");
                return;
            }
            if ("uninstall".equals(args[0])) {
                if (Files.exists(root.resolve("fail-uninstall"))) System.out.println("Failure [DELETE_FAILED_INTERNAL_ERROR]");
                else { Files.createFile(root.resolve("uninstalled")); System.out.println("Success"); }
                return;
            }
            if ("shell".equals(args[0])) {
                if ("log".equals(args[1])) append(root, "10-29 12:00:00.000 123 123 I ANDROLOG_CAPTURE: " + args[args.length - 1]);
                else if ("start".equals(args[2])) {
                    if (Files.exists(root.resolve("fail-launch"))) System.out.println("Error type 3\nActivity does not exist");
                    else { append(root, "10-29 12:00:01.000 123 123 I TEST: CLASS=launcher"); System.out.println("Status: ok"); }
                }
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Files.write(root.resolve("stopped"), new byte[]{1}); }
                catch (IOException ignored) { }
            }));
            System.out.println("10-29 11:59:00.000 123 123 I TEST: CLASS=stale");
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
