package com.jordansamhi.androlog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.jordansamhi.androlog.instrumentation.CustomLogInstrumenter;
import com.jordansamhi.androlog.instrumentation.MethodCallInstrumenter;
import com.jordansamhi.androlog.utils.PackageScope;
import com.jordansamhi.androspecter.files.LibrariesManager;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.Stmt;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** AndroLog's own Jimple code view, captured before any probes are inserted. */
public final class CodeCatalog {
    public String apkSha256;
    public List<Method> methods = new ArrayList<>();
    public CodeCatalog() { }
    public CodeCatalog(String apkSha256, List<Method> methods) { this.apkSha256 = apkSha256; this.methods = new ArrayList<>(methods); }

    public static final class Method {
        public String className;
        public String signature;
        public List<Statement> statements = new ArrayList<>();
        public Method() { }
        public Method(String className, String signature, List<Statement> statements) {
            this.className = className; this.signature = signature; this.statements = new ArrayList<>(statements);
        }
    }
    public static final class Statement {
        public int index;
        public String text;
        public boolean selectable;
        public List<CustomLogPlan.Variable> locals = new ArrayList<>();
        public Statement() { }
        public Statement(int index, String text, boolean selectable) { this.index = index; this.text = text; this.selectable = selectable; }
    }

    static CodeCatalog inspect(String sha256, boolean includeLibraries, String targetPackage) {
        Map<String, Method> collected = new ConcurrentHashMap<>();
        PackManager.v().getPack("jtp").add(new Transform("jtp.andrologCodeCatalog", new BodyTransformer() {
            @Override protected void internalTransform(Body body, String phase, Map<String, String> options) {
                SootMethod method = body.getMethod(); SootClass owner = method.getDeclaringClass();
                if (!method.isConcrete() || owner.getName().equals(Constants.LOG_CHECKER_CLASS)
                        || owner.getName().startsWith(MethodCallInstrumenter.RUNTIME_CLASS)
                        || owner.getName().equals(CustomLogInstrumenter.RUNTIME_CLASS)
                        || !PackageScope.includes(targetPackage, owner.getPackageName())
                        || (!includeLibraries && LibrariesManager.v().isLibrary(owner))) return;
                List<Statement> statements = new ArrayList<>(); int index = 0;
                LocalVariableAvailability available = new LocalVariableAvailability(body);
                for (Unit unit : body.getUnits()) {
                    Statement statement = new Statement(++index, unit.toString(), CustomLogInstrumenter.isEligible((Stmt) unit));
                    if (statement.selectable) statement.locals = available.at(unit);
                    statements.add(statement);
                }
                collected.put(method.getSignature(), new Method(owner.getName(), method.getSignature(), statements));
            }
        }));
        PackManager.v().runPacks();
        List<Method> methods = new ArrayList<>(collected.values());
        methods.sort(Comparator.comparing((Method method) -> method.className).thenComparing(method -> method.signature));
        return new CodeCatalog(sha256, methods);
    }

    public static CodeCatalog read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            CodeCatalog catalog = new Gson().fromJson(reader, CodeCatalog.class);
            if (catalog == null || catalog.apkSha256 == null || !catalog.apkSha256.matches("[a-f0-9]{64}") || catalog.methods == null) {
                throw new IOException("The APK code catalog is incomplete. Browse the APK again.");
            }
            for (Method method : catalog.methods) {
                if (method == null || method.className == null || method.signature == null || method.statements == null) throw new IOException("The APK code catalog contains an incomplete method.");
                int index = 0;
                for (Statement statement : method.statements) {
                    if (statement == null || statement.index != ++index || statement.text == null) throw new IOException("The APK code catalog contains an invalid statement.");
                }
            }
            return catalog;
        } catch (JsonParseException error) { throw new IOException("Cannot read the APK code catalog: " + error.getMessage(), error); }
    }

    public void write(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) { new GsonBuilder().setPrettyPrinting().create().toJson(this, writer); }
    }
}
