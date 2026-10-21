package dev.moonbear.k4auto;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

/**
 * Keeps a live-ish GPS fix so every voice turn can carry position context to the
 * agent. Uses LocationManager (no Play Services dependency needed): the car's
 * head unit keeps the phone's GPS warm for navigation anyway.
 *
 * Updates come from both GPS and network providers; whichever gives the freshest
 * fix wins. Nothing here throws, so a device without location hardware degrades
 * to "no fix" instead of breaking the voice loop.
 */
public final class MoonbearLocation implements LocationListener {

    private static final String TAG = "MoonbearLocation";
    private static final long MIN_TIME_MS = 30_000L;
    private static final float MIN_DISTANCE_M = 15f;
    /** Ignore fixes older than this when reporting context. */
    private static final long MAX_AGE_MS = 10 * 60_000L;

    private final Context context;
    private LocationManager manager;
    private volatile Location last;
    private boolean listening;

    public MoonbearLocation(Context context) {
        this.context = context.getApplicationContext();
        try {
            manager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        } catch (Throwable t) {
            Log.w(TAG, "No LocationManager: " + t);
        }
    }

    public static boolean hasPermission(Context context) {
        try {
            return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Start monitoring. Safe to call repeatedly. */
    public void start() {
        if (manager == null || listening || !hasPermission(context)) {
            seedFromLastKnown();
            return;
        }
        listening = true;
        seedFromLastKnown();
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!manager.isProviderEnabled(provider)) continue;
                manager.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M, this);
            } catch (Throwable t) {
                Log.w(TAG, "requestLocationUpdates(" + provider + ") failed: " + t);
            }
        }
    }

    public void stop() {
        if (manager == null || !listening) return;
        listening = false;
        try {
            manager.removeUpdates(this);
        } catch (Throwable ignored) {}
    }

    private void seedFromLastKnown() {
        if (manager == null || !hasPermission(context)) return;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                Location l = manager.getLastKnownLocation(provider);
                if (l != null && (last == null || l.getTime() > last.getTime())) {
                    last = l;
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        Location current = last;
        if (current == null || location.getTime() >= current.getTime()) {
            last = location;
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    /** Fresh enough to be worth sending. */
    public boolean hasUsableFix() {
        Location l = last;
        return l != null && (System.currentTimeMillis() - l.getTime()) <= MAX_AGE_MS;
    }

    /** Compact JSON for the bridge, or null when there is no usable fix. */
    public JSONObject asJson() {
        Location l = last;
        if (l == null || !hasUsableFix()) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("lat", l.getLatitude());
            o.put("lon", l.getLongitude());
            if (l.hasAccuracy()) o.put("accuracy_m", Math.round(l.getAccuracy()));
            o.put("age_ms", Math.max(0L, System.currentTimeMillis() - l.getTime()));
            if (l.hasSpeed()) o.put("speed_mps", Math.round(l.getSpeed() * 10.0) / 10.0);
            if (l.hasBearing()) o.put("bearing", Math.round(l.getBearing()));
            o.put("provider", l.getProvider() == null ? "unknown" : l.getProvider());
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Human-readable fix for the on-screen status line. */
    public String summary() {
        Location l = last;
        if (l == null) return "GPS: no fix";
        if (!hasUsableFix()) return "GPS: stale fix";
        String acc = l.hasAccuracy() ? " ±" + Math.round(l.getAccuracy()) + "m" : "";
        return String.format(java.util.Locale.US, "GPS %.5f, %.5f%s",
                l.getLatitude(), l.getLongitude(), acc);
    }
}
