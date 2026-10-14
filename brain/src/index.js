// Prem's Brain API. Providers, tools and memory sit behind small interfaces.
// Secrets (GEMINI_API_KEY, BRAIN_TOKEN) come from Worker secrets, never from code or logs.

const SYSTEM = "You are Prem Chavan's personal assistant, made by Prem Chavan. Reply in Marathi unless asked otherwise. Keep replies short and honest. Never claim to have saved or done something you did not.";

// ---- Provider interface: async ask({env, history, text}) -> {text} or throws Error ----
const providers = {
  async gemini({ env, history, text }) {
    if (!env.GEMINI_API_KEY) throw new Error("GEMINI_API_KEY secret is not set on the server");
    const model = env.GEMINI_MODEL || "gemini-3.5-flash";
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;
    const contents = [...history, { role: "user", text }].map((h) => ({
      role: h.role === "model" ? "model" : "user",
      parts: [{ text: h.text }],
    }));
    const r = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
      body: JSON.stringify({ system_instruction: { parts: [{ text: SYSTEM }] }, contents }),
    });
    const body = await r.text();
    if (!r.ok) throw new Error(`Gemini HTTP ${r.status}: ${body.slice(0, 300)}`);
    const j = JSON.parse(body);
    const out = (j.candidates?.[0]?.content?.parts || []).map((p) => p.text || "").join("").trim();
    if (!out) throw new Error("Gemini returned an empty reply");
    return { text: out };
  },
};

// ---- Tool interface: each tool {name, needsConfirm, run(args, env)} ----
const tools = {
  memory_list: { needsConfirm: false, run: async (a, env) => (env.MEM ? await memGet(env) : []) },
};
async function memGet(env) {
  return JSON.parse((await env.MEM.get("facts")) || "[]");
}

function log(event, data) {
  // Structured log of every request and tool call. Never include secrets.
  console.log(JSON.stringify({ t: new Date().toISOString(), event, ...data }));
}

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8" } });

export default {
  async fetch(req, env) {
    // On Deno Deploy the second argument is not our env, so read Deno.env instead.
    if (typeof Deno !== "undefined") {
      const g = (k) => Deno.env.get(k) || undefined;
      env = { GEMINI_API_KEY: g("GEMINI_API_KEY"), BRAIN_TOKEN: g("BRAIN_TOKEN"), PROVIDER: g("PROVIDER"), GEMINI_MODEL: g("GEMINI_MODEL") };
    }
    env = env || {};
    const url = new URL(req.url);
    if (url.pathname === "/health") {
      return json({ ok: true, provider: env.PROVIDER || "gemini", geminiKeySet: !!env.GEMINI_API_KEY, tokenSet: !!env.BRAIN_TOKEN });
    }
    if (url.pathname !== "/ask" || req.method !== "POST") return json({ ok: false, error: "not found" }, 404);

    if (!env.BRAIN_TOKEN || req.headers.get("authorization") !== `Bearer ${env.BRAIN_TOKEN}`) {
      log("auth_fail", {});
      return json({ ok: false, error: "unauthorized" }, 401);
    }
    let body;
    try { body = await req.json(); } catch { return json({ ok: false, error: "body must be JSON" }, 400); }
    const text = String(body.text || "").trim();
    if (!text) return json({ ok: false, error: "text is required" }, 400);
    const history = Array.isArray(body.history) ? body.history.slice(-20) : [];

    const name = env.PROVIDER || "gemini";
    const started = Date.now();
    try {
      const out = await providers[name]({ env, history, text });
      log("ask_ok", { provider: name, ms: Date.now() - started, in_chars: text.length, out_chars: out.text.length });
      return json({ ok: true, reply: out.text, provider: name });
    } catch (e) {
      log("ask_error", { provider: name, ms: Date.now() - started, error: String(e.message).slice(0, 400) });
      return json({ ok: false, error: String(e.message), provider: name }, 502);
    }
  },
};
