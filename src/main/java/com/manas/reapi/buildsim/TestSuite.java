package com.manas.reapi.buildsim;

import java.util.*;

/**
 * A synthetic suite of integration tests, each exercising one or more
 * build targets. Membership is generated once, deterministically, from a
 * fixed seed, so every run of the release gate selects against the exact
 * same suite.
 */
public final class TestSuite {
    public static final int NUM_TESTS = 300;

    private final List<List<Integer>> exercisedTargets; // exercisedTargets.get(testId)

    public TestSuite(long seed) {
        Random rng = new Random(seed);
        exercisedTargets = new ArrayList<>(NUM_TESTS);
        for (int t = 0; t < NUM_TESTS; t++) {
            int count = 1 + rng.nextInt(3); // each test exercises 1 to 3 build targets
            List<Integer> targets = new ArrayList<>(count);
            for (int c = 0; c < count; c++) {
                targets.add(rng.nextInt(BuildGraph.NUM_TARGETS));
            }
            exercisedTargets.add(targets);
        }
    }

    public int size() {
        return NUM_TESTS;
    }

    public List<Integer> exercisedTargets(int testId) {
        return exercisedTargets.get(testId);
    }

    /** Tests that exercise at least one target in {@code impactedTargets}. */
    public Set<Integer> testsTouching(Set<Integer> impactedTargets) {
        Set<Integer> hit = new HashSet<>();
        for (int t = 0; t < NUM_TESTS; t++) {
            for (int target : exercisedTargets.get(t)) {
                if (impactedTargets.contains(target)) {
                    hit.add(t);
                    break;
                }
            }
        }
        return hit;
    }
}
