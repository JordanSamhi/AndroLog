package com.jordansamhi.androlog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** These validation paths intentionally stop before Soot or dependency singleton initialization. */
public class MainTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void helpWorksAfterOtherOptionsWithoutRequiredInputs() throws Exception {
        Result result = invoke("-a", "missing.apk", "--help");
        assertEquals(0, result.status);
        assertTrue(result.out.contains("--verbose"));
        assertTrue(result.out.contains("--parse-per-minute"));
        assertTrue(result.out.contains("--capture"));
        assertTrue(result.out.contains("--duration"));
        assertEquals("", result.err);
    }

    @Test public void absentArgumentsAndUnknownOptionsReturnUsageFailures() throws Exception {
        Result empty = invoke();
        assertEquals(2, empty.status);
        assertTrue(empty.out.contains("usage:"));
        assertTrue(empty.err.contains("--apk"));
        Result unknown = invoke("--not-an-option");
        assertEquals(2, unknown.status);
        assertTrue(unknown.err.contains("--not-an-option"));
        assertFalse(unknown.err.contains("\tat "));
    }

    @Test public void invalidThreadCountsFailBeforeReadingTheApk() throws Exception {
        for (String value : new String[]{"0", "-1", "many", "2147483648"}) {
            Result result = invoke("-a", "missing.apk", "-p", "missing-platforms", "-t", value);
            assertEquals(2, result.status);
            assertTrue(result.err.contains("positive integer"));
        }
    }

    @Test public void reportsMissingInputFilesOnStderr() throws Exception {
        Path platforms = temporary.newFolder("platforms").toPath();
        Result missingApk = invoke("-a", "missing.apk", "-p", platforms.toString());
        assertEquals(2, missingApk.status);
        assertTrue(missingApk.err.contains("APK is not a readable file"));
        Path apk = temporary.newFile("input.apk").toPath();
        Result missingLog = invoke("-a", apk.toString(), "-p", platforms.toString(), "-pa", "missing.log");
        assertEquals(2, missingLog.status);
        assertTrue(missingLog.err.contains("Runtime log is not a readable file"));
    }

    @Test public void refusesConflictingParsingModes() throws Exception {
        Result result = invoke("-a", "unused.apk", "-p", "unused", "-pa", "one.log", "-pam", "two.log");
        assertEquals(2, result.status);
        assertTrue(result.err.contains("either --parse or --parse-per-minute"));
    }

    @Test public void captureRequiresProbesAndCannotParseAnExistingLog() throws Exception {
        Result modes = invoke("-a", "unused.apk", "-p", "unused", "--capture", "-pa", "old.log", "-m");
        assertEquals(2, modes.status);
        assertTrue(modes.err.contains("cannot be combined"));
        Result probes = invoke("-a", "unused.apk", "-p", "unused", "--capture");
        assertEquals(2, probes.status);
        assertTrue(probes.err.contains("Select probes"));
    }

    @Test public void captureOnlyArgumentsAndInvalidDurationsAreRejected() throws Exception {
        for (String flag : new String[]{"--device", "--adb", "--duration"}) {
            Result result = invoke("-a", "unused.apk", "-p", "unused", flag, "one");
            assertEquals(2, result.status);
            assertTrue(result.err.contains("requires --capture"));
        }
        for (String seconds : new String[]{"0", "-1", "1.5", "many", "2147483648"}) {
            Result result = invoke("-a", "unused.apk", "-p", "unused", "--capture", "-m", "--duration", seconds);
            assertEquals(2, result.status);
            assertTrue(result.err.contains("positive number of seconds"));
        }
        Result tag = invoke("-a", "unused.apk", "-p", "unused", "--capture", "-m", "-l", "unsafe:tag");
        assertEquals(2, tag.status);
        assertTrue(tag.err.contains("Capture log tags"));
    }

    @Test public void captureReportCannotOverwriteTheOriginalApk() throws Exception {
        Path platforms = temporary.newFolder("platforms").toPath();
        Path apk = temporary.newFile("input.apk").toPath();
        Result result = invoke("-a", apk.toString(), "-p", platforms.toString(), "--capture", "-m", "-html", apk.toString());
        assertEquals(2, result.status);
        assertTrue(result.err.contains("HTML output must be separate"));
    }

    @Test public void jsonOutputCannotOverwriteTheApkOrLogEvenWithoutHtml() throws Exception {
        Path platforms = temporary.newFolder("platforms").toPath();
        Path apk = temporary.newFile("input.apk").toPath();
        Path log = temporary.newFile("runtime.log").toPath();
        Files.write(apk, "original apk".getBytes(StandardCharsets.UTF_8));
        Files.write(log, "original log".getBytes(StandardCharsets.UTF_8));
        for (Path output : new Path[]{apk, log}) {
            Result result = invoke("-a", apk.toString(), "-p", platforms.toString(), "-pa", log.toString(), "-j", output.toString());
            assertEquals(2, result.status);
            assertTrue(result.err.contains("JSON output must be separate"));
        }
        assertEquals("original apk", new String(Files.readAllBytes(apk), StandardCharsets.UTF_8));
        assertEquals("original log", new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
    }

    @Test public void outputAliasesThroughSymbolicLinksAreRejected() throws Exception {
        Path platforms = temporary.newFolder("platforms").toPath();
        Path apk = temporary.newFile("input.apk").toPath();
        Path log = temporary.newFile("runtime.log").toPath();
        Path alias = temporary.getRoot().toPath().resolve("alias.json");
        Files.createSymbolicLink(alias, log);
        Result result = invoke("-a", apk.toString(), "-p", platforms.toString(), "-pa", log.toString(), "-j", alias.toString());
        assertEquals(2, result.status);
        assertTrue(result.err.contains("JSON output must be separate"));
    }

    @Test public void instrumentedOutputCannotReplaceInputApk() throws Exception {
        Path directory = temporary.newFolder("same directory").toPath();
        Path apk = directory.resolve("input.apk");
        Files.write(apk, "input".getBytes(StandardCharsets.UTF_8));
        Result result = invoke("-a", apk.toString(), "-p", directory.toString(), "-o", directory.toString(), "-m");
        assertEquals(2, result.status);
        assertTrue(result.err.contains("would overwrite the input"));
    }

    @Test public void nonexistentOutputsThroughSymlinkedParentDirectoriesCannotOverwriteEachOther() throws Exception {
        Path directory = temporary.newFolder("real outputs").toPath();
        Path alias = temporary.getRoot().toPath().resolve("linked outputs");
        Files.createSymbolicLink(alias, directory);
        Path apk = temporary.newFile("input.apk").toPath();
        Path log = temporary.newFile("runtime.log").toPath();
        Path html = directory.resolve("not-created-yet/report");
        Path json = alias.resolve("not-created-yet/report");

        Result result = invoke("-a", apk.toString(), "-p", directory.toString(), "-pa", log.toString(),
                "-m", "-html", html.toString(), "-j", json.toString());

        assertEquals(2, result.status);
        assertTrue(result.err.contains("HTML and JSON outputs must be separate"));
        assertFalse(Files.exists(html));
    }

    @Test public void codeInspectionAndCustomLogsRejectConflictingModes() throws Exception {
        for (String[] mode : new String[][]{{"--inspect-code", "code.json", "-m"},
                {"--inspect-code", "code.json", "--custom-logs", "points.json"},
                {"--custom-logs", "points.json", "-pa", "runtime.log"},
                {"--custom-logs", "points.json", "--capture", "-m"}}) {
            String[] args = new String[mode.length + 4];
            System.arraycopy(new String[]{"-a", "unused.apk", "-p", "unused"}, 0, args, 0, 4);
            System.arraycopy(mode, 0, args, 4, mode.length);
            assertEquals(2, invoke(args).status);
        }
    }

    @Test public void codeCatalogCannotOverwriteOriginalApk() throws Exception {
        Path apk = temporary.newFile("input.apk").toPath();
        Path platforms = temporary.newFolder("platforms").toPath();
        Result result = invoke("-a", apk.toString(), "-p", platforms.toString(), "--inspect-code", apk.toString());
        assertEquals(2, result.status); assertTrue(result.err.contains("separate from the APK"));
    }

    private static Result invoke(String... args) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = Main.run(args, new PrintStream(stdout, true, "UTF-8"), new PrintStream(stderr, true, "UTF-8"));
        return new Result(status, stdout.toString("UTF-8"), stderr.toString("UTF-8"));
    }

    private static class Result {
        final int status;
        final String out;
        final String err;
        Result(int status, String out, String err) { this.status = status; this.out = out; this.err = err; }
    }
}
