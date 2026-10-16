package undelphi.gogen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import undelphi.sql.SqlInventory;
import undelphi.sql.SqlSource;
import undelphi.sql.TestFiles;
import undelphi.target.TargetProfile;

/**
 * F5: a regra PUNIT que o undelphi extrai da SQL do PCNOV9860.exe e converte em Go é executada sobre
 * os 423 itens das 10 notas da planilha comparacao_punit_9860_10_notas_2026-09-29.xlsx.
 *
 * <ol>
 * <li>Tradução: o Go gerado tem que bater, item a item, com a fórmula escrita à mão em Java a partir
 * da SQL (prova que SQL → Go não muda a conta).</li>
 * <li>Paridade com o WinThor: o resultado arredondado a 4 casas é comparado com o PUNIT gravado pelo ERP.
 * A taxa medida vira piso de regressão.</li>
 * </ol>
 */
class ValidacaoPunit9860Test {

    private static final MathContext MC = new MathContext(40);

    record Item(int id, Map<String, BigDecimal> v) {
        BigDecimal get(String k) {
            return v.get(k);
        }
    }

    @Test
    void punitGeradoEmGoBateComAFormulaEComOErp(@TempDir Path dir) throws Exception {
        GoTool.require();
        SqlInventory inv = SqlInventory.analyze(List.of(new SqlSource("TDMDADOSXML.exeGerarPCCOTA", "TOraSQL",
                "SQL.Strings", TestFiles.read("sql/exeGerarPCCOTA.sql"))));
        GoGenerator.generate(inv, TargetProfile.empty(), dir, "teste/gerado", "PCNOV9860.exe");
        Files.writeString(dir.resolve("punit_9860_10_notas.csv"), TestFiles.read("validacao/punit_9860_10_notas.csv"));
        Files.writeString(dir.resolve("pcnov9860/validacao_test.go"), TestFiles.read("validacao/validacao_test.go.txt"));

        GoTool.Run run = GoTool.run(dir, "test", "-run", "TestValidacaoPunit9860", "./pcnov9860/");
        assertEquals(0, run.exit(), run::output);

        List<Item> items = items(TestFiles.read("validacao/punit_9860_10_notas.csv"));
        Map<String, String> goResult = new HashMap<>();
        for (String line : Files.readAllLines(dir.resolve("resultado_go.txt"), StandardCharsets.UTF_8)) {
            String[] p = line.split(";", 3);
            goResult.put(p[0] + "/" + p[1], p[2]);
        }
        assertEquals(items.size() * 2, goResult.size());

        StringBuilder report = new StringBuilder("# F5 — PUNIT do PCNOV9860 gerado em Go × WinThor\n\n");
        report.append(items.size()).append(" itens de 10 notas (planilha de 29/09/2026). PRECOXMLUSANDOFATOR = 'S', ")
                .append("fator = fator gravado em concorrente_dados.\n\n");
        report.append("| Variante de vUnCom | Igual à fórmula | Exata (≤ 0,0001) | Até 1 centavo | Divergente | Sem valor no ERP |\n")
                .append("|---|---|---|---|---|---|\n");
        Map<String, Double> exactRate = new HashMap<>();
        for (String variante : List.of("vuncom", "vuncom_rateado")) {
            int same = 0;
            int exact = 0;
            int cent = 0;
            int diverge = 0;
            int noErp = 0;
            List<String> mismatches = new ArrayList<>();
            for (Item it : items) {
                String go = goResult.get(it.id() + "/" + variante);
                BigDecimal ref = reference(it, variante);
                if (ref == null) {
                    assertTrue(go.isEmpty() || go.startsWith("ERRO"), "item " + it.id() + ": esperava NULL/erro, veio " + go);
                    same++;
                } else {
                    BigDecimal goVal = new BigDecimal(go);
                    if (goVal.subtract(ref).abs().compareTo(new BigDecimal("1e-20")) <= 0) {
                        same++;
                    } else {
                        mismatches.add(it.id() + ": go=" + go + " ref=" + ref.toPlainString());
                    }
                }
                BigDecimal erp = it.get("punit_erp");
                if (erp == null || ref == null) {
                    noErp++;
                    continue;
                }
                BigDecimal diff = ref.setScale(4, RoundingMode.HALF_UP).subtract(erp).abs();
                if (diff.compareTo(new BigDecimal("0.0001")) <= 0) {
                    exact++;
                } else if (diff.compareTo(new BigDecimal("0.01")) <= 0) {
                    cent++;
                } else {
                    diverge++;
                }
            }
            assertTrue(mismatches.isEmpty(), () -> "Go gerado diverge da fórmula da SQL: " + mismatches);
            int compared = exact + cent + diverge;
            exactRate.put(variante, compared == 0 ? 0 : 100.0 * exact / compared);
            report.append(String.format("| %s | %d/%d | %d (%.1f%%) | %d | %d | %d |%n", variante, same, items.size(), exact,
                    exactRate.get(variante), cent, diverge, noErp));
        }
        Path reportFile = Path.of("build", "reports", "f5_punit_9860.md");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // piso de regressão medido na primeira execução (16/10/2026); só pode subir
        assertTrue(exactRate.get("vuncom") >= PISO_VUNCOM, () -> "paridade caiu: " + exactRate);
        assertTrue(exactRate.get("vuncom_rateado") >= PISO_RATEADO, () -> "paridade caiu: " + exactRate);
    }

    /** Sem rateio de despesas: 213/423 exatos na medição de 16/10/2026. */
    static final double PISO_VUNCOM = 50.0;
    /** Com vUnCom rateado (como o WinThor grava em NFE_ITENS): 423/423 exatos em 16/10/2026. */
    static final double PISO_RATEADO = 100.0;

    /**
     * Fórmula escrita à mão a partir da SQL (exeGerarPCCOTA, PRECOXMLUSANDOFATOR = 'S'):
     * vIPI/qCom + vUnCom/f + (vICMSST/f)/qCom − vDesc/f, com f = DECODE(NVL(FATOR,0),0,1,FATOR).
     */
    static BigDecimal reference(Item it, String variante) {
        BigDecimal q = it.get("qcom");
        BigDecimal vun = it.get(variante);
        BigDecimal ipi = it.get("vipi");
        BigDecimal st = it.get("vicmsst");
        BigDecimal desc = it.get("vdesc");
        BigDecimal fator = it.get("fator_erp");
        if (q == null || vun == null || ipi == null || st == null || desc == null || q.signum() == 0) {
            return null;
        }
        BigDecimal f = fator == null || fator.signum() == 0 ? BigDecimal.ONE : fator;
        return ipi.divide(q, MC).add(vun.divide(f, MC)).add(st.divide(f, MC).divide(q, MC)).subtract(desc.divide(f, MC));
    }

    static List<Item> items(String csv) {
        List<Item> out = new ArrayList<>();
        String[] lines = csv.strip().split("\r?\n");
        String[] head = lines[0].split(",");
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",", -1);
            Map<String, BigDecimal> v = new HashMap<>();
            for (int k = 1; k < head.length; k++) {
                String c = k < cells.length ? cells[k].strip() : "";
                v.put(head[k], c.isEmpty() ? null : new BigDecimal(c));
            }
            out.add(new Item(Integer.parseInt(cells[0]), v));
        }
        return out;
    }
}
