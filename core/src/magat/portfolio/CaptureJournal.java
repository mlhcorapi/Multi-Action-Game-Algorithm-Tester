package magat.portfolio;

import magat.game.Capture;
import magat.game.Game;
import magat.run.RunGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Append-only record of finished source games, so a restarted build does not replay them.
 *
 * <p>Layout under {@code _build_journal/}: {@code key.txt}; one {@code <source>_<attempt>_<k>.bin}
 * per capture; and ONE {@code index.csv} line per finished attempt:
 * {@code source,attempt,<phase@seed|phase@seed|...  or  ->,END}.
 *
 * <p><b>Why the whole attempt is one line, and why it ends in {@code END}.</b> An attempt's record
 * must be all-or-nothing. One line per capture looks equivalent and is not: a kill between the third
 * and fourth line of an eleven-capture game leaves a journal that claims the game yielded three, the
 * resume believes it, and the portfolio is silently built from the wrong scenarios: the one failure
 * here that produces bad data rather than lost time. One line per attempt reduces the window to a
 * single append, and the {@code END} sentinel closes it: a line that does not parse to four fields
 * ending in {@code END} is a torn write, and the attempt is replayed.
 *
 * <p>Bins are written BEFORE the index line, so a kill between the two leaves an orphan bin that the
 * replay overwrites, never a claimed capture whose state is missing. A bin that fails to load also
 * sends its attempt back to be replayed.
 *
 * <p>The journal is keyed by the game, its configuration and the source definitions, and discarded
 * when the key changes. It is not keyed by the targets: what an attempt yields does not depend on how
 * many captures are wanted, so a toy build can grow into a full one without replaying a game.
 */
final class CaptureJournal<S> {

    static final String DIR = "_build_journal";
    private static final String INDEX = "index.csv";
    private static final String KEY = "key.txt";

    private final Path dir;
    private final Path index;
    private final Game<S, ?> game;
    /** source -> attempt -> recorded captures (phase, seed), empty when the attempt yielded none. */
    private final Map<String, Map<Integer, List<long[]>>> known = new LinkedHashMap<>();
    private int torn;

    private CaptureJournal(Path dir, Game<S, ?> game) {
        this.dir = dir;
        this.index = dir.resolve(INDEX);
        this.game = game;
    }

    static <S> CaptureJournal<S> open(Path dir, String key, Game<S, ?> game) throws IOException {
        CaptureJournal<S> j = new CaptureJournal<>(dir, game);
        Path keyFile = dir.resolve(KEY);
        if (Files.isDirectory(dir)) {
            String have = Files.isRegularFile(keyFile) ? new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8) : "";
            if (!have.trim().equals(key.trim())) {
                System.out.println("[portfolio] the build journal was written for a different game, configuration or "
                        + "source definition - discarding it.");
                RunGuard.deleteTree(dir);
            }
        }
        Files.createDirectories(dir);
        RunGuard.atomicWrite(keyFile, key.trim() + "\n");
        j.load();
        return j;
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(index)) return;
        for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
            String[] f = line.split(",", -1);
            if (f.length != 4 || !"END".equals(f[3].trim())) { torn++; continue; }
            try {
                String src = f[0].trim();
                int attempt = Integer.parseInt(f[1].trim());
                List<long[]> caps = new ArrayList<>();
                String list = f[2].trim();
                if (!list.isEmpty() && !list.equals("-")) {
                    for (String c : list.split("\\|")) {
                        String[] ps = c.split("@");
                        caps.add(new long[]{Integer.parseInt(ps[0].trim()), Long.parseLong(ps[1].trim())});
                    }
                }
                known.computeIfAbsent(src, k -> new LinkedHashMap<>()).put(attempt, caps);
            } catch (RuntimeException e) {
                torn++;
            }
        }
        if (torn > 0)
            System.out.println("[portfolio] journal: " + torn + " incomplete record(s) ignored (interrupted mid-write); "
                    + "those attempts will be replayed.");
    }

    int attemptsOnDisk() {
        int n = 0;
        for (Map<Integer, List<long[]>> m : known.values()) n += m.size();
        return n;
    }

    int tornRecords() { return torn; }

    /** Captures of a journalled attempt, or null if the attempt still has to be played. */
    synchronized List<Capture<S>> read(String source, int attempt) {
        Map<Integer, List<long[]>> bySource = known.get(source);
        if (bySource == null) return null;
        List<long[]> caps = bySource.get(attempt);
        if (caps == null) return null;
        List<Capture<S>> out = new ArrayList<>(caps.size());
        for (int k = 0; k < caps.size(); k++) {
            Path bin = binFile(source, attempt, k);
            try {
                S state = game.deserialize(Files.readAllBytes(bin));
                out.add(new Capture<>(attempt, caps.get(k)[1], (int) caps.get(k)[0], state));
            } catch (Exception e) {
                // A capture the index claims but cannot be loaded: distrust the whole attempt.
                System.out.println("[portfolio] journal entry " + bin.getFileName() + " unreadable (" + e
                        + ") - replaying attempt " + attempt);
                bySource.remove(attempt);
                return null;
            }
        }
        return out;
    }

    /** Records a finished attempt: every bin first, then ONE terminated index line. */
    void write(String source, int attempt, List<Capture<S>> caps) throws IOException {
        StringBuilder list = new StringBuilder();
        for (int k = 0; k < caps.size(); k++) {
            Capture<S> c = caps.get(k);
            RunGuard.atomicWrite(binFile(source, attempt, k), game.serialize(c.state));
            if (list.length() > 0) list.append('|');
            list.append(c.phase).append('@').append(c.seed);
        }
        RunGuard.appendLine(index, source + "," + attempt + "," + (caps.isEmpty() ? "-" : list.toString()) + ",END");
        synchronized (this) {
            List<long[]> rec = new ArrayList<>();
            for (Capture<S> c : caps) rec.add(new long[]{c.phase, c.seed});
            known.computeIfAbsent(source, k -> new LinkedHashMap<>()).put(attempt, rec);
        }
    }

    void discard() throws IOException {
        RunGuard.deleteTree(dir);
    }

    private Path binFile(String source, int attempt, int k) {
        return dir.resolve(String.format(Locale.ROOT, "%s_%05d_%02d.bin", source, attempt, k));
    }
}
