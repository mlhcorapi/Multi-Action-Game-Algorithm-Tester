package core.data.exp;

/**
 * v6 portfolio entry point.
 *
 * <p>The v6 portfolio is built by exactly the same procedure as v5 — same source games, same capture
 * ticks, same validation, same manifest schema (including the action-composition columns H5 needs) —
 * so this delegates to {@link BuildV5Portfolio} rather than duplicating it. Two reasons that is the
 * right call rather than a shortcut:
 *
 * <ol>
 *   <li>The source games that generate the scenarios are already played by an EMCTS agent using
 *       <b>greedy</b> initialization ({@code BuildV5Portfolio.buildEmctsSourceAgent} never sets
 *       {@code randomInitialSequence}, whose default is false). v5's random-init switch applied only
 *       to the agents under test in the isolation phase, never to portfolio construction — so nothing
 *       about the portfolio needed to change for v6's greedy-init switch.</li>
 *   <li>Building v6's portfolio by an identical procedure keeps it comparable with v5's, which is what
 *       lets the two experiments be read against each other.</li>
 * </ol>
 *
 * <p>Only the output directory differs, so v6 gets its own copy of the scenarios under
 * {@code test_results/v6/_portfolio} and never mutates v5's.
 *
 * <p>Usage: {@code java -cp "out:lib/*" core.data.exp.BuildV6Portfolio [outDir] [threads] [numSingle] [numDual]}
 */
public class BuildV6Portfolio {

    /** Scenario counts, re-exported so v6 code never has to reach into the v5 class for them. */
    public static final int NUM_SINGLE_SCENARIOS = BuildV5Portfolio.NUM_SINGLE_SCENARIOS;
    public static final int NUM_DUAL_SCENARIOS   = BuildV5Portfolio.NUM_DUAL_SCENARIOS;

    private BuildV6Portfolio() {}

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : RunExperimentV6.BASE + "/_portfolio";
        String threads = args.length > 1 ? args[1] : String.valueOf(Runtime.getRuntime().availableProcessors());
        String numSingle = args.length > 2 ? args[2] : String.valueOf(NUM_SINGLE_SCENARIOS);
        String numDual   = args.length > 3 ? args[3] : String.valueOf(NUM_DUAL_SCENARIOS);
        BuildV5Portfolio.main(new String[]{ outDir, threads, numSingle, numDual });
    }

    /** Zero-padded 3-digit scenario id (e.g. 007, 042, 200). */
    public static String sid(int id) { return BuildV5Portfolio.sid(id); }
}
