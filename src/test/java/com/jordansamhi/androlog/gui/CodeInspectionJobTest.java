package com.jordansamhi.androlog.gui;

import com.jordansamhi.androlog.CodeCatalog;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

import static org.junit.Assert.*;

public class CodeInspectionJobTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void inspectionUsesScopeAndPlatformsWithoutSigningToolsAndCleansItsFiles() throws Exception {
        Assume.assumeFalse(GuiSettings.windows());
        Path input = apk("app with spaces.apk");
        GuiSettings settings = settings();
        Path session;
        try (CodeInspectionJob job = fakeJob(input, settings,
                "printf '%s\\n' \"$@\"\n"
                + "while [ \"$#\" -gt 0 ]; do\ncase \"$1\" in --inspect-code) catalog=\"$2\"; shift 2;; *) shift;; esac\ndone\n"
                + "cat > \"$catalog\" <<'CATALOG'\n"
                + "{\"apkSha256\":\"" + String.join("", Collections.nCopies(64, "a")) + "\",\"methods\":[{\"className\":\"com.example.Main\",\"signature\":\"<com.example.Main: void run()>\",\"statements\":[{\"index\":1,\"text\":\"return\",\"selectable\":true}]}]}\n"
                + "CATALOG\n")) {
            session = job.directory;
            CodeCatalog result = job.run();
            assertEquals("com.example.Main", result.methods.get(0).className);
            assertEquals("return", result.methods.get(0).statements.get(0).text);
            List<String> args = Files.readAllLines(job.log, StandardCharsets.UTF_8);
            assertTrue(args.contains(input.toString())); assertTrue(args.contains("--inspect-code"));
            assertTrue(args.contains("-n")); assertTrue(args.contains("com.example"));
            assertTrue(args.contains(settings.platforms.toString()));
            assertFalse(Files.exists(job.directory.resolve("configuration")));
        }
        assertFalse(Files.exists(session)); assertTrue(Files.exists(input));
    }

    @Test public void inspectionFailuresExposeWorkerMessageAndCleanup() throws Exception {
        Assume.assumeFalse(GuiSettings.windows());
        Path session;
        try (CodeInspectionJob job = fakeJob(apk("bad.apk"), settings(), "printf 'Error: malformed DEX code\\n'\nexit 7\n")) {
            session = job.directory;
            try { job.run(); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("malformed DEX")); }
        }
        assertFalse(Files.exists(session));
    }

    @Test public void cancellationBeforeStartDoesNotLaunchInspection() throws Exception {
        Assume.assumeFalse(GuiSettings.windows());
        try (CodeInspectionJob job = fakeJob(apk("before.apk"), settings(), "exit 0\n")) {
            job.cancel();
            try { job.run(); fail(); }
            catch (CancellationException expected) { assertFalse(Files.exists(job.log)); }
        }
    }

    @Test(timeout = 10000) public void cancellationStopsInspectionAndAllowsCleanup() throws Exception {
        Assume.assumeFalse(GuiSettings.windows());
        Path session;
        try (CodeInspectionJob job = fakeJob(apk("cancel.apk"), settings(), "printf 'started\\n'\nwhile :; do sleep 0.1; done\n")) {
            session = job.directory;
            CompletableFuture<Boolean> finished = CompletableFuture.supplyAsync(() -> {
                try { job.run(); return false; }
                catch (CancellationException expected) { return true; }
                catch (Exception error) { throw new CompletionException(error); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((!Files.exists(job.log) || Files.size(job.log) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.size(job.log) > 0);
            job.cancel(); assertTrue(finished.get(5, TimeUnit.SECONDS));
        }
        assertFalse(Files.exists(session));
    }

    @Test public void missingPlatformsAreRejectedWithoutRequiringBuildTools() throws Exception {
        GuiSettings settings = new GuiSettings();
        try { new CodeInspectionJob(apk("missing.apk"), settings, false, ""); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("platforms")); }
    }

    private CodeInspectionJob fakeJob(Path apk, GuiSettings settings, String script) throws Exception {
        Path javaHome = temporary.newFolder().toPath(); Files.createDirectories(javaHome.resolve("bin"));
        Path java = javaHome.resolve("bin/java");
        Files.write(java, ("#!/bin/sh\nset -eu\n" + script).getBytes(StandardCharsets.UTF_8));
        java.toFile().setExecutable(true);
        String original = System.getProperty("java.home");
        try {
            System.setProperty("java.home", javaHome.toString());
            return new CodeInspectionJob(apk, settings, true, "com.example");
        } finally { System.setProperty("java.home", original); }
    }

    private GuiSettings settings() throws Exception {
        GuiSettings settings = new GuiSettings();
        settings.platforms = temporary.newFolder("platforms").toPath();
        Files.createDirectories(settings.platforms.resolve("android-36"));
        Files.createFile(settings.platforms.resolve("android-36/android.jar"));
        return settings;
    }

    private Path apk(String name) throws IOException {
        Path path = temporary.newFile(name).toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String entry : Arrays.asList("AndroidManifest.xml", "classes.dex")) {
                zip.putNextEntry(new ZipEntry(entry)); zip.write(new byte[]{1}); zip.closeEntry();
            }
        }
        return path;
    }
}
