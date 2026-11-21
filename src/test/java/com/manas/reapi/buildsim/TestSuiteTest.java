package com.manas.reapi.buildsim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TestSuiteTest {

    @Test
    void sameSeedProducesTheIdenticalSuite() {
        TestSuite a = new TestSuite(42L);
        TestSuite b = new TestSuite(42L);
        for (int t = 0; t < TestSuite.NUM_TESTS; t++) {
            assertEquals(a.exercisedTargets(t), b.exercisedTargets(t));
        }
    }

    @Test
    void everyTestExercisesOneToThreeInRangeTargets() {
        TestSuite suite = new TestSuite(1L);
        for (int t = 0; t < TestSuite.NUM_TESTS; t++) {
            List<Integer> targets = suite.exercisedTargets(t);
            assertTrue(targets.size() >= 1 && targets.size() <= 3);
            for (int target : targets) {
                assertTrue(target >= 0 && target < BuildGraph.NUM_TARGETS);
            }
        }
    }

    @Test
    void testsTouchingIsExactlyTheTestsWhoseExercisedSetIntersectsTheGivenTargets() {
        TestSuite suite = new TestSuite(99L);
        int probeTarget = suite.exercisedTargets(0).get(0);
        Set<Integer> hit = suite.testsTouching(Set.of(probeTarget));
        assertTrue(hit.contains(0));
        for (int t = 0; t < TestSuite.NUM_TESTS; t++) {
            boolean shouldHit = suite.exercisedTargets(t).contains(probeTarget);
            assertEquals(shouldHit, hit.contains(t), "mismatch for test " + t);
        }
    }
}
