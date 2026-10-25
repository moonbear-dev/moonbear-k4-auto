package dev.moonbear.k4auto;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.TenVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

/**
 * Silero VAD v5 on-device (ONNX via sherpa-onnx).
 *
 * Replaces the old energy gate: a fixed RMS floor cannot separate speech from
 * road noise or the stereo, and it reacts to any loud transient. Silero scores
 * 32 ms frames at 16 kHz properly, on one CPU thread, well under real time.
 */
public final class SileroVad implements AutoCloseable {
    private static final String TAG = "MoonbearVad";

    /**
     * Samples per inference window at 16 kHz.
     *
     * Silero v5/v6 is fed window_size (512) PLUS window_overlap (64) = 576 samples;
     * sherpa hard-fails with "n: 512 != window_size: 576" otherwise, so the VAD never
     * runs at all. v4 was 512 — we ship v6, so this is 576.
     */
    public static final int WINDOW = 576;

    private Vad vad;
    private boolean broken;
    private int zeroStreak;

    public SileroVad(Context context) {
        this(context, "silero_vad.onnx");
    }

    public SileroVad(Context context, String assetName) {
        try {
            SileroVadModelConfig silero = new SileroVadModelConfig(
                    assetName,
                    0.10f,              // threshold — floor-level; the car mic arrives very quiet
                    0.6f,               // minSilenceDuration (s) — don't close a segment on a short pause
                    0.05f,              // minSpeechDuration (s) — a single syllable counts
                    WINDOW - 64,        // v5/v6 window_size (overlap is added back by sherpa)
                    20.0f               // maxSpeechDuration (s)
            );
            TenVadModelConfig ten = new TenVadModelConfig("", 0.5f, 0.25f, 0.25f, 256, 5.0f);
            VadModelConfig cfg = new VadModelConfig(silero, ten, 16000, 1, "cpu", false);
            vad = new Vad(context.getAssets(), cfg);
            Log.i(TAG, "Silero VAD ready (" + assetName + ")");
        } catch (Throwable t) {
            broken = true;
            vad = null;
            Log.e(TAG, "Silero VAD unavailable, falling back to energy gate: " + t);
        }
    }

    public boolean isUsable() {
        return vad != null && !broken;
    }

    /** Speech probability for exactly {@link #WINDOW} float samples in [-1, 1]. */
    /** Feed one 512-sample window into sherpa's segmenter (v5/v6 path). */
    public synchronized void accept(float[] window) {
        if (vad == null) return;
        try {
            vad.acceptWaveform(window);
        } catch (Throwable t) {
            Log.e(TAG, "acceptWaveform failed: " + t);
            broken = true;
        }
    }

    /** True while the segmenter believes speech is happening right now. */
    public synchronized boolean speechActive() {
        if (vad == null) return false;
        try {
            return vad.isSpeechDetected();
        } catch (Throwable t) {
            return false;
        }
    }

    public synchronized boolean hasSegment() {
        if (vad == null) return false;
        try {
            return !vad.empty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Pop the completed segment's samples (float32, 16 kHz). */
    public synchronized float[] takeSegment() {
        if (vad == null) return null;
        try {
            com.k2fsa.sherpa.onnx.SpeechSegment seg = vad.front();
            if (seg == null) return null;
            float[] samples = seg.getSamples();
            vad.pop();
            return samples;
        } catch (Throwable t) {
            Log.e(TAG, "takeSegment failed: " + t);
            return null;
        }
    }

    public synchronized float probability(float[] window) {
        if (vad == null) return 0f;
        try {
            float p = vad.compute(window);
            // A model whose I/O does not match the wrapper returns a flat 0 for every
            // frame — that used to leave the loop stuck in "Listening" forever.
            double energy = 0;
            for (float v : window) energy += v * v;
            energy = Math.sqrt(energy / window.length);
            if (p <= 0f && energy > 0.01) {
                zeroStreak++;
                if (zeroStreak > 40) {          // ~1.3s of audible audio scored as silence
                    broken = true;
                    Log.e(TAG, "VAD returning zero probability on audible input; disabling it");
                }
            } else if (p > 0f) {
                zeroStreak = 0;
            }
            return p;
        } catch (Throwable t) {
            Log.e(TAG, "compute failed: " + t);
            broken = true;
            return 0f;
        }
    }

    public synchronized void reset() {
        if (vad != null) {
            try {
                vad.reset();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void close() {
        if (vad != null) {
            try {
                vad.release();
            } catch (Throwable ignored) {}
            vad = null;
        }
    }
}
