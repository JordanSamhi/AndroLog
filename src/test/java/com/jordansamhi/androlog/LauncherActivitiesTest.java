package com.jordansamhi.androlog;

import org.junit.Test;
import soot.jimple.infoflow.android.axml.AXmlAttribute;
import soot.jimple.infoflow.android.axml.AXmlNode;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class LauncherActivitiesTest {
    private static final String NS = "http://schemas.android.com/apk/res/android";
    private final AXmlNode manifest = new AXmlNode("manifest", null, null);
    private final AXmlNode application = new AXmlNode("application", null, manifest);

    public LauncherActivitiesTest() { manifest.addAttribute(new AXmlAttribute<>("package", "example.app", null)); }

    @Test
    public void usesIntentFiltersRatherThanGuessingMainActivity() {
        activity(".MainActivity");
        launcher(activity(".Welcome"), "LAUNCHER");
        assertEquals(Collections.singletonList("example.app.Welcome"), LauncherActivities.fromManifest(manifest, null));
    }

    @Test
    public void expandsNamesAndDeduplicatesMultipleLaunchersDeterministically() {
        launcher(activity("Start"), "LAUNCHER");
        launcher(activity(".Start"), "LAUNCHER");
        launcher(activity("other.Entry"), "LAUNCHER");
        assertEquals(Arrays.asList("example.app.Start", "other.Entry"), LauncherActivities.fromManifest(manifest, null));
    }

    @Test
    public void resolvesAliasToTargetClassUsingTheAliasesOwnFiltersAndEnabledFlag() {
        AXmlNode target = activity(".RealEntry");
        target.addAttribute(new AXmlAttribute<>("enabled", false, NS));
        AXmlNode alias = new AXmlNode("activity-alias", null, application);
        alias.addAttribute(new AXmlAttribute<>("name", ".LauncherIcon", NS));
        alias.addAttribute(new AXmlAttribute<>("targetActivity", "example.app.RealEntry", NS));
        launcher(alias, "LAUNCHER");
        assertEquals(Collections.singletonList("example.app.RealEntry"), LauncherActivities.fromManifest(manifest, null));
        alias.addAttribute(new AXmlAttribute<>("enabled", false, NS));
        assertEquals(Collections.emptyList(), LauncherActivities.fromManifest(manifest, null));
    }

    @Test
    public void requiresMainAndLauncherInTheSameIntentFilter() {
        AXmlNode activity = activity(".Split");
        item(new AXmlNode("intent-filter", null, activity), "action", "android.intent.action.MAIN");
        item(new AXmlNode("intent-filter", null, activity), "category", "android.intent.category.LAUNCHER");
        AXmlNode incomplete = new AXmlNode("intent-filter", null, activity);
        new AXmlNode("data", null, incomplete); // A data element need not have android:name.
        assertEquals(Collections.emptyList(), LauncherActivities.fromManifest(manifest, null));
    }

    @Test
    public void excludesDisabledActivitiesAndApplications() {
        AXmlNode off = activity(".Off"); launcher(off, "LAUNCHER");
        off.addAttribute(new AXmlAttribute<>("enabled", false, NS));
        launcher(activity(".On"), "LAUNCHER");
        assertEquals(Collections.singletonList("example.app.On"), LauncherActivities.fromManifest(manifest, null));
        application.addAttribute(new AXmlAttribute<>("enabled", false, NS));
        assertEquals(Collections.emptyList(), LauncherActivities.fromManifest(manifest, null));
    }

    @Test
    public void ignoresAliasesWithoutADeclaredTargetAndMissingActivityNames() {
        AXmlNode alias = new AXmlNode("activity-alias", null, application);
        alias.addAttribute(new AXmlAttribute<>("targetActivity", ".Missing", NS)); launcher(alias, "LAUNCHER");
        launcher(new AXmlNode("activity", null, application), "LAUNCHER");
        assertEquals(Collections.emptyList(), LauncherActivities.fromManifest(manifest, null));
    }

    @Test
    public void supportsTelevisionLauncherActivities() {
        launcher(activity(".Television"), "LEANBACK_LAUNCHER");
        assertEquals(Collections.singletonList("example.app.Television"), LauncherActivities.fromManifest(manifest, null));
    }

    @Test public void captureLaunchesTheAliasInsteadOfItsTargetClass() {
        activity(".RealEntry");
        AXmlNode alias = new AXmlNode("activity-alias", null, application);
        alias.addAttribute(new AXmlAttribute<>("name", ".LauncherIcon", NS));
        alias.addAttribute(new AXmlAttribute<>("targetActivity", ".RealEntry", NS));
        launcher(alias, "LAUNCHER");
        LauncherActivities.LaunchTarget target = LauncherActivities.launchTargets(manifest, null).get(0);
        assertEquals("example.app/example.app.LauncherIcon", target.component());
        assertEquals("example.app.RealEntry", target.className);
        assertEquals("android.intent.category.LAUNCHER", target.category);
    }

    private AXmlNode activity(String name) {
        AXmlNode node = new AXmlNode("activity", null, application);
        node.addAttribute(new AXmlAttribute<>("name", name, NS)); return node;
    }

    private void launcher(AXmlNode component, String category) {
        AXmlNode filter = new AXmlNode("intent-filter", null, component);
        item(filter, "action", "android.intent.action.MAIN"); item(filter, "category", "android.intent.category." + category);
    }

    private void item(AXmlNode filter, String tag, String name) {
        new AXmlNode(tag, null, filter).addAttribute(new AXmlAttribute<>("name", name, NS));
    }
}
