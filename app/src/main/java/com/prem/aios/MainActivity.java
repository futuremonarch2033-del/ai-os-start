package com.prem.aios;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final int REQ_MIC = 1001;
    private static final String ACT_NONE = "";
    private static final String ACT_RECORD = "record";
    private static final String ACT_LISTEN = "listen";
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

    private TextView status;
    private TextView transcript;
    private TextView reply;
    private Button recordBtn;
    private Button playBtn;
    private Button listenBtn;

    private MediaRecorder recorder;
    private MediaPlayer player;
    private File audioFile;
    private boolean recording = false;

    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private String pendingAction = ACT_NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioFile = new File(getCacheDir(), "voice_note.m4a");

        status = new TextView(this);
        status.setTextSize(20f);
        status.setGravity(Gravity.CENTER);
        status.setText("LISTEN = speak Marathi, AI replies below");

        transcript = new TextView(this);
        transcript.setTextSize(22f);
        transcript.setGravity(Gravity.CENTER);
        transcript.setText("(your Marathi transcript)");

        reply = new TextView(this);
        reply.setTextSize(22f);
        reply.setGravity(Gravity.CENTER);
        reply.setText("(AI reply in Marathi)");

        listenBtn = new Button(this);
        listenBtn.setText("LISTEN");

        recordBtn = new Button(this);
        recordBtn.setText("RECORD");

        playBtn = new Button(this);
        playBtn.setText("PLAY");
        playBtn.setEnabled(false);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(24, 14, 24, 14);
        layout.addView(status, lp);
        layout.addView(listenBtn, lp);
        layout.addView(transcript, lp);
        layout.addView(reply, lp);
        layout.addView(recordBtn, lp);
        layout.addView(playBtn, lp);
        setContentView(layout);

        listenBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listening) {
                    if (recognizer != null) {
                        recognizer.stopListening();
                    }
                    status.setText("Finishing...");
                } else {
                    ensureMicPermission(ACT_LISTEN);
                }
            }
        });

        recordBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) {
                    stopRecording();
                } else {
                    ensureMicPermission(ACT_RECORD);
                }
            }
        });

        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playRecording();
            }
        });
    }

    private void ensureMicPermission(String action) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            if (ACT_LISTEN.equals(action)) {
                startListening();
            } else {
                startRecording();
            }
        } else {
            pendingAction = action;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (ACT_LISTEN.equals(pendingAction)) {
                    startListening();
                } else {
                    startRecording();
                }
            } else {
                status.setText("Mic permission denied - cannot listen or record");
            }
            pendingAction = ACT_NONE;
        }
    }

    // ---- Step 2: Marathi speech-to-text ----

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("Speech recognition not available on this phone");
            return;
        }
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                status.setText("Listening... speak Marathi");
            }

            @Override
            public void onBeginningOfSpeech() {
                status.setText("Hearing you...");
            }

            @Override
            public void onRmsChanged(float rmsdB) { }

            @Override
            public void onBufferReceived(byte[] buffer) { }

            @Override
            public void onEndOfSpeech() {
                status.setText("Processing...");
            }

            @Override
            public void onError(int error) {
                listening = false;
                listenBtn.setText("LISTEN");
                status.setText("Error: " + errorText(error));
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                listenBtn.setText("LISTEN");
                String text = bestResult(results);
                if (text == null || text.isEmpty()) {
                    status.setText("Error: empty result");
                } else {
                    transcript.setText(text);
                    askGemini(text);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                String text = bestResult(partialResults);
                if (text != null && !text.isEmpty()) {
                    transcript.setText(text + " ...");
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) { }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "mr-IN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizer.startListening(intent);
        listening = true;
        listenBtn.setText("STOP LISTEN");
    }

    private String bestResult(Bundle bundle) {
        ArrayList<String> matches =
                bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (matches == null || matches.isEmpty()) ? null : matches.get(0);
    }

    private String errorText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "network timeout - check internet";
            case SpeechRecognizer.ERROR_NETWORK:
                return "network error - check internet";
            case SpeechRecognizer.ERROR_AUDIO:
                return "audio recording problem";
            case SpeechRecognizer.ERROR_SERVER:
                return "speech service error - try again";
            case SpeechRecognizer.ERROR_CLIENT:
                return "app-side error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "no speech heard - try again";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "no speech matched - try again";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "recognizer busy - wait a moment";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "mic permission missing";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                return "too many requests - wait a bit";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                return "Marathi not supported by this phone's speech service";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "Marathi language data not on this phone";
            case SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT:
                return "cannot check language support";
            default:
                return "unknown (" + error + ")";
        }
    }

    // ---- Step 3: send transcript to Gemini brain, show reply ----

    private void askGemini(final String userText) {
        final String apiKey = BuildConfig.GEMINI_API_KEY;
        if (apiKey == null || apiKey.isEmpty()) {
            status.setText("No API key in this build - add GitHub secret and rebuild");
            return;
        }
        status.setText("Thinking...");
        reply.setText("...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = callGemini(apiKey, userText);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (result.startsWith("Error:")) {
                            status.setText(result);
                            reply.setText("(no reply)");
                        } else {
                            status.setText("Done. LISTEN again?");
                            reply.setText(result);
                        }
                    }
                });
            }
        }).start();
    }

    private String callGemini(String apiKey, String userText) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(GEMINI_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("x-goog-api-key", apiKey);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            JSONObject system = new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text",
                            "You are a helpful voice assistant."
                            + " Always reply in Marathi, in 1-3 short sentences.")));
            JSONObject content = new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", userText)));
            JSONObject body = new JSONObject()
                    .put("system_instruction", system)
                    .put("contents", new JSONArray().put(content));

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) {
                return "Error: Gemini HTTP " + code + " - " + snippet(resp);
            }
            JSONObject root = new JSONObject(resp);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                return "Error: Gemini returned no candidates";
            }
            JSONArray parts = candidates.getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                sb.append(parts.getJSONObject(i).optString("text", ""));
            }
            String text = sb.toString().trim();
            return text.isEmpty() ? "Error: empty reply from Gemini" : text;
        } catch (Exception e) {
            return "Error: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readAll(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toString("UTF-8");
    }

    private String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    // ---- Step 1: mic record + playback ----

    private void startRecording() {
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(96000);
        recorder.setAudioSamplingRate(44100);
        recorder.setOutputFile(audioFile.getAbsolutePath());
        try {
            recorder.prepare();
            recorder.start();
            recording = true;
            recordBtn.setText("STOP");
            playBtn.setEnabled(false);
            status.setText("Recording... tap STOP");
        } catch (IOException | RuntimeException e) {
            status.setText("Record failed: " + e.getMessage());
            recorder.release();
            recorder = null;
        }
    }

    private void stopRecording() {
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            // stop() throws if the recording was too short; file may be unusable
        }
        recorder.release();
        recorder = null;
        recording = false;
        recordBtn.setText("RECORD");
        playBtn.setEnabled(audioFile.exists() && audioFile.length() > 0);
        status.setText("Saved. Tap PLAY to hear it");
    }

    private void playRecording() {
        if (player != null) {
            player.release();
        }
        player = new MediaPlayer();
        try {
            player.setDataSource(audioFile.getAbsolutePath());
            player.prepare();
            player.start();
            status.setText("Playing...");
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    status.setText("Done. RECORD again?");
                }
            });
        } catch (IOException e) {
            status.setText("Play failed: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
