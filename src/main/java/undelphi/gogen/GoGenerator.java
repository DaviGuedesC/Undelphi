package undelphi.gogen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import undelphi.sql.Ast.*;
import undelphi.sql.SqlAnalysis;
import undelphi.sql.SqlInventory;
import undelphi.sql.SqlPrinter;
import undelphi.target.GapReport;
import undelphi.target.OracleToPostgres;
import undelphi.target.SourceTranslator;
import undelphi.target.TargetProfile;

/**
 * Gera o código Go de uma rotina:
 * <pre>
 * out/go.mod
 * out/undelphirt/undelphirt.go   runtime com a semântica do Oracle
 * out/&lt;pacote&gt;/repo.go          Repo{DB *sql.DB}, conexão pelo perfil (variáveis de ambiente)
 * out/&lt;pacote&gt;/consultas.go     uma função por SQL do exe (SELECT → linhas; DML/PL-SQL → linhas afetadas)
 * out/&lt;pacote&gt;/regras.go        colunas calculadas da SQL (PUNIT, PTABELA...) como funções puras
 * out/relatorios/*.md            mapa do Oracle e lacunas
 * </pre>
 * Toda SQL vira função com assinatura estável; se tiver lacuna no destino, a função devolve
 * undelphirt.ErrLacuna explicando o motivo (o código que chama continua compilando).
 */
public final class GoGenerator {

    public record Result(Path outDir, String packageName, List<Path> files, int functions, int ready, int rules,
            Map<String, QueryInfo> queries) {

        /** Função gerada para a SQL do componente (origin "TDMDADOSXML.qryX", property SQL.Strings), ou null. */
        public QueryInfo query(String origin, String property) {
            return queries.get(origin + "|" + property);
        }
    }

    /**
     * Uma função do repositório: nome, tipos, campo Go de cada parâmetro (:NOME → Campo), se é SELECT, se traduziu
     * sem lacuna, e a SQL Oracle original (os handlers usam para montar filtros dinâmicos).
     */
    public record QueryInfo(String function, String paramsType, String rowType, Map<String, String> paramFields,
            boolean select, boolean ready, String oracleSql, List<String> gaps) {
    }

    private static final String RT = ExprToGo.RT;

    private final SqlInventory inventory;
    private final TargetProfile profile;
    private final String module;
    private final String pkg;
    private final Set<String> usedNames = new HashSet<>();
    private final Map<String, QueryInfo> queries = new LinkedHashMap<>();
    private int ready;

    private GoGenerator(SqlInventory inventory, TargetProfile profile, String module, String pkg) {
        this.inventory = inventory;
        this.profile = profile;
        this.module = module;
        this.pkg = pkg;
    }

    /**
     * @param module  caminho do módulo Go (ex. "inova/gerado")
     * @param exeName nome do exe, para o pacote (ex. "PCNOV9860.exe" → pacote pcnov9860)
     */
    public static Result generate(SqlInventory inventory, TargetProfile profile, Path outDir, String module, String exeName)
            throws IOException {
        GoGenerator g = new GoGenerator(inventory, profile, module, GoNames.packageName(exeName));
        return g.write(outDir);
    }

    private Result write(Path out) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.createDirectories(out.resolve("undelphirt"));
        Files.createDirectories(out.resolve(pkg));
        Files.createDirectories(out.resolve("relatorios"));

        files.add(writeFile(out.resolve("go.mod"), "module " + module + "\n\ngo 1.22\n"));
        files.add(writeFile(out.resolve("undelphirt/undelphirt.go"), runtime()));
        files.add(writeFile(out.resolve(pkg + "/repo.go"), repo()));

        GapReport gaps = GapReport.build(inventory, profile);
        StringBuilder q = new StringBuilder(header("consultas.go"));
        q.append("import (\n\t\"context\"\n\t\"encoding/json\"\n\n\t\"").append(module).append("/undelphirt\"\n)\n\n");
        q.append("var _ context.Context\nvar _ = json.Marshal\nvar _ = undelphirt.Null\n");
        int functions = 0;
        for (SourceTranslator.Translated t : gaps.items()) {
            q.append('\n').append(function(t));
            functions++;
        }
        files.add(writeFile(out.resolve(pkg + "/consultas.go"), q.toString()));

        StringBuilder r = new StringBuilder(header("regras.go"));
        r.append("import \"").append(module).append("/undelphirt\"\n\nvar _ = undelphirt.Null\n");
        int rules = 0;
        Set<String> ruleNames = new HashSet<>();
        for (SqlAnalysis a : inventory.analyses()) {
            for (SqlAnalysis.Rule rule : a.rules()) {
                r.append('\n').append(rule(a, rule, ruleNames));
                rules++;
            }
        }
        files.add(writeFile(out.resolve(pkg + "/regras.go"), r.toString()));

        files.add(writeFile(out.resolve("relatorios/mapa_oracle.md"), inventory.toMarkdown()));
        files.add(writeFile(out.resolve("relatorios/lacunas.md"), gaps.toMarkdown()));
        return new Result(out, pkg, files, functions, ready, rules, queries);
    }

    private static Path writeFile(Path p, String content) throws IOException {
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private String header(String file) {
        return "// Código gerado pelo undelphi a partir do exe. NÃO EDITAR: é recriado a cada geração.\n"
                + "// Perfil de destino: " + profile.name() + "\n\npackage " + pkg + "\n\n";
    }

    static String runtime() throws IOException {
        try (InputStream in = GoGenerator.class.getResourceAsStream("undelphirt.go.txt")) {
            if (in == null) {
                throw new IOException("runtime Go não encontrado nos recursos");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String repo() {
        TargetProfile.Connection c = profile.connection();
        return header("repo.go") + """
                import (
                	"context"
                	"database/sql"
                	"fmt"

                	"%s/undelphirt"
                )

                // Repo executa as SQLs da rotina no banco de destino.
                type Repo struct {
                	DB *sql.DB
                }

                // DSN monta a conexão do perfil "%s" a partir das variáveis de ambiente (%s).
                // O driver (ex. github.com/lib/pq ou pgx/stdlib) é registrado por quem usa o pacote.
                func DSN() string {
                	return fmt.Sprintf("host=%%s port=%%s dbname=%%s user=%%s password=%%s search_path=%%s sslmode=disable",
                		undelphirt.Expand(%s), undelphirt.Expand(%s), undelphirt.Expand(%s),
                		undelphirt.Expand(%s), undelphirt.Expand(%s), undelphirt.Expand(%s))
                }

                // Driver é o driver do perfil.
                const Driver = %s

                type queryer interface {
                	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
                }

                // queryAll lê todas as linhas como valores do runtime, com os nomes das colunas.
                func queryAll(ctx context.Context, db queryer, query string, args ...any) ([]string, [][]undelphirt.V, error) {
                	rows, err := db.QueryContext(ctx, query, args...)
                	if err != nil {
                		return nil, nil, err
                	}
                	defer rows.Close()
                	cols, err := rows.Columns()
                	if err != nil {
                		return nil, nil, err
                	}
                	var out [][]undelphirt.V
                	for rows.Next() {
                		raw := make([]any, len(cols))
                		ptrs := make([]any, len(cols))
                		for i := range raw {
                			ptrs[i] = &raw[i]
                		}
                		if err := rows.Scan(ptrs...); err != nil {
                			return nil, nil, err
                		}
                		vals := make([]undelphirt.V, len(cols))
                		for i, x := range raw {
                			vals[i] = undelphirt.Of(x)
                		}
                		out = append(out, vals)
                	}
                	return cols, out, rows.Err()
                }

                // asMap transforma uma linha em mapa COLUNA → valor (nomes em maiúsculas, como no Oracle).
                func asMap(cols []string, vals []undelphirt.V) undelphirt.Map {
                	m := undelphirt.Map{}
                	for i, c := range cols {
                		m[upper(c)] = vals[i]
                	}
                	return m
                }

                func upper(s string) string {
                	b := []byte(s)
                	for i, c := range b {
                		if c >= 'a' && c <= 'z' {
                			b[i] = c - 32
                		}
                	}
                	return string(b)
                }

                func affected(res sql.Result) int64 {
                	n, err := res.RowsAffected()
                	if err != nil {
                		return 0
                	}
                	return n
                }

                var _ = asMap
                var _ = affected
                """.formatted(module, profile.name(), String.join(", ", profile.envVars()),
                ExprToGo.goString(c.host()), ExprToGo.goString(c.port()), ExprToGo.goString(c.database()),
                ExprToGo.goString(c.user()), ExprToGo.goString(c.password()), ExprToGo.goString(c.schema()),
                ExprToGo.goString(c.driver()));
    }

    // ---------------------------------------------------------------- uma função por SQL

    private String uniqueName(String base) {
        String name = base;
        for (int k = 2; !usedNames.add(name); k++) {
            name = base + k;
        }
        return name;
    }

    private String function(SourceTranslator.Translated t) {
        SqlAnalysis a = t.analysis();
        String name = uniqueName(GoNames.queryFunc(a.source()));
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(name).append(" — ").append(a.source().origin()).append(" (")
                .append(a.source().componentClass().isEmpty() ? "literal no código" : a.source().componentClass())
                .append(a.source().property().isEmpty() ? "" : ", " + a.source().property()).append(")\n");
        sb.append("// Tipo: ").append(a.kind().name().toLowerCase()).append(". Tabelas Oracle: ")
                .append(a.tables().isEmpty() ? "nenhuma" : String.join(", ", a.tables().values().stream()
                        .map(u -> u.table() + " (" + u.access() + ")").toList())).append(".\n");
        for (OracleToPostgres.Issue i : t.issues()) {
            sb.append("// ").append(i.severity() == OracleToPostgres.Severity.LACUNA ? "LACUNA: " : "Aviso: ")
                    .append(oneLine(i.message())).append('\n');
        }
        sb.append("//\n// SQL original (Oracle):\n");
        for (String line : (a.source().sql() == null ? "" : a.source().sql()).strip().split("\n")) {
            sb.append("//   ").append(line.replace("\r", "")).append('\n');
        }

        // Params: todos os binds da SQL original, na ordem em que aparecem
        String paramsType = uniqueName(name + "Params");
        sb.append("type ").append(paramsType).append(" struct {\n");
        Map<String, String> paramFields = new LinkedHashMap<>();
        for (String p : a.params()) {
            String f = GoNames.field(p);
            while (paramFields.containsValue(f)) {
                f = f + "_";
            }
            paramFields.put(p, f);
            sb.append('\t').append(f).append(" any // :").append(p).append('\n');
        }
        sb.append("}\n\n");

        boolean isQuery = a.statements().size() == 1 && a.statements().get(0) instanceof Query
                && a.kind() == SqlAnalysis.Kind.CONSULTA;
        String rowTypeName = isQuery ? uniqueName(name + "Row") : null;
        queries.put(a.source().origin() + "|" + a.source().property(), new QueryInfo(name, paramsType, rowTypeName,
                Map.copyOf(paramFields), isQuery, t.translatable(), a.source().sql(),
                t.gaps().stream().map(i -> oneLine(i.message())).toList()));
        if (isQuery) {
            Query oracleQuery = (Query) a.statements().get(0);
            String rowType = rowTypeName;
            List<String> rowFields = rowFields(oracleQuery);
            List<String> columnNames = columnNames(oracleQuery);
            boolean star = rowFields == null;
            sb.append("type ").append(rowType).append(" struct {\n");
            if (star) {
                sb.append("\tColunas undelphirt.Map `json:\"-\"` // SELECT *: colunas pelo nome\n");
            } else {
                for (int i = 0; i < rowFields.size(); i++) {
                    sb.append('\t').append(rowFields.get(i)).append(" undelphirt.V `json:\"").append(columnNames.get(i))
                            .append("\"`\n");
                }
            }
            sb.append("}\n\n");
            if (star) {
                sb.append("// MarshalJSON devolve as colunas do SELECT * pelo nome.\nfunc (r ").append(rowType)
                        .append(") MarshalJSON() ([]byte, error) { return json.Marshal(r.Colunas) }\n\n");
            }
            sb.append("func (r *Repo) ").append(name).append("(ctx context.Context, p ").append(paramsType)
                    .append(") ([]").append(rowType).append(", error) {\n");
            if (!t.translatable()) {
                sb.append("\treturn nil, undelphirt.Lacuna(").append(ExprToGo.goString(a.source().origin())).append(", ")
                        .append(ExprToGo.goString(gapText(t))).append(")\n}\n");
                return sb.toString();
            }
            ready++;
            SqlPrinter printer = new SqlPrinter(SqlPrinter.Params.POSITIONAL);
            String sql = printer.stmt(t.statements().get(0), "");
            sb.append("\tconst query = ").append(goRaw(sql)).append('\n');
            sb.append("\tcols, rows, err := queryAll(ctx, r.DB, query").append(args(printer.params(), paramFields, Map.of()))
                    .append(")\n\tif err != nil {\n\t\treturn nil, err\n\t}\n");
            sb.append("\tout := make([]").append(rowType).append(", 0, len(rows))\n\tfor _, v := range rows {\n");
            if (star) {
                sb.append("\t\tout = append(out, ").append(rowType).append("{Colunas: asMap(cols, v)})\n");
            } else {
                sb.append("\t\t_ = cols\n\t\tout = append(out, ").append(rowType).append("{");
                for (int i = 0; i < rowFields.size(); i++) {
                    sb.append(i > 0 ? ", " : "").append(rowFields.get(i)).append(": v[").append(i).append(']');
                }
                sb.append("})\n");
            }
            sb.append("\t}\n\treturn out, nil\n}\n");
            return sb.toString();
        }

        sb.append("func (r *Repo) ").append(name).append("(ctx context.Context, p ").append(paramsType)
                .append(") (n int64, err error) {\n");
        if (!t.translatable()) {
            sb.append("\treturn 0, undelphirt.Lacuna(").append(ExprToGo.goString(a.source().origin())).append(", ")
                    .append(ExprToGo.goString(gapText(t))).append(")\n}\n");
            return sb.toString();
        }
        ready++;
        sb.append("\tdefer undelphirt.Recover(&err)\n");
        sb.append("\ttx, err := r.DB.BeginTx(ctx, nil)\n\tif err != nil {\n\t\treturn 0, err\n\t}\n\tdefer tx.Rollback()\n");
        Body body = new Body(paramFields);
        for (Stmt s : t.statements()) {
            body.stmt(s, "\t");
        }
        sb.append(body.vars);
        sb.append(body.code);
        sb.append("\tif err := tx.Commit(); err != nil {\n\t\treturn 0, err\n\t}\n\treturn n, nil\n}\n");
        return sb.toString();
    }

    /**
     * Nome da coluna como o Oracle devolve (alias ou nome da coluna, em maiúsculas): é o que a tela Vue usa.
     * Coluna repetida (ex. A.CODPRACA e B.CODPRACA) ganha sufixo _2, como a tag JSON precisa ser única.
     */
    private static List<String> columnNames(Query q) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int k = 1;
        for (SelectItem it : q.first().items()) {
            String base = it.alias() != null ? it.alias() : it.expr() instanceof Col c ? c.name() : "COLUNA" + k;
            String name = base;
            for (int n = 2; !seen.add(name); n++) {
                name = base + "_" + n;
            }
            out.add(name);
            k++;
        }
        return out;
    }

    /** Nomes dos campos da linha a partir da lista do SELECT; null se houver *. */
    private static List<String> rowFields(Query q) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int k = 1;
        for (SelectItem it : q.first().items()) {
            if (it.expr() instanceof Star) {
                return null;
            }
            String base = it.alias() != null ? it.alias() : it.expr() instanceof Col c ? c.name() : "Coluna" + k;
            String f = GoNames.field(base);
            while (!seen.add(f)) {
                f = f + "_";
            }
            out.add(f);
            k++;
        }
        return out;
    }

    private static String gapText(SourceTranslator.Translated t) {
        return String.join("; ", t.gaps().stream().map(i -> oneLine(i.message())).toList());
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    /** Argumentos posicionais: parâmetro → p.Campo; "REC.COL" → recREC.Get("COL"). */
    private static String args(List<String> params, Map<String, String> fields, Map<String, String> records) {
        StringBuilder sb = new StringBuilder();
        for (String p : params) {
            sb.append(", ").append(paramExpr(p, fields, false));
        }
        return sb.toString();
    }

    private static String paramExpr(String p, Map<String, String> fields, boolean asValue) {
        int dot = p.indexOf('.');
        if (dot > 0) {
            return "rec" + GoNames.field(p.substring(0, dot)) + ".Get(" + ExprToGo.goString(p.substring(dot + 1)) + ")";
        }
        String f = fields.get(p);
        String ref = f == null ? RT + ".Null" : "p." + f;
        return asValue && f != null ? RT + ".Of(" + ref + ")" : ref;
    }

    /** Raw string Go (crase), ou string comum se a SQL tiver crase. */
    private static String goRaw(String sql) {
        return sql.contains("`") ? ExprToGo.goString(sql) : "`" + sql + "`";
    }

    // ---------------------------------------------------------------- corpo de PL/SQL

    /** Gera o fluxo de um bloco PL/SQL em Go dentro de uma transação. */
    private static final class Body {
        final Map<String, String> paramFields;
        final StringBuilder vars = new StringBuilder();
        final StringBuilder code = new StringBuilder();
        final Set<String> declared = new LinkedHashSet<>();
        final Map<String, Integer> counter = new HashMap<>();

        Body(Map<String, String> paramFields) {
            this.paramFields = paramFields;
        }

        ExprToGo.Resolver resolver() {
            return new ExprToGo.Resolver() {
                @Override
                public String column(Col c) {
                    if (c.parts().size() == 1) {
                        return var(c.name());
                    }
                    return "rec" + GoNames.field(c.qualifier()) + ".Get(" + ExprToGo.goString(c.name()) + ")";
                }

                @Override
                public String param(String name) {
                    return paramExpr(name, paramFields, true);
                }
            };
        }

        String var(String name) {
            String v = "v" + GoNames.field(name);
            if (declared.add(v)) {
                vars.append("\tvar ").append(v).append(" undelphirt.V\n\t_ = ").append(v).append('\n');
            }
            return v;
        }

        String fresh(String base) {
            int k = counter.merge(base, 1, Integer::sum);
            return base + k;
        }

        void stmt(Stmt s, String ind) {
            switch (s) {
                case Block b -> {
                    for (String d : b.declarations()) {
                        String name = d.strip().split("\\s+")[0];
                        var(name.toUpperCase());
                    }
                    b.body().forEach(x -> stmt(x, ind));
                    if (!b.exceptionBody().isEmpty()) {
                        code.append(ind).append("// EXCEPTION do Oracle não convertido: os erros sobem para quem chamou\n");
                    }
                }
                case Query q -> {
                    SqlPrinter pr = new SqlPrinter(SqlPrinter.Params.POSITIONAL);
                    String sql = pr.query(q, "");
                    String into = fresh("sel");
                    code.append(ind).append("{\n").append(ind).append("\tconst query = ").append(goRaw(sql)).append('\n');
                    code.append(ind).append("\t_, ").append(into).append(", err := queryAll(ctx, tx, query")
                            .append(args(pr.params(), paramFields, Map.of())).append(")\n");
                    code.append(ind).append("\tif err != nil {\n").append(ind).append("\t\treturn 0, err\n").append(ind).append("\t}\n");
                    code.append(ind).append("\t_ = ").append(into).append("\n").append(ind).append("}\n");
                }
                case Insert i -> exec(new SqlPrinter(SqlPrinter.Params.POSITIONAL), i, ind);
                case Update u -> exec(new SqlPrinter(SqlPrinter.Params.POSITIONAL), u, ind);
                case Delete d -> exec(new SqlPrinter(SqlPrinter.Params.POSITIONAL), d, ind);
                case ForLoop f -> {
                    SqlPrinter pr = new SqlPrinter(SqlPrinter.Params.POSITIONAL);
                    String sql = pr.query(f.cursor(), "");
                    String cols = fresh("cols");
                    String rows = fresh("linhas");
                    String rec = "rec" + GoNames.field(f.record());
                    code.append(ind).append("// FOR ").append(f.record()).append(" IN (cursor) LOOP\n");
                    code.append(ind).append("{\n");
                    code.append(ind).append("\tconst query = ").append(goRaw(sql)).append('\n');
                    code.append(ind).append('\t').append(cols).append(", ").append(rows).append(", err := queryAll(ctx, tx, query")
                            .append(args(pr.params(), paramFields, Map.of())).append(")\n");
                    code.append(ind).append("\tif err != nil {\n").append(ind).append("\t\treturn 0, err\n").append(ind).append("\t}\n");
                    code.append(ind).append("\tfor _, linha := range ").append(rows).append(" {\n");
                    code.append(ind).append("\t\t").append(rec).append(" := asMap(").append(cols).append(", linha)\n");
                    code.append(ind).append("\t\t_ = ").append(rec).append('\n');
                    f.body().forEach(x -> stmt(x, ind + "\t\t"));
                    code.append(ind).append("\t}\n").append(ind).append("}\n");
                }
                case If i -> {
                    for (int k = 0; k < i.branches().size(); k++) {
                        Branch br = i.branches().get(k);
                        String cond = ExprToGo.compile(br.condition(), resolver());
                        code.append(k == 0 ? ind + "if " : " else if ").append(RT).append(".Truth(").append(cond.replace("\n", "\n" + ind))
                                .append(") {\n");
                        br.body().forEach(x -> stmt(x, ind + "\t"));
                        code.append(ind).append('}');
                    }
                    if (!i.elseBody().isEmpty()) {
                        code.append(" else {\n");
                        i.elseBody().forEach(x -> stmt(x, ind + "\t"));
                        code.append(ind).append('}');
                    }
                    code.append('\n');
                }
                case Assign as -> {
                    String target = as.target() instanceof Col c && c.parts().size() == 1 ? var(c.name()) : null;
                    if (target == null) {
                        code.append(ind).append("// atribuição não convertida: ").append(oneLine(SqlPrinter.oracle(as))).append('\n');
                    } else {
                        code.append(ind).append(target).append(" = ")
                                .append(ExprToGo.compile(as.value(), resolver()).replace("\n", "\n" + ind)).append('\n');
                    }
                }
                case Return r -> code.append(ind).append("return n, tx.Commit()\n");
                case NullStmt n -> code.append(ind).append("// NULL;\n");
                case Call c -> {
                    SqlPrinter pr = new SqlPrinter(SqlPrinter.Params.POSITIONAL);
                    StringBuilder call = new StringBuilder("SELECT ").append(c.name()).append('(');
                    for (int k = 0; k < c.args().size(); k++) {
                        call.append(k > 0 ? ", " : "").append(pr.expr(c.args().get(k)));
                    }
                    call.append(')');
                    code.append(ind).append("if _, err := tx.ExecContext(ctx, ").append(ExprToGo.goString(call.toString()))
                            .append(args(pr.params(), paramFields, Map.of())).append("); err != nil {\n")
                            .append(ind).append("\treturn 0, err\n").append(ind).append("}\n");
                }
                case RawStmt r -> code.append(ind).append("// ").append(oneLine(r.text())).append('\n');
                case CreateObject c -> code.append(ind).append("// ").append(c.kind()).append(' ').append(c.name()).append('\n');
            }
        }

        private void exec(SqlPrinter pr, Stmt s, String ind) {
            String sql = pr.stmt(s, "");
            String res = fresh("res");
            code.append(ind).append("{\n");
            code.append(ind).append("\tconst cmd = ").append(goRaw(sql).replace("\n", "\n" + ind + "\t")).append('\n');
            code.append(ind).append('\t').append(res).append(", err := tx.ExecContext(ctx, cmd")
                    .append(args(pr.params(), paramFields, Map.of())).append(")\n");
            code.append(ind).append("\tif err != nil {\n").append(ind).append("\t\treturn 0, err\n").append(ind).append("\t}\n");
            code.append(ind).append("\tn += affected(").append(res).append(")\n").append(ind).append("}\n");
        }
    }

    // ---------------------------------------------------------------- regras calculadas

    private String rule(SqlAnalysis a, SqlAnalysis.Rule rule, Set<String> names) {
        String base = "Regra" + GoNames.queryFunc(a.source()) + GoNames.field(rule.name());
        String name = base;
        for (int k = 2; !names.add(name); k++) {
            name = base + k;
        }
        // entradas: colunas qualificadas como escritas na SQL; sem qualificador, usa a qualificada de mesmo nome
        Map<String, String> qualifiedByName = new HashMap<>();
        Set<String> inputs = new LinkedHashSet<>();
        OracleToPostgresWalk.cols(rule.expr(), c -> {
            if (c.parts().size() > 1) {
                qualifiedByName.putIfAbsent(c.name(), String.join(".", c.parts()));
            }
        });
        java.util.function.Function<Col, String> keyOf = c -> c.parts().size() > 1 ? String.join(".", c.parts())
                : qualifiedByName.getOrDefault(c.name(), c.name());
        OracleToPostgresWalk.cols(rule.expr(), c -> {
            if (!(c.parts().size() == 1 && c.name().equals("SYSDATE"))) {
                inputs.add(keyOf.apply(c));
            }
        });
        OracleToPostgresWalk.params(rule.expr(), p -> inputs.add(":" + p));

        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(name).append(" calcula ").append(rule.name()).append(" como em ")
                .append(a.source().origin()).append(":\n//\n");
        for (String line : rule.oracleText().split("\n")) {
            sb.append("//   ").append(line).append('\n');
        }
        sb.append("//\n// Entradas (in.Get): ").append(String.join(", ", inputs)).append(".\n");
        sb.append("func ").append(name).append("(in undelphirt.Row) (v undelphirt.V, err error) {\n");
        sb.append("\tdefer undelphirt.Recover(&err)\n");
        sb.append("\treturn ").append(ExprToGo.compile(rule.expr(), ExprToGo.ruleResolver(keyOf)).replace("\n", "\n\t"))
                .append(", nil\n}\n\n");
        sb.append("// ").append(name).append("Entradas lista os nomes que a regra lê.\n");
        sb.append("var ").append(name).append("Entradas = []string{");
        int k = 0;
        for (String in : inputs) {
            sb.append(k++ > 0 ? ", " : "").append(ExprToGo.goString(in));
        }
        sb.append("}\n");
        return sb.toString();
    }
}
