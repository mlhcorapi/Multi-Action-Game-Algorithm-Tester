package core.data.exp;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Small CSV / checkpoint helpers shared by the experiment runners.
 * - Telemetry doubles are rounded to 4 dp to remove float noise.
 * - Rows are flushed once per matchup (not per game) so an interrupted matchup leaves no
 *   partial rows; completed matchups are recorded in a checkpoint file and skipped on resume.
 */
public class ExpCsv {

    public static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 10000.0) / 10000.0;
    }

    public static void ensureDir(File dir) {
        if (!dir.exists()) dir.mkdirs();
    }

    /**
     * Append a batch of rows to a CSV, writing the header only when the file is created.
     *
     * <p>Rows are terminated with an explicit {@code '\n'} rather than {@code println()}'s
     * platform separator. Resume paths rewrite these files wholesale (see
     * {@code IsolationPhaseRunnerV6.compactPartialLeaf}) and then append to them, so a
     * platform-dependent terminator produces a file whose first N lines end differently from the
     * rest — invisible on Linux, and on Windows enough to make two runs of the same work compare
     * unequal for no real reason. One terminator everywhere keeps a resumed file identical to an
     * uninterrupted one.
     */
    public static synchronized void append(File file, String header, List<String> lines) {
        if (lines.isEmpty()) return;
        boolean isNew = !file.exists();
        try (FileWriter fw = new FileWriter(file, true);
             PrintWriter pw = new PrintWriter(fw)) {
            if (isNew && header != null) { pw.print(header); pw.print('\n'); }
            for (String line : lines) { pw.print(line); pw.print('\n'); }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void writeText(File file, String content) {
        try (FileWriter fw = new FileWriter(file, false);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.print(content);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ---- checkpoint (matchup-level resume) ----

    public static Set<String> readCompleted(File checkpoint) {
        Set<String> done = new HashSet<>();
        if (checkpoint.exists()) {
            try {
                for (String line : Files.readAllLines(checkpoint.toPath())) {
                    String key = line.trim();
                    if (!key.isEmpty()) done.add(key);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return done;
    }

    public static synchronized void markCompleted(File checkpoint, String key) {
        try (FileWriter fw = new FileWriter(checkpoint, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(key);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
