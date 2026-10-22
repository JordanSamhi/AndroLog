package com.jordansamhi.androlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Writes a standalone, offline coverage explorer with no external assets. */
public class HtmlReportWriter {
    private static final String PLACEHOLDER = "__ANDROLOG_DATA__";
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("statements", "Statements");
        LABELS.put("methods", "Methods");
        LABELS.put("classes", "Classes");
        LABELS.put("activities", "Activities");
        LABELS.put("services", "Services");
        LABELS.put("broadcast-receivers", "Broadcast receivers");
        LABELS.put("content-providers", "Content providers");
    }

    public void write(SummaryBuilder baseline, SummaryLogBuilder observations, String apkName,
                      String logIdentifier, String outputPath, boolean perMinute) throws IOException {
        Map<String, Object> data = buildData(baseline, observations, apkName, logIdentifier, perMinute);
        // Keep Gson's HTML escaping: APK strings must not close the raw-text script element.
        Gson gson = new GsonBuilder().serializeNulls().create();
        String template = readResource("/report.html");
        String linksPlaceholder = "__ANDROLOG_LINKS__";
        if (template.indexOf(linksPlaceholder) < 0
                || template.indexOf(linksPlaceholder) != template.lastIndexOf(linksPlaceholder)) {
            throw new IOException("HTML report template must contain exactly one code links placeholder");
        }
        // Insert trusted assets before user data, so marker-like APK strings stay untouched.
        template = template.replace(linksPlaceholder, readResource("/report-links.js"));
        String graphPlaceholder = "__ANDROLOG_GRAPH__";
        if (template.indexOf(graphPlaceholder) < 0
                || template.indexOf(graphPlaceholder) != template.lastIndexOf(graphPlaceholder)) {
            throw new IOException("HTML report template must contain exactly one graph placeholder");
        }
        template = template.replace(graphPlaceholder, readResource("/report-graph.js"));
        if (template.indexOf(PLACEHOLDER) < 0
                || template.indexOf(PLACEHOLDER) != template.lastIndexOf(PLACEHOLDER)) {
            throw new IOException("HTML report template must contain exactly one data placeholder");
        }
        String html = template.replace(PLACEHOLDER, gson.toJson(data));
        Path output = Paths.get(outputPath).toAbsolutePath();
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.write(output, html.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> buildData(SummaryBuilder baseline, SummaryLogBuilder observations,
                                         String apkName, String tag, boolean perMinute) {
        Map<String, Integer> totals = baseline.getSummary();
        Set<String> known = baseline.getVisitedComponents();
        Set<String> seen = observations.getVisitedComponents();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appName", apkName);
        data.put("logIdentifier", tag);
        data.put("generatedAt", Instant.now().toString());
        String targetPackage = baseline.getTargetPackage();
        data.put("scope", targetPackage == null ? "Selected APK packages" : "Package prefix: " + targetPackage);

        List<String> warnings = new ArrayList<>();
        warnings.add("Uncovered means no matching probe was observed in the supplied log; it does not prove the code never executes.");
        if (!totals.containsKey("statements")) {
            warnings.add("Statement coverage was not selected. Use -s during both instrumentation and reporting to highlight individual Jimple statements.");
        } else {
            warnings.add("Newly instrumented APKs record arrival before each eligible statement, including branches and throwing operations; this does not imply successful completion. Identity, return and monitor instructions are excluded. Older APKs used after-statement probes; reinstrument and collect fresh logs for the improved coverage.");
        }
        if (perMinute && observations.getUnattributedCount() > 0) {
            warnings.add(observations.getUnattributedCount()
                    + " distinct observations had no usable timestamp. They count in overall coverage but are absent from the timeline.");
        }
        data.put("warnings", warnings);

        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map.Entry<String, String> category : LABELS.entrySet()) {
            if (totals.containsKey(category.getKey())) {
                Map<String, Object> metric = new LinkedHashMap<>();
                metric.put("key", category.getKey());
                metric.put("label", category.getValue());
                metric.put("total", totals.get(category.getKey()));
                metric.put("covered", observations.getSummary().getOrDefault(category.getKey(), 0));
                categories.add(metric);
            }
        }
        data.put("categories", categories);

        Map<String, Map<String, Object>> classes = new TreeMap<>();
        Map<String, List<Map<String, Object>>> methodsByClass = new TreeMap<>();
        for (CoverageMethod method : baseline.getCodeMethods()) {
            String className = method.getClassName();
            if (!classes.containsKey(className)) {
                Map<String, Object> clazz = new LinkedHashMap<>();
                clazz.put("name", className);
                int lastDot = className.lastIndexOf('.');
                clazz.put("packageName", lastDot < 0 ? "(default package)" : className.substring(0, lastDot));
                clazz.put("classCovered", measured("classes", className, known, seen));
                clazz.put("componentType", method.getComponentType());
                String category = componentCategory(method.getComponentType());
                clazz.put("componentCovered", category == null ? null : measured(category, className, known, seen));
                List<Map<String, Object>> methods = new ArrayList<>();
                clazz.put("methods", methods);
                methodsByClass.put(className, methods);
                classes.put(className, clazz);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("signature", method.getSignature());
            item.put("covered", measured("methods", method.getSignature(), known, seen));
            List<Map<String, Object>> statements = new ArrayList<>();
            for (CoverageMethod.Statement statement : method.getStatements()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("line", statement.getLine());
                row.put("text", statement.getText());
                String payload = method.getSignature() + "|" + statement.getText() + "|" + statement.getLine();
                String status;
                if (!statement.isEligible()) {
                    status = "excluded";
                } else if (!known.contains("statementsSTATEMENT=" + payload)) {
                    status = "unmeasured";
                } else {
                    status = seen.contains("statements" + payload) ? "covered" : "uncovered";
                }
                row.put("status", status);
                statements.add(row);
            }
            item.put("statements", statements);
            methodsByClass.get(className).add(item);
        }
        for (List<Map<String, Object>> methods : methodsByClass.values()) {
            methods.sort((left, right) -> ((String) left.get("signature")).compareTo((String) right.get("signature")));
        }
        data.put("classes", new ArrayList<>(classes.values()));
        Set<String> codeSignatures = new HashSet<>();
        for (CoverageMethod method : baseline.getCodeMethods()) codeSignatures.add(method.getSignature());
        Map<String, Object> callGraph = observations.getCallGraph().toMap(codeSignatures);
        callGraph.put("launcherActivities", baseline.getLauncherActivities());
        data.put("callGraph", callGraph);

        List<Map<String, Object>> timeline = new ArrayList<>();
        if (perMinute) {
            Map<String, Integer> cumulative = new LinkedHashMap<>();
            for (String category : LABELS.keySet()) {
                if (totals.containsKey(category)) {
                    cumulative.put(category, 0);
                }
            }
            for (Map.Entry<String, Map<String, Integer>> minute : new TreeMap<>(observations.getPerMinuteSummaries()).entrySet()) {
                for (String category : cumulative.keySet()) {
                    cumulative.put(category, cumulative.get(category) + minute.getValue().getOrDefault(category, 0));
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("minute", minute.getKey());
                entry.put("counts", new LinkedHashMap<>(cumulative));
                timeline.add(entry);
            }
        }
        data.put("timeline", timeline);
        return data;
    }

    private Boolean measured(String type, String entity, Set<String> known, Set<String> seen) {
        return known.contains(type + entity) ? seen.contains(type + entity) : null;
    }

    private String componentCategory(String type) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case "Activity": return "activities";
            case "Service": return "services";
            case "BroadcastReceiver": return "broadcast-receivers";
            case "ContentProvider": return "content-providers";
            default: return null;
        }
    }

    private String readResource(String resource) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing HTML report resource: " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
