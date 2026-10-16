package undelphi.sql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import undelphi.sql.Ast.*;
import undelphi.sql.SqlLexer.Token;
import undelphi.sql.SqlLexer.Type;

/**
 * Parser de SQL/PL-SQL do Oracle por descida recursiva. Nunca falha no nível do script: o que não
 * entende vira {@link RawStmt} (com o motivo), para o inventário seguir com o resto.
 */
public final class SqlParser {

    /** Palavras que não podem ser alias nem nome de coluna sem aspas. */
    static final Set<String> RESERVED = Set.of("FROM", "WHERE", "GROUP", "ORDER", "HAVING", "UNION", "MINUS",
            "INTERSECT", "INTO", "CONNECT", "START", "AND", "OR", "NOT", "ON", "JOIN", "LEFT", "RIGHT", "INNER",
            "FULL", "CROSS", "OUTER", "WHEN", "THEN", "ELSE", "END", "AS", "IS", "IN", "BETWEEN", "LIKE", "SELECT",
            "SET", "VALUES", "LOOP", "FOR", "USING", "WITH", "BY", "ASC", "DESC", "NULLS", "FETCH", "OFFSET",
            "RETURNING", "EXCEPTION", "BEGIN", "IF", "ELSIF", "NATURAL", "OVER", "ESCAPE", "PRIOR", "CASE",
            "DISTINCT", "ALL", "EXISTS", "NULL", "UNIQUE", "DECLARE");

    public static final class ParseError extends RuntimeException {
        public ParseError(String message) {
            super(message);
        }
    }

    private final String src;
    private final List<Token> t;
    private int p;

    private SqlParser(String src) {
        this.src = src;
        this.t = SqlLexer.tokenize(src);
    }

    /** Script inteiro: um ou mais comandos. Nunca lança exceção. */
    public static List<Stmt> parse(String sql) {
        return new SqlParser(sql).script();
    }

    /** Uma expressão isolada (lança ParseError se sobrar texto). */
    public static Expr parseExpression(String sql) {
        SqlParser parser = new SqlParser(sql);
        Expr e = parser.expr(0);
        if (parser.peek().type() != Type.EOF) {
            throw new ParseError("texto sobrando depois da expressão: " + parser.peek().text());
        }
        return e;
    }

    // ---------------------------------------------------------------- script e comandos

    private List<Stmt> script() {
        List<Stmt> out = new ArrayList<>();
        while (peek().type() != Type.EOF) {
            if (at(";") || at("/")) {
                p++;
                continue;
            }
            int start = p;
            try {
                out.add(statement());
                accept(";");
            }
            catch (ParseError | IndexOutOfBoundsException | NumberFormatException e) {
                p = start;
                boolean procedural = at("BEGIN") || at("DECLARE");
                int end = procedural ? t.size() - 1 : findStatementEnd(start);
                out.add(new RawStmt(text(start, end), e.getMessage()));
                p = end;
            }
        }
        return out;
    }

    /** Próximo ';' fora de parênteses (ou o fim). */
    private int findStatementEnd(int from) {
        int depth = 0;
        for (int i = from; i < t.size(); i++) {
            Token k = t.get(i);
            if (k.type() == Type.EOF) {
                return i;
            }
            if (k.is("(")) {
                depth++;
            } else if (k.is(")")) {
                depth--;
            } else if (k.is(";") && depth <= 0) {
                return i;
            }
        }
        return t.size() - 1;
    }

    private Stmt statement() {
        Token k = peek();
        if (k.is("SELECT") || k.is("WITH") || (k.is("(") && peek(1).is("SELECT"))) {
            return query();
        }
        if (k.is("INSERT")) {
            return insert();
        }
        if (k.is("UPDATE")) {
            return update();
        }
        if (k.is("DELETE")) {
            return delete();
        }
        if (k.is("BEGIN") || k.is("DECLARE")) {
            return block();
        }
        if (k.is("CREATE")) {
            return create();
        }
        if (k.is("FOR")) {
            return forLoop();
        }
        if (k.is("IF")) {
            return ifStmt();
        }
        if (k.is("RETURN")) {
            p++;
            return new Return(at(";") ? null : expr(0));
        }
        if (k.is("NULL") && peek(1).is(";")) {
            p++;
            return new NullStmt();
        }
        if (k.is("MERGE") || k.is("ALTER") || k.is("DROP") || k.is("TRUNCATE") || k.is("GRANT") || k.is("COMMIT")
                || k.is("ROLLBACK") || k.is("LOCK") || k.is("EXECUTE") || k.is("SAVEPOINT") || k.is("EXIT")
                || k.is("OPEN") || k.is("FETCH") || k.is("CLOSE") || k.is("RAISE") || k.is("WHILE") || k.is("GOTO")) {
            if (k.is("WHILE")) {
                throw new ParseError("WHILE ainda não suportado");
            }
            int start = p;
            int end = findStatementEnd(start);
            p = end;
            return new RawStmt(text(start, end), "comando " + k.text().toUpperCase() + " não interpretado");
        }
        if (k.isIdent()) {
            int start = p;
            Expr target = primary();
            if (at(":=")) {
                p++;
                return new Assign(target, expr(0));
            }
            if (target instanceof Func f) {
                return new Call(f.name(), f.args());
            }
            if (target instanceof Col c && (at(";") || peek().type() == Type.EOF)) {
                return new Call(String.join(".", c.parts()), List.of());
            }
            p = start;
        }
        throw new ParseError("comando não reconhecido: " + k.text());
    }

    private Query query() {
        if (at("WITH")) {
            throw new ParseError("WITH (CTE) ainda não suportado");
        }
        if (at("(") && peek(1).is("SELECT")) {
            p++;
            Query inner = query();
            expect(")");
            return inner;
        }
        QueryBlock first = queryBlock();
        List<SetOp> ops = new ArrayList<>();
        while (at("UNION") || at("MINUS") || at("INTERSECT") || at("EXCEPT")) {
            String op = next().text().toUpperCase();
            if (at("ALL")) {
                p++;
                op += " ALL";
            }
            QueryBlock b;
            if (at("(")) {
                p++;
                b = queryBlock();
                expect(")");
            } else {
                b = queryBlock();
            }
            ops.add(new SetOp(op, b));
        }
        List<OrderItem> order = new ArrayList<>();
        if (at("ORDER")) {
            p++;
            expect("BY");
            do {
                Expr e = expr(0);
                boolean desc = false;
                if (at("ASC")) {
                    p++;
                } else if (at("DESC")) {
                    p++;
                    desc = true;
                }
                String nulls = null;
                if (at("NULLS")) {
                    p++;
                    nulls = next().text().toUpperCase();
                }
                order.add(new OrderItem(e, desc, nulls));
            } while (accept(","));
        }
        boolean forUpdate = false;
        if (at("FOR") && peek(1).is("UPDATE")) {
            p += 2;
            forUpdate = true;
            if (at("OF")) {
                p++;
                do {
                    primary();
                } while (accept(","));
            }
            if (at("NOWAIT")) {
                p++;
            } else if (at("WAIT")) {
                p += 2;
            } else if (at("SKIP")) {
                p += 2;
            }
        }
        return new Query(first, ops, order, forUpdate);
    }

    private QueryBlock queryBlock() {
        expect("SELECT");
        boolean distinct = false;
        if (at("DISTINCT") || at("UNIQUE")) {
            p++;
            distinct = true;
        } else if (at("ALL")) {
            p++;
        }
        List<SelectItem> items = new ArrayList<>();
        do {
            Expr e = expr(0);
            String alias = null;
            if (at("AS")) {
                p++;
                alias = next().name();
            } else if (peek().isIdent() && !RESERVED.contains(peek().name())) {
                alias = next().name();
            }
            items.add(new SelectItem(e, alias));
        } while (accept(","));
        List<String> into = new ArrayList<>();
        if (at("INTO")) {
            p++;
            do {
                into.add(colText(primary()));
            } while (accept(","));
        }
        List<FromItem> from = new ArrayList<>();
        if (at("FROM")) {
            p++;
            do {
                from.add(fromItem());
            } while (accept(","));
        }
        Expr where = null;
        String connectBy = null;
        if (at("WHERE")) {
            p++;
            where = expr(0);
        }
        if (at("START") || at("CONNECT")) {
            int start = p;
            while (!(peek().type() == Type.EOF || at("GROUP") || at("ORDER") || at(")") || at(";") || at("UNION")
                    || at("MINUS") || at("INTERSECT"))) {
                if (at("(")) {
                    skipParens();
                } else {
                    p++;
                }
            }
            connectBy = text(start, p);
        }
        List<Expr> groupBy = new ArrayList<>();
        Expr having = null;
        if (at("GROUP")) {
            p++;
            expect("BY");
            do {
                groupBy.add(expr(0));
            } while (accept(","));
        }
        if (at("HAVING")) {
            p++;
            having = expr(0);
        }
        return new QueryBlock(distinct, items, into, from, where, connectBy, groupBy, having);
    }

    private FromItem fromItem() {
        FromItem left = fromPrimary();
        while (true) {
            String type = null;
            if (at("JOIN")) {
                type = "JOIN";
                p++;
            } else if (at("INNER") && peek(1).is("JOIN")) {
                type = "JOIN";
                p += 2;
            } else if ((at("LEFT") || at("RIGHT") || at("FULL")) && (peek(1).is("JOIN") || peek(1).is("OUTER"))) {
                type = next().text().toUpperCase() + " JOIN";
                accept("OUTER");
                expect("JOIN");
            } else if (at("CROSS") && peek(1).is("JOIN")) {
                type = "CROSS JOIN";
                p += 2;
            } else if (at("NATURAL")) {
                throw new ParseError("NATURAL JOIN não suportado");
            }
            if (type == null) {
                return left;
            }
            FromItem right = fromPrimary();
            Expr on = null;
            if (at("ON")) {
                p++;
                on = expr(0);
            } else if (at("USING")) {
                throw new ParseError("JOIN ... USING não suportado");
            }
            left = new JoinRef(left, type, right, on);
        }
    }

    private FromItem fromPrimary() {
        if (at("(")) {
            p++;
            Query q = query();
            expect(")");
            return new SubqueryRef(q, optionalAlias());
        }
        if (at("TABLE") && peek(1).is("(")) {
            throw new ParseError("TABLE(...) não suportado");
        }
        String name = qualifiedName();
        return new TableRef(name, optionalAlias());
    }

    private String optionalAlias() {
        if (at("AS")) {
            p++;
            return next().name();
        }
        if (peek().isIdent() && !RESERVED.contains(peek().name()) && !at("PARTITION") && !at("SAMPLE")) {
            return next().name();
        }
        return null;
    }

    /** schema.tabela@dblink. */
    private String qualifiedName() {
        Token first = next();
        if (!first.isIdent()) {
            throw new ParseError("esperava nome de tabela, veio " + first.text());
        }
        StringBuilder sb = new StringBuilder(first.name());
        while (at(".") && peek(1).isIdent()) {
            p++;
            sb.append('.').append(next().name());
        }
        if (at("@")) {
            p++;
            sb.append('@').append(next().name());
        }
        return sb.toString();
    }

    private Insert insert() {
        expect("INSERT");
        expect("INTO");
        String table = qualifiedName();
        String alias = null;
        if (peek().isIdent() && !RESERVED.contains(peek().name())) {
            alias = next().name();
        }
        List<String> cols = new ArrayList<>();
        if (at("(") && !peek(1).is("SELECT")) {
            p++;
            do {
                cols.add(colText(primary()));
            } while (accept(","));
            expect(")");
        }
        if (at("VALUES")) {
            p++;
            expect("(");
            List<Expr> values = new ArrayList<>();
            do {
                values.add(expr(0));
            } while (accept(","));
            expect(")");
            skipReturning();
            return new Insert(table, alias, cols, values, null);
        }
        Query q = query();
        skipReturning();
        return new Insert(table, alias, cols, List.of(), q);
    }

    private Update update() {
        expect("UPDATE");
        String table = qualifiedName();
        String alias = optionalAlias();
        expect("SET");
        List<Assignment> sets = new ArrayList<>();
        do {
            if (at("(")) {
                throw new ParseError("UPDATE ... SET (colunas) = (subconsulta) não suportado");
            }
            Expr target = primary();
            expect("=");
            sets.add(new Assignment(target, expr(0)));
        } while (accept(","));
        Expr where = null;
        if (at("WHERE")) {
            p++;
            where = expr(0);
        }
        skipReturning();
        return new Update(table, alias, sets, where);
    }

    private Delete delete() {
        expect("DELETE");
        accept("FROM");
        String table = qualifiedName();
        String alias = optionalAlias();
        Expr where = null;
        if (at("WHERE")) {
            p++;
            where = expr(0);
        }
        skipReturning();
        return new Delete(table, alias, where);
    }

    private void skipReturning() {
        if (at("RETURNING") || at("RETURN")) {
            while (!(at(";") || peek().type() == Type.EOF)) {
                p++;
            }
        }
    }

    private Block block() {
        List<String> decls = new ArrayList<>();
        if (at("DECLARE")) {
            p++;
            while (!at("BEGIN")) {
                if (peek().type() == Type.EOF) {
                    throw new ParseError("DECLARE sem BEGIN");
                }
                int start = p;
                int end = findStatementEnd(start);
                decls.add(text(start, end));
                p = end;
                accept(";");
            }
        }
        expect("BEGIN");
        List<Stmt> body = statements(Set.of("END", "EXCEPTION"));
        List<Stmt> handlers = new ArrayList<>();
        if (at("EXCEPTION")) {
            p++;
            while (at("WHEN")) {
                int start = p;
                while (!at("THEN")) {
                    if (peek().type() == Type.EOF) {
                        throw new ParseError("WHEN sem THEN no EXCEPTION");
                    }
                    p++;
                }
                p++;
                handlers.add(new RawStmt(text(start, p), "tratador de exceção"));
                handlers.addAll(statements(Set.of("END", "WHEN")));
            }
        }
        expect("END");
        if (peek().isIdent() && !at("LOOP") && !at("IF")) {
            p++; // rótulo
        }
        return new Block(decls, body, handlers);
    }

    private List<Stmt> statements(Set<String> terminators) {
        List<Stmt> out = new ArrayList<>();
        while (true) {
            Token k = peek();
            if (k.type() == Type.EOF) {
                throw new ParseError("bloco sem END");
            }
            if (k.type() == Type.IDENT && terminators.contains(k.name())) {
                return out;
            }
            if (k.is(";")) {
                p++;
                continue;
            }
            out.add(statement());
            expect(";");
        }
    }

    private ForLoop forLoop() {
        expect("FOR");
        String rec = next().name();
        expect("IN");
        Query cursor = null;
        if (at("(") && (peek(1).is("SELECT") || peek(1).is("WITH"))) {
            p++;
            cursor = query();
            expect(")");
        } else {
            while (!at("LOOP")) {
                if (peek().type() == Type.EOF) {
                    throw new ParseError("FOR sem LOOP");
                }
                p++;
            }
        }
        expect("LOOP");
        List<Stmt> body = statements(Set.of("END"));
        expect("END");
        expect("LOOP");
        return new ForLoop(rec, cursor, body);
    }

    private If ifStmt() {
        expect("IF");
        List<Branch> branches = new ArrayList<>();
        Expr cond = expr(0);
        expect("THEN");
        branches.add(new Branch(cond, statements(Set.of("ELSIF", "ELSE", "END"))));
        List<Stmt> elseBody = List.of();
        while (true) {
            if (at("ELSIF")) {
                p++;
                Expr c = expr(0);
                expect("THEN");
                branches.add(new Branch(c, statements(Set.of("ELSIF", "ELSE", "END"))));
            } else if (at("ELSE")) {
                p++;
                elseBody = statements(Set.of("END"));
            } else {
                break;
            }
        }
        expect("END");
        expect("IF");
        return new If(branches, elseBody);
    }

    private CreateObject create() {
        int start = p;
        expect("CREATE");
        if (at("OR")) {
            p++;
            expect("REPLACE");
        }
        StringBuilder kind = new StringBuilder(next().text().toUpperCase());
        if (kind.toString().equals("PACKAGE") && at("BODY")) {
            p++;
            kind.append(" BODY");
        }
        String name = qualifiedName();
        p = t.size() - 1; // o objeto vai até o fim do texto
        return new CreateObject(kind.toString(), name, text(start, p));
    }

    // ---------------------------------------------------------------- expressões

    private static int binaryPrecedence(Token k) {
        if (k.is("OR")) {
            return 1;
        }
        if (k.is("AND")) {
            return 2;
        }
        if (k.is("=") || k.is("<>") || k.is("!=") || k.is("^=") || k.is("<") || k.is(">") || k.is("<=")
                || k.is(">=") || k.is("LIKE") || k.is("IN") || k.is("BETWEEN") || k.is("IS") || k.is("NOT")) {
            return 4;
        }
        if (k.is("+") || k.is("-") || k.is("||")) {
            return 5;
        }
        if (k.is("*") || k.is("/")) {
            return 6;
        }
        return -1;
    }

    private Expr expr(int minPrec) {
        Expr left = unary();
        while (true) {
            Token k = peek();
            int prec = binaryPrecedence(k);
            if (prec < 0 || prec < minPrec) {
                return left;
            }
            if (k.is("NOT")) {
                Token n = peek(1);
                if (!(n.is("IN") || n.is("LIKE") || n.is("BETWEEN"))) {
                    return left;
                }
                p++;
                left = negatable(left, true);
                continue;
            }
            if (k.is("IN") || k.is("LIKE") || k.is("BETWEEN")) {
                left = negatable(left, false);
                continue;
            }
            if (k.is("IS")) {
                p++;
                boolean not = accept("NOT");
                expect("NULL");
                left = new IsNull(left, not);
                continue;
            }
            p++;
            String op = k.text().toUpperCase();
            if (op.equals("!=") || op.equals("^=")) {
                op = "<>";
            }
            Expr right = expr(prec + 1);
            left = new Binary(op, left, right);
        }
    }

    private Expr negatable(Expr left, boolean not) {
        Token k = next();
        if (k.is("IN")) {
            expect("(");
            if (at("SELECT") || at("WITH")) {
                Query q = query();
                expect(")");
                return new In(left, List.of(), q, not);
            }
            List<Expr> list = new ArrayList<>();
            do {
                list.add(expr(0));
            } while (accept(","));
            expect(")");
            return new In(left, list, null, not);
        }
        if (k.is("LIKE")) {
            Expr pattern = expr(5);
            if (at("ESCAPE")) {
                p++;
                expr(5);
            }
            Expr like = new Binary("LIKE", left, pattern);
            return not ? new Unary("NOT", like) : like;
        }
        Expr low = expr(5);
        expect("AND");
        Expr high = expr(5);
        return new Between(left, low, high, not);
    }

    private Expr unary() {
        if (at("NOT")) {
            p++;
            return new Unary("NOT", expr(3));
        }
        if (at("-") || at("+")) {
            String op = next().text();
            Expr e = expr(7);
            if (op.equals("-") && e instanceof Num n) {
                return new Num(n.value().negate());
            }
            return op.equals("-") ? new Unary("-", e) : e;
        }
        if (at("PRIOR")) {
            p++;
            return new Unary("PRIOR", expr(7));
        }
        if (at("EXISTS")) {
            p++;
            expect("(");
            Query q = query();
            expect(")");
            return new Exists(q);
        }
        return primary();
    }

    private Expr primary() {
        Token k = next();
        switch (k.type()) {
            case NUMBER:
                return new Num(new BigDecimal(k.text()));
            case STRING:
                return new Str(k.text());
            case PARAM:
                return new Param(k.text());
            default:
                break;
        }
        if (k.is("(")) {
            if (at("SELECT") || at("WITH")) {
                Query q = query();
                expect(")");
                return new SubqueryExpr(q);
            }
            Expr e = expr(0);
            if (at(",")) {
                List<Expr> items = new ArrayList<>(List.of(e));
                while (accept(",")) {
                    items.add(expr(0));
                }
                expect(")");
                return new Tuple(items);
            }
            expect(")");
            return e;
        }
        if (k.is("*")) {
            return new Star(null);
        }
        if (k.is("CASE")) {
            return caseExpr();
        }
        if (k.is("NULL")) {
            return new Null();
        }
        if (!k.isIdent()) {
            throw new ParseError("expressão inesperada: " + k.text());
        }
        if (k.type() == Type.IDENT && RESERVED.contains(k.name())) {
            throw new ParseError("palavra reservada no lugar de expressão: " + k.text());
        }
        if ((k.is("DATE") || k.is("TIMESTAMP")) && peek().type() == Type.STRING) {
            String lit = next().text();
            return new Func("TO_DATE", List.of(new Str(lit), new Str(k.is("DATE") ? "YYYY-MM-DD" : "YYYY-MM-DD HH24:MI:SS")),
                    false, false);
        }
        List<String> parts = new ArrayList<>(List.of(k.name()));
        while (at(".")) {
            p++;
            if (at("*")) {
                p++;
                return new Star(String.join(".", parts));
            }
            Token part = next();
            if (!part.isIdent()) {
                throw new ParseError("nome inválido depois de '.': " + part.text());
            }
            parts.add(part.name());
        }
        if (at("(")) {
            return functionCall(String.join(".", parts), k.pos());
        }
        boolean outer = false;
        if (at("(+)")) {
            p++;
            outer = true;
        }
        return new Col(parts, outer);
    }

    private Expr functionCall(String name, int startPos) {
        int startToken = p - 1;
        while (startToken > 0 && t.get(startToken).pos() > startPos) {
            startToken--;
        }
        expect("(");
        List<Expr> args = new ArrayList<>();
        boolean distinct = false;
        boolean star = false;
        if (name.equals("CAST")) {
            Expr e = expr(0);
            expect("AS");
            int s = p;
            while (!at(")")) {
                if (at("(")) {
                    skipParens();
                } else {
                    p++;
                }
            }
            String type = text(s, p);
            expect(")");
            return new Func("CAST", List.of(e, new Raw(type)), false, false);
        }
        if (name.equals("EXTRACT")) {
            String field = next().name();
            expect("FROM");
            Expr e = expr(0);
            expect(")");
            return new Func("EXTRACT", List.of(new Raw(field), e), false, false);
        }
        if (at("*")) {
            p++;
            star = true;
        } else if (!at(")")) {
            if (at("DISTINCT")) {
                p++;
                distinct = true;
            } else if (at("ALL")) {
                p++;
            }
            do {
                if (peek().isIdent() && peek(1).is("=>")) {
                    p += 2; // argumento nomeado: fica só o valor
                }
                args.add(expr(0));
            } while (accept(","));
        }
        expect(")");
        if (at("OVER") || at("KEEP") || at("WITHIN")) {
            while (at("OVER") || at("KEEP") || at("WITHIN")) {
                p++;
                accept("GROUP");
                skipParens();
            }
            return new Raw(text(startToken, p));
        }
        return new Func(name, args, distinct, star);
    }

    private Expr caseExpr() {
        Expr subject = null;
        if (!at("WHEN")) {
            subject = expr(0);
        }
        List<When> whens = new ArrayList<>();
        while (at("WHEN")) {
            p++;
            Expr cond = expr(0);
            expect("THEN");
            whens.add(new When(cond, expr(0)));
        }
        Expr elseExpr = null;
        if (at("ELSE")) {
            p++;
            elseExpr = expr(0);
        }
        expect("END");
        if (whens.isEmpty()) {
            throw new ParseError("CASE sem WHEN");
        }
        return new Case(subject, whens, elseExpr);
    }

    // ---------------------------------------------------------------- utilitários

    private static String colText(Expr e) {
        if (e instanceof Col c) {
            return String.join(".", c.parts());
        }
        throw new ParseError("esperava nome de coluna/variável");
    }

    private void skipParens() {
        expect("(");
        int depth = 1;
        while (depth > 0) {
            Token k = next();
            if (k.type() == Type.EOF) {
                throw new ParseError("parênteses sem fechar");
            }
            if (k.is("(")) {
                depth++;
            } else if (k.is(")")) {
                depth--;
            }
        }
    }

    private Token peek() {
        return t.get(Math.min(p, t.size() - 1));
    }

    private Token peek(int ahead) {
        return t.get(Math.min(p + ahead, t.size() - 1));
    }

    private Token next() {
        Token k = peek();
        if (p < t.size() - 1) {
            p++;
        }
        return k;
    }

    private boolean at(String word) {
        Token k = peek();
        return k.type() != Type.STRING && k.type() != Type.QUOTED_IDENT && k.type() != Type.PARAM && k.text().equalsIgnoreCase(word);
    }

    private boolean accept(String word) {
        if (at(word)) {
            p++;
            return true;
        }
        return false;
    }

    private void expect(String word) {
        if (!accept(word)) {
            throw new ParseError("esperava " + word + ", veio '" + peek().text() + "' na posição " + peek().pos());
        }
    }

    private String text(int fromToken, int toToken) {
        int a = t.get(Math.min(fromToken, t.size() - 1)).pos();
        int b = t.get(Math.min(toToken, t.size() - 1)).pos();
        return src.substring(Math.min(a, src.length()), Math.min(Math.max(a, b), src.length())).strip();
    }
}
