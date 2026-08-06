package com.wendy.paygateway.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money helper: every amount on the money path is a BigDecimal, normalised to 2 decimals with
 * HALF_UP rounding, so double precision loss can never happen.
 *
 * <p>Yuan &lt;-&gt; cent conversion for channel APIs is funnelled through here too, so nobody
 * hand-writes {@code * 100} and gets it wrong.
 */
public final class MoneyUtil {

    public static final int SCALE = 2;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private MoneyUtil() {
    }

    /** Normalise to 2 decimal places. */
    public static BigDecimal scale(BigDecimal value) {
        return value == null ? ZERO : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(String value) {
        return scale(new BigDecimal(value));
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return scale(nvl(a).add(nvl(b)));
    }

    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return scale(nvl(a).subtract(nvl(b)));
    }

    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return scale(nvl(a).multiply(nvl(b)));
    }

    /** Yuan -&gt; cents; channel APIs almost always take cents. */
    public static long toCent(BigDecimal yuan) {
        return scale(yuan).multiply(HUNDRED).longValueExact();
    }

    /** Cents -&gt; yuan. */
    public static BigDecimal fromCent(long cent) {
        return scale(BigDecimal.valueOf(cent).divide(HUNDRED, SCALE, RoundingMode.HALF_UP));
    }

    /** a &gt; b. */
    public static boolean gt(BigDecimal a, BigDecimal b) {
        return nvl(a).compareTo(nvl(b)) > 0;
    }

    /** a &gt;= b. */
    public static boolean gte(BigDecimal a, BigDecimal b) {
        return nvl(a).compareTo(nvl(b)) >= 0;
    }

    /** a &lt; b. */
    public static boolean lt(BigDecimal a, BigDecimal b) {
        return nvl(a).compareTo(nvl(b)) < 0;
    }

    /** Numeric equality, ignoring scale differences (1.0 equals 1.00). */
    public static boolean eq(BigDecimal a, BigDecimal b) {
        return nvl(a).compareTo(nvl(b)) == 0;
    }

    public static boolean isPositive(BigDecimal a) {
        return nvl(a).compareTo(BigDecimal.ZERO) > 0;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
