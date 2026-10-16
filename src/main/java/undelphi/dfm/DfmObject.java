package undelphi.dfm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Um objeto de um DFM (form, componente ou item filho) com suas propriedades e filhos. */
public final class DfmObject {

    public static final int FF_INHERITED = 1;
    public static final int FF_CHILD_POS = 2;
    public static final int FF_INLINE = 4;

    private final String className;
    private final String name;
    private final int flags;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<DfmObject> children = new ArrayList<>();
    private DfmObject parent;

    public DfmObject(String className, String name, int flags) {
        this.className = className;
        this.name = name;
        this.flags = flags;
    }

    public String className() {
        return className;
    }

    public String name() {
        return name;
    }

    public int flags() {
        return flags;
    }

    public boolean inherited() {
        return (flags & FF_INHERITED) != 0;
    }

    public boolean inline() {
        return (flags & FF_INLINE) != 0;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public List<DfmObject> children() {
        return children;
    }

    public DfmObject parent() {
        return parent;
    }

    void addChild(DfmObject child) {
        child.parent = this;
        children.add(child);
    }

    public Object get(String property) {
        return properties.get(property);
    }

    /** Valor como texto: strings, identificadores e números; null se ausente. */
    public String text(String property) {
        Object v = properties.get(property);
        if (v == null) {
            return null;
        }
        if (v instanceof DfmValue.Ident id) {
            return id.name();
        }
        return v.toString();
    }

    public int integer(String property, int fallback) {
        return properties.get(property) instanceof Long l ? l.intValue() : fallback;
    }

    public boolean bool(String property, boolean fallback) {
        return properties.get(property) instanceof Boolean b ? b : fallback;
    }

    /** Linhas de uma propriedade TStrings (ex.: "SQL.Strings"), juntas por quebra de linha; null se ausente. */
    public String lines(String property) {
        if (!(properties.get(property) instanceof List<?> list)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object line : list) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /** Caminho "Form.Painel.Componente" a partir da raiz. */
    public String path() {
        return parent == null ? name : parent.path() + "." + name;
    }

    public DfmObject root() {
        return parent == null ? this : parent.root();
    }

    /** Visita este objeto e todos os descendentes (pré-ordem). */
    public void walk(Consumer<DfmObject> visitor) {
        visitor.accept(this);
        for (DfmObject child : children) {
            child.walk(visitor);
        }
    }

    public DfmObject find(String componentName) {
        if (name.equalsIgnoreCase(componentName)) {
            return this;
        }
        for (DfmObject child : children) {
            DfmObject found = child.find(componentName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name + ": " + className;
    }
}
