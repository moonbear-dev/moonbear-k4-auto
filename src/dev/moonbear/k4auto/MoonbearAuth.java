package dev.moonbear.k4auto;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Per-device credentials for the voice bridge.
 *
 * The APK ships NO secret. A device obtains its signing key only after syntox
 * approves the pairing request in Discord, and that key is stored encrypted
 * with an AES-GCM key held in the Android Keystore (hardware-backed where the
 * device supports it) — never in plaintext preferences.
 */
public final class MoonbearAuth {

    private static final String TAG = "MoonbearAuth";
    private static final String PREFS = "moonbear_auth";
    private static final String KEY_ALIAS = "moonbear_device_key";
    private static final String K_DEVICE = "device_id";
    private static final String K_SECRET = "secret_ct";
    private static final String K_IV = "secret_iv";
    private static final String K_NAME = "device_name";
    public static final String PENDING_REQUEST = "pending_request_id";

    private MoonbearAuth() {}

    // ---------------------------------------------------------------- storage

    private static SecretKey keystoreKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    public static boolean isPaired(Context context) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return p.contains(K_DEVICE) && p.contains(K_SECRET) && p.contains(K_IV);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void saveCredentials(Context context, String deviceId, String secretHex, String name) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey());
            byte[] ct = cipher.doFinal(secretHex.getBytes(StandardCharsets.UTF_8));
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(K_DEVICE, deviceId)
                    .putString(K_SECRET, Base64.encodeToString(ct, Base64.NO_WRAP))
                    .putString(K_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString(K_NAME, name == null ? "" : name)
                    .remove(PENDING_REQUEST)
                    .apply();
        } catch (Throwable t) {
            Log.e(TAG, "Could not persist credentials: " + t);
        }
    }

    public static String deviceId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_DEVICE, null);
    }

    private static String secret(Context context) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String ct = p.getString(K_SECRET, null);
            String iv = p.getString(K_IV, null);
            if (ct == null || iv == null) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.e(TAG, "Could not read credentials: " + t);
            return null;
        }
    }

    public static void forget(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static void setPendingRequest(Context context, String requestId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PENDING_REQUEST, requestId).apply();
    }

    public static String pendingRequest(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PENDING_REQUEST, null);
    }

    // ---------------------------------------------------------------- signing

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return toHex(md.digest(data));
    }

    /** The bridge keys HMAC on the raw bytes of the hex secret, not the hex text. */
    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hmacHex(byte[] key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Attach X-Mb-Device / X-Mb-Ts / X-Mb-Sig to a request. Signature covers the
     * raw body, so neither audio nor location can be altered in flight.
     */
    public static boolean sign(Context context, HttpURLConnection conn, byte[] body) {
        try {
            String deviceId = deviceId(context);
            String secret = secret(context);
            if (deviceId == null || secret == null) return false;
            String ts = String.valueOf(System.currentTimeMillis());
            String canonical = deviceId + "\n" + ts + "\n" + sha256Hex(body != null ? body : new byte[0]);
            conn.setRequestProperty("X-Mb-Device", deviceId);
            conn.setRequestProperty("X-Mb-Ts", ts);
            conn.setRequestProperty("X-Mb-Sig", hmacHex(hexToBytes(secret), canonical));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Signing failed: " + t);
            return false;
        }
    }

    /** Best-effort: read a fully-buffered response body. */
    public static String readBody(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    public static String randomDeviceName() {
        return "Galaxy A57";
    }
}
