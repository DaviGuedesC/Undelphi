package undelphi.target;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Perfil de destino: para onde vai cada tabela/coluna do WinThor, com qual usuário e conexão.
 * Valores de conexão aceitam ${VAR} e ${VAR:-padrão}, resolvidos do ambiente na hora de rodar
 * (no código Go gerado), nunca gravados no código. Senha literal é recusada.
 *
 * <pre>
 * nome: destino-exemplo
 * conexao:
 *   driver: postgres
 *   host: ${PGHOST}
 *   porta: ${PGPORT:-5432}
 *   banco: ${PGDATABASE}
 *   usuario: pricing2
 *   senha: ${PGPASSWORD}
 *   schema: public
 * escrita: bloqueada            # bloqueada | permitida
 * colunas_padrao: minusculas    # colunas não listadas: minusculas | igual | lacuna
 * esquema_destino: esquema.yaml # opcional: tabelas e colunas que existem no destino
 * tabelas:
 *   PCTABPR:
 *     destino: preco_dados
 *     colunas:
 *       PTABELA1: precoatacado1
 *       VLST: ~                 # sem equivalente: vira lacuna
 * funcoes:
 *   FNC_X: fnc_x                # objetos do Oracle reimplementados no destino
 * sequencias:
 *   DFSEQ_PCCOTA: seq_cotacao
 * </pre>
 */
public final class TargetProfile {

    public enum ColumnDefault {
        MINUSCULAS, IGUAL, LACUNA
    }

    public record Connection(String driver, String host, String port, String database, String user, String password,
            String schema) {
    }

    /** Destino de uma tabela. columns: coluna Oracle → coluna destino (null = sem equivalente). */
    public record TableMapping(String source, String target, Map<String, String> columns) {
    }

    /** Resultado de mapear uma coluna: nome no destino, ou null com o motivo. */
    public record ColumnTarget(String name, String gap) {
        public boolean ok() {
            return name != null;
        }
    }

    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?}");

    private final String name;
    private final Connection connection;
    private final boolean writesAllowed;
    private final ColumnDefault columnDefault;
    private final Map<String, TableMapping> tables;
    private final Map<String, String> functions;
    private final Map<String, String> sequences;
    private final TargetSchema schema;

    private TargetProfile(String name, Connection connection, boolean writesAllowed, ColumnDefault columnDefault,
            Map<String, TableMapping> tables, Map<String, String> functions, Map<String, String> sequences,
            TargetSchema schema) {
        this.name = name;
        this.connection = connection;
        this.writesAllowed = writesAllowed;
        this.columnDefault = columnDefault;
        this.tables = tables;
        this.functions = functions;
        this.sequences = sequences;
        this.schema = schema;
    }

    /** Perfil vazio: nada mapeado (tudo vira lacuna), útil para só ver o que falta. */
    public static TargetProfile empty() {
        return new TargetProfile("vazio", new Connection("postgres", "${PGHOST}", "${PGPORT:-5432}", "${PGDATABASE}",
                "${PGUSER}", "${PGPASSWORD}", "public"), false, ColumnDefault.MINUSCULAS, Map.of(), Map.of(), Map.of(), null);
    }

    public static TargetProfile load(Path yaml) throws IOException {
        TargetProfile p = parse(Files.readString(yaml, StandardCharsets.UTF_8), yaml.getParent());
        return p;
    }

    public static TargetProfile parse(String yamlText, Path baseDir) throws IOException {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
        if (!(root instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("perfil de destino vazio ou inválido");
        }
        String name = str(m.get("nome"), "perfil");

        Map<?, ?> c = m.get("conexao") instanceof Map<?, ?> cm ? cm : Map.of();
        String password = str(c.get("senha"), "${PGPASSWORD}");
        if (!password.isEmpty() && !ENV.matcher(password).matches()) {
            throw new IllegalArgumentException(
                    "conexao.senha precisa vir de variável de ambiente (ex. ${PGPASSWORD}); senha escrita no perfil não é aceita");
        }
        Connection conn = new Connection(str(c.get("driver"), "postgres"), str(c.get("host"), "${PGHOST}"),
                str(c.get("porta"), "${PGPORT:-5432}"), str(c.get("banco"), "${PGDATABASE}"),
                str(c.get("usuario"), "${PGUSER}"), password, str(c.get("schema"), "public"));

        String escrita = str(m.get("escrita"), "bloqueada").toLowerCase(Locale.ROOT);
        if (!escrita.equals("bloqueada") && !escrita.equals("permitida")) {
            throw new IllegalArgumentException("escrita deve ser 'bloqueada' ou 'permitida', veio: " + escrita);
        }
        ColumnDefault def = ColumnDefault.valueOf(str(m.get("colunas_padrao"), "minusculas").toUpperCase(Locale.ROOT));

        Map<String, TableMapping> tables = new LinkedHashMap<>();
        if (m.get("tabelas") instanceof Map<?, ?> tm) {
            for (Map.Entry<?, ?> e : tm.entrySet()) {
                String source = String.valueOf(e.getKey()).toUpperCase(Locale.ROOT);
                String target;
                Map<String, String> cols = new LinkedHashMap<>();
                if (e.getValue() instanceof Map<?, ?> tv) {
                    target = str(tv.get("destino"), null);
                    if (tv.get("colunas") instanceof Map<?, ?> cv) {
                        cv.forEach((k, v) -> cols.put(String.valueOf(k).toUpperCase(Locale.ROOT), v == null ? null : String.valueOf(v)));
                    }
                } else {
                    target = e.getValue() == null ? null : String.valueOf(e.getValue());
                }
                tables.put(source, new TableMapping(source, target, cols));
            }
        }
        Map<String, String> functions = upperKeys(m.get("funcoes"));
        Map<String, String> sequences = upperKeys(m.get("sequencias"));

        TargetSchema schema = null;
        Object schemaRef = m.get("esquema_destino");
        if (schemaRef instanceof String s && !s.isBlank()) {
            Path file = baseDir == null ? Path.of(s) : baseDir.resolve(s);
            schema = TargetSchema.load(file);
        } else if (schemaRef instanceof Map<?, ?> inline) {
            schema = TargetSchema.of(inline);
        }
        return new TargetProfile(name, conn, escrita.equals("permitida"), def, tables, functions, sequences, schema);
    }

    public String name() {
        return name;
    }

    public Connection connection() {
        return connection;
    }

    public boolean writesAllowed() {
        return writesAllowed;
    }

    public Map<String, TableMapping> tables() {
        return Collections.unmodifiableMap(tables);
    }

    public Map<String, String> functions() {
        return functions;
    }

    public Map<String, String> sequences() {
        return sequences;
    }

    /** Esquema do destino (para conferir se tabela/coluna existem), ou null se não informado. */
    public TargetSchema schema() {
        return schema;
    }

    /** Tabela de destino, ou null se não mapeada. */
    public String targetTable(String oracleTable) {
        TableMapping t = tables.get(oracleTable.toUpperCase(Locale.ROOT));
        return t == null ? null : t.target();
    }

    public ColumnTarget targetColumn(String oracleTable, String oracleColumn) {
        TableMapping t = tables.get(oracleTable.toUpperCase(Locale.ROOT));
        String col = oracleColumn.toUpperCase(Locale.ROOT);
        if (t == null || t.target() == null) {
            return new ColumnTarget(null, "tabela " + oracleTable + " sem destino no perfil");
        }
        String target;
        if (t.columns().containsKey(col)) {
            target = t.columns().get(col);
            if (target == null) {
                return new ColumnTarget(null, oracleTable + "." + col + " marcada sem equivalente no perfil");
            }
        } else {
            switch (columnDefault) {
                case MINUSCULAS -> target = col.toLowerCase(Locale.ROOT);
                case IGUAL -> target = col;
                default -> {
                    return new ColumnTarget(null, oracleTable + "." + col + " não listada no perfil");
                }
            }
        }
        if (schema != null && schema.hasTable(t.target()) && !schema.hasColumn(t.target(), target)) {
            return new ColumnTarget(null, oracleTable + "." + col + " → " + t.target() + "." + target + " não existe no destino");
        }
        return new ColumnTarget(target, null);
    }

    /** Variáveis de ambiente que a conexão usa (para documentar no código gerado). */
    public Set<String> envVars() {
        Set<String> out = new TreeSet<>();
        for (String v : List.of(connection.host(), connection.port(), connection.database(), connection.user(),
                connection.password(), connection.schema())) {
            Matcher mt = ENV.matcher(v);
            while (mt.find()) {
                out.add(mt.group(1));
            }
        }
        return out;
    }

    /** Resolve ${VAR} e ${VAR:-padrão} com o mapa dado (o ambiente, na prática). */
    public static String expand(String value, Map<String, String> env) {
        Matcher mt = ENV.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (mt.find()) {
            String v = env.get(mt.group(1));
            if (v == null || v.isEmpty()) {
                v = mt.group(3) != null ? mt.group(3) : "";
            }
            mt.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        mt.appendTail(sb);
        return sb.toString();
    }

    private static Map<String, String> upperKeys(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k).toUpperCase(Locale.ROOT), v == null ? null : String.valueOf(v)));
        }
        return out;
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    /** Lista de tabelas do perfil (para exibir). */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        tables.values().forEach(t -> out.add(t.source() + " → " + (t.target() == null ? "(sem destino)" : t.target())));
        return out;
    }
}
