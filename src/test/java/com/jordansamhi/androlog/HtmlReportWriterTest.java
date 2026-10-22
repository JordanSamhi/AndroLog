package com.jordansamhi.androlog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HtmlReportWriterTest {
    private static final Pattern REPORT_DATA = Pattern.compile(
            "<script\\b[^>]*\\bid=[\"']report-data[\"'][^>]*>(.*?)</script\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final String CLASS = "example.Alpha";
    private static final String METHOD = "<example.Alpha: void run()>";

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
    public void writesUtf8StandaloneReportAndEscapesUntrustedScriptContent() throws Exception {
        String hostile = "</script><script id=\"injected\">alert('été|日本語=1 __ANDROLOG_LINKS__ __ANDROLOG_GRAPH__')</script>";
        String text = "message = \"" + hostile + "\"";
        addMethod(CLASS, METHOD, new CoverageMethod.Statement(1, text, true));
        CoverageTestSupport.addBaseline(baseline, "statements", METHOD + "|" + text + "|1");

        Path output = temporary.getRoot().toPath().resolve("nested/report/coverage.html");
        new HtmlReportWriter().write(baseline, observed, hostile + ".apk", hostile, output.toString(), false);

        assertTrue(Files.isRegularFile(output));
        String html = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        assertFalse("Untrusted data must not terminate its JSON script element", html.contains(hostile));
        assertFalse(html.contains("__ANDROLOG_DATA__"));
        JsonObject data = dataFrom(html);
        assertEquals(hostile + ".apk", data.get("appName").getAsString());
        assertEquals(hostile, data.get("logIdentifier").getAsString());
        assertEquals(text, firstMethod(data).getAsJsonArray("statements").get(0).getAsJsonObject().get("text").getAsString());
        assertFalse("The report must not load external scripts", Pattern.compile("<script[^>]+src=", Pattern.CASE_INSENSITIVE).matcher(html).find());
        assertFalse("The report must not load external styles", Pattern.compile("<link[^>]+href=", Pattern.CASE_INSENSITIVE).matcher(html).find());
    }

    @Test
    public void distinguishesObservedUnobservedExcludedAndUnmeasuredStatements() throws Exception {
        addMethod(CLASS, METHOD,
                new CoverageMethod.Statement(1, "this := @this: example.Alpha", false),
                new CoverageMethod.Statement(2, "x = 1", true),
                new CoverageMethod.Statement(3, "y = 2", true),
                new CoverageMethod.Statement(4, "unmatched = 3", true));
        CoverageTestSupport.addBaseline(baseline, "methods", METHOD);
        CoverageTestSupport.addBaseline(baseline, "classes", CLASS);
        CoverageTestSupport.addBaseline(baseline, "statements", METHOD + "|x = 1|2");
        CoverageTestSupport.addBaseline(baseline, "statements", METHOD + "|y = 2|3");
        observed.record("methods", METHOD, "10-22 12:34:01.123 TAG: METHOD=" + METHOD);
        observed.record("statements", METHOD + "|x = 1|2", "10-22 12:34:01.123 TAG: STATEMENT=" + METHOD + "|x = 1|2");

        JsonObject data = writeReport(false);
        JsonObject method = firstMethod(data);
        assertTrue(method.get("covered").getAsBoolean());
        assertFalse(data.getAsJsonArray("classes").get(0).getAsJsonObject().get("classCovered").getAsBoolean());
        JsonArray statements = method.getAsJsonArray("statements");
        assertEquals("excluded", statements.get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("covered", statements.get(1).getAsJsonObject().get("status").getAsString());
        assertEquals("uncovered", statements.get(2).getAsJsonObject().get("status").getAsString());
        assertEquals("unmeasured", statements.get(3).getAsJsonObject().get("status").getAsString());
        JsonObject category = findCategory(data, "statements");
        assertEquals(2, category.get("total").getAsInt());
        assertEquals(1, category.get("covered").getAsInt());
    }

    @Test
    public void keepsUnselectedCoverageAndZeroDenominatorsNeutral() throws Exception {
        addMethod(CLASS, METHOD, new CoverageMethod.Statement(1, "x = 1", true),
                new CoverageMethod.Statement(2, "return", false));

        JsonObject unselected = writeReport(false);
        JsonObject method = firstMethod(unselected);
        assertTrue(method.get("covered").isJsonNull());
        assertTrue(unselected.getAsJsonArray("classes").get(0).getAsJsonObject().get("classCovered").isJsonNull());
        assertEquals("unmeasured", method.getAsJsonArray("statements").get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("excluded", method.getAsJsonArray("statements").get(1).getAsJsonObject().get("status").getAsString());

        baseline.getSummary().put("statements", 0);
        baseline.getSummary().put("methods", 0);
        JsonObject zero = writeReport(false);
        for (JsonElement element : zero.getAsJsonArray("categories")) {
            assertEquals(0, element.getAsJsonObject().get("total").getAsInt());
            assertEquals(0, element.getAsJsonObject().get("covered").getAsInt());
        }
        assertFalse(zero.toString().contains("NaN"));
        assertFalse(zero.toString().contains("Infinity"));
        assertEquals("unmeasured", firstMethod(zero).getAsJsonArray("statements").get(0).getAsJsonObject().get("status").getAsString());
    }

    @Test
    public void ordersClassesAndMethodsDeterministically() throws Exception {
        addMethod("example.Zed", "<example.Zed: void z()>");
        addMethod(CLASS, "<example.Alpha: void z()>");
        addMethod(CLASS, "<example.Alpha: void a()>");

        JsonArray first = writeReport(false).getAsJsonArray("classes");
        JsonArray second = writeReport(false).getAsJsonArray("classes");

        assertEquals(first, second);
        assertEquals(CLASS, first.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("example.Zed", first.get(1).getAsJsonObject().get("name").getAsString());
        JsonArray methods = first.get(0).getAsJsonObject().getAsJsonArray("methods");
        assertEquals("<example.Alpha: void a()>", methods.get(0).getAsJsonObject().get("signature").getAsString());
        assertEquals("<example.Alpha: void z()>", methods.get(1).getAsJsonObject().get("signature").getAsString());
    }

    @Test
    public void buildsSortedCumulativeTimelineWithoutTimestampLessObservations() throws Exception {
        String second = "<example.Alpha: void second()>";
        String third = "<example.Alpha: void third()>";
        CoverageTestSupport.addBaseline(baseline, "methods", METHOD);
        CoverageTestSupport.addBaseline(baseline, "methods", second);
        CoverageTestSupport.addBaseline(baseline, "methods", third);
        observed.record("methods", METHOD, "10-22 12:35:01.123 TAG: METHOD=" + METHOD);
        observed.record("methods", second, "10-22 12:34:01.123 TAG: METHOD=" + second);
        observed.record("methods", third, "TAG: METHOD=" + third);
        observed.record("methods", METHOD, "10-22 12:36:01.123 TAG: METHOD=" + METHOD);

        JsonObject data = writeReport(true);
        JsonArray timeline = data.getAsJsonArray("timeline");
        assertEquals(3, timeline.size());
        assertEquals("10-22 12:34", timeline.get(0).getAsJsonObject().get("minute").getAsString());
        assertEquals(1, timeline.get(0).getAsJsonObject().getAsJsonObject("counts").get("methods").getAsInt());
        assertEquals("10-22 12:35", timeline.get(1).getAsJsonObject().get("minute").getAsString());
        assertEquals(2, timeline.get(1).getAsJsonObject().getAsJsonObject("counts").get("methods").getAsInt());
        assertEquals("10-22 12:36", timeline.get(2).getAsJsonObject().get("minute").getAsString());
        assertEquals(2, timeline.get(2).getAsJsonObject().getAsJsonObject("counts").get("methods").getAsInt());
        assertEquals(3, findCategory(data, "methods").get("covered").getAsInt());
        assertTrue("The missing timestamp must be disclosed", data.getAsJsonArray("warnings").size() > 0);
    }

    @Test
    public void graphOnlyReportIncludesObservedTargetsAndNavigableCode() throws Exception {
        baseline.setCollectCallGraph(true);
        baseline.setLauncherActivities(Collections.singletonList("example.Caller"));
        addMethod("example.Caller", CallGraphTest.CALLER);
        addMethod("example.Child", CallGraphTest.CHILD);
        CoverageTestSupport.addCallSite(baseline,
                new CallSite(CallGraphTest.CALLER, 4, CallGraphTest.DECLARED, "virtual"), CallGraphTest.CHILD);
        observed.getCallGraph().accept(CallGraphTest.record(1, "O", 4, CallGraphTest.DECLARED,
                CallGraphTest.CHILD, "virtual"), baseline.getCallSites(), baseline.getGraphMethods());

        JsonObject data = writeReport(false);
        JsonObject graph = data.getAsJsonObject("callGraph");
        assertTrue(graph.get("available").getAsBoolean());
        assertEquals("example.Caller", graph.getAsJsonArray("launcherActivities").get(0).getAsString());
        assertEquals(1, graph.getAsJsonObject("stats").get("observedEdges").getAsInt());
        assertEquals(CallGraphTest.CHILD, graph.getAsJsonArray("edges").get(0).getAsJsonObject().get("callee").getAsString());
        for (JsonElement node : graph.getAsJsonArray("nodes")) assertTrue(node.getAsJsonObject().get("inReport").getAsBoolean());
        assertTrue(firstMethod(data).get("covered").isJsonNull());
    }

    @Test
    public void distinguishesMissingManifestMetadataFromNoLauncher() throws Exception {
        assertTrue(writeReport(false).getAsJsonObject("callGraph").get("launcherActivities").isJsonNull());
        baseline.setLauncherActivities(Collections.emptyList());
        assertEquals(0, writeReport(false).getAsJsonObject("callGraph").getAsJsonArray("launcherActivities").size());
    }

    private void addMethod(String className, String signature, CoverageMethod.Statement... statements) throws Exception {
        CoverageTestSupport.addCodeMethod(baseline, new CoverageMethod(className, signature, null,
                statements.length == 0 ? Collections.emptyList() : Arrays.asList(statements)));
    }

    private JsonObject writeReport(boolean perMinute) throws Exception {
        Path output = temporary.newFile().toPath();
        new HtmlReportWriter().write(baseline, observed, "example.apk", "TAG", output.toString(), perMinute);
        return dataFrom(new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
    }

    private static JsonObject dataFrom(String html) {
        Matcher matcher = REPORT_DATA.matcher(html);
        assertTrue("Expected embedded JSON data", matcher.find());
        return JsonParser.parseString(matcher.group(1)).getAsJsonObject();
    }

    private static JsonObject firstMethod(JsonObject data) {
        return data.getAsJsonArray("classes").get(0).getAsJsonObject().getAsJsonArray("methods").get(0).getAsJsonObject();
    }

    private static JsonObject findCategory(JsonObject data, String key) {
        for (JsonElement element : data.getAsJsonArray("categories")) {
            JsonObject category = element.getAsJsonObject();
            if (key.equals(category.get("key").getAsString())) {
                return category;
            }
        }
        throw new AssertionError("Missing category: " + key);
    }
}
