package undelphi.dfm;

import java.util.ArrayList;
import java.util.List;

import undelphi.pe.PeImage;

/** Extrai os DFMs guardados como RCDATA no exe. */
public final class FormResources {

    /** DFM lido de um recurso: nome do recurso (classe do form em maiúsculas) e a árvore. */
    public record Form(String resourceName, DfmObject root) {
    }

    /** Recurso que parecia DFM mas não foi lido (o erro vai para o relatório, sem derrubar o resto). */
    public record Failure(String resourceName, String error) {
    }

    public record Result(List<Form> forms, List<Failure> failures) {
    }

    public static Result extract(PeImage pe) {
        List<Form> forms = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (PeImage.Resource r : pe.dfmResources()) {
            try {
                forms.add(new Form(r.name(), DfmParser.parse(pe.data(), r.offset())));
            }
            catch (RuntimeException e) {
                failures.add(new Failure(r.name(), e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return new Result(forms, failures);
    }

    private FormResources() {
    }
}
