package com.jordansamhi.androlog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class CustomLogPlanTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void planRoundTripPreservesLiteralMessageAndRejectsChangedApk() throws Exception {
        Path apk = temporary.newFile("original.apk").toPath();
        Files.write(apk, new byte[]{1, 2, 3});
        String message = "reached this point: \"quoted\" / café / {not an expression}";
        CustomLogPlan plan = new CustomLogPlan(CustomLogPlan.sha256(apk), Collections.singletonList(
                new CustomLogPlan.Point("<example.App: void run()>", 2, "return", message)));
        Path output = temporary.newFile("points.json").toPath(); plan.write(output);
        CustomLogPlan restored = CustomLogPlan.read(output); restored.verifyApk(apk);
        assertEquals(message, restored.points.get(0).message);
        Files.write(apk, new byte[]{4, 5, 6});
        expectFailure(() -> restored.verifyApk(apk), "APK has changed");
    }

    @Test public void invalidMessagesAndDuplicatePointsFailBeforeInstrumentation() throws Exception {
        for (String message : Arrays.asList("", "  ", "one\ntwo", "one\rtwo", "zero\0byte", repeat("é", 501))) {
            expectFailure(() -> CustomLogPlan.validateMessage(message), "");
        }
        CustomLogPlan.validateMessage(repeat("é", 500));
        CustomLogPlan.Point point = new CustomLogPlan.Point("<example.App: void run()>", 1, "return", "message");
        CustomLogPlan duplicate = new CustomLogPlan(repeat("a", 64), Arrays.asList(point, point));
        expectFailure(duplicate::validate, "one custom message");
    }

    @Test public void malformedPlansAndCatalogsFailWithReadableErrors() throws Exception {
        Path file = temporary.newFile("invalid.json").toPath();
        Files.write(file, "{broken".getBytes(StandardCharsets.UTF_8));
        expectFailure(() -> CustomLogPlan.read(file), "Cannot read");
        expectFailure(() -> CodeCatalog.read(file), "Cannot read");
        Files.write(file, "null".getBytes(StandardCharsets.UTF_8));
        expectFailure(() -> CustomLogPlan.read(file), "empty");
        expectFailure(() -> CodeCatalog.read(file), "incomplete");
    }

    @Test public void variableReferencesRoundTripAndRejectUnsupportedOrMissingPlaceholders() throws Exception {
        CustomLogPlan.Point point = new CustomLogPlan.Point("<example.App: void run()>", 2, "return", "value={$i0}");
        point.variables.add(new CustomLogPlan.Variable("$i0", "int"));
        CustomLogPlan plan = new CustomLogPlan(repeat("a", 64), Collections.singletonList(point));
        Path file = temporary.newFile("variables.json").toPath(); plan.write(file);
        CustomLogPlan restored = CustomLogPlan.read(file);
        assertEquals("$i0", restored.points.get(0).variables.get(0).name);
        assertEquals("int", restored.points.get(0).variables.get(0).type);
        for (String type : Arrays.asList("java.lang.Object", "example.Custom", "a", "int[]", "java.lang.Object[][]")) {
            point.variables.get(0).type = type; plan.write(file);
            assertEquals(type, CustomLogPlan.read(file).points.get(0).variables.get(0).type);
        }
        point.variables.get(0).type = "char"; expectFailure(plan::validate, "supported local");
        point.variables.get(0).type = "int"; point.message = "literal"; expectFailure(plan::validate, "placeholder");
        point.message = "{$i0}"; point.variables.add(new CustomLogPlan.Variable("$i0", "int")); expectFailure(plan::validate, "only be listed once");
    }

    @Test public void explicitKindsRoundTripAndKeepLiteralBracesAsStatementMarkers() throws Exception {
        CustomLogPlan.Point marker = new CustomLogPlan.Point("<example.App: void run()>", 1, "nop", "reached {literal}");
        marker.kind = "marker";
        CustomLogPlan.Point value = new CustomLogPlan.Point("<example.App: void run()>", 2, "return", "{$r2}");
        value.kind = "value"; value.variables.add(new CustomLogPlan.Variable("$r2", "example.Object"));
        CustomLogPlan plan = new CustomLogPlan(repeat("a", 64), Arrays.asList(marker, value));
        Path file = temporary.newFile("kinds.json").toPath(); plan.write(file);
        CustomLogPlan restored = CustomLogPlan.read(file);
        assertEquals("marker", restored.points.get(0).kind);
        assertEquals("reached {literal}", restored.points.get(0).message);
        assertTrue(restored.points.get(0).variables.isEmpty());
        assertEquals("value", restored.points.get(1).kind);
        assertEquals("{$r2}", restored.points.get(1).message);
        assertEquals("example.Object", restored.points.get(1).variables.get(0).type);
    }

    @Test public void explicitKindsRejectContradictoryBindingsButLegacyMixedMessagesStillWork() throws Exception {
        CustomLogPlan.Point point = new CustomLogPlan.Point("<example.App: void run()>", 2, "return", "{first}");
        CustomLogPlan plan = new CustomLogPlan(repeat("a", 64), Collections.singletonList(point));
        point.kind = "invalid"; expectFailure(plan::validate, "statement reached, a custom message, or a variable value");
        point.kind = "value"; expectFailure(plan::validate, "exactly one variable");
        point.variables.add(new CustomLogPlan.Variable("first", "int")); plan.validate();
        point.kind = "marker"; expectFailure(plan::validate, "cannot bind variables");
        point.kind = "value"; point.message = "value={first}"; expectFailure(plan::validate, "additional message text");
        point.variables.add(new CustomLogPlan.Variable("second", "java.lang.String")); point.message = "{first} {second}";
        expectFailure(plan::validate, "exactly one variable");
        point.kind = null; plan.validate();
        Path file = temporary.newFile("legacy-values.json").toPath(); plan.write(file);
        CustomLogPlan restored = CustomLogPlan.read(file);
        assertNull(restored.points.get(0).kind); assertEquals(2, restored.points.get(0).variables.size());
        assertEquals("{first} {second}", restored.points.get(0).message);
    }

    private static String repeat(String value, int count) { return String.join("", Collections.nCopies(count, value)); }

    @Test public void reachPointsNeedNoUserInputAndMessagesRemainLiteral() throws Exception {
        CustomLogPlan.Point reached = new CustomLogPlan.Point("<example.App: void run()>", 1, "nop", null);
        reached.kind = "reached";
        CustomLogPlan.Point message = new CustomLogPlan.Point("<example.App: void run()>", 2, "return", "literal {braces}");
        message.kind = "message";
        CustomLogPlan plan = new CustomLogPlan(repeat("a", 64), Arrays.asList(reached, message));
        Path file = temporary.newFile("three-kinds.json").toPath(); plan.write(file);
        CustomLogPlan restored = CustomLogPlan.read(file);
        assertEquals("reached", restored.points.get(0).kind); assertNull(restored.points.get(0).message);
        assertEquals("message", restored.points.get(1).kind); assertEquals("literal {braces}", restored.points.get(1).message);
        reached.message = ""; plan.validate();
        reached.message = "not allowed"; expectFailure(plan::validate, "do not take a message");
        reached.message = null; reached.variables.add(new CustomLogPlan.Variable("value", "int"));
        expectFailure(plan::validate, "cannot bind variables"); reached.variables.clear();
        message.variables.add(new CustomLogPlan.Variable("braces", "java.lang.String")); expectFailure(plan::validate, "cannot bind variables");
        message.variables.clear(); message.message = ""; expectFailure(plan::validate, "Enter a message");
    }
    private interface Action { void run() throws IOException; }
    private static void expectFailure(Action action, String message) throws Exception {
        try { action.run(); fail("Expected IOException"); } catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
    }
}
