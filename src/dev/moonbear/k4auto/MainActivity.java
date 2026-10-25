package dev.moonbear.k4auto;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Two states only: needs pairing, or listening. No transport controls — opening
 * the app starts the continuous voice loop, and pairing needs syntox's approval
 * in Discord before the phone can talk to the bridge at all.
 */
public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1001;
    private static final long PAIR_POLL_MS = 3000L;

    private MoonbearConfig config;
    private volatile boolean downloading;
    private TextView statusView;
    private TextView headlineView;
    private TextView sublineView;
    private LinearLayout pairingPanel;
    private LinearLayout statusPanel;
    private TextView pairingStatus;
    private Button pairButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling;
    private String lastLocation = "";

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            lastLocation = intent.getStringExtra("location");
            render(intent.getStringExtra("state"),
                    intent.getStringExtra("headline"),
                    intent.getStringExtra("subline"));
        }
    };

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                java.io.FileWriter w = new java.io.FileWriter(
                        new java.io.File(getFilesDir(), "engine.crash"));
                java.io.PrintWriter pw = new java.io.PrintWriter(w);
                ex.printStackTrace(pw);
                pw.close();
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(thread, ex);
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        installCrashHandler();
        super.onCreate(savedInstanceState);
        config = new MoonbearConfig(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#0B0F17"));
            getWindow().setNavigationBarColor(Color.parseColor("#0B0F17"));
        }
        setContentView(buildUi());
        requestNeededPermissions();
        applyState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            IntentFilter filter = new IntentFilter(MoonbearCarAudioService.ACTION_STATE_UPDATE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
        } catch (Throwable ignored) {}
        applyState();
        renderFromService();
    }

    /** Pull the service's live snapshot — broadcasts sent while we were paused are gone. */
    private void renderFromService() {
        try {
            String[] snap = MoonbearCarAudioService.snapshot();
            if (snap == null || snap.length < 3) return;
            if (MoonbearCarAudioService.STATE_IDLE.equals(snap[0])) return;
            lastLocation = snap[3];
            render(snap[0], snap[1], snap[2]);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        polling = false;
        super.onDestroy();
    }

    private void applyState() {
        boolean paired = MoonbearAuth.isPaired(this);
        pairingPanel.setVisibility(paired ? View.GONE : View.VISIBLE);
        statusPanel.setVisibility(paired ? View.VISIBLE : View.GONE);
        if (paired) {
            startVoiceLoop();
        } else {
            render("IDLE", "Pair this phone", "Pairing needs approval in Discord");
        }
    }

    // ------------------------------------------------------------- pairing

    private void beginOauth() {
        pairButton.setEnabled(false);
        pairingStatus.setText("Starting Discord sign-in…");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("name", Build.MODEL == null ? "Android phone" : Build.MODEL);
                String resp = MoonbearHttp.callPublic(this, config.getServerUrl(),
                        "/api/moonbear-voice/oauth/start", "POST", body.toString(), 20_000);
                if (resp == null) {
                    ui(() -> {
                        pairingStatus.setText("Bridge refused the sign-in.\n"
                                + "If this is a fresh bridge, Discord OAuth is not configured yet.");
                        pairButton.setEnabled(true);
                    });
                    return;
                }
                JSONObject o = new JSONObject(resp);
                String loginId = o.optString("login_id", "");
                String url = o.optString("authorize_url", "");
                if (loginId.isEmpty() || url.isEmpty()) {
                    ui(() -> {
                        pairingStatus.setText("Bridge did not return a sign-in URL.");
                        pairButton.setEnabled(true);
                    });
                    return;
                }
                ui(() -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                        pairingStatus.setText("Complete the sign-in in Discord,\nthen come back — this phone connects automatically.");
                    } catch (Throwable t) {
                        pairingStatus.setText("No browser available: " + t.getMessage());
                    }
                });
                startPolling(loginId);
            } catch (Throwable t) {
                ui(() -> {
                    pairingStatus.setText("Sign-in error: " + t.getMessage());
                    pairButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void startPolling(String requestId) {
        polling = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!polling || isFinishing()) return;
                new Thread(() -> {
                    String resp = MoonbearHttp.callPublic(MainActivity.this, config.getServerUrl(),
                            "/api/moonbear-voice/oauth/status/" + requestId, "GET", null, 20_000);
                    if (resp == null) {
                        handler.postDelayed(this, PAIR_POLL_MS);
                        return;
                    }
                    try {
                        JSONObject o = new JSONObject(resp);
                        String status = o.optString("status", "");
                        if ("approved".equals(status)) {
                            String deviceId = o.optString("device_id", "");
                            String secret = o.optString("secret", "");
                            if (!deviceId.isEmpty() && !secret.isEmpty()) {
                                MoonbearAuth.saveCredentials(MainActivity.this, deviceId, secret, Build.MODEL);
                                ui(() -> {
                                    pairingStatus.setText("Signed in ✅");
                                    applyState();
                                });
                                return;
                            }
                        } else if ("denied".equals(status) || "expired".equals(status)) {
                            ui(() -> pairingStatus.setText("expired".equals(status)
                                    ? "Request expired — tap to try again."
                                    : "That Discord account is not syntox."));
                            return;
                        }
                    } catch (Throwable ignored) {}
                    handler.postDelayed(this, PAIR_POLL_MS);
                }).start();
            }
        }, PAIR_POLL_MS);
    }

    private void startVoiceLoop() {
        try {
            Intent i = new Intent(this, MoonbearCarAudioService.class);
            i.setAction(MoonbearCarAudioService.ACTION_COMMAND);
            i.putExtra("cmd", MoonbearCarAudioService.CMD_START_LOOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable ignored) {}
    }

    private void requestNeededPermissions() {
        try {
            java.util.List<String> needed = new java.util.ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
            if (!MoonbearLocation.hasPermission(this)) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
                needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), REQ_PERMS);
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------- ui

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0F17"));

        pairingPanel = new LinearLayout(this);
        pairingPanel.setOrientation(LinearLayout.VERTICAL);
        pairingPanel.setGravity(Gravity.CENTER);
        int pad = dp(28);
        pairingPanel.setPadding(pad, pad, pad, pad);

        TextView pairTitle = new TextView(this);
        pairTitle.setText("🐻🌙 Moonbear");
        pairTitle.setTextColor(Color.parseColor("#F5B942"));
        pairTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        pairTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pairTitle.setGravity(Gravity.CENTER);
        pairingPanel.addView(pairTitle);

        pairingStatus = new TextView(this);
        pairingStatus.setText("Sign in with Discord to link this phone.\nMoonbear verifies it is you — no token is stored in the app.");
        pairingStatus.setTextColor(Color.parseColor("#94A3B8"));
        pairingStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        pairingStatus.setGravity(Gravity.CENTER);
        pairingStatus.setPadding(0, dp(18), 0, dp(18));
        pairingPanel.addView(pairingStatus);

        pairButton = new Button(this);
        pairButton.setText("Sign in with Discord");
        pairButton.setAllCaps(false);
        pairButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        pairButton.setTypeface(Typeface.DEFAULT_BOLD);
        pairButton.setOnClickListener(v -> beginOauth());
        pairingPanel.addView(pairButton);

        statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setGravity(Gravity.CENTER);
        statusPanel.setPadding(pad, pad, pad, pad);
        statusPanel.setOnLongClickListener(v -> {
            MoonbearAuth.forget(this);
            applyState();
            return true;
        });

        TextView title = new TextView(this);
        title.setText("🐻🌙 Moonbear");
        title.setTextColor(Color.parseColor("#F5B942"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        statusPanel.addView(title);

        statusView = new TextView(this);
        statusView.setText("● Ready");
        statusView.setTextColor(Color.parseColor("#F5B942"));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(8);
        statusPanel.addView(statusView, statusLp);

        headlineView = new TextView(this);
        headlineView.setText("Just talk");
        headlineView.setTextColor(Color.WHITE);
        headlineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        headlineView.setTypeface(Typeface.DEFAULT_BOLD);
        headlineView.setGravity(Gravity.CENTER);
        statusPanel.addView(headlineView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        sublineView = new TextView(this);
        sublineView.setText("Listening over the Kia K4 mic — no buttons needed");
        sublineView.setTextColor(Color.parseColor("#94A3B8"));
        sublineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sublineView.setGravity(Gravity.CENTER);
        statusPanel.addView(sublineView);

        root.addView(pairingPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(statusPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private void render(String state, String headline, String subline) {
        if (state == null) state = MoonbearCarAudioService.STATE_IDLE;
        if (headline != null) headlineView.setText(headline);
        if (subline != null && !downloading) {
            String text = subline;
            if (lastLocation != null && !lastLocation.isEmpty()) text = text + "\n" + lastLocation;
            sublineView.setText(text);
        }
        if (MoonbearCarAudioService.STATE_LISTENING.equals(state)) {
            statusView.setText("● Listening");
            statusView.setTextColor(Color.parseColor("#22C55E"));
        } else if (MoonbearCarAudioService.STATE_THINKING.equals(state)) {
            statusView.setText("● Thinking");
            statusView.setTextColor(Color.parseColor("#38BDF8"));
        } else if (MoonbearCarAudioService.STATE_SPEAKING.equals(state)) {
            statusView.setText("● Speaking");
            statusView.setTextColor(Color.parseColor("#C084FC"));
        } else {
            statusView.setText("● Ready");
            statusView.setTextColor(Color.parseColor("#F5B942"));
        }
    }

    private void ui(Runnable r) {
        handler.post(r);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
