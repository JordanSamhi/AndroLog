package com.jordansamhi.androlog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import soot.*;
import soot.jimple.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.Assert.*;

public class CodeCatalogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @After public void cleanup() { G.reset(); }

    @Test public void compactCatalogPreservesStatementLocationsAndVariableAvailability() throws Exception {
        G.reset();
        SootClass owner = new SootClass("example.Catalog", Modifier.PUBLIC);
        Scene.v().addClass(owner);
        SootMethod method = new SootMethod("run", Collections.emptyList(), VoidType.v(), Modifier.PUBLIC | Modifier.STATIC);
        owner.addMethod(method);
        JimpleBody body = Jimple.v().newBody(method); method.setActiveBody(body);
        for (int i = 0; i < 12; i++) {
            Local local = Jimple.v().newLocal(String.format(Locale.ROOT, "value%02d", i), IntType.v());
            body.getLocals().add(local); body.getUnits().add(Jimple.v().newAssignStmt(local, IntConstant.v(i)));
        }
        body.getUnits().add(Jimple.v().newReturnVoidStmt());
        CodeCatalog.Method description = CodeCatalog.describe(body);
        assertEquals(12, description.locals.size());
        CodeCatalog catalog = new CodeCatalog(String.join("", Collections.nCopies(64, "a")), Collections.singletonList(description));
        Path file = temporary.getRoot().toPath().resolve("catalog.json"); catalog.write(file);
        CodeCatalog.Method restored = CodeCatalog.read(file).methods.get(0);
        LocalVariableAvailability available = new LocalVariableAvailability(body);
        int index = 0;
        for (Unit unit : body.getUnits()) {
            CodeCatalog.Statement statement = restored.statements.get(index++);
            assertEquals(index, statement.index); assertEquals(unit.toString(), statement.text);
            assertTrue(statement.selectable); assertNull(statement.locals);
            BitSet bits = BitSet.valueOf(Base64.getDecoder().decode(statement.localMask));
            List<String> actual = new ArrayList<>();
            for (int bit = bits.nextSetBit(0); bit >= 0; bit = bits.nextSetBit(bit + 1)) actual.add(restored.locals.get(bit).name);
            List<String> expected = new ArrayList<>();
            for (CustomLogPlan.Variable variable : available.at(unit)) expected.add(variable.name);
            assertEquals(expected, actual);
        }
        JsonObject json = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject jsonMethod = json.getAsJsonArray("methods").get(0).getAsJsonObject();
        assertEquals(12, jsonMethod.getAsJsonArray("locals").size());
        for (com.google.gson.JsonElement statement : jsonMethod.getAsJsonArray("statements")) assertFalse(statement.getAsJsonObject().has("locals"));
    }
}
