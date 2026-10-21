package dev.moonbear.k4auto;

import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.validation.HostValidator;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

/**
 * Car App Library entry point: this is what makes Moonbear Auto selectable from
 * Android Auto's Customize-launcher list.
 *
 * No controls. Opening it starts the continuous voice loop; the screen only
 * shows what the car is doing (listening / thinking / speaking).
 */
public class MoonbearCarAppService extends CarAppService {

    private static final String TAG = "MoonbearCarApp";

    @Override
    @NonNull
    public Session onCreateSession() {
        return new MoonbearSession();
    }

    /** Sideloaded builds are not Play-signed, so every host must be accepted. */
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    static class MoonbearSession extends Session {
        @NonNull
        @Override
        public Screen onCreateScreen(@NonNull Intent intent) {
            startVoiceLoop(getCarContext());
            return new MoonbearScreen(getCarContext());
        }

        private void startVoiceLoop(CarContext context) {
            try {
                Intent i = new Intent(context, MoonbearCarAudioService.class);
                i.setAction(MoonbearCarAudioService.ACTION_COMMAND);
                i.putExtra("cmd", MoonbearCarAudioService.CMD_START_LOOP);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i);
                } else {
                    context.startService(i);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Could not start voice loop: " + t);
            }
        }
    }

    static class MoonbearScreen extends Screen {

        MoonbearScreen(@NonNull CarContext carContext) {
            super(carContext);
        }

        @NonNull
        @Override
        public Template onGetTemplate() {
            Row status = new Row.Builder()
                    .setTitle(MoonbearState.state + " — " + MoonbearState.headline)
                    .addText(MoonbearState.subline)
                    .build();

            Pane pane = new Pane.Builder()
                    .addRow(status)
                    .build();

            return new PaneTemplate.Builder(pane)
                    .setTitle("🐻🌙 Moonbear")
                    .build();
        }
    }
}
