package undelphi.gogen;

import java.util.function.Consumer;

import undelphi.sql.Ast.*;

/** Percorre uma expressão (sem entrar em subconsultas) coletando colunas e parâmetros. */
final class OracleToPostgresWalk {

    private OracleToPostgresWalk() {
    }

    static void cols(Expr e, Consumer<Col> out) {
        walk(e, x -> {
            if (x instanceof Col c) {
                out.accept(c);
            }
        });
    }

    static void params(Expr e, Consumer<String> out) {
        walk(e, x -> {
            if (x instanceof Param p) {
                out.accept(p.name());
            }
        });
    }

    static void walk(Expr e, Consumer<Expr> v) {
        v.accept(e);
        switch (e) {
            case Func f -> f.args().forEach(a -> walk(a, v));
            case Unary u -> walk(u.expr(), v);
            case Binary b -> {
                walk(b.left(), v);
                walk(b.right(), v);
            }
            case Case c -> {
                if (c.subject() != null) {
                    walk(c.subject(), v);
                }
                c.whens().forEach(w -> {
                    walk(w.condition(), v);
                    walk(w.result(), v);
                });
                if (c.elseExpr() != null) {
                    walk(c.elseExpr(), v);
                }
            }
            case IsNull i -> walk(i.expr(), v);
            case In i -> {
                walk(i.expr(), v);
                i.list().forEach(a -> walk(a, v));
            }
            case Between b -> {
                walk(b.expr(), v);
                walk(b.low(), v);
                walk(b.high(), v);
            }
            case Tuple t -> t.items().forEach(a -> walk(a, v));
            default -> {
            }
        }
    }
}
