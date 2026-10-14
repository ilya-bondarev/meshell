package com.mesystem.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class SecretStore {
    private final String alias;
    private final SharedPreferences prefs;
    SecretStore(Context context) { this(context,"credentials","meshell-chatgpt-v1"); }
    SecretStore(Context context,String name,String alias) {
        prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE); this.alias=alias;
    }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(!store.containsAlias(alias)) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(alias,null);
    }
    synchronized JSONObject load() throws Exception {
        String saved=prefs.getString("sealed",null);
        if(saved==null) return null;
        String[] parts=saved.split("\\.",2);
        if(parts.length!=2) throw new IllegalStateException(Language.text(R.string.damaged_sign_in_storage));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }
    synchronized void save(JSONObject value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
        String sealed=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+"."+
            Base64.encodeToString(cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString("sealed",sealed).commit()) throw new IllegalStateException(Language.text(R.string.cannot_save_sign_in));
    }
    synchronized void clear() { prefs.edit().clear().commit(); }
}
