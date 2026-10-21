package com.pifa.allocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The arithmetic of the allocation (PRD BR-AL-03..06, architecture.md 7.4). The Drools rules decide
 * <em>which</em> strategy applies to a product; this class computes the numbers. It is pure (no I/O,
 * no state), works only with integers where it matters, and is property-tested for three invariants:
 * the total never exceeds the allocatable stock, no quantity is negative, and the result is
 * deterministic.
 *
 * <p>All arrays are indexed by region in display order (NORTH, EAST, WEST, SOUTH); ties are always
 * broken by that order.
 */
public final class AllocationCalculator {

    private AllocationCalculator() {}

    /**
     * Result for one product.
     *
     * @param alloc       quantity per region (same order as the input arrays)
     * @param unallocated stock left over (sufficient products only; 0 otherwise)
     * @param explanation one line a person can read, e.g. "Constrained: weighted baseline 25/25/25/25; remainder 2 -> NORTH, EAST"
     */
    public record Result(int[] alloc, int unallocated, String explanation) {}

    /**
     * Sufficient stock: every region gets its full demand; the rest is shown as unallocated (BR-AL-03).
     *
     * @param allocatable units available to allocate (&gt;= the total demand for a sufficient product)
     * @param demand      revised demand per region
     * @return the allocation
     */
    public static Result fullDemand(int allocatable, int[] demand) {
        int[] alloc = demand.clone();
        int total = Arrays.stream(alloc).sum();
        int left = Math.max(allocatable - total, 0);
        return new Result(alloc, left, "Sufficient: full demand" + (left > 0 ? "; " + left + " unallocated" : ""));
    }

    /**
     * Turns sales per region into whole-percent weights totalling exactly 100 (largest-remainder rounding, ties to
     * the earlier region), for the SALES_SHARE weight mode.
     *
     * <p>Example: sales 50/30/20/0 give 50/30/20/0; sales 1/1/1/0 give 34/33/33/0.
     *
     * @param sales units sold per region (&gt;= 0), in region order
     * @return weights in percent, or null when there were no sales at all (no basis for a share)
     */
    public static int[] sharePercent(long[] sales) {
        long total = Arrays.stream(sales).sum();
        if (total <= 0) return null;
        int n = sales.length;
        int[] w = new int[n];
        long[] rem = new long[n];
        int given = 0;
        for (int i = 0; i < n; i++) {
            w[i] = (int) (sales[i] * 100 / total);
            rem[i] = sales[i] * 100 % total;
            given += w[i];
        }
        for (int k = 0; k < 100 - given; k++) {
            int best = 0;
            for (int i = 1; i < n; i++) if (rem[i] > rem[best]) best = i;
            w[best]++;
            rem[best] = -1; // each region gets at most one extra point
        }
        return w;
    }

    /**
     * Constrained stock, weighted baseline (BR-AL-04, BR-AL-05): each region gets
     * {@code floor(allocatable x weight%)}; the units left by rounding go one at a time to the regions
     * with the largest unmet demand, ties broken by region order.
     *
     * <p>Example from the PRD: 100 units, weights 25 each: 25/25/25/25. 102 units: 25 each plus one for
     * each of the two regions with the most unmet demand.
     *
     * @param allocatable units to share (&gt;= 0)
     * @param demand      revised demand per region
     * @param weightsPct  weight per region in percent, totalling 100
     * @param regions     region codes, for the explanation
     * @return the allocation; its total always equals {@code allocatable}
     */
    public static Result weightedBaseline(int allocatable, int[] demand, int[] weightsPct, String[] regions) {
        int n = demand.length;
        int[] alloc = new int[n];
        for (int i = 0; i < n; i++) alloc[i] = (int) ((long) allocatable * weightsPct[i] / 100);
        int remainder = allocatable - Arrays.stream(alloc).sum();
        List<String> got = distribute(alloc, remainder, demand, regions, false);
        String weights = String.join("/", IntStream.of(weightsPct).mapToObj(String::valueOf).toList());
        return new Result(alloc, 0, "Constrained: weighted baseline " + weights + (got.isEmpty() ? "" : "; remainder " + got.size() + " -> " + String.join(", ", got)));
    }

    /**
     * Constrained stock with cap-at-demand (BR-AL-06): start from the weighted shares, cap each region at
     * its demand, share the excess among regions that still have unmet demand in proportion to that
     * unmet demand (repeated until nothing is left or every region is satisfied), then round down and
     * hand out the rounding remainder by unmet demand.
     *
     * @param allocatable units to share (&gt;= 0)
     * @param demand      revised demand per region
     * @param weightsPct  weight per region in percent, totalling 100
     * @param regions     region codes, for the explanation
     * @return the allocation; no region exceeds its demand; the total is min(allocatable, total demand)
     */
    public static Result weightedBaselineCapped(int allocatable, int[] demand, int[] weightsPct, String[] regions) {
        int n = demand.length;
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = Math.min(allocatable * weightsPct[i] / 100.0, demand[i]);
        for (int round = 0; round < n + 1; round++) {
            double excess = allocatable - Arrays.stream(a).sum();
            double unmetTotal = 0;
            for (int i = 0; i < n; i++) unmetTotal += Math.max(demand[i] - a[i], 0);
            if (excess < 1e-9 || unmetTotal < 1e-9) break;
            for (int i = 0; i < n; i++) {
                double unmet = Math.max(demand[i] - a[i], 0);
                a[i] += Math.min(excess * unmet / unmetTotal, unmet);
            }
        }
        int[] alloc = new int[n];
        for (int i = 0; i < n; i++) alloc[i] = (int) Math.floor(a[i] + 1e-9);
        int target = Math.min(allocatable, Arrays.stream(demand).sum());
        int remainder = target - Arrays.stream(alloc).sum();
        List<String> got = distribute(alloc, remainder, demand, regions, true);
        String weights = String.join("/", IntStream.of(weightsPct).mapToObj(String::valueOf).toList());
        return new Result(alloc, 0, "Constrained: weighted baseline " + weights + ", capped at demand"
            + (got.isEmpty() ? "" : "; remainder " + got.size() + " -> " + String.join(", ", got)));
    }

    /**
     * Hands out {@code remainder} units one at a time, in order of largest unmet demand
     * ({@code demand - alloc}), ties by region order; repeats the order if needed.
     *
     * @param alloc      allocation to increase in place
     * @param remainder  units to hand out (0 or more)
     * @param demand     demand per region
     * @param regions    region codes
     * @param capAtDemand if true, a region whose allocation already equals its demand gets nothing more
     * @return region codes that received a unit, in order (for the explanation)
     */
    static List<String> distribute(int[] alloc, int remainder, int[] demand, String[] regions, boolean capAtDemand) {
        List<String> got = new ArrayList<>();
        int n = alloc.length;
        while (remainder > 0) {
            Integer[] order = IntStream.range(0, n).boxed().toArray(Integer[]::new);
            Arrays.sort(order, Comparator.<Integer>comparingInt(i -> -(demand[i] - alloc[i])).thenComparingInt(i -> i));
            boolean gaveAny = false;
            for (int i : order) {
                if (remainder == 0) break;
                if (capAtDemand && alloc[i] >= demand[i]) continue;
                alloc[i]++;
                remainder--;
                got.add(regions[i]);
                gaveAny = true;
            }
            if (!gaveAny) break; // capped and everyone is satisfied: the rest stays unallocated
        }
        return got;
    }
}
