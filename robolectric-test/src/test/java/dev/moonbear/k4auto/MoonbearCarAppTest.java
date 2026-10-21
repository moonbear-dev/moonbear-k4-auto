package dev.moonbear.k4auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.model.Action;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.testing.TestCarContext;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Exercises the Car App Library entry point that makes Moonbear pinnable from
 * Android Auto's Customize-launcher list. This is the runtime proof that the
 * Android Auto template path resolves and renders -- i.e. that the
 * "known-unreachable" entries in the DEX link check really are unreachable.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MoonbearCarAppTest {

    private static final String[] CAR_API_CLASSES = {
            "androidx.car.app.CarAppService",
            "androidx.car.app.CarContext",
            "androidx.car.app.Session",
            "androidx.car.app.Screen",
            "androidx.car.app.model.Template",
            "androidx.car.app.model.PaneTemplate",
            "androidx.car.app.model.Pane",
            "androidx.car.app.model.Row",
            "androidx.car.app.model.Action",
            "androidx.car.app.validation.HostValidator",
    };

    @Test
    public void carAppEntryPointLoadsAndBuildsTemplate() throws Exception {
        // 1. The service and its whole superclass chain must load.
        MoonbearCarAppService service =
                Robolectric.buildService(MoonbearCarAppService.class).create().get();

        // Sideloaded builds are not Play-signed: the host validator must allow all hosts,
        // otherwise Android Auto refuses the connection and the app never appears.
        assertNotNull("createHostValidator must not be null", service.createHostValidator());

        // 2. Session creation must work.
        Session session = service.onCreateSession();
        assertNotNull("onCreateSession must return a session", session);

        // 3. Every template class the car screen touches must resolve on the dex.
        for (String name : CAR_API_CLASSES) {
            Class.forName(name); // throws NoClassDefFoundError if it was never bundled
        }

        // 4. Build the actual screen and render the template the car will show.
        CarContext carContext = TestCarContext.createCarContext(RuntimeEnvironment.getApplication());
        Screen screen = new MoonbearCarAppService.MoonbearScreen(carContext);
        Template template = screen.onGetTemplate();

        assertNotNull("car screen must render a template", template);
        assertTrue("car screen should use a PaneTemplate, got " + template.getClass(),
                template instanceof PaneTemplate);

        PaneTemplate pane = (PaneTemplate) template;
        assertNotNull("template must carry a title", pane.getTitle());
        assertNotNull("template must carry a pane", pane.getPane());
        assertEquals("continuous mode: the car screen exposes no controls",
                0, pane.getPane().getActions().size());
        assertEquals("the single status row must be present", 1, pane.getPane().getRows().size());
    }

    /**
     * The screen renders live state from the service (same process), so the
     * status row must reflect what the service published.
     */
    @Test
    public void screenReflectsPublishedServiceState() {
        MoonbearState.publish(MoonbearCarAudioService.STATE_LISTENING,
                "Listening in Kia K4...", "Speak naturally");

        CarContext carContext = TestCarContext.createCarContext(RuntimeEnvironment.getApplication());
        Screen screen = new MoonbearCarAppService.MoonbearScreen(carContext);
        PaneTemplate template = (PaneTemplate) screen.onGetTemplate();

        String rowText = template.getPane().getRows().get(0).getTitle().toString();
        assertTrue("car row must surface the live state, got '" + rowText + "'",
                rowText.toUpperCase().contains("LISTENING"));

        MoonbearState.publish(MoonbearCarAudioService.STATE_IDLE, "Standby", "Tap to talk");
    }
}
