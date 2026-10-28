package com.jordansamhi.androlog;

import soot.jimple.infoflow.android.axml.AXmlAttribute;
import soot.jimple.infoflow.android.axml.AXmlNode;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.jimple.infoflow.android.resources.ARSCFileParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.TreeSet;

/** Resolves launcher components in the APK's binary manifest to code classes. */
public final class LauncherActivities {
    private LauncherActivities() { }

    public static List<String> read(Path apk) throws IOException {
        try (ProcessManifest manifest = new ProcessManifest(apk.toFile())) {
            return fromManifest(manifest.getManifest(), manifest.getArscParser());
        }
    }

    /** Preserve the manifest component name: an activity alias must be launched through its alias. */
    static LaunchTarget readLaunchTarget(Path apk) throws IOException {
        try (ProcessManifest manifest = new ProcessManifest(apk.toFile())) {
            List<LaunchTarget> targets = launchTargets(manifest.getManifest(), manifest.getArscParser());
            if (targets.isEmpty()) throw new IOException("The APK has no enabled launcher activity to start.");
            return targets.get(0);
        }
    }

    static List<String> fromManifest(AXmlNode manifest, ARSCFileParser resources) {
        Set<String> classes = new TreeSet<>();
        for (LaunchTarget target : launchTargets(manifest, resources)) classes.add(target.className);
        return new ArrayList<>(classes);
    }

    static List<LaunchTarget> launchTargets(AXmlNode manifest, ARSCFileParser resources) {
        String packageName = attribute(manifest, "package", resources);
        Map<String, LaunchTarget> launchers = new TreeMap<>();
        for (AXmlNode application : manifest.getChildrenWithTag("application")) {
            if (!enabled(application, resources)) continue;
            Set<String> activities = new HashSet<>();
            for (AXmlNode activity : application.getChildrenWithTag("activity")) {
                String name = expand(attribute(activity, "name", resources), packageName);
                if (name != null) activities.add(name);
            }
            for (AXmlNode component : application.getChildren()) {
                boolean alias = "activity-alias".equals(component.getTag());
                if ((!alias && !"activity".equals(component.getTag())) || !enabled(component, resources)) continue;
                String category = launcherCategory(component, resources);
                if (category == null) continue;
                String target = expand(attribute(component, alias ? "targetActivity" : "name", resources), packageName);
                // An alias is not a code class. Its own enabled flag and filters apply.
                String name = expand(attribute(component, "name", resources), packageName);
                if (target != null && name != null && activities.contains(target)) {
                    launchers.put(target + "/" + name, new LaunchTarget(packageName, name, target, category));
                }
            }
        }
        return new ArrayList<>(launchers.values());
    }

    private static String launcherCategory(AXmlNode component, ARSCFileParser resources) {
        for (AXmlNode filter : component.getChildrenWithTag("intent-filter")) {
            boolean main = false;
            String category = null;
            for (AXmlNode item : filter.getChildren()) {
                String name = attribute(item, "name", resources);
                if ("action".equals(item.getTag()) && "android.intent.action.MAIN".equals(name)) main = true;
                if ("category".equals(item.getTag()) && ("android.intent.category.LAUNCHER".equals(name)
                        || "android.intent.category.LEANBACK_LAUNCHER".equals(name))) category = name;
            }
            if (main && category != null) return category;
        }
        return null;
    }

    static final class LaunchTarget {
        final String packageName, componentName, className, category;
        LaunchTarget(String packageName, String componentName, String className, String category) {
            this.packageName = packageName;
            this.componentName = componentName;
            this.className = className;
            this.category = category;
        }
        String component() { return packageName + "/" + componentName; }
    }

    private static boolean enabled(AXmlNode node, ARSCFileParser resources) {
        AXmlAttribute<?> value = node.getAttribute("enabled");
        return value == null || (resources == null ? value.asBoolean() : value.asBoolean(resources));
    }

    private static String attribute(AXmlNode node, String name, ARSCFileParser resources) {
        AXmlAttribute<?> value = node.getAttribute(name);
        return value == null ? null : resources == null ? value.asString() : value.asString(resources);
    }

    private static String expand(String name, String packageName) {
        if (name == null || name.isEmpty()) return null;
        if (name.startsWith(".")) return packageName == null ? null : packageName + name;
        if (name.indexOf('.') < 0) return packageName == null ? null : packageName + "." + name;
        return name;
    }
}
