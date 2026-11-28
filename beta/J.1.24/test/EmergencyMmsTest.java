package org.shidulab.apparecchio;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class EmergencyMmsTest {
    public static void main(String[] args) {
        String transaction = "12345678-1234-1234-1234-123456789abc";
        byte[] audio = new byte[257];
        Arrays.fill(audio, (byte) 17);
        var packet = new ByteArrayInputStream(EmergencyMms.compose("+390000000000", transaction, audio));
        octets(packet, 0x8C, 0x80, 0x98);
        text(packet, transaction);
        octets(packet, 0x8D, 0x92, 0x89, 1, 0x81, 0x97);
        text(packet, "+390000000000/TYPE=PLMN");
        octets(packet, 0x84, 0xA3, 1);
        int headers = uintvar(packet);
        int content = uintvar(packet);
        int before = packet.available();
        text(packet, "audio/3gpp");
        octets(packet, 0x8E);
        text(packet, "emergenza.3gp");
        if (headers != before - packet.available() || content != audio.length
                || !Arrays.equals(packet.readAllBytes(), audio)) throw new AssertionError("Multipart MMS");
        try {
            EmergencyMms.compose("all", transaction, audio);
            throw new AssertionError("Invalid recipient accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("OK: MMS direct recipient, part lengths and audio bytes");
    }

    private static int uintvar(ByteArrayInputStream data) {
        int value = 0, next;
        do {
            next = data.read();
            if (next < 0) throw new AssertionError("Missing length");
            value = value * 128 + (next & 127);
        } while (next >= 128);
        return value;
    }

    private static void octets(ByteArrayInputStream data, int... values) {
        for (int value : values) if (data.read() != value) throw new AssertionError("Unexpected octet");
    }

    private static void text(ByteArrayInputStream data, String value) {
        for (byte item : value.getBytes(StandardCharsets.US_ASCII)) octets(data, item);
        octets(data, 0);
    }
}
