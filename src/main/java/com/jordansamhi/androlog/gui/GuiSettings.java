package com.jordansamhi.androlog.gui;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Per-user desktop settings. SDK paths never need to be written into the checkout or JAR. */
final class GuiSettings {
    Path platforms;
    Path buildTools;
    boolean animations = true;

    static Path settingsFile() {
        return Paths.get(System.getProperty("user.home"), ".config", "androlog", "gui.properties");
    }

    static GuiSettings load() {
        GuiSettings settings = new GuiSettings();
        Properties properties = new Properties();
        if (Files.isRegularFile(settingsFile())) {
            try (InputStream input = Files.newInputStream(settingsFile())) { properties.load(input); }
            catch (IOException ignored) { /* A moved SDK can be selected again in Settings. */ }
        }
        settings.platforms = path(properties.getProperty("platforms"));
        settings.buildTools = path(properties.getProperty("buildTools"));
        settings.animations = Boolean.parseBoolean(properties.getProperty("animations", "true"));
        for (Path root : sdkCandidates()) {
            if (settings.platforms == null && validPlatforms(root.resolve("platforms"))) settings.platforms = root.resolve("platforms");
            if (settings.buildTools == null) settings.buildTools = discoverBuildTools(root);
        }
        return settings;
    }

    void save() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("platforms", platforms == null ? "" : platforms.toString());
        properties.setProperty("buildTools", buildTools == null ? "" : buildTools.toString());
        properties.setProperty("animations", Boolean.toString(animations));
        Files.createDirectories(settingsFile().getParent());
        try (OutputStream output = Files.newOutputStream(settingsFile())) {
            properties.store(output, "AndroLog GUI - local SDK preferences");
        }
    }

    void validate() throws IOException {
        if (!validPlatforms(platforms)) throw new IOException("Choose an Android SDK platforms folder containing an installed android.jar in Settings.");
        if (!validBuildTools(buildTools)) throw new IOException("Choose an Android SDK Build Tools folder containing zipalign and apksigner in Settings.");
    }

    boolean ready() {
        try { validate(); return true; } catch (IOException error) { return false; }
    }

    Properties signingConfiguration() {
        Properties properties = new Properties();
        properties.setProperty("zipalignPath", buildTools.resolve(windows() ? "zipalign.exe" : "zipalign").toString());
        properties.setProperty("apksignerPath", buildTools.resolve(windows() ? "apksigner.bat" : "apksigner").toString());
        return properties;
    }

    static boolean validPlatforms(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) return false;
        try (Stream<Path> children = Files.list(directory)) {
            return children.anyMatch(child -> Files.isReadable(child.resolve("android.jar")) && Files.isRegularFile(child.resolve("android.jar")));
        } catch (IOException error) { return false; }
    }

    static boolean validBuildTools(Path directory) {
        if (directory == null) return false;
        return executable(directory.resolve(windows() ? "zipalign.exe" : "zipalign"))
                && executable(directory.resolve(windows() ? "apksigner.bat" : "apksigner"));
    }

    static Path discoverBuildTools(Path sdk) {
        Path directory = sdk.resolve("build-tools");
        if (!Files.isDirectory(directory)) return null;
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(GuiSettings::validBuildTools)
                    .max((a, b) -> compareVersions(a.getFileName().toString(), b.getFileName().toString())).orElse(null);
        } catch (IOException error) { return null; }
    }

    static int compareVersions(String first, String second) {
        String[] a = first.split("[. -]"), b = second.split("[. -]");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int comparison;
            try { comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i])); }
            catch (NumberFormatException ignored) { comparison = a[i].compareTo(b[i]); }
            if (comparison != 0) return comparison;
        }
        // Prefer a stable release over its preview (36.0.0 versus 36.0.0-rc1).
        return Integer.compare(b.length, a.length);
    }

    private static List<Path> sdkCandidates() {
        List<Path> roots = new ArrayList<>();
        for (String variable : Arrays.asList("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            Path root = path(System.getenv(variable));
            if (root != null) roots.add(root);
        }
        Path home = Paths.get(System.getProperty("user.home"));
        roots.add(home.resolve("Library/Android/sdk"));
        roots.add(home.resolve("Android/Sdk"));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) roots.add(Paths.get(local, "Android", "Sdk"));
        return roots;
    }

    static Path path(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try { return Paths.get(text.trim()).toAbsolutePath().normalize(); }
        catch (InvalidPathException error) { return null; }
    }

    private static boolean executable(Path path) { return Files.isRegularFile(path) && Files.isExecutable(path); }
    static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
}
