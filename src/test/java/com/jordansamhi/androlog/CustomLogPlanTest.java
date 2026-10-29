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
        point.variables.get(0).type = "java.lang.Object"; expectFailure(plan::validate, "supported local");
        point.variables.get(0).type = "int"; point.message = "literal"; expectFailure(plan::validate, "placeholder");
        point.message = "{$i0}"; point.variables.add(new CustomLogPlan.Variable("$i0", "int")); expectFailure(plan::validate, "only be listed once");
    }

    private static String repeat(String value, int count) { return String.join("", Collections.nCopies(count, value)); }
    private interface Action { void run() throws IOException; }
    private static void expectFailure(Action action, String message) throws Exception {
        try { action.run(); fail("Expected IOException"); } catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
    }
}
