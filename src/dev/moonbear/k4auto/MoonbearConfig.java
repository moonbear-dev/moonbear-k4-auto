package dev.moonbear.k4auto;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App settings. Deliberately tiny: the app has one behaviour (continuous
 * bidirectional audio through the Hermes agent), so there is nothing to toggle
 * beyond where the bridge lives.
 */
public class MoonbearConfig {
    private static final String PREFS_NAME = "moonbear_k4_prefs";

    public static final String DEFAULT_SERVER_URL = "https://45.32.241.209:9443";

    private final SharedPreferences prefs;

    public MoonbearConfig(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getServerUrl() {
        String url = prefs.getString("server_url", DEFAULT_SERVER_URL).trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.isEmpty() ? DEFAULT_SERVER_URL : url;
    }

    public void setServerUrl(String url) {
        prefs.edit().putString("server_url", url != null ? url.trim() : DEFAULT_SERVER_URL).apply();
    }

    /** "server" = Kokoro-82M on the VPS, "client" = on-device TTS. */
    public String getTtsMode() {
        return prefs.getString("tts_mode", "server");
    }

    public String getMicMode() {
        return prefs.getString("mic_mode", "smart");
    }

    public boolean isMirrorDiscord() {
        return prefs.getBoolean("mirror_discord", true);
    }
}
