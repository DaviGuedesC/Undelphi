package undelphi.gogen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import undelphi.lift.HandlerGoGenerator;
import undelphi.sql.SqlInventory;
import undelphi.sql.SqlSource;
import undelphi.sql.TestFiles;
import undelphi.target.TargetProfile;

/** F4: a camada HTTP gerada (tela.go) cumpre o contrato da tela Vue: Valor, eventos, dados, 501 em lacuna, 400. */
class TelaHttpTest {

    @Test
    void camadaHttpDosHandlersPassaNosTestesGo(@TempDir Path dir) throws Exception {
        GoTool.require();
        GoGenerator.Result go = GoGenerator.generate(SqlInventory.analyze(List.of(
                new SqlSource("TDMX.qry", "TOraQuery", "SQL.Strings", "SELECT CODPROD FROM PCPRODUT WHERE CODPROD = :COD"))),
                TargetProfile.empty(), dir, "teste/gerado", "x.exe");
        HandlerGoGenerator.generate(List.of(), List.of(), List.of(), Map.of(), go, TargetProfile.empty(), "teste/gerado");
        Files.writeString(dir.resolve("x/tela_test.go"),
                TestFiles.read("lift/tela_test.go.txt").replace("{{PKG}}", "x").replace("{{MODULE}}", "teste/gerado"));
        GoTool.Run run = GoTool.run(dir, "test", "./x/");
        assertEquals(0, run.exit(), run::output);
    }
}
