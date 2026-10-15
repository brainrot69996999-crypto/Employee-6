package com.aiemployees.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.util.regex.Pattern;

// Lets the "Fix my phone" employee tap inside the Settings app.
// It does nothing unless the user just pressed "Fix it", it only works on Settings screens,
// and it refuses anything that looks risky (resets, accounts, passwords, locks, deleting, cleaning...).
public class AgentService extends AccessibilityService {
  static AgentService me;
  static long armedUntil = 0;
  static boolean stop = false;
  boolean more;
  View box;
  interface Tap { void on(int i); }
  static final Set<String> OK = new HashSet<>(Arrays.asList("com.android.settings", "com.miui.securitycenter", "com.miui.powerkeeper"));
  static final Pattern NO = Pattern.compile("\\b(reset|erase|factory|wipe|format|delete|remove|uninstall|install|password|pin|fingerprint|face|screen lock|lock screen|sim lock|find my|find device|unknown|account|clear|clean|play protect|developer|backup|payment|wallet|forget|unpair|power off|shut down|restart|reboot)", Pattern.CASE_INSENSITIVE);
  final List<AccessibilityNodeInfo> items = new ArrayList<>();

  static boolean armed() { return System.currentTimeMillis() < armedUntil; }
  @Override public void onServiceConnected() { me = this; }
  @Override public boolean onUnbind(Intent i) { me = null; return super.onUnbind(i); }
  @Override public void onAccessibilityEvent(AccessibilityEvent e) {} // events are never read
  @Override public void onInterrupt() {}

  // A small card over Settings: shows what the helper is doing (with a Stop button) or asks a yes/no question.
  // It uses the accessibility overlay window, which needs no extra permission and works on POCO.
  void card(String text, String[] btns, Tap tap) {
    new Handler(Looper.getMainLooper()).post(() -> { try {
      WindowManager w = (WindowManager) getSystemService(WINDOW_SERVICE);
      if (box != null) { w.removeView(box); box = null; }
      if (text.isEmpty()) return;
      LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(36, 28, 36, 28); l.setBackgroundColor(0xF2FFFFFF);
      TextView t = new TextView(this); t.setText(text); t.setTextSize(18); t.setTextColor(0xFF16202E); l.addView(t);
      LinearLayout r = new LinearLayout(this);
      for (int i = 0; i < btns.length; i++) { final int k = i; Button b = new Button(this); b.setText(btns[i]); b.setOnClickListener(v -> tap.on(k)); r.addView(b); }
      l.addView(r); box = l;
      WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
      p.gravity = Gravity.BOTTOM; w.addView(box, p);
    } catch (Exception ignored) {} });
  }

  boolean inSettings() {
    AccessibilityNodeInfo r = getRootInActiveWindow();
    return r != null && r.getPackageName() != null && OK.contains(r.getPackageName().toString());
  }

  // A numbered list of the switches and tappable rows on the current Settings screen, for the AI to choose from.
  String dump() {
    items.clear();
    if (!armed() || !inSettings()) return "";
    more = false;
    StringBuilder sb = new StringBuilder();
    walk(getRootInActiveWindow(), sb);
    if (more) sb.append("(scroll down for more)\n");
    return sb.toString();
  }

  void walk(AccessibilityNodeInfo n, StringBuilder sb) {
    if (n == null || items.size() >= 40) return;
    if (n.isScrollable() && n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)) more = true;
    AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
    if (ri != null && n.isVisibleToUser()) { // sliders: the AI can set them with "slide"
      String sl = text(n, 0); if (sl.isEmpty() && n.getParent() != null) sl = text(n.getParent(), 0);
      float span = ri.getMax() - ri.getMin(); int pct = span <= 0 ? 0 : Math.round((ri.getCurrent() - ri.getMin()) * 100 / span);
      sb.append(items.size()).append(". ").append(sl).append(" [SLIDER now ").append(pct).append("%]\n"); items.add(n);
    } else if (n.isVisibleToUser() && (n.isClickable() || n.isCheckable())) {
      String label = text(n, 0);
      if (!label.isEmpty() && !label.contains("@")) {
        Boolean st = state(n);
        sb.append(items.size()).append(". ").append(label).append(st == null ? "" : (st ? " [ON]" : " [OFF]")).append("\n");
        items.add(n);
      }
    }
    for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), sb);
  }

  String text(AccessibilityNodeInfo n, int d) {
    StringBuilder s = new StringBuilder();
    if (n.getText() != null) s.append(n.getText()); else if (n.getContentDescription() != null) s.append(n.getContentDescription());
    if (d < 3) for (int i = 0; i < n.getChildCount() && s.length() < 70; i++) {
      AccessibilityNodeInfo c = n.getChild(i);
      if (c != null) { String t = text(c, d + 1); if (!t.isEmpty()) s.append(s.length() > 0 ? " - " : "").append(t); }
    }
    return s.length() > 70 ? s.substring(0, 70) : s.toString();
  }

  Boolean state(AccessibilityNodeInfo n) {
    if (n.isCheckable()) return n.isChecked();
    for (int i = 0; i < n.getChildCount(); i++) {
      AccessibilityNodeInfo c = n.getChild(i);
      if (c != null) { Boolean b = state(c); if (b != null) return b; }
    }
    return null;
  }

  String click(int id) {
    if (!armed() || !inSettings() || id < 0 || id >= items.size()) return "nothing to tap";
    AccessibilityNodeInfo n = items.get(id);
    if (!n.refresh()) return "screen changed";
    if (NO.matcher(text(n, 0)).find()) return "blocked: not allowed";
    return n.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? "ok" : "could not tap";
  }

  String slide(int id, int pct) {
    if (!armed() || !inSettings() || id < 0 || id >= items.size()) return "nothing to slide";
    AccessibilityNodeInfo n = items.get(id); AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
    if (ri == null || !n.refresh()) return "nothing to slide";
    if (NO.matcher(text(n, 0)).find()) return "blocked: not allowed";
    android.os.Bundle b = new android.os.Bundle();
    b.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, ri.getMin() + (ri.getMax() - ri.getMin()) * Math.max(0, Math.min(100, pct)) / 100f);
    return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), b) ? "ok" : "could not slide";
  }

  // Typing is allowed only into the Settings search box, so it can never enter passwords or messages.
  String type(String text) {
    if (!armed() || !inSettings()) return "nothing to type in";
    AccessibilityNodeInfo f = findSearch(getRootInActiveWindow());
    if (f == null) return "no search box";
    android.os.Bundle b = new android.os.Bundle(); b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
    return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) ? "ok" : "could not type";
  }

  AccessibilityNodeInfo findSearch(AccessibilityNodeInfo n) {
    if (n == null) return null;
    String h = (n.getHintText() == null ? "" : n.getHintText().toString()) + (n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName());
    if (n.isEditable() && h.toLowerCase().contains("search")) return n;
    for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo r = findSearch(n.getChild(i)); if (r != null) return r; }
    return null;
  }

  boolean scroll(AccessibilityNodeInfo n) {
    if (n == null) return false;
    if (n.isScrollable()) return n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    for (int i = 0; i < n.getChildCount(); i++) if (scroll(n.getChild(i))) return true;
    return false;
  }
}
