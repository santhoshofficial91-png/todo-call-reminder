"use strict";
const API = "https://nicynemyjdiukkbltjwq.supabase.co/functions/v1/task-api";
const TZ = "Asia/Kolkata";
const $ = (id) => document.getElementById(id);

/* ---------- storage (token must also be readable by the service worker) ---------- */
function idbSet(key, val) {
  return new Promise((resolve) => {
    const open = indexedDB.open("rem", 1);
    open.onupgradeneeded = () => open.result.createObjectStore("kv");
    open.onerror = () => resolve();
    open.onsuccess = () => {
      try {
        const tx = open.result.transaction("kv", "readwrite");
        tx.objectStore("kv").put(val, key);
        tx.oncomplete = () => resolve();
        tx.onerror = () => resolve();
      } catch (e) { resolve(); }
    };
  });
}
const getToken = () => { try { return localStorage.getItem("token") || ""; } catch (e) { return ""; } };
async function setToken(t) { try { localStorage.setItem("token", t); } catch (e) {} await idbSet("token", t); }

/* ---------- api ---------- */
async function api(action, extra) {
  const res = await fetch(API, {
    method: "POST",
    headers: { "content-type": "application/json", "x-app-token": getToken() },
    body: JSON.stringify(Object.assign({ action }, extra || {})),
  });
  if (res.status === 401) { showPair(); throw new Error("இணைப்புக் குறியீடு தவறு"); }
  const j = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(j.error || ("பிழை " + res.status));
  return j;
}

/* ---------- helpers ---------- */
function fmt(d) {
  if (!d) return "நேரம் இல்லை";
  return new Date(d).toLocaleString("en-IN", { timeZone: TZ, day: "numeric", month: "short", hour: "numeric", minute: "2-digit", hour12: true });
}
function el(tag, props, ...kids) {
  const n = document.createElement(tag);
  Object.assign(n, props || {});
  for (const k of kids) n.append(k);
  return n;
}
function notice(text) { $("notice").replaceChildren(text ? el("div", { className: "msg", textContent: text }) : ""); }
function istNowString() {
  return new Date().toLocaleString("en-GB", { timeZone: TZ, dateStyle: "full", timeStyle: "short" });
}

/* ---------- task list ---------- */
let pending = [];
async function load() {
  try {
    const j = await api("list");
    pending = j.pending;
    render(j.pending, j.done);
    $("sub").textContent = `${j.pending.length} நிலுவையில்`;
    notice("");
  } catch (e) { notice(e.message); }
}
function taskCard(t, isDone) {
  const late = !isDone && t.due_at && new Date(t.due_at) < new Date();
  const box = el("div", { className: "card task" + (late ? " late" : "") });
  const chk = el("input", { type: "checkbox", checked: isDone });
  chk.style.cssText = "width:22px;height:22px;margin-top:2px";
  chk.onchange = async () => { try { if (chk.checked) await api("done", { id: t.id }); else await api("reschedule", { id: t.id, minutes: 1 }); } catch (e) { notice(e.message); } load(); };
  const body = el("div", { className: "t" });
  body.append(el("div", { className: "title", textContent: t.title }));
  body.append(el("div", { className: "meta", textContent: (late ? "⏰ தாமதம் · " : "") + (isDone ? "முடிந்தது" : fmt(t.due_at)) + (t.reschedule_count ? ` · ரீஷெடியூல் ×${t.reschedule_count}` : "") }));
  if (!isDone) {
    const acts = el("div", { className: "acts" });
    for (const [label, mins] of [["+1 மணி", 60], ["+3 மணி", 180]]) {
      acts.append(el("button", { className: "ghost sm", textContent: label, onclick: async () => { try { await api("reschedule", { id: t.id, minutes: mins }); } catch (e) { notice(e.message); } load(); } }));
    }
    acts.append(el("button", { className: "ghost sm", textContent: "நீக்கு", onclick: async () => { try { await api("cancel", { id: t.id }); } catch (e) { notice(e.message); } load(); } }));
    body.append(acts);
  }
  box.append(chk, body);
  return box;
}
function render(p, d) {
  const root = $("lists");
  root.replaceChildren();
  if (!p.length && !d.length) { root.append(el("div", { className: "empty", textContent: "வேலை எதுவும் இல்லை. மேலே சேர்க்கலாம் அல்லது 'AI-யோடு பேசு' அழுத்துங்க." })); return; }
  if (p.length) { root.append(el("div", { className: "sec", textContent: `நிலுவை (${p.length})` })); p.forEach((t) => root.append(taskCard(t, false))); }
  if (d.length) { root.append(el("div", { className: "sec", textContent: "சமீபத்தில் முடிந்தவை" })); d.forEach((t) => root.append(taskCard(t, true))); }
}
$("addBtn").onclick = async () => {
  const title = $("newTitle").value.trim(); if (!title) return;
  const due = $("newDue").value;
  try { await api("add", { title, due_at: due ? new Date(due).toISOString() : null }); $("newTitle").value = ""; $("newDue").value = ""; } catch (e) { notice(e.message); }
  load();
};

/* ---------- pairing + notifications ---------- */
function showPair() { $("pair").classList.remove("hidden"); $("main").classList.add("hidden"); }
function showMain() { $("pair").classList.add("hidden"); $("main").classList.remove("hidden"); }
$("pairBtn").onclick = async () => {
  const c = $("code").value.trim(); if (!c) return;
  await setToken(c);
  try { await api("summary"); showMain(); load(); updateEnableInfo(); } catch (e) { notice(e.message); }
};

function urlB64ToUint8(s) {
  const pad = "=".repeat((4 - (s.length % 4)) % 4);
  const raw = atob((s + pad).replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from([...raw].map((c) => c.charCodeAt(0)));
}
const isIOS = /iPhone|iPad|iPod/.test(navigator.userAgent);
const standalone = window.matchMedia("(display-mode: standalone)").matches || navigator.standalone;

async function updateEnableInfo() {
  const info = $("enableInfo");
  if (!("serviceWorker" in navigator) || !("PushManager" in window)) {
    info.textContent = isIOS && !standalone ? "iPhone: Safari-ல் Share → 'Add to Home Screen' செய்து அந்த ஐகானைத் திறங்க. அப்புறம் அலாரம் இயக்கலாம." : "இந்த உலாவி push-ஐ ஆதரிக்கவில்லை.";
    return;
  }
  if (Notification.permission === "granted") {
    const reg = await navigator.serviceWorker.getRegistration();
    const sub = reg && (await reg.pushManager.getSubscription());
    info.textContent = sub ? "✅ அலாரம் இயக்கத்தில் உள்ளது" : "அனுமதி உள்ளது, ஆனால் இன்னும் இணைக்கவில்லை — மேலே அழுத்துங்க.";
  } else if (Notification.permission === "denied") {
    info.textContent = "Notification தடுக்கப்பட்டுள்ளது. உலாவி/ஃபோன் settings-ல் அனுமதியுங்க.";
  } else {
    info.textContent = "ஒவ்வொரு மணி நேரமும் கால் அலாரம் வர இதை இயக்குங்க.";
  }
}
$("enable").onclick = async () => {
  try {
    if (!("serviceWorker" in navigator) || !("PushManager" in window)) { updateEnableInfo(); return; }
    const perm = await Notification.requestPermission();
    if (perm !== "granted") { updateEnableInfo(); return; }
    const reg = await navigator.serviceWorker.register("sw.js");
    await navigator.serviceWorker.ready;
    const { key } = await api("vapid_public");
    let sub = await reg.pushManager.getSubscription();
    if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: urlB64ToUint8(key) });
    await api("subscribe", { subscription: sub.toJSON() });
    updateEnableInfo();
  } catch (e) { notice("அலாரம் இயக்க முடியவில்லை: " + e.message); }
};

/* ---------- live voice call (Gemini Live API) ---------- */
let call = null; // active session state

const TOOLS = [{
  functionDeclarations: [
    { name: "mark_done", description: "Mark a pending task as finished.", parameters: { type: "OBJECT", properties: { task_id: { type: "STRING" } }, required: ["task_id"] } },
    { name: "reschedule", description: "Remind again for this task later. For relative times give minutes from now (fractions allowed: 25 seconds = 0.42). For a clock time (e.g. 5 pm) give due_at_iso as an ISO 8601 timestamp with +05:30 offset instead.", parameters: { type: "OBJECT", properties: { task_id: { type: "STRING" }, minutes: { type: "NUMBER" }, due_at_iso: { type: "STRING" } }, required: ["task_id"] } },
    { name: "add_task", description: "Add a new task. Optionally set when to remind: minutes_from_now (fractions allowed) or due_at_iso (ISO 8601 with +05:30).", parameters: { type: "OBJECT", properties: { title: { type: "STRING" }, minutes_from_now: { type: "NUMBER" }, due_at_iso: { type: "STRING" } }, required: ["title"] } },
    { name: "cancel_task", description: "Remove a task the user no longer needs.", parameters: { type: "OBJECT", properties: { task_id: { type: "STRING" } }, required: ["task_id"] } },
    { name: "end_call", description: "Hang up after saying goodbye.", parameters: { type: "OBJECT", properties: {} } },
  ],
}];

function systemPrompt(tasks, mode) {
  const list = tasks.length
    ? tasks.map((t, i) => `${i + 1}. id=${t.id} | ${t.title} | due: ${t.due_at ? fmt(t.due_at) : "no time"}`).join("\n")
    : "(no pending tasks)";
  const flow = mode === "task"
    ? `This is a reminder call for ONLY the task(s) listed below, whose time has just come. Say "வணக்கம்", tell the user it is time for that task, and ask if they have finished it. Do not talk about any other task unless the user brings it up.
- If finished -> call mark_done, confirm briefly, say goodbye and call end_call.
- If not finished -> ask when to remind again (or use the time they already said) and call reschedule, confirm the new time, say goodbye and call end_call.`
    : `This is the regular hourly check-in about everything still pending. Say "வணக்கம்", say how many tasks are pending, then go through them one at a time (at most 5, then summarise the rest). After each, wait for the user's answer.
- When all are handled or the user says bye, say a brief goodbye and call end_call.`;
  return `You are a warm, brisk personal reminder assistant on a phone call with the user. Speak ONLY in natural, colloquial Tamil (you may keep common English words like "task" or "meeting"). Keep every turn short: one or two sentences.
Current time in India (IST, +05:30): ${istNowString()}.
Tasks for this call:
${list}

${flow}

General rules:
- The user may interrupt at any moment. Drop what you were saying and react to what they said.
- If the user says a task is finished -> call mark_done with that task's id, then confirm in a few words.
- If they ask to be reminded later -> call reschedule. Relative times: use minutes (1 hour = 60, 15 minutes = 15, 25 seconds = 0.42). Clock times ("5 o'clock evening", "tomorrow 9 am"): compute the exact ISO 8601 timestamp in IST (+05:30) from the current time above and pass due_at_iso. Always confirm the new time back to the user.
- If they mention a new task -> call add_task (with a time if they gave one). If they no longer need a task -> cancel_task.
- Never read out ids. Never invent tasks. If something is unclear, ask a short question.`;
}

function addCap(kind, text) {
  const caps = $("caps");
  let last = caps.lastElementChild;
  if (!last || last.dataset.kind !== kind) {
    last = el("div", { className: kind }); last.dataset.kind = kind; caps.append(last);
    while (caps.children.length > 6) caps.firstChild.remove();
  }
  last.textContent += text;
  caps.scrollTop = caps.scrollHeight;
}

function openIncoming() {
  if (call) return;
  $("call").classList.remove("hidden");
  $("call").classList.add("ring");
  $("incoming").classList.remove("hidden");
  $("active").classList.add("hidden");
  $("caps").replaceChildren();
  $("cstate").textContent = "நினைவூட்டல் கால் வருகிறது…";
  try { navigator.vibrate && navigator.vibrate([400, 200, 400, 200, 400]); } catch (e) {}
}
function closeCall() {
  $("call").classList.add("hidden");
  $("call").classList.remove("ring");
}

async function answerCall() {
  $("call").classList.remove("ring");
  $("incoming").classList.add("hidden");
  $("active").classList.remove("hidden");
  $("cstate").textContent = "இணைக்கிறது…";
  const s = (call = { ws: null, mic: null, actx: null, node: null, sources: new Set(), nextT: 0, muted: false, gotOut: false, ended: false, wake: null });
  try {
    const [tok, ctx] = await Promise.all([api("live_token"), api("call_context")]);
    s.mode = ctx.mode;
    s.tasks = ctx.pending;
    s.mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } });
    s.actx = new (window.AudioContext || window.webkitAudioContext)();
    await s.actx.resume();
    try { s.wake = await navigator.wakeLock.request("screen"); } catch (e) {}

    const workletSrc = `
      class Cap extends AudioWorkletProcessor {
        constructor(){ super(); this.buf=[]; this.pos=0; this.ratio=sampleRate/16000; this.out=[]; }
        process(inputs){
          const ch = inputs[0] && inputs[0][0]; if(!ch) return true;
          for(let i=0;i<ch.length;i++) this.buf.push(ch[i]);
          while(this.pos+1 < this.buf.length){
            const i=Math.floor(this.pos), f=this.pos-i;
            this.out.push(this.buf[i]*(1-f)+this.buf[i+1]*f);
            this.pos += this.ratio;
          }
          const drop=Math.floor(this.pos); this.buf=this.buf.slice(drop); this.pos-=drop;
          if(this.out.length>=1600){
            const pcm=new Int16Array(this.out.length);
            for(let i=0;i<pcm.length;i++){ const v=Math.max(-1,Math.min(1,this.out[i])); pcm[i]=v<0?v*32768:v*32767; }
            this.out=[]; this.port.postMessage(pcm.buffer,[pcm.buffer]);
          }
          return true;
        }
      }
      registerProcessor('cap', Cap);`;
    const url = URL.createObjectURL(new Blob([workletSrc], { type: "application/javascript" }));
    await s.actx.audioWorklet.addModule(url);
    s.node = new AudioWorkletNode(s.actx, "cap");
    s.actx.createMediaStreamSource(s.mic).connect(s.node);

    // Output chain: boost volume, soft-limit, then route to ear (default) or loudspeaker.
    s.gain = s.actx.createGain();
    s.gain.gain.value = 3.0;
    s.comp = s.actx.createDynamicsCompressor();
    s.comp.threshold.value = -18; s.comp.knee.value = 12; s.comp.ratio.value = 6; s.comp.attack.value = 0.003; s.comp.release.value = 0.2;
    s.gain.connect(s.comp);
    s.streamDest = s.actx.createMediaStreamDestination();
    s.audioEl = new Audio();
    s.audioEl.srcObject = s.streamDest.stream;
    s.audioEl.setAttribute("playsinline", "");
    s.speaker = false;
    try { if (navigator.audioSession) navigator.audioSession.type = "play-and-record"; } catch (e) {}
    applyRoute(s);

    const wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=" + encodeURIComponent(tok.token);
    const ws = (s.ws = new WebSocket(wsUrl));
    ws.onopen = () => {
      ws.send(JSON.stringify({
        setup: {
          model: "models/" + tok.model,
          generationConfig: { responseModalities: ["AUDIO"] },
          systemInstruction: { parts: [{ text: systemPrompt(s.tasks, s.mode) }] },
          tools: TOOLS,
          inputAudioTranscription: {},
          outputAudioTranscription: {},
        },
      }));
    };
    ws.onmessage = async (ev) => {
      const raw = ev.data instanceof Blob ? await ev.data.text() : ev.data;
      let m; try { m = JSON.parse(raw); } catch (e) { return; }
      handleServer(s, m);
    };
    ws.onerror = () => { if (!s.ended) $("cstate").textContent = "இணைப்புப் பிழை"; };
    ws.onclose = (e) => { if (!s.ended) { $("cstate").textContent = "அழைப்பு துண்டிக்கப்பட்டது" + (e.reason ? ": " + e.reason : ""); finishCall(false); } };

    s.node.port.onmessage = (e) => {
      if (s.muted || !s.ready || ws.readyState !== 1) return;
      const bytes = new Uint8Array(e.data);
      let bin = ""; for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
      ws.send(JSON.stringify({ realtimeInput: { audio: { data: btoa(bin), mimeType: "audio/pcm;rate=16000" } } }));
    };
  } catch (e) {
    $("cstate").textContent = "தொடங்க முடியவில்லை: " + (e.message || e);
    finishCall(false);
  }
}

function handleServer(s, m) {
  if (m.setupComplete) {
    s.ready = true;
    $("cstate").textContent = "பேசுங்கள் — நடுவில் குறுக்கிடலாம்";
    s.ws.send(JSON.stringify({ realtimeInput: { text: "அழைப்பு தொடங்கிவிட்டது. இப்போதே தொடங்கு." } }));
    setTimeout(() => { // fallback if the model ignores realtime text
      if (!s.gotOut && !s.ended && s.ws.readyState === 1) {
        s.ws.send(JSON.stringify({ clientContent: { turns: [{ role: "user", parts: [{ text: "அழைப்பு தொடங்கிவிட்டது. இப்போதே தொடங்கு." }] }], turnComplete: true } }));
      }
    }, 6000);
  }
  const sc = m.serverContent;
  if (sc) {
    if (sc.interrupted) stopPlayback(s);
    if (sc.inputTranscription && sc.inputTranscription.text) addCap("me", sc.inputTranscription.text);
    if (sc.outputTranscription && sc.outputTranscription.text) addCap("ai", sc.outputTranscription.text);
    const parts = sc.modelTurn && sc.modelTurn.parts;
    if (parts) for (const p of parts) if (p.inlineData && p.inlineData.data) { s.gotOut = true; playChunk(s, p.inlineData.data); }
  }
  if (m.toolCall && m.toolCall.functionCalls) runTools(s, m.toolCall.functionCalls);
}

function playChunk(s, b64) {
  const bin = atob(b64);
  const n = bin.length >> 1;
  const f32 = new Float32Array(n);
  for (let i = 0; i < n; i++) { let v = bin.charCodeAt(2 * i) | (bin.charCodeAt(2 * i + 1) << 8); if (v & 0x8000) v -= 0x10000; f32[i] = v / 32768; }
  const buf = s.actx.createBuffer(1, n, 24000);
  buf.copyToChannel(f32, 0);
  const src = s.actx.createBufferSource();
  src.buffer = buf; src.connect(s.gain);
  const t = Math.max(s.actx.currentTime + 0.02, s.nextT);
  src.start(t); s.nextT = t + buf.duration;
  s.sources.add(src); src.onended = () => s.sources.delete(src);
}
// Ear mode: direct WebAudio output (phone-call style). Speaker mode: same audio through an <audio> element,
// which most phones play on the loudspeaker. Phone browsers expose no official earpiece/speaker switch,
// so this is a best-effort toggle.
function applyRoute(s) {
  try { s.comp.disconnect(); } catch (e) {}
  s.gain.gain.value = s.speaker ? 3.0 : 0.8; // loud on speaker, soft for the ear
  if (s.speaker) {
    s.comp.connect(s.streamDest);
    s.audioEl.play().catch(() => {});
  } else {
    try { s.audioEl.pause(); } catch (e) {}
    s.comp.connect(s.actx.destination);
  }
  $("spkLbl").textContent = s.speaker ? "ஸ்பீக்கர் ஆன்" : "ஸ்பீக்கர்";
  $("spk").style.opacity = s.speaker ? "1" : ".6";
}
function stopPlayback(s) {
  for (const src of s.sources) { try { src.stop(); } catch (e) {} }
  s.sources.clear(); s.nextT = 0;
}

async function runTools(s, calls) {
  const responses = [];
  let hangup = false;
  for (const c of calls) {
    const a = c.args || {};
    let out = { ok: true };
    try {
      if (c.name === "mark_done") await api("done", { id: a.task_id });
      else if (c.name === "reschedule") out = await api("reschedule", a.minutes != null && !a.due_at_iso ? { id: a.task_id, minutes: Number(a.minutes) } : { id: a.task_id, due_at: a.due_at_iso });
      else if (c.name === "add_task") out = await api("add", { title: a.title, due_at: a.due_at_iso || (a.minutes_from_now ? new Date(Date.now() + Number(a.minutes_from_now) * 60000).toISOString() : null), source: "voice" });
      else if (c.name === "cancel_task") await api("cancel", { id: a.task_id });
      else if (c.name === "end_call") hangup = true;
      else out = { ok: false, error: "unknown tool" };
    } catch (e) { out = { ok: false, error: String(e.message || e) }; }
    responses.push({ id: c.id, name: c.name, response: { output: out } });
  }
  if (s.ws && s.ws.readyState === 1) s.ws.send(JSON.stringify({ toolResponse: { functionResponses: responses } }));
  load();
  if (hangup) setTimeout(() => finishCall(true), 4000); // let the goodbye finish playing
}

function finishCall(clean) {
  const s = call; if (!s || s.ended) { closeCall(); return; }
  s.ended = true;
  try { s.ws && s.ws.close(); } catch (e) {}
  try { s.mic && s.mic.getTracks().forEach((t) => t.stop()); } catch (e) {}
  try { s.node && s.node.disconnect(); } catch (e) {}
  stopPlayback(s);
  try { s.actx && s.actx.close(); } catch (e) {}
  try { s.wake && s.wake.release(); } catch (e) {}
  call = null;
  setTimeout(closeCall, clean ? 600 : 2500);
  load();
}

$("talk").onclick = () => openIncoming();
$("answer").onclick = () => answerCall();
$("decline").onclick = () => closeCall();
$("hang").onclick = () => finishCall(true);
$("spk").onclick = () => { if (call && call.comp) { call.speaker = !call.speaker; applyRoute(call); } };
$("mute").onclick = () => {
  if (!call) return;
  call.muted = !call.muted;
  $("muteLbl").textContent = call.muted ? "மைக் ஆஃப்" : "மைக் ஆன்";
  $("mute").style.opacity = call.muted ? ".5" : "1";
};

/* ---------- boot ---------- */
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.addEventListener("message", (e) => { if (e.data && e.data.type === "incoming-call") openIncoming(); });
  navigator.serviceWorker.register("sw.js").catch(() => {});
}
(async function init() {
  if (!getToken()) { showPair(); return; }
  await idbSet("token", getToken());
  showMain();
  await load();
  updateEnableInfo();
  if (new URLSearchParams(location.search).get("call") === "1") openIncoming();
  setInterval(() => { if (!call) load(); }, 60000);
})();
