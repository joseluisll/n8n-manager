package io.github.joseluisll.n8nmanager.deps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionExpressionTest {

    @Test
    void minimumBoundAcceptsEqualAndGreater() {
        VersionExpression expr = VersionExpression.parse(">=24.0.0");
        assertTrue(expr.isSatisfiedBy("24.0.0"));
        assertTrue(expr.isSatisfiedBy("v24.22.3"));
        assertTrue(expr.isSatisfiedBy("25.1.0"));
        assertFalse(expr.isSatisfiedBy("22.22.1"));
        assertFalse(expr.isSatisfiedBy("23.9.9"));
    }

    @Test
    void minimumBoundWithMissingComponentsCountsZero() {
        assertTrue(VersionExpression.parse(">=21").isSatisfiedBy("21.0.13.1"));
        assertFalse(VersionExpression.parse(">=21").isSatisfiedBy("17.0.20.1"));
        assertTrue(VersionExpression.parse(">=3.9.0").isSatisfiedBy("3.9.16"));
        assertFalse(VersionExpression.parse(">=3.9.0").isSatisfiedBy("3.8.9"));
    }

    @Test
    void exactExpressionDoesNotMatchLongerVersion() {
        assertFalse(VersionExpression.parse("21").isSatisfiedBy("21.0.13.1"));
        assertTrue(VersionExpression.parse("21").isSatisfiedBy("21"));
        assertTrue(VersionExpression.parse("3.9.16").isSatisfiedBy("3.9.16"));
        assertFalse(VersionExpression.parse("3.9.16").isSatisfiedBy("3.9.16.1"));
    }

    @Test
    void rejectsNonNumericAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> VersionExpression.parse(""));
        assertThrows(IllegalArgumentException.class, () -> VersionExpression.parse(">=abc"));
        assertThrows(IllegalArgumentException.class, () -> VersionExpression.parse("latest"));
    }

    @Test
    void compareOrdersSemantically() {
        assertTrue(VersionExpression.compare("24.22.3", "24.9.0") > 0);
        assertEquals(0, VersionExpression.compare("3.9.16", "3.9.16"));
        assertTrue(VersionExpression.compare("22.22.1", "24.0.0") < 0);
        assertTrue(VersionExpression.compare("21.0.13.1", "21.0.13") > 0);
    }
}
