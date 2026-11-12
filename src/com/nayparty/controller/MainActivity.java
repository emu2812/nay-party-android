package com.nayparty.controller;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nảy Party — Tay cầm (native Android controller).
 *
 * Talks to the room API of the Nảy Party worker:
 *   POST /api/rooms/:code/join    {name}                  -> {seat, playerToken, color}
 *   GET  /api/rooms/:code/state?role=player&token=...     -> filtered player view
 *   POST /api/rooms/:code/action  {role, token, action..} -> updated player view
 *
 * Pure framework code (org.json ships with Android), zero external deps.
 */
public class MainActivity extends Activity {

    private static final String BASE = "https://nay-party.nguyenhailinh140101.workers.dev";
    private static final String PREFS = "nayparty";

    // ---- palette (NEON ARENA) ----
    private static final int C_BG = Color.parseColor("#0B0817");
    private static final int C_PANEL = Color.parseColor("#1B1533");
    private static final int C_PANEL2 = Color.parseColor("#251D47");
    private static final int C_TEXT = Color.parseColor("#F4F0FF");
    private static final int C_MUTED = Color.parseColor("#A79FD0");
    private static final int C_RED = Color.parseColor("#E84F4F");
    private static final int C_GREEN = Color.parseColor("#3ED97E");
    private static final HashMap<String, Integer> SEAT_HEX = new HashMap<String, Integer>();
    static {
        SEAT_HEX.put("blue", Color.parseColor("#4FA8E8"));
        SEAT_HEX.put("pink", Color.parseColor("#F77FB0"));
        SEAT_HEX.put("green", Color.parseColor("#58C472"));
        SEAT_HEX.put("purple", Color.parseColor("#A78BFA"));
    }
    private static final int[] MEM_HEX = {
        Color.parseColor("#E84F4F"), Color.parseColor("#58C472"),
        Color.parseColor("#4FA8E8"), Color.parseColor("#FFC93D") };
    private static final String[] MEM_NAME = { "Đỏ", "Xanh lá", "Xanh dương", "Vàng" };
    private static final String[] LETTERS = { "A", "B", "C", "D" };

    // pad bit indexes
    private static final int B_U = 0, B_D = 1, B_L = 2, B_R = 3, B_A = 4, B_B = 5;
    private static final String[] PAD_KEYS = { "u", "d", "l", "r", "a", "b" };

    // ---- session ----
    private SharedPreferences prefs;
    private String sCode, sName, sToken, sColor;
    private int sSeat;
    private boolean inRoom = false;

    // ---- networking ----
    private final ExecutorService padExec = Executors.newSingleThreadExecutor();
    private final ExecutorService actExec = Executors.newFixedThreadPool(4);
    private final ExecutorService pollExec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean pollRunning = false;
    private int netFailCount = 0;
    private boolean destroyed = false;
    private int actSeq = 0;

    // ---- view state ----
    private JSONObject view;            // last player view from the server
    private long serverOffset = 0;      // serverNow - deviceNow at last view
    private String currentSig = "";     // structure signature of what's on screen
    private FrameLayout rootFrame;

    // dynamic (updated in place between rebuilds)
    private TextView tvTimer;           // countdown / phase timer text
    private TextView tvMyCount;         // dua-bam my tap count
    private TextView tvLives;           // arena lives
    private View knotMarker;            // keo-co knot marker
    private View knotTrack;
    private int duaDisplay = -1;        // optimistic dua-bam display count

    // local input locks (server confirms via poll a moment later)
    private String lockKey = "";        // gameId:round the player already acted in
    private String guessEntry = "";     // doan-so keypad entry
    private String inputCtx = "";       // gameId:round the local input state belongs to

    // vibration bookkeeping
    private String lastVibKey = "";
    private String prevGameId = "";
    private Integer prevMyLives = null;
    private Boolean prevMyAlive = null;

    // gamepad state
    private final boolean[] padState = new boolean[6];

    // ======================= lifecycle =======================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        sCode = prefs.getString("code", "");
        sName = prefs.getString("name", "");
        sToken = prefs.getString("token", "");
        sColor = prefs.getString("color", "blue");
        sSeat = prefs.getInt("seat", 0);

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(C_BG);
        setContentView(rootFrame);

        if (sToken != null && sToken.length() > 0 && sCode != null && sCode.length() == 6 && sSeat > 0) {
            // Try to resume the previous room; the first poll decides.
            inRoom = true;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showRoomShell("Đang nối lại phòng " + sCode + "…");
            startPolling();
        } else {
            showJoin(null);
        }
        startTimerLoop();
    }

    private void stopPolling() {
        pollGen++;
        pollRunning = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Never leave a pad bit stuck "held" on the server.
        clearPad(true);
        // Stop polling while backgrounded; onResume restarts it.
        stopPolling();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (inRoom && !destroyed) startPolling();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        inRoom = false;
        stopPolling();
        padExec.shutdownNow();
        actExec.shutdownNow();
        pollExec.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (inRoom) { leaveRoom(null); return; }
        super.onBackPressed();
    }

    // ======================= small UI helpers =======================

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView centerText(String s, float sp, int color, boolean bold) {
        TextView t = text(s, sp, color, bold);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private GradientDrawable roundRect(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
    }

    private GradientDrawable stageBg(int top, int bottom) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { top, bottom });
        g.setCornerRadius(dp(24));
        return g;
    }

    private Button bigButton(String label, int fill, int stroke, float textSp) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(roundRect(fill, stroke, 22));
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topMargin);
        return p;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        return lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, topMargin);
    }

    private void setScreen(View v) {
        // NOTE: dynamic refs (tvTimer, tvMyCount, ...) are assigned by the
        // screen builders while the view is being constructed, i.e. BEFORE
        // this call — they must not be reset here.
        rootFrame.removeAllViews();
        rootFrame.addView(v, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void vibrate(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        } catch (Exception ignored) { }
    }

    private void vibratePattern(long[] pattern) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(pattern, -1);
        } catch (Exception ignored) { }
    }

    // ======================= JSON helpers =======================

    private static JSONObject jObj(JSONObject o, String k) {
        if (o == null) return null;
        Object v = o.opt(k);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    private static JSONArray jArr(JSONObject o, String k) {
        if (o == null) return null;
        Object v = o.opt(k);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    private static String jStr(JSONObject o, String k, String def) {
        if (o == null) return def;
        Object v = o.opt(k);
        if (v == null || v == JSONObject.NULL) return def;
        return String.valueOf(v);
    }

    private static int jInt(JSONObject o, String k, int def) {
        if (o == null) return def;
        Object v = o.opt(k);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }

    private static long jLong(JSONObject o, String k, long def) {
        if (o == null) return def;
        Object v = o.opt(k);
        if (v instanceof Number) return ((Number) v).longValue();
        return def;
    }

    private static boolean jBool(JSONObject o, String k, boolean def) {
        if (o == null) return def;
        Object v = o.opt(k);
        if (v instanceof Boolean) return (Boolean) v;
        return def;
    }

    private JSONObject playerBySeat(JSONObject v, int seat) {
        JSONArray ps = jArr(v, "players");
        if (ps == null) return null;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && jInt(p, "seat", -1) == seat) return p;
        }
        return null;
    }

    private int accent() {
        Integer c = SEAT_HEX.get(sColor);
        return c != null ? c : Color.parseColor("#4FA8E8");
    }

    private static String fmt(long n) {
        String s = String.valueOf(Math.abs(n));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) out.append('.');
            out.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + out.toString();
    }

    // ======================= HTTP =======================

    private static class Resp {
        int status; String body;
    }

    private Resp http(String method, String path, JSONObject payload) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (payload != null) {
            byte[] bytes = payload.toString().getBytes(Charset.forName("UTF-8"));
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.close();
        }
        Resp r = new Resp();
        r.status = c.getResponseCode();
        InputStream is = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = "";
        if (is != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            r.body = sb.toString();
        }
        c.disconnect();
        return r;
    }

    private JSONObject parseOk(Resp r) {
        try {
            JSONObject o = new JSONObject(r.body);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    // ======================= session / join / leave =======================

    private void saveSession() {
        prefs.edit()
            .putString("code", sCode).putString("name", sName)
            .putString("token", sToken).putString("color", sColor)
            .putInt("seat", sSeat).apply();
    }

    private void clearToken() {
        sToken = "";
        prefs.edit().remove("token").apply();
    }

    private void enterRoom() {
        inRoom = true;
        pollGen++;
        pollRunning = false;
        netFailCount = 0;
        view = null;
        currentSig = "";
        prevGameId = ""; prevMyLives = null; prevMyAlive = null; lastVibKey = "";
        lockKey = ""; guessEntry = ""; inputCtx = "";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showRoomShell("Đang nối phòng " + sCode + "…");
        startPolling();
    }

    private void leaveRoom(String message) {
        clearPad(true); // send the reset while the session is still live
        inRoom = false;
        pollRunning = false;
        pollGen++;
        clearToken();
        view = null;
        currentSig = "";
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showJoin(message);
    }

    private boolean joining = false;
    private int joinSeq = 0;

    private void doJoin(final String code, final String name, final TextView status, final Button joinBtn) {
        if (joining) return;
        joining = true;
        joinBtn.setEnabled(false);
        final int mySeq = ++joinSeq;
        status.setText("Đang vào phòng…");
        actExec.execute(new Runnable() {
            @Override public void run() { joinAttempt(code, name, 0, mySeq, status, joinBtn); }
        });
    }

    private void joinAttempt(final String code, final String name, final int attempt,
                             final int mySeq, final TextView status, final Button joinBtn) {
        JSONObject payload = new JSONObject();
        try { payload.put("name", name); } catch (Exception ignored) { }
        Resp r = null; Exception err = null;
        try { r = http("POST", "/api/rooms/" + code + "/join", payload); }
        catch (Exception e) { err = e; }
        final Resp fr = r; final Exception fe = err;
        ui.post(new Runnable() {
            @Override public void run() {
                if (mySeq != joinSeq || destroyed) return; // a newer attempt owns the screen
                if (fe != null) {
                    joining = false;
                    joinBtn.setEnabled(true);
                    status.setText("Mất kết nối. Kiểm tra mạng rồi thử lại nhé.");
                    return;
                }
                JSONObject o = parseOk(fr);
                if (o != null && o.optBoolean("ok", false)) {
                    joining = false;
                    sCode = code; sName = name;
                    sToken = jStr(o, "playerToken", "");
                    sSeat = jInt(o, "seat", 0);
                    sColor = jStr(o, "color", "blue");
                    saveSession();
                    enterRoom();
                    return;
                }
                String error = o != null ? jStr(o, "error", "") : "";
                // Fresh rooms can briefly be invisible: retry only "not found".
                if (error.contains("Không thấy phòng này") && attempt < 8) {
                    status.setText("Đang tìm phòng… (phòng mới tạo có thể cần vài giây)");
                    ui.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (mySeq != joinSeq) return;
                            actExec.execute(new Runnable() {
                                @Override public void run() { joinAttempt(code, name, attempt + 1, mySeq, status, joinBtn); }
                            });
                        }
                    }, 2000);
                    return;
                }
                joining = false;
                joinBtn.setEnabled(true);
                status.setText(error.length() > 0 ? error : "Không vào được phòng. Thử lại nhé.");
            }
        });
    }

    // ======================= polling =======================

    private void startPolling() {
        if (pollRunning) return;
        pollRunning = true;
        pollOnce(pollGen);
    }

    private int pollGen = 0;

    private void pollOnce(final int gen) {
        if (!inRoom || gen != pollGen) { if (gen == pollGen) pollRunning = false; return; }
        pollExec.execute(new Runnable() {
            @Override public void run() {
                Resp r = null; Exception err = null;
                try {
                    r = http("GET", "/api/rooms/" + sCode + "/state?role=player&token="
                        + java.net.URLEncoder.encode(sToken, "UTF-8"), null);
                } catch (Exception e) { err = e; }
                final Resp fr = r; final Exception fe = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!inRoom || gen != pollGen) { if (gen == pollGen) pollRunning = false; return; }
                        handlePoll(fr, fe);
                        ui.postDelayed(new Runnable() {
                            @Override public void run() { pollOnce(gen); }
                        }, 300);
                    }
                });
            }
        });
    }

    private void handlePoll(Resp r, Exception err) {
        JSONObject o = (err == null && r != null && r.status < 500) ? parseOk(r) : null;
        if (o == null) {
            // Network failure, an HTTP 5xx, or a non-JSON proxy error page:
            // none of these prove the room is gone, so treat them as a
            // connection hiccup and keep retrying instead of kicking the
            // player out (leaveRoom also wipes their saved token/seat).
            netFailCount++;
            if (netFailCount == 3) toast("Đang mất kết nối, đang thử lại…");
            if (netFailCount >= 25) leaveRoom("Mất kết nối với phòng. Vào lại nhé.");
            return;
        }
        if (!o.optBoolean("ok", false)) {
            // Definitive server answer: room gone or session invalid —
            // never sit on a stuck screen.
            String error = jStr(o, "error", "");
            leaveRoom(error.length() > 0 ? error : "Phòng đã đóng hoặc hết hạn. Tạo phòng mới trên TV nhé.");
            return;
        }
        netFailCount = 0;
        applyView(o);
    }

    // ======================= actions =======================

    /** Fire-and-forget gameplay action; a successful response doubles as a fresh view. */
    private void sendAction(final JSONObject body, final boolean useResponse) {
        try {
            body.put("role", "player");
            body.put("token", sToken);
        } catch (Exception ignored) { }
        final int seq = ++actSeq;
        actExec.execute(new Runnable() {
            @Override public void run() {
                Resp r = null;
                try { r = http("POST", "/api/rooms/" + sCode + "/action", body); }
                catch (Exception e) { r = null; }
                if (r == null) return;
                final JSONObject o = parseOk(r);
                if (o == null) return;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!inRoom) return;
                        if (o.optBoolean("ok", false)) {
                            // Responses can land out of order on the thread
                            // pool: only the latest action's view may win.
                            if (useResponse && seq == actSeq) applyView(o);
                        } else {
                            String error = jStr(o, "error", "");
                            if (error.length() > 0 && !error.equals("Chưa vào game.")) toast(error);
                        }
                    }
                });
            }
        });
    }

    private JSONObject actionBody(String action) {
        JSONObject b = new JSONObject();
        try { b.put("action", action); } catch (Exception ignored) { }
        return b;
    }

    /** Ordered pad channel: every pad snapshot goes through one thread. */
    private void sendPadSnapshot(final JSONObject pad) {
        padExec.execute(new Runnable() {
            @Override public void run() {
                JSONObject b = actionBody("pad");
                try {
                    b.put("role", "player");
                    b.put("token", sToken);
                    b.put("pad", pad);
                } catch (Exception ignored) { }
                try { http("POST", "/api/rooms/" + sCode + "/action", b); }
                catch (Exception ignored) { }
            }
        });
    }

    private JSONObject padJson() {
        JSONObject p = new JSONObject();
        try {
            for (int i = 0; i < 6; i++) p.put(PAD_KEYS[i], padState[i]);
        } catch (Exception ignored) { }
        return p;
    }

    private void onPadChanged() {
        if (!inRoom) return;
        sendPadSnapshot(padJson());
    }

    private void clearPad(boolean send) {
        boolean any = false;
        for (int i = 0; i < 6; i++) { if (padState[i]) { padState[i] = false; any = true; } }
        if (any && send && inRoom) sendPadSnapshot(padJson());
    }

    /** Kéo Co: one press = rising edge of B, then release — strictly ordered on the pad channel. */
    private void sendTugPress() {
        padExec.execute(new Runnable() {
            @Override public void run() {
                JSONObject on = new JSONObject();
                JSONObject off = new JSONObject();
                try {
                    for (int i = 0; i < 6; i++) { on.put(PAD_KEYS[i], i == B_B); off.put(PAD_KEYS[i], false); }
                } catch (Exception ignored) { }
                postPadSync(on);
                postPadSync(off);
            }
        });
    }

    private void postPadSync(JSONObject pad) {
        JSONObject b = actionBody("pad");
        try {
            b.put("role", "player");
            b.put("token", sToken);
            b.put("pad", pad);
        } catch (Exception ignored) { }
        try { http("POST", "/api/rooms/" + sCode + "/action", b); }
        catch (Exception ignored) { }
    }

    // ======================= view handling =======================

    private long estServerNow() { return System.currentTimeMillis() + serverOffset; }

    private void applyView(JSONObject v) {
        if (destroyed) return;
        view = v;
        // Match boundary: a new match reuses the same gameId:round keys, so
        // a local input lock (or a half-typed guess) from the PREVIOUS match
        // must never survive into it — otherwise round 1 of every repeated
        // game would start locked and its controls would ignore touches.
        if (!jStr(v, "phase", "").equals("playing")) {
            lockKey = "";
            guessEntry = "";
            inputCtx = "";
        } else {
            JSONObject gNow = jObj(v, "game");
            String ctx = gNow == null ? "" : jStr(gNow, "id", "") + ":" + jInt(gNow, "round", 0);
            if (!ctx.equals(inputCtx)) {
                inputCtx = ctx;
                guessEntry = ""; // digits typed for one round never carry into the next
            }
        }
        long sn = jLong(v, "serverNow", 0);
        if (sn > 0) serverOffset = sn - System.currentTimeMillis();
        JSONObject me = playerBySeat(v, sSeat);
        if (me != null) {
            String c = jStr(me, "color", "");
            if (c.length() > 0 && !c.equals(sColor)) { sColor = c; saveSession(); }
        }
        checkVibrations(v);
        String sig = signature(v);
        if (!sig.equals(currentSig)) {
            currentSig = sig;
            setScreen(buildRoom(v));
        } else {
            updateDynamic(v);
        }
    }

    /** The worker's lastGame entry carries the game id under "game" (not "id"). */
    private static String lastGameId(JSONObject lg) {
        if (lg == null) return "";
        String id = jStr(lg, "game", "");
        return id.length() > 0 ? id : jStr(lg, "id", "");
    }

    private String pointsSig(JSONObject v, JSONObject points) {
        if (points == null) return "";
        StringBuilder sb = new StringBuilder();
        JSONArray ps = jArr(v, "players");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p == null) continue;
                int seat = jInt(p, "seat", -1);
                sb.append(seat).append('=').append(points.optInt(String.valueOf(seat), 0)).append(',');
            }
        }
        return sb.toString();
    }

    private String signature(JSONObject v) {
        String phase = jStr(v, "phase", "");
        StringBuilder sb = new StringBuilder(phase);
        if (phase.equals("lobby") || phase.equals("choosing")) {
            JSONArray ps = jArr(v, "players");
            if (ps != null) {
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject p = ps.optJSONObject(i);
                    if (p != null) sb.append('|').append(jInt(p, "seat", 0)).append(':').append(jStr(p, "name", ""));
                }
            }
            return sb.toString();
        }
        if (phase.equals("results")) {
            JSONObject lg = jObj(v, "lastGame");
            sb.append('|').append(lg == null ? "" : lastGameId(lg));
            sb.append('|').append(pointsSig(v, lg == null ? null : jObj(lg, "points")));
            return sb.toString();
        }
        if (phase.equals("podium") || phase.equals("final")) {
            JSONArray st = jArr(v, "standings");
            if (st != null) for (int i = 0; i < st.length(); i++) sb.append('|').append(st.optInt(i, -1));
            JSONArray ps = jArr(v, "players");
            if (ps != null) {
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject p = ps.optJSONObject(i);
                    if (p != null) sb.append('|').append(jInt(p, "seat", 0)).append('=').append(jInt(p, "score", 0));
                }
            }
            return sb.toString();
        }
        if (phase.equals("playing")) {
            JSONObject g = jObj(v, "game");
            if (g == null) return sb.toString();
            String gid = jStr(g, "id", "");
            String gp = jStr(g, "phase", "");
            int round = jInt(g, "round", 0);
            sb.append('|').append(gid).append(':').append(gp).append(':').append(round);
            if (gid.equals("nhanh-tay")) {
                sb.append(':').append(jStr(g, "mine", "waiting"));
            } else if (gid.equals("do-vui")) {
                boolean locked = g.opt("mine") instanceof Number || lockKey.equals(gid + ":" + round);
                sb.append(locked ? ":locked" : ":open");
            } else if (gid.equals("doan-so")) {
                boolean locked = g.opt("mine") instanceof Number || lockKey.equals(gid + ":" + round);
                sb.append(locked ? ":locked" : ":open");
            } else if (gid.equals("nho-mau")) {
                JSONObject mine = jObj(g, "mine");
                sb.append(':').append(mine == null ? "playing" : jStr(mine, "status", "playing"));
            } else if (gid.equals("day-ra") || gid.equals("ne-dan")) {
                JSONObject blobs = jObj(g, "blobs");
                JSONObject my = blobs == null ? null : jObj(blobs, String.valueOf(sSeat));
                sb.append(':').append(my == null ? "?" : (jBool(my, "alive", true) ? "alive" : "ko"));
            } else if (gid.equals("chia-cuop")) {
                JSONArray pair = jArr(g, "pair");
                boolean inPair = pair != null && (pair.optInt(0, -1) == sSeat || pair.optInt(1, -1) == sSeat);
                sb.append(':').append(inPair ? "in" : "out");
                sb.append(':').append(jBool(g, "mineChosen", false) || lockKey.equals(gid + ":" + round) ? "chosen" : "open");
                JSONArray chat = jArr(g, "chat");
                if (chat != null) {
                    for (int i = 0; i < chat.length(); i++) {
                        JSONObject c = chat.optJSONObject(i);
                        if (c != null) sb.append('|').append(jInt(c, "seat", 0)).append(':').append(jStr(c, "text", ""));
                    }
                }
            }
            return sb.toString();
        }
        return sb.toString();
    }

    private void updateDynamic(JSONObject v) {
        JSONObject g = jObj(v, "game");
        if (g == null) return;
        String gid = jStr(g, "id", "");
        if (gid.equals("dua-bam") && tvMyCount != null) {
            int mine = jInt(g, "mine", 0);
            if (mine >= duaDisplay) duaDisplay = mine;
            if (duaDisplay < 0) duaDisplay = mine;
            tvMyCount.setText(String.valueOf(duaDisplay));
        }
        if (gid.equals("keo-co") && tvMyCount != null) {
            JSONObject presses = jObj(g, "presses");
            int mine = presses == null ? 0 : presses.optInt(String.valueOf(sSeat), 0);
            if (mine >= duaDisplay) duaDisplay = mine;
            tvMyCount.setText(String.valueOf(Math.max(duaDisplay, 0)));
        }
        if ((gid.equals("day-ra") || gid.equals("ne-dan")) && tvLives != null) {
            JSONObject blobs = jObj(g, "blobs");
            JSONObject my = blobs == null ? null : jObj(blobs, String.valueOf(sSeat));
            if (my != null) tvLives.setText(hearts(jInt(my, "lives", 0)));
        }
        if (gid.equals("keo-co") && knotMarker != null && knotTrack != null) {
            int tw = knotTrack.getWidth();
            int mw = knotMarker.getWidth();
            if (tw > mw && mw > 0) {
                double knot = 0;
                Object kv = g.opt("knot");
                if (kv instanceof Number) knot = ((Number) kv).doubleValue();
                double pct = 50 + (knot / 84.0) * 46.0;
                if (pct < 3) pct = 3;
                if (pct > 97) pct = 97;
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) knotMarker.getLayoutParams();
                p.leftMargin = (int) ((tw - mw) * pct / 100.0);
                knotMarker.setLayoutParams(p);
            }
        }
    }

    private String hearts(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("❤");
        return sb.toString();
    }

    // ======================= vibrations (mirror the web controller) =======================

    private void checkVibrations(JSONObject v) {
        JSONObject g = jObj(v, "game");
        String roomPhase = jStr(v, "phase", "");
        if (g != null) {
            String gid = jStr(g, "id", "");
            String gp = jStr(g, "phase", "");
            if (gid.equals("do-vui") && gp.equals("reveal")) {
                JSONObject rev = jObj(g, "reveal");
                Object mine = g.opt("mine");
                if (rev != null && mine instanceof Number
                        && ((Number) mine).intValue() == jInt(rev, "correct", -999)) {
                    String key = "quiz:" + jInt(g, "round", 0);
                    if (!key.equals(lastVibKey)) { lastVibKey = key; vibrate(90); }
                }
            }
            if ((gid.equals("day-ra") || gid.equals("ne-dan")) && (gp.equals("arena") || gp.equals("dodge"))) {
                JSONObject blobs = jObj(g, "blobs");
                JSONObject my = blobs == null ? null : jObj(blobs, String.valueOf(sSeat));
                if (my != null) {
                    int lives = jInt(my, "lives", 0);
                    boolean alive = jBool(my, "alive", true);
                    if (!gid.equals(prevGameId)) {
                        prevGameId = gid; prevMyLives = lives; prevMyAlive = alive;
                    } else {
                        if (prevMyLives != null && lives < prevMyLives) vibrate(150);
                        if (prevMyAlive != null && prevMyAlive && !alive) vibratePattern(new long[] { 0, 180, 60, 180 });
                        prevMyLives = lives; prevMyAlive = alive;
                    }
                }
            } else {
                prevGameId = gid; prevMyLives = null; prevMyAlive = null;
            }
        } else {
            prevGameId = ""; prevMyLives = null; prevMyAlive = null;
        }
        if (roomPhase.equals("results")) {
            JSONObject lg = jObj(v, "lastGame");
            JSONObject pts = lg == null ? null : jObj(lg, "points");
            if (pts != null) {
                int my = pts.optInt(String.valueOf(sSeat), 0);
                int max = 0;
                JSONArray ps = jArr(v, "players");
                if (ps != null) {
                    for (int i = 0; i < ps.length(); i++) {
                        JSONObject p = ps.optJSONObject(i);
                        if (p != null) max = Math.max(max, pts.optInt(String.valueOf(jInt(p, "seat", -1)), 0));
                    }
                }
                if (my > 0 && my >= max) {
                    String key = "res:" + lastGameId(lg) + ":" + pointsSig(v, pts);
                    if (!key.equals(lastVibKey)) { lastVibKey = key; vibratePattern(new long[] { 0, 70, 50, 70 }); }
                }
            }
        }
    }

    // ======================= timer loop (countdowns) =======================

    private void startTimerLoop() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                tickTimer();
                startTimerLoop();
            }
        }, 200);
    }

    private void tickTimer() {
        if (tvTimer == null || view == null) return;
        JSONObject g = jObj(view, "game");
        if (g == null) return;
        long endsAt = jLong(g, "endsAt", 0);
        if (endsAt <= 0) return;
        long rem = endsAt - estServerNow();
        Object mode = tvTimer.getTag();
        if ("cd".equals(mode)) {
            tvTimer.setText(rem > 0 ? String.valueOf((long) Math.ceil(rem / 1000.0)) : "");
        } else {
            tvTimer.setText(rem > 0 ? ("Còn " + (long) Math.ceil(rem / 1000.0) + "s") : "");
        }
    }

    // ======================= join screen =======================

    private void showJoin(String message) {
        joining = false;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(36), dp(24), dp(24));

        TextView title = centerText("NẢY PARTY", 40, C_TEXT, true);
        col.addView(title);
        TextView sub = centerText("Tay cầm điện thoại", 17, accent(), true);
        col.addView(sub);

        TextView status = text(message == null ? "" : message, 15, Color.parseColor("#FF9DB0"), false);
        status.setGravity(Gravity.CENTER);
        col.addView(status, matchWrap(18));

        col.addView(text("Mã phòng (6 số trên màn hình TV)", 15, C_MUTED, true), matchWrap(14));
        final EditText codeIn = new EditText(this);
        codeIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeIn.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(6) });
        codeIn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        codeIn.setTypeface(Typeface.DEFAULT_BOLD);
        codeIn.setGravity(Gravity.CENTER);
        codeIn.setTextColor(C_TEXT);
        codeIn.setHintTextColor(C_MUTED);
        codeIn.setHint("000000");
        codeIn.setBackground(roundRect(C_PANEL, accent(), 16));
        codeIn.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (sCode != null && sCode.length() > 0) codeIn.setText(sCode);
        col.addView(codeIn, matchWrap(6));

        col.addView(text("Tên của bạn", 15, C_MUTED, true), matchWrap(16));
        final EditText nameIn = new EditText(this);
        nameIn.setInputType(InputType.TYPE_CLASS_TEXT);
        nameIn.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(14) });
        nameIn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        nameIn.setTextColor(C_TEXT);
        nameIn.setHintTextColor(C_MUTED);
        nameIn.setHint("Ví dụ: An");
        nameIn.setBackground(roundRect(C_PANEL, accent(), 16));
        nameIn.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (sName != null && sName.length() > 0) nameIn.setText(sName);
        col.addView(nameIn, matchWrap(6));

        final Button joinBtn = bigButton("Vào phòng", Color.parseColor("#D6538A"), Color.parseColor("#F77FB0"), 22);
        col.addView(joinBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(60), 24));
        joinBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View vw) {
                String code = codeIn.getText().toString().trim();
                String name = nameIn.getText().toString().trim();
                if (code.length() != 6) { status.setText("Nhập đủ mã phòng 6 số nhé."); return; }
                if (name.length() == 0) { status.setText("Nhập tên của bạn trước nhé."); return; }
                hideKeyboard();
                doJoin(code, name, status, joinBtn);
            }
        });

        TextView hint = centerText("Mở phòng trên TV / máy tính trước, rồi nhập mã ở đây.\nMàn hình lớn chọn game, điện thoại này là tay cầm.", 14, C_MUTED, false);
        col.addView(hint, matchWrap(20));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setScreen(sv);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View cur = getCurrentFocus();
            if (imm != null && cur != null) imm.hideSoftInputFromWindow(cur.getWindowToken(), 0);
        } catch (Exception ignored) { }
    }

    // ======================= room screens =======================

    private void showRoomShell(String msg) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(24), dp(24), dp(24), dp(24));
        col.addView(centerText(msg, 20, C_TEXT, true));
        col.addView(centerText("Chờ máy chủ phản hồi…", 14, C_MUTED, false), matchWrap(8));
        Button leave = bigButton("Về màn hình vào phòng", C_PANEL2, accent(), 16);
        leave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { leaveRoom(null); }
        });
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 22);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(leave, p);
        setScreen(col);
    }

    private LinearLayout buildRoom(JSONObject v) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(12), dp(14), dp(14));

        root.addView(buildHeader(v));
        View content = contentFor(v);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cp.topMargin = dp(10);
        root.addView(content, cp);
        return root;
    }

    private View buildHeader(JSONObject v) {
        JSONObject me = playerBySeat(v, sSeat);
        String myName = me != null ? jStr(me, "name", sName) : sName;
        int myScore = me != null ? jInt(me, "liveScore", jInt(me, "score", 0)) : 0;
        String myColorName = me != null ? jStr(me, "color", sColor) : sColor;
        Integer ac = SEAT_HEX.get(myColorName);
        int accent = ac != null ? ac : accent();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(accent);
        d.setStroke(dp(2), Color.WHITE);
        dot.setBackground(d);
        row.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView nm = text("  " + myName + " · P" + sSeat, 18, C_TEXT, true);
        row.addView(nm);

        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView sc = text(fmt(myScore) + " điểm  ", 17, accent, true);
        row.addView(sc);

        Button leave = new Button(this);
        leave.setText("Thoát");
        leave.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        leave.setTypeface(Typeface.DEFAULT_BOLD);
        leave.setTextColor(C_TEXT);
        leave.setAllCaps(false);
        leave.setBackground(roundRect(C_PANEL2, C_MUTED, 14));
        leave.setPadding(dp(12), dp(4), dp(12), dp(4));
        leave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View vw) { leaveRoom(null); }
        });
        row.addView(leave, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        col.addView(row);

        JSONObject g = jObj(v, "game");
        String sub = "Phòng " + jStr(v, "code", sCode);
        if (g != null) {
            sub += " · " + jStr(g, "name", "");
            sub += " · Vòng " + jInt(g, "round", 1) + "/" + jInt(g, "totalRounds", 1);
            String gid = jStr(g, "id", "");
            if (jBool(g, "finalRound", false)
                && (gid.equals("nhanh-tay") || gid.equals("do-vui") || gid.equals("doan-so")
                    || gid.equals("dua-bam") || gid.equals("nho-mau"))) {
                sub += " · Vòng cuối ×2";
            }
        } else {
            String ph = jStr(v, "phase", "");
            if (ph.equals("lobby") || ph.equals("choosing")) sub += " · Sảnh chờ";
            else if (ph.equals("results")) sub += " · Kết quả";
            else if (ph.equals("podium")) sub += " · Bục vinh quang";
            else if (ph.equals("final")) sub += " · Kết thúc";
        }
        TextView st = text(sub, 13, C_MUTED, false);
        col.addView(st, matchWrap(4));
        return col;
    }

    private View contentFor(JSONObject v) {
        String phase = jStr(v, "phase", "");
        if (phase.equals("lobby") || phase.equals("choosing")) return lobbyView(v);
        if (phase.equals("results")) return resultsView(v);
        if (phase.equals("podium")) return standingsView(v, "🏆 Bục vinh quang", "Đợi màn hình lớn chọn game tiếp…");
        if (phase.equals("final")) return standingsView(v, "🎉 Kết thúc buổi chơi!", "Cảm ơn bạn đã chơi Nảy Party!");
        if (phase.equals("playing")) {
            JSONObject g = jObj(v, "game");
            if (g != null) return gameScreen(v, g);
        }
        return centerText("Đang tải…", 18, C_MUTED, false);
    }

    private View panel(View inner, int top, int bottom) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(stageBg(top, bottom));
        wrap.setPadding(dp(16), dp(16), dp(16), dp(16));
        wrap.addView(inner, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    private LinearLayout col() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private View lobbyView(JSONObject v) {
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = centerText("Đã vào phòng!", 26, C_TEXT, true);
        c.addView(t, matchWrap(6));
        c.addView(centerText("Đợi màn hình lớn chọn game…", 16, C_MUTED, false), matchWrap(4));
        c.addView(centerText("Trong phòng đang có:", 15, C_MUTED, true), matchWrap(14));
        JSONArray ps = jArr(v, "players");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p == null) continue;
                boolean isBot = "bot".equals(jStr(p, "type", ""));
                Integer pc = SEAT_HEX.get(jStr(p, "color", "blue"));
                TextView chip = centerText(
                    (jInt(p, "seat", 0) == sSeat ? "Bạn · " : "")
                        + jStr(p, "name", "") + (isBot ? " (Máy)" : " · P" + jInt(p, "seat", 0)),
                    17, C_TEXT, true);
                chip.setBackground(roundRect(C_PANEL2, pc != null ? pc : C_MUTED, 999));
                chip.setPadding(dp(16), dp(8), dp(16), dp(8));
                LinearLayout.LayoutParams p2 = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 8);
                p2.gravity = Gravity.CENTER_HORIZONTAL;
                c.addView(chip, p2);
            }
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(c);
        return panel(sv, C_PANEL, C_BG);
    }

    private View resultsView(JSONObject v) {
        JSONObject lg = jObj(v, "lastGame");
        JSONObject pts = lg == null ? null : jObj(lg, "points");
        LinearLayout c = col();
        c.addView(centerText("Kết quả game", 22, C_TEXT, true));
        if (lg != null) c.addView(centerText(jStr(lg, "name", ""), 17, accent(), true), matchWrap(2));
        int my = pts == null ? 0 : pts.optInt(String.valueOf(sSeat), 0);
        TextView big = centerText("+" + fmt(my) + " điểm", 40, C_GREEN, true);
        c.addView(big, matchWrap(10));
        c.addView(centerText("điểm của bạn ở game này", 14, C_MUTED, false));
        if (pts != null) {
            List<JSONObject> rows = sortedPlayersByPoints(v, pts);
            for (JSONObject p : rows) {
                int seat = jInt(p, "seat", 0);
                c.addView(scoreRow(p, pts.optInt(String.valueOf(seat), 0), seat == sSeat), matchWrap(6));
            }
        }
        c.addView(centerText("Đợi màn hình lớn chọn tiếp…", 14, C_MUTED, false), matchWrap(14));
        ScrollView sv = new ScrollView(this);
        sv.addView(c);
        return panel(sv, C_PANEL, C_BG);
    }

    private View standingsView(JSONObject v, String title, String footer) {
        LinearLayout c = col();
        c.addView(centerText(title, 24, C_TEXT, true));
        JSONArray st = jArr(v, "standings");
        int myRank = 0;
        if (st != null) {
            for (int i = 0; i < st.length(); i++) {
                int seat = st.optInt(i, -1);
                if (seat == sSeat) myRank = i + 1;
                JSONObject p = playerBySeat(v, seat);
                if (p == null) continue;
                String medal = i == 0 ? "🥇 " : i == 1 ? "🥈 " : i == 2 ? "🥉 " : (i + 1) + ". ";
                TextView row = text(medal + jStr(p, "name", "") + " — " + fmt(jInt(p, "score", 0)) + " điểm",
                    18, seat == sSeat ? accent() : C_TEXT, seat == sSeat);
                row.setBackground(roundRect(seat == sSeat ? C_PANEL2 : C_PANEL, seat == sSeat ? accent() : C_PANEL, 14));
                row.setPadding(dp(14), dp(10), dp(14), dp(10));
                c.addView(row, matchWrap(6));
            }
        }
        if (myRank > 0) c.addView(centerText("Bạn hạng " + myRank + "!", 18, accent(), true), matchWrap(12));
        c.addView(centerText(footer, 14, C_MUTED, false), matchWrap(6));
        ScrollView sv = new ScrollView(this);
        sv.addView(c);
        return panel(sv, C_PANEL, C_BG);
    }

    private List<JSONObject> sortedPlayersByPoints(JSONObject v, final JSONObject pts) {
        List<JSONObject> rows = new ArrayList<JSONObject>();
        JSONArray ps = jArr(v, "players");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p != null) rows.add(p);
            }
        }
        java.util.Collections.sort(rows, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                int pa = pts.optInt(String.valueOf(jInt(a, "seat", 0)), 0);
                int pb = pts.optInt(String.valueOf(jInt(b, "seat", 0)), 0);
                if (pb != pa) return pb - pa;
                return jInt(a, "seat", 0) - jInt(b, "seat", 0);
            }
        });
        return rows;
    }

    private View scoreRow(JSONObject p, int points, boolean mine) {
        Integer pc = SEAT_HEX.get(jStr(p, "color", "blue"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundRect(mine ? C_PANEL2 : C_PANEL, mine ? accent() : C_PANEL, 14));
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(pc != null ? pc : C_MUTED);
        dot.setBackground(d);
        row.addView(dot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        row.addView(text("  " + jStr(p, "name", ""), 17, C_TEXT, mine));
        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(text(fmt(points) + " điểm", 17, mine ? accent() : C_TEXT, true));
        return row;
    }

    // ======================= game screens =======================

    private int[] stageColors(String gid) {
        if (gid.equals("nhanh-tay")) return new int[] { 0xFF57400A, 0xFF2A1D05 };
        if (gid.equals("do-vui")) return new int[] { 0xFF173A6B, 0xFF0C1D3A };
        if (gid.equals("doan-so")) return new int[] { 0xFF0F4D33, 0xFF07271B };
        if (gid.equals("dua-bam")) return new int[] { 0xFF57246B, 0xFF2A1038 };
        if (gid.equals("nho-mau")) return new int[] { 0xFF2E2A6B, 0xFF161238 };
        if (gid.equals("day-ra")) return new int[] { 0xFF3A0E1E, 0xFF170A18 };
        if (gid.equals("ne-dan")) return new int[] { 0xFF33104F, 0xFF0C0618 };
        if (gid.equals("keo-co")) return new int[] { 0xFF0A4034, 0xFF04140F };
        if (gid.equals("chia-cuop")) return new int[] { 0xFF2B1E63, 0xFF0D0A22 };
        return new int[] { C_PANEL, C_BG };
    }

    /** Fire the action the moment the finger touches down, like the web's pointerdown dispatch. */
    private void fireOnDown(Button b, final Runnable action) {
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View vw, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    vw.setScaleX(0.94f); vw.setScaleY(0.94f);
                    action.run();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    vw.setScaleX(1f); vw.setScaleY(1f);
                }
                return true;
            }
        });
    }

    private TextView timerLine(String tag) {
        TextView t = centerText("", 15, C_MUTED, true);
        t.setTag(tag);
        tvTimer = t;
        return t;
    }

    private View gameScreen(JSONObject v, JSONObject g) {
        String gid = jStr(g, "id", "");
        String gp = jStr(g, "phase", "");
        int[] sc = stageColors(gid);
        if (gp.equals("countdown")) {
            LinearLayout c = col();
            c.setGravity(Gravity.CENTER);
            c.addView(centerText(jStr(g, "name", ""), 27, C_TEXT, true));
            TextView big = centerText("3", 110, C_TEXT, true);
            big.setTag("cd");
            tvTimer = big;
            c.addView(big);
            c.addView(centerText("Chuẩn bị…", 16, C_MUTED, false));
            return panel(c, sc[0], sc[1]);
        }
        LinearLayout inner;
        if (gid.equals("nhanh-tay")) inner = nhanhTay(v, g);
        else if (gid.equals("do-vui")) inner = doVui(v, g);
        else if (gid.equals("doan-so")) inner = doanSo(v, g);
        else if (gid.equals("dua-bam")) inner = duaBam(v, g);
        else if (gid.equals("nho-mau")) inner = nhoMau(v, g);
        else if (gid.equals("day-ra") || gid.equals("ne-dan")) inner = arenaPad(v, g);
        else if (gid.equals("keo-co")) inner = keoCo(v, g);
        else if (gid.equals("chia-cuop")) inner = chiaCuop(v, g);
        else {
            inner = col();
            inner.addView(centerText("Đang chơi: " + jStr(g, "name", ""), 18, C_TEXT, true));
        }
        return panel(inner, sc[0], sc[1]);
    }

    // ---------- Nhanh Tay ----------
    private LinearLayout nhanhTay(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gp = jStr(g, "phase", "");
        String mine = jStr(g, "mine", "waiting");
        final String lock = "nhanh-tay:" + jInt(g, "round", 0);
        if (gp.equals("wait")) {
            if (mine.equals("early")) {
                c.addView(centerText("Bạn bấm sớm!", 28, C_RED, true), matchWrap(30));
                c.addView(centerText("−100 điểm vòng này. Đợi vòng sau nhé.", 16, C_TEXT, false), matchWrap(6));
            } else {
                Button b = bigButton("ĐỢI…\nĐừng bấm vội!", C_RED, 0xFFFF8A8A, 30);
                LinearLayout.LayoutParams p = lp(dp(270), dp(270), 26);
                p.gravity = Gravity.CENTER_HORIZONTAL;
                c.addView(b, p);
                fireOnDown(b, new Runnable() {
                    @Override public void run() {
                        if (lockKey.equals(lock)) return;
                        lockKey = lock;
                        sendAction(actionBody("tap"), true);
                    }
                });
            }
        } else if (gp.equals("go")) {
            if (mine.equals("tapped")) {
                c.addView(centerText("Đã bấm! Đợi kết quả…", 26, C_TEXT, true), matchWrap(40));
            } else {
                Button b = bigButton("BẤM NGAY!", Color.parseColor("#1FA855"), C_GREEN, 32);
                LinearLayout.LayoutParams p = lp(dp(270), dp(270), 26);
                p.gravity = Gravity.CENTER_HORIZONTAL;
                c.addView(b, p);
                fireOnDown(b, new Runnable() {
                    @Override public void run() {
                        if (lockKey.equals(lock)) return;
                        lockKey = lock;
                        sendAction(actionBody("tap"), true);
                    }
                });
            }
        } else if (gp.equals("roundEnd")) {
            JSONObject lr = jObj(g, "lastRound");
            JSONArray es = lr == null ? null : jArr(lr, "entries");
            JSONObject e = null;
            if (es != null) {
                for (int i = 0; i < es.length(); i++) {
                    JSONObject x = es.optJSONObject(i);
                    if (x != null && jInt(x, "seat", -1) == sSeat) { e = x; break; }
                }
            }
            if (e != null) {
                String st = jStr(e, "status", "");
                String msg;
                if (st.equals("tapped")) {
                    int gap = jInt(e, "gapMs", 0);
                    msg = "Bạn bấm trong " + jInt(e, "reactionMs", 0) + " ms"
                        + (gap > 0 ? "\n(chậm hơn " + gap + " ms)" : "\n— NHANH NHẤT!");
                } else if (st.equals("early")) msg = "Bấm sớm mất rồi!";
                else msg = "Bạn chưa bấm";
                c.addView(centerText(msg, 25, C_TEXT, true), matchWrap(34));
                int pts = jInt(e, "points", 0);
                c.addView(centerText((pts > 0 ? "+" : "") + fmt(pts) + " điểm", 24, pts > 0 ? C_GREEN : C_MUTED, true), matchWrap(8));
            } else {
                c.addView(centerText("Hết vòng!", 24, C_TEXT, true), matchWrap(34));
            }
        } else {
            c.addView(centerText("Đợi…", 20, C_MUTED, false), matchWrap(40));
        }
        return c;
    }

    // ---------- Đố Vui Tốc Độ ----------
    private LinearLayout doVui(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        String gp = jStr(g, "phase", "");
        final int round = jInt(g, "round", 0);
        if (gp.equals("question")) {
            c.addView(text(jStr(g, "question", ""), 20, C_TEXT, true), matchWrap(2));
            c.addView(timerLine("sec"), matchWrap(2));
            Object mine = g.opt("mine");
            boolean locked = mine instanceof Number || lockKey.equals("do-vui:" + round);
            if (!locked) {
                JSONArray opts = jArr(g, "options");
                LinearLayout grid = col();
                for (int r = 0; r < 2; r++) {
                    LinearLayout rowL = new LinearLayout(this);
                    rowL.setOrientation(LinearLayout.HORIZONTAL);
                    for (int k = 0; k < 2; k++) {
                        final int idx = r * 2 + k;
                        String label = LETTERS[idx] + ". " + (opts == null ? "" : opts.optString(idx, ""));
                        Button b = bigButton(label, Color.parseColor("#2E4A7A"), accent(), 18);
                        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(84), 1f);
                        if (k == 1) bp.leftMargin = dp(10);
                        rowL.addView(b, bp);
                        fireOnDown(b, new Runnable() {
                            @Override public void run() {
                                if (lockKey.equals("do-vui:" + round)) return;
                                lockKey = "do-vui:" + round;
                                JSONObject bdy = actionBody("answer");
                                try { bdy.put("choice", idx); } catch (Exception ignored) { }
                                vibrate(25);
                                sendAction(bdy, true);
                            }
                        });
                    }
                    LinearLayout.LayoutParams rp = matchWrap(r == 0 ? 12 : 10);
                    grid.addView(rowL, rp);
                }
                c.addView(grid);
            } else {
                c.addView(centerText("Đã trả lời! Đợi mọi người…", 22, C_TEXT, true), matchWrap(26));
                int streak = jInt(g, "myStreak", 0);
                if (streak >= 1) {
                    c.addView(centerText("🔥 Chuỗi đúng ×" + (streak + 1) + " đang chờ — đúng tiếp là cộng điểm chuỗi!", 15, Color.parseColor("#FFC93D"), true), matchWrap(8));
                }
            }
        } else if (gp.equals("reveal")) {
            JSONObject rev = jObj(g, "reveal");
            Object mine = g.opt("mine");
            int correct = rev == null ? -1 : jInt(rev, "correct", -1);
            boolean okAns = mine instanceof Number && ((Number) mine).intValue() == correct;
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.addView(centerText(okAns ? "ĐÚNG RỒI!" : "Chưa đúng!", 30, okAns ? C_GREEN : C_RED, true), matchWrap(16));
            JSONArray opts = jArr(g, "options");
            if (rev != null && opts != null && correct >= 0 && correct < opts.length()) {
                c.addView(centerText("Đáp án đúng: " + LETTERS[correct] + ". " + opts.optString(correct, ""), 17, C_TEXT, false), matchWrap(8));
            }
            JSONObject pts = rev == null ? null : jObj(rev, "points");
            int my = pts == null ? 0 : pts.optInt(String.valueOf(sSeat), 0);
            c.addView(centerText("+" + fmt(my) + " điểm", 24, C_GREEN, true), matchWrap(6));
        } else {
            c.addView(centerText("Đợi câu hỏi…", 18, C_MUTED, false), matchWrap(30));
        }
        return c;
    }

    // ---------- Đoán Số Chuẩn ----------
    private LinearLayout doanSo(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        String gp = jStr(g, "phase", "");
        final int round = jInt(g, "round", 0);
        if (gp.equals("guess")) {
            c.addView(text(jStr(g, "question", "") + " (" + jStr(g, "unit", "") + ")", 19, C_TEXT, true), matchWrap(2));
            c.addView(timerLine("sec"), matchWrap(2));
            Object mine = g.opt("mine");
            boolean locked = mine instanceof Number || lockKey.equals("doan-so:" + round);
            if (locked) {
                if (mine instanceof Number) {
                    c.addView(centerText("Đã đoán: " + fmt(((Number) mine).longValue()) + " " + jStr(g, "unit", ""), 24, C_TEXT, true), matchWrap(22));
                } else {
                    c.addView(centerText("Đã chốt số đoán!", 24, C_TEXT, true), matchWrap(22));
                }
                c.addView(centerText("Đợi kết quả trên TV…", 15, C_MUTED, false), matchWrap(4));
            } else {
                final TextView display = centerText(guessEntry.length() == 0 ? "—" : fmt(Long.parseLong(guessEntry)), 40, C_TEXT, true);
                display.setBackground(roundRect(C_PANEL2, accent(), 16));
                display.setPadding(dp(10), dp(10), dp(10), dp(10));
                c.addView(display, matchWrap(8));
                String[] keys = { "1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "D" };
                LinearLayout grid = col();
                for (int r = 0; r < 4; r++) {
                    LinearLayout rowL = new LinearLayout(this);
                    rowL.setOrientation(LinearLayout.HORIZONTAL);
                    for (int k = 0; k < 3; k++) {
                        final String key = keys[r * 3 + k];
                        Button b = bigButton(key.equals("C") ? "Xoá" : key.equals("D") ? "←" : key,
                            C_PANEL2, C_MUTED, 22);
                        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(60), 1f);
                        if (k > 0) bp.leftMargin = dp(8);
                        rowL.addView(b, bp);
                        fireOnDown(b, new Runnable() {
                            @Override public void run() {
                                if (key.equals("C")) guessEntry = "";
                                else if (key.equals("D")) {
                                    if (guessEntry.length() > 0) guessEntry = guessEntry.substring(0, guessEntry.length() - 1);
                                } else if (guessEntry.length() < 9) guessEntry += key;
                                display.setText(guessEntry.length() == 0 ? "—" : fmt(Long.parseLong(guessEntry)));
                            }
                        });
                    }
                    grid.addView(rowL, matchWrap(r == 0 ? 10 : 8));
                }
                c.addView(grid);
                Button submit = bigButton("ĐOÁN!", Color.parseColor("#1FA855"), C_GREEN, 22);
                LinearLayout.LayoutParams sp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(58), 14);
                c.addView(submit, sp);
                fireOnDown(submit, new Runnable() {
                    @Override public void run() {
                        if (lockKey.equals("doan-so:" + round)) return;
                        if (guessEntry.length() == 0) { toast("Nhập số đoán trước đã."); return; }
                        long val = Long.parseLong(guessEntry);
                        guessEntry = "";
                        lockKey = "doan-so:" + round;
                        JSONObject bdy = actionBody("guess");
                        try { bdy.put("value", val); } catch (Exception ignored) { }
                        sendAction(bdy, true);
                    }
                });
            }
        } else if (gp.equals("reveal")) {
            JSONObject rev = jObj(g, "reveal");
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            if (rev != null) {
                c.addView(centerText("Đáp án đúng: " + fmt(jLong(rev, "answer", 0)) + " " + jStr(g, "unit", ""), 24, C_TEXT, true), matchWrap(14));
                JSONArray es = jArr(rev, "entries");
                JSONObject e = null;
                if (es != null) {
                    for (int i = 0; i < es.length(); i++) {
                        JSONObject x = es.optJSONObject(i);
                        if (x != null && jInt(x, "seat", -1) == sSeat) { e = x; break; }
                    }
                }
                if (e != null) {
                    Object val = e.opt("value");
                    if (val instanceof Number) {
                        Object diff = e.opt("diff");
                        c.addView(centerText("Bạn đoán " + fmt(((Number) val).longValue())
                            + (diff instanceof Number ? " · lệch " + fmt(((Number) diff).longValue()) : ""), 17, C_TEXT, false), matchWrap(6));
                    } else {
                        c.addView(centerText("Bạn không đoán vòng này", 17, C_MUTED, false), matchWrap(6));
                    }
                    c.addView(centerText("+" + fmt(jInt(e, "points", 0)) + " điểm", 24, C_GREEN, true), matchWrap(6));
                }
            }
        } else {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
        }
        return c;
    }

    // ---------- Đua Bấm Nút ----------
    private LinearLayout duaBam(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gp = jStr(g, "phase", "");
        if (gp.equals("race")) {
            duaDisplay = jInt(g, "mine", 0);
            c.addView(centerText("Số lần bấm của bạn", 17, C_MUTED, true), matchWrap(8));
            TextView count = centerText(String.valueOf(duaDisplay), 66, C_TEXT, true);
            tvMyCount = count;
            c.addView(count);
            Button b = bigButton("BẤM!", Color.parseColor("#B45FE8"), Color.parseColor("#FF9DE2"), 34);
            LinearLayout.LayoutParams p = lp(dp(250), dp(250), 10);
            p.gravity = Gravity.CENTER_HORIZONTAL;
            c.addView(b, p);
            fireOnDown(b, new Runnable() {
                @Override public void run() {
                    duaDisplay++;
                    if (tvMyCount != null) tvMyCount.setText(String.valueOf(duaDisplay));
                    sendAction(actionBody("tap"), false);
                }
            });
            c.addView(timerLine("sec"), matchWrap(2));
        } else if (gp.equals("pause")) {
            JSONObject lr = jObj(g, "lastRound");
            JSONArray es = lr == null ? null : jArr(lr, "entries");
            c.addView(centerText("Kết quả vòng", 20, C_TEXT, true), matchWrap(4));
            if (es != null) {
                for (int i = 0; i < es.length(); i++) {
                    JSONObject e = es.optJSONObject(i);
                    if (e == null) continue;
                    JSONObject p = playerBySeat(v, jInt(e, "seat", -1));
                    if (p == null) continue;
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.addView(text(jStr(p, "name", ""), 17, C_TEXT, jInt(e, "seat", -1) == sSeat));
                    row.addView(text("  · " + jInt(e, "taps", 0) + " lần", 15, C_MUTED, false));
                    View sp = new View(this);
                    row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
                    row.addView(text("+" + fmt(jInt(e, "points", 0)), 17, C_GREEN, true));
                    c.addView(row, matchWrap(6));
                }
            }
        } else {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
        }
        return c;
    }

    // ---------- Nhớ Màu ----------
    private LinearLayout nhoMau(JSONObject v, JSONObject g) {
        final LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gp = jStr(g, "phase", "");
        final int len = jInt(g, "len", 0);
        if (gp.equals("show")) {
            c.addView(centerText("Nhìn lên TV\nvà nhớ dãy màu!", 25, C_TEXT, true), matchWrap(34));
            c.addView(centerText("Dãy dài " + len + " màu", 16, C_MUTED, false), matchWrap(6));
        } else if (gp.equals("input")) {
            JSONObject mine = jObj(g, "mine");
            String status = mine == null ? "playing" : jStr(mine, "status", "playing");
            if (status.equals("done")) {
                c.addView(centerText("Hoàn thành! Đợi kết quả…", 24, C_TEXT, true), matchWrap(34));
            } else if (status.equals("failed")) {
                c.addView(centerText("Sai rồi! Vòng này 0 điểm.", 24, C_RED, true), matchWrap(34));
            } else {
                final int[] shown = new int[] { mine == null ? 0 : jInt(mine, "taps", 0) };
                final TextView prog = centerText("Đã bấm " + shown[0] + "/" + len + " màu", 19, C_TEXT, true);
                c.addView(prog, matchWrap(10));
                LinearLayout grid = col();
                for (int r = 0; r < 2; r++) {
                    LinearLayout rowL = new LinearLayout(this);
                    rowL.setOrientation(LinearLayout.HORIZONTAL);
                    for (int k = 0; k < 2; k++) {
                        final int idx = r * 2 + k;
                        Button b = new Button(this);
                        b.setText(MEM_NAME[idx]);
                        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                        b.setTypeface(Typeface.DEFAULT_BOLD);
                        b.setTextColor(Color.WHITE);
                        b.setAllCaps(false);
                        b.setBackground(roundRect(MEM_HEX[idx], Color.WHITE, 22));
                        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(112), 1f);
                        if (k == 1) bp.leftMargin = dp(12);
                        rowL.addView(b, bp);
                        fireOnDown(b, new Runnable() {
                            @Override public void run() {
                                if (shown[0] < len) {
                                    shown[0]++;
                                    prog.setText("Đã bấm " + shown[0] + "/" + len + " màu");
                                }
                                JSONObject bdy = actionBody("color");
                                try { bdy.put("color", idx); } catch (Exception ignored) { }
                                sendAction(bdy, false);
                            }
                        });
                    }
                    grid.addView(rowL, matchWrap(r == 0 ? 12 : 12));
                }
                c.addView(grid);
                c.addView(timerLine("sec"), matchWrap(4));
            }
        } else if (gp.equals("pause")) {
            JSONObject lr = jObj(g, "lastRound");
            JSONObject pts = lr == null ? null : jObj(lr, "points");
            int my = pts == null ? 0 : pts.optInt(String.valueOf(sSeat), 0);
            c.addView(centerText(my > 0 ? "Nhớ siêu quá!" : "Chưa thuộc dãy này", 25, C_TEXT, true), matchWrap(34));
            c.addView(centerText("+" + fmt(my) + " điểm", 23, C_GREEN, true), matchWrap(6));
        } else {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
        }
        return c;
    }

    // ---------- Đẩy Ra Khỏi Sàn / Né Đạn: native gamepad ----------

    /** A hold-button that tracks every pointer touching it (true multi-touch). */
    private class PadButton extends Button {
        private final int bit;
        private final HashSet<Integer> pointers = new HashSet<Integer>();
        private final GradientDrawable bgOff;
        private final GradientDrawable bgOn;

        PadButton(int bit, String label, int base, int glow, float radiusDp) {
            super(MainActivity.this);
            this.bit = bit;
            setText(label);
            setTextColor(Color.WHITE);
            setTypeface(Typeface.DEFAULT_BOLD);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            setAllCaps(false);
            setFocusable(false);
            bgOff = roundRect(base, glow, radiusDp);
            bgOn = roundRect(glow, Color.WHITE, radiusDp);
            setBackground(bgOff);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                pointers.add(ev.getPointerId(ev.getActionIndex()));
                syncBit();
                return true;
            }
            if (a == MotionEvent.ACTION_POINTER_UP || a == MotionEvent.ACTION_UP) {
                pointers.remove(ev.getPointerId(ev.getActionIndex()));
                syncBit();
                return true;
            }
            if (a == MotionEvent.ACTION_CANCEL) {
                pointers.clear();
                syncBit();
                return true;
            }
            return true;
        }

        private void syncBit() {
            boolean held = !pointers.isEmpty();
            setBackground(held ? bgOn : bgOff);
            if (padState[bit] != held) {
                padState[bit] = held;
                onPadChanged();
            }
        }
    }

    private LinearLayout arenaPad(JSONObject v, JSONObject g) {
        clearPad(true); // a rebuilt screen must never inherit a stuck "held" bit
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gid = jStr(g, "id", "");
        String gp = jStr(g, "phase", "");
        if (!(gp.equals("arena") || gp.equals("dodge"))) {
            c.addView(centerText("Đợi vào sân…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        JSONObject blobs = jObj(g, "blobs");
        JSONObject my = blobs == null ? null : jObj(blobs, String.valueOf(sSeat));
        if (my == null) {
            c.addView(centerText("Đang vào sân…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        if (!jBool(my, "alive", true)) {
            TextView ko = centerText("K.O.!", 56, C_RED, true);
            c.addView(ko, matchWrap(18));
            c.addView(centerText("Bạn đã bị loại!", 24, C_TEXT, true), matchWrap(4));
            c.addView(centerText("Xem TV đợi kết quả nhé…", 15, C_MUTED, false), matchWrap(4));
            return c;
        }
        LinearLayout livesRow = new LinearLayout(this);
        livesRow.setOrientation(LinearLayout.HORIZONTAL);
        livesRow.setGravity(Gravity.CENTER);
        livesRow.addView(text("Mạng của bạn: ", 17, C_TEXT, true));
        TextView lv = text(hearts(jInt(my, "lives", 0)), 17, Color.parseColor("#FF8FA3"), true);
        tvLives = lv;
        livesRow.addView(lv);
        c.addView(livesRow, matchWrap(6));

        LinearLayout padRow = new LinearLayout(this);
        padRow.setOrientation(LinearLayout.HORIZONTAL);
        padRow.setGravity(Gravity.CENTER);

        // D-pad (3x3 grid)
        LinearLayout dpad = col();
        int DB = dp(62);
        int[][] bits = {
            { -1, B_U, -1 },
            { B_L, -1, B_R },
            { -1, B_D, -1 } };
        String[][] labels = {
            { "", "▲", "" },
            { "◀", "", "▶" },
            { "", "▼", "" } };
        for (int r = 0; r < 3; r++) {
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 3; k++) {
                if (bits[r][k] < 0) {
                    View empty = new View(this);
                    rowL.addView(empty, new LinearLayout.LayoutParams(DB, DB));
                } else {
                    PadButton b = new PadButton(bits[r][k], labels[r][k],
                        C_PANEL2, Color.parseColor("#6DB6F2"), 16);
                    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(DB, DB);
                    if (k > 0) bp.leftMargin = dp(4);
                    rowL.addView(b, bp);
                }
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) rp.topMargin = dp(4);
            dpad.addView(rowL, rp);
        }
        padRow.addView(dpad);

        View gap = new View(this);
        padRow.addView(gap, new LinearLayout.LayoutParams(dp(26), 1));

        // A / B cluster (offset like a real gamepad)
        LinearLayout ab = col();
        LinearLayout rowA = new LinearLayout(this);
        rowA.setOrientation(LinearLayout.HORIZONTAL);
        View spA = new View(this);
        rowA.addView(spA, new LinearLayout.LayoutParams(dp(38), 1));
        PadButton btnA = new PadButton(B_A, "A\nĐẨY", Color.parseColor("#B03030"),
            Color.parseColor("#FF6B6B"), 999);
        rowA.addView(btnA, new LinearLayout.LayoutParams(dp(84), dp(84)));
        ab.addView(rowA);
        LinearLayout rowB = new LinearLayout(this);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        PadButton btnB = new PadButton(B_B, "B\nLướt", Color.parseColor("#2F7FBF"),
            Color.parseColor("#6DB6F2"), 999);
        rowB.addView(btnB, new LinearLayout.LayoutParams(dp(84), dp(84)));
        LinearLayout.LayoutParams rb = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rb.topMargin = dp(6);
        ab.addView(rowB, rb);
        padRow.addView(ab);

        LinearLayout.LayoutParams pr = matchWrap(16);
        pr.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(padRow, pr);

        c.addView(centerText(gid.equals("ne-dan")
            ? "Giữ D-pad để né đạn · B: lướt nhanh"
            : "Giữ D-pad để di chuyển · A: đẩy đối thủ · B: lướt nhanh", 14, C_MUTED, false), matchWrap(12));
        return c;
    }

    // ---------- Kéo Co ----------
    private LinearLayout keoCo(JSONObject v, JSONObject g) {
        final LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gp = jStr(g, "phase", "");
        if (!gp.equals("pull")) {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        String team = sSeat % 2 == 1 ? "A" : "B";
        JSONObject teams = jObj(g, "teams");
        if (teams != null) {
            JSONArray ta = jArr(teams, "A");
            if (ta != null) {
                for (int i = 0; i < ta.length(); i++) if (ta.optInt(i, -1) == sSeat) team = "A";
                JSONArray tb = jArr(teams, "B");
                if (tb != null) for (int i = 0; i < tb.length(); i++) if (tb.optInt(i, -1) == sSeat) team = "B";
            }
        }
        c.addView(centerText("BẠN Ở ĐỘI " + team, 22, C_TEXT, true), matchWrap(8));
        c.addView(centerText("Bấm nút KÉO liên tục!", 16, C_MUTED, false), matchWrap(2));

        // Rope: two halves + centre line + knot marker
        final FrameLayout track = new FrameLayout(this);
        LinearLayout halves = new LinearLayout(this);
        halves.setOrientation(LinearLayout.HORIZONTAL);
        View leftHalf = new View(this);
        GradientDrawable lh = new GradientDrawable();
        lh.setColor(Color.parseColor("#4FA8E8"));
        lh.setCornerRadii(new float[] { dp(15), dp(15), 0, 0, 0, 0, dp(15), dp(15) });
        leftHalf.setBackground(lh);
        halves.addView(leftHalf, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        View rightHalf = new View(this);
        GradientDrawable rh = new GradientDrawable();
        rh.setColor(Color.parseColor("#F77FB0"));
        rh.setCornerRadii(new float[] { 0, 0, dp(15), dp(15), dp(15), dp(15), 0, 0 });
        rightHalf.setBackground(rh);
        halves.addView(rightHalf, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        track.addView(halves, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(26), Gravity.CENTER_VERTICAL));
        View mid = new View(this);
        mid.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams midP = new FrameLayout.LayoutParams(dp(4), dp(38), Gravity.CENTER);
        track.addView(mid, midP);
        View marker = new View(this);
        GradientDrawable md = new GradientDrawable();
        md.setShape(GradientDrawable.OVAL);
        md.setColor(Color.parseColor("#FFC93D"));
        md.setStroke(dp(3), Color.WHITE);
        marker.setBackground(md);
        FrameLayout.LayoutParams mkP = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER_VERTICAL);
        track.addView(marker, mkP);
        knotTrack = track;
        knotMarker = marker;
        LinearLayout.LayoutParams tp = matchWrap(16);
        c.addView(track, tp);
        track.post(new Runnable() {
            @Override public void run() { if (view != null) updateDynamic(view); }
        });

        JSONObject presses = jObj(g, "presses");
        duaDisplay = presses == null ? 0 : presses.optInt(String.valueOf(sSeat), 0);
        LinearLayout pullRow = new LinearLayout(this);
        pullRow.setOrientation(LinearLayout.HORIZONTAL);
        pullRow.setGravity(Gravity.CENTER);
        pullRow.addView(text("Bạn đã kéo: ", 17, C_TEXT, true));
        TextView pc = text(String.valueOf(duaDisplay), 19, accent(), true);
        tvMyCount = pc;
        pullRow.addView(pc);
        pullRow.addView(text(" lần", 17, C_TEXT, true));
        c.addView(pullRow, matchWrap(10));

        Button b = bigButton("KÉO!", C_RED, 0xFFFF8A8A, 32);
        LinearLayout.LayoutParams bp = lp(dp(170), dp(170), 10);
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(b, bp);
        fireOnDown(b, new Runnable() {
            @Override public void run() {
                duaDisplay++;
                if (tvMyCount != null) tvMyCount.setText(String.valueOf(duaDisplay));
                sendTugPress();
            }
        });
        c.addView(timerLine("sec"), matchWrap(4));
        return c;
    }

    // ---------- Chia Hay Cướp ----------
    private String chatDraft = "";

    private LinearLayout chiaCuop(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        String gp = jStr(g, "phase", "");
        final int round = jInt(g, "round", 0);
        JSONArray pair = jArr(g, "pair");
        boolean inPair = pair != null && (pair.optInt(0, -1) == sSeat || pair.optInt(1, -1) == sSeat);
        int foeSeat = -1;
        if (inPair && pair != null) foeSeat = pair.optInt(0, -1) == sSeat ? pair.optInt(1, -1) : pair.optInt(0, -1);
        JSONObject foe = foeSeat > 0 ? playerBySeat(v, foeSeat) : null;

        if (gp.equals("talk")) {
            String potLine = "Nồi " + fmt(jInt(g, "pot", 400)) + " điểm"
                + (jBool(g, "finalRound", false) ? " · VÒNG CUỐI ×2" : "");
            c.addView(centerText(potLine, 21, Color.parseColor("#FFC93D"), true), matchWrap(4));
            JSONArray chat = jArr(g, "chat");
            if (chat != null) {
                for (int i = 0; i < chat.length(); i++) {
                    JSONObject cm = chat.optJSONObject(i);
                    if (cm == null) continue;
                    JSONObject who = playerBySeat(v, jInt(cm, "seat", -1));
                    TextView line = text((who == null ? "" : jStr(who, "name", "")) + ": " + jStr(cm, "text", ""),
                        16, C_TEXT, jInt(cm, "seat", -1) == sSeat);
                    line.setBackground(roundRect(C_PANEL2, C_PANEL2, 12));
                    line.setPadding(dp(12), dp(8), dp(12), dp(8));
                    c.addView(line, matchWrap(6));
                }
            }
            if (inPair) {
                c.addView(centerText("Bạn đấu với " + (foe == null ? "" : jStr(foe, "name", ""))
                    + ". Nói gì cũng được — lát nữa chọn mới là thật!", 15, C_MUTED, false), matchWrap(10));
                LinearLayout rowL = new LinearLayout(this);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                final EditText chatIn = new EditText(this);
                chatIn.setInputType(InputType.TYPE_CLASS_TEXT);
                chatIn.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(60) });
                chatIn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                chatIn.setTextColor(C_TEXT);
                chatIn.setHintTextColor(C_MUTED);
                chatIn.setHint("Lời thương lượng…");
                chatIn.setBackground(roundRect(C_PANEL2, accent(), 14));
                chatIn.setPadding(dp(12), dp(8), dp(12), dp(8));
                chatIn.setText(chatDraft);
                chatIn.setSelection(chatIn.getText().length());
                chatIn.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
                    @Override public void onTextChanged(CharSequence s, int a, int b, int n) { chatDraft = s.toString(); }
                    @Override public void afterTextChanged(android.text.Editable s) { }
                });
                rowL.addView(chatIn, new LinearLayout.LayoutParams(0, dp(52), 1f));
                Button send = bigButton("Gửi", Color.parseColor("#2F7FBF"), Color.parseColor("#6DB6F2"), 17);
                LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
                sbp.leftMargin = dp(8);
                rowL.addView(send, sbp);
                send.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vw) {
                        String txt = chatIn.getText().toString().trim();
                        if (txt.length() == 0) { toast("Nhập lời nhắn trước đã."); return; }
                        chatDraft = "";
                        chatIn.setText("");
                        JSONObject bdy = actionBody("chat");
                        try { bdy.put("text", txt); } catch (Exception ignored) { }
                        sendAction(bdy, true);
                    }
                });
                c.addView(rowL, matchWrap(10));
            } else {
                JSONObject w1 = pair == null ? null : playerBySeat(v, pair.optInt(0, -1));
                JSONObject w2 = pair == null ? null : playerBySeat(v, pair.optInt(1, -1));
                c.addView(centerText("Vòng này " + (w1 == null ? "" : jStr(w1, "name", "")) + " và "
                    + (w2 == null ? "" : jStr(w2, "name", "")) + " thương lượng — bạn ngồi ngoài xem.",
                    15, C_MUTED, false), matchWrap(10));
            }
            c.addView(timerLine("sec"), matchWrap(4));
        } else if (gp.equals("choose")) {
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            if (!inPair) {
                c.addView(centerText("Hai người kia đang chọn bí mật…", 22, C_TEXT, true), matchWrap(30));
                c.addView(centerText("Xem TV đoán thử ai sẽ cướp!", 15, C_MUTED, false), matchWrap(4));
            } else if (jBool(g, "mineChosen", false) || lockKey.equals("chia-cuop:" + round)) {
                c.addView(centerText("Đã khoá lựa chọn!", 25, C_TEXT, true), matchWrap(30));
                c.addView(centerText("Bí mật đến phút công bố — đợi đối thủ…", 15, C_MUTED, false), matchWrap(4));
            } else {
                c.addView(centerText("CHỌN BÍ MẬT ĐI!", 25, Color.parseColor("#FFC93D"), true), matchWrap(16));
                c.addView(centerText("Cả hai CHIA: mỗi người một nửa nồi\nMột người CƯỚP: cướp hết\nCả hai CƯỚP: mất trắng!", 15, C_TEXT, false), matchWrap(4));
                Button chia = bigButton("CHIA", Color.parseColor("#1FA855"), C_GREEN, 24);
                LinearLayout.LayoutParams cp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(62), 16);
                c.addView(chia, cp);
                fireOnDown(chia, new Runnable() {
                    @Override public void run() {
                        if (lockKey.equals("chia-cuop:" + round)) return;
                        lockKey = "chia-cuop:" + round;
                        JSONObject bdy = actionBody("choose");
                        try { bdy.put("choice", "chia"); } catch (Exception ignored) { }
                        sendAction(bdy, true);
                    }
                });
                Button cuop = bigButton("CƯỚP", Color.parseColor("#D6538A"), Color.parseColor("#F77FB0"), 24);
                LinearLayout.LayoutParams cp2 = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(62), 12);
                c.addView(cuop, cp2);
                fireOnDown(cuop, new Runnable() {
                    @Override public void run() {
                        if (lockKey.equals("chia-cuop:" + round)) return;
                        lockKey = "chia-cuop:" + round;
                        JSONObject bdy = actionBody("choose");
                        try { bdy.put("choice", "cuop"); } catch (Exception ignored) { }
                        sendAction(bdy, true);
                    }
                });
                c.addView(timerLine("sec"), matchWrap(6));
            }
        } else if (gp.equals("reveal")) {
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            JSONObject rev = jObj(g, "reveal");
            JSONArray es = rev == null ? null : jArr(rev, "entries");
            JSONObject e = null;
            if (es != null) {
                for (int i = 0; i < es.length(); i++) {
                    JSONObject x = es.optJSONObject(i);
                    if (x != null && jInt(x, "seat", -1) == sSeat) { e = x; break; }
                }
            }
            if (e != null) {
                String ch = jStr(e, "choice", "");
                c.addView(centerText("Bạn đã " + (ch.equals("cuop") ? "CƯỚP" : "CHIA"), 26, C_TEXT, true), matchWrap(30));
                c.addView(centerText("+" + fmt(jInt(e, "points", 0)) + " điểm", 24, C_GREEN, true), matchWrap(6));
            } else {
                c.addView(centerText("Xem kết quả trên TV!", 22, C_TEXT, true), matchWrap(30));
            }
        } else {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
        }
        return c;
    }
}
