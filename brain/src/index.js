// Prem's Brain API. Providers, tools and memory sit behind small interfaces.
// Secrets (GEMINI_API_KEY, BRAIN_TOKEN) come from Worker secrets, never from code or logs.

const SYSTEM = "You are Prem Chavan's personal assistant, made by Prem Chavan. Reply in Marathi unless asked otherwise. Keep replies short and honest. Never claim to have saved or done something you did not.";

// ---- Provider interface: async generate({env, body}) -> Gemini-shaped JSON, or throws Error ----
async function geminiGenerate(env, body) {
  if (!env.GEMINI_API_KEY) throw new Error("GEMINI_API_KEY secret is not set on the server");
  const models = (env.GEMINI_MODEL || "gemini-3.5-flash,gemini-3.8-flash,gemini-flash-latest,gemini-flash-lite-latest").split(",").map((m) => m.trim()).filter(Boolean);
  const errs = [];
  for (const model of models) {
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;
    for (let attempt = 0; attempt < 2; attempt++) {
      let r, t;
      try {
        r = await fetch(url, {
          method: "POST",
          headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
          body: JSON.stringify(body),
          signal: AbortSignal.timeout(Number(env.MODEL_TIMEOUT_MS) || 12000),
        });
        t = await r.text();
      } catch (e) {
        // Timeout or network error: record it and fall through to the next model.
        errs.push(`${model} ${e.name === "TimeoutError" ? "timed out" : "network error: " + e.message}`);
        break;
      }
      if (r.ok) return JSON.parse(t);
      errs.push(`${model} HTTP ${r.status}: ${t.replace(/\s+/g, " ").slice(0, 160)}`);
      if (r.status !== 503 && r.status !== 429) break;
      await new Promise((res) => setTimeout(res, 700));
    }
  }
  throw new Error("Gemini failed on all models: " + errs.join(" | "));
}
const providers = {
  async gemini({ env, history, text }) {
    const contents = [...history, { role: "user", text }].map((h) => ({
      role: h.role === "model" ? "model" : "user",
      parts: [{ text: h.text }],
    }));
    const j = await geminiGenerate(env, { system_instruction: { parts: [{ text: SYSTEM }] }, contents });
    const out = (j.candidates?.[0]?.content?.parts || []).map((p) => p.text || "").join("").trim();
    if (!out) throw new Error("Gemini returned an empty reply");
    return { text: out };
  },
};

// ---- Tool interface: each tool {description, parameters, needsConfirm, run(args, env)} ----
// needsConfirm tools never run from the model's own say-so; see runTool.
const tools = {
  get_datetime: {
    description: "Get the current date, weekday and time. Use whenever the user asks what time, date or day it is, or needs a relative date worked out. Default timezone is Asia/Kolkata (IST).",
    parameters: { type: "object", properties: { timezone: { type: "string", description: "IANA timezone, e.g. Asia/Kolkata. Optional." } } },
    needsConfirm: false,
    async run(a) {
      const tz = a.timezone || "Asia/Kolkata";
      const now = new Date();
      const fmt = (o) => new Intl.DateTimeFormat("en-GB", { timeZone: tz, ...o }).format(now);
      return {
        timezone: tz,
        date: fmt({ year: "numeric", month: "long", day: "numeric" }),
        weekday: fmt({ weekday: "long" }),
        time_24h: fmt({ hour: "2-digit", minute: "2-digit", hour12: false }),
        time_12h: new Intl.DateTimeFormat("en-US", { timeZone: tz, hour: "numeric", minute: "2-digit", hour12: true }).format(now),
        iso_utc: now.toISOString(),
      };
    },
  },
};

function toolDeclarations() {
  return [{ functionDeclarations: Object.entries(tools).map(([name, t]) => ({ name, description: t.description, parameters: t.parameters })) }];
}

// Runs one tool call. Every call is logged (name, args, outcome, timing; never secrets).
async function runTool(name, args, env) {
  const t = tools[name];
  const started = Date.now();
  if (!t) {
    log("tool_call", { tool: name, args, ok: false, error: "unknown tool" });
    return { error: `unknown tool ${name}` };
  }
  if (t.needsConfirm) {
    log("tool_call", { tool: name, args, ok: false, error: "needs user confirmation" });
    return { error: "this tool needs explicit user confirmation and is not available yet" };
  }
  try {
    const result = await t.run(args || {}, env);
    log("tool_call", { tool: name, args, ok: true, ms: Date.now() - started });
    return result;
  } catch (e) {
    log("tool_call", { tool: name, args, ok: false, ms: Date.now() - started, error: String(e.message).slice(0, 200) });
    return { error: String(e.message) };
  }
}

// Gemini call with the tool loop. Tools are on for the chat request only (not JSON-mode or memory-compress calls).
async function generateWithTools(env, body, wantTools) {
  if (!wantTools) return await geminiGenerate(env, body);
  const contents = [...body.contents];
  const reqBody = { ...body, contents, tools: toolDeclarations() };
  for (let i = 0; i < 4; i++) {
    const out = await geminiGenerate(env, reqBody);
    const content = out.candidates?.[0]?.content;
    const calls = (content?.parts || []).filter((p) => p.functionCall);
    if (!calls.length) return out;
    contents.push(content);
    const responses = [];
    for (const p of calls) {
      const r = await runTool(p.functionCall.name, p.functionCall.args, env);
      responses.push({ functionResponse: { name: p.functionCall.name, response: { result: r } } });
    }
    contents.push({ role: "user", parts: responses });
  }
  throw new Error("tool loop did not finish after 4 rounds");
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
      env = { GEMINI_API_KEY: g("GEMINI_API_KEY"), BRAIN_TOKEN: g("BRAIN_TOKEN"), PROVIDER: g("PROVIDER"), GEMINI_MODEL: g("GEMINI_MODEL"), MODEL_TIMEOUT_MS: g("MODEL_TIMEOUT_MS") };
    }
    env = env || {};
    const url = new URL(req.url);
    if (url.pathname === "/health") {
      return json({ ok: true, provider: env.PROVIDER || "gemini", geminiKeySet: !!env.GEMINI_API_KEY, tokenSet: !!env.BRAIN_TOKEN });
    }
    if (!["/ask", "/generate"].includes(url.pathname) || req.method !== "POST") return json({ ok: false, error: "not found" }, 404);

    if (!env.BRAIN_TOKEN || req.headers.get("authorization") !== `Bearer ${env.BRAIN_TOKEN}`) {
      log("auth_fail", {});
      return json({ ok: false, error: "unauthorized" }, 401);
    }
    let body;
    try { body = await req.json(); } catch { return json({ ok: false, error: "body must be JSON" }, 400); }
    if (url.pathname === "/generate") {
      // Gemini-shaped passthrough for the app (system_instruction, contents, generationConfig). Key stays server-side.
      if (!Array.isArray(body.contents)) return json({ ok: false, error: "contents is required" }, 400);
      const started = Date.now();
      try {
        const sysText = JSON.stringify(body.system_instruction || "");
        const wantTools = !body.generationConfig?.responseMimeType && (req.headers.get("x-brain-tools") === "1" || sysText.includes("voice assistant"));
        const out = await generateWithTools(env, { system_instruction: body.system_instruction, contents: body.contents, generationConfig: body.generationConfig }, wantTools);
        log("generate_ok", { ms: Date.now() - started });
        return json(out);
      } catch (e) {
        log("generate_error", { ms: Date.now() - started, error: String(e.message).slice(0, 400) });
        return json({ ok: false, error: String(e.message) }, 502);
      }
    }
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
