package com.jordansamhi.androlog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.Assert.*;

public class ApkPreparatorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void handlesSpacesAndOnlyReplacesAfterBothToolsSucceed() throws Exception {
        Path directory = temporary.newFolder("APK directory with spaces").toPath();
        Path apk = directory.resolve("my app.apk");
        Files.write(apk, "original".getBytes(StandardCharsets.UTF_8));
        Path align = script("fake zipalign", "cp \"$3\" \"$4\"\nprintf '|aligned' >> \"$4\"\n");
        Path sign = script("fake apksigner", signerArguments()
                + "cp \"$input\" \"$output\"\nprintf '|signed' >> \"$output\"\ntouch \"$output.idsig\"\n");

        new ApkPreparator(apk.toString(), configuration(align, sign)).prepareApk();

        assertEquals("original|aligned|signed", read(apk));
        assertNoTemporaryFiles(directory);
    }

    @Test public void signingFailureRetainsOriginalAndReportsToolDiagnostics() throws Exception {
        Path directory = temporary.newFolder("failed signing").toPath();
        Path apk = directory.resolve("app.apk");
        Files.write(apk, "original".getBytes(StandardCharsets.UTF_8));
        Path align = script("align succeeds", "cp \"$3\" \"$4\"\nprintf '|aligned' >> \"$4\"\n");
        Path sign = script("sign fails", signerArguments()
                + "printf 'partial' > \"$output\"\nprintf 'invalid signing key' >&2\nexit 7\n");

        UncheckedIOException failure = failure(new ApkPreparator(apk.toString(), configuration(align, sign)));

        assertTrue(failure.getMessage().contains("apksigner exited with status 7"));
        assertTrue(failure.getMessage().contains("invalid signing key"));
        assertEquals("original", read(apk));
        assertNoTemporaryFiles(directory);
    }

    @Test public void alignmentFailureStopsBeforeSigningAndRetainsOriginal() throws Exception {
        Path directory = temporary.newFolder("failed alignment").toPath();
        Path apk = directory.resolve("app.apk");
        Files.write(apk, "original".getBytes(StandardCharsets.UTF_8));
        Path align = script("align fails", "printf 'invalid zip' >&2\nexit 9\n");
        Path sign = script("sign must not run", "touch \"$(dirname \"$0\")/sign-ran\"\nexit 99\n");

        UncheckedIOException failure = failure(new ApkPreparator(apk.toString(), configuration(align, sign)));

        assertTrue(failure.getMessage().contains("zipalign exited with status 9"));
        assertFalse(Files.exists(sign.getParent().resolve("sign-ran")));
        assertEquals("original", read(apk));
        assertNoTemporaryFiles(directory);
    }

    @Test public void successfulToolExitWithoutAnOutputIsStillAFailure() throws Exception {
        Path directory = temporary.newFolder("missing signed output").toPath();
        Path apk = directory.resolve("app.apk");
        Files.write(apk, "original".getBytes(StandardCharsets.UTF_8));
        Path align = script("align writes", "cp \"$3\" \"$4\"\n");
        Path sign = script("sign writes nothing", "exit 0\n");

        UncheckedIOException failure = failure(new ApkPreparator(apk.toString(), configuration(align, sign)));

        assertTrue(failure.getMessage().contains("apksigner output is missing or empty"));
        assertEquals("original", read(apk));
        assertNoTemporaryFiles(directory);
    }

    @Test(timeout = 10000) public void drainsLargeToolOutputWithoutBlockingAndBoundsDiagnostics() throws Exception {
        Path apk = temporary.newFile("large-output.apk").toPath();
        Files.write(apk, "original".getBytes(StandardCharsets.UTF_8));
        Path align = script("noisy align", "i=0\nwhile [ \"$i\" -lt 5000 ]; do\n"
                + "  printf 'diagnostic output that exceeds the pipe buffer\\n' >&2\n  i=$((i + 1))\ndone\n"
                + "printf 'FINAL: APK central directory is invalid' >&2\nexit 5\n");
        Path sign = script("unused sign", "exit 0\n");

        UncheckedIOException failure = failure(new ApkPreparator(apk.toString(), configuration(align, sign)));

        assertTrue(failure.getMessage().contains("zipalign exited with status 5"));
        assertTrue(failure.getMessage().contains("tool output truncated"));
        assertTrue(failure.getMessage().contains("FINAL: APK central directory is invalid"));
        assertTrue(failure.getMessage().length() < 18000);
        assertEquals("original", read(apk));
    }

    @Test public void missingConfigurationExplainsWhichLocalSettingIsRequired() {
        try {
            new ApkPreparator("unused.apk", new Properties());
            fail("Missing tools must fail before APK work starts");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("apksignerPath"));
            assertTrue(expected.getMessage().contains("config.properties"));
        }
    }

    private Path script(String name, String body) throws IOException {
        Path path = temporary.newFile(name).toPath();
        Files.write(path, ("#!/bin/sh\nset -eu\n" + body).getBytes(StandardCharsets.UTF_8));
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }

    private static String signerArguments() {
        return "input=''\noutput=''\nwhile [ \"$#\" -gt 0 ]; do\n"
                + "  case \"$1\" in\n    --in) input=\"$2\"; shift 2;;\n"
                + "    --out) output=\"$2\"; shift 2;;\n    *) shift;;\n  esac\ndone\n";
    }

    private static Properties configuration(Path align, Path sign) {
        Properties properties = new Properties();
        properties.setProperty("zipalignPath", align.toString());
        properties.setProperty("apksignerPath", sign.toString());
        return properties;
    }

    private static UncheckedIOException failure(ApkPreparator preparator) {
        try {
            preparator.prepareApk();
            fail("Preparation should fail");
            return null;
        } catch (UncheckedIOException expected) {
            return expected;
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void assertNoTemporaryFiles(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith(".androlog-prepare-")));
        }
    }
}
