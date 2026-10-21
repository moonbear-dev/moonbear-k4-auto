package dev.moonbear.k4auto;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import dev.moonbear.k4auto.R;
import android.util.Log;

import androidx.media.MediaBrowserServiceCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Voice service: probe a mic source that works, capture with Silero v6, stream Kokoro PCM back.
 *
 * This keeps the two behaviours the rewrite lost:
 *   - mic source probing (some sources init fine and return zeros under Android Auto)
 *   - a jitter-buffered player with silence fill, so sentence gaps don't underrun
 * and drops everything that never worked: barge-in, local TTS, espeak, MP3 fallback.
 */
public class MoonbearCarAudioService extends MediaBrowserServiceCompat {

    private static final String TAG = "MoonbearService";
    private static final String CHANNEL_ID = "moonbear_voice";
    private static final String ROOT_ID = "root";

    public static final String ACTION_COMMAND = "dev.moonbear.k4auto.COMMAND";
    public static final String ACTION_STATE_UPDATE = "dev.moonbear.k4auto.STATE_UPDATE";

    public static final String CMD_START_LOOP = "start_loop";
    public static final String CMD_STOP = "stop";
    public static final String CMD_REPLAY = "replay";

    public static final String STATE_IDLE = "IDLE";
    public static final String STATE_LISTENING = "LISTENING";
    public static final String STATE_THINKING = "THINKING";
    public static final String STATE_SPEAKING = "SPEAKING";

    private static final int SAMPLE_RATE = 16000;
    private static final int PCM_RATE = 24000;
    private static final int SILENCE_MS = 1400;  // give the speaker room to pause without losing the turn
    private static final int MAX_TURN_MS = 60000;
    private static final int NO_SPEECH_MS = 15000;
    private static final float PREAMP = 4.0f;   // enough to lift the mic; higher clips and wrecks ASR

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService side = Executors.newCachedThreadPool();   // all async work
    private Thread turnThread;

    private MoonbearConfig config;
    private MoonbearLocation location;
    private AudioManager audioManager;
    private MediaSessionCompat session;
    private OnDeviceStt stt;
    private OnDeviceTts tts;
    private volatile boolean onDevice;
    private static final int BEAR_SID = 0;   // kokoro voices.bin index
    private SileroVad vad;

    private volatile boolean running;
    private volatile android.media.AudioTrack streamTrack;
    private volatile boolean streamCancelled;

    // last published snapshot — the UI reads this on resume so a broadcast that
    // lands while the activity is paused can never leave it stuck on a stale state
    static volatile String snapState = STATE_IDLE;
    static volatile String snapHeadline = "";
    static volatile String snapSubline = "";
    static volatile String snapLocation = "";

    public static String[] snapshot() {
        return new String[] { snapState, snapHeadline, snapSubline, snapLocation };
    }

    private String state = STATE_IDLE;
    private String headline = "Just talk";
    private String subline = "Listening over the Kia K4 mic";
    private volatile String lastSpoken = "";

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        config = new MoonbearConfig(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        location = new MoonbearLocation(this);
        createChannel();

        session = new MediaSessionCompat(this, "moonbear");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { ensureListening(); }
            @Override public void onSkipToNext() { ensureListening(); }
        });
        session.setActive(true);
        setSessionToken(session.getSessionToken());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(ROOT_ID, new Bundle());
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaItem>> result) {
        List<MediaItem> items = new ArrayList<>();
        MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                .setMediaId("moonbear.live")
                .setTitle("Moonbear")
                .setSubtitle("Continuous voice — just talk")
                .build();
        items.add(new MediaItem(desc, MediaItem.FLAG_BROWSABLE));
        result.sendResult(items);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, notification());
        }
        String cmd = (intent == null) ? null : intent.getStringExtra("cmd");
        if (cmd == null) cmd = CMD_START_LOOP;
        switch (cmd) {
            case CMD_STOP:
                stopEverything();
                break;
            case CMD_REPLAY:
                replayLast();
                break;
            default:
                ensureListening();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopEverything();
        if (session != null) {
            session.release();
            session = null;
        }
        side.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ loop

    public void ensureListening() {
        if (!MoonbearAuth.isPaired(this)) {
            publish(STATE_IDLE, "Pair this phone", "Pairing is approved in Discord");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish(STATE_IDLE, "Mic permission needed", "Grant microphone access to Moonbear Auto");
            return;
        }
        if (running) return;
        running = true;
        if (!location.hasUsableFix()) location.start();
        if (ModelManager.ready(this) && stt == null && !onDevice) {
            side.submit(() -> {
                try {
                    long t0 = android.os.SystemClock.elapsedRealtime();
                    stt = new OnDeviceStt(this);
                    tts = new OnDeviceTts(this);
                    onDevice = true;
                    report("on-device ready in " + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
                } catch (Throwable t) {
                    report("on-device init failed: " + t);
                }
            });
        }
        turnThread = new Thread(this::turnLoop, "moonbear-turn");
        turnThread.setDaemon(true);
        turnThread.start();
    }

    private void turnLoop() {
        while (running) {
            try {
                // no fallback: if the on-device engines are not loaded, say so and wait
                if (stt == null || tts == null) {
                    publish(STATE_IDLE, "Voice models missing", "On-device models not installed");
                    sleep(2000);
                    continue;
                }

                byte[] wav = captureTurn();
                if (wav == null) continue;

                publish(STATE_THINKING, "Thinking…", "moonbear is answering");

                String heard = stt.transcribe(wavToFloats(wav), SAMPLE_RATE);
                if (heard.isEmpty()) {
                    publish(STATE_LISTENING, "Listening…", "Didn't catch that");
                    continue;
                }

                JSONObject reply = sendText(heard);
                if (reply == null) {
                    publish(STATE_LISTENING, "Listening…", "Bridge unreachable — retrying");
                    continue;
                }
                String spoken = reply.optString("reply", "");
                if (spoken.isEmpty()) {
                    publish(STATE_LISTENING, "Listening…", "Say that again");
                    continue;
                }

                lastSpoken = spoken;
                publish(STATE_SPEAKING, spoken, "on-device voice");
                playSamples(tts.say(spoken, BEAR_SID));
                publish(STATE_LISTENING, "Listening…", "Just talk");
            } catch (Throwable t) {
                Log.e(TAG, "turn failed", t);
                publish(STATE_LISTENING, "Listening…", "Recovering");
            }
        }
    }

    private void replayLast() {
        final String text = lastSpoken;
        if (text.isEmpty()) { ensureListening(); return; }
        side.submit(() -> {
            publish(STATE_SPEAKING, text, "on-device voice");
            if (tts != null) playSamples(tts.say(text, BEAR_SID));
            publish(STATE_LISTENING, "Listening…", "Just talk");
        });
    }

    /** 16-bit PCM WAV -> mono floats; tolerates a standard 44-byte header. */
    private float[] wavToFloats(byte[] wav) {
        int off = 44;
        if (wav.length < 48) return new float[0];
        int n = (wav.length - off) / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = wav[off + i * 2] & 0xff;
            int hi = wav[off + i * 2 + 1];
            out[i] = ((short) ((hi << 8) | lo)) / 32768f;
        }
        return out;
    }

    /** Text-only turn: the phone has already transcribed and will speak locally. */
    private JSONObject sendText(String text) {
        try {
            JSONObject body = new JSONObject();
            body.put("text", text);
            body.put("tts", "none");
            body.put("voice", "bear");
            body.put("mirror_discord", false);
            java.text.SimpleDateFormat iso =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US);
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            iso.setTimeZone(tz);
            body.put("device_time", iso.format(new java.util.Date()));
            body.put("device_timezone", tz.getID());
            if (location != null && location.hasUsableFix()) body.put("location", location.asJson());
            String json = MoonbearHttp.callSigned(this, config.getServerUrl(),
                    "/api/moonbear-voice/talk", "POST", body.toString(), 60_000);
            return json == null ? null : new JSONObject(json);
        } catch (Exception e) {
            Log.e(TAG, "sendText failed", e);
            return null;
        }
    }

    private String streamPathFor(String text) {
        try {
            JSONObject body = new JSONObject();
            body.put("text", text);
            body.put("stream", true);
            String json = MoonbearHttp.callSigned(this, config.getServerUrl(),
                    "/api/moonbear-voice/talk", "POST", body.toString(), 60_000);
            return json == null ? "" : new JSONObject(json).optString("pcm_path", "");
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ capture

    private byte[] captureTurn() {
        if (vad == null) vad = new SileroVad(this);
        vad.reset();

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBuf * 2, 8192);

        int[] candidates = {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        AudioRecord recorder = null;
        int bestPeak = -1;
        for (int src : candidates) {
            AudioRecord probe = new AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (probe.getState() != AudioRecord.STATE_INITIALIZED) {
                try { probe.release(); } catch (Exception ignored) {}
                continue;
            }
            probe.startRecording();
            short[] tmp = new short[SileroVad.WINDOW];
            int peak = 0;
            long until = System.currentTimeMillis() + 350;
            while (System.currentTimeMillis() < until) {
                int n = probe.read(tmp, 0, tmp.length);
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) {
                    int a = Math.abs((int) tmp[i]);
                    if (a > peak) peak = a;
                }
            }
            report("source=" + src + " peak=" + peak);
            // pick the LOUDSET source, not the first that clears the floor:
            // VOICE_COMMUNICATION often scrapes past it at peak ~25 while returning near-silence
            if (peak > bestPeak) {
                if (recorder != null) {
                    try { recorder.stop(); } catch (Exception ignored) {}
                    try { recorder.release(); } catch (Exception ignored) {}
                }
                recorder = probe;
                bestPeak = peak;
            } else {
                try { probe.stop(); } catch (Exception ignored) {}
                try { probe.release(); } catch (Exception ignored) {}
            }
        }
        if (recorder != null && bestPeak <= 20) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (recorder == null) {
            publish(STATE_LISTENING, "Mic silent", "All mic sources returned zeros");
            return null;
        }

        publish(STATE_LISTENING, "Listening…", "Just talk");
        tone(720, 45, 4500);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        short[] buf = new short[SileroVad.WINDOW];
        float[] frame = new float[SileroVad.WINDOW];
        short[] carry = new short[SileroVad.WINDOW * 2];
        int carryLen = 0;
        // record RAW frames once speech starts — relying on the segmenter's output
        // dropped everything between emitted segments, which is what "missed audio" was.
        float[][] preroll = new float[10][];
        int prerollCount = 0, prerollNext = 0;
        boolean capturing = false;
        int speechFrames = 0;
        long started = System.currentTimeMillis();
        long lastSpeech = started;

        try {
            while (running) {
                int n = recorder.read(buf, 0, buf.length);
                if (n <= 0) continue;
                System.arraycopy(buf, 0, carry, carryLen, n);
                carryLen += n;

                while (carryLen >= SileroVad.WINDOW) {
                    System.arraycopy(carry, 0, buf, 0, SileroVad.WINDOW);
                    int rest = carryLen - SileroVad.WINDOW;
                    if (rest > 0) System.arraycopy(carry, SileroVad.WINDOW, carry, 0, rest);
                    carryLen = rest;

                    for (int i = 0; i < SileroVad.WINDOW; i++) frame[i] = buf[i] / 32768f;
                    vad.accept(frame);
                    boolean speaking = vad.speechActive();
                    long now = System.currentTimeMillis();

                    if (!capturing) {
                        preroll[prerollNext] = frame.clone();
                        prerollNext = (prerollNext + 1) % preroll.length;
                        if (prerollCount < preroll.length) prerollCount++;
                        if (speaking) {
                            capturing = true;
                            for (int k = 0; k < prerollCount; k++) {
                                int idx = (prerollNext - prerollCount + k + preroll.length * 2) % preroll.length;
                                appendFrames(out, preroll[idx]);
                            }
                            lastSpeech = now;
                        } else if (now - started > NO_SPEECH_MS) {
                            return null;
                        }
                        continue;
                    }

                    appendFrames(out, frame);
                    if (speaking) { lastSpeech = now; speechFrames++; }
                    if (now - lastSpeech > SILENCE_MS) {
                        // anything under ~0.7s is a blip, not an utterance: sending it
                        // makes the recogniser invent a word out of noise
                        if (out.size() < SAMPLE_RATE * 2 * 45 / 100) return null;   // 0.45s of audio
                        if (speechFrames * SileroVad.WINDOW < SAMPLE_RATE * 15 / 100) return null;   // 0.15s of actual speech
                        report("cap " + (out.size() / 2 * 1000 / SAMPLE_RATE) + "ms speech="
                                + (speechFrames * SileroVad.WINDOW * 1000 / SAMPLE_RATE) + "ms");
                        return wav(out.toByteArray());
                    }
                    if (now - started > MAX_TURN_MS) {
                        report("cap hit max: " + out.size() / 2 + " frames, speech=" + speechFrames);
                        return wav(out.toByteArray());
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "capture failed", t);
        } finally {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
        }
        return null;
    }

    private void appendFrames(ByteArrayOutputStream out, float[] samples) {
        for (float s : samples) {
            int v = (int) (s * 32768f * PREAMP);
            if (v > 32700) v = 32700;
            if (v < -32700) v = -32700;
            out.write(v & 0xff);
            out.write((v >> 8) & 0xff);
        }
    }

    private byte[] wav(byte[] pcm) {
        int dataLen = pcm.length;
        byte[] out = new byte[44 + dataLen];
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        int fileLen = 36 + dataLen;
        out[4] = (byte) (fileLen & 0xff); out[5] = (byte) ((fileLen >> 8) & 0xff);
        out[6] = (byte) ((fileLen >> 16) & 0xff); out[7] = (byte) ((fileLen >> 24) & 0xff);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        out[16] = 16; out[20] = 1; out[22] = 1;                 // PCM, mono
        out[24] = (byte) (SAMPLE_RATE & 0xff); out[25] = (byte) ((SAMPLE_RATE >> 8) & 0xff);
        int byteRate = SAMPLE_RATE * 2;
        out[28] = (byte) (byteRate & 0xff); out[29] = (byte) ((byteRate >> 8) & 0xff);
        out[30] = (byte) ((byteRate >> 16) & 0xff); out[31] = (byte) ((byteRate >> 24) & 0xff);
        out[32] = 2; out[34] = 16;
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';   // REQUIRED
        // four bytes, not two: a 4s capture is ~142 KB and the high word was being dropped,
        // so the bridge only ever read (dataLen mod 65536) bytes of audio
        out[40] = (byte) (dataLen & 0xff); out[41] = (byte) ((dataLen >> 8) & 0xff);
        out[42] = (byte) ((dataLen >> 16) & 0xff); out[43] = (byte) ((dataLen >> 24) & 0xff);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    // ------------------------------------------------------------------ bridge

    private JSONObject send(byte[] wavBytes) {
        try {
            JSONObject body = new JSONObject();
            body.put("audio_b64", android.util.Base64.encodeToString(wavBytes, android.util.Base64.NO_WRAP));
            body.put("stream", true);
            body.put("mirror_discord", config.isMirrorDiscord());
            TimeZone tz = TimeZone.getDefault();
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            iso.setTimeZone(tz);
            body.put("device_time", iso.format(new Date()));
            body.put("device_timezone", tz.getID());
            if (location.hasUsableFix()) body.put("location", location.asJson());
            String json = MoonbearHttp.callSigned(this, config.getServerUrl(),
                    "/api/moonbear-voice/talk", "POST", body.toString(), 125_000);
            return json == null ? null : new JSONObject(json);
        } catch (Throwable t) {
            Log.e(TAG, "send failed", t);
            return null;
        }
    }


    /** Short earcon, the way Siri/Assistant acknowledge you. Non-blocking. */
    private void tone(final int freq, final int ms, final int amp) {
        side.submit(() -> {
            android.media.AudioTrack t = null;
            try {
                int n = PCM_RATE * ms / 1000;
                short[] pcm = new short[n];
                for (int i = 0; i < n; i++) {
                    double fade = Math.min(1.0, Math.min(i, n - i) / (PCM_RATE * 0.008));
                    pcm[i] = (short) (Math.sin(2 * Math.PI * freq * i / PCM_RATE) * amp * fade);
                }
                t = new android.media.AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(PCM_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(Math.max(n * 2, 4096))
                        .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                        .build();
                if (t.getState() != android.media.AudioTrack.STATE_INITIALIZED) return;
                t.write(pcm, 0, n);
                t.play();
                sleep(ms + 60);
            } catch (Throwable ignored) {
            } finally {
                if (t != null) {
                    try { t.stop(); } catch (Exception ignored) {}
                    try { t.release(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /** Read the PCM stream on one thread, play it from a queue on another. */
    private void playPcm(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            InputStream remote = MoonbearHttp.openSignedStream(this, config.getServerUrl(), path, 12_000, 60_000);
            if (remote == null) { report("stream=null"); return; }
            playPcmStream(remote);
        } catch (Throwable t) {
            Log.e(TAG, "remote playback failed", t);
        }
    }

    /** Play 24 kHz mono float samples produced on the device. */
    private void playSamples(float[] samples) {
        if (samples == null || samples.length == 0) return;
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int v = (int) (samples[i] * 32767f);
            if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
            pcm[i * 2] = (byte) (v & 0xff);
            pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        playPcmStream(new java.io.ByteArrayInputStream(pcm));
    }

    private void playPcmStream(InputStream in) {
        android.media.AudioTrack track = null;
        try {

            int minBuf = android.media.AudioTrack.getMinBufferSize(PCM_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            // Legacy STREAM_MUSIC constructor: the same path media apps use, which is the
            // route the car actually drains. The Builder/USAGE_MEDIA track reported
            // PLAYSTATE_PLAYING with head stuck at 0.
            track = new android.media.AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    PCM_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf * 4, PCM_RATE * 2),
                    android.media.AudioTrack.MODE_STREAM);
            try { track.setVolume(1.0f); } catch (Throwable ignored) {}
            streamTrack = track;
            streamCancelled = false;
            tone(1180, 55, 6000);      // "here it comes"
            requestFocus();
            track.play();

            final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(128);
            final InputStream stream = in;
            final boolean[] done = {false};
            Thread reader = new Thread(() -> {
                byte[] chunk = new byte[8192];
                int carryByte = -1;
                try {
                    int n;
                    int idle = 0;
                    while (!streamCancelled) {
                        n = stream.read(chunk);
                        if (n < 0) break;                  // real EOF
                        if (n == 0) {
                            // a chunked stream can return 0 without being finished;
                            // treating that as EOF was cutting replies short
                            if (++idle > 600) break;       // ~6s of nothing: give up
                            try { Thread.sleep(10); } catch (InterruptedException ie) { break; }
                            continue;
                        }
                        idle = 0;
                        int len = n;
                        byte[] src;
                        if (carryByte >= 0) {
                            byte[] joined = new byte[len + 1];
                            joined[0] = (byte) carryByte;
                            System.arraycopy(chunk, 0, joined, 1, len);
                            src = joined;
                            len += 1;
                            carryByte = -1;
                        } else {
                            src = java.util.Arrays.copyOf(chunk, len);
                        }
                        if ((len & 1) != 0) { carryByte = src[len - 1] & 0xff; len -= 1; }
                        if (len > 0) queue.put(java.util.Arrays.copyOf(src, len));
                    }
                } catch (Throwable ignored) {
                } finally {
                    done[0] = true;
                }
            }, "pcm-reader");
            reader.setDaemon(true);
            reader.start();

            byte[] silence = new byte[PCM_RATE / 25];     // 40 ms
            long written = 0;
            while (running && !streamCancelled) {
                byte[] chunk = queue.poll(40, TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    writeAll(track, chunk);
                    written += chunk.length;
                } else if (done[0] && queue.isEmpty()) {
                    break;
                } else {
                    writeAll(track, silence);             // keep the pipe fed
                }
            }
            long frames = written / 2;
            long deadline = System.currentTimeMillis() + 8000;
            while (!streamCancelled && System.currentTimeMillis() < deadline
                    && track.getPlaybackHeadPosition() < frames) {
                sleep(50);
            }
            // the mixer still holds a few tens of ms after the last frame; stopping
            // immediately clips the final syllable
            sleep(250);
            report("play=" + track.getPlayState() + " underruns=" + track.getUnderrunCount()
                    + " written=" + written
                    + " head=" + track.getPlaybackHeadPosition() + " frames=" + frames);
        } catch (Throwable t) {
            Log.e(TAG, "playback failed", t);
            report("exception=" + t);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
            }
            streamTrack = null;
        }
    }

    private void writeAll(android.media.AudioTrack track, byte[] data) {
        int usable = data.length & ~1;
        int off = 0;
        while (off < usable && !streamCancelled) {
            int w = track.write(data, off, usable - off);
            if (w <= 0) break;
            off += w;
        }
    }

    private android.media.AudioFocusRequest focusRequest;

    private void requestFocus() {
        try {
            if (audioManager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                focusRequest = new android.media.AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs).build();
                audioManager.requestAudioFocus(focusRequest);
            } else {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Throwable t) {
            Log.w(TAG, "focus failed: " + t);
        }
    }

    // ------------------------------------------------------------------ control

    private void stopEverything() {
        running = false;
        streamCancelled = true;
        android.media.AudioTrack t = streamTrack;
        if (t != null) {
            try { t.stop(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
            streamTrack = null;
        }
        if (location != null) location.stop();
        publish(STATE_IDLE, "Stopped", "Open Moonbear Auto to start again");
    }

    private void publish(String newState, String head, String sub) {
        state = newState;
        headline = head;
        publishMetadata(head);
        subline = sub;
        snapState = state;
        snapHeadline = headline;
        snapSubline = subline;
        Intent i = new Intent(ACTION_STATE_UPDATE);
        i.setPackage(getPackageName());
        i.putExtra("state", state);
        i.putExtra("headline", headline);
        i.putExtra("subline", subline);
        if (location != null && location.hasUsableFix()) {
            snapLocation = location.summary();
            i.putExtra("location", snapLocation);
        }
        sendBroadcast(i);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try { nm.notify(1, notification()); } catch (Exception ignored) {}
        }
    }

    /** One line to the bridge: mic source, playback state. No screenshots needed. */
    private void report(String msg) {
        try {
            JSONObject body = new JSONObject();
            body.put("debug", msg);
            final String payload = body.toString();
            side.submit(() -> {
                try {
                    MoonbearHttp.callSigned(this, config.getServerUrl(),
                            "/api/moonbear-voice/debug", "POST", payload, 8_000);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private Bitmap art() {
        try {
            return BitmapFactory.decodeResource(getResources(), R.drawable.moonbear_art);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void publishMetadata(String title) {
        try {
            MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title == null ? "Moonbear" : title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Kia K4")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Moonbear Auto")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1);
            Bitmap b = art();
            if (b != null) md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, b);
            session.setMetadata(md.build());
            session.setActive(true);
        } catch (Throwable ignored) {
        }
    }

    private Notification notification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Moonbear — " + headline)
                .setContentText(subline)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setLargeIcon(art())
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Moonbear voice",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
