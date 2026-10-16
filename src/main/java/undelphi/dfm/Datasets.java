package undelphi.dfm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Datasets do DFM (TOraQuery, TClientDataSet...) e seus campos persistentes (TStringField, TFloatField...). */
public final class Datasets {

    /** Campo persistente: nome no banco, classe Delphi, rótulo e tamanho. */
    public record Field(String fieldName, String className, String displayLabel, int size, boolean visible) {

        /** Tipo lógico para os geradores: "texto", "numero", "data", "booleano", "binario". */
        public String kind() {
            String c = className.toLowerCase(Locale.ROOT);
            if (c.contains("date") || c.contains("time")) {
                return "data";
            }
            if (c.contains("boolean")) {
                return "booleano";
            }
            if (c.contains("blob") || c.contains("memo") && !c.contains("widememo") || c.contains("bytes") || c.contains("graphic")) {
                return c.contains("memo") ? "texto" : "binario";
            }
            if (c.contains("float") || c.contains("integer") || c.contains("currency") || c.contains("bcd")
                    || c.contains("largeint") || c.contains("smallint") || c.contains("word") || c.contains("autoinc")
                    || c.contains("extended") || c.contains("single") || c.contains("shortint") || c.contains("byte")) {
                return "numero";
            }
            return "texto";
        }
    }

    /** É um dataset (tem campos ou é uma classe de dataset conhecida). */
    public static boolean isDataset(DfmObject obj) {
        String c = obj.className().toLowerCase(Locale.ROOT);
        return c.endsWith("query") || c.endsWith("table") || c.endsWith("dataset") || c.endsWith("storedproc")
                || c.endsWith("memtable") || c.equals("toratable") || !fields(obj).isEmpty();
    }

    /** É um componente que só executa SQL (TOraSQL, TFDCommand...), sem devolver linhas. */
    public static boolean isCommand(DfmObject obj) {
        String c = obj.className().toLowerCase(Locale.ROOT);
        return c.equals("torasql") || c.endsWith("command") || c.endsWith("script");
    }

    public static boolean isDataSource(DfmObject obj) {
        return obj.className().toLowerCase(Locale.ROOT).endsWith("datasource");
    }

    public static List<Field> fields(DfmObject dataset) {
        List<Field> out = new ArrayList<>();
        for (DfmObject child : dataset.children()) {
            String c = child.className();
            if (c.endsWith("Field") && child.text("FieldName") != null) {
                out.add(new Field(child.text("FieldName"), c, child.text("DisplayLabel"), child.integer("Size", 0),
                        child.bool("Visible", true)));
            }
        }
        if (out.isEmpty() && dataset.get("FieldDefs") instanceof DfmValue.Collection defs) {
            for (DfmValue.Item item : defs.items()) {
                Object name = item.properties().get("Name");
                Object type = item.properties().get("DataType");
                Object size = item.properties().get("Size");
                if (name instanceof String n) {
                    String t = type == null ? "ftString" : type.toString();
                    out.add(new Field(n, "T" + t.substring(2) + "Field", null,
                            size instanceof Long l ? l.intValue() : 0, true));
                }
            }
        }
        return out;
    }

    public static Field field(DfmObject dataset, String fieldName) {
        if (dataset == null || fieldName == null) {
            return null;
        }
        return fields(dataset).stream().filter(f -> f.fieldName().equalsIgnoreCase(fieldName)).findFirst().orElse(null);
    }

    private Datasets() {
    }
}
