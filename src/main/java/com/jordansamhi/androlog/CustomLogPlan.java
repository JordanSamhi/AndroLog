package com.jordansamhi.androlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Literal log messages bound to exact original statements of one APK. */
public final class CustomLogPlan {
    public static final int MAX_MESSAGE_BYTES = 1000;
    public String apkSha256;
    public List<Point> points = new ArrayList<>();

    public CustomLogPlan() { }
    public CustomLogPlan(String apkSha256, List<Point> points) {
        this.apkSha256 = apkSha256; this.points = new ArrayList<>(points);
    }

    public static final class Point {
        public String method;
        public int index;
        public String statement;
        public String message;
        public String id; // Optional unique marker for GUI execution feedback.
        public Point() { }
        public Point(String method, int index, String statement, String message) {
            this.method = method; this.index = index; this.statement = statement; this.message = message;
        }
    }

    public void validate() throws IOException {
        if (apkSha256 == null || !apkSha256.matches("[a-f0-9]{64}")) throw new IOException("Custom logs need a valid APK fingerprint. Browse the APK again.");
        if (points == null || points.isEmpty()) throw new IOException("Select at least one custom log point.");
        Set<String> identities = new HashSet<>();
        for (Point point : points) {
            if (point == null || point.method == null || point.method.trim().isEmpty() || point.index < 1
                    || point.statement == null || point.statement.trim().isEmpty()) {
                throw new IOException("A custom log point is incomplete. Browse the APK and select its statement again.");
            }
            validateMessage(point.message);
            if (point.id != null && !point.id.matches("[a-f0-9]{32}")) throw new IOException("Invalid custom log point identifier.");
            if (!identities.add(point.method + "\n" + point.index)) throw new IOException("Only one custom message may be attached to each statement. Edit the existing message instead.");
        }
    }

    public static void validateMessage(String message) throws IOException {
        if (message == null || message.trim().isEmpty()) throw new IOException("Enter a message for the custom log.");
        if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0 || message.indexOf('\0') >= 0) {
            throw new IOException("Custom log messages must fit on one line and cannot contain NUL characters.");
        }
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new IOException("Keep custom log messages within " + MAX_MESSAGE_BYTES + " UTF-8 bytes.");
        }
    }

    public void verifyApk(Path apk) throws IOException {
        validate();
        if (!apkSha256.equals(sha256(apk))) throw new IOException("The APK has changed since these custom points were selected. Browse the APK again.");
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            StringBuilder text = new StringBuilder(64);
            for (byte value : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static CustomLogPlan read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            CustomLogPlan plan = new Gson().fromJson(reader, CustomLogPlan.class);
            if (plan == null) throw new IOException("The custom log plan is empty.");
            plan.validate(); return plan;
        } catch (JsonParseException error) { throw new IOException("Cannot read the custom log plan: " + error.getMessage(), error); }
    }

    public void write(Path path) throws IOException {
        validate();
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) { new GsonBuilder().setPrettyPrinting().create().toJson(this, writer); }
    }
}
