package undelphi.model;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Separa forms da aplicação dos forms internos de bibliotecas (DevExpress, TeeChart, ReportBuilder, ACBr...),
 * pelo nome do recurso. A F3 refina isso com o nome da unit vindo do RTTI.
 */
public final class FormClassifier {

    private static final List<String> LIBRARY_PREFIXES = List.of(
            "TACBR", "TAXIS", "TBACKIMAGE", "TBASEFUNCTION", "TBASESOURCE", "TBMPOPTIONS", "TBRUSHDIALOG",
            "TCHART", "TCX", "TDUALLISTFORM", "TDX", "TEMFOPTIONS", "TFILTER", "TFMCREATECATEGORY", "TFMDX",
            "TFMFILTER", "TFMPRINTERSETUP", "TFMSEARCH", "TFMZOOM", "TFORMATEDITOR", "TFORMPREVIEW", "TFORMTEE",
            "TFRL", "TFRMADDGROUPITEMS", "TFRMCAMERADIALOG", "TLAYOUTEDITFORM", "TLOGINDIALOG", "TMARGINSEDITOR",
            "TMOUSECURSOREDIT", "TPASSWORDDIALOG", "TPENDIALOG", "TPP", "TRACALCDIALOG", "TSELECTLISTFORM",
            "TSERIES", "TSTANDARDDX", "TSTRINGSEDITOR", "TSYMBOLEDITOR", "TTEE", "TTEXTSHAPEEDITOR",
            "TVISUALSEDITOR", "TZR", "TJV");

    /** Relatórios Fortes Report do ACBr (DANFE, DACTE...): TfrmDACTeRLRetrato, TfrmNFeDAInutRL... */
    private static final Pattern ACBR_REPORT = Pattern.compile("^TFR[ML].*RL(RETRATO|RETRATOA5|PAISAGEM|SIMPLIFICADO|ETIQUETA|ISSNET|SIMPLISS)?$");

    public static boolean isLibrary(String resourceName) {
        String n = resourceName.toUpperCase(Locale.ROOT);
        return LIBRARY_PREFIXES.stream().anyMatch(n::startsWith) || ACBR_REPORT.matcher(n).matches();
    }

    private FormClassifier() {
    }
}
