package undelphi.dfm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Monta DFM binário (TPF0) nos testes, no mesmo formato que o TWriter do Delphi grava. */
public final class DfmBuilder {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public DfmBuilder() {
        out.writeBytes("TPF0".getBytes(StandardCharsets.US_ASCII));
    }

    public DfmBuilder object(String className, String name) {
        shortString(className);
        shortString(name);
        return this;
    }

    public DfmBuilder inherited(String className, String name) {
        out.write(0xF1);
        return object(className, name);
    }

    public DfmBuilder str(String prop, String value) {
        shortString(prop);
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        boolean ascii = b.length == value.length() && b.length < 256;
        out.write(ascii ? 6 : 20);
        if (ascii) {
            out.write(b.length);
        }
        else {
            int32(b.length);
        }
        out.writeBytes(b);
        return this;
    }

    public DfmBuilder integer(String prop, int value) {
        shortString(prop);
        if (value >= -128 && value <= 127) {
            out.write(2);
            out.write(value);
        }
        else {
            out.write(4);
            int32(value);
        }
        return this;
    }

    public DfmBuilder bool(String prop, boolean value) {
        shortString(prop);
        out.write(value ? 9 : 8);
        return this;
    }

    public DfmBuilder ident(String prop, String value) {
        shortString(prop);
        out.write(7);
        shortString(value);
        return this;
    }

    public DfmBuilder set(String prop, String... items) {
        shortString(prop);
        out.write(11);
        for (String i : items) {
            shortString(i);
        }
        out.write(0);
        return this;
    }

    public DfmBuilder lines(String prop, List<String> lines) {
        shortString(prop);
        out.write(1);
        for (String l : lines) {
            byte[] b = l.getBytes(StandardCharsets.US_ASCII);
            out.write(6);
            out.write(b.length);
            out.writeBytes(b);
        }
        out.write(0);
        return this;
    }

    /** Coleção com itens de propriedades string: {{"FieldName","CODPROD","Title.Caption","Código"}, ...}. */
    public DfmBuilder collection(String prop, String[]... items) {
        shortString(prop);
        out.write(14);
        for (String[] item : items) {
            out.write(1);
            for (int i = 0; i < item.length; i += 2) {
                str(item[i], item[i + 1]);
            }
            out.write(0);
        }
        out.write(0);
        return this;
    }

    /** Fim das propriedades do objeto atual (antes dos filhos). */
    public DfmBuilder endProps() {
        out.write(0);
        return this;
    }

    /** Fim dos filhos do objeto atual. */
    public DfmBuilder end() {
        out.write(0);
        return this;
    }

    public byte[] bytes() {
        return out.toByteArray();
    }

    private void shortString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b.length);
        out.writeBytes(b);
    }

    private void int32(int v) {
        out.write(v);
        out.write(v >> 8);
        out.write(v >> 16);
        out.write(v >> 24);
    }
}
