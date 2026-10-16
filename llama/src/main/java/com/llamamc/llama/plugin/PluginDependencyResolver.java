package com.llamamc.llama.plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Decides the load order of the plugins and rejects the ones that cannot load. Candidates keep their scan order, which
 * breaks ties between plugins that are ready at the same time.
 */
final class PluginDependencyResolver {

    private static final String CYCLE_SEPARATOR = " -> ";

    private PluginDependencyResolver() {
    }

    static Resolution resolve(List<PluginCandidate> candidates) {
        var rejected = new ArrayList<Rejection>();
        var active = firstOfEachId(candidates, rejected);
        var knownIds = candidates.stream().map(candidate -> candidate.descriptor().id()).collect(Collectors.toSet());
        dropUnsatisfied(active, knownIds, rejected);
        // Cycles are found before anything is removed, so each member reports the cycle it is part of.
        for (var cycle : cycles(active).entrySet()) {
            rejected.add(new Rejection(active.remove(cycle.getKey()), "dependency cycle: " + cycle.getValue()));
        }
        dropUnsatisfied(active, knownIds, rejected);
        return new Resolution(sortByDependencies(active), List.copyOf(rejected));
    }

    private static Map<String, PluginCandidate> firstOfEachId(List<PluginCandidate> candidates,
                                                              List<Rejection> rejected) {
        var active = new LinkedHashMap<String, PluginCandidate>();
        for (var candidate : candidates) {
            var id = candidate.descriptor().id();
            var existing = active.putIfAbsent(id, candidate);
            if (existing != null) {
                rejected.add(new Rejection(candidate,
                        "duplicate plugin id '" + id + "', already provided by " + existing.jar().getFileName()));
            }
        }
        return active;
    }

    /** Removes every plugin whose hard dependency is not active, until nothing changes. */
    private static void dropUnsatisfied(Map<String, PluginCandidate> active, Set<String> knownIds,
                                        List<Rejection> rejected) {
        var dropped = true;
        while (dropped) {
            dropped = false;
            for (var candidate : List.copyOf(active.values())) {
                var missing = firstInactiveHardDependency(candidate.descriptor(), active);
                if (missing == null) {
                    continue;
                }
                active.remove(candidate.descriptor().id());
                var reason = knownIds.contains(missing)
                        ? "dependency '" + missing + "' is disabled"
                        : "missing dependency '" + missing + "'";
                rejected.add(new Rejection(candidate, reason));
                dropped = true;
            }
        }
    }

    private static String firstInactiveHardDependency(PluginDescriptor descriptor, Map<String, PluginCandidate> active) {
        for (var id : descriptor.depends()) {
            if (!active.containsKey(id)) {
                return id;
            }
        }
        return null;
    }

    /** Maps each plugin that lies on a cycle to the shortest cycle through it, written as a path. */
    private static Map<String, String> cycles(Map<String, PluginCandidate> active) {
        var cycles = new LinkedHashMap<String, String>();
        for (var id : active.keySet()) {
            var path = cyclePath(id, active);
            if (path != null) {
                cycles.put(id, String.join(CYCLE_SEPARATOR, path));
            }
        }
        return cycles;
    }

    private static List<String> cyclePath(String start, Map<String, PluginCandidate> active) {
        var parent = new HashMap<String, String>();
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(start);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var next : dependenciesOf(current, active)) {
                if (next.equals(start)) {
                    return closedPath(start, current, parent);
                }
                if (visited.add(next)) {
                    parent.put(next, current);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    private static List<String> closedPath(String start, String last, Map<String, String> parent) {
        var path = new ArrayList<String>();
        for (var node = last; node != null; node = parent.get(node)) {
            path.addFirst(node);
        }
        path.add(start);
        return path;
    }

    /** Kahn's algorithm over the active plugins. Every node is placed, because the cycles are already gone. */
    private static List<PluginCandidate> sortByDependencies(Map<String, PluginCandidate> active) {
        var position = new HashMap<String, Integer>();
        for (var id : active.keySet()) {
            position.put(id, position.size());
        }
        var pending = new HashMap<String, Integer>();
        var dependents = new HashMap<String, List<String>>();
        var ready = new PriorityQueue<String>(Comparator.comparing(position::get));
        for (var id : active.keySet()) {
            var dependencies = dependenciesOf(id, active);
            pending.put(id, dependencies.size());
            for (var dependency : dependencies) {
                dependents.computeIfAbsent(dependency, _ -> new ArrayList<>()).add(id);
            }
            if (dependencies.isEmpty()) {
                ready.add(id);
            }
        }
        var order = new ArrayList<PluginCandidate>();
        while (!ready.isEmpty()) {
            var id = ready.poll();
            order.add(active.get(id));
            for (var dependent : dependents.getOrDefault(id, List.of())) {
                if (pending.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (order.size() != active.size()) {
            throw new IllegalStateException("plugins with unresolved dependencies remain after the cycle check");
        }
        return order;
    }

    /** Hard and soft dependencies that are active. A soft dependency that is absent or disabled is ignored. */
    private static List<String> dependenciesOf(String id, Map<String, PluginCandidate> active) {
        var descriptor = active.get(id).descriptor();
        return Stream.concat(descriptor.depends().stream(), descriptor.softDepends().stream())
                .filter(active::containsKey)
                .distinct()
                .toList();
    }

    record Resolution(List<PluginCandidate> order, List<Rejection> rejected) {
    }

    record Rejection(PluginCandidate candidate, String reason) {
    }
}
