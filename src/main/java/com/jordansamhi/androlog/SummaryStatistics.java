package com.jordansamhi.androlog;

import java.io.*;
import java.util.Map;
import java.util.HashMap;
import com.jordansamhi.androspecter.printers.Writer;

/**
 * This class is responsible for comparing and displaying summary statistics.
 * It compares the statistics from two different sources, typically summaries of
 * coverage metrics, and displays the comparison in a formatted manner.
 */
public class SummaryStatistics {

    /**
     * Compares and prints the summary statistics from two different summary builders.
     * This method takes two {@link SummaryBuilder} instances and compares their summaries
     * by category. It prints out the comparison in a readable format, showing the percentage
     * coverage for each category.
     *
     * @param summaryBuilder    The first summary builder, representing the baseline summary.
     * @param summaryLogBuilder The second summary builder, representing the summary to be compared.
     */
    public void compareSummaries(SummaryBuilder summaryBuilder, SummaryLogBuilder summaryLogBuilder) {
        Map<String, Integer> summary = summaryBuilder.getSummary();
        Map<String, Integer> logSummary = summaryLogBuilder.getSummary();

        System.out.println();
        System.out.println("=== Coverage Summary ===");
        System.out.println("------------------------");
        summary.keySet().forEach(category ->
                printFormattedPercentage(category, summary.getOrDefault(category, 0), logSummary.getOrDefault(category, 0)));
        System.out.println("------------------------");
    }

    public void compareSummariesToJson(SummaryBuilder summaryBuilder, SummaryLogBuilder summaryLogBuilder, String filePath) {
        Map<String, Integer> summary = summaryBuilder.getSummary();
        Map<String, Integer> logSummary = summaryLogBuilder.getSummary();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"coverageSummary\": {\n");

        int count = 0;
        int size = summary.size();

        for (String category : summary.keySet()) {
            int total = summary.getOrDefault(category, 0);
            int covered = logSummary.getOrDefault(category, 0);
            double percent = total != 0 ? (covered * 100.0 / total) : 0.0;

            json.append("    \"").append(category).append("\": {\n");
            json.append("      \"covered\": ").append(covered).append(",\n");
            json.append("      \"total\": ").append(total).append(",\n");
            json.append("      \"percent\": \"").append(String.format("%.1f%%", percent)).append("\"\n");
            json.append("    }");

            count++;
            if (count < size) {
                json.append(",");
            }
            json.append("\n");
        }

        json.append("  }\n");
        json.append("}\n");

        // Write JSON to file
        File file = new File(filePath);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(json.toString());
        } catch (IOException e) {
            System.err.println("Error writing JSON to file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void compareSummariesPerMinute(SummaryBuilder summaryBuilder, SummaryLogBuilder summaryLogBuilder) {
        Map<String, Integer> summary = summaryBuilder.getSummary();
        Map<String, Integer> logSummary = summaryLogBuilder.getSummary();

        System.out.println();
        System.out.println("=== Coverage Summary ===");
        System.out.println("------------------------");
        summary.keySet().forEach(category ->
                printFormattedPercentage(category, summary.getOrDefault(category, 0), logSummary.getOrDefault(category, 0)));
        System.out.println("------------------------");

        System.out.println();
        System.out.println("=== Per-Minute Coverage Summaries ===");
        System.out.println("------------------------");

        Map<String, Map<String, Integer>> perMinuteSummaries = summaryLogBuilder.getPerMinuteSummaries();
        Map<String, Integer> cumulativeTotals = new HashMap<>();

        for (String minute : perMinuteSummaries.keySet()) {
            System.out.println("Minute: " + minute);
            Map<String, Integer> minuteSummary = perMinuteSummaries.get(minute);

            for (String category : minuteSummary.keySet()) {
                cumulativeTotals.put(category, cumulativeTotals.getOrDefault(category, 0) + minuteSummary.get(category));
            }

            for (String category : summary.keySet()) {
                int totalCount = cumulativeTotals.getOrDefault(category, 0);
                int overallCount = summary.getOrDefault(category, 0);
                printFormattedPercentage(category, overallCount, totalCount);
            }
            System.out.println("------------------------");
        }
    }

    public void compareSummariesPerMinuteToJson(SummaryBuilder summaryBuilder, SummaryLogBuilder summaryLogBuilder, String filePath) {
        Map<String, Integer> summary = summaryBuilder.getSummary();
        Map<String, Integer> logSummary = summaryLogBuilder.getSummary();
        Map<String, Map<String, Integer>> perMinuteSummaries = summaryLogBuilder.getPerMinuteSummaries();

        StringBuilder json = new StringBuilder();
        json.append("{\n");

        // Coverage Summary
        json.append("  \"coverageSummary\": {\n");
        int coverageCount = 0;
        for (String category : summary.keySet()) {
            int total = summary.getOrDefault(category, 0);
            int covered = logSummary.getOrDefault(category, 0);
            double percent = total != 0 ? (covered * 100.0 / total) : 0.0;

            json.append("    \"").append(category).append("\": {\n");
            json.append("      \"covered\": ").append(covered).append(",\n");
            json.append("      \"total\": ").append(total).append(",\n");
            json.append("      \"percent\": \"").append(String.format("%.1f%%", percent)).append("\"\n");
            json.append("    }");

            coverageCount++;
            if (coverageCount < summary.size()) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  },\n");

        // Per-Minute Summaries
        json.append("  \"perMinuteSummaries\": {\n");
        Map<String, Integer> cumulativeTotals = new HashMap<>();
        int minuteCount = 0;
        int totalMinutes = perMinuteSummaries.size();

        for (String minute : perMinuteSummaries.keySet()) {
            Map<String, Integer> minuteSummary = perMinuteSummaries.get(minute);

            json.append("    \"").append(minute).append("\": {\n");
            int catCount = 0;
            int totalCategories = summary.size();

            for (String category : summary.keySet()) {
                int count = minuteSummary.getOrDefault(category, 0);
                cumulativeTotals.put(category, cumulativeTotals.getOrDefault(category, 0) + count);
                int cumulative = cumulativeTotals.get(category);
                int overall = summary.getOrDefault(category, 0);
                double percent = overall != 0 ? (cumulative * 100.0 / overall) : 0.0;

                json.append("      \"").append(category).append("\": {\n");
                json.append("        \"cumulative\": ").append(cumulative).append(",\n");
                json.append("        \"overall\": ").append(overall).append(",\n");
                json.append("        \"percent\": \"").append(String.format("%.1f%%", percent)).append("\"\n");
                json.append("      }");

                catCount++;
                if (catCount < totalCategories) {
                    json.append(",");
                }
                json.append("\n");
            }

            json.append("    }");

            minuteCount++;
            if (minuteCount < totalMinutes) {
                json.append(",");
            }
            json.append("\n");
        }

        json.append("  }\n");
        json.append("}\n");

        // Write JSON to file
        File file = new File(filePath);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(json.toString());
        } catch (IOException e) {
            System.err.println("Error writing JSON to file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Prints the formatted percentage of a given category.
     * This method calculates and formats the percentage of coverage for a particular category
     * based on total and covered metrics. The output is formatted and aligned for readability.
     *
     * @param category The name of the category for which the percentage is being calculated.
     * @param total    The total count for the category.
     * @param covered  The covered count for the category.
     */
    private void printFormattedPercentage(String category, int total, int covered) {
        float percentage = total > 0 ? (float) covered * 100 / total : 0;
        System.out.printf("%-20s: %5.1f%% (%d/%d) %n", category, percentage, covered, total);
    }
}