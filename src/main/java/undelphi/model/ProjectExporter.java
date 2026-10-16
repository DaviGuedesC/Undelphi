package undelphi.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import undelphi.dfm.DfmSql;
import undelphi.dfm.DfmTextWriter;
import undelphi.dfm.FormResources;
import undelphi.engine.DelphiDecompiler;
import undelphi.engine.GhidraEngine;
import undelphi.gogen.GoGenerator;
import undelphi.lift.HandlerGoGenerator;
import undelphi.rtti.AppScope;
import undelphi.rtti.EventBinder;
import undelphi.sql.SqlInventory;
import undelphi.sql.SqlSource;
import undelphi.target.GapReport;
import undelphi.target.TargetProfile;
import undelphi.vue.FormModel;
import undelphi.vue.VueGenerator;

/**
 * Grava em disco o que o undelphi tira de um exe: forms em texto, SQL dos componentes, telas Vue,
 * mapa do Oracle, lacunas para o destino e o código Go.
 */
public final class ProjectExporter {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Resumo do que foi gravado, para a CLI e a interface. */
    public record Summary(int forms, int appForms, int sql, int vue, List<String> warnings, int goFunctions, int goReady,
            int rules, int handlers, int handlersConverted, int handlersPartial, int events, int eventsResolved) {

        public Summary(int forms, int appForms, int sql, int vue, List<String> warnings) {
            this(forms, appForms, sql, vue, warnings, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public Summary(int forms, int appForms, int sql, int vue, List<String> warnings, int goFunctions, int goReady,
                int rules) {
            this(forms, appForms, sql, vue, warnings, goFunctions, goReady, rules, 0, 0, 0, 0, 0);
        }
    }

    /** Resultado da F3 + F4: estrutura recuperada, decompilação e handlers Go. */
    public record HandlersExport(AppScope.Structure structure, DelphiDecompiler.Result decompiled,
            HandlerGoGenerator.Result handlers) {
    }

    /** Módulo Go padrão do código gerado. */
    public static final String DEFAULT_GO_MODULE = "undelphi/gerado";

    /** SQL dos forms da aplicação no formato do analisador (sem os forms internos de bibliotecas). */
    public static List<SqlSource> sqlSources(DelphiProject p) {
        List<SqlSource> out = new ArrayList<>();
        for (DfmSql s : DfmSql.extract(p.appForms())) {
            out.add(new SqlSource(s.origin(), s.componentClass(), s.property(), s.sql()));
        }
        return out;
    }

    /**
     * Mapa do Oracle e lacunas (pasta/oracle/) e código Go (pasta/go/, com go.mod, runtime, consultas e regras).
     * Sem perfil, usa o perfil vazio: tudo aparece como lacuna, mas o Go é gerado do mesmo jeito.
     */
    public static GoGenerator.Result exportOracleAndGo(DelphiProject p, Path out, TargetProfile profile, String module)
            throws IOException {
        return exportOracleAndGo(p, out, profile, module, List.of());
    }

    /** Idem, somando SQL achada fora do DFM (literais do código, F3). */
    public static GoGenerator.Result exportOracleAndGo(DelphiProject p, Path out, TargetProfile profile, String module,
            List<SqlSource> extra) throws IOException {
        List<SqlSource> sources = new ArrayList<>(sqlSources(p));
        sources.addAll(extra);
        SqlInventory inventory = SqlInventory.analyze(sources);
        Path oracle = Files.createDirectories(out.resolve("oracle"));
        Files.writeString(oracle.resolve("mapa_oracle.md"), inventory.toMarkdown(), StandardCharsets.UTF_8);
        Files.writeString(oracle.resolve("lacunas.md"), GapReport.build(inventory, profile).toMarkdown(), StandardCharsets.UTF_8);
        return GoGenerator.generate(inventory, profile, out.resolve("go"), module, p.exe().getFileName().toString());
    }

    public static Summary exportForms(DelphiProject p, Path out) throws IOException {
        Path dir = Files.createDirectories(out.resolve("forms"));
        for (FormResources.Form f : p.forms()) {
            Files.writeString(dir.resolve(f.resourceName() + ".dfm"), DfmTextWriter.write(f.root()), StandardCharsets.UTF_8);
        }
        return new Summary(p.forms().size(), p.appForms().size(), 0, 0, failures(p));
    }

    public static List<DfmSql> exportSql(DelphiProject p, Path out) throws IOException {
        Path dir = Files.createDirectories(out.resolve("sql"));
        List<DfmSql> sql = DfmSql.extract(p.forms());
        Files.writeString(dir.resolve("sql-dos-forms.json"), GSON.toJson(sql), StandardCharsets.UTF_8);
        StringBuilder md = new StringBuilder("# SQL dos componentes — ").append(p.exe().getFileName()).append("\n\n");
        md.append(sql.size()).append(" textos de SQL em componentes do DFM.\n\n");
        for (DfmSql s : sql) {
            md.append("## ").append(s.origin()).append(" · ").append(s.property()).append("\n\n");
            md.append("Componente `").append(s.componentClass()).append("`\n\n```sql\n").append(s.sql()).append("\n```\n\n");
        }
        Files.writeString(dir.resolve("sql-dos-forms.md"), md.toString(), StandardCharsets.UTF_8);
        return sql;
    }

    public static List<VueGenerator.Result> exportVue(DelphiProject p, Path out) throws IOException {
        Path dir = Files.createDirectories(out.resolve("vue"));
        List<DfmSql> sql = DfmSql.extract(p.forms());
        List<VueGenerator.Result> results = new ArrayList<>();
        StringBuilder md = new StringBuilder("# Telas Vue — ").append(p.exe().getFileName()).append("\n\n");
        md.append("| Tela | Form Delphi | Eventos | Campos | Datasets | Avisos |\n|---|---|---|---|---|---|\n");
        for (FormResources.Form f : p.appForms()) {
            if (!hasVisualContent(f)) {
                continue;
            }
            VueGenerator.Result r = VueGenerator.generate(p, f, sql);
            results.add(r);
            Files.writeString(dir.resolve(r.fileName()), r.code(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(r.model().formName + ".modelo.json"), GSON.toJson(r.model()), StandardCharsets.UTF_8);
            FormModel m = r.model();
            md.append("| [").append(r.fileName()).append("](").append(r.fileName()).append(") | ").append(m.resourceName)
                    .append(" | ").append(m.events.size()).append(" | ").append(m.inputs.size()).append(" | ")
                    .append(m.datasets.size()).append(" | ").append(m.warnings.size()).append(" |\n");
        }
        for (VueGenerator.Result r : results) {
            if (!r.model().warnings.isEmpty()) {
                md.append("\n## Avisos em ").append(r.fileName()).append("\n\n");
                r.model().warnings.forEach(w -> md.append("- ").append(w).append('\n'));
            }
        }
        Files.writeString(dir.resolve("README.md"), md.toString(), StandardCharsets.UTF_8);
        return results;
    }

    /** Data modules (só componentes não visuais) não viram tela. */
    static boolean hasVisualContent(FormResources.Form f) {
        String c = f.root().className().toLowerCase();
        return !(c.startsWith("tdm") || c.contains("datamodule"));
    }

    public static Summary exportAll(DelphiProject p, Path out) throws IOException {
        return exportAll(p, out, TargetProfile.empty(), DEFAULT_GO_MODULE);
    }

    public static Summary exportAll(DelphiProject p, Path out, TargetProfile profile, String module) throws IOException {
        try {
            return exportAll(p, out, profile, module, false);
        }
        catch (IOException | RuntimeException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * Tudo: forms, SQL, Vue, mapa do Oracle, lacunas, Go das SQLs e, com ghidra=true, a F3 (RTTI, eventos,
     * decompilação em pasta/decompilado/) e a F4 (handlers Go das telas). A F3 importa o exe no Ghidra embutido
     * (cerca de 1 minuto num exe de 47 MB).
     */
    public static Summary exportAll(DelphiProject p, Path out, TargetProfile profile, String module, boolean ghidra)
            throws Exception {
        exportForms(p, out);
        List<DfmSql> sql = exportSql(p, out);
        List<VueGenerator.Result> vue = exportVue(p, out);
        AppScope.Structure structure = ghidra && p.pe() != null ? AppScope.read(p.pe(), p.appForms()) : null;
        GoGenerator.Result go = exportOracleAndGo(p, out, profile, module,
                structure == null ? List.of() : structure.sqlLiterals());
        List<String> warnings = failures(p);
        vue.forEach(r -> r.model().warnings.forEach(w -> warnings.add(r.fileName() + ": " + w)));
        if (structure == null) {
            return new Summary(p.forms().size(), p.appForms().size(), sql.size(), vue.size(), warnings, go.functions(),
                    go.ready(), go.rules());
        }
        HandlersExport h = exportHandlers(p, out, structure, go, vue.stream().map(VueGenerator.Result::model).toList(),
                profile, module);
        long resolved = structure.bindings().stream().filter(EventBinder.Binding::resolved).count();
        return new Summary(p.forms().size(), p.appForms().size(), sql.size(), vue.size(), warnings, go.functions(),
                go.ready(), go.rules(), h.handlers().handlers().size(),
                (int) h.handlers().count(HandlerGoGenerator.Status.CONVERTIDO),
                (int) h.handlers().count(HandlerGoGenerator.Status.PARCIAL), structure.bindings().size(), (int) resolved);
    }

    /**
     * F3 + F4: decompila no Ghidra os métodos ligados a eventos (com nomes, structs e globais do RTTI), grava o C em
     * pasta/decompilado/, o relatório de estrutura em pasta/estrutura.md e os handlers Go das telas em pasta/go/.
     */
    public static HandlersExport exportHandlers(DelphiProject p, Path out, AppScope.Structure s, GoGenerator.Result go,
            List<FormModel> models, TargetProfile profile, String module) throws Exception {
        List<Long> targets = s.bindings().stream().filter(EventBinder.Binding::resolved).map(EventBinder.Binding::address)
                .distinct().toList();
        DelphiDecompiler.Result dec;
        try (GhidraEngine engine = new GhidraEngine()) {
            dec = DelphiDecompiler.decompile(engine, p.exe(), s, targets, 60);
        }
        Path dir = Files.createDirectories(out.resolve("decompilado"));
        for (EventBinder.Binding b : s.bindings()) {
            DelphiDecompiler.Decompiled d = dec.methods().get(b.address());
            if (d != null && d.ok()) {
                Files.writeString(dir.resolve(b.formClass() + "." + b.method() + ".c"), d.code(), StandardCharsets.UTF_8);
            }
        }
        HandlerGoGenerator.Result handlers = HandlerGoGenerator.generate(p.forms(), models, s.bindings(), dec.methods(), go,
                profile, module);
        Files.writeString(out.resolve("estrutura.md"), structureReport(p, s, dec), StandardCharsets.UTF_8);
        return new HandlersExport(s, dec, handlers);
    }

    private static String structureReport(DelphiProject p, AppScope.Structure s, DelphiDecompiler.Result dec) {
        StringBuilder sb = new StringBuilder("# Estrutura Delphi (F3) — ").append(p.exe().getFileName()).append("\n\n");
        long resolved = s.bindings().stream().filter(EventBinder.Binding::resolved).count();
        long ok = dec.methods().values().stream().filter(DelphiDecompiler.Decompiled::ok).count();
        sb.append(String.format("- %,d classes recuperadas do RTTI; %d são da aplicação: %s%n", s.classes().all().size(),
                s.appClasses().size(), String.join(", ", s.appClasses())));
        sb.append(String.format("- %d eventos nos forms da aplicação, %d ligados ao endereço do método%n", s.bindings().size(), resolved));
        sb.append(String.format("- %d métodos decompilados sem erro de %d (C em decompilado/)%n", ok, dec.methods().size()));
        sb.append(String.format("- %,d métodos nomeados no Ghidra, %d structs de form, %d globais de form, %d literais da aplicação "
                + "(%d com SQL, que entram no mapa do Oracle)%n", dec.labels(), dec.structs(), dec.globals(), dec.literals(),
                s.sqlLiterals().size()));
        sb.append(String.format("- tempos: importação %,d ms, preparo %,d ms, decompilação %,d ms%n%n", dec.importMs(),
                dec.prepareMs(), dec.decompileMs()));
        sb.append("## Variáveis globais dos forms\n\n| Variável | Classe | Endereço |\n|---|---|---|\n");
        s.globals().values().forEach(g -> sb.append(String.format("| %s | %s | 0x%08X |%n", g.name(), g.className(), g.address())));
        sb.append("\n## Eventos\n\n| Form | Componente | Evento | Método | Endereço | Decompilado |\n|---|---|---|---|---|---|\n");
        for (EventBinder.Binding b : s.bindings()) {
            DelphiDecompiler.Decompiled d = dec.methods().get(b.address());
            sb.append("| ").append(b.formResource()).append(" | ").append(b.component()).append(" | ").append(b.event())
                    .append(" | ").append(b.method()).append(" | ")
                    .append(b.resolved() ? String.format("0x%08X", b.address()) : "não achado no RTTI").append(" | ")
                    .append(d == null ? "" : d.ok() ? "[C](decompilado/" + b.formClass() + "." + b.method() + ".c)"
                            : "erro: " + d.error().replace("|", "/"))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private static List<String> failures(DelphiProject p) {
        List<String> out = new ArrayList<>();
        p.failures().forEach(f -> out.add("DFM " + f.resourceName() + " não lido: " + f.error()));
        return out;
    }

    private ProjectExporter() {
    }
}
