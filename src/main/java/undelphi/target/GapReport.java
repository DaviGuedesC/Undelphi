package undelphi.target;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import undelphi.sql.SqlAnalysis;
import undelphi.sql.SqlInventory;
import undelphi.target.OracleToPostgres.Issue;

/** Relatório de lacunas: o que de cada SQL do exe roda no destino e o que falta. */
public final class GapReport {

    private final TargetProfile profile;
    private final List<SourceTranslator.Translated> items;

    private GapReport(TargetProfile profile, List<SourceTranslator.Translated> items) {
        this.profile = profile;
        this.items = items;
    }

    public static GapReport build(SqlInventory inventory, TargetProfile profile) {
        List<SourceTranslator.Translated> out = new ArrayList<>();
        for (SqlAnalysis a : inventory.analyses()) {
            out.add(SourceTranslator.translate(a, profile));
        }
        return new GapReport(profile, out);
    }

    public List<SourceTranslator.Translated> items() {
        return items;
    }

    public long ready() {
        return items.stream().filter(SourceTranslator.Translated::translatable).count();
    }

    /** Tabelas do Oracle usadas sem destino no perfil. */
    public Set<String> unmappedTables() {
        Set<String> out = new TreeSet<>();
        for (SourceTranslator.Translated t : items) {
            for (String table : t.analysis().tables().keySet()) {
                if (profile.targetTable(table) == null) {
                    out.add(table);
                }
            }
        }
        return out;
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder("# Lacunas para o destino\n\n");
        sb.append("Perfil: **").append(profile.name()).append("**, escrita ")
                .append(profile.writesAllowed() ? "permitida" : "bloqueada").append(".\n\n");
        sb.append(String.format("%d de %d SQLs traduzem sem lacuna.%n%n", ready(), items.size()));

        Set<String> unmapped = unmappedTables();
        if (!unmapped.isEmpty()) {
            sb.append("## Tabelas sem destino no perfil\n\n");
            unmapped.forEach(t -> sb.append("- ").append(t).append('\n'));
            sb.append('\n');
        }

        Map<String, Set<String>> byGap = new TreeMap<>();
        for (SourceTranslator.Translated t : items) {
            for (Issue i : t.gaps()) {
                byGap.computeIfAbsent(i.message(), k -> new TreeSet<>()).add(t.analysis().source().origin());
            }
        }
        if (!byGap.isEmpty()) {
            sb.append("## Lacunas\n\n| Lacuna | Onde |\n|---|---|\n");
            byGap.forEach((k, v) -> sb.append("| ").append(k.replace("|", "\\|")).append(" | ")
                    .append(String.join(", ", v)).append(" |\n"));
            sb.append('\n');
        }

        sb.append("## Por SQL\n\n| Origem | Situação | Avisos |\n|---|---|---|\n");
        for (SourceTranslator.Translated t : items) {
            sb.append("| ").append(t.analysis().source().origin()).append(" | ")
                    .append(t.translatable() ? "pronta" : t.gaps().size() + " lacuna(s)").append(" | ")
                    .append(String.join("; ", t.warnings().stream().map(Issue::message).toList()).replace("|", "\\|"))
                    .append(" |\n");
        }
        return sb.toString();
    }
}
