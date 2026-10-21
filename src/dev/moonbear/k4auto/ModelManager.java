package dev.moonbear.k4auto;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads the on-device models once, into app-private storage.
 *
 * Nothing is bundled in the APK: Parakeet is ~650 MB and Kokoro ~90 MB, so
 * shipping them is out of the question. Files are pulled from the bridge,
 * which serves them from /root/models.
 */
public final class ModelManager {

    private static final String TAG = "ModelManager";

    /** Official sherpa-onnx model repos — the phone fetches straight from Hugging Face. */
    private static final String HF = "https://huggingface.co";
    private static final String REPO_PARAKEET =
            "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8";
    private static final String REPO_KOKORO = "csukuangfj/kokoro-int8-multi-lang-v1_0";

    public interface Progress {
        void onProgress(String file, long done, long total);
        void onDone(boolean ok, String error);
    }

    /** [model dir, file name] pairs needed at runtime. */
    private static final String[][] FILES = {
            {"parakeet-v3-int8", "encoder.int8.onnx"},
            {"parakeet-v3-int8", "decoder.int8.onnx"},
            {"parakeet-v3-int8", "joiner.int8.onnx"},
            {"parakeet-v3-int8", "tokens.txt"},
            {"kokoro-int8-multi-lang-v1_0", "model.int8.onnx"},
            {"kokoro-int8-multi-lang-v1_0", "voices.bin"},
            {"kokoro-int8-multi-lang-v1_0", "lexicon-us-en.txt"},
            {"kokoro-int8-multi-lang-v1_0", "tokens.txt"},
    };

    private ModelManager() {}

    public static File dir(Context ctx, String model) {
        File d = new File(ctx.getFilesDir(), "models/" + model);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static boolean ready(Context ctx) {
        for (String[] f : FILES) {
            File out = new File(dir(ctx, f[0]), f[1]);
            if (!out.isFile() || out.length() < 1024) return false;
        }
        return true;
    }

    /** Total bytes still to fetch, for a progress bar. */
    public static long missingBytes(Context ctx) {
        long total = 0;
        for (String[] f : FILES) {
            File out = new File(dir(ctx, f[0]), f[1]);
            if (!out.isFile()) total += expectedSize(f[1]);
        }
        return total;
    }

    private static long expectedSize(String name) {
        switch (name) {
            case "encoder.int8.onnx": return 652_184_281L;
            case "decoder.int8.onnx": return 11_845_275L;
            case "joiner.int8.onnx": return 6_355_277L;
            case "model.int8.onnx": return 114_203_756L;
            case "voices.bin": return 28_200_960L;
            case "lexicon-us-en.txt": return 5_956_885L;
            case "tokens.txt": return 687L;
            default: return 0;
        }
    }

    /** Blocking; call from a worker thread. Copies the bundled models out of assets. */
    public static void ensureModels(Context ctx, String baseUrl, Progress cb) {
        try {
            for (String[] f : FILES) {
                File out = new File(dir(ctx, f[0]), f[1]);
                if (out.isFile() && out.length() > 1_000_000) continue;
                copyAsset(ctx, "models/" + f[0] + "/" + f[1], out, f[1], cb);
                if ("voices.bin".equals(f[1])) injectBearVoice(ctx, out);
            }
            cb.onDone(true, null);
        } catch (Exception e) {
            Log.e(TAG, "model copy failed", e);
            cb.onDone(false, e.toString());
        }
    }

    private static void copyAsset(Context ctx, String asset, File out, String name,
                                   Progress cb) throws Exception {
        File part = new File(out.getAbsolutePath() + ".part");
        long expected = expectedSize(name);
        try (InputStream in = ctx.getAssets().open(asset);
             FileOutputStream fo = new FileOutputStream(part)) {
            byte[] buf = new byte[512 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                done += n;
                if (cb != null) cb.onProgress(name, done, expected);
            }
        }
        if (!part.renameTo(out)) throw new java.io.IOException("cannot rename " + part);
        Log.i(TAG, "unpacked " + name + " (" + out.length() + " bytes)");
    }

    /**
     * The walked bear voice is one Kokoro style tensor (510 x 1 x 256 = 130,560 floats).
     * It ships in the APK as assets/bear_voice.bin, so voices.bin's speaker 0 is
     * overwritten on the device and nothing custom has to be hosted anywhere.
     */
    private static void injectBearVoice(Context ctx, File voicesBin) {
        try (InputStream in = ctx.getAssets().open("bear_voice.bin");
             java.io.RandomAccessFile raf = new java.io.RandomAccessFile(voicesBin, "rw")) {
            byte[] bear = new byte[(int) 510 * 256 * 4];
            int read = 0;
            while (read < bear.length) {
                int n = in.read(bear, read, bear.length - read);
                if (n <= 0) break;
                read += n;
            }
            if (read != bear.length) {
                Log.w(TAG, "bear voice asset short: " + read);
                return;
            }
            raf.seek(0);
            raf.write(bear);            // speaker 0 == BEAR_SID
            Log.i(TAG, "bear voice injected into voices.bin");
        } catch (Throwable t) {
            Log.e(TAG, "bear voice injection failed", t);
        }
    }

    /** Names still missing, for the status line. */
    public static List<String> missing(Context ctx) {
        List<String> out = new ArrayList<>();
        for (String[] f : FILES) {
            File file = new File(dir(ctx, f[0]), f[1]);
            if (!file.isFile()) out.add(f[1]);
        }
        return out;
    }
}
