package undelphi.rtti;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import undelphi.pe.PeImage;

/**
 * Recupera as classes de um exe Delphi (2009 em diante) varrendo as VMTs: cada VMT começa com vmtSelfPtr, um ponteiro
 * para o próprio início das entradas virtuais. Daí saem nome, ancestral, tamanho da instância, unit (pelo TypeInfo),
 * métodos published e estendidos, e campos published e estendidos.
 */
public final class RttiReader {

    // Slots negativos da VMT (Delphi 2009+), em unidades de ponteiro, contando a partir de vmtSelfPtr
    private static final int SLOT_TYPEINFO = 4;
    private static final int SLOT_FIELDTABLE = 5;
    private static final int SLOT_METHODTABLE = 6;
    private static final int SLOT_CLASSNAME = 8;
    private static final int SLOT_INSTANCESIZE = 9;
    private static final int SLOT_PARENT = 10;
    private static final int HEADER_SLOTS = 22;

    private static final int TK_CLASS = 7;

    private final PeImage pe;
    private final int p;
    private final Map<Long, DelphiClass> byVmt = new LinkedHashMap<>();
    private final Map<String, DelphiClass> byName = new HashMap<>();
    private final Map<Long, String> typeNames = new HashMap<>();

    private RttiReader(PeImage pe) {
        this.pe = pe;
        this.p = pe.pointerSize();
    }

    /** Varre o exe e devolve as classes encontradas, já ligadas aos ancestrais. */
    public static ClassIndex read(PeImage pe) {
        RttiReader r = new RttiReader(pe);
        r.scan();
        return new ClassIndex(r.byVmt, r.byName);
    }

    /** Índice das classes recuperadas. */
    public record ClassIndex(Map<Long, DelphiClass> byVmt, Map<String, DelphiClass> byName) {

        public Collection<DelphiClass> all() {
            return byVmt.values();
        }

        public DelphiClass byName(String name) {
            return byName.get(name.toLowerCase(Locale.ROOT));
        }

        /**
         * Método (de qualquer classe) que começa no endereço. Quando várias classes registram o mesmo endereço
         * (herança, getters compartilhados), fica o nome declarado (published/estendido) antes do acessor get_/set_,
         * e a classe mais ancestral (a que declarou o método).
         */
        public Map<Long, String> methodNames() {
            Map<Long, String> out = new HashMap<>();
            Map<Long, int[]> rank = new HashMap<>(); // {acessor? 1:0, profundidade}
            for (DelphiClass c : byVmt.values()) {
                int depth = 0;
                for (DelphiClass a = c.parent(); a != null; a = a.parent()) {
                    depth++;
                }
                for (DelphiClass.Method m : c.methods()) {
                    int[] r = { m.virtualIndex() == -2 ? 1 : 0, depth };
                    int[] old = rank.get(m.address());
                    if (old == null || r[0] < old[0] || (r[0] == old[0] && r[1] < old[1])) {
                        rank.put(m.address(), r);
                        out.put(m.address(), c.name() + "." + m.name());
                    }
                }
            }
            return out;
        }
    }

    private void scan() {
        int headerBytes = HEADER_SLOTS * p;
        for (PeImage.Section s : pe.sections()) {
            int start = s.rawOffset();
            int end = start + s.rawSize() - headerBytes;
            for (int off = start; off < end; off += 4) {
                long va = pe.offsetToVa(off);
                if (pe.pointerAt(off) != va + headerBytes) {
                    continue;
                }
                DelphiClass c = tryClass(off, va + headerBytes);
                if (c != null) {
                    byVmt.put(c.vmt(), c);
                }
            }
        }
        for (DelphiClass c : byVmt.values()) {
            c.setParent(byVmt.get(c.parentVmt()));
            byName.putIfAbsent(c.name().toLowerCase(Locale.ROOT), c);
        }
        for (DelphiClass c : byVmt.values()) {
            readTypeInfo(c);
            readMethods(c);
            readFields(c);
            readVirtuals(c);
        }
        for (DelphiClass c : byVmt.values()) {
            readProperties(c);
        }
    }

    /**
     * Getters/setters das propriedades (TPropInfo do TypeInfo da classe e, no RTTI estendido, as públicas): viram
     * métodos "get_Text"/"set_Text". GetProc/SetProc: 0xFFxxxxxx = campo (sem método), 0xFExxxxxx = método virtual
     * (offset na VMT), senão endereço estático.
     */
    private void readProperties(DelphiClass c) {
        long ti = pe.pointerAt(slot(c, SLOT_TYPEINFO));
        int off = pe.vaToOffset(ti);
        if (p != 4 || off < 0 || pe.u8(off) != TK_CLASS) {
            return;
        }
        try {
            int data = off + 2 + pe.u8(off + 1);
            int unit = data + 2 * p + 2;
            int pos = unit + 1 + pe.u8(unit);
            int count = pe.u16(pos);
            pos += 2;
            for (int i = 0; i < count && i < 4096; i++) {
                pos = readPropInfo(c, pos);
                if (pos < 0) {
                    return;
                }
            }
            // PropDataEx (Delphi 2010+): Count e {Flags, PPropInfo, AttrData(len16 + dados)}
            int exCount = pe.u16(pos);
            pos += 2;
            for (int i = 0; i < exCount && i < 4096; i++) {
                long info = pe.pointerAt(pos + 1);
                int infoOff = pe.vaToOffset(info);
                if (infoOff >= 0) {
                    readPropInfo(c, infoOff);
                }
                int attr = pos + 1 + p;
                pos = attr + pe.u16(attr);
            }
        }
        catch (IndexOutOfBoundsException e) {
            // TypeInfo truncado: fica com o que foi lido
        }
    }

    /** Lê um TPropInfo (32 bits) e registra os acessores; devolve o offset do próximo, ou -1. */
    private int readPropInfo(DelphiClass c, int at) {
        long get = pe.u32(at + 4);
        long set = pe.u32(at + 8);
        int nameAt = at + 4 * 6 + 2;
        String name = identifier(nameAt);
        if (name == null) {
            return -1;
        }
        addAccessor(c, "get_" + name, get);
        addAccessor(c, "set_" + name, set);
        return nameAt + 1 + pe.u8(nameAt);
    }

    private void addAccessor(DelphiClass c, String name, long proc) {
        long kind = proc & 0xFF000000L;
        long address;
        if (proc == 0 || kind == 0xFF000000L) {
            return; // sem acessor ou acesso direto ao campo
        }
        if (kind == 0xFE000000L) {
            int slotOffset = (short) (proc & 0xFFFF);
            int index = slotOffset / p;
            if (index < 0 || index >= c.virtualMethods.size()) {
                return;
            }
            address = c.virtualMethods.get(index);
        } else {
            address = proc;
        }
        long target = address;
        if (pe.vaToOffset(target) >= 0 && c.methods.stream().noneMatch(m -> m.address() == target)) {
            c.methods.add(new DelphiClass.Method(name, target, false, -2));
        }
    }

    private DelphiClass tryClass(int recordOffset, long vmt) {
        long namePtr = pe.pointerAt(recordOffset + SLOT_CLASSNAME * p);
        int nameOff = pe.vaToOffset(namePtr);
        if (nameOff < 0) {
            return null;
        }
        String name = identifier(nameOff);
        if (name == null) {
            return null;
        }
        long size = pe.u32(recordOffset + SLOT_INSTANCESIZE * p);
        if (size < p || size > 16 * 1024 * 1024) {
            return null;
        }
        long parentRef = pe.pointerAt(recordOffset + SLOT_PARENT * p);
        long parentVmt = 0;
        if (parentRef != 0) {
            int parentRefOff = pe.vaToOffset(parentRef);
            if (parentRefOff < 0) {
                return null;
            }
            parentVmt = pe.pointerAt(parentRefOff);
        }
        return new DelphiClass(name, vmt, (int) size, parentVmt);
    }

    private int slot(DelphiClass c, int slot) {
        return pe.vaToOffset(c.vmt() - (long) (HEADER_SLOTS - slot) * p);
    }

    private void readTypeInfo(DelphiClass c) {
        long ti = pe.pointerAt(slot(c, SLOT_TYPEINFO));
        int off = pe.vaToOffset(ti);
        if (off < 0 || pe.u8(off) != TK_CLASS) {
            return;
        }
        int nameLen = pe.u8(off + 1);
        int data = off + 2 + nameLen;
        // TTypeData de tkClass: ClassType, ParentInfo, PropCount (SmallInt), UnitName (ShortString)
        int unit = data + 2 * p + 2;
        String unitName = identifier(unit);
        if (unitName != null) {
            c.setUnitName(unitName);
        }
    }

    private void readMethods(DelphiClass c) {
        long mt = pe.pointerAt(slot(c, SLOT_METHODTABLE));
        int off = pe.vaToOffset(mt);
        if (mt == 0 || off < 0) {
            return;
        }
        try {
            int count = pe.u16(off);
            int pos = off + 2;
            for (int i = 0; i < count; i++) {
                int len = pe.u16(pos);
                long code = pe.pointerAt(pos + 2);
                String name = identifier(pos + 2 + p);
                if (name == null || len < 2 + p + 1) {
                    return;
                }
                c.methods.add(new DelphiClass.Method(name, code, true, -1));
                pos += len;
            }
            // RTTI estendido (Delphi 2010+): ExCount e entradas {PVmtMethodEntry, Flags, VirtualIndex}
            int exCount = pe.u16(pos);
            pos += 2;
            for (int i = 0; i < exCount && i < 10000; i++) {
                long entry = pe.pointerAt(pos);
                int virtualIndex = (short) pe.u16(pos + p + 2);
                pos += p + 4;
                int entryOff = pe.vaToOffset(entry);
                if (entryOff < 0) {
                    continue;
                }
                long code = pe.pointerAt(entryOff + 2);
                String name = identifier(entryOff + 2 + p);
                if (name != null && c.methods.stream().noneMatch(m -> m.address() == code && m.name().equals(name))) {
                    c.methods.add(new DelphiClass.Method(name, code, false, virtualIndex));
                }
            }
        }
        catch (IndexOutOfBoundsException e) {
            // tabela truncada ou falso positivo: fica com o que foi lido
        }
    }

    private void readFields(DelphiClass c) {
        long ft = pe.pointerAt(slot(c, SLOT_FIELDTABLE));
        int off = pe.vaToOffset(ft);
        if (ft == 0 || off < 0) {
            return;
        }
        try {
            int count = pe.u16(off);
            long classTab = pe.pointerAt(off + 2);
            List<String> classTypes = classTable(classTab);
            int pos = off + 2 + p;
            for (int i = 0; i < count; i++) {
                int fieldOffset = (int) pe.u32(pos);
                int typeIndex = pe.u16(pos + 4);
                String name = identifier(pos + 6);
                if (name == null) {
                    return;
                }
                String type = typeIndex < classTypes.size() ? classTypes.get(typeIndex) : null;
                c.fields.add(new DelphiClass.Field(name, fieldOffset, type));
                pos += 6 + 1 + pe.u8(pos + 6);
            }
            // campos estendidos: ExCount e {Flags, TypeRef, Offset, Name, AttrData}
            int exCount = pe.u16(pos);
            pos += 2;
            for (int i = 0; i < exCount && i < 10000; i++) {
                long typeRef = pe.pointerAt(pos + 1);
                int fieldOffset = (int) pe.u32(pos + 1 + p);
                int nameAt = pos + 1 + p + 4;
                String name = identifier(nameAt);
                if (name == null) {
                    return;
                }
                int attr = nameAt + 1 + pe.u8(nameAt);
                pos = attr + pe.u16(attr);
                if (c.fields.stream().noneMatch(f -> f.offset() == fieldOffset)) {
                    c.fields.add(new DelphiClass.Field(name, fieldOffset, typeName(typeRef)));
                }
            }
        }
        catch (IndexOutOfBoundsException e) {
            // idem
        }
    }

    private List<String> classTable(long classTab) {
        List<String> out = new ArrayList<>();
        int off = pe.vaToOffset(classTab);
        if (classTab == 0 || off < 0) {
            return out;
        }
        int count = pe.u16(off);
        for (int i = 0; i < count && i < 4096; i++) {
            long ref = pe.pointerAt(off + 2 + i * p);
            int refOff = pe.vaToOffset(ref);
            long vmt = refOff >= 0 ? pe.pointerAt(refOff) : 0;
            out.add(className(vmt));
        }
        return out;
    }

    private String className(long vmt) {
        DelphiClass c = byVmt.get(vmt);
        if (c != null) {
            return c.name();
        }
        int nameSlot = pe.vaToOffset(vmt - (long) (HEADER_SLOTS - SLOT_CLASSNAME) * p);
        if (nameSlot < 0) {
            return null;
        }
        int nameOff = pe.vaToOffset(pe.pointerAt(nameSlot));
        return nameOff < 0 ? null : identifier(nameOff);
    }

    /** Nome do tipo a partir de um PPTypeInfo (ponteiro para ponteiro para TTypeInfo). */
    private String typeName(long ppTypeInfo) {
        return typeNames.computeIfAbsent(ppTypeInfo, k -> {
            int off = pe.vaToOffset(k);
            if (off < 0) {
                return null;
            }
            int ti = pe.vaToOffset(pe.pointerAt(off));
            return ti < 0 ? null : identifier(ti + 1);
        });
    }

    private void readVirtuals(DelphiClass c) {
        // entradas virtuais vão até o início de outra estrutura: paramos no primeiro valor que não é código
        int off = pe.vaToOffset(c.vmt());
        if (off < 0) {
            return;
        }
        PeImage.Section text = pe.section(".text");
        if (text == null) {
            return;
        }
        long lo = pe.imageBase() + text.virtualAddress();
        long hi = lo + text.virtualSize();
        for (int i = 0; i < 2048; i++) {
            int at = off + i * p;
            if (at + p > pe.data().length) {
                break;
            }
            long target = pe.pointerAt(at);
            if (target < lo || target >= hi || byVmt.containsKey(target + (long) HEADER_SLOTS * p)) {
                break;
            }
            c.virtualMethods.add(target);
        }
    }

    /** ShortString com cara de identificador Delphi (letras, dígitos, _, ., &lt;, &gt;, @, vírgula), ou null. */
    private String identifier(int off) {
        if (off < 0 || off >= pe.data().length) {
            return null;
        }
        int len = pe.u8(off);
        if (len == 0 || len > 200 || off + 1 + len > pe.data().length) {
            return null;
        }
        byte first = pe.data()[off + 1];
        if (!(Character.isLetter(first) || first == '_' || first == '@')) {
            return null;
        }
        for (int i = 1; i <= len; i++) {
            int ch = pe.data()[off + i] & 0xFF;
            boolean ok = ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '_' || ch == '.'
                    || ch == '<' || ch == '>' || ch == '@' || ch == ',' || ch == ' ' || ch >= 0x80;
            if (!ok) {
                return null;
            }
        }
        return new String(pe.data(), off + 1, len, StandardCharsets.UTF_8);
    }
}
