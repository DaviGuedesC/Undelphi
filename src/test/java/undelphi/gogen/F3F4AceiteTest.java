package undelphi.gogen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import undelphi.model.DelphiProject;
import undelphi.rtti.AppScope;
import undelphi.rtti.DelphiClass;
import undelphi.rtti.EventBinder;

/**
 * Aceite da F3 (estrutura Delphi + decompilação) e da F4 (handlers Go) com o exe real da 9860.
 * Pulado se o exe não existir (-Dundelphi.amostra9860=...). Importa no Ghidra: leva cerca de 1 minuto.
 */
class F3F4AceiteTest {

    static DelphiProject project;
    static AppScope.Structure structure;

    @BeforeAll
    static void abrir() throws Exception {
        Path exe = Path.of(System.getProperty("undelphi.amostra9860", "C:/Users/S00025/Documents/9860/PCNOV9860.exe"));
        assumeTrue(Files.isRegularFile(exe), "exe da 9860 não disponível nesta máquina");
        project = DelphiProject.open(exe);
        structure = AppScope.read(project.pe(), project.appForms());
    }

    @Test
    void f3RecuperaClassesEventosGlobaisELiterais() {
        DelphiClass principal = structure.classes().byName("TfrmPrincipal");
        assertNotNull(principal);
        assertEquals(0x028D2B1CL, principal.findMethod("btnGerarClick").address());
        assertEquals("TcxTextEdit", principal.allFields().stream().filter(f -> f.name().equals("edtChaveBase"))
                .findFirst().orElseThrow().typeName());
        // acessores de propriedades viram métodos nomeados
        assertTrue(structure.classes().methodNames().containsValue("TcxCustomDateEdit.get_Date"));

        long resolved = structure.bindings().stream().filter(EventBinder.Binding::resolved).count();
        assertTrue(structure.bindings().size() >= 170 && resolved >= structure.bindings().size() - 3,
                () -> resolved + " de " + structure.bindings().size());
        assertTrue(structure.globals().containsKey("dmDadosXML"));
        assertTrue(structure.globals().containsKey("frmPrincipal"));
        assertTrue(structure.globalCells().values().stream().anyMatch(g -> g.name().equals("dmDados9801")));
        assertTrue(structure.sqlLiterals().stream()
                .anyMatch(s -> s.sql().contains("NFE_CABECALHO.DTCADASTRO BETWEEN :DTINICIAL AND :DTFINAL")));
    }

    @Test
    void f3EF4GeramCDecompiladoEHandlersGoQueCompilam(@TempDir Path out) throws Exception {
        Path perfil = out.resolve("perfil.yaml");
        Files.writeString(perfil, """
                nome: teste-aceite
                conexao:
                  senha: ${PGPASSWORD}
                tabelas:
                  NFE_CABECALHO: nfe_cabecalho
                """);
        CliRunner.Run run = CliRunner.run("exportar", project.exe().toString(), out.toString(), "--perfil", perfil.toString(),
                "--modulo", "teste/gerado");
        assertEquals(0, run.exit(), run::output);
        String handlersLine = run.output().lines().filter(l -> l.startsWith("F4:")).findFirst().orElse("");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("F4: (\\d+) handlers Go \\((\\d+) convertidos").matcher(handlersLine);
        assertTrue(m.find(), run::output);
        assertTrue(Integer.parseInt(m.group(1)) > 50, handlersLine);
        assertTrue(Integer.parseInt(m.group(2)) >= 6, handlersLine);

        String c = Files.readString(out.resolve("decompilado/TfrmPrincipal.btnConsultaPesquisarClick.c"), StandardCharsets.UTF_8);
        assertTrue(c.contains("void TfrmPrincipal::btnConsultaPesquisarClick(TfrmPrincipal *this,TObject *Sender)"), c);
        assertTrue(c.contains("TOraDataSet::ParamByName((*ref_dmDados9801)->qryCabecalho,L\"DTINICIAL\")"), c);
        assertTrue(c.contains("TcxCustomDateEdit::get_Date(this->dtaPeriodoInicial)"), c);

        String go = Files.readString(out.resolve("go/pcnov9860/eventos_frmprincipal.go"), StandardCharsets.UTF_8);
        assertTrue(go.contains("func (s *FrmPrincipalServico) BtnConsultaPesquisarClick(ctx context.Context, req *Requisicao) (*Resposta, error)"));
        // filtro dinâmico traduzido e ligado aos campos de data da tela
        assertTrue(go.contains("dtcadastro BETWEEN $1 AND $2"), go);
        assertTrue(go.contains("req.Valor(\"dtaPeriodoInicial\"), req.Valor(\"dtaPeriodoFinal\")"), go);
        assertTrue(go.contains("resp.Dados[\"dmDados9801.qryCabecalho\"] = linhasJSON(cols, rows)"));
        assertTrue(go.contains("registrarEvento(mux, base+\"/eventos/btnConsultaPesquisarClick\", s.BtnConsultaPesquisarClick)"));
        assertTrue(Files.readString(out.resolve("estrutura.md"), StandardCharsets.UTF_8).contains("| dmDadosXML | TdmDadosXML |"));
        assertTrue(Files.readString(out.resolve("go/relatorios/handlers.md"), StandardCharsets.UTF_8).contains("| btnConsultaPesquisarClick |"));

        GoTool.Run vet = GoTool.run(out.resolve("go"), "vet", "./...");
        assertEquals(0, vet.exit(), vet::output);
    }
}
