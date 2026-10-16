package undelphi.dfm;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser do formato binário de DFM (assinatura TPF0), o mesmo que o TReader do Delphi lê em tempo de execução.
 */
public final class DfmParser {

    // TValueType (System.Classes)
    private static final int VA_NULL = 0;
    private static final int VA_LIST = 1;
    private static final int VA_INT8 = 2;
    private static final int VA_INT16 = 3;
    private static final int VA_INT32 = 4;
    private static final int VA_EXTENDED = 5;
    private static final int VA_STRING = 6;
    private static final int VA_IDENT = 7;
    private static final int VA_FALSE = 8;
    private static final int VA_TRUE = 9;
    private static final int VA_BINARY = 10;
    private static final int VA_SET = 11;
    private static final int VA_LSTRING = 12;
    private static final int VA_NIL = 13;
    private static final int VA_COLLECTION = 14;
    private static final int VA_SINGLE = 15;
    private static final int VA_CURRENCY = 16;
    private static final int VA_DATE = 17;
    private static final int VA_WSTRING = 18;
    private static final int VA_INT64 = 19;
    private static final int VA_UTF8STRING = 20;
    private static final int VA_DOUBLE = 21;

    /** Strings ANSI do DFM: o WinThor é compilado em Windows-1252. */
    private static final Charset ANSI = Charset.forName("windows-1252");

    private final byte[] b;
    private int pos;

    private DfmParser(byte[] data, int offset) {
        this.b = data;
        this.pos = offset;
    }

    /** Lê o DFM que começa em offset (na assinatura TPF0). */
    public static DfmObject parse(byte[] data, int offset) {
        DfmParser p = new DfmParser(data, offset);
        if (data[offset] != 'T' || data[offset + 1] != 'P' || data[offset + 2] != 'F' || data[offset + 3] != '0') {
            throw new IllegalArgumentException("DFM sem assinatura TPF0 no offset " + offset);
        }
        p.pos += 4;
        return p.readObject();
    }

    private DfmObject readObject() {
        int flags = 0;
        int prefix = u8();
        if ((prefix & 0xF0) == 0xF0) {
            flags = prefix & 0x0F;
            pos++;
            if ((flags & DfmObject.FF_CHILD_POS) != 0) {
                readValue();
            }
        }
        String className = shortString();
        String name = shortString();
        DfmObject obj = new DfmObject(className, name, flags);
        readProperties(obj.properties());
        while (u8() != VA_NULL) {
            obj.addChild(readObject());
        }
        pos++;
        return obj;
    }

    private void readProperties(Map<String, Object> into) {
        while (u8() != VA_NULL) {
            String name = shortString();
            into.put(name, readValue());
        }
        pos++;
    }

    private Object readValue() {
        int type = u8();
        pos++;
        return switch (type) {
            case VA_NULL -> null;
            case VA_LIST -> {
                List<Object> list = new ArrayList<>();
                while (u8() != VA_NULL) {
                    list.add(readValue());
                }
                pos++;
                yield list;
            }
            case VA_INT8 -> (long) b[pos++];
            case VA_INT16 -> {
                long v = (short) (u8() | u8(pos + 1) << 8);
                pos += 2;
                yield v;
            }
            case VA_INT32 -> {
                long v = i32();
                yield v;
            }
            case VA_INT64 -> {
                long lo = i32() & 0xFFFFFFFFL;
                long hi = i32();
                yield hi << 32 | lo;
            }
            case VA_EXTENDED -> {
                double v = extended(pos);
                pos += 10;
                yield v;
            }
            case VA_SINGLE -> {
                double v = Float.intBitsToFloat(i32());
                yield v;
            }
            case VA_DOUBLE, VA_DATE -> {
                long lo = i32() & 0xFFFFFFFFL;
                long hi = i32();
                yield Double.longBitsToDouble(hi << 32 | lo);
            }
            case VA_CURRENCY -> {
                long lo = i32() & 0xFFFFFFFFL;
                long hi = i32();
                yield (hi << 32 | lo) / 10000.0;
            }
            case VA_STRING -> {
                int len = u8();
                pos++;
                yield text(len, ANSI);
            }
            case VA_LSTRING -> text(i32(), ANSI);
            case VA_UTF8STRING -> text(i32(), StandardCharsets.UTF_8);
            case VA_WSTRING -> text(i32() * 2, StandardCharsets.UTF_16LE);
            case VA_IDENT -> new DfmValue.Ident(shortString());
            case VA_FALSE -> Boolean.FALSE;
            case VA_TRUE -> Boolean.TRUE;
            case VA_NIL -> new DfmValue.Nil();
            case VA_BINARY -> {
                int len = i32();
                byte[] bytes = java.util.Arrays.copyOfRange(b, pos, pos + len);
                pos += len;
                yield new DfmValue.Binary(bytes);
            }
            case VA_SET -> {
                List<String> items = new ArrayList<>();
                String item;
                while (!(item = shortString()).isEmpty()) {
                    items.add(item);
                }
                yield new DfmValue.SetOf(items);
            }
            case VA_COLLECTION -> {
                List<DfmValue.Item> items = new ArrayList<>();
                while (u8() != VA_NULL) {
                    Integer index = null;
                    int t = u8();
                    if (t == VA_INT8 || t == VA_INT16 || t == VA_INT32) {
                        index = ((Long) readValue()).intValue();
                    }
                    if (u8() != VA_LIST) {
                        throw new IllegalStateException("item de coleção sem vaList no offset " + pos);
                    }
                    pos++;
                    Map<String, Object> props = new LinkedHashMap<>();
                    readProperties(props);
                    items.add(new DfmValue.Item(index, props));
                }
                pos++;
                yield new DfmValue.Collection(items);
            }
            default -> throw new IllegalStateException("tipo de valor DFM desconhecido " + type + " no offset " + (pos - 1));
        };
    }

    private String shortString() {
        int len = u8();
        pos++;
        return text(len, StandardCharsets.UTF_8);
    }

    private String text(int len, Charset cs) {
        String s = new String(b, pos, len, cs);
        pos += len;
        return s;
    }

    private int u8() {
        return b[pos] & 0xFF;
    }

    private int u8(int at) {
        return b[at] & 0xFF;
    }

    private int i32() {
        int v = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
        pos += 4;
        return v;
    }

    /** Extended de 80 bits (x87) para double. */
    static double extended(byte[] b, int at) {
        long mantissa = 0;
        for (int i = 7; i >= 0; i--) {
            mantissa = mantissa << 8 | (b[at + i] & 0xFF);
        }
        int se = (b[at + 8] & 0xFF) | (b[at + 9] & 0xFF) << 8;
        int exponent = se & 0x7FFF;
        boolean negative = (se & 0x8000) != 0;
        if (exponent == 0 && mantissa == 0) {
            return negative ? -0.0 : 0.0;
        }
        double m = (double) (mantissa >>> 11) * 2048.0 + (mantissa & 0x7FF);
        double v = Math.scalb(m, exponent - 16383 - 63);
        return negative ? -v : v;
    }

    private double extended(int at) {
        return extended(b, at);
    }
}
