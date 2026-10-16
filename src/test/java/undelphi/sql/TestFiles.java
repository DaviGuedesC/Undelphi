package undelphi.sql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assumptions;

/**
 * Lê arquivos de src/test/resources e src/test/resources-privado. O diretório privado (fora do git) guarda
 * amostras reais de rotinas e dados de clientes; sem ele, os testes que dependem dessas amostras são pulados.
 */
public final class TestFiles {

    public static String read(String resource) throws IOException {
        String text = optional(resource);
        Assumptions.assumeTrue(text != null, "amostra privada ausente (src/test/resources-privado): " + resource);
        return text;
    }

    /** Conteúdo do recurso, ou null se ele não existir. */
    public static String optional(String resource) throws IOException {
        try (InputStream in = TestFiles.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private TestFiles() {
    }
}
