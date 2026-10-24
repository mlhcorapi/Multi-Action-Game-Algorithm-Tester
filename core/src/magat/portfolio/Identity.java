package magat.portfolio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * A portfolio's identity: SHA-256 over its ordered {@code id,source,phase} lines, one per scenario,
 * each terminated by a newline. The formula is fixed: the published Tribes portfolio's identity
 * ({@code ec243fd7...}), which its split files, every currency and every result name, depends on it.
 *
 * <p>Branching and the {@code describe} facts are outside it on purpose: they may be recomputed,
 * while a changed identity means a different portfolio. The game and its configuration are recorded
 * in the manifest header and checked on load, but not hashed: an engine commit that changes nothing
 * about the positions must not change what every result says it was measured on.
 */
public final class Identity {

    public static final String FILE = "identity.txt";

    private Identity() {}

    /** The identity of the given rows, in the order given. */
    public static String of(List<Manifest.Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (Manifest.Row r : rows) sb.append(r.identityLine()).append('\n');
        return sha256(sb.toString());
    }

    /** Reads {@code identity.txt}, or null when there is none. */
    public static String read(Path portfolioDir) throws IOException {
        Path f = portfolioDir.resolve(FILE);
        if (!Files.isRegularFile(f)) return null;
        String s = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim();
        return s.isEmpty() ? null : s;
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 of a file's bytes. */
    public static String sha256(Path file) throws IOException {
        return sha256(Files.readAllBytes(file));
    }

    /** The first twelve hex digits, for banners. */
    public static String shortForm(String identity) {
        return identity == null ? "(none)" : identity.substring(0, Math.min(12, identity.length())) + "...";
    }
}
