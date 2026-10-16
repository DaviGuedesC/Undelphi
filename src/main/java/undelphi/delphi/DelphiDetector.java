package undelphi.delphi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Triagem de executável Delphi direto nos bytes: arquitetura, versão do compilador, forms e bibliotecas. */
public final class DelphiDetector {

    private static final Pattern COMPILER = Pattern.compile(
            "Embarcadero Delphi for (Win32|Win64) compiler version (\\d+\\.\\d+) \\(([^)]{1,40})\\)");

    /** Versão do compilador → nome comercial. */
    private static final Map<String, String> PRODUCTS = Map.ofEntries(
            Map.entry("20.0", "Delphi 2009"),
            Map.entry("21.0", "Delphi 2010"),
            Map.entry("22.0", "Delphi XE"),
            Map.entry("23.0", "Delphi XE2"),
            Map.entry("24.0", "Delphi XE3"),
            Map.entry("25.0", "Delphi XE4"),
            Map.entry("26.0", "Delphi XE5"),
            Map.entry("27.0", "Delphi XE6"),
            Map.entry("28.0", "Delphi XE7"),
            Map.entry("29.0", "Delphi XE8"),
            Map.entry("30.0", "Delphi 10 Seattle"),
            Map.entry("31.0", "Delphi 10.1 Berlin"),
            Map.entry("32.0", "Delphi 10.2 Tokyo"),
            Map.entry("33.0", "Delphi 10.3 Rio"),
            Map.entry("34.0", "Delphi 10.4 Sydney"),
            Map.entry("35.0", "Delphi 11 Alexandria"),
            Map.entry("36.0", "Delphi 12 Athens"),
            Map.entry("37.0", "Delphi 13 Florence"));

    /** Família de componentes → nomes de classe que a denunciam. */
    private static final Map<String, List<String>> LIBRARIES = new LinkedHashMap<>();
    static {
        LIBRARIES.put("ODAC (Devart/Oracle)", List.of("TOraSession", "TOraQuery", "TOraSQL", "TOraStoredProc"));
        LIBRARIES.put("FireDAC", List.of("TFDConnection", "TFDQuery", "TFDStoredProc"));
        LIBRARIES.put("dbExpress", List.of("TSQLConnection", "TSQLQuery"));
        LIBRARIES.put("ADO", List.of("TADOConnection", "TADOQuery"));
        LIBRARIES.put("ClientDataSet/Midas", List.of("TClientDataSet"));
        LIBRARIES.put("DevExpress", List.of("TcxGrid", "TdxBar", "TcxButton", "TcxTextEdit"));
        LIBRARIES.put("ReportBuilder", List.of("TppReport"));
        LIBRARIES.put("FastReport", List.of("TfrxReport"));
        LIBRARIES.put("QuickReport", List.of("TQuickRep"));
    }

    public static ExeInfo detect(Path exe) throws IOException {
        return detect(exe, Files.readAllBytes(exe));
    }

    public static ExeInfo detect(Path exe, byte[] bytes) {
        // ISO-8859-1 mapeia byte a byte, então índices no texto são offsets no arquivo
        String text = new String(bytes, StandardCharsets.ISO_8859_1);

        String version = null;
        String build = null;
        String product = null;
        Matcher m = COMPILER.matcher(text);
        if (m.find()) {
            version = m.group(2);
            build = m.group(3);
            product = PRODUCTS.getOrDefault(version, "Delphi (versão " + version + " desconhecida)");
        }

        Map<String, Integer> libs = new LinkedHashMap<>();
        LIBRARIES.forEach((name, markers) -> {
            int refs = markers.stream().mapToInt(marker -> count(text, marker)).sum();
            if (refs > 0) {
                libs.put(name, refs);
            }
        });

        return new ExeInfo(exe, bytes.length, machine(bytes), version, build, product, count(text, "TPF0"), libs);
    }

    static String machine(byte[] b) {
        if (b.length < 0x40 || b[0] != 'M' || b[1] != 'Z') {
            return "não é PE";
        }
        int pe = le32(b, 0x3C);
        if (pe < 0 || pe + 6 > b.length || b[pe] != 'P' || b[pe + 1] != 'E' || b[pe + 2] != 0 || b[pe + 3] != 0) {
            return "não é PE";
        }
        int machine = le16(b, pe + 4);
        return switch (machine) {
            case 0x14C -> "Win32 (x86)";
            case 0x8664 -> "Win64 (x64)";
            default -> String.format("desconhecida (0x%X)", machine);
        };
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] b, int off) {
        return le16(b, off) | le16(b, off + 2) << 16;
    }

    private DelphiDetector() {
    }
}
