package com.jordansamhi.androlog;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;

/** Aggregates complete runtime edge records; never pairs adjacent log lines. */
public final class CallGraph {
    private final Set<String> events = new HashSet<>();
    private final Map<String, SiteObservation> sites = new TreeMap<>();
    private boolean legacy;

    /** Reject foreign APK sites, truncated records and unconfirmed entry claims. */
    public boolean accept(String message, Map<String, CallSite> baseline, Set<String> methods) {
        if (message.startsWith("CALL=")) return acceptLegacy(message.substring(5), baseline);
        if (!message.startsWith("MC2|")) return false;
        String[] fields = message.split("\\|", -1);
        if (fields.length != 9) return false;
        try {
            String trace = UUID.fromString(fields[1]).toString();
            if (!trace.equalsIgnoreCase(fields[1])) return false;
            long sequence = Long.parseLong(fields[2]);
            int line = Integer.parseInt(fields[4]);
            if (sequence < 1 || line < 1 || !("D".equals(fields[3]) || "O".equals(fields[3]))) return false;
            String caller = decode(fields[5]), declared = decode(fields[6]), actual = decode(fields[7]);
            CallSite site = baseline.get(CallSite.key(caller, line));
            if (site == null || !site.declared.equals(declared) || !site.kind.equals(fields[8])) return false;
            boolean observed = "O".equals(fields[3]);
            if (observed) {
                if (!methods.contains(actual) || !subSignature(actual).equals(subSignature(declared))
                        || "constructor".equals(site.kind) || "dynamic".equals(site.kind)
                        || actual.contains(" <clinit>(")) return false;
            } else if (!actual.isEmpty()) return false;
            if (!events.add(trace + ":" + sequence)) return true;
            SiteObservation observation = sites.computeIfAbsent(site.key(), ignored -> new SiteObservation(site, false));
            if (observed) observation.targets.merge(actual, 1L, Long::sum);
            else observation.declarations++;
            return true;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private boolean acceptLegacy(String message, Map<String, CallSite> baseline) {
        int arrow = message.indexOf("-->");
        if (arrow < 0) return false;
        String caller = message.substring(0, arrow), declared = message.substring(arrow + 3);
        CallSite match = null;
        for (CallSite site : baseline.values()) {
            if (site.caller.equals(caller) && site.declared.equals(declared)) { match = site; break; }
        }
        if (match == null) return false;
        String key = "legacy:" + message;
        if (events.add(key)) {
            SiteObservation observation = new SiteObservation(new CallSite(caller, 0, declared, "legacy"), true);
            observation.declarations = 1;
            sites.put(key, observation);
        }
        legacy = true;
        return true;
    }

    public boolean hasData() { return !sites.isEmpty(); }

    /** Same graph schema is embedded in HTML and included in JSON exports. */
    public Map<String, Object> toMap(Set<String> codeMethods) {
        Map<List<String>, Edge> edges = new LinkedHashMap<>();
        long unresolved = 0;
        for (SiteObservation site : sites.values()) {
            if (site.targets.isEmpty()) {
                unresolved++;
                addEdge(edges, site, site.site.declared, "declared", site.declarations);
            } else {
                for (Map.Entry<String, Long> target : site.targets.entrySet()) {
                    addEdge(edges, site, target.getKey(), "observed", target.getValue());
                }
            }
        }
        Map<String, Map<String, Object>> nodes = new TreeMap<>();
        List<Map<String, Object>> edgeData = new ArrayList<>();
        long observedEdges = 0, declaredEdges = 0;
        for (Edge edge : edges.values()) {
            nodes.put(edge.caller, node(edge.caller, codeMethods));
            nodes.put(edge.callee, node(edge.callee, codeMethods));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("caller", edge.caller); item.put("callee", edge.callee); item.put("kind", edge.kind);
            item.put("count", edge.count); item.put("callSites", edge.sites);
            edgeData.add(item);
            if ("observed".equals(edge.kind)) observedEdges++; else declaredEdges++;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("observedEdges", observedEdges); stats.put("declaredEdges", declaredEdges);
        stats.put("unresolvedSites", unresolved); stats.put("callSites", sites.size());
        stats.put("records", events.size());
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("available", hasData());
        graph.put("nodes", new ArrayList<>(nodes.values())); graph.put("edges", edgeData); graph.put("stats", stats);
        graph.put("note", "This dynamic call graph contains relationships recorded during execution. "
                + "Confirmed edges match a callee entry to its direct caller on the same thread and, for instance calls, the same receiver. "
                + "Callsites without a confirmed target retain their declared target. Constructors are declared-only. "
                + "Each callsite/target relationship is recorded once per process; distinct-record counts are not invocation frequencies. "
                + "Uninstrumented code and missing logs can leave targets unconfirmed."
                + (legacy ? " Legacy CALL records contain declared targets only." : ""));
        return graph;
    }

    private static void addEdge(Map<List<String>, Edge> edges, SiteObservation observation, String callee, String kind, long count) {
        CallSite site = observation.site;
        List<String> key = Arrays.asList(site.caller, callee, kind);
        Edge edge = edges.computeIfAbsent(key, ignored -> new Edge(site.caller, callee, kind));
        edge.count += count;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", observation.legacy ? "legacy" : Integer.toString(site.line));
        detail.put("declared", site.declared); detail.put("kind", site.kind);
        edge.sites.add(detail);
    }

    private static Map<String, Object> node(String signature, Set<String> codeMethods) {
        String owner = className(signature), method = subSignature(signature);
        int firstSpace = method.indexOf(' ');
        String shortMethod = firstSpace < 0 ? method : method.substring(firstSpace + 1);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", signature); node.put("className", owner);
        node.put("label", owner.substring(owner.lastIndexOf('.') + 1) + "." + shortMethod);
        node.put("inReport", codeMethods.contains(signature));
        return node;
    }

    static String className(String signature) {
        int colon = signature.indexOf(": ");
        return signature.startsWith("<") && colon > 1 ? signature.substring(1, colon) : "";
    }

    static String subSignature(String signature) {
        int colon = signature.indexOf(": ");
        return colon > 0 && signature.endsWith(">") ? signature.substring(colon + 2, signature.length() - 1) : "";
    }

    private static String decode(String encoded) {
        try { return URLDecoder.decode(encoded, "UTF-8"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private static final class SiteObservation {
        final CallSite site;
        final boolean legacy;
        long declarations;
        final Map<String, Long> targets = new TreeMap<>();
        SiteObservation(CallSite site, boolean legacy) { this.site = site; this.legacy = legacy; }
    }

    private static final class Edge {
        final String caller, callee, kind;
        long count;
        final List<Map<String, Object>> sites = new ArrayList<>();
        Edge(String caller, String callee, String kind) { this.caller = caller; this.callee = callee; this.kind = kind; }
    }
}
