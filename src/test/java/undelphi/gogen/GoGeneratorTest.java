package undelphi.gogen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import undelphi.sql.SqlInventory;
import undelphi.sql.SqlSource;
import undelphi.sql.TestFiles;
import undelphi.target.TargetProfile;

class GoGeneratorTest {

    /** Perfil que mapeia tudo e permite escrita: exercita o caminho "pronto" do gerador. */
    static final String PERFIL_TUDO = """
            nome: teste-tudo
            conexao:
              usuario: ${PGUSER}
              senha: ${PGPASSWORD}
            escrita: permitida
            tabelas:
              PCCLIENT: cliente_dados
              PCPRACA: praca_dados
              PCREGIAO: regiao_dados
              PCFILIAL: filial_dados
              PCPLPAG: plano_dados
              PCTABPRCLI: tabprcli_dados
              PCTABPR: preco_dados
              NFE_ITENS: nfe_itens
              NFE_CABECALHO: nfe_cabecalho
              PCCOTA: cotacao
            sequencias:
              DFSEQ_PCCOTA: seq_cotacao
            """;

    /** SQL real do 9860 (amostra privada), ou null fora da máquina de desenvolvimento. */
    private static String pccota() throws Exception {
        return TestFiles.optional("sql/exeGerarPCCOTA.sql");
    }

    private static List<SqlSource> amostra() throws Exception {
        List<SqlSource> out = new java.util.ArrayList<>(base());
        if (pccota() != null) {
            out.add(4, new SqlSource("TDMDADOSXML.exeGerarPCCOTA", "TOraSQL", "SQL.Strings", pccota()));
        }
        return out;
    }

    private static List<SqlSource> base() throws Exception {
        return List.of(
                new SqlSource("TDMTESTE.qryCliente", "TOraQuery", "SQL.Strings",
                        "SELECT C.CODCLI, C.CLIENTE, NVL(C.LIMCRED, 0) * 2 LIMITE FROM PCCLIENT C WHERE C.CODCLI = :CODCLI"),
                new SqlSource("TDMTESTE.qryTudo", "TOraQuery", "SQL.Strings", "SELECT * FROM PCPRACA WHERE ROWNUM = 1"),
                new SqlSource("TDMTESTE.qryTudo", "TOraQuery", "SQLUpdate.Strings",
                        "UPDATE PCPRACA SET PRACA = :PRACA WHERE CODPRACA = :OLD_CODPRACA"),
                new SqlSource("TDMTESTE.qrySemDestino", "TOraQuery", "SQL.Strings", "SELECT X FROM PCOUTRA"),
                new SqlSource("literal@0x52A1F0", "", "", " AND CODFILIAL = :FILIAL"));
    }

    @Test
    void nomesSeguemOContrato() {
        assertEquals("DmdadosxmlExeGerarPCCOTA",
                GoNames.queryFunc(new SqlSource("TDMDADOSXML.exeGerarPCCOTA", "TOraSQL", "SQL.Strings", "")));
        assertEquals("DmtesteQryTudoSQLUpdate",
                GoNames.queryFunc(new SqlSource("TDMTESTE.qryTudo", "TOraQuery", "SQLUpdate.Strings", "")));
        assertEquals("Literal52A1F0", GoNames.queryFunc(new SqlSource("literal@0x52A1F0", "", "", "")));
        assertEquals("pcnov9860", GoNames.packageName("PCNOV9860.exe"));
        assertEquals("CodCli", GoNames.field("COD_CLI"));
        assertEquals("Chavenfe", GoNames.field("CHAVENFE"));
    }

    @Test
    void codigoGeradoCompilaERuntimePassaNosTestesGo(@TempDir Path dir) throws Exception {
        GoTool.require();
        TargetProfile profile = TargetProfile.parse(PERFIL_TUDO, null);
        GoGenerator.Result r = GoGenerator.generate(SqlInventory.analyze(amostra()), profile, dir, "teste/gerado", "PCNOV9860.exe");
        assertEquals("pcnov9860", r.packageName());
        assertEquals(pccota() != null ? 6 : 5, r.functions());

        String consultas = Files.readString(dir.resolve("pcnov9860/consultas.go"), StandardCharsets.UTF_8);
        assertTrue(consultas.contains("func (r *Repo) DmtesteQryCliente(ctx context.Context, p DmtesteQryClienteParams) ([]DmtesteQryClienteRow, error)"));
        assertTrue(consultas.contains("Codcli any // :CODCLI"));
        assertTrue(consultas.contains("FROM cliente_dados C"));
        assertTrue(consultas.contains("func (r *Repo) DmtesteQryTudoSQLUpdate(ctx context.Context, p DmtesteQryTudoSQLUpdateParams) (n int64, err error)"));
        assertTrue(consultas.contains("Colunas undelphirt.Map"));
        assertTrue(consultas.contains("undelphirt.Lacuna(\"TDMTESTE.qrySemDestino\", \"tabela PCOUTRA sem destino no perfil\")"));
        if (pccota() != null) {
            // o bloco do 9860 depende de ROWID: continua lacuna mesmo com tudo mapeado
            assertTrue(consultas.contains("func (r *Repo) DmdadosxmlExeGerarPCCOTA("));
            assertTrue(consultas.contains("ROWID"));
        }

        Files.writeString(dir.resolve("undelphirt/undelphirt_test.go"), TestFiles.read("gogen/undelphirt_test.go.txt"));
        GoTool.Run build = GoTool.run(dir, "build", "./...");
        assertEquals(0, build.exit(), build::output);
        GoTool.Run vet = GoTool.run(dir, "vet", "./...");
        assertEquals(0, vet.exit(), vet::output);
        GoTool.Run test = GoTool.run(dir, "test", "./undelphirt/");
        assertEquals(0, test.exit(), test::output);
    }

    @Test
    void blocoPlsqlTraduzivelViraFluxoGoQueCompila(@TempDir Path dir) throws Exception {
        GoTool.require();
        TargetProfile profile = TargetProfile.parse(PERFIL_TUDO, null);
        SqlSource bloco = new SqlSource("TDMTESTE.exeAtualiza", "TOraSQL", "SQL.Strings", """
                DECLARE
                  V NUMBER;
                BEGIN
                  FOR R IN (SELECT C.CODCLI, C.LIMCRED FROM PCCLIENT C WHERE C.CODPRACA = :PRACA) LOOP
                    IF NVL(R.LIMCRED, 0) > 100 THEN
                      UPDATE PCCLIENT SET LIMCRED = R.LIMCRED * 0.9 WHERE CODCLI = R.CODCLI;
                    ELSE
                      V := NVL(V, 0) + 1;
                    END IF;
                  END LOOP;
                END;""");
        GoGenerator.Result r = GoGenerator.generate(SqlInventory.analyze(List.of(bloco)), profile, dir, "teste/gerado", "x.exe");
        assertEquals(1, r.ready());
        String code = Files.readString(dir.resolve("x/consultas.go"), StandardCharsets.UTF_8);
        assertTrue(code.contains("recR := asMap("), code);
        assertTrue(code.contains("recR.Get(\"LIMCRED\")"), code);
        assertTrue(code.contains("tx.ExecContext(ctx, cmd"), code);
        GoTool.Run build = GoTool.run(dir, "vet", "./...");
        assertEquals(0, build.exit(), build::output);
    }
}
