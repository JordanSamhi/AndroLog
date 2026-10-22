package com.jordansamhi.androlog;

import org.xmlpull.v1.XmlPullParserException;
import soot.jimple.infoflow.android.axml.AXmlAttribute;
import soot.jimple.infoflow.android.axml.AXmlNode;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.jimple.infoflow.android.resources.ARSCFileParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Resolves launcher components in the APK's binary manifest to code classes. */
public final class LauncherActivities {
    private LauncherActivities() { }

    public static List<String> read(Path apk) throws IOException, XmlPullParserException {
        try (ProcessManifest manifest = new ProcessManifest(apk.toFile())) {
            return fromManifest(manifest.getManifest(), manifest.getArscParser());
        }
    }

    static List<String> fromManifest(AXmlNode manifest, ARSCFileParser resources) {
        String packageName = attribute(manifest, "package", resources);
        Set<String> launchers = new TreeSet<>();
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
                if (!hasLauncherFilter(component, resources)) continue;
                String target = expand(attribute(component, alias ? "targetActivity" : "name", resources), packageName);
                // An alias is not a code class. Its own enabled flag and filters apply.
                if (target != null && activities.contains(target)) launchers.add(target);
            }
        }
        return new ArrayList<>(launchers);
    }

    private static boolean hasLauncherFilter(AXmlNode component, ARSCFileParser resources) {
        for (AXmlNode filter : component.getChildrenWithTag("intent-filter")) {
            boolean main = false, launcher = false;
            for (AXmlNode item : filter.getChildren()) {
                String name = attribute(item, "name", resources);
                if ("action".equals(item.getTag()) && "android.intent.action.MAIN".equals(name)) main = true;
                if ("category".equals(item.getTag()) && ("android.intent.category.LAUNCHER".equals(name)
                        || "android.intent.category.LEANBACK_LAUNCHER".equals(name))) launcher = true;
            }
            if (main && launcher) return true;
        }
        return false;
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
