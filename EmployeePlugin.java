package com.aiemployees.app;

import android.content.*;
import android.net.Uri;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.Iterator;

@CapacitorPlugin(name = "Employee")
public class EmployeePlugin extends Plugin {
  @PluginMethod public void status(PluginCall c) {
    Context x = getContext(); JSObject o = new JSObject();
    String en = Settings.Secure.getString(x.getContentResolver(), "enabled_notification_listeners");
    boolean on = en != null && en.contains(x.getPackageName());
    if (on) try { NotificationListenerService.requestRebind(new ComponentName(x, EmployeeListener.class)); } catch (Exception ignored) {}
    o.put("notif", on); o.put("listening", EmployeeListener.me != null); EmployeeListener.sync();
    o.put("overlay", Settings.canDrawOverlays(x)); o.put("brand", Build.MANUFACTURER.toLowerCase());
    o.put("agent", AgentService.me != null);
    o.put("write", Settings.System.canWrite(x));
    o.put("dnd", ((NotificationManager) x.getSystemService(Context.NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted());
    o.put("battery", ((PowerManager) x.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(x.getPackageName()));
    c.resolve(o);
  }

  @PluginMethod public void request(PluginCall c) {
    Context x = getContext(); String t = c.getString("type", "notif"); Uri pkg = Uri.parse("package:" + x.getPackageName()); Intent i;
    // Xiaomi / POCO have their own Autostart and Battery saver screens
    if (t.equals("autostart") && open(new Intent().setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))) { c.resolve(); return; }
    if (t.equals("powersaver") && open(new Intent().setComponent(new ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")).putExtra("package_name", x.getPackageName()).putExtra("package_label", "AI Employees"))) { c.resolve(); return; }
    if (t.equals("overlay")) i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg);
    else if (t.equals("battery")) i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg);
    else if (t.equals("accessibility")) i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    else if (t.equals("write")) i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg);
    else if (t.equals("dnd")) i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
    else if (t.equals("notif")) i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    else i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); x.startActivity(i); c.resolve();
  }

  boolean open(Intent in) {
    try { in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(in); return true; } catch (Exception e) { return false; }
  }

  @PluginMethod public void configure(PluginCall c) {
    SharedPreferences.Editor e = getContext().getSharedPreferences("emp", 0).edit();
    JSObject d = c.getData(); Iterator<String> k = d.keys();
    while (k.hasNext()) { String key = k.next(); Object v = d.opt(key); if (v instanceof Boolean) e.putBoolean(key, (Boolean) v); else e.putString(key, String.valueOf(v)); }
    e.apply(); EmployeeListener.sync(); c.resolve();
  }

  @PluginMethod public void getLog(PluginCall c) {
    JSObject o = new JSObject(); o.put("log", getContext().getSharedPreferences("emp", 0).getString("log", "[]")); c.resolve(o);
  }

  @PluginMethod public void clearLog(PluginCall c) {
    getContext().getSharedPreferences("emp", 0).edit().remove("log").apply(); c.resolve();
  }

  // Settings helper: only these fixed actions can run, whatever the AI asks for.
  @PluginMethod public void settingsDo(PluginCall c) {
    Context x = getContext(); JSObject r = new JSObject(); String a = c.getString("do", "");
    try {
      int v = Math.max(0, Math.min(100, c.getInt("value", 50)));
      if (a.equals("brightness") || a.equals("auto_brightness") || a.equals("timeout") || a.equals("rotate") || a.equals("font")) {
        if (!Settings.System.canWrite(x)) { r.put("ok", false); r.put("msg", "Allow \"Modify system settings\" first."); c.resolve(r); return; }
        android.content.ContentResolver cr = x.getContentResolver();
        if (a.equals("brightness")) { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0); Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.max(1, v * 255 / 100)); }
        else if (a.equals("auto_brightness")) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, c.getBoolean("on", true) ? 1 : 0);
        else if (a.equals("timeout")) Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, Math.max(15, c.getInt("value", 30)) * 1000);
        else if (a.equals("font")) Settings.System.putFloat(cr, Settings.System.FONT_SCALE, Math.max(0.85f, Math.min(2.0f, c.getInt("value", 100) / 100f)));
        else Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, c.getBoolean("on", true) ? 1 : 0);
      } else if (a.equals("volume")) {
        AudioManager am = (AudioManager) x.getSystemService(Context.AUDIO_SERVICE); String s = c.getString("stream", "media");
        int st = s.equals("ring") ? AudioManager.STREAM_RING : s.equals("alarm") ? AudioManager.STREAM_ALARM : s.equals("notification") ? AudioManager.STREAM_NOTIFICATION : AudioManager.STREAM_MUSIC;
        am.setStreamVolume(st, Math.round(v / 100f * am.getStreamMaxVolume(st)), 0);
      } else if (a.equals("flashlight")) {
        CameraManager cm = (CameraManager) x.getSystemService(Context.CAMERA_SERVICE);
        cm.setTorchMode(cm.getCameraIdList()[0], c.getBoolean("on", true));
      } else if (a.equals("ringer")) {
        AudioManager rm = (AudioManager) x.getSystemService(Context.AUDIO_SERVICE); String md = c.getString("mode", "normal");
        rm.setRingerMode(md.equals("silent") ? AudioManager.RINGER_MODE_SILENT : md.equals("vibrate") ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL);
      } else if (a.equals("dnd")) {
        NotificationManager nm = (NotificationManager) x.getSystemService(Context.NOTIFICATION_SERVICE);
        if (!nm.isNotificationPolicyAccessGranted()) { r.put("ok", false); r.put("msg", "Allow Do Not Disturb access first."); c.resolve(r); return; }
        String m = c.getString("mode", "off");
        nm.setInterruptionFilter(m.equals("silent") ? NotificationManager.INTERRUPTION_FILTER_NONE : m.equals("alarms") ? NotificationManager.INTERRUPTION_FILTER_ALARMS : m.equals("priority") ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL);
      } else if (a.equals("open") || a.equals("app")) {
        Intent i = a.equals("open") ? page(c.getString("page", "")) : appPage(x, c.getString("app", ""), c.getString("page", "details"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); x.startActivity(i);
      } else { r.put("ok", false); r.put("msg", "I can't do that one."); c.resolve(r); return; }
      r.put("ok", true);
    } catch (Exception e) { r.put("ok", false); r.put("msg", "That didn't work on this phone."); }
    c.resolve(r);
  }

  Intent page(String p) {
    switch (p) {
      case "wifi": return new Intent(Settings.ACTION_WIFI_SETTINGS);
      case "bluetooth": return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
      case "internet": return new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
      case "airplane": return new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS);
      case "network": return new Intent(Settings.ACTION_WIRELESS_SETTINGS);
      case "display": return new Intent(Settings.ACTION_DISPLAY_SETTINGS);
      case "sound": return new Intent(Settings.ACTION_SOUND_SETTINGS);
      case "battery": return new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
      case "battery_usage": return new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
      case "location": return new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
      case "apps": return new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS);
      case "default_apps": return new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
      case "storage": return new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
      case "language": return new Intent(Settings.ACTION_LOCALE_SETTINGS);
      case "datetime": return new Intent(Settings.ACTION_DATE_SETTINGS);
      case "keyboard": return new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
      case "privacy": return new Intent(Settings.ACTION_PRIVACY_SETTINGS);
      case "nfc": return new Intent(Settings.ACTION_NFC_SETTINGS);
      case "vpn": return new Intent(Settings.ACTION_VPN_SETTINGS);
      case "about": return new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS);
      case "accessibility": return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
      case "wallpaper": return new Intent(Intent.ACTION_SET_WALLPAPER);
      default: return new Intent(Settings.ACTION_SETTINGS);
    }
  }

  Intent appPage(Context x, String name, String page) {
    String pkg = null, q = name.toLowerCase().trim(); PackageManager pm = x.getPackageManager();
    if (!q.isEmpty()) for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
      if (ri.loadLabel(pm).toString().toLowerCase().contains(q)) { pkg = ri.activityInfo.packageName; break; }
    if (pkg == null) return new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS);
    return page.equals("notifications") ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
      : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
  }

  // "Fix my phone": the helper may tap in Settings only for a short time after the user presses "Fix it".
  @PluginMethod public void agentArm(PluginCall c) {
    AgentService.armedUntil = System.currentTimeMillis() + c.getInt("seconds", 60) * 1000L;
    if (c.getBoolean("reset", false)) AgentService.stop = false;
    JSObject o = new JSObject(); o.put("stop", AgentService.stop); c.resolve(o);
  }

  @PluginMethod public void agentDump(PluginCall c) {
    JSObject o = new JSObject(); AgentService a = AgentService.me;
    o.put("screen", a == null ? "" : a.dump()); c.resolve(o);
  }

  @PluginMethod public void agentDo(PluginCall c) {
    JSObject o = new JSObject(); AgentService a = AgentService.me; String d = c.getString("do", ""), res = "ok";
    if (a == null || !AgentService.armed()) res = "helper access is off";
    else if (d.equals("click")) res = a.click(c.getInt("id", -1));
    else if (d.equals("scroll")) a.scroll(a.getRootInActiveWindow());
    else if (d.equals("slide")) res = a.slide(c.getInt("id", -1), c.getInt("value", 50));
    else if (d.equals("type")) res = a.type(c.getString("text", ""));
    else if (d.equals("back")) a.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
    o.put("res", res); c.resolve(o);
  }

  @PluginMethod public void agentHome(PluginCall c) {
    try {
      Intent li = getContext().getPackageManager().getLaunchIntentForPackage(getContext().getPackageName());
      li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); getContext().startActivity(li);
    } catch (Exception ignored) {}
    c.resolve();
  }

  // A short summary of the phone's state, so the AI can find the cause without clicking through screens.
  @PluginMethod public void agentInfo(PluginCall c) {
    Context x = getContext(); StringBuilder b = new StringBuilder();
    try {
      AudioManager am = (AudioManager) x.getSystemService(Context.AUDIO_SERVICE);
      int[] st = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION};
      String[] nm = {"media", "ring", "alarm", "notification"};
      for (int i = 0; i < st.length; i++) b.append(nm[i]).append(" volume ").append(am.getStreamVolume(st[i]) * 100 / Math.max(1, am.getStreamMaxVolume(st[i]))).append("%; ");
      int rm = am.getRingerMode(); b.append("ringer ").append(rm == AudioManager.RINGER_MODE_SILENT ? "silent" : rm == AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "normal").append("; ");
      StringBuilder ext = new StringBuilder();
      for (android.media.AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
        int t = d.getType();
        if (t == 7 || t == 8 || t == 26) ext.append("Bluetooth audio, "); else if (t == 3 || t == 4) ext.append("wired headphones, "); else if (t == 11 || t == 22) ext.append("USB audio, "); else if (t == 9) ext.append("HDMI, ");
      }
      b.append("connected audio devices: ").append(ext.length() == 0 ? "none" : ext.toString()).append("; ");
      NotificationManager nm2 = (NotificationManager) x.getSystemService(Context.NOTIFICATION_SERVICE);
      b.append("do not disturb ").append(nm2.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL ? "off" : "on").append("; ");
      b.append("airplane mode ").append(Settings.Global.getInt(x.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1 ? "on" : "off").append("; ");
      android.net.ConnectivityManager cm = (android.net.ConnectivityManager) x.getSystemService(Context.CONNECTIVITY_SERVICE);
      android.net.Network net = cm.getActiveNetwork(); android.net.NetworkCapabilities nc = net == null ? null : cm.getNetworkCapabilities(net);
      String inet = "none";
      if (nc != null) inet = (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : "mobile data") + (nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? " working" : " not working");
      b.append("internet: ").append(inet).append("; ");
      b.append("Wi-Fi switch ").append(((android.net.wifi.WifiManager) x.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).isWifiEnabled() ? "on" : "off").append("; ");
      android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
      b.append("Bluetooth ").append(ba != null && ba.isEnabled() ? "on" : "off").append("; ");
      b.append("battery saver ").append(((PowerManager) x.getSystemService(Context.POWER_SERVICE)).isPowerSaveMode() ? "on" : "off").append("; ");
      b.append("battery ").append(((android.os.BatteryManager) x.getSystemService(Context.BATTERY_SERVICE)).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)).append("%; ");
      android.content.ContentResolver cr = x.getContentResolver();
      b.append("mono audio ").append(Settings.System.getInt(cr, "master_mono", 0) == 1 ? "on" : "off").append("; audio balance ").append(Settings.System.getFloat(cr, "master_balance", 0f))
        .append("; text size ").append(Math.round(x.getResources().getConfiguration().fontScale * 100)).append("%; brightness ").append(Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0) * 100 / 255)
        .append("%; screen timeout ").append(Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 0) / 1000).append("s; ");
      android.os.StatFs sf = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath()); b.append("free storage ").append(sf.getAvailableBytes() / 1000000000L).append(" GB");
    } catch (Exception ignored) {}
    JSObject o = new JSObject(); o.put("info", b.toString()); c.resolve(o);
  }

  // Progress card with a Stop button (empty text hides it).
  @PluginMethod public void agentNote(PluginCall c) {
    AgentService a = AgentService.me;
    if (a != null) a.card(c.getString("text", ""), new String[]{"Stop"}, i -> AgentService.stop = true);
    c.resolve();
  }

  // Yes/No question on the screen; answers when the user taps.
  @PluginMethod public void agentAsk(PluginCall c) {
    AgentService a = AgentService.me;
    if (a == null) { JSObject n = new JSObject(); n.put("yes", false); c.resolve(n); return; }
    a.card(c.getString("text", ""), new String[]{"Yes", "No"}, i -> { a.card("", new String[0], null); JSObject o = new JSObject(); o.put("yes", i == 0); c.resolve(o); });
  }

  static android.media.ToneGenerator tone;
  @PluginMethod public void testSound(PluginCall c) {
    try { if (tone != null) tone.release(); tone = new android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 100); tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 1500); } catch (Exception ignored) {}
    c.resolve();
  }

  // Who this phone is (brand, model, Android and skin version, language, screen, memory), so the AI can use what it knows about it.
  @PluginMethod public void agentProfile(PluginCall c) {
    Context x = getContext(); StringBuilder b = new StringBuilder();
    try {
      b.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE);
      String os = prop("ro.mi.os.version.name"); if (os.isEmpty()) os = prop("ro.miui.ui.version.name");
      if (!os.isEmpty()) b.append(", ").append(os.startsWith("OS") ? "HyperOS " + os.substring(2) : "MIUI " + os);
      android.util.DisplayMetrics dm = x.getResources().getDisplayMetrics();
      android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
      ((android.app.ActivityManager) x.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
      b.append(", language ").append(java.util.Locale.getDefault().toLanguageTag()).append(", screen ").append(dm.widthPixels).append("x").append(dm.heightPixels).append(", RAM ").append(Math.round(mi.totalMem / 1e9)).append(" GB");
    } catch (Exception ignored) {}
    JSObject o = new JSObject(); o.put("profile", b.toString()); c.resolve(o);
  }

  String prop(String k) {
    try {
      java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(Runtime.getRuntime().exec(new String[]{"getprop", k}).getInputStream()));
      String v = r.readLine(); return v == null ? "" : v.trim();
    } catch (Exception e) { return ""; }
  }
}
