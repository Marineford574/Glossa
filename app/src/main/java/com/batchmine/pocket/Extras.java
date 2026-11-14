package com.batchmine.pocket;

import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Places where new features plug in without touching the screens that show them. A feature registers itself once (in register() below) and
 *  is then asked by the screens:
 *   - Home cards: blocks on Home, in order (today's rings, this week, review, words that keep coming up … later a reminder or stats card).
 *   - Word facts: small chips about a word (level A1–C2, gender, frequency …) shown by the word sheet and the Words list.
 *   - Sync parts: data sent to the PC and taken from it at each sync (levels, definition switches, cards from the tray …).
 *  The screens never need to know which features exist. */
final class Extras {
    private Extras() { }

    // ---------------------------------------------------------------- Home cards
    interface HomeCard {
        /** Adds its block(s) to Home, or nothing when it has nothing to say. */
        void add(MainActivity a, LinearLayout page);
    }

    private static final class Ordered<T> { final int order; final String id; final T it; Ordered(int o, String i, T t) { order = o; id = i; it = t; } }

    private static final List<Ordered<HomeCard>> HOME = new ArrayList<>();

    /** order: where on Home (lower = higher up; the built-in ones use 100, 200, 300 …). The same id again replaces the earlier one. */
    static synchronized void homeCard(String id, int order, HomeCard c) {
        HOME.removeIf(o -> o.id.equals(id));
        HOME.add(new Ordered<>(order, id, c));
        Collections.sort(HOME, (a, b) -> Integer.compare(a.order, b.order));
    }

    static void addHomeCards(MainActivity a, LinearLayout page, int fromOrder, int toOrder) {
        List<Ordered<HomeCard>> now;
        synchronized (Extras.class) { now = new ArrayList<>(HOME); }
        for (Ordered<HomeCard> o : now) {
            if (o.order < fromOrder || o.order >= toOrder) continue;
            try { o.it.add(a, page); } catch (RuntimeException e) { android.util.Log.w("Glossa", "home card " + o.id, e); }   // one broken card never empties Home
        }
    }

    // ---------------------------------------------------------------- facts about a word
    static final class Fact {
        final String text, kind;           // kind: "new" (highlighted), "level", "gender", "freq", "ipa" … (only "new" is drawn differently now)
        Fact(String text, String kind) { this.text = text; this.kind = kind; }
    }

    interface FactSource { List<Fact> of(String lang, String word); }

    private static final List<Ordered<FactSource>> FACTS = new ArrayList<>();

    static synchronized void factSource(String id, int order, FactSource s) {
        FACTS.removeIf(o -> o.id.equals(id));
        FACTS.add(new Ordered<>(order, id, s));
        Collections.sort(FACTS, (a, b) -> Integer.compare(a.order, b.order));
    }

    static List<Fact> facts(String lang, String word) {
        List<Ordered<FactSource>> now;
        synchronized (Extras.class) { now = new ArrayList<>(FACTS); }
        List<Fact> out = new ArrayList<>();
        for (Ordered<FactSource> o : now) {
            try { List<Fact> f = o.it.of(lang, word); if (f != null) out.addAll(f); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    // ---------------------------------------------------------------- sync
    interface SyncPart {
        /** Puts its data into the "extra" map sent to the PC (key = its own name). */
        default void push(Map<String, Object> extra) { }
        /** Takes its data from the "extra" map the PC answered with (runs on the sync thread: no views here). */
        default void pulled(Map<String, Object> extra) { }
    }

    private static final List<Ordered<SyncPart>> SYNC = new ArrayList<>();

    static synchronized void syncPart(String id, SyncPart p) {
        SYNC.removeIf(o -> o.id.equals(id));
        SYNC.add(new Ordered<>(0, id, p));
    }

    static void push(Map<String, Object> extra) {
        List<Ordered<SyncPart>> now;
        synchronized (Extras.class) { now = new ArrayList<>(SYNC); }
        for (Ordered<SyncPart> o : now) try { o.it.push(extra); } catch (RuntimeException e) { android.util.Log.w("Glossa", "sync push " + o.id, e); }
    }

    static void pulled(Map<String, Object> extra) {
        List<Ordered<SyncPart>> now;
        synchronized (Extras.class) { now = new ArrayList<>(SYNC); }
        for (Ordered<SyncPart> o : now) try { o.it.pulled(extra); } catch (RuntimeException e) { android.util.Log.w("Glossa", "sync pull " + o.id, e); }
    }

    // ---------------------------------------------------------------- the features that exist now
    private static boolean registered;

    /** Called once when the app starts (MainActivity.onCreate). New features add one line here. */
    static synchronized void register() {
        if (registered) return;
        registered = true;
        // Home: the blocks that were already there, in their old order; MainActivity draws them.
        homeCard("today", 100, (a, l) -> a.homeToday(l));
        homeCard("week", 200, (a, l) -> a.homeWeek(l));
        homeCard("review", 300, (a, l) -> a.homeReview(l));
        homeCard("trouble", 400, (a, l) -> a.homeTrouble(l));
        DefLangs.register();                               // 3  definition-language switches follow the PC
        Levels.register();                                 // 1  CEFR levels: chip + table from the PC
        SentenceAudio.register();                          // 10 the audio of saved sentences, kept on the phone
        Genders.register();                                // 4  noun gender from the wty files on the PC
    }
}
