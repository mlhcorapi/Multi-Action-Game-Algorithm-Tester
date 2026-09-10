package magat.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The arms registered for one game, by id. Insertion order is the listing order. */
public final class AgentRegistry<S, A> {

    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final Map<String, AgentFactory<S, A>> arms = new LinkedHashMap<>();

    public AgentRegistry<S, A> register(AgentFactory<S, A> arm) {
        String id = arm.id();
        if (id == null || !ID.matcher(id).matches())
            throw new IllegalArgumentException("arm id must be lowercase kebab-case: '" + id + "'");
        if (arms.containsKey(id)) throw new IllegalArgumentException("arm already registered: " + id);
        arms.put(id, arm);
        return this;
    }

    public AgentFactory<S, A> get(String id) {
        AgentFactory<S, A> f = arms.get(id);
        if (f == null) throw new IllegalArgumentException("unknown arm '" + id + "'; registered: " + ids());
        return f;
    }

    public boolean has(String id) {
        return arms.containsKey(id);
    }

    public List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(arms.keySet()));
    }

    public List<AgentFactory<S, A>> all() {
        return Collections.unmodifiableList(new ArrayList<>(arms.values()));
    }
}
