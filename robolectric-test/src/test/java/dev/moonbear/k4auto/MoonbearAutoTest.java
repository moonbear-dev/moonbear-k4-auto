package dev.moonbear.k4auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.widget.Button;
import android.widget.TextView;

import androidx.media.MediaBrowserServiceCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the REAL dev.moonbear.k4auto classes on an Android runtime (Robolectric)
 * to catch classes of failure that only show up at runtime: missing classes on
 * the dex path, resource inflation failures, MediaBrowserService contract breaks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MoonbearAutoTest {

    // ---------- 1. MainActivity launches without crashing (the v1.1 bug class) ----------

    @Test
    public void mainActivityLaunchesAndBuildsUi() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        assertNotNull(activity);
        assertNotNull("content view must be set", activity.findViewById(android.R.id.content));

        List<String> texts = collectTexts(activity.findViewById(android.R.id.content));
        assertTrue("title missing: " + texts, containsAny(texts, "Moonbear"));
        // A fresh install is unpaired, so the app must show the pairing gate --
        // pairing is approved in Discord, never in the app.
        assertTrue("sign-in gate missing: " + texts,
                containsAny(texts, "Sign in with Discord"));
        assertTrue("sign-in explanation missing: " + texts,
                containsAny(texts, "no token is stored"));
        assertFalse("the app must not expose buttons any more: " + texts,
                containsAny(texts, "TAP TO TALK"));
        assertFalse("stop control must be gone: " + texts, containsAny(texts, "Stop"));

        controller.pause().stop().destroy();
    }

    // ---------- 2. MediaBrowserService contract (what Android Auto / Gearhead checks) ----------

    @Test
    public void browserRootAdvertisesSessionBinderForGearhead() {
        ServiceController<MoonbearCarAudioService> controller =
                Robolectric.buildService(MoonbearCarAudioService.class).create();
        MoonbearCarAudioService service = controller.get();

        // Gearhead connects with MediaBrowserCompat and reads the session from
        // BrowserRoot extras. MediaBrowserServiceCompat injects
        // "android.support.v4.media.session.EXTRA_BINDER" + EXTRA_TOKEN2 itself,
        // but ONLY when the service has published a session token via
        // setSessionToken(...) -- so that is the contract to assert here.
        assertNotNull("service must publish a MediaSession token or the car cannot talk to it",
                service.getSessionToken());
        assertNotNull("session token must carry a real platform token/binder",
                service.getSessionToken().getToken());

        MediaBrowserServiceCompat.BrowserRoot root = service.onGetRoot(
                "com.google.android.projection.gearhead", 0, null);
        assertNotNull("onGetRoot returned null - Android Auto would not list the app", root);
        assertEquals("root id mismatch", "root", root.getRootId());

        Bundle extras = root.getExtras();
        assertNotNull("BrowserRoot extras are required by Gearhead", extras);
        assertTrue("car UI needs CONTENT_STYLE hints to render the list",
                extras.getBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED"));
        assertEquals("browsable hint must be a list", 1,
                extras.getInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"));
        assertEquals("playable hint must be a list", 1,
                extras.getInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"));

        controller.destroy();
    }

    @Test
    public void mediaBrowserDeliversBrowsableItemsToCar() throws Exception {
        ServiceController<MoonbearCarAudioService> controller =
                Robolectric.buildService(MoonbearCarAudioService.class).create();
        MoonbearCarAudioService service = controller.get();

        List<MediaBrowserCompat.MediaItem> rootItems = loadChildren(service, "root");
        assertTrue("root should expose at least one browsable tab, got " + rootItems.size(),
                rootItems.size() >= 1);
        for (MediaBrowserCompat.MediaItem item : rootItems) {
            assertTrue("Android Auto only lists BROWSABLE children at root level",
                    item.isBrowsable());
        }

        String tabId = rootItems.get(0).getMediaId();
        List<MediaBrowserCompat.MediaItem> controls = loadChildren(service, tabId);
        assertEquals("continuous mode exposes a single status row (no controls)",
                1, controls.size());
        for (MediaBrowserCompat.MediaItem item : controls) {
            assertTrue("leaf items must be PLAYABLE for steering-wheel selection",
                    item.isPlayable());
            assertNotNull(item.getDescription().getTitle());
        }

        controller.destroy();
    }

    // ---------- 3. Steering-wheel / voice command path is wired ----------

    @Test
    public void serviceAcceptsCommandIntent() {
        ServiceController<MoonbearCarAudioService> controller =
                Robolectric.buildService(MoonbearCarAudioService.class).create();
        MoonbearCarAudioService service = controller.get();

        Intent i = new Intent(service, MoonbearCarAudioService.class);
        i.setAction(MoonbearCarAudioService.ACTION_COMMAND);
        i.putExtra("cmd", MoonbearCarAudioService.CMD_REFRESH_UI);
        int rc = service.onStartCommand(i, 0, 1);
        assertTrue("service must not reject command intents",
                rc == android.app.Service.START_STICKY
                        || rc == android.app.Service.START_NOT_STICKY
                        || rc == android.app.Service.START_STICKY_COMPATIBILITY);

        controller.destroy();
    }

    // ---------- helpers ----------

    private static List<String> collectTexts(android.view.View root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(android.view.View v, List<String> out) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) out.add(t.toString());
        }
        if (v instanceof Button) {
            CharSequence t = ((Button) v).getText();
            if (t != null) out.add(t.toString());
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static boolean containsAny(List<String> haystack, String needle) {
        for (String s : haystack) {
            if (s.toLowerCase().contains(needle.toLowerCase())) return true;
        }
        return false;
    }

    private static List<MediaBrowserCompat.MediaItem> loadChildren(
            MediaBrowserServiceCompat service, String parentId) throws Exception {
        final List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        service.onLoadChildren(parentId, androidx.media.ResultFactory.result(
                service, items -> {
                    if (items != null) {
                        out.addAll(items);
                    }
                    latch.countDown();
                }));
        assertTrue("onLoadChildren never returned for parent=" + parentId,
                latch.await(10, TimeUnit.SECONDS));
        return out;
    }
}
