package undelphi.cli;

import java.nio.file.Files;
import java.nio.file.Path;

import undelphi.delphi.DelphiDetector;
import undelphi.delphi.ExeInfo;
import undelphi.engine.GhidraEngine;
import undelphi.gogen.GoGenerator;
import undelphi.model.DelphiProject;
import undelphi.model.ProjectExporter;
import undelphi.target.TargetProfile;

/** Modo linha de comando, para processar aplicações em lote. */
public final class Cli {

    private static final String USAGE = """
            uso:
              undelphi                          abre a interface
              undelphi gui [exe]                abre a interface (opcionalmente já com um exe)
              undelphi info <exe>               triagem rápida: versão do Delphi, forms, bibliotecas
              undelphi ghidra <exe> [--analyze] carrega no Ghidra embutido e mostra o resumo
              undelphi forms <exe> <pasta>      grava cada DFM do exe em texto (pasta/forms/*.dfm)
              undelphi sql <exe> <pasta>        grava a SQL dos componentes (pasta/sql/)
              undelphi vue <exe> <pasta>        gera as telas Vue dos forms da aplicação (pasta/vue/)
              undelphi oracle <exe> <pasta>     mapa do Oracle e lacunas para o destino (pasta/oracle/)
              undelphi go <exe> <pasta>         mapa do Oracle + código Go das SQLs e regras (pasta/go/)
              undelphi exportar <exe> <pasta>   tudo acima + F3 (RTTI, eventos, C decompilado) e F4 (handlers Go)

            opções de oracle, go e exportar:
              --perfil <arquivo.yaml>           perfil de destino (de-para WinThor → destino); sem ele tudo vira lacuna
              --modulo <caminho>                módulo Go do código gerado (padrão: undelphi/gerado)
              --sem-ghidra                      (exportar) pula F3/F4, que importam o exe no Ghidra (~1 min)
            """;

    public static int run(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.print(USAGE);
            return 2;
        }
        Path exe = Path.of(args[1]);
        if (!Files.isRegularFile(exe)) {
            System.err.println("arquivo não encontrado: " + exe);
            return 1;
        }
        switch (args[0]) {
            case "info", "ghidra" -> {
                ExeInfo info = DelphiDetector.detect(exe);
                System.out.print(info.describe());
                if (args[0].equals("ghidra")) {
                    boolean analyze = args.length > 2 && args[2].equals("--analyze");
                    try (GhidraEngine engine = new GhidraEngine()) {
                        System.out.print(engine.load(exe, analyze).describe());
                    }
                }
                return info.isDelphi() ? 0 : 3;
            }
            case "forms", "sql", "vue", "oracle", "go", "exportar" -> {
                if (args.length < 3) {
                    System.err.print(USAGE);
                    return 2;
                }
                Path out = Files.createDirectories(Path.of(args[2]));
                TargetProfile profile = TargetProfile.empty();
                String module = ProjectExporter.DEFAULT_GO_MODULE;
                boolean ghidra = true;
                for (int i = 3; i < args.length; i++) {
                    switch (args[i]) {
                        case "--sem-ghidra" -> ghidra = false;
                        case "--perfil" -> {
                            if (++i >= args.length) {
                                System.err.println("--perfil precisa do caminho do arquivo YAML");
                                return 2;
                            }
                            profile = TargetProfile.load(Path.of(args[i]));
                        }
                        case "--modulo" -> {
                            if (++i >= args.length) {
                                System.err.println("--modulo precisa do caminho do módulo Go");
                                return 2;
                            }
                            module = args[i];
                        }
                        default -> {
                            System.err.println("opção desconhecida: " + args[i]);
                            System.err.print(USAGE);
                            return 2;
                        }
                    }
                }
                DelphiProject project = DelphiProject.open(exe);
                switch (args[0]) {
                    case "forms" -> {
                        ProjectExporter.exportForms(project, out);
                        System.out.printf("%d forms gravados em %s (%d da aplicação), %d falhas%n", project.forms().size(),
                                out.resolve("forms"), project.appForms().size(), project.failures().size());
                    }
                    case "sql" -> System.out.printf("%d textos de SQL gravados em %s%n",
                            ProjectExporter.exportSql(project, out).size(), out.resolve("sql"));
                    case "vue" -> System.out.printf("%d telas Vue gravadas em %s%n",
                            ProjectExporter.exportVue(project, out).size(), out.resolve("vue"));
                    case "oracle", "go" -> {
                        GoGenerator.Result go = ProjectExporter.exportOracleAndGo(project, out, profile, module);
                        System.out.printf("Mapa do Oracle e lacunas em %s (perfil %s)%n", out.resolve("oracle"), profile.name());
                        System.out.printf("Go em %s: pacote %s, %d funções (%d sem lacuna), %d regras calculadas%n",
                                go.outDir(), go.packageName(), go.functions(), go.ready(), go.rules());
                    }
                    default -> {
                        ProjectExporter.Summary s = ProjectExporter.exportAll(project, out, profile, module, ghidra);
                        System.out.printf("%d forms (%d da aplicação), %d textos de SQL, %d telas Vue em %s%n", s.forms(),
                                s.appForms(), s.sql(), s.vue(), out);
                        System.out.printf("Go: %d funções (%d sem lacuna), %d regras calculadas; perfil %s%n",
                                s.goFunctions(), s.goReady(), s.rules(), profile.name());
                        if (ghidra) {
                            System.out.printf("F3: %d eventos, %d ligados ao método; C em %s%n", s.events(),
                                    s.eventsResolved(), out.resolve("decompilado"));
                            System.out.printf("F4: %d handlers Go (%d convertidos, %d parciais, %d só interface ou manuais); ver %s%n",
                                    s.handlers(), s.handlersConverted(), s.handlersPartial(),
                                    s.handlers() - s.handlersConverted() - s.handlersPartial(),
                                    out.resolve("go").resolve("relatorios").resolve("handlers.md"));
                        }
                        s.warnings().forEach(w -> System.out.println("aviso: " + w));
                    }
                }
                project.failures().forEach(f -> System.err.println("falhou " + f.resourceName() + ": " + f.error()));
                return project.failures().isEmpty() ? 0 : 4;
            }
            default -> {
                System.err.print(USAGE);
                return 2;
            }
        }
    }

    private Cli() {
    }
}
