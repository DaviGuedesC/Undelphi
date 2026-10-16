package undelphi.delphi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DelphiDetectorTest {

    /** PE mínimo: cabeçalho MZ, PE com o machine pedido e um trecho de texto no corpo. */
    private static byte[] fakePe(int machine, String body) {
        byte[] b = new byte[0x400];
        b[0] = 'M';
        b[1] = 'Z';
        b[0x3C] = (byte) 0x80;
        b[0x80] = 'P';
        b[0x81] = 'E';
        b[0x84] = (byte) machine;
        b[0x85] = (byte) (machine >> 8);
        byte[] text = body.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(text, 0, b, 0x100, text.length);
        return b;
    }

    @Test
    void reconheceDelphi10Seattle32Bits() {
        byte[] exe = fakePe(0x14C, "Embarcadero Delphi for Win32 compiler version 30.0 (23.0.20618.2753)"
                + "\0TPF0\0TfrmPrincipal\0TPF0\0TOraQuery\0TOraSQL\0TcxGrid\0");

        ExeInfo info = DelphiDetector.detect(Path.of("teste.exe"), exe);

        assertEquals("Win32 (x86)", info.machine());
        assertEquals("30.0", info.compilerVersion());
        assertEquals("23.0.20618.2753", info.compilerBuild());
        assertEquals("Delphi 10 Seattle", info.product());
        assertEquals(2, info.formResources());
        assertEquals(2, info.libraries().get("ODAC (Devart/Oracle)"));
        assertEquals(1, info.libraries().get("DevExpress"));
        assertTrue(info.isDelphi());
    }

    @Test
    void versaoDesconhecidaFicaExplicita() {
        byte[] exe = fakePe(0x8664, "Embarcadero Delphi for Win64 compiler version 99.0 (99.0.1.1)");

        ExeInfo info = DelphiDetector.detect(Path.of("teste.exe"), exe);

        assertEquals("Win64 (x64)", info.machine());
        assertEquals("Delphi (versão 99.0 desconhecida)", info.product());
    }

    @Test
    void naoPeNaoEhDelphi() {
        ExeInfo info = DelphiDetector.detect(Path.of("x.txt"), "texto qualquer".getBytes(StandardCharsets.US_ASCII));

        assertEquals("não é PE", info.machine());
        assertNull(info.compilerVersion());
        assertFalse(info.isDelphi());
    }
}
