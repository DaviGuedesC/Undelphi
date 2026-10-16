package undelphi.engine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ghidra.GhidraApplicationLayout;
import ghidra.app.util.importer.ProgramLoader;
import ghidra.app.util.opinion.LoadResults;
import ghidra.base.project.GhidraProject;
import ghidra.framework.Application;
import ghidra.framework.HeadlessGhidraApplicationConfiguration;
import ghidra.program.model.listing.Program;
import ghidra.util.task.TaskMonitor;

/**
 * Ghidra embutido em modo headless, na mesma JVM da interface. Cada instância usa um projeto
 * temporário, apagado no close().
 */
public final class GhidraEngine implements AutoCloseable {

    private final GhidraProject project;

    public GhidraEngine() throws IOException {
        initApplication();
        Path dir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "undelphi"));
        project = GhidraProject.createProject(dir.toString(), "undelphi_" + System.nanoTime(), true);
    }

    /** Diretório de instalação: -Dundelphi.ghidra=... (o undelphi.cmd passa) ou GHIDRA_INSTALL_DIR. */
    public static File installDir() {
        String dir = System.getProperty("undelphi.ghidra", System.getenv("GHIDRA_INSTALL_DIR"));
        if (dir == null || !new File(dir, "Ghidra/application.properties").exists()) {
            throw new IllegalStateException("Ghidra não encontrado (defina -Dundelphi.ghidra ou GHIDRA_INSTALL_DIR): " + dir);
        }
        return new File(dir);
    }

    /** Inicializa o Ghidra uma vez por processo (a parte lenta: ~3 s). A splash chama isso antes da janela abrir. */
    public static synchronized void initApplication() throws IOException {
        if (!Application.isInitialized()) {
            Application.initializeApplication(
                    new GhidraApplicationLayout(installDir()), new HeadlessGhidraApplicationConfiguration());
        }
    }

    /** Importa o exe (loader PE do Ghidra). Com analyze=true roda a autoanálise completa, que é lenta em exes grandes. */
    public ProgramSummary load(Path exe, boolean analyze) throws Exception {
        long t0 = System.currentTimeMillis();
        try (LoadResults<Program> results = ProgramLoader.builder()
                .source(exe.toFile())
                .project(project.getProject())
                .monitor(TaskMonitor.DUMMY)
                .load()) {
            Program program = results.getPrimaryDomainObject(this);
            long importMs = System.currentTimeMillis() - t0;
            long analysisMs = 0;
            try {
                if (analyze) {
                    long t1 = System.currentTimeMillis();
                    GhidraProject.analyze(program);
                    analysisMs = System.currentTimeMillis() - t1;
                }
                return ProgramSummary.of(program, importMs, analysisMs);
            }
            finally {
                program.release(this);
            }
        }
    }

    /** Importa o exe e devolve o Program aberto (quem chama libera com program.release(consumer)). */
    public Program importProgram(Path exe, Object consumer) throws Exception {
        try (LoadResults<Program> results = ProgramLoader.builder()
                .source(exe.toFile())
                .project(project.getProject())
                .monitor(TaskMonitor.DUMMY)
                .load()) {
            return results.getPrimaryDomainObject(consumer);
        }
    }

    @Override
    public void close() {
        project.close();
    }
}
