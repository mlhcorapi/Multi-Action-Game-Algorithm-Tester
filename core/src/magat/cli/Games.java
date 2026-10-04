package magat.cli;

import magat.game.GameModule;

import java.util.Locale;

/**
 * Loads a game adapter by name without the core importing it: {@code tribes} resolves to
 * {@code magat.games.tribes.TribesModule}, which must have a public no-argument constructor.
 */
public final class Games {

    private Games() {}

    @SuppressWarnings("unchecked")
    public static <S, A> GameModule<S, A> load(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        String cls = "magat.games." + n + "." + Character.toUpperCase(n.charAt(0)) + n.substring(1) + "Module";
        try {
            return (GameModule<S, A>) Class.forName(cls).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("no game adapter '" + name + "' (expected class " + cls
                    + " on the classpath)");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not instantiate " + cls + ": " + e, e);
        }
    }
}
