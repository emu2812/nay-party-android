package com.nayparty.controller;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Nảy Party — Tay cầm (native Android controller).
 *
 * Talks to the room API of the Nảy Party worker:
 *   POST /api/rooms/:code/join    {name}                  -> {seat, playerToken, color}
 *   GET  /api/rooms/:code/state?role=player&token=...     -> filtered player view
 *   POST /api/rooms/:code/action  {role, token, action..} -> updated player view
 *   WSS  /api/rooms/:code/ws?role=player&token=...        -> primary channel while
 *        connected: pushes {"type":"state","view":...} and carries the same
 *        action objects as frames; HTTP stays as the automatic fallback.
 *
 * Pure framework code (org.json ships with Android), zero external deps —
 * the WebSocket client below is hand-rolled on a raw TLS socket (RFC 6455).
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
    private static final int C_HINT = Color.parseColor("#C7CFF0");
    private static final int C_RED = Color.parseColor("#E84F4F");
    private static final int C_GREEN = Color.parseColor("#3ED97E");
    private static final HashMap<String, Integer> SEAT_HEX = new HashMap<String, Integer>();
    static {
        SEAT_HEX.put("blue", Color.parseColor("#4FA8E8"));
        SEAT_HEX.put("pink", Color.parseColor("#F77FB0"));
        SEAT_HEX.put("green", Color.parseColor("#58C472"));
        SEAT_HEX.put("purple", Color.parseColor("#A78BFA"));
        // Seats 5-12 (worker SEAT_COLORS order): rooms now hold 12.
        SEAT_HEX.put("orange", Color.parseColor("#F59E0B"));
        SEAT_HEX.put("red", Color.parseColor("#E84F4F"));
        SEAT_HEX.put("cyan", Color.parseColor("#38BDF8"));
        SEAT_HEX.put("yellow", Color.parseColor("#FFC93D"));
        SEAT_HEX.put("lime", Color.parseColor("#A3E635"));
        SEAT_HEX.put("brown", Color.parseColor("#B07D4F"));
        SEAT_HEX.put("silver", Color.parseColor("#C0C7D1"));
        SEAT_HEX.put("magenta", Color.parseColor("#E879F9"));
    }
    private static final int[] MEM_HEX = {
        Color.parseColor("#E84F4F"), Color.parseColor("#58C472"),
        Color.parseColor("#4FA8E8"), Color.parseColor("#FFC93D") };
    private static final String[] MEM_NAME = { "Đỏ", "Xanh lá", "Xanh dương", "Vàng" };
    private static final String[] LETTERS = { "A", "B", "C", "D" };
    // Giải Mã Chung symbols (worker DECODE_SYMBOLS: name + color per index)
    private static final String[] SYM_NAME = { "ĐỎ", "XANH", "VÀNG", "HỒNG" };
    private static final int[] SYM_HEX = {
        Color.parseColor("#FF4757"), Color.parseColor("#3FA9F5"),
        Color.parseColor("#FFC93D"), Color.parseColor("#F77FB0") };

    // pad bit indexes
    private static final int B_U = 0, B_D = 1, B_L = 2, B_R = 3, B_A = 4, B_B = 5, B_X = 6, B_Y = 7;
    private static final String[] PAD_KEYS = { "u", "d", "l", "r", "a", "b", "x", "y" };

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
    private long lastViewServerNow = 0; // serverNow of the last applied view (cross-channel staleness guard)

    // ---- WebSocket transport ----
    // Primary channel while open; every send path checks wsOpen and falls
    // back to the exact v1.4 HTTP code when it is down. State machine:
    //   wsWanted (in a room session) + !wsSuspended (activity foreground)
    //   gate wsEnsure(), which starts one connect attempt per wsGen.
    //   A death (EOF / IO error / failed handshake) runs wsOnDown(gen),
    //   which schedules the next wsEnsure() with backoff 1s->2s->5s->10s.
    //   A server {"type":"bye"} closes the socket and suppresses attempts
    //   until wsSuppressUntil (10s) or the next user action (wsKick()).
    private static final String WS_HOST = "nay-party.nguyenhailinh140101.workers.dev";
    private static final long[] WS_BACKOFF = { 1000, 2000, 5000, 10000 };
    private static final SecureRandom WS_RNG = new SecureRandom();
    private final ExecutorService wsExec = Executors.newSingleThreadExecutor();
    private final Object wsWriteLock = new Object();
    private volatile boolean wsOpen = false;
    private volatile boolean wsConnecting = false;
    private volatile boolean wsSuspended = false; // activity paused
    private volatile Socket wsSocket;
    private volatile OutputStream wsOut;
    private volatile int wsGen = 0;
    private int wsDownGen = -1;         // last generation whose death was handled
    private int wsBackoffIdx = 0;
    private boolean wsWanted = false;   // in a room session (enterRoom/leaveRoom)
    private long wsSuppressUntil = 0;   // after "bye": no reconnect before this time

    // ---- view state ----
    private JSONObject view;            // last player view from the server
    private long serverOffset = 0;      // serverNow - deviceNow at last view
    private String currentSig = "";     // structure signature of what's on screen
    private FrameLayout rootFrame;

    // dynamic (updated in place between rebuilds)
    private TextView tvTimer;           // countdown / phase timer text
    private TextView tvMyCount;         // dua-bam my tap count
    private TextView tvLives;           // arena lives
    private TextView tvMineValue;       // cuop-sao banked / crown hold / splat cells / box deliveries / shared checks
    private TextView tvHearts;          // chung-mot-than team hearts
    private TextView tvDefendLine;      // thu-thanh crystal HP + team kills (updated in place)
    private TextView tvDmg;             // nguoi-que my damage % (updated in place)
    private TextView tvTempleLine;      // den-lua-nuoc gems + team hearts (updated in place)
    private TextView tvScoreLine;       // da-bong scoreboard (updated in place)
    private TextView tvFrogLine;        // ech-op hearts/chamber/flies (updated in place)
    private TextView tvAimAngle, tvAimPower, tvAimFace; // can-goc aim readouts (updated in place)
    private TextView tvCrownLine;       // giu-vuong-mien holder banner (updated in place, no rebuild)
    // cheo-thuyen fast-ticker refs (beat flash runs off server-synced time)
    private Button rowBtn;
    private GradientDrawable rowBgNorm, rowBgGold;
    private FrameLayout rowTrack;
    private View rowFill;
    private TextView tvRowMeta, tvRowHits, tvRowSplash;
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
    private final boolean[] padState = new boolean[8];

    // can-goc aim state: local stepper values while it is my turn (the
    // server stays authoritative — setAim echoes come back in the view
    // and re-sync these whenever no stepper is being held).
    private int aimAngle = 45, aimPower = 60, aimFace = 1;
    private long aimForEndsAt = -1;     // endsAt of the aim phase the locals belong to
    private int aimHoldCount = 0;       // steppers currently held (blocks readout re-sync)
    private int aimGen = 0;             // bumped off the can-goc screen: kills repeat loops
    private long aimLastSentAt = 0;
    private boolean aimFlushScheduled = false;

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
            wsWanted = true;
            wsEnsure();
        } else {
            showJoin(null);
        }
        startTimerLoop();
        startRowLoop();
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
        // Same for the socket: close it while backgrounded (wsSuspended
        // blocks the reader's reconnect scheduling); onResume reopens.
        wsSuspended = true;
        wsCloseNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        wsSuspended = false;
        if (inRoom && !destroyed) {
            startPolling();
            wsEnsure();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        inRoom = false;
        stopPolling();
        wsWanted = false;
        wsCloseNow();
        padExec.shutdownNow();
        actExec.shutdownNow();
        pollExec.shutdownNow();
        wsExec.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // configChanges keeps this activity alive across rotation, so a
        // programmatically built screen would keep its old-orientation
        // geometry. Rebuild the room screen from the last known state via
        // the normal applyView path (forcing the rebuild branch); the
        // screen builders clearPad() first, so a direction held through
        // the rotation can never stick on the server, and the session
        // (room, token, seat) is untouched. Off-room screens are fluid
        // columns that reflow on their own — rebuilding them would lose
        // half-typed join input.
        if (!destroyed && inRoom && view != null) {
            currentSig = "";
            applyView(view);
        }
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

    private static double jDouble(JSONObject o, String k, double def) {
        if (o == null) return def;
        Object v = o.opt(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
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
        // State JSON is highly repetitive; gzip shrinks every poll and
        // action download several-fold, which is the difference between
        // a playable and an unplayable controller on weak mobile data.
        c.setRequestProperty("Accept-Encoding", "gzip");
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
        if (is != null && "gzip".equalsIgnoreCase(c.getContentEncoding())) {
            // Transparently inflate (success and error bodies alike) so
            // callers still receive plain text. Reading the gzip stream
            // to EOF consumes the whole bounded body, so the keep-alive
            // pool can still reuse the connection afterwards.
            is = new GZIPInputStream(is);
        }
        r.body = "";
        if (is != null) {
            // Read the body to EOF and close the stream, but never call
            // disconnect(): with the stream fully consumed, the platform
            // keep-alive pool can reuse this connection, so the next poll
            // or pad send skips a fresh TCP+TLS handshake — a full
            // round-trip tax on every request over slow mobile data.
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            try {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                r.body = sb.toString();
            } finally {
                br.close();
            }
        }
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
        lastViewServerNow = 0;
        prevGameId = ""; prevMyLives = null; prevMyAlive = null; lastVibKey = "";
        lockKey = ""; guessEntry = ""; inputCtx = "";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showRoomShell("Đang nối phòng " + sCode + "…");
        startPolling();
        // Open the WebSocket: it becomes the primary channel as soon as
        // the handshake lands; until then (and whenever it is down) the
        // HTTP poll below runs at full speed.
        wsWanted = true;
        wsBackoffIdx = 0;
        wsSuppressUntil = 0;
        wsEnsure();
    }

    private void leaveRoom(String message) {
        clearPad(true); // send the reset while the session is still live
        wsWanted = false;
        wsCloseNow();
        inRoom = false;
        pollRunning = false;
        pollGen++;
        clearToken();
        view = null;
        currentSig = "";
        lastViewServerNow = 0;
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
                        }, wsOpen ? 2000 : 250);
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
        // A user action also ends a server-"bye" suppression window.
        wsKick();
        // WS fast path: frames carry the bare action object — identity
        // comes from the upgrade URL, so NO role/token fields are added.
        // No per-action response exists on WS; state arrives as pushes.
        if (wsSendText(body.toString())) return;
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

    // Latest-wins pad channel: a snapshot is state, not an event, so a
    // stale backlog must never queue up behind a slow connection — only
    // the newest snapshot is worth sending. One drain task on padExec
    // sends the pending snapshot and loops while a newer one lands
    // mid-send, so the final state (e.g. clearPad's all-zeros release)
    // is always flushed and never dropped. Kéo Co presses are events,
    // not state: they keep their own strictly ordered posts (see
    // sendTugPress) and are never coalesced.
    private final Object padSlotLock = new Object();
    private JSONObject pendingPad = null;
    private boolean padDrainScheduled = false;

    private void sendPadSnapshot(final JSONObject pad) {
        // A pad change is a user action: it ends a "bye" suppression.
        wsKick();
        if (wsOpen) {
            // WS path: absolute-state frames over one ordered TCP stream
            // need no coalescing — each snapshot goes out as it happens.
            // Drop anything still queued for HTTP: those snapshots predate
            // the socket, and the connect sync (plus this frame) already
            // carries newer state.
            synchronized (padSlotLock) { pendingPad = null; }
            wsSendText(padFrame(pad).toString());
            return;
        }
        synchronized (padSlotLock) {
            pendingPad = pad;
            if (padDrainScheduled) return;
            padDrainScheduled = true;
        }
        try {
            padExec.execute(new Runnable() {
                @Override public void run() {
                    for (;;) {
                        JSONObject p;
                        synchronized (padSlotLock) {
                            p = pendingPad;
                            pendingPad = null;
                            if (p == null) { padDrainScheduled = false; return; }
                        }
                        // The socket came up while this snapshot waited:
                        // it is stale by construction (the connect sync
                        // and direct WS frames carry newer state) — drop
                        // it instead of letting HTTP overwrite the pad.
                        if (wsOpen) continue;
                        postPadSync(p);
                    }
                }
            });
        } catch (RuntimeException e) {
            // Executor already shut down (onDestroy race): keep the slot
            // bookkeeping consistent instead of wedging future sends.
            synchronized (padSlotLock) { padDrainScheduled = false; }
        }
    }

    private JSONObject padJson() {
        JSONObject p = new JSONObject();
        try {
            for (int i = 0; i < padState.length; i++) p.put(PAD_KEYS[i], padState[i]);
        } catch (Exception ignored) { }
        return p;
    }

    private void onPadChanged() {
        if (!inRoom) return;
        sendPadSnapshot(padJson());
    }

    private void clearPad(boolean send) {
        boolean any = false;
        for (int i = 0; i < padState.length; i++) { if (padState[i]) { padState[i] = false; any = true; } }
        if (any && send && inRoom) sendPadSnapshot(padJson());
    }

    /** Kéo Co: one press = rising edge of B, then release — strictly ordered on the pad channel. */
    private void sendTugPress() {
        wsKick();
        final JSONObject on = new JSONObject();
        final JSONObject off = new JSONObject();
        try {
            for (int i = 0; i < PAD_KEYS.length; i++) { on.put(PAD_KEYS[i], i == B_B); off.put(PAD_KEYS[i], false); }
        } catch (Exception ignored) { }
        if (wsOpen) {
            // Same press, as two frames: the single ordered wsExec queue
            // (and TCP under it) keeps on-before-off, like the HTTP posts.
            wsSendText(padFrame(on).toString());
            wsSendText(padFrame(off).toString());
            return;
        }
        padExec.execute(new Runnable() {
            @Override public void run() {
                postPadSync(on);
                postPadSync(off);
            }
        });
    }

    /** One pad action object (identity lives in the channel, not the body). */
    private JSONObject padFrame(JSONObject pad) {
        JSONObject b = actionBody("pad");
        try { b.put("pad", pad); } catch (Exception ignored) { }
        return b;
    }

    private void postPadSync(JSONObject pad) {
        // Deliberately NOT re-routed when the socket opens mid-flight:
        // this is the tail of an ordered HTTP sequence (a Kéo Co on/off
        // pair, or the drain), and finishing it on the same channel keeps
        // its internal order absolute. Fresh input never starts here —
        // sendPadSnapshot/sendTugPress pick the channel once, up front.
        JSONObject b = actionBody("pad");
        try {
            b.put("role", "player");
            b.put("token", sToken);
            b.put("pad", pad);
        } catch (Exception ignored) { }
        try { http("POST", "/api/rooms/" + sCode + "/action", b); }
        catch (Exception ignored) { }
    }

    // ======================= WebSocket transport =======================
    // Hand-rolled RFC 6455 client on a raw TLS socket (no external deps).
    // Threading: ONE reader thread per connection parses frames and posts
    // decoded messages to the UI thread; ALL client->server frames are
    // written by tasks on the single-thread wsExec queue, so text sends
    // stay in submission order (tug on/off pairs included). The reader
    // thread's pongs bypass the queue but share wsWriteLock with the
    // queued writes, so bytes from the two writers can never interleave
    // mid-frame. A connection is identified by its wsGen; any callback
    // carrying a stale gen is a no-op, which keeps teardown race-free.

    /** Start a connect attempt if one is due. Safe to call from anywhere. */
    private void wsEnsure() {
        if (destroyed || !wsWanted || wsSuspended || wsOpen || wsConnecting) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs < wsSuppressUntil) {
            // Server said "bye": wait out the window, then retry on our own.
            final int gen = wsGen;
            ui.postDelayed(new Runnable() {
                @Override public void run() { if (gen == wsGen) wsEnsure(); }
            }, wsSuppressUntil - nowMs + 50);
            return;
        }
        wsConnecting = true;
        final int gen = ++wsGen;
        try {
            wsExec.execute(new Runnable() {
                @Override public void run() { wsConnect(gen); }
            });
        } catch (RuntimeException e) {
            wsConnecting = false; // executor shut down (onDestroy race)
        }
    }

    /** A user action ends a "bye" suppression immediately, then reconnects. */
    private void wsKick() {
        if (wsSuppressUntil > 0) wsSuppressUntil = 0;
        wsEnsure();
    }

    /** Full teardown: invalidate the generation, close the socket. */
    private void wsCloseNow() {
        wsGen++;
        wsOpen = false;
        wsConnecting = false;
        Socket s = wsSocket;
        wsSocket = null;
        wsOut = null;
        closeQuietly(s);
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) { }
    }

    /** One connection died (EOF, IO error, or a failed handshake). */
    private void wsOnDown(int gen) {
        if (gen != wsGen || gen == wsDownGen) return; // stale or already handled
        wsDownGen = gen;
        wsOpen = false;
        wsConnecting = false;
        Socket s = wsSocket;
        wsSocket = null;
        wsOut = null;
        closeQuietly(s);
        if (destroyed || !wsWanted || wsSuspended) return;
        long delay = WS_BACKOFF[Math.min(wsBackoffIdx, WS_BACKOFF.length - 1)];
        if (wsBackoffIdx < WS_BACKOFF.length - 1) wsBackoffIdx++;
        final int g = gen;
        ui.postDelayed(new Runnable() {
            @Override public void run() { if (g == wsGen) wsEnsure(); }
        }, delay);
    }

    /** Connect + HTTP upgrade handshake. Runs on wsExec. */
    private void wsConnect(final int gen) {
        Socket sock = null;
        try {
            sock = SSLSocketFactory.getDefault().createSocket(WS_HOST, 443);
            wsSocket = sock; // lets wsCloseNow abort a stuck handshake
            sock.setSoTimeout(10000);
            ((SSLSocket) sock).startHandshake();

            byte[] keyBytes = new byte[16];
            WS_RNG.nextBytes(keyBytes);
            String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
            String path = "/api/rooms/" + sCode + "/ws?role=player&token="
                + java.net.URLEncoder.encode(sToken, "UTF-8");
            String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + WS_HOST + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
            OutputStream out = sock.getOutputStream();
            out.write(req.getBytes("ISO-8859-1"));
            out.flush();

            // The same buffered stream feeds the frame reader afterwards,
            // so bytes the server pipelined past the header block survive.
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream(), 8192);
            String headers = wsReadHeaders(in);
            if (!wsHandshakeOk(headers, key)) throw new Exception("upgrade rejected");

            if (gen != wsGen || destroyed || !wsWanted || wsSuspended) {
                // Torn down (or backgrounded) while handshaking: the fresh
                // socket is useless — drop it; wsOnDown clears the flags.
                closeQuietly(sock);
                wsOnDown(gen);
                return;
            }
            sock.setSoTimeout(0);
            wsOut = out;
            wsOpen = true;
            wsConnecting = false;
            wsBackoffIdx = 0;
            final BufferedInputStream fin = in;
            Thread reader = new Thread(new Runnable() {
                @Override public void run() { wsReadLoop(gen, fin); }
            }, "np-ws-reader");
            reader.setDaemon(true);
            reader.start();
            // Sync the current pad state over the fresh socket, so input
            // state on the server matches the fingers from frame one.
            if (view != null && "playing".equals(jStr(view, "phase", ""))) {
                wsSendText(padFrame(padJson()).toString());
            }
        } catch (Exception e) {
            closeQuietly(sock);
            if (wsSocket == sock) wsSocket = null;
            wsOnDown(gen); // failed handshake counts as down: backoff + HTTP fallback
        }
    }

    /** Read an HTTP header block (up to \r\n\r\n) off the upgrade response. */
    private static String wsReadHeaders(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final byte[] end = { '\r', '\n', '\r', '\n' };
        int matched = 0;
        int b;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            if (buf.size() > 16384) throw new Exception("headers too large");
            if (b == end[matched]) {
                matched++;
                if (matched == 4) return new String(buf.toByteArray(), "ISO-8859-1");
            } else {
                matched = (b == '\r') ? 1 : 0;
            }
        }
        throw new Exception("eof during handshake");
    }

    /** Validate the 101 and, when present, the Sec-WebSocket-Accept hash. */
    private static boolean wsHandshakeOk(String headers, String key) {
        int eol = headers.indexOf("\r\n");
        String statusLine = eol >= 0 ? headers.substring(0, eol) : headers;
        if (!statusLine.contains(" 101")) return false;
        String accept = null;
        String[] lines = headers.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase("sec-websocket-accept")) {
                accept = lines[i].substring(colon + 1).trim();
            }
        }
        if (accept == null) return true; // 101 verified; accept is a "when present" check
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            String expect = Base64.encodeToString(
                sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("ISO-8859-1")),
                Base64.NO_WRAP);
            return expect.equals(accept);
        } catch (Exception e) {
            return true; // never block a 101 on local crypto trouble
        }
    }

    /**
     * Queue one text frame for sending. Returns false (caller falls back
     * to HTTP) when the socket is down or the queue is gone. Fire-and-
     * forget once queued: a failed write tears the connection down via
     * wsOnDown, and the surviving HTTP poll keeps the game playable.
     */
    private boolean wsSendText(final String text) {
        if (!wsOpen) return false;
        final int gen = wsGen;
        try {
            wsExec.execute(new Runnable() {
                @Override public void run() {
                    try { wsWriteFrame(0x1, text.getBytes("UTF-8")); }
                    catch (Exception e) { wsOnDown(gen); }
                }
            });
            return true;
        } catch (RuntimeException e) {
            return false; // executor shut down: HTTP fallback takes it
        }
    }

    /** Serialize one masked client frame and write it under the write lock. */
    private void wsWriteFrame(int opcode, byte[] payload) throws Exception {
        OutputStream out = wsOut;
        if (out == null) throw new Exception("ws not open");
        int len = payload.length;
        ByteArrayOutputStream frame = new ByteArrayOutputStream(len + 14);
        frame.write(0x80 | opcode); // FIN + opcode
        if (len < 126) {
            frame.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) frame.write((int) ((len >> (8 * i)) & 0xFF));
        }
        byte[] mask = new byte[4];
        WS_RNG.nextBytes(mask);
        frame.write(mask, 0, 4);
        for (int i = 0; i < len; i++) frame.write(payload[i] ^ mask[i & 3]);
        byte[] bytes = frame.toByteArray();
        synchronized (wsWriteLock) {
            out.write(bytes);
            out.flush();
        }
    }

    private static int wsReadByte(InputStream in) throws Exception {
        int b = in.read();
        if (b < 0) throw new Exception("eof");
        return b;
    }

    private static void wsReadFully(InputStream in, byte[] buf, int off, int len) throws Exception {
        int n = 0;
        while (n < len) {
            int r = in.read(buf, off + n, len - n);
            if (r < 0) throw new Exception("eof");
            n += r;
        }
    }

    /** Frame reader: runs on its own thread for the life of one connection. */
    private void wsReadLoop(final int gen, BufferedInputStream in) {
        ByteArrayOutputStream frag = null;
        boolean fragIsText = false;
        try {
            for (;;) {
                int b0 = wsReadByte(in);
                int b1 = wsReadByte(in);
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = (wsReadByte(in) << 8) | wsReadByte(in);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | wsReadByte(in);
                }
                if (len < 0 || len > (1 << 20)) throw new Exception("oversized frame");
                byte[] mask = null;
                if (masked) { // servers don't mask, but parse it if one does
                    mask = new byte[4];
                    wsReadFully(in, mask, 0, 4);
                }
                byte[] payload = new byte[(int) len];
                wsReadFully(in, payload, 0, (int) len);
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

                if (op == 0x9) {          // ping -> pong with the same payload
                    try { wsWriteFrame(0xA, payload); } catch (Exception ignored) { }
                    continue;
                }
                if (op == 0xA) continue;  // pong: nothing to do
                if (op == 0x8) {          // close: answer once, then hang up
                    try { wsWriteFrame(0x8, new byte[0]); } catch (Exception ignored) { }
                    throw new Exception("server closed");
                }
                if (op == 0x1 || op == 0x2) {
                    frag = new ByteArrayOutputStream();
                    fragIsText = (op == 0x1);
                    frag.write(payload, 0, payload.length);
                } else if (op == 0x0) {
                    if (frag == null) continue; // stray continuation
                    frag.write(payload, 0, payload.length);
                } else {
                    continue; // unknown opcode: skip the frame
                }
                if (fin && frag != null) {
                    byte[] msg = frag.toByteArray();
                    boolean isText = fragIsText;
                    frag = null;
                    if (isText) wsOnMessage(new String(msg, "UTF-8"));
                }
            }
        } catch (Exception ignored) { }
        wsOnDown(gen);
    }

    /** Dispatch one decoded server message. Runs on the reader thread. */
    private void wsOnMessage(String text) {
        JSONObject o;
        try { o = new JSONObject(text); } catch (Exception e) { return; }
        String type = jStr(o, "type", "");
        if (type.equals("state")) {
            final JSONObject v = jObj(o, "view");
            if (v == null) return;
            // Exactly the poll path: same applyView, same guards.
            ui.post(new Runnable() {
                @Override public void run() {
                    if (destroyed || !inRoom) return;
                    netFailCount = 0;
                    applyView(v);
                }
            });
        } else if (type.equals("error")) {
            final String error = jStr(o, "error", "");
            if (error.length() == 0) return;
            // Same surface as an HTTP action error (sendAction's toast).
            ui.post(new Runnable() {
                @Override public void run() {
                    if (destroyed || !inRoom) return;
                    if (!error.equals("Chưa vào game.")) toast(error);
                }
            });
        } else if (type.equals("bye")) {
            // Fatal on the server side: drop to HTTP and don't spin —
            // reconnect attempts stay suppressed for 10s or until the
            // next user action (wsKick). Closing just the socket routes
            // the teardown through the normal wsOnDown path.
            wsSuppressUntil = System.currentTimeMillis() + 10000;
            closeQuietly(wsSocket);
        }
    }

    // ======================= view handling =======================

    private long estServerNow() { return System.currentTimeMillis() + serverOffset; }

    private void applyView(JSONObject v) {
        if (destroyed) return;
        // Cross-channel staleness guard: with WS pushing state AND the
        // HTTP safety-net poll answering in parallel, a slow poll response
        // can land after a fresher pushed view. serverNow orders them —
        // a view older than the last applied one is dropped whole (equal
        // timestamps still apply: re-applying is a no-op by signature,
        // and onConfigurationChanged re-applies the current view).
        long snIn = jLong(v, "serverNow", 0);
        if (snIn > 0 && lastViewServerNow > 0 && snIn < lastViewServerNow) return;
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
        if (sn > 0) {
            serverOffset = sn - System.currentTimeMillis();
            lastViewServerNow = sn;
        }
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
            } else if (gid.equals("cuop-sao") || gid.equals("giu-vuong-mien") || gid.equals("son-san")) {
                // Steady pad screen: banked/hold/cells and the crown banner
                // all refresh in place via updateDynamic — no rebuilds.
            } else if (gid.equals("cao-boi")) {
                sb.append(':').append(jStr(g, "mine", "waiting"));
            } else if (gid.equals("chung-mot-than")) {
                // Owned directions are dealt once per match; the pad screen
                // only depends on them (hearts/checks update in place).
                JSONArray md = jArr(g, "myDirs");
                if (md != null) {
                    for (int i = 0; i < md.length(); i++) sb.append(':').append(md.optString(i, ""));
                }
            } else if (gid.equals("day-hop") || gid.equals("cheo-thuyen")) {
                // Steady screens: deliveries / beat stats update in place
                // (cheo-thuyen also has its own 50ms beat ticker).
            } else if (gid.equals("xe-tank") || gid.equals("dat-bom") || gid.equals("nguoi-que")) {
                // Same deal as day-ra/ne-dan: rebuild only when my alive
                // status flips; lives refresh in place via tvLives
                // (nguoi-que damage % rides along via tvDmg).
                JSONObject blobs = jObj(g, "blobs");
                JSONObject my = blobs == null ? null : jObj(blobs, String.valueOf(sSeat));
                sb.append(':').append(my == null ? "?" : (jBool(my, "alive", true) ? "alive" : "ko"));
            } else if (gid.equals("thu-thanh")) {
                // Steady pad screen: crystal HP + kills refresh in place
                // via tvDefendLine; rebuild only if I somehow get K.O.'d.
                JSONObject blobs = jObj(g, "blobs");
                JSONObject my = blobs == null ? null : jObj(blobs, String.valueOf(sSeat));
                sb.append(':').append(my == null ? "?" : (jBool(my, "alive", true) ? "alive" : "ko"));
            } else if (gid.equals("ke-gia-mao")) {
                if (gp.equals("answer")) {
                    boolean locked = g.opt("mine") instanceof Number
                        || lockKey.equals("ke-gia-mao:a:" + round);
                    sb.append(locked ? ":locked" : ":open");
                } else if (gp.equals("discuss")) {
                    // Rebuild when the public chat grows (same pattern as
                    // chia-cuop); my half-typed message survives via the
                    // draft field, exactly like the negotiation chat.
                    JSONArray chat = jArr(g, "chatLog");
                    if (chat != null) {
                        for (int i = 0; i < chat.length(); i++) {
                            JSONObject cm = chat.optJSONObject(i);
                            if (cm != null) sb.append('|').append(jInt(cm, "seat", 0)).append(':').append(jStr(cm, "text", ""));
                        }
                    }
                } else if (gp.equals("vote")) {
                    boolean locked = g.opt("myVote") instanceof Number
                        || lockKey.equals("ke-gia-mao:v:" + round);
                    sb.append(locked ? ":locked" : ":open");
                }
            } else if (gid.equals("giai-ma-chung")) {
                // Progress is rendered as text (step checkmarks, whose
                // turn it is), so level/hearts/done/mySteps all belong
                // to the structure — same choice as the web phone.
                sb.append(':').append(jInt(g, "level", 0));
                sb.append(':').append(jInt(g, "hearts", 0));
                sb.append(':').append(jInt(g, "done", 0));
                JSONArray ms = jArr(g, "mySteps");
                if (ms != null) {
                    for (int i = 0; i < ms.length(); i++) {
                        JSONObject st = ms.optJSONObject(i);
                        if (st != null) sb.append('|').append(jInt(st, "pos", 0)).append('=').append(jInt(st, "sym", -1));
                    }
                }
            } else if (gid.equals("den-lua-nuoc")) {
                // Steady pad screen: my element is fixed for the whole
                // match; hearts/gems refresh in place via tvTempleLine.
            } else if (gid.equals("ma-soi")) {
                // Everything a phone may see is its own private kit plus
                // public facts; rebuild whenever any of it moves (the
                // discuss chat is serialized like ke-gia-mao's).
                sb.append(':').append(jStr(g, "myRole", ""));
                sb.append(':').append(jBool(g, "myAlive", true) ? "alive" : "dead");
                if (gp.equals("night")) {
                    sb.append(':').append(g.opt("myWolfVote"));
                    sb.append(':').append(jInt(g, "wolfVotesIn", 0));
                    sb.append(':').append(g.opt("myGuardPick"));
                    sb.append(':').append(g.opt("mySeerPick"));
                    JSONArray notes = jArr(g, "mySeerNotes");
                    if (notes != null) {
                        for (int i = 0; i < notes.length(); i++) {
                            JSONObject nt = notes.optJSONObject(i);
                            if (nt != null) sb.append('|').append(jInt(nt, "seat", 0)).append(jBool(nt, "wolf", false) ? "W" : "D");
                        }
                    }
                    sb.append(':').append(jBool(g, "wolvesIn", false) ? "win" : "wout");
                    sb.append(':').append(g.opt("myVictim"));
                    sb.append(':').append(jBool(g, "myWitchSave", false) ? "sv" : "nsv");
                    sb.append(':').append(g.opt("myWitchPoison"));
                    sb.append(':').append(jBool(g, "myWitchDone", false) ? "wd" : "nwd");
                    sb.append(':').append(jInt(g, "mySaveLeft", 0)).append('/').append(jInt(g, "myPoisonLeft", 0));
                } else if (gp.equals("dawn")) {
                    JSONArray dd = jArr(g, "dawnDeaths");
                    if (dd != null) {
                        for (int i = 0; i < dd.length(); i++) {
                            JSONObject d0 = dd.optJSONObject(i);
                            if (d0 != null) sb.append('|').append(jInt(d0, "seat", 0));
                        }
                    }
                } else if (gp.equals("discuss")) {
                    JSONArray chat = jArr(g, "chatLog");
                    if (chat != null) {
                        for (int i = 0; i < chat.length(); i++) {
                            JSONObject cm = chat.optJSONObject(i);
                            if (cm != null) sb.append('|').append(jInt(cm, "seat", 0)).append(':').append(jStr(cm, "text", ""));
                        }
                    }
                } else if (gp.equals("vote")) {
                    JSONObject voted = jObj(g, "voted");
                    JSONArray ps2 = jArr(v, "players");
                    if (voted != null && ps2 != null) {
                        for (int i = 0; i < ps2.length(); i++) {
                            JSONObject p2 = ps2.optJSONObject(i);
                            if (p2 != null) sb.append('|').append(voted.optBoolean(String.valueOf(jInt(p2, "seat", 0)), false) ? 1 : 0);
                        }
                    }
                } else if (gp.equals("execute") || gp.equals("wolfEnd")) {
                    JSONObject ex = jObj(g, "exec");
                    sb.append(':').append(ex == null ? "" : String.valueOf(ex.opt("seat")));
                    sb.append(':').append(jStr(g, "winner", ""));
                }
            } else if (gid.equals("ca-ngua")) {
                // Turn flow is discrete state: turn, dice, every horse,
                // the event log tail and my legal moves drive rebuilds.
                sb.append(':').append(g.opt("turn"));
                sb.append(':').append(g.opt("dice"));
                JSONObject hs = jObj(g, "horses");
                JSONArray ps3 = jArr(v, "players");
                if (hs != null && ps3 != null) {
                    for (int i = 0; i < ps3.length(); i++) {
                        JSONObject p3 = ps3.optJSONObject(i);
                        if (p3 == null) continue;
                        JSONArray hh = hs.optJSONArray(String.valueOf(jInt(p3, "seat", 0)));
                        if (hh != null) for (int k = 0; k < hh.length(); k++) sb.append(',').append(hh.optInt(k, 0));
                        sb.append(';');
                    }
                }
                JSONArray lg2 = jArr(g, "log");
                sb.append(':').append(lg2 == null ? 0 : lg2.length());
                if (lg2 != null && lg2.length() > 0) sb.append(':').append(lg2.optString(lg2.length() - 1, ""));
                JSONArray ml = jArr(g, "myLegal");
                sb.append(':').append(ml == null ? 0 : ml.length());
                sb.append(':').append(g.opt("winner"));
            } else if (gid.equals("da-bong")) {
                // Steady pad screen: the scoreboard refreshes in place
                // (tvScoreLine); only the goal banner needs a rebuild,
                // and the phase flip soccer<->goal already forces one.
                JSONObject gi = jObj(g, "goalInfo");
                if (gi != null) sb.append(':').append(jStr(gi, "team", "")).append(':').append(gi.opt("by"));
            } else if (gid.equals("ech-op")) {
                // Belly state drives the whole screen (pad vs in-belly
                // card vs carrying chip); hearts/flies/chamber refresh
                // in place via tvFrogLine.
                sb.append(':').append(jStr(g, "myState", "free"));
                sb.append(':').append(g.opt("myPassengerSeat"));
                sb.append(':').append(g.opt("myCarrierSeat"));
            } else if (gid.equals("can-goc")) {
                // Turn, wind and my cannon's survival drive rebuilds.
                // Angle/power must NOT be here: they refresh in place
                // (updateDynamic) so a held stepper is never rebuilt
                // out from under the thumb.
                sb.append(':').append(g.opt("turn"));
                sb.append(':').append(jInt(g, "wind", 0));
                JSONObject mc = jObj(g, "myCannon");
                sb.append(':').append(mc == null ? "?" : (jBool(mc, "alive", true) ? "alive" : "dead"));
                JSONObject bm = jObj(g, "boom");
                if (bm != null) sb.append(':').append(jLong(bm, "at", 0));
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
        if ((gid.equals("day-ra") || gid.equals("ne-dan") || gid.equals("xe-tank") || gid.equals("dat-bom")
                || gid.equals("nguoi-que")) && tvLives != null) {
            JSONObject blobs = jObj(g, "blobs");
            JSONObject my = blobs == null ? null : jObj(blobs, String.valueOf(sSeat));
            if (my != null) tvLives.setText(hearts(jInt(my, "lives", 0)));
        }
        if (gid.equals("nguoi-que") && tvDmg != null) {
            int dmg = myDmgOf(g);
            tvDmg.setText(dmg + "%");
            tvDmg.setTextColor(dmgColor(dmg));
        }
        if (gid.equals("den-lua-nuoc") && tvTempleLine != null) {
            tvTempleLine.setText(templeLineText(g));
        }
        if (gid.equals("thu-thanh") && tvDefendLine != null) {
            JSONObject crystal = jObj(g, "crystal");
            int hp = crystal == null ? 10 : jInt(crystal, "hp", 10);
            int max = crystal == null ? 10 : jInt(crystal, "max", 10);
            tvDefendLine.setText("💎 Pha lê: " + hp + "/" + max + " · Đã hạ: " + jInt(g, "kills", 0) + " slime");
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
        if ((gid.equals("cuop-sao") || gid.equals("giu-vuong-mien") || gid.equals("son-san"))
                && tvMineValue != null) {
            tvMineValue.setText(String.valueOf(jInt(g, "mine", 0)));
        }
        if (gid.equals("giu-vuong-mien") && tvCrownLine != null) {
            tvCrownLine.setText(crownBanner(v, g));
            tvCrownLine.setTextColor(jBool(g, "myCrown", false)
                ? Color.parseColor("#FFC93D") : C_TEXT);
        }
        if (gid.equals("day-hop") && tvMineValue != null) {
            tvMineValue.setText(String.valueOf(jInt(g, "deliveries", 0)));
        }
        if (gid.equals("chung-mot-than")) {
            if (tvMineValue != null) tvMineValue.setText(String.valueOf(jInt(g, "checks", 0)));
            if (tvHearts != null) tvHearts.setText(hearts(jInt(g, "hearts", 0)));
        }
        if (gid.equals("cheo-thuyen")) {
            if (tvRowHits != null) tvRowHits.setText(String.valueOf(jInt(g, "mine", 0)));
            if (tvRowSplash != null) tvRowSplash.setText(String.valueOf(jInt(g, "mySplash", 0)));
        }
        if (gid.equals("da-bong") && tvScoreLine != null) {
            tvScoreLine.setText(soccerScoreText(g));
        }
        if (gid.equals("ech-op") && tvFrogLine != null) {
            tvFrogLine.setText(frogLineText(g));
        }
        if (gid.equals("can-goc") && tvAimAngle != null && aimHoldCount == 0
                && jStr(g, "phase", "").equals("aim") && jBool(g, "myTurn", false)) {
            // Re-sync the local aim from the authoritative view (my own
            // setAim echoes land here) — never while a stepper is held,
            // or a mid-hold echo would fight the thumb by a step.
            JSONObject mc = jObj(g, "myCannon");
            if (mc != null) {
                aimAngle = (int) Math.round(jDouble(mc, "angle", aimAngle));
                aimPower = jInt(mc, "power", aimPower);
                int f = jInt(mc, "face", 0);
                if (f != 0) aimFace = f;
                refreshAimReadouts();
            }
        }
    }

    /** Crown banner text for Giữ Vương Miện, mirroring the web phone line. */
    private String crownBanner(JSONObject v, JSONObject g) {
        if (jBool(g, "myCrown", false)) return "👑 BẠN ĐANG GIỮ VƯƠNG MIỆN!";
        JSONObject crown = jObj(g, "crown");
        Object holder = crown == null ? null : crown.opt("holder");
        if (holder instanceof Number) {
            JSONObject hp = playerBySeat(v, ((Number) holder).intValue());
            if (hp != null) return "Vương miện: " + jStr(hp, "name", "") + " đang giữ";
        }
        return "Vương miện đang ở giữa sàn!";
    }

    private String hearts(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("❤");
        return sb.toString();
    }

    /** My Người Que damage %: per-blob value first, myDmg as fallback. */
    private int myDmgOf(JSONObject g) {
        JSONObject blobs = jObj(g, "blobs");
        JSONObject my = blobs == null ? null : jObj(blobs, String.valueOf(sSeat));
        return jInt(my, "dmg", jInt(g, "myDmg", 0));
    }

    /** Damage colour: the higher the %, the farther you fly when hit. */
    private int dmgColor(int dmg) {
        if (dmg >= 100) return C_RED;
        if (dmg >= 50) return Color.parseColor("#FFC93D");
        return C_TEXT;
    }

    /** Đền Lửa & Nước status line: gems per element + team hearts,
     *  mirroring the web phone's score chips. */
    private String templeLineText(JSONObject g) {
        JSONObject gc = jObj(g, "gemCount");
        int per = Math.max(1, jInt(g, "gemsTotal", 10) / 2);
        return "🔥 " + (gc == null ? 0 : jInt(gc, "lua", 0)) + "/" + per
            + " · 💧 " + (gc == null ? 0 : jInt(gc, "nuoc", 0)) + "/" + per
            + " · Tim đội: " + hearts(jInt(g, "hearts", 0));
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
            if ((gid.equals("day-ra") || gid.equals("ne-dan") || gid.equals("xe-tank") || gid.equals("dat-bom")
                    || gid.equals("nguoi-que"))
                    && (gp.equals("arena") || gp.equals("dodge") || gp.equals("tank") || gp.equals("bomber")
                    || gp.equals("fight"))) {
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

    // ======================= cheo-thuyen beat ticker =======================
    // Mirrors the web client's rowbtn flash: the CHÈO button burns gold
    // inside the +-260ms stroke window around each 1000ms beat, computed
    // client-side from server-synced time (serverOffset) so it stays
    // smooth between the 250ms polls.

    private void startRowLoop() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                tickRow();
                startRowLoop();
            }
        }, 50);
    }

    private void tickRow() {
        if (rowBtn == null || view == null) return;
        if (!rowBtn.isAttachedToWindow()) return;
        JSONObject g = jObj(view, "game");
        if (g == null || !jStr(g, "id", "").equals("cheo-thuyen")
            || !jStr(g, "phase", "").equals("row")) return;
        long nb = jLong(g, "nextBeatAt", 0);
        long rs = jLong(g, "rowStart", 0);
        if (nb > 0 && rs > 0) {
            long t = estServerNow();
            boolean inWin = (nb - t >= 0 && nb - t <= 260)
                || (t - (nb - 1000) <= 260 && nb - 1000 >= rs);
            rowBtn.setBackground(inWin ? rowBgGold : rowBgNorm);
        }
        double dist = jDouble(g, "distance", 0);
        int course = jInt(g, "course", 400);
        if (tvRowMeta != null) {
            tvRowMeta.setText("Nhịp " + jInt(g, "beat", 1) + "/" + jInt(g, "beats", 45)
                + " · Thuyền đã đi " + Math.round(dist) + "/" + course + " m");
        }
        if (rowTrack != null && rowFill != null) {
            int tw = rowTrack.getWidth();
            if (tw > 0 && course > 0) {
                double pct = dist / course;
                if (pct < 0) pct = 0;
                if (pct > 1) pct = 1;
                FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) rowFill.getLayoutParams();
                int w = (int) (tw * pct);
                if (fp.width != w) { fp.width = w; rowFill.setLayoutParams(fp); }
            }
        }
        if (tvRowHits != null) tvRowHits.setText(String.valueOf(jInt(g, "mine", 0)));
        if (tvRowSplash != null) tvRowSplash.setText(String.valueOf(jInt(g, "mySplash", 0)));
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

    private View buildRoom(JSONObject v) {
        if (jStr(v, "phase", "").equals("playing")) return buildPlayRoom(v);
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

    /**
     * In-game room: the APK is a pure controller, so while a game is
     * playing there is NO header/title block at all — the stage owns the
     * whole screen and every freed dp goes to the controls. The player
     * is never trapped: a compact "Thoát" chip floats in the top corner
     * (an overlay, never a layout row, so it costs the buttons nothing).
     * Gameplay status (lives / time / team line) lives inside the game
     * screens themselves, exactly as before.
     */
    private FrameLayout buildPlayRoom(JSONObject v) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(C_BG);
        frame.setPadding(dp(8), dp(8), dp(8), dp(8));
        frame.addView(contentFor(v), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button chip = new Button(this);
        chip.setText("Thoát");
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(C_TEXT);
        chip.setAllCaps(false);
        chip.setBackground(roundRect(Color.parseColor("#CC251D47"), C_MUTED, 12));
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View vw) { leaveRoom(null); }
        });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(28), Gravity.TOP | Gravity.END);
        cp.topMargin = dp(2);
        cp.rightMargin = dp(2);
        frame.addView(chip, cp);
        return frame;
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
                    || gid.equals("dua-bam") || gid.equals("nho-mau") || gid.equals("cao-boi"))) {
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

    /** Play-screen stage: tight padding (8 sides / 10 vertical) so the
     *  controls get the space the old 16dp panel frame used to eat. The
     *  pad geometry budgets exactly this (32dp horizontal, 36dp
     *  vertical including the play-room root padding). */
    private View stagePanel(View inner, int top, int bottom) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(stageBg(top, bottom));
        wrap.setPadding(dp(8), dp(10), dp(8), dp(10));
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

    /** Co-op team banner (lastGame.teamResult), mirroring the web results screen. */
    private View teamBanner(JSONObject lg) {
        JSONObject tr = lg == null ? null : jObj(lg, "teamResult");
        if (tr == null) return null;
        LinearLayout b = col();
        b.setBackground(roundRect(Color.parseColor("#3A2A05"), Color.parseColor("#FFC93D"), 16));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView l1 = centerText("🏆 Cả đội đạt hạng " + jStr(tr, "tierLabel", "")
            + " — mỗi người +" + fmt(jInt(tr, "pointsEach", 0)) + " điểm!", 18,
            Color.parseColor("#FFC93D"), true);
        b.addView(l1);
        TextView l2 = centerText("Thành tích đội: " + fmt(jInt(tr, "score", 0))
            + " " + jStr(tr, "unit", ""), 15, C_TEXT, false);
        b.addView(l2, matchWrap(2));
        return b;
    }

    private View resultsView(JSONObject v) {
        JSONObject lg = jObj(v, "lastGame");
        JSONObject pts = lg == null ? null : jObj(lg, "points");
        LinearLayout c = col();
        c.addView(centerText("Kết quả game", 22, C_TEXT, true));
        if (lg != null) c.addView(centerText(jStr(lg, "name", ""), 17, accent(), true), matchWrap(2));
        View banner = teamBanner(lg);
        if (banner != null) c.addView(banner, matchWrap(10));
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
        if (jStr(v, "phase", "").equals("podium")) {
            // Co-op: the podium is a TEAM result (everyone banked the same
            // points) — show the team banner like the results screen does.
            View banner = teamBanner(jObj(v, "lastGame"));
            if (banner != null) c.addView(banner, matchWrap(8));
        }
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
        if (gid.equals("cuop-sao")) return new int[] { 0xFF2E2005, 0xFF140E02 };
        if (gid.equals("giu-vuong-mien")) return new int[] { 0xFF2A1052, 0xFF0E0618 };
        if (gid.equals("son-san")) return new int[] { 0xFF04303A, 0xFF02141A };
        if (gid.equals("cao-boi")) return new int[] { 0xFF3A1C05, 0xFF180B02 };
        if (gid.equals("chung-mot-than")) return new int[] { 0xFF2A1458, 0xFF0C0518 };
        if (gid.equals("day-hop")) return new int[] { 0xFF331D04, 0xFF120A01 };
        if (gid.equals("cheo-thuyen")) return new int[] { 0xFF083A56, 0xFF02101A };
        if (gid.equals("xe-tank")) return new int[] { 0xFF0B2018, 0xFF060F0B };
        if (gid.equals("dat-bom")) return new int[] { 0xFF241006, 0xFF120702 };
        if (gid.equals("ke-gia-mao")) return new int[] { 0xFF1E0A38, 0xFF0C0516 };
        if (gid.equals("thu-thanh")) return new int[] { 0xFF06222E, 0xFF02101A };
        if (gid.equals("giai-ma-chung")) return new int[] { 0xFF191238, 0xFF08061A };
        if (gid.equals("nguoi-que")) return new int[] { 0xFF2A0A16, 0xFF0D0309 };
        if (gid.equals("den-lua-nuoc")) return new int[] { 0xFF241305, 0xFF0A0501 };
        if (gid.equals("ma-soi")) return new int[] { 0xFF2A1060, 0xFF0D0618 };
        if (gid.equals("ca-ngua")) return new int[] { 0xFF4A2405, 0xFF1A0D02 };
        if (gid.equals("da-bong")) return new int[] { 0xFF0B3D1E, 0xFF04150A };
        if (gid.equals("ech-op")) return new int[] { 0xFF2E1C07, 0xFF0D0701 };
        if (gid.equals("can-goc")) return new int[] { 0xFF0E2233, 0xFF050C14 };
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
        TextView t = centerText("", 16, C_HINT, true);
        t.setTag(tag);
        tvTimer = t;
        return t;
    }

    private View gameScreen(JSONObject v, JSONObject g) {
        // Any rebuild detaches the cheo-thuyen ticker's views; clear the
        // refs so the ticker idles until rowBoat() installs fresh ones.
        rowBtn = null; rowTrack = null; rowFill = null;
        tvRowMeta = null; tvRowHits = null; tvRowSplash = null;
        tvScoreLine = null; tvFrogLine = null;
        tvAimAngle = null; tvAimPower = null; tvAimFace = null;
        String gid = jStr(g, "id", "");
        // Leaving the cannon screen kills any held-stepper repeat loop.
        if (!gid.equals("can-goc")) aimGen++;
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
            return stagePanel(c, sc[0], sc[1]);
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
        else if (gid.equals("cuop-sao") || gid.equals("giu-vuong-mien") || gid.equals("son-san")) inner = versusPad(v, g);
        else if (gid.equals("cao-boi")) inner = caoBoi(v, g);
        else if (gid.equals("chung-mot-than")) inner = sharedBody(v, g);
        else if (gid.equals("day-hop")) inner = boxPush(v, g);
        else if (gid.equals("cheo-thuyen")) inner = rowBoat(v, g);
        else if (gid.equals("xe-tank") || gid.equals("dat-bom") || gid.equals("thu-thanh")) inner = gunPad(v, g);
        else if (gid.equals("ke-gia-mao")) inner = keGiaMao(v, g);
        else if (gid.equals("giai-ma-chung")) inner = giaiMaChung(v, g);
        else if (gid.equals("nguoi-que")) inner = fightPad(v, g);
        else if (gid.equals("den-lua-nuoc")) inner = templePad(v, g);
        else if (gid.equals("ma-soi")) inner = maSoi(v, g);
        else if (gid.equals("ca-ngua")) inner = caNgua(v, g);
        else if (gid.equals("da-bong")) inner = soccerPad(v, g);
        else if (gid.equals("ech-op")) inner = frogPad(v, g);
        else if (gid.equals("can-goc")) inner = cannonAim(v, g);
        else {
            inner = col();
            inner.addView(centerText("Đang chơi: " + jStr(g, "name", ""), 18, C_TEXT, true));
        }
        return stagePanel(inner, sc[0], sc[1]);
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
                LinearLayout.LayoutParams p = lp(dp(fitSquareDp(270)), dp(fitSquareDp(270)), 26);
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
                LinearLayout.LayoutParams p = lp(dp(fitSquareDp(270)), dp(fitSquareDp(270)), 26);
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
            this(bit, label, base, glow, radiusDp, 17);
        }

        PadButton(int bit, String label, int base, int glow, float radiusDp, float textSp) {
            super(MainActivity.this);
            this.bit = bit;
            setText(label);
            setTextColor(Color.WHITE);
            setTypeface(Typeface.DEFAULT_BOLD);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
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

    // ---------- circular direction disc (replaces the 3x3 D-pad grid) ----
    /**
     * A round direction disc: the thumb presses once and SLIDES across
     * four segments instead of hunting four separate square buttons.
     * Direction bits are computed from the thumb's offset from the disc
     * centre: dead zone inside 0.18R, axis thresholds at +/-0.25R, and
     * both axes may engage at once, so a diagonal slide gives a natural
     * 8-way input. Bits are written to padState through the exact same
     * pathway as PadButton (padState + onPadChanged), so snapshots,
     * latest-wins coalescing, WS frames and clearPad behave identically.
     * The lit segments are drawn FROM padState — the single source of
     * truth — so a clearPad() issued anywhere (screen rebuild, onPause)
     * also unlights the disc on its next draw.
     */
    private class DiscPad extends View {
        private final HashSet<String> dim;
        private int activePointer = -1;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF segOval = new RectF();

        DiscPad(HashSet<String> dimDirs) {
            super(MainActivity.this);
            this.dim = dimDirs;
            setFocusable(false);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setColor(Color.parseColor("#6DB6F2"));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setColor(Color.parseColor("#17122B"));
            arrowPaint.setTypeface(Typeface.DEFAULT_BOLD);
            arrowPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f;
            float rimW = Math.max(2f, dp(3));
            // Base disc.
            fillPaint.setColor(C_PANEL2);
            canvas.drawCircle(cx, cy, r - rimW / 2f, fillPaint);
            // Lit segments: pie slices under the hub (drawn after, it
            // covers their centres). Angles: 0 deg = 3 o'clock, positive
            // clockwise (y grows downward).
            float sr = r - rimW;
            segOval.set(cx - sr, cy - sr, cx + sr, cy + sr);
            segPaint.setColor(Color.parseColor("#6DB6F2"));
            if (padState[B_R]) canvas.drawArc(segOval, -45, 90, true, segPaint);
            if (padState[B_D]) canvas.drawArc(segOval, 45, 90, true, segPaint);
            if (padState[B_L]) canvas.drawArc(segOval, 135, 90, true, segPaint);
            if (padState[B_U]) canvas.drawArc(segOval, 225, 90, true, segPaint);
            // Separators along the diagonals, hub edge to rim.
            float hubR = r * 0.30f;
            linePaint.setStrokeWidth(Math.max(1.5f, dp(2)));
            for (int i = 0; i < 4; i++) {
                double ang = Math.PI / 4 + i * Math.PI / 2;
                canvas.drawLine(
                    cx + (float) Math.cos(ang) * hubR, cy + (float) Math.sin(ang) * hubR,
                    cx + (float) Math.cos(ang) * sr, cy + (float) Math.sin(ang) * sr, linePaint);
            }
            // Centre hub + its ring.
            hubPaint.setColor(C_PANEL);
            canvas.drawCircle(cx, cy, hubR, hubPaint);
            ringPaint.setStrokeWidth(Math.max(1.5f, dp(1.5f)));
            canvas.drawCircle(cx, cy, hubR, ringPaint);
            // Coloured outer rim.
            ringPaint.setStrokeWidth(rimW);
            canvas.drawCircle(cx, cy, r - rimW / 2f, ringPaint);
            // Pole arrows.
            float aDist = r * 0.68f;
            arrowPaint.setTextSize(Math.min(w, h) * 0.15f);
            Paint.FontMetrics fm = arrowPaint.getFontMetrics();
            float baseOff = -(fm.ascent + fm.descent) / 2f;
            drawArrow(canvas, "▲", cx, cy - aDist + baseOff, B_U, "u");
            drawArrow(canvas, "▼", cx, cy + aDist + baseOff, B_D, "d");
            drawArrow(canvas, "◀", cx - aDist, cy + baseOff, B_L, "l");
            drawArrow(canvas, "▶", cx + aDist, cy + baseOff, B_R, "r");
        }

        private void drawArrow(Canvas canvas, String glyph, float x, float y, int bit, String key) {
            int alpha = 235;
            if (dim != null && dim.contains(key)) alpha = 90; // not mine: dim like the old buttons
            arrowPaint.setColor(padState[bit] ? Color.WHITE : Color.parseColor("#D9D4F5"));
            arrowPaint.setAlpha(alpha);
            canvas.drawText(glyph, x, y, arrowPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                if (activePointer < 0) {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    activePointer = ev.getPointerId(ev.getActionIndex());
                    applyTouch(ev, ev.getActionIndex());
                }
                return true;
            }
            if (a == MotionEvent.ACTION_MOVE) {
                if (activePointer >= 0) {
                    int idx = ev.findPointerIndex(activePointer);
                    if (idx >= 0) applyTouch(ev, idx);
                }
                return true;
            }
            if (a == MotionEvent.ACTION_POINTER_UP) {
                if (ev.getPointerId(ev.getActionIndex()) == activePointer) {
                    activePointer = -1;
                    setDirs(false, false, false, false);
                }
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                activePointer = -1;
                setDirs(false, false, false, false);
                return true;
            }
            return true;
        }

        private void applyTouch(MotionEvent ev, int idx) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = Math.min(w, h) / 2f;
            float dx = ev.getX(idx) - w / 2f;
            float dy = ev.getY(idx) - h / 2f;
            double dist = Math.hypot(dx, dy);
            boolean u = false, d = false, l = false, rr = false;
            if (dist >= 0.18 * r && dist <= 1.3 * r) {
                float th = 0.25f * r;
                rr = dx > th;
                l = dx < -th;
                d = dy > th;
                u = dy < -th;
            }
            setDirs(u, d, l, rr);
        }

        /** Same write path as PadButton.syncBit: padState, then onPadChanged. */
        private void setDirs(boolean u, boolean d, boolean l, boolean r) {
            if (padState[B_U] == u && padState[B_D] == d
                && padState[B_L] == l && padState[B_R] == r) return;
            padState[B_U] = u;
            padState[B_D] = d;
            padState[B_L] = l;
            padState[B_R] = r;
            onPadChanged();
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            activePointer = -1;
            setDirs(false, false, false, false);
            super.onDetachedFromWindow();
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

        // Same adaptive cluster as every other gamepad screen,
        // bottom-anchored under the thumbs.
        addFlex(c);
        LinearLayout.LayoutParams pr = matchWrap(4);
        pr.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(padCluster("A\nĐẨY", "B\nLướt", 17, null), pr);

        addHint(c, gid.equals("ne-dan")
            ? "A: ĐẨY · B: LƯỚT · Né hết đạn bay tới"
            : "A: ĐẨY · B: LƯỚT · Đẩy đối thủ rơi sàn");
        return c;
    }

    // ---- adaptive gamepad geometry -------------------------------------
    // v1.3 scaled a fixed 342dp design down until it fit, which on real
    // phones shrank the D-pad cells to ~47-54dp (and ~37dp in landscape,
    // where ~244dp of header/hint chrome ate the height). v1.4 inverts
    // it: play screens have NO header block and tight 8/10 stage
    // padding, and the geometry is solved from the actual budgets so
    // the buttons are as large as the screen allows:
    //   horizontal: root 8x2 + stage panel 8x2 = 32dp of padding.
    //   vertical:   root 8x2 + stage panel 10x2 = 36dp, plus at most
    //               64dp of in-screen lines (status + margins + timer;
    //               the one-line hint exists only in portrait and is
    //               budgeted there separately).
    // Portrait width is the binding constraint: the built cluster is
    // 3 cells + 2 gaps (4) + middle gap (8) + the A/B column at 1.25x
    // cell (no stagger spacer in portrait) = 4.25*cell + 16dp, so
    // cell = (wDp - 32 - 16) / 4.25. Landscape is height-bound:
    // cell = min(width fit, (height left after lines - 40 chrome) / 3)
    // with the middle gap flexing and a 0.3*ab stagger, D-pad left /
    // A/B right like holding a real gamepad.
    // Reference points (dp): 360x640 portrait -> cell 73, A/B 91;
    // 320x480 portrait -> cell 64, A/B 80; 640x360 landscape ->
    // cell 73, A/B 91, whole stack 327 <= 360 with no scrolling.
    // The built width never exceeds the stage: nothing can overflow
    // the screen edge in either orientation (the v1.3 guarantee).
    // v1.7: the 3x3 button grid became a circular DiscPad whose
    // diameter is exactly 3 cells (220dp at the 360dp reference) —
    // inside the same envelope (the grid's 2x4dp of gaps is now spare
    // slack). Games with 3-4 action buttons use the tighter diamond
    // geometry of computeDiaGeom() instead (disc ~188dp, buttons
    // 66dp at the 360dp reference).
    private static class PadGeom {
        int cell;       // D-pad button size (px)
        int disc;       // direction disc diameter (px) = 3 cells, no gaps
        int cellGap;    // gap between D-pad cells (px)
        int midGap;     // fixed D-pad <-> A/B gap (px; unused when flexGap)
        boolean flexGap;
        int spacer;     // A-row indent (px)
        int ab;         // A/B button size (px)
        int abGap;      // vertical gap between the A and B rows (px)
        float dirSp;    // D-pad arrow text size (sp)
        float abSp;     // A/B label text size (sp)
    }

    private PadGeom computePadGeom() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float wDp = dm.widthPixels / dm.density;
        float hDp = dm.heightPixels / dm.density;
        boolean land = wDp > hDp;
        float availW = wDp - 32f;
        PadGeom g = new PadGeom();
        g.cellGap = dp(4);
        g.abGap = dp(6);
        float cellF;
        if (!land) {
            cellF = (availW - 16f) / 4.25f;
            // Height can only bind on unusually short portrait windows:
            // lines budget 94 = status 32 + cluster margin 10 + the
            // single hint line 30 + timer 22.
            float maxByH = (hDp - 36f - 94f) / 3f;
            if (cellF > maxByH) cellF = maxByH;
            g.midGap = dp(8);
            g.flexGap = false;
            g.spacer = 0;
        } else {
            // Height: after root+panel (36) and the in-screen lines
            // (64), reserve 40dp of chrome inside the pad area.
            float byH = (hDp - 36f - 64f - 40f) / 3f;
            // Width with flexed middle gap (>= 24) and stagger 0.3*ab:
            // 3c + 2*4 + 24 + 0.375c + 1.25c = 4.625c + 32.
            float byW = (availW - 32f) / 4.625f;
            cellF = Math.min(byH, byW);
            g.flexGap = true;
            g.midGap = 0;
            g.spacer = -1; // derived from ab below
        }
        if (cellF > 150f) cellF = 150f;
        if (cellF < 40f) cellF = 40f;
        float abF = cellF * 1.25f;
        g.cell = dp(cellF);
        g.disc = dp(cellF * 3f);
        g.ab = dp(abF);
        if (g.spacer < 0) g.spacer = dp(abF * 0.3f);
        g.dirSp = Math.max(20f, Math.min(34f, cellF * 0.45f));
        g.abSp = Math.max(14f, Math.min(20f, abF * 0.2f));
        return g;
    }

    // ---- diamond geometry (games with 3-4 action buttons) ----------
    // The ABXY diamond is taller and narrower than the roomy A/B
    // column: Y over the middle, X and A flanking, B under the middle,
    // so its footprint is (2*btn + 2) wide and (3*btn + 8) tall.
    // Solved from the same stage budget (availW = wDp - 32): buttons
    // are sized first (60-68dp), the disc takes what remains.
    // Reference points (dp): 360x640 portrait -> btn 66, diamond
    // 134 wide x 206 tall, disc 188, cluster 188+6+134 = 328 = stage;
    // 320x480 portrait -> btn 60, diamond 122x188, disc 160, cluster
    // 288 = stage; 640x360 landscape -> btn 68, disc capped by the
    // height budget at 240, middle gap flexes, stack <= 340 <= 360.
    private static class DiaGeom {
        int disc;       // disc diameter (px)
        int btn;        // diamond button size (px)
        int w;          // diamond footprint width (px)
        int h;          // diamond footprint height (px)
        int midGap;     // fixed disc <-> diamond gap (px; unused when flexGap)
        boolean flexGap;
        float btnSp;    // diamond button label size (sp)
    }

    private DiaGeom computeDiaGeom() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float wDp = dm.widthPixels / dm.density;
        float hDp = dm.heightPixels / dm.density;
        boolean land = wDp > hDp;
        float availW = wDp - 32f;
        // Vertical room for the cluster: same line budgets as
        // computePadGeom (36dp root+panel; 94dp of lines in portrait,
        // 64dp in landscape, +20dp safety there).
        float hBudget = land ? (hDp - 36f - 64f - 20f) : (hDp - 36f - 94f);
        float dbF = Math.min(68f, Math.max(60f, availW * 0.20f));
        if (3f * dbF + 8f > hBudget) { // unusually short screen: shrink buttons first
            dbF = Math.max(44f, (hBudget - 8f) / 3f);
        }
        float diaW = 2f * dbF + 2f;
        float diaH = 3f * dbF + 8f;
        float discF = availW - 6f - diaW;
        if (discF > hBudget) discF = hBudget; // landscape: height binds, gap flexes
        if (discF < 100f) { // very narrow screen: trade button size for disc size
            float cut = 100f - discF;
            dbF = Math.max(44f, dbF - cut / 2f);
            diaW = 2f * dbF + 2f;
            diaH = 3f * dbF + 8f;
            discF = availW - 6f - diaW;
        }
        DiaGeom g = new DiaGeom();
        g.btn = dp(dbF);
        g.w = dp(diaW);
        g.h = dp(diaH);
        g.disc = dp(discF);
        g.midGap = dp(6);
        g.flexGap = land;
        g.btnSp = Math.max(11f, Math.min(14f, dbF * 0.19f));
        return g;
    }

    /** Side length (dp) for the full-screen single tap buttons (Nhanh
     *  Tay, Cao Bồi): the design size, capped so the button always fits
     *  the stage — by width first, and in landscape by the height left
     *  after the play-screen chrome (~88dp now that play screens have
     *  no header block: root 16 + stage panel 20 + the button's own
     *  top margin 26 + safety). At 640x360 the full 270dp design size
     *  fits again. */
    private int fitSquareDp(float wantDp) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float wDp = dm.widthPixels / dm.density;
        float hDp = dm.heightPixels / dm.density;
        float max = wDp - 64f;
        if (wDp > hDp) max = Math.min(max, hDp - 88f);
        if (max < 110f) max = 110f;
        return (int) Math.min(wantDp, max);
    }

    private boolean isLandscape() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels > dm.heightPixels;
    }

    /** Flexible empty space that pushes the pad cluster to the bottom
     *  of the play screen, where the thumbs actually rest. */
    private void addFlex(LinearLayout c) {
        View f = new View(this);
        c.addView(f, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** The single explanatory line allowed on a pad screen: short,
     *  single-line, bright (never small grey text). Dropped entirely in
     *  landscape, where vertical space belongs to the buttons. */
    private void addHint(LinearLayout c, String s) {
        if (isLandscape()) return;
        TextView t = centerText(s, 14, C_HINT, false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(t, matchWrap(10));
    }

    /**
     * Shared controller cluster: the circular direction disc on the
     * left, the game's action buttons on the right. Which action
     * buttons exist is per game — a null label HIDES that button.
     * Games with 1-2 action buttons get the roomy staggered column
     * (computePadGeom, unchanged since v1.4); games with 3-4 get the
     * classic gamepad diamond — Y top, X left, A right, B bottom —
     * on the tighter computeDiaGeom budget. dimDirs holds direction
     * keys ("u","d","l","r") to draw dimmed on the disc — used by
     * Chung Một Thân, where only the directions a player owns are
     * live (the server filters the rest anyway).
     */
    private LinearLayout padCluster(String aLabel, String bLabel, float abSp, HashSet<String> dimDirs) {
        return padCluster(aLabel, bLabel, null, null, abSp, dimDirs);
    }

    private LinearLayout padCluster(String aLabel, String bLabel, String xLabel, String yLabel,
                                    float abSp, HashSet<String> dimDirs) {
        // The per-game action-button spec, in slot order A, B, X, Y.
        int[] specBit = { B_A, B_B, B_X, B_Y };
        String[] specLabel = { aLabel, bLabel, xLabel, yLabel };
        int[] specBase = { Color.parseColor("#B03030"), Color.parseColor("#2F7FBF"),
            Color.parseColor("#1E7A46"), Color.parseColor("#9A6B0F") };
        int[] specGlow = { Color.parseColor("#FF6B6B"), Color.parseColor("#6DB6F2"),
            Color.parseColor("#58D68D"), Color.parseColor("#FFC93D") };
        int n = 0;
        for (int i = 0; i < 4; i++) if (specLabel[i] != null) n++;
        int[] vBit = new int[n];
        String[] vLabel = new String[n];
        int[] vBase = new int[n];
        int[] vGlow = new int[n];
        int k = 0;
        for (int i = 0; i < 4; i++) {
            if (specLabel[i] == null) continue;
            vBit[k] = specBit[i];
            vLabel[k] = specLabel[i];
            vBase[k] = specBase[i];
            vGlow[k] = specGlow[i];
            k++;
        }

        LinearLayout padRow = new LinearLayout(this);
        padRow.setOrientation(LinearLayout.HORIZONTAL);
        padRow.setGravity(Gravity.CENTER);
        DiscPad disc = new DiscPad(dimDirs);
        if (n >= 3) {
            DiaGeom dg = computeDiaGeom();
            padRow.addView(disc, new LinearLayout.LayoutParams(dg.disc, dg.disc));
            View gap = new View(this);
            if (dg.flexGap) {
                // Landscape grip: disc left, diamond right, space flexes.
                padRow.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
            } else {
                padRow.addView(gap, new LinearLayout.LayoutParams(dg.midGap, 1));
            }
            padRow.addView(buildDiamond(dg, vBit, vLabel, vBase, vGlow),
                new LinearLayout.LayoutParams(dg.w, dg.h));
            return padRow;
        }
        PadGeom pg = computePadGeom();
        padRow.addView(disc, new LinearLayout.LayoutParams(pg.disc, pg.disc));

        View gap = new View(this);
        if (pg.flexGap) {
            // Landscape grip: disc left, buttons right, space flexes between.
            padRow.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        } else {
            padRow.addView(gap, new LinearLayout.LayoutParams(pg.midGap, 1));
        }
        if (n > 0) padRow.addView(buildAbColumn(pg, vBit, vLabel, vBase, vGlow, abSp));
        return padRow;
    }

    /** The roomy 1-2 button column: first button top (staggered in
     *  landscape), second button bottom — the v1.4 arrangement. */
    private LinearLayout buildAbColumn(PadGeom pg, int[] bits, String[] labels,
                                       int[] bases, int[] glows, float abSp) {
        LinearLayout ab = col();
        LinearLayout rowA = new LinearLayout(this);
        rowA.setOrientation(LinearLayout.HORIZONTAL);
        View spA = new View(this);
        rowA.addView(spA, new LinearLayout.LayoutParams(pg.spacer, 1));
        PadButton top = new PadButton(bits[0], labels[0], bases[0], glows[0], 999,
            Math.max(abSp, pg.abSp));
        rowA.addView(top, new LinearLayout.LayoutParams(pg.ab, pg.ab));
        ab.addView(rowA);
        if (bits.length > 1) {
            LinearLayout rowB = new LinearLayout(this);
            rowB.setOrientation(LinearLayout.HORIZONTAL);
            PadButton bot = new PadButton(bits[1], labels[1], bases[1], glows[1], 999,
                Math.max(abSp, pg.abSp));
            rowB.addView(bot, new LinearLayout.LayoutParams(pg.ab, pg.ab));
            LinearLayout.LayoutParams rb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rb.topMargin = pg.abGap;
            ab.addView(rowB, rb);
        }
        return ab;
    }

    /** The classic gamepad diamond inside a (2*btn+2) x (3*btn+8)
     *  footprint: Y top-centre, X middle-left, A middle-right, B
     *  bottom-centre. Buttons a game doesn't use are simply absent. */
    private FrameLayout buildDiamond(DiaGeom dg, int[] bits, String[] labels,
                                     int[] bases, int[] glows) {
        FrameLayout dia = new FrameLayout(this);
        for (int i = 0; i < bits.length; i++) {
            int cxPx, cyPx;
            if (bits[i] == B_Y) { cxPx = dg.w / 2; cyPx = dg.btn / 2; }
            else if (bits[i] == B_X) { cxPx = dg.btn / 2; cyPx = dg.h / 2; }
            else if (bits[i] == B_A) { cxPx = dg.w - dg.btn / 2; cyPx = dg.h / 2; }
            else { cxPx = dg.w / 2; cyPx = dg.h - dg.btn / 2; } // B_B
            PadButton b = new PadButton(bits[i], labels[i], bases[i], glows[i], 999, dg.btnSp);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dg.btn, dg.btn);
            lp.gravity = Gravity.LEFT | Gravity.TOP;
            lp.leftMargin = cxPx - dg.btn / 2;
            lp.topMargin = cyPx - dg.btn / 2;
            dia.addView(b, lp);
        }
        return dia;
    }

    // ---------- Cướp Sao / Giữ Vương Miện / Sơn Sàn (versus arena pads) ----------
    private LinearLayout versusPad(JSONObject v, JSONObject g) {
        clearPad(true); // a rebuilt screen must never inherit a stuck "held" bit
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gid = jStr(g, "id", "");
        String gp = jStr(g, "phase", "");
        boolean live = (gid.equals("cuop-sao") && gp.equals("steal"))
            || (gid.equals("giu-vuong-mien") && gp.equals("crown"))
            || (gid.equals("son-san") && gp.equals("splat"));
        if (!live) {
            c.addView(centerText("Đợi vào sân…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        String aLabel = "A\nXÔ";
        float abSp = 17;
        String hint;
        if (gid.equals("cuop-sao")) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.addView(text("Sao của bạn: ★ ", 20, C_TEXT, true));
            TextView mv = text(String.valueOf(jInt(g, "mine", 0)), 22, Color.parseColor("#FFC93D"), true);
            tvMineValue = mv;
            row.addView(mv);
            c.addView(row, matchWrap(6));
            hint = "Bay qua sao là nhặt · A: XÔ";
        } else if (gid.equals("giu-vuong-mien")) {
            TextView banner = centerText(crownBanner(v, g), 21,
                jBool(g, "myCrown", false) ? Color.parseColor("#FFC93D") : C_TEXT, true);
            tvCrownLine = banner;
            c.addView(banner, matchWrap(6));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.addView(text("Điểm giữ của bạn: ", 17, C_TEXT, true));
            TextView mv = text(String.valueOf(jInt(g, "mine", 0)), 19, accent(), true);
            tvMineValue = mv;
            row.addView(mv);
            c.addView(row, matchWrap(2));
            hint = "Giữ vương miện càng lâu càng nhiều điểm";
        } else { // son-san
            JSONArray grid = jArr(g, "grid");
            int total = grid != null ? grid.length() : 144;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.addView(text("Ô của bạn: ", 20, C_TEXT, true));
            TextView mv = text(String.valueOf(jInt(g, "mine", 0)), 22, accent(), true);
            tvMineValue = mv;
            row.addView(mv);
            row.addView(text("/" + total, 20, C_TEXT, true));
            c.addView(row, matchWrap(6));
            aLabel = "A\nSƠN BÙNG";
            abSp = 13;
            hint = "Chạy qua ô là sơn · A: SƠN BÙNG 3×3";
        }
        addFlex(c);
        LinearLayout.LayoutParams pr = matchWrap(4);
        pr.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(padCluster(aLabel, "B\nLướt", abSp, null), pr);
        addHint(c, hint);
        return c;
    }

    // ---------- Xe Tăng / Đặt Bom / Thủ Thành (gamepad + guns) ----------
    // All three ride the same held-state pad channel: A is a rising-edge
    // "burst" on the server (fires a shell / plants a bomb), B dashes.
    private LinearLayout gunPad(JSONObject v, JSONObject g) {
        clearPad(true); // a rebuilt screen must never inherit a stuck "held" bit
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gid = jStr(g, "id", "");
        String gp = jStr(g, "phase", "");
        boolean live = (gid.equals("xe-tank") && gp.equals("tank"))
            || (gid.equals("dat-bom") && gp.equals("bomber"))
            || (gid.equals("thu-thanh") && gp.equals("defend"));
        if (!live) {
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
        String aLabel;
        float abSp = 17;
        String hint;
        if (gid.equals("thu-thanh")) {
            // Co-op defence: no personal lives — the header tracks the
            // crystal and the team's kills, refreshed in place.
            JSONObject crystal = jObj(g, "crystal");
            int hp = crystal == null ? 10 : jInt(crystal, "hp", 10);
            int max = crystal == null ? 10 : jInt(crystal, "max", 10);
            TextView line = centerText("💎 Pha lê: " + hp + "/" + max + " · Đã hạ: " + jInt(g, "kills", 0) + " slime",
                19, C_TEXT, true);
            tvDefendLine = line;
            c.addView(line, matchWrap(6));
            aLabel = "A\nBẮN";
            hint = "Bắn slime trước khi chúng chạm pha lê";
        } else {
            LinearLayout livesRow = new LinearLayout(this);
            livesRow.setOrientation(LinearLayout.HORIZONTAL);
            livesRow.setGravity(Gravity.CENTER);
            livesRow.addView(text("Mạng của bạn: ", 17, C_TEXT, true));
            TextView lv = text(hearts(jInt(my, "lives", 0)), 17, Color.parseColor("#FF8FA3"), true);
            tvLives = lv;
            livesRow.addView(lv);
            c.addView(livesRow, matchWrap(6));
            if (gid.equals("dat-bom")) {
                aLabel = "A\nĐẶT BOM";
                abSp = 13;
                hint = "Đặt bom xong chạy ngay — nổ chữ thập!";
            } else {
                aLabel = "A\nBẮN";
                hint = "A: BẮN · Núp sau thùng chắn";
            }
        }
        addFlex(c);
        LinearLayout.LayoutParams pr = matchWrap(4);
        pr.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(padCluster(aLabel, "B\nLướt", abSp, null), pr);
        addHint(c, hint);
        c.addView(timerLine("sec"), matchWrap(2));
        return c;
    }

    // ---------- Người Que Đại Chiến (versus: platform fighter) ----------
    // Smash rules on the standard pad: ←/→ run, ↑ double-jumps, A attacks,
    // B dashes. Damage % is the knockback meter — the higher it climbs,
    // the farther a hit sends you. Lives + % refresh in place (tvLives /
    // tvDmg); the signature only flips alive/ko, like xe-tank.
    private LinearLayout fightPad(JSONObject v, JSONObject g) {
        clearPad(true); // a rebuilt screen must never inherit a stuck "held" bit
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        if (!jStr(g, "phase", "").equals("fight")) {
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
            c.addView(centerText("Người que của bạn đã văng khỏi sàn!", 22, C_TEXT, true), matchWrap(4));
            c.addView(centerText("Xem TV đợi kết quả nhé…", 15, C_MUTED, false), matchWrap(4));
            return c;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(text("Mạng: ", 17, C_TEXT, true));
        TextView lv = text(hearts(jInt(my, "lives", 0)), 17, Color.parseColor("#FF8FA3"), true);
        tvLives = lv;
        row.addView(lv);
        row.addView(text("  ·  Sát thương: ", 17, C_TEXT, true));
        int dmg = myDmgOf(g);
        TextView dv = text(dmg + "%", 20, dmgColor(dmg), true);
        tvDmg = dv;
        row.addView(dv);
        c.addView(row, matchWrap(6));
        addFlex(c);
        LinearLayout.LayoutParams pr = matchWrap(4);
        pr.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(padCluster("A\nĐÁNH", "B\nLƯỚT", "X\nĐÁNH MẠNH", "Y\nNHẢY", 17, null), pr);
        addHint(c, "A ĐÁNH · X ĐÁNH MẠNH · B LƯỚT · Y NHẢY · % càng cao càng dễ bị đánh văng");
        c.addView(timerLine("sec"), matchWrap(2));
        return c;
    }

    // ---------- Đền Lửa & Nước (co-op: element platformer) ----------
    // ←/→ run, ↑, A or Y jumps; B and X don't exist in this game, so
    // they are hidden rather than shown dead. Your element is dealt
    // once for the whole match, so it sits in a coloured chip inside the
    // screen (play screens stay header-less); team hearts + gem counts
    // refresh in place via tvTempleLine.
    private LinearLayout templePad(JSONObject v, JSONObject g) {
        clearPad(true); // a rebuilt screen must never inherit a stuck "held" bit
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        if (!jStr(g, "phase", "").equals("temple")) {
            c.addView(centerText("Đợi vào đền…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        boolean fire = jStr(g, "myEl", "").equals("lua");
        TextView chip = centerText(fire ? "🔥 BẠN LÀ LỬA" : "💧 BẠN LÀ NƯỚC", 17, Color.WHITE, true);
        chip.setBackground(roundRect(fire ? Color.parseColor("#B03030") : Color.parseColor("#2F7FBF"),
            Color.WHITE, 16));
        chip.setPadding(dp(16), dp(5), dp(16), dp(5));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        cp.topMargin = dp(4);
        c.addView(chip, cp);
        TextView line = centerText(templeLineText(g), 17, C_TEXT, true);
        tvTempleLine = line;
        c.addView(line, matchWrap(4));
        addFlex(c);
        LinearLayout.LayoutParams pr = matchWrap(4);
        pr.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(padCluster("A\nNHẢY", null, null, "Y\nNHẢY", 17, null), pr);
        addHint(c, fire
            ? "Nhặt ngọc lửa 🔥 · Tránh xa hồ nước!"
            : "Nhặt ngọc nước 💧 · Tránh xa hồ lửa!");
        c.addView(timerLine("sec"), matchWrap(2));
        return c;
    }

    // ---------- Cao Bồi (duel: one huge button) ----------
    private LinearLayout caoBoi(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gp = jStr(g, "phase", "");
        String mine = jStr(g, "mine", "waiting");
        final String lock = "cao-boi:" + jInt(g, "round", 0);
        if (gp.equals("wait")) {
            if (mine.equals("foul")) {
                c.addView(centerText("KẸT SÚNG!", 32, C_RED, true), matchWrap(30));
                c.addView(centerText("Bạn bắn trước hiệu lệnh — bị khoá và −100 điểm vòng này. Đợi vòng sau nhé.", 16, C_TEXT, false), matchWrap(6));
            } else {
                Button b = bigButton("ĐỢI…\nNghe hiệu lệnh BẮN!", C_RED, 0xFFFF8A8A, 28);
                LinearLayout.LayoutParams p = lp(dp(fitSquareDp(270)), dp(fitSquareDp(270)), 26);
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
            if (mine.equals("fired")) {
                c.addView(centerText("ĐÃ BẮN! Đợi kết quả…", 28, C_TEXT, true), matchWrap(40));
            } else {
                Button b = bigButton("BẮN!", Color.parseColor("#1FA855"), C_GREEN, 36);
                LinearLayout.LayoutParams p = lp(dp(fitSquareDp(270)), dp(fitSquareDp(270)), 26);
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
                if (st.equals("fired")) {
                    msg = "Bạn bắn trong " + jInt(e, "reactionMs", 0) + " ms — nhanh thứ " + jInt(e, "order", 0) + "!";
                } else if (st.equals("foul")) msg = "Phạm quy! Bắn quá sớm";
                else msg = "Bạn chưa bắn";
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

    // ---------- Chung Một Thân (co-op: split steering of one shared ball) ----------
    private LinearLayout sharedBody(JSONObject v, JSONObject g) {
        clearPad(true);
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        if (!jStr(g, "phase", "").equals("shared")) {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(text("⭐ Đội: ", 19, C_TEXT, true));
        TextView chk = text(String.valueOf(jInt(g, "checks", 0)), 20, accent(), true);
        tvMineValue = chk;
        row.addView(chk);
        row.addView(text(" điểm · Tim: ", 19, C_TEXT, true));
        TextView hr = text(hearts(jInt(g, "hearts", 0)), 19, Color.parseColor("#FF8FA3"), true);
        tvHearts = hr;
        row.addView(hr);
        c.addView(row, matchWrap(6));

        // Which directions are MINE (dealt by the server in game.myDirs)
        JSONArray md = jArr(g, "myDirs");
        HashSet<String> mine = new HashSet<String>();
        if (md != null) for (int i = 0; i < md.length(); i++) mine.add(md.optString(i, ""));
        LinearLayout dirs = new LinearLayout(this);
        dirs.setOrientation(LinearLayout.HORIZONTAL);
        dirs.setGravity(Gravity.CENTER);
        dirs.addView(text("Bạn giữ: ", 16, C_MUTED, true));
        if (mine.isEmpty()) {
            dirs.addView(text("—", 16, C_TEXT, true));
        } else {
            String[][] arrows = { { "u", "▲" }, { "r", "▶" }, { "d", "▼" }, { "l", "◀" } };
            for (int i = 0; i < arrows.length; i++) {
                if (!mine.contains(arrows[i][0])) continue;
                TextView chip = centerText(arrows[i][1], 20, Color.WHITE, true);
                chip.setBackground(roundRect(accent(), Color.WHITE, 10));
                chip.setPadding(dp(10), dp(2), dp(10), dp(2));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.leftMargin = dp(6);
                dirs.addView(chip, cp);
            }
        }
        c.addView(dirs, matchWrap(6));

        HashSet<String> dim = new HashSet<String>();
        String[] allDirs = { "u", "d", "l", "r" };
        for (int i = 0; i < allDirs.length; i++) if (!mine.contains(allDirs[i])) dim.add(allDirs[i]);
        addFlex(c);
        LinearLayout.LayoutParams pr = matchWrap(4);
        pr.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(padCluster("A\nĐẨY", "B\nLướt", 17, dim), pr);
        addHint(c, "Lái bóng chạm sao ⭐ · Né đạn!");
        c.addView(timerLine("sec"), matchWrap(2));
        return c;
    }

    // ---------- Đẩy Hộp Về Đích (co-op: push the crate together) ----------
    private LinearLayout boxPush(JSONObject v, JSONObject g) {
        clearPad(true);
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        if (!jStr(g, "phase", "").equals("box")) {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(text("📦 Đội đã giao: ", 20, C_TEXT, true));
        TextView dv = text(String.valueOf(jInt(g, "deliveries", 0)), 22, accent(), true);
        tvMineValue = dv;
        row.addView(dv);
        row.addView(text(" hộp", 20, C_TEXT, true));
        c.addView(row, matchWrap(6));
        addFlex(c);
        LinearLayout.LayoutParams pr = matchWrap(4);
        pr.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(padCluster("A\nĐẨY", "B\nLướt", 17, null), pr);
        addHint(c, "Hai người cùng đẩy một hướng mới đi!");
        c.addView(timerLine("sec"), matchWrap(2));
        return c;
    }

    // ---------- Chèo Thuyền (co-op: row on the beat) ----------
    private LinearLayout rowBoat(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        if (!jStr(g, "phase", "").equals("row")) {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        double dist = jDouble(g, "distance", 0);
        int course = jInt(g, "course", 400);
        TextView meta = centerText("Nhịp " + jInt(g, "beat", 1) + "/" + jInt(g, "beats", 45)
            + " · Thuyền đã đi " + Math.round(dist) + "/" + course + " m", 19, C_TEXT, true);
        tvRowMeta = meta;
        c.addView(meta, matchWrap(6));

        FrameLayout track = new FrameLayout(this);
        GradientDrawable trk = new GradientDrawable();
        trk.setColor(C_PANEL2);
        trk.setCornerRadius(dp(7));
        track.setBackground(trk);
        View fill = new View(this);
        GradientDrawable fl = new GradientDrawable();
        fl.setColor(Color.parseColor("#38BDF8"));
        fl.setCornerRadius(dp(7));
        fill.setBackground(fl);
        track.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        rowTrack = track;
        rowFill = fill;
        c.addView(track, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(14), 8));

        rowBgNorm = roundRect(Color.parseColor("#0369A1"), Color.parseColor("#38BDF8"), 22);
        rowBgGold = roundRect(Color.parseColor("#FFC93D"), Color.WHITE, 22);
        Button b = new Button(this);
        b.setText("CHÈO!");
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(rowBgNorm);
        rowBtn = b;
        LinearLayout.LayoutParams bp = lp(dp(250), dp(190), 14);
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(b, bp);
        fireOnDown(b, new Runnable() {
            @Override public void run() {
                // No client lock: strokes are repeatable by design — the
                // server dedupes to one stroke per player per beat and
                // counts off-beat presses as splashes.
                sendAction(actionBody("tap"), false);
            }
        });
        c.addView(centerText("Bấm khi nút cháy VÀNG = đúng nhịp", 15, C_MUTED, false), matchWrap(8));

        LinearLayout counts = new LinearLayout(this);
        counts.setOrientation(LinearLayout.HORIZONTAL);
        counts.setGravity(Gravity.CENTER);
        counts.addView(text("Bạn trúng nhịp: ", 17, C_TEXT, true));
        TextView hits = text(String.valueOf(jInt(g, "mine", 0)), 18, accent(), true);
        tvRowHits = hits;
        counts.addView(hits);
        counts.addView(text(" · Trượt (tạt nước): ", 17, C_TEXT, true));
        TextView spl = text(String.valueOf(jInt(g, "mySplash", 0)), 18, C_TEXT, true);
        tvRowSplash = spl;
        counts.addView(spl);
        c.addView(counts, matchWrap(10));
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

    // ---------- Kẻ Giả Mạo ----------
    private String impChatDraft = "";

    private LinearLayout keGiaMao(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        String gp = jStr(g, "phase", "");
        final int round = jInt(g, "round", 0);
        if (gp.equals("answer")) {
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView chip = centerText("Chủ đề: " + jStr(g, "category", ""), 15, C_MUTED, true);
            chip.setBackground(roundRect(C_PANEL2, accent(), 999));
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout.LayoutParams chp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4);
            chp.gravity = Gravity.CENTER_HORIZONTAL;
            c.addView(chip, chp);
            // PRIVACY: myQuestion is the only question this seat ever
            // receives (real or variant) — render exactly that, nothing else.
            c.addView(text(jStr(g, "myQuestion", ""), 21, C_TEXT, true), matchWrap(12));
            c.addView(timerLine("sec"), matchWrap(2));
            Object mine = g.opt("mine");
            boolean locked = mine instanceof Number || lockKey.equals("ke-gia-mao:a:" + round);
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
                                if (lockKey.equals("ke-gia-mao:a:" + round)) return;
                                lockKey = "ke-gia-mao:a:" + round;
                                JSONObject bdy = actionBody("answer");
                                try { bdy.put("choice", idx); } catch (Exception ignored) { }
                                vibrate(25);
                                sendAction(bdy, true);
                            }
                        });
                    }
                    grid.addView(rowL, matchWrap(r == 0 ? 12 : 10));
                }
                c.addView(grid);
                c.addView(centerText("Trả lời bí mật — đừng đọc to câu hỏi hay đáp án của bạn!", 14, C_MUTED, false), matchWrap(10));
            } else {
                c.addView(centerText("Đã trả lời! Đợi mọi người…", 22, C_TEXT, true), matchWrap(24));
            }
        } else if (gp.equals("discuss")) {
            c.addView(centerText("Bàn luận: ai đang giả vờ?", 21, C_TEXT, true), matchWrap(2));
            // Public discussion — EVERY player may chat here (unlike Chia
            // Hay Cướp's pair-only negotiation).
            LinearLayout msgs = col();
            JSONArray chat = jArr(g, "chatLog");
            if (chat != null) {
                for (int i = 0; i < chat.length(); i++) {
                    JSONObject cm = chat.optJSONObject(i);
                    if (cm == null) continue;
                    JSONObject who = playerBySeat(v, jInt(cm, "seat", -1));
                    TextView line = text((who == null ? "" : jStr(who, "name", "")) + ": " + jStr(cm, "text", ""),
                        16, C_TEXT, jInt(cm, "seat", -1) == sSeat);
                    line.setBackground(roundRect(C_PANEL2, C_PANEL2, 12));
                    line.setPadding(dp(12), dp(8), dp(12), dp(8));
                    msgs.addView(line, matchWrap(6));
                }
            }
            final ScrollView sv = new ScrollView(this);
            sv.addView(msgs);
            LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            svp.topMargin = dp(8);
            c.addView(sv, svp);
            sv.post(new Runnable() {
                @Override public void run() { sv.fullScroll(View.FOCUS_DOWN); }
            });
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            final EditText chatIn = new EditText(this);
            chatIn.setInputType(InputType.TYPE_CLASS_TEXT);
            chatIn.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(60) });
            chatIn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            chatIn.setTextColor(C_TEXT);
            chatIn.setHintTextColor(C_MUTED);
            chatIn.setHint("Nói gì đó…");
            chatIn.setBackground(roundRect(C_PANEL2, accent(), 14));
            chatIn.setPadding(dp(12), dp(8), dp(12), dp(8));
            chatIn.setText(impChatDraft);
            chatIn.setSelection(chatIn.getText().length());
            chatIn.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int n) { impChatDraft = s.toString(); }
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
                    impChatDraft = "";
                    chatIn.setText("");
                    JSONObject bdy = actionBody("chat");
                    try { bdy.put("text", txt); } catch (Exception ignored) { }
                    sendAction(bdy, true);
                }
            });
            c.addView(rowL, matchWrap(10));
            c.addView(centerText("Đừng lộ đáp án của bạn — kẻ giả mạo đang nghe đấy!", 14, C_MUTED, false), matchWrap(6));
            c.addView(timerLine("sec"), matchWrap(2));
        } else if (gp.equals("vote")) {
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            Object myVote = g.opt("myVote");
            boolean locked = myVote instanceof Number || lockKey.equals("ke-gia-mao:v:" + round);
            if (!locked) {
                c.addView(centerText("Bạn tố cáo ai là kẻ giả mạo?", 23, C_TEXT, true), matchWrap(14));
                JSONArray ps = jArr(v, "players");
                if (ps != null) {
                    for (int i = 0; i < ps.length(); i++) {
                        JSONObject p = ps.optJSONObject(i);
                        if (p == null) continue;
                        final int targetSeat = jInt(p, "seat", -1);
                        if (targetSeat == sSeat || targetSeat < 0) continue;
                        Integer pc = SEAT_HEX.get(jStr(p, "color", "blue"));
                        Button b = bigButton(jStr(p, "name", ""), pc != null ? pc : C_PANEL2, Color.WHITE, 20);
                        c.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(60), 10));
                        fireOnDown(b, new Runnable() {
                            @Override public void run() {
                                if (lockKey.equals("ke-gia-mao:v:" + round)) return;
                                lockKey = "ke-gia-mao:v:" + round;
                                JSONObject bdy = actionBody("vote");
                                try { bdy.put("seat", targetSeat); } catch (Exception ignored) { }
                                vibrate(25);
                                sendAction(bdy, true);
                            }
                        });
                    }
                }
                c.addView(timerLine("sec"), matchWrap(8));
            } else {
                c.addView(centerText("Đã tố cáo! Đợi công bố…", 24, C_TEXT, true), matchWrap(30));
                if (myVote instanceof Number) {
                    JSONObject who = playerBySeat(v, ((Number) myVote).intValue());
                    if (who != null) c.addView(centerText("Bạn đã tố cáo: " + jStr(who, "name", ""), 17, C_MUTED, false), matchWrap(6));
                }
            }
        } else if (gp.equals("reveal")) {
            JSONObject rev = jObj(g, "reveal");
            if (rev == null) {
                c.addView(centerText("Đang công bố…", 20, C_MUTED, false), matchWrap(30));
            } else {
                c.setGravity(Gravity.CENTER_HORIZONTAL);
                boolean caught = jBool(rev, "caught", false);
                c.addView(centerText(caught ? "🎉 Bắt được kẻ giả mạo!" : "😈 Kẻ giả mạo đã thoát!",
                    27, caught ? C_GREEN : C_RED, true), matchWrap(16));
                int impSeat = jInt(rev, "impostor", -1);
                JSONObject imp = playerBySeat(v, impSeat);
                c.addView(centerText("Kẻ giả mạo: " + (imp == null ? "" : jStr(imp, "name", ""))
                    + (impSeat == sSeat ? " — chính là BẠN!" : ""), 20, C_TEXT, true), matchWrap(8));
                c.addView(text("Câu hỏi thật: " + jStr(rev, "realQ", ""), 16, C_TEXT, false), matchWrap(10));
                c.addView(text("Câu hỏi giả: " + jStr(rev, "fakeQ", ""), 16, C_MUTED, false), matchWrap(4));
                JSONObject pts = jObj(rev, "points");
                int my = pts == null ? 0 : pts.optInt(String.valueOf(sSeat), 0);
                c.addView(centerText("+" + fmt(my) + " điểm vòng này", 24, C_GREEN, true), matchWrap(12));
            }
        } else {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
        }
        return c;
    }

    // ---------- Giải Mã Chung (co-op: each player holds part of the code) ----------
    private LinearLayout giaiMaChung(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        final String gp = jStr(g, "phase", "");
        if (!gp.equals("decode") && !gp.equals("levelup")) {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        int done = jInt(g, "done", 0);
        int total = jInt(g, "total", 0);
        c.addView(centerText("Màn " + jInt(g, "level", 1) + " · Tim đội: "
            + hearts(Math.max(0, jInt(g, "hearts", 0))) + " · Đã qua " + jInt(g, "levelsDone", 0) + " màn",
            18, C_TEXT, true), matchWrap(4));
        c.addView(centerText("Tiến độ màn này: " + done + "/" + total + " bước", 17, C_MUTED, true), matchWrap(2));
        if (gp.equals("levelup")) {
            c.addView(centerText("QUA MÀN! 🎉", 30, C_GREEN, true), matchWrap(8));
        }
        c.addView(text("Phần mật mã của bạn:", 16, C_MUTED, true), matchWrap(10));
        // SECRECY: mySteps lists ONLY this seat's steps (pos + symbol) —
        // the current-step highlight is derived from the public progress
        // counter plus my own list, never from anyone else's steps.
        JSONArray ms = jArr(g, "mySteps");
        boolean myTurn = false;
        if (ms == null || ms.length() == 0) {
            c.addView(centerText("Màn này bạn không giữ bước nào — cổ vũ đồng đội nhé!", 15, C_TEXT, false), matchWrap(4));
        } else {
            for (int i = 0; i < ms.length(); i++) {
                JSONObject st = ms.optJSONObject(i);
                if (st == null) continue;
                int pos = jInt(st, "pos", 0);
                int sym = jInt(st, "sym", -1);
                String nm = jStr(st, "name", (sym >= 0 && sym < SYM_NAME.length) ? SYM_NAME[sym] : "");
                int symColor = (sym >= 0 && sym < SYM_HEX.length) ? SYM_HEX[sym] : C_MUTED;
                boolean isDone = pos <= done;
                boolean isCurrent = gp.equals("decode") && pos == done + 1;
                if (isCurrent) myTurn = true;
                String label = (isDone ? "✓ " : isCurrent ? "👉 " : "")
                    + "Bước " + pos + ": " + nm + (isCurrent ? " — TỚI LƯỢT BẠN!" : "");
                TextView stepChip = centerText(label, 18, isCurrent ? C_TEXT : symColor, true);
                stepChip.setBackground(roundRect(isCurrent ? C_PANEL : C_PANEL2, symColor, 12));
                stepChip.setPadding(dp(14), dp(8), dp(14), dp(8));
                if (isDone) stepChip.setAlpha(0.55f);
                LinearLayout.LayoutParams scp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 6);
                scp.gravity = Gravity.CENTER_HORIZONTAL;
                c.addView(stepChip, scp);
            }
        }
        if (gp.equals("decode") && total > 0) {
            c.addView(centerText(myTurn
                    ? "Tới lượt bạn — bấm đúng ký hiệu của bước " + (done + 1) + "!"
                    : "Đợi đồng đội bấm bước " + (done + 1) + "…",
                15, myTurn ? Color.parseColor("#FFC93D") : C_MUTED, true), matchWrap(8));
        }
        LinearLayout grid = col();
        for (int r = 0; r < 2; r++) {
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 2; k++) {
                final int idx = r * 2 + k;
                Button b = bigButton(SYM_NAME[idx], SYM_HEX[idx], Color.WHITE, 20);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(84), 1f);
                if (k == 1) bp.leftMargin = dp(10);
                rowL.addView(b, bp);
                fireOnDown(b, new Runnable() {
                    @Override public void run() {
                        // No client lock: the server adjudicates every
                        // press (owner + symbol); a wrong press costs the
                        // team a heart by design.
                        if (!gp.equals("decode")) return;
                        JSONObject bdy = actionBody("symbol");
                        try { bdy.put("sym", idx); } catch (Exception ignored) { }
                        sendAction(bdy, false);
                    }
                });
            }
            grid.addView(rowL, matchWrap(r == 0 ? 12 : 10));
        }
        c.addView(grid);
        c.addView(centerText("Chỉ bấm khi tới lượt bước của bạn và đúng ký hiệu của bước đó — bấm sai cả đội mất 1 tim!", 14, C_MUTED, false), matchWrap(10));
        c.addView(timerLine("sec"), matchWrap(2));
        return c;
    }

    // ======================= shared helpers for the v1.8 games =======================

    private String playerName(JSONObject v, int seat) {
        JSONObject p = playerBySeat(v, seat);
        return p != null ? jStr(p, "name", "") : ("P" + seat);
    }

    private static boolean containsSeat(JSONArray arr, int seat) {
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) if (arr.optInt(i, -1) == seat) return true;
        return false;
    }

    /** One full-width seat-picker button in the target's seat colour. */
    private Button pickButton(JSONObject v, int seat, String suffix, Runnable action) {
        JSONObject p = playerBySeat(v, seat);
        Integer pc = p == null ? null : SEAT_HEX.get(jStr(p, "color", "blue"));
        Button b = bigButton(playerName(v, seat) + (suffix == null ? "" : suffix),
            pc != null ? pc : C_PANEL2, Color.WHITE, 19);
        fireOnDown(b, action);
        return b;
    }

    // ---------- Ma Sói (versus: hidden roles, night/day cycle) ----------
    // PRIVACY mirrors the server: the view hands this phone ONLY its own
    // role kit (myRole + the role-specific pick fields); there is nothing
    // else secret in the payload to render. Night picks are one-shot on
    // the server; the screens key off the confirmed fields (myWolfVote,
    // myGuardPick, mySeerPick, myWitch*) and lockKey only bridges the
    // round-trip so a double-tap can't fire twice.
    private String wolfChatDraft = "";

    private static String wolfRoleName(String role) {
        if (role.equals("soi")) return "Sói 🐺";
        if (role.equals("tien-tri")) return "Tiên Tri 🔮";
        if (role.equals("bao-ve")) return "Bảo Vệ 🛡️";
        if (role.equals("phu-thuy")) return "Phù Thủy 🧙";
        return "Dân Làng 🧑‍🌾";
    }

    private int wolfRoleColor(String role) {
        if (role.equals("soi")) return Color.parseColor("#B03030");
        if (role.equals("tien-tri")) return Color.parseColor("#7C3AED");
        if (role.equals("bao-ve")) return Color.parseColor("#2F7FBF");
        if (role.equals("phu-thuy")) return Color.parseColor("#B83A8C");
        return Color.parseColor("#1E7A46");
    }

    private boolean wolfAlive(JSONObject g, int seat) {
        JSONObject am = jObj(g, "aliveMap");
        return am == null || am.optBoolean(String.valueOf(seat), true);
    }

    private void addDeadList(LinearLayout body, JSONObject v, JSONObject g) {
        JSONArray dl = jArr(g, "deadList");
        if (dl == null || dl.length() == 0) return;
        body.addView(text("Đã mất:", 15, C_MUTED, true), matchWrap(10));
        for (int i = 0; i < dl.length(); i++) {
            JSONObject d = dl.optJSONObject(i);
            if (d == null) continue;
            body.addView(text("💀 " + playerName(v, jInt(d, "seat", -1)) + " — "
                + wolfRoleName(jStr(d, "role", "")), 16, C_TEXT, false), matchWrap(3));
        }
    }

    private LinearLayout maSoi(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        final String gp = jStr(g, "phase", "");
        final int night = jInt(g, "night", jInt(g, "round", 1));
        final String myRole = jStr(g, "myRole", "");
        final boolean myAlive = jBool(g, "myAlive", true);
        final JSONArray ps = jArr(v, "players");

        // Role card: the one secret this phone holds, shown all game.
        if (myRole.length() > 0) {
            TextView chip = centerText("Vai của bạn: " + wolfRoleName(myRole)
                + (myAlive ? "" : " · 💀 ĐÃ CHẾT"), 17, Color.WHITE, true);
            chip.setBackground(roundRect(wolfRoleColor(myRole), Color.WHITE, 16));
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout.LayoutParams chp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2);
            chp.gravity = Gravity.CENTER_HORIZONTAL;
            c.addView(chip, chp);
        }
        String banner = gp.equals("night") ? "🌙 ĐÊM " + night + " — LÀNG ĐANG NGỦ…"
            : gp.equals("dawn") ? "☀️ BÌNH MINH ĐÊM " + night
            : gp.equals("discuss") ? "🗣️ BÀN LUẬN — AI LÀ SÓI?"
            : gp.equals("vote") ? "🗳️ BỎ PHIẾU TREO CỔ"
            : gp.equals("execute") ? "⚖️ KẾT QUẢ BỎ PHIẾU"
            : gp.equals("wolfEnd") ? "🏁 KẾT THÚC!" : "";
        if (banner.length() > 0) c.addView(centerText(banner, 19, C_TEXT, true), matchWrap(4));

        LinearLayout body = col();
        if (gp.equals("night")) {
            if (!myAlive) {
                body.addView(centerText("💀 Bạn đã chết — theo dõi diễn biến trên TV nhé.", 18, C_TEXT, true), matchWrap(16));
                addDeadList(body, v, g);
            } else if (myRole.equals("soi")) {
                final JSONArray team = jArr(g, "wolfTeam");
                StringBuilder names = new StringBuilder();
                if (team != null) {
                    for (int i = 0; i < team.length(); i++) {
                        if (i > 0) names.append(", ");
                        names.append(playerName(v, team.optInt(i, -1)));
                    }
                }
                body.addView(centerText("🐺 Bầy sói: " + names, 17, C_TEXT, true), matchWrap(6));
                Object myVote = g.opt("myWolfVote");
                if (myVote instanceof Number) {
                    body.addView(centerText("Đã chọn con mồi: " + playerName(v, ((Number) myVote).intValue()), 19, C_TEXT, true), matchWrap(8));
                    body.addView(centerText("Phiếu sói đã vào: " + jInt(g, "wolfVotesIn", 0)
                        + "/" + (team == null ? 0 : team.length()), 15, C_MUTED, false), matchWrap(2));
                } else {
                    body.addView(centerText("Chọn con mồi đêm nay:", 17, C_MUTED, true), matchWrap(6));
                    final String lock = "ma-soi:wolfVote:" + night;
                    if (ps != null) {
                        for (int i = 0; i < ps.length(); i++) {
                            JSONObject p = ps.optJSONObject(i);
                            if (p == null) continue;
                            final int ts = jInt(p, "seat", -1);
                            if (ts < 0 || ts == sSeat || !wolfAlive(g, ts)) continue;
                            Button b = pickButton(v, ts, containsSeat(team, ts) ? " 🐺 đồng đội" : "", new Runnable() {
                                @Override public void run() {
                                    if (lockKey.equals(lock)) return;
                                    lockKey = lock;
                                    JSONObject bdy = actionBody("wolfVote");
                                    try { bdy.put("seat", ts); } catch (Exception ignored) { }
                                    vibrate(25);
                                    sendAction(bdy, true);
                                }
                            });
                            body.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 8));
                        }
                    }
                }
            } else if (myRole.equals("bao-ve")) {
                Object pick = g.opt("myGuardPick");
                if (pick instanceof Number) {
                    body.addView(centerText("🛡️ Đêm nay bạn bảo vệ: " + playerName(v, ((Number) pick).intValue()), 19, C_TEXT, true), matchWrap(10));
                } else {
                    body.addView(centerText("Chọn người để bảo vệ đêm nay:", 17, C_MUTED, true), matchWrap(6));
                    final Object last = g.opt("myGuardLast");
                    final String lock = "ma-soi:wolfGuard:" + night;
                    if (ps != null) {
                        for (int i = 0; i < ps.length(); i++) {
                            JSONObject p = ps.optJSONObject(i);
                            if (p == null) continue;
                            final int ts = jInt(p, "seat", -1);
                            if (ts < 0 || !wolfAlive(g, ts)) continue;
                            // The server refuses last night's target again.
                            if (last instanceof Number && ((Number) last).intValue() == ts) continue;
                            Button b = pickButton(v, ts, ts == sSeat ? " (chính bạn)" : "", new Runnable() {
                                @Override public void run() {
                                    if (lockKey.equals(lock)) return;
                                    lockKey = lock;
                                    JSONObject bdy = actionBody("wolfGuard");
                                    try { bdy.put("seat", ts); } catch (Exception ignored) { }
                                    vibrate(25);
                                    sendAction(bdy, true);
                                }
                            });
                            body.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 8));
                        }
                    }
                    if (last instanceof Number) {
                        body.addView(centerText("Đêm qua bạn đã bảo vệ " + playerName(v, ((Number) last).intValue())
                            + " — luật không cho bảo vệ lại đêm nay.", 14, C_MUTED, false), matchWrap(8));
                    }
                }
            } else if (myRole.equals("tien-tri")) {
                Object pick = g.opt("mySeerPick");
                JSONArray notes = jArr(g, "mySeerNotes");
                if (pick instanceof Number) {
                    int ts = ((Number) pick).intValue();
                    Boolean isWolf = null;
                    if (notes != null) {
                        for (int i = 0; i < notes.length(); i++) {
                            JSONObject nt = notes.optJSONObject(i);
                            if (nt != null && jInt(nt, "seat", -1) == ts) isWolf = jBool(nt, "wolf", false);
                        }
                    }
                    boolean wolf = isWolf != null && isWolf;
                    body.addView(centerText("🔮 " + playerName(v, ts) + (wolf ? " LÀ SÓI! 🐺" : " là dân lành ✅"),
                        21, wolf ? C_RED : C_GREEN, true), matchWrap(10));
                } else {
                    body.addView(centerText("Soi một người đêm nay:", 17, C_MUTED, true), matchWrap(6));
                    final String lock = "ma-soi:wolfSeer:" + night;
                    if (ps != null) {
                        for (int i = 0; i < ps.length(); i++) {
                            JSONObject p = ps.optJSONObject(i);
                            if (p == null) continue;
                            final int ts = jInt(p, "seat", -1);
                            if (ts < 0 || ts == sSeat || !wolfAlive(g, ts)) continue;
                            Button b = pickButton(v, ts, "", new Runnable() {
                                @Override public void run() {
                                    if (lockKey.equals(lock)) return;
                                    lockKey = lock;
                                    JSONObject bdy = actionBody("wolfSeer");
                                    try { bdy.put("seat", ts); } catch (Exception ignored) { }
                                    vibrate(25);
                                    sendAction(bdy, true);
                                }
                            });
                            body.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 8));
                        }
                    }
                }
                if (notes != null && notes.length() > 0) {
                    body.addView(text("Sổ tay tiên tri:", 15, C_MUTED, true), matchWrap(10));
                    for (int i = 0; i < notes.length(); i++) {
                        JSONObject nt = notes.optJSONObject(i);
                        if (nt == null) continue;
                        body.addView(text("🔮 " + playerName(v, jInt(nt, "seat", -1)) + ": "
                            + (jBool(nt, "wolf", false) ? "🐺 SÓI" : "✅ dân lành"), 16, C_TEXT, false), matchWrap(3));
                    }
                }
            } else if (myRole.equals("phu-thuy")) {
                body.addView(centerText("Thuốc cứu còn: " + jInt(g, "mySaveLeft", 0)
                    + " · Thuốc độc còn: " + jInt(g, "myPoisonLeft", 0), 16, C_TEXT, true), matchWrap(4));
                if (jBool(g, "myWitchDone", false)) {
                    body.addView(centerText("✔ Đã chốt lựa chọn cho đêm nay.", 19, C_TEXT, true), matchWrap(10));
                } else if (!jBool(g, "wolvesIn", false)) {
                    body.addView(centerText("Đợi bầy sói chọn xong con mồi đã… 🐺", 17, C_MUTED, false), matchWrap(10));
                } else {
                    Object victim = g.opt("myVictim");
                    final boolean saveOn = jBool(g, "myWitchSave", false);
                    if (victim instanceof Number) {
                        final String vName = playerName(v, ((Number) victim).intValue());
                        body.addView(centerText("Con mồi của sói đêm nay: " + vName, 19, C_RED, true), matchWrap(6));
                        if (jInt(g, "mySaveLeft", 0) > 0 || saveOn) {
                            Button sv = bigButton(saveOn ? "💊 ĐÃ DẶN CỨU " + vName + " (bấm để huỷ cứu)"
                                    : "💊 Cứu " + vName,
                                saveOn ? Color.parseColor("#1FA855") : C_PANEL2, C_GREEN, 18);
                            body.addView(sv, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 8));
                            fireOnDown(sv, new Runnable() {
                                @Override public void run() {
                                    JSONObject bdy = actionBody("wolfWitch");
                                    try { bdy.put("save", !saveOn); } catch (Exception ignored) { }
                                    vibrate(25);
                                    sendAction(bdy, true);
                                }
                            });
                        }
                    } else {
                        body.addView(centerText("Đêm nay sói chưa chọn được ai.", 16, C_MUTED, false), matchWrap(6));
                    }
                    Object poison = g.opt("myWitchPoison");
                    if (jInt(g, "myPoisonLeft", 0) > 0) {
                        if (poison instanceof Number) {
                            body.addView(centerText("☠️ Sẽ đầu độc: " + playerName(v, ((Number) poison).intValue()), 18, C_TEXT, true), matchWrap(8));
                            Button cancel = bigButton("Huỷ thuốc độc", C_PANEL2, C_MUTED, 16);
                            body.addView(cancel, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(50), 6));
                            fireOnDown(cancel, new Runnable() {
                                @Override public void run() {
                                    JSONObject bdy = actionBody("wolfWitch");
                                    try { bdy.put("poison", JSONObject.NULL); } catch (Exception ignored) { }
                                    sendAction(bdy, true);
                                }
                            });
                            body.addView(centerText("Hoặc chọn người khác để đổi mục tiêu:", 14, C_MUTED, false), matchWrap(6));
                        } else {
                            body.addView(centerText("Đầu độc ai đó (không bắt buộc):", 15, C_MUTED, true), matchWrap(8));
                        }
                        if (ps != null) {
                            for (int i = 0; i < ps.length(); i++) {
                                JSONObject p = ps.optJSONObject(i);
                                if (p == null) continue;
                                final int ts = jInt(p, "seat", -1);
                                if (ts < 0 || ts == sSeat || !wolfAlive(g, ts)) continue;
                                if (poison instanceof Number && ((Number) poison).intValue() == ts) continue;
                                Button b = pickButton(v, ts, " ☠️", new Runnable() {
                                    @Override public void run() {
                                        JSONObject bdy = actionBody("wolfWitch");
                                        try { bdy.put("poison", ts); } catch (Exception ignored) { }
                                        vibrate(25);
                                        sendAction(bdy, true);
                                    }
                                });
                                body.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 8));
                            }
                        }
                    }
                    Button done = bigButton("✔ Xong — chốt đêm nay", Color.parseColor("#2F7FBF"), Color.parseColor("#6DB6F2"), 18);
                    body.addView(done, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 12));
                    fireOnDown(done, new Runnable() {
                        @Override public void run() {
                            JSONObject bdy = actionBody("wolfWitch");
                            try { bdy.put("done", true); } catch (Exception ignored) { }
                            vibrate(25);
                            sendAction(bdy, true);
                        }
                    });
                }
            } else {
                body.addView(centerText("🌙 Bạn ngủ yên nhé… mong trời mau sáng.", 18, C_TEXT, false), matchWrap(16));
            }
        } else if (gp.equals("dawn")) {
            JSONArray dd = jArr(g, "dawnDeaths");
            if (dd == null || dd.length() == 0) {
                body.addView(centerText("Đêm qua bình yên — không ai chết! 🎉", 20, C_GREEN, true), matchWrap(14));
            } else {
                body.addView(centerText("Đêm qua làng đã mất:", 18, C_TEXT, true), matchWrap(8));
                for (int i = 0; i < dd.length(); i++) {
                    JSONObject d = dd.optJSONObject(i);
                    if (d == null) continue;
                    body.addView(centerText("💀 " + playerName(v, jInt(d, "seat", -1)) + " — "
                        + wolfRoleName(jStr(d, "role", "")), 18, C_TEXT, true), matchWrap(4));
                }
            }
        } else if (gp.equals("discuss")) {
            LinearLayout msgs = col();
            JSONArray chat = jArr(g, "chatLog");
            if (chat != null) {
                for (int i = 0; i < chat.length(); i++) {
                    JSONObject cm = chat.optJSONObject(i);
                    if (cm == null) continue;
                    JSONObject who = playerBySeat(v, jInt(cm, "seat", -1));
                    TextView line = text((who == null ? "" : jStr(who, "name", "")) + ": " + jStr(cm, "text", ""),
                        16, C_TEXT, jInt(cm, "seat", -1) == sSeat);
                    line.setBackground(roundRect(C_PANEL2, C_PANEL2, 12));
                    line.setPadding(dp(12), dp(8), dp(12), dp(8));
                    msgs.addView(line, matchWrap(6));
                }
            }
            final ScrollView sv = new ScrollView(this);
            sv.addView(msgs);
            body.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            sv.post(new Runnable() {
                @Override public void run() { sv.fullScroll(View.FOCUS_DOWN); }
            });
            if (myAlive) {
                // Same composer as the impostor game's discussion.
                LinearLayout rowL = new LinearLayout(this);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                final EditText chatIn = new EditText(this);
                chatIn.setInputType(InputType.TYPE_CLASS_TEXT);
                chatIn.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(60) });
                chatIn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                chatIn.setTextColor(C_TEXT);
                chatIn.setHintTextColor(C_MUTED);
                chatIn.setHint("Nói gì đó…");
                chatIn.setBackground(roundRect(C_PANEL2, accent(), 14));
                chatIn.setPadding(dp(12), dp(8), dp(12), dp(8));
                chatIn.setText(wolfChatDraft);
                chatIn.setSelection(chatIn.getText().length());
                chatIn.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
                    @Override public void onTextChanged(CharSequence s, int a, int b, int n) { wolfChatDraft = s.toString(); }
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
                        wolfChatDraft = "";
                        chatIn.setText("");
                        JSONObject bdy = actionBody("chat");
                        try { bdy.put("text", txt); } catch (Exception ignored) { }
                        sendAction(bdy, true);
                    }
                });
                body.addView(rowL, matchWrap(10));
            } else {
                body.addView(centerText("💀 Bạn đã chết — chỉ theo dõi thôi, không trò chuyện được.", 15, C_MUTED, false), matchWrap(8));
            }
        } else if (gp.equals("vote")) {
            if (!myAlive) {
                body.addView(centerText("💀 Bạn đã chết — theo dõi kết quả bỏ phiếu trên TV nhé.", 18, C_TEXT, true), matchWrap(16));
                addDeadList(body, v, g);
            } else {
                JSONObject voted = jObj(g, "voted");
                boolean iVoted = (voted != null && voted.optBoolean(String.valueOf(sSeat), false))
                    || lockKey.equals("ma-soi:v:" + night);
                int inCount = 0, aliveCount = 0;
                if (ps != null) {
                    for (int i = 0; i < ps.length(); i++) {
                        JSONObject p = ps.optJSONObject(i);
                        if (p == null) continue;
                        int ts = jInt(p, "seat", -1);
                        if (!wolfAlive(g, ts)) continue;
                        aliveCount++;
                        if (voted != null && voted.optBoolean(String.valueOf(ts), false)) inCount++;
                    }
                }
                if (iVoted) {
                    body.addView(centerText("Đã bỏ phiếu ✔ Đợi kết quả…", 21, C_TEXT, true), matchWrap(14));
                } else {
                    body.addView(centerText("Bạn nghi ai là sói? Treo cổ người đó!", 18, C_TEXT, true), matchWrap(6));
                    final String lock = "ma-soi:v:" + night;
                    if (ps != null) {
                        for (int i = 0; i < ps.length(); i++) {
                            JSONObject p = ps.optJSONObject(i);
                            if (p == null) continue;
                            final int ts = jInt(p, "seat", -1);
                            if (ts < 0 || ts == sSeat || !wolfAlive(g, ts)) continue;
                            Button b = pickButton(v, ts, "", new Runnable() {
                                @Override public void run() {
                                    if (lockKey.equals(lock)) return;
                                    lockKey = lock;
                                    JSONObject bdy = actionBody("vote");
                                    try { bdy.put("seat", ts); } catch (Exception ignored) { }
                                    vibrate(25);
                                    sendAction(bdy, true);
                                }
                            });
                            body.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), 8));
                        }
                    }
                }
                body.addView(centerText("Đã vào phiếu: " + inCount + "/" + aliveCount + " người còn sống", 15, C_MUTED, false), matchWrap(8));
            }
        } else if (gp.equals("execute")) {
            JSONObject ex = jObj(g, "exec");
            if (ex != null && ex.opt("seat") instanceof Number) {
                int es = ((Number) ex.opt("seat")).intValue();
                body.addView(centerText("⚖️ " + playerName(v, es) + " bị treo cổ!", 22, C_RED, true), matchWrap(10));
                body.addView(centerText("Vai của họ: " + wolfRoleName(jStr(ex, "role", "")), 18, C_TEXT, true), matchWrap(4));
            } else {
                body.addView(centerText("⚖️ Hoà phiếu — không ai bị treo cổ.", 20, C_TEXT, true), matchWrap(10));
            }
            JSONObject tally = jObj(g, "voteTally");
            if (tally != null) {
                body.addView(text("Số phiếu:", 15, C_MUTED, true), matchWrap(8));
                Iterator<String> kit = tally.keys();
                while (kit.hasNext()) {
                    String ks = kit.next();
                    int ts = -1;
                    try { ts = Integer.parseInt(ks); } catch (Exception ignored) { }
                    if (ts < 0) continue;
                    body.addView(text(playerName(v, ts) + " — " + tally.optInt(ks, 0) + " phiếu", 16, C_TEXT, false), matchWrap(3));
                }
            }
        } else if (gp.equals("wolfEnd")) {
            String winner = jStr(g, "winner", "");
            boolean wolfWon = winner.equals("soi");
            body.addView(centerText(wolfWon ? "🐺 BẦY SÓI THẮNG!" : "🧑‍🌾 DÂN LÀNG THẮNG!",
                26, wolfWon ? C_RED : C_GREEN, true), matchWrap(8));
            if (myRole.length() > 0) {
                boolean iWon = wolfWon == myRole.equals("soi");
                body.addView(centerText(iWon ? "Bạn ở phe thắng! 🎉" : "Phe của bạn đã thua.", 16, C_TEXT, false), matchWrap(2));
            }
            JSONObject roles = jObj(g, "roles");
            body.addView(text("Tất cả vai trò:", 15, C_MUTED, true), matchWrap(8));
            if (ps != null) {
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject p = ps.optJSONObject(i);
                    if (p == null) continue;
                    int ts = jInt(p, "seat", -1);
                    String role = roles == null ? "" : jStr(roles, String.valueOf(ts), "");
                    body.addView(text(playerName(v, ts) + " — " + wolfRoleName(role)
                        + (wolfAlive(g, ts) ? "" : " 💀"), 16, C_TEXT, ts == sSeat), matchWrap(3));
                }
            }
        } else {
            body.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(20));
        }

        if (gp.equals("discuss")) {
            // Discuss keeps its own weighted layout (chat + composer).
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            bp.topMargin = dp(6);
            c.addView(body, bp);
        } else {
            // Pickers at 12 players are long: the body scrolls, the role
            // card and banner stay pinned.
            ScrollView sv = new ScrollView(this);
            sv.addView(body);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            bp.topMargin = dp(6);
            c.addView(sv, bp);
        }
        c.addView(timerLine("sec"), matchWrap(2));
        return c;
    }

    // ---------- Cờ Cá Ngựa (versus: turn-based dice board) ----------
    // Self-healing tap lock: the roll/move transition lands within a
    // few hundred ms (response view or WS push); 1.2s of lockout kills
    // double-taps yet can never wedge the screen if a send got lost.
    private long nguaLockUntil = 0;

    private boolean nguaTryLock() {
        long now = System.currentTimeMillis();
        if (now < nguaLockUntil) return false;
        nguaLockUntil = now + 1200;
        return true;
    }

    private int nguaHome(JSONObject horses, int seat) {
        JSONArray hh = horses == null ? null : horses.optJSONArray(String.valueOf(seat));
        if (hh == null) return 0;
        int n = 0;
        for (int i = 0; i < hh.length(); i++) if (hh.optInt(i, 0) == 58) n++;
        return n;
    }

    private LinearLayout caNgua(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gp = jStr(g, "phase", "");
        int turn = jInt(g, "turn", -1);
        boolean myTurn = jBool(g, "myTurn", false);
        JSONObject horses = jObj(g, "horses");
        if (gp.equals("ngWin")) {
            int winner = jInt(g, "winner", -1);
            c.addView(centerText(winner == sSeat ? "🎉 BẠN THẮNG!"
                : "🏆 " + playerName(v, winner) + " THẮNG!", 28, winner == sSeat ? C_GREEN : C_TEXT, true), matchWrap(16));
            c.addView(centerText("Ngựa của bạn đã về chuồng: " + nguaHome(horses, sSeat) + "/4", 17, C_MUTED, false), matchWrap(4));
            return c;
        }
        c.addView(centerText(myTurn ? "🎲 TỚI LƯỢT BẠN!" : "Lượt của " + playerName(v, turn),
            22, myTurn ? Color.parseColor("#FFC93D") : C_TEXT, true), matchWrap(4));
        Object dice = g.opt("dice");
        if (dice instanceof Number) {
            c.addView(centerText("Xúc xắc: 🎲 " + ((Number) dice).intValue(), 19, C_TEXT, true), matchWrap(2));
        }
        c.addView(centerText("Ngựa của bạn đã về chuồng: " + nguaHome(horses, sSeat) + "/4", 15, C_MUTED, true), matchWrap(2));
        if (gp.equals("roll") && myTurn) {
            Button b = bigButton("🎲 THẢY XÚC XẮC", Color.parseColor("#D97706"), Color.parseColor("#FFC93D"), 26);
            c.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(84), 14));
            fireOnDown(b, new Runnable() {
                @Override public void run() {
                    if (!nguaTryLock()) return;
                    vibrate(25);
                    sendAction(actionBody("roll"), true);
                }
            });
        } else if (gp.equals("pick") && myTurn) {
            c.addView(centerText("Chọn ngựa để đi:", 17, C_MUTED, true), matchWrap(8));
            JSONArray legal = jArr(g, "myLegal");
            if (legal != null) {
                for (int i = 0; i < legal.length(); i++) {
                    JSONObject le = legal.optJSONObject(i);
                    if (le == null) continue;
                    final int horse = jInt(le, "horse", -1);
                    if (horse < 0) continue;
                    Button b = bigButton("🐴 Ngựa " + (horse + 1) + " — " + jStr(le, "hint", ""),
                        Color.parseColor("#92400E"), Color.parseColor("#FFC93D"), 18);
                    c.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(58), 8));
                    fireOnDown(b, new Runnable() {
                        @Override public void run() {
                            if (!nguaTryLock()) return;
                            JSONObject bdy = actionBody("move");
                            try { bdy.put("horse", horse); } catch (Exception ignored) { }
                            vibrate(25);
                            sendAction(bdy, true);
                        }
                    });
                }
            }
        } else {
            JSONArray log = jArr(g, "log");
            if (log != null && log.length() > 0) {
                for (int i = log.length() - 1; i >= Math.max(0, log.length() - 2); i--) {
                    c.addView(centerText(log.optString(i, ""), 15,
                        i == log.length() - 1 ? C_TEXT : C_MUTED, false), matchWrap(4));
                }
            } else {
                c.addView(centerText("Đợi người chơi khác…", 15, C_MUTED, false), matchWrap(6));
            }
        }
        JSONArray ps = jArr(v, "players");
        if (ps != null) {
            LinearLayout rows = col();
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p == null) continue;
                int seat = jInt(p, "seat", -1);
                TextView row = text("🐴 " + jStr(p, "name", "") + " — về chuồng " + nguaHome(horses, seat) + "/4",
                    15, seat == sSeat ? accent() : C_MUTED, seat == sSeat);
                rows.addView(row, matchWrap(3));
            }
            ScrollView sv = new ScrollView(this);
            sv.addView(rows);
            LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            svp.topMargin = dp(10);
            c.addView(sv, svp);
        }
        c.addView(timerLine("sec"), matchWrap(2));
        return c;
    }

    // ---------- Đá Bóng Đầu To (versus: team soccer on the pad) ----------
    private String soccerScoreText(JSONObject g) {
        JSONObject sb = jObj(g, "scoreboard");
        return "ĐỎ " + (sb == null ? 0 : jInt(sb, "do", 0))
            + " — " + (sb == null ? 0 : jInt(sb, "xanh", 0)) + " XANH";
    }

    private LinearLayout soccerPad(JSONObject v, JSONObject g) {
        clearPad(true); // a rebuilt screen must never inherit a stuck "held" bit
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gp = jStr(g, "phase", "");
        if (!gp.equals("soccer") && !gp.equals("goal")) {
            c.addView(centerText("Đợi vào sân…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        boolean red = jStr(g, "myTeam", "").equals("do");
        TextView chip = centerText(red ? "🔴 BẠN Ở ĐỘI ĐỎ" : "🔵 BẠN Ở ĐỘI XANH", 17, Color.WHITE, true);
        chip.setBackground(roundRect(red ? Color.parseColor("#B03030") : Color.parseColor("#2F7FBF"),
            Color.WHITE, 16));
        chip.setPadding(dp(16), dp(5), dp(16), dp(5));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        cp.topMargin = dp(4);
        c.addView(chip, cp);
        TextView score = centerText(soccerScoreText(g), 22, C_TEXT, true);
        tvScoreLine = score;
        c.addView(score, matchWrap(4));
        if (gp.equals("goal")) {
            JSONObject gi = jObj(g, "goalInfo");
            String msg = "⚽ GÔÔÔL!";
            if (gi != null) {
                Object by = gi.opt("by");
                String teamName = jStr(gi, "team", "").equals("do") ? "ĐỘI ĐỎ" : "ĐỘI XANH";
                if (by instanceof Number) {
                    msg += "\n" + playerName(v, ((Number) by).intValue())
                        + (jBool(gi, "ownGoal", false) ? " đá phản lưới nhà! 😱" : " ghi bàn cho " + teamName + "!");
                } else {
                    msg += "\nVào rồi!";
                }
            }
            c.addView(centerText(msg, 21, Color.parseColor("#FFC93D"), true), matchWrap(4));
        }
        addFlex(c);
        LinearLayout.LayoutParams pr = matchWrap(4);
        pr.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(padCluster("A\nSÚT", "B\nLƯỚT", null, "Y\nNHẢY", 13, null), pr);
        addHint(c, "A: SÚT · B: LƯỚT · Y/▲: NHẢY · Đưa bóng vào gôn đối thủ!");
        c.addView(timerLine("sec"), matchWrap(2));
        return c;
    }

    // ---------- Ếch Ộp Phiêu Lưu (co-op: swallow-carry platformer) ----------
    // B and X both gulp/spit on the server (rising edge near a free,
    // non-carrying teammate swallows; a second press while carrying
    // spits). While swallowed this phone shows the in-belly card and no
    // pad at all — the passenger frog has no movement of its own.
    private String frogLineText(JSONObject g) {
        String s = "Tim: " + hearts(Math.max(0, jInt(g, "hearts", 0)))
            + " · Màn " + jInt(g, "chamber", 1) + "/" + jInt(g, "chambersTotal", 3)
            + " · 🪰 " + jInt(g, "fliesEaten", 0) + "/" + jInt(g, "fliesTotal", 9);
        if (jBool(g, "myDoor", false)) s += " · 🚪 Bạn đang ở cửa!";
        return s;
    }

    private LinearLayout frogPad(JSONObject v, JSONObject g) {
        clearPad(true); // a rebuilt screen must never inherit a stuck "held" bit
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gp = jStr(g, "phase", "");
        if (!gp.equals("frog") && !gp.equals("frogClear")) {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        TextView line = centerText(frogLineText(g), 17, C_TEXT, true);
        tvFrogLine = line;
        c.addView(line, matchWrap(4));
        if (gp.equals("frogClear")) {
            c.addView(centerText("🎉 QUA MÀN " + jInt(g, "chamber", 1) + "!", 30, C_GREEN, true), matchWrap(20));
            c.addView(centerText("Chuẩn bị màn tiếp theo…", 16, C_MUTED, false), matchWrap(4));
            return c;
        }
        String state = jStr(g, "myState", "free");
        if (state.equals("in")) {
            Object carrier = g.opt("myCarrierSeat");
            c.addView(centerText("🐸 Bạn đang ở trong bụng "
                + (carrier instanceof Number ? playerName(v, ((Number) carrier).intValue()) : "đồng đội")
                + "!", 24, C_TEXT, true), matchWrap(24));
            c.addView(centerText("Đợi bạn ấy vác qua đoạn khó rồi nhả ra nhé.", 16, C_MUTED, false), matchWrap(6));
            return c;
        }
        if (state.equals("carry")) {
            Object pas = g.opt("myPassengerSeat");
            TextView chip = centerText("🐸 Đang vác "
                + (pas instanceof Number ? playerName(v, ((Number) pas).intValue()) : "")
                + " trong bụng", 16, Color.WHITE, true);
            chip.setBackground(roundRect(Color.parseColor("#1E7A46"), Color.WHITE, 16));
            chip.setPadding(dp(16), dp(5), dp(16), dp(5));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.gravity = Gravity.CENTER_HORIZONTAL;
            c.addView(chip, cp);
        } else {
            c.addView(centerText("🐸 Tự do", 15, C_MUTED, true), matchWrap(2));
        }
        addFlex(c);
        LinearLayout.LayoutParams pr = matchWrap(4);
        pr.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(padCluster("A\nNHẢY", "B\nNUỐT / NHẢ", "X\nNUỐT / NHẢ", "Y\nNHẢY", 12, null), pr);
        addHint(c, "Cạnh đồng đội bấm NUỐT/NHẢ để nuốt · Đang vác bấm lại để nhả ra");
        c.addView(timerLine("sec"), matchWrap(2));
        return c;
    }

    // ---------- Đại Bác Căn Góc (versus: turn-based artillery) ----------
    // The aim screen is NOT a pad cluster: big angle/power/face readouts
    // plus hold-to-repeat steppers that write through setAim (throttled
    // to one send per ~120ms while held, plus a final send on release).
    // Firing goes through the pad channel as a 200ms A pulse — the
    // server reads the rising edge, and 200ms always spans at least one
    // 125ms server tick, so the edge can never slip between samples.
    private static String windText(int wind) {
        if (wind == 0) return "💨 Gió lặng";
        return wind > 0 ? "💨 Gió thổi sang PHẢI ▶ " + wind : "💨 Gió thổi sang TRÁI ◀ " + (-wind);
    }

    private boolean aimActive() {
        if (view == null) return false;
        JSONObject g = jObj(view, "game");
        return g != null && jStr(g, "id", "").equals("can-goc")
            && jStr(g, "phase", "").equals("aim") && jBool(g, "myTurn", false);
    }

    private void refreshAimReadouts() {
        if (tvAimAngle != null) tvAimAngle.setText("Góc: " + aimAngle + "°");
        if (tvAimPower != null) tvAimPower.setText("Lực: " + aimPower);
        if (tvAimFace != null) tvAimFace.setText(aimFace > 0 ? "Hướng: ▶ PHẢI" : "Hướng: ◀ TRÁI");
    }

    private void aimSend(boolean force) {
        if (!aimActive()) return;
        long now = System.currentTimeMillis();
        if (!force && now - aimLastSentAt < 120) {
            if (!aimFlushScheduled) {
                aimFlushScheduled = true;
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        aimFlushScheduled = false;
                        aimSend(true);
                    }
                }, 120);
            }
            return;
        }
        aimLastSentAt = now;
        JSONObject b = actionBody("setAim");
        try {
            b.put("angle", aimAngle);
            b.put("power", aimPower);
            b.put("face", aimFace);
        } catch (Exception ignored) { }
        sendAction(b, false);
    }

    private void aimStep(int field, int delta) {
        if (field == 0) aimAngle = Math.max(5, Math.min(85, aimAngle + delta));
        else aimPower = Math.max(0, Math.min(100, aimPower + delta));
        refreshAimReadouts();
        aimSend(false);
    }

    /** Hold-to-repeat stepper: one step on touch-down, then one step
     *  per ~60ms while held; the final value is force-sent on release. */
    private void attachHoldStepper(Button b, final int field, final int delta) {
        b.setOnTouchListener(new View.OnTouchListener() {
            private Runnable rep;
            @Override public boolean onTouch(View vw, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    vw.setScaleX(0.94f); vw.setScaleY(0.94f);
                    if (vw.getParent() != null) vw.getParent().requestDisallowInterceptTouchEvent(true);
                    final int gen = aimGen;
                    aimHoldCount++;
                    aimStep(field, delta);
                    rep = new Runnable() {
                        @Override public void run() {
                            if (gen != aimGen || !aimActive()) return;
                            aimStep(field, delta);
                            if (aimHoldCount > 0) ui.postDelayed(this, 60);
                        }
                    };
                    ui.postDelayed(rep, 60);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    vw.setScaleX(1f); vw.setScaleY(1f);
                    if (rep != null) { ui.removeCallbacks(rep); rep = null; }
                    if (aimHoldCount > 0) aimHoldCount--;
                    aimSend(true);
                }
                return true;
            }
        });
    }

    /** Fire: one A pulse on the pad channel (rising edge fires). */
    private void fireCannon() {
        if (padState[B_A]) return;
        padState[B_A] = true;
        onPadChanged();
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (padState[B_A]) {
                    padState[B_A] = false;
                    onPadChanged();
                }
            }
        }, 200);
    }

    private void addCannonList(LinearLayout c, JSONObject v, JSONObject g) {
        JSONObject cannons = jObj(g, "cannons");
        JSONArray ps = jArr(v, "players");
        if (cannons == null || ps == null) return;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            int seat = jInt(p, "seat", -1);
            JSONObject cn = jObj(cannons, String.valueOf(seat));
            if (cn == null) continue;
            boolean alive = jBool(cn, "alive", true);
            int hp = jInt(cn, "hp", 0);
            Integer pc = SEAT_HEX.get(jStr(p, "color", "blue"));
            TextView row = text((alive ? "🟢 " : "💀 ") + jStr(p, "name", "") + (seat == sSeat ? " (bạn)" : "")
                + " — " + (alive ? hp + " HP" : "đã bị phá huỷ"), 16, seat == sSeat ? accent() : C_TEXT, seat == sSeat);
            row.setBackground(roundRect(C_PANEL2, pc != null ? pc : C_MUTED, 12));
            row.setPadding(dp(12), dp(6), dp(12), dp(6));
            c.addView(row, matchWrap(5));
        }
    }

    private LinearLayout cannonAim(JSONObject v, JSONObject g) {
        LinearLayout c = col();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        String gp = jStr(g, "phase", "");
        int wind = jInt(g, "wind", 0);
        JSONObject mc = jObj(g, "myCannon");
        boolean myTurn = jBool(g, "myTurn", false);
        if (gp.equals("shell")) {
            c.addView(centerText("🚀 ĐẠN ĐANG BAY…", 30, C_TEXT, true), matchWrap(40));
            c.addView(centerText(windText(wind), 16, C_MUTED, false), matchWrap(6));
            return c;
        }
        if (gp.equals("boom")) {
            c.addView(centerText("💥 BÙM!", 40, C_RED, true), matchWrap(30));
            JSONObject boom = jObj(g, "boom");
            JSONObject hits = boom == null ? null : jObj(boom, "hits");
            if (hits != null) {
                Iterator<String> kit = hits.keys();
                while (kit.hasNext()) {
                    String ks = kit.next();
                    int seat = -1;
                    try { seat = Integer.parseInt(ks); } catch (Exception ignored) { }
                    if (seat < 0) continue;
                    c.addView(centerText(playerName(v, seat) + " trúng đạn −" + hits.optInt(ks, 0) + " HP",
                        17, C_TEXT, false), matchWrap(4));
                }
            }
            return c;
        }
        if (!gp.equals("aim")) {
            c.addView(centerText("Đợi…", 18, C_MUTED, false), matchWrap(30));
            return c;
        }
        if (mc != null && !jBool(mc, "alive", true)) {
            c.addView(centerText("💥 Pháo của bạn đã bị phá huỷ!", 24, C_RED, true), matchWrap(24));
            c.addView(centerText("Xem TV đợi kết quả nhé…", 15, C_MUTED, false), matchWrap(4));
            addCannonList(c, v, g);
            return c;
        }
        if (!myTurn) {
            int turn = jInt(g, "turn", -1);
            c.addView(centerText("Lượt của " + playerName(v, turn), 24, C_TEXT, true), matchWrap(10));
            c.addView(centerText(windText(wind), 17, C_MUTED, true), matchWrap(2));
            LinearLayout rows = col();
            addCannonList(rows, v, g);
            ScrollView sv = new ScrollView(this);
            sv.addView(rows);
            LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            svp.topMargin = dp(8);
            c.addView(sv, svp);
            c.addView(centerText("Đợi tới lượt của bạn để căn góc…", 14, C_MUTED, false), matchWrap(6));
            return c;
        }
        // My turn: seed the local aim from the server's cannon once per
        // aim phase (endsAt is unique per turn); after that the steppers
        // own the locals and updateDynamic only re-syncs between holds.
        long endsAt = jLong(g, "endsAt", 0);
        if (mc != null && aimForEndsAt != endsAt) {
            aimForEndsAt = endsAt;
            aimAngle = (int) Math.round(jDouble(mc, "angle", 45));
            aimPower = jInt(mc, "power", 60);
            int f = jInt(mc, "face", 1);
            aimFace = f == 0 ? 1 : f;
        }
        c.addView(centerText("🎯 TỚI LƯỢT BẠN — CĂN GÓC ĐI!", 20, Color.parseColor("#FFC93D"), true), matchWrap(2));
        c.addView(centerText(windText(wind) + (mc != null ? " · Pháo của bạn: " + jInt(mc, "hp", 0) + " HP" : ""),
            15, C_MUTED, true), matchWrap(2));

        LinearLayout reads = new LinearLayout(this);
        reads.setOrientation(LinearLayout.HORIZONTAL);
        reads.setGravity(Gravity.CENTER);
        tvAimAngle = centerText("", 25, C_TEXT, true);
        tvAimPower = centerText("", 25, C_TEXT, true);
        tvAimFace = centerText("", 19, accent(), true);
        reads.addView(tvAimAngle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        reads.addView(tvAimPower, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        reads.addView(tvAimFace, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(reads, matchWrap(8));
        refreshAimReadouts();

        String[][] steppers = {
            { "− GÓC", "0", "-1" }, { "GÓC +", "0", "1" },
            { "− LỰC", "1", "-1" }, { "LỰC +", "1", "1" } };
        for (int r = 0; r < 2; r++) {
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 2; k++) {
                String[] s = steppers[r * 2 + k];
                Button b = bigButton(s[0], C_PANEL2, accent(), 21);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(64), 1f);
                if (k == 1) bp.leftMargin = dp(10);
                rowL.addView(b, bp);
                attachHoldStepper(b, Integer.parseInt(s[1]), Integer.parseInt(s[2]));
            }
            c.addView(rowL, matchWrap(r == 0 ? 10 : 8));
        }

        Button flip = bigButton("⇋ ĐỔI HƯỚNG", Color.parseColor("#2F7FBF"), Color.parseColor("#6DB6F2"), 19);
        c.addView(flip, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), 10));
        fireOnDown(flip, new Runnable() {
            @Override public void run() {
                aimFace = -aimFace;
                refreshAimReadouts();
                aimSend(true);
            }
        });

        Button fire = bigButton("🔥 BẮN", Color.parseColor("#B03030"), Color.parseColor("#FF6B6B"), 30);
        c.addView(fire, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(84), 12));
        fireOnDown(fire, new Runnable() {
            @Override public void run() {
                vibrate(40);
                fireCannon();
            }
        });
        c.addView(timerLine("sec"), matchWrap(4));
        return c;
    }
}
