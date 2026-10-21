package com.pifa.master;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalises {@code Product_name} text so spelling-level differences still match the master
 * (architecture.md section 5.3): Unicode NFKC, trim, collapse repeated whitespace, case-fold.
 */
public final class ProductNames {

    private ProductNames() {}

    /**
     * @param raw the text exactly as typed in a file or master record; may be null
     * @return the normalised key, e.g. {@code "  Mono  Laser M100 "} becomes {@code "mono laser m100"};
     *         an empty string for null input
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        // NFKC also turns non-breaking spaces and full-width letters into their plain forms.
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
        return s.toLowerCase(Locale.ROOT);
    }
}
