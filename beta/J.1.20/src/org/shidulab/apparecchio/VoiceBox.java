package org.shidulab.apparecchio;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class VoiceBox extends SQLiteOpenHelper {
    static final int MAX_SAMPLES = 48000 * 15;

    static final class Capture {
        private final short[] buffer = new short[MAX_SAMPLES];
        private int length;
        private boolean stopped;

        synchronized void accept(short[] pcm, int count) {
            if (stopped) return;
            int available = Math.min(count, buffer.length - length);
            System.arraycopy(pcm, 0, buffer, length, available);
            length += available;
        }

        synchronized short[] finish() {
            stopped = true;
            return java.util.Arrays.copyOf(buffer, length);
        }
    }

    VoiceBox(Context context) { super(context, "voicebox.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE drafts(id INTEGER PRIMARY KEY, recipient TEXT NOT NULL, "
                + "message TEXT NOT NULL, pcm BLOB, created INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Versione archivio non supportata");
    }

    void save(String recipient, String message, short[] pcm) {
        ContentValues v = new ContentValues();
        v.put("recipient", recipient);
        v.put("message", message);
        v.put("created", System.currentTimeMillis());
        if (pcm != null) {
            ByteBuffer bytes = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short sample : pcm) bytes.putShort(sample);
            v.put("pcm", bytes.array());
        }
        getWritableDatabase().insertOrThrow("drafts", null, v);
    }

    record Draft(long id, String recipient, String message, boolean audio) {
        @Override public String toString() {
            return "Per " + recipient + " · " + (audio ? "Messaggio vocale" : message);
        }
    }

    java.util.ArrayList<Draft> drafts() {
        var list = new java.util.ArrayList<Draft>();
        try (var cursor = getReadableDatabase().rawQuery(
                "SELECT id,recipient,message,pcm IS NOT NULL FROM drafts ORDER BY id DESC", null)) {
            while (cursor.moveToNext()) list.add(new Draft(cursor.getLong(0), cursor.getString(1),
                    cursor.getString(2), cursor.getInt(3) == 1));
        }
        return list;
    }

    short[] audio(long id) {
        try (var cursor = getReadableDatabase().rawQuery("SELECT pcm FROM drafts WHERE id=?",
                new String[]{Long.toString(id)})) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null;
            ByteBuffer bytes = ByteBuffer.wrap(cursor.getBlob(0)).order(ByteOrder.LITTLE_ENDIAN);
            short[] result = new short[bytes.remaining() / 2];
            bytes.asShortBuffer().get(result);
            return result;
        }
    }
}
