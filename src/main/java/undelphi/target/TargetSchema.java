package undelphi.target;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Tabelas e colunas que existem no banco de destino (arquivo YAML ou leitura do information_schema). */
public final class TargetSchema {

    private final Map<String, Set<String>> tables;

    private TargetSchema(Map<String, Set<String>> tables) {
        this.tables = tables;
    }

    /** Formato: { tabela: [coluna, coluna, ...] }. */
    public static TargetSchema load(Path yaml) throws IOException {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(yaml, StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("esquema de destino inválido: " + yaml);
        }
        return of(m);
    }

    public static TargetSchema of(Map<?, ?> m) {
        Map<String, Set<String>> out = new TreeMap<>();
        m.forEach((k, v) -> {
            Set<String> cols = new TreeSet<>();
            if (v instanceof List<?> l) {
                l.forEach(c -> cols.add(String.valueOf(c).toLowerCase(Locale.ROOT)));
            }
            out.put(String.valueOf(k).toLowerCase(Locale.ROOT), cols);
        });
        return new TargetSchema(out);
    }

    /**
     * Lê tabelas e colunas do schema via JDBC (somente leitura no information_schema). A URL e o usuário
     * vêm de quem chama; a senha deve vir do ambiente.
     */
    public static TargetSchema introspect(String jdbcUrl, String user, String password, String schema) throws SQLException {
        Map<String, Set<String>> out = new TreeMap<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, password)) {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = ? ORDER BY 1, ordinal_position")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.computeIfAbsent(rs.getString(1).toLowerCase(Locale.ROOT), k -> new TreeSet<>())
                                .add(rs.getString(2).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return new TargetSchema(out);
    }

    public void save(Path yaml) throws IOException {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Map<String, List<String>> plain = new TreeMap<>();
        tables.forEach((k, v) -> plain.put(k, List.copyOf(v)));
        Files.writeString(yaml, new Yaml(o).dump(plain), StandardCharsets.UTF_8);
    }

    public boolean hasTable(String table) {
        return tables.containsKey(strip(table));
    }

    public boolean hasColumn(String table, String column) {
        Set<String> cols = tables.get(strip(table));
        return cols != null && cols.contains(column.toLowerCase(Locale.ROOT));
    }

    public Map<String, Set<String>> tables() {
        return tables;
    }

    /** "public.tabela" → "tabela". */
    private static String strip(String table) {
        String t = table.toLowerCase(Locale.ROOT);
        int dot = t.lastIndexOf('.');
        return dot < 0 ? t : t.substring(dot + 1);
    }
}
