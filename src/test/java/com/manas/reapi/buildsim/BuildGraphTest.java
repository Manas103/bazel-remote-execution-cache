package com.manas.reapi.buildsim;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BuildGraphTest {

    @Test
    void aTargetWithNoDependentsImpactsOnlyItself() {
        BuildGraph graph = new BuildGraph();
        // No target ever appears in another target's depsOf() output unless
        // it is one of the first BASE_COUNT targets, so a high-id target is
        // guaranteed to be a leaf.
        int leaf = BuildGraph.NUM_TARGETS - 1;
        assertEquals(Set.of(leaf), graph.transitiveImpact(List.of(leaf)));
    }

    @Test
    void impactOfABaseTargetMatchesABruteForceScanOfDepsOf() {
        BuildGraph graph = new BuildGraph();
        int baseTarget = 5;

        Set<Integer> expected = new HashSet<>();
        expected.add(baseTarget);
        for (int j = 0; j < BuildGraph.NUM_TARGETS; j++) {
            if (BuildGraph.depsOf(j).contains(baseTarget)) {
                expected.add(j);
            }
        }

        assertEquals(expected, graph.transitiveImpact(List.of(baseTarget)));
        assertTrue(expected.size() > 1, "a base target should have real dependents to make this test meaningful");
    }

    @Test
    void impactOfMultipleTargetsIsTheUnionOfEachOnesImpact() {
        BuildGraph graph = new BuildGraph();
        Set<Integer> a = graph.transitiveImpact(List.of(3));
        Set<Integer> b = graph.transitiveImpact(List.of(9));
        Set<Integer> union = new HashSet<>(a);
        union.addAll(b);

        assertEquals(union, graph.transitiveImpact(List.of(3, 9)));
    }

    @Test
    void everyNonBaseTargetDependsOnExactlyTwoBaseTargets() {
        for (int i = BuildGraph.BASE_COUNT; i < BuildGraph.NUM_TARGETS; i++) {
            List<Integer> deps = BuildGraph.depsOf(i);
            assertEquals(2, deps.size());
            for (int d : deps) {
                assertTrue(d < BuildGraph.BASE_COUNT, "dependency " + d + " of target " + i + " should be a base target");
            }
        }
    }
}
