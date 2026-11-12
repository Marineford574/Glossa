package com.batchmine.pocket;

import com.batchmine.pocket.core.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The phone's playlists: a name and the video files in the order you want to watch them ("playlists" in the preferences). */
final class Lists {
    private Lists() { }

    static final class PL {
        String id, name;
        List<String> items = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    static List<PL> all() {
        List<PL> out = new ArrayList<>();
        try {
            for (Object o : Json.arr(Json.parse(App.get().prefs().getString("playlists", "[]")))) {
                Map<String, Object> m = Json.obj(o);
                PL p = new PL();
                p.id = Json.str(m.get("id"), "");
                p.name = Json.str(m.get("name"), "Playlist");
                for (Object x : Json.arr(m.get("items"))) p.items.add(String.valueOf(x));
                if (!p.id.isEmpty()) out.add(p);
            }
        } catch (RuntimeException ignored) { /* damaged: none */ }
        return out;
    }

    static PL get(String id) { for (PL p : all()) if (p.id.equals(id)) return p; return null; }

    static void save(List<PL> ps) {
        List<Object> arr = new ArrayList<>();
        for (PL p : ps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id); m.put("name", p.name); m.put("items", new ArrayList<Object>(p.items));
            arr.add(m);
        }
        App.get().prefs().edit().putString("playlists", Json.write(arr)).apply();
    }

    static PL create(String name, List<String> items) {
        List<PL> ps = all();
        PL p = new PL();
        p.id = Long.toString(System.currentTimeMillis(), 36);
        p.name = name.trim().isEmpty() ? "Playlist" : name.trim();
        for (String i : items) if (!p.items.contains(i)) p.items.add(i);
        ps.add(p);
        save(ps);
        return p;
    }

    static void update(PL p) {
        List<PL> ps = all();
        for (int i = 0; i < ps.size(); i++) if (ps.get(i).id.equals(p.id)) ps.set(i, p);
        save(ps);
    }

    static void delete(String id) {
        List<PL> ps = all();
        ps.removeIf(p -> p.id.equals(id));
        save(ps);
    }

    static void add(String id, String file) {
        PL p = get(id);
        if (p != null && !p.items.contains(file)) { p.items.add(file); update(p); }
    }
}
