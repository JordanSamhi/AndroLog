package com.jordansamhi.androlog;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LogParserTest {
    private static final String TAG = "COVERAGE_TEST";
    private static final String METHOD = "<example.Demo: void run()>";

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private SummaryBuilder baseline;
    private SummaryLogBuilder observed;

    @Before
    public void setUp() throws Exception {
        CoverageTestSupport.resetBuilders();
        baseline = SummaryBuilder.v();
        observed = SummaryLogBuilder.v();
    }

    @After
    public void tearDown() throws Exception {
        CoverageTestSupport.resetBuilders();
    }

    @Test
    public void matchesFullStatementIdentityAndCountsEachProbeOnce() throws Exception {
        String first = METHOD + "|x = 1|2";
        String second = METHOD + "|x = 1|3";
        CoverageTestSupport.addBaseline(baseline, "statements", first);
        CoverageTestSupport.addBaseline(baseline, "statements", second);

        parse(
                timestamped("12:34:01.123", "STATEMENT=" + first),
                timestamped("12:35:01.123", "STATEMENT=" + first),
                timestamped("12:35:02.123", "STATEMENT=" + METHOD + "|changed = 9|3"),
                timestamped("12:35:03.123", "STATEMENT=" + METHOD + "|x = 1|99"),
                timestamped("12:35:04.123", "STATEMENT=" + second));

        assertEquals(Integer.valueOf(2), observed.getSummary().get("statements"));
        assertEquals(Integer.valueOf(1), observed.getPerMinuteSummaries().get("10-22 12:34").get("statements"));
        assertEquals(Integer.valueOf(1), observed.getPerMinuteSummaries().get("10-22 12:35").get("statements"));
    }

    @Test
    public void preservesPipesEqualsAndUnicodeInsideStatementPayload() throws Exception {
        String payload = METHOD + "|message = \"été|日本語=ready\"|7";
        CoverageTestSupport.addBaseline(baseline, "statements", payload);

        parse(timestamped("12:34:01.123", "STATEMENT=" + payload),
                timestamped("12:34:02.123", "STATEMENT=" + payload),
                timestamped("12:34:03.123", "STATEMENT=" + payload.replace("=ready", "=wrong")));

        assertEquals(Integer.valueOf(1), observed.getSummary().get("statements"));
    }

    @Test
    public void ignoresMalformedUnknownAndUnrelatedLinesWithoutAborting() throws Exception {
        CoverageTestSupport.addBaseline(baseline, "methods", METHOD);
        CoverageTestSupport.addBaseline(baseline, "statements", METHOD + "|x = 1|2");

        parse("", "unrelated log output", TAG, TAG + ": STATEMENT=", TAG + ": STATEMENT=broken",
                TAG + ": STATEMENT=" + METHOD + "|", TAG + ": METHOD=", TAG + ": CLASS=unknown.Class",
                TAG + ": UNKNOWN=" + METHOD, TAG + ": METHOD=<other.Demo: void run()>",
                "10-22 12:34:01.123  101  102 I OTHER_TAG: METHOD=" + METHOD,
                timestamped("12:34:02.123", "METHOD=" + METHOD));

        assertEquals(Integer.valueOf(1), observed.getSummary().get("methods"));
        assertEquals(1, observed.getSummary().size());
    }

    @Test
    public void acceptsMessageOnlyLogsWithoutInventingTimelineMinutes() throws Exception {
        String payload = METHOD + "|message = \"été\"|2";
        CoverageTestSupport.addBaseline(baseline, "methods", METHOD);
        CoverageTestSupport.addBaseline(baseline, "statements", payload);

        parse(TAG + ": METHOD=" + METHOD,
                TAG + ": STATEMENT=" + payload,
                TAG + ": STATEMENT=" + payload);

        assertEquals(Integer.valueOf(1), observed.getSummary().get("methods"));
        assertEquals(Integer.valueOf(1), observed.getSummary().get("statements"));
        assertTrue(observed.getPerMinuteSummaries().isEmpty());
        assertEquals(2, observed.getUnattributedCount());
    }

    @Test
    public void acceptsKnownClassesMethodsAndEveryAndroidComponentCategory() throws Exception {
        String[][] categories = {
                {"classes", "CLASS", "example.Demo"},
                {"methods", "METHOD", METHOD},
                {"activities", "ACTIVITY", "example.MainActivity"},
                {"services", "SERVICE", "example.SyncService"},
                {"broadcast-receivers", "BROADCASTRECEIVER", "example.Receiver"},
                {"content-providers", "CONTENTPROVIDER", "example.Provider"}
        };
        for (String[] category : categories) {
            CoverageTestSupport.addBaseline(baseline, category[0], category[2]);
        }
        String[] lines = Arrays.stream(categories)
                .map(category -> timestamped("12:34:01.123", category[1] + "=" + category[2]))
                .toArray(String[]::new);

        parse(lines);

        Map<String, Integer> summary = observed.getSummary();
        assertEquals(categories.length, summary.size());
        for (String[] category : categories) {
            assertEquals(category[0], Integer.valueOf(1), summary.get(category[0]));
        }
    }

    @Test
    public void reportsUnreadableLogsInsteadOfProducingAnEmptyCoverageResult() {
        Path missing = temporary.getRoot().toPath().resolve("missing.log");

        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> new LogParser(TAG, baseline).parseLogs(missing.toString()));

        assertTrue(error.getMessage().contains(missing.toString()));
        assertTrue(observed.getSummary().isEmpty());
    }

    @Test
    public void onlyAcceptsCompleteCallRecordsUnderTheExactLogTag() throws Exception {
        CoverageTestSupport.addCallSite(baseline,
                new CallSite(CallGraphTest.CALLER, 4, CallGraphTest.DECLARED, "virtual"), CallGraphTest.CHILD);
        String record = CallGraphTest.record(1, "O", 4, CallGraphTest.DECLARED, CallGraphTest.CHILD, "virtual");
        parse("OTHER_" + TAG + ": " + record,
                "OTHER: " + TAG + ": " + record,
                "10-22 12:34:01.123  101  102 I OTHER: " + TAG + ": " + record,
                TAG + ": prefix " + record,
                TAG + ": " + record.substring(0, record.lastIndexOf('|')));
        assertTrue(!observed.getCallGraph().hasData());
        parse(timestamped("12:34:02.123", record),
                "I/" + TAG + "( 101): " + record,
                "10-22 12:34:02.123 I/" + TAG + "( 101): " + record,
                TAG + ": " + record);
        Map<?, ?> stats = (Map<?, ?>) observed.getCallGraph().toMap(baseline.getGraphMethods()).get("stats");
        assertEquals(1, stats.get("records"));
        assertEquals(1L, stats.get("observedEdges"));
        assertTrue(observed.getSummary().isEmpty());
    }

    private void parse(String... lines) throws Exception {
        Path path = temporary.newFile().toPath();
        Files.write(path, Arrays.asList(lines), StandardCharsets.UTF_8);
        new LogParser(TAG, baseline).parseLogs(path.toString());
    }

    private static String timestamped(String time, String message) {
        return "10-22 " + time + "  101  102 I " + TAG + ": " + message;
    }
}
