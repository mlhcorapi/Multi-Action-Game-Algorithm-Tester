#!/usr/bin/env python3
"""The engine's legal-action generation, made faster without changing a single output. After every
advance the engine recomputes the active tribe's legal actions; that is 85% of a search's CPU time and
nearly all of its allocation (DESIGN 8.6). Four changes, each returning exactly what the code it
replaces returned, in the same order:

1. `StepMove.getNeighbours`: the zone-of-control test (is an enemy unit next to this tile?) is computed
   once per tile and kept, instead of building a neighbourhood list and looking every neighbour up for
   every node the path search expands; the neighbour loop runs over indices in `Vector2d.neighborhood`'s
   order. A `StepMove` serves one unit on one state that does not change while its moves are found.
2. `Pathfinder`: the node already created for a position is looked up by id (one node per position),
   in both searches, instead of by scanning every node; Dijkstra's destinations are de-duplicated by id.
3. `BuildFactory`: one probe action is tested for every (tile, building) and an action is created only
   for a feasible one (`isFeasible` reads an action and changes nothing); the empty-tile test is made
   once per tile; `Types.BUILDING.values()`, which clones an array per call, is read once.
4. `Board.getUnitAt`: an empty tile (0, the board's "no unit", as `enemyUnitAt` reads it) answers null
   without a hash lookup; actor ids start at 1.

Applied to a Tribes tree; idempotent. The engine commit that carries it also adds
`test/MoveGenerationEquivalenceTest.java`, which plays seeded games forward and compares every unit's
destinations and paths, every city's build actions and every tile's unit against the original code.

    python tools/patch_engine_perf.py <tribes engine tree>
"""
import pathlib
import sys

MARK = "// MAGAT: zone of control per tile"


def patch(root, rel, reps):
    p = root / rel
    raw = p.read_bytes().decode("utf-8")
    crlf = "\r\n" in raw
    s = raw.replace("\r\n", "\n")
    for a, b in reps:
        if s.count(a) != 1:
            sys.exit(f"unexpected {rel}: {a.strip().splitlines()[0][:80]!r} found {s.count(a)} time(s)")
        s = s.replace(a, b, 1)
    p.write_bytes((s.replace("\n", "\r\n") if crlf else s).encode("utf-8"))
    print(f"patched {p}")


SCAN = """                PathNode neighbour = null;
                if (nodes != null) {
                    for (PathNode n2 : nodes) {
                        if (nb.equals(n2)) {
                            neighbour = n2;
                            inCache = true;
                            break;
                        }
                    }
                }
                if (neighbour == null) {
                    neighbour = nb;  // Node was not found in cache
                }
"""
LOOKUP = """                // MAGAT: the node for this position, by id (one per position), not by scanning them all.
                PathNode neighbour = byId.get(nb.getId());
                if (neighbour != null) {
                    inCache = true;
                } else {
                    neighbour = nb;  // Node was not found in cache
                }
"""


def main(argv):
    if len(argv) != 1:
        sys.exit(__doc__)
    root = pathlib.Path(argv[0]).resolve()
    if MARK in (root / "src/core/actions/unitactions/StepMove.java").read_text(encoding="utf-8"):
        print("already patched")
        return

    patch(root, "src/core/actions/unitactions/StepMove.java", [
        ("""        for(Vector2d tile : from.neighborhood(1, 0, board.getSize())) {
""", """        // MAGAT: Vector2d.neighborhood(1, 0, size)'s order (x outer, y inner; the centre and off-board
        // tiles skipped), without building the list.
        int size = board.getSize();
        for (int tx = from.x - 1; tx <= from.x + 1; tx++)
        for (int ty = from.y - 1; ty <= from.y + 1; ty++) {
            if ((tx == from.x && ty == from.y) || tx < 0 || ty < 0 || tx >= size || ty >= size) continue;
            Vector2d tile = new Vector2d(tx, ty);
"""),
        ("""            //Check if there is an enemy unit adjacent to the destination.
            for (Vector2d tileAdj : tile.neighborhood(1, 0, board.getSize())) {
                Unit u = board.getUnitAt(tileAdj.x, tileAdj.y);  // There might not be a unit there at all
                if (u != null && u.getTribeId() != unit.getTribeId()) {
                    zoneOfControl = true;
                }
            }
""", """            //Check if there is an enemy unit adjacent to the destination.
            zoneOfControl = enemyAdjacent(board, tile.x, tile.y, size);
"""),
        ("""    @Override
    public void addJumpLink(Vector2d from, Vector2d to, boolean reverse) {""",
         """    """ + MARK + """, computed once: this object serves one unit on one state, which does
    // not change while that unit's moves are found. 0 not known yet, 1 no enemy adjacent, 2 an enemy adjacent.
    private byte[][] zoc;

    private boolean enemyAdjacent(Board board, int x, int y, int size) {
        if (zoc == null) zoc = new byte[size][size];
        byte z = zoc[x][y];
        if (z == 0) {
            z = 1;
            for (int i = x - 1; i <= x + 1; i++)
                for (int j = y - 1; j <= y + 1; j++) {
                    if ((i == x && j == y) || i < 0 || j < 0 || i >= size || j >= size) continue;
                    Unit u = board.getUnitAt(i, j);  // There might not be a unit there at all
                    if (u != null && u.getTribeId() != unit.getTribeId()) z = 2;
                }
            zoc[x][y] = z;
        }
        return z == 2;
    }

    @Override
    public void addJumpLink(Vector2d from, Vector2d to, boolean reverse) {"""),
    ])

    patch(root, "src/utils/graph/Pathfinder.java", [
        ("""import java.util.ArrayList;
import java.util.HashSet;
""", """import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
"""),
        ("""    public HashSet<PathNode> nodes;
""", """    public HashSet<PathNode> nodes;
    // MAGAT: the same nodes by id; PathNode's id is its position, and nodes holds one per position.
    private HashMap<Integer, PathNode> byId;
"""),
        ("""        nodes = new HashSet<>();

        root.setVisited(true);""", """        nodes = new HashSet<>();
        byId = new HashMap<>();
        HashSet<Integer> destinationIds = new HashSet<>();

        root.setVisited(true);"""),
        ("""        openList.add(root);
        nodes.add(root);

        while (openList.size() != 0)
        {
            node = openList.poll();
            //nodes.add(node);
//            System.out.println("Remaining in list: " + openList.size());

            if (!destinationsFromStart.contains(node) && (node != root))
            {""", """        openList.add(root);
        nodes.add(root);
        byId.put(root.getId(), root);

        while (openList.size() != 0)
        {
            node = openList.poll();
            //nodes.add(node);
//            System.out.println("Remaining in list: " + openList.size());

            // MAGAT: de-duplicated by id, as contains() did by position.
            if ((node != root) && destinationIds.add(node.getId()))
            {"""),
        (SCAN + """
                if (!visited.contains(neighbour)) {
                    neighbour.setVisited(true);
                    visited.add(neighbour);
                    neighbour.setTotalCost(nbCost + node.getTotalCost());
                    openList.add(neighbour);
                    nodes.add(neighbour);""", LOOKUP + """
                if (!visited.contains(neighbour)) {
                    neighbour.setVisited(true);
                    visited.add(neighbour);
                    neighbour.setTotalCost(nbCost + node.getTotalCost());
                    openList.add(neighbour);
                    if (nodes.add(neighbour)) byId.put(neighbour.getId(), neighbour);"""),
        ("""        nodes = new HashSet<>();
        PathNode node = null;""", """        nodes = new HashSet<>();
        byId = new HashMap<>();
        PathNode node = null;"""),
        ("""        openList.add(root);
        nodes.add(root);

        while(openList.size() != 0)""", """        openList.add(root);
        nodes.add(root);
        byId.put(root.getId(), root);

        while(openList.size() != 0)"""),
        (SCAN + """
                if (!openList.contains(neighbour) && !closedList.contains(neighbour)) {""", LOOKUP + """
                if (!openList.contains(neighbour) && !closedList.contains(neighbour)) {"""),
        ("""                    openList.add(neighbour);
                    nodes.add(neighbour);

                } else if""", """                    openList.add(neighbour);
                    if (nodes.add(neighbour)) byId.put(neighbour.getId(), neighbour);

                } else if"""),
    ])

    patch(root, "src/core/actions/cityactions/factory/BuildFactory.java", [
        ("""public class BuildFactory implements ActionFactory {

    @Override""", """public class BuildFactory implements ActionFactory {

    // MAGAT: values() clones the array on every call.
    private static final Types.BUILDING[] BUILDINGS = Types.BUILDING.values();

    @Override"""),
        ("""        for(Vector2d tile : tiles){
            for(Types.BUILDING building: Types.BUILDING.values()){
                //check if tile is empty
                if(board.getBuildingAt(tile.x, tile.y) == null) {
                    Build action = new Build(city.getActorId());
                    action.setBuildingType(building);
                    action.setTargetPos(tile.copy());
                    if (action.isFeasible(gs)) {
                        actions.add(action);
                    }
                }
            }
        }""", """        // MAGAT: one probe for the feasibility tests (isFeasible reads an action and changes nothing); an
        // action is created only for a feasible building, as before.
        Build probe = new Build(city.getActorId());

        for(Vector2d tile : tiles){
            //check if tile is empty (the same for every building)
            if(board.getBuildingAt(tile.x, tile.y) != null) continue;
            probe.setTargetPos(tile);
            for(Types.BUILDING building: BUILDINGS){
                probe.setBuildingType(building);
                if (probe.isFeasible(gs)) {
                    Build action = new Build(city.getActorId());
                    action.setBuildingType(building);
                    action.setTargetPos(tile.copy());
                    actions.add(action);
                }
            }
        }"""),
    ])

    patch(root, "src/core/game/Board.java", [
        ("""    public Unit getUnitAt(int x, int y){

        Actor act = gameActors.get(units[x][y]);""", """    public Unit getUnitAt(int x, int y){

        // MAGAT: 0 is the board's "no unit" (enemyUnitAt reads it so too); actor ids start at 1.
        if (units[x][y] == 0) return null;
        Actor act = gameActors.get(units[x][y]);"""),
    ])


if __name__ == "__main__":
    main(sys.argv[1:])
