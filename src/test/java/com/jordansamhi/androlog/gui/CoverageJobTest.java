package com.jordansamhi.androlog.gui;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class CoverageJobTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void noBaselineIsDisplayedAsUnavailable() {
        assertEquals("—", new CoverageJob.Metric(0, 0).display());
        assertEquals("25.0%", new CoverageJob.Metric(1, 4).display());
    }
    @Test public void changedLogIsRejectedBeforeRunningAnalysis() throws Exception {
        Path apk = temporary.newFile("app.apk").toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) {
            for (String name : new String[]{"AndroidManifest.xml", "classes.dex"}) { zip.putNextEntry(new ZipEntry(name)); zip.write(1); zip.closeEntry(); }
        }
        Path sdk = temporary.newFolder("platforms").toPath(); Path platform = Files.createDirectory(sdk.resolve("android-1")); Files.createFile(platform.resolve("android.jar"));
        GuiSettings settings = new GuiSettings(); settings.platforms = sdk;
        Path log = temporary.newFile("runtime.log").toPath(); Files.write(log, "ANDROLOG: CLASS=A\n".getBytes(StandardCharsets.UTF_8));
        RuntimeLog data = RuntimeLog.read(log, "ANDROLOG");
        Files.write(log, "ANDROLOG: CLASS=B\n".getBytes(StandardCharsets.UTF_8));
        try (CoverageJob job = new CoverageJob(apk, log, settings, "ANDROLOG", new boolean[]{true,false,false,false}, "", false, data.sourceHash)) {
            try { job.run(); fail("changed log accepted"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("changed")); }
        }
    }
}
