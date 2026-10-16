package undelphi.vue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import undelphi.dfm.Datasets;
import undelphi.dfm.DfmObject;
import undelphi.dfm.DfmSql;
import undelphi.dfm.DfmValue;
import undelphi.dfm.FormResources.Form;
import undelphi.model.DelphiProject;

/**
 * Gera uma tela Vue 3 (script setup) a partir de um form Delphi, no padrão do Inova: componentes InovaButton,
 * InovaDataTable e InovaTabs, cliente HTTP em '@/services/api' e tokens de cor do design system.
 */
public final class VueGenerator {

    /** Resultado: o código do .vue e o modelo (eventos, campos, datasets) que a F4 usa para gerar o backend. */
    public record Result(String fileName, String code, FormModel model) {
    }

    private enum Kind {
        CONTAINER, PAGES, PAGE, LABEL, TEXT, NUMBER, DATE, MEMO, SELECT, CHECK, RADIO, RADIO_GROUP, BUTTON_EDIT,
        BUTTON, GRID, DBGRID, VGRID, VALUE_LIST, LIST, IGNORE, NON_VISUAL, UNKNOWN
    }

    private static final Map<String, Kind> KINDS = new HashMap<>();
    static {
        for (String c : List.of("TGroupBox", "TcxGroupBox", "TPanel", "TcxPanel", "TScrollBox", "TcxScrollBox",
                "TFlowPanel", "TGridPanel", "TrfWallpaper", "TWallpaperRotinaPC", "TFrame", "TcxGridPopupMenu", "TdxPanel", "TJvPanel",
                "TCategoryPanel", "TCategoryPanelGroup", "TdxLayoutControl", "TcxPageControlPanel")) {
            KINDS.put(c, Kind.CONTAINER);
        }
        for (String c : List.of("TJvWizard", "TPageControl", "TcxPageControl", "TTabControl", "TcxTabControl",
                "TJvPageList", "TdxPageControl")) {
            KINDS.put(c, Kind.PAGES);
        }
        for (String c : List.of("TJvWizardWelcomePage", "TJvWizardInteriorPage", "TTabSheet", "TcxTabSheet",
                "TJvStandardPage", "TdxTabSheet")) {
            KINDS.put(c, Kind.PAGE);
        }
        for (String c : List.of("TLabel", "TcxLabel", "TStaticText", "TJvLabel", "TcxDBLabel", "TDBText", "TJvGroupHeader")) {
            KINDS.put(c, Kind.LABEL);
        }
        for (String c : List.of("TEdit", "TcxTextEdit", "TcxMaskEdit", "TMaskEdit", "TDBEdit", "TcxDBTextEdit",
                "TcxDBMaskEdit", "TLabeledEdit", "TJvEdit", "TcxHyperLinkEdit")) {
            KINDS.put(c, Kind.TEXT);
        }
        for (String c : List.of("TcxSpinEdit", "TcxCurrencyEdit", "TcxCalcEdit", "TSpinEdit", "TcxDBSpinEdit",
                "TcxDBCurrencyEdit", "TcxDBCalcEdit", "TJvSpinEdit", "TJvCalcEdit", "TcxTrackBar")) {
            KINDS.put(c, Kind.NUMBER);
        }
        for (String c : List.of("TcxDateEdit", "TDateTimePicker", "TcxDBDateEdit", "TJvDateEdit", "TcxTimeEdit",
                "TJvDateTimePicker")) {
            KINDS.put(c, Kind.DATE);
        }
        for (String c : List.of("TMemo", "TcxMemo", "TRichEdit", "TcxRichEdit", "TDBMemo", "TcxDBMemo", "TJvMemo")) {
            KINDS.put(c, Kind.MEMO);
        }
        for (String c : List.of("TComboBox", "TcxComboBox", "TcxImageComboBox", "TcxLookupComboBox",
                "TDBLookupComboBox", "TcxDBLookupComboBox", "TcxDBComboBox", "TDBComboBox", "TcxDBImageComboBox",
                "TJvComboBox", "TcxCheckComboBox", "TcxExtLookupComboBox")) {
            KINDS.put(c, Kind.SELECT);
        }
        for (String c : List.of("TCheckBox", "TcxCheckBox", "TDBCheckBox", "TcxDBCheckBox", "TJvCheckBox")) {
            KINDS.put(c, Kind.CHECK);
        }
        for (String c : List.of("TRadioButton", "TcxRadioButton")) {
            KINDS.put(c, Kind.RADIO);
        }
        for (String c : List.of("TRadioGroup", "TcxRadioGroup", "TDBRadioGroup", "TcxDBRadioGroup")) {
            KINDS.put(c, Kind.RADIO_GROUP);
        }
        for (String c : List.of("TcxButtonEdit", "TPesquisarEditButton", "TcxDBButtonEdit", "TJvFilenameEdit",
                "TJvDirectoryEdit", "TcxShellComboBox")) {
            KINDS.put(c, Kind.BUTTON_EDIT);
        }
        for (String c : List.of("TButton", "TBitBtn", "TSpeedButton", "TcxButton", "TJvBitBtn", "TJvXPButton",
                "TJvImgBtn", "TdxBarLargeButton", "TdxBarButton", "TToolButton", "TJvSpeedButton")) {
            KINDS.put(c, Kind.BUTTON);
        }
        KINDS.put("TcxGrid", Kind.GRID);
        for (String c : List.of("TDBGrid", "TJvDBGrid", "TJvDBUltimGrid", "TSMDBGrid", "TwwDBGrid", "TStringGrid")) {
            KINDS.put(c, Kind.DBGRID);
        }
        for (String c : List.of("TcxVerticalGrid", "TcxDBVerticalGrid")) {
            KINDS.put(c, Kind.VGRID);
        }
        KINDS.put("TValueListEditor", Kind.VALUE_LIST);
        for (String c : List.of("TcxListView", "TListView", "TListBox", "TcxListBox", "TcxCheckListBox",
                "TCheckListBox", "TcxMCListBox", "TJvListBox", "TTreeView", "TcxTreeView", "TJvThumbView")) {
            KINDS.put(c, Kind.LIST);
        }
        for (String c : List.of("TImage", "TcxImage", "TcxSplitter", "TSplitter", "TBevel", "TShape", "TWebBrowser",
                "TStatusBar", "TdxStatusBar", "TProgressBar", "TcxProgressBar", "TAnimate", "TPaintBox",
                "TToolBar", "TdxBarDockControl", "TcxGridLevel", "TCoolBar", "TControlBar", "TJvGradient",
                "TcxImageList", "TdxSkinController", "TChart", "TDBChart", "TppViewer", "TcxDBNavigator",
                "TDBNavigator", "TcxNavigator")) {
            KINDS.put(c, Kind.IGNORE);
        }
    }

    /** Eventos de campo que viram chamadas ao backend, e o evento DOM correspondente. */
    private static final Map<String, String> INPUT_EVENTS = new LinkedHashMap<>();
    static {
        INPUT_EVENTS.put("OnChange", "change");
        INPUT_EVENTS.put("Properties.OnChange", "change");
        INPUT_EVENTS.put("Properties.OnEditValueChanged", "change");
        INPUT_EVENTS.put("OnExit", "blur");
        INPUT_EVENTS.put("OnKeyPress", "keydown.enter");
        INPUT_EVENTS.put("OnKeyDown", "keydown.enter");
        INPUT_EVENTS.put("OnClick", "click");
    }

    private final DelphiProject project;
    private final Form form;
    private final FormModel model;
    private final StringBuilder t = new StringBuilder();
    private final Map<String, String> formState = new LinkedHashMap<>();
    private final Map<String, String> tabsState = new LinkedHashMap<>();
    private final Map<String, List<String[]>> tabLists = new LinkedHashMap<>();
    private final Map<String, String> pageEnterEvents = new LinkedHashMap<>();
    private final Map<String, String> listState = new LinkedHashMap<>();
    private final Map<String, String> columnDefs = new LinkedHashMap<>();
    private final Set<String> components = new TreeSet<>();
    private final Map<String, String> labelFor = new HashMap<>();
    private final Set<String> consumedLabels = new HashSet<>();
    private final Set<String> radioGroups = new LinkedHashSet<>();
    private final Map<String, String> sqlOriginByDataset = new HashMap<>();

    private VueGenerator(DelphiProject project, Form form, List<DfmSql> sql) {
        this.project = project;
        this.form = form;
        String base = "/undelphi/" + project.baseName().toLowerCase(Locale.ROOT) + "/" + form.root().name().toLowerCase(Locale.ROOT);
        String caption = clean(form.root().text("Caption"));
        this.model = new FormModel(form.resourceName(), form.root().name(), caption == null ? form.root().name() : caption, base);
        for (DfmSql s : sql) {
            if (s.property().equals("SQL.Strings")) {
                sqlOriginByDataset.put(s.origin().toUpperCase(Locale.ROOT), s.origin());
            }
        }
    }

    public static Result generate(DelphiProject project, Form form, List<DfmSql> sql) {
        VueGenerator g = new VueGenerator(project, form, sql);
        String code = g.build();
        return new Result(form.root().name() + ".vue", code, g.model);
    }

    private String build() {
        DfmObject root = form.root();
        pairLabels(root);
        for (String ev : List.of("OnCreate", "OnShow", "OnActivate")) {
            if (root.text(ev) != null) {
                model.addEvent(new FormModel.Event(root.text(ev), root.name(), root.className(), ev));
            }
        }

        t.append("<template>\n");
        t.append("  <div class=\"ud-tela\">\n");
        t.append("    <header class=\"ud-cabecalho\">\n");
        t.append("      <h2>").append(html(model.caption)).append("</h2>\n");
        t.append("      <span class=\"ud-origem font-mono\">").append(html(project.baseName() + " · " + form.resourceName())).append("</span>\n");
        t.append("    </header>\n");
        t.append("    <div v-if=\"mensagem.texto\" :class=\"['ud-mensagem', 'ud-' + mensagem.tipo]\" role=\"status\">\n");
        t.append("      <span>{{ mensagem.texto }}</span>\n");
        t.append("      <button class=\"ud-fechar\" @click=\"mensagem.texto = ''\" title=\"Fechar\">&times;</button>\n");
        t.append("    </div>\n");
        renderChildren(root, 2);
        t.append("  </div>\n");
        t.append("</template>\n\n");
        t.append(script());
        t.append('\n');
        t.append(STYLE);
        return t.toString();
    }

    /**
     * Liga cada TLabel ao campo que ele descreve: pelo FocusControl quando ele aponta para um campo ainda sem rótulo;
     * senão, ao campo mais próximo à direita na mesma linha (o Delphi não obriga o FocusControl, e às vezes ele está errado).
     */
    private void pairLabels(DfmObject root) {
        List<DfmObject> labels = new ArrayList<>();
        root.walk(o -> {
            if (kind(o) == Kind.LABEL) {
                labels.add(o);
            }
        });
        for (DfmObject label : labels) {
            String target = label.text("FocusControl");
            if (target != null) {
                target = target.toLowerCase(Locale.ROOT);
                if (!labelFor.containsKey(target)) {
                    DfmObject input = label.root().find(target);
                    if (input != null && input.parent() == label.parent()
                            && Math.abs(input.integer("Top", 0) - label.integer("Top", 0)) <= 16) {
                        labelFor.put(target, clean(label.text("Caption")));
                        consumedLabels.add(label.name());
                        continue;
                    }
                }
            }
            DfmObject best = null;
            for (DfmObject sib : label.parent().children()) {
                if (!isInput(sib) || labelFor.containsKey(sib.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                int dy = Math.abs(sib.integer("Top", 0) - label.integer("Top", 0));
                int dx = sib.integer("Left", 0) - label.integer("Left", 0);
                if (dy <= 10 && dx > 0 && dx <= 400 && (best == null || dx < best.integer("Left", 0) - label.integer("Left", 0))) {
                    best = sib;
                }
            }
            if (best != null) {
                labelFor.put(best.name().toLowerCase(Locale.ROOT), clean(label.text("Caption")));
                consumedLabels.add(label.name());
            }
        }
    }

    private boolean isInput(DfmObject o) {
        return switch (kind(o)) {
            case TEXT, NUMBER, DATE, MEMO, SELECT, BUTTON_EDIT -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- classificação

    private Kind kind(DfmObject o) {
        Kind k = KINDS.get(o.className());
        if (k != null) {
            return k;
        }
        String c = o.className();
        if (Datasets.isDataset(o) || Datasets.isDataSource(o) || Datasets.isCommand(o)) {
            return Kind.NON_VISUAL;
        }
        if (c.endsWith("Field") || c.endsWith("Action") || c.endsWith("ActionList") || c.endsWith("Session")
                || c.endsWith("Timer") || c.endsWith("Dialog") || c.endsWith("ImageList") || c.endsWith("Menu")
                || c.endsWith("MenuItem") || c.endsWith("Repository") || c.endsWith("Controller") || c.endsWith("Style")
                || c.endsWith("Provider") || c.endsWith("Report") || c.endsWith("Pipeline") || c.contains("Printer")) {
            return Kind.NON_VISUAL;
        }
        if (o.get("Width") == null && o.get("Height") == null && o.get("Align") == null) {
            return Kind.NON_VISUAL;
        }
        if (c.endsWith("Page") || c.endsWith("Sheet")) {
            return Kind.PAGE;
        }
        if (c.endsWith("Panel") || c.endsWith("Box") && !c.endsWith("ComboBox") && !c.endsWith("ListBox") && !c.endsWith("CheckBox")) {
            return Kind.CONTAINER;
        }
        return Kind.UNKNOWN;
    }

    private boolean visible(DfmObject o) {
        return o.bool("Visible", true);
    }

    // ---------------------------------------------------------------- template

    private void renderChildren(DfmObject parent, int depth) {
        List<DfmObject> visual = new ArrayList<>();
        for (DfmObject c : parent.children()) {
            Kind k = kind(c);
            if (k == Kind.NON_VISUAL || k == Kind.IGNORE && !c.className().equals("TWebBrowser")) {
                continue;
            }
            if (k == Kind.LABEL && consumedLabels.contains(c.name())) {
                continue;
            }
            visual.add(c);
        }
        visual.sort(Comparator.comparingInt((DfmObject o) -> o.integer("Top", 0)).thenComparingInt(o -> o.integer("Left", 0)));

        // linhas: componentes com Top parecido ficam lado a lado
        List<List<DfmObject>> rows = new ArrayList<>();
        int rowTop = Integer.MIN_VALUE;
        for (DfmObject c : visual) {
            int top = c.integer("Top", 0);
            boolean block = isBlock(c);
            if (rows.isEmpty() || block || isBlock(rows.get(rows.size() - 1).get(0)) || Math.abs(top - rowTop) > 12) {
                rows.add(new ArrayList<>());
                rowTop = top;
            }
            rows.get(rows.size() - 1).add(c);
        }
        String in = pad(depth);
        for (List<DfmObject> row : rows) {
            row.sort(Comparator.comparingInt(o -> o.integer("Left", 0)));
            if (row.size() == 1) {
                render(row.get(0), depth);
            }
            else {
                t.append(in).append("<div class=\"ud-linha\">\n");
                for (DfmObject c : row) {
                    render(c, depth + 1);
                }
                t.append(in).append("</div>\n");
            }
        }
    }

    private boolean isBlock(DfmObject o) {
        Kind k = kind(o);
        return switch (k) {
            case CONTAINER, PAGES, PAGE, GRID, DBGRID, VGRID, VALUE_LIST, LIST, MEMO -> true;
            default -> o.className().equals("TWebBrowser");
        };
    }

    private void render(DfmObject o, int depth) {
        String in = pad(depth);
        String hidden = visible(o) ? "" : " v-show=\"false\"";
        switch (kind(o)) {
            case CONTAINER -> {
                String caption = clean(o.text("Caption"));
                boolean titled = caption != null && !caption.isBlank() && !caption.equalsIgnoreCase(o.name());
                t.append(in).append("<section class=\"").append(titled ? "ud-grupo" : "ud-bloco").append("\"").append(hidden)
                        .append(" data-delphi=\"").append(html(o.name())).append("\">\n");
                if (titled) {
                    t.append(in).append("  <h3>").append(html(caption)).append("</h3>\n");
                }
                renderChildren(o, depth + 1);
                t.append(in).append("</section>\n");
            }
            case PAGES -> renderPages(o, depth);
            case PAGE -> {
                t.append(in).append("<section class=\"ud-bloco\"").append(hidden).append(">\n");
                renderChildren(o, depth + 1);
                t.append(in).append("</section>\n");
            }
            case LABEL -> {
                String caption = clean(o.text("Caption"));
                if (caption != null && !caption.isBlank()) {
                    t.append(in).append("<span class=\"ud-rotulo\"").append(hidden).append(">").append(html(caption)).append("</span>\n");
                }
            }
            case TEXT, NUMBER, DATE, MEMO -> renderInput(o, depth, hidden);
            case SELECT -> renderSelect(o, depth, hidden);
            case CHECK -> {
                state(o.name(), String.valueOf(o.bool("Checked", false) || "cbsChecked".equals(o.text("State"))));
                model.inputs.add(new FormModel.Input(o.name(), o.className(), "booleano", clean(o.text("Caption"))));
                t.append(in).append("<label class=\"ud-check\"").append(hidden).append(">\n");
                t.append(in).append("  <input type=\"checkbox\" v-model=\"form.").append(o.name()).append("\"")
                        .append(disabled(o)).append(domEvents(o)).append(" />\n");
                t.append(in).append("  <span>").append(html(nz(clean(o.text("Caption"))))).append("</span>\n");
                t.append(in).append("</label>\n");
            }
            case RADIO -> {
                String group = "opcao_" + o.parent().name();
                if (radioGroups.add(group)) {
                    state(group, "''");
                    model.inputs.add(new FormModel.Input(group, "TRadioButton", "opcao", null));
                }
                if (o.bool("Checked", false)) {
                    formState.put(group, js(o.name()));
                }
                t.append(in).append("<label class=\"ud-check\"").append(hidden).append(">\n");
                t.append(in).append("  <input type=\"radio\" value=\"").append(html(o.name())).append("\" v-model=\"form.")
                        .append(group).append("\"").append(disabled(o)).append(domEvents(o)).append(" />\n");
                t.append(in).append("  <span>").append(html(nz(clean(o.text("Caption"))))).append("</span>\n");
                t.append(in).append("</label>\n");
            }
            case RADIO_GROUP -> renderRadioGroup(o, depth, hidden);
            case BUTTON_EDIT -> renderButtonEdit(o, depth, hidden);
            case BUTTON -> renderButton(o, depth, hidden);
            case GRID -> renderCxGrid(o, depth, hidden);
            case DBGRID -> renderDbGrid(o, depth, hidden);
            case VGRID -> renderVerticalGrid(o, depth, hidden);
            case VALUE_LIST -> renderKeyValue(o, depth, hidden);
            case LIST -> {
                listState.put(o.name(), "[]");
                model.lists.add(o.name());
                t.append(in).append("<ul class=\"ud-lista\"").append(hidden).append(" data-delphi=\"").append(html(o.name())).append("\">\n");
                t.append(in).append("  <li v-for=\"(item, i) in listas.").append(o.name()).append("\" :key=\"i\">{{ item }}</li>\n");
                t.append(in).append("</ul>\n");
            }
            case IGNORE -> t.append(in).append("<!-- undelphi: ").append(html(o.className())).append(" ").append(html(o.name()))
                    .append(" não tem equivalente na web -->\n");
            case UNKNOWN -> {
                model.warnings.add(o.className() + " " + o.name() + ": componente sem mapeamento para Vue");
                t.append(in).append("<div class=\"ud-pendente\"").append(hidden).append(">")
                        .append(html(o.className() + " " + o.name())).append(" (sem mapeamento)</div>\n");
                renderChildren(o, depth);
            }
            default -> {
            }
        }
    }

    private void renderKeyValue(DfmObject o, int depth, String hidden) {
        String in = pad(depth);
        listState.put(o.name(), "{}");
        model.lists.add(o.name());
        t.append(in).append("<table class=\"ud-vgrid\"").append(hidden).append(" data-delphi=\"").append(html(o.name())).append("\">\n");
        t.append(in).append("  <tbody>\n");
        t.append(in).append("    <tr v-for=\"(valor, chave) in listas.").append(o.name()).append("\" :key=\"chave\">\n");
        t.append(in).append("      <th>{{ chave }}</th>\n");
        t.append(in).append("      <td class=\"font-mono\">{{ valor }}</td>\n");
        t.append(in).append("    </tr>\n");
        t.append(in).append("    <tr v-if=\"!Object.keys(listas.").append(o.name()).append(").length\"><td colspan=\"2\" class=\"ud-vazio\">Sem dados.</td></tr>\n");
        t.append(in).append("  </tbody>\n");
        t.append(in).append("</table>\n");
    }

    private void renderPages(DfmObject o, int depth) {
        String in = pad(depth);
        List<DfmObject> pages = new ArrayList<>();
        for (DfmObject c : o.children()) {
            if (kind(c) == Kind.PAGE) {
                pages.add(c);
            }
        }
        pages.sort(Comparator.comparingInt(p -> p.integer("PageIndex", 0)));
        if (pages.isEmpty()) {
            renderChildren(o, depth);
            return;
        }
        boolean wizard = o.className().contains("Wizard");
        List<String[]> tabs = new ArrayList<>();
        for (DfmObject p : pages) {
            String label = clean(p.text("Header.Title.Text"));
            if (label == null || label.isBlank() || label.equals("Title")) {
                label = clean(p.text("Caption"));
            }
            if (label == null || label.isBlank()) {
                label = p.name();
            }
            tabs.add(new String[] { p.name(), label });
            if (p.text("OnPage") != null) {
                pageEnterEvents.put(p.name(), p.text("OnPage"));
                model.addEvent(new FormModel.Event(p.text("OnPage"), p.name(), p.className(), "OnPage"));
            }
            if (p.text("OnShow") != null) {
                pageEnterEvents.put(p.name(), p.text("OnShow"));
                model.addEvent(new FormModel.Event(p.text("OnShow"), p.name(), p.className(), "OnShow"));
            }
        }
        String active = o.text("ActivePage");
        tabsState.put(o.name(), js(active != null && pages.stream().anyMatch(p -> p.name().equals(active)) ? active : pages.get(0).name()));
        tabLists.put(o.name(), tabs);
        components.add("InovaTabs");

        t.append(in).append("<section class=\"ud-paginas\" data-delphi=\"").append(html(o.name())).append("\">\n");
        t.append(in).append("  <InovaTabs v-model=\"abas.").append(o.name()).append("\" :tabs=\"abasDe.").append(o.name()).append("\" />\n");
        for (int i = 0; i < pages.size(); i++) {
            DfmObject p = pages.get(i);
            t.append(in).append("  <div v-show=\"abas.").append(o.name()).append(" === '").append(p.name())
                    .append("'\" class=\"ud-pagina\" data-delphi=\"").append(html(p.name())).append("\">\n");
            String subtitle = clean(p.text("Header.Subtitle.Text"));
            if (subtitle != null && !subtitle.isBlank() && !subtitle.equals("Subtitle")) {
                t.append(in).append("    <p class=\"ud-subtitulo\">").append(html(subtitle)).append("</p>\n");
            }
            renderChildren(p, depth + 2);
            if (wizard) {
                String back = p.text("OnBackButtonClick");
                String next = p.text("OnNextButtonClick");
                String finish = p.text("OnFinishButtonClick");
                for (String[] ev : new String[][] { { back, "OnBackButtonClick" }, { next, "OnNextButtonClick" }, { finish, "OnFinishButtonClick" } }) {
                    if (ev[0] != null) {
                        model.addEvent(new FormModel.Event(ev[0], p.name(), p.className(), ev[1]));
                    }
                }
                components.add("InovaButton");
                t.append(in).append("    <footer class=\"ud-assistente\">\n");
                if (i > 0) {
                    t.append(in).append("      <InovaButton variant=\"neutral\" @click=\"voltar('").append(o.name()).append("', ")
                            .append(back == null ? "null" : "'" + back + "'").append(")\">Voltar</InovaButton>\n");
                }
                if (i < pages.size() - 1) {
                    t.append(in).append("      <InovaButton :loading=\"!!ocupado.evento\" @click=\"avancar('").append(o.name()).append("', ")
                            .append(next == null ? "null" : "'" + next + "'").append(")\">Avançar</InovaButton>\n");
                }
                else if (finish != null) {
                    t.append(in).append("      <InovaButton :loading=\"!!ocupado.evento\" @click=\"evento('").append(finish)
                            .append("')\">Concluir</InovaButton>\n");
                }
                t.append(in).append("    </footer>\n");
            }
            t.append(in).append("  </div>\n");
        }
        t.append(in).append("</section>\n");
    }

    private void renderInput(DfmObject o, int depth, String hidden) {
        String in = pad(depth);
        Kind k = kind(o);
        String kindName = switch (k) {
            case NUMBER -> "numero";
            case DATE -> "data";
            default -> "texto";
        };
        String label = labelFor.get(o.name().toLowerCase(Locale.ROOT));
        model.inputs.add(new FormModel.Input(o.name(), o.className(), kindName, label));
        String initial = o.text("Text");
        if (initial == null) {
            initial = o.text("Value") != null ? o.text("Value") : o.text("EditValue");
        }
        if (k == Kind.MEMO) {
            Object lines = o.get("Lines.Strings");
            state(o.name(), lines instanceof List<?> ? js(o.lines("Lines.Strings")) : "''");
        }
        else if (k == Kind.NUMBER) {
            state(o.name(), initial != null && initial.matches("-?\\d+(\\.\\d+)?") ? initial : "null");
        }
        else {
            state(o.name(), initial != null ? js(initial) : "''");
        }
        String placeholder = o.text("TextHint") != null ? o.text("TextHint") : o.text("Properties.Nullstring");
        t.append(in).append("<label class=\"ud-campo").append(k == Kind.MEMO ? " ud-largo" : "").append("\"").append(hidden).append(">\n");
        if (label != null && !label.isBlank()) {
            t.append(in).append("  <span>").append(html(label)).append("</span>\n");
        }
        String common = " v-model" + (k == Kind.NUMBER ? ".number" : "") + "=\"form." + o.name() + "\"" + disabled(o) + domEvents(o)
                + (o.text("Hint") != null ? " title=\"" + html(o.text("Hint")) + "\"" : "")
                + (placeholder != null ? " placeholder=\"" + html(placeholder) + "\"" : "");
        switch (k) {
            case MEMO -> t.append(in).append("  <textarea rows=\"4\"").append(common).append("></textarea>\n");
            case NUMBER -> t.append(in).append("  <input type=\"number\" class=\"font-mono\"").append(common).append(" />\n");
            case DATE -> t.append(in).append("  <input type=\"date\"").append(common).append(" />\n");
            default -> {
                int max = o.integer("MaxLength", o.integer("Properties.MaxLength", 0));
                t.append(in).append("  <input type=\"").append(o.text("PasswordChar") != null ? "password" : "text").append("\"")
                        .append(max > 0 ? " maxlength=\"" + max + "\"" : "").append(common).append(" />\n");
            }
        }
        t.append(in).append("</label>\n");
    }

    private void renderSelect(DfmObject o, int depth, String hidden) {
        String in = pad(depth);
        String label = labelFor.get(o.name().toLowerCase(Locale.ROOT));
        model.inputs.add(new FormModel.Input(o.name(), o.className(), "opcao", label));
        List<String> items = items(o);
        String text = o.text("Text");
        state(o.name(), text != null ? js(text) : "''");
        t.append(in).append("<label class=\"ud-campo\"").append(hidden).append(">\n");
        if (label != null && !label.isBlank()) {
            t.append(in).append("  <span>").append(html(label)).append("</span>\n");
        }
        t.append(in).append("  <select v-model=\"form.").append(o.name()).append("\"").append(disabled(o)).append(domEvents(o)).append(">\n");
        if (items.isEmpty()) {
            listState.put(o.name(), "[]");
            model.lists.add(o.name());
            t.append(in).append("    <option v-for=\"op in listas.").append(o.name()).append("\" :key=\"op\" :value=\"op\">{{ op }}</option>\n");
        }
        for (String item : items) {
            t.append(in).append("    <option value=\"").append(html(item)).append("\">").append(html(item)).append("</option>\n");
        }
        t.append(in).append("  </select>\n");
        t.append(in).append("</label>\n");
    }

    private void renderRadioGroup(DfmObject o, int depth, String hidden) {
        String in = pad(depth);
        List<String> items = items(o);
        model.inputs.add(new FormModel.Input(o.name(), o.className(), "opcao", clean(o.text("Caption"))));
        int index = o.integer("ItemIndex", o.integer("ItemIndex", -1));
        state(o.name(), index >= 0 ? String.valueOf(index) : "null");
        t.append(in).append("<fieldset class=\"ud-grupo\"").append(hidden).append(">\n");
        String caption = clean(o.text("Caption"));
        if (caption != null && !caption.isBlank()) {
            t.append(in).append("  <legend>").append(html(caption)).append("</legend>\n");
        }
        for (int i = 0; i < items.size(); i++) {
            t.append(in).append("  <label class=\"ud-check\"><input type=\"radio\" :value=\"").append(i).append("\" v-model=\"form.")
                    .append(o.name()).append("\"").append(domEvents(o)).append(" /> <span>").append(html(items.get(i))).append("</span></label>\n");
        }
        t.append(in).append("</fieldset>\n");
    }

    private void renderButtonEdit(DfmObject o, int depth, String hidden) {
        String in = pad(depth);
        String label = labelFor.get(o.name().toLowerCase(Locale.ROOT));
        model.inputs.add(new FormModel.Input(o.name(), o.className(), "texto", label));
        state(o.name(), o.text("Text") != null ? js(o.text("Text")) : "''");
        String button = null;
        for (String p : List.of("Properties.OnButtonClick", "OnButtonClick", "OnPropertiesButtonClick", "OnClickBtn")) {
            if (o.text(p) != null) {
                button = o.text(p);
                model.addEvent(new FormModel.Event(button, o.name(), o.className(), p));
                break;
            }
        }
        String placeholder = o.text("TextHint");
        t.append(in).append("<label class=\"ud-campo\"").append(hidden).append(">\n");
        if (label != null && !label.isBlank()) {
            t.append(in).append("  <span>").append(html(label)).append("</span>\n");
        }
        t.append(in).append("  <div class=\"ud-com-botao\">\n");
        t.append(in).append("    <input type=\"text\" v-model=\"form.").append(o.name()).append("\"").append(disabled(o)).append(domEvents(o))
                .append(placeholder != null ? " placeholder=\"" + html(placeholder) + "\"" : "").append(" />\n");
        if (button != null) {
            components.add("InovaButton");
            t.append(in).append("    <InovaButton size=\"sm\" variant=\"neutral\" title=\"Pesquisar\" @click=\"evento('").append(button).append("')\">…</InovaButton>\n");
        }
        t.append(in).append("  </div>\n");
        t.append(in).append("</label>\n");
    }

    private void renderButton(DfmObject o, int depth, String hidden) {
        String in = pad(depth);
        String caption = clean(o.text("Caption"));
        String handler = o.text("OnClick");
        if (o.text("Action") != null) {
            DfmObject action = project.resolve(o, o.text("Action"));
            if (action != null) {
                if (caption == null) {
                    caption = clean(action.text("Caption"));
                }
                if (handler == null && action.text("OnExecute") != null) {
                    handler = action.text("OnExecute");
                    model.addEvent(new FormModel.Event(handler, action.name(), action.className(), "OnExecute"));
                }
            }
        }
        if (caption == null || caption.isBlank()) {
            caption = o.text("Hint") != null ? o.text("Hint") : o.name();
        }
        components.add("InovaButton");
        String variant = o.bool("Default", false) ? "primary" : "neutral";
        t.append(in).append("<InovaButton variant=\"").append(variant).append("\"").append(hidden)
                .append(o.bool("Enabled", true) ? "" : " disabled");
        if (handler != null) {
            if (o.text("OnClick") != null) {
                model.addEvent(new FormModel.Event(handler, o.name(), o.className(), "OnClick"));
            }
            t.append(" :loading=\"ocupado.evento === '").append(handler).append("'\" @click=\"evento('").append(handler).append("')\"");
        }
        t.append(">").append(html(caption)).append("</InovaButton>\n");
    }

    private void renderCxGrid(DfmObject grid, int depth, String hidden) {
        List<DfmObject> views = new ArrayList<>();
        for (DfmObject c : grid.children()) {
            if (c.className().contains("View")) {
                views.add(c);
            }
        }
        String in = pad(depth);
        if (views.isEmpty()) {
            t.append(in).append("<!-- undelphi: ").append(html(grid.name())).append(" sem views -->\n");
            return;
        }
        for (DfmObject view : views) {
            List<String> cols = new ArrayList<>();
            DfmObject dataset = datasetOf(view, view.text("DataController.DataSource"));
            for (DfmObject col : view.children()) {
                if (!col.className().contains("Column")) {
                    continue;
                }
                String field = col.text("DataBinding.FieldName");
                String key = field != null ? field : col.name();
                Datasets.Field f = Datasets.field(dataset, field);
                String label = clean(col.text("Caption"));
                if (label == null && f != null && f.displayLabel() != null) {
                    label = f.displayLabel();
                }
                if (label == null) {
                    label = key;
                }
                cols.add(column(key, label, col.integer("Width", 0), !col.bool("Visible", true),
                        f != null && f.kind().equals("numero")));
            }
            renderTable(view, dataset, cols, depth, hidden, view.text("OnCellDblClick") != null ? view.text("OnCellDblClick")
                    : view.text("OnDblClick"), view.text("OnFocusedRecordChanged"));
        }
    }

    private void renderDbGrid(DfmObject grid, int depth, String hidden) {
        DfmObject dataset = datasetOf(grid, grid.text("DataSource"));
        List<String> cols = new ArrayList<>();
        if (grid.get("Columns") instanceof DfmValue.Collection c) {
            for (DfmValue.Item item : c.items()) {
                Object field = item.properties().get("FieldName");
                if (!(field instanceof String fieldName)) {
                    continue;
                }
                Object title = item.properties().get("Title.Caption");
                Object width = item.properties().get("Width");
                Object vis = item.properties().get("Visible");
                Datasets.Field f = Datasets.field(dataset, fieldName);
                cols.add(column(fieldName, title instanceof String s ? clean(s) : fieldName,
                        width instanceof Long l ? l.intValue() : 0, Boolean.FALSE.equals(vis), f != null && f.kind().equals("numero")));
            }
        }
        if (cols.isEmpty() && dataset != null) {
            for (Datasets.Field f : Datasets.fields(dataset)) {
                cols.add(column(f.fieldName(), f.displayLabel() != null ? f.displayLabel() : f.fieldName(), 0, !f.visible(),
                        f.kind().equals("numero")));
            }
        }
        renderTable(grid, dataset, cols, depth, hidden, grid.text("OnDblClick"), grid.text("OnCellClick"));
    }

    private void renderTable(DfmObject view, DfmObject dataset, List<String> cols, int depth, String hidden, String dblClick,
            String rowChange) {
        String in = pad(depth);
        String key = dataset != null ? datasetKey(dataset) : view.name();
        registerDataset(key, dataset);
        columnDefs.put(view.name(), "[\n" + String.join(",\n", cols.stream().map(c -> "    " + c).toList()) + "\n  ]");
        components.add("InovaDataTable");
        components.add("InovaButton");
        String handler = dblClick != null ? dblClick : rowChange;
        if (handler != null) {
            model.addEvent(new FormModel.Event(handler, view.name(), view.className(), dblClick != null ? "OnDblClick" : "OnFocusedRecordChanged"));
        }
        t.append(in).append("<InovaDataTable").append(hidden).append(" :columns=\"colunas.").append(view.name())
                .append("\" :data=\"dados['").append(key).append("']\" :loading=\"!!carregando['").append(key)
                .append("']\" storage-key=\"undelphi-").append(project.baseName().toLowerCase(Locale.ROOT)).append("-").append(view.name())
                .append("\" searchable paginate row-clickable @row-click=\"(linha) => selecionar('").append(key).append("', linha")
                .append(handler != null ? ", '" + handler + "'" : "").append(")\">\n");
        t.append(in).append("  <template #toolbar-actions>\n");
        t.append(in).append("    <InovaButton size=\"sm\" variant=\"ghost\" @click=\"carregar('").append(key).append("')\">Recarregar</InovaButton>\n");
        t.append(in).append("  </template>\n");
        t.append(in).append("</InovaDataTable>\n");
    }

    private void renderVerticalGrid(DfmObject grid, int depth, String hidden) {
        String in = pad(depth);
        if (grid.children().stream().noneMatch(c -> c.className().contains("Row"))) {
            // linhas criadas em tempo de execução: o backend devolve um mapa rótulo → valor em listas.<grade>
            renderKeyValue(grid, depth, hidden);
            return;
        }
        DfmObject dataset = datasetOf(grid, grid.text("DataController.DataSource"));
        String key = dataset != null ? datasetKey(dataset) : null;
        if (key != null) {
            registerDataset(key, dataset);
        }
        t.append(in).append("<table class=\"ud-vgrid\"").append(hidden).append(" data-delphi=\"").append(html(grid.name())).append("\">\n");
        t.append(in).append("  <tbody>\n");
        renderVerticalRows(grid, key, depth + 2);
        t.append(in).append("  </tbody>\n");
        t.append(in).append("</table>\n");
    }

    private void renderVerticalRows(DfmObject parent, String datasetKey, int depth) {
        String in = pad(depth);
        for (DfmObject row : parent.children()) {
            if (!row.className().contains("Row")) {
                continue;
            }
            String caption = clean(row.text("Properties.Caption"));
            if (row.className().contains("Category")) {
                t.append(in).append("<tr class=\"ud-categoria\"><th colspan=\"2\">").append(html(nz(caption))).append("</th></tr>\n");
            }
            else {
                String field = row.text("Properties.DataBinding.FieldName");
                String value;
                if (field != null && datasetKey != null) {
                    value = "(dados['" + datasetKey + "']?.[0] || {})['" + field + "'] ?? ''";
                }
                else {
                    state(row.name(), "''");
                    model.inputs.add(new FormModel.Input(row.name(), row.className(), "texto", caption));
                    value = "form." + row.name();
                }
                t.append(in).append("<tr><th>").append(html(nz(caption != null ? caption : field != null ? field : row.name())))
                        .append("</th><td class=\"font-mono\">{{ ").append(value).append(" }}</td></tr>\n");
            }
            renderVerticalRows(row, datasetKey, depth);
        }
    }

    // ---------------------------------------------------------------- datasets

    private DfmObject datasetOf(DfmObject context, String dataSourceRef) {
        DfmObject ds = project.resolve(context, dataSourceRef);
        if (ds == null) {
            return null;
        }
        if (Datasets.isDataSource(ds)) {
            return project.resolve(ds, ds.text("DataSet"));
        }
        return ds;
    }

    private String datasetKey(DfmObject dataset) {
        return dataset.root().name() + "." + dataset.name();
    }

    private void registerDataset(String key, DfmObject dataset) {
        if (model.datasets.containsKey(key)) {
            return;
        }
        String origin = null;
        List<String> fields = new ArrayList<>();
        if (dataset != null) {
            var f = project.formOf(dataset);
            if (f != null) {
                origin = sqlOriginByDataset.get((f.resourceName() + "." + dataset.name()).toUpperCase(Locale.ROOT));
            }
            for (Datasets.Field fl : Datasets.fields(dataset)) {
                fields.add(fl.fieldName());
            }
        }
        model.datasets.put(key, new FormModel.Dataset(key, dataset == null ? null : dataset.className(), origin, fields));
    }

    // ---------------------------------------------------------------- script

    private String script() {
        StringBuilder s = new StringBuilder();
        s.append("<script setup>\n");
        s.append("// Gerado pelo undelphi a partir de ").append(form.resourceName()).append(" (").append(project.exe().getFileName())
                .append("). Gere de novo em vez de editar.\n");
        s.append("// Backend esperado (F4): POST ").append(model.apiBase).append("/eventos/<handler> e GET ")
                .append(model.apiBase).append("/dados/<dataset>\n");
        s.append("import { reactive").append(pageEnterEvents.isEmpty() ? "" : ", watch").append(", onMounted } from 'vue'\n");
        s.append("import api from '@/services/api'\n");
        if (!components.isEmpty()) {
            s.append("import { ").append(String.join(", ", components)).append(" } from '@/components/common'\n");
        }
        s.append("\nconst BASE = ").append(js(model.apiBase)).append("\n\n");
        s.append("const form = reactive(").append(object(formState)).append(")\n");
        s.append("const abas = reactive(").append(object(tabsState)).append(")\n");
        Map<String, String> dados = new LinkedHashMap<>();
        Map<String, String> carregando = new LinkedHashMap<>();
        for (String k : model.datasets.keySet()) {
            dados.put(k, "[]");
            carregando.put(k, "false");
        }
        s.append("const dados = reactive(").append(object(dados)).append(")\n");
        s.append("const carregando = reactive(").append(object(carregando)).append(")\n");
        s.append("const listas = reactive(").append(object(listState)).append(")\n");
        s.append("const selecao = reactive({})\n");
        s.append("const mensagem = reactive({ texto: '', tipo: 'info' })\n");
        s.append("const ocupado = reactive({ evento: '' })\n\n");

        Map<String, String> tabs = new LinkedHashMap<>();
        tabLists.forEach((k, v) -> tabs.put(k, "[\n" + String.join(",\n", v.stream()
                .map(x -> "    { id: " + js(x[0]) + ", label: " + js(x[1]) + " }").toList()) + "\n  ]"));
        s.append("const abasDe = ").append(object(tabs)).append("\n");
        s.append("const colunas = ").append(object(columnDefs)).append("\n\n");

        s.append("""
                function aplicar(resposta) {
                  if (!resposta) return
                  if (resposta.form) Object.assign(form, resposta.form)
                  if (resposta.dados) Object.assign(dados, resposta.dados)
                  if (resposta.listas) Object.assign(listas, resposta.listas)
                  if (resposta.abas) Object.assign(abas, resposta.abas)
                  if (resposta.mensagem || resposta.erro) {
                    mensagem.texto = resposta.erro || resposta.mensagem
                    mensagem.tipo = resposta.erro ? 'erro' : 'info'
                  }
                }

                function falha(e) {
                  mensagem.texto = e?.response?.data?.erro || e?.message || 'Falha ao falar com o servidor.'
                  mensagem.tipo = 'erro'
                }

                async function evento(nome) {
                  ocupado.evento = nome
                  mensagem.texto = ''
                  try {
                    const { data } = await api.post(`${BASE}/eventos/${nome}`, { form, abas, selecao })
                    aplicar(data)
                    return !data?.erro
                  } catch (e) {
                    falha(e)
                    return false
                  } finally {
                    ocupado.evento = ''
                  }
                }

                async function carregar(ds) {
                  carregando[ds] = true
                  try {
                    const { data } = await api.get(`${BASE}/dados/${encodeURIComponent(ds)}`, { params: form })
                    dados[ds] = Array.isArray(data) ? data : data?.linhas || []
                  } catch (e) {
                    falha(e)
                  } finally {
                    carregando[ds] = false
                  }
                }

                function selecionar(ds, linha, nomeEvento) {
                  selecao[ds] = linha
                  if (nomeEvento) evento(nomeEvento)
                }
                """);
        if (!tabLists.isEmpty()) {
            s.append("""

                    async function avancar(grupo, nomeEvento) {
                      if (nomeEvento && !(await evento(nomeEvento))) return
                      const lista = abasDe[grupo]
                      const i = lista.findIndex((a) => a.id === abas[grupo])
                      if (i >= 0 && i < lista.length - 1) abas[grupo] = lista[i + 1].id
                    }

                    async function voltar(grupo, nomeEvento) {
                      if (nomeEvento && !(await evento(nomeEvento))) return
                      const lista = abasDe[grupo]
                      const i = lista.findIndex((a) => a.id === abas[grupo])
                      if (i > 0) abas[grupo] = lista[i - 1].id
                    }
                    """);
        }
        if (!pageEnterEvents.isEmpty()) {
            s.append("\n// Evento OnPage/OnShow de cada página\n");
            s.append("const aoEntrar = ").append(object(quoteValues(pageEnterEvents))).append("\n");
            for (String group : tabLists.keySet()) {
                s.append("watch(() => abas.").append(group).append(", (pagina) => {\n");
                s.append("  if (aoEntrar[pagina]) evento(aoEntrar[pagina])\n");
                s.append("})\n");
            }
        }
        s.append("\nonMounted(() => {\n");
        boolean any = false;
        for (FormModel.Event e : model.events) {
            if (e.component().equals(form.root().name())) {
                s.append("  evento(").append(js(e.handler())).append(")\n");
                any = true;
            }
        }
        for (FormModel.Dataset d : model.datasets.values()) {
            if (d.sqlOrigin() != null) {
                s.append("  carregar(").append(js(d.key())).append(")\n");
                any = true;
            }
        }
        if (!any) {
            s.append("  // nenhum evento de abertura no form original\n");
        }
        s.append("})\n");
        s.append("</script>\n");
        return s.toString();
    }

    // ---------------------------------------------------------------- utilidades

    private String domEvents(DfmObject o) {
        StringBuilder sb = new StringBuilder();
        Set<String> used = new HashSet<>();
        for (Map.Entry<String, String> e : INPUT_EVENTS.entrySet()) {
            String handler = o.text(e.getKey());
            if (handler != null && used.add(e.getValue())) {
                model.addEvent(new FormModel.Event(handler, o.name(), o.className(), e.getKey()));
                sb.append(" @").append(e.getValue()).append("=\"evento('").append(handler).append("')\"");
            }
        }
        return sb.toString();
    }

    private static String disabled(DfmObject o) {
        boolean readOnly = o.bool("ReadOnly", false) || o.bool("Properties.ReadOnly", false);
        return (o.bool("Enabled", true) ? "" : " disabled") + (readOnly ? " readonly" : "");
    }

    private static List<String> items(DfmObject o) {
        for (String p : List.of("Items.Strings", "Properties.Items.Strings")) {
            if (o.get(p) instanceof List<?> l) {
                return l.stream().map(String::valueOf).toList();
            }
        }
        if (o.get("Properties.Items") instanceof DfmValue.Collection c) {
            List<String> out = new ArrayList<>();
            for (DfmValue.Item item : c.items()) {
                Object caption = item.properties().get("Caption");
                if (caption == null) {
                    caption = item.properties().get("Description");
                }
                if (caption != null) {
                    out.add(clean(caption.toString()));
                }
            }
            return out;
        }
        return List.of();
    }

    private void state(String name, String initial) {
        formState.putIfAbsent(name, initial);
    }

    private static String column(String key, String label, int width, boolean hidden, boolean numeric) {
        StringBuilder c = new StringBuilder("{ key: ").append(js(key)).append(", label: ").append(js(label));
        if (width > 0) {
            c.append(", width: '").append(width).append("px'");
        }
        if (numeric) {
            c.append(", align: 'right', mono: true");
        }
        if (hidden) {
            c.append(", defaultHidden: true");
        }
        return c.append(" }").toString();
    }

    private static Map<String, String> quoteValues(Map<String, String> m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, js(v)));
        return out;
    }

    private static String object(Map<String, String> entries) {
        if (entries.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String k = e.getKey();
            sb.append("  ").append(k.matches("[A-Za-z_$][A-Za-z0-9_$]*") ? k : js(k)).append(": ").append(e.getValue());
            sb.append(++i < entries.size() ? ",\n" : "\n");
        }
        return sb.append("}").toString();
    }

    /** Remove o "&" de atalho de teclado das legendas do Delphi ("&Gravar" → "Gravar"; "&&" → "&"). */
    static String clean(String caption) {
        if (caption == null) {
            return null;
        }
        return caption.replace("&&", "\u0000").replace("&", "").replace("\u0000", "&").trim();
    }

    static String html(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("{{", "{ {");
    }

    static String js(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\'' -> sb.append("\\'");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('\'').toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String pad(int depth) {
        return "  ".repeat(depth);
    }

    private static final String STYLE = """
            <style scoped>
            .ud-tela {
              display: flex;
              flex-direction: column;
              gap: 16px;
              padding: 16px 20px;
              color: var(--text-primary);
            }
            .ud-cabecalho {
              display: flex;
              align-items: baseline;
              justify-content: space-between;
              gap: 12px;
              border-bottom: 1px solid var(--border-color);
              padding-bottom: 8px;
            }
            .ud-cabecalho h2 {
              margin: 0;
              font-size: 18px;
              font-weight: 800;
            }
            .ud-origem {
              font-size: 11px;
              color: var(--text-secondary);
            }
            .ud-mensagem {
              display: flex;
              justify-content: space-between;
              align-items: center;
              gap: 12px;
              padding: 8px 12px;
              border: 1px solid var(--border-color);
              background: var(--bg-subtle);
              font-size: 13px;
            }
            .ud-mensagem.ud-erro {
              border-color: var(--destructive, #dc2626);
              color: var(--destructive, #dc2626);
            }
            .ud-fechar {
              background: transparent;
              border: none;
              font-size: 16px;
              cursor: pointer;
              color: inherit;
            }
            .ud-bloco,
            .ud-grupo,
            .ud-pagina {
              display: flex;
              flex-direction: column;
              gap: 12px;
            }
            .ud-grupo {
              border: 1px solid var(--border-color);
              padding: 12px;
              margin: 0;
            }
            .ud-grupo h3,
            .ud-grupo legend {
              margin: 0;
              font-size: 12px;
              font-weight: 800;
              text-transform: uppercase;
              letter-spacing: 0.04em;
              color: var(--text-secondary);
            }
            .ud-linha {
              display: flex;
              flex-wrap: wrap;
              align-items: flex-end;
              gap: 12px;
            }
            .ud-campo {
              display: flex;
              flex-direction: column;
              gap: 4px;
              font-size: 12px;
              min-width: 160px;
            }
            .ud-campo.ud-largo {
              width: 100%;
            }
            .ud-campo > span,
            .ud-rotulo {
              font-size: 12px;
              font-weight: 600;
              color: var(--text-secondary);
            }
            .ud-campo input,
            .ud-campo select,
            .ud-campo textarea {
              height: 32px;
              padding: 4px 8px;
              border: 1px solid var(--border-color);
              border-radius: 0;
              background: var(--bg-surface);
              color: var(--text-primary);
              font: inherit;
              font-size: 13px;
            }
            .ud-campo textarea {
              height: auto;
            }
            .ud-campo input:focus,
            .ud-campo select:focus,
            .ud-campo textarea:focus {
              outline: none;
              border-color: var(--secondary);
              box-shadow: 0 0 0 1px var(--secondary);
            }
            .ud-com-botao {
              display: flex;
              gap: 4px;
            }
            .ud-com-botao input {
              flex: 1;
            }
            .ud-check {
              display: inline-flex;
              align-items: center;
              gap: 6px;
              font-size: 13px;
              cursor: pointer;
            }
            .ud-vgrid {
              border-collapse: collapse;
              font-size: 12px;
              border: 1px solid var(--border-color);
              background: var(--bg-surface);
            }
            .ud-vgrid th,
            .ud-vgrid td {
              padding: 6px 10px;
              border-bottom: 1px solid var(--border-color);
              text-align: left;
            }
            .ud-vgrid th {
              width: 220px;
              color: var(--text-secondary);
              background: var(--table-header-bg, var(--bg-subtle));
            }
            .ud-vgrid .ud-categoria th {
              text-transform: uppercase;
              font-weight: 800;
            }
            .ud-lista {
              margin: 0;
              padding: 8px 12px 8px 28px;
              border: 1px solid var(--border-color);
              font-size: 12px;
              max-height: 240px;
              overflow: auto;
            }
            .ud-assistente {
              display: flex;
              justify-content: flex-end;
              gap: 8px;
              border-top: 1px solid var(--border-color);
              padding-top: 12px;
            }
            .ud-subtitulo {
              margin: 0;
              font-size: 13px;
              color: var(--text-secondary);
            }
            .ud-vazio {
              color: var(--text-secondary);
              font-style: italic;
            }
            .ud-pendente {
              padding: 8px;
              border: 1px dashed var(--warning, #d97706);
              color: var(--text-secondary);
              font-size: 12px;
            }
            .font-mono {
              font-family: 'JetBrains Mono', monospace;
            }
            </style>
            """;
}
