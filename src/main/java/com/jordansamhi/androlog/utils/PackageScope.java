package com.jordansamhi.androlog.utils;

/** Matches AndroSpecter 2's exact-package or subpackage scope across every probe and baseline. */
public final class PackageScope {
    private PackageScope() { }

    public static String normalize(String target) {
        if (target == null) return null;
        String normalized = target.trim();
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.isEmpty() ? null : normalized;
    }

    public static boolean includes(String target, String packageName) {
        return target == null || packageName.equals(target) || packageName.startsWith(target + ".");
    }
}
