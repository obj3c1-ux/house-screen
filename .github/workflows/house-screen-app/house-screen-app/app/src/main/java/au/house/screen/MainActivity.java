package au.house.screen;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * One app for every wall screen in the house. On first start it asks for the
 * screen's link (the same /display?k=... link the Fire TV uses) and which room
 * this tablet is: bedroom, loft or door. Then it stays full screen forever.
 *
 * Hidden settings: tap the top-left corner 7 times quickly.
 */
public class MainActivity extends Activity {
    static final String[] ROOMS = {"bedroom", "loft", "door"};
    static final String[] ROOM_NAMES = {"Bedroom (main)", "Loft / bar (big tablet)", "Front door"};
    static final String HOST = "https://aurelius.obj3c1.workers.dev";
    String lastError = "", micStatus = "Mic: off";
    MicStreamer mic;

    WebView web;
    TextView banner;
    SharedPreferences prefs;
    final Handler h = new Handler(Looper.getMainLooper());
    PowerManager.WakeLock wake;
    int cornerTaps = 0;
    long cornerFirst = 0;
    int fails = 0;
    boolean loadedOk = false, httpFail = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("house", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        try {
            wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "house:screen");
            wake.acquire();
        } catch (Exception ignored) { }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        banner = new TextView(this);
        banner.setTextColor(0xFF62F2D6);
        banner.setBackgroundColor(0xCC000000);
        banner.setPadding(24, 12, 24, 12);
        banner.setTextSize(16);
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        setupWeb();
        hideBars();

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
        }
        if (prefs.getString("url", "").isEmpty()) showSettings(true);
        else load();
        h.postDelayed(watchdog, 60_000);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // music and videos start by themselves
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);                     // honours the page's width=1280 viewport
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " HouseScreen/" + BuildConfigLite.version(this));
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(Color.BLACK);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.addJavascriptInterface(new Bridge(), "HouseApp");

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                loadedOk = true; fails = 0; if (!httpFail) banner.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, android.webkit.WebResourceResponse resp) {
                if (r != null && r.isForMainFrame() && resp != null) {
                    int code = resp.getStatusCode();
                    loadedOk = true; httpFail = true;
                    showBanner(code == 403 ? "The house said no to this screen's code (403). Tap the top-left corner 7 times and check the code."
                            : "The house answered with an error (" + code + "). It will try again.", 0);
                    if (code != 403) retryLater();
                }
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r != null && r.isForMainFrame()) retryLater();
            }
            @Override @SuppressWarnings("deprecation")
            public void onReceivedError(WebView v, int code, String desc, String url) { retryLater(); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m != null && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) noteError(m.message() + " @" + m.lineNumber());
                return false;
            }
            @Override public void onPermissionRequest(final PermissionRequest req) {
                // the page may use this tablet's microphone (sound reactions, talking at the door)
                h.post(new Runnable() { public void run() { try { req.grant(req.getResources()); } catch (Exception ignored) { } } });
            }
        });
    }

    String room() { return prefs.getString("room", "bedroom"); }

    /** what was typed: just the pairing code, or the whole link */
    static String linkFrom(String typed) {
        String u = typed == null ? "" : typed.trim().replace(" ", "");
        if (u.isEmpty()) return "";
        if (u.matches("(?i)[0-9a-f]{12}([0-9a-f]{52})?")) return HOST + "/display?k=" + u.toLowerCase();
        if (!u.startsWith("http")) u = "https://" + u;
        return u;
    }

    void load() {
        String u = linkFrom(prefs.getString("url", ""));
        if (u.isEmpty()) return;
        String sep = u.contains("?") ? "&" : "?";
        u = u + sep + "room=" + room() + "&app=1";
        loadedOk = false; httpFail = false;
        web.loadUrl(u);
        startMic(u);
    }

    void startMic(String link) {
        if (mic != null) { mic.shutdown(); mic = null; }
        if (!prefs.getBoolean("mic", true)) { micStatus = "Mic: off (turned off in setup)"; return; }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { micStatus = "Mic: waiting for permission"; return; }
        mic = new MicStreamer(this, link, room(), new MicStreamer.Status() { public void set(String st) { micStatus = st; } });
        mic.start();
    }
    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) { String u = linkFrom(prefs.getString("url", "")); if (!u.isEmpty()) startMic(u); }
    }

    void showBanner(String text, long hideAfterMs) {
        banner.setText(text);
        banner.setVisibility(View.VISIBLE);
        if (hideAfterMs > 0) h.postDelayed(new Runnable() { public void run() { banner.setVisibility(View.GONE); } }, hideAfterMs);
    }
    void noteError(final String msg) {
        h.post(new Runnable() { public void run() {
            boolean first = lastError.isEmpty();
            lastError = msg == null ? "" : msg;
            if (first) showBanner("Page error: " + lastError, 20_000);
        } });
    }
    static String engine(WebView w) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/([0-9.]+)").matcher(w.getSettings().getUserAgentString());
        return m.find() ? m.group(1) : "unknown";
    }

    void retryLater() {
        loadedOk = false;
        fails++;
        long wait = Math.min(60_000, 3_000L * fails);
        banner.setText("No connection to the house, trying again in " + (wait / 1000) + " s");
        banner.setVisibility(View.VISIBLE);
        h.removeCallbacks(reload);
        h.postDelayed(reload, wait);
    }

    final Runnable reload = new Runnable() { public void run() { if (online()) load(); else retryLater(); } };

    // every minute: if the page never finished loading, try again; reload once every 6 hours to stay fresh
    long lastFull = System.currentTimeMillis();
    final Runnable watchdog = new Runnable() {
        public void run() {
            if (!loadedOk && online()) load();
            if (System.currentTimeMillis() - lastFull > 6 * 3600_000L) { lastFull = System.currentTimeMillis(); load(); }
            hideBars();
            h.postDelayed(this, 60_000);
        }
    };

    boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Exception e) { return true; }
    }

    @SuppressWarnings("deprecation")
    void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }

    // hidden settings: 7 quick taps in the top-left corner
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && e.getX() < 120 && e.getY() < 120) {
            long t = System.currentTimeMillis();
            if (t - cornerFirst > 4000) { cornerFirst = t; cornerTaps = 0; }
            if (++cornerTaps >= 7) { cornerTaps = 0; showSettings(false); return true; }
        }
        return super.dispatchTouchEvent(e);
    }

    // the back button does nothing (so nobody leaves the screen by accident); a long press of MENU opens settings on Fire remotes
    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) return true;
        if (code == KeyEvent.KEYCODE_MENU) { showSettings(false); return true; }
        return super.onKeyDown(code, e);
    }
    @Override public void onBackPressed() { }

    void showSettings(final boolean first) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);
        TextView l1 = new TextView(this);
        l1.setText("Screen code: the 12-letter pairing code from the dashboard (or the whole /display?k=… link)");
        box.addView(l1);
        final EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("e.g. b4cf2880ac44");
        url.setText(prefs.getString("url", ""));
        box.addView(url);
        TextView l2 = new TextView(this);
        l2.setText("\nWhich room is this screen?");
        box.addView(l2);
        final RadioGroup g = new RadioGroup(this);
        String cur = room();
        for (int i = 0; i < ROOMS.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(ROOM_NAMES[i]);
            r.setId(1000 + i);
            g.addView(r);
            if (ROOMS[i].equals(cur)) g.check(1000 + i);
        }
        if (g.getCheckedRadioButtonId() == -1) g.check(1000);
        box.addView(g);
        final android.widget.CheckBox micBox = new android.widget.CheckBox(this);
        micBox.setText("Send this tablet's microphone to Aurelius (so she hears this room)");
        micBox.setChecked(prefs.getBoolean("mic", true));
        box.addView(micBox);
        TextView info = new TextView(this);
        info.setText("\nWeb engine: Chrome " + engine(web) + "   ·   " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")"
                + "\n" + micStatus
                + (lastError.isEmpty() ? "" : "\nLast page error: " + lastError));
        info.setTextSize(12);
        box.addView(info);
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle("House screen setup")
                .setView(box)
                .setCancelable(!first)
                .setPositiveButton("Save", (di, w) -> {
                    int idx = g.getCheckedRadioButtonId() - 1000;
                    prefs.edit().putString("url", url.getText().toString().trim()).putBoolean("mic", micBox.isChecked())
                            .putString("room", ROOMS[Math.max(0, Math.min(ROOMS.length - 1, idx))]).apply();
                    load();
                    hideBars();
                });
        if (!first) {
            d.setNegativeButton("Cancel", (di, w) -> hideBars());
            d.setNeutralButton("Reload", (di, w) -> { web.clearCache(false); load(); hideBars(); });
        }
        d.show();
    }

    @Override protected void onResume() { super.onResume(); web.onResume(); web.resumeTimers(); hideBars(); }
    // keep playing when something briefly covers the app; the page itself stays alive
    @Override protected void onPause() { super.onPause(); }
    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (mic != null) mic.shutdown();
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /** what the page can ask the app: window.HouseApp.room(), .version(), .reload(), .settings() */
    class Bridge {
        @JavascriptInterface public String room() { return MainActivity.this.room(); }
        @JavascriptInterface public String version() { return BuildConfigLite.version(MainActivity.this); }
        @JavascriptInterface public String device() { return Build.MANUFACTURER + " " + Build.MODEL; }
        @JavascriptInterface public void reload() { h.post(MainActivity.this::load); }
        @JavascriptInterface public void settings() { h.post(() -> showSettings(false)); }
        @JavascriptInterface public void jsError(String m) { noteError(m); }
    }
}
