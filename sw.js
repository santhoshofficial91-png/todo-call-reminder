// Service worker: shows the "incoming reminder call" notification when the server pushes.
const API = "https://nicynemyjdiukkbltjwq.supabase.co/functions/v1/task-api";

function idbGet(key) {
  return new Promise((resolve) => {
    const open = indexedDB.open("rem", 1);
    open.onupgradeneeded = () => open.result.createObjectStore("kv");
    open.onerror = () => resolve(null);
    open.onsuccess = () => {
      try {
        const r = open.result.transaction("kv").objectStore("kv").get(key);
        r.onsuccess = () => resolve(r.result ?? null);
        r.onerror = () => resolve(null);
      } catch (e) { resolve(null); }
    };
  });
}

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  event.waitUntil((async () => {
    let body = "இன்னும் சில வேலைகள் பாக்கி உள்ளன. தட்டிப் பேசுங்கள்.";
    let n = 0;
    let title = "";
    try {
      const token = await idbGet("token");
      if (token) {
        const r = await fetch(API, {
          method: "POST",
          headers: { "content-type": "application/json", "x-app-token": token },
          body: JSON.stringify({ action: "summary" }),
        });
        const j = await r.json();
        n = (j.pending || []).length;
        if (j.mode === "task" && n) {
          title = "📞 நினைவூட்டல்: " + j.pending[0].title;
          body = "இந்த வேலைக்கான நேரம் வந்துவிட்டது. தட்டிப் பேசுங்கள்.";
        } else if (n) {
          body = j.pending.slice(0, 4).map((t) => "• " + t.title).join("\n") + (n > 4 ? `\n+${n - 4} மேலும்` : "");
        } else {
          body = "எல்லா வேலைகளும் முடிந்தன 🎉";
        }
      }
    } catch (e) { /* keep fallback text */ }
    await self.registration.showNotification(title || `📞 நினைவூட்டல் கால் (${n} வேலை)`, {
      body,
      tag: "reminder-call",
      renotify: true,
      requireInteraction: true,
      vibrate: [400, 200, 400, 200, 400, 200, 400],
      icon: "icon-192.png",
      badge: "icon-192.png",
      data: { url: "./?call=1" },
    });
  })());
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || "./?call=1";
  event.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const c of all) {
      if ("focus" in c) {
        await c.focus();
        c.postMessage({ type: "incoming-call" });
        return;
      }
    }
    await self.clients.openWindow(url);
  })());
});
