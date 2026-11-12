package com.batchmine.pocket.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** "Look up on other sites": the same links as the PC's word card (mine_word.site_urls), as {name, url}. */
public final class Sites {
    private Sites() { }

    private static String name(String lang) {
        String[][] n = {{"it", "Italian"}, {"de", "German"}, {"en", "English"}, {"fr", "French"}, {"es", "Spanish"}, {"pt", "Portuguese"}, {"nl", "Dutch"},
                {"ja", "Japanese"}, {"ru", "Russian"}, {"pl", "Polish"}, {"sv", "Swedish"}, {"hr", "Croatian"}, {"tr", "Turkish"}, {"zh", "Chinese"},
                {"ko", "Korean"}, {"la", "Latin"}, {"grc", "Ancient Greek"}};
        for (String[] x : n) if (x[0].equals(lang)) return x[1].toLowerCase();
        return "english";
    }

    private static String enc(String w) {
        try { return URLEncoder.encode(w, "UTF-8").replace("+", "%20"); } catch (UnsupportedEncodingException e) { return w; }
    }

    public static List<String[]> urls(String lang, String word) {
        String w = enc(word), nm = name(lang);
        List<String[]> out = new ArrayList<>();
        if (lang.equals("la") || lang.equals("grc")) {
            out.add(new String[]{"Logeion", "https://logeion.uchicago.edu/" + w});
            out.add(lang.equals("la") ? new String[]{"Whitaker's Words", "https://latin-words.com/cgi-bin/translate.cgi?query=" + w}
                    : new String[]{"Perseus (LSJ)", "https://www.perseus.tufts.edu/hopper/morph?l=" + w + "&la=greek"});
            out.add(new String[]{"Wiktionary", "https://en.wiktionary.org/wiki/" + w});
            out.add(new String[]{"YouGlish", "https://youglish.com/pronounce/" + w + "/" + (lang.equals("la") ? "latin" : "greek")});
            return out;
        }
        out.add(new String[]{"Reverso Context", "https://context.reverso.net/translation/" + (lang.equals("en") ? "english-german" : nm + "-english") + "/" + w});
        switch (lang) {
            case "hr":
                out.add(new String[]{"rjecnik.hr", "https://rjecnik.hr/search/?strict=yes&q=" + w});
                out.add(new String[]{"Wiktionary", "https://en.wiktionary.org/wiki/" + w + "#Serbo-Croatian"});
                break;
            case "it": out.add(new String[]{"Treccani", "https://www.treccani.it/vocabolario/ricerca/" + w + "/"}); break;
            case "de": out.add(new String[]{"Duden", "https://www.duden.de/suchen/dudenonline/" + w}); break;
            case "fr": out.add(new String[]{"Larousse", "https://www.larousse.fr/dictionnaires/francais/" + w}); break;
            case "es": out.add(new String[]{"RAE", "https://dle.rae.es/" + w}); break;
            default: out.add(new String[]{"Wiktionary", "https://" + lang + ".wiktionary.org/wiki/" + w});
        }
        out.add(new String[]{"Forvo", "https://forvo.com/word/" + w + "/#" + lang});
        out.add(new String[]{"YouGlish", "https://youglish.com/pronounce/" + w + "/" + nm});
        return out;
    }
}
