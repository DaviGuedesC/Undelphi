package undelphi.dfm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import undelphi.dfm.FormResources.Form;

/**
 * SQL guardada nos componentes do DFM (TOraQuery, TOraSQL, TOraStoredProc, TFDQuery, TClientDataSet...).
 *
 * @param origin         "&lt;RECURSO&gt;.&lt;componente&gt;", ex. "TDMDADOSXML.exeGerarPCCOTA"
 * @param componentClass classe do componente, ex. "TOraSQL"
 * @param property       propriedade de onde veio, ex. "SQL.Strings", "SQLUpdate.Strings", "StoredProcName"
 * @param sql            o texto
 */
public record DfmSql(String origin, String componentClass, String property, String sql) {

    /** SQL.Strings, SQLInsert/SQLUpdate/SQLDelete/SQLRefresh/SQLLock/SQLRecCount.Strings (ODAC, FireDAC...). */
    private static final Pattern SQL_LIST = Pattern.compile("^SQL[A-Za-z]*\\.Strings$");

    public static List<DfmSql> extract(List<Form> forms) {
        List<DfmSql> out = new ArrayList<>();
        for (Form f : forms) {
            f.root().walk(obj -> {
                for (Map.Entry<String, Object> p : obj.properties().entrySet()) {
                    String name = p.getKey();
                    String text = null;
                    if (SQL_LIST.matcher(name).matches()) {
                        text = obj.lines(name);
                    }
                    else if ((name.equals("CommandText") || name.equals("StoredProcName")) && p.getValue() instanceof String s) {
                        text = s;
                    }
                    if (text != null && !text.isBlank()) {
                        out.add(new DfmSql(f.resourceName() + "." + obj.name(), obj.className(), name, text));
                    }
                }
            });
        }
        return out;
    }
}
