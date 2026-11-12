import com.batchmine.pocket.core.*;
import java.io.*; import java.nio.file.*; import java.util.*;
public class CoreTest {
  static int fails = 0;
  static void check(String name, boolean ok, Object info) { System.out.println((ok ? "  ok  " : "  FAIL ") + name + (ok ? "" : "  " + info)); if (!ok) fails++; }
  public static void main(String[] a) throws Exception {
    String d = "coretest/";
    List<Subs.Cue> c = Subs.parse(new String(Files.readAllBytes(Paths.get(d+"s.srt")), "UTF-8"));
    check("srt: two lines, tags gone", c.size()==2 && c.get(0).text.equals("Attento, Natsu!") && c.get(1).text.equals("-Sbrighiamoci!\n-Sì."), c.size()>0?c.get(0).text:c);
    check("srt: times", Math.abs(c.get(0).start-1)<1e-9 && Math.abs(c.get(0).end-2.5)<1e-9, c.get(0).end);
    check("srt: line at time", Subs.at(c,1.2)==0 && Subs.at(c,2.7)==-1 && Subs.around(c,2.7)==0 && Subs.at(c,3.5)==1, "");
    List<Subs.Cue> v = Subs.parse(new String(Files.readAllBytes(Paths.get(d+"s.vtt")), "UTF-8"));
    check("vtt: short and hour times", v.size()==2 && Math.abs(v.get(1).start-3602.5)<1e-9 && Math.abs(v.get(1).end-3603.25)<1e-9, v.size()==2?v.get(1).start:v.size());
    List<int[]> w = Subs.words("L'acqua, c’è dell'anno—sì 3 volte.");
    StringBuilder ws = new StringBuilder(); for (int[] x : w) ws.append("L'acqua, c’è dell'anno—sì 3 volte.", x[0], x[1]).append('|');
    check("words: elisions kept with the word", ws.toString().equals("L'acqua|c’è|dell'anno|sì|volte|"), ws);
    Book b = Book.fromEpub(new FileInputStream(d+"t.epub"), "x");
    check("epub: title and language", b.title.equals("Il Libro") && b.lang.equals("it"), b.title+"/"+b.lang);
    check("epub: chapters from the nav, in spine order", b.chapters.size()==2 && b.chapters.get(0).title.equals("Capitolo primo") && b.chapters.get(1).title.equals("Secondo"), b.chapters.size());
    check("epub: paragraphs and entities", b.chapters.get(0).paras.equals(Arrays.asList("Uno","C’era una volta un re.","Dell'acqua fresca.")), b.chapters.get(0).paras);
    check("epub: br splits", b.chapters.get(1).paras.equals(Arrays.asList("Fine","della storia.")) || b.chapters.get(1).paras.equals(Arrays.asList("Fine della storia.")), b.chapters.get(1).paras);
    Book t = Book.fromText("Capitolo 1\n\nPrimo paragrafo\nsu due righe.\n\nSecondo.\n\nCapitolo 2\n\nAltro.", "T");
    check("txt: headings become chapters", t.chapters.size()==2 && t.chapters.get(0).paras.get(0).equals("Primo paragrafo su due righe.") && t.chapters.get(1).title.equals("Capitolo 2"), t.chapters.size());
    File bf = new File(d+"b.json"); b.write(bf); Book b2 = Book.read(bf);
    check("book: saved and read back", b2.title.equals(b.title) && b2.paraCount()==b.paraCount(), b2.paraCount());
    // word store
    File sd = new File(d+"store"); Dict.deleteTree(sd); sd.mkdirs();
    WordStore s = new WordStore(sd, "it");
    Map<String,Object> pc = new LinkedHashMap<>(); pc.put("known", Arrays.asList("casa","andare")); Map<String,Object> cards = new LinkedHashMap<>(); cards.put("tornare","young"); cards.put("re","mature"); pc.put("cards", cards); pc.put("seen", Collections.singletonMap("volta", 2));
    s.pulled(pc);
    Set<String> cs = new LinkedHashSet<>(Arrays.asList("tornato","tornare"));
    check("state: learning card", s.state(cs,"tornato",true).equals("y"), s.state(cs,"tornato",true));
    check("state: mature card = known", s.state(Collections.singleton("re"),"re",true).equals("k"), "");
    check("state: known", s.state(Collections.singleton("casa"),"casa",true).equals("k"), "");
    check("state: looked up before", s.state(Collections.singleton("volta"),"volta",true).equals("e"), "");
    check("state: unknown", s.state(Collections.singleton("fresca"),"fresca",true).equals("u"), "");
    check("state: name not in a dictionary = no colour", s.state(Collections.singleton("natsu"),"Natsu",false).equals(""), s.state(Collections.singleton("natsu"),"Natsu",false));
    check("state: lowercase word not listed = unknown", s.state(Collections.singleton("sbrigiamoci"),"sbrigiamoci",false).equals("u"), "");
    s.markKnown(new HashSet<>(Arrays.asList("Fresca"))); s.lookedUp("acqua"); s.lookedUp("acqua");
    Map<String,Object> card = new LinkedHashMap<>(); card.put("kind","video"); card.put("word","fresca"); card.put("sentence","Dell'acqua fresca."); String id = s.addPending(card);
    check("marked known here", s.isKnown("fresca") && s.unsentCount()==3, s.unsentCount());
    Map<String,Object> out = s.outgoing();
    s.markKnown(new HashSet<>(Arrays.asList("dopo")));          // while the sync runs
    Map<String,Object> res = new LinkedHashMap<>(); res.put("cards", Arrays.asList(Collections.singletonMap("id", id)));
    s.sent(out, res);
    check("sent: only what went out is cleared", s.unsentCount()==1 && s.isKnown("dopo") && s.isKnown("fresca") && s.pending().isEmpty(), s.outgoing());
    pc.put("known", Arrays.asList("casa","andare","fresca")); Map<String,Object> sn = new LinkedHashMap<>(); sn.put("volta",2); sn.put("acqua",2); pc.put("seen", sn);
    s.pulled(pc);
    check("pull keeps unsent phone changes", s.isKnown("dopo") && s.isKnown("fresca") && s.state(Collections.singleton("acqua"),"acqua",true).equals("e"), s.outgoing());
    WordStore s2 = new WordStore(sd, "it");
    check("store: saved to disk", s2.isKnown("dopo") && s2.knownCount()==s.knownCount(), s2.knownCount());
    check("pc link parsed", PcLink.parse("http://192.168.1.5:8780/?t=abc_DEF-1").base.equals("http://192.168.1.5:8780") && PcLink.parse("http://x:1/?t=abc").token.equals("abc") && PcLink.parse("nope")==null, "");
    // series from file names
    String[][] eps = {
      {"Il commissario Montalbano S02E03.mkv", "Il commissario Montalbano", "2", "3"},
      {"Don.Matteo.s1e12.720p.mp4", "Don Matteo", "1", "12"},
      {"Gomorra 3x07 - La faida.mkv", "Gomorra", "3", "7"},
      {"Dark - Folge 4.mp4", "Dark", "0", "4"},
      {"[Subs] Kaguya-sama - 05 [1080p].mkv", "Kaguya-sama", "0", "5"},
      {"Novine_08.mp4", "Novine", "0", "8"},
      {"La vita è bella (1997).mp4", "", "0", "-1"},
      {"Blade Runner 2049.mkv", "", "0", "-1"},
      {"Suburra Episodio 2.vlink", "Suburra", "0", "2"},
      {"Mushi-Shi__S1_01.mkv", "Mushi-Shi", "1", "1"},
      {"Dark_Staffel_1_Folge_01.mp4", "Dark", "1", "1"},
      {"Fairy_Tail__S1__01.mkv", "Fairy Tail", "1", "1"},
      {"Gomorra Stagione 2 Episodio 10.mkv", "Gomorra", "2", "10"}};
    for (String[] e : eps) {
      Shows.Ep x = Shows.of(e[0]);
      check("show: " + e[0], x.show.equals(e[1]) && x.season == Integer.parseInt(e[2]) && x.episode == Integer.parseInt(e[3]), x.show + "|" + x.season + "|" + x.episode);
    }
    try {
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(bo)) {
        String[][] ents = {{"META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>"},
          {"OEBPS/content.opf", "<package><metadata><meta name=\"cover\" content=\"cv\"/></metadata><manifest><item id=\"cv\" href=\"img/front%20.jpg\" media-type=\"image/jpeg\"/></manifest></package>"}};
        for (String[] en : ents) { zo.putNextEntry(new java.util.zip.ZipEntry(en[0])); zo.write(en[1].getBytes("UTF-8")); zo.closeEntry(); }
        zo.putNextEntry(new java.util.zip.ZipEntry("OEBPS/img/front .jpg")); zo.write(new byte[400]); zo.closeEntry();
      }
      byte[] pic = EpubCover.extract(new java.io.ByteArrayInputStream(bo.toByteArray()));
      check("epub cover", pic != null && pic.length == 400, pic == null ? "null" : pic.length);
    } catch (Exception ex) { check("epub cover", false, ex); }
    check("pretty name", Shows.pretty("Some_Film.2019.1080p.x264.mkv").equals("Some Film 2019"), Shows.pretty("Some_Film.2019.1080p.x264.mkv"));
    check("show key ignores case and dots", Shows.key("Don Matteo").equals(Shows.key("don.matteo")), "");

    // ---- details: tags, EPUB author with special characters, folder rules
    {
      Map<String, Object> e = new java.util.LinkedHashMap<>();
      Map<String, Object> f = new java.util.LinkedHashMap<>();
      f.put("tags", " Sci-Fi, #Mystery;  sci-fi"); f.put("rating", "4"); f.put("creator", "Baran bo Odar"); f.put("level", "b2");
      Meta.update(e, f, 5);
      check("meta: tags tidied, stars, level upper case", Meta.list(e, "tags").equals(java.util.Arrays.asList("sci-fi", "mystery")) && ((Number) e.get("rating")).intValue() == 4 && "B2".equals(e.get("level")), e.toString());
      e.put("suggest", new java.util.ArrayList<Object>(java.util.Arrays.asList("time travel", "german")));
      Meta.decide(e, java.util.Arrays.asList("german"), java.util.Arrays.asList("time travel"), 6);
      check("meta: an accepted tag joins, a turned-down one is remembered", Meta.list(e, "tags").contains("german") && !e.containsKey("suggest") && Meta.list(e, "rejected").contains("time travel"), e.toString());
      Map<String, Object> mine = new java.util.LinkedHashMap<>(); mine.put("t", 10L); mine.put("pckey", "series:/x");
      Map<String, Object> theirs = new java.util.LinkedHashMap<>(); theirs.put("t", 20L); theirs.put("desc", "PC");
      check("meta: a newer PC entry replaces, the link stays", Meta.takeNewer(mine, theirs) && "PC".equals(mine.get("desc")) && "series:/x".equals(mine.get("pckey")) && !Meta.takeNewer(mine, theirs), mine.toString());
      check("meta: folder rules", Meta.folderShow("Mushi-Shi", new String[]{}) [0].equals("Mushi-Shi") && Meta.folderShow("Shows", new String[]{"Dark", "Season 2"})[0].equals("Dark")
          && Meta.folderShow("Shows", new String[]{"Dark", "Season 2"})[1].equals("2") && Meta.folderShow("Dark", new String[]{"Staffel 1"})[0].equals("Dark"), "");
      String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><package><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Povratak</dc:title>"
          + "<dc:creator opf:role=\"aut\">Krle\u017ea, Miroslav</dc:creator><dc:creator>Ivan Gor&#269;i&#x107; &amp; \u0160enoa</dc:creator><dc:date>1932-01-01T00:00:00</dc:date>"
          + "<dc:description>&lt;p&gt;Ein Maler kehrt zur&#252;ck.&lt;/p&gt;</dc:description><dc:language>hr</dc:language></metadata></package>";
      Map<String, String> m = EpubMeta.parse(opf.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      check("epub meta: authors keep their letters, year, description", "Miroslav Krle\u017ea, Ivan Gor\u010di\u0107 & \u0160enoa".equals(m.get("creator")) && "1932".equals(m.get("aired")) && "Ein Maler kehrt zur\u00fcck.".equals(m.get("desc")), m.toString());
      byte[] latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><metadata><dc:creator>D\u00fcrrenmatt, Friedrich</dc:creator></metadata>".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
      check("epub meta: a file in another encoding is read in that encoding", "Friedrich D\u00fcrrenmatt".equals(EpubMeta.parse(latin).get("creator")), EpubMeta.parse(latin).toString());
      {
        List<Subs.Cue> ecs = new ArrayList<>(Arrays.asList(new Subs.Cue(1, 3, "Ciao amico mio"), new Subs.Cue(4, 5, "Come stai?")));
        List<Subs.Cue> sp = Subs.split(ecs, 0, 5);
        check("split a line: two lines, time shared by length", sp.size() == 3 && sp.get(0).text.equals("Ciao") && sp.get(1).text.equals("amico mio") && Math.abs(sp.get(0).end - (1 + 2 * 4.0 / 13)) < 1e-9, "");
        List<Subs.Cue> mg = Subs.mergeNext(ecs, 0);
        check("merge with the next line", mg.size() == 1 && mg.get(0).end == 5 && mg.get(0).text.equals("Ciao amico mio\nCome stai?"), "");
        String srt = Subs.toSrt(Subs.shift(ecs, 0.5));
        check("shift and write .srt", srt.startsWith("1\n00:00:01,500 --> 00:00:03,500\nCiao amico mio\n\n2\n") && Subs.parse(srt).size() == 2, srt);
      }
      check("dictionary language from its title", "it".equals(Dict.langOfTitle("Leipzig Italian Mixed-Typical")) && "de".equals(Dict.langOfTitle("Leipzig German Mixed-Typical"))
          && "it".equals(Dict.langOfTitle("wty-it-de")) && "".equals(Dict.langOfTitle("JMdict")) && "hr".equals(Dict.langOfTitle("Hrvatski rječnik")), "");
      Map<String, Object> bk = new java.util.LinkedHashMap<>();
      check("meta: the book's own details fill empty fields only", Meta.fromEpub(bk, m, 1) && !Meta.fromEpub(bk, m, 2) && bk.get("creator") != null, bk.toString());
    }
    System.out.println(fails == 0 ? "all passed" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
