package undelphi.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.data.CategoryPath;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.TerminatedStringDataType;
import ghidra.program.model.data.TerminatedUnicodeDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Function.FunctionUpdateType;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.ParameterImpl;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.ReturnParameterImpl;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Namespace;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.util.task.TaskMonitor;
import undelphi.rtti.AppScope;
import undelphi.rtti.DelphiClass;
import undelphi.rtti.FormInstances;
import undelphi.rtti.RttiReader;
import undelphi.rtti.StringLiterals;

/**
 * Decompila métodos Delphi no Ghidra embutido depois de aplicar o que o RTTI recuperou: nomes Classe::método em
 * todos os métodos conhecidos, uma struct por classe da aplicação (componentes nos offsets certos), as variáveis
 * globais dos forms tipadas e os literais de string. Devolve o C de cada método e fatos tirados direto das
 * instruções (métodos chamados, literais e globais usados), que a F4 usa para gerar o Go.
 */
public final class DelphiDecompiler {

    /** Um método decompilado. calls/strings/globals estão na ordem em que aparecem no código. */
    public record Decompiled(long address, String name, String code, List<String> calls, List<String> strings,
            List<String> globals, List<String> virtualCalls, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    /** Resultado: método → decompilação, e números do preparo (para o relatório). */
    public record Result(Map<Long, Decompiled> methods, int labels, int structs, int globals, int literals, long importMs,
            long prepareMs, long decompileMs) {
    }

    private static final CategoryPath CATEGORY = new CategoryPath("/Delphi");

    private final Program program;
    private final AddressSpace space;
    private final Memory memory;
    private final Map<String, DataType> structs = new HashMap<>();
    private final Map<Long, String> literalText = new HashMap<>();
    private final Map<Long, String> globalNames = new HashMap<>();
    private final Map<Long, DelphiClass> ownerOf = new HashMap<>();
    private RttiReader.ClassIndex classes;
    private final Map<String, String> globalType = new HashMap<>();
    private Map<Long, String> methodNames = Map.of();

    private DelphiDecompiler(Program program) {
        this.program = program;
        this.space = program.getAddressFactory().getDefaultAddressSpace();
        this.memory = program.getMemory();
    }

    /**
     * @param s       o que a F3 recuperou do exe (classes, globais dos forms e suas células, literais da aplicação)
     * @param targets endereços dos métodos a decompilar (os handlers de evento)
     */
    public static Result decompile(GhidraEngine engine, java.nio.file.Path exe, AppScope.Structure s,
            Collection<Long> targets, int timeoutSeconds) throws Exception {
        RttiReader.ClassIndex classes = s.classes();
        Map<String, FormInstances.Instance> globals = s.globals();
        Object consumer = new Object();
        long t0 = System.currentTimeMillis();
        Program program = engine.importProgram(exe, consumer);
        long importMs = System.currentTimeMillis() - t0;
        try {
            DelphiDecompiler d = new DelphiDecompiler(program);
            d.classes = classes;
            d.methodNames = classes.methodNames();
            globals.values().forEach(g -> d.globalType.put(g.name(), g.className()));
            s.globalCells().values().forEach(g -> d.globalType.put("ref_" + g.name(), g.className()));
            long t1 = System.currentTimeMillis();
            int tx = program.startTransaction("undelphi: RTTI");
            int labels;
            int structCount;
            Map<Long, Function> functions;
            try {
                labels = d.applyMethodNames(classes);
                structCount = d.applyStructs(classes, s.appClasses());
                d.applyGlobals(globals);
                d.applyGlobalCells(s.globalCells());
                d.applyLiterals(s.literals());
                functions = d.createFunctions(targets);
                program.endTransaction(tx, true);
            }
            catch (Exception e) {
                program.endTransaction(tx, false);
                throw e;
            }
            long prepareMs = System.currentTimeMillis() - t1;

            long t2 = System.currentTimeMillis();
            Map<Long, Decompiled> out = new LinkedHashMap<>();
            DecompInterface ifc = new DecompInterface();
            try {
                ifc.setOptions(new DecompileOptions());
                ifc.toggleCCode(true);
                if (!ifc.openProgram(program)) {
                    throw new IllegalStateException("decompilador do Ghidra não abriu: " + ifc.getLastMessage());
                }
                for (long t : targets) {
                    Function f = functions.get(t);
                    if (f == null) {
                        out.put(t, new Decompiled(t, null, null, List.of(), List.of(), List.of(), List.of(), "função não criada no endereço"));
                        continue;
                    }
                    out.put(t, d.decompileOne(ifc, f, timeoutSeconds));
                }
            }
            finally {
                ifc.dispose();
            }
            return new Result(out, labels, structCount, globals.size(), d.literalText.size(), importMs, prepareMs,
                    System.currentTimeMillis() - t2);
        }
        finally {
            program.release(consumer);
        }
    }

    private Address addr(long va) {
        Address a = space.getAddress(va);
        return memory.contains(a) ? a : null;
    }

    /**
     * Classe::método em todos os endereços de método do RTTI, com o melhor nome por endereço (o declarado antes do
     * acessor get_/set_, e a classe mais ancestral), para getters compartilhados não saírem com a classe errada.
     */
    private int applyMethodNames(RttiReader.ClassIndex classes) {
        SymbolTable st = program.getSymbolTable();
        for (DelphiClass c : classes.all()) {
            for (DelphiClass.Method m : c.methods()) {
                if (m.virtualIndex() != -2) {
                    ownerOf.putIfAbsent(m.address(), c);
                }
            }
        }
        Map<String, Namespace> namespaces = new HashMap<>();
        int n = 0;
        for (Map.Entry<Long, String> e : methodNames.entrySet()) {
            Address a = addr(e.getKey());
            String full = e.getValue();
            int dot = full.lastIndexOf('.');
            if (a == null || dot <= 0) {
                continue;
            }
            String cls = ghidraName(full.substring(0, dot));
            try {
                Namespace ns = namespaces.get(cls);
                if (ns == null) {
                    ns = st.getOrCreateNameSpace(program.getGlobalNamespace(), cls, SourceType.IMPORTED);
                    namespaces.put(cls, ns);
                }
                st.createLabel(a, ghidraName(full.substring(dot + 1)), ns, SourceType.IMPORTED);
                n++;
            }
            catch (Exception ex) {
                // nome inválido para o Ghidra: fica sem
            }
        }
        return n;
    }

    private static String ghidraName(String delphi) {
        return delphi.replace(' ', '_').replace(',', '_');
    }

    /** Struct por classe da aplicação, com os campos published/estendidos nos offsets. */
    private int applyStructs(RttiReader.ClassIndex classes, Collection<String> appClasses) {
        DataTypeManager dtm = program.getDataTypeManager();
        int n = 0;
        for (String name : appClasses) {
            DelphiClass c = classes.byName(name);
            if (c == null || c.instanceSize() <= 0) {
                continue;
            }
            StructureDataType s = new StructureDataType(CATEGORY, c.name(), c.instanceSize(), dtm);
            int ptr = program.getDefaultPointerSize();
            for (DelphiClass.Field f : c.allFields()) {
                if (f.offset() < ptr || f.offset() + ptr > c.instanceSize()) {
                    continue;
                }
                try {
                    s.replaceAtOffset(f.offset(), new PointerDataType(placeholder(f.typeName(), dtm), dtm), ptr, f.name(), null);
                }
                catch (IllegalArgumentException e) {
                    // campo sobreposto: fica o primeiro
                }
            }
            structs.put(c.name(), dtm.addDataType(s, DataTypeConflictHandler.REPLACE_HANDLER));
            n++;
        }
        return n;
    }

    /** Struct vazia com o nome da classe do componente (ex. TOraQuery), só para o tipo aparecer no C. */
    private DataType placeholder(String typeName, DataTypeManager dtm) {
        String name = typeName == null || typeName.isBlank() ? "TObject" : ghidraName(typeName);
        DataType known = structs.get(name);
        if (known != null) {
            return known;
        }
        DataType dt = dtm.getDataType(CATEGORY, name);
        if (dt == null) {
            dt = dtm.addDataType(new StructureDataType(CATEGORY, name, program.getDefaultPointerSize(), dtm),
                    DataTypeConflictHandler.KEEP_HANDLER);
        }
        return dt;
    }

    private void applyGlobals(Map<String, FormInstances.Instance> globals) {
        SymbolTable st = program.getSymbolTable();
        Listing listing = program.getListing();
        DataTypeManager dtm = program.getDataTypeManager();
        for (FormInstances.Instance g : globals.values()) {
            Address a = addr(g.address());
            if (a == null) {
                continue;
            }
            globalNames.put(g.address(), g.name());
            try {
                st.createLabel(a, g.name(), SourceType.IMPORTED);
                DataType target = structs.get(g.className());
                listing.clearCodeUnits(a, a.add(program.getDefaultPointerSize() - 1), false);
                listing.createData(a, new PointerDataType(target != null ? target : placeholder(g.className(), dtm), dtm));
            }
            catch (Exception e) {
                // já havia dado definido: mantém o rótulo
            }
        }
    }

    /** Células que apontam para as globais: ref_dmDadosXML, do tipo TdmDadosXML** (o C mostra (*ref_dmDadosXML)->qryX). */
    private void applyGlobalCells(Map<Long, FormInstances.Instance> cells) {
        SymbolTable st = program.getSymbolTable();
        Listing listing = program.getListing();
        DataTypeManager dtm = program.getDataTypeManager();
        cells.forEach((cell, g) -> {
            Address a = addr(cell);
            if (a == null) {
                return;
            }
            globalNames.put(cell, g.name());
            try {
                st.createLabel(a, "ref_" + g.name(), SourceType.IMPORTED);
                DataType target = structs.get(g.className());
                DataType inner = new PointerDataType(target != null ? target : placeholder(g.className(), dtm), dtm);
                listing.clearCodeUnits(a, a.add(program.getDefaultPointerSize() - 1), false);
                listing.createData(a, new PointerDataType(inner, dtm));
            }
            catch (Exception e) {
                // mantém o que havia
            }
        });
    }

    private void applyLiterals(List<StringLiterals.Literal> literals) {
        Listing listing = program.getListing();
        for (StringLiterals.Literal l : literals) {
            literalText.put(l.address(), l.text());
            Address a = addr(l.address());
            if (a == null) {
                continue;
            }
            try {
                listing.createData(a, l.unicode() ? new TerminatedUnicodeDataType() : new TerminatedStringDataType());
            }
            catch (Exception e) {
                // sobreposto a outro dado: ignora
            }
        }
    }

    /** Desmonta e cria as funções dos alvos e das funções que eles chamam diretamente. */
    private Map<Long, Function> createFunctions(Collection<Long> targets) throws Exception {
        Map<Long, Function> out = new HashMap<>();
        Set<Address> callees = new LinkedHashSet<>();
        for (long t : targets) {
            Function f = createFunction(addr(t));
            if (f == null) {
                continue;
            }
            out.put(t, f);
            typeThis(f, t);
            for (Instruction ins : program.getListing().getInstructions(f.getBody(), true)) {
                if (ins.getFlowType().isCall()) {
                    for (Address flow : ins.getFlows()) {
                        callees.add(flow);
                    }
                }
            }
        }
        for (Address c : callees) {
            if (program.getFunctionManager().getFunctionAt(c) == null) {
                createFunction(c);
            }
        }
        return out;
    }

    private Function createFunction(Address a) {
        if (a == null) {
            return null;
        }
        Function existing = program.getFunctionManager().getFunctionAt(a);
        if (existing != null) {
            return existing;
        }
        new DisassembleCommand(a, null, true).applyTo(program, TaskMonitor.DUMMY);
        new CreateFunctionCmd(a).applyTo(program, TaskMonitor.DUMMY);
        return program.getFunctionManager().getFunctionAt(a);
    }

    /** this = ponteiro para a struct da classe dona do método; Sender = TObject*. Convenção register do Delphi. */
    private void typeThis(Function f, long address) {
        DelphiClass owner = ownerOf.get(address);
        DataType self = owner == null ? null : structs.get(owner.name());
        if (self == null) {
            return;
        }
        DataTypeManager dtm = program.getDataTypeManager();
        try {
            f.updateFunction("__register", new ReturnParameterImpl(VoidDataType.dataType, program),
                    FunctionUpdateType.DYNAMIC_STORAGE_ALL_PARAMS, true, SourceType.IMPORTED,
                    new ParameterImpl("this", new PointerDataType(self, dtm), program),
                    new ParameterImpl("Sender", new PointerDataType(placeholder("TObject", dtm), dtm), program));
        }
        catch (Exception e) {
            // assinatura não aplicada: o decompilador infere
        }
    }

    private Decompiled decompileOne(DecompInterface ifc, Function f, int timeoutSeconds) {
        long entry = f.getEntryPoint().getOffset();
        String name = f.getName(true);
        List<String> calls = new ArrayList<>();
        List<String> strings = new ArrayList<>();
        List<String> globalsUsed = new ArrayList<>();
        for (Instruction ins : program.getListing().getInstructions(f.getBody(), true)) {
            if (ins.getFlowType().isCall()) {
                for (Address flow : ins.getFlows()) {
                    calls.add(nameAt(flow));
                }
            }
            for (int op = 0; op < ins.getNumOperands(); op++) {
                for (Object o : ins.getOpObjects(op)) {
                    long v = o instanceof Scalar s ? s.getUnsignedValue() : o instanceof Address ad ? ad.getOffset() : -1;
                    if (v <= 0) {
                        continue;
                    }
                    String lit = literalText.get(v);
                    if (lit != null) {
                        strings.add(lit);
                    }
                    String g = globalNames.get(v);
                    if (g != null && !globalsUsed.contains(g)) {
                        globalsUsed.add(g);
                    }
                }
            }
        }
        DecompileResults r = ifc.decompileFunction(f, timeoutSeconds, TaskMonitor.DUMMY);
        if (r == null || !r.decompileCompleted() || r.getDecompiledFunction() == null) {
            return new Decompiled(entry, name, null, calls, strings, globalsUsed, List.of(),
                    r == null ? "sem resultado" : String.valueOf(r.getErrorMessage()).strip());
        }
        List<VirtualCalls.Resolved> virtuals = new ArrayList<>();
        String code = VirtualCalls.resolve(r.getDecompiledFunction().getC(), ownerOf.get(entry), classes, globalType,
                methodNames, virtuals);
        return new Decompiled(entry, name, code, calls, strings, globalsUsed,
                virtuals.stream().map(v -> v.method() + "(" + v.receiver() + ")").toList(), null);
    }

    private String nameAt(Address a) {
        Function f = program.getFunctionManager().getFunctionAt(a);
        if (f != null && !f.getName().startsWith("FUN_")) {
            return f.getName(true);
        }
        Symbol s = program.getSymbolTable().getPrimarySymbol(a);
        if (s != null && s.getSource() != SourceType.DEFAULT) {
            return s.getName(true);
        }
        return String.format("FUN_%08x", a.getOffset());
    }
}
