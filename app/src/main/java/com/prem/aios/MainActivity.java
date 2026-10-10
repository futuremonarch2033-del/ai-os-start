package com.prem.aios;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_MIC = 1001;
    private static final int REQ_CAMERA = 1002;
    private static final String ACT_NONE = "";
    private static final String ACT_RECORD = "record";
    private static final String ACT_LISTEN = "listen";
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent";

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

    private TextToSpeech tts;
    private boolean ttsInitDone = false;
    private boolean ttsReady = false;
    private boolean pendingSpeak = false;
    private String lastReply = "";
    private Button replayBtn;
    private boolean loopActive = false;
    private int consecutiveListenErrors = 0;
    private CameraManager cameraManager;
    private String torchCameraId;
    private boolean torchAvailable = false;
    private boolean torchOn = false;
    private int pendingTorch = 0;
    private TextView torchStatus;
    private Memory memory;
    // Conversation history (in RAM only, cleared on app restart): alternating user/model turns
    private final ArrayList<String[]> history = new ArrayList<String[]>();
    private static final int MAX_HISTORY_TURNS = 12;
    private TextView memoryStatus;
    private Button viewMemoryBtn;
    private Button forgetBtn;
    private boolean awaitingForgetConfirm = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioFile = new File(getCacheDir(), "voice_note.m4a");

        status = new TextView(this);
        status.setTextSize(20f);
        status.setGravity(Gravity.CENTER);
        status.setText("LISTEN = speak Marathi, AI replies below");

        memory = new Memory(this);
        memoryStatus = new TextView(this);
        memoryStatus.setTextSize(18f);
        memoryStatus.setGravity(Gravity.CENTER);
        viewMemoryBtn = new Button(this);
        viewMemoryBtn.setText("VIEW MEMORY");
        forgetBtn = new Button(this);
        forgetBtn.setText("FORGET EVERYTHING");
        updateMemoryStatus();

        torchStatus = new TextView(this);
        torchStatus.setTextSize(18f);
        torchStatus.setGravity(Gravity.CENTER);
        torchStatus.setText("Torch: OFF");

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

        replayBtn = new Button(this);
        replayBtn.setText("REPLAY VOICE");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(24, 14, 24, 14);
        layout.addView(status, lp);
        layout.addView(torchStatus, lp);
        layout.addView(memoryStatus, lp);
        layout.addView(listenBtn, lp);
        layout.addView(transcript, lp);
        layout.addView(reply, lp);
        layout.addView(replayBtn, lp);
        layout.addView(recordBtn, lp);
        layout.addView(playBtn, lp);
        layout.addView(viewMemoryBtn, lp);
        layout.addView(forgetBtn, lp);
        setContentView(layout);

        // ---- Step 7: flashlight (torch) setup ----
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(hasFlash)) {
                    torchCameraId = id;
                    if (facing != null
                            && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        break;
                    }
                }
            }
        } catch (CameraAccessException e) {
            torchCameraId = null;
        }
        torchAvailable = (torchCameraId != null);
        if (torchAvailable) {
            cameraManager.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (cameraId.equals(torchCameraId)) {
                        torchOn = enabled;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                torchStatus.setText(torchOn ? "Torch: ON" : "Torch: OFF");
                            }
                        });
                    }
                }

                @Override
                public void onTorchModeUnavailable(String cameraId) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            torchStatus.setText("Torch: unavailable (camera busy)");
                        }
                    });
                }
            }, null);
        }

        viewMemoryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Memory: " + memory.count() + " facts")
                        .setMessage(memory.asDisplay())
                        .setPositiveButton("OK", null)
                        .show();
            }
        });

        forgetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmForgetDialog();
            }
        });

        listenBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listening || loopActive) {
                    loopActive = false;
                    if (recognizer != null) {
                        recognizer.stopListening();
                    }
                    if (tts != null) {
                        tts.stop();
                    }
                    listening = false;
                    listenBtn.setText("LISTEN");
                    status.setText("Conversation stopped. LISTEN to start again");
                } else {
                    loopActive = true;
                    consecutiveListenErrors = 0;
                    ensureMicPermission(ACT_LISTEN);
                }
            }
        });

        recordBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loopActive = false;
                if (listening && recognizer != null) {
                    recognizer.stopListening();
                }
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

        replayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastReply.isEmpty()) {
                    status.setText("Nothing to replay yet - LISTEN first");
                } else {
                    speakReply();
                }
            }
        });

        // ---- Step 4: text-to-speech setup ----
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int initStatus) {
                ttsInitDone = true;
                if (initStatus == TextToSpeech.SUCCESS) {
                    int r = tts.setLanguage(new Locale("mr", "IN"));
                    ttsReady = (r == TextToSpeech.LANG_AVAILABLE
                            || r == TextToSpeech.LANG_COUNTRY_AVAILABLE
                            || r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE);
                } else {
                    ttsReady = false;
                }
                if (pendingSpeak) {
                    pendingSpeak = false;
                    speakReply();
                }
            }
        });

        // ---- Step 5: continuous hands-free loop ----
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) { }

            @Override
            public void onDone(String utteranceId) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        maybeContinueLoop();
                    }
                });
            }

            @Override
            public void onError(String utteranceId) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        maybeContinueLoop();
                    }
                });
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
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingTorch != 0) {
                    applyTorch(pendingTorch == 1);
                }
            } else {
                status.setText("Camera permission denied - cannot control torch");
                reply.setText("(torch not changed)");
            }
            pendingTorch = 0;
            return;
        }
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
                consecutiveListenErrors = 0;
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
                if (loopActive) {
                    consecutiveListenErrors++;
                    if (consecutiveListenErrors >= 5) {
                        loopActive = false;
                        listenBtn.setText("LISTEN");
                        status.setText("Stopped after repeated errors: " + errorText(error));
                    } else {
                        status.setText("Retrying... (" + errorText(error) + ")");
                        listenBtn.setText("STOP LISTEN");
                        maybeContinueLoop();
                    }
                } else {
                    listenBtn.setText("LISTEN");
                    status.setText("Error: " + errorText(error));
                }
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                consecutiveListenErrors = 0;
                if (!loopActive) {
                    listenBtn.setText("LISTEN");
                }
                String text = bestResult(results);
                if (text == null || text.isEmpty()) {
                    status.setText("Error: empty result");
                } else {
                    transcript.setText(text);
                    if (!handleMemoryCommand(text) && !handleLocalCommand(text)) {
                        askGemini(text);
                    }
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
                            maybeContinueLoop();
                        } else {
                            synchronized (history) {
                                history.add(new String[]{"user", userText});
                                history.add(new String[]{"model", result});
                                while (history.size() > MAX_HISTORY_TURNS * 2) {
                                    history.remove(0);
                                    history.remove(0);
                                }
                            }
                            status.setText("Done. LISTEN again?");
                            reply.setText(result);
                            lastReply = result;
                            if (ttsInitDone) {
                                speakReply();
                            } else {
                                pendingSpeak = true;
                            }
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

            String today = new java.text.SimpleDateFormat("EEEE, d MMMM yyyy",
                    Locale.US).format(new java.util.Date());
            JSONObject system = new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text",
                            "Today's date is " + today + "." + memory.asPrompt()
                            + " You are a personal AI voice assistant created by Prem Chavan."
                            + " Always reply in Marathi, in 1-3 short sentences."
                            + " If asked who made you, say Prem Chavan created you."
                            + " Never claim Google or any company made you.")));
            JSONArray contents = new JSONArray();
            synchronized (history) {
                for (String[] h : history) {
                    contents.put(new JSONObject().put("role", h[0]).put("parts",
                            new JSONArray().put(new JSONObject().put("text", h[1]))));
                }
            }
            contents.put(new JSONObject().put("role", "user").put("parts",
                    new JSONArray().put(new JSONObject().put("text", userText))));
            JSONObject body = new JSONObject()
                    .put("system_instruction", system)
                    .put("contents", contents);

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

    // ---- Step 5: restart listening so the conversation keeps going ----

    private void maybeContinueLoop() {
        if (!loopActive || listening) {
            return;
        }
        listenBtn.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (loopActive && !listening) {
                    startListening();
                }
            }
        }, 400);
    }

    // ---- Step 4: speak the reply aloud (Marathi voice) ----

    private void speakReply() {
        if (tts == null || !ttsReady) {
            status.setText("Marathi voice not installed - reply is text-only below."
                    + " Install: Settings > General management > Text-to-speech"
                    + " > Google TTS voice data > Marathi");
            maybeContinueLoop();
            return;
        }
        tts.stop();
        tts.speak(lastReply, TextToSpeech.QUEUE_FLUSH, null, "reply-utterance");
        status.setText("Speaking reply... (REPLAY VOICE to hear again)");
    }

    // ---- Memory: on-device command layer ----

    private void updateMemoryStatus() {
        memoryStatus.setText("Memory: " + memory.count() + " facts");
    }

    private void showLocalReply(String msg) {
        reply.setText(msg);
        lastReply = msg;
        updateMemoryStatus();
        if (ttsInitDone) {
            speakReply();
        } else {
            pendingSpeak = true;
        }
    }

    private void confirmForgetDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Forget everything?")
                .setMessage("This deletes all " + memory.count()
                        + " stored facts permanently.")
                .setPositiveButton("YES, DELETE", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        memory.clear();
                        updateMemoryStatus();
                        status.setText("Memory erased");
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private boolean handleMemoryCommand(String text) {
        if (awaitingForgetConfirm) {
            awaitingForgetConfirm = false;
            if (Memory.isYes(text)) {
                memory.clear();
                status.setText("Memory erased");
                showLocalReply("ठीक आहे, मी सगळं विसरलो.");
            } else {
                status.setText("Forget cancelled");
                showLocalReply("ठीक आहे, मी काहीही पुसलं नाही.");
            }
            return true;
        }
        if (Memory.isForgetAll(text)) {
            if (memory.count() == 0) {
                showLocalReply("माझ्या memory मध्ये काहीच नाही.");
            } else {
                awaitingForgetConfirm = true;
                status.setText("Confirm: say YES (हो) to erase " + memory.count() + " facts");
                showLocalReply("तुला खात्री आहे का? " + memory.count()
                        + " गोष्टी कायमच्या पुसल्या जातील. हो म्हण.");
            }
            return true;
        }
        String target = Memory.extractForgetTarget(text);
        if (target != null) {
            if (target.isEmpty()) {
                status.setText("Forget: say which fact");
                showLocalReply("कोणती गोष्ट विसरू ते सांग.");
                return true;
            }
            java.util.List<String> hits = memory.find(target);
            if (hits.isEmpty()) {
                status.setText("Forget: no matching fact");
                showLocalReply("तसं काही माझ्या memory मध्ये सापडलं नाही.");
            } else if (hits.size() > 1) {
                status.setText("Forget: " + hits.size() + " facts match, be more specific");
                showLocalReply("अनेक गोष्टी जुळतात, नीट सांग. VIEW MEMORY मध्ये पहा.");
            } else if (memory.remove(hits.get(0))) {
                status.setText("Forgot: " + hits.get(0));
                showLocalReply("ठीक आहे, विसरलो: " + hits.get(0));
            } else {
                status.setText("Error: could not delete fact");
                showLocalReply("त्रुटी: ती गोष्ट पुसता आली नाही.");
            }
            return true;
        }
        String fact = Memory.extractFact(text);
        if (fact != null) {
            if (fact.isEmpty()) {
                status.setText("Nothing to remember in that sentence");
                showLocalReply("काय लक्षात ठेवू ते सांग.");
            } else if (memory.add(fact)) {
                status.setText("Saved to memory");
                showLocalReply("ठीक आहे, लक्षात ठेवलं: " + fact);
            } else {
                status.setText("Not saved (duplicate, empty or memory full)");
                showLocalReply("हे आधीच लक्षात आहे किंवा memory भरली आहे.");
            }
            return true;
        }
        return false;
    }

    // ---- Step 7: on-device command layer - flashlight ----

    private boolean handleLocalCommand(String text) {
        String t = text.toLowerCase(Locale.US);
        boolean mentionsTorch = t.contains("टॉर्च") || t.contains("torch")
                || t.contains("flashlight") || t.contains("फ्लॅश") || t.contains("लाइट");
        if (!mentionsTorch) {
            return false;
        }
        boolean wantsOff = t.contains("बंद") || t.contains("ऑफ")
                || t.matches(".*\\boff\\b.*");
        boolean wantsOn = t.contains("चालू") || t.contains("सुरू")
                || t.contains("लाव") || t.contains("ऑन")
                || t.matches(".*\\bon\\b.*");
        if (wantsOff) {
            handleTorchCommand(false);
            return true;
        }
        if (wantsOn) {
            handleTorchCommand(true);
            return true;
        }
        return false;
    }

    private void handleTorchCommand(boolean turnOn) {
        if (!torchAvailable) {
            reply.setText("This phone has no flashlight hardware");
            status.setText("Torch not available on this device");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pendingTorch = turnOn ? 1 : -1;
            status.setText("Camera permission needed to control the torch");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        applyTorch(turnOn);
    }

    private void applyTorch(boolean turnOn) {
        try {
            cameraManager.setTorchMode(torchCameraId, turnOn);
            torchOn = turnOn;
            torchStatus.setText(turnOn ? "Torch: ON" : "Torch: OFF");
            String confirm = turnOn ? "टॉर्च चालू केला" : "टॉर्च बंद केला";
            reply.setText(confirm);
            lastReply = confirm;
            if (ttsInitDone) {
                speakReply();
            } else {
                pendingSpeak = true;
            }
        } catch (CameraAccessException | RuntimeException e) {
            status.setText("Torch failed: camera busy - close camera apps and retry");
            reply.setText("(torch not changed)");
        }
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
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
