package undelphi.dfm;

import java.util.List;
import java.util.Map;

/** Tipos de valor do DFM que não têm equivalente direto em Java (os demais usam Long, Double, String, Boolean e List). */
public final class DfmValue {

    /** Identificador (enum, nome de evento, referência a componente): OnClick = btnGravarClick. */
    public record Ident(String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Conjunto: [fsBold, fsItalic]. */
    public record SetOf(List<String> items) {
    }

    /** Dados binários (imagens, layouts de grid etc.). */
    public record Binary(byte[] bytes) {
    }

    /** nil. */
    public record Nil() {
    }

    /** Item de coleção (Columns, Params, Panels...). */
    public record Item(Integer index, Map<String, Object> properties) {
    }

    /** Coleção: Columns = &lt;item ... end&gt;. */
    public record Collection(List<Item> items) {
    }

    private DfmValue() {
    }
}
