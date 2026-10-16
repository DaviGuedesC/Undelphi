package undelphi.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Sonda manual (não é teste): desenha a splash num PNG, para conferir o visual sem tela. */
public final class SplashRender {

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                SplashScreen s = new SplashScreen();
                s.status("Iniciando o motor de análise (Ghidra)...", 0.45);
                var c = s.getContentPane();
                c.setSize(c.getPreferredSize());
                BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                c.paint(g);
                g.dispose();
                ImageIO.write(img, "png", new File(args[0]));
                s.dispose();
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.exit(0);
    }
}
