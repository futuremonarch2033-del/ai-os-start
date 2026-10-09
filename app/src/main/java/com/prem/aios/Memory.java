package com.prem.aios;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory tool: persistent on-device fact store.
 * Facts are kept as a JSON array of strings in SharedPreferences,
 * so they survive app restarts. Nothing is sent anywhere by this class.
 */
public class Memory {

    private static final String PREFS = "ai_os_memory";
    private static final String KEY_FACTS = "facts";
    private static final int MAX_FACTS = 200;

    private final SharedPreferences prefs;

    public Memory(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<String> all() {
        List<String> out = new ArrayList<String>();
        String raw = prefs.getString(KEY_FACTS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.getString(i));
            }
        } catch (Exception e) {
            // corrupted store: report as empty, do not invent data
        }
        return out;
    }

    public synchronized int count() {
        return all().size();
    }

    /** Returns true if saved, false if empty, duplicate or store full. */
    public synchronized boolean add(String fact) {
        if (fact == null) {
            return false;
        }
        String f = fact.trim();
        if (f.isEmpty()) {
            return false;
        }
        List<String> facts = all();
        for (String x : facts) {
            if (x.equalsIgnoreCase(f)) {
                return false;
            }
        }
        if (facts.size() >= MAX_FACTS) {
            return false;
        }
        facts.add(f);
        return save(facts);
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY_FACTS).commit();
    }

    /** Facts as one block of text for the Gemini system instruction ("" if none). */
    public synchronized String asPrompt() {
        List<String> facts = all();
        if (facts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(" Facts the user asked you to remember (use them naturally;"
                + " if asked about something not listed here, say you do not know):");
        for (int i = 0; i < facts.size(); i++) {
            sb.append(" ").append(i + 1).append(") ").append(facts.get(i)).append(";");
        }
        return sb.toString();
    }

    /** Numbered list for on-screen display. */
    public synchronized String asDisplay() {
        List<String> facts = all();
        if (facts.isEmpty()) {
            return "(no facts stored)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < facts.size(); i++) {
            sb.append(i + 1).append(". ").append(facts.get(i)).append("\n");
        }
        return sb.toString();
    }

    private boolean save(List<String> facts) {
        JSONArray arr = new JSONArray();
        for (String f : facts) {
            arr.put(f);
        }
        return prefs.edit().putString(KEY_FACTS, arr.toString()).commit();
    }

    /**
     * If the text is a "remember" request, returns the fact to store
     * (trigger phrase removed); otherwise null.
     */
    public static String extractFact(String text) {
        String[] triggers = {
                "लक्षात ठेवा", "लक्षात ठेव", "लक्षात ठेवायचं", "लक्षात ठेवायचे",
                "remember that", "please remember", "remember"
        };
        String lower = text.toLowerCase(java.util.Locale.US);
        if (lower.contains("?") || lower.contains("do you remember")
                || lower.contains("what") || lower.contains("लक्षात आहे")) {
            return null;
        }
        for (String tr : triggers) {
            int idx = lower.indexOf(tr);
            if (idx >= 0) {
                String fact = (text.substring(0, idx) + " "
                        + text.substring(idx + tr.length())).trim();
                fact = fact.replaceAll("^[,.:;\\-\\s]+|[,.:;\\-\\s]+$", "");
                return fact;
            }
        }
        return null;
    }

    public static boolean isForgetAll(String text) {
        String t = text.toLowerCase(java.util.Locale.US);
        boolean forget = t.contains("विसर") || t.contains("forget")
                || t.contains("पुसून") || t.contains("delete");
        boolean all = t.contains("सगळं") || t.contains("सगळे") || t.contains("सर्व")
                || t.contains("everything") || t.contains("all");
        return forget && all;
    }

    public static boolean isYes(String text) {
        String t = text.toLowerCase(java.util.Locale.US).trim();
        return t.contains("हो") || t.contains("होय") || t.contains("yes")
                || t.contains("confirm") || t.contains("ok");
    }
}
