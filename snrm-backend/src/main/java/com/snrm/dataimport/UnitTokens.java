package com.snrm.dataimport;

import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeUnit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the tokens a spreadsheet actually contains — units first, and by now the two other scalar
 * vocabularies stage 1 has to recognise.
 *
 * <blockquote>"Unit values are case-insensitive and accept both singular and plural spellings plus
 * common abbreviations ({@code h}, {@code hr}, {@code hrs}, {@code hour}, {@code hours})."</blockquote>
 *
 * <p><strong>Why a token table rather than {@code TimeUnit.valueOf}.</strong> Nobody types
 * {@code HOUR} into a spreadsheet. They type {@code h}, or {@code hrs}, or {@code Hours}, and a rate
 * column often reads {@code /day} or {@code per week} because that is how the unit is written when it
 * is part of a heading. Refusing those would make the unit columns unusable in practice
 * and push every real file through the mapping step for no gain. What is <em>not</em> accepted is
 * anything ambiguous: {@code m} is rejected outright, because minute and month are both plausible and
 * guessing wrong silently rescales a duration by a factor of 43,200.
 *
 * <p>An unrecognised token is an error on its row, not a fallback to the period unit. The fallback
 * is for a unit column that is <em>absent</em> — the file never claimed a unit — whereas a
 * present-but-unreadable token means the user did claim one and it did not survive; substituting a
 * different unit for it would change the number's meaning without saying so.
 *
 * <p><strong>Two more vocabularies live here for the same reason.</strong> {@link #roundingPolicy}
 * and {@link #flag}, added with FR-30: they are token tables the importer needs, they follow
 * the identical discipline — lenient about the spellings a spreadsheet produces, and refusing rather
 * than guessing where two readings are plausible — and separate copies of that discipline would
 * eventually diverge from it. The class is named for what it started as.
 */
public final class UnitTokens {

    private static final Map<String, TimeUnit> TIME_UNITS = timeUnits();

    private static final Map<String, Boolean> FLAGS = flags();

    private static Map<String, TimeUnit> timeUnits() {
        Map<String, TimeUnit> tokens = new LinkedHashMap<>();
        put(tokens, TimeUnit.SECOND, "s", "sec", "secs", "second", "seconds");
        put(tokens, TimeUnit.MINUTE, "min", "mins", "minute", "minutes");
        put(tokens, TimeUnit.HOUR, "h", "hr", "hrs", "hour", "hours");
        put(tokens, TimeUnit.DAY, "d", "day", "days");
        put(tokens, TimeUnit.WEEK, "w", "wk", "wks", "week", "weeks");
        put(tokens, TimeUnit.MONTH, "mo", "mon", "mth", "mths", "month", "months");
        put(tokens, TimeUnit.YEAR, "y", "yr", "yrs", "year", "years");
        return tokens;
    }

    private static void put(Map<String, TimeUnit> tokens, TimeUnit unit, String... aliases) {
        tokens.put(unit.name().toLowerCase(Locale.ROOT), unit);
        for (String alias : aliases) {
            tokens.put(alias, unit);
        }
    }

    /**
     * The yes/no spellings a {@code caption_visible} cell may hold (FR-30).
     *
     * <p>Chosen the way the unit table is: what a spreadsheet actually produces, and nothing beyond
     * it. Excel writes {@code TRUE}/{@code FALSE} from a boolean cell, exports from other tools write
     * {@code 1}/{@code 0}, and a person filling the column in by hand writes {@code yes} or
     * {@code y}. {@code on}/{@code off} costs nothing and is how a checkbox reads in prose.
     *
     * <p>What is deliberately <em>not</em> here is anything that could be read two ways. {@code -}
     * and {@code x} are both used in real files to mean "yes, ticked" and to mean "nothing here", so
     * both are refused — the same rule that refuses {@code m} for a unit. An empty cell is not a
     * token at all: it is an <em>absent</em> flag, which is read as visible.
     */
    private static Map<String, Boolean> flags() {
        Map<String, Boolean> tokens = new LinkedHashMap<>();
        for (String yes : new String[] {"true", "t", "yes", "y", "1", "on"}) {
            tokens.put(yes, Boolean.TRUE);
        }
        for (String no : new String[] {"false", "f", "no", "n", "0", "off"}) {
            tokens.put(no, Boolean.FALSE);
        }
        return tokens;
    }

    private UnitTokens() {
    }

    /**
     * The unit a cell names, or empty if the token is not one this table knows.
     *
     * <p>Strips the denominator phrasing a rate column tends to carry — a leading {@code /} or
     * {@code per}, a trailing period — before matching, so {@code per week}, {@code /wk} and
     * {@code WEEK} are one answer.
     */
    public static Optional<TimeUnit> timeUnit(String raw) {
        String token = normalise(raw);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(TIME_UNITS.get(token));
    }

    /** The rounding policy a {@code network_meta} cell names. */
    public static Optional<RoundingPolicy> roundingPolicy(String raw) {
        String token = normalise(raw);
        for (RoundingPolicy policy : RoundingPolicy.values()) {
            if (policy.name().toLowerCase(Locale.ROOT).equals(token)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    /**
     * The boolean a {@code caption_visible} cell names, or empty if the token is not one this table
     * knows (FR-30).
     *
     * <p>Empty for an unreadable token as well as for an unknown one, and the caller reports it
     * rather than falling back: the fallback to <em>visible</em> is for a flag that is
     * <em>absent</em>, whereas a cell reading {@code maybe} is a claim that did not survive, and
     * showing a caption the file asked to hide is exactly the wrong way to guess.
     */
    public static Optional<Boolean> flag(String raw) {
        String token = normalise(raw);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(FLAGS.get(token));
    }

    /**
     * Every token a unit column accepts, for the wizard's tooltip and the OpenAPI description.
     *
     * <p>Deliberately the full list rather than a summary: the point of documenting it is that a user
     * looking at a rejected cell can see which spelling to use.
     */
    public static String acceptedTimeUnitTokens() {
        return String.join(", ", TIME_UNITS.keySet());
    }

    /** Every token a flag column accepts, for the same reason {@link #acceptedTimeUnitTokens} is. */
    public static String acceptedFlagTokens() {
        return String.join(", ", FLAGS.keySet());
    }

    /**
     * Lower-cases, trims, and drops the {@code per} / {@code /} of a denominator.
     *
     * <p>Also drops an interior space so {@code per  week} normalises; nothing here collapses two
     * distinct words into one token, because no accepted token contains a space.
     */
    private static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String token = raw.trim().toLowerCase(Locale.ROOT);
        if (token.startsWith("/")) {
            token = token.substring(1);
        } else if (token.startsWith("per ") || token.startsWith("per/")) {
            token = token.substring(4);
        } else if (token.equals("per")) {
            return "";
        }
        // A trailing full stop is how an abbreviation is written in prose — "hrs." — and carries no
        // meaning of its own.
        while (token.endsWith(".")) {
            token = token.substring(0, token.length() - 1);
        }
        return token.replace(" ", "");
    }
}
