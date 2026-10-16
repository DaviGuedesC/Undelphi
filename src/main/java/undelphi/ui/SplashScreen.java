package undelphi.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * Tela de abertura no estilo da do Ghidra: janela sem borda com a logo à esquerda, nome e versão grandes,
 * créditos do motor e uma linha de status com o que está sendo carregado.
 */
public final class SplashScreen extends JWindow {

    public static final String VERSION = SplashScreen.class.getPackage().getImplementationVersion() != null
            ? SplashScreen.class.getPackage().getImplementationVersion() : "0.1.0";

    private static final Color BG_TOP = new Color(0x2a, 0x0d, 0x10);
    private static final Color BG_BOTTOM = new Color(0x12, 0x06, 0x08);
    private static final Color ACCENT = new Color(0xbe, 0x1e, 0x2d);
    private static final Color TEXT = new Color(0xf2, 0xee, 0xee);
    private static final Color MUTED = new Color(0xb9, 0xa8, 0xaa);

    private final BufferedImage logo;
    private volatile String status = "Iniciando...";
    private volatile double progress;

    public SplashScreen() {
        logo = image("logo-256.png");
        setIconImages(icons());
        Canvas canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(640, 320));
        setContentPane(canvas);
        pack();
        setLocationRelativeTo(null);
        setAlwaysOnTop(true);
    }

    /** Ícones da janela/taskbar em vários tamanhos. */
    public static List<Image> icons() {
        List<Image> out = new ArrayList<>();
        for (int s : new int[] { 16, 32, 64, 256 }) {
            BufferedImage img = image("logo-" + s + ".png");
            if (img != null) {
                out.add(img);
            }
        }
        return out;
    }

    /** Ícone na taskbar/dock do sistema, quando suportado. */
    public static void installTaskbarIcon() {
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                BufferedImage img = image("logo-256.png");
                if (img != null) {
                    Taskbar.getTaskbar().setIconImage(img);
                }
            }
        }
        catch (RuntimeException e) {
            // sem taskbar (ex. Windows antigo): fica com o ícone da janela
        }
    }

    static BufferedImage image(String name) {
        try (InputStream in = SplashScreen.class.getResourceAsStream(name)) {
            return in == null ? null : ImageIO.read(in);
        }
        catch (IOException e) {
            return null;
        }
    }

    /** Atualiza a linha de status (pode ser chamado de qualquer thread). progress de 0 a 1, ou negativo para manter. */
    public void status(String text, double progress) {
        this.status = text;
        if (progress >= 0) {
            this.progress = Math.min(1, progress);
        }
        SwingUtilities.invokeLater(this::repaint);
    }

    private final class Canvas extends JComponent {

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int w = getWidth();
            int h = getHeight();

            g.setPaint(new GradientPaint(0, 0, BG_TOP, w, h, BG_BOTTOM));
            g.fillRect(0, 0, w, h);

            // logo à esquerda, com um brilho atrás
            int size = 220;
            int lx = 36;
            int ly = (h - 28 - size) / 2;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
            g.setColor(ACCENT);
            g.fillOval(lx - 14, ly - 14, size + 28, size + 28);
            g.setComposite(AlphaComposite.SrcOver);
            if (logo != null) {
                g.drawImage(logo, lx, ly, size, size, null);
            }

            // título, versão e créditos
            int tx = lx + size + 36;
            g.setColor(TEXT);
            g.setFont(new Font("Segoe UI", Font.BOLD, 52));
            g.drawString("Undelphi", tx, 112);
            g.setColor(ACCENT);
            g.fillRect(tx + 2, 126, 64, 4);
            g.setColor(MUTED);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 16));
            g.drawString("Versão " + VERSION, tx, 158);
            g.drawString("Delphi Win32 → Vue.js + Go", tx, 182);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            g.drawString("Motor de análise: Ghidra 12.1 (NSA, Apache 2.0)", tx, 216);

            // barra de status embaixo, como a do Ghidra
            int barY = h - 28;
            g.setColor(new Color(0, 0, 0, 120));
            g.fillRect(0, barY, w, 28);
            g.setColor(ACCENT);
            g.fillRect(0, barY, (int) (w * progress), 3);
            g.setColor(TEXT);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            g.drawString(status, 12, barY + 19);

            g.setColor(new Color(255, 255, 255, 40));
            g.setStroke(new BasicStroke(1));
            g.drawRect(0, 0, w - 1, h - 1);
            g.dispose();
        }
    }
}
