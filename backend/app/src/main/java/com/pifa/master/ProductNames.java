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

    /** Allowed product codes after normalisation: letters, digits, dot, dash, underscore; 1..50 characters. */
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("^[A-Z0-9._-]{1,50}$");

    /**
     * Normalises a {@code Product_code} (Phase 7): NFKC, trim, upper-case. Codes are identifiers, so inner spaces are
     * not collapsed: {@code "prn m100"} is simply invalid.
     *
     * @param raw the code as typed; may be null
     * @return the normalised code, or "" for null/blank input
     */
    public static String normalizeCode(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().toUpperCase(Locale.ROOT);
    }

    /** @param code a normalised code @return true if it is a valid code (see {@link #CODE}) */
    public static boolean isValidCode(String code) {
        return CODE.matcher(code).matches();
    }
}
