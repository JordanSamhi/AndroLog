package com.jordansamhi.androlog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Joins custom probes extracted from the APK to runtime events, with no local plan files. */
public final class CustomPointReport {
    private CustomPointReport() { }

    public static Map<String, Object> build(SummaryBuilder apk, SummaryLogBuilder log) {
        List<Map<String, Object>> expected = apk.getCustomPoints();
        Map<String, Integer> occurrences = new HashMap<>();
        for (Map<String, Object> point : expected) {
            if ("message".equals(kindOf(point))) occurrences.merge((String) point.get("message"), 1, Integer::sum);
        }
        List<Map<String, Object>> points = new ArrayList<>();
        Set<String> knownMarkers = new HashSet<>();
        int observed = 0, unknown = 0;
        for (Map<String, Object> source : expected) {
            Map<String, Object> point = new LinkedHashMap<>(source);
            String id = (String) point.get("id"), message = (String) point.get("message");
            String kind = kindOf(point);
            boolean valuePoint = "value".equals(kind), reachedPoint = "reached".equals(kind);
            point.put("kind", kind);
            if (reachedPoint) point.remove("message");
            // Samples come from this run, never from cached/source metadata or a literal marker.
            point.remove("samples"); point.remove("latestValue");
            if (valuePoint) point.put("dynamic", true); else point.remove("dynamic");
            Integer count;
            if (id != null) {
                knownMarkers.add(id);
                count = log.getCustomMarkers().getOrDefault(id, 0);
                // A value event already proves arrival, even if capture ends before its companion marker.
                // Paired events describe the same visit and must not be added together.
                if (valuePoint) count = Math.max(count, log.getCustomValueCount(id));
            } else if ("message".equals(kind)) {
                // Old probes recorded only their message. A shared message cannot identify a location.
                int hits = log.getCustomMessages().getOrDefault(message, 0);
                count = hits > 0 && occurrences.getOrDefault(message, 0) > 1 ? null : Integer.valueOf(hits);
                point.put("id", legacyId(point));
            } else {
                // Reached/value probes require an ID; message text cannot establish their identity.
                count = null; point.put("id", legacyId(point));
            }
            String status = count == null ? "unknown" : count > 0 ? "covered" : "uncovered";
            if ("covered".equals(status)) observed++;
            if ("unknown".equals(status)) unknown++;
            point.put("count", count); point.put("status", status); points.add(point);
            if (valuePoint) {
                List<String> samples = id == null ? Collections.emptyList() : log.getCustomValues(id);
                point.put("samples", samples);
                if (!samples.isEmpty()) point.put("latestValue", samples.get(samples.size() - 1));
            }
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Map.Entry<String, Integer> message : log.getCustomMessages().entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("message", message.getKey()); row.put("count", message.getValue());
            messages.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("points", points); summary.put("observed", observed); summary.put("unknown", unknown);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", log.getCustomMessageTotal()); result.put("omitted", log.getOmittedCustomMessages()); result.put("messages", messages);
        result.put("markers", log.getCustomMarkers()); result.put("summary", summary);
        long unmatched = log.getCustomMarkers().keySet().stream().filter(id -> !knownMarkers.contains(id)).count();
        result.put("unmatchedMarkers", unmatched);
        if (unmatched > 0) result.put("notice", "Some custom-point markers do not belong to this APK and were ignored. Check that the APK and logs come from the same instrumentation.");
        return result;
    }

    private static String kindOf(Map<String, Object> point) {
        Object kind = point.get("kind");
        if ("reached".equals(kind)) return "reached";
        if ("message".equals(kind) || "marker".equals(kind)) return "message";
        return "value".equals(kind) || Boolean.TRUE.equals(point.get("dynamic")) ? "value" : "message";
    }

    private static String legacyId(Map<String, Object> point) {
        try {
            String value = point.get("method") + "\n" + point.get("index") + "\n" + point.get("message");
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder("legacy-");
            for (int i = 0; i < 16; i++) id.append(String.format(Locale.ROOT, "%02x", bytes[i] & 255));
            return id.toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
