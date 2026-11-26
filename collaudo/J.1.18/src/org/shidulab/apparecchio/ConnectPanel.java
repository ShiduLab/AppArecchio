package org.shidulab.apparecchio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.location.LocationManager;


import android.view.MotionEvent;
import android.widget.*;
import java.util.LinkedHashMap;
import org.json.JSONObject;

final class ConnectPanel implements AutoCloseable {
    private final Activity activity;
    private final EditText nickname, message;
    private final Button connect;
    private final RoomMembership membership;
    private final ToggleButton conversation;
    private final TextView status, delivery;
    private final Spinner participant;
    private final android.widget.SeekBar volume;
    private final LinkedHashMap<String,String> names=new LinkedHashMap<>();
    private final LinkedHashMap<Long,JSONObject> inbox=new LinkedHashMap<>();
    private Connect channel;
    private boolean pending;
    private String permittedVoice, wantedVoice;
    private ListeningSession attached;

    private final java.util.function.Consumer<short[]> remoteAudio;
    ConnectPanel(Activity activity,LinearLayout panel,java.util.function.Consumer<short[]> remoteAudio) {
        this.activity=activity;
        this.remoteAudio=remoteAudio;
        membership=new RoomMembership(activity);
        TextView title=new TextView(activity);
        title.setText("COMUNICA");
        title.setTextSize(22);
        title.setAccessibilityHeading(true);
        panel.addView(title);
        nickname=field(panel,"Nome nella stanza");
        nickname.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(50)});
        connect=button(panel,membership.exists()?"Rientra nella stanza":"Crea stanza Internet",()->{if(channel==null){if(membership.exists())resume();else start(null);}else close();});
        button(panel,"Nuova stanza Internet",()->{if(channel==null)start(null);});

        participant=new Spinner(activity);
        panel.addView(participant);
        button(panel,"Richiedi dialogo sui dati",this::requestVoice);
        button(panel,"Chiudi dialogo sui dati",this::stopVoice);
        conversation=new ToggleButton(activity);
        conversation.setTextOn("Conversazione attiva");
        conversation.setTextOff("Conversazione ferma");
        conversation.setChecked(false);
        conversation.setOnCheckedChangeListener((b,on)->talk(on));
        panel.addView(conversation);
        Button hold=button(panel,"Tieni premuto per parlare",()->{});
        hold.setOnTouchListener((v,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN -> talk(true);
                case MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> talk(conversation.isChecked());
                default -> { }
            }
            return true;
        });
        hold.setOnClickListener(v->{conversation.setChecked(!conversation.isChecked());});
        volume=new android.widget.SeekBar(activity);
        volume.setMax(100);
        volume.setProgress(40);
        volume.setContentDescription("Volume comunicazione");
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int v,boolean user){if(attached!=null)attached.mix().remoteVolume(v/100f);}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        panel.addView(volume);
        message=field(panel,"Scrivi un messaggio");
        button(panel,"Accoda messaggio",()->{
            String text=message.getText().toString().trim();
            if(channel==null||text.isEmpty()||text.length()>2000){status("Collega il servizio e scrivi il messaggio.");return;}
            channel.enqueue("text",text,target());
            message.setText("");
        });
        button(panel,"Messaggi ricevuti",this::showInbox);
        button(panel,"Condividi posizione",this::location);
        status=new TextView(activity);
        panel.addView(status);
        delivery=new TextView(activity);
        panel.addView(delivery);
        status("Comunicazione scollegata");
    }



    private EditText field(LinearLayout panel,String hint){
        EditText e=new EditText(activity);e.setHint(hint);e.setSingleLine();panel.addView(e);return e;
    }
    private Button button(LinearLayout panel,String text,Runnable action){
        Button b=new Button(activity);b.setText(text);b.setOnClickListener(v->action.run());panel.addView(b);return b;
    }
    private void status(String status){this.status.setText(status);}

    void acceptLink(android.net.Uri link) {
        if(link==null)return;
        try {
            java.net.URL root=AppService.root(activity);
            java.net.URI incoming=new java.net.URI(link.toString());
            String query=incoming.getRawQuery();
            if(!root.getProtocol().equals(incoming.getScheme())
                    || !root.getAuthority().equals(incoming.getRawAuthority())
                    || !(root.getPath()+"invito").equals(incoming.getPath())
                    || query==null || !query.matches("stanza=[a-f0-9]{48}")) return;
            String invite=query.substring(7);
            if(!nickname.getText().toString().trim().isEmpty()){
                start(invite);
                return;
            }
            EditText name=new EditText(activity);
            name.setSingleLine();
            name.setHint("Nome nella stanza");
            name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(50)});
            new AlertDialog.Builder(activity).setTitle("Entra nella stanza")
                .setView(name).setNegativeButton("Annulla",null)
                .setPositiveButton("Entra",(dialog,i)->{
                    nickname.setText(name.getText());
                    start(invite);
                }).show();
        }catch(Exception error){status("Invito non disponibile.");}
    }

    private void resume() {
        if(pending||channel!=null)return;
        pending=true;
        connect.setEnabled(false);
        new Thread(()->{
            try {
                java.net.URL root=AppService.root(activity);
                String issued=membership.read(root.toString());
                if(issued==null)throw new IllegalStateException();
                Connect.request(root,"inbox",new JSONObject().put("after",System.currentTimeMillis()*1000),6000,issued);
                activity.runOnUiThread(()->open(root,issued,""));
            }catch(Exception error){activity.runOnUiThread(()->{
                pending=false;
                connect.setEnabled(true);
                status("Stanza non raggiungibile. Riprova quando torna la connessione.");
            });}
        },"Rientro in Comunica").start();
    }

    private void start(String invite) {
        if(pending || channel!=null)return;
        String name=nickname.getText().toString().trim();
        if(name.isEmpty()){status("Scrivi il nome da mostrare nella stanza.");return;}
        pending=true;
        connect.setEnabled(false);
        status("Connessione al servizio…");
        new Thread(()->{
            try {
                java.net.URL root=AppService.root(activity);
                JSONObject body=new JSONObject().put("name",name);
                if(invite!=null)body.put("invite",invite);
                JSONObject reply=Connect.request(root,invite==null?"room":"join",body,10000,null);
                String issued=reply.getString("key");
                String share=reply.optString("invite");
                membership.save(root.toString(),issued);
                activity.runOnUiThread(()->open(root,issued,share));
            }catch(Exception error){activity.runOnUiThread(()->{
                pending=false;connect.setEnabled(true);
                if(!activity.isDestroyed())status(error.getMessage()==null?"Servizio non disponibile.":error.getMessage());
            });}
        },"Accesso a Comunica").start();
    }

    private void open(java.net.URL root,String issued,String share) {
        pending=false;connect.setEnabled(true);
        if(activity.isDestroyed())return;
        try{
            channel=new Connect(activity,root.toString(),issued,this::receive,this::status);
            attached=ListeningService.currentSession();
            if(attached!=null)attach(attached);
            connect.setText("Scollega");
            status("Stanza aperta");
            if(!share.isEmpty()){
                String url=new java.net.URL(root,"invito?stanza="+share).toString();
                android.content.Intent send=new android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,url);
                activity.startActivity(android.content.Intent.createChooser(send,"Invita un partecipante"));
            }
        }catch(Exception error){status("Servizio non disponibile.");}
    }
    private void attach(ListeningSession session){
        attached=session;
        attached.connection(channel);
        attached.mix().remoteVolume(volume.getProgress()/100f);
    }

    private String target(){
        int index=participant.getSelectedItemPosition();
        if(index<=0)return "all";
        return names.keySet().stream().skip(index-1L).findFirst().orElse("all");
    }
    private void requestVoice(){
        Connect owner=channel;
        String to=target();
        if(owner==null||"all".equals(to)){status("Scegli un partecipante per il dialogo.");return;}
        if(ListeningService.currentSession()==null){status("Attiva prima Ascolta con le cuffie.");return;}
        wantedVoice=to;
        owner.voiceControl("voice-request",to,()->status("In attesa di accettazione vocale"));
    }

    private void stopVoice(){
        String to=permittedVoice==null?wantedVoice:permittedVoice;
        permittedVoice=null;wantedVoice=null;
        if(channel!=null){
            channel.endTalk();
            if(to!=null)channel.voiceControl("voice-stop",to,null);
        }
        conversation.setChecked(false);
    }

    private boolean controlEvent(JSONObject e){
        String from=e.optString("from");
        switch(e.optString("kind")){
            case "voice-request" -> {
                Connect owner=channel;
                if(permittedVoice!=null||wantedVoice!=null)return true;
                new AlertDialog.Builder(activity).setTitle("Richiesta di dialogo vocale")
                    .setMessage(names.getOrDefault(from,"Un partecipante")+" vuole parlarti.")
                    .setNegativeButton("Rifiuta",(dlg,i)->{if(channel==owner)owner.voiceControl("voice-stop",from,null);})
                    .setPositiveButton("Accetta",(dlg,i)->{
                        if(channel!=owner)return;
                        if(ListeningService.currentSession()==null){status("Attiva prima Ascolta con le cuffie.");owner.voiceControl("voice-stop",from,null);return;}
                        owner.voiceControl("voice-accept",from,()->{permittedVoice=from;status("Dialogo accettato. Puoi parlare.");});
                    }).show();
                return true;
            }
            case "voice-accept" -> {
                if(from.equals(wantedVoice)){permittedVoice=from;wantedVoice=null;status("Dialogo accettato. Puoi parlare.");}
                return true;
            }
            case "voice-stop" -> {
                if(from.equals(permittedVoice)||from.equals(wantedVoice)){
                    channel.endTalk();permittedVoice=null;wantedVoice=null;conversation.setChecked(false);
                    status("Dialogo chiuso o rifiutato.");
                }
                return true;
            }
            default -> {return false;}
        }
    }

    private void talk(boolean on){
        if(channel==null){if(on)status("Collega prima il servizio.");return;}
        ListeningSession session=ListeningService.currentSession();
        if(on&&session==null){status("Attiva Ascolta per usare il microfono condiviso.");return;}
        if(session!=null&&attached!=session)attach(session);
        if(on&&permittedVoice==null){
            status("Richiedi il dialogo e attendi che venga accettato.");
            conversation.setChecked(false);
            return;
        }
        if(permittedVoice!=null)channel.destination=permittedVoice;
        if(on){channel.transmitting=true;status("Microfono in trasmissione.");}
        else{channel.endTalk();status("Microfono remoto fermo.");}
    }

    private void receive(JSONObject feed){
        if(channel==null)return;
        LinkedHashMap<String,String> current=new LinkedHashMap<>();
        var members=feed.optJSONArray("members");
        String own=feed.optString("self");
        if(members!=null)for(int i=0;i<members.length();i++){
            JSONObject person=members.optJSONObject(i);
            if(person!=null&&!own.equals(person.optString("id")))current.put(person.optString("id"),person.optString("name"));
        }
        if(!names.equals(current)){
            String prior=target();
            names.clear();
            names.putAll(current);
            var labels=new java.util.ArrayList<String>();
            labels.add("Tutti nella stanza");
            labels.addAll(names.values());
            var adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            participant.setAdapter(adapter);
            participant.setSelection(java.util.stream.IntStream.range(0,names.size())
                .filter(i->names.keySet().stream().skip(i).findFirst().orElse("").equals(prior)).findFirst().orElse(-1)+1);
        }
        JSONObject counts=feed.optJSONObject("counts");
        if(counts!=null)delivery.setText("Inviati "+counts.optInt("sent")+" · Consegnati "+
            counts.optInt("delivered")+" · Vocali ascoltati "+counts.optInt("played"));
        var events=feed.optJSONArray("events");
        if(events==null)return;
        for(int i=0;i<events.length();i++){
            JSONObject e=events.optJSONObject(i);
            if(e==null||controlEvent(e))continue;
            if("voice".equals(e.optString("kind"))){
                if(!e.optString("from").equals(permittedVoice))continue;
                ListeningSession listening=ListeningService.currentSession();
                if(listening!=null){
                    if(attached!=listening)attach(listening);
                    if (System.currentTimeMillis()-e.optLong("time") > 3000) continue;
                    short[] pcm=Connect.decode(e.optString("value"),3);
                    listening.mix().playRemote(pcm);
                    remoteAudio.accept(pcm);
                }
            }else{
                inbox.put(e.optLong("id"),e);
                while(inbox.size()>100)inbox.remove(inbox.keySet().iterator().next());
                channel.receipt(e.optLong("id"),false);
                status("Nuovo messaggio da "+names.getOrDefault(e.optString("from"),"un partecipante"));
            }
        }
    }

    private void showInbox(){
        JSONObject[] events=inbox.values().toArray(new JSONObject[0]);
        if(events.length==0){status("Nessun messaggio.");return;}
        String[] labels=java.util.Arrays.stream(events).map(e->names.getOrDefault(e.optString("from"),"Partecipante")
            +" · "+e.optString("kind")).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle("Messaggi ricevuti").setItems(labels,(dialog,i)->{
            JSONObject message=events[i];
            if("audio".equals(message.optString("kind"))){
                ListeningSession s=ListeningService.currentSession();
                if(s==null){status("Attiva Ascolta per riprodurre il messaggio.");return;}
                Connect owner=channel;
                long id=message.optLong("id");
                s.mix().playNote(Connect.decode(message.optString("value"),1),
                    ()->activity.runOnUiThread(()->{if(channel==owner&&owner!=null)owner.receipt(id,true);}));
            }else new AlertDialog.Builder(activity).setMessage(message.optString("value"))
                    .setPositiveButton("Chiudi",null).show();
        }).show();
    }

    boolean enqueueAudio(short[] pcm){
        if(channel==null)return false;
        channel.enqueue("audio",Connect.encode(pcm),target());
        return true;
    }

    private void location(){
        if(channel==null){status("Collega prima il servizio.");return;}
        if(activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},421);
            status("Consenti la posizione e tocca di nuovo Condividi posizione.");
            return;
        }
        new AlertDialog.Builder(activity).setMessage("Inviare la posizione al destinatario selezionato?")
            .setNegativeButton("Annulla",null).setPositiveButton("Invia",(dialog,which)->{
                Connect owner=channel;String recipient=target();
                if(owner==null)return;
                try {
                    activity.getSystemService(LocationManager.class).getCurrentLocation(
                        LocationManager.NETWORK_PROVIDER,null,activity.getMainExecutor(),result->{
                            if(channel!=owner||result==null){status("Posizione non disponibile.");return;}
                            try{owner.enqueue("position",new JSONObject().put("lat",result.getLatitude())
                                .put("lon",result.getLongitude()),recipient);}
                            catch(Exception e){status("Invio posizione non riuscito.");}
                        });
                } catch(Exception e){status("Posizione non disponibile.");}
            }).show();
    }

    void listeningChanged(boolean active){if(!active&&channel!=null)stopVoice();}
    @Override public void close(){
        stopVoice();
        if(attached!=null){attached.connection(null);attached.mix().clearRemote();attached=null;}
        if(channel!=null)channel.close();
        channel=null;
        inbox.clear();
        names.clear();
        participant.setAdapter(null);
        conversation.setChecked(false);
        connect.setText(membership.exists()?"Rientra nella stanza":"Crea stanza Internet");
        delivery.setText("");
        status("Comunicazione scollegata");
    }
}
