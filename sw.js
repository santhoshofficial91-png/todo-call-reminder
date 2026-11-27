// Service worker: shows the "incoming reminder call" notification when the server pushes.
// The server sends an encrypted payload (title/body), so we show the notification
// IMMEDIATELY with no network call (network is suspended while the phone sleeps).

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  let d = null;
  try { d = event.data ? event.data.json() : null; } catch (e) { d = null; }
  // The server still sends Tamil wrapper text; show it in English here.
  const en = (t) => String(t || "")
    .replace(/நினைவூட்டல் கால் \((\d+) வேலை\)/, "Reminder Call ($1 tasks)")
    .replace("📞 மீண்டும்: ", "📞 Again: ")
    .replace("📞 நினைவூட்டல்: ", "📞 Reminder: ")
    .replace("இந்த வேலைக்கான நேரம் வந்துவிட்டது. தட்டிப் பேசுங்கள்.", "It's time for this task. Tap to talk.");
  const title = en(d && d.title) || "📞 Reminder Call";
  const body = en(d && d.body) || "You have pending tasks. Tap to talk.";
  const mode = d && typeof d.mode === "string" ? d.mode : "hourly";
  // Retain separate task alerts even when the server does not send a task ID.
  const id = d && typeof d.task_id === "string" && /^[a-zA-Z0-9_-]{1,80}$/.test(d.task_id) ? d.task_id : null;
  const tag = mode === "task" ? "friday-task-" + (id || crypto.randomUUID()) : "friday-" + mode;
  event.waitUntil(self.registration.showNotification(title, {
    body,
    tag,
    renotify: true,
    requireInteraction: true,
    vibrate: [400, 200, 400, 200, 400, 200, 400],
    icon: "icon-192.png",
    badge: "icon-192.png",
    data: { url: "./?call=1", mode },
  }));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || "./?call=1";
  event.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const c of all) {
      if ("focus" in c && c.url.startsWith(self.registration.scope)) {
        await c.focus();
        c.postMessage({ type: "incoming-call" });
        return;
      }
    }
    await self.clients.openWindow(url);
  })());
});
