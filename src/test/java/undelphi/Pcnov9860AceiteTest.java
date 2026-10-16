package undelphi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import undelphi.dfm.DfmSql;
import undelphi.model.DelphiProject;
import undelphi.vue.VueGenerator;

/**
 * Aceite contra o exe real da rotina 9860 (fica fora do repositório). Pulado se o exe não existir;
 * caminho em -Dundelphi.amostra9860=... (padrão: C:\Users\S00025\Documents\9860\PCNOV9860.exe).
 */
class Pcnov9860AceiteTest {

    static DelphiProject project;

    @BeforeAll
    static void abrir() throws Exception {
        Path exe = Path.of(System.getProperty("undelphi.amostra9860", "C:/Users/S00025/Documents/9860/PCNOV9860.exe"));
        assumeTrue(Files.isRegularFile(exe), "exe da 9860 não disponível nesta máquina");
        project = DelphiProject.open(exe);
    }

    @Test
    void identificaDelphiSeattleELeTodosOsForms() {
        assertEquals("Delphi 10 Seattle", project.info().product());
        assertEquals(193, project.forms().size());
        assertTrue(project.failures().isEmpty(), () -> "DFMs com falha: " + project.failures());
        assertTrue(project.formByResource("TFRMPRINCIPAL") != null);
    }

    @Test
    void encontraARegraDoPtabelaNaSqlDoGerarPccota() {
        List<DfmSql> sql = DfmSql.extract(project.forms());
        DfmSql gerar = sql.stream().filter(s -> s.origin().equals("TDMDADOSXML.exeGerarPCCOTA")).findFirst().orElseThrow();

        assertEquals("TOraSQL", gerar.componentClass());
        String s = gerar.sql().replaceAll("\\s+", " ");
        for (int n = 1; n <= 5; n++) {
            assertTrue(s.contains("WHEN PCPLPAG.NUMPR = " + n + " THEN ROUND((NVL(PCTABPR.PTABELA" + n + ",2) + NVL(PCTABPR.VLST,2)),2)"),
                    "regra da tabela " + n);
        }
    }

    @Test
    void geraATelaPrincipalComOAssistente() {
        List<DfmSql> sql = DfmSql.extract(project.forms());
        VueGenerator.Result r = VueGenerator.generate(project, project.formByResource("TFRMPRINCIPAL"), sql);

        assertTrue(r.code().contains("<InovaTabs v-model=\"abas.wzdPassoAPasso\""));
        assertTrue(r.code().contains("evento('btnGerarClick')"));
        assertTrue(r.code().contains(":data=\"dados['dmDadosXML.cdsProdutos']\""));
        assertTrue(r.model().warnings.isEmpty(), () -> "avisos: " + r.model().warnings);
    }
}
