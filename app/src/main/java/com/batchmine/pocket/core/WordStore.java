package com.batchmine.pocket.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What you know in one language, on the phone: the known-word list and card states that came from the PC, what you
 * changed here since the last sync (marked known / unknown, looked up), the words you saved, and the cards waiting for
 * the PC. Kept in one JSON file per language (words_LANG.json), written whole after each change.
 *
 * Colour states are the PC's: k known, y learning, n new card, c has a card, s saved, m card made, e looked up before,
 * u unknown, "" no colour (names and junk).
 */
public final class WordStore {
    public final String lang;
    private final File file;
    private final Set<String> known = new HashSet<>();          // from the PC
    private final Map<String, String> cards = new HashMap<>();  // word -> mature / young / new / card (from the PC)
    private final Set<String> knownAdd = new LinkedHashSet<>(); // marked known here, not sent yet
    private final Set<String> knownDel = new LinkedHashSet<>(); // marked unknown again here, not sent yet
    private final Map<String, Integer> seen = new HashMap<>();  // looked-up words, all (PC + here)
    private final Map<String, Integer> seenAdd = new LinkedHashMap<>();   // looked up here, not sent yet
    private final Set<String> mined = new LinkedHashSet<>();
    private final List<Map<String, Object>> saved = new ArrayList<>();
    private final List<Map<String, Object>> pending = new ArrayList<>();  // cards for the PC to make
    private long pulled = 0;

    public WordStore(File dir, String lang) {
        this.lang = lang;
        this.file = new File(dir, "words_" + lang + ".json");
        load();
    }

    private synchronized void load() {
        if (!file.isFile()) return;
        try {
            Map<String, Object> m = Json.obj(Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
            for (Object w : Json.arr(m.get("known"))) known.add(Json.str(w, ""));
            for (Map.Entry<String, Object> e : Json.obj(m.get("cards")).entrySet()) cards.put(e.getKey(), Json.str(e.getValue(), "card"));
            for (Object w : Json.arr(m.get("known_add"))) knownAdd.add(Json.str(w, ""));
            for (Object w : Json.arr(m.get("known_del"))) knownDel.add(Json.str(w, ""));
            for (Map.Entry<String, Object> e : Json.obj(m.get("seen")).entrySet()) seen.put(e.getKey(), (int) Json.num(e.getValue(), 1));
            for (Map.Entry<String, Object> e : Json.obj(m.get("seen_add")).entrySet()) seenAdd.put(e.getKey(), (int) Json.num(e.getValue(), 1));
            for (Object w : Json.arr(m.get("mined"))) mined.add(Json.str(w, ""));
            for (Object x : Json.arr(m.get("saved"))) saved.add(Json.obj(x));
            for (Object x : Json.arr(m.get("pending"))) pending.add(Json.obj(x));
            pulled = (long) Json.num(m.get("pulled"), 0);
        } catch (IOException | RuntimeException e) {
            // Damaged (cut off while being written?): keep it aside so it is not overwritten by an empty list, and go on.
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(new File(file.getPath() + ".damaged-" + System.currentTimeMillis()));
            File tmp = new File(file.getPath() + ".tmp");
            if (tmp.isFile()) {                                      // the half-finished save may be the good copy
                try {
                    Json.obj(Json.parse(new String(Files.readAllBytes(tmp.toPath()), StandardCharsets.UTF_8)));
                    if (tmp.renameTo(file)) { known.clear(); cards.clear(); knownAdd.clear(); knownDel.clear(); seen.clear(); seenAdd.clear();
                        mined.clear(); saved.clear(); pending.clear(); load(); }
                } catch (IOException | RuntimeException ignored) { }
            }
        }
    }

    public synchronized void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lang", lang);
        m.put("pulled", pulled);
        m.put("known", new ArrayList<>(known));
        m.put("cards", new LinkedHashMap<>(cards));
        m.put("known_add", new ArrayList<>(knownAdd));
        m.put("known_del", new ArrayList<>(knownDel));
        m.put("seen", new LinkedHashMap<>(seen));
        m.put("seen_add", new LinkedHashMap<>(seenAdd));
        m.put("mined", new ArrayList<>(mined));
        m.put("saved", new ArrayList<>(saved));
        m.put("pending", new ArrayList<>(pending));
        try {
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), Json.write(m).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
        } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- colour
    /** Every word counted as known (from the PC and marked here). */
    public synchronized Set<String> knownWords() {
        Set<String> out = new HashSet<>(known);
        out.addAll(knownAdd);
        out.removeAll(knownDel);
        return out;
    }

    public synchronized boolean isKnown(String w) { return (known.contains(w) || knownAdd.contains(w)) && !knownDel.contains(w); }

    /**
     * The colour state of a word: CANDS are its forms (itself in lower case and its dictionary forms), SURFACE the word as
     * written, LISTED whether a dictionary has it (unknown-looking words that no dictionary lists and are capitalised are
     * names: no colour).
     */
    public synchronized String state(Set<String> cands, String surface, boolean listed) {
        String best = null;
        for (String c : cands) {
            String s = cards.get(c);
            if (s == null) continue;
            if (s.equals("mature")) best = "k";
            else if (s.equals("young") && !"k".equals(best)) best = "y";
            else if (s.equals("new") && best == null) best = "n";
            else if (best == null) best = "c";
        }
        if (best != null) return best;
        for (String c : cands) if (isKnown(c)) return "k";
        for (Map<String, Object> x : saved) if (cands.contains(Json.str(x.get("word"), "").toLowerCase())) return "s";
        for (String c : cands) if (mined.contains(c)) return "m";
        if (!listed) {
            boolean real = surface.length() > 2 && surface.codePoints().allMatch(Character::isLetter)
                    && (lang.equals("de") || surface.equals(surface.toLowerCase()));
            if (!real) return "";
        }
        for (String c : cands) if (seen.containsKey(c)) return "e";
        return "u";
    }

    // ---------------------------------------------------------------- changes made on the phone
    public synchronized void markKnown(Set<String> words) {
        for (String w : words) { w = w.toLowerCase(); knownDel.remove(w); if (!known.contains(w)) knownAdd.add(w); }
        save();
    }

    public synchronized void markUnknown(Set<String> words) {
        for (String w : words) { w = w.toLowerCase(); knownAdd.remove(w); if (known.contains(w)) knownDel.add(w); }
        save();
    }

    public synchronized void lookedUp(String word) {
        word = word.toLowerCase();
        seen.merge(word, 1, Integer::sum);
        seenAdd.merge(word, 1, Integer::sum);
        save();
    }

    public synchronized void saveWord(Map<String, Object> item) {
        String w = Json.str(item.get("word"), "").toLowerCase();
        saved.removeIf(x -> Json.str(x.get("word"), "").equalsIgnoreCase(w));
        saved.add(0, item);
        save();
    }

    public synchronized void unsave(String word) { saved.removeIf(x -> Json.str(x.get("word"), "").equalsIgnoreCase(word)); save(); }

    public synchronized List<Map<String, Object>> saved() { return new ArrayList<>(saved); }

    public synchronized void addMined(String word) { mined.add(word.toLowerCase()); save(); }

    /** A card the PC makes when you sync (video line or sentence with the word); returns its id. */
    public synchronized String addPending(Map<String, Object> card) {
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        card.put("id", id);
        card.put("lang", lang);
        card.put("made", System.currentTimeMillis());
        pending.add(card);
        mined.add(Json.str(card.get("word"), "").toLowerCase());
        save();
        return id;
    }

    public synchronized List<Map<String, Object>> pending() { return new ArrayList<>(pending); }

    public synchronized void removePending(String id) { pending.removeIf(x -> id.equals(x.get("id"))); save(); }

    public synchronized int knownCount() {
        Set<String> s = new HashSet<>(known);
        s.addAll(knownAdd);
        s.removeAll(knownDel);
        return s.size();
    }

    public synchronized long pulledAt() { return pulled; }

    public synchronized int unsentCount() { return knownAdd.size() + knownDel.size() + seenAdd.size() + pending.size(); }

    // ---------------------------------------------------------------- sync
    /** What to send to the PC. */
    public synchronized Map<String, Object> outgoing() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lang", lang);
        m.put("known_add", new ArrayList<>(knownAdd));
        m.put("known_remove", new ArrayList<>(knownDel));
        m.put("seen", new LinkedHashMap<>(seenAdd));
        m.put("cards", new ArrayList<>(pending));
        return m;
    }

    /**
     * The PC took what OUT held: clear exactly that (changes made while the request ran stay for next time). RESULT says
     * per card whether the PC took it.
     */
    public synchronized void sent(Map<String, Object> out, Map<String, Object> result) {
        for (Object w : Json.arr(out.get("known_add"))) { String s = Json.str(w, ""); if (knownAdd.remove(s)) known.add(s); }
        for (Object w : Json.arr(out.get("known_remove"))) { String s = Json.str(w, ""); if (knownDel.remove(s)) known.remove(s); }
        for (Map.Entry<String, Object> e : Json.obj(out.get("seen")).entrySet()) {
            Integer left = seenAdd.get(e.getKey());
            if (left == null) continue;
            int rest = left - (int) Json.num(e.getValue(), 0);
            if (rest > 0) seenAdd.put(e.getKey(), rest); else seenAdd.remove(e.getKey());
        }
        Set<String> taken = new HashSet<>();
        for (Object c : Json.arr(result.get("cards"))) {
            Map<String, Object> cm = Json.obj(c);
            if (!"error".equals(cm.get("status"))) taken.add(Json.str(cm.get("id"), ""));
        }
        pending.removeIf(x -> taken.contains(Json.str(x.get("id"), "")));
        save();
    }

    /**
     * The PC's lists replace the PC part here; the phone's unsent changes stay on top of them, so nothing marked on either
     * side is lost.
     */
    public synchronized void pulled(Map<String, Object> pc) {
        known.clear();
        for (Object w : Json.arr(pc.get("known"))) {
            String k = Json.str(w, "").toLowerCase();
            int br = k.indexOf('【');                                   // Japanese heteronyms come as 生物【せいぶつ】: the phone knows the word by its spelling
            known.add(br > 0 ? k.substring(0, br) : k);
        }
        cards.clear();
        for (Map.Entry<String, Object> e : Json.obj(pc.get("cards")).entrySet()) cards.put(e.getKey().toLowerCase(), Json.str(e.getValue(), "card"));
        Map<String, Integer> ns = new HashMap<>();
        for (Map.Entry<String, Object> e : Json.obj(pc.get("seen")).entrySet()) ns.put(e.getKey().toLowerCase(), (int) Json.num(e.getValue(), 1));
        for (Map.Entry<String, Integer> e : seenAdd.entrySet()) ns.merge(e.getKey(), e.getValue(), Integer::sum);
        seen.clear();
        seen.putAll(ns);
        for (Object w : Json.arr(pc.get("mined"))) mined.add(Json.str(w, "").toLowerCase());
        knownAdd.removeAll(known);                 // already known there
        pulled = System.currentTimeMillis();
        save();
    }
}
