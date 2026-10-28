package com.jordansamhi.androlog.gui;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class InstrumentationJobTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void progressSurvivesSplitPipeChunksAndIgnoresOrdinaryDiagnostics() {
        List<Integer> stages = new ArrayList<>();
        InstrumentationJob.StageReader reader = new InstrumentationJob.StageReader(stages::add);
        reader.accept("An APK named [4/4] is not a stage\n@@ANDRO");
        reader.accept("LOG_STAGE 1\r\n[1/4] Load APK ... ");
        reader.accept("completed\n@@ANDROLOG_STAGE 2\n@@ANDROLOG_STAGE 2\n@@ANDROLOG_STAGE 3\n");
        reader.accept("@@ANDROLOG_STAGE 4\n@@ANDROLOG_STAGE 1\n");
        assertEquals(Arrays.asList(1, 2, 3, 4), stages);
    }

    @Test public void rejectsResourceOnlySplitsAndInvalidArchives() throws Exception {
        Path split = apk("resources.apk", false);
        try { InstrumentationJob.validateApk(split); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("classes.dex")); }
        Path text = write("not an apk.apk", "hello");
        try { InstrumentationJob.validateApk(text); fail(); }
        catch (IOException expected) { /* Invalid ZIP. */ }
    }

    @Test public void saveProtectsOriginalEvenThroughAnAliasedParent() throws Exception {
        Path input = apk("original.apk", true), output = write("prepared.apk", "signed");
        Path folderAlias = temporary.getRoot().toPath().resolve("alias");
        try { Files.createSymbolicLink(folderAlias, input.getParent()); }
        catch (UnsupportedOperationException | IOException error) { Assume.assumeNoException(error); }
        byte[] before = Files.readAllBytes(input);
        try { InstrumentationJob.copySafely(output, folderAlias.resolve(input.getFileName()), input, true); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("original APK")); }
        assertArrayEquals(before, Files.readAllBytes(input));
    }

    @Test public void saveRequiresOverwriteConsentAndFailedCopyKeepsExistingFile() throws Exception {
        Path input = apk("original.apk", true), output = write("prepared.apk", "signed"), destination = write("already here.apk", "existing");
        try { InstrumentationJob.copySafely(output, destination, input, false); fail(); }
        catch (FileAlreadyExistsException expected) { /* Explicit replacement is required. */ }
        assertEquals("existing", read(destination));
        try { InstrumentationJob.copySafely(output.resolveSibling("missing.apk"), destination, input, true); fail(); }
        catch (IOException expected) { /* A failed staging copy must not truncate the destination. */ }
        assertEquals("existing", read(destination));
        InstrumentationJob.copySafely(output, destination, input, true);
        assertEquals("signed", read(destination));
    }

    @Test public void workerUsesSelectedFlagsLocalSigningConfigAndPathsWithSpaces() throws Exception {
        Assume.assumeFalse(GuiSettings.windows());
        GuiSettings settings = settings();
        Path input = apk("my app.apk", true);
        try (InstrumentationJob job = fakeJob(input, settings, new boolean[]{false, true, false, false, true},
                "printf '%s\\n' \"$@\"\n"
                + "while [ \"$#\" -gt 0 ]; do\ncase \"$1\" in -o) output=\"$2\"; shift 2;; -a) apk=\"$2\"; shift 2;; *) shift;; esac\ndone\n"
                + "printf '@@ANDROLOG_STAGE 1\\n@@ANDROLOG_STAGE 2\\n@@ANDROLOG_STAGE 3\\n@@ANDROLOG_STAGE 4\\n'\n"
                + "mkdir -p \"$output\"\ncp \"$apk\" \"$output/$(basename \"$apk\")\"\n")) {
            Properties configuration = new Properties();
            try (InputStream stream = Files.newInputStream(job.directory.resolve("configuration/config.properties"))) { configuration.load(stream); }
            assertEquals(settings.buildTools.resolve("apksigner").toString(), configuration.getProperty("apksignerPath"));
            List<Integer> stages = new ArrayList<>(); job.run(stages::add);
            assertEquals(Arrays.asList(1, 2, 3, 4), stages);
            List<String> log = Files.readAllLines(job.log, StandardCharsets.UTF_8);
            log = log.subList(log.indexOf("com.jordansamhi.androlog.Main") + 1, log.size());
            assertTrue(log.contains(input.toString())); assertTrue(log.contains("-m")); assertTrue(log.contains("-mc"));
            assertFalse(log.contains("-c")); assertFalse(log.contains("-s")); assertFalse(log.contains("-cp"));
            assertTrue(log.contains("-n")); assertTrue(log.contains("com.example")); assertTrue(log.contains("MY_TAG"));
            Path saved = input.resolveSibling("saved app.apk"); job.saveApk(saved, false);
            assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(saved));
        }
    }

    @Test public void workerFailureKeepsDiagnosticsAndNeverOffersPartialOutput() throws Exception {
        Assume.assumeFalse(GuiSettings.windows());
        Path input = apk("original.apk", true); Path session;
        try (InstrumentationJob job = fakeJob(input, settings(), new boolean[]{true, false, false, false, false},
                "printf 'Error during align and sign apk: signing failed\\n'\nexit 7\n")) {
            session = job.directory;
            try { job.run(stage -> {}); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("signing failed")); }
            assertTrue(job.readLog().contains("signing failed")); assertFalse(Files.exists(job.output));
        }
        assertFalse(Files.exists(session)); assertTrue(Files.exists(input));
    }

    @Test(timeout = 10000) public void cancellationStopsActiveWorkerAndASecondRunHasIndependentState() throws Exception {
        Assume.assumeFalse(GuiSettings.windows());
        Path input = apk("original.apk", true);
        try (InstrumentationJob job = fakeJob(input, settings(), new boolean[]{true, false, false, false, false},
                "printf '@@ANDROLOG_STAGE 1\\n'\nwhile :; do sleep 0.1; done\n")) {
            CountDownLatch started = new CountDownLatch(1);
            CompletableFuture<Boolean> cancelled = CompletableFuture.supplyAsync(() -> {
                try { job.run(stage -> started.countDown()); return false; }
                catch (CancellationException expected) { return true; }
                catch (Exception error) { throw new CompletionException(error); }
            });
            assertTrue(started.await(3, TimeUnit.SECONDS)); job.cancel();
            assertTrue(cancelled.get(5, TimeUnit.SECONDS)); assertFalse(Files.exists(job.output));
        }
        try (InstrumentationJob next = fakeJob(input, settings(), new boolean[]{true, false, false, false, false}, "exit 8\n")) {
            try { next.run(stage -> {}); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("exit 8")); }
        }
    }

    @Test public void cancellationBeforeStartDoesNotLaunchAWorker() throws Exception {
        Assume.assumeFalse(GuiSettings.windows());
        try (InstrumentationJob job = fakeJob(apk("original.apk", true), settings(), new boolean[]{true, false, false, false, false}, "exit 0\n")) {
            job.cancel();
            try { job.run(stage -> fail()); fail(); } catch (CancellationException expected) { assertFalse(Files.exists(job.log)); }
        }
    }

    @Test public void emptySelectionAndInvalidTagsFailBeforeCreatingAWorker() throws Exception {
        Path input = apk("original.apk", true); GuiSettings settings = settings();
        try { new InstrumentationJob(input, settings, new boolean[5], false, "", "ANDROLOG"); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("at least one")); }
        try { new InstrumentationJob(input, settings, new boolean[]{true, false, false, false, false}, false, "", "bad tag"); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("log tag")); }
    }

    @Test public void discoversLatestUsableBuildToolsNumericallyAndSkipsIncompleteVersions() throws Exception {
        Path sdk = temporary.newFolder("sdk").toPath();
        tools(sdk.resolve("build-tools/9.0.0")); tools(sdk.resolve("build-tools/10.0.0")); tools(sdk.resolve("build-tools/10.0.0-rc1"));
        Files.createDirectories(sdk.resolve("build-tools/11.0.0"));
        assertEquals("10.0.0", GuiSettings.discoverBuildTools(sdk).getFileName().toString());
    }

    private InstrumentationJob fakeJob(Path apk, GuiSettings settings, boolean[] flags, String script) throws Exception {
        Path javaHome = temporary.newFolder().toPath(); Files.createDirectories(javaHome.resolve("bin"));
        executable(javaHome.resolve("bin/java"), script);
        String original = System.getProperty("java.home");
        try {
            System.setProperty("java.home", javaHome.toString());
            return new InstrumentationJob(apk, settings, flags, true, "com.example", "MY_TAG");
        } finally { System.setProperty("java.home", original); }
    }

    private GuiSettings settings() throws Exception {
        Path sdk = temporary.newFolder().toPath(); GuiSettings settings = new GuiSettings();
        settings.platforms = Files.createDirectories(sdk.resolve("platforms"));
        Files.createDirectories(settings.platforms.resolve("android-35")); Files.createFile(settings.platforms.resolve("android-35/android.jar"));
        settings.buildTools = sdk.resolve("build-tools/36.1.0"); tools(settings.buildTools); return settings;
    }

    private void tools(Path folder) throws Exception {
        Files.createDirectories(folder);
        executable(folder.resolve(GuiSettings.windows() ? "apksigner.bat" : "apksigner"), "exit 0\n");
        executable(folder.resolve(GuiSettings.windows() ? "zipalign.exe" : "zipalign"), "exit 0\n");
    }
    private void executable(Path path, String script) throws Exception {
        Files.write(path, ("#!/bin/sh\nset -eu\n" + script).getBytes(StandardCharsets.UTF_8)); path.toFile().setExecutable(true);
    }
    private Path apk(String name, boolean dex) throws IOException {
        Path path = temporary.newFile(name).toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml")); zip.write(new byte[]{1}); zip.closeEntry();
            if (dex) { zip.putNextEntry(new ZipEntry("classes.dex")); zip.write(new byte[]{2}); zip.closeEntry(); }
        }
        return path;
    }
    private Path write(String name, String value) throws IOException { Path path = temporary.newFile(name).toPath(); return Files.write(path, value.getBytes(StandardCharsets.UTF_8)); }
    private String read(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
}
