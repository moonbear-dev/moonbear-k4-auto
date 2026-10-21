package dev.moonbear.k4auto;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import java.io.File;

/**
 * Kokoro-82M int8, running on the phone.
 *
 * The bear voice is a Kokoro style tensor (510 x 1 x 256). sherpa-onnx reads
 * a voices.bin bank of those tensors, so the walked tensor is one entry in
 * that bank — sid selects it.
 */
public final class OnDeviceTts {

    private static final String TAG = "OnDeviceTts";

    private final OfflineTts tts;

    public OnDeviceTts(Context ctx) {
        File d = ModelManager.dir(ctx, "kokoro-int8-multi-lang-v1_0");

        OfflineTtsKokoroModelConfig kokoro = new OfflineTtsKokoroModelConfig();
        kokoro.setModel(new File(d, "model.int8.onnx").getAbsolutePath());
        kokoro.setVoices(new File(d, "voices.bin").getAbsolutePath());
        kokoro.setTokens(new File(d, "tokens.txt").getAbsolutePath());
        kokoro.setLexicon(new File(d, "lexicon-us-en.txt").getAbsolutePath());
        kokoro.setLang("en-us");
        File dataDir = new File(d, "espeak-ng-data");
        if (dataDir.isDirectory()) kokoro.setDataDir(dataDir.getAbsolutePath());

        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setKokoro(kokoro);
        model.setNumThreads(4);
        model.setDebug(false);
        model.setProvider("cpu");

        OfflineTtsConfig cfg = new OfflineTtsConfig();
        cfg.setModel(model);

        long t0 = SystemClock.elapsedRealtime();
        tts = new OfflineTts(ctx.getAssets(), cfg);
        Log.i(TAG, "kokoro loaded in " + (SystemClock.elapsedRealtime() - t0)
                + " ms, speakers=" + tts.numSpeakers() + ", sr=" + tts.sampleRate());
    }

    public int sampleRate() {
        return tts.sampleRate();
    }

    public int speakers() {
        return tts.numSpeakers();
    }

    /** Returns mono float samples at {@link #sampleRate()}. */
    public float[] say(String text, int sid) {
        if (text == null || text.isEmpty()) return new float[0];
        GeneratedAudio audio = tts.generate(text, sid, 1.0f);
        return audio == null ? new float[0] : audio.getSamples();
    }

    public void release() {
        try { tts.release(); } catch (Throwable ignored) {}
    }
}
