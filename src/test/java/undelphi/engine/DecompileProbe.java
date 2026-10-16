package undelphi.engine;

import java.nio.file.Path;
import java.util.List;

import undelphi.model.DelphiProject;
import undelphi.rtti.AppScope;
import undelphi.rtti.EventBinder;

/** Sonda manual: decompila alguns handlers de um form e imprime o C e os fatos. */
public final class DecompileProbe {

    public static void main(String[] args) throws Exception {
        DelphiProject p = DelphiProject.open(Path.of(args[0]));
        AppScope.Structure s = AppScope.read(p.pe(), p.appForms());
        System.out.printf("classes app: %s, literais app: %d (SQL: %d)%n", s.appClasses(), s.literals().size(), s.sqlLiterals().size());
        boolean byCount = args[2].matches("\\d+");
        List<String> names = byCount ? List.of() : List.of(args[2].split(","));
        List<EventBinder.Binding> targets = s.bindings().stream()
                .filter(b -> b.resolved() && b.formResource().equals(args[1]) && (byCount || names.contains(b.method())))
                .collect(java.util.stream.Collectors.toMap(EventBinder.Binding::address, b -> b, (a, b) -> a,
                        java.util.LinkedHashMap::new)).values().stream()
                .limit(byCount ? Integer.parseInt(args[2]) : 1000).toList();
        try (GhidraEngine engine = new GhidraEngine()) {
            System.out.println("células de globais: " + s.globalCells().size());
            DelphiDecompiler.Result r = DelphiDecompiler.decompile(engine, p.exe(), s,
                    targets.stream().map(EventBinder.Binding::address).toList(), 60);
            System.out.printf("labels=%d structs=%d globals=%d literais=%d import=%dms preparo=%dms decompilação=%dms%n",
                    r.labels(), r.structs(), r.globals(), r.literals(), r.importMs(), r.prepareMs(), r.decompileMs());
            for (EventBinder.Binding b : targets) {
                DelphiDecompiler.Decompiled d = r.methods().get(b.address());
                System.out.println("==== " + b.qualifiedName() + " (" + b.component() + "." + b.event() + ") " + d.name());
                System.out.println("calls: " + d.calls());
                System.out.println("strings: " + d.strings());
                System.out.println("globals: " + d.globals());
                System.out.println("virtuais: " + d.virtualCalls());
                System.out.println(d.ok() ? d.code() : "ERRO: " + d.error());
            }
        }
    }
}
