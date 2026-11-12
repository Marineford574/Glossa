package com.batchmine.pocket.core;

import java.util.ArrayList;
import java.util.List;

/** How much of a book you can read: the share of its words you know, and how many of its whole sentences you would know every word of (the PC Reader's two counts). */
public final class Rating {
    private Rating() { }

    /** 0: not a word to count (a name, junk), 1: known, 2: unknown. */
    public interface State { int of(String lowerWord); }

    public static final class Row {
        public String title = "";
        public int words, unknownWords, sentences, knownSentences;
        /** % of the words known, -1 when there are none to count. */
        public int wordsPct() { return words == 0 ? -1 : (int) Math.round(100.0 * (words - unknownWords) / words); }
        public int sentencesPct() { return sentences == 0 ? -1 : (int) Math.round(100.0 * knownSentences / sentences); }
    }

    /** rows.get(0) is the whole book, then one row per chapter. */
    public static List<Row> rate(Book b, State st) {
        List<Row> rows = new ArrayList<>();
        Row all = new Row();
        all.title = b.title;
        rows.add(all);
        for (Book.Chapter c : b.chapters) {
            Row r = new Row();
            r.title = c.title;
            for (String p : c.paras) paragraph(p, st, r);
            rows.add(r);
            all.words += r.words; all.unknownWords += r.unknownWords; all.sentences += r.sentences; all.knownSentences += r.knownSentences;
        }
        return rows;
    }

    static void paragraph(String p, State st, Row r) {
        List<int[]> ws = Subs.words(p);
        int[] unk = new int[ws.size()];
        int nu = 0;
        boolean[] counted = new boolean[ws.size()];
        for (int i = 0; i < ws.size(); i++) {
            int s = st.of(p.substring(ws.get(i)[0], ws.get(i)[1]).toLowerCase(java.util.Locale.ROOT));
            if (s == 0) continue;
            counted[i] = true;
            r.words++;
            if (s == 2) { r.unknownWords++; unk[nu++] = ws.get(i)[0]; }
        }
        // sentences: cut after . ! ? …  (and closing quotes); fewer than three words is not a sentence to count
        int from = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[.!?…]+[\"'»”’)]*(\\s+|$)").matcher(p);
        List<int[]> sents = new ArrayList<>();
        while (m.find()) { sents.add(new int[]{from, m.end()}); from = m.end(); }
        if (from < p.length()) sents.add(new int[]{from, p.length()});
        for (int[] s : sents) {
            int words = 0;
            for (int[] w : ws) if (w[0] >= s[0] && w[0] < s[1]) words++;
            if (words < 3) continue;
            r.sentences++;
            boolean ok = true;
            for (int i = 0; i < nu; i++) if (unk[i] >= s[0] && unk[i] < s[1]) { ok = false; break; }
            if (ok) r.knownSentences++;
        }
    }
}
