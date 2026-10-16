package undelphi.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SqlAnalyzerTest {

    private static SqlAnalysis analyze(String sql) {
        return SqlAnalyzer.analyze(new SqlSource("TESTE.qry", "TOraQuery", "SQL.Strings", sql));
    }

    @Test
    void consultaResolveAliasesEColunas() {
        SqlAnalysis a = analyze("""
                SELECT C.CODCLI, C.CLIENTE, P.DESCRICAO
                  FROM PCCLIENT C, PCPLPAG P
                 WHERE C.CODPLPAG = P.CODPLPAG(+)
                   AND C.CODCLI = :CODCLI""");
        assertEquals(SqlAnalysis.Kind.CONSULTA, a.kind());
        assertEquals(Set.of("PCCLIENT", "PCPLPAG"), a.tables().keySet());
        assertEquals(Set.of("CODCLI", "CLIENTE", "CODPLPAG"), a.tables().get("PCCLIENT").columnsRead());
        assertTrue(a.tables().get("PCPLPAG").outerJoined());
        assertEquals(Set.of("CODCLI"), a.params());
        assertTrue(a.warnings().isEmpty(), a.warnings()::toString);
        assertFalse(a.writes());
    }

    @Test
    void colunaSemQualificadorComUmaTabelaSo() {
        SqlAnalysis a = analyze("SELECT CODPROD, DESCRICAO FROM PCPRODUT WHERE CODPROD = :COD");
        assertEquals(Set.of("CODPROD", "DESCRICAO"), a.tables().get("PCPRODUT").columnsRead());
        assertTrue(a.unresolvedColumns().isEmpty());
    }

    @Test
    void colunaSemQualificadorComDuasTabelasFicaPendente() {
        SqlAnalysis a = analyze("SELECT CODPROD FROM PCPRODUT A, PCEST B WHERE A.CODPROD = B.CODPROD");
        assertEquals(Set.of("CODPROD"), a.unresolvedColumns());
    }

    @Test
    void gravacaoMarcaColunasGravadas() {
        SqlAnalysis a = analyze("UPDATE PCCOTA SET PUNIT = :P * 2, OBS = 'X' WHERE ROWID = :RID");
        assertEquals(SqlAnalysis.Kind.GRAVACAO, a.kind());
        assertTrue(a.writes());
        assertEquals(Set.of("OBS", "PUNIT"), a.tables().get("PCCOTA").columnsWritten());
        assertEquals("PCCOTA.PUNIT", a.rules().get(0).name());
    }

    @Test
    void chamadasDeObjetosDoBancoESequences() {
        SqlAnalysis a = analyze("""
                BEGIN
                  pkg_parametro_fiscal.SET_DATA1(:D1);
                  INSERT INTO T (ID, V) VALUES (SEQ_T.NEXTVAL, FNC_CALCULA(:X));
                END;""");
        assertEquals(SqlAnalysis.Kind.PLSQL, a.kind());
        assertEquals(Set.of("PKG_PARAMETRO_FISCAL.SET_DATA1", "FNC_CALCULA"), a.calls());
        assertEquals(Set.of("SEQ_T"), a.sequences());
    }

    @Test
    void storedProcNameViraChamada() {
        SqlAnalysis a = SqlAnalyzer.analyze(new SqlSource("X.sp", "TOraStoredProc", "StoredProcName", "pkg.proc"));
        assertEquals(SqlAnalysis.Kind.CHAMADA, a.kind());
        assertEquals(Set.of("PKG.PROC"), a.calls());
    }

    @Test
    void createFunctionRegistraObjetoETabelasDoCorpo() {
        SqlAnalysis a = analyze("""
                CREATE OR REPLACE FUNCTION FNC_X RETURN NUMBER IS
                  V NUMBER;
                BEGIN
                  SELECT MIN(ID) INTO V FROM PCMETAPARAMFILIAL WHERE ID > 1000;
                  RETURN V;
                END;""");
        assertEquals(SqlAnalysis.Kind.DDL, a.kind());
        assertEquals(List.of("FUNCTION FNC_X"), a.created());
        assertTrue(a.tables().containsKey("PCMETAPARAMFILIAL"));
    }

    @Test
    void fragmentoDeCodigoAindaRendeTabelas() {
        SqlAnalysis a = analyze("SELECT X FROM PCPEDC P, PCPEDI I WHERE P.NUMPED = I.NUMPED AND ");
        assertFalse(a.fullyParsed());
        assertEquals(SqlAnalysis.Kind.FRAGMENTO, a.kind());
        assertEquals(Set.of("PCPEDC", "PCPEDI"), a.tables().keySet());
    }

    @Test
    void rotina9860EncontraRegrasEAlertas() throws Exception {
        SqlAnalysis a = SqlAnalyzer.analyze(new SqlSource("TDMDADOSXML.exeGerarPCCOTA", "TOraSQL", "SQL.Strings",
                TestFiles.read("sql/exeGerarPCCOTA.sql")));
        assertTrue(a.fullyParsed(), a.parseIssues()::toString);
        assertEquals(SqlAnalysis.Kind.PLSQL, a.kind());

        // tabelas e acesso
        assertTrue(a.tables().get("PCCOTA").written());
        assertTrue(a.tables().get("PCTABPR").read());
        assertFalse(a.tables().get("PCTABPR").written());
        assertTrue(a.tables().get("PCTABPR").columnsRead().containsAll(
                Set.of("PTABELA1", "PTABELA7", "VLST", "CODPROD", "NUMREGIAO")));
        assertTrue(a.tables().get("PCPLPAG").outerJoined());
        assertTrue(a.tables().get("PCCOTA").columnsWritten().containsAll(Set.of("PUNIT", "PTABELA", "PRAZO")));
        assertEquals(Set.of("DFSEQ_PCCOTA"), a.sequences());
        assertEquals(Set.of("CODCONC", "PRECOXMLUSANDOFATOR", "CODCLI", "CHAVENFE"), a.params());

        // as duas regras de preço
        SqlAnalysis.Rule ptabela = a.rules().stream().filter(r -> r.name().equals("PTABELA")).findFirst().orElse(null);
        assertNotNull(ptabela);
        assertTrue(ptabela.oracleText().contains("WHEN PCPLPAG.NUMPR = 7 THEN ROUND(NVL(PCTABPR.PTABELA7, 2) + NVL(PCTABPR.VLST, 2), 2)"),
                ptabela.oracleText());
        SqlAnalysis.Rule punit = a.rules().stream().filter(r -> r.name().equals("PUNIT")).findFirst().orElse(null);
        assertNotNull(punit);
        assertTrue(punit.oracleText().startsWith("CASE WHEN NVL(:PRECOXMLUSANDOFATOR, 'N') = 'S'"), punit.oracleText());

        // alertas: join trocado, NVL com 2, ROWNUM sem ordem
        assertTrue(a.warnings().stream().anyMatch(w -> w.code().equals("JOIN_SUSPEITO")
                && w.message().contains("DADOSCLIENTE.CODCLI = PCPLPAG.CODPLPAG(+)")
                && w.message().contains("DADOSCLIENTE.CODPLPAG")), a.warnings()::toString);
        assertTrue(a.warnings().stream().anyMatch(w -> w.code().equals("NVL_PADRAO") && w.message().contains("PCTABPR.PTABELA1, 2")));
        assertTrue(a.warnings().stream().anyMatch(w -> w.code().equals("ROWNUM_SEM_ORDEM")));
    }

    @Test
    void inventarioJuntaTudoNoMapaDoOracle() throws Exception {
        SqlInventory inv = SqlInventory.analyze(List.of(
                new SqlSource("TDMDADOSXML.exeGerarPCCOTA", "TOraSQL", "SQL.Strings", TestFiles.read("sql/exeGerarPCCOTA.sql")),
                new SqlSource("TDMLOGIN.exeCriarAplciarData", "TOraSQL", "SQL.Strings", """
                        CREATE OR REPLACE FUNCTION APLICARDATA(pdDATAINICIAL IN DATE, pdDATAFINAL IN DATE)
                           RETURN VARCHAR2
                        IS
                        BEGIN
                          pkg_parametro_fiscal.SET_DATA1(pdDATAINICIAL);
                          pkg_parametro_fiscal.SET_DATA2(pdDATAFINAL);
                          RETURN 'S';
                        END;""")));
        assertEquals(Set.of("TDMDADOSXML.exeGerarPCCOTA"), inv.tables().get("PCCOTA").writtenBy());
        assertTrue(inv.calls().containsKey("PKG_PARAMETRO_FISCAL.SET_DATA1"));
        assertEquals("TDMLOGIN.exeCriarAplciarData", inv.created().get("FUNCTION APLICARDATA"));
        String md = inv.toMarkdown();
        assertTrue(md.contains("| PCCOTA | leitura e gravação |"), md);
        assertTrue(md.contains("**PUNIT**"));
        assertTrue(md.contains("Join compara chaves"));
    }
}
