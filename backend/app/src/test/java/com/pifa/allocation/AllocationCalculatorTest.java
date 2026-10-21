package com.pifa.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Tests of {@link AllocationCalculator}: the PRD examples as fixed cases, and property-based tests
 * (jqwik generates thousands of random inputs) for the invariants of architecture.md 7.4:
 * total never above the allocatable stock, no negative quantity, deterministic result.
 */
class AllocationCalculatorTest {

    private static final String[] R = {"NORTH", "EAST", "WEST", "SOUTH"};
    private static final int[] EQUAL = {25, 25, 25, 25};

    /** PRD worked example: Model A, 100 units, constrained -> 25/25/25/25. */
    @Example
    void workedExampleSplitsEqually() {
        var r = AllocationCalculator.weightedBaseline(100, new int[]{40, 35, 30, 25}, EQUAL, R);
        assertThat(r.alloc()).containsExactly(25, 25, 25, 25);
        assertThat(r.explanation()).isEqualTo("Constrained: weighted baseline 25/25/25/25");
    }

    /** PRD BR-AL-05: 102 units -> 25 each + 1 North + 1 East (the two largest unmet demands). */
    @Example
    void roundingRemainderGoesToLargestUnmetDemand() {
        var r = AllocationCalculator.weightedBaseline(102, new int[]{40, 35, 30, 25}, EQUAL, R);
        assertThat(r.alloc()).containsExactly(26, 26, 25, 25);
        assertThat(r.explanation()).endsWith("remainder 2 -> NORTH, EAST");
    }

    /** Ties in unmet demand are broken by region display order (N, E, W, S). */
    @Example
    void tiesFollowRegionOrder() {
        var r = AllocationCalculator.weightedBaseline(101, new int[]{50, 50, 50, 50}, EQUAL, R);
        assertThat(r.alloc()).containsExactly(26, 25, 25, 25);
    }

    /** Custom weights 30/30/20/20 (an Admin parameter change). */
    @Example
    void customWeights() {
        var r = AllocationCalculator.weightedBaseline(100, new int[]{40, 35, 30, 25}, new int[]{30, 30, 20, 20}, R);
        assertThat(r.alloc()).containsExactly(30, 30, 20, 20);
    }

    /** Sufficient stock: full demand, the rest unallocated (BR-AL-03). */
    @Example
    void sufficientGetsFullDemand() {
        var r = AllocationCalculator.fullDemand(500, new int[]{40, 35, 30, 25});
        assertThat(r.alloc()).containsExactly(40, 35, 30, 25);
        assertThat(r.unallocated()).isEqualTo(370);
    }

    /** Cap at demand (BR-AL-06): South wants only 10, its excess goes to the regions still short. */
    @Example
    void capAtDemandRedistributesExcess() {
        var r = AllocationCalculator.weightedBaselineCapped(100, new int[]{60, 40, 30, 10}, EQUAL, R);
        assertThat(r.alloc()[3]).isEqualTo(10);
        assertThat(Arrays.stream(r.alloc()).sum()).isEqualTo(100);
        for (int i = 0; i < 4; i++) assertThat(r.alloc()[i]).isLessThanOrEqualTo(new int[]{60, 40, 30, 10}[i]);
    }

    /** Zero stock gives zero to everyone. */
    @Example
    void zeroStock() {
        assertThat(AllocationCalculator.weightedBaseline(0, new int[]{5, 5, 5, 5}, EQUAL, R).alloc()).containsExactly(0, 0, 0, 0);
    }

    // ------------------------------------------------------------ properties

    @Provide
    Arbitrary<int[]> demand() {
        return Arbitraries.integers().between(0, 50_000).array(int[].class).ofSize(4);
    }

    /** Four weights 0..100 that always total 100, from three random cut points. */
    @Provide
    Arbitrary<int[]> weights() {
        return Arbitraries.integers().between(0, 100).list().ofSize(3).map(cuts -> {
            List<Integer> c = cuts.stream().sorted().toList();
            return new int[]{c.get(0), c.get(1) - c.get(0), c.get(2) - c.get(1), 100 - c.get(2)};
        });
    }

    /** Weighted baseline: total is exactly the allocatable stock, nothing negative, deterministic. */
    @Property(tries = 3000)
    void baselineSplitsExactly(@ForAll @IntRange(min = 0, max = 1_000_000) int allocatable, @ForAll("demand") int[] demand, @ForAll("weights") int[] weights) {
        var a = AllocationCalculator.weightedBaseline(allocatable, demand, weights, R);
        var b = AllocationCalculator.weightedBaseline(allocatable, demand, weights, R);
        assertThat(Arrays.stream(a.alloc()).sum()).isEqualTo(allocatable);
        assertThat(Arrays.stream(a.alloc()).allMatch(q -> q >= 0)).isTrue();
        assertThat(a.alloc()).containsExactly(b.alloc());
    }

    /** Capped: total equals the stock (demand exceeds it), no region above its demand, nothing negative, deterministic. */
    @Property(tries = 3000)
    void cappedNeverExceedsDemand(@ForAll("demand") int[] demand, @ForAll("weights") int[] weights, @ForAll @IntRange(min = 0, max = 99) int pct) {
        int total = Arrays.stream(demand).sum();
        Assume.that(total > 0);
        int allocatable = (int) ((long) total * pct / 100); // constrained: always below total demand
        var a = AllocationCalculator.weightedBaselineCapped(allocatable, demand, weights, R);
        var b = AllocationCalculator.weightedBaselineCapped(allocatable, demand, weights, R);
        assertThat(Arrays.stream(a.alloc()).sum()).isEqualTo(allocatable);
        for (int i = 0; i < 4; i++) {
            assertThat(a.alloc()[i]).isBetween(0, demand[i]);
        }
        assertThat(a.alloc()).containsExactly(b.alloc());
    }

    /** Full demand: never negative; unallocated is what is left (0 if demand is larger). */
    @Property(tries = 1000)
    void fullDemandLeavesTheRestUnallocated(@ForAll("demand") int[] demand, @ForAll @IntRange(min = 0, max = 300_000) int allocatable) {
        var a = AllocationCalculator.fullDemand(allocatable, demand);
        assertThat(a.alloc()).containsExactly(demand);
        assertThat(a.unallocated()).isEqualTo(Math.max(allocatable - Arrays.stream(demand).sum(), 0));
    }

    /** Sales-share weights (Phase 7): worked examples. */
    @Example
    void salesShareExamples() {
        assertThat(AllocationCalculator.sharePercent(new long[]{50, 30, 20, 0})).containsExactly(50, 30, 20, 0);
        assertThat(AllocationCalculator.sharePercent(new long[]{1, 1, 1, 0})).containsExactly(34, 33, 33, 0);
        assertThat(AllocationCalculator.sharePercent(new long[]{0, 0, 0, 0})).isNull();
    }

    /** Sales-share weights always total exactly 100, and a region with no sales never gets a weight. */
    @Property
    void salesShareTotals100(@ForAll @IntRange(min = 0, max = 100000) int a, @ForAll @IntRange(min = 0, max = 100000) int b,
                             @ForAll @IntRange(min = 0, max = 100000) int c, @ForAll @IntRange(min = 0, max = 100000) int d) {
        long[] sales = {a, b, c, d};
        Assume.that(a + b + c + d > 0);
        int[] w = AllocationCalculator.sharePercent(sales);
        assertThat(Arrays.stream(w).sum()).isEqualTo(100);
        for (int i = 0; i < 4; i++) if (sales[i] == 0) assertThat(w[i]).isZero();
    }
}
