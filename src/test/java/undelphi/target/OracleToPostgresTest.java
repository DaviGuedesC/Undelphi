package undelphi.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import undelphi.sql.Ast;
import undelphi.sql.SqlAnalyzer;
import undelphi.sql.SqlInventory;
import undelphi.sql.SqlParser;
import undelphi.sql.SqlPrinter;
import undelphi.sql.SqlSource;
import undelphi.sql.TestFiles;

class OracleToPostgresTest {

    private static final String PROFILE = """
            nome: teste
            conexao:
              usuario: pricing2
              senha: ${PGPASSWORD}
            escrita: bloqueada
            tabelas:
              PCCLIENT:
                destino: cliente_dados
                colunas:
                  CLIENTE: nome
              PCPLPAG: plano_dados
              PCTABPR:
                destino: preco_dados
                colunas:
                  PTABELA1: precoatacado1
                  VLST: ~
            sequencias:
              SEQ_X: seq_x
            """;

    private static TargetProfile profile() throws Exception {
        return TargetProfile.parse(PROFILE, null);
    }

    private static String pg(String oracle) throws Exception {
        Ast.Stmt s = SqlParser.parse(oracle).get(0);
        OracleToPostgres.Result r = OracleToPostgres.translate(s, profile());
        return new SqlPrinter(SqlPrinter.Params.POSITIONAL).stmt(r.stmt(), "").replaceAll("\\s+", " ").replace(" , ", ", ").strip();
    }

    @Test
    void senhaLiteralNoPerfilEhRecusada() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TargetProfile.parse("conexao:\n  senha: segredo123\n", null));
        assertTrue(e.getMessage().contains("variável de ambiente"));
    }

    @Test
    void expandeVariaveisDeAmbienteComPadrao() {
        assertEquals("db:5432", TargetProfile.expand("${H}:${P:-5432}", Map.of("H", "db")));
        assertEquals("db:6000", TargetProfile.expand("${H}:${P:-5432}", Map.of("H", "db", "P", "6000")));
    }

    @Test
    void renomeiaTabelasEColunasMantendoOsQualificadores() throws Exception {
        assertEquals("SELECT C.CODCLI, C.nome FROM cliente_dados C WHERE C.CODCLI = $1",
                pg("SELECT C.CODCLI, C.CLIENTE FROM PCCLIENT C WHERE C.CODCLI = :CODCLI").replace("codcli", "CODCLI"));
    }

    @Test
    void tabelaSemAliasGanhaONomeOriginalComoAlias() throws Exception {
        String sql = pg("SELECT PCTABPR.PTABELA1 FROM PCTABPR");
        assertEquals("SELECT PCTABPR.precoatacado1 FROM preco_dados PCTABPR", sql);
    }

    @Test
    void funcoesOracleViramPostgres() throws Exception {
        String sql = pg("SELECT NVL(A, 0), DECODE(B, 1, 'um', NULL, 'nulo', 'outro'), TRUNC(SYSDATE), "
                + "NVL2(C, 1, 2), ROUND(D, 2), TO_NUMBER(E), SYSDATE - 30 FROM PCCLIENT");
        assertTrue(sql.contains("COALESCE(a, 0)"), sql);
        assertTrue(sql.contains("CASE WHEN b = 1 THEN 'um' WHEN b IS NULL THEN 'nulo' ELSE 'outro' END"), sql);
        assertTrue(sql.contains("CURRENT_DATE"), sql);
        assertTrue(sql.contains("CASE WHEN c IS NOT NULL THEN 1 ELSE 2 END"), sql);
        assertTrue(sql.contains("ROUND(CAST(d AS NUMERIC), 2)"), sql);
        assertTrue(sql.contains("CAST(e AS NUMERIC)"), sql);
        assertTrue(sql.contains("LOCALTIMESTAMP(0) - 30 * INTERVAL '1 day'"), sql);
    }

    @Test
    void rownumViraLimitEDualSome() throws Exception {
        assertEquals("SELECT CODCLI FROM cliente_dados PCCLIENT WHERE codcli > 0 LIMIT 1",
                pg("SELECT CODCLI FROM PCCLIENT WHERE CODCLI > 0 AND ROWNUM = 1").replace("SELECT codcli", "SELECT CODCLI"));
        assertEquals("SELECT 1", pg("SELECT 1 FROM DUAL"));
        assertEquals("SELECT 1 FROM cliente_dados PCCLIENT LIMIT 9", pg("SELECT 1 FROM PCCLIENT WHERE ROWNUM < 10"));
    }

    @Test
    void joinExternoOracleViraLeftJoin() throws Exception {
        String sql = pg("""
                SELECT C.CODCLI, P.DESCRICAO
                  FROM PCCLIENT C, PCPLPAG P
                 WHERE C.CODPLPAG = P.CODPLPAG(+)
                   AND P.TIPO(+) = 'A'
                   AND C.CODCLI = :X""");
        assertEquals("SELECT C.codcli, P.descricao FROM cliente_dados C LEFT JOIN plano_dados P "
                + "ON C.codplpag = P.codplpag AND P.tipo = 'A' WHERE C.codcli = $1", sql);
    }

    @Test
    void lacunasColunaSemEquivalenteTabelaSemDestinoEEscrita() throws Exception {
        Ast.Stmt s = SqlParser.parse("UPDATE PCTABPR SET VLST = 1 WHERE CODPROD IN (SELECT CODPROD FROM PCPRODUT)").get(0);
        OracleToPostgres.Result r = OracleToPostgres.translate(s, profile());
        assertFalse(r.translatable());
        List<String> msgs = r.issues().stream().map(OracleToPostgres.Issue::message).toList();
        assertTrue(msgs.stream().anyMatch(m -> m.contains("PCTABPR.VLST marcada sem equivalente")), msgs::toString);
        assertTrue(msgs.stream().anyMatch(m -> m.contains("tabela PCPRODUT sem destino")), msgs::toString);
        assertTrue(msgs.stream().anyMatch(m -> m.contains("bloqueada pelo perfil")), msgs::toString);
    }

    @Test
    void esquemaDoDestinoApontaColunaInexistente() throws Exception {
        TargetProfile p = TargetProfile.parse(PROFILE + """
                esquema_destino:
                  preco_dados: [codprod, precoatacado1]
                """, null);
        OracleToPostgres.Result r = OracleToPostgres.translate(
                SqlParser.parse("SELECT PTABELA1, PTABELA2 FROM PCTABPR").get(0), p);
        assertTrue(r.issues().stream().anyMatch(i -> i.message().contains("preco_dados.ptabela2 não existe no destino")),
                r.issues()::toString);
        assertTrue(r.issues().stream().noneMatch(i -> i.message().contains("PTABELA1")));
    }

    @Test
    void blocoDo9860TraduzPorPartesERegistrosViramParametros() throws Exception {
        SqlInventory inv = SqlInventory.analyze(List.of(new SqlSource("TDMDADOSXML.exeGerarPCCOTA", "TOraSQL",
                "SQL.Strings", TestFiles.read("sql/exeGerarPCCOTA.sql"))));
        SourceTranslator.Translated t = SourceTranslator.translate(inv.analyses().get(0), profile());
        Ast.Block b = (Ast.Block) t.statements().get(0);
        Ast.ForLoop loop = (Ast.ForLoop) b.body().get(0);
        String cursor = new SqlPrinter(SqlPrinter.Params.POSITIONAL).query(loop.cursor(), "");
        assertTrue(cursor.contains("LEFT JOIN plano_dados PCPLPAG ON DADOSCLIENTE.CODCLI = PCPLPAG.codplpag"), cursor);
        assertTrue(cursor.contains("COALESCE(PCTABPR.precoatacado1, 2)"), cursor);
        Ast.If ifs = (Ast.If) loop.body().get(0);
        Ast.Insert ins = (Ast.Insert) ifs.branches().get(0).body().get(0);
        assertTrue(ins.values().contains(new Ast.Param("REGISTROS.PUNIT")), ins.values()::toString);
        assertFalse(t.translatable());
        GapReport gr = GapReport.build(inv, profile());
        assertTrue(gr.unmappedTables().containsAll(List.of("PCCOTA", "NFE_ITENS", "NFE_CABECALHO")));
        assertTrue(gr.toMarkdown().contains("ROWID"));
        assertTrue(SqlAnalyzer.analyze(new SqlSource("x", "", "", "SELECT 1 FROM DUAL")).tables().isEmpty());
    }
}
