package magat.agents.oep;

import magat.agent.AgentFactory;
import magat.agent.SearchAgent;
import magat.agent.TurnBudget;
import magat.agent.TurnRecord;
import magat.game.Game;
import magat.meter.Meter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Online Evolutionary Planning (Justesen et al. 2017): a genetic algorithm whose individuals are whole turns.
 *
 * <p>The initial population is random turns: random legal actions, at most {@link OepParams#maxActions} of
 * them, then the end of turn. Each generation sorts the population by fitness and kills its worse half; the
 * survivors refill it with offspring, each the uniform crossover of two survivors drawn at random, with one
 * action replaced at random in one offspring in ten. An offspring is played from the position: an action that
 * is not legal where it falls is replaced by a random legal one. A turn's fitness is the heuristic's value of
 * the position after its end of turn, measured once; the survivors keep theirs. The turn played is the fittest
 * found. The end of turn is drawn only when nothing else is legal, so a turn ends at its last action.
 *
 * <p>The initial population is the initialization, paid from the budget, and it stops when the budget runs out
 * (one turn at least).
 *
 * <p><b>Its own operations</b>, recorded through the meter beside the forward model's: {@code oep.genome}, one
 * turn's own work (drawing it or breeding it, playing it, and its key), timed without the operations nested
 * in it; {@code oep.legal}, one legal-action query; {@code oep.check}, one legality check of a turn's action as
 * it is played; and {@code oep.generation}, one generation's selection (sorting the population and killing
 * the worse half).
 */
public final class Oep<S, A> implements SearchAgent<S, A> {

    /** The operations OEP records, in the order its rows carry them. */
    public static final List<String> OPERATIONS = Collections.unmodifiableList(Arrays.asList(
            "oep.genome", "oep.legal", "oep.check", "oep.generation"));
    private static final Meter.Op GENOME = Meter.register("oep.genome");
    private static final Meter.Op LEGAL = Meter.register("oep.legal");
    private static final Meter.Op CHECK = Meter.register("oep.check");
    private static final Meter.Op GENERATION = Meter.register("oep.generation");

    /** One individual: a turn's actions (its end of turn follows them) and the value of where they lead. */
    private static final class Genome<A> {
        final List<A> actions;
        final double value;
        final boolean ended, terminal;

        Genome(List<A> actions, double value, boolean ended, boolean terminal) {
            this.actions = actions;
            this.value = value;
            this.ended = ended;
            this.terminal = terminal;
        }
    }

    private static final Comparator<Genome<?>> FITTEST_FIRST = (a, b) -> Double.compare(b.value, a.value);

    private final Random rnd;
    private final OepParams params;

    private Game<S, A> game;
    private int player;
    private long evaluated, generations;
    private double sumValue;
    private final Set<String> keys = new HashSet<>();

    public Oep(long seed, OepParams params) {
        this.rnd = new Random(seed);
        this.params = params;
        if (params.populationSize < 2) throw new IllegalArgumentException("populationSize must be >= 2");
        if (params.maxActions < 1) throw new IllegalArgumentException("maxActions must be >= 1");
    }

    // ------------------------------------------------------------------ the turn

    @Override
    public TurnRecord plan(Game<S, A> game, S state, int player, TurnBudget budget) {
        this.game = game;
        this.player = player;
        long t0 = System.currentTimeMillis();

        // The initial population, paid from the budget: random turns until it is full or the budget is spent.
        List<Genome<A>> population = new ArrayList<>();
        while (population.size() < params.populationSize && (population.isEmpty() || !budget.exhausted())) {
            try (Meter.Span g = GENOME.span()) {
                population.add(play(state, null, -1));
            }
        }
        double initial = fittest(population).value;
        long initMillis = System.currentTimeMillis() - t0;
        budget.beginSearch();

        int survivors = Math.max(1, (int) (params.populationSize * (1 - params.killRate)));
        while (!budget.exhausted()) {
            generations++;
            int parents;
            try (Meter.Span g = GENERATION.span()) {
                population.sort(FITTEST_FIRST);
                while (population.size() > survivors) population.remove(population.size() - 1);
                parents = population.size();
            }
            while (population.size() < params.populationSize && !budget.exhausted()) {
                try (Meter.Span g = GENOME.span()) {
                    population.add(offspring(state, population, parents));
                }
            }
        }

        Genome<A> best = fittest(population);
        return TurnRecord.builder()
                .values(initial, best.value, best.terminal, evaluated > 0 ? sumValue / evaluated : initial)
                .search(Meter.advances(), Meter.copies(), Meter.evals(), Meter.units())
                .init(budget, initMillis)
                .planMillis(System.currentTimeMillis() - t0)
                .diversity(evaluated, keys.size(), evaluated - keys.size())
                .sequenceLength(best.actions.size() + (best.ended ? 1 : 0))
                .telemetry("generations", generations)
                .build();
    }

    // ------------------------------------------------------------------ operators

    /** Two survivors drawn at random, crossed over uniformly, the offspring mutated one time in ten, then played. */
    private Genome<A> offspring(S root, List<Genome<A>> population, int parents) {
        int i = rnd.nextInt(parents);
        int j = i;
        if (parents > 1) {
            j = rnd.nextInt(parents - 1);
            if (j >= i) j++;
        }
        List<A> a = population.get(i).actions, b = population.get(j).actions;
        List<A> child = new ArrayList<>(Math.max(a.size(), b.size()));
        for (int k = 0; k < Math.max(a.size(), b.size()); k++) {
            if (k >= a.size()) child.add(b.get(k));
            else if (k >= b.size()) child.add(a.get(k));
            else child.add(rnd.nextBoolean() ? a.get(k) : b.get(k));
        }
        int mutated = !child.isEmpty() && rnd.nextDouble() < params.mutationRate ? rnd.nextInt(child.size()) : -1;
        return play(root, child, mutated);
    }

    /**
     * Plays a turn from a copy of the root and measures its fitness: the template's actions, each kept where it
     * is legal and replaced by a random legal one where it is not or where it is the mutated one; with no
     * template, random legal actions to the longest plan. Then the end of turn.
     */
    private Genome<A> play(S root, List<A> template, int mutated) {
        S s = game.copy(root);
        List<A> actions = new ArrayList<>();
        int length = template == null ? params.maxActions : Math.min(template.size(), params.maxActions);
        for (int k = 0; k < length && !over(s); k++) {
            A a = template == null || k == mutated ? null : template.get(k);
            if (a != null && !isLegal(s, a)) a = null;
            if (a == null) a = randomAction(s);
            if (a == null) break;                          // nothing is legal but the end of turn
            game.advance(s, a);
            actions.add(a);
        }
        boolean ended = false;
        if (!over(s)) {
            A end = endTurn(s);
            if (end != null) {
                game.advance(s, end);
                ended = true;
            }
        }
        double value = game.evaluate(s, player);
        evaluated++;
        sumValue += value;
        keys.add(key(actions));
        return new Genome<>(actions, value, ended, game.isTerminal(s));
    }

    private static <A> Genome<A> fittest(List<Genome<A>> population) {
        Genome<A> best = population.get(0);
        for (Genome<A> g : population) if (g.value > best.value) best = g;
        return best;
    }

    private String key(List<A> actions) {
        StringBuilder sb = new StringBuilder();
        for (A a : actions) sb.append(game.actionKey(a)).append('|');
        return sb.toString();
    }

    // ------------------------------------------------------------------ the game

    /** The game is over, or the turn is no longer this player's. */
    private boolean over(S state) {
        return game.isTerminal(state) || game.activePlayer(state) != player;
    }

    /** A legal action other than the end of turn, drawn at random, after one {@code oep.legal} operation; null if none. */
    private A randomAction(S state) {
        List<A> legal;
        try (Meter.Span q = LEGAL.span()) {
            legal = game.legalActions(state);
        }
        List<A> pool = new ArrayList<>(legal.size());
        for (A a : legal) if (!game.endsTurn(a)) pool.add(a);
        return pool.isEmpty() ? null : pool.get(rnd.nextInt(pool.size()));
    }

    /** Whether a turn's action is legal where it falls, as one {@code oep.check} operation. */
    private boolean isLegal(S state, A action) {
        try (Meter.Span c = CHECK.span()) {
            return game.isLegal(state, action);
        }
    }

    /** The end-of-turn action, found by one {@code oep.legal} operation. */
    private A endTurn(S state) {
        try (Meter.Span q = LEGAL.span()) {
            return game.endTurnAction(state);
        }
    }

    /** The registry entry. */
    public static <S, A> AgentFactory<S, A> factory() {
        Meter.declare(OPERATIONS);
        return new AgentFactory<S, A>() {
            @Override public String id() { return "oep"; }
            @Override public SearchAgent<S, A> create(long seed, TurnBudget budget) { return new Oep<>(seed, new OepParams()); }
            @Override public List<String> operations() { return OPERATIONS; }
            @Override public String description() {
                return "online evolutionary planning: 50 whole turns, the worse half killed each generation, "
                        + "uniform crossover, one offspring in ten mutated, illegal actions replaced at random";
            }
        };
    }
}
