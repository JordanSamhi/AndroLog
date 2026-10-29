package com.jordansamhi.androlog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of a method's Jimple body for a detailed coverage report.
 */
public final class CoverageMethod {
    private final String className;
    private final String signature;
    private final String componentType;
    private final List<Statement> statements;

    public CoverageMethod(String className, String signature, String componentType, List<Statement> statements) {
        this.className = className;
        this.signature = signature;
        this.componentType = componentType;
        this.statements = Collections.unmodifiableList(new ArrayList<>(statements));
    }

    public String getClassName() {
        return className;
    }

    public String getSignature() {
        return signature;
    }

    /**
     * Returns the Android component type, or null for an ordinary class.
     */
    public String getComponentType() {
        return componentType;
    }

    public List<Statement> getStatements() {
        return statements;
    }

    /**
     * One original Jimple unit, including units excluded from statement coverage.
     * The line is its one-based position in the method body, not a Java source line.
     */
    public static final class Statement {
        private final int line;
        private final String text;
        private final boolean eligible;
        private final String coveragePayload;

        public Statement(int line, String text, boolean eligible) {
            this(line, text, eligible, null);
        }

        /** Probe identity is independent of display positions after DEX has been reloaded. */
        public Statement(int line, String text, boolean eligible, String coveragePayload) {
            this.line = line;
            this.text = text;
            this.eligible = eligible;
            this.coveragePayload = coveragePayload;
        }

        public int getLine() {
            return line;
        }

        public String getText() {
            return text;
        }

        public boolean isEligible() {
            return eligible;
        }

        public String getCoveragePayload() { return coveragePayload; }
    }
}
