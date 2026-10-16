package undelphi.gogen;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Roda o toolchain Go nos testes (go build/vet/test no código gerado). Usa o Go local se ele estiver
 * completo; senão, o Go da imagem Docker golang:1.22-alpine (nesta máquina o compile.exe do Go local
 * costuma sumir).
 */
final class GoTool {

    record Run(int exit, String output) {
    }

    static final String IMAGE = System.getenv().getOrDefault("UNDELPHI_GO_IMAGE", "golang:1.22-alpine");

    /** Go local completo (com o compilador), ou null. */
    static Path localGo() {
        List<Path> candidates = new ArrayList<>();
        String env = System.getenv("UNDELPHI_GO");
        if (env != null) {
            candidates.add(Path.of(env));
        }
        candidates.add(Path.of("C:/Users/devuser/tools/go/bin/go.exe"));
        candidates.add(Path.of("C:/Users/devuser/tools/go-1.22.12-novo/go/bin/go.exe"));
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            candidates.add(Path.of(dir, "go.exe"));
            candidates.add(Path.of(dir, "go"));
        }
        for (Path go : candidates) {
            if (Files.isRegularFile(go) && hasCompiler(go)) {
                return go;
            }
        }
        return null;
    }

    private static boolean hasCompiler(Path go) {
        Path tools = go.toAbsolutePath().getParent().getParent().resolve("pkg/tool");
        if (!Files.isDirectory(tools)) {
            return false;
        }
        try (var dirs = Files.list(tools)) {
            return dirs.anyMatch(d -> Files.isRegularFile(d.resolve("compile.exe")) || Files.isRegularFile(d.resolve("compile")));
        }
        catch (IOException e) {
            return false;
        }
    }

    static Path docker() {
        String env = System.getenv("UNDELPHI_DOCKER");
        if (env != null && Files.isRegularFile(Path.of(env))) {
            return Path.of(env);
        }
        Path known = Path.of("C:/Users/S00025/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe");
        if (Files.isRegularFile(known)) {
            return known;
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            for (String exe : List.of("docker.exe", "docker")) {
                if (Files.isRegularFile(Path.of(dir, exe))) {
                    return Path.of(dir, exe);
                }
            }
        }
        return null;
    }

    /** Pula o teste se não houver Go local completo nem Docker. */
    static void require() {
        assumeTrue(localGo() != null || docker() != null, "sem Go local completo e sem Docker para rodar o Go");
    }

    static Run run(Path dir, String... goArgs) throws IOException, InterruptedException {
        require();
        List<String> cmd = new ArrayList<>();
        Path go = localGo();
        ProcessBuilder pb;
        if (go != null) {
            cmd.add(go.toString());
            cmd.addAll(List.of(goArgs));
            pb = new ProcessBuilder(cmd).directory(dir.toFile());
            pb.environment().put("GOTOOLCHAIN", "local");
            pb.environment().put("GOPROXY", "off");
            pb.environment().putIfAbsent("GOCACHE", Path.of(System.getProperty("java.io.tmpdir"), "undelphi-gocache").toString());
        } else {
            cmd.addAll(List.of(docker().toString(), "run", "--rm", "-v", dir.toAbsolutePath() + ":/src", "-w", "/src",
                    "-e", "GOTOOLCHAIN=local", "-e", "GOPROXY=off", "-e", "GOCACHE=/tmp/gocache", "-e", "CGO_ENABLED=0",
                    IMAGE, "go"));
            cmd.addAll(List.of(goArgs));
            pb = new ProcessBuilder(cmd);
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return new Run(-1, "timeout");
        }
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    private GoTool() {
    }
}
