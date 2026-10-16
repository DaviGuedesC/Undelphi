package undelphi.sql;

import java.math.BigDecimal;
import java.util.List;

/** Árvore sintática de SQL/PL-SQL do Oracle (o subconjunto que as rotinas do WinThor usam). */
public final class Ast {

    private Ast() {
    }

    // ---------------------------------------------------------------- expressões

    public sealed interface Expr {
    }

    public record Num(BigDecimal value) implements Expr {
    }

    public record Str(String value) implements Expr {
    }

    public record Null() implements Expr {
    }

    /** Referência a coluna (ou variável/registro no PL/SQL): partes = [qualificador..., nome]. outer = sufixo (+). */
    public record Col(List<String> parts, boolean outer) implements Expr {

        public String name() {
            return parts.get(parts.size() - 1);
        }

        /** Qualificador (alias/tabela/registro), ou null. */
        public String qualifier() {
            return parts.size() < 2 ? null : String.join(".", parts.subList(0, parts.size() - 1));
        }

        public Col withOuter(boolean o) {
            return new Col(parts, o);
        }
    }

    public record Param(String name) implements Expr {
    }

    /** Chamada de função; name pode ser "PKG.FUNC". star = COUNT(*). */
    public record Func(String name, List<Expr> args, boolean distinct, boolean star) implements Expr {
    }

    /** -, +, NOT, PRIOR. */
    public record Unary(String op, Expr expr) implements Expr {
    }

    /** OR AND = <> < > <= >= + - * / || LIKE. */
    public record Binary(String op, Expr left, Expr right) implements Expr {
    }

    public record When(Expr condition, Expr result) {
    }

    /** CASE [subject] WHEN ... THEN ... [ELSE] END. */
    public record Case(Expr subject, List<When> whens, Expr elseExpr) implements Expr {
    }

    public record IsNull(Expr expr, boolean not) implements Expr {
    }

    public record In(Expr expr, List<Expr> list, Query subquery, boolean not) implements Expr {
    }

    public record Between(Expr expr, Expr low, Expr high, boolean not) implements Expr {
    }

    public record Exists(Query subquery) implements Expr {
    }

    public record SubqueryExpr(Query query) implements Expr {
    }

    public record Tuple(List<Expr> items) implements Expr {
    }

    /** q.* ou *. */
    public record Star(String qualifier) implements Expr {
    }

    /** Trecho não interpretado (ex. funções analíticas OVER(...)); guardado como texto. */
    public record Raw(String text) implements Expr {
    }

    // ---------------------------------------------------------------- consultas

    public record SelectItem(Expr expr, String alias) {
    }

    public sealed interface FromItem {
    }

    /** name = [schema.]tabela[@dblink]. */
    public record TableRef(String name, String alias) implements FromItem {

        public String refName() {
            return alias != null ? alias : name;
        }
    }

    public record SubqueryRef(Query query, String alias) implements FromItem {
    }

    /** type: JOIN, LEFT JOIN, RIGHT JOIN, FULL JOIN, CROSS JOIN. */
    public record JoinRef(FromItem left, String type, FromItem right, Expr on) implements FromItem {
    }

    public record OrderItem(Expr expr, boolean desc, String nulls) {
    }

    /** Um SELECT simples. into = variáveis do SELECT ... INTO (PL/SQL). */
    public record QueryBlock(boolean distinct, List<SelectItem> items, List<String> into, List<FromItem> from,
            Expr where, String connectBy, List<Expr> groupBy, Expr having) {
    }

    /** op: UNION, UNION ALL, MINUS, INTERSECT. */
    public record SetOp(String op, QueryBlock block) {
    }

    /** limit: só existe depois da tradução para Postgres (vem de ROWNUM). */
    public record Query(QueryBlock first, List<SetOp> setOps, List<OrderItem> orderBy, boolean forUpdate, Expr limit)
            implements Stmt {

        public Query(QueryBlock first, List<SetOp> setOps, List<OrderItem> orderBy, boolean forUpdate) {
            this(first, setOps, orderBy, forUpdate, null);
        }
    }

    // ---------------------------------------------------------------- comandos

    public sealed interface Stmt {
    }

    public record Insert(String table, String alias, List<String> columns, List<Expr> values, Query select)
            implements Stmt {
    }

    public record Assignment(Expr target, Expr value) {
    }

    public record Update(String table, String alias, List<Assignment> sets, Expr where) implements Stmt {
    }

    public record Delete(String table, String alias, Expr where) implements Stmt {
    }

    /** BEGIN ... [EXCEPTION ...] END, com o DECLARE opcional (declarações ficam como texto). */
    public record Block(List<String> declarations, List<Stmt> body, List<Stmt> exceptionBody) implements Stmt {
    }

    /** FOR rec IN (SELECT ...) LOOP ... END LOOP. */
    public record ForLoop(String record, Query cursor, List<Stmt> body) implements Stmt {
    }

    public record Branch(Expr condition, List<Stmt> body) {
    }

    public record If(List<Branch> branches, List<Stmt> elseBody) implements Stmt {
    }

    /** var := expr. */
    public record Assign(Expr target, Expr value) implements Stmt {
    }

    /** Chamada de procedimento (pkg.proc(args)) como comando. */
    public record Call(String name, List<Expr> args) implements Stmt {
    }

    public record Return(Expr value) implements Stmt {
    }

    public record NullStmt() implements Stmt {
    }

    /** CREATE [OR REPLACE] FUNCTION/PROCEDURE/... — o corpo fica como texto. */
    public record CreateObject(String kind, String name, String text) implements Stmt {
    }

    /** Comando que o parser não entendeu (ex. SQL montada em pedaços no código). */
    public record RawStmt(String text, String reason) implements Stmt {
    }
}
