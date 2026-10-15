package com.aiemployees.app;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.service.notification.*;
import android.view.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.*;

public class EmployeeListener extends NotificationListenerService {
  static EmployeeListener me;
  final Map<String, Long> seen = new ConcurrentHashMap<>(), latest = new ConcurrentHashMap<>();
  final Map<String, JSONArray> hist = new ConcurrentHashMap<>();
  View dot;

  SharedPreferences p() { return getSharedPreferences("emp", 0); }
  static void sync() {
    if (me != null) { boolean on = me.p().getBoolean("active", false); me.dot(on); me.fg(on); }
  }
  @Override public void onListenerConnected() { me = this; sync(); }
  @Override public void onListenerDisconnected() {
    dot(false); me = null;
    try { requestRebind(new ComponentName(this, EmployeeListener.class)); } catch (Exception ignored) {}
  }
  // If you open the chat yourself, WhatsApp clears the notification and the assistant stands down.
  @Override public void onNotificationRemoved(StatusBarNotification sbn) { latest.remove(sbn.getKey()); }

  @Override public void onNotificationPosted(StatusBarNotification sbn) {
    try {
      SharedPreferences p = p(); String pkg = sbn.getPackageName();
      if (!p.getBoolean("active", false) || !(pkg.equals("com.whatsapp") || pkg.equals("com.whatsapp.w4b"))) return;
      Notification n = sbn.getNotification();
      if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || n.actions == null) return;
      Bundle e = n.extras;
      CharSequence who = e.getCharSequence(Notification.EXTRA_TITLE), msg = e.getCharSequence(Notification.EXTRA_TEXT);
      if (who == null || msg == null) return;
      if (e.getBoolean("android.isGroupConversation") && !p.getBoolean("groups", false)) return;
      Notification.Action act = null;
      for (Notification.Action a : n.actions) if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) act = a;
      if (act == null) return;

      // The preview text can be "3 new messages": read the real messages from the notification instead.
      StringBuilder chat = new StringBuilder(), fresh = new StringBuilder();
      Parcelable[] ms = e.getParcelableArray("android.messages");
      if (ms != null) for (int i = Math.max(0, ms.length - 8); i < ms.length; i++) {
        if (!(ms[i] instanceof Bundle)) continue;
        Bundle m = (Bundle) ms[i];
        CharSequence t = m.getCharSequence("text"), s = m.getCharSequence("sender");
        if (t == null) continue;
        boolean mine = s == null || s.toString().equals("You");
        chat.append(mine ? "Assistant" : s).append(": ").append(t).append("\n");
        if (mine) fresh.setLength(0); else fresh.append(fresh.length() > 0 ? " / " : "").append(t);
      }
      final String text = fresh.length() > 0 ? fresh.toString() : msg.toString();

      String k = who + "|" + text; long now = System.currentTimeMillis();
      Long t0 = seen.get(k); if (t0 != null && now - t0 < 30000) return;
      if (seen.size() > 300) seen.clear();
      seen.put(k, now);

      final String key = sbn.getKey(), name = who.toString(), ctx = chat.toString();
      final Notification.Action fa = act; final long tok = System.nanoTime();
      latest.put(key, tok);
      // Keep the CPU awake until the reply is sent, otherwise a locked phone can pause this work.
      PowerManager.WakeLock w0 = null;
      try { w0 = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "emp:reply"); w0.acquire(30000); } catch (Exception ignored) {}
      final PowerManager.WakeLock wl = w0;
      new Thread(() -> { try {
        try { Thread.sleep(1200); } catch (Exception ignored) {} // wait so a burst of messages gets one reply
        Long c = latest.get(key); if (c == null || c != tok) return;
        String ans = reply(name, text, ctx);
        c = latest.get(key); if (c == null || c != tok) return;
        seen.put(name + "|" + ans, System.currentTimeMillis());
        send(fa, ans); log(name, text, ans);
      } finally { if (wl != null && wl.isHeld()) wl.release(); } }).start();
    } catch (Exception ignored) {}
  }

  String reply(String who, String text, String ctx) {
    SharedPreferences p = p();
    JSONArray h = hist.get(who); if (h == null) hist.put(who, h = new JSONArray());
    String line = who + " just wrote: " + text;
    String ask = (ctx.split("\n").length > 1 ? "Recent chat:\n" + ctx + "\n" : "") + line + "\nWrite the reply.";
    String[][] provs = {
      {p.getString("url", ""), p.getString("model", ""), p.getString("key", "")},
      {p.getString("url2", "https://text.pollinations.ai/openai"), p.getString("model2", "openai"), ""}};
    for (String[] v : provs) {
      if (v[0].isEmpty() || (v[2].isEmpty() && !v[0].contains("pollinations"))) continue; // no key: skip instantly
      try {
        JSONArray m = new JSONArray().put(new JSONObject().put("role", "system").put("content", p.getString("sys", "Reply briefly and politely.")));
        for (int i = Math.max(0, h.length() - 6); i < h.length(); i++) m.put(h.get(i));
        m.put(new JSONObject().put("role", "user").put("content", ask));
        String a = call(v[0], v[2], new JSONObject().put("model", v[1]).put("temperature", 0.4).put("max_tokens", 120).put("messages", m).toString());
        if (a != null) {
          h.put(new JSONObject().put("role", "user").put("content", line));
          h.put(new JSONObject().put("role", "assistant").put("content", a));
          return a;
        }
      } catch (Exception ignored) {}
    }
    return local(text, p.getString("owner", ""));
  }

  String call(String url, String key, String body) {
    try {
      HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
      c.setConnectTimeout(4000); c.setReadTimeout(9000); c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
      c.getOutputStream().write(body.getBytes("UTF-8"));
      if (c.getResponseCode() != 200) return null;
      String out = new Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A").next();
      String a = new JSONObject(out).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
      a = a.replaceAll("^\"|\"$", "").replaceFirst("^(Assistant|Reply):\\s*", "").trim();
      return a.isEmpty() ? null : a;
    } catch (Exception e) { return null; }
  }

  // Only used if every AI is unreachable: still answers what was said, like a person would.
  String local(String text, String owner) {
    String t = text.toLowerCase(Locale.ROOT).trim(), o = owner.isEmpty() ? "the owner" : owner;
    if (t.matches("^(hi+|hello+|hey+|hlo+|namaste|good (morning|afternoon|evening)).{0,12}$")) return "Hi! I'm " + o + "'s assistant. " + o + " is busy right now, what's it about?";
    if (t.contains("?")) return "Good question, let me check with " + o + " and get back to you shortly.";
    return "Got it, I'll pass this on to " + o + " and get back to you.";
  }

  void send(Notification.Action a, String text) {
    try {
      Intent i = new Intent(); Bundle b = new Bundle();
      for (RemoteInput r : a.getRemoteInputs()) b.putCharSequence(r.getResultKey(), text);
      RemoteInput.addResultsToIntent(a.getRemoteInputs(), i, b);
      a.actionIntent.send(this, 0, i);
    } catch (Exception ignored) {}
  }

  // Keeps the last 100 handled messages so the app can brief the owner.
  synchronized void log(String who, String msg, String ans) {
    try {
      SharedPreferences p = p(); JSONArray old = new JSONArray(p.getString("log", "[]")), neu = new JSONArray();
      for (int i = Math.max(0, old.length() - 99); i < old.length(); i++) neu.put(old.get(i));
      neu.put(new JSONObject().put("c", who).put("m", msg).put("r", ans).put("t", System.currentTimeMillis()));
      p.edit().putString("log", neu.toString()).apply();
    } catch (Exception ignored) {}
  }

  // Runs the listener as a foreground service so phone makers are less likely to freeze it when the screen is off.
  void fg(boolean on) {
    try {
      if (on) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("duty", "Assistant on duty", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "duty").setSmallIcon(android.R.drawable.ic_dialog_info)
          .setContentTitle("Your assistant is on duty").setContentText("Replying to your WhatsApp messages").setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, 1 << 30); // 1<<30 = specialUse
        else startForeground(7, n);
      } else stopForeground(true);
    } catch (Exception ignored) {}
  }

  void dot(boolean on) {
    new Handler(Looper.getMainLooper()).post(() -> { try {
      WindowManager w = (WindowManager) getSystemService(WINDOW_SERVICE);
      if (dot != null) { w.removeView(dot); dot = null; }
      if (on && Settings.canDrawOverlays(this)) {
        dot = new View(this); dot.setBackgroundColor(0xFF1F8A5B);
        WindowManager.LayoutParams l = new WindowManager.LayoutParams(28, 28, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        l.gravity = Gravity.TOP | Gravity.END; l.x = 12; l.y = 90; w.addView(dot, l);
      }
    } catch (Exception ignored) {} });
  }
}
