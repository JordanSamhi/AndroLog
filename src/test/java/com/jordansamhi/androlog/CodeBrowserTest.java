package com.jordansamhi.androlog;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import soot.*;
import soot.jimple.*;
import soot.options.Options;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class CodeBrowserTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path apk;
    private final List<SootMethod> methods = new ArrayList<>();
    private final Map<String, AtomicInteger> loads = new HashMap<>();

    @Before public void setup() throws Exception {
        G.reset(); Options.v().set_allow_phantom_refs(true);
        Options.v().set_src_prec(Options.src_prec_class);
        // These numeric fixtures need exception hierarchies, not bytecode from the
        // host JDK (which may be newer than the pinned Soot class-file reader).
        for (String name : Scene.v().getBasicClasses()) if (name.startsWith("java.")) {
            try { hierarchy(Class.forName(name, false, getClass().getClassLoader())); }
            catch (ClassNotFoundException ignored) { /* Platform-specific optional basic class. */ }
        }
        apk = temporary.newFile("source.apk").toPath();
        Files.write(apk, new byte[] {1, 2, 3});
    }
    @After public void cleanup() { G.reset(); }

    @Test public void outlineAndClassBrowsingDoNotLoadOtherClassesOrComputeVariableRows() throws Exception {
        SootMethod selected = method("example.Opened", "first"), sameClass = method("example.Opened", "second"), untouched = method("example.Other", "run");
        CodeBrowser.Session session = session();
        CodeBrowser.Outline outline = session.outline();
        assertTrue(outline.lazy); assertEquals(3, outline.methods.size());
        assertFalse(selected.hasActiveBody()); assertFalse(sameClass.hasActiveBody()); assertFalse(untouched.hasActiveBody());
        CodeBrowser.ClassCode code = session.readClass("example.Opened");
        assertEquals(2, code.methods.size()); assertFalse(untouched.hasActiveBody());
        assertEquals(1, loads.get(selected.getSignature()).get());
        assertSame(code, session.readClass("example.Opened"));
        String json = new Gson().toJson(code);
        assertFalse(json.contains("locals")); assertFalse(json.contains("localMask"));
        // The pre-jtp cleanup removes the redundant local copy, preserving capture indices.
        assertEquals(3, code.methods.get(0).statements.size());
        assertEquals("input := @parameter0: int", code.methods.get(0).statements.get(0).text);
        assertFalse(code.methods.get(0).statements.get(0).selectable);
        assertEquals("return result", code.methods.get(0).statements.get(2).text);
        assertEquals(3, code.methods.get(0).statements.get(2).index);
    }

    @Test public void variablesLoadOnlyTheRequestedMethodAndCacheItsResult() throws Exception {
        SootMethod selected = method("example.Only", "selected"), other = method("example.Only", "other");
        CodeBrowser.Session session = session();
        CodeBrowser.Variables variables = session.readVariables(selected.getSignature());
        assertEquals(selected.getSignature(), variables.signature);
        assertFalse(other.hasActiveBody());
        assertSame(variables, session.readVariables(selected.getSignature()));
        assertEquals(1, loads.get(selected.getSignature()).get());
        List<String> names = new ArrayList<>(); for (CustomLogPlan.Variable variable : variables.locals) names.add(variable.name);
        assertEquals(Arrays.asList("input", "result"), names);
        assertEquals("", variables.statements.get(0).localMask);
        assertEquals(BitSet.valueOf(new byte[] {1}), BitSet.valueOf(Base64.getDecoder().decode(variables.statements.get(1).localMask)));
        assertEquals(BitSet.valueOf(new byte[] {3}), BitSet.valueOf(Base64.getDecoder().decode(variables.statements.get(2).localMask)));
        session.readClass("example.Only");
        assertEquals(1, loads.get(selected.getSignature()).get());
    }

    @Test public void protocolCorrelatesRepliesRecoversFromInvalidRequestsAndEndsOnEof() throws Exception {
        SootMethod selected = method("example.Protocol", "run");
        String requests = "{\"id\":1,\"action\":\"unknown\"}\n"
                + "{\"id\":2,\"action\":\"class\",\"className\":\"example.Protocol\"}\n"
                + "{\"id\":3,\"action\":\"variables\",\"method\":" + new Gson().toJson(selected.getSignature()) + "}\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodeBrowser.serve(session(), new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)), new PrintStream(bytes, true, "UTF-8"));
        List<JsonObject> replies = replies(bytes);
        assertEquals(4, replies.size());
        assertEquals(0, replies.get(0).get("id").getAsInt());
        assertTrue(replies.get(0).getAsJsonObject("result").get("lazy").getAsBoolean());
        assertEquals(1, replies.get(1).get("id").getAsInt()); assertTrue(replies.get(1).has("error"));
        assertEquals("example.Protocol", replies.get(2).getAsJsonObject("result").get("className").getAsString());
        assertEquals(selected.getSignature(), replies.get(3).getAsJsonObject("result").get("signature").getAsString());
    }

    @Test public void aChangedApkInvalidatesCachedCodeEvenIfTheOriginalContentsAreRestored() throws Exception {
        method("example.Stale", "run"); CodeBrowser.Session session = session();
        JsonObject request = JsonParser.parseString("{\"action\":\"class\",\"className\":\"example.Stale\"}").getAsJsonObject();
        assertNotNull(session.handle(request));
        Files.write(apk, new byte[] {9, 8, 7});
        try { session.handle(request); fail("Changed APK must invalidate cached code"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("APK changed")); }
        Files.write(apk, new byte[] {1, 2, 3});
        try { session.handle(request); fail("A stale session must remain invalid"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("APK changed")); }
    }

    @Test public void startupErrorsUseTheInitialProtocolId() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(), errors = new ByteArrayOutputStream();
        assertEquals(1, CodeBrowser.execute(new String[] {"--unknown", "value"}, new ByteArrayInputStream(new byte[0]),
                new PrintStream(bytes, true, "UTF-8"), new PrintStream(errors, true, "UTF-8")));
        JsonObject reply = replies(bytes).get(0);
        assertEquals(0, reply.get("id").getAsInt()); assertTrue(reply.has("error"));
    }

    private CodeBrowser.Session session() throws Exception { return new CodeBrowser.Session(apk, CustomLogPlan.sha256(apk), methods); }

    private static SootClass hierarchy(Class<?> type) {
        SootClass existing = Scene.v().getSootClassUnsafe(type.getName(), false);
        if (existing != null) return existing;
        SootClass created = new SootClass(type.getName(), type.getModifiers());
        Scene.v().addClass(created);
        if (type.getSuperclass() != null) created.setSuperclass(hierarchy(type.getSuperclass()));
        return created;
    }

    private SootMethod method(String className, String name) {
        SootClass owner = Scene.v().getSootClassUnsafe(className, false);
        if (owner == null) {
            owner = new SootClass(className, Modifier.PUBLIC); owner.setSuperclass(Scene.v().getSootClass("java.lang.Object"));
            Scene.v().addClass(owner); owner.setApplicationClass();
        }
        SootMethod method = new SootMethod(name, Collections.singletonList(IntType.v()), IntType.v(), Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method); methods.add(method);
        AtomicInteger count = new AtomicInteger(); loads.put(method.getSignature(), count);
        method.setSource((current, phase) -> {
            count.incrementAndGet(); JimpleBody body = Jimple.v().newBody(current);
            Local input = Jimple.v().newLocal("input", IntType.v()), copy = Jimple.v().newLocal("$copy", IntType.v()), result = Jimple.v().newLocal("result", IntType.v());
            Collections.addAll(body.getLocals(), input, copy, result);
            Collections.addAll(body.getUnits(), Jimple.v().newIdentityStmt(input, Jimple.v().newParameterRef(IntType.v(), 0)),
                    Jimple.v().newAssignStmt(copy, input), Jimple.v().newAssignStmt(result, Jimple.v().newAddExpr(copy, IntConstant.v(1))), Jimple.v().newReturnStmt(result));
            return body;
        });
        return method;
    }

    private static List<JsonObject> replies(ByteArrayOutputStream bytes) throws Exception {
        List<JsonObject> result = new ArrayList<>();
        for (String line : bytes.toString("UTF-8").split("\n")) {
            assertTrue(line.startsWith(CodeBrowser.PREFIX));
            result.add(JsonParser.parseString(line.substring(CodeBrowser.PREFIX.length())).getAsJsonObject());
        }
        return result;
    }
}
