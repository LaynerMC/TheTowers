package com.thetowers.leveling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Number formatting for the leveling system: the "grouped" form
 * ({@code 1,340}) and the "abbreviated short" form ({@code 1,3k}).
 *
 * <p>Both the thousands separator and the short-form decimal separator are
 * configurable, so the same value can be shown as {@code 1,340} and
 * {@code 1,3k} (comma in both roles, the project's convention) or as
 * {@code 1.340} and {@code 1.3k} depending on the server's locale.
 *
 * <p>Abbreviations are a list of (threshold, suffix) pairs checked from the
 * largest down: the first threshold the value meets wins. Below the smallest
 * threshold the plain grouped form is returned. Values are whole numbers
 * throughout: the short form's single decimal digit is the only fraction the
 * leveling system ever shows.
 */
final class LevelingFormatter {

    private final String thousandsSeparator;
    private final String shortDecimalSeparator;
    private final int shortDecimals;
    private final List<Abbreviation> abbreviations;

    /** A single abbreviation rule, e.g. threshold 1000, suffix "k". */
    static final class Abbreviation implements Comparable<Abbreviation> {
        final long threshold;
        final String suffix;

        Abbreviation(long threshold, String suffix) {
            this.threshold = threshold;
            this.suffix = suffix;
        }

        @Override
        public int compareTo(Abbreviation other) {
            return Long.compare(other.threshold, this.threshold); // descending
        }
    }

    LevelingFormatter(String thousandsSeparator, String shortDecimalSeparator,
            int shortDecimals, List<Abbreviation> abbreviations) {
        this.thousandsSeparator = thousandsSeparator == null ? "," : thousandsSeparator;
        this.shortDecimalSeparator = shortDecimalSeparator == null ? "," : shortDecimalSeparator;
        this.shortDecimals = Math.max(0, shortDecimals);
        List<Abbreviation> sorted = new ArrayList<Abbreviation>(
                abbreviations == null ? Collections.<Abbreviation>emptyList() : abbreviations);
        Collections.sort(sorted);
        this.abbreviations = sorted;
    }

    /** The grouped form, e.g. {@code 1,340}. Negative values keep their sign. */
    String grouped(long value) {
        return insertThousands(Math.abs(value), value < 0);
    }

    /** The abbreviated short form, e.g. {@code 1,3k}. Falls back to grouped below the smallest threshold. */
    String shortForm(long value) {
        long absolute = Math.abs(value);
        for (Abbreviation ab : abbreviations) {
            if (absolute >= ab.threshold) {
                return formatAbbreviated(absolute, ab, value < 0);
            }
        }
        return grouped(value);
    }

    private String formatAbbreviated(long absolute, Abbreviation ab, boolean negative) {
        // Divide and keep shortDecimals digits, rounding half-up.
        double scaled = (double) absolute / (double) ab.threshold;
        double factor = Math.pow(10d, shortDecimals);
        double rounded = Math.round(scaled * factor) / factor;
        if (shortDecimals <= 0) {
            return (negative ? "-" : "") + (long) rounded + ab.suffix;
        }
        String text = String.format(Locale.US, "%." + shortDecimals + "f", rounded);
        // Locale.US gives a '.' decimal point: swap it for the configured separator.
        if (!".".equals(shortDecimalSeparator)) {
            text = text.replace(".", shortDecimalSeparator);
        }
        return (negative ? "-" : "") + text + ab.suffix;
    }

    private String insertThousands(long absolute, boolean negative) {
        String digits = Long.toString(absolute);
        StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3);
        int length = digits.length();
        for (int i = 0; i < length; i++) {
            if (i > 0 && (length - i) % 3 == 0 && thousandsSeparator.length() > 0) {
                out.append(thousandsSeparator);
            }
            out.append(digits.charAt(i));
        }
        return (negative ? "-" : "") + out.toString();
    }
}
