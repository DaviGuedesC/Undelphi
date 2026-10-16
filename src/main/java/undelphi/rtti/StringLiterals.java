package undelphi.rtti;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import undelphi.pe.PeImage;

/**
 * Strings literais do código Delphi (2009+). O compilador grava cada constante com o cabeçalho do
 * StrRec: CodePage (2), ElemSize (2), RefCnt = -1 (4), Length (4), e logo depois os caracteres e um zero.
 * UnicodeString: ElemSize 2 e CodePage 1200; AnsiString: ElemSize 1.
 */
public final class StringLiterals {

    /** Literal: endereço dos caracteres (o valor que o código usa) e o texto. */
    public record Literal(long address, String text, boolean unicode) {
    }

    private static final Charset ANSI = Charset.forName("windows-1252");
    private static final Pattern SQL = Pattern.compile(
            "(?is)^\\s*(select|insert\\s+into|update\\s+\\w|delete\\s+from|merge\\s+into|begin|declare|"
                    + "(and|or|where|from|order\\s+by|group\\s+by|inner\\s+join|left\\s+join|union)\\s)");
    private static final Pattern SQL_WORDS = Pattern.compile(
            "(?i)\\b(select|from|where|update|insert|delete|values|and|join|order by|group by|nvl|decode|rownum)\\b");

    private StringLiterals() {
    }

    public static List<Literal> scan(PeImage pe) {
        List<Literal> out = new ArrayList<>();
        byte[] d = pe.data();
        for (PeImage.Section s : pe.sections()) {
            int start = s.rawOffset();
            int end = Math.min(d.length, start + s.rawSize()) - 12;
            for (int off = start; off < end; off += 4) {
                if (pe.i32(off + 4) != -1) {
                    continue;
                }
                int codePage = pe.u16(off);
                int elem = pe.u16(off + 2);
                int len = pe.i32(off + 8);
                if (len <= 0 || len > 65536) {
                    continue;
                }
                int chars = off + 12;
                if (elem == 2 && codePage == 1200) {
                    int bytes = len * 2;
                    if (chars + bytes + 2 > d.length || pe.u16(chars + bytes) != 0) {
                        continue;
                    }
                    String text = new String(d, chars, bytes, StandardCharsets.UTF_16LE);
                    if (printable(text)) {
                        out.add(new Literal(pe.offsetToVa(chars), text, true));
                    }
                } else if (elem == 1 && codePage != 0 && codePage != 0xFFFF) {
                    if (chars + len + 1 > d.length || d[chars + len] != 0) {
                        continue;
                    }
                    String text = new String(d, chars, len, ANSI);
                    if (printable(text)) {
                        out.add(new Literal(pe.offsetToVa(chars), text, false));
                    }
                }
            }
        }
        return out;
    }

    /** Condição com bind: TABELA.COLUNA = :PARAM, BETWEEN :A AND :B, LIKE :X, IN (:Y). */
    private static final Pattern CONDITION = Pattern.compile(
            "(?i)\\b\\w+\\.\\w+\\s*(=|<>|<=|>=|<|>|between|like|in\\s*\\()\\s*:\\w+");

    /** Parece SQL (comando inteiro ou pedaço de SQL montada em código). */
    public static boolean looksLikeSql(String text) {
        if (text.length() < 8) {
            return false;
        }
        if (CONDITION.matcher(text).find()) {
            return true;
        }
        if (SQL.matcher(text).find()) {
            return SQL_WORDS.matcher(text).results().count() >= 2 || text.strip().toUpperCase().startsWith("SELECT");
        }
        return false;
    }

    private static boolean printable(String s) {
        int bad = 0;
        for (char c : s.toCharArray()) {
            if (c < 32 && c != '\r' && c != '\n' && c != '\t') {
                bad++;
            }
        }
        return bad == 0;
    }
}
