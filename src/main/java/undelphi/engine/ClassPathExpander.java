package undelphi.engine;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * No executável (jpackage) o classpath tem só o jar lançador, e os jars do Ghidra vêm pelo Class-Path do manifesto
 * dele. A JVM carrega as classes, mas o Ghidra procura extensões varrendo a propriedade java.class.path; sem os jars
 * lá ele falha com "Unable to locate extension points". Aqui a propriedade ganha as entradas dos manifestos.
 */
public final class ClassPathExpander {

    private ClassPathExpander() {
    }

    public static void expand() {
        String cp = System.getProperty("java.class.path", "");
        Set<String> entries = new LinkedHashSet<>();
        for (String e : cp.split(File.pathSeparator)) {
            if (!e.isBlank()) {
                entries.add(e);
            }
        }
        int before = entries.size();
        for (String e : Set.copyOf(entries)) {
            File jar = new File(e);
            if (!jar.isFile() || !e.toLowerCase().endsWith(".jar")) {
                continue;
            }
            try (JarFile jf = new JarFile(jar)) {
                Manifest m = jf.getManifest();
                String extra = m == null ? null : m.getMainAttributes().getValue("Class-Path");
                if (extra == null) {
                    continue;
                }
                for (String ref : extra.trim().split("\\s+")) {
                    File f = ref.startsWith("file:") ? Path.of(URI.create(ref)).toFile() : new File(jar.getParentFile(), ref);
                    if (f.isFile()) {
                        entries.add(f.getAbsolutePath());
                    }
                }
            }
            catch (Exception ex) {
                // jar ilegível: segue com o resto
            }
        }
        if (entries.size() > before) {
            System.setProperty("java.class.path", String.join(File.pathSeparator, entries));
        }
    }
}
