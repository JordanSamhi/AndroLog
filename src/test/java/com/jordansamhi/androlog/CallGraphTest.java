package com.jordansamhi.androlog;

import org.junit.Test;

import java.net.URLEncoder;
import java.util.*;

import static org.junit.Assert.*;

public class CallGraphTest {
    static final String CALLER = "<example.Caller: void run()>";
    static final String DECLARED = "<example.Base: int work(java.lang.String)>";
    static final String CHILD = "<example.Child: int work(java.lang.String)>";
    static final String OTHER = "<example.Other: int work(java.lang.String)>";
    static final String TRACE = "fe2e665a-68cc-44b8-abde-16104cd7378a";
    private final CallGraph graph = new CallGraph();
    private final Map<String, CallSite> sites = new HashMap<>();
    private final Set<String> methods = new HashSet<>(Arrays.asList(CALLER, CHILD, OTHER));

    public CallGraphTest() { site(4, DECLARED, "virtual"); }

    @Test
    public void resolvesMultipleOverridesAndKeepsUnresolvedSitesSeparate() throws Exception {
        site(7, DECLARED, "virtual");
        accept(record(1, "D", 4, DECLARED, "", "virtual"));
        accept(record(2, "O", 4, DECLARED, CHILD, "virtual"));
        accept(record(3, "O", 4, DECLARED, OTHER, "virtual"));
        accept(record(4, "D", 7, DECLARED, "", "virtual"));
        Map<String, Object> data = graph.toMap(methods);
        assertEquals(2L, stats(data).get("observedEdges"));
        assertEquals(1L, stats(data).get("declaredEdges"));
        assertEquals(1L, stats(data).get("unresolvedSites"));
        assertEquals(2, stats(data).get("callSites"));
        assertEquals(3, edges(data).size());
        assertEquals("observed", edge(data, CHILD).get("kind"));
        assertEquals("declared", edge(data, DECLARED).get("kind"));
        assertEquals("7", ((List<Map<String, Object>>) edge(data, DECLARED).get("callSites")).get(0).get("id"));
        for (Map<String, Object> node : (List<Map<String, Object>>) data.get("nodes")) {
            assertEquals(!DECLARED.equals(node.get("id")), node.get("inReport"));
        }
    }

    @Test
    public void completeObservedRecordsSurviveLostDeclarationsAndDeduplicateMergedLogs() throws Exception {
        String entry = record(2, "O", 4, DECLARED, CHILD, "virtual");
        accept(entry); // No D record is needed to reconstruct this relationship.
        accept(entry);
        accept(entry.replace(TRACE, "e081de75-6b2e-4efd-a687-b848afc000d8"));
        Map<String, Object> data = graph.toMap(methods);
        assertEquals(1, edges(data).size());
        assertEquals(2L, edge(data, CHILD).get("count"));
        assertEquals(2, stats(data).get("records"));
        assertEquals(0L, stats(data).get("unresolvedSites"));
    }

    @Test
    public void rejectsTruncationForeignSitesWrongTargetsAndInvalidEncodings() throws Exception {
        String valid = record(1, "O", 4, DECLARED, CHILD, "virtual");
        String[] invalid = {
                valid.substring(0, valid.lastIndexOf('|')), valid + "|extra",
                valid.replace(TRACE, "bad-id"), valid.replace("|1|O|", "|0|O|"),
                record(1, "O", 99, DECLARED, CHILD, "virtual"),
                record(1, "O", 4, OTHER, CHILD, "virtual"),
                record(1, "O", 4, DECLARED, CHILD, "interface"),
                record(1, "D", 4, DECLARED, CHILD, "virtual"),
                record(1, "O", 4, DECLARED, "<foreign.Target: int work(java.lang.String)>", "virtual"),
                record(1, "O", 4, DECLARED, CALLER, "virtual"), valid.replace("%3C", "%XY")
        };
        for (String line : invalid) assertFalse(line, graph.accept(line, sites, methods));
        assertFalse(graph.hasData());
        accept(valid); // Rejected records must not reserve the event identifier.
        assertTrue(graph.hasData());
    }

    @Test
    public void constructorsAndLegacyRecordsNeverClaimConfirmedDispatch() throws Exception {
        String constructor = "<example.Child: void <init>()>";
        methods.add(constructor);
        site(8, constructor, "constructor");
        assertFalse(graph.accept(record(1, "O", 8, constructor, constructor, "constructor"), sites, methods));
        accept(record(2, "D", 8, constructor, "", "constructor"));
        accept("CALL=" + CALLER + "-->" + DECLARED);
        accept("CALL=" + CALLER + "-->" + DECLARED);
        Map<String, Object> data = graph.toMap(methods);
        assertEquals(0L, stats(data).get("observedEdges"));
        assertEquals(2L, stats(data).get("declaredEdges"));
        assertTrue(data.get("note").toString().contains("Legacy CALL"));
    }

    @Test
    public void aggregatesDistinctCallsitesWithoutLosingTheirIdentity() throws Exception {
        site(5, DECLARED, "virtual");
        accept(record(1, "O", 4, DECLARED, CHILD, "virtual"));
        accept(record(2, "O", 5, DECLARED, CHILD, "virtual"));
        Map<String, Object> edge = edge(graph.toMap(methods), CHILD);
        assertEquals(2L, edge.get("count"));
        assertEquals(2, ((List<?>) edge.get("callSites")).size());
    }

    private void site(int line, String declared, String kind) {
        CallSite site = new CallSite(CALLER, line, declared, kind);
        sites.put(site.key(), site);
    }

    private void accept(String record) { assertTrue(record, graph.accept(record, sites, methods)); }

    static String record(long sequence, String status, int line, String declared, String actual, String kind) throws Exception {
        return "MC2|" + TRACE + "|" + sequence + "|" + status + "|" + line + "|"
                + URLEncoder.encode(CALLER, "UTF-8") + "|" + URLEncoder.encode(declared, "UTF-8")
                + "|" + URLEncoder.encode(actual, "UTF-8") + "|" + kind;
    }

    private static Map<String, Object> stats(Map<String, Object> data) { return (Map<String, Object>) data.get("stats"); }
    private static List<Map<String, Object>> edges(Map<String, Object> data) { return (List<Map<String, Object>>) data.get("edges"); }
    private static Map<String, Object> edge(Map<String, Object> data, String target) {
        return edges(data).stream().filter(edge -> target.equals(edge.get("callee"))).findFirst().get();
    }
}
