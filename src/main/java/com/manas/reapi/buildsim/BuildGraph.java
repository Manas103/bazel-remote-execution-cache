package com.manas.reapi.buildsim;

import java.util.*;

/**
 * The static shape of the synthetic 420-target build graph: who depends on
 * whom, and (the part BuildSimulator itself never needed) who is
 * transitively affected if a given target's source changes. This is the
 * one place both BuildSimulator (which only needs depsOf) and the release
 * gate (which needs the reverse, transitive reachability) read the graph
 * from, so the two can never silently drift apart.
 */
public final class BuildGraph {
    public static final int NUM_TARGETS = 420;

    // The first BASE_COUNT targets are shared libraries with no
    // dependencies; every other target depends on exactly two of them.
    // See BuildSimulator's README Findings for why this is flat and
    // two-level rather than a long chain.
    public static final int BASE_COUNT = 20;

    private final List<List<Integer>> dependents; // dependents.get(i) = targets that directly depend on i

    public BuildGraph() {
        dependents = new ArrayList<>(NUM_TARGETS);
        for (int i = 0; i < NUM_TARGETS; i++) dependents.add(new ArrayList<>());
        for (int i = 0; i < NUM_TARGETS; i++) {
            for (int dep : depsOf(i)) {
                dependents.get(dep).add(i);
            }
        }
    }

    public static List<Integer> depsOf(int i) {
        List<Integer> deps = new ArrayList<>();
        if (i < BASE_COUNT) return deps;
        deps.add(i % BASE_COUNT);
        deps.add((i * 7 + 3) % BASE_COUNT);
        return deps;
    }

    /**
     * Every target whose output can change as a direct or transitive
     * consequence of an edit to one of {@code changed}, including the
     * changed targets themselves. This is the fast, graph-only answer the
     * release gate actually uses to pick tests; ReleaseGateSimulator diffs
     * it against real rebuilt output digests to check it is not just
     * plausible but exactly right.
     */
    public Set<Integer> transitiveImpact(Collection<Integer> changed) {
        Set<Integer> impacted = new HashSet<>(changed);
        Deque<Integer> frontier = new ArrayDeque<>(changed);
        while (!frontier.isEmpty()) {
            int t = frontier.poll();
            for (int dependent : dependents.get(t)) {
                if (impacted.add(dependent)) {
                    frontier.add(dependent);
                }
            }
        }
        return impacted;
    }
}
