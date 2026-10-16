package undelphi.sql;

/**
 * Um texto SQL encontrado no exe.
 *
 * @param origin         onde estava, ex. "TDMDADOSXML.exeGerarPCCOTA" ou "código @0x0052A1F0"
 * @param componentClass classe do componente (TOraQuery, TOraSQL...) ou "literal" para SQL achada no código
 * @param property       propriedade do componente (SQL.Strings, SQLInsert.Strings, StoredProcName...)
 * @param sql            o texto
 */
public record SqlSource(String origin, String componentClass, String property, String sql) {

    /** Só o nome do procedimento (StoredProcName), sem SQL. */
    public boolean isStoredProcName() {
        return "StoredProcName".equalsIgnoreCase(property);
    }
}
