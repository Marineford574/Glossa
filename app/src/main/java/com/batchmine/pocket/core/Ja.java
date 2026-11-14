package com.batchmine.pocket.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Japanese on the phone, without a tokenizer: the text is cut into words by the longest match against the headwords of your Japanese
 * dictionary (JMdict / Jitendex), and inflected forms (食べた, 書かれなかった, 美味しくない, 勉強します) are traced back to their dictionary
 * form by suffix rules, the way Yomitan does it. Less exact than the PC's UniDic: words that are not in the dictionary come out as single
 * kanji or as a katakana run, and a long match can swallow the boundary of two words (東京都 for 東京 + 都 when 東京都 is a headword).
 */
public final class Ja {
    private Ja() { }

    /** Set by the app: is this string a headword (or an inflected form of one) in a Japanese dictionary? null: no dictionary, no cutting. */
    public static volatile Predicate<String> LISTED;

    public static boolean isHan(int c) { return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || c == 0x3005 || c == 0x3007; }
    public static boolean isHira(int c) { return c >= 0x3041 && c <= 0x309F; }
    public static boolean isKata(int c) { return (c >= 0x30A1 && c <= 0x30FF) || c == 0x30FC; }
    public static boolean isJaChar(int c) { return isHan(c) || isHira(c) || isKata(c); }

    /** Does the text hold kana or kanji? */
    public static boolean isJa(String s) {
        for (int i = 0; i < s.length(); i++) if (isJaChar(s.charAt(i))) return true;
        return false;
    }

    public static String toHira(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 0x30A1 && c <= 0x30F6 ? (char) (c - 0x60) : c);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- deinflection
    private static final String U = "うくぐすつぬぶむる", A = "わかがさたなばまら", I = "いきぎしちにびみり", E = "えけげせてねべめれ", O = "おこごそとのぼもろ";
    private static final List<String[]> RULES = new ArrayList<>();   // {suffix, replacement, "w" if the whole word may be the suffix}

    private static void r(String from, String to) { RULES.add(new String[]{from, to, ""}); }
    private static void rw(String from, String to) { RULES.add(new String[]{from, to, "w"}); }

    static {
        for (int i = 0; i < 9; i++) {
            String u = String.valueOf(U.charAt(i)), a = String.valueOf(A.charAt(i)), in = String.valueOf(I.charAt(i)), e = String.valueOf(E.charAt(i)), o = String.valueOf(O.charAt(i));
            for (String s : new String[]{"ます", "ました", "ません", "ませんでした", "ましょう", "たい", "たくない", "たかった", "ながら", "なさい", "ませんか", "ましたら"}) r(in + s, u);
            for (String s : new String[]{"ない", "なかった", "なくて", "ず", "ぬ", "なければ", "なきゃ", "ないで"}) r(a + s, u);
            r(a + "れる", u); r(a + "れた", u); r(a + "せる", u); r(a + "せた", u); r(a + "される", u);
            r(e + "る", u); r(e + "ば", u); r(o + "う", u); r(e, u);
        }
        for (String[] p : new String[][]{{"って", "う"}, {"った", "う"}, {"って", "つ"}, {"った", "つ"}, {"って", "る"}, {"った", "る"}, {"んで", "む"}, {"んだ", "む"}, {"んで", "ぶ"}, {"んだ", "ぶ"},
                {"んで", "ぬ"}, {"んだ", "ぬ"}, {"いて", "く"}, {"いた", "く"}, {"いで", "ぐ"}, {"いだ", "ぐ"}, {"して", "す"}, {"した", "す"}, {"ったら", "う"}, {"ったり", "う"}, {"いたり", "く"}, {"んだり", "む"}}) r(p[0], p[1]);
        rw("行って", "行く"); rw("行った", "行く"); rw("いって", "いく"); rw("いった", "いく");
        for (String s : new String[]{"ます", "ました", "ません", "ませんでした", "ましょう", "たい", "たくない", "たかった", "ない", "なかった", "なくて", "ず", "なければ", "ないで", "られる", "られた", "させる", "させた",
                "れば", "よう", "ろ", "て", "た", "たら", "たり", "ながら", "なさい", "よ"}) r(s, "る");
        for (String s : new String[]{"くない", "かった", "くなかった", "くて", "ければ", "く", "さ", "すぎる", "そう", "かったら"}) r(s, "い");
        for (String s : new String[]{"します", "しました", "しません", "して", "した", "しない", "しなかった", "しよう", "すれば", "しろ", "される", "させる", "できる", "しながら", "したい", "している", "してる"})
            RULES.add(new String[]{s, "する", s.length() <= 5 && !s.equals("できる") ? "w" : ""});
        for (String s : new String[]{"します", "しました", "しません", "して", "した", "しない", "しなかった", "しよう", "すれば", "しろ", "される", "させる", "できる", "している", "してる", "したい", "しながら"})
            r(s, "");                                                                           // 勉強します -> 勉強 (nouns that take する are listed without it)
        for (String s : new String[]{"ます", "ました", "ません", "て", "た", "ない", "なかった", "よう", "れば", "い", "られる", "させる", "たい"}) { r("来" + s, "来る"); }
        for (String s : new String[]{"きます", "きました", "きて", "きた", "きたい"}) rw(s, "くる");
        for (String s : new String[]{"こない", "こなかった", "こよう", "これば", "こられる", "こさせる"}) rw(s, "くる");
        RULES.add(new String[]{"来い", "来る", "w"}); RULES.add(new String[]{"こい", "くる", "w"});
        r("ちゃう", "てしまう"); r("じゃう", "でしまう"); r("ちゃった", "てしまった"); r("んじゃった", "んでしまった");
    }

    private static final Map<String, List<String>> CACHE = new HashMap<>();

    /** The dictionary forms a word may stand for (itself not included), nearest first: 食べた → 食べる, 書かれなかった → 書く. */
    public static List<String> deinflect(String word) {
        synchronized (CACHE) {
            List<String> hit = CACHE.get(word);
            if (hit != null) return hit;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> level = new ArrayList<>();
        level.add(word);
        for (int depth = 0; depth < 3; depth++) {
            List<String> next = new ArrayList<>();
            for (String w : level) {
                for (String[] rule : RULES) {
                    String suf = rule[0];
                    if (!w.endsWith(suf)) continue;
                    if (w.length() == suf.length() && !rule[2].equals("w")) continue;      // the suffix needs a stem in front of it
                    String d = w.substring(0, w.length() - suf.length()) + rule[1];
                    if (!d.equals(word) && seen.add(d)) next.add(d);
                }
                if (depth == 0 && w.length() > 1) {                                           // a stem alone (食べ, 見, 話し): + る / う-row
                    char last = w.charAt(w.length() - 1);
                    if (E.indexOf(last) >= 0 || I.indexOf(last) >= 0) { String d = w + "る"; if (seen.add(d)) next.add(d); }
                    int ix = I.indexOf(last);
                    if (ix >= 0) { String d = w.substring(0, w.length() - 1) + U.charAt(ix); if (seen.add(d)) next.add(d); }
                }
            }
            level = next;
        }
        List<String> out = new ArrayList<>(seen);
        synchronized (CACHE) { if (CACHE.size() > 20000) CACHE.clear(); CACHE.put(word, out); }
        return out;
    }

    // ---------------------------------------------------------------- cutting into words
    /** TEXT as [start, end) word pairs: runs of other letters as they are, Japanese cut by the longest headword (or inflected form) that LISTED knows. */
    public static List<int[]> segment(String text, Predicate<String> listed) {
        List<int[]> out = new ArrayList<>();
        int n = text.length(), i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (isJaChar(c)) {
                int runEnd = i;
                while (runEnd < n && isJaChar(text.charAt(runEnd))) runEnd++;
                int hit = 0;
                for (int j = Math.min(runEnd, i + 12); j > i; j--) {
                    String s = text.substring(i, j);
                    if (listed.test(s)) { hit = j; break; }
                }
                if (hit == 0) {
                    hit = i + 1;
                    if (isKata(c)) { while (hit < runEnd && isKata(text.charAt(hit))) hit++; }      // an unknown loanword: the whole katakana run
                }
                out.add(new int[]{i, hit});
                i = hit;
            } else if (Character.isLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK) {
                int j = i;
                while (j < n && !isJaChar(text.charAt(j)) && (Character.isLetter(text.charAt(j)) || Character.getType(text.charAt(j)) == Character.NON_SPACING_MARK
                        || ((text.charAt(j) == '\'' || text.charAt(j) == '’') && j + 1 < n && Character.isLetter(text.charAt(j + 1))))) j++;
                out.add(new int[]{i, j});
                i = j;
            } else i++;
        }
        return out;
    }

    // ---------------------------------------------------------------- furigana
    /** WORD (a headword such as 食べる, 引き出す, 日本語) cut into [text, reading] pairs, the reading "" for the kana that need none:
     *  食べる / たべる -> [食, た] [べる, ""]. When the kana of the word cannot be found in the reading the whole word gets the whole reading. */
    public static List<String[]> furiganaPairs(String word, String reading) {
        List<String[]> out = new ArrayList<>();
        String rd = toHira(reading);
        List<String> seg = new ArrayList<>();
        List<Boolean> han = new ArrayList<>();
        int i = 0;
        while (i < word.length()) {
            boolean h = isHan(word.charAt(i));
            int j = i;
            while (j < word.length() && isHan(word.charAt(j)) == h) j++;
            seg.add(word.substring(i, j)); han.add(h);
            i = j;
        }
        int pos = 0;
        boolean ok = true;
        for (int k = 0; k < seg.size() && ok; k++) {
            String t = seg.get(k);
            if (!han.get(k)) {
                String kana = toHira(t);
                if (!rd.startsWith(kana, pos)) { ok = false; break; }
                out.add(new String[]{t, ""});
                pos += kana.length();
            } else {
                int end;
                if (k == seg.size() - 1) end = rd.length();
                else {
                    String next = toHira(seg.get(k + 1));
                    end = rd.indexOf(next, pos + 1);
                    if (end < 0) { ok = false; break; }
                }
                if (end <= pos) { ok = false; break; }
                out.add(new String[]{t, rd.substring(pos, end)});
                pos = end;
            }
        }
        if (!ok || pos != rd.length()) {
            out.clear();
            if (isHan(word.charAt(0)) || word.codePoints().anyMatch(Ja::isHan)) out.add(new String[]{word, rd});
        }
        return out;
    }

    /** The ruby of an inflected SURFACE (食べた) from its dictionary form LEMMA and that one's READING: {start, end, reading} in SURFACE
     *  for each kanji group, as far as the surface still follows the lemma (食 → た; the る/た ending needs none). */
    public static List<String[]> rubyFor(String surface, String lemma, String reading) {
        List<String[]> out = new ArrayList<>();
        if (reading == null || reading.isEmpty() || lemma.isEmpty()) return out;
        List<String[]> pairs = furiganaPairs(lemma, reading);
        if (pairs.size() == 1 && pairs.get(0)[0].equals(lemma) && !surface.equals(lemma) && lemma.chars().anyMatch(c -> !isHan(c))) return out;   // no alignment: no guess for another form
        int pos = 0;
        for (String[] p : pairs) {
            if (!surface.startsWith(p[0], pos)) break;
            if (!p[1].isEmpty()) out.add(new String[]{String.valueOf(pos), String.valueOf(pos + p[0].length()), p[1]});
            pos += p[0].length();
        }
        return out;
    }

    // ---------------------------------------------------------------- Aozora Bunko text
    private static final Pattern DASHES = Pattern.compile("^-{20,}\\s*$");

    /** Does this text look like an Aozora Bunko file (《ruby》, ［＃notes］, 底本：)? */
    public static boolean looksAozora(String t) {
        return t.contains("底本：") && (t.contains("《") || t.contains("［＃"));
    }

    /** An Aozora Bunko .txt as plain paragraphs (title first): ruby, notes, the explanation block at the top and the colophon removed. */
    public static String aozora(String raw) {
        String[] lines = raw.replace("﻿", "").replace("\r", "").split("\n", -1);
        String title = lines.length > 0 ? lines[0].trim() : "", author = lines.length > 1 ? lines[1].trim() : "";
        int from = 2;
        List<Integer> dash = new ArrayList<>();
        for (int k = 2; k < Math.min(lines.length, 42); k++) if (DASHES.matcher(lines[k]).find()) dash.add(k);
        if (dash.size() >= 2) from = dash.get(1) + 1;
        StringBuilder body = new StringBuilder();
        for (int k = from; k < lines.length; k++) {
            if (lines[k].startsWith("底本：")) break;
            body.append(lines[k]).append('\n');
        }
        String t = body.toString().replaceAll("※［＃[^］]*］", "〓").replaceAll("［＃[^］]*］", "").replaceAll("《[^》]*》", "").replace("｜", "");
        StringBuilder out = new StringBuilder(title + (author.isEmpty() ? "" : " — " + author));
        for (String p : t.split("\n")) {
            String s = p.replace('　', ' ').trim();
            if (!s.isEmpty()) out.append("\n\n").append(s);
        }
        return out.append('\n').toString();
    }
}
