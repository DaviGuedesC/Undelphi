package undelphi.rtti;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import undelphi.dfm.FormResources;
import undelphi.pe.PeImage;

/**
 * Acha as variáveis globais dos forms e data modules (dmDadosXML, frmPrincipal...) pelas chamadas
 * Application.CreateForm(TdmDadosXML, dmDadosXML) do .dpr. Em Win32 o Delphi passa a classe em EDX e o endereço
 * da variável em ECX. Visto no PCNOV9860 (Delphi 10 Seattle):
 *
 * <pre>
 * 8B 0D &lt;p&gt;     mov ecx, [p]      ; p guarda o endereço da variável global
 * 8B 03          mov eax, [ebx]    ; Application
 * 8B 15 &lt;c&gt;     mov edx, [c]      ; c guarda a referência da classe (a VMT)
 * E8 ...         call TApplication.CreateForm
 * </pre>
 *
 * Também aceita as formas diretas "mov edx, VMT" (BA) e "mov ecx, global" (B9).
 */
public final class FormInstances {

    /** Variável global de um form: nome (o do DFM), classe e endereço. */
    public record Instance(String name, String className, long address) {
    }

    private static final int WINDOW = 24;

    private FormInstances() {
    }

    /**
     * Células de dados que guardam o endereço de uma global de form. O código de outras units chega na global por
     * elas: {@code mov eax, [célula]; mov eax, [eax]; mov eax, [eax+offsetDoCampo]}. Célula → instância.
     */
    public static Map<Long, Instance> pointerCells(PeImage pe, Map<String, Instance> globals) {
        Map<Long, Instance> byAddress = new HashMap<>();
        globals.values().forEach(g -> byAddress.put(g.address(), g));
        Map<Long, Instance> out = new LinkedHashMap<>();
        if (byAddress.isEmpty() || pe.is64()) {
            return out;
        }
        for (PeImage.Section s : pe.sections()) {
            String n = s.name().toLowerCase();
            if (!n.equals(".data") && !n.equals("data")) {
                continue;
            }
            int end = Math.min(pe.data().length, s.rawOffset() + s.rawSize()) - 4;
            for (int off = s.rawOffset(); off < end; off += 4) {
                Instance g = byAddress.get(pe.u32(off));
                if (g != null) {
                    out.put(pe.offsetToVa(off), g);
                }
            }
        }
        return out;
    }

    /** Nome da variável → instância. Só para exes 32 bits. */
    public static Map<String, Instance> find(PeImage pe, RttiReader.ClassIndex classes, List<FormResources.Form> forms) {
        Map<String, Instance> out = new LinkedHashMap<>();
        if (pe.is64()) {
            return out;
        }
        Map<Long, FormResources.Form> byVmt = new HashMap<>();
        for (FormResources.Form f : forms) {
            DelphiClass c = classes.byName(f.root().className());
            if (c != null) {
                byVmt.put(c.vmt(), f);
            }
        }
        if (byVmt.isEmpty()) {
            return out;
        }
        byte[] d = pe.data();
        // células de dados que guardam a VMT de um form (referências de classe)
        Map<Long, FormResources.Form> cells = new HashMap<>();
        for (PeImage.Section s : pe.sections()) {
            int end = Math.min(d.length, s.rawOffset() + s.rawSize()) - 4;
            for (int off = s.rawOffset(); off < end; off += 4) {
                FormResources.Form f = byVmt.get(pe.u32(off));
                if (f != null) {
                    cells.put(pe.offsetToVa(off), f);
                }
            }
        }
        // .text e .itext (o código de inicialização do .dpr, onde ficam os CreateForm, vai para a .itext)
        for (PeImage.Section code : pe.sections()) {
            String sn = code.name().toLowerCase();
            if (sn.equals(".text") || sn.equals(".itext") || sn.equals("code")) {
                scanCode(pe, code, byVmt, cells, out);
            }
        }
        return out;
    }

    private static void scanCode(PeImage pe, PeImage.Section text, Map<Long, FormResources.Form> byVmt,
            Map<Long, FormResources.Form> cells, Map<String, Instance> out) {
        byte[] d = pe.data();
        int start = text.rawOffset();
        int end = Math.min(d.length - 6, start + text.rawSize() - 6);
        for (int off = start; off < end; off++) {
            FormResources.Form f = null;
            int b0 = d[off] & 0xFF;
            if (b0 == 0x8B && (d[off + 1] & 0xFF) == 0x15) {
                f = cells.get(pe.u32(off + 2)); // mov edx, [célula]
            } else if (b0 == 0xBA) {
                f = byVmt.get(pe.u32(off + 1)); // mov edx, VMT
            }
            if (f == null) {
                continue;
            }
            long global = nearestEcx(pe, off, start, end);
            if (global != 0 && isData(pe, global)) {
                String name = f.root().name();
                out.putIfAbsent(name, new Instance(name, f.root().className(), global));
            }
        }
    }

    /**
     * Endereço que vai para ECX perto da instrução: "mov ecx, [p]" (desreferencia p) ou "mov ecx, imm". Procura
     * primeiro para trás (o compilador carrega ECX antes de EDX), depois para a frente.
     */
    private static long nearestEcx(PeImage pe, int around, int start, int end) {
        byte[] d = pe.data();
        for (int k = -WINDOW; k <= WINDOW; k++) {
            int delta = k < 0 ? -k : k; // -1..-24, depois 0..24
            if (k >= 0 && delta == 0) {
                continue;
            }
            for (int at : new int[] { k < 0 ? around - delta : around + delta }) {
                if (at < start || at >= end) {
                    continue;
                }
                if ((d[at] & 0xFF) == 0x8B && (d[at + 1] & 0xFF) == 0x0D) {
                    long cell = pe.u32(at + 2);
                    int cellOff = pe.vaToOffset(cell);
                    if (cellOff >= 0 && isData(pe, cell)) {
                        return pe.u32(cellOff);
                    }
                }
                if ((d[at] & 0xFF) == 0xB9) {
                    return pe.u32(at + 1);
                }
            }
        }
        return 0;
    }

    /** Endereço numa seção de dados (onde ficam as variáveis globais). */
    private static boolean isData(PeImage pe, long va) {
        for (PeImage.Section s : pe.sections()) {
            long lo = pe.imageBase() + s.virtualAddress();
            long hi = lo + Math.max(s.virtualSize(), s.rawSize());
            if (va >= lo && va < hi) {
                String n = s.name().toLowerCase();
                return n.equals(".bss") || n.equals(".data") || n.equals("bss") || n.equals("data");
            }
        }
        return false;
    }
}
