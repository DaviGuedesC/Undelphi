package undelphi.rtti;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import undelphi.model.DelphiProject;

/** Sonda manual (não é teste): eventos ligados, globais dos forms e literais de SQL de um exe. */
public final class F3Probe {

    public static void main(String[] args) throws Exception {
        DelphiProject p = DelphiProject.open(Path.of(args[0]));
        RttiReader.ClassIndex idx = RttiReader.read(p.pe());
        List<EventBinder.Binding> b = EventBinder.bind(p.appForms(), idx);
        System.out.printf("eventos: %d, resolvidos: %d%n", b.size(), b.stream().filter(EventBinder.Binding::resolved).count());
        b.stream().filter(x -> !x.resolved()).limit(15).forEach(x -> System.out.println("  sem endereço: " + x));
        b.stream().filter(EventBinder.Binding::resolved).limit(8)
                .forEach(x -> System.out.printf("  %s.%s %s -> %s @%08X%n", x.formResource(), x.component(), x.event(), x.method(), x.address()));
        Map<String, FormInstances.Instance> inst = FormInstances.find(p.pe(), idx, p.appForms());
        System.out.println("globais de forms: " + inst.size());
        inst.values().forEach(i -> System.out.printf("  %s : %s @%08X%n", i.name(), i.className(), i.address()));
        List<StringLiterals.Literal> lits = StringLiterals.scan(p.pe());
        List<StringLiterals.Literal> sql = lits.stream().filter(l -> StringLiterals.looksLikeSql(l.text())).toList();
        System.out.printf("literais: %d, com cara de SQL: %d%n", lits.size(), sql.size());
        sql.stream().limit(12).forEach(l -> System.out.printf("  @%08X %s%n", l.address(),
                l.text().replaceAll("\\s+", " ").substring(0, Math.min(110, l.text().replaceAll("\\s+", " ").length()))));
    }
}
