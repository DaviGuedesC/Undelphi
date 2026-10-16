package undelphi.lift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o C decompilado de um handler (já com os nomes do RTTI aplicados) e extrai as ações que dá para converter:
 * parâmetros de query com o valor vindo de um campo da tela, filtros dinâmicos, abertura/execução de datasets,
 * leitura e escrita de campos, mensagens ao usuário, troca de aba e chamadas a outros métodos do form.
 */
public final class HandlerActions {

    /** Componente referenciado: form (nome da raiz do DFM) e nome do componente. */
    public record Ref(String form, String component) {
        public String key() {
            return form + "." + component;
        }
    }

    public sealed interface Action {
    }

    /** ParamByName(ds, "NOME") := valor; source = campo da tela de onde veio o valor (ou null). */
    public record SetParam(Ref dataset, String param, String setter, Ref source) implements Action {
    }

    /** AddWhere/SetWhere(ds, "condição"): filtro montado em tempo de execução. */
    public record DynamicWhere(Ref dataset, String method, String condition) implements Action {
    }

    /** SQL montada em código: ds.SQL.Clear (clear=true, line=null) ou ds.SQL.Add('linha'). */
    public record SqlText(Ref dataset, String line, boolean clear) implements Action {
    }

    /** Open/ExecSQL/Execute/Refresh/Active... */
    public record Execute(Ref dataset, String method) implements Action {
    }

    public record ReadField(Ref field, String property) implements Action {
    }

    public record WriteField(Ref field, String property) implements Action {
    }

    /** Mensagem ao usuário. kind: aviso, erro, informacao, confirmacao. */
    public record Message(String kind, String text) implements Action {
    }

    /** Troca de aba/página (ActivePage, wizard). */
    public record Navigate(Ref control, String method) implements Action {
    }

    /** Chamada a outro método do próprio form (pode ser outro handler). */
    public record CallOwn(String method) implements Action {
    }

    /** Uso de um componente de outro form/data module (dmDadosXML.qryCliente), com ou sem ação reconhecida. */
    public record Touch(Ref component) implements Action {
    }

    private static final Pattern CALL = Pattern.compile("(\\w+)::(\\w+)\\s*\\(");
    private static final Pattern STRING = Pattern.compile("L\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern REF = Pattern.compile("\\(\\*ref_(\\w+)\\)->(\\w+)|\\bthis->(\\w+)|\\b(dm\\w+|frm\\w+|Frm\\w+)->(\\w+)");
    private static final Pattern EXEC = Pattern.compile(
            "(?i)^(Open|ExecSQL|Execute|ExecProc|Refresh|RefreshRecord|set_Active|ApplyUpdates|Post|Delete|Append|Insert|Edit|First|Close)$");
    private static final Pattern READ = Pattern.compile("^get_(\\w+)$");
    private static final Pattern WRITE = Pattern.compile("^set_(\\w+)$");
    private static final Pattern MESSAGE_CLASS = Pattern.compile("(?i)^(TPCLinguas|TApplication|Dialogs|Vcl_Dialogs)$");
    /** piVar7 = (int *)TCustomDADataSet::get_SQL(ref) — guarda o TStrings da SQL numa variável. */
    private static final Pattern SQL_VAR = Pattern.compile("(\\w+) = (?:\\([^)]*\\))?\\w+::get_SQL\\(");
    /** Chamada virtual sobre essa variável: (**(code **)(*piVar7 + 0x3c))(piVar7,L"...") ou (...)() */
    private static final Pattern VIRTUAL_ON = Pattern.compile("\\(\\*\\*\\(code \\*\\*\\)\\(\\*(\\w+) \\+ 0x[0-9a-f]+\\)\\)\\s*\\(([^;]*)\\)");

    private HandlerActions() {
    }

    /**
     * @param code      C decompilado
     * @param ownerForm nome da raiz do DFM do form dono (ex. frmPrincipal), para resolver this->X
     * @param ownerClass classe do form (ex. TfrmPrincipal), para reconhecer chamadas a métodos próprios
     */
    public static List<Action> extract(String code, String ownerForm, String ownerClass) {
        List<Action> out = new ArrayList<>();
        if (code == null) {
            return out;
        }
        Ref lastRead = null;
        Map<Ref, SetParam> pendingParam = new LinkedHashMap<>();
        SetParam lastParam = null;
        Map<String, Ref> sqlVars = new LinkedHashMap<>();
        java.util.Set<Ref> touched = new java.util.LinkedHashSet<>();
        for (String stmt : statements(code)) {
            if (stmt.endsWith("{") && stmt.contains("::") && !stmt.contains(";")
                    && (stmt.startsWith("void ") || stmt.matches("^\\w[\\w *]* \\**\\w+::\\w+\\(.*"))) {
                continue; // assinatura da própria função
            }
            List<Ref> refs = refs(stmt, ownerForm);
            List<String> strings = strings(stmt);
            for (Ref r : refs) {
                if (!r.form().equals(ownerForm) && touched.add(r)) {
                    out.add(new Touch(r));
                }
            }
            // SQL montada em código: x = get_SQL(ds); x.Clear; x.Add('...')
            Matcher sv = SQL_VAR.matcher(stmt);
            if (sv.find() && !refs.isEmpty()) {
                sqlVars.put(sv.group(1), refs.get(0));
                continue;
            }
            Matcher vo = VIRTUAL_ON.matcher(stmt);
            if (vo.find() && sqlVars.containsKey(vo.group(1))) {
                Ref ds = sqlVars.get(vo.group(1));
                String args = vo.group(2).strip();
                if (args.isEmpty()) {
                    out.add(new SqlText(ds, null, true));
                } else if (!strings.isEmpty()) {
                    out.add(new SqlText(ds, strings.get(0), false));
                }
                continue;
            }
            Matcher m = CALL.matcher(stmt);
            while (m.find()) {
                String cls = m.group(1);
                String method = m.group(2);
                Ref first = refs.isEmpty() ? null : refs.get(0);
                if (method.equals("ParamByName") && first != null && !strings.isEmpty()) {
                    lastParam = new SetParam(first, strings.get(0).toUpperCase(), null, null);
                    pendingParam.put(first, lastParam);
                    continue;
                }
                if (method.startsWith("set_As") && lastParam != null) {
                    SetParam bound = new SetParam(lastParam.dataset(), lastParam.param(), method.substring(4), lastRead);
                    out.add(bound);
                    lastParam = null;
                    continue;
                }
                if ((method.equals("AddWhere") || method.equals("SetWhere") || method.equals("AddOrderBy")) && first != null
                        && !strings.isEmpty()) {
                    out.add(new DynamicWhere(first, method, strings.get(0)));
                    continue;
                }
                if (EXEC.matcher(method).matches() && first != null && !cls.startsWith("TScreen")) {
                    if (method.equals("set_Active") && stmt.contains(",0)")) {
                        continue; // Active := False
                    }
                    out.add(new Execute(first, method));
                    continue;
                }
                if (method.equals("set_ActivePage") || method.equals("set_ActivePageIndex") || method.equals("SelectNextPage")
                        || method.equals("set_TabIndex")) {
                    out.add(new Navigate(first, method));
                    continue;
                }
                Matcher r = READ.matcher(method);
                if (r.matches() && first != null && first.form().equals(ownerForm)) {
                    lastRead = first;
                    out.add(new ReadField(first, r.group(1)));
                    continue;
                }
                Matcher w = WRITE.matcher(method);
                if (w.matches() && first != null && first.form().equals(ownerForm) && !method.startsWith("set_Cursor")) {
                    out.add(new WriteField(first, w.group(1)));
                    continue;
                }
                if (!strings.isEmpty() && (MESSAGE_CLASS.matcher(cls).matches() || method.equals("ShowMessage")
                        || method.equals("MessageDlg") || method.equals("MessageBox"))) {
                    out.add(new Message(kind(method), strings.get(strings.size() - 1)));
                    continue;
                }
                if (cls.equals(ownerClass) && !method.startsWith("get_") && !method.startsWith("set_")) {
                    out.add(new CallOwn(method));
                }
            }
        }
        // parâmetro sem setter reconhecido (valor veio de expressão não convertida)
        for (SetParam p : pendingParam.values()) {
            boolean bound = out.stream().anyMatch(a -> a instanceof SetParam s && s.dataset().equals(p.dataset())
                    && s.param().equals(p.param()));
            if (!bound) {
                out.add(p);
            }
        }
        return out;
    }

    private static String kind(String method) {
        String m = method.toLowerCase();
        if (m.contains("erro") || m.contains("error")) {
            return "erro";
        }
        if (m.contains("confirma") || m.contains("pergunta")) {
            return "confirmacao";
        }
        if (m.contains("informa") || m.contains("info") || m.equals("showmessage")) {
            return "informacao";
        }
        return "aviso";
    }

    /** Junta as linhas de um mesmo comando C (chamadas quebradas em várias linhas). */
    static List<String> statements(String code) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String raw : code.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("/*")) {
                continue;
            }
            cur.append(cur.isEmpty() ? "" : " ").append(line);
            if (line.endsWith(";") || line.endsWith("{") || line.endsWith("}")) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    static List<Ref> refs(String stmt, String ownerForm) {
        List<Ref> out = new ArrayList<>();
        Matcher m = REF.matcher(stmt);
        while (m.find()) {
            if (m.group(1) != null) {
                out.add(new Ref(m.group(1), m.group(2)));
            } else if (m.group(3) != null) {
                out.add(new Ref(ownerForm, m.group(3)));
            } else {
                out.add(new Ref(m.group(4), m.group(5)));
            }
        }
        return out;
    }

    static List<String> strings(String stmt) {
        List<String> out = new ArrayList<>();
        Matcher m = STRING.matcher(stmt);
        while (m.find()) {
            out.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r")
                    .replace("\\t", "\t"));
        }
        return out;
    }
}
