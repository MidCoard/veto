package top.focess.veto.agent.loop;

import java.util.regex.Pattern;

/**
 * Evaluates a {@link Check} over {@link Scope} vars deterministically, with zero LLM calls. {@link
 * Check.Llm} is the single exception — one model call — and is not evaluated here (the loop handles
 * it).
 */
public final class CheckEvaluator {

    private CheckEvaluator() {}

    /**
     * Evaluates a non-{@link Check.Llm} check to a boolean. {@link Check.Llm} throws (loop-owned).
     */
    public static boolean evaluate(Check check, Scope scope, int currentSteps) {
        return switch (check) {
            case Check.Equals e -> stringOf(scope.get(e.var())).equals(e.value());
            case Check.NotEquals e -> !stringOf(scope.get(e.var())).equals(e.value());
            case Check.Contains c -> stringOf(scope.get(c.var())).contains(c.substring());
            case Check.Matches m ->
                    Pattern.compile(m.regex()).matcher(stringOf(scope.get(m.var()))).find();
            case Check.Empty e -> {
                Object v = scope.get(e.var());
                yield v == Scope.UNDEFINED || stringOf(v).isEmpty();
            }
            case Check.NotEmpty e -> {
                Object v = scope.get(e.var());
                yield v != Scope.UNDEFINED && !stringOf(v).isEmpty();
            }
            case Check.Numeric n -> numericCompare(scope.get(n.var()), n.op(), n.value());
            case Check.ExitOk e -> exitOk(scope, e.stepId());
            case Check.Llm l ->
                    throw new UnsupportedOperationException(
                            "llm check is loop-owned (one model call); not evaluated by CheckEvaluator");
        };
    }

    private static boolean exitOk(Scope scope, String stepId) {
        Object ok = scope.get("step_ok:" + stepId);
        if (ok instanceof Boolean b) {
            return b;
        }
        Object code = scope.get("exit_code:" + stepId);
        if (code instanceof Number n) {
            return n.intValue() == 0;
        }
        return false;
    }

    private static boolean numericCompare(Object lhs, String op, String rhs) {
        double a = toDouble(lhs);
        double b = toDouble(rhs);
        return switch (op) {
            case "gt" -> a > b;
            case "lt" -> a < b;
            case "eq" -> a == b;
            case "gte" -> a >= b;
            case "lte" -> a <= b;
            default -> false;
        };
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(stringOf(o));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String stringOf(Object o) {
        if (o == null || o == Scope.UNDEFINED) {
            return "";
        }
        return o.toString();
    }
}
