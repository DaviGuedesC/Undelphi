package undelphi.sql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import undelphi.sql.Ast.*;
import undelphi.sql.SqlLexer.Token;
import undelphi.sql.SqlLexer.Type;

/** Analisa uma {@link SqlSource}: monta a AST e extrai o que ela faz no banco. */
public final class SqlAnalyzer {

    /** Funções nativas do Oracle (o resto que aparece como função é objeto do banco: regra fora do exe). */
    static final Set<String> BUILTINS = Set.of("NVL", "NVL2", "DECODE", "ROUND", "TRUNC", "TO_CHAR", "TO_DATE",
            "TO_NUMBER", "TO_TIMESTAMP", "SUM", "COUNT", "MAX", "MIN", "AVG", "SUBSTR", "INSTR", "LENGTH", "UPPER",
            "LOWER", "TRIM", "LTRIM", "RTRIM", "LPAD", "RPAD", "REPLACE", "COALESCE", "ABS", "CEIL", "FLOOR", "MOD",
            "POWER", "SQRT", "SIGN", "GREATEST", "LEAST", "ADD_MONTHS", "MONTHS_BETWEEN", "LAST_DAY", "NEXT_DAY",
            "EXTRACT", "CAST", "CHR", "ASCII", "CONCAT", "NULLIF", "REGEXP_LIKE", "REGEXP_REPLACE", "REGEXP_SUBSTR",
            "REGEXP_INSTR", "INITCAP", "TRANSLATE", "SYS_CONTEXT", "USERENV", "ROWIDTOCHAR", "TO_CLOB", "LISTAGG",
            "STDDEV", "VARIANCE", "MEDIAN", "SYS_GUID", "DUMP", "VSIZE", "BITAND", "NUMTODSINTERVAL", "LNNVL", "EXP",
            "LN", "LOG", "SYSDATE", "WM_CONCAT", "TO_NCHAR", "NLS_UPPER", "SOUNDEX", "UNISTR");

    /** Pseudocolunas e variáveis do Oracle. */
    static final Set<String> PSEUDO = Set.of("SYSDATE", "SYSTIMESTAMP", "ROWNUM", "ROWID", "LEVEL", "USER", "UID",
            "CURRENT_DATE", "CURRENT_TIMESTAMP", "LOCALTIMESTAMP", "SQL%ROWCOUNT", "SQLERRM", "SQLCODE", "TRUE", "FALSE");

    private SqlAnalyzer() {
    }

    public static SqlAnalysis analyze(SqlSource source) {
        if (source.isStoredProcName()) {
            SqlAnalysis a = new SqlAnalysis(source, List.of());
            if (source.sql() != null && !source.sql().isBlank()) {
                a.kind = SqlAnalysis.Kind.CHAMADA;
                a.calls.add(source.sql().strip().toUpperCase());
            }
            return a;
        }
        String sql = source.sql() == null ? "" : source.sql();
        List<Stmt> stmts = SqlParser.parse(sql);
        SqlAnalysis a = new SqlAnalysis(source, stmts);
        new Walker(a).run();
        return a;
    }

    // ---------------------------------------------------------------- escopos

    private enum RefKind {
        TABLE, DERIVED, RECORD
    }

    private record Ref(RefKind kind, String table, Set<String> columns) {
    }

    private static final class Scope {
        final Scope parent;
        final Map<String, Ref> refs = new HashMap<>();
        boolean hasFrom;

        Scope(Scope parent) {
            this.parent = parent;
        }

        Ref lookup(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                Ref r = s.refs.get(name);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }

        /** Escopo de consulta mais interno (o que tem FROM). */
        Scope queryScope() {
            for (Scope s = this; s != null; s = s.parent) {
                if (s.hasFrom) {
                    return s;
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- percurso

    private static final class Walker {
        private final SqlAnalysis a;
        private boolean inDml;

        Walker(SqlAnalysis a) {
            this.a = a;
        }

        void run() {
            List<Stmt> stmts = a.statements();
            if (stmts.isEmpty()) {
                a.kind = SqlAnalysis.Kind.VAZIA;
                return;
            }
            a.kind = kindOf(stmts.get(0));
            for (Stmt s : stmts) {
                stmt(s, new Scope(null));
            }
            if (a.kind == SqlAnalysis.Kind.CONSULTA && stmts.stream().anyMatch(s -> kindOf(s) == SqlAnalysis.Kind.GRAVACAO)) {
                a.kind = SqlAnalysis.Kind.GRAVACAO;
            }
        }

        static SqlAnalysis.Kind kindOf(Stmt s) {
            return switch (s) {
                case Query q -> SqlAnalysis.Kind.CONSULTA;
                case Insert i -> SqlAnalysis.Kind.GRAVACAO;
                case Update u -> SqlAnalysis.Kind.GRAVACAO;
                case Delete d -> SqlAnalysis.Kind.GRAVACAO;
                case Block b -> SqlAnalysis.Kind.PLSQL;
                case CreateObject c -> SqlAnalysis.Kind.DDL;
                case Call c -> SqlAnalysis.Kind.CHAMADA;
                case RawStmt r -> r.reason() != null && r.reason().startsWith("comando ") ? rawKind(r.text())
                        : SqlAnalysis.Kind.FRAGMENTO;
                default -> SqlAnalysis.Kind.PLSQL;
            };
        }

        static SqlAnalysis.Kind rawKind(String text) {
            String up = text.stripLeading().toUpperCase();
            if (up.startsWith("MERGE")) {
                return SqlAnalysis.Kind.GRAVACAO;
            }
            if (up.startsWith("ALTER") || up.startsWith("DROP") || up.startsWith("TRUNCATE") || up.startsWith("GRANT")) {
                return SqlAnalysis.Kind.DDL;
            }
            if (up.startsWith("EXEC")) {
                return SqlAnalysis.Kind.CHAMADA;
            }
            return SqlAnalysis.Kind.FRAGMENTO;
        }

        void stmt(Stmt s, Scope scope) {
            switch (s) {
                case Query q -> query(q, scope);
                case Insert i -> {
                    SqlAnalysis.TableUse use = a.use(i.table());
                    use.written = true;
                    i.columns().forEach(c -> use.columnsWritten.add(lastPart(c)));
                    Scope inner = new Scope(scope);
                    inner.refs.put(i.table(), new Ref(RefKind.TABLE, i.table(), null));
                    i.values().forEach(v -> expr(v, inner));
                    if (i.select() != null) {
                        query(i.select(), scope);
                    }
                }
                case Update u -> {
                    SqlAnalysis.TableUse use = a.use(u.table());
                    use.written = true;
                    Scope inner = dmlScope(u.table(), u.alias(), scope);
                    for (Assignment as : u.sets()) {
                        if (as.target() instanceof Col c) {
                            use.columnsWritten.add(c.name());
                        }
                        expr(as.value(), inner);
                        ruleIfComputed(u.table() + "." + (as.target() instanceof Col c ? c.name() : "?"), as.value());
                    }
                    dmlWhere(u.where(), inner);
                }
                case Delete d -> {
                    a.use(d.table()).written = true;
                    dmlWhere(d.where(), dmlScope(d.table(), d.alias(), scope));
                }
                case Block b -> {
                    b.body().forEach(x -> stmt(x, scope));
                    b.exceptionBody().forEach(x -> stmt(x, scope));
                }
                case ForLoop f -> {
                    if (f.cursor() != null) {
                        query(f.cursor(), scope);
                    }
                    Scope inner = new Scope(scope);
                    inner.refs.put(f.record(), new Ref(RefKind.RECORD, null, null));
                    f.body().forEach(x -> stmt(x, inner));
                }
                case If i -> {
                    for (Branch br : i.branches()) {
                        expr(br.condition(), scope);
                        br.body().forEach(x -> stmt(x, scope));
                    }
                    i.elseBody().forEach(x -> stmt(x, scope));
                }
                case Assign as -> expr(as.value(), scope);
                case Call c -> {
                    if (!BUILTINS.contains(c.name())) {
                        a.calls.add(c.name());
                    }
                    c.args().forEach(x -> expr(x, scope));
                }
                case Return r -> {
                    if (r.value() != null) {
                        expr(r.value(), scope);
                    }
                }
                case NullStmt n -> {
                }
                case CreateObject c -> {
                    a.created.add(c.kind() + " " + c.name());
                    createdBody(c);
                }
                case RawStmt r -> {
                    if (r.reason() != null && !r.reason().equals("tratador de exceção")) {
                        a.parseIssues.add(r.reason());
                    }
                    if (!"tratador de exceção".equals(r.reason())) {
                        tokenScan(r.text());
                    }
                }
            }
        }

        private Scope dmlScope(String table, String alias, Scope parent) {
            Scope inner = new Scope(parent);
            inner.hasFrom = true;
            Ref ref = new Ref(RefKind.TABLE, table, null);
            inner.refs.put(table, ref);
            if (alias != null) {
                inner.refs.put(alias, ref);
            }
            return inner;
        }

        private void dmlWhere(Expr where, Scope inner) {
            if (where != null) {
                boolean save = inDml;
                inDml = true;
                expr(where, inner);
                inDml = save;
            }
        }

        /** Corpo de CREATE FUNCTION/PROCEDURE: analisa como bloco DECLARE ... BEGIN ... END. */
        private void createdBody(CreateObject c) {
            List<Token> toks = SqlLexer.tokenize(c.text());
            for (int i = 0; i < toks.size(); i++) {
                Token k = toks.get(i);
                if ((k.is("IS") || k.is("AS")) && i > 2) {
                    String body = c.text().substring(k.pos() + k.text().length());
                    List<Stmt> stmts = SqlParser.parse("DECLARE " + body);
                    if (stmts.size() == 1 && stmts.get(0) instanceof Block) {
                        stmt(stmts.get(0), new Scope(null));
                    } else {
                        tokenScan(body);
                    }
                    return;
                }
            }
        }

        void query(Query q, Scope scope) {
            boolean ordered = !q.orderBy().isEmpty();
            Scope first = block(q.first(), scope, ordered);
            for (SetOp op : q.setOps()) {
                block(op.block(), scope, ordered);
            }
            for (OrderItem o : q.orderBy()) {
                expr(o.expr(), first);
            }
        }

        private Scope block(QueryBlock b, Scope parent, boolean ordered) {
            Scope s = new Scope(parent);
            s.hasFrom = !b.from().isEmpty();
            List<Expr> joinConditions = new ArrayList<>();
            for (FromItem f : b.from()) {
                register(f, s, parent, joinConditions);
            }
            for (SelectItem it : b.items()) {
                expr(it.expr(), s);
                ruleIfComputed(it.alias() != null ? it.alias() : null, it.expr());
            }
            if (b.where() != null) {
                expr(b.where(), s);
                joinConditions.add(b.where());
                if (!ordered && mentionsRownum(b.where()) && b.from().size() >= 1) {
                    a.warn("ROWNUM_SEM_ORDEM", "ROWNUM sem ORDER BY em consulta sobre " + fromNames(b)
                            + ": o Oracle devolve uma linha qualquer; o resultado pode variar.");
                }
            }
            for (Expr j : joinConditions) {
                if (j != b.where()) {
                    expr(j, s);
                }
                lintJoins(j, s);
            }
            b.groupBy().forEach(g -> expr(g, s));
            if (b.having() != null) {
                expr(b.having(), s);
            }
            if (b.items().stream().anyMatch(i -> i.expr() instanceof Star)) {
                a.warn("SELECT_ASTERISCO", "SELECT * em " + fromNames(b)
                        + ": as colunas devolvidas dependem da estrutura da tabela no Oracle.");
            }
            return s;
        }

        private static String fromNames(QueryBlock b) {
            List<String> names = new ArrayList<>();
            for (FromItem f : b.from()) {
                collectNames(f, names);
            }
            return String.join(", ", names);
        }

        private static void collectNames(FromItem f, List<String> out) {
            switch (f) {
                case TableRef t -> out.add(t.name());
                case SubqueryRef s -> out.add(s.alias() != null ? s.alias() : "(subconsulta)");
                case JoinRef j -> {
                    collectNames(j.left(), out);
                    collectNames(j.right(), out);
                }
            }
        }

        private void register(FromItem f, Scope s, Scope parent, List<Expr> joinConditions) {
            switch (f) {
                case TableRef t -> {
                    if (t.name().equals("DUAL")) {
                        return;
                    }
                    a.use(t.name()).read = true;
                    Ref ref = new Ref(RefKind.TABLE, t.name(), null);
                    s.refs.put(t.refName(), ref);
                    if (t.alias() != null) {
                        s.refs.putIfAbsent(t.name(), ref);
                    }
                }
                case SubqueryRef sq -> {
                    query(sq.query(), parent);
                    Set<String> cols = new HashSet<>();
                    for (SelectItem it : sq.query().first().items()) {
                        if (it.alias() != null) {
                            cols.add(it.alias());
                        } else if (it.expr() instanceof Col c) {
                            cols.add(c.name());
                        }
                    }
                    if (sq.alias() != null) {
                        s.refs.put(sq.alias(), new Ref(RefKind.DERIVED, null, cols));
                    }
                }
                case JoinRef j -> {
                    register(j.left(), s, parent, joinConditions);
                    register(j.right(), s, parent, joinConditions);
                    if (j.on() != null) {
                        joinConditions.add(j.on());
                    }
                    if (j.type().startsWith("LEFT") || j.type().startsWith("RIGHT") || j.type().startsWith("FULL")) {
                        FromItem outerSide = j.type().startsWith("LEFT") ? j.right() : j.left();
                        if (outerSide instanceof TableRef t) {
                            a.use(t.name()).outerJoined = true;
                        }
                    }
                }
            }
        }

        void expr(Expr e, Scope s) {
            switch (e) {
                case Col c -> column(c, s);
                case Param p -> a.params.add(p.name());
                case Func f -> {
                    if (!BUILTINS.contains(f.name())) {
                        a.calls.add(f.name());
                    }
                    if (f.name().equals("NVL") && f.args().size() == 2 && f.args().get(1) instanceof Num n
                            && n.value().signum() != 0 && f.args().get(0) instanceof Col c) {
                        a.warn("NVL_PADRAO", "NVL(" + String.join(".", c.parts()) + ", " + n.value().toPlainString()
                                + "): quando a coluna é nula o valor vira " + n.value().toPlainString()
                                + " (conferir se não deveria ser 0).");
                    }
                    f.args().forEach(x -> expr(x, s));
                }
                case Unary u -> expr(u.expr(), s);
                case Binary b -> {
                    expr(b.left(), s);
                    expr(b.right(), s);
                }
                case Case c -> {
                    if (c.subject() != null) {
                        expr(c.subject(), s);
                    }
                    for (When w : c.whens()) {
                        expr(w.condition(), s);
                        expr(w.result(), s);
                    }
                    if (c.elseExpr() != null) {
                        expr(c.elseExpr(), s);
                    }
                }
                case IsNull i -> expr(i.expr(), s);
                case In i -> {
                    expr(i.expr(), s);
                    i.list().forEach(x -> expr(x, s));
                    if (i.subquery() != null) {
                        query(i.subquery(), s);
                    }
                }
                case Between b -> {
                    expr(b.expr(), s);
                    expr(b.low(), s);
                    expr(b.high(), s);
                }
                case Exists x -> query(x.subquery(), s);
                case SubqueryExpr q -> query(q.query(), s);
                case Tuple t -> t.items().forEach(x -> expr(x, s));
                case Num n -> {
                }
                case Str x -> {
                }
                case Null n -> {
                }
                case Star x -> {
                }
                case Raw r -> tokenScanExpr(r.text(), s);
            }
        }

        private void column(Col c, Scope s) {
            String name = c.name();
            if (c.parts().size() >= 2) {
                String q = c.qualifier();
                if (name.equals("NEXTVAL") || name.equals("CURRVAL")) {
                    a.sequences.add(q);
                    return;
                }
                Ref r = s.lookup(q);
                if (r == null && c.parts().size() == 3) {
                    r = s.lookup(c.parts().get(1));
                }
                if (r != null && r.kind() == RefKind.TABLE) {
                    SqlAnalysis.TableUse use = a.use(r.table());
                    use.columnsRead.add(name);
                    if (c.outer()) {
                        use.outerJoined = true;
                    }
                }
                return;
            }
            if (PSEUDO.contains(name)) {
                return;
            }
            Scope qs = s.queryScope();
            if (qs == null) {
                return; // variável de PL/SQL
            }
            List<Ref> tables = qs.refs.values().stream().distinct().toList();
            if (tables.size() == 1 && tables.get(0).kind() == RefKind.TABLE) {
                a.use(tables.get(0).table()).columnsRead.add(name);
                if (c.outer()) {
                    a.use(tables.get(0).table()).outerJoined = true;
                }
            } else if (!tables.isEmpty()) {
                a.unresolvedColumns.add(name);
            }
        }

        private void ruleIfComputed(String name, Expr e) {
            if (e instanceof Col || e instanceof Param || e instanceof Num || e instanceof Str || e instanceof Null
                    || e instanceof Star || e instanceof SubqueryExpr || e instanceof Raw) {
                return;
            }
            if (e instanceof Func f && (f.name().equals("COUNT") || f.args().isEmpty())) {
                return;
            }
            if (name == null) {
                return;
            }
            String text = SqlPrinter.oracle(e);
            if (a.rules.stream().noneMatch(r -> r.name().equals(name) && r.oracleText().equals(text))) {
                a.rules.add(new SqlAnalysis.Rule(name, e, text));
            }
        }

        private static boolean mentionsRownum(Expr e) {
            return switch (e) {
                case Col c -> c.parts().size() == 1 && c.name().equals("ROWNUM");
                case Binary b -> mentionsRownum(b.left()) || mentionsRownum(b.right());
                case Unary u -> mentionsRownum(u.expr());
                case Between b -> mentionsRownum(b.expr());
                default -> false;
            };
        }

        /** Join com colunas de nomes diferentes que parecem chaves (ex. CODCLI = CODPLPAG). */
        private void lintJoins(Expr cond, Scope s) {
            List<Expr> parts = new ArrayList<>();
            SqlPrinter.flattenAnd(cond, parts);
            for (Expr p : parts) {
                if (!(p instanceof Binary b) || !b.op().equals("=") || !(b.left() instanceof Col l)
                        || !(b.right() instanceof Col r) || l.qualifier() == null || r.qualifier() == null) {
                    continue;
                }
                if (l.qualifier().equals(r.qualifier()) || l.name().equals(r.name())) {
                    continue;
                }
                if (!l.name().startsWith("COD") || !r.name().startsWith("COD")) {
                    continue;
                }
                Ref lr = s.lookup(l.qualifier());
                Ref rr = s.lookup(r.qualifier());
                if (lr == null || rr == null || lr.kind() == RefKind.RECORD || rr.kind() == RefKind.RECORD) {
                    continue;
                }
                String suggestion = "";
                if (lr.kind() == RefKind.DERIVED && lr.columns() != null && lr.columns().contains(r.name())) {
                    suggestion = " " + l.qualifier() + " tem a coluna " + r.name() + "; talvez o certo seja "
                            + l.qualifier() + "." + r.name() + " = " + SqlPrinter.oracle(r) + ".";
                } else if (rr.kind() == RefKind.DERIVED && rr.columns() != null && rr.columns().contains(l.name())) {
                    suggestion = " " + r.qualifier() + " tem a coluna " + l.name() + "; talvez o certo seja "
                            + SqlPrinter.oracle(l) + " = " + r.qualifier() + "." + l.name() + ".";
                }
                a.warn("JOIN_SUSPEITO", "Join compara chaves de nomes diferentes: " + SqlPrinter.oracle(b) + "." + suggestion);
            }
        }

        /** Varredura por tokens para SQL que o parser não entendeu: tabelas depois de FROM/JOIN/INTO/UPDATE. */
        private void tokenScan(String text) {
            List<Token> toks = SqlLexer.tokenize(text);
            for (int i = 0; i < toks.size(); i++) {
                Token k = toks.get(i);
                if (k.type() == Type.PARAM) {
                    a.params.add(k.text());
                }
                boolean read = k.is("FROM") || k.is("JOIN");
                boolean write = (k.is("INTO") && i > 0 && (toks.get(i - 1).is("INSERT") || toks.get(i - 1).is("MERGE")))
                        || k.is("UPDATE") || (k.is("FROM") && i > 0 && toks.get(i - 1).is("DELETE"));
                if (!read && !write) {
                    continue;
                }
                int j = i + 1;
                while (j < toks.size()) {
                    Token n = toks.get(j);
                    if (!n.isIdent() || SqlParser.RESERVED.contains(n.name())) {
                        break;
                    }
                    String name = n.name();
                    if (j + 2 < toks.size() && toks.get(j + 1).is(".") && toks.get(j + 2).isIdent()) {
                        name = name + "." + toks.get(j + 2).name();
                        j += 2;
                    }
                    if (!name.equals("DUAL")) {
                        SqlAnalysis.TableUse use = a.use(name);
                        if (write) {
                            use.written = true;
                        } else {
                            use.read = true;
                        }
                    }
                    j++;
                    if (j < toks.size() && toks.get(j).isIdent() && !SqlParser.RESERVED.contains(toks.get(j).name())) {
                        j++; // alias
                    }
                    if (j < toks.size() && toks.get(j).is(",") && read) {
                        j++;
                        continue;
                    }
                    break;
                }
            }
        }

        private void tokenScanExpr(String text, Scope s) {
            for (Token k : SqlLexer.tokenize(text)) {
                if (k.type() == Type.PARAM) {
                    a.params.add(k.text());
                }
            }
        }

        private static String lastPart(String dotted) {
            int i = dotted.lastIndexOf('.');
            return i < 0 ? dotted : dotted.substring(i + 1);
        }
    }
}
