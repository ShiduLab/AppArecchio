package org.shidulab.apparecchio;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class EmergencySent extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        final String id = intent.getStringExtra("id");
        final String kind = intent.getStringExtra("kind");
        final String attempt = intent.getStringExtra("attempt");
        final int part = intent.getIntExtra("part", -1);
        if (!EmergencyOutbox.validId(id) || !EmergencyOutbox.validId(attempt)
                || (!"sms".equals(kind) && !"mms".equals(kind)) || part < 0) return;
        final boolean sent = getResultCode() == Activity.RESULT_OK;
        PendingResult pending = goAsync();
        new Thread(() -> {
            try { EmergencyOutbox.result(context, id, kind, attempt, part, sent); }
            catch (Exception ignored) { }
            finally { pending.finish(); }
        }, "Esito invio emergenza").start();
    }
}
