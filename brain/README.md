# Brain API
POST /ask  (Authorization: Bearer BRAIN_TOKEN)  {"text":"...","history":[{"role":"user|model","text":"..."}]}
-> {"ok":true,"reply":"..."} or {"ok":false,"error":"real error text"}
GET /health
Secrets: GEMINI_API_KEY, BRAIN_TOKEN (Worker secrets). Optional: PROVIDER, GEMINI_MODEL.
