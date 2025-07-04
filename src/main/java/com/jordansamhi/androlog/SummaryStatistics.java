package com.jordansamhi.androlog;

import java.util.Map;
import java.util.HashMap;

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