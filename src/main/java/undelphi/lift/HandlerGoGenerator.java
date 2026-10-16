package undelphi.lift;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import undelphi.dfm.FormResources;
import undelphi.engine.DelphiDecompiler;
import undelphi.gogen.GoGenerator;
import undelphi.gogen.GoNames;
import undelphi.rtti.EventBinder;
import undelphi.sql.Ast;
import undelphi.sql.SqlParser;
import undelphi.sql.SqlPrinter;
import undelphi.target.OracleToPostgres;
import undelphi.target.TargetProfile;
import undelphi.vue.FormModel;

/**
 * F4: gera os handlers Go das telas. Para cada tela Vue (FormModel), um serviço com um método por evento e as
 * rotas no contrato da tela (POST base/eventos/&lt;handler&gt;, GET base/dados/&lt;dataset&gt;). O corpo de cada
 * método vem das ações reconhecidas no C decompilado (HandlerActions); o que não dá para converter fica como
 * comentário REVISAR, com o C do Ghidra junto, e o método compila do mesmo jeito.
 */
public final class HandlerGoGenerator {

    /** Situação de um handler convertido. */
    public enum Status {
        /** Todas as ações com SQL foram ligadas aos campos da tela. */
        CONVERTIDO,
        /** Parte convertida, parte marcada REVISAR. */
        PARCIAL,
        /** Só mexe na interface (fechar, foco, habilitar botão): não há backend a escrever. */
        INTERFACE,
        /** Tem regra (validação, cálculo, gravação de campo) que não deu para converter, ou sem decompilação. */
        MANUAL
    }

    public record HandlerReport(String form, String handler, String events, Status status, List<String> sql,
            List<String> notes) {
    }

    public record Result(List<Path> files, List<HandlerReport> handlers) {

        public long count(Status s) {
            return handlers.stream().filter(h -> h.status() == s).count();
        }
    }

    private static final String RT = "undelphirt";

    private final Map<String, String> resourceOfRoot = new HashMap<>();
    private final GoGenerator.Result go;
    private final TargetProfile profile;
    private final String module;

    private HandlerGoGenerator(List<FormResources.Form> forms, GoGenerator.Result go, TargetProfile profile, String module) {
        for (FormResources.Form f : forms) {
            resourceOfRoot.putIfAbsent(f.root().name().toLowerCase(), f.resourceName());
        }
        this.go = go;
        this.profile = profile;
        this.module = module;
    }

    /**
     * @param forms      todos os forms do exe (para achar o recurso de dmDadosXML etc.)
     * @param models     modelos das telas Vue (contrato de rotas, eventos e grades)
     * @param bindings   eventos ligados aos endereços (F3)
     * @param decompiled endereço → decompilação (F3); pode estar vazio (gera só esqueletos)
     */
    public static Result generate(List<FormResources.Form> forms, List<FormModel> models, List<EventBinder.Binding> bindings,
            Map<Long, DelphiDecompiler.Decompiled> decompiled, GoGenerator.Result go, TargetProfile profile, String module)
            throws IOException {
        return new HandlerGoGenerator(forms, go, profile, module).write(models, bindings, decompiled);
    }

    private Result write(List<FormModel> models, List<EventBinder.Binding> bindings,
            Map<Long, DelphiDecompiler.Decompiled> decompiled) throws IOException {
        Path pkgDir = go.outDir().resolve(go.packageName());
        List<Path> files = new ArrayList<>();
        List<HandlerReport> reports = new ArrayList<>();
        Path tela = pkgDir.resolve("tela.go");
        Files.writeString(tela, resource("tela.go.txt").replace("{{PKG}}", go.packageName()).replace("{{MODULE}}", module),
                StandardCharsets.UTF_8);
        files.add(tela);
        Set<String> serviceNames = new LinkedHashSet<>();
        for (FormModel m : models) {
            String service = GoNames.field(m.formName) + "Servico";
            while (!serviceNames.add(service)) {
                service = service + "_";
            }
            Path file = pkgDir.resolve("eventos_" + m.formName.toLowerCase() + ".go");
            Files.writeString(file, formFile(m, service, bindings, decompiled, reports), StandardCharsets.UTF_8);
            files.add(file);
        }
        Path report = go.outDir().resolve("relatorios").resolve("handlers.md");
        Files.writeString(report, report(reports), StandardCharsets.UTF_8);
        files.add(report);
        return new Result(files, reports);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = HandlerGoGenerator.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("recurso não encontrado: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- um arquivo por tela

    private String formFile(FormModel m, String service, List<EventBinder.Binding> bindings,
            Map<Long, DelphiDecompiler.Decompiled> decompiled, List<HandlerReport> reports) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Código gerado pelo undelphi a partir de ").append(m.resourceName)
                .append(". NÃO EDITAR: gere de novo; complete à mão só o que está marcado REVISAR.\n\n");
        sb.append("package ").append(go.packageName()).append("\n\nimport (\n\t\"context\"\n\t\"net/http\"\n\n\t\"")
                .append(module).append("/undelphirt\"\n)\n\nvar _ = undelphirt.Null\nvar _ context.Context\n\n");
        sb.append("// ").append(service).append(" atende a tela ").append(m.formName).append(" (").append(m.resourceName)
                .append(m.caption != null ? ", \"" + oneLine(m.caption) + "\"" : "").append(").\n");
        sb.append("type ").append(service).append(" struct {\n\tRepo *Repo\n}\n\n");

        // eventos únicos por handler (vários componentes podem usar o mesmo método)
        Map<String, List<FormModel.Event>> byHandler = new LinkedHashMap<>();
        for (FormModel.Event e : m.events) {
            byHandler.computeIfAbsent(e.handler(), k -> new ArrayList<>()).add(e);
        }
        Map<String, String> dataMethods = new LinkedHashMap<>();
        for (FormModel.Dataset d : m.datasets.values()) {
            dataMethods.put(d.key(), "dados" + GoNames.field(d.key()));
        }

        sb.append("// Registrar liga as rotas da tela em mux. base vazia usa \"").append(m.apiBase).append("\".\n");
        sb.append("func (s *").append(service).append(") Registrar(mux *http.ServeMux, base string) {\n");
        sb.append("\tif base == \"\" {\n\t\tbase = ").append(goString(m.apiBase)).append("\n\t}\n");
        for (String h : byHandler.keySet()) {
            sb.append("\tregistrarEvento(mux, base+").append(goString("/eventos/" + h)).append(", s.").append(GoNames.field(h))
                    .append(")\n");
        }
        dataMethods.forEach((key, method) -> sb.append("\tregistrarDados(mux, base+").append(goString("/dados/" + key))
                .append(", s.").append(method).append(")\n"));
        sb.append("}\n");

        for (Map.Entry<String, List<FormModel.Event>> e : byHandler.entrySet()) {
            EventBinder.Binding b = bindings.stream()
                    .filter(x -> x.formResource().equalsIgnoreCase(m.resourceName) && x.method().equalsIgnoreCase(e.getKey())
                            && x.resolved())
                    .findFirst().orElse(null);
            DelphiDecompiler.Decompiled d = b == null ? null : decompiled.get(b.address());
            sb.append('\n').append(handler(m, service, e.getKey(), e.getValue(), b, d, reports));
        }
        for (FormModel.Dataset d : m.datasets.values()) {
            sb.append('\n').append(dataMethod(service, dataMethods.get(d.key()), d));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- handler

    private String handler(FormModel m, String service, String handler, List<FormModel.Event> events,
            EventBinder.Binding binding, DelphiDecompiler.Decompiled d, List<HandlerReport> reports) {
        String method = GoNames.field(handler);
        List<String> notes = new ArrayList<>();
        List<String> sqlUsed = new ArrayList<>();
        StringBuilder body = new StringBuilder("\tresp := NovaResposta()\n");
        StringBuilder doc = new StringBuilder();
        String eventsText = String.join(", ", events.stream().map(e -> e.component() + "." + e.property()).toList());
        doc.append("// ").append(method).append(" é o ").append(handler).append(" do Delphi (").append(eventsText).append(").\n");

        int converted = 0;
        int pending = 0;
        if (binding == null) {
            notes.add("método sem endereço no RTTI");
            pending++;
        } else if (d == null || !d.ok()) {
            notes.add("decompilação indisponível" + (d != null && d.error() != null ? ": " + d.error() : ""));
            pending++;
        } else {
            doc.append("// Origem: ").append(binding.qualifiedName()).append(String.format(" @ 0x%08X.%n", binding.address()));
            List<HandlerActions.Action> actions = HandlerActions.extract(d.code(), m.formName, binding.formClass());
            Map<String, Group> groups = group(actions);
            for (Group g : groups.values()) {
                String origin = resourceOfRoot.getOrDefault(g.dataset.form().toLowerCase(), g.dataset.form().toUpperCase())
                        + "." + g.dataset.component();
                GoGenerator.QueryInfo info = go.query(origin, "SQL.Strings");
                sqlUsed.add(origin);
                if (!g.sqlVersions.isEmpty()) {
                    // SQL montada no código do evento (SQL.Clear/SQL.Add), cada versão na ordem em que rodava
                    for (String version : g.sqlVersions) {
                        if (runQuery(body, g, version, origin + " (SQL montada no evento)", notes)) {
                            converted++;
                        } else {
                            pending++;
                        }
                    }
                    continue;
                }
                if (info == null) {
                    body.append("\t// REVISAR: ").append(g.dataset.key())
                            .append(" não tem SQL no DFM (ClientDataSet em memória ou SQL montada em código).\n");
                    notes.add(g.dataset.key() + " sem SQL no DFM");
                    pending++;
                    continue;
                }
                if (!g.where.isEmpty()) {
                    if (runQuery(body, g, info.oracleSql(), origin, notes)) {
                        converted++;
                    } else {
                        pending++;
                    }
                    continue;
                }
                body.append("\t{\n");
                body.append("\t\t// ").append(g.dataset.key()).append(" (").append(origin).append(")\n");
                String params = params(g, info, notes);
                if (info.select()) {
                    body.append("\t\tlinhas, err := s.Repo.").append(info.function()).append("(ctx, ").append(params).append(")\n");
                    body.append("\t\tif err != nil {\n\t\t\treturn resp.Falha(err), nil\n\t\t}\n");
                    body.append("\t\tresp.Dados[").append(goString(g.dataset.key())).append("] = linhas\n");
                } else {
                    body.append("\t\tif _, err := s.Repo.").append(info.function()).append("(ctx, ").append(params)
                            .append("); err != nil {\n\t\t\treturn resp.Falha(err), nil\n\t\t}\n");
                }
                body.append("\t}\n");
                if (!info.ready()) {
                    notes.add(origin + " tem lacuna no destino: " + String.join("; ", info.gaps()));
                }
                converted++;
            }
            List<String> validations = new ArrayList<>();
            String info = null;
            for (HandlerActions.Action a : actions) {
                switch (a) {
                    case HandlerActions.Message msg -> {
                        if (msg.kind().equals("informacao")) {
                            info = msg.text();
                        } else {
                            validations.add(msg.kind() + ": " + msg.text());
                        }
                    }
                    case HandlerActions.Navigate nav -> body.append("\t// troca de aba no original (")
                            .append(nav.control() == null ? "?" : nav.control().component()).append('.').append(nav.method())
                            .append("): a tela avança sozinha quando o evento termina sem erro\n");
                    case HandlerActions.WriteField w -> {
                        body.append("\t// REVISAR: o original altera ").append(w.field().component()).append('.')
                                .append(w.property()).append(" (preencher resp.Form[")
                                .append(goString(w.field().component())).append("])\n");
                        String note = "altera " + w.field().component() + "." + w.property() + " na tela";
                        if (!notes.contains(note)) {
                            notes.add(note);
                        }
                        pending++;
                    }
                    case HandlerActions.CallOwn c -> body.append("\t// o original chama ").append(c.method())
                            .append(" (método do próprio form)\n");
                    case HandlerActions.Touch t -> {
                        if (!groups.containsKey(t.component().key())) {
                            body.append("\t// REVISAR: o original usa ").append(t.component().key())
                                    .append(" sem ação reconhecida (ver o C abaixo)\n");
                            notes.add("usa " + t.component().key() + " (lógica não convertida)");
                            pending++;
                        }
                    }
                    default -> {
                    }
                }
            }
            if (!validations.isEmpty()) {
                doc.append("//\n// REVISAR — mensagens do original (a condição de cada uma está no C abaixo):\n");
                validations.forEach(v -> doc.append("//   - ").append(oneLine(v)).append('\n'));
                notes.add(validations.size() + " mensagem(ns) de validação com condição a conferir");
                pending++;
            }
            if (info != null) {
                body.append("\tresp.Mensagem = ").append(goString(info)).append('\n');
            }
            doc.append("//\n// C decompilado (Ghidra, com nomes do RTTI):\n");
            for (String line : d.code().strip().split("\n")) {
                if (!line.strip().matches("^(undefined\\d?|int|uint|char|bool|float10|double|wchar_t|code|T\\w+) \\*{0,2}\\w+(\\s*\\[\\d+\\])?;$")) {
                    doc.append("//   ").append(line.replace("\r", "")).append('\n');
                }
            }
        }
        Status status = converted > 0 && pending == 0 ? Status.CONVERTIDO
                : converted > 0 ? Status.PARCIAL
                : pending == 0 ? Status.INTERFACE : Status.MANUAL;
        reports.add(new HandlerReport(m.formName, handler, eventsText, status, sqlUsed, notes));
        doc.append("//\n// Situação: ").append(status.name().toLowerCase()).append(".\n");
        return doc + "func (s *" + service + ") " + method + "(ctx context.Context, req *Requisicao) (*Resposta, error) {\n"
                + body + "\treturn resp, nil\n}\n";
    }

    /** Ações de um mesmo dataset, na ordem em que aparecem. */
    private static final class Group {
        final HandlerActions.Ref dataset;
        final Map<String, HandlerActions.SetParam> params = new LinkedHashMap<>();
        final List<String> where = new ArrayList<>();
        /** SQL montada em código (SQL.Add) sendo montada agora. */
        final List<String> sqlLines = new ArrayList<>();
        /** Cada SQL montada em código, na ordem (um SQL.Clear fecha a versão anterior); vazia se usa a SQL do DFM. */
        final List<String> sqlVersions = new ArrayList<>();

        void closeVersion() {
            if (!sqlLines.isEmpty()) {
                sqlVersions.add(String.join("\n", sqlLines));
                sqlLines.clear();
            }
        }

        Group(HandlerActions.Ref dataset) {
            this.dataset = dataset;
        }
    }

    private static Map<String, Group> group(List<HandlerActions.Action> actions) {
        Map<String, Group> out = new LinkedHashMap<>();
        for (HandlerActions.Action a : actions) {
            switch (a) {
                case HandlerActions.SetParam p -> out.computeIfAbsent(p.dataset().key(), k -> new Group(p.dataset())).params
                        .put(p.param(), p);
                case HandlerActions.DynamicWhere w -> {
                    if (w.method().equals("AddWhere") || w.method().equals("SetWhere")) {
                        out.computeIfAbsent(w.dataset().key(), k -> new Group(w.dataset())).where.add(w.condition());
                    }
                }
                case HandlerActions.Execute x -> out.computeIfAbsent(x.dataset().key(), k -> new Group(x.dataset()));
                case HandlerActions.SqlText t -> {
                    Group g = out.computeIfAbsent(t.dataset().key(), k -> new Group(t.dataset()));
                    if (t.clear()) {
                        g.closeVersion();
                    } else {
                        g.sqlLines.add(t.line());
                    }
                }
                default -> {
                }
            }
        }
        out.values().forEach(Group::closeVersion);
        return out;
    }

    /** NomeParams{Campo: valor...}: valor do campo da tela que o original usava, ou o campo de mesmo nome. */
    private String params(Group g, GoGenerator.QueryInfo info, List<String> notes) {
        StringBuilder sb = new StringBuilder(info.paramsType()).append('{');
        int k = 0;
        for (Map.Entry<String, String> p : new java.util.TreeMap<>(info.paramFields()).entrySet()) {
            sb.append(k++ > 0 ? ", " : "").append(p.getValue()).append(": ").append(valueOf(g, p.getKey(), notes));
        }
        return sb.append('}').toString();
    }

    private String valueOf(Group g, String param, List<String> notes) {
        HandlerActions.SetParam sp = g.params.get(param);
        if (sp != null && sp.source() != null) {
            return "req.Valor(" + goString(sp.source().component()) + ")";
        }
        notes.add(":" + param + " de " + g.dataset.key() + " sem origem reconhecida (usa o campo de mesmo nome)");
        return "req.Valor(" + goString(param) + ")";
    }

    /**
     * Executa no destino uma SQL que o evento monta: a SQL do componente com os AddWhere do evento, ou a SQL montada
     * linha a linha (SQL.Add). Traduz para o destino; SELECT preenche resp.Dados, comando roda com ExecContext.
     * Devolve false se a SQL não foi entendida (fica REVISAR).
     */
    private boolean runQuery(StringBuilder body, Group g, String baseSql, String origin, List<String> notes) {
        List<Ast.Stmt> stmts = SqlParser.parse(baseSql == null ? "" : baseSql);
        if (stmts.size() != 1 || stmts.get(0) instanceof Ast.RawStmt) {
            body.append("\t// REVISAR: SQL de ").append(g.dataset.key()).append(" não entendida: ")
                    .append(abbreviate(oneLine(baseSql))).append('\n');
            notes.add("SQL não entendida em " + g.dataset.key());
            return false;
        }
        Ast.Stmt st = stmts.get(0);
        if (!g.where.isEmpty()) {
            Ast.Expr extra;
            try {
                extra = SqlParser.parseExpression(String.join(" AND ", g.where.stream().map(w -> "(" + w + ")").toList()));
            }
            catch (SqlParser.ParseError e) {
                body.append("\t// REVISAR: filtro dinâmico não entendido: ").append(oneLine(String.join(" AND ", g.where))).append('\n');
                notes.add("filtro dinâmico não entendido em " + g.dataset.key());
                return false;
            }
            if (!(st instanceof Ast.Query q)) {
                body.append("\t// REVISAR: AddWhere sobre comando que não é SELECT em ").append(g.dataset.key()).append('\n');
                notes.add("AddWhere sobre comando que não é SELECT em " + g.dataset.key());
                return false;
            }
            Ast.QueryBlock b = q.first();
            Ast.Expr where = b.where() == null ? extra : new Ast.Binary("AND", b.where(), extra);
            st = new Ast.Query(new Ast.QueryBlock(b.distinct(), b.items(), b.into(), b.from(), where, b.connectBy(),
                    b.groupBy(), b.having()), q.setOps(), q.orderBy(), q.forUpdate());
        }
        OracleToPostgres.Result r = OracleToPostgres.translate(st, profile);
        body.append("\t{\n\t\t// ").append(g.dataset.key()).append(" (").append(origin).append(")\n");
        g.where.forEach(w -> body.append("\t\t// filtro do original: ").append(oneLine(w)).append('\n'));
        if (!r.translatable()) {
            List<String> gaps = r.issues().stream().filter(i -> i.severity() == OracleToPostgres.Severity.LACUNA)
                    .map(OracleToPostgres.Issue::message).toList();
            body.append("\t\tif err := ").append(RT).append(".Lacuna(").append(goString(origin)).append(", ")
                    .append(goString(oneLine(String.join("; ", gaps)))).append("); err != nil {\n")
                    .append("\t\t\treturn resp.Falha(err), nil\n\t\t}\n\t}\n");
            notes.add(origin + " tem lacuna no destino: " + String.join("; ", gaps));
            return true; // comportamento definido: erro explícito de lacuna
        }
        SqlPrinter printer = new SqlPrinter(SqlPrinter.Params.POSITIONAL);
        String sql = printer.stmt(r.stmt(), "");
        StringBuilder args = new StringBuilder();
        for (String p : printer.params()) {
            args.append(", ").append(valueOf(g, p, notes));
        }
        String literal = sql.contains("`") ? goString(sql) : "`" + sql + "`";
        if (r.stmt() instanceof Ast.Query) {
            body.append("\t\tconst query = ").append(literal).append('\n');
            body.append("\t\tcols, rows, err := queryAll(ctx, s.Repo.DB, query").append(args)
                    .append(")\n\t\tif err != nil {\n\t\t\treturn resp.Falha(err), nil\n\t\t}\n");
            body.append("\t\tresp.Dados[").append(goString(g.dataset.key())).append("] = linhasJSON(cols, rows)\n\t}\n");
        } else {
            body.append("\t\tconst cmd = ").append(literal).append('\n');
            body.append("\t\tif _, err := s.Repo.DB.ExecContext(ctx, cmd").append(args)
                    .append("); err != nil {\n\t\t\treturn resp.Falha(err), nil\n\t\t}\n\t}\n");
        }
        return true;
    }

    private static String abbreviate(String s) {
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }

    // ---------------------------------------------------------------- dados das grades

    private String dataMethod(String service, String method, FormModel.Dataset d) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(method).append(" devolve as linhas da grade ").append(d.key()).append(".\n");
        sb.append("func (s *").append(service).append(") ").append(method)
                .append("(ctx context.Context, q *Requisicao) (any, error) {\n");
        GoGenerator.QueryInfo info = d.sqlOrigin() == null ? null : go.query(d.sqlOrigin(), "SQL.Strings");
        if (info == null) {
            sb.append("\t// ").append(d.key()).append(" não tem SQL no DFM (dataset em memória): os dados vêm dos eventos.\n");
            sb.append("\treturn []any{}, nil\n}\n");
            return sb.toString();
        }
        if (!info.select()) {
            sb.append("\treturn nil, ").append(RT).append(".Lacuna(").append(goString(d.sqlOrigin()))
                    .append(", \"o componente não é um SELECT\")\n}\n");
            return sb.toString();
        }
        sb.append("\treturn s.Repo.").append(info.function()).append("(ctx, ").append(info.paramsType()).append('{');
        int k = 0;
        for (Map.Entry<String, String> p : new java.util.TreeMap<>(info.paramFields()).entrySet()) {
            sb.append(k++ > 0 ? ", " : "").append(p.getValue()).append(": q.Valor(").append(goString(p.getKey())).append(')');
        }
        sb.append("})\n}\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- relatório

    private static String report(List<HandlerReport> reports) {
        StringBuilder sb = new StringBuilder("# Handlers convertidos (F4)\n\n");
        long conv = reports.stream().filter(r -> r.status() == Status.CONVERTIDO).count();
        long part = reports.stream().filter(r -> r.status() == Status.PARCIAL).count();
        long ui = reports.stream().filter(r -> r.status() == Status.INTERFACE).count();
        long man = reports.stream().filter(r -> r.status() == Status.MANUAL).count();
        sb.append(String.format("%d handlers: %d convertidos, %d parciais, %d só de interface, %d manuais.%n%n", reports.size(),
                conv, part, ui, man));
        sb.append("- **convertido**: as SQLs que o evento usa foram ligadas aos campos da tela.\n");
        sb.append("- **parcial**: parte convertida; o resto está marcado REVISAR no código, com o C do original ao lado.\n");
        sb.append("- **interface**: o evento só mexe na tela (fechar, foco, habilitar); não há backend a escrever.\n");
        sb.append("- **manual**: tem regra (validação, cálculo, campo alterado) que não deu para converter; o método existe e "
                + "compila, com o C do original ao lado para completar.\n\n");
        sb.append("| Tela | Handler | Eventos | Situação | SQL usada | Observações |\n|---|---|---|---|---|---|\n");
        for (HandlerReport r : reports) {
            sb.append("| ").append(r.form()).append(" | ").append(r.handler()).append(" | ").append(r.events()).append(" | ")
                    .append(r.status().name().toLowerCase()).append(" | ").append(String.join(", ", r.sql())).append(" | ")
                    .append(String.join("; ", r.notes()).replace("|", "\\|")).append(" |\n");
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    private static String goString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 32 ? String.format("\\x%02x", (int) c) : String.valueOf(c));
            }
        }
        return sb.append('"').toString();
    }
}
