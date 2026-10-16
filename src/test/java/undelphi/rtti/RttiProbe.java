package undelphi.rtti;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

import undelphi.pe.PeImage;

/** Sonda manual (não é teste): imprime o que o RttiReader recupera de um exe. */
public final class RttiProbe {

    public static void main(String[] args) throws Exception {
        PeImage pe = PeImage.read(Path.of(args[0]));
        long t0 = System.currentTimeMillis();
        RttiReader.ClassIndex idx = RttiReader.read(pe);
        System.out.printf("%d classes em %d ms%n", idx.all().size(), System.currentTimeMillis() - t0);
        Map<String, Integer> units = new TreeMap<>();
        idx.all().forEach(c -> units.merge(String.valueOf(c.unitName()), 1, Integer::sum));
        System.out.println("units: " + units.size());
        units.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(40)
                .forEach(e -> System.out.println("  " + e.getKey() + " " + e.getValue()));
        for (String name : args.length > 1 ? args[1].split(",") : new String[] { "TfrmPrincipal", "TdmDadosXML", "TDataSet", "TOraQuery", "TField" }) {
            DelphiClass c = idx.byName(name);
            if (c == null) {
                System.out.println("== " + name + " não encontrada");
                continue;
            }
            System.out.printf("== %s vmt=%08X size=%d parent=%s unit=%s methods=%d fields=%d virtuals=%d%n", c.name(), c.vmt(),
                    c.instanceSize(), c.parent(), c.unitName(), c.methods().size(), c.fields().size(), c.virtualMethods().size());
            c.methods().stream().sorted(Comparator.comparing(DelphiClass.Method::name)).limit(60)
                    .forEach(m -> System.out.printf("   m %-40s %08X %s vi=%d%n", m.name(), m.address(), m.published() ? "pub" : "ext", m.virtualIndex()));
            c.fields().stream().limit(40).forEach(f -> System.out.printf("   f %-40s +%04X %s%n", f.name(), f.offset(), f.typeName()));
        }
    }
}
