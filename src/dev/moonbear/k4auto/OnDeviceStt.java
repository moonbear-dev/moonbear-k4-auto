package dev.moonbear.k4auto;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;

import java.io.File;

/** Parakeet TDT 0.6B v3, running on the phone. */
public final class OnDeviceStt {

    private static final String TAG = "OnDeviceStt";

    private final OfflineRecognizer recognizer;

    public OnDeviceStt(Context ctx) {
        File d = ModelManager.dir(ctx, "parakeet-v3-int8");

        OfflineTransducerModelConfig transducer = new OfflineTransducerModelConfig();
        transducer.setEncoder(new File(d, "encoder.int8.onnx").getAbsolutePath());
        transducer.setDecoder(new File(d, "decoder.int8.onnx").getAbsolutePath());
        transducer.setJoiner(new File(d, "joiner.int8.onnx").getAbsolutePath());

        OfflineModelConfig model = new OfflineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(new File(d, "tokens.txt").getAbsolutePath());
        model.setModelType("nemo_transducer");
        model.setNumThreads(4);
        model.setDebug(false);
        model.setProvider("cpu");

        OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
        cfg.setModelConfig(model);
        cfg.setDecodingMethod("greedy_search");

        long t0 = SystemClock.elapsedRealtime();
        recognizer = new OfflineRecognizer(ctx.getAssets(), cfg);
        Log.i(TAG, "parakeet loaded in " + (SystemClock.elapsedRealtime() - t0) + " ms");
    }

    /** samples: mono float in [-1,1] at the given rate (16 kHz expected). */
    public String transcribe(float[] samples, int sampleRate) {
        if (samples == null || samples.length == 0) return "";
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, sampleRate);
            recognizer.decode(stream);
            OfflineRecognizerResult r = recognizer.getResult(stream);
            String text = r != null ? r.getText() : "";
            return text == null ? "" : text.trim();
        } finally {
            stream.release();
        }
    }

    public void release() {
        try { recognizer.release(); } catch (Throwable ignored) {}
    }
}
