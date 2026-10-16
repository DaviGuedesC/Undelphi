package undelphi.rtti;

import java.util.ArrayList;
import java.util.List;

/** Classe Delphi recuperada da VMT e do RTTI do exe. */
public final class DelphiClass {

    /** Método com endereço: published (handlers de evento) ou público com RTTI estendido. */
    public record Method(String name, long address, boolean published, int virtualIndex) {
    }

    /** Campo com offset no objeto: published (componentes do form) ou com RTTI estendido. */
    public record Field(String name, int offset, String typeName) {
    }

    private final String name;
    private final long vmt;
    private final int instanceSize;
    private final long parentVmt;
    private String unitName;
    private DelphiClass parent;
    final List<Method> methods = new ArrayList<>();
    final List<Field> fields = new ArrayList<>();
    final List<Long> virtualMethods = new ArrayList<>();

    DelphiClass(String name, long vmt, int instanceSize, long parentVmt) {
        this.name = name;
        this.vmt = vmt;
        this.instanceSize = instanceSize;
        this.parentVmt = parentVmt;
    }

    public String name() {
        return name;
    }

    /** Endereço da VMT (o valor de uma referência de classe, TClass). */
    public long vmt() {
        return vmt;
    }

    public int instanceSize() {
        return instanceSize;
    }

    long parentVmt() {
        return parentVmt;
    }

    public DelphiClass parent() {
        return parent;
    }

    void setParent(DelphiClass parent) {
        this.parent = parent;
    }

    /** Unit onde a classe foi declarada (do TypeInfo), ou null se a classe não tem TypeInfo. */
    public String unitName() {
        return unitName;
    }

    void setUnitName(String unitName) {
        this.unitName = unitName;
    }

    public List<Method> methods() {
        return methods;
    }

    public List<Field> fields() {
        return fields;
    }

    /** Endereços das entradas virtuais da VMT (índice 0 em diante, depois de Destroy). */
    public List<Long> virtualMethods() {
        return virtualMethods;
    }

    /** Procura o método na classe e nos ancestrais (como o Delphi faz para ligar um evento do DFM). */
    public Method findMethod(String methodName) {
        for (DelphiClass c = this; c != null; c = c.parent) {
            for (Method m : c.methods) {
                if (m.name().equalsIgnoreCase(methodName)) {
                    return m;
                }
            }
        }
        return null;
    }

    /** Campo no offset, procurando na classe e nos ancestrais. */
    public Field fieldAt(int offset) {
        for (DelphiClass c = this; c != null; c = c.parent) {
            for (Field f : c.fields) {
                if (f.offset() == offset) {
                    return f;
                }
            }
        }
        return null;
    }

    /** Todos os campos conhecidos (classe e ancestrais), sem repetir offset. */
    public List<Field> allFields() {
        List<Field> out = new ArrayList<>();
        for (DelphiClass c = this; c != null; c = c.parent) {
            for (Field f : c.fields) {
                if (out.stream().noneMatch(x -> x.offset() == f.offset())) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    public boolean inheritsFrom(String className) {
        for (DelphiClass c = this; c != null; c = c.parent) {
            if (c.name.equalsIgnoreCase(className)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return name + (unitName != null ? " (" + unitName + ")" : "");
    }
}
