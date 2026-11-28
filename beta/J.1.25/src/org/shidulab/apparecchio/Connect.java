package org.shidulab.apparecchio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

final class Connect implements AutoCloseable {
    private final URL endpoint;
    private final String token;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ScheduledExecutorService receiver = Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();
    private final Consumer<JSONObject> show;
    private final Consumer<String> status;
    private record VoicePacket(short[] samples, String to) {}
    private final ArrayBlockingQueue<VoicePacket> voice = new ArrayBlockingQueue<>(3);
    private short[] packet = new short[4800];
    private int length, phase;
    private long cursor;
    private volatile boolean closed;
    volatile boolean transmitting;
    volatile String destination = "all";

    Connect(Context context, String address, String token, Consumer<JSONObject> show, Consumer<String> status) throws Exception {
        this.context = context.getApplicationContext();
        endpoint = new URL(address.endsWith("/") ? address : address + "/");
        if (!endpoint.getProtocol().equals("https") || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getRef() != null
                || !token.matches("[a-f0-9]{48}")) throw new IllegalArgumentException("Servono indirizzo HTTPS e chiave partecipante.");
        this.token = token;
        this.show = show;
        this.status = status;
        receiver.scheduleWithFixedDelay(this::receive, 0, 220, TimeUnit.MILLISECONDS);
        sender.scheduleWithFixedDelay(this::flush, 0, 160, TimeUnit.MILLISECONDS);
    }

    void capture(short[] samples, int count) {
        if (!transmitting || closed) {
            if (!closed && length > 0) voice.offer(new VoicePacket(java.util.Arrays.copyOf(packet,length),destination));
            length=0;
            phase=0;
            return;
        }
        for (int i=0;i<count;i++) {
            if (++phase != 3) continue;
            phase=0;
            packet[length++]=samples[i];
            if(length==packet.length) {
                if(voice.offer(new VoicePacket(packet,destination))) packet=new short[packet.length];
                length=0;
            }
        }
    }

    void endTalk() {
        transmitting=false;
        // Already captured packets remain queued for delivery.
    }

    void voiceControl(String kind,String recipient,Runnable success){
        sender.execute(()->{
            try{
                post("send",event(kind,true,recipient),5000);
                if(success!=null)main.post(()->{if(!closed)success.run();});
            }catch(Exception error){update("Richiesta vocale non inviata.");}
        });
    }

    void enqueue(String type, Object value, String to) {
        sender.execute(() -> {
            try {
                JSONObject event = event(type,value,to);
                android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(outbox(),event.getString("nonce")+".json"));
                var stream=file.startWrite();
                try {stream.write(event.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}
                catch (IOException e){file.failWrite(stream);throw e;}
                update("In attesa di invio");
            } catch(Exception e){update("Impossibile salvare il messaggio");}
        });
    }

    private java.io.File outbox() throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        String scope=Base64.encodeToString(digest.digest((endpoint.toString()+token).getBytes(StandardCharsets.UTF_8)),Base64.URL_SAFE|Base64.NO_WRAP);
        java.io.File folder=new java.io.File(context.getFilesDir(),"outbox/"+scope);
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Coda non disponibile");
        return folder;
    }

    private static JSONObject event(String kind,Object data,String target) throws Exception {
        return new JSONObject().put("nonce", UUID.randomUUID().toString()).put("kind", kind).put("value",data).put("to",target);
    }

    static String encode(short[] pcm) {
        ByteBuffer b=ByteBuffer.allocate(pcm.length*2).order(ByteOrder.LITTLE_ENDIAN);
        b.asShortBuffer().put(pcm);
        return Base64.encodeToString(b.array(),Base64.NO_WRAP);
    }

    static short[] decode(String data,int factor) {
        ByteBuffer b=ByteBuffer.wrap(Base64.decode(data,Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN);
        short[] pcm=new short[b.remaining()/2*factor];
        for(int i=0;i<pcm.length;){short sample=b.getShort();for(int j=0;j<factor;j++)pcm[i++]=sample;}
        return pcm;
    }

    private void flush() {
        if(closed)return;
        try {
            VoicePacket live=voice.poll();
            if(live!=null) post("send",event("voice",encode(live.samples()),live.to()),3000);
            java.io.File[] items=outbox().listFiles((folder,name)->name.endsWith(".json"));
            if(items!=null&&items.length>0){
                java.util.Arrays.sort(items,java.util.Comparator.comparingLong(java.io.File::lastModified));
                var f=new android.util.AtomicFile(items[0]);
                post("send",new JSONObject(new String(f.readFully(),StandardCharsets.UTF_8)),7000);
                f.delete();
                update("Inviato al servizio");
            }
        } catch(Exception error){update("Rete o destinatario non disponibile. Messaggi conservati.");}
    }

    private void receive() {
        if(closed)return;
        try {
            JSONObject feed=post("inbox",new JSONObject().put("after",cursor),6000);
            JSONArray items=feed.getJSONArray("events");
            for(int i=0;i<items.length();i++)cursor=Math.max(cursor,items.getJSONObject(i).getLong("id"));
            main.post(()->{if(!closed)show.accept(feed);});
        } catch(Exception ignored) { }
    }

    void receipt(long id,boolean played) {
        sender.execute(()->{
            try {post("receipt",new JSONObject().put("id",id).put("played",played),5000);}
            catch(Exception ignored) {}
        });
    }

    private JSONObject post(String path, JSONObject body, int timeout) throws Exception {
        return request(endpoint,path,body,timeout,token);
    }

    static JSONObject request(URL endpoint,String path,JSONObject body,int timeout,String token) throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL(endpoint,path).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setDoOutput(true);
            if (token != null) c.setRequestProperty("Authorization","Bearer "+token);
            c.setRequestProperty("Content-Type","application/json");
            byte[] request=body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(request.length);
            try(var out=c.getOutputStream()){out.write(request);}
            if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
            try(var in=c.getInputStream()){
                ByteArrayOutputStream result=new ByteArrayOutputStream();
                byte[] block=new byte[8192];
                for(int count;(count=in.read(block))!=-1;){
                    if(result.size()+count>8_000_000)throw new IOException("Risposta troppo grande");
                    result.write(block,0,count);
                }
                return new JSONObject(result.toString("UTF-8"));
            }
        } finally {c.disconnect();}
    }

    private void update(String text){main.post(()->{if(!closed)status.accept(text);});}
    @Override public void close(){closed=true;endTalk();receiver.shutdownNow();sender.shutdownNow();}
}
