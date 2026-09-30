package com.scada.gateway.pac;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/** Контракт кадра запроса и zlib-распаковки тела (протокол driver-master). */
class PacProtocolTest {

    @Test
    void zlibBombIsRejected() throws Exception {
        // 32 МБ нулей сжимаются в десятки КБ — кадр PAC (≤ 64 КБ) распаковался бы в десятки МБ.
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(new byte[32 * 1024 * 1024]);
        d.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        byte[] bomb = out.toByteArray();
        assertTrue(bomb.length < 65535, "бомба помещается в один кадр: " + bomb.length + " Б");
        java.util.zip.DataFormatException e = assertThrows(java.util.zip.DataFormatException.class,
                () -> PacProtocol.inflate(bomb, 0, bomb.length));
        assertTrue(e.getMessage().contains("МБ"), e.getMessage());
    }

    @Test
    void buildRequest_headerLayout() {
        byte[] payload = {(byte) PacProtocol.CMD_GET_DEVICES_STATES};
        byte[] f = PacProtocol.buildRequest(7, payload);

        assertEquals(PacProtocol.REQUEST_HEADER_LEN + 1, f.length);
        assertEquals('s', f[0]);                 // NetId
        assertEquals(1, f[1]);                    // ServiceID
        assertEquals(1, f[2]);                    // FrameSingle
        assertEquals(7, f[3]);                    // pidx
        assertEquals(0, f[4]);                    // lenHi (len=1)
        assertEquals(1, f[5]);                    // lenLo
        assertEquals(PacProtocol.CMD_GET_DEVICES_STATES, f[6]);
    }

    @Test
    void buildRequest_lengthBigEndian16() {
        byte[] f = PacProtocol.buildRequest(1, new byte[300]);
        assertEquals((300 >> 8) & 0xFF, f[4] & 0xFF);
        assertEquals(300 & 0xFF, f[5] & 0xFF);
    }

    @Test
    void inflate_roundTripsZlib() throws Exception {
        byte[] original = "tags={}\ntags['9001']=1\ntags['9002']=22.5\n".getBytes(StandardCharsets.UTF_8);

        // Deflater по умолчанию = zlib-формат (как C++ compress2 → Inflater по умолчанию).
        Deflater d = new Deflater();
        d.setInput(original);
        d.finish();
        byte[] comp = new byte[512];
        int n = d.deflate(comp);
        d.end();

        byte[] back = PacProtocol.inflate(comp, 0, n);
        assertArrayEquals(original, back);
    }
}
