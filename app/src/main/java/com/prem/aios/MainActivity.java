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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
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
            "https://prem-brain--brain.futuremonarch2033-del.deno.net/generate";

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
    private int consecutiveSilence = 0;
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
    private Button clearChatBtn;
    private static final int REQ_NOTIF = 1003;
    private TextView reminderStatus;
    private Button viewRemindersBtn;
    private boolean awaitingReminderConfirm = false;
    private Reminders.Item pendingReminder = null;
    private boolean awaitingAlarmConfirm = false;
    private int pendingAlarmHour = -1, pendingAlarmMin = -1;
    private String pendingAlarmLabel = "";
    private boolean awaitingSettingsReturn = false;
    private long settingsLaunchedAt = 0L;
    private boolean awaitingForgetConfirm = false;
    private android.widget.EditText pairInput;
    private Button pairBtn;
    private LinearLayout pairBox;

    // Device token comes from private app storage after pairing; the build-time token is only a legacy fallback.
    private String authToken() {
        String t = getSharedPreferences("aios_prefs", MODE_PRIVATE).getString("device_token", "");
        if (t != null && !t.isEmpty()) {
            return t;
        }
        return BuildConfig.BRAIN_TOKEN;
    }

    private boolean isPaired() {
        String t = getSharedPreferences("aios_prefs", MODE_PRIVATE).getString("device_token", "");
        return t != null && !t.isEmpty();
    }

    private void doPair() {
        final String code = pairInput.getText().toString().trim();
        if (code.isEmpty()) {
            status.setText("Type the pairing code first");
            return;
        }
        status.setText("Pairing...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                boolean ok = false;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(GEMINI_URL.replace("/generate", "/pair")).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);
                    conn.setDoOutput(true);
                    JSONObject body = new JSONObject().put("code", code)
                            .put("name", android.os.Build.MODEL);
                    OutputStream os = conn.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8"));
                    os.close();
                    int rc = conn.getResponseCode();
                    InputStream is = (rc >= 200 && rc < 300) ? conn.getInputStream() : conn.getErrorStream();
                    JSONObject o = new JSONObject(readAll(is));
                    if (rc >= 200 && rc < 300 && o.optBoolean("ok", false)) {
                        getSharedPreferences("aios_prefs", MODE_PRIVATE).edit()
                                .putString("device_token", o.getString("token")).apply();
                        ok = true;
                        msg = "Paired OK (device " + o.optString("id") + ")";
                    } else {
                        msg = "Pairing failed: " + o.optString("error", "HTTP " + rc);
                    }
                } catch (Exception e) {
                    msg = "Pairing failed: " + e.getMessage();
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
                final String fmsg = msg;
                final boolean fok = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(fmsg);
                        if (fok) {
                            pairInput.setText("");
                            pairBox.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }).start();
    }

    private void onAuthRejected() {
        if (isPaired()) {
            getSharedPreferences("aios_prefs", MODE_PRIVATE).edit().remove("device_token").apply();
            pairBox.setVisibility(View.VISIBLE);
            status.setText("This device was rejected (revoked?). Enter a new pairing code and tap PAIR");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioFile = new File(getCacheDir(), "voice_note.m4a");

        status = new TextView(this);
        status.setTextSize(20f);
        status.setGravity(Gravity.CENTER);
        status.setText("");

        memory = new Memory(this);
        memoryStatus = new TextView(this);
        memoryStatus.setTextSize(18f);
        memoryStatus.setGravity(Gravity.CENTER);
        viewMemoryBtn = new Button(this);
        viewMemoryBtn.setText("VIEW MEMORY");
        forgetBtn = new Button(this);
        forgetBtn.setText("FORGET EVERYTHING");
        clearChatBtn = new Button(this);
        clearChatBtn.setText("CLEAR CONVERSATION");
        reminderStatus = new TextView(this);
        reminderStatus.setTextSize(18f);
        reminderStatus.setGravity(Gravity.CENTER);
        viewRemindersBtn = new Button(this);
        viewRemindersBtn.setText("VIEW REMINDERS");
        updateReminderStatus();
        updateMemoryStatus();

        torchStatus = new TextView(this);
        torchStatus.setTextSize(18f);
        torchStatus.setGravity(Gravity.CENTER);
        torchStatus.setText("Torch: OFF");

        transcript = new TextView(this);
        transcript.setTextSize(22f);
        transcript.setGravity(Gravity.CENTER);
        transcript.setText("");

        reply = new TextView(this);
        reply.setTextSize(22f);
        reply.setGravity(Gravity.CENTER);
        reply.setText("");

        listenBtn = new Button(this);
        listenBtn.setText("LISTEN");

        recordBtn = new Button(this);
        recordBtn.setText("RECORD");

        playBtn = new Button(this);
        playBtn.setText("PLAY");
        playBtn.setEnabled(false);

        replayBtn = new Button(this);
        replayBtn.setText("REPLAY VOICE");

        buildUi();

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

        viewRemindersBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRemindersDialog();
            }
        });

        clearChatBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearConversation();
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
                    consecutiveSilence = 0;
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

    // ---- V2 arc-reactor interface ----
    private OrbView orb;
    private LinearLayout confirmRow;
    private EditText inputEdit;
    private TextView micView;
    private TextView subtitleView;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        }
        return g;
    }

    private void routeText(String text) {
        transcript.setText(text);
        if (!handleReminderCommand(text) && !handleAlarmCommand(text) && !handleMemoryCommand(text)
                && !handleLocalCommand(text)) {
            askGemini(text);
        }
    }

    private void sendTyped() {
        String t = inputEdit.getText().toString().trim();
        if (t.isEmpty()) {
            return;
        }
        inputEdit.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(inputEdit.getWindowToken(), 0);
        }
        if (!isPaired() && authToken().isEmpty()) {
            status.setText("Not paired - enter the pairing code and tap PAIR");
            return;
        }
        routeText(t);
    }

    private boolean awaitingAnyConfirm() {
        return awaitingReminderConfirm || awaitingAlarmConfirm || awaitingForgetConfirm;
    }

    private TextView chip(String label, int textColor, int stroke) {
        TextView c = new TextView(this);
        c.setText(label);
        c.setTextColor(textColor);
        c.setTextSize(17f);
        c.setTypeface(Typeface.DEFAULT_BOLD);
        c.setGravity(Gravity.CENTER);
        c.setBackground(shape(0xFF061A2B, stroke, 22, 1.5f));
        c.setPadding(dp(30), dp(10), dp(30), dp(10));
        return c;
    }

    private void buildUi() {
        final int CY = 0xFF13D8FF;
        getWindow().setStatusBarColor(0xFF030A14);
        getWindow().setNavigationBarColor(0xFF030A14);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF030A14);
        root.setPadding(dp(14), dp(10), dp(14), dp(12));

        // header: title + subtitle centered, menu dots right
        FrameLayout header = new FrameLayout(this);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER);
        TextView title = new TextView(this);
        title.setText("A I   O S");
        title.setTextColor(CY);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.25f);
        title.setGravity(Gravity.CENTER);
        subtitleView = new TextView(this);
        subtitleView.setTextColor(0xFF6E8FA0);
        subtitleView.setTextSize(13f);
        subtitleView.setGravity(Gravity.CENTER);
        titleBox.addView(title);
        titleBox.addView(subtitleView);
        header.addView(titleBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView dots = new TextView(this);
        dots.setText("\u22EE");
        dots.setTextColor(CY);
        dots.setTextSize(28f);
        dots.setTypeface(Typeface.DEFAULT_BOLD);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(dp(12), 0, dp(12), 0);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(dots, dlp);
        dots.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        });
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // orb: fixed share of the screen so chat keeps room
        orb = new OrbView(this);
        int orbSize = Math.min(getResources().getDisplayMetrics().widthPixels - dp(40),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.36f));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(orbSize, orbSize);
        olp.gravity = Gravity.CENTER_HORIZONTAL;
        olp.topMargin = dp(8);
        root.addView(orb, olp);

        // status line under the orb
        status.setTextColor(CY);
        status.setTextSize(18f);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), 0, dp(8), dp(6));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // pairing box (only when not paired)
        pairBox = new LinearLayout(this);
        pairBox.setOrientation(LinearLayout.VERTICAL);
        pairBox.setPadding(dp(14), dp(8), dp(14), dp(8));
        pairBox.setBackground(shape(0xFF061A2B, 0xFF0B5E78, 14, 1f));
        TextView pairLabel = new TextView(this);
        pairLabel.setText("Pair this device (one-time code)");
        pairLabel.setTextColor(0xFFB6E8F5);
        pairInput = new EditText(this);
        pairInput.setHint("pairing code");
        pairInput.setHintTextColor(0xFF4F7183);
        pairInput.setTextColor(Color.WHITE);
        pairInput.setSingleLine(true);
        pairBtn = new Button(this);
        pairBtn.setText("PAIR");
        pairBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doPair();
            }
        });
        pairBox.addView(pairLabel);
        pairBox.addView(pairInput);
        pairBox.addView(pairBtn);
        pairBox.setVisibility(isPaired() ? View.GONE : View.VISIBLE);
        root.addView(pairBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // chat area
        LinearLayout chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(dp(4), dp(6), dp(4), dp(6));

        transcript.setTextColor(0xFF03121C);
        transcript.setTextSize(17f);
        transcript.setGravity(Gravity.START);
        transcript.setBackground(shape(0xFF00BFF2, 0, 20, 0));
        transcript.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.END;
        tlp.topMargin = dp(8);
        tlp.leftMargin = dp(60);
        chat.addView(transcript, tlp);

        reply.setTextColor(0xFFEAF6FB);
        reply.setTextSize(17f);
        reply.setGravity(Gravity.START);
        reply.setBackground(shape(0xFF061A2B, 0xFF0B7FA0, 20, 1f));
        reply.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.gravity = Gravity.START;
        rlp.topMargin = dp(10);
        rlp.rightMargin = dp(40);
        chat.addView(reply, rlp);

        confirmRow = new LinearLayout(this);
        confirmRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView yes = chip("हो", CY, CY);
        TextView no = chip("नाही", 0xFF9FB4C0, 0xFF5E7686);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(12);
        confirmRow.addView(yes, clp);
        confirmRow.addView(no);
        LinearLayout.LayoutParams cr = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cr.topMargin = dp(10);
        chat.addView(confirmRow, cr);
        yes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (awaitingAnyConfirm()) {
                    routeText("हो");
                }
            }
        });
        no.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (awaitingAnyConfirm()) {
                    routeText("नाही");
                }
            }
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(chat);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // input bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(shape(0xFF061220, 0xFF0B7FA0, 30, 1.5f));
        bar.setPadding(dp(18), dp(6), dp(8), dp(6));
        inputEdit = new EditText(this);
        inputEdit.setHint("इथे लिहा...");
        inputEdit.setHintTextColor(0xFF4F7183);
        inputEdit.setTextColor(Color.WHITE);
        inputEdit.setBackground(null);
        inputEdit.setMaxLines(3);
        bar.addView(inputEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        micView = new TextView(this);
        micView.setText("\uD83C\uDF99");
        micView.setTextSize(22f);
        micView.setTextColor(CY);
        micView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(52), dp(52));
        mlp.rightMargin = dp(8);
        bar.addView(micView, mlp);
        micView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listenBtn.performClick();
            }
        });

        TextView send = new TextView(this);
        send.setText("\u27A4");
        send.setTextSize(24f);
        send.setTextColor(0xFF03121C);
        send.setGravity(Gravity.CENTER);
        send.setBackground(shape(CY, 0, 26, 0));
        bar.addView(send, new LinearLayout.LayoutParams(dp(52), dp(52)));
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendTyped();
            }
        });
        inputEdit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                sendTyped();
                return true;
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        root.addView(bar, blp);

        setContentView(root);

        // keep orb, bubbles, confirm chips and mic state in sync with app state
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean sp = false;
                try {
                    sp = tts != null && ttsReady && tts.isSpeaking();
                } catch (Exception ignored) {
                }
                orb.setSpeaking(sp);
                transcript.setVisibility(transcript.getText().length() == 0 ? View.GONE : View.VISIBLE);
                reply.setVisibility(reply.getText().length() == 0 ? View.GONE : View.VISIBLE);
                confirmRow.setVisibility(awaitingAnyConfirm() ? View.VISIBLE : View.GONE);
                boolean on = listening || loopActive;
                micView.setBackground(on ? shape(0xFF13D8FF, 0, 26, 0)
                        : shape(0x00000000, 0xFF13D8FF, 26, 1.5f));
                micView.setTextColor(on ? 0xFF03121C : 0xFF13D8FF);
                subtitleView.setText(isPaired() ? "ऑनलाइन \u2022 सुरक्षित" : "पेअर केलेले नाही");
                pairBox.setVisibility(isPaired() ? View.GONE : View.VISIBLE);
                uiHandler.postDelayed(this, 120);
            }
        });
    }

    private void showMenu() {
        final String[] names = {"आवाज पुन्हा ऐका (REPLAY VOICE)", "रेकॉर्ड (RECORD)", "प्ले (PLAY)",
                "मेमरी पहा (VIEW MEMORY)", "रिमाइंडर पहा (VIEW REMINDERS)",
                "संभाषण पुसा (CLEAR CONVERSATION)", "सर्व विसर (FORGET EVERYTHING)"};
        new AlertDialog.Builder(this)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Button[] bs = {replayBtn, recordBtn, playBtn, viewMemoryBtn,
                                viewRemindersBtn, clearChatBtn, forgetBtn};
                        if (bs[which].isEnabled()) {
                            bs[which].performClick();
                        }
                    }
                })
                .setNegativeButton("CLOSE", null)
                .show();
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
        if (requestCode == REQ_NOTIF) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                tryArmPending();
            } else {
                pendingReminder = null;
                status.setText("Notification permission DENIED - reminder NOT set."
                        + " Enable: Settings > Apps > this app > Notifications");
                reply.setText("(reminder not set)");
            }
            return;
        }
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
                status.setText("ऐकतोय...");
            }

            @Override
            public void onBeginningOfSpeech() {
                consecutiveListenErrors = 0;
                status.setText("ऐकतोय...");
            }

            @Override
            public void onRmsChanged(float rmsdB) { }

            @Override
            public void onBufferReceived(byte[] buffer) { }

            @Override
            public void onEndOfSpeech() {
                status.setText("विचार करतोय...");
            }

            @Override
            public void onError(int error) {
                listening = false;
                if (loopActive) {
                    consecutiveListenErrors++;
                    boolean silence = (error == SpeechRecognizer.ERROR_NO_MATCH
                            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                    if (silence) {
                        consecutiveSilence++;
                    }
                    if (consecutiveSilence >= 3) {
                        loopActive = false;
                        consecutiveSilence = 0;
                        listenBtn.setText("LISTEN");
                        status.setText("Conversation ended (no speech heard 3 times). LISTEN to start again");
                    } else if (consecutiveListenErrors >= 5) {
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
                consecutiveSilence = 0;
                if (!loopActive) {
                    listenBtn.setText("LISTEN");
                }
                String text = bestResult(results);
                if (text == null || text.isEmpty()) {
                    status.setText("Error: empty result");
                } else {
                    routeText(text);
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
        final String apiKey = authToken();
        if (apiKey == null || apiKey.isEmpty()) {
            status.setText("Not paired - enter the pairing code in the box and tap PAIR");
            return;
        }
        status.setText("विचार करतोय...");
        reply.setText("...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String r0 = callGemini(apiKey, userText);
                if (r0.startsWith("Error:")) { r0 = callGemini(apiKey, userText); }
                final String result = r0;
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
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(45000);
            conn.setRequestProperty("x-brain-tools", "1");
            conn.setDoOutput(true);

            String today = new java.text.SimpleDateFormat("EEEE, d MMMM yyyy",
                    Locale.US).format(new java.util.Date());
            JSONObject system = new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text",
                            "Today's date is " + today + "." + memory.asPrompt()
                            + " You are a personal AI voice assistant created by Prem Chavan."
                            + " Always reply in Marathi, in 1-3 short sentences."
                            + " If asked who made you, say Prem Chavan created you."
                            + " Never claim Google or any company made you."
                            + " IMPORTANT HONESTY RULE: you have NO memory of your own between turns"
                            + " beyond this chat session and the list of remembered facts given to you."
                            + " Facts are saved ONLY when the user says the command लक्षात ठेव"
                            + " (or 'remember that ...'), which the app handles itself."
                            + " If the user states a personal fact or asks you to remember something"
                            + " without that command, NEVER say or imply that you will remember it."
                            + " Instead say briefly that you cannot store it yourself and that they"
                            + " should add 'लक्षात ठेव' at the end (in English: say 'remember that'"
                            + " before the fact). Example reply: हे मी स्वतः लक्षात ठेवू शकत नाही"
                            + " - साठवायचं असेल तर शेवटी 'लक्षात ठेव' असं म्हण.")));
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
                if (code == 401) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onAuthRejected();
                        }
                    });
                }
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
                    if (tts != null && tts.isSpeaking()) {
                        maybeContinueLoop();
                        return;
                    }
                    startListening();
                }
            }
        }, 700);
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
        status.setText("बोलतोय...");
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

    private void saveFactAsync(final String raw) {
        final String apiKey = authToken();
        if (apiKey == null || apiKey.isEmpty()) {
            finishSave(raw, "no API key, saved as spoken");
            return;
        }
        status.setText("Saving to memory...");
        reply.setText("...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String compact = compressFact(apiKey, raw);
                final String fact;
                final String how;
                if (compact == null) {
                    fact = raw;
                    how = "compression failed, saved as spoken";
                } else {
                    fact = compact;
                    how = "shortened by Gemini from: " + raw;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finishSave(fact, how);
                    }
                });
            }
        }).start();
    }

    private void finishSave(String fact, String how) {
        if (memory.add(fact)) {
            status.setText("Saved (" + how + ")");
            showLocalReply("ठीक आहे, लक्षात ठेवलं: " + fact);
        } else {
            status.setText("Not saved (duplicate, empty or memory full)");
            showLocalReply("हे आधीच लक्षात आहे किंवा memory भरली आहे.");
        }
    }

    /** One Gemini call: sentence -> short fact. Returns null on any failure. */
    private String compressFact(String apiKey, String raw) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(GEMINI_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(25000);
            conn.setDoOutput(true);
            JSONObject system = new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text",
                            "Rewrite the user's sentence as ONE short factual statement about the user"
                            + " (the speaker). Keep the same language as the input. Keep every name,"
                            + " number and detail. Add nothing new. Output only the statement, no quotes.")));
            JSONObject content = new JSONObject().put("role", "user").put("parts",
                    new JSONArray().put(new JSONObject().put("text", raw)));
            JSONObject body = new JSONObject()
                    .put("system_instruction", system)
                    .put("contents", new JSONArray().put(content));
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            JSONObject root = new JSONObject(readAll(conn.getInputStream()));
            JSONArray parts = root.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                sb.append(parts.getJSONObject(i).optString("text", ""));
            }
            String out = sb.toString().trim();
            if (out.isEmpty() || out.length() > raw.length() * 2 + 40 || out.contains("\n")) {
                return null;
            }
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---- Device alarm (system clock app). Needs the user's explicit "हो" before it is set. ----

    private boolean handleAlarmCommand(String text) {
        if (awaitingAlarmConfirm) {
            awaitingAlarmConfirm = false;
            if (Memory.isYes(text)) {
                setSystemAlarm();
            } else {
                status.setText("Alarm cancelled (not set)");
                showLocalReply("ठीक आहे, अलार्म रद्द केला.");
            }
            return true;
        }
        String t = text.toLowerCase(Locale.US);
        if (t.contains("अलार्म") || t.contains("अलार्म") || t.contains("alarm") || t.contains("गजर")) {
            parseAlarmAsync(text);
            return true;
        }
        return false;
    }

    private void parseAlarmAsync(final String raw) {
        final String apiKey = authToken();
        if (apiKey == null || apiKey.isEmpty()) {
            status.setText("Not paired - enter the pairing code and tap PAIR");
            return;
        }
        status.setText("Understanding alarm...");
        reply.setText("...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String now = new java.text.SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm",
                        Locale.US).format(new java.util.Date());
                String system = "Current local time: " + now + "."
                        + " Decide if the user's sentence asks to set a device alarm."
                        + " Reply with ONLY a JSON object: {\"is_alarm\": true/false,"
                        + " \"hour\": 0-23 or null, \"minute\": 0-59 or null,"
                        + " \"label\": short label or empty}."
                        + " Convert spoken times to 24-hour (\"सकाळी 6\" = 6, \"संध्याकाळी 6\" = 18)."
                        + " If no clock time is given, set hour and minute to null.";
                String j0 = callGeminiJson(apiKey, system, raw);
                if (j0 == null) { j0 = callGeminiJson(apiKey, system, raw); }
                final String json = j0;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onAlarmParsed(raw, json);
                    }
                });
            }
        }).start();
    }

    private void onAlarmParsed(String raw, String json) {
        if (json == null) {
            status.setText("Error: could not parse alarm (network or Gemini failed) - not set");
            reply.setText("(alarm not set)");
            maybeContinueLoop();
            return;
        }
        try {
            String j = json.trim();
            if (j.startsWith("```")) {
                j = j.replaceAll("^```[a-zA-Z]*", "").replaceAll("```$", "").trim();
            }
            JSONObject o = new JSONObject(j);
            if (!o.optBoolean("is_alarm", false)) {
                askGemini(raw);
                return;
            }
            if (o.isNull("hour") || o.isNull("minute")) {
                status.setText("Alarm NOT set: no time heard in: " + raw);
                showLocalReply("वेळ समजली नाही. कितीचा अलार्म लावू ते पुन्हा सांग.");
                return;
            }
            int h = o.optInt("hour", -1), m = o.optInt("minute", -1);
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                status.setText("Alarm NOT set: invalid time " + h + ":" + m);
                showLocalReply("वेळ समजली नाही. पुन्हा सांग.");
                return;
            }
            String label = o.optString("label", "").trim();
            if (label.equals("null")) {
                label = "";
            }
            pendingAlarmHour = h;
            pendingAlarmMin = m;
            pendingAlarmLabel = label;
            awaitingAlarmConfirm = true;
            String hhmm = String.format(Locale.US, "%02d:%02d", h, m);
            status.setText("Confirm alarm at " + hhmm + " - say YES (हो) to set it");
            showLocalReply("अलार्म " + hhmm + " ला लावू? बरोबर असेल तर हो म्हण.");
        } catch (Exception e) {
            status.setText("Error: alarm parse failed (" + e.getMessage() + ") - not set");
            reply.setText("(alarm not set)");
            maybeContinueLoop();
        }
    }

    private void setSystemAlarm() {
        try {
            Intent i = new Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                    .putExtra(android.provider.AlarmClock.EXTRA_HOUR, pendingAlarmHour)
                    .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, pendingAlarmMin)
                    .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, pendingAlarmLabel)
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true);
            startActivity(i);
            String hhmm = String.format(Locale.US, "%02d:%02d", pendingAlarmHour, pendingAlarmMin);
            status.setText("Alarm requested for " + hhmm + " (check your Clock app)");
            showLocalReply("अलार्म " + hhmm + " ला लावला.");
        } catch (Exception e) {
            status.setText("Error: alarm NOT set - " + e.getMessage());
            showLocalReply("अलार्म सेट झाला नाही.");
        }
    }

    // ---- Reminders ----

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingSettingsReturn && pendingReminder != null
                && System.currentTimeMillis() - settingsLaunchedAt > 1500) {
            awaitingSettingsReturn = false;
            tryArmPending();
        }
        updateReminderStatus();
    }

    private void updateReminderStatus() {
        reminderStatus.setText("Reminders: " + Reminders.all(this).size() + " pending");
    }

    private boolean handleReminderCommand(String text) {
        if (awaitingReminderConfirm) {
            awaitingReminderConfirm = false;
            if (Memory.isYes(text)) {
                tryArmPending();
            } else {
                pendingReminder = null;
                status.setText("Reminder cancelled (not set)");
                showLocalReply("ठीक आहे, रिमाइंडर रद्द केला.");
            }
            return true;
        }
        String t = text.toLowerCase(Locale.US);
        if (t.contains("आठवण") || t.contains("remind")) {
            parseReminderAsync(text);
            return true;
        }
        return false;
    }

    private void parseReminderAsync(final String raw) {
        final String apiKey = authToken();
        if (apiKey == null || apiKey.isEmpty()) {
            status.setText("Not paired - enter the pairing code and tap PAIR");
            return;
        }
        status.setText("Understanding reminder...");
        reply.setText("...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String now = new java.text.SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm",
                        Locale.US).format(new java.util.Date());
                String tz = java.util.TimeZone.getDefault().getID();
                String system = "Current local time: " + now + " (timezone " + tz + ")."
                        + " Decide if the user's sentence asks to be reminded of something."
                        + " Reply with ONLY a JSON object: {\"is_reminder\": true/false,"
                        + " \"minutes_from_now\": number or null,"
                        + " \"at\": \"yyyy-MM-dd HH:mm\" or null,"
                        + " \"text\": short reminder text in the user's language}."
                        + " Use minutes_from_now for relative times (\"in 10 minutes\"),"
                        + " and at for clock times (\"at 6 pm\", \"tomorrow 7 am\"; if a clock"
                        + " time today has already passed, use tomorrow). If no time is given,"
                        + " set both to null.";
                String j0 = callGeminiJson(apiKey, system, raw);
                if (j0 == null) { j0 = callGeminiJson(apiKey, system, raw); }
                final String json = j0;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onReminderParsed(raw, json);
                    }
                });
            }
        }).start();
    }

    private void onReminderParsed(String raw, String json) {
        if (json == null) {
            status.setText("Error: could not parse reminder (network or Gemini failed) - not set");
            reply.setText("(reminder not set)");
            maybeContinueLoop();
            return;
        }
        try {
            String j = json.trim();
            if (j.startsWith("```")) {
                j = j.replaceAll("^```[a-zA-Z]*", "").replaceAll("```$", "").trim();
            }
            JSONObject o = new JSONObject(j);
            if (!o.optBoolean("is_reminder", false)) {
                askGemini(raw);
                return;
            }
            long now = System.currentTimeMillis();
            long time = 0L;
            if (!o.isNull("minutes_from_now") && o.optDouble("minutes_from_now", 0) > 0) {
                time = now + (long) (o.optDouble("minutes_from_now") * 60000.0);
            } else if (!o.isNull("at")) {
                java.util.Date d = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                        Locale.US).parse(o.optString("at"));
                if (d != null) {
                    time = d.getTime();
                }
            }
            String rtext = o.optString("text", "").trim();
            if (rtext.isEmpty() || rtext.equals("null")) {
                rtext = raw;
            }
            if (time <= now + 5000 || time > now + 366L * 24 * 3600 * 1000) {
                status.setText("Reminder NOT set: could not get a valid future time from: " + raw);
                showLocalReply("वेळ समजली नाही. कधी आठवण करू ते पुन्हा सांग.");
                return;
            }
            Reminders.Item it = new Reminders.Item();
            it.id = now;
            it.time = time;
            it.text = rtext;
            pendingReminder = it;
            awaitingReminderConfirm = true;
            status.setText("Confirm reminder: \"" + rtext + "\" at "
                    + Reminders.format(time) + " - say YES (हो) to set it");
            showLocalReply("रिमाइंडर: " + rtext + ", " + Reminders.format(time)
                    + ". बरोबर असेल तर हो म्हण.");
        } catch (Exception e) {
            status.setText("Error: reminder parse failed (" + e.getMessage() + ") - not set");
            reply.setText("(reminder not set)");
            maybeContinueLoop();
        }
    }

    private void tryArmPending() {
        if (pendingReminder == null) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            status.setText("Allow notifications to set the reminder (NOT set yet)");
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            return;
        }
        if (!Reminders.canExact(this)) {
            status.setText("Turn ON 'Alarms & reminders' for this app, then come back."
                    + " Reminder NOT set yet.");
            awaitingSettingsReturn = true;
            settingsLaunchedAt = System.currentTimeMillis();
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                status.setText("Error: cannot open Alarms & reminders settings. Open it manually:"
                        + " Settings > Apps > Special access > Alarms & reminders");
            }
            return;
        }
        Reminders.Item it = pendingReminder;
        pendingReminder = null;
        if (it.time <= System.currentTimeMillis() + 2000) {
            status.setText("Reminder NOT set: time already passed");
            showLocalReply("ती वेळ निघून गेली आहे, रिमाइंडर सेट झाला नाही.");
            return;
        }
        String err = Reminders.arm(this, it);
        if (err != null) {
            status.setText("Error: reminder NOT set - " + err);
            showLocalReply("रिमाइंडर सेट झाला नाही.");
            return;
        }
        if (!Reminders.add(this, it)) {
            Reminders.disarm(this, it);
            status.setText("Error: could not save reminder - NOT set");
            showLocalReply("रिमाइंडर सेव्ह झाला नाही.");
            return;
        }
        updateReminderStatus();
        status.setText("Reminder SET for " + Reminders.format(it.time));
        showLocalReply("रिमाइंडर सेट झाला: " + it.text + ", " + Reminders.format(it.time));
    }

    private void showRemindersDialog() {
        final java.util.List<Reminders.Item> items = Reminders.all(this);
        if (items.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Reminders: 0")
                    .setMessage("(no pending reminders)")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = Reminders.format(items.get(i).time) + " - " + items.get(i).text;
        }
        new AlertDialog.Builder(this)
                .setTitle("Reminders: " + items.size() + " (tap one to cancel it)")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        confirmCancelReminder(items.get(which));
                    }
                })
                .setPositiveButton("CLOSE", null)
                .show();
    }

    private void confirmCancelReminder(final Reminders.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("Cancel this reminder?")
                .setMessage(Reminders.format(it.time) + " - " + it.text)
                .setPositiveButton("YES, CANCEL", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Reminders.disarm(MainActivity.this, it);
                        Reminders.remove(MainActivity.this, it.id);
                        updateReminderStatus();
                        status.setText("Reminder cancelled");
                    }
                })
                .setNegativeButton("KEEP", null)
                .show();
    }

    /** One Gemini call returning JSON text, or null on any failure. */
    private String callGeminiJson(String apiKey, String system, String userText) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(GEMINI_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            JSONObject sys = new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", system)));
            JSONObject content = new JSONObject().put("role", "user").put("parts",
                    new JSONArray().put(new JSONObject().put("text", userText)));
            JSONObject body = new JSONObject()
                    .put("system_instruction", sys)
                    .put("contents", new JSONArray().put(content))
                    .put("generationConfig", new JSONObject()
                            .put("responseMimeType", "application/json"));
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            JSONObject root = new JSONObject(readAll(conn.getInputStream()));
            JSONArray parts = root.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                sb.append(parts.getJSONObject(i).optString("text", ""));
            }
            String out = sb.toString().trim();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void clearConversation() {
        int n;
        synchronized (history) {
            n = history.size() / 2;
            history.clear();
        }
        status.setText("Conversation cleared (" + n + " exchanges). Memory facts kept.");
    }

    private boolean isClearConversation(String text) {
        String t = text.toLowerCase(Locale.US);
        return t.contains("संभाषण") || t.contains("conversation")
                || t.contains("चॅट") || t.contains("chat history");
    }

    private boolean handleMemoryCommand(String text) {
        String tl = text.toLowerCase(Locale.US);
        if (isClearConversation(text) && (tl.contains("clear") || tl.contains("reset")
                || tl.contains("पुसून") || tl.contains("नवीन") || tl.contains("सुरू नव्याने")
                || tl.contains("साफ"))) {
            clearConversation();
            showLocalReply("ठीक आहे, संभाषण पुसलं. तुझ्या memory मधल्या गोष्टी तशाच आहेत.");
            return true;
        }
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
            } else {
                saveFactAsync(fact);
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
