package undelphi.vue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import undelphi.dfm.DfmBuilder;
import undelphi.dfm.DfmParser;
import undelphi.dfm.DfmSql;
import undelphi.dfm.FormResources;
import undelphi.model.DelphiProject;

class VueGeneratorTest {

    private static DelphiProject project() {
        byte[] form = new DfmBuilder()
                .object("TfrmPreco", "frmPreco").str("Caption", "Preços").ident("OnShow", "FormShow").endProps()
                .object("TLabel", "lblCodigo").integer("Left", 8).integer("Top", 12).integer("Width", 40)
                .str("Caption", "&Código:").ident("FocusControl", "edtCodigo").endProps().end()
                .object("TcxTextEdit", "edtCodigo").integer("Left", 60).integer("Top", 10).integer("Width", 120)
                .str("TextHint", "Código do produto").ident("OnExit", "edtCodigoExit").endProps().end()
                .object("TcxButton", "btnPesquisar").integer("Left", 200).integer("Top", 8).integer("Width", 75)
                .str("Caption", "&Pesquisar").ident("OnClick", "btnPesquisarClick").endProps().end()
                .object("TcxGrid", "grdPrecos").integer("Top", 60).integer("Width", 600).integer("Height", 300).endProps()
                .object("TcxGridDBTableView", "tblPrecos").ident("DataController.DataSource", "dmPreco.dtsPrecos").endProps()
                .object("TcxGridDBColumn", "tblPrecosCODPROD").str("DataBinding.FieldName", "CODPROD").endProps().end()
                .object("TcxGridDBColumn", "tblPrecosPTABELA1").str("DataBinding.FieldName", "PTABELA1")
                .str("Caption", "Preço tabela 1").integer("Width", 90).endProps().end()
                .end()
                .end()
                .end()
                .bytes();
        byte[] dm = new DfmBuilder()
                .object("TdmPreco", "dmPreco").endProps()
                .object("TOraQuery", "qryPrecos")
                .lines("SQL.Strings", List.of("SELECT CODPROD, PTABELA1 FROM PCTABPR WHERE CODPROD = :CODPROD")).endProps()
                .object("TFloatField", "qryPrecosCODPROD").str("FieldName", "CODPROD").str("DisplayLabel", "Produto").endProps().end()
                .object("TFloatField", "qryPrecosPTABELA1").str("FieldName", "PTABELA1").endProps().end()
                .end()
                .object("TOraDataSource", "dtsPrecos").ident("DataSet", "qryPrecos").integer("Left", 10).endProps().end()
                .end()
                .bytes();
        return DelphiProject.fromForms(Path.of("PCTESTE.exe"), List.of(
                new FormResources.Form("TFRMPRECO", DfmParser.parse(form, 0)),
                new FormResources.Form("TDMPRECO", DfmParser.parse(dm, 0))));
    }

    @Test
    void geraTelaComCamposBotoesEGrade() {
        DelphiProject p = project();
        VueGenerator.Result r = VueGenerator.generate(p, p.formByResource("TFRMPRECO"), DfmSql.extract(p.forms()));
        String code = r.code();

        assertEquals("frmPreco.vue", r.fileName());
        assertTrue(code.contains("<h2>Preços</h2>"));
        assertTrue(code.contains("<span>Código:</span>"), "rótulo ligado ao campo pelo FocusControl, sem o &");
        assertTrue(code.contains("v-model=\"form.edtCodigo\""));
        assertTrue(code.contains("@blur=\"evento('edtCodigoExit')\""), "OnExit vira blur");
        assertTrue(code.contains("@click=\"evento('btnPesquisarClick')\">Pesquisar</InovaButton>"));
        assertTrue(code.contains(":data=\"dados['dmPreco.qryPrecos']\""), "grade ligada ao dataset pelo DataSource");
        assertTrue(code.contains("{ key: 'CODPROD', label: 'Produto', align: 'right', mono: true }"), "rótulo do DisplayLabel do campo");
        assertTrue(code.contains("{ key: 'PTABELA1', label: 'Preço tabela 1', width: '90px', align: 'right', mono: true }"));
        assertTrue(code.contains("import { InovaButton, InovaDataTable } from '@/components/common'"));
        assertTrue(code.contains("evento('FormShow')"), "OnShow roda ao montar a tela");
        assertTrue(code.contains("carregar('dmPreco.qryPrecos')"), "dataset com SQL carrega ao abrir");
        assertFalse(code.contains("lblCodigo"), "label consumido pelo campo não aparece solto");

        FormModel m = r.model();
        assertEquals("/undelphi/pcteste/frmpreco", m.apiBase);
        assertEquals("TDMPRECO.qryPrecos", m.datasets.get("dmPreco.qryPrecos").sqlOrigin());
        assertTrue(m.events.stream().anyMatch(e -> e.handler().equals("btnPesquisarClick") && e.property().equals("OnClick")));
        assertTrue(m.inputs.stream().anyMatch(i -> i.name().equals("edtCodigo") && "Código:".equals(i.label())));
    }

    @Test
    void escapaTextoEmHtmlEJs() {
        assertEquals("a &lt;b&gt; &amp; &quot;c&quot;", VueGenerator.html("a <b> & \"c\""));
        assertEquals("'it\\'s\\n'", VueGenerator.js("it's\n"));
        assertEquals("Salvar & sair", VueGenerator.clean("&Salvar && sair"));
    }
}
