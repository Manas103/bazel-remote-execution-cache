package com.manas.reapi.buildsim;

import java.util.Set;

/**
 * The release gate's actual selection step: given the set of build targets
 * an edit touched, which tests are even worth running. This never
 * executes anything; it is pure graph reachability over BuildGraph plus a
 * membership check over TestSuite, which is the whole point, selection
 * has to be cheap enough to run before any test does.
 */
public final class AffectedTestSelector {
    private AffectedTestSelector() {}

    public static Set<Integer> select(BuildGraph graph, TestSuite suite, Set<Integer> changedTargets) {
        Set<Integer> impacted = graph.transitiveImpact(changedTargets);
        return suite.testsTouching(impacted);
    }
}
