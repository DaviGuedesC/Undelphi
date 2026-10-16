package undelphi.dfm;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converte a árvore do DFM para o formato texto do Delphi (o mesmo que a IDE mostra em "View as Text"). */
public final class DfmTextWriter {

    private static final int MAX_BINARY_BYTES = 64;

    public static String write(DfmObject root) {
        StringBuilder sb = new StringBuilder();
        writeObject(sb, root, 0);
        return sb.toString();
    }

    private static void writeObject(StringBuilder sb, DfmObject obj, int depth) {
        String indent = "  ".repeat(depth);
        String keyword = obj.inline() ? "inline" : obj.inherited() ? "inherited" : "object";
        sb.append(indent).append(keyword).append(' ');
        if (!obj.name().isEmpty()) {
            sb.append(obj.name()).append(": ");
        }
        sb.append(obj.className()).append('\n');
        writeProperties(sb, obj.properties(), depth + 1);
        for (DfmObject child : obj.children()) {
            writeObject(sb, child, depth + 1);
        }
        sb.append(indent).append("end\n");
    }

    private static void writeProperties(StringBuilder sb, Map<String, Object> props, int depth) {
        String indent = "  ".repeat(depth);
        props.forEach((name, value) -> {
            sb.append(indent).append(name).append(" = ");
            if (isSecret(name)) {
                sb.append("'<redigido pelo undelphi>'");
            }
            else {
                writeValue(sb, value, depth);
            }
            sb.append('\n');
        });
    }

    /** Senhas e strings de conexão nunca saem do exe (EncryptedPassword do TOraSession, Password, ConnectString...). */
    public static boolean isSecret(String property) {
        String p = property.toLowerCase(Locale.ROOT);
        return p.contains("password") || p.contains("connectstring") || p.contains("connectionstring")
                || p.endsWith("pwd") || p.equals("params.strings");
    }

    private static void writeValue(StringBuilder sb, Object value, int depth) {
        String indent = "  ".repeat(depth);
        if (value == null || value instanceof DfmValue.Nil) {
            sb.append("nil");
        }
        else if (value instanceof String s) {
            sb.append(quote(s));
        }
        else if (value instanceof Double d) {
            sb.append(String.format(Locale.ROOT, "%s", d));
        }
        else if (value instanceof Boolean bool) {
            sb.append(bool ? "True" : "False");
        }
        else if (value instanceof DfmValue.SetOf set) {
            sb.append('[').append(String.join(", ", set.items())).append(']');
        }
        else if (value instanceof DfmValue.Binary bin) {
            sb.append("{ ").append(bin.bytes().length).append(" bytes");
            if (bin.bytes().length <= MAX_BINARY_BYTES) {
                sb.append(':');
                for (byte x : bin.bytes()) {
                    sb.append(String.format(" %02X", x));
                }
            }
            sb.append(" }");
        }
        else if (value instanceof List<?> list) {
            sb.append('(');
            for (int i = 0; i < list.size(); i++) {
                sb.append('\n').append(indent).append("  ");
                writeValue(sb, list.get(i), depth + 1);
            }
            sb.append(')');
        }
        else if (value instanceof DfmValue.Collection col) {
            sb.append('<');
            for (DfmValue.Item item : col.items()) {
                sb.append('\n').append(indent).append("  item");
                if (item.index() != null) {
                    sb.append(" [").append(item.index()).append(']');
                }
                sb.append('\n');
                writeProperties(sb, item.properties(), depth + 2);
                sb.append(indent).append("  end");
            }
            sb.append('>');
        }
        else {
            sb.append(value);
        }
    }

    /** Aspas no estilo Delphi: 'it''s'. Caracteres de controle viram #13 etc. */
    static String quote(String s) {
        if (s.isEmpty()) {
            return "''";
        }
        StringBuilder sb = new StringBuilder();
        boolean open = false;
        for (char c : s.toCharArray()) {
            if (c < 32) {
                if (open) {
                    sb.append('\'');
                    open = false;
                }
                sb.append('#').append((int) c);
            }
            else {
                if (!open) {
                    sb.append('\'');
                    open = true;
                }
                sb.append(c == '\'' ? "''" : String.valueOf(c));
            }
        }
        if (open) {
            sb.append('\'');
        }
        return sb.toString();
    }

    private DfmTextWriter() {
    }
}
