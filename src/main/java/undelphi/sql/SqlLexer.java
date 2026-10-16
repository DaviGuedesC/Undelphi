package undelphi.sql;

import java.util.ArrayList;
import java.util.List;

/** Tokenizador de SQL/PL-SQL do Oracle. Comentários são descartados. */
public final class SqlLexer {

    public enum Type {
        IDENT, QUOTED_IDENT, STRING, NUMBER, PARAM, OP, PUNCT, EOF
    }

    public record Token(Type type, String text, int pos) {

        public boolean is(String word) {
            return (type == Type.IDENT || type == Type.OP || type == Type.PUNCT) && text.equalsIgnoreCase(word);
        }

        public boolean isIdent() {
            return type == Type.IDENT || type == Type.QUOTED_IDENT;
        }

        /** Nome normalizado: identificador comum em maiúsculas, entre aspas como está. */
        public String name() {
            return type == Type.QUOTED_IDENT ? text : text.toUpperCase();
        }

        @Override
        public String toString() {
            return text;
        }
    }

    public static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'') {
                int start = i++;
                StringBuilder sb = new StringBuilder();
                while (i < n) {
                    char d = sql.charAt(i++);
                    if (d == '\'') {
                        if (i < n && sql.charAt(i) == '\'') {
                            sb.append('\'');
                            i++;
                        } else {
                            break;
                        }
                    } else {
                        sb.append(d);
                    }
                }
                out.add(new Token(Type.STRING, sb.toString(), start));
            } else if (c == '"') {
                int start = i++;
                int end = sql.indexOf('"', i);
                end = end < 0 ? n : end;
                out.add(new Token(Type.QUOTED_IDENT, sql.substring(i, end), start));
                i = Math.min(n, end + 1);
            } else if (c == ':' && i + 1 < n && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                int start = i++;
                while (i < n && isIdentPart(sql.charAt(i))) {
                    i++;
                }
                out.add(new Token(Type.PARAM, sql.substring(start + 1, i).toUpperCase(), start));
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                int start = i;
                while (i < n && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    if (sql.charAt(i) == '.' && i + 1 < n && sql.charAt(i + 1) == '.') {
                        break; // faixa 1..10 do PL/SQL
                    }
                    i++;
                }
                if (i < n && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E') && i + 1 < n
                        && (Character.isDigit(sql.charAt(i + 1)) || sql.charAt(i + 1) == '-' || sql.charAt(i + 1) == '+')) {
                    i += 2;
                    while (i < n && Character.isDigit(sql.charAt(i))) {
                        i++;
                    }
                }
                out.add(new Token(Type.NUMBER, sql.substring(start, i), start));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && isIdentPart(sql.charAt(i))) {
                    i++;
                }
                out.add(new Token(Type.IDENT, sql.substring(start, i), start));
            } else {
                String two = i + 1 < n ? sql.substring(i, i + 2) : "";
                if (sql.startsWith("(+)", i)) {
                    out.add(new Token(Type.OP, "(+)", i));
                    i += 3;
                } else if (two.equals("<>") || two.equals("!=") || two.equals("^=") || two.equals("<=") || two.equals(">=")
                        || two.equals("||") || two.equals(":=") || two.equals("=>") || two.equals("..")) {
                    out.add(new Token(Type.OP, two, i));
                    i += 2;
                } else if ("=<>+-*/%".indexOf(c) >= 0) {
                    out.add(new Token(Type.OP, String.valueOf(c), i));
                    i++;
                } else {
                    out.add(new Token(Type.PUNCT, String.valueOf(c), i));
                    i++;
                }
            }
        }
        out.add(new Token(Type.EOF, "", n));
        return out;
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private SqlLexer() {
    }
}
