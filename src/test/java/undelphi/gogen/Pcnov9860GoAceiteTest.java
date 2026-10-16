package undelphi.gogen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import undelphi.model.DelphiProject;
import undelphi.model.ProjectExporter;
import undelphi.sql.SqlAnalysis;
import undelphi.sql.SqlInventory;
import undelphi.target.TargetProfile;

/**
 * Aceite ponta a ponta com o exe real: exe → DFM → SQL → mapa do Oracle → lacunas → Go que compila.
 * Pulado se o exe não existir (-Dundelphi.amostra9860=...).
 */
class Pcnov9860GoAceiteTest {

    @Test
    void exportarGeraMapaLacunasEGoQueCompila(@TempDir Path out) throws Exception {
        Path exe = Path.of(System.getProperty("undelphi.amostra9860", "C:/Users/S00025/Documents/9860/PCNOV9860.exe"));
        assumeTrue(Files.isRegularFile(exe), "exe da 9860 não disponível nesta máquina");
        DelphiProject project = DelphiProject.open(exe);

        SqlInventory inv = SqlInventory.analyze(ProjectExporter.sqlSources(project));
        SqlAnalysis gerar = inv.find("TDMDADOSXML.exeGerarPCCOTA");
        assertTrue(gerar.fullyParsed(), gerar.parseIssues()::toString);
        assertTrue(gerar.rules().stream().anyMatch(r -> r.name().equals("PUNIT")));
        assertTrue(gerar.warnings().stream().anyMatch(w -> w.code().equals("JOIN_SUSPEITO")));
        assertTrue(inv.tables().get("PCCOTA").writtenBy().contains("TDMDADOSXML.exeGerarPCCOTA"));

        ProjectExporter.Summary s = ProjectExporter.exportAll(project, out, TargetProfile.empty(), "teste/gerado");
        assertEquals(inv.analyses().size(), s.goFunctions());
        assertTrue(s.rules() > 0);

        String mapa = Files.readString(out.resolve("oracle/mapa_oracle.md"), StandardCharsets.UTF_8);
        assertTrue(mapa.contains("| PCCOTA | leitura e gravação |"), mapa);
        assertTrue(mapa.contains("DADOSCLIENTE.CODCLI = PCPLPAG.CODPLPAG(+)"));
        assertTrue(Files.readString(out.resolve("oracle/lacunas.md"), StandardCharsets.UTF_8).contains("Tabelas sem destino"));
        String consultas = Files.readString(out.resolve("go/pcnov9860/consultas.go"), StandardCharsets.UTF_8);
        assertTrue(consultas.contains("func (r *Repo) DmdadosxmlExeGerarPCCOTA(ctx context.Context, p DmdadosxmlExeGerarPCCOTAParams) (n int64, err error)"));
        assertTrue(Files.readString(out.resolve("go/pcnov9860/regras.go"), StandardCharsets.UTF_8)
                .contains("func RegraDmdadosxmlExeGerarPCCOTAPunit(in undelphirt.Row)"));

        GoTool.Run vet = GoTool.run(out.resolve("go"), "vet", "./...");
        assertEquals(0, vet.exit(), vet::output);
    }
}
