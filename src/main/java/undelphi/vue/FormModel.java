package undelphi.vue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O que a tela Vue gerada espera do backend. A F4 gera os handlers Go a partir daqui, então este é o contrato
 * entre a tela e o servidor:
 * <ul>
 * <li>POST {base}/eventos/{handler} com {form, abas, selecao} → {mensagem?, erro?, form?, dados?, listas?, abas?}</li>
 * <li>GET {base}/dados/{dataset} com os campos do form na query string → linhas</li>
 * </ul>
 */
public final class FormModel {

    /** Evento do Delphi ligado a um componente: btnGerar.OnClick = btnGerarClick. */
    public record Event(String handler, String component, String componentClass, String property) {
    }

    /** Campo editável da tela (vira form.&lt;nome&gt;). kind: texto, numero, data, booleano, opcao, lista. */
    public record Input(String name, String componentClass, String kind, String label) {
    }

    /** Dataset exibido em grade: chave "dmDadosXML.cdsProdutos" e de onde vem a SQL (origin do DfmSql), se houver. */
    public record Dataset(String key, String datasetClass, String sqlOrigin, List<String> fields) {
    }

    public final String resourceName;
    public final String formName;
    public final String caption;
    public final String apiBase;
    public final List<Event> events = new ArrayList<>();
    public final List<Input> inputs = new ArrayList<>();
    public final Map<String, Dataset> datasets = new LinkedHashMap<>();
    public final List<String> lists = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    FormModel(String resourceName, String formName, String caption, String apiBase) {
        this.resourceName = resourceName;
        this.formName = formName;
        this.caption = caption;
        this.apiBase = apiBase;
    }

    void addEvent(Event e) {
        boolean known = events.stream().anyMatch(x -> x.handler().equals(e.handler()) && x.component().equals(e.component())
                && x.property().equals(e.property()));
        if (!known) {
            events.add(e);
        }
    }
}
