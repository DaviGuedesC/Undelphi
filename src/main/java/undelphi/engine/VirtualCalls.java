package undelphi.engine;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import undelphi.rtti.DelphiClass;
import undelphi.rtti.RttiReader;

/**
 * Troca as chamadas virtuais que o decompilador mostra cruas, como
 * {@code (**(code **)((int)*this->edtChaveBase + 0x104))()}, pelo método da VMT da classe do objeto
 * ({@code TWinControl::SetFocus(this->edtChaveBase)}). O tipo do objeto vem do RTTI: campo do form
 * (this->campo), campo de outro form via global (dmDadosXML->qryX) ou o próprio this.
 */
public final class VirtualCalls {

    /** Uma chamada resolvida: nome do método e a classe onde a VMT foi lida. */
    public record Resolved(String receiver, String method) {
    }

    private static final Pattern CALL = Pattern.compile(
            "\\(\\*\\*\\(code \\*\\*\\)\\(\\(int\\)\\*((?:\\(\\*\\w+\\)|\\w+)(?:->\\w+)?) \\+ 0x([0-9a-f]+)\\)\\)(\\(\\))?");

    private VirtualCalls() {
    }

    /**
     * @param owner      classe dona do método decompilado (o tipo de this)
     * @param globalType nome da global → classe (ex. dmDadosXML → TdmDadosXML)
     */
    public static String resolve(String code, DelphiClass owner, RttiReader.ClassIndex classes,
            Map<String, String> globalType, Map<Long, String> methodNames, List<Resolved> out) {
        Matcher m = CALL.matcher(code);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String receiver = m.group(1);
            int offset = Integer.parseInt(m.group(2), 16);
            DelphiClass cls = typeOf(receiver, owner, classes, globalType);
            String name = null;
            if (cls != null && offset % 4 == 0 && offset / 4 < cls.virtualMethods().size()) {
                long target = cls.virtualMethods().get(offset / 4);
                String known = methodNames.get(target);
                name = known != null ? known.replace(".", "::") : cls.name() + "::virtual_" + m.group(2);
            }
            if (name == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            out.add(new Resolved(receiver, name));
            String replacement = m.group(3) != null ? name + "(" + receiver + ")" : name;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static DelphiClass typeOf(String receiver, DelphiClass owner, RttiReader.ClassIndex classes,
            Map<String, String> globalType) {
        int arrow = receiver.indexOf("->");
        if (arrow < 0) {
            if (receiver.equals("this")) {
                return owner;
            }
            String g = globalType.get(receiver);
            return g == null ? null : classes.byName(g);
        }
        String base = receiver.substring(0, arrow).replace("(*", "").replace(")", "");
        String field = receiver.substring(arrow + 2);
        DelphiClass baseClass = base.equals("this") ? owner
                : globalType.containsKey(base) ? classes.byName(globalType.get(base)) : null;
        if (baseClass == null) {
            return null;
        }
        for (DelphiClass.Field f : baseClass.allFields()) {
            if (f.name().equals(field)) {
                return f.typeName() == null ? null : classes.byName(f.typeName());
            }
        }
        return null;
    }
}
