package org.shidulab.apparecchio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.json.JSONObject;

final class NearbyPanel implements AutoCloseable {
    static final int PERMISSIONS=422, PICK=423, SAVE=424;
    private static final String SERVICE="org.shidulab.apparecchio.nearby";
    private final Activity activity;
    private final ConnectionsClient client;
    private final TextView status;
    private final Button voice, send;
    private final LinkedHashMap<String,String> found=new LinkedHashMap<>();
    private final java.util.concurrent.ExecutorService files=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile String peer;
    private String displayName=roomName();
    private boolean advertising;
    private int roomGeneration;
    private static String roomName(){return "AppArecchio · "+UUID.randomUUID().toString().substring(0,5);}
    private final java.util.function.Consumer<short[]> translated;
    private ListeningSession listening;
    private volatile boolean transmitting;
    private final java.util.concurrent.atomic.AtomicInteger inFlight=new java.util.concurrent.atomic.AtomicInteger();
    private final short[] frame=new short[1600];
    private int framePosition, phase;
    private Outgoing outgoing;
    private Receipt receiving;
    private java.io.File staged;
    private long incomingPayload;
    private static final long MAX_FILE=20L*1024*1024;
    private record Outgoing(String offer,Uri uri,String name,long bytes) {}
    private record Receipt(String offer,String name,long bytes,boolean approved) {}

    NearbyPanel(Activity activity,LinearLayout panel,java.util.function.Consumer<short[]> translated){
        this.activity=activity;
        this.translated=translated;
        client=Nearby.getConnectionsClient(activity);
        status=new TextView(activity);
        status.setText("Dispositivi vicini · scollegato");
        panel.addView(status);
        button(panel,"Crea stanza",()->permissions(()->openRoom(false)));
        button(panel,"Nuova stanza",()->permissions(()->openRoom(true)));
        button(panel,"Cerca App’Arecchio vicini",()-> permissions(this::discover));
        button(panel,"Scegli un dispositivo",this::choose);
        voice=button(panel,"Avvia voce",this::toggleVoice);
        send=button(panel,"Proponi un file",this::pick);
        button(panel,"Scollega dispositivo vicino",this::disconnect);
        voice.setEnabled(false);
        send.setEnabled(false);
    }
    private Button button(LinearLayout panel,String title,Runnable action){
        Button b=new Button(activity);
        b.setText(title);
        b.setOnClickListener(v->action.run());
        panel.addView(b);
        return b;
    }
    private Runnable permissionAction;
    private void permissions(Runnable action){
        var list=new java.util.ArrayList<String>();
        if(Build.VERSION.SDK_INT<=32)list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(Build.VERSION.SDK_INT>=31){
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
            list.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if(Build.VERSION.SDK_INT>=33)list.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        list.removeIf(p->activity.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED);
        if(list.isEmpty()){action.run();return;}
        permissionAction=action;
        activity.requestPermissions(list.toArray(new String[0]),PERMISSIONS);
    }
    void permissionResult(int[] results){
        Runnable action=permissionAction;
        permissionAction=null;
        if(results.length>0&&java.util.Arrays.stream(results).allMatch(v->v==PackageManager.PERMISSION_GRANTED)){
            if(action!=null)action.run();
        }else status.setText("Permessi necessari per cercare dispositivi vicini.");
    }
    private String ownName(){return displayName;}
    private void openRoom(boolean fresh){
        if(peer!=null){
            if(!fresh){status.setText("La stanza è aperta e il dispositivo è collegato.");return;}
            new AlertDialog.Builder(activity).setTitle("Apri una nuova stanza")
                .setMessage("Il dispositivo attualmente collegato verrà scollegato.")
                .setNegativeButton("Annulla",null)
                .setPositiveButton("Continua",(dialog,i)->{disconnect();openRoom(true);}).show();
            return;
        }
        if(advertising&&!fresh){status.setText("Stanza aperta · "+ownName());return;}
        int generation=++roomGeneration;
        client.stopDiscovery();
        client.stopAdvertising();
        advertising=false;
        if(fresh)displayName=roomName();
        advertise(generation);
    }
    private void advertise(int generation){
        client.startAdvertising(ownName(),SERVICE,lifecycle,new AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                        .addOnSuccessListener(v->{if(generation==roomGeneration){advertising=true;status.setText("Stanza aperta · "+ownName());}})
            .addOnFailureListener(e->{if(generation==roomGeneration)status.setText("Stanza non aperta. Controlla Wi-Fi, Bluetooth e permessi dei dispositivi vicini.");});
    }
    private void discover(){
        if(peer!=null){status.setText("Scollega il dispositivo prima di cercarne un altro.");return;}
        roomGeneration++;
        client.stopAdvertising();
        advertising=false;
        found.clear();
        client.startDiscovery(SERVICE,new EndpointDiscoveryCallback(){
            @Override public void onEndpointFound(String id,DiscoveredEndpointInfo info){
                found.put(id,info.getEndpointName());
                status.setText("Dispositivi trovati: "+found.size());
            }
            @Override public void onEndpointLost(String id){found.remove(id);}
        },new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
            .addOnSuccessListener(v->status.setText("Ricerca dispositivi vicini"))
            .addOnFailureListener(e->status.setText("Ricerca non disponibile."));
    }
    private void choose(){
        if(found.isEmpty()){status.setText("Avvia la ricerca e rendi visibile l’altro telefono.");return;}
        String[] ids=found.keySet().toArray(new String[0]);
        String[] names=found.values().toArray(new String[0]);
        new AlertDialog.Builder(activity).setTitle("Collega un dispositivo").setItems(names,(dialog,i)->
            client.requestConnection(ownName(),ids[i],lifecycle)
                .addOnFailureListener(e->status.setText("Richiesta non riuscita."))).show();
    }
    private final ConnectionLifecycleCallback lifecycle=new ConnectionLifecycleCallback(){
        @Override public void onConnectionInitiated(String id,ConnectionInfo info){
            AlertDialog d=new AlertDialog.Builder(activity).setTitle("Richiesta di connessione")
                .setMessage(info.getEndpointName()+"\nControlla che il codice sia identico sull’altro telefono: "+info.getAuthenticationDigits())
                .setPositiveButton("Accetta",(dialog,i)->client.acceptConnection(id,payloads))
                .setNegativeButton("Rifiuta",(dialog,i)->client.rejectConnection(id)).create();
            d.setOnCancelListener(dialog->client.rejectConnection(id));
            d.show();
        }
        @Override public void onConnectionResult(String id,ConnectionResolution result){
            if(!result.getStatus().isSuccess()){status.setText("Connessione non accettata o non disponibile.");return;}
            peer=id;
            advertising=false;
            client.stopDiscovery();
            client.stopAdvertising();
            voice.setEnabled(true);
            send.setEnabled(true);
            status.setText("Dispositivo vicino collegato");
        }
        @Override public void onDisconnected(String id){if(id.equals(peer))reset();}
    };
    private boolean allowedVoice, requestedVoice;
    private void toggleVoice(){
        if(peer==null)return;
        if(allowedVoice){control("voice-stop",null,null,0);stopVoice();return;}
        if(ListeningService.currentSession()==null){status.setText("Avvia Ascolta con le cuffie.");return;}
        requestedVoice=true;
        control("voice-offer",null,null,0);
        status.setText("In attesa di accettazione vocale");
    }
    private void startVoice(){
        listening=ListeningService.currentSession();
        if(listening==null){control("voice-stop",null,null,0);return;}
        allowedVoice=true;
        listening.nearby(this);
        transmitting=true;
        voice.setText("Ferma voce");
        status.setText("Dialogo vocale attivo");
    }
    private void stopVoice(){
        transmitting=false;
        allowedVoice=false;
        requestedVoice=false;
        if(listening!=null){listening.nearby(null);listening.mix().clearRemote();}
        listening=null;
        voice.setText("Avvia voce");
        if(peer!=null)status.setText("Dialogo vocale fermo");
    }
    void capture(short[] samples,int count){
        if(!transmitting){framePosition=0;phase=0;return;}
        for(int i=0;i<count;i++){
            if(++phase!=3)continue;
            phase=0;
            frame[framePosition++]=samples[i];
            if(framePosition<frame.length)continue;
            framePosition=0;
            if(inFlight.incrementAndGet()>3){inFlight.decrementAndGet();continue;}
            ByteBuffer packet=ByteBuffer.allocate(1+2*frame.length).order(ByteOrder.LITTLE_ENDIAN);
            packet.put((byte)1);
            for(short sample:frame)packet.putShort(sample);
            String destination=peer;
            activity.runOnUiThread(()->{
                if(!transmitting||destination==null||!destination.equals(peer)){inFlight.decrementAndGet();return;}
                client.sendPayload(destination,Payload.fromBytes(packet.array()))
                    .addOnCompleteListener(task->inFlight.decrementAndGet());
            });
        }
    }
    private PayloadCallback payloads=new PayloadCallback(){
        @Override public void onPayloadReceived(String from,Payload payload){
            if(!from.equals(peer))return;
            if(payload.getType()==Payload.Type.FILE){
                if(receiving==null||!receiving.approved()||incomingFile!=null){client.cancelPayload(payload.getId());return;}
                incomingPayload=payload.getId();incomingFile=payload;
                return;
            }
            if(payload.getType()!=Payload.Type.BYTES)return;
            byte[] data=payload.asBytes();
            if(data==null||data.length==0)return;
            if(data[0]==1){
                if(!allowedVoice||listening==null||data.length!=3201)return;
                ByteBuffer raw=ByteBuffer.wrap(data,1,data.length-1).order(ByteOrder.LITTLE_ENDIAN);
                short[] pcm=new short[(data.length-1)/2*3];
                for(int i=0;i<pcm.length;i+=3){short v=raw.getShort();pcm[i]=pcm[i+1]=pcm[i+2]=v;}
                listening.mix().playRemote(pcm);
                translated.accept(pcm);
                return;
            }
            if(data[0]!=0||data.length>1024)return;
            try{message(new JSONObject(new String(data,1,data.length-1,StandardCharsets.UTF_8)));}
            catch(Exception ignored){}
        }
        @Override public void onPayloadTransferUpdate(String from,PayloadTransferUpdate update){
            if(!from.equals(peer)||incomingFile==null||update.getPayloadId()!=incomingPayload)return;
            if(update.getBytesTransferred()>receiving.bytes()){client.cancelPayload(incomingPayload);receiving=null;incomingFile=null;return;}
            if(update.getStatus()==PayloadTransferUpdate.Status.SUCCESS)stageFile();
            else if(update.getStatus()!=PayloadTransferUpdate.Status.IN_PROGRESS){
                receiving=null;incomingFile=null;status.setText("Ricezione interrotta.");
            }
        }
    };
    private Payload incomingFile;
    private void control(String operation,String id,String name,long bytes){
        if(peer==null)return;
        try{
            byte[] json=new JSONObject().put("op",operation).put("id",id).put("name",name).put("size",bytes)
                .toString().getBytes(StandardCharsets.UTF_8);
            byte[] message=new byte[json.length+1];
            System.arraycopy(json,0,message,1,json.length);
            client.sendPayload(peer,Payload.fromBytes(message)).addOnFailureListener(e->status.setText("Richiesta non inviata."));
        }catch(Exception e){status.setText("Richiesta non valida.");}
    }
    private void message(JSONObject data){
        String operation=data.optString("op");
        switch(operation){
            case "voice-offer" -> {
                if(allowedVoice)return;
                new AlertDialog.Builder(activity).setTitle("Richiesta di dialogo vocale")
                    .setMessage("Vuoi ascoltare e parlare con il dispositivo collegato?")
                    .setNegativeButton("Rifiuta",(d,i)->control("voice-stop",null,null,0))
                    .setPositiveButton("Accetta",(d,i)->{
                        if(ListeningService.currentSession()==null){status.setText("Avvia Ascolta con le cuffie.");control("voice-stop",null,null,0);return;}
                        control("voice-accept",null,null,0);startVoice();
                    }).show();
            }
            case "voice-accept" -> {if(requestedVoice){requestedVoice=false;startVoice();}}
            case "voice-stop" -> stopVoice();
            case "file-offer" -> fileOffer(data);
            case "file-accept" -> sendFile(data.optString("id"));
            case "file-reject" -> {if(outgoing!=null&&outgoing.offer().equals(data.optString("id"))){outgoing=null;status.setText("File rifiutato.");}}
        }
    }
    private void pick(){
        if(peer==null||outgoing!=null)return;
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        activity.startActivityForResult(pick,PICK);
    }
    void selected(Uri uri){
        if(uri==null||peer==null)return;
        try(var cursor=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
            if(cursor==null||!cursor.moveToFirst()||cursor.isNull(1))throw new java.io.IOException();
            long bytes=cursor.getLong(1);
            if(bytes<=0||bytes>MAX_FILE)throw new java.io.IOException();
            outgoing=new Outgoing(UUID.randomUUID().toString(),uri,filename(cursor.getString(0)),bytes);
            control("file-offer",outgoing.offer(),outgoing.name(),outgoing.bytes());
            status.setText("In attesa di accettazione del file");
        }catch(Exception e){status.setText("Scegli un file disponibile fino a 20 MB.");}
    }
    private static String filename(String value){
        String name=value==null?"File AppArecchio":value.replaceAll("[/\\\\\\p{Cntrl}]","_").trim();
        return name.isEmpty()?"File AppArecchio":name.substring(0,Math.min(90,name.length()));
    }
    private void fileOffer(JSONObject data){
        String id=data.optString("id");
        long bytes=data.optLong("size");
        if(!id.matches("[a-f0-9-]{36}")||bytes<=0||bytes>MAX_FILE||receiving!=null||staged!=null)return;
        String from=peer;
        String name=filename(data.optString("name"));
        receiving=new Receipt(id,name,bytes,false);
        var prompt=new AlertDialog.Builder(activity).setTitle("File in arrivo").setMessage(name+" · "+bytes+" byte")
            .setPositiveButton("Accetta",(d,i)->{
                if(from==null||!from.equals(peer))return;
                receiving=new Receipt(id,name,bytes,true);control("file-accept",id,null,0);
            }).setNegativeButton("Rifiuta",(d,i)->reject(from,id)).create();
        prompt.setOnCancelListener(d->reject(from,id));prompt.show();
    }
    private void reject(String from,String id){if(from!=null&&from.equals(peer)){receiving=null;control("file-reject",id,null,0);}}
    private void sendFile(String id){
        Outgoing file=outgoing;
        if(file==null||!id.equals(file.offer()))return;
        outgoing=null;
        try{
            ParcelFileDescriptor pfd=activity.getContentResolver().openFileDescriptor(file.uri(),"r");
            if(pfd==null)throw new java.io.IOException();
            Payload payload;
            try{payload=Payload.fromFile(pfd);}catch(Exception e){pfd.close();throw e;}
            client.sendPayload(peer,payload).addOnFailureListener(e->{
                try{pfd.close();}catch(Exception ignored){}
                status.setText("Invio file non riuscito.");
            });
        }catch(Exception e){status.setText("File non disponibile.");}
    }
    private void stageFile(){
        var offer=receiving;var payload=incomingFile;
        receiving=null;incomingFile=null;
        if(payload==null||offer==null)return;
        files.execute(()->{
            java.io.File copy=null;
            try{
                Uri uri=payload.asFile().asUri();
                if(uri==null)throw new java.io.IOException();
                copy=java.io.File.createTempFile("ricevuto-",".bin",activity.getCacheDir());
                try(var in=activity.getContentResolver().openInputStream(uri);var out=new java.io.FileOutputStream(copy)){
                    if(in==null)throw new java.io.IOException();
                    byte[] b=new byte[8192];long total=0;
                    for(int n;(n=in.read(b))!=-1;){total+=n;if(total>offer.bytes())throw new java.io.IOException();out.write(b,0,n);}
                    if(total!=offer.bytes())throw new java.io.IOException();
                }
                var result=copy;
                activity.runOnUiThread(()->{
                    if(activity.isDestroyed()){result.delete();return;}
                    staged=result;
                    Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,offer.name());
                    activity.startActivityForResult(save,SAVE);
                });
            }catch(Exception e){if(copy!=null)copy.delete();activity.runOnUiThread(()->status.setText("File incompleto."));}
        });
    }
    void save(Uri uri){
        var file=staged;staged=null;
        if(file==null)return;
        files.execute(()->{
            try{
                if(uri!=null){
                    try(var stream=activity.getContentResolver().openOutputStream(uri);var input=new java.io.FileInputStream(file)){
                        if(stream==null)throw new java.io.IOException();
                        byte[] buffer = new byte[16384];
                        for (int length; (length = input.read(buffer)) != -1;) {
                            stream.write(buffer, 0, length);
                        }
                    }
                    activity.runOnUiThread(()->status.setText("File salvato."));
                }
            }catch(Exception error){activity.runOnUiThread(()->status.setText("Salvataggio non riuscito."));}
            finally{file.delete();}
        });
    }
    void listeningChanged(boolean active){if(!active&&allowedVoice){control("voice-stop",null,null,0);stopVoice();}}
    private void reset(){stopVoice();peer=null;receiving=null;incomingFile=null;outgoing=null;voice.setEnabled(false);send.setEnabled(false);status.setText("Dispositivo vicino scollegato");}
    private void disconnect(){roomGeneration++;advertising=false;client.stopDiscovery();client.stopAdvertising();client.stopAllEndpoints();reset();}
    @Override public void close(){disconnect();files.shutdown();if(staged!=null)staged.delete();}
}
