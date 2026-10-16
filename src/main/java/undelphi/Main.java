package undelphi;

import java.nio.file.Path;

import undelphi.cli.Cli;
import undelphi.ui.MainWindow;

/** Sem argumentos (ou "gui [exe]") abre a interface; qualquer outro comando roda no modo linha de comando. */
public final class Main {

    public static void main(String[] args) throws Exception {
        undelphi.engine.ClassPathExpander.expand();
        if (args.length == 0 || args[0].equals("gui")) {
            MainWindow.launch(args.length > 1 ? Path.of(args[1]) : null);
            return;
        }
        System.exit(Cli.run(args));
    }

    private Main() {
    }
}
