package com.jordansamhi.androlog;

import com.google.gson.*;
import com.jordansamhi.androlog.instrumentation.CustomLogInstrumenter;
import com.jordansamhi.androlog.instrumentation.MethodCallInstrumenter;
import com.jordansamhi.androspecter.utils.Constants;
import soot.*;
import soot.jimple.Stmt;
import soot.options.Options;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One APK session: list method names first, then read only requested classes and locals. */
public final class CodeBrowser {
    static final String PREFIX = "@@ANDROLOG_CODE ";
    private static final Gson JSON = new Gson();

    private CodeBrowser() { }

    public static void main(String[] arguments) {
        int status = execute(arguments, System.in, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    static int execute(String[] arguments, InputStream input, PrintStream out, PrintStream err) {
        try {
            Map<String, String> options = options(arguments);
            Path apk = Paths.get(required(options, "--apk")).toAbsolutePath().normalize();
            Path platforms = Paths.get(required(options, "--platforms")).toAbsolutePath().normalize();
            Path output = Paths.get(required(options, "--output")).toAbsolutePath().normalize();
            if (!Files.isRegularFile(apk) || !Files.isReadable(apk)) throw new IOException("Cannot read the original APK.");
            if (!Files.isDirectory(platforms)) throw new IOException("Choose the Android SDK platforms folder.");
            Files.createDirectories(output);
            String hash = CustomLogPlan.sha256(apk);
            ApkAnalysisSetup.initialize(platforms.toString(), apk, output);
            Options.v().set_wrong_staticness(Options.wrong_staticness_ignore);
            List<SootMethod> methods = new ArrayList<>();
            for (SootClass owner : Scene.v().getApplicationClasses()) {
                if (owner.getName().equals(Constants.LOG_CHECKER_CLASS)
                        || owner.getName().startsWith(MethodCallInstrumenter.RUNTIME_CLASS)
                        || owner.getName().equals(CustomLogInstrumenter.RUNTIME_CLASS)) continue;
                for (SootMethod method : owner.getMethods()) if (method.isConcrete()) methods.add(method);
            }
            Session session = new Session(apk, hash, methods);
            session.verifyUnchanged();
            serve(session, input, out);
            return 0;
        } catch (Exception | LinkageError error) {
            reply(out, 0, null, message(error));
            err.println("Error: " + message(error)); err.flush();
            return 1;
        }
    }

    static void serve(Session session, InputStream input, PrintStream out) throws IOException {
        reply(out, 0, session.outline(), null);
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            long id = -1;
            try {
                if (line.length() > 65536) throw new IOException("The code browser request is too large.");
                JsonElement parsed = JsonParser.parseString(line);
                if (!parsed.isJsonObject()) throw new IOException("Invalid code browser request.");
                JsonObject request = parsed.getAsJsonObject();
                JsonElement identifier = request.get("id");
                if (identifier == null || !identifier.isJsonPrimitive() || !identifier.getAsJsonPrimitive().isNumber()
                        || !identifier.getAsString().matches("[1-9][0-9]{0,15}")) throw new IOException("Invalid code browser request ID.");
                id = identifier.getAsLong();
                if (id > 9007199254740991L) throw new IOException("Invalid code browser request ID.");
                reply(out, id, session.handle(request), null);
            } catch (Exception | LinkageError error) {
                reply(out, id, null, message(error));
            }
        }
    }

    static final class Session {
        private final Path apk;
        private final String hash;
        private final Map<String, SootMethod> methods = new LinkedHashMap<>();
        private final Map<String, List<SootMethod>> classes = new LinkedHashMap<>();
        private final Map<String, Body> bodies = new HashMap<>();
        private final Map<String, MethodCode> code = new HashMap<>();
        private final Map<String, ClassCode> classCode = new HashMap<>();
        private final Map<String, Variables> variables = new HashMap<>();
        private final Map<String, String> failed = new HashMap<>();
        private boolean stale;

        Session(Path apk, String hash, Collection<SootMethod> available) {
            this.apk = apk; this.hash = hash;
            List<SootMethod> sorted = new ArrayList<>(available);
            sorted.sort(Comparator.comparing((SootMethod method) -> method.getDeclaringClass().getName()).thenComparing(method -> method.getSignature()));
            for (SootMethod method : sorted) {
                methods.put(method.getSignature(), method);
                classes.computeIfAbsent(method.getDeclaringClass().getName(), key -> new ArrayList<>()).add(method);
            }
        }

        Outline outline() {
            List<MethodOutline> entries = new ArrayList<>();
            for (SootMethod method : methods.values()) entries.add(new MethodOutline(method));
            return new Outline(hash, entries);
        }

        Object handle(JsonObject request) throws IOException {
            verifyUnchanged();
            Object result;
            String action = requiredString(request, "action");
            if ("class".equals(action)) result = readClass(requiredString(request, "className"));
            else if ("variables".equals(action)) result = readVariables(requiredString(request, "method"));
            else throw new IOException("Unknown code browser action.");
            verifyUnchanged();
            return result;
        }

        void verifyUnchanged() throws IOException {
            if (!stale) {
                try { stale = !hash.equals(CustomLogPlan.sha256(apk)); }
                catch (IOException error) { stale = true; }
            }
            if (stale) throw new IOException("The APK changed while browsing its code. Close the code browser and choose the APK again.");
        }

        ClassCode readClass(String className) throws IOException {
            List<SootMethod> members = classes.get(className);
            if (members == null) throw new IOException("This class is not in the selected APK.");
            ClassCode cached = classCode.get(className);
            if (cached != null) return cached;
            List<MethodCode> result = new ArrayList<>();
            for (SootMethod method : members) {
                String signature = method.getSignature();
                MethodCode entry = code.get(signature);
                if (entry == null) {
                    List<Statement> statements = new ArrayList<>(); int index = 0;
                    for (Unit unit : body(method).getUnits()) statements.add(new Statement(++index, unit.toString(), CustomLogInstrumenter.isEligible((Stmt) unit)));
                    entry = new MethodCode(method, statements); code.put(signature, entry);
                }
                result.add(entry);
            }
            ClassCode created = new ClassCode(className, result); classCode.put(className, created); return created;
        }

        Variables readVariables(String signature) throws IOException {
            SootMethod method = methods.get(signature);
            if (method == null) throw new IOException("This method is not in the selected APK.");
            Variables cached = variables.get(signature);
            if (cached != null) return cached;
            Body body = body(method);
            LocalVariableAvailability available = new LocalVariableAvailability(body);
            List<Availability> statements = new ArrayList<>(); int index = 0;
            for (Unit unit : body.getUnits()) statements.add(new Availability(++index,
                    CustomLogInstrumenter.isEligible((Stmt) unit) ? available.maskAt(unit) : ""));
            Variables created = new Variables(signature, available.locals(), statements); variables.put(signature, created); return created;
        }

        private Body body(SootMethod method) throws IOException {
            String signature = method.getSignature();
            if (failed.containsKey(signature)) throw new IOException(failed.get(signature));
            Body cached = bodies.get(signature); if (cached != null) return cached;
            try {
                Body body = method.retrieveActiveBody();
                normalize(body);
                bodies.put(signature, body); return body;
            } catch (RuntimeException | LinkageError error) {
                String message = "Cannot read " + signature + ": " + message(error);
                failed.put(signature, message); throw new IOException(message, error);
            }
        }
    }

    static void normalize(Body body) {
        // Match Soot 4.6 PackManager.runBodyPacks immediately before our jtp capture.
        // retrieveActiveBody alone leaves copy/branch/dead-code cleanup unapplied.
        for (String phase : Arrays.asList("jb.cp", "jb.cbf", "jb.uce", "jb.dae", "jb.cp-ule")) {
            PackManager.v().getTransform(phase).apply(body);
        }
        ApkAnalysisSetup.preserveInterfaceAssignments(body);
    }

    static final class Outline {
        final String apkSha256; final boolean lazy = true; final List<MethodOutline> methods;
        Outline(String hash, List<MethodOutline> methods) { this.apkSha256 = hash; this.methods = methods; }
    }
    static class MethodOutline {
        final String className, signature;
        MethodOutline(SootMethod method) { className = method.getDeclaringClass().getName(); signature = method.getSignature(); }
    }
    static final class MethodCode extends MethodOutline {
        final List<Statement> statements;
        MethodCode(SootMethod method, List<Statement> statements) { super(method); this.statements = statements; }
    }
    static final class Statement {
        final int index; final String text; final boolean selectable;
        Statement(int index, String text, boolean selectable) { this.index = index; this.text = text; this.selectable = selectable; }
    }
    static final class ClassCode {
        final String className; final List<MethodCode> methods;
        ClassCode(String className, List<MethodCode> methods) { this.className = className; this.methods = methods; }
    }
    static final class Variables {
        final String signature; final List<CustomLogPlan.Variable> locals; final List<Availability> statements;
        Variables(String signature, List<CustomLogPlan.Variable> locals, List<Availability> statements) {
            this.signature = signature; this.locals = locals; this.statements = statements;
        }
    }
    static final class Availability {
        final int index; final String localMask;
        Availability(int index, String localMask) { this.index = index; this.localMask = localMask; }
    }

    private static Map<String, String> options(String[] arguments) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < arguments.length; i++) {
            String key = arguments[i];
            if (!Arrays.asList("--apk", "--platforms", "--output").contains(key)) throw new IOException("Unknown code browser option: " + key);
            if (values.containsKey(key)) throw new IOException("Duplicate code browser option: " + key);
            if (++i == arguments.length) throw new IOException("Missing value for " + key);
            values.put(key, arguments[i]);
        }
        return values;
    }

    private static String required(Map<String, String> options, String key) throws IOException {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) throw new IOException("Missing " + key + ".");
        return value;
    }

    private static String requiredString(JsonObject request, String key) throws IOException {
        JsonElement value = request.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isEmpty() || value.getAsString().length() > 32768) throw new IOException("Invalid code browser " + key + ".");
        return value.getAsString();
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null ? error.getClass().getSimpleName() : value.replace('\r', ' ').replace('\n', ' ');
    }

    private static void reply(PrintStream out, long id, Object result, String error) {
        Map<String, Object> response = new LinkedHashMap<>(); response.put("id", id);
        if (error == null) response.put("result", result); else response.put("error", error);
        out.println(PREFIX + JSON.toJson(response)); out.flush();
    }
}
