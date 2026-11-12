package com.batchmine.pocket;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Rect;
import android.text.Layout;
import android.text.Spannable;
import android.text.style.BackgroundColorSpan;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Game controller support (Xbox, PlayStation, Switch Pro, 8BitDo … over Bluetooth or USB), the same ideas as the PC:
 * the Player and the Reader get their own button meanings, Y picks a word with the D-pad and A opens its card, B goes back, and
 * every button can be changed (Settings → Controller). Anywhere else the D-pad moves between the buttons and A presses.
 *
 * An Activity calls {@link #key} from dispatchKeyEvent and {@link #motion} from dispatchGenericMotionEvent.
 */
final class Pad {
    private Pad() {}

    /** What a screen does for an action name (see ACTIONS); returns false when it has no use for it. */
    interface Target { boolean act(String action); }

    static final String[] BUTTONS = {"A", "B", "X", "Y", "LB", "RB", "LT", "RT", "View", "Menu", "LS", "RS", "Up", "Down", "Left", "Right"};

    /** The actions of each place: {id, label}. */
    static final Map<String, String[][]> ACTIONS = new LinkedHashMap<>();
    /** The standard buttons of each place. */
    static final Map<String, Map<String, String>> DEFAULT = new LinkedHashMap<>();
    static final Map<String, String> TITLE = new LinkedHashMap<>();

    private static void def(String ctx, String title, String[][] acts, String... pairs) {
        ACTIONS.put(ctx, acts);
        TITLE.put(ctx, title);
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        DEFAULT.put(ctx, m);
    }

    static {
        def("player", "Player", new String[][]{{"play", "Play / pause"}, {"prev", "Previous line"}, {"next", "Next line"}, {"repeat", "This line again"}, {"pick", "Pick a word of the line"},
                {"back5", "Back 5 s"}, {"fwd5", "Forward 5 s"}, {"mark", "Mark the line for a card"}, {"quick", "Quick settings"}, {"menu", "Menu"}, {"lines", "All lines"},
                {"volUp", "Volume up"}, {"volDown", "Volume down"}, {"speed", "Speed"}, {"back", "Back"}},
                "A", "play", "B", "back", "X", "repeat", "Y", "pick", "LB", "prev", "RB", "next", "LT", "back5", "RT", "fwd5", "Left", "prev", "Right", "next", "Up", "volUp", "Down", "volDown",
                "Menu", "quick", "View", "menu", "LS", "mark", "RS", "lines");
        def("reader", "Reader", new String[][]{{"pick", "Pick a word on the page"}, {"next", "Next page"}, {"prev", "Page back"}, {"scrollUp", "Scroll up"}, {"scrollDown", "Scroll down"}, {"back", "Back"}},
                "A", "pick", "Y", "pick", "B", "back", "LB", "prev", "RB", "next", "LT", "prev", "RT", "next", "Left", "prev", "Right", "next", "Up", "scrollUp", "Down", "scrollDown");
        def("app", "Everywhere else", new String[][]{{"up", "Move up"}, {"down", "Move down"}, {"left", "Move left"}, {"right", "Move right"}, {"press", "Press"}, {"back", "Back"}, {"scrollUp", "Scroll up"}, {"scrollDown", "Scroll down"}},
                "A", "press", "B", "back", "Up", "up", "Down", "down", "Left", "left", "Right", "right", "LB", "scrollUp", "RB", "scrollDown");
        def("word", "Word card", new String[][]{{"defUp", "Definition above"}, {"defDown", "Definition below"}, {"select", "Tick / untick the definition (without one: make the card)"}, {"card", "Make the card"},
                {"known", "Add to known"}, {"save", "Save for later"}, {"scrollUp", "Scroll up"}, {"scrollDown", "Scroll down"}, {"close", "Close"}},
                "A", "select", "B", "close", "X", "known", "Y", "save", "Menu", "card", "Up", "defUp", "Down", "defDown", "Left", "", "Right", "", "LT", "scrollUp", "RT", "scrollDown", "LB", "scrollUp", "RB", "scrollDown");
    }

    /** What a button is called on the controller in your hands: PlayStation pads say ✕ ○ □ △ L1 R1 L2 R2 Share Options; the standard names are Xbox's. */
    static String label(String b) {
        boolean ps = false, nin = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || (d.getSources() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) continue;
            String n = String.valueOf(d.getName()).toLowerCase(java.util.Locale.ROOT);
            if (d.getVendorId() == 0x054c || n.contains("dualsense") || n.contains("dualshock") || n.contains("wireless controller") || n.contains("playstation")) ps = true;
            if (d.getVendorId() == 0x057e || n.contains("pro controller") || n.contains("joy-con")) nin = true;
        }
        if (ps) switch (b) {
            case "A": return "✕"; case "B": return "○"; case "X": return "□"; case "Y": return "△";
            case "LB": return "L1"; case "RB": return "R1"; case "LT": return "L2"; case "RT": return "R2";
            case "View": return "Share"; case "Menu": return "Options"; case "LS": return "L3"; case "RS": return "R3"; default: return b;
        }
        if (nin) switch (b) {
            case "A": return "B"; case "B": return "A"; case "X": return "Y"; case "Y": return "X";
            case "LB": return "L"; case "RB": return "R"; case "LT": return "ZL"; case "RT": return "ZR"; case "View": return "−"; case "Menu": return "+"; default: return b;
        }
        return b;
    }

    static String button(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "LB";
            case KeyEvent.KEYCODE_BUTTON_R1: return "RB";
            case KeyEvent.KEYCODE_BUTTON_L2: return "LT";
            case KeyEvent.KEYCODE_BUTTON_R2: return "RT";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "View";
            case KeyEvent.KEYCODE_BUTTON_START: return "Menu";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "LS";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "RS";
            case KeyEvent.KEYCODE_DPAD_UP: return "Up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "Down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "Right";
            default: return null;
        }
    }

    /** A controller event (not a keyboard): the buttons always are, the arrows only when they come from a game pad / D-pad device. */
    static boolean fromPad(KeyEvent e) {
        String b = button(e.getKeyCode());
        if (b == null) return false;
        if (e.getKeyCode() >= KeyEvent.KEYCODE_DPAD_UP && e.getKeyCode() <= KeyEvent.KEYCODE_DPAD_RIGHT)
            return (e.getSource() & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) != 0;
        return true;
    }

    static String actionFor(String ctx, String button) {
        String own = App.get().prefs().getString("pad_" + ctx + "_" + button, null);
        if (own != null) return own;                                           // "" = no action
        String d = DEFAULT.get(ctx).get(button);
        return d == null ? "" : d;
    }

    // ---------------------------------------------------------------- the events

    static Dialog.OnKeyListener capture;                                        // set while the settings wait for a button
    private static long lastRepeat;

    /** Call from dispatchKeyEvent. CTX: "player", "reader" or "app". True when the event was used. */
    static boolean key(Activity a, KeyEvent e, String ctx, Target t) {
        if (!fromPad(e)) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) return true;                     // the press was handled on the way down
        boolean arrow = e.getKeyCode() >= KeyEvent.KEYCODE_DPAD_UP && e.getKeyCode() <= KeyEvent.KEYCODE_DPAD_RIGHT;
        if (e.getRepeatCount() > 0 && !arrow) return true;                       // holding a button once is one press; a held arrow repeats
        String ctx2 = picking != null ? "app" : ctx;
        String act = picking != null ? pickAction(e.getKeyCode()) : actionFor(ctx2, button(e.getKeyCode()));
        if (act == null || act.isEmpty()) return true;
        return run(a, act, ctx2, t);
    }

    private static String pickAction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return "p-up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "p-down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "p-left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "p-right";
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_Y: return "p-go";
            case KeyEvent.KEYCODE_BUTTON_B: return "p-stop";
            default: return "p-stop";
        }
    }

    private static boolean run(Activity a, String act, String ctx, Target t) {
        if (act.startsWith("p-")) {
            Picker p = picking;
            if (p == null) return true;
            switch (act) {
                case "p-go": p.go(); break;
                case "p-stop": p.stop(); break;
                default: p.move(act.substring(2));
            }
            return true;
        }
        if (t != null && t.act(act)) return true;
        return generic(a, act);
    }

    /** What every screen understands: move between the buttons, press, back, scroll. */
    private static boolean generic(Activity a, String act) {
        switch (act) {
            case "up": inject(a, KeyEvent.KEYCODE_DPAD_UP); return true;
            case "down": inject(a, KeyEvent.KEYCODE_DPAD_DOWN); return true;
            case "left": inject(a, KeyEvent.KEYCODE_DPAD_LEFT); return true;
            case "right": inject(a, KeyEvent.KEYCODE_DPAD_RIGHT); return true;
            case "press": inject(a, KeyEvent.KEYCODE_DPAD_CENTER); return true;
            case "back": a.onBackPressed(); return true;
            case "scrollUp": case "scrollDown": {
                View f = a.getCurrentFocus();
                android.widget.AbsListView lv = findList(a.getWindow().getDecorView());
                int dy = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.6f) * (act.equals("scrollUp") ? -1 : 1);
                if (lv != null) { lv.smoothScrollBy(dy, 250); return true; }
                android.widget.ScrollView sv = findScroll(a.getWindow().getDecorView());
                if (sv != null) { sv.smoothScrollBy(0, dy); return true; }
                return f != null;
            }
            default: return false;
        }
    }

    static void inject(Activity a, int code) {
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private static android.widget.AbsListView findList(View v) {
        if (v instanceof android.widget.AbsListView && v.getVisibility() == View.VISIBLE && v.isShown()) return (android.widget.AbsListView) v;
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
            android.widget.AbsListView r = findList(((android.view.ViewGroup) v).getChildAt(i));
            if (r != null) return r;
        }
        return null;
    }

    private static android.widget.ScrollView findScroll(View v) {
        if (v instanceof android.widget.ScrollView && v.isShown()) return (android.widget.ScrollView) v;
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
            android.widget.ScrollView r = findScroll(((android.view.ViewGroup) v).getChildAt(i));
            if (r != null) return r;
        }
        return null;
    }

    private static int stickDir;                                                 // 0 none, else the key code the left stick is holding

    /** Call from dispatchGenericMotionEvent: the left stick works as the D-pad (repeating while held), the right stick scrolls. */
    static boolean motion(Activity a, MotionEvent e, String ctx, Target t) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || e.getAction() != MotionEvent.ACTION_MOVE) return false;
        float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y), ry = e.getAxisValue(MotionEvent.AXIS_RZ);
        if (Math.abs(ry) < 0.25f) ry = e.getAxisValue(MotionEvent.AXIS_RY);
        long now = android.os.SystemClock.uptimeMillis();
        int dir = Math.abs(x) > Math.abs(y) ? (x < -0.6f ? KeyEvent.KEYCODE_DPAD_LEFT : x > 0.6f ? KeyEvent.KEYCODE_DPAD_RIGHT : 0)
                                            : (y < -0.6f ? KeyEvent.KEYCODE_DPAD_UP : y > 0.6f ? KeyEvent.KEYCODE_DPAD_DOWN : 0);
        if (dir == 0) stickDir = 0;
        else if (dir != stickDir || now - lastRepeat > 220) {
            boolean first = dir != stickDir;
            if (first || now - lastRepeat > 220) {
                stickDir = dir;
                lastRepeat = now + (first ? 180 : 0);                             // a longer wait before the first repeat
                String ctx2 = picking != null ? "app" : ctx;
                String act = picking != null ? pickAction(dir) : actionFor(ctx2, button(dir));
                if (act != null && !act.isEmpty()) run(a, act, ctx2, t);
            }
        }
        if (Math.abs(ry) > 0.35f && now - lastScroll > 120) { lastScroll = now; if (picking == null) run(a, ry < 0 ? "scrollUp" : "scrollDown", ctx, t); }
        return true;
    }

    private static long lastScroll;

    // ---------------------------------------------------------------- picking a word with the D-pad

    static Picker picking;

    /** The tappable words (Colourer.WordSpan) of some TextViews, one of them framed; the D-pad moves the frame, A taps the word. */
    static final class Picker {
        private final Activity a;
        private final List<Hit> hits = new ArrayList<>();
        private int i;
        private final BackgroundColorSpan mark = new BackgroundColorSpan((Ui.ACCENT & 0x00FFFFFF) | 0x77000000);
        private Hit shown;
        private final Runnable onStop;

        private static final class Hit {
            TextView tv; Colourer.WordSpan sp; int s, e; float x, y;
        }

        private Picker(Activity a, Runnable onStop) { this.a = a; this.onStop = onStop; }

        /** Starts picking among the words of VIEWS that are on the screen. False when there are none. */
        static boolean start(Activity a, List<TextView> views, Runnable onStop) {
            stopAny();
            Picker p = new Picker(a, onStop);
            for (TextView tv : views) {
                if (!(tv.getText() instanceof Spannable) || tv.getLayout() == null || !tv.isShown()) continue;
                Rect vis = new Rect();
                if (!tv.getGlobalVisibleRect(vis)) continue;
                Spannable sp = (Spannable) tv.getText();
                Layout lay = tv.getLayout();
                int[] loc = new int[2];
                tv.getLocationOnScreen(loc);
                for (Colourer.WordSpan w : sp.getSpans(0, sp.length(), Colourer.WordSpan.class)) {
                    Hit h = new Hit();
                    h.tv = tv; h.sp = w; h.s = sp.getSpanStart(w); h.e = sp.getSpanEnd(w);
                    int line = lay.getLineForOffset(h.s);
                    h.x = loc[0] + tv.getTotalPaddingLeft() + lay.getPrimaryHorizontal(h.s);
                    h.y = loc[1] + tv.getTotalPaddingTop() + (lay.getLineTop(line) + lay.getLineBottom(line)) / 2f;
                    if (h.y < vis.top - 4 || h.y > vis.bottom + 4) continue;
                    p.hits.add(h);
                }
            }
            if (p.hits.isEmpty()) return false;
            Collections.sort(p.hits, (x, y) -> Math.abs(x.y - y.y) > 6 ? Float.compare(x.y, y.y) : Float.compare(x.x, y.x));
            picking = p;
            p.show(0);
            return true;
        }

        static void stopAny() { if (picking != null) picking.stop(); }

        private void show(int k) {
            clear();
            i = Math.max(0, Math.min(hits.size() - 1, k));
            shown = hits.get(i);
            ((Spannable) shown.tv.getText()).setSpan(mark, shown.s, shown.e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            shown.tv.invalidate();
        }

        private void clear() {
            if (shown != null && shown.tv.getText() instanceof Spannable) { ((Spannable) shown.tv.getText()).removeSpan(mark); shown.tv.invalidate(); }
            shown = null;
        }

        void move(String dir) {
            if (dir.equals("left")) show(i - 1);
            else if (dir.equals("right")) show(i + 1);
            else {
                Hit c = hits.get(i);
                int best = -1;
                float bd = Float.MAX_VALUE;
                for (int k = 0; k < hits.size(); k++) {
                    Hit h = hits.get(k);
                    boolean ok = dir.equals("down") ? h.y > c.y + 6 : h.y < c.y - 6;
                    if (!ok) continue;
                    float d = Math.abs(h.y - c.y) * 3 + Math.abs(h.x - c.x);
                    if (d < bd) { bd = d; best = k; }
                }
                if (best >= 0) show(best);
            }
        }

        void go() {
            Hit h = shown;
            stop();
            if (h != null) h.sp.onClick(h.tv);
        }

        void stop() {
            clear();
            if (picking == this) picking = null;
            if (onStop != null) onStop.run();
        }
    }

    // ---------------------------------------------------------------- Settings → Controller

    /** Every button of every place; tap a row, press the button for it. */
    static void settings(Activity a) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 14), Ui.dp(a, 16));
        draw(a, box);
        Ui.sheet(a, "Game controller", Ui.scroll(a, box), true);
    }

    private static void draw(Activity a, LinearLayout box) {
        box.removeAllViews();
        box.addView(Ui.hint(a, hasPad() ? "A controller is connected. Tap a row, then press the button you want for it."
                : "Connect a controller (Bluetooth or USB) and press a button. Tap a row, then press the button you want for it."));
        for (String ctx : ACTIONS.keySet()) {
            box.addView(Ui.section(a, TITLE.get(ctx)));
            LinearLayout g = Ui.group(a);
            for (String[] act : ACTIONS.get(ctx)) {
                List<String> bs = new ArrayList<>();
                for (String b : BUTTONS) if (act[0].equals(actionFor(ctx, b))) bs.add(label(b));
                final String actId = act[0];
                Ui.pickRow(g, act[1], bs.isEmpty() ? "–" : android.text.TextUtils.join(" / ", bs), v -> ask(a, ctx, actId, act[1], () -> draw(a, box)));
            }
            Ui.add(box, g, 0);
        }
        box.addView(Ui.small(a, "Back to the standard buttons", v -> {
            android.content.SharedPreferences.Editor ed = App.get().prefs().edit();
            for (String k : new ArrayList<>(App.get().prefs().getAll().keySet())) if (k.startsWith("pad_")) ed.remove(k);
            ed.apply();
            draw(a, box);
        }));
    }

    static boolean hasPad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && ((d.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)) return true;
        }
        return false;
    }

    private static void ask(Activity a, String ctx, String act, String label, Runnable done) {
        android.app.AlertDialog d = Ui.dialog(a).setTitle(label).setMessage("Press the button on the controller for this.\n(Back on the phone: no button.)")
                .setNegativeButton("No button", (x, w) -> {
                    android.content.SharedPreferences.Editor ed = App.get().prefs().edit();
                    for (String b : BUTTONS) if (act.equals(actionFor(ctx, b))) ed.putString("pad_" + ctx + "_" + b, "");
                    ed.apply();
                    done.run();
                }).create();
        d.setOnKeyListener((dd, code, ev) -> {
            String b = button(code);
            if (b == null || ev.getAction() != KeyEvent.ACTION_DOWN || !fromPad(ev)) return false;
            App.get().prefs().edit().putString("pad_" + ctx + "_" + b, act).apply();
            dd.dismiss();
            done.run();
            return true;
        });
        d.show();
    }
}
