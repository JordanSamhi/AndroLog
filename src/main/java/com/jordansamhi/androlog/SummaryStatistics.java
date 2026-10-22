package com.jordansamhi.androlog;

import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Presents observed coverage and writes the existing JSON report formats. */
public class SummaryStatistics {
    private static final Map<String, String> CATEGORY_LABELS = new LinkedHashMap<>();

    static {
        CATEGORY_LABELS.put("statements", "Statements");
        CATEGORY_LABELS.put("methods", "Methods");
        CATEGORY_LABELS.put("classes", "Classes");
        CATEGORY_LABELS.put("activities", "Activities");
        CATEGORY_LABELS.put("services", "Services");
        CATEGORY_LABELS.put("broadcast-receivers", "Broadcast receivers");
        CATEGORY_LABELS.put("content-providers", "Content providers");
    }

    /** Prints one aggregate table, including selected categories with no probes. */
    public void compareSummaries(SummaryBuilder baseline, SummaryLogBuilder observations) {
        Map<String, Integer> totals = baseline.getSummary();
        List<String> categories = orderedCategories(totals);
        if (categories.isEmpty() && (baseline.isCallGraphRequested() || observations.getCallGraph().hasData())) {
            printCallGraph(baseline, observations);
            return;
        }
        System.out.println();
        System.out.println("Coverage summary");
        if (categories.isEmpty()) {
            System.out.println("No coverage categories were selected.");
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String category : categories) {
            int total = totals.get(category);
            int covered = observations.getSummary().getOrDefault(category, 0);
            rows.add(Arrays.asList(label(category), count(covered), count(total), terminalPercent(covered, total)));
        }
        printTable(Arrays.asList("Category", "Covered", "Total", "Coverage"), rows);
        printCallGraph(baseline, observations);
    }

    /** Writes aggregate JSON without printing a second terminal summary. */
    public void compareSummariesToJson(SummaryBuilder baseline, SummaryLogBuilder observations, String filePath) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("coverageSummary", aggregateJson(baseline.getSummary(), observations.getSummary()));
        addCallGraph(report, baseline, observations);
        writeJson(report, filePath);
    }

    /** Prints aggregate coverage once, followed by cumulative timestamped counts. */
    public void compareSummariesPerMinute(SummaryBuilder baseline, SummaryLogBuilder observations) {
        compareSummaries(baseline, observations);
        Map<String, Integer> totals = baseline.getSummary();
        List<String> categories = orderedCategories(totals);
        System.out.println();
        System.out.println("Cumulative coverage by minute");
        if (observations.getPerMinuteSummaries().isEmpty()) {
            System.out.println("No timestamped observations are available.");
        } else {
            List<String> headers = new ArrayList<>();
            headers.add("Log minute");
            for (String category : categories) {
                headers.add(label(category));
            }
            Map<String, Integer> cumulative = new LinkedHashMap<>();
            List<List<String>> rows = new ArrayList<>();
            for (Map.Entry<String, Map<String, Integer>> minute : new TreeMap<>(observations.getPerMinuteSummaries()).entrySet()) {
                List<String> row = new ArrayList<>();
                row.add(minute.getKey());
                for (String category : categories) {
                    int covered = cumulative.getOrDefault(category, 0) + minute.getValue().getOrDefault(category, 0);
                    cumulative.put(category, covered);
                    row.add(count(covered) + " (" + terminalPercent(covered, totals.get(category)) + ")");
                }
                rows.add(row);
            }
            printTable(headers, rows);
        }
        if (observations.getUnattributedCount() > 0) {
            System.out.println(count(observations.getUnattributedCount())
                    + " observations without timestamps are included in the overall totals only.");
        }
    }

    /** Preserves the aggregate and cumulative JSON keys used by existing callers. */
    public void compareSummariesPerMinuteToJson(SummaryBuilder baseline, SummaryLogBuilder observations, String filePath) {
        Map<String, Integer> totals = baseline.getSummary();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("coverageSummary", aggregateJson(totals, observations.getSummary()));
        Map<String, Object> minutes = new LinkedHashMap<>();
        Map<String, Integer> cumulative = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> minute : new TreeMap<>(observations.getPerMinuteSummaries()).entrySet()) {
            Map<String, Object> categories = new LinkedHashMap<>();
            for (String category : orderedCategories(totals)) {
                int covered = cumulative.getOrDefault(category, 0) + minute.getValue().getOrDefault(category, 0);
                cumulative.put(category, covered);
                categories.put(category, metricJson(covered, totals.get(category), true));
            }
            minutes.put(minute.getKey(), categories);
        }
        report.put("perMinuteSummaries", minutes);
        addCallGraph(report, baseline, observations);
        writeJson(report, filePath);
    }

    private void printCallGraph(SummaryBuilder baseline, SummaryLogBuilder observations) {
        if (!baseline.isCallGraphRequested() && !observations.getCallGraph().hasData()) return;
        Map<String, Object> graph = observations.getCallGraph().toMap(Collections.emptySet());
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) graph.get("stats");
        System.out.println();
        System.out.println("Dynamic call graph");
        if (!observations.getCallGraph().hasData()) {
            System.out.println("No matching dynamic-call-graph records. Instrument with -mc and collect a new runtime log.");
        } else {
            System.out.println(stats.get("observedEdges") + " confirmed edges; " + stats.get("unresolvedSites")
                    + " callsites without a confirmed target (declared targets retained).");
            System.out.println("Distinct relationships only; counts are not invocation frequencies.");
        }
    }

    private void addCallGraph(Map<String, Object> report, SummaryBuilder baseline, SummaryLogBuilder observations) {
        if (baseline.isCallGraphRequested() || observations.getCallGraph().hasData()) {
            java.util.Set<String> code = new java.util.HashSet<>();
            for (CoverageMethod method : baseline.getCodeMethods()) code.add(method.getSignature());
            Map<String, Object> callGraph = observations.getCallGraph().toMap(code);
            callGraph.put("launcherActivities", baseline.getLauncherActivities());
            report.put("callGraph", callGraph);
        }
    }

    private Map<String, Object> aggregateJson(Map<String, Integer> totals, Map<String, Integer> observed) {
        Map<String, Object> categories = new LinkedHashMap<>();
        for (String category : orderedCategories(totals)) {
            categories.put(category, metricJson(observed.getOrDefault(category, 0), totals.get(category), false));
        }
        return categories;
    }

    private Map<String, Object> metricJson(int covered, int total, boolean cumulative) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put(cumulative ? "cumulative" : "covered", covered);
        metric.put(cumulative ? "overall" : "total", total);
        // Keep the established JSON string format, including 0.0% for no probes.
        metric.put("percent", percent(covered, total));
        return metric;
    }

    private void writeJson(Map<String, Object> report, String filePath) {
        Path output = Paths.get(filePath).toAbsolutePath();
        try {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n";
            Files.write(output, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write JSON coverage report: " + output, e);
        }
    }

    private List<String> orderedCategories(Map<String, Integer> totals) {
        List<String> categories = new ArrayList<>();
        for (String category : CATEGORY_LABELS.keySet()) {
            if (totals.containsKey(category)) {
                categories.add(category);
            }
        }
        TreeSet<String> remaining = new TreeSet<>(totals.keySet());
        remaining.removeAll(categories);
        categories.addAll(remaining);
        return categories;
    }

    private String label(String category) {
        return CATEGORY_LABELS.getOrDefault(category, category);
    }

    private String count(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private String percent(int covered, int total) {
        return String.format(Locale.ROOT, "%.1f%%", total == 0 ? 0.0 : covered * 100.0 / total);
    }

    private String terminalPercent(int covered, int total) {
        return total == 0 ? "n/a" : percent(covered, total);
    }

    private void printTable(List<String> headers, List<List<String>> rows) {
        int[] widths = new int[headers.size()];
        for (int column = 0; column < headers.size(); column++) {
            widths[column] = headers.get(column).length();
            for (List<String> row : rows) {
                widths[column] = Math.max(widths[column], row.get(column).length());
            }
        }
        printRow(headers, widths);
        List<String> separator = new ArrayList<>();
        for (int width : widths) {
            separator.add(String.join("", Collections.nCopies(width, "-")));
        }
        printRow(separator, widths);
        for (List<String> row : rows) {
            printRow(row, widths);
        }
    }

    private void printRow(List<String> row, int[] widths) {
        StringBuilder line = new StringBuilder();
        for (int column = 0; column < row.size(); column++) {
            if (column > 0) {
                line.append("  ");
            }
            line.append(String.format(Locale.ROOT, "%" + (column == 0 ? "-" : "") + widths[column] + "s", row.get(column)));
        }
        System.out.println(line);
    }
}
