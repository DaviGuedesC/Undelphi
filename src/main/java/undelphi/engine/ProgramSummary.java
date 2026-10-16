package undelphi.engine;

import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryBlock;

/** O que o Ghidra entendeu do exe carregado. */
public record ProgramSummary(
        String format,
        String language,
        String compilerSpec,
        String imageBase,
        String memoryBlocks,
        int functions,
        long importMs,
        long analysisMs) {

    static ProgramSummary of(Program p, long importMs, long analysisMs) {
        StringBuilder blocks = new StringBuilder();
        for (MemoryBlock b : p.getMemory().getBlocks()) {
            if (!blocks.isEmpty()) {
                blocks.append(", ");
            }
            blocks.append(b.getName());
        }
        return new ProgramSummary(
                p.getExecutableFormat(),
                p.getLanguageID().getIdAsString(),
                p.getCompilerSpec().getCompilerSpecID().getIdAsString(),
                p.getImageBase().toString(),
                blocks.toString(),
                p.getFunctionManager().getFunctionCount(),
                importMs,
                analysisMs);
    }

    public String describe() {
        return "Ghidra:\n"
                + "  Formato:      " + format + '\n'
                + "  Linguagem:    " + language + " / " + compilerSpec + '\n'
                + "  Image base:   0x" + imageBase + '\n'
                + "  Seções:       " + memoryBlocks + '\n'
                + String.format("  Funções:      %,d%s%n", functions, analysisMs == 0 ? " (sem autoanálise)" : "")
                + String.format("  Importação:   %,d ms%n", importMs)
                + (analysisMs == 0 ? "" : String.format("  Análise:      %,d ms%n", analysisMs));
    }
}
