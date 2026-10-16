package undelphi.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import undelphi.sql.Ast.*;

/**
 * Imprime a AST de volta em SQL. No modo Oracle os parâmetros saem como :NOME; no modo posicional
 * (Postgres/database/sql) saem como $1, $2... na ordem da primeira ocorrência, e {@link #params()} diz
 * qual nome é cada posição.
 */
public final class SqlPrinter {

    public enum Params {
        NAMED, POSITIONAL
    }

    private final Params paramStyle;
    private final List<String> params = new ArrayList<>();

    public SqlPrinter(Params paramStyle) {
        this.paramStyle = paramStyle;
    }

    public static String oracle(Expr e) {
        return new SqlPrinter(Params.NAMED).expr(e);
    }

    public static String oracle(Stmt s) {
        return new SqlPrinter(Params.NAMED).stmt(s, "");
    }

    /** Nomes dos parâmetros na ordem das posições ($1 = params().get(0)). */
    public List<String> params() {
        return params;
    }

    // ---------------------------------------------------------------- comandos

    public String stmt(Stmt s, String ind) {
        return switch (s) {
            case Query q -> query(q, ind);
            case Insert i -> {
                StringBuilder sb = new StringBuilder(ind).append("INSERT INTO ").append(i.table());
                if (i.alias() != null) {
                    sb.append(' ').append(i.alias());
                }
                if (!i.columns().isEmpty()) {
                    sb.append(" (").append(String.join(", ", i.columns())).append(')');
                }
                if (i.select() != null) {
                    sb.append('\n').append(query(i.select(), ind));
                } else {
                    sb.append("\n").append(ind).append("VALUES (")
                            .append(i.values().stream().map(this::expr).collect(Collectors.joining(", "))).append(')');
                }
                yield sb.toString();
            }
            case Update u -> {
                StringBuilder sb = new StringBuilder(ind).append("UPDATE ").append(u.table());
                if (u.alias() != null) {
                    sb.append(' ').append(u.alias());
                }
                sb.append("\n").append(ind).append("   SET ");
                for (int k = 0; k < u.sets().size(); k++) {
                    if (k > 0) {
                        sb.append("\n").append(ind).append("     , ");
                    }
                    Assignment a = u.sets().get(k);
                    sb.append(expr(a.target())).append(" = ").append(expr(a.value()));
                }
                if (u.where() != null) {
                    sb.append("\n").append(ind).append(" WHERE ").append(expr(u.where()));
                }
                yield sb.toString();
            }
            case Delete d -> ind + "DELETE FROM " + d.table() + (d.alias() != null ? " " + d.alias() : "")
                    + (d.where() != null ? "\n" + ind + " WHERE " + expr(d.where()) : "");
            case Block b -> {
                StringBuilder sb = new StringBuilder();
                if (!b.declarations().isEmpty()) {
                    sb.append(ind).append("DECLARE\n");
                    b.declarations().forEach(d -> sb.append(ind).append("  ").append(d).append(";\n"));
                }
                sb.append(ind).append("BEGIN\n");
                body(sb, b.body(), ind + "  ");
                if (!b.exceptionBody().isEmpty()) {
                    sb.append(ind).append("EXCEPTION\n");
                    body(sb, b.exceptionBody(), ind + "  ");
                }
                yield sb.append(ind).append("END").toString();
            }
            case ForLoop f -> {
                StringBuilder sb = new StringBuilder(ind).append("FOR ").append(f.record()).append(" IN (\n");
                sb.append(f.cursor() == null ? ind + "  ..." : query(f.cursor(), ind + "  ")).append(")\n");
                sb.append(ind).append("LOOP\n");
                body(sb, f.body(), ind + "  ");
                yield sb.append(ind).append("END LOOP").toString();
            }
            case If i -> {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < i.branches().size(); k++) {
                    Branch br = i.branches().get(k);
                    sb.append(ind).append(k == 0 ? "IF " : "ELSIF ").append(expr(br.condition())).append(" THEN\n");
                    body(sb, br.body(), ind + "  ");
                }
                if (!i.elseBody().isEmpty()) {
                    sb.append(ind).append("ELSE\n");
                    body(sb, i.elseBody(), ind + "  ");
                }
                yield sb.append(ind).append("END IF").toString();
            }
            case Assign a -> ind + expr(a.target()) + " := " + expr(a.value());
            case Call c -> ind + c.name() + (c.args().isEmpty() ? "" : "(" + exprList(c.args()) + ")");
            case Return r -> ind + "RETURN" + (r.value() != null ? " " + expr(r.value()) : "");
            case NullStmt n -> ind + "NULL";
            case CreateObject c -> c.text();
            case RawStmt r -> r.text();
        };
    }

    private void body(StringBuilder sb, List<Stmt> stmts, String ind) {
        for (Stmt s : stmts) {
            sb.append(stmt(s, ind));
            sb.append(s instanceof RawStmt r && r.text().endsWith("THEN") ? "\n" : ";\n");
        }
    }

    public String query(Query q, String ind) {
        StringBuilder sb = new StringBuilder(block(q.first(), ind));
        for (SetOp op : q.setOps()) {
            sb.append('\n').append(ind).append(op.op()).append('\n').append(block(op.block(), ind));
        }
        if (!q.orderBy().isEmpty()) {
            sb.append('\n').append(ind).append(" ORDER BY ").append(q.orderBy().stream()
                    .map(o -> expr(o.expr()) + (o.desc() ? " DESC" : "") + (o.nulls() != null ? " NULLS " + o.nulls() : ""))
                    .collect(Collectors.joining(", ")));
        }
        if (q.limit() != null) {
            sb.append('\n').append(ind).append(" LIMIT ").append(expr(q.limit()));
        }
        if (q.forUpdate()) {
            sb.append('\n').append(ind).append(" FOR UPDATE");
        }
        return sb.toString();
    }

    private String block(QueryBlock b, String ind) {
        StringBuilder sb = new StringBuilder(ind).append("SELECT ");
        if (b.distinct()) {
            sb.append("DISTINCT ");
        }
        for (int k = 0; k < b.items().size(); k++) {
            if (k > 0) {
                sb.append('\n').append(ind).append("     , ");
            }
            SelectItem it = b.items().get(k);
            sb.append(expr(it.expr(), ind + "       "));
            if (it.alias() != null) {
                sb.append(' ').append(it.alias());
            }
        }
        if (!b.into().isEmpty()) {
            sb.append('\n').append(ind).append("  INTO ").append(String.join(", ", b.into()));
        }
        if (!b.from().isEmpty()) {
            sb.append('\n').append(ind).append("  FROM ");
            for (int k = 0; k < b.from().size(); k++) {
                if (k > 0) {
                    sb.append('\n').append(ind).append("     , ");
                }
                sb.append(from(b.from().get(k), ind));
            }
        }
        if (b.where() != null) {
            sb.append('\n').append(ind).append(" WHERE ").append(where(b.where(), ind));
        }
        if (b.connectBy() != null) {
            sb.append('\n').append(ind).append(' ').append(b.connectBy());
        }
        if (!b.groupBy().isEmpty()) {
            sb.append('\n').append(ind).append(" GROUP BY ").append(exprList(b.groupBy()));
        }
        if (b.having() != null) {
            sb.append('\n').append(ind).append("HAVING ").append(expr(b.having()));
        }
        return sb.toString();
    }

    /** WHERE com um AND por linha. */
    private String where(Expr e, String ind) {
        List<Expr> parts = new ArrayList<>();
        flattenAnd(e, parts);
        return parts.stream().map(x -> expr(x, ind + "       ")).collect(Collectors.joining("\n" + ind + "   AND "));
    }

    static void flattenAnd(Expr e, List<Expr> out) {
        if (e instanceof Binary b && b.op().equals("AND")) {
            flattenAnd(b.left(), out);
            flattenAnd(b.right(), out);
        } else {
            out.add(e);
        }
    }

    private String from(FromItem f, String ind) {
        return switch (f) {
            case TableRef t -> t.name() + (t.alias() != null ? " " + t.alias() : "");
            case SubqueryRef s -> "(\n" + query(s.query(), ind + "        ") + ")" + (s.alias() != null ? " " + s.alias() : "");
            case JoinRef j -> from(j.left(), ind) + "\n" + ind + "  " + j.type() + " " + from(j.right(), ind)
                    + (j.on() != null ? " ON " + expr(j.on(), ind + "    ") : "");
        };
    }

    // ---------------------------------------------------------------- expressões

    public String expr(Expr e) {
        return expr(e, "");
    }

    private String exprList(List<Expr> list) {
        return list.stream().map(this::expr).collect(Collectors.joining(", "));
    }

    private String expr(Expr e, String ind) {
        return switch (e) {
            case Num n -> n.value().toPlainString();
            case Str s -> "'" + s.value().replace("'", "''") + "'";
            case Null n -> "NULL";
            case Col c -> String.join(".", c.parts()) + (c.outer() ? "(+)" : "");
            case Param p -> param(p.name());
            case Func f -> f.name() + "(" + (f.star() ? "*" : (f.distinct() ? "DISTINCT " : "")
                    + f.args().stream().map(a -> a instanceof Raw r && f.name().equals("CAST") ? r.text() : expr(a, ind))
                            .collect(Collectors.joining(f.name().equals("CAST") ? " AS " : f.name().equals("EXTRACT") ? " FROM " : ", ")))
                    + ")";
            case Unary u -> u.op().equals("-") ? "-" + paren(u.expr(), 7, ind)
                    : u.op() + " " + paren(u.expr(), u.op().equals("NOT") ? 3 : 7, ind);
            case Binary b -> paren(b.left(), prec(b), ind) + " " + b.op() + " " + paren(b.right(), prec(b) + 1, ind);
            case Case c -> {
                StringBuilder sb = new StringBuilder("CASE");
                if (c.subject() != null) {
                    sb.append(' ').append(expr(c.subject(), ind));
                }
                boolean multi = c.whens().size() > 1;
                for (When w : c.whens()) {
                    sb.append(multi ? "\n" + ind + "     WHEN " : " WHEN ").append(expr(w.condition(), ind))
                            .append(" THEN ").append(expr(w.result(), ind + "  "));
                }
                if (c.elseExpr() != null) {
                    sb.append(multi ? "\n" + ind + "     ELSE " : " ELSE ").append(expr(c.elseExpr(), ind + "  "));
                }
                yield sb.append(multi ? "\n" + ind + "END" : " END").toString();
            }
            case IsNull i -> paren(i.expr(), 5, ind) + (i.not() ? " IS NOT NULL" : " IS NULL");
            case In i -> paren(i.expr(), 5, ind) + (i.not() ? " NOT IN (" : " IN (")
                    + (i.subquery() != null ? "\n" + query(i.subquery(), ind + "  ") : exprList(i.list())) + ")";
            case Between b -> paren(b.expr(), 5, ind) + (b.not() ? " NOT BETWEEN " : " BETWEEN ") + paren(b.low(), 5, ind)
                    + " AND " + paren(b.high(), 5, ind);
            case Exists x -> "EXISTS (\n" + query(x.subquery(), ind + "  ") + ")";
            case SubqueryExpr s -> "(\n" + query(s.query(), ind + "  ") + ")";
            case Tuple t -> "(" + exprList(t.items()) + ")";
            case Star s -> s.qualifier() == null ? "*" : s.qualifier() + ".*";
            case Raw r -> r.text();
        };
    }

    private String param(String name) {
        if (paramStyle == Params.NAMED) {
            return ":" + name;
        }
        int idx = params.indexOf(name);
        if (idx < 0) {
            params.add(name);
            idx = params.size() - 1;
        }
        return "$" + (idx + 1);
    }

    static int prec(Binary b) {
        return switch (b.op()) {
            case "OR" -> 1;
            case "AND" -> 2;
            case "+", "-", "||" -> 5;
            case "*", "/" -> 6;
            default -> 4;
        };
    }

    private static int precOf(Expr e) {
        return switch (e) {
            case Binary b -> prec(b);
            case Unary u -> u.op().equals("NOT") ? 3 : 7;
            case IsNull i -> 4;
            case In i -> 4;
            case Between b -> 4;
            default -> 9;
        };
    }

    private String paren(Expr e, int minPrec, String ind) {
        String s = expr(e, ind);
        return precOf(e) < minPrec ? "(" + s + ")" : s;
    }
}
