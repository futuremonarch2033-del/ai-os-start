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

// ---- Storage interface (swap the backend here; tools only use get/set/list/del) ----
let _kv;
const store = {
  async kv() { if (!_kv) _kv = await Deno.openKv(); return _kv; },
  async get(key) { return (await (await this.kv()).get(key)).value; },
  async set(key, value, opts) { await (await this.kv()).set(key, value, opts); },
  async del(key) { await (await this.kv()).delete(key); },
  async listRange(prefix, start, end, limit = 20) {
    const out = [];
    for await (const e of (await this.kv()).list({ start, end }, { limit })) out.push({ key: e.key, value: e.value });
    return out;
  },
  async list(prefix, limit = 20) {
    const out = [];
    for await (const e of (await this.kv()).list({ prefix }, { limit, reverse: true })) out.push({ key: e.key, value: e.value });
    return out;
  },
};

// ======================= TOOL FRAMEWORK (meta-tool pattern) =======================
// A tool is defined by a SPEC, never wired by hand:
//   registerTool({ name, description, parameters (JSON schema), needsConfirm, run(args, env),
//                  summary?(args)  - confirmation question (needed when needsConfirm),
//                  doneMsg?        - reply after a confirmed run })
// registerTool validates the spec, rejects duplicates, and wraps run() with argument validation.
// defineCollection(spec) is a generator on top: one declarative spec -> <name>_create (confirm)
// and <name>_list tools backed by the storage interface. New tools = new specs, no new plumbing.
// GET /tools (authenticated) lists everything registered.
const tools = {};

function registerTool(spec) {
  if (!spec || !/^[a-z][a-z0-9_]{2,40}$/.test(spec.name || "")) throw new Error(`bad tool name: ${spec && spec.name}`);
  if (tools[spec.name]) throw new Error(`duplicate tool: ${spec.name}`);
  if (typeof spec.description !== "string" || !spec.description) throw new Error(`${spec.name}: description required`);
  if (!spec.parameters || spec.parameters.type !== "object") throw new Error(`${spec.name}: parameters must be an object schema`);
  if (typeof spec.needsConfirm !== "boolean") throw new Error(`${spec.name}: needsConfirm must be explicit true/false`);
  if (typeof spec.run !== "function") throw new Error(`${spec.name}: run must be a function`);
  if (spec.needsConfirm && typeof spec.summary !== "function") throw new Error(`${spec.name}: confirm tools need summary()`);
  const inner = spec.run;
  tools[spec.name] = { ...spec, async run(args, env) {
    const bad = validateArgs(spec.parameters, args || {});
    if (bad) throw new Error(bad);
    return await inner(args || {}, env);
  } };
}

function validateArgs(schema, args) {
  for (const k of schema.required || []) {
    if (args[k] === undefined || args[k] === null || args[k] === "") return `missing required argument: ${k}`;
  }
  for (const [k, def] of Object.entries(schema.properties || {})) {
    const v = args[k];
    if (v === undefined || v === null) continue;
    const ok = def.type === "integer" ? Number.isInteger(v) : def.type === "number" ? typeof v === "number" : def.type === "string" ? typeof v === "string" : def.type === "boolean" ? typeof v === "boolean" : true;
    if (!ok) return `argument ${k} must be ${def.type}`;
    if (def.enum && !def.enum.includes(v)) return `argument ${k} must be one of ${def.enum.join(", ")}`;
  }
  return null;
}

// Collection spec: {name, noun, fields:{f:{type,description,required}}, summary(a), doneMsg,
//   createDescription, listDescription, prepare?(rec)->{key:[...], rec} (validate + key), listQuery?(args)->{start,end}|null, listParams?}
function defineCollection(c) {
  const props = {};
  const required = [];
  for (const [f, d] of Object.entries(c.fields)) { props[f] = { type: d.type, description: d.description }; if (d.required) required.push(f); }
  registerTool({
    name: `${c.name}_create`, description: c.createDescription, needsConfirm: true,
    parameters: { type: "object", properties: props, required },
    summary: c.summary, doneMsg: c.doneMsg,
    async run(a) {
      const id = Date.now().toString();
      let rec = { id, created: new Date().toISOString() };
      for (const f of Object.keys(c.fields)) if (a[f] !== undefined) rec[f] = a[f];
      let key = [c.name, id];
      if (c.prepare) { const p = c.prepare(rec); rec = p.rec; key = p.key; }
      await store.set(key, rec);
      return { saved: true, id };
    },
  });
  registerTool({
    name: `${c.name}_list`, description: c.listDescription, needsConfirm: false,
    parameters: { type: "object", properties: { limit: { type: "integer", description: "Max items, default 10" }, ...(c.listParams || {}) } },
    async run(a) {
      const limit = Math.min(a.limit || 10, 30);
      const q = c.listQuery ? c.listQuery(a) : null;
      const items = q ? await store.listRange([c.name], q.start, q.end, limit) : await store.list([c.name], limit);
      const recs = items.map((i) => (c.present ? c.present(i.value) : i.value));
      return { count: recs.length, [c.name]: recs };
    },
  });
}

// ---- Tool: current date/time ----
registerTool({
  name: "get_datetime",
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
});

// ---- Collection: notes ----
defineCollection({
  name: "notes",
  fields: { text: { type: "string", description: "The note text", required: true }, title: { type: "string", description: "Optional short title" } },
  createDescription: "Save a new note for the user. Needs the user's explicit confirmation, which the server asks for itself. Call it when the user asks to save/write down a note.",
  listDescription: "List the user's most recent saved notes (newest first). Use to read or search notes.",
  summary: (a) => `नोट सेव्ह करू? "${String(a.text || "").slice(0, 120)}"`,
  doneMsg: "झाले, नोट सेव्ह केली.",
});

// ---- Collection: calendar events (times are IST, UTC+05:30, no DST) ----
const IST = "+05:30";
const DAY = 86400000;
const istDate = (ms) => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata" }).format(new Date(ms));
const istTime = (ms) => new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Kolkata", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(ms));
function dayRange(a) {
  let d = String(a.date || "today").trim().toLowerCase();
  const todayStr = istDate(Date.now());
  let startMs;
  if (d === "today") startMs = Date.parse(todayStr + "T00:00:00" + IST);
  else if (d === "tomorrow") startMs = Date.parse(todayStr + "T00:00:00" + IST) + DAY;
  else if (/^\d{4}-\d{2}-\d{2}$/.test(d)) startMs = Date.parse(d + "T00:00:00" + IST);
  else throw new Error("date must be today, tomorrow or YYYY-MM-DD");
  if (Number.isNaN(startMs)) throw new Error("invalid date");
  const days = Math.min(Math.max(a.days || 1, 1), 31);
  return { start: startMs, end: startMs + days * DAY };
}
defineCollection({
  name: "events",
  fields: {
    title: { type: "string", description: "What the event is", required: true },
    start: { type: "string", description: "Start date and time in IST as YYYY-MM-DDTHH:MM (24h). Work out relative dates with get_datetime first.", required: true },
    note: { type: "string", description: "Optional detail or place" },
  },
  createDescription: "Add a calendar event/appointment to the user's schedule. Needs the user's explicit confirmation, which the server asks for itself.",
  listDescription: "List calendar events/schedule for a day or range (IST), oldest first. Use for 'what is my schedule today/tomorrow'.",
  listParams: { date: { type: "string", description: "today, tomorrow, or YYYY-MM-DD. Default today." }, days: { type: "integer", description: "Number of days from date, default 1" } },
  listQuery: (a) => { const r = dayRange(a); return { start: ["events", r.start], end: ["events", r.end] }; },
  prepare: (rec) => {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(rec.start)) throw new Error("start must be YYYY-MM-DDTHH:MM");
    const ms = Date.parse(rec.start + ":00" + IST);
    if (Number.isNaN(ms)) throw new Error("invalid start time");
    return { rec: { ...rec, startMs: ms }, key: ["events", ms, rec.id] };
  },
  present: (r) => ({ title: r.title, date: istDate(r.startMs), time: istTime(r.startMs), note: r.note || "" }),
  summary: (a) => `इव्हेंट जोडू? "${String(a.title || "").slice(0, 80)}" - ${String(a.start || "")}`,
  doneMsg: "झाले, इव्हेंट शेड्यूलमध्ये जोडला.",
});

// ---- Tool: morning briefing (composes other tools; read-only) ----
registerTool({
  name: "morning_briefing",
  description: "Give the user a morning briefing: today's date and weekday, today's schedule, and recent notes, in one go. Use when the user asks for a briefing, 'good morning', or 'what's my day'.",
  parameters: { type: "object", properties: {} },
  needsConfirm: false,
  async run(a, env) {
    const dt = await tools.get_datetime.run({}, env);
    const ev = await tools.events_list.run({ date: "today", limit: 20 }, env);
    const tm = await tools.events_list.run({ date: "tomorrow", limit: 3 }, env);
    const nt = await tools.notes_list.run({ limit: 5 }, env);
    return {
      date: dt.date, weekday: dt.weekday, time_now: dt.time_12h,
      today_schedule: ev.events, tomorrow_first_items: tm.events,
      recent_notes: nt.notes.map((n) => n.text),
      reminders: "Reminders live on the phone and are not visible to the server; do not claim any.",
    };
  },
});

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
    // Never runs on the model's say-so: park it as a pending action; only the user's next explicit "yes" executes it (see handlePending).
    await store.set(["pending", "current"], { tool: name, args: args || {}, at: Date.now() }, { expireIn: 120000 });
    log("tool_call", { tool: name, args, ok: false, pending: true });
    return { pending_confirmation: true, note: "Ask nothing yourself. Tell the user briefly that you are waiting for their confirmation." };
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
const YES = /^\s*(हो|हो\s*ठीक आहे|होय|ठीक आहे|yes|yeah|ok|okay|confirm|हां|हाँ)\s*[.!।]?\s*$/i;
const NO = /^\s*(नाही|नको|no|cancel|रद्द)\s*[.!।]?\s*$/i;
const textReply = (t) => ({ candidates: [{ content: { role: "model", parts: [{ text: t }] } }] });

// Deterministic confirmation: acts only on the user's own last message, never on model output.
async function handlePending(env, body) {
  const last = body.contents[body.contents.length - 1];
  const said = (last?.parts || []).map((p) => p.text || "").join("");
  const isYes = YES.test(said), isNo = NO.test(said);
  if (!isYes && !isNo) return null;
  const pend = await store.get(["pending", "current"]);
  if (!pend) return null;
  await store.del(["pending", "current"]);
  const t = tools[pend.tool];
  if (isNo || !t) { log("tool_call", { tool: pend.tool, args: pend.args, ok: false, cancelled: true }); return textReply("ठीक आहे, रद्द केले."); }
  const started = Date.now();
  try {
    await t.run(pend.args, env);
    log("tool_call", { tool: pend.tool, args: pend.args, ok: true, confirmed: true, ms: Date.now() - started });
    return textReply(t.doneMsg || "झाले.");
  } catch (e) {
    log("tool_call", { tool: pend.tool, args: pend.args, ok: false, confirmed: true, error: String(e.message).slice(0, 200) });
    return textReply("हे करता आले नाही: " + String(e.message));
  }
}

function pendingPrompt(name, args) { const t = tools[name]; return textReply((t.summary ? t.summary(args) : `${name} चालवू?`) + " — 'हो' म्हणा."); }

async function generateWithTools(env, body, wantTools) {
  if (!wantTools) return await geminiGenerate(env, body);
  const done = await handlePending(env, body);
  if (done) return done;
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
      if (tools[p.functionCall.name]?.needsConfirm && r.pending_confirmation) return pendingPrompt(p.functionCall.name, p.functionCall.args);
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
    if (url.pathname === "/tools" && req.method === "GET") {
      if (!env.BRAIN_TOKEN || req.headers.get("authorization") !== `Bearer ${env.BRAIN_TOKEN}`) return json({ ok: false, error: "unauthorized" }, 401);
      return json({ ok: true, tools: Object.entries(tools).map(([name, t]) => ({ name, description: t.description, needsConfirm: t.needsConfirm, parameters: t.parameters })) });
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
