package io.github.joseluisll.n8nmanager.deps;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * Version expression parsing and comparison for dependency expectations.
 *
 * <p>An expression is either an exact version ("21.0.13", "21") or a minimum
 * bound prefixed with ">=" (">=24.0.0"). Components are compared numerically,
 * missing components counting as zero.
 */
public final class VersionExpression {

    private final boolean minimum;
    private final int[] parts;

    private VersionExpression(boolean minimum, int[] parts) {
        this.minimum = minimum;
        this.parts = parts;
    }

    public static VersionExpression parse(String expression) {
        String expr = expression == null ? "" : expression.trim();
        if (expr.isEmpty()) {
            throw new IllegalArgumentException("empty version expression");
        }
        boolean minimum = false;
        if (expr.startsWith(">=")) {
            minimum = true;
            expr = expr.substring(2).trim();
        } else if (expr.startsWith("=")) {
            expr = expr.substring(1).trim();
        }
        if (expr.startsWith("v") || expr.startsWith("V")) {
            expr = expr.substring(1);
        }
        String[] tokens = expr.split("\\.");
        int[] parts = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            if (!tokens[i].matches("\\d+")) {
                throw new IllegalArgumentException(
                        "unsupported version expression '" + expression + "'");
            }
            parts[i] = Integer.parseInt(tokens[i]);
        }
        return new VersionExpression(minimum, parts);
    }

    /** Parses a found version, tolerating a leading "v" and a trailing build suffix. */
    public static int[] parseVersion(String version) {
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        // Drop pre-release/build suffixes ("21.0.13.1+2", "3.9.16 (build ...)").
        Matcher m = Pattern.compile("\\d+(?:\\.\\d+)*").matcher(v);
        if (!m.find()) {
            throw new IllegalArgumentException("no numeric version in '" + version + "'");
        }
        String[] tokens = m.group().split("\\.");
        int[] parts = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            parts[i] = Integer.parseInt(tokens[i]);
        }
        return parts;
    }

    /** True when {@code foundVersion} satisfies this expression. */
    public boolean isSatisfiedBy(String foundVersion) {
        int[] found = parseVersion(foundVersion);
        int len = Math.max(found.length, parts.length);
        for (int i = 0; i < len; i++) {
            int f = i < found.length ? found[i] : 0;
            int r = i < parts.length ? parts[i] : 0;
            if (f != r) {
                return minimum && f > r;
            }
            if (!minimum && i == len - 1 && found.length != parts.length) {
                // exact expression: "21" must not match "21.0.13.1"
                return false;
            }
        }
        return true;
    }

    public static int compare(String a, String b) {
        int[] pa = parseVersion(a);
        int[] pb = parseVersion(b);
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int x = i < pa.length ? pa[i] : 0;
            int y = i < pb.length ? pb[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }
}
