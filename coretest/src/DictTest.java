import com.batchmine.pocket.core.*;
import java.io.*;
import java.util.*;

public class DictTest {
    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        Dict.deleteTree(root);
        long t0 = System.currentTimeMillis();
        for (int i = 1; i < a.length; i++) {
            try (InputStream in = new BufferedInputStream(new FileInputStream(a[i]))) {
                File d = Dict.importZip(in, root, null);
                System.out.println("imported " + d.getName() + " in " + (System.currentTimeMillis() - t0) / 1000.0 + " s, heap used "
                    + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576 + " MB");
            }
        }
        Dictionaries ds = new Dictionaries(root);
        for (Dict d : ds.list()) System.out.println(d.title + " [" + d.lang + "] " + d.summary());
        for (String w : new String[]{"casa", "case", "mangiato", "Sbrigiamoci", "dell'acqua", "Attento", "tornato", "xyzzy", "puoi"}) {
            long t = System.nanoTime();
            Dictionaries.Result r = ds.lookup(w, "it");
            System.out.printf("%-12s %5.1f ms  lemma=%s hits=%d ipa=%s cands=%s%n", w, (System.nanoTime() - t) / 1e6, r.lemma(), r.hits.size(), r.ipa, ds.candidates(w, "it"));
            if (!r.hits.isEmpty()) {
                Dictionaries.Hit h = r.hits.get(0);
                System.out.println("    via=" + h.via + " pos=" + h.pos + " glosses=" + h.glosses.subList(0, Math.min(2, h.glosses.size())));
            }
        }
        Dictionaries.Hit h = ds.lookup("casa", "it").hits.get(0);
        System.out.println(h.html.substring(0, Math.min(500, h.html.length())));
        System.out.println("suggest: " + ds.suggest("mang", "it", 8));
        System.out.println("listed xyzzy=" + ds.listed(ds.candidates("xyzzy","it"), "it") + " casa=" + ds.listed(ds.candidates("case","it"), "it"));
    }
}
