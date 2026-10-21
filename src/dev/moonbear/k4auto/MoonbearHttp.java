package dev.moonbear.k4auto;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * HTTPS transport that trusts exactly one certificate — the one baked into the
 * APK as res/raw/moonbear_ca.pem. No system CAs, no hostname negotiation with
 * a real CA: only the Moonbear bridge can answer.
 */
public final class MoonbearHttp {

    private static final String TAG = "MoonbearHttp";
    private static SSLContext pinned;

    private MoonbearHttp() {}

    private static synchronized SSLContext pinnedContext(Context context) throws Exception {
        if (pinned != null) return pinned;
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (InputStream in = context.getResources().openRawResource(R.raw.moonbear_ca)) {
            X509Certificate cert = (X509Certificate) cf.generateCertificate(in);
            ks.setCertificateEntry("moonbear", cert);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        pinned = ctx;
        return ctx;
    }

    public static HttpURLConnection open(Context context, String baseUrl, String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        if (conn instanceof HttpsURLConnection) {
            ((HttpsURLConnection) conn).setSSLSocketFactory(pinnedContext(context).getSocketFactory());
        }
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "MoonbearAuto/2.0");
        return conn;
    }

    /**
     * Signed JSON request. Returns the response body, or null when the bridge
     * rejected us / could not be reached (callers degrade rather than crash).
     */
    public static String callSigned(Context context, String baseUrl, String path,
                                    String method, String jsonBody, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            byte[] payload = jsonBody == null ? new byte[0] : jsonBody.getBytes("UTF-8");
            conn = open(context, baseUrl, path);
            conn.setRequestMethod(method);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(readTimeoutMs);
            if (!MoonbearAuth.sign(context, conn, payload)) {
                Log.w(TAG, "Not paired: refusing to call " + path);
                return null;
            }
            if (jsonBody != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            int code = conn.getResponseCode();
            if (code >= 400) {
                InputStream err = conn.getErrorStream();
                Log.w(TAG, path + " -> HTTP " + code + (err != null ? " " + MoonbearAuth.readBody(err) : ""));
                return null;
            }
            InputStream in = conn.getInputStream();
            return in == null ? "" : MoonbearAuth.readBody(in);
        } catch (Throwable t) {
            Log.w(TAG, path + " failed: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Unsigned call, only for the public pairing endpoints. */
    /** Open a signed request and hand back the raw stream (no buffering). */
    public static java.io.InputStream openSignedStream(Context context, String baseUrl, String path,
                                                       int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = open(context, baseUrl, path);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        // sign over an empty body: the stream carries no payload
        if (!MoonbearAuth.sign(context, conn, new byte[0])) {
            conn.disconnect();
            return null;
        }
        int code = conn.getResponseCode();
        if (code >= 400) {
            Log.w(TAG, path + " -> HTTP " + code);
            conn.disconnect();
            return null;
        }
        return conn.getInputStream();
    }

    public static String callPublic(Context context, String baseUrl, String path,
                                    String method, String jsonBody, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            byte[] payload = jsonBody == null ? new byte[0] : jsonBody.getBytes("UTF-8");
            conn = open(context, baseUrl, path);
            conn.setRequestMethod(method);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(readTimeoutMs);
            if (jsonBody != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            int code = conn.getResponseCode();
            if (code >= 400) return null;
            InputStream in = conn.getInputStream();
            return in == null ? "" : MoonbearAuth.readBody(in);
        } catch (Throwable t) {
            Log.w(TAG, path + " failed: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
