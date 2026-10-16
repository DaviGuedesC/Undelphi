package undelphi.rtti;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import undelphi.dfm.FormResources;
import undelphi.pe.PeImage;
import undelphi.sql.SqlSource;

/**
 * O que é da aplicação (e não das bibliotecas) num exe Delphi: as classes dos forms/data modules da aplicação e as
 * faixas de código dos seus métodos. Os literais de string usados por uma unit ficam logo antes das funções que os
 * usam, então um literal dentro (ou pouco antes) dessas faixas é da aplicação.
 */
public final class AppScope {

    /** Tudo o que a F3 recupera de um exe, reunido para a F4 e para os relatórios. */
    public record Structure(RttiReader.ClassIndex classes, List<EventBinder.Binding> bindings,
            Map<String, FormInstances.Instance> globals, Map<Long, FormInstances.Instance> globalCells,
            List<StringLiterals.Literal> literals, Set<String> appClasses) {

        /** SQL achada em literais do código da aplicação (para o mapa do Oracle). */
        public List<SqlSource> sqlLiterals() {
            List<SqlSource> out = new ArrayList<>();
            for (StringLiterals.Literal l : literals) {
                if (StringLiterals.looksLikeSql(l.text())) {
                    out.add(new SqlSource(String.format("literal@0x%08X", l.address()), "", "", l.text()));
                }
            }
            return out;
        }
    }

    /** Antes do primeiro método de uma classe ainda pode haver constantes da unit. */
    private static final long BEFORE = 0x8000;
    private static final long AFTER = 0x800;

    private AppScope() {
    }

    /** Lê RTTI, liga eventos, acha as globais dos forms e os literais da aplicação. */
    public static Structure read(PeImage pe, List<FormResources.Form> appForms) {
        RttiReader.ClassIndex classes = RttiReader.read(pe);
        Set<String> appClasses = new LinkedHashSet<>();
        for (FormResources.Form f : appForms) {
            if (classes.byName(f.root().className()) != null) {
                appClasses.add(classes.byName(f.root().className()).name());
            }
        }
        List<long[]> ranges = new ArrayList<>();
        for (String name : appClasses) {
            DelphiClass c = classes.byName(name);
            long lo = Long.MAX_VALUE;
            long hi = 0;
            for (DelphiClass.Method m : c.methods()) {
                lo = Math.min(lo, m.address());
                hi = Math.max(hi, m.address());
            }
            if (hi > 0) {
                ranges.add(new long[] { lo - BEFORE, hi + AFTER });
            }
        }
        List<StringLiterals.Literal> literals = new ArrayList<>();
        for (StringLiterals.Literal l : StringLiterals.scan(pe)) {
            for (long[] r : ranges) {
                if (l.address() >= r[0] && l.address() <= r[1]) {
                    literals.add(l);
                    break;
                }
            }
        }
        Map<String, FormInstances.Instance> globals = FormInstances.find(pe, classes, appForms);
        return new Structure(classes, EventBinder.bind(appForms, classes), globals, FormInstances.pointerCells(pe, globals),
                literals, appClasses);
    }
}
