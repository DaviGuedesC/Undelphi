package undelphi.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

import undelphi.dfm.DfmSql;
import undelphi.dfm.DfmTextWriter;
import undelphi.dfm.FormResources;
import undelphi.engine.GhidraEngine;
import undelphi.engine.ProgramSummary;
import undelphi.model.DelphiProject;
import undelphi.model.FormClassifier;
import undelphi.model.ProjectExporter;
import undelphi.vue.VueGenerator;

/** Janela principal: árvore do exe à esquerda, uma aba por saída à direita. */
public final class MainWindow extends JFrame {

    /** Nó da árvore que representa um form. */
    private record FormNode(FormResources.Form form) {
        @Override
        public String toString() {
            return form.root().name() + "  (" + form.resourceName() + ")";
        }
    }

    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("(nenhum exe aberto)"));
    private final JTree tree = new JTree(treeModel);
    private final JTextArea summary = monospaced();
    private final JTextArea dfmText = monospaced();
    private final JTextArea vueText = monospaced();
    private final JTextArea sqlText = monospaced();
    private final DefaultTableModel sqlModel = new DefaultTableModel(new Object[] { "Origem", "Componente", "Propriedade" }, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTabbedPane tabs = new JTabbedPane();
    private final JButton ghidraButton = new JButton("Carregar no Ghidra");
    private final JButton exportButton = new JButton("Exportar tudo...");
    private final JLabel status = new JLabel(" ");
    private GhidraEngine engine;
    private DelphiProject project;
    private List<DfmSql> sql = List.of();

    /** Mostra a splash, inicializa o Ghidra em segundo plano e abre a janela principal. */
    public static void launch(Path exe) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
        catch (Exception ignored) {
            // fica com o look and feel padrão
        }
        SplashScreen.installTaskbarIcon();
        SplashScreen[] splash = new SplashScreen[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                splash[0] = new SplashScreen();
                splash[0].setVisible(true);
            });
        }
        catch (Exception e) {
            // sem splash (ambiente sem tela): segue direto
        }
        long t0 = System.currentTimeMillis();
        String engineError = null;
        if (splash[0] != null) {
            splash[0].status("Iniciando o motor de análise (Ghidra)...", 0.15);
        }
        try {
            GhidraEngine.installDir();
            GhidraEngine.initApplication();
        }
        catch (Throwable t) {
            engineError = rootMessage(t);
        }
        if (splash[0] != null) {
            splash[0].status(engineError == null ? "Preparando a interface..." : "Ghidra indisponível: " + engineError, 0.85);
        }
        // a splash fica pelo menos 1,2 s na tela, mesmo quando o carregamento é rápido
        long rest = 1200 - (System.currentTimeMillis() - t0);
        if (rest > 0) {
            try {
                Thread.sleep(rest);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String error = engineError;
        SwingUtilities.invokeLater(() -> {
            MainWindow w = new MainWindow();
            w.setVisible(true);
            if (splash[0] != null) {
                splash[0].status("Pronto.", 1);
                splash[0].dispose();
            }
            if (error != null) {
                w.status.setText("Ghidra indisponível (" + error + "): forms, SQL e Vue funcionam; decompilação não.");
            }
            if (exe != null) {
                w.open(exe);
            }
        });
    }

    private MainWindow() {
        super("Undelphi " + SplashScreen.VERSION);
        setIconImages(SplashScreen.icons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1280, 800);
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (engine != null) {
                    engine.close();
                }
                System.exit(0);
            }
        });

        JMenuItem openItem = new JMenuItem("Abrir exe...");
        openItem.addActionListener(e -> chooseFile());
        JMenuItem exportItem = new JMenuItem("Exportar tudo...");
        exportItem.addActionListener(e -> exportAll());
        JMenu fileMenu = new JMenu("Arquivo");
        fileMenu.add(openItem);
        fileMenu.add(exportItem);
        JMenuBar menuBar = new JMenuBar();
        menuBar.add(fileMenu);
        setJMenuBar(menuBar);

        ghidraButton.setEnabled(false);
        ghidraButton.addActionListener(e -> loadInGhidra());
        exportButton.setEnabled(false);
        exportButton.addActionListener(e -> exportAll());
        JPanel summaryPanel = new JPanel(new BorderLayout());
        summaryPanel.add(new JScrollPane(summary), BorderLayout.CENTER);
        JPanel actions = new JPanel();
        actions.add(ghidraButton);
        actions.add(exportButton);
        summaryPanel.add(actions, BorderLayout.SOUTH);

        JTable sqlTable = new JTable(sqlModel);
        sqlTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sqlTable.getSelectionModel().addListSelectionListener(e -> {
            int row = sqlTable.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0 && row < sql.size()) {
                sqlText.setText(sql.get(sqlTable.convertRowIndexToModel(row)).sql());
                sqlText.setCaretPosition(0);
            }
        });
        sqlTable.setAutoCreateRowSorter(true);
        JSplitPane sqlSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(sqlTable), new JScrollPane(sqlText));
        sqlSplit.setDividerLocation(260);

        tabs.addTab("Resumo", summaryPanel);
        tabs.addTab("Forms", new JScrollPane(dfmText));
        tabs.addTab("SQL / Oracle", sqlSplit);
        tabs.addTab("Vue", new JScrollPane(vueText));
        tabs.addTab("Go", new JScrollPane(monospaced("Handlers Go (F4): use \"Exportar tudo\" para gerar a pasta go/.")));

        tree.addTreeSelectionListener(e -> {
            Object node = e.getPath().getLastPathComponent();
            if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof FormNode f) {
                showForm(f.form());
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(tree), tabs);
        split.setDividerLocation(340);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    private static JTextArea monospaced() {
        return monospaced("");
    }

    private static JTextArea monospaced(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setTabSize(4);
        return area;
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser(project == null ? null : project.exe().getParent().toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Executáveis (*.exe, *.dll, *.bpl)", "exe", "dll", "bpl"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            open(chooser.getSelectedFile().toPath());
        }
    }

    private void open(Path exe) {
        ghidraButton.setEnabled(false);
        exportButton.setEnabled(false);
        status.setText("Lendo " + exe.getFileName() + "...");
        new SwingWorker<DelphiProject, Void>() {
            @Override
            protected DelphiProject doInBackground() throws Exception {
                return DelphiProject.open(exe);
            }

            @Override
            protected void done() {
                try {
                    project = get();
                    sql = DfmSql.extract(project.forms());
                    setTitle("undelphi — " + exe.getFileName());
                    summary.setText(project.info().describe()
                            + String.format("%nForms lidos:  %d (%d da aplicação, %d falhas)%nSQL em forms: %d textos%n",
                                    project.forms().size(), project.appForms().size(), project.failures().size(), sql.size()));
                    treeModel.setRoot(tree(project));
                    sqlModel.setRowCount(0);
                    sql.forEach(s -> sqlModel.addRow(new Object[] { s.origin(), s.componentClass(), s.property() }));
                    ghidraButton.setEnabled(true);
                    exportButton.setEnabled(true);
                    status.setText(project.info().isDelphi() ? "Pronto. Escolha um form na árvore." : "Não parece ser um executável Delphi.");
                }
                catch (InterruptedException | ExecutionException e) {
                    status.setText("Erro ao ler: " + rootMessage(e));
                }
            }
        }.execute();
    }

    private void showForm(FormResources.Form form) {
        dfmText.setText(DfmTextWriter.write(form.root()));
        dfmText.setCaretPosition(0);
        if (FormClassifier.isLibrary(form.resourceName())) {
            vueText.setText("// " + form.resourceName() + " é um form interno de biblioteca: não vira tela.");
        }
        else {
            VueGenerator.Result r = VueGenerator.generate(project, form, sql);
            vueText.setText(r.code());
        }
        vueText.setCaretPosition(0);
        status.setText(form.root().name() + ": " + form.root().className());
    }

    private void exportAll() {
        if (project == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Pasta de saída");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path out = chooser.getSelectedFile().toPath();
        status.setText("Exportando para " + out + "...");
        new SwingWorker<ProjectExporter.Summary, Void>() {
            @Override
            protected ProjectExporter.Summary doInBackground() throws Exception {
                return ProjectExporter.exportAll(project, out);
            }

            @Override
            protected void done() {
                try {
                    ProjectExporter.Summary s = get();
                    status.setText(String.format("Exportado: %d forms, %d SQL, %d telas Vue, %d funções Go (%d sem lacuna), %d avisos em %s",
                            s.forms(), s.sql(), s.vue(), s.goFunctions(), s.goReady(), s.warnings().size(), out));
                }
                catch (InterruptedException | ExecutionException e) {
                    status.setText("Erro ao exportar: " + rootMessage(e));
                }
            }
        }.execute();
    }

    private void loadInGhidra() {
        Path exe = project.exe();
        ghidraButton.setEnabled(false);
        status.setText("Carregando no Ghidra (sem autoanálise)...");
        new SwingWorker<ProgramSummary, Void>() {
            @Override
            protected ProgramSummary doInBackground() throws Exception {
                if (engine == null) {
                    engine = new GhidraEngine();
                }
                return engine.load(exe, false);
            }

            @Override
            protected void done() {
                try {
                    summary.append('\n' + get().describe());
                    status.setText("Carregado no Ghidra.");
                }
                catch (InterruptedException | ExecutionException e) {
                    status.setText("Erro no Ghidra: " + rootMessage(e));
                }
                ghidraButton.setEnabled(true);
            }
        }.execute();
    }

    private static DefaultMutableTreeNode tree(DelphiProject p) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(p.exe().getFileName().toString() + " — "
                + (p.info().product() != null ? p.info().product() : "compilador não identificado"));
        DefaultMutableTreeNode app = new DefaultMutableTreeNode("Forms da aplicação (" + p.appForms().size() + ")");
        DefaultMutableTreeNode lib = new DefaultMutableTreeNode("Forms de bibliotecas (" + (p.forms().size() - p.appForms().size()) + ")");
        for (FormResources.Form f : p.forms()) {
            (FormClassifier.isLibrary(f.resourceName()) ? lib : app).add(new DefaultMutableTreeNode(new FormNode(f)));
        }
        root.add(app);
        root.add(lib);
        DefaultMutableTreeNode libs = new DefaultMutableTreeNode("Bibliotecas de componentes");
        p.info().libraries().forEach((name, refs) -> libs.add(new DefaultMutableTreeNode(name + " (" + refs + ")")));
        root.add(libs);
        return root;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
