package org.shidulab.apparecchio;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.UUID;
import org.json.JSONObject;

public final class EmergencyWorker extends Worker {
    public EmergencyWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        String id = getInputData().getString("id");
        try {
            JSONObject job = EmergencyOutbox.read(context, id);
            if (!EmergencyOutbox.active(job)) return Result.success();
            if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                return Result.retry();
            }
            int subscription = SubscriptionManager.getDefaultSmsSubscriptionId();
            if (!SubscriptionManager.isValidSubscriptionId(subscription)) return Result.retry();
            SmsManager sms = manager(context, subscription);
            if (!EmergencyOutbox.SENT.equals(job.getString("sms")) && !isStopped()) sendText(context, sms, id, job);
            if (!EmergencyOutbox.SENT.equals(job.getString("mms")) && !isStopped()) sendAudio(context, sms, id, job);
            return EmergencyOutbox.active(EmergencyOutbox.read(context, id)) ? Result.retry() : Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    @SuppressWarnings("deprecation")
    private static SmsManager manager(Context context, int subscription) {
        if (Build.VERSION.SDK_INT >= 31) {
            return context.getSystemService(SmsManager.class).createForSubscriptionId(subscription);
        }
        return SmsManager.getSmsManagerForSubscriptionId(subscription);
    }

    private void sendText(Context context, SmsManager sms, String id, JSONObject job) throws Exception {
        String attempt = UUID.randomUUID().toString();
        ArrayList<String> parts = sms.divideMessage(job.getString("text"));
        if (parts.isEmpty() || !EmergencyOutbox.claim(context, id, "sms", attempt, parts.size())) return;
        try {
            var sent = new ArrayList<PendingIntent>(parts.size());
            for (int i = 0; i < parts.size(); i++) sent.add(callback(context, id, "sms", attempt, i));
            if (parts.size() == 1) sms.sendTextMessage(job.getString("number"), null, parts.get(0), sent.get(0), null);
            else sms.sendMultipartTextMessage(job.getString("number"), null, parts, sent, null);
        } catch (RuntimeException e) {
            EmergencyOutbox.result(context, id, "sms", attempt, 0, false);
        }
    }

    private void sendAudio(Context context, SmsManager sms, String id, JSONObject job) throws Exception {
        String audio = job.getString("audio");
        if (!EmergencyOutbox.validId(audio)) throw new IOException("Bozza vocale non valida.");
        File recording = new File(EmergencyOutbox.directory(context, "recordings"), audio + ".3gp");
        byte[] content = EmergencyMms.compose(job.getString("number"), id, Files.readAllBytes(recording.toPath()));
        String attempt = UUID.randomUUID().toString();
        if (!EmergencyOutbox.claim(context, id, "mms", attempt, 1)) return;
        try {
            File folder = new File(context.getCacheDir(), "emergency-mms");
            if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) throw new IOException();
            File pdu = new File(folder, id + ".pdu");
            Files.write(pdu.toPath(), content);
            Uri uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".emergenza", pdu);
            sms.sendMultimediaMessage(context, uri, null, null, callback(context, id, "mms", attempt, 0));
        } catch (Exception e) {
            EmergencyOutbox.result(context, id, "mms", attempt, 0, false);
        }
    }

    private PendingIntent callback(Context context, String id, String kind, String attempt, int part) {
        var intent = new Intent(context, EmergencySent.class)
            .setData(Uri.parse("apparecchio-emergency://" + id + "/" + kind + "/" + attempt + "/" + part))
            .putExtra("id", id).putExtra("kind", kind).putExtra("attempt", attempt).putExtra("part", part);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
    }
}
