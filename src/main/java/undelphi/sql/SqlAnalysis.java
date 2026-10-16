package undelphi.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** O que uma SQL do exe faz: tipo, tabelas, colunas, parâmetros, chamadas, regras e alertas. */
public final class SqlAnalysis {

    public enum Kind {
        /** SELECT. */
        CONSULTA,
        /** INSERT/UPDATE/DELETE. */
        GRAVACAO,
        /** Bloco BEGIN...END. */
        PLSQL,
        /** CREATE/ALTER/DROP. */
        DDL,
        /** Chamada de procedimento (StoredProcName, EXEC). */
        CHAMADA,
        /** SQL que o parser não entendeu (geralmente montada em pedaços no código). */
        FRAGMENTO,
        VAZIA
    }

    /** Uso de uma tabela do Oracle por esta SQL. */
    public static final class TableUse {
        final String table;
        boolean read;
        boolean written;
        boolean outerJoined;
        final Set<String> columnsRead = new TreeSet<>();
        final Set<String> columnsWritten = new TreeSet<>();

        TableUse(String table) {
            this.table = table;
        }

        public String table() {
            return table;
        }

        public boolean read() {
            return read;
        }

        public boolean written() {
            return written;
        }

        public boolean outerJoined() {
            return outerJoined;
        }

        public Set<String> columnsRead() {
            return columnsRead;
        }

        public Set<String> columnsWritten() {
            return columnsWritten;
        }

        public String access() {
            return read && written ? "L/G" : written ? "G" : "L";
        }
    }

    /** Coluna calculada: alias = expressão (ex. PUNIT = CASE ...). */
    public record Rule(String name, Ast.Expr expr, String oracleText) {
    }

    /** Alerta de revisão (join suspeito, ROWNUM sem ORDER BY...). */
    public record Warning(String code, String message) {
    }

    private final SqlSource source;
    private final List<Ast.Stmt> statements;
    Kind kind = Kind.VAZIA;
    final Map<String, TableUse> tables = new LinkedHashMap<>();
    final Set<String> params = new LinkedHashSet<>();
    final Set<String> calls = new TreeSet<>();
    final Set<String> sequences = new TreeSet<>();
    final List<String> created = new ArrayList<>();
    final List<Rule> rules = new ArrayList<>();
    final List<Warning> warnings = new ArrayList<>();
    final List<String> parseIssues = new ArrayList<>();
    final Set<String> unresolvedColumns = new TreeSet<>();

    SqlAnalysis(SqlSource source, List<Ast.Stmt> statements) {
        this.source = source;
        this.statements = statements;
    }

    TableUse use(String table) {
        return tables.computeIfAbsent(table, TableUse::new);
    }

    void warn(String code, String message) {
        if (warnings.stream().noneMatch(w -> w.message().equals(message))) {
            warnings.add(new Warning(code, message));
        }
    }

    public SqlSource source() {
        return source;
    }

    public List<Ast.Stmt> statements() {
        return statements;
    }

    public Kind kind() {
        return kind;
    }

    public Map<String, TableUse> tables() {
        return tables;
    }

    public Set<String> params() {
        return params;
    }

    /** Procedimentos, funções e pacotes do banco chamados (regras que ficam no Oracle). */
    public Set<String> calls() {
        return calls;
    }

    public Set<String> sequences() {
        return sequences;
    }

    /** Objetos criados por DDL (ex. "FUNCTION APLICARDATA"). */
    public List<String> created() {
        return created;
    }

    public List<Rule> rules() {
        return rules;
    }

    public List<Warning> warnings() {
        return warnings;
    }

    /** Trechos que o parser não entendeu, com o motivo. */
    public List<String> parseIssues() {
        return parseIssues;
    }

    /** Colunas sem qualificador numa consulta com várias tabelas (não dá para saber de qual tabela são). */
    public Set<String> unresolvedColumns() {
        return unresolvedColumns;
    }

    public boolean writes() {
        return tables.values().stream().anyMatch(TableUse::written);
    }

    /** Totalmente entendida pelo parser (sem RawStmt). */
    public boolean fullyParsed() {
        return parseIssues.isEmpty();
    }
}
