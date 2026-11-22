// Service worker: shows the "incoming reminder call" notification when the server pushes.
// The server sends an encrypted payload (title/body), so we show the notification
// IMMEDIATELY with no network call (network is suspended while the phone sleeps).

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  let d = null;
  try { d = event.data ? event.data.json() : null; } catch (e) { d = null; }
  const title = (d && d.title) || "📞 Reminder Call";
  const body = (d && d.body) || "You have pending tasks. Tap to talk.";
  event.waitUntil(self.registration.showNotification(title, {
    body,
    tag: "reminder-call",
    renotify: true,
    requireInteraction: true,
    vibrate: [400, 200, 400, 200, 400, 200, 400],
    icon: "icon-192.png",
    badge: "icon-192.png",
    data: { url: "./?call=1", mode: (d && d.mode) || "hourly" },
  }));
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
