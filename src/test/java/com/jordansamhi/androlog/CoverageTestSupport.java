package com.jordansamhi.androlog;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

/** Isolates legacy singleton state without adding test-only production APIs. */
final class CoverageTestSupport {
    private CoverageTestSupport() {
    }

    static void resetBuilders() throws Exception {
        resetSingleton(SummaryBuilder.class);
        resetSingleton(SummaryLogBuilder.class);
    }

    private static void resetSingleton(Class<?> type) throws Exception {
        Field instance = type.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @SuppressWarnings("unchecked")
    static void addCodeMethod(SummaryBuilder baseline, CoverageMethod method) throws Exception {
        Field field = SummaryBuilder.class.getDeclaredField("codeMethods");
        field.setAccessible(true);
        ((Map<String, CoverageMethod>) field.get(baseline)).put(method.getSignature(), method);
    }

    static void addBaseline(SummaryBuilder baseline, String type, String payload) {
        baseline.getVisitedComponents().add(type + ("statements".equals(type) ? "STATEMENT=" : "") + payload);
        baseline.getSummary().merge(type, 1, Integer::sum);
    }

    @SuppressWarnings("unchecked")
    static void addCallSite(SummaryBuilder baseline, CallSite site, String... methods) throws Exception {
        Field sites = SummaryBuilder.class.getDeclaredField("callSites");
        sites.setAccessible(true);
        ((Map<String, CallSite>) sites.get(baseline)).put(site.key(), site);
        Field targets = SummaryBuilder.class.getDeclaredField("graphMethods");
        targets.setAccessible(true);
        for (String method : methods) ((Set<String>) targets.get(baseline)).add(method);
    }
}
