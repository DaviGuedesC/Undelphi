package undelphi.gogen;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import undelphi.sql.Ast.*;

/**
 * Compila uma expressão SQL (semântica Oracle) em uma expressão Go sobre o runtime undelphirt.
 * CASE/NVL2 viram funções anônimas para avaliar só o ramo escolhido, como no Oracle.
 */
final class ExprToGo {

    static final String RT = "undelphirt";

    /** Como cada referência vira Go: coluna/variável e parâmetro. */
    interface Resolver {
        String column(Col c);

        String param(String name);
    }

    private static final Map<String, String> BINARY = Map.ofEntries(
            Map.entry("+", "Add"), Map.entry("-", "Sub"), Map.entry("*", "Mul"), Map.entry("/", "Div"),
            Map.entry("||", "Concat"), Map.entry("=", "Eq"), Map.entry("<>", "Ne"), Map.entry("<", "Lt"),
            Map.entry("<=", "Le"), Map.entry(">", "Gt"), Map.entry(">=", "Ge"), Map.entry("AND", "And"),
            Map.entry("OR", "Or"), Map.entry("LIKE", "Like"));

    private static final Map<String, String> FUNCS = Map.ofEntries(
            Map.entry("NVL", "Nvl"), Map.entry("COALESCE", "Coalesce"), Map.entry("DECODE", "Decode"),
            Map.entry("ROUND", "Round"), Map.entry("TRUNC", "Trunc"), Map.entry("ABS", "Abs"), Map.entry("MOD", "Mod"),
            Map.entry("GREATEST", "Greatest"), Map.entry("LEAST", "Least"), Map.entry("UPPER", "Upper"),
            Map.entry("LOWER", "Lower"), Map.entry("TRIM", "Trim"), Map.entry("LENGTH", "Length"),
            Map.entry("SUBSTR", "Substr"), Map.entry("TO_NUMBER", "ToNumber"), Map.entry("TO_CHAR", "ToChar"));

    private final Resolver resolver;

    private ExprToGo(Resolver resolver) {
        this.resolver = resolver;
    }

    static String compile(Expr e, Resolver resolver) {
        return new ExprToGo(resolver).go(e);
    }

    private String go(Expr e) {
        return switch (e) {
            case Num n -> RT + ".Num(\"" + n.value().toPlainString() + "\")";
            case Str s -> RT + ".Str(" + goString(s.value()) + ")";
            case Null n -> RT + ".Null";
            case Col c -> c.parts().size() == 1 && c.name().equals("SYSDATE") ? RT + ".Sysdate()" : resolver.column(c);
            case Param p -> resolver.param(p.name());
            case Unary u -> switch (u.op()) {
                case "-" -> RT + ".Neg(" + go(u.expr()) + ")";
                case "NOT" -> RT + ".Not(" + go(u.expr()) + ")";
                default -> unsupported(u.op());
            };
            case Binary b -> {
                String fn = BINARY.get(b.op());
                yield fn == null ? unsupported(b.op()) : RT + "." + fn + "(" + go(b.left()) + ", " + go(b.right()) + ")";
            }
            case Case c -> caseExpr(c);
            case IsNull i -> i.not() ? RT + ".Not(" + RT + ".IsNullV(" + go(i.expr()) + "))" : RT + ".IsNullV(" + go(i.expr()) + ")";
            case In i -> {
                if (i.subquery() != null) {
                    yield unsupported("IN (subconsulta)");
                }
                String in = RT + ".In(" + go(i.expr()) + (i.list().isEmpty() ? "" : ", " + list(i.list())) + ")";
                yield i.not() ? RT + ".Not(" + in + ")" : in;
            }
            case Between b -> {
                String bt = RT + ".Between(" + go(b.expr()) + ", " + go(b.low()) + ", " + go(b.high()) + ")";
                yield b.not() ? RT + ".Not(" + bt + ")" : bt;
            }
            case Func f -> func(f);
            case Exists x -> unsupported("EXISTS (subconsulta)");
            case SubqueryExpr q -> unsupported("subconsulta");
            case Tuple t -> unsupported("tupla");
            case Star s -> unsupported("*");
            case Raw r -> unsupported(r.text());
        };
    }

    private String func(Func f) {
        String n = f.name();
        if (n.equals("NVL2") && f.args().size() == 3) {
            return "func() " + RT + ".V {\n\tif !" + go(f.args().get(0)) + ".IsNull() {\n\t\treturn " + go(f.args().get(1))
                    + "\n\t}\n\treturn " + go(f.args().get(2)) + "\n}()";
        }
        if (n.equals("TO_CHAR") && f.args().size() > 1) {
            return unsupported("TO_CHAR com máscara");
        }
        if (n.equals("TO_NUMBER") && f.args().size() > 1) {
            return unsupported("TO_NUMBER com máscara");
        }
        String fn = FUNCS.get(n);
        if (fn == null) {
            return unsupported(n);
        }
        return RT + "." + fn + "(" + list(f.args()) + ")";
    }

    private String caseExpr(Case c) {
        StringBuilder sb = new StringBuilder("func() " + RT + ".V {\n");
        String subject = c.subject() == null ? null : go(c.subject());
        for (When w : c.whens()) {
            String cond = subject == null ? go(w.condition()) : RT + ".Eq(" + subject + ", " + go(w.condition()) + ")";
            sb.append("\tif ").append(RT).append(".Truth(").append(indent(cond)).append(") {\n\t\treturn ")
                    .append(indent(indent(go(w.result())))).append("\n\t}\n");
        }
        sb.append("\treturn ").append(c.elseExpr() == null ? RT + ".Null" : indent(go(c.elseExpr()))).append("\n}()");
        return sb.toString();
    }

    private String list(List<Expr> args) {
        return args.stream().map(this::go).collect(Collectors.joining(", "));
    }

    private static String indent(String code) {
        return code.replace("\n", "\n\t");
    }

    private static String unsupported(String what) {
        return RT + ".Unsupported(" + goString(what) + ")";
    }

    /** Literal de string Go. */
    static String goString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) {
                        sb.append(String.format("\\x%02x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Resolver das regras: valores vêm de in.Get("QUALIFICADOR.COLUNA") / in.Get(":PARAM"). */
    static Resolver ruleResolver(Function<Col, String> keyOf) {
        return new Resolver() {
            @Override
            public String column(Col c) {
                return "in.Get(" + goString(keyOf.apply(c)) + ")";
            }

            @Override
            public String param(String name) {
                return "in.Get(" + goString(":" + name) + ")";
            }
        };
    }
}
