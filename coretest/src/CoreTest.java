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
    System.out.println(fails == 0 ? "all passed" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
