package undelphi.pe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Leitura direta de um executável PE (32 ou 64 bits): seções, conversão de endereços e recursos.
 * Não depende do Ghidra, para que forms, SQL e RTTI saiam rápido e de forma determinística.
 */
public final class PeImage {

    public static final int RT_RCDATA = 10;

    public record Section(String name, long virtualAddress, long virtualSize, int rawOffset, int rawSize) {
        boolean containsRva(long rva) {
            return rva >= virtualAddress && rva < virtualAddress + Math.max(virtualSize, rawSize);
        }
    }

    /** Recurso: tipo e nome podem ser numéricos (id) ou texto. */
    public record Resource(String type, String name, int language, int offset, int size) {
    }

    private final byte[] data;
    private final boolean pe64;
    private final long imageBase;
    private final List<Section> sections = new ArrayList<>();
    private final long resourceRva;

    private PeImage(byte[] data) {
        this.data = data;
        if (data.length < 0x40 || data[0] != 'M' || data[1] != 'Z') {
            throw new IllegalArgumentException("não é um executável PE (sem MZ)");
        }
        int pe = i32(0x3C);
        if (pe <= 0 || pe + 24 > data.length || data[pe] != 'P' || data[pe + 1] != 'E') {
            throw new IllegalArgumentException("não é um executável PE (sem assinatura PE)");
        }
        int sectionCount = u16(pe + 6);
        int optSize = u16(pe + 20);
        int opt = pe + 24;
        int magic = u16(opt);
        pe64 = magic == 0x20B;
        imageBase = pe64 ? i64(opt + 24) : u32(opt + 28);
        int dirs = pe64 ? opt + 112 : opt + 96;
        int dirCount = (int) u32(pe64 ? opt + 108 : opt + 92);
        resourceRva = dirCount > 2 ? u32(dirs + 2 * 8) : 0;

        int sec = opt + optSize;
        for (int i = 0; i < sectionCount; i++, sec += 40) {
            String name = new String(data, sec, 8, StandardCharsets.ISO_8859_1).replace("\0", "");
            sections.add(new Section(name, u32(sec + 12), u32(sec + 8), (int) u32(sec + 20), (int) u32(sec + 16)));
        }
    }

    public static PeImage read(Path exe) throws IOException {
        return new PeImage(Files.readAllBytes(exe));
    }

    public static PeImage of(byte[] data) {
        return new PeImage(data);
    }

    public byte[] data() {
        return data;
    }

    public boolean is64() {
        return pe64;
    }

    public long imageBase() {
        return imageBase;
    }

    public List<Section> sections() {
        return sections;
    }

    public Section section(String name) {
        return sections.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    /** Offset no arquivo de um RVA, ou -1 se o RVA não estiver em nenhuma seção com dados. */
    public int rvaToOffset(long rva) {
        for (Section s : sections) {
            if (s.containsRva(rva)) {
                long delta = rva - s.virtualAddress();
                return delta < s.rawSize() ? (int) (s.rawOffset() + delta) : -1;
            }
        }
        return -1;
    }

    /** Offset no arquivo de um endereço virtual (image base + RVA), ou -1. */
    public int vaToOffset(long va) {
        return va < imageBase ? -1 : rvaToOffset(va - imageBase);
    }

    /** Endereço virtual de um offset do arquivo, ou -1. */
    public long offsetToVa(int offset) {
        for (Section s : sections) {
            if (offset >= s.rawOffset() && offset < s.rawOffset() + s.rawSize()) {
                return imageBase + s.virtualAddress() + (offset - s.rawOffset());
            }
        }
        return -1;
    }

    /** Lê um ponteiro (4 ou 8 bytes) no offset e devolve o endereço virtual apontado. */
    public long pointerAt(int offset) {
        return pe64 ? i64(offset) : u32(offset);
    }

    public int pointerSize() {
        return pe64 ? 8 : 4;
    }

    /** Todos os recursos (tipo → nome → idioma). */
    public List<Resource> resources() {
        List<Resource> out = new ArrayList<>();
        int root = rvaToOffset(resourceRva);
        if (resourceRva == 0 || root < 0) {
            return out;
        }
        for (int[] type : entries(root, root)) {
            String typeName = entryName(root, type[0]);
            for (int[] name : entries(root, root + (type[1] & 0x7FFFFFFF))) {
                String resName = entryName(root, name[0]);
                for (int[] lang : entries(root, root + (name[1] & 0x7FFFFFFF))) {
                    int entry = root + lang[1];
                    int dataOffset = rvaToOffset(u32(entry));
                    if (dataOffset >= 0) {
                        out.add(new Resource(typeName, resName, lang[0], dataOffset, (int) u32(entry + 4)));
                    }
                }
            }
        }
        return out;
    }

    /** Recursos RCDATA cujo conteúdo começa com a assinatura de DFM binário (TPF0). */
    public List<Resource> dfmResources() {
        return resources().stream()
                .filter(r -> r.type().equals(String.valueOf(RT_RCDATA)))
                .filter(r -> r.size() > 4 && data[r.offset()] == 'T' && data[r.offset() + 1] == 'P'
                        && data[r.offset() + 2] == 'F' && data[r.offset() + 3] == '0')
                .toList();
    }

    /** Pares {nome/id, offsetParaDados} das entradas de um diretório de recursos. */
    private List<int[]> entries(int root, int dir) {
        List<int[]> out = new ArrayList<>();
        int count = u16(dir + 12) + u16(dir + 14);
        for (int i = 0; i < count; i++) {
            int e = dir + 16 + i * 8;
            out.add(new int[] { (int) u32(e), (int) u32(e + 4) });
        }
        return out;
    }

    private String entryName(int root, int raw) {
        if ((raw & 0x80000000) == 0) {
            return String.valueOf(raw);
        }
        int off = root + (raw & 0x7FFFFFFF);
        int len = u16(off);
        return new String(data, off + 2, len * 2, StandardCharsets.UTF_16LE);
    }

    public int u8(int off) {
        return data[off] & 0xFF;
    }

    public int u16(int off) {
        return (data[off] & 0xFF) | (data[off + 1] & 0xFF) << 8;
    }

    public long u32(int off) {
        return (u16(off) | (long) u16(off + 2) << 16) & 0xFFFFFFFFL;
    }

    public int i32(int off) {
        return (int) u32(off);
    }

    public long i64(int off) {
        return u32(off) | u32(off + 4) << 32;
    }

    /** ShortString do Delphi (1 byte de tamanho + texto) no offset. */
    public String shortString(int off) {
        int len = u8(off);
        return new String(data, off + 1, len, StandardCharsets.UTF_8);
    }
}
