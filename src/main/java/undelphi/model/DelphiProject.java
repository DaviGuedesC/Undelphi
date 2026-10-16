package undelphi.model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import undelphi.delphi.DelphiDetector;
import undelphi.delphi.ExeInfo;
import undelphi.dfm.DfmObject;
import undelphi.dfm.FormResources;
import undelphi.pe.PeImage;

/** Um exe Delphi aberto: triagem, imagem PE e forms, com resolução de referências entre forms e data modules. */
public final class DelphiProject {

    private final Path exe;
    private final ExeInfo info;
    private final PeImage pe;
    private final List<FormResources.Form> forms;
    private final List<FormResources.Failure> failures;
    private final Map<String, FormResources.Form> byRootName = new LinkedHashMap<>();
    private final Map<String, FormResources.Form> byResource = new LinkedHashMap<>();

    private DelphiProject(Path exe, ExeInfo info, PeImage pe, FormResources.Result result) {
        this.exe = exe;
        this.info = info;
        this.pe = pe;
        this.forms = result.forms();
        this.failures = result.failures();
        for (FormResources.Form f : forms) {
            byRootName.putIfAbsent(f.root().name().toLowerCase(Locale.ROOT), f);
            byResource.put(f.resourceName().toUpperCase(Locale.ROOT), f);
        }
    }

    /** Projeto só com forms já lidos (testes e ferramentas que não têm o exe). */
    public static DelphiProject fromForms(Path exe, List<FormResources.Form> forms) {
        return new DelphiProject(exe, null, null, new FormResources.Result(forms, List.of()));
    }

    public static DelphiProject open(Path exe) throws IOException {
        PeImage pe = PeImage.read(exe);
        ExeInfo info = DelphiDetector.detect(exe, pe.data());
        return new DelphiProject(exe, info, pe, FormResources.extract(pe));
    }

    public Path exe() {
        return exe;
    }

    /** Nome do exe sem extensão, ex. "PCNOV9860". */
    public String baseName() {
        String n = exe.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public ExeInfo info() {
        return info;
    }

    public PeImage pe() {
        return pe;
    }

    public List<FormResources.Form> forms() {
        return forms;
    }

    public List<FormResources.Failure> failures() {
        return failures;
    }

    /** Forms e data modules da aplicação (sem os forms internos de bibliotecas). */
    public List<FormResources.Form> appForms() {
        List<FormResources.Form> out = new ArrayList<>();
        for (FormResources.Form f : forms) {
            if (!FormClassifier.isLibrary(f.resourceName())) {
                out.add(f);
            }
        }
        return out;
    }

    public FormResources.Form formByResource(String resourceName) {
        return byResource.get(resourceName.toUpperCase(Locale.ROOT));
    }

    public FormResources.Form formOf(DfmObject obj) {
        return byRootName.get(obj.root().name().toLowerCase(Locale.ROOT));
    }

    /**
     * Resolve uma referência de componente como o Delphi faz no DFM: "dmDadosXML.dtsProdutos" aponta para outro
     * form/data module; "qryX" é procurado primeiro no próprio form.
     */
    public DfmObject resolve(DfmObject context, String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        int dot = reference.indexOf('.');
        if (dot > 0) {
            FormResources.Form other = byRootName.get(reference.substring(0, dot).toLowerCase(Locale.ROOT));
            if (other != null) {
                return other.root().find(reference.substring(dot + 1));
            }
        }
        DfmObject local = context.root().find(reference);
        if (local != null) {
            return local;
        }
        for (FormResources.Form f : forms) {
            DfmObject found = f.root().find(reference);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
