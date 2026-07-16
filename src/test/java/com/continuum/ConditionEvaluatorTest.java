package com.continuum;

import com.continuum.engine.ConditionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit-executes the CONDITIONAL expression evaluator across its grammar. */
class ConditionEvaluatorTest {

    private final ConditionEvaluator ev = new ConditionEvaluator();

    @Test
    void numeric_comparisons() {
        Map<String, Object> ctx = Map.of("amount", 150);
        assertTrue(ev.eval("amount > 100", ctx));
        assertFalse(ev.eval("amount < 100", ctx));
        assertTrue(ev.eval("amount >= 150", ctx));
        assertTrue(ev.eval("amount <= 150", ctx));
        assertFalse(ev.eval("amount == 100", ctx));
        assertTrue(ev.eval("amount != 100", ctx));
    }

    @Test
    void string_equality() {
        Map<String, Object> ctx = Map.of("status", "OPEN");
        assertTrue(ev.eval("status == \"OPEN\"", ctx));
        assertFalse(ev.eval("status == \"CLOSED\"", ctx));
        assertTrue(ev.eval("status != \"CLOSED\"", ctx));
    }

    @Test
    void truthy_bareword() {
        assertTrue(ev.eval("flag", Map.of("flag", true)));
        assertFalse(ev.eval("flag", Map.of("flag", false)));
        assertFalse(ev.eval("missing", Map.of()));
        assertTrue(ev.eval("n", Map.of("n", 5)));
        assertFalse(ev.eval("n", Map.of("n", 0)));
    }

    @Test
    void string_number_coercion() {
        assertTrue(ev.eval("amount > 100", Map.of("amount", "150")));
    }

    @Test
    void blank_condition_is_false() {
        assertFalse(ev.eval("", Map.of()));
        assertFalse(ev.eval(null, Map.of()));
    }
}
