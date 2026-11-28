package org.shidulab.apparecchio;

import android.content.Context;
import android.util.AtomicFile;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

final class EmergencyOutbox {
    static final String QUEUED = "In attesa della rete";
    static final String SENDING = "In attesa del gestore";
    static final String SENT = "Inviato al gestore";
    static final String STOPPED = "Invio fermato";
    private static final Object LOCK = new Object();

    static boolean validId(String id) {
        return id != null && id.matches("[a-f0-9-]{36}");
    }

    static File directory(Context context, String name) throws IOException {
        File dir = new File(context.getFilesDir(), "emergency/" + name);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("Archivio non disponibile.");
        return dir;
    }

    static String add(Context context, String name, String number, String text, String audio) throws Exception {
        if (!number.matches("\\+?[0-9]{5,18}") || (text.isBlank() && audio.isEmpty())
                || (!audio.isEmpty() && !validId(audio))) throw new IOException("Messaggio o numero non valido.");
        String id = UUID.randomUUID().toString();
        JSONObject job = new JSONObject().put("id", id).put("name", name).put("number", number)
            .put("text", text).put("audio", audio).put("created", System.currentTimeMillis())
            .put("sms", text.isBlank() ? SENT : QUEUED).put("mms", audio.isEmpty() ? SENT : QUEUED);
        write(context, job);
        schedule(context, id);
        return id;
    }

    private static AtomicFile file(Context context, String id) throws IOException {
        if (!validId(id)) throw new IOException("Identificativo non valido.");
        return new AtomicFile(new File(directory(context, "outbox"), id + ".json"));
    }

    static JSONObject read(Context context, String id) throws Exception {
        synchronized (LOCK) {
            return new JSONObject(new String(file(context, id).readFully(), StandardCharsets.UTF_8));
        }
    }

    private static void write(Context context, JSONObject job) throws Exception {
        synchronized (LOCK) {
            AtomicFile file = file(context, job.getString("id"));
            var stream = file.startWrite();
            try {
                stream.write(job.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(stream);
            } catch (IOException e) {
                file.failWrite(stream);
                throw e;
            }
        }
    }

    static boolean active(JSONObject job) {
        return !job.optBoolean("stopped") &&
            (!SENT.equals(job.optString("sms")) || !SENT.equals(job.optString("mms")));
    }

    static boolean claim(Context context, String id, String kind, String attempt, int parts) throws Exception {
        synchronized (LOCK) {
            JSONObject job = read(context, id);
            if (!active(job) || SENT.equals(job.optString(kind))) return false;
            long now = System.currentTimeMillis();
            JSONObject previous = job.optJSONObject(kind + "Attempt");
            if (previous != null && SENDING.equals(job.optString(kind))
                    && now - previous.optLong("at") < TimeUnit.MINUTES.toMillis(3)) return false;
            job.put(kind, SENDING).put(kind + "Attempt", new JSONObject()
                .put("id", attempt).put("at", now).put("parts", parts).put("ack", new JSONArray()));
            write(context, job);
            return true;
        }
    }

    static void result(Context context, String id, String kind, String attempt, int part, boolean sent) throws Exception {
        synchronized (LOCK) {
            JSONObject job = read(context, id);
            JSONObject session = job.optJSONObject(kind + "Attempt");
            if (session == null || !session.optString("id").equals(attempt) || !SENDING.equals(job.optString(kind))) return;
            if (!sent) job.put(kind, QUEUED);
            else {
                JSONArray ack = session.getJSONArray("ack");
                for (int i = 0; i < ack.length(); i++) if (ack.optInt(i) == part) return;
                ack.put(part);
                if (ack.length() >= session.getInt("parts")) job.put(kind, SENT);
            }
            write(context, job);
        }
    }

    static void stop(Context context, String id) throws Exception {
        synchronized (LOCK) {
            JSONObject job = read(context, id);
            job.put("stopped", true);
            write(context, job);
        }
        WorkManager.getInstance(context).cancelUniqueWork("emergency:" + id);
    }

    static ArrayList<JSONObject> list(Context context) throws IOException {
        var jobs = new ArrayList<JSONObject>();
        File[] files = directory(context, "outbox").listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return jobs;
        for (File f : files) {
            String name = f.getName();
            try { jobs.add(read(context, name.substring(0, name.length() - 5))); }
            catch (Exception ignored) { }
        }
        jobs.sort((a, b) -> Long.compare(b.optLong("created"), a.optLong("created")));
        return jobs;
    }

    static void restore(Context context) throws IOException {
        for (JSONObject job : list(context)) {
            if (active(job)) schedule(context, job.optString("id"));
        }
    }

    private static void schedule(Context context, String id) {
        var request = new OneTimeWorkRequest.Builder(EmergencyWorker.class)
            .setInputData(new Data.Builder().putString("id", id).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 20, TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork("emergency:" + id, ExistingWorkPolicy.KEEP, request);
    }
}
