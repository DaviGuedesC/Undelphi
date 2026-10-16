package undelphi.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Todas as SQLs de um exe analisadas, mais o mapa do Oracle (tabelas, objetos chamados, regras, alertas). */
public final class SqlInventory {

    /** Uma tabela do Oracle e quem a usa. */
    public static final class TableSummary {
        final String table;
        final Set<String> readBy = new TreeSet<>();
        final Set<String> writtenBy = new TreeSet<>();
        final Set<String> columnsRead = new TreeSet<>();
        final Set<String> columnsWritten = new TreeSet<>();

        TableSummary(String table) {
            this.table = table;
        }

        public String table() {
            return table;
        }

        public Set<String> readBy() {
            return readBy;
        }

        public Set<String> writtenBy() {
            return writtenBy;
        }

        public Set<String> columnsRead() {
            return columnsRead;
        }

        public Set<String> columnsWritten() {
            return columnsWritten;
        }

        /** Todas as colunas usadas (lidas ou gravadas). */
        public Set<String> columns() {
            Set<String> all = new TreeSet<>(columnsRead);
            all.addAll(columnsWritten);
            return all;
        }
    }

    private final List<SqlAnalysis> analyses;
    private final Map<String, TableSummary> tables = new TreeMap<>();
    private final Map<String, Set<String>> calls = new TreeMap<>();
    private final Map<String, Set<String>> sequences = new TreeMap<>();
    private final Map<String, String> created = new TreeMap<>();

    private SqlInventory(List<SqlAnalysis> analyses) {
        this.analyses = analyses;
        for (SqlAnalysis a : analyses) {
            String origin = a.source().origin();
            a.tables().forEach((name, use) -> {
                TableSummary t = tables.computeIfAbsent(name, TableSummary::new);
                if (use.read()) {
                    t.readBy.add(origin);
                }
                if (use.written()) {
                    t.writtenBy.add(origin);
                }
                if (!use.read() && !use.written()) {
                    t.readBy.add(origin);
                }
                t.columnsRead.addAll(use.columnsRead());
                t.columnsWritten.addAll(use.columnsWritten());
            });
            a.calls().forEach(c -> calls.computeIfAbsent(c, k -> new TreeSet<>()).add(origin));
            a.sequences().forEach(c -> sequences.computeIfAbsent(c, k -> new TreeSet<>()).add(origin));
            a.created().forEach(c -> created.put(c, origin));
        }
    }

    public static SqlInventory analyze(List<SqlSource> sources) {
        List<SqlAnalysis> out = new ArrayList<>();
        for (SqlSource s : sources) {
            out.add(SqlAnalyzer.analyze(s));
        }
        return new SqlInventory(out);
    }

    public List<SqlAnalysis> analyses() {
        return analyses;
    }

    public Map<String, TableSummary> tables() {
        return tables;
    }

    /** Procedimentos/funções/pacotes do banco chamados → onde. */
    public Map<String, Set<String>> calls() {
        return calls;
    }

    public Map<String, Set<String>> sequences() {
        return sequences;
    }

    /** Objetos que o exe cria no banco (CREATE OR REPLACE ...) → onde. */
    public Map<String, String> created() {
        return created;
    }

    public SqlAnalysis find(String origin) {
        return analyses.stream().filter(a -> a.source().origin().equals(origin)).findFirst().orElse(null);
    }

    /** Mapa do Oracle em Markdown (vai para o relatório da rotina). */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        long writes = analyses.stream().filter(SqlAnalysis::writes).count();
        long fragments = analyses.stream().filter(a -> !a.fullyParsed()).count();
        sb.append("# Mapa do Oracle\n\n");
        sb.append(String.format("%d SQLs analisadas: %d gravam no banco, %d não foram entendidas por completo.%n%n",
                analyses.size(), writes, fragments));

        sb.append("## Tabelas\n\n| Tabela | Acesso | Colunas lidas | Colunas gravadas | Usada em |\n|---|---|---|---|---|\n");
        tables.values().forEach(t -> sb.append("| ").append(t.table).append(" | ")
                .append(!t.writtenBy.isEmpty() && !t.readBy.isEmpty() ? "leitura e gravação" : !t.writtenBy.isEmpty() ? "gravação" : "leitura")
                .append(" | ").append(String.join(", ", t.columnsRead)).append(" | ").append(String.join(", ", t.columnsWritten))
                .append(" | ").append(join(union(t.readBy, t.writtenBy))).append(" |\n"));

        if (!calls.isEmpty()) {
            sb.append("\n## Objetos do banco chamados (regras fora do exe)\n\n")
                    .append("O código destes objetos fica no Oracle (levantar em ALL_SOURCE).\n\n| Objeto | Chamado em |\n|---|---|\n");
            calls.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(join(v)).append(" |\n"));
        }
        if (!created.isEmpty()) {
            sb.append("\n## Objetos que o exe cria no banco\n\n| Objeto | Onde |\n|---|---|\n");
            created.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        }
        if (!sequences.isEmpty()) {
            sb.append("\n## Sequences\n\n| Sequence | Usada em |\n|---|---|\n");
            sequences.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(join(v)).append(" |\n"));
        }

        List<SqlAnalysis> withRules = analyses.stream().filter(a -> !a.rules().isEmpty()).toList();
        if (!withRules.isEmpty()) {
            sb.append("\n## Regras calculadas na SQL\n");
            for (SqlAnalysis a : withRules) {
                sb.append("\n### ").append(a.source().origin()).append("\n\n");
                for (SqlAnalysis.Rule r : a.rules()) {
                    sb.append("**").append(r.name()).append("**\n\n```sql\n").append(r.oracleText()).append("\n```\n\n");
                }
            }
        }

        List<SqlAnalysis> warned = analyses.stream().filter(a -> !a.warnings().isEmpty()).toList();
        if (!warned.isEmpty()) {
            sb.append("\n## Alertas para revisão\n\n| Onde | Alerta |\n|---|---|\n");
            for (SqlAnalysis a : warned) {
                for (SqlAnalysis.Warning w : a.warnings()) {
                    sb.append("| ").append(a.source().origin()).append(" | ").append(w.message().replace("|", "\\|")).append(" |\n");
                }
            }
        }

        sb.append("\n## SQLs\n\n| Origem | Componente | Tipo | Tabelas | Parâmetros | Observação |\n|---|---|---|---|---|---|\n");
        for (SqlAnalysis a : analyses) {
            sb.append("| ").append(a.source().origin()).append(" | ").append(a.source().componentClass())
                    .append(" | ").append(a.kind().name().toLowerCase()).append(" | ")
                    .append(a.tables().values().stream().map(u -> u.table() + " (" + u.access() + ")").collect(Collectors.joining(", ")))
                    .append(" | ").append(String.join(", ", a.params())).append(" | ")
                    .append(a.fullyParsed() ? "" : "não entendida por completo: " + String.join("; ", a.parseIssues()).replace("|", "\\|"))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    private static String join(Set<String> origins) {
        if (origins.size() <= 4) {
            return String.join(", ", origins);
        }
        return origins.stream().limit(4).collect(Collectors.joining(", ")) + " e mais " + (origins.size() - 4);
    }
}
