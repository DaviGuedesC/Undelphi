package undelphi.dfm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DfmParserTest {

    static byte[] sampleForm() {
        return new DfmBuilder()
                .object("TfrmTeste", "frmTeste")
                .str("Caption", "Cadastro de preço")
                .integer("Left", 10)
                .integer("ClientWidth", 1024)
                .set("BorderIcons", "biSystemMenu", "biMinimize")
                .ident("OnCreate", "FormCreate")
                .endProps()
                .object("TOraSession", "dbLogin")
                .str("Username", "winthor")
                .str("EncryptedPassword", "9AFF00")
                .endProps().end()
                .object("TOraQuery", "qryPreco")
                .ident("Session", "dbLogin")
                .lines("SQL.Strings", List.of("SELECT CODPROD, PTABELA1", "  FROM PCTABPR", " WHERE CODPROD = :CODPROD"))
                .endProps()
                .object("TFloatField", "qryPrecoCODPROD").str("FieldName", "CODPROD").str("DisplayLabel", "Código").endProps().end()
                .end()
                .object("TButton", "btnGravar")
                .integer("Left", 300)
                .integer("Top", 20)
                .integer("Width", 75)
                .str("Caption", "&Gravar")
                .bool("Default", true)
                .ident("OnClick", "btnGravarClick")
                .endProps().end()
                .object("TDBGrid", "grdPreco")
                .collection("Columns", new String[] { "FieldName", "CODPROD", "Title.Caption", "Produto" })
                .endProps().end()
                .end()
                .bytes();
    }

    @Test
    void leFormComPropriedadesFilhosEColecoes() {
        DfmObject root = DfmParser.parse(sampleForm(), 0);

        assertEquals("frmTeste", root.name());
        assertEquals("TfrmTeste", root.className());
        assertEquals("Cadastro de preço", root.text("Caption"));
        assertEquals(10, root.integer("Left", -1));
        assertEquals(1024, root.integer("ClientWidth", -1));
        assertEquals("FormCreate", root.text("OnCreate"));
        assertInstanceOf(DfmValue.SetOf.class, root.get("BorderIcons"));
        assertEquals(4, root.children().size());

        DfmObject qry = root.find("qryPreco");
        assertEquals("SELECT CODPROD, PTABELA1\n  FROM PCTABPR\n WHERE CODPROD = :CODPROD", qry.lines("SQL.Strings"));
        assertEquals("frmTeste.qryPreco", qry.path());
        assertEquals("Código", Datasets.fields(qry).get(0).displayLabel());
        assertEquals("numero", Datasets.fields(qry).get(0).kind());

        DfmObject grid = root.find("grdPreco");
        DfmValue.Collection cols = (DfmValue.Collection) grid.get("Columns");
        assertEquals("Produto", cols.items().get(0).properties().get("Title.Caption"));
    }

    @Test
    void textoDoDfmRedigeSenha() {
        String text = DfmTextWriter.write(DfmParser.parse(sampleForm(), 0));

        assertTrue(text.startsWith("object frmTeste: TfrmTeste\n"));
        assertTrue(text.contains("Caption = 'Cadastro de preço'"));
        assertTrue(text.contains("OnClick = btnGravarClick"));
        assertTrue(text.contains("BorderIcons = [biSystemMenu, biMinimize]"));
        assertTrue(text.contains("EncryptedPassword = '<redigido pelo undelphi>'"));
        assertFalse(text.contains("9AFF00"), "a senha criptografada não pode sair no texto");
    }

    @Test
    void sqlDosComponentes() {
        DfmObject root = DfmParser.parse(sampleForm(), 0);
        List<DfmSql> sql = DfmSql.extract(List.of(new FormResources.Form("TFRMTESTE", root)));

        assertEquals(1, sql.size());
        assertEquals("TFRMTESTE.qryPreco", sql.get(0).origin());
        assertEquals("TOraQuery", sql.get(0).componentClass());
        assertEquals("SQL.Strings", sql.get(0).property());
        assertTrue(sql.get(0).sql().contains("FROM PCTABPR"));
    }

    @Test
    void formHerdadoMarcaFlag() {
        byte[] b = new DfmBuilder().inherited("TfrmFilho", "frmFilho").endProps().end().bytes();
        DfmObject root = DfmParser.parse(b, 0);
        assertTrue(root.inherited());
        assertTrue(DfmTextWriter.write(root).startsWith("inherited frmFilho: TfrmFilho"));
    }

    @Test
    void extendedDe80Bits() {
        // 999.0 em extended: expoente 0x4008, mantissa 0xF9C0000000000000
        byte[] b = { 0, 0, 0, 0, 0, 0, (byte) 0xC0, (byte) 0xF9, 0x08, 0x40 };
        assertEquals(999.0, DfmParser.extended(b, 0), 1e-9);
    }
}
