package undelphi.delphi;

import java.nio.file.Path;
import java.util.Map;

/**
 * Resultado da triagem rápida de um executável (leitura dos bytes, sem Ghidra).
 *
 * @param compilerVersion versão do compilador Delphi, ex. "30.0" (null se não encontrada)
 * @param compilerBuild   build do compilador, ex. "23.0.20618.2753"
 * @param product         nome comercial, ex. "Delphi 10 Seattle"
 * @param formResources   ocorrências da assinatura de DFM binário (TPF0)
 * @param libraries       família de componentes → número de referências encontradas
 */
public record ExeInfo(
        Path path,
        long size,
        String machine,
        String compilerVersion,
        String compilerBuild,
        String product,
        int formResources,
        Map<String, Integer> libraries) {

    public boolean isDelphi() {
        return compilerVersion != null || formResources > 0;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Arquivo:      ").append(path.getFileName()).append('\n');
        sb.append("Tamanho:      ").append(String.format("%,d bytes", size)).append('\n');
        sb.append("Arquitetura:  ").append(machine).append('\n');
        if (compilerVersion != null) {
            sb.append("Compilador:   ").append(product)
                    .append(" (versão ").append(compilerVersion)
                    .append(", build ").append(compilerBuild).append(")\n");
        } else {
            sb.append("Compilador:   não identificado").append(isDelphi() ? " (há forms Delphi)" : "").append('\n');
        }
        sb.append("Forms (TPF0): ").append(formResources).append('\n');
        sb.append("Bibliotecas:").append(libraries.isEmpty() ? " nenhuma reconhecida" : "").append('\n');
        libraries.forEach((name, refs) -> sb.append(String.format("  - %-22s %,d refs%n", name, refs)));
        return sb.toString();
    }
}
