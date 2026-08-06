package com.wendy.paygateway.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyUtilTest {

    @Test
    @DisplayName("Addition keeps precision: 0.1 + 0.2 must equal 0.30")
    void addKeepsPrecision() {
        assertEquals(new BigDecimal("0.30"), MoneyUtil.add(new BigDecimal("0.1"), new BigDecimal("0.2")));
    }

    @Test
    @DisplayName("Always two decimals, rounded HALF_UP")
    void scaleHalfUp() {
        assertEquals(new BigDecimal("1.24"), MoneyUtil.scale(new BigDecimal("1.235")));
        assertEquals(new BigDecimal("1.23"), MoneyUtil.scale(new BigDecimal("1.234")));
    }

    @Test
    @DisplayName("Yuan/cent conversion: 99.90 yuan = 9990 cents")
    void yuanCentConversion() {
        assertEquals(9990L, MoneyUtil.toCent(new BigDecimal("99.90")));
        assertEquals(new BigDecimal("99.90"), MoneyUtil.fromCent(9990L));
    }

    @Test
    @DisplayName("Comparison uses compareTo, so 1.0 and 1.00 are equal")
    void equalsIgnoresScale() {
        assertTrue(MoneyUtil.eq(new BigDecimal("1.0"), new BigDecimal("1.00")));
        assertTrue(MoneyUtil.gt(new BigDecimal("1.01"), new BigDecimal("1.00")));
        assertFalse(MoneyUtil.gt(new BigDecimal("1.00"), new BigDecimal("1.00")));
        assertTrue(MoneyUtil.gte(new BigDecimal("1.00"), new BigDecimal("1.00")));
    }

    @Test
    @DisplayName("Refundable amount = paid - already refunded")
    void refundable() {
        assertEquals(new BigDecimal("70.00"),
                MoneyUtil.subtract(new BigDecimal("100.00"), new BigDecimal("30.00")));
    }
}
