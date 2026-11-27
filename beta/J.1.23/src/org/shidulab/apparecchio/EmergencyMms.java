package org.shidulab.apparecchio;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class EmergencyMms {
    private EmergencyMms() { }

    static byte[] compose(String number, String transaction, byte[] recording) {
        if (!number.matches("\\+?[0-9]{5,18}") || !transaction.matches("[a-f0-9-]{36}")
                || recording.length == 0 || recording.length > 280_000) {
            throw new IllegalArgumentException("Allegato MMS non valido.");
        }
        var pdu = new ByteArrayOutputStream();
        pdu.write(0x8C); // X-Mms-Message-Type
        pdu.write(0x80); // M-Send.req
        pdu.write(0x98); // X-Mms-Transaction-ID
        text(pdu, transaction);
        pdu.write(0x8D); // X-Mms-MMS-Version
        pdu.write(0x92); // MMS 1.2
        pdu.write(0x89); // From
        pdu.write(1);
        pdu.write(0x81); // Insert-address-token
        pdu.write(0x97); // To
        text(pdu, number + "/TYPE=PLMN");
        pdu.write(0x84); // Content-Type
        pdu.write(0xA3); // application/vnd.wap.multipart.mixed
        uintvar(pdu, 1);

        var part = new ByteArrayOutputStream();
        text(part, "audio/3gpp");
        part.write(0x8E); // Content-Location
        text(part, "emergenza.3gp");
        uintvar(pdu, part.size());
        uintvar(pdu, recording.length);
        byte[] headers = part.toByteArray();
        pdu.write(headers, 0, headers.length);
        pdu.write(recording, 0, recording.length);
        return pdu.toByteArray();
    }

    private static void text(ByteArrayOutputStream target, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        target.write(encoded, 0, encoded.length);
        target.write(0);
    }

    private static void uintvar(ByteArrayOutputStream target, int value) {
        int shift = 0;
        while ((value >>> (shift + 7)) != 0) shift += 7;
        for (; shift > 0; shift -= 7) target.write(((value >>> shift) & 127) | 128);
        target.write(value & 127);
    }
}
