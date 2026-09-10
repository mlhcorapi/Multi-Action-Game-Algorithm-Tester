package magat.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named flags only: {@code --key value} or {@code --key=value}, and bare {@code --flag}. Positional
 * words before the first flag are the command path. Positional arguments were the source of one
 * calibrator's argument tangle, so there are none.
 */
public final class Args {

    private final List<String> words = new ArrayList<>();
    private final Map<String, String> flags = new LinkedHashMap<>();

    public static Args parse(String[] argv) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String s = argv[i];
            if (s.startsWith("--")) {
                String k = s.substring(2), v = "true";
                int eq = k.indexOf('=');
                if (eq >= 0) { v = k.substring(eq + 1); k = k.substring(0, eq); }
                else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) v = argv[++i];
                a.flags.put(k, v);
            } else {
                a.words.add(s);
            }
        }
        return a;
    }

    public List<String> words() { return words; }
    public String word(int i) { return i < words.size() ? words.get(i) : null; }

    public boolean has(String key) { return flags.containsKey(key); }
    public String get(String key) { return flags.get(key); }

    public String get(String key, String dflt) {
        String v = flags.get(key);
        return v == null ? dflt : v;
    }

    public String require(String key) {
        String v = flags.get(key);
        if (v == null) throw new IllegalArgumentException("missing required flag --" + key);
        return v;
    }

    public int getInt(String key, int dflt) {
        String v = flags.get(key);
        return v == null ? dflt : Integer.parseInt(v.trim());
    }

    public boolean flag(String key) {
        String v = flags.get(key);
        return v != null && !v.equalsIgnoreCase("false");
    }

    public long[] getLongs(String key, long[] dflt) {
        String v = flags.get(key);
        if (v == null) return dflt;
        String[] p = v.split(",");
        long[] out = new long[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Long.parseLong(p[i].trim());
        return out;
    }

    public List<String> getList(String key, List<String> dflt) {
        String v = flags.get(key);
        if (v == null) return dflt;
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }
}
