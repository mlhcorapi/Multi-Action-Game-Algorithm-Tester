package core.data.exp;

import core.Constants;
import core.Types;
import core.actions.Action;
import core.actions.cityactions.CityAction;
import core.actions.unitactions.UnitAction;
import core.game.Game;
import core.game.GameState;
import players.Agent;
import utils.ElapsedCpuTimer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Verifies that {@link Action#toString()} is a faithful identity key for the search metrics.
 *
 * <p>Both agents' {@code seqKey} — the basis of the <b>UniqueSequences</b> metric and of the
 * duplicate-child telemetry — is the '|'-join of each action's {@code toString()}. That is only
 * correct if, within any one reachable state's action set, two DISTINCT actions never share a
 * string (a collision would make genuinely different sequences count as one), and no string
 * contains the '|' separator (which could make different sequences concatenate identically).
 *
 * <p>This probe plays real games (one agent samples uniformly from {@code allGoodActions}, the
 * opponent passes) and at EVERY act() call groups the current action list by toString, comparing
 * members field-by-field (action type + actor ids + target position/ids) — deliberately NOT via
 * {@code Action.equals}, which compares {@code targetPos} by reference and is itself unreliable.
 * Any group whose members differ in those fields is a real collision and is printed with its
 * state context. Exits nonzero if any collision or separator violation is found.
 */
public class ActionKeyCollisionProbe {

    private static int statesScanned = 0;
    private static int actionsScanned = 0;
    private static int collisions = 0;
    private static int separatorViolations = 0;
    private static final Map<String, Integer> collisionsByType = new LinkedHashMap<>();

    public static void main(String[] args) {
        Constants.VISUALS = false;
        Constants.LOG_STATS = false;
        Constants.VERBOSE = false;
        Constants.MAX_TURNS_CAPITALS = 25;
        Constants.TURN_TIME_LIMITED = false;

        Types.TRIBE[] tribes = new Types.TRIBE[]{Types.TRIBE.IMPERIUS, Types.TRIBE.IMPERIUS2};
        // Several level seeds so the scan covers different map layouts (resource clusters are
        // what historically collided).
        long[] levelSeeds = {100, 4242, 90210};

        for (long levelSeed : levelSeeds) {
            ScanningAgent scanner = new ScanningAgent(42);
            ArrayList<Agent> players = new ArrayList<>();
            players.add(scanner);
            players.add(new players.DoNothingAgent(7));
            Game game = new Game();
            game.init(players, levelSeed, tribes, 200 + levelSeed, Types.GAME_MODE.CAPITALS);
            game.run(null, null);
        }

        System.out.printf(Locale.ROOT,
                "%nScanned %d states / %d actions:%n  toString collisions: %d%n  '|' violations: %d%n",
                statesScanned, actionsScanned, collisions, separatorViolations);
        for (Map.Entry<String, Integer> e : collisionsByType.entrySet())
            System.out.println("    " + e.getKey() + ": " + e.getValue());
        boolean pass = collisions == 0 && separatorViolations == 0;
        System.out.println(pass ? "ActionKeyCollisionProbe: PASS"
                                : "ActionKeyCollisionProbe: FAIL (seqKey undercounts UniqueSequences)");
        if (!pass) System.exit(1);
    }

    /** Field-level identity signature, independent of toString. */
    private static String signature(Action a) {
        StringBuilder sb = new StringBuilder(a.getActionType().toString());
        if (a instanceof CityAction) {
            CityAction c = (CityAction) a;
            sb.append("#city=").append(c.getCityId());
            if (c.getTargetPos() != null)
                sb.append("#pos=").append(c.getTargetPos().x).append(';').append(c.getTargetPos().y);
        } else if (a instanceof UnitAction) {
            UnitAction u = (UnitAction) a;
            sb.append("#unit=").append(u.getUnitId());
        }
        // Type-specific extras that define the action but live on subclasses:
        if (a instanceof core.actions.cityactions.Spawn)
            sb.append("#spawn=").append(((core.actions.cityactions.Spawn) a).getUnitType());
        if (a instanceof core.actions.cityactions.Build)
            sb.append("#bld=").append(((core.actions.cityactions.Build) a).getBuildingType());
        if (a instanceof core.actions.cityactions.LevelUp)
            sb.append("#bonus=").append(((core.actions.cityactions.LevelUp) a).getBonus());
        if (a instanceof core.actions.cityactions.ResourceGathering)
            sb.append("#res=").append(((core.actions.cityactions.ResourceGathering) a).getResource());
        if (a instanceof core.actions.unitactions.Move) {
            core.actions.unitactions.Move m = (core.actions.unitactions.Move) a;
            sb.append("#to=").append(m.getDestination().x).append(';').append(m.getDestination().y);
        }
        if (a instanceof core.actions.unitactions.Attack)
            sb.append("#tgt=").append(((core.actions.unitactions.Attack) a).getTargetId());
        if (a instanceof core.actions.unitactions.Convert)
            sb.append("#tgt=").append(((core.actions.unitactions.Convert) a).getTargetId());
        if (a instanceof core.actions.unitactions.Capture) {
            core.actions.unitactions.Capture c = (core.actions.unitactions.Capture) a;
            sb.append("#kind=").append(c.getCaptureType()).append("#tgt=").append(c.getTargetCity());
        }
        if (a instanceof core.actions.tribeactions.TribeAction)
            sb.append("#tribe=").append(((core.actions.tribeactions.TribeAction) a).getTribeId());
        if (a instanceof core.actions.tribeactions.BuildRoad) {
            core.actions.tribeactions.BuildRoad r = (core.actions.tribeactions.BuildRoad) a;
            sb.append("#pos=").append(r.getPosition().x).append(';').append(r.getPosition().y);
        }
        if (a instanceof core.actions.tribeactions.ResearchTech)
            sb.append("#tech=").append(((core.actions.tribeactions.ResearchTech) a).getTech());
        if (a instanceof core.actions.tribeactions.SendStars) {
            core.actions.tribeactions.SendStars s = (core.actions.tribeactions.SendStars) a;
            sb.append("#tgt=").append(s.getTargetID()).append("#n=").append(s.getNumStars());
        }
        if (a instanceof core.actions.tribeactions.DeclareWar)
            sb.append("#tgt=").append(((core.actions.tribeactions.DeclareWar) a).getTargetID());
        return sb.toString();
    }

    /** Plays uniformly random good actions; scans every state's action list on the way. */
    private static final class ScanningAgent extends Agent {
        private final Random rnd;

        ScanningAgent(long seed) { super(seed); rnd = new Random(seed); }

        @Override
        public Action act(GameState gs, ElapsedCpuTimer ect) {
            ArrayList<Action> actions = allGoodActions(gs, rnd);
            scan(gs, actions);
            if (actions.isEmpty()) return new core.actions.tribeactions.EndTurn(gs.getActiveTribeID());
            return actions.get(rnd.nextInt(actions.size()));
        }

        private void scan(GameState gs, List<Action> actions) {
            statesScanned++;
            Map<String, List<Action>> byString = new LinkedHashMap<>();
            for (Action a : actions) {
                actionsScanned++;
                String s = a.toString();
                if (s.indexOf('|') >= 0) {
                    separatorViolations++;
                    System.out.println("[probe] '|' in toString: " + s);
                }
                byString.computeIfAbsent(s, k -> new ArrayList<>()).add(a);
            }
            for (Map.Entry<String, List<Action>> e : byString.entrySet()) {
                if (e.getValue().size() < 2) continue;
                // Same string; are the actions actually identical field-for-field?
                String ref = signature(e.getValue().get(0));
                for (int i = 1; i < e.getValue().size(); i++) {
                    String sig = signature(e.getValue().get(i));
                    if (!sig.equals(ref)) {
                        collisions++;
                        String type = e.getValue().get(i).getActionType().toString();
                        collisionsByType.merge(type, 1, Integer::sum);
                        if (collisions <= 10) {
                            System.out.println("[probe] COLLISION tick=" + gs.getTick()
                                    + " string=\"" + e.getKey() + "\"\n        " + ref + "\n     vs " + sig);
                        }
                    }
                }
            }
        }

        @Override
        public Agent copy() { return null; }
    }
}
