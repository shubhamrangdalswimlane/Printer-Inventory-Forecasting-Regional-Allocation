package com.pifa.golive;

import com.pifa.master.ProductNames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Suggests master products for a product name that did not match exactly (Phase 6 "matching report").
 *
 * <p>Two similarity measures are combined, because real-world name drift comes in two shapes:
 * <ul>
 *   <li><b>Typos and punctuation</b> ({@code "Mono Laser M-100"} vs {@code "Mono Laser M100"}): edit distance on the
 *       normalised text with spaces and punctuation removed;</li>
 *   <li><b>Reordered or extra words</b> ({@code "M100 Mono Laser Printer"}): word overlap (Jaccard).</li>
 * </ul>
 * The score is the better of the two, between 0 (nothing alike) and 1 (identical). Only suggestions scoring at
 * least {@link #MIN_SCORE} are returned. This is advice for a human: the Admin decides and adds an alias.
 */
public final class NameMatcher {

    /** Suggestions below this similarity are noise and are not shown. */
    public static final double MIN_SCORE = 0.5;

    /**
     * One suggested master product.
     *
     * @param productId the product
     * @param name      its display name
     * @param score     similarity 0..1 (1 = identical after normalisation), rounded to 2 decimals
     */
    public record Suggestion(UUID productId, String name, double score) {}

    /** Precomputed forms of one master product, so each lookup does not redo the work. */
    private record Candidate(UUID id, String name, String compact, Set<String> words) {}

    private final List<Candidate> candidates = new ArrayList<>();

    /**
     * @param products master products: id to display name (normally the active products)
     */
    public NameMatcher(Map<UUID, String> products) {
        products.forEach((id, name) -> candidates.add(new Candidate(id, name, compact(name), words(name))));
    }

    /**
     * Finds the closest master products to a name.
     *
     * @param name  the unmatched name as it appears in the file
     * @param limit maximum number of suggestions
     * @return up to {@code limit} suggestions, best first; empty when nothing is similar enough
     */
    public List<Suggestion> suggest(String name, int limit) {
        String c = compact(name);
        Set<String> w = words(name);
        List<Suggestion> out = new ArrayList<>();
        for (Candidate cand : candidates) {
            double score = Math.max(jaccard(w, cand.words()), editSimilarity(c, cand.compact()));
            if (score >= MIN_SCORE) out.add(new Suggestion(cand.id(), cand.name(), Math.round(score * 100) / 100.0));
        }
        out.sort(Comparator.comparingDouble(Suggestion::score).reversed().thenComparing(Suggestion::name));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** @return the normalised name with everything except letters and digits removed ("Mono-Laser M100" -> "monolaserm100") */
    static String compact(String s) {
        return ProductNames.normalize(s).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** @return the normalised name split into words on anything that is not a letter or digit */
    static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        for (String w : ProductNames.normalize(s).split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** @return |A ∩ B| / |A ∪ B|, or 0 when both are empty */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    /**
     * @return 1 - levenshtein(a, b) / max(|a|, |b|); 0 when either is empty. Skips the O(n·m) computation when
     *         the length difference alone already puts the result under {@link #MIN_SCORE}.
     */
    static double editSimilarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (a.isEmpty() || b.isEmpty()) return 0;
        if ((double) Math.abs(a.length() - b.length()) / max > 1 - MIN_SCORE) return 0;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return 1 - (double) prev[b.length()] / max;
    }
}
