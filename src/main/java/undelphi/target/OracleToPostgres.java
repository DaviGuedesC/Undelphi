package undelphi.target;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import undelphi.sql.Ast.*;

/**
 * Traduz um comando Oracle para Postgres aplicando o perfil de destino (tabelas/colunas/funções/sequences).
 * O que não tem tradução segura vira {@link Issue} do tipo LACUNA (a consulta não deve rodar como está) ou
 * AVISO (roda, mas a semântica pode mudar).
 */
public final class OracleToPostgres {

    public enum Severity {
        LACUNA, AVISO
    }

    public record Issue(Severity severity, String message) {
    }

    public record Result(Stmt stmt, List<Issue> issues) {

        /** Sem lacunas: pode rodar no destino. */
        public boolean translatable() {
            return issues.stream().noneMatch(i -> i.severity() == Severity.LACUNA);
        }
    }

    private final TargetProfile profile;
    /** Registros de cursor do PL/SQL (FOR rec IN ...): rec.COL vira o parâmetro "REC.COL". */
    private final Set<String> records;
    private final List<Issue> issues = new ArrayList<>();

    private OracleToPostgres(TargetProfile profile, Set<String> records) {
        this.profile = profile;
        this.records = records;
    }

    public static Result translate(Stmt stmt, TargetProfile profile) {
        return translate(stmt, profile, Set.of());
    }

    public static Result translate(Stmt stmt, TargetProfile profile, Set<String> records) {
        OracleToPostgres t = new OracleToPostgres(profile, records);
        Stmt out = t.stmt(stmt);
        return new Result(out, List.copyOf(new LinkedHashSet<>(t.issues)));
    }

    /** Traduz uma expressão solta (sem tabelas no escopo). */
    public static Result translateExpr(Expr e, TargetProfile profile, Set<String> records) {
        OracleToPostgres t = new OracleToPostgres(profile, records);
        Expr out = t.expr(e, new Scope(null));
        return new Result(new Return(out), List.copyOf(new LinkedHashSet<>(t.issues)));
    }

    private void gap(String msg) {
        issues.add(new Issue(Severity.LACUNA, msg));
    }

    private void warn(String msg) {
        issues.add(new Issue(Severity.AVISO, msg));
    }

    // ---------------------------------------------------------------- escopo

    /** alias/nome → tabela Oracle (null = subconsulta). */
    private static final class Scope {
        final Scope parent;
        final Map<String, String> refs = new HashMap<>();
        final Set<String> derived = new LinkedHashSet<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        /** Tabela Oracle do qualificador; "" se for subconsulta; null se desconhecido. */
        String lookup(String q) {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.derived.contains(q)) {
                    return "";
                }
                String t = s.refs.get(q);
                if (t != null) {
                    return t;
                }
            }
            return null;
        }

        /** Única tabela do escopo de consulta mais interno, ou null. */
        String singleTable() {
            for (Scope s = this; s != null; s = s.parent) {
                if (!s.refs.isEmpty() || !s.derived.isEmpty()) {
                    Set<String> distinct = new LinkedHashSet<>(s.refs.values());
                    return distinct.size() == 1 && s.derived.isEmpty() ? distinct.iterator().next() : null;
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- comandos

    private Stmt stmt(Stmt s) {
        return switch (s) {
            case Query q -> query(q, null);
            case Insert i -> {
                checkWrite(i.table());
                Scope sc = dmlScope(i.table(), i.alias(), null);
                List<String> cols = new ArrayList<>();
                for (String c : i.columns()) {
                    cols.add(column(i.table(), lastPart(c)));
                }
                List<Expr> values = i.values().stream().map(v -> expr(v, sc)).toList();
                Query sel = i.select() == null ? null : query(i.select(), null);
                yield new Insert(table(i.table()), null, cols, values, sel);
            }
            case Update u -> {
                checkWrite(u.table());
                Scope sc = dmlScope(u.table(), u.alias(), null);
                List<Assignment> sets = new ArrayList<>();
                for (Assignment a : u.sets()) {
                    String col = a.target() instanceof Col c ? column(u.table(), c.name()) : "?";
                    sets.add(new Assignment(new Col(List.of(col), false), expr(a.value(), sc)));
                }
                yield new Update(table(u.table()), u.alias() != null ? u.alias() : u.table(), sets,
                        u.where() == null ? null : expr(u.where(), sc));
            }
            case Delete d -> {
                checkWrite(d.table());
                Scope sc = dmlScope(d.table(), d.alias(), null);
                yield new Delete(table(d.table()), d.alias() != null ? d.alias() : d.table(),
                        d.where() == null ? null : expr(d.where(), sc));
            }
            case Call c -> {
                String mapped = profile.functions().get(c.name());
                if (mapped == null) {
                    gap("procedimento do Oracle " + c.name() + " sem equivalente no destino (mapear em funcoes:)");
                    yield c;
                }
                yield new Call(mapped, c.args().stream().map(a -> expr(a, new Scope(null))).toList());
            }
            default -> {
                gap("comando " + s.getClass().getSimpleName() + " não é traduzido isoladamente");
                yield s;
            }
        };
    }

    private void checkWrite(String table) {
        if (!profile.writesAllowed()) {
            gap("gravação em " + table + " bloqueada pelo perfil (escrita: bloqueada)");
        }
    }

    private Scope dmlScope(String table, String alias, Scope parent) {
        Scope sc = new Scope(parent);
        sc.refs.put(table, table);
        if (alias != null) {
            sc.refs.put(alias, table);
        }
        return sc;
    }

    private String table(String oracle) {
        String t = profile.targetTable(oracle);
        if (t == null) {
            gap("tabela " + oracle + " sem destino no perfil");
            return oracle;
        }
        if (profile.schema() != null && !profile.schema().hasTable(t)) {
            gap("tabela de destino " + t + " (de " + oracle + ") não existe no esquema do destino");
        }
        return t;
    }

    private String column(String oracleTable, String col) {
        if (col.equals("ROWID")) {
            gap("ROWID de " + oracleTable + " não tem equivalente estável no Postgres");
            return "ctid";
        }
        TargetProfile.ColumnTarget ct = profile.targetColumn(oracleTable, col);
        if (!ct.ok()) {
            if (profile.targetTable(oracleTable) != null) {
                gap(ct.gap());
            }
            return col;
        }
        return ct.name();
    }

    // ---------------------------------------------------------------- consultas

    private Query query(Query q, Scope parent) {
        Expr[] limit = new Expr[1];
        QueryBlock first = block(q.first(), parent, limit);
        List<SetOp> ops = new ArrayList<>();
        for (SetOp op : q.setOps()) {
            Expr[] ignored = new Expr[1];
            ops.add(new SetOp(op.op().equals("MINUS") ? "EXCEPT" : op.op(), block(op.block(), parent, ignored)));
            if (ignored[0] != null) {
                warn("ROWNUM em parte de UNION/MINUS: o limite foi descartado nessa parte");
            }
        }
        Scope orderScope = scopeOf(q.first(), parent);
        List<OrderItem> order = q.orderBy().stream()
                .map(o -> new OrderItem(expr(o.expr(), orderScope), o.desc(), o.nulls())).toList();
        if (limit[0] != null && !order.isEmpty()) {
            warn("ROWNUM com ORDER BY: no Oracle o limite é aplicado antes da ordenação; no Postgres (LIMIT), depois");
        }
        return new Query(first, ops, order, q.forUpdate(), limit[0]);
    }

    private Scope scopeOf(QueryBlock b, Scope parent) {
        Scope s = new Scope(parent);
        for (FromItem f : b.from()) {
            registerOriginal(f, s);
        }
        return s;
    }

    private void registerOriginal(FromItem f, Scope s) {
        switch (f) {
            case TableRef t -> {
                s.refs.put(t.refName(), t.name());
                if (t.alias() != null) {
                    s.refs.putIfAbsent(t.name(), t.name());
                }
            }
            case SubqueryRef sq -> {
                if (sq.alias() != null) {
                    s.derived.add(sq.alias());
                }
            }
            case JoinRef j -> {
                registerOriginal(j.left(), s);
                registerOriginal(j.right(), s);
            }
        }
    }

    private QueryBlock block(QueryBlock b, Scope parent, Expr[] limitOut) {
        Scope s = scopeOf(b, parent);
        List<FromItem> source = new ArrayList<>(b.from());
        source.removeIf(f -> f instanceof TableRef t && t.name().equals("DUAL"));

        // WHERE: separa ROWNUM, condições (+) por tabela externa e o resto
        List<Expr> conjuncts = new ArrayList<>();
        if (b.where() != null) {
            flattenAnd(b.where(), conjuncts);
        }
        List<Expr> rest = new ArrayList<>();
        Map<String, List<Expr>> outerConds = new LinkedHashMap<>();
        for (Expr c : conjuncts) {
            Expr lim = rownumLimit(c);
            if (lim != null) {
                limitOut[0] = lim;
                continue;
            }
            Set<String> outerQuals = new LinkedHashSet<>();
            collectOuter(c, outerQuals);
            if (outerQuals.isEmpty()) {
                rest.add(c);
            } else if (outerQuals.size() == 1) {
                outerConds.computeIfAbsent(outerQuals.iterator().next(), k -> new ArrayList<>()).add(c);
            } else {
                gap("condição com (+) em mais de uma tabela: " + undelphi.sql.SqlPrinter.oracle(c));
                rest.add(c);
            }
        }

        List<FromItem> from = new ArrayList<>();
        if (outerConds.isEmpty()) {
            for (FromItem f : source) {
                from.add(fromItem(f, s, parent));
            }
        } else {
            from.add(outerJoinTree(source, outerConds, s, parent));
        }

        List<SelectItem> items = b.items().stream()
                .map(it -> new SelectItem(expr(it.expr(), s), it.alias())).toList();
        Expr where = rest.isEmpty() ? null : andAll(rest.stream().map(c -> expr(c, s)).toList());
        if (b.connectBy() != null) {
            gap("CONNECT BY (consulta hierárquica) precisa virar WITH RECURSIVE à mão");
        }
        if (!b.into().isEmpty()) {
            warn("SELECT ... INTO (PL/SQL): no destino o resultado volta como linha");
        }
        return new QueryBlock(b.distinct(), items, List.of(), from, where, null,
                b.groupBy().stream().map(g -> expr(g, s)).toList(), b.having() == null ? null : expr(b.having(), s));
    }

    /** Monta FROM a CROSS JOIN b ... LEFT JOIN x ON (condições que tinham (+) em x). */
    private FromItem outerJoinTree(List<FromItem> source, Map<String, List<Expr>> outerConds, Scope s, Scope parent) {
        Map<String, FromItem> byName = new LinkedHashMap<>();
        for (FromItem f : source) {
            byName.put(refName(f), f);
        }
        List<String> inner = new ArrayList<>();
        for (String name : byName.keySet()) {
            if (!outerConds.containsKey(name)) {
                inner.add(name);
            }
        }
        if (inner.isEmpty() || source.stream().anyMatch(f -> f instanceof JoinRef)) {
            gap("join externo (+) em formato que não sei converter (todas as tabelas externas ou mistura com JOIN ANSI)");
            return fromItem(source.get(0), s, parent);
        }
        FromItem tree = fromItem(byName.get(inner.get(0)), s, parent);
        Set<String> present = new LinkedHashSet<>(List.of(inner.get(0)));
        for (int k = 1; k < inner.size(); k++) {
            tree = new JoinRef(tree, "CROSS JOIN", fromItem(byName.get(inner.get(k)), s, parent), null);
            present.add(inner.get(k));
        }
        List<String> pending = new ArrayList<>(outerConds.keySet());
        while (!pending.isEmpty()) {
            String next = null;
            for (String o : pending) {
                Set<String> refs = new LinkedHashSet<>();
                outerConds.get(o).forEach(c -> collectQualifiers(c, refs));
                refs.remove(o);
                refs.removeIf(r -> !byName.containsKey(r)); // de fora (escopo pai, registro, parâmetro)
                if (present.containsAll(refs)) {
                    next = o;
                    break;
                }
            }
            if (next == null) {
                gap("joins externos (+) com dependência circular entre " + pending);
                break;
            }
            FromItem item = byName.get(next);
            if (item == null) {
                gap("tabela externa " + next + " não está no FROM");
                pending.remove(next);
                continue;
            }
            Expr on = andAll(outerConds.get(next).stream().map(c -> expr(c, s)).toList());
            tree = new JoinRef(tree, "LEFT JOIN", fromItem(item, s, parent), on);
            present.add(next);
            pending.remove(next);
        }
        return tree;
    }

    private static String refName(FromItem f) {
        return switch (f) {
            case TableRef t -> t.refName();
            case SubqueryRef sq -> sq.alias() != null ? sq.alias() : "?";
            case JoinRef j -> "?";
        };
    }

    private FromItem fromItem(FromItem f, Scope s, Scope parent) {
        return switch (f) {
            case TableRef t -> new TableRef(table(t.name()), t.alias() != null ? t.alias() : t.name());
            case SubqueryRef sq -> new SubqueryRef(query(sq.query(), parent), sq.alias());
            case JoinRef j -> new JoinRef(fromItem(j.left(), s, parent), j.type(), fromItem(j.right(), s, parent),
                    j.on() == null ? null : expr(j.on(), s));
        };
    }

    /** ROWNUM = 1 / ROWNUM <= n / ROWNUM < n → limite; outra coisa → null. */
    private static Expr rownumLimit(Expr c) {
        if (c instanceof Binary b && b.left() instanceof Col col && col.parts().size() == 1 && col.name().equals("ROWNUM")) {
            switch (b.op()) {
                case "=", "<=" -> {
                    return b.right();
                }
                case "<" -> {
                    if (b.right() instanceof Num n) {
                        return new Num(n.value().subtract(BigDecimal.ONE));
                    }
                    return new Binary("-", b.right(), new Num(BigDecimal.ONE));
                }
                default -> {
                    return null;
                }
            }
        }
        return null;
    }

    private static void collectOuter(Expr e, Set<String> out) {
        walk(e, x -> {
            if (x instanceof Col c && c.outer() && c.qualifier() != null) {
                out.add(c.qualifier());
            }
        });
    }

    private static void collectQualifiers(Expr e, Set<String> out) {
        walk(e, x -> {
            if (x instanceof Col c && c.qualifier() != null) {
                out.add(c.qualifier());
            }
        });
    }

    /** Visita a expressão sem entrar em subconsultas. */
    static void walk(Expr e, java.util.function.Consumer<Expr> v) {
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

    private static void flattenAnd(Expr e, List<Expr> out) {
        if (e instanceof Binary b && b.op().equals("AND")) {
            flattenAnd(b.left(), out);
            flattenAnd(b.right(), out);
        } else {
            out.add(e);
        }
    }

    private static Expr andAll(List<Expr> list) {
        Expr out = list.get(0);
        for (int k = 1; k < list.size(); k++) {
            out = new Binary("AND", out, list.get(k));
        }
        return out;
    }

    // ---------------------------------------------------------------- expressões

    private Expr expr(Expr e, Scope s) {
        return switch (e) {
            case Col c -> col(c, s);
            case Str str -> {
                if (str.value().isEmpty()) {
                    warn("'' no Oracle é NULL; traduzido como NULL");
                    yield new Null();
                }
                yield str;
            }
            case Func f -> func(f, s);
            case Unary u -> new Unary(u.op(), expr(u.expr(), s));
            case Binary b -> binary(b, s);
            case Case c -> new Case(c.subject() == null ? null : expr(c.subject(), s),
                    c.whens().stream().map(w -> new When(expr(w.condition(), s), expr(w.result(), s))).toList(),
                    c.elseExpr() == null ? null : expr(c.elseExpr(), s));
            case IsNull i -> new IsNull(expr(i.expr(), s), i.not());
            case In i -> new In(expr(i.expr(), s), i.list().stream().map(x -> expr(x, s)).toList(),
                    i.subquery() == null ? null : query(i.subquery(), s), i.not());
            case Between b -> new Between(expr(b.expr(), s), expr(b.low(), s), expr(b.high(), s), b.not());
            case Exists x -> new Exists(query(x.subquery(), s));
            case SubqueryExpr q -> new SubqueryExpr(query(q.query(), s));
            case Tuple t -> new Tuple(t.items().stream().map(x -> expr(x, s)).toList());
            case Raw r -> {
                warn("trecho não interpretado mantido como está: " + r.text());
                yield r;
            }
            default -> e;
        };
    }

    private Expr col(Col c, Scope s) {
        String name = c.name();
        if (c.parts().size() >= 2) {
            String q = c.qualifier();
            if (records.contains(q)) {
                return new Param(q + "." + name);
            }
            if (name.equals("NEXTVAL") || name.equals("CURRVAL")) {
                String seq = profile.sequences().get(q);
                if (seq == null) {
                    gap("sequence " + q + " sem equivalente no destino (mapear em sequencias:)");
                    seq = q.toLowerCase();
                }
                return new Func(name.equals("NEXTVAL") ? "NEXTVAL" : "CURRVAL", List.of(new Str(seq)), false, false);
            }
            String table = s.lookup(q);
            if (table == null && c.parts().size() == 3) {
                table = s.lookup(c.parts().get(1));
            }
            if (table == null) {
                warn("qualificador desconhecido " + q + " (variável de pacote?) mantido");
                return c.withOuter(false);
            }
            if (table.isEmpty()) {
                return c.withOuter(false); // coluna de subconsulta: o nome é o alias de lá
            }
            List<String> parts = new ArrayList<>(c.parts());
            parts.set(parts.size() - 1, column(table, name));
            return new Col(parts, false);
        }
        switch (name) {
            case "SYSDATE":
                return new Raw("LOCALTIMESTAMP(0)");
            case "SYSTIMESTAMP":
                return new Raw("CURRENT_TIMESTAMP");
            case "USER":
                return new Raw("CURRENT_USER");
            case "ROWNUM":
                warn("ROWNUM na lista de colunas virou ROW_NUMBER() OVER ()");
                return new Raw("ROW_NUMBER() OVER ()");
            case "ROWID":
                gap("ROWID não tem equivalente estável no Postgres");
                return new Raw("ctid");
            case "LEVEL":
                gap("LEVEL (CONNECT BY) sem tradução automática");
                return c;
            default:
                break;
        }
        String single = s.singleTable();
        if (single != null) {
            return new Col(List.of(column(single, name)), false);
        }
        return c.withOuter(false);
    }

    private Expr binary(Binary b, Scope s) {
        if ((b.op().equals("+") || b.op().equals("-")) && isSysdate(b.left()) && b.right() instanceof Num n) {
            return new Binary(b.op(), new Raw("LOCALTIMESTAMP(0)"), new Binary("*", n, new Raw("INTERVAL '1 day'")));
        }
        return new Binary(b.op(), expr(b.left(), s), expr(b.right(), s));
    }

    private static boolean isSysdate(Expr e) {
        return e instanceof Col c && c.parts().size() == 1 && c.name().equals("SYSDATE");
    }

    private static boolean looksLikeDate(Expr e) {
        if (isSysdate(e)) {
            return true;
        }
        if (e instanceof Col c) {
            String n = c.name();
            return n.startsWith("DT") || n.startsWith("DATA") || n.endsWith("DATA") || n.startsWith("DH");
        }
        return e instanceof Func f && (f.name().equals("TO_DATE") || f.name().equals("TRUNC"));
    }

    private static Expr cast(Expr e, String type) {
        return new Func("CAST", List.of(e, new Raw(type)), false, false);
    }

    private Expr func(Func f, Scope s) {
        String n = f.name();
        if (n.equals("TRUNC") && f.args().size() == 1 && isSysdate(f.args().get(0))) {
            return new Raw("CURRENT_DATE");
        }
        List<Expr> a = f.args().stream().map(x -> expr(x, s)).toList();
        switch (n) {
            case "NVL":
                return new Func("COALESCE", a, false, false);
            case "NVL2":
                return new Case(null, List.of(new When(new IsNull(a.get(0), true), a.get(1))), a.get(2));
            case "DECODE": {
                Expr subject = a.get(0);
                List<When> whens = new ArrayList<>();
                int k = 1;
                for (; k + 1 < a.size(); k += 2) {
                    Expr search = a.get(k);
                    Expr cond = search instanceof Null ? new IsNull(subject, false) : new Binary("=", subject, search);
                    whens.add(new When(cond, a.get(k + 1)));
                }
                return new Case(null, whens, k < a.size() ? a.get(k) : null);
            }
            case "TRUNC":
                if (a.size() == 1) {
                    if (looksLikeDate(f.args().get(0))) {
                        return cast(new Func("DATE_TRUNC", List.of(new Str("day"), a.get(0)), false, false), "DATE");
                    }
                    warn("TRUNC com um argumento: tratado como número; se for data use DATE_TRUNC");
                    return new Func("TRUNC", List.of(cast(a.get(0), "NUMERIC")), false, false);
                }
                return new Func("TRUNC", List.of(cast(a.get(0), "NUMERIC"), a.get(1)), false, false);
            case "ROUND":
                return new Func("ROUND", a.size() == 1 ? List.of(cast(a.get(0), "NUMERIC"))
                        : List.of(cast(a.get(0), "NUMERIC"), a.get(1)), false, false);
            case "TO_NUMBER":
                return a.size() == 1 ? cast(a.get(0), "NUMERIC") : new Func("TO_NUMBER", a, false, false);
            case "TO_CHAR":
                return a.size() == 1 ? cast(a.get(0), "TEXT") : new Func("TO_CHAR", a, false, false);
            case "TO_DATE":
                warn("TO_DATE no Postgres devolve só a data (sem hora); use TO_TIMESTAMP se a hora importar");
                return new Func("TO_DATE", a, false, false);
            case "INSTR":
                if (a.size() == 2) {
                    return new Func("STRPOS", a, false, false);
                }
                gap("INSTR com posição/ocorrência sem tradução automática");
                return new Func(n, a, false, false);
            case "ADD_MONTHS":
                return new Binary("+", a.get(0), new Binary("*", a.get(1), new Raw("INTERVAL '1 month'")));
            case "LAST_DAY":
                return cast(new Binary("-", new Binary("+", new Func("DATE_TRUNC", List.of(new Str("month"), a.get(0)), false, false),
                        new Raw("INTERVAL '1 month'")), new Raw("INTERVAL '1 day'")), "DATE");
            case "MONTHS_BETWEEN":
                gap("MONTHS_BETWEEN sem equivalente direto no Postgres");
                return new Func(n, a, false, false);
            case "GREATEST", "LEAST":
                warn(n + " no Postgres ignora NULL; no Oracle devolve NULL se algum argumento for nulo");
                return new Func(n, a, false, false);
            default:
                break;
        }
        if (undelphiBuiltin(n)) {
            return new Func(n, a, f.distinct(), f.star());
        }
        String mapped = profile.functions().get(n);
        if (mapped == null) {
            gap("função do Oracle " + n + " sem equivalente no destino (mapear em funcoes:)");
            return new Func(n, a, f.distinct(), f.star());
        }
        return new Func(mapped, a, f.distinct(), f.star());
    }

    /** Funções que existem com o mesmo nome e semântica no Postgres. */
    private static boolean undelphiBuiltin(String n) {
        return Set.of("COALESCE", "SUM", "COUNT", "MAX", "MIN", "AVG", "SUBSTR", "LENGTH", "UPPER", "LOWER", "TRIM",
                "LTRIM", "RTRIM", "LPAD", "RPAD", "REPLACE", "ABS", "CEIL", "FLOOR", "MOD", "POWER", "SQRT", "SIGN",
                "CHR", "ASCII", "CONCAT", "NULLIF", "INITCAP", "TRANSLATE", "EXP", "LN", "CAST", "EXTRACT", "TO_TIMESTAMP",
                "REGEXP_REPLACE", "STDDEV", "VARIANCE").contains(n);
    }

    private static String lastPart(String dotted) {
        int i = dotted.lastIndexOf('.');
        return i < 0 ? dotted : dotted.substring(i + 1);
    }
}
