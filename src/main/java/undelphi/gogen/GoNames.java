package undelphi.gogen;

import java.util.Locale;

import undelphi.sql.SqlSource;

/**
 * Nomes Go gerados a partir das SQLs. É o contrato com o gerador de handlers (F4) e as telas Vue:
 * eles chamam estas funções em vez de adivinhar o nome.
 *
 * <ul>
 * <li>origin "TDMDADOSXML.exeGerarPCCOTA" → "DmdadosxmlExeGerarPCCOTA" (sai o T inicial do recurso;
 * partes todas em maiúsculas viram "Maiuscula+minúsculas"; as de caixa mista só ganham a inicial maiúscula)</li>
 * <li>property diferente de SQL.Strings vira sufixo: "SQLUpdate.Strings" → "...SqlUpdate"</li>
 * <li>origin "literal@0x52A1F0" → "Literal52A1F0"</li>
 * </ul>
 */
public final class GoNames {

    private GoNames() {
    }

    /** Nome exportado da função do repositório para esta SQL. */
    public static String queryFunc(SqlSource s) {
        String origin = s.origin() == null ? "" : s.origin();
        StringBuilder sb = new StringBuilder();
        if (origin.startsWith("literal@")) {
            sb.append("Literal").append(origin.substring("literal@".length()).replaceFirst("^0[xX]", "").toUpperCase(Locale.ROOT));
        } else {
            String[] parts = origin.split("[.]");
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (i == 0 && p.length() > 1 && p.charAt(0) == 'T' && p.equals(p.toUpperCase(Locale.ROOT))) {
                    p = p.substring(1);
                }
                sb.append(camel(p));
            }
        }
        String prop = s.property();
        if (prop != null && !prop.isBlank() && !prop.equalsIgnoreCase("SQL.Strings") && !prop.equalsIgnoreCase("SQL")) {
            sb.append(camel(prop.replaceAll("(?i)\\.Strings$", "")));
        }
        String out = sb.toString();
        if (out.isEmpty() || !Character.isLetter(out.charAt(0))) {
            out = "Sql" + out;
        }
        return out;
    }

    public static String paramsType(SqlSource s) {
        return queryFunc(s) + "Params";
    }

    public static String rowType(SqlSource s) {
        return queryFunc(s) + "Row";
    }

    /** Nome do pacote Go da rotina: "PCNOV9860.exe" → "pcnov9860". */
    public static String packageName(String exeName) {
        String base = exeName.replaceAll("(?i)\\.(exe|dll|bpl)$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (base.isEmpty()) {
            return "rotina";
        }
        return Character.isLetter(base.charAt(0)) ? base : "r" + base;
    }

    /** Campo Go de um parâmetro/coluna: "CHAVENFE" → "Chavenfe", "COD_CLI" → "CodCli". */
    public static String field(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("[^A-Za-z0-9]+")) {
            sb.append(camel(part));
        }
        String out = sb.toString();
        if (out.isEmpty()) {
            return "X";
        }
        return Character.isLetter(out.charAt(0)) ? out : "C" + out;
    }

    /** Uma parte: toda maiúscula → "Inicial+resto minúsculo"; caixa mista → só a inicial maiúscula. */
    static String camel(String part) {
        StringBuilder sb = new StringBuilder();
        for (String p : part.split("[^A-Za-z0-9]+")) {
            if (p.isEmpty()) {
                continue;
            }
            if (p.equals(p.toUpperCase(Locale.ROOT)) || p.equals(p.toLowerCase(Locale.ROOT))) {
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase(Locale.ROOT));
            } else {
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        return sb.toString();
    }
}
