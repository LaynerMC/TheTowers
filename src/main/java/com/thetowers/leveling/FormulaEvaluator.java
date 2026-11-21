package com.thetowers.leveling;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A small, dependency-free math expression evaluator for the leveling system.
 *
 * <p>Supports whole and decimal numbers, the operators {@code + - * / % ^}
 * ({@code ^} is exponentiation, right-associative), parentheses, a unary
 * minus, and a handful of functions ({@code sqrt}, {@code abs}, {@code floor},
 * {@code ceil}, {@code round}, {@code sin}, {@code cos}, {@code tan},
 * {@code log}, {@code exp}, {@code max(a,b)}, {@code min(a,b)},
 * {@code pow(a,b)}). Variables are written between braces, e.g.
 * {@code {level}}, and are substituted from a context map before evaluation.
 *
 * <p>The parser is a classic recursive descent with this grammar:
 * <pre>
 *   expr   = term   (('+'|'-') term)*
 *   term   = factor (('*'|'/'|'%') factor)*
 *   factor = unary  ('^' factor)?          // right-associative
 *   unary  = ('-'|'+') unary | primary
 *   primary= number | variable | '(' expr ')' | func '(' arg (',' arg)* ')'
 * </pre>
 *
 * <p>It is intentionally tiny: the leveling formulas only need basic
 * arithmetic, and bundling a full expression library just for that would be
 * wasteful. The result is a {@code double}; callers round it to a whole
 * number, since levels and EXP are always integers.
 */
final class FormulaEvaluator {

    /** A variable context: names without braces -> numeric values. */
    private final Map<String, Double> variables;

    /** The raw expression, with variables already substituted by their values. */
    private final String expression;

    /** Current position in {@link #expression}. */
    private int pos;

    private FormulaEvaluator(String expression, Map<String, Double> variables) {
        this.expression = expression;
        this.variables = variables;
        this.pos = 0;
    }

    /**
     * Evaluates an expression, substituting the given variables.
     *
     * @param expression the formula, e.g. {@code "100 + ({level} - 1) * 50"}
     * @param variables  variable values keyed by name without braces
     * @return the evaluated result
     * @throws IllegalArgumentException if the expression is invalid
     */
    static double evaluate(String expression, Map<String, Double> variables) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("empty formula");
        }
        // A bare number (no operators) is common: short-circuit it.
        String trimmed = expression.trim();
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
            // Not a plain number: parse it as an expression.
        }
        FormulaEvaluator evaluator = new FormulaEvaluator(substitute(trimmed, variables), variables);
        double result = evaluator.parseExpression();
        evaluator.skipSpaces();
        if (evaluator.pos < evaluator.expression.length()) {
            throw new IllegalArgumentException("unexpected character '"
                    + evaluator.expression.charAt(evaluator.pos) + "' at position " + evaluator.pos);
        }
        return result;
    }

    /** Replaces every {@code {name}} token with its numeric value. */
    private static String substitute(String expression, Map<String, Double> variables) {
        if (variables == null || variables.isEmpty() || expression.indexOf('{') < 0) {
            return expression;
        }
        StringBuilder out = new StringBuilder(expression.length());
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c == '{') {
                int end = expression.indexOf('}', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated variable in formula: " + expression);
                }
                String name = expression.substring(i + 1, end).trim();
                Double value = variables.get(name);
                if (value == null) {
                    throw new IllegalArgumentException("unknown variable in formula: {" + name + "}");
                }
                out.append(value.doubleValue());
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ================= Recursive descent =================

    private double parseExpression() {
        double value = parseTerm();
        skipSpaces();
        while (pos < expression.length()) {
            char c = expression.charAt(pos);
            if (c == '+') {
                pos++;
                value += parseTerm();
            } else if (c == '-') {
                pos++;
                value -= parseTerm();
            } else {
                break;
            }
            skipSpaces();
        }
        return value;
    }

    private double parseTerm() {
        double value = parseFactor();
        skipSpaces();
        while (pos < expression.length()) {
            char c = expression.charAt(pos);
            if (c == '*') {
                pos++;
                value *= parseFactor();
            } else if (c == '/') {
                pos++;
                double divisor = parseFactor();
                value = divisor == 0d ? 0d : value / divisor;
            } else if (c == '%') {
                pos++;
                double divisor = parseFactor();
                value = divisor == 0d ? 0d : value % divisor;
            } else {
                break;
            }
            skipSpaces();
        }
        return value;
    }

    /** {@code ^} is right-associative: 2^3^2 = 2^(3^2). */
    private double parseFactor() {
        double base = parseUnary();
        skipSpaces();
        if (pos < expression.length() && expression.charAt(pos) == '^') {
            pos++;
            double exponent = parseFactor();
            return Math.pow(base, exponent);
        }
        return base;
    }

    private double parseUnary() {
        skipSpaces();
        if (pos < expression.length()) {
            char c = expression.charAt(pos);
            if (c == '-') {
                pos++;
                return -parseUnary();
            }
            if (c == '+') {
                pos++;
                return parseUnary();
            }
        }
        return parsePrimary();
    }

    private double parsePrimary() {
        skipSpaces();
        if (pos >= expression.length()) {
            throw new IllegalArgumentException("unexpected end of formula");
        }
        char c = expression.charAt(pos);
        if (c == '(') {
            pos++;
            double value = parseExpression();
            skipSpaces();
            expect(')');
            return value;
        }
        if (Character.isDigit(c) || c == '.') {
            return parseNumber();
        }
        if (Character.isLetter(c)) {
            return parseFunction();
        }
        throw new IllegalArgumentException("unexpected character '" + c + "' at position " + pos);
    }

    private double parseNumber() {
        int start = pos;
        while (pos < expression.length()) {
            char c = expression.charAt(pos);
            if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E'
                    || ((c == '+' || c == '-') && pos > start
                            && (expression.charAt(pos - 1) == 'e' || expression.charAt(pos - 1) == 'E'))) {
                pos++;
            } else {
                break;
            }
        }
        return Double.parseDouble(expression.substring(start, pos));
    }

    private double parseFunction() {
        int start = pos;
        while (pos < expression.length() && Character.isLetter(expression.charAt(pos))) {
            pos++;
        }
        String name = expression.substring(start, pos).toLowerCase(Locale.ROOT);
        skipSpaces();
        expect('(');
        java.util.List<Double> args = new java.util.ArrayList<Double>();
        skipSpaces();
        if (pos < expression.length() && expression.charAt(pos) != ')') {
            args.add(Double.valueOf(parseExpression()));
            skipSpaces();
            while (pos < expression.length() && expression.charAt(pos) == ',') {
                pos++;
                args.add(Double.valueOf(parseExpression()));
                skipSpaces();
            }
        }
        expect(')');
        return applyFunction(name, args);
    }

    private double applyFunction(String name, java.util.List<Double> args) {
        double a = args.isEmpty() ? 0d : args.get(0).doubleValue();
        double b = args.size() > 1 ? args.get(1).doubleValue() : 0d;
        switch (name) {
            case "sqrt": return Math.sqrt(a);
            case "abs": return Math.abs(a);
            case "floor": return Math.floor(a);
            case "ceil": return Math.ceil(a);
            case "round": return Math.round(a);
            case "sin": return Math.sin(a);
            case "cos": return Math.cos(a);
            case "tan": return Math.tan(a);
            case "log": return Math.log(a);
            case "exp": return Math.exp(a);
            case "max": return Math.max(a, b);
            case "min": return Math.min(a, b);
            case "pow": return Math.pow(a, b);
            default:
                throw new IllegalArgumentException("unknown function: " + name);
        }
    }

    // ================= Helpers =================

    private void skipSpaces() {
        while (pos < expression.length() && Character.isWhitespace(expression.charAt(pos))) {
            pos++;
        }
    }

    private void expect(char expected) {
        skipSpaces();
        if (pos >= expression.length() || expression.charAt(pos) != expected) {
            throw new IllegalArgumentException("expected '" + expected + "' but found "
                    + (pos < expression.length() ? "'" + expression.charAt(pos) + "'" : "end of formula"));
        }
        pos++;
    }

    /** Convenience: evaluates a formula with a single variable. */
    static double with(String expression, String variable, double value) {
        Map<String, Double> vars = new HashMap<String, Double>();
        vars.put(variable, value);
        return evaluate(expression, vars);
    }
}
