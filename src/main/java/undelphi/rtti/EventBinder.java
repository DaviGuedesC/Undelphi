package undelphi.rtti;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import undelphi.dfm.DfmObject;
import undelphi.dfm.DfmValue;
import undelphi.dfm.FormResources;

/**
 * Liga cada evento do DFM (btnGerar.OnClick = btnGerarClick) ao endereço do método na classe do form, como o
 * Delphi faz ao carregar o form: procura o método published pelo nome na classe e nos ancestrais.
 */
public final class EventBinder {

    /**
     * Evento ligado: form (recurso e classe), componente, propriedade do evento e o método.
     *
     * @param address endereço do método, ou 0 se a classe ou o método não foram achados no RTTI
     */
    public record Binding(String formResource, String formClass, String component, String componentClass, String event,
            String method, long address) {

        public boolean resolved() {
            return address != 0;
        }

        /** Nome qualificado: TfrmPrincipal.btnGerarClick. */
        public String qualifiedName() {
            return formClass + "." + method;
        }
    }

    private EventBinder() {
    }

    public static List<Binding> bind(List<FormResources.Form> forms, RttiReader.ClassIndex classes) {
        List<Binding> out = new ArrayList<>();
        for (FormResources.Form f : forms) {
            DfmObject root = f.root();
            DelphiClass cls = classes.byName(root.className());
            root.walk(obj -> {
                for (Map.Entry<String, Object> p : obj.properties().entrySet()) {
                    if (!(p.getValue() instanceof DfmValue.Ident id) || !isEvent(p.getKey())) {
                        continue;
                    }
                    DelphiClass.Method m = cls == null ? null : cls.findMethod(id.name());
                    boolean dup = out.stream().anyMatch(b -> b.formResource().equals(f.resourceName())
                            && b.component().equals(obj.name()) && b.event().equals(p.getKey()));
                    if (!dup) {
                        out.add(new Binding(f.resourceName(), root.className(), obj.name(), obj.className(), p.getKey(),
                                id.name(), m == null ? 0 : m.address()));
                    }
                }
            });
        }
        return out;
    }

    /** "OnClick", "Properties.OnButtonClick"... */
    static boolean isEvent(String property) {
        String last = property.substring(property.lastIndexOf('.') + 1);
        return last.length() > 2 && last.startsWith("On") && Character.isUpperCase(last.charAt(2))
                && !last.toLowerCase(Locale.ROOT).equals("onlyone");
    }
}
