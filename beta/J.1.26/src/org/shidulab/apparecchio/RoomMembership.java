package org.shidulab.apparecchio;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class RoomMembership {
    private static final String ALIAS="apparecchiocchio-room";
    private final android.content.SharedPreferences prefs;

    RoomMembership(Context context){prefs=context.getSharedPreferences("occhio-room",Context.MODE_PRIVATE);}
    boolean exists(){return prefs.contains("member");}

    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    void save(String endpoint,String member,String invite) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key());
        cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
        byte[] coded=cipher.doFinal((member+"\n"+invite).getBytes(StandardCharsets.UTF_8));
        if(!prefs.edit().putString("member",Base64.encodeToString(coded,Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).commit())
            throw new java.io.IOException("Impossibile salvare l’accesso alla stanza.");
    }

    String[] read(String endpoint) throws Exception {
        if(!exists())return null;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.DEFAULT)));
        cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("member",""),Base64.DEFAULT)),StandardCharsets.UTF_8).split("\n",2);
    }
}
