package undelphi.gogen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Roda a CLI do undelphi numa JVM separada, como o undelphi.cmd faz. Necessário para o Ghidra: dentro do worker de
 * teste do Gradle ele não consegue instalar o filtro de serialização (o Gradle já instalou um) e encerra a JVM.
 */
final class CliRunner {

    record Run(int exit, String output) {
    }

    static Run run(String... args) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        // classpath num arquivo de argumentos: com os ~200 jars do Ghidra ele passa do limite de linha do Windows
        Path argFile = Files.createTempFile("undelphi-cli", ".args");
        String cp = System.getProperty("java.class.path").replace('\\', '/');
        Files.writeString(argFile, "-cp \"" + cp + "\"\n", StandardCharsets.UTF_8);
        List<String> cmd = new ArrayList<>(List.of(java.toString(), "-Xmx4g", "-Dstdout.encoding=UTF-8",
                "-Dundelphi.ghidra=" + System.getProperty("undelphi.ghidra"), "@" + argFile, "undelphi.Main"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        boolean done = p.waitFor(20, TimeUnit.MINUTES);
        Files.deleteIfExists(argFile);
        if (!done) {
            p.destroyForcibly();
            return new Run(-1, "timeout");
        }
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    private CliRunner() {
    }
}
