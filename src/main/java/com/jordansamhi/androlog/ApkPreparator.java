package com.jordansamhi.androlog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Aligns and signs an exported APK, replacing it only after both tools succeed. */
public class ApkPreparator {
    private static final int MAX_DIAGNOSTIC_BYTES = 16 * 1024;
    private final String apksignerPath;
    private final String zipalignPath;
    private final Path apkPath;

    public ApkPreparator(String apkPath) {
        this(apkPath, loadConfiguration());
    }

    ApkPreparator(String apkPath, Properties properties) {
        this.apkPath = Paths.get(apkPath).toAbsolutePath().normalize();
        this.apksignerPath = executable(properties, "apksignerPath");
        this.zipalignPath = executable(properties, "zipalignPath");
    }

    public void prepareApk() {
        Path temporary = null;
        try {
            requireOutput(apkPath, "Exported APK");
            temporary = Files.createTempDirectory(apkPath.getParent(), ".androlog-prepare-");
            Path aligned = temporary.resolve("aligned.apk");
            Path signed = temporary.resolve("signed.apk");
            Path keystore = temporary.resolve("keystore.keystore");
            try (InputStream input = ApkPreparator.class.getClassLoader().getResourceAsStream("keystore.keystore")) {
                if (input == null) throw new IOException("Bundled signing keystore was not found.");
                Files.copy(input, keystore);
            }
            execute("zipalign", Arrays.asList(zipalignPath, "-v", "4", apkPath.toString(), aligned.toString()));
            requireOutput(aligned, "zipalign output");
            execute("apksigner", Arrays.asList(apksignerPath, "sign", "--ks", keystore.toString(),
                    "--ks-pass", "pass:android", "--in", aligned.toString(), "--out", signed.toString(),
                    "--ks-key-alias", "android"));
            requireOutput(signed, "apksigner output");
            try {
                Files.move(signed, apkPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(signed, apkPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot prepare APK " + apkPath + ": " + error.getMessage(), error);
        } finally {
            cleanTemporaryDirectory(temporary);
        }
    }

    private static Properties loadConfiguration() {
        Properties properties = new Properties();
        try (InputStream input = ApkPreparator.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) throw new IOException("config.properties was not found on the classpath.");
            properties.load(input);
            return properties;
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot load signing configuration: " + error.getMessage(), error);
        }
    }

    private static String executable(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Signing tool not configured: set " + key
                    + " in config.properties to the Android SDK build-tools executable, then rebuild or use a local classpath override.");
        }
        Path path = Paths.get(value);
        if (path.isAbsolute() || value.contains("/") || value.contains("\\") || Files.exists(path)) {
            if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toAbsolutePath().normalize().toString();
        } else {
            String searchPath = System.getenv("PATH");
            if (searchPath != null) {
                for (String directory : searchPath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
                    Path candidate = Paths.get(directory.isEmpty() ? "." : directory).resolve(value);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return candidate.toAbsolutePath().normalize().toString();
                    }
                }
            }
        }
        throw new IllegalStateException("Signing tool " + key + " is not an executable file: " + value
                + ". Check the configured path and executable permission.");
    }

    private static void requireOutput(Path output, String label) throws IOException {
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException(label + " is missing or empty: " + output);
        }
    }

    private static void execute(String name, List<String> arguments) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        } catch (IOException error) {
            throw new IOException("Cannot start " + name + " at " + arguments.get(0) + ": " + error.getMessage(), error);
        }
        ByteArrayOutputStream diagnostic = new ByteArrayOutputStream();
        boolean truncated = false;
        try {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int excess = diagnostic.size() + count - MAX_DIAGNOSTIC_BYTES;
                    if (excess > 0) {
                        byte[] previous = diagnostic.toByteArray();
                        diagnostic.reset();
                        diagnostic.write(previous, excess, previous.length - excess);
                        truncated = true;
                    }
                    diagnostic.write(buffer, 0, count);
                }
            }
            int status = process.waitFor();
            if (status != 0) {
                String detail = new String(diagnostic.toByteArray(), StandardCharsets.UTF_8).trim();
                throw new IOException(name + " exited with status " + status
                        + (truncated ? ":\n[earlier tool output truncated]\n" : (detail.isEmpty() ? "" : ":\n"))
                        + detail);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + name + ".", error);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void cleanTemporaryDirectory(Path directory) {
        if (directory == null) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException error) {
            System.err.println("Warning: could not remove temporary signing files at " + directory + ": " + error.getMessage());
        }
    }
}
