package com.continuum.engine;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tiny boolean expression evaluator for CONDITIONAL steps (spec §3.1.1). Grammar:
 *
 *   expr := IDENT                       // truthy check on a context value
 *         | IDENT OP VALUE              // comparison
 *   OP    := == | != | > | < | >= | <=
 *   VALUE := number | true | false | "string" | bareword
 *
 * The left operand is always a key looked up in the execution context. Numeric
 * comparison is used when both sides parse as numbers; otherwise string compare
 * (only == / != are meaningful for non-numbers). Deliberately small and
 * dependency-free - enough to branch on request data / prior context.
 */
@Component
public class ConditionEvaluator {

    private static final String[] OPS = {">=", "<=", "==", "!=", ">", "<"};

    public boolean eval(String expr, Map<String, Object> context) {
        if (expr == null || expr.isBlank()) return false;
        String s = expr.trim();
        for (String op : OPS) {
            int idx = s.indexOf(op);
            if (idx > 0) {
                String key = s.substring(0, idx).trim();
                String rhs = s.substring(idx + op.length()).trim();
                return compare(context.get(key), stripQuotes(rhs), op);
            }
        }
        return truthy(context.get(s));
    }

    private static boolean compare(Object left, String rhs, String op) {
        Double ln = asNumber(left);
        Double rn = asNumber(rhs);
        if (ln != null && rn != null) {
            int c = Double.compare(ln, rn);
            return switch (op) {
                case ">"  -> c > 0;
                case "<"  -> c < 0;
                case ">=" -> c >= 0;
                case "<=" -> c <= 0;
                case "==" -> c == 0;
                case "!=" -> c != 0;
                default   -> false;
            };
        }
        String ls = left == null ? "null" : String.valueOf(left);
        boolean eq = ls.equals(rhs);
        return switch (op) {
            case "==" -> eq;
            case "!=" -> !eq;
            default   -> false; // ordering undefined for non-numbers
        };
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        String s = String.valueOf(v).trim();
        return !(s.isEmpty() || s.equalsIgnoreCase("false") || s.equals("0"));
    }

    private static Double asNumber(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return null;
        try { return Double.parseDouble(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
