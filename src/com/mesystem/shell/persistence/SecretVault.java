package com.mesystem.shell;

import android.content.Context;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class SecretVault {
    private final SecretStore store;
    volatile int revision;
    SecretVault(Context context) { store=new SecretStore(context,"vault","meshell-vault-v1"); }
    private JSONObject all() throws Exception { JSONObject value=store.load(); return value==null?new JSONObject():value; }
    synchronized void put(String name,String kind,Object value) throws Exception {
        if(!name.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException(Language.text(R.string.name_latin_letters_digits));
        if(value==null || value.toString().length()>256000) throw new IllegalArgumentException(Language.text(R.string.secret_is_empty_or_too_large));
        JSONObject data=all(); data.put(name,new JSONObject().put("kind",kind).put("value",value)); store.save(data); revision++;
    }
    synchronized Object get(String name) throws Exception {
        JSONObject item=all().optJSONObject(name);
        if(item==null) throw new IllegalArgumentException(Language.text(R.string.secret_not_found)+name);
        return item.get("value");
    }
    synchronized JSONObject object(String name) throws Exception {
        Object v=get(name); return v instanceof JSONObject?(JSONObject)v:new JSONObject(v.toString());
    }
    synchronized void remove(String name) throws Exception { JSONObject data=all(); data.remove(name); store.save(data); revision++; }
    synchronized JSONArray list() throws Exception {
        JSONObject data=all(); JSONArray result=new JSONArray(); Iterator<String> keys=data.keys();
        while(keys.hasNext()) { String name=keys.next(); result.put(new JSONObject().put("name",name).put("kind",data.getJSONObject(name).optString("kind"))); }
        return result;
    }
    synchronized String generate(String name,int bytes) throws Exception {
        if(all().has(name)) throw new IllegalArgumentException(Language.text(R.string.vault_alias_already_exists));
        byte[] data=new byte[Math.max(16,Math.min(128,bytes))]; new SecureRandom().nextBytes(data);
        put(name,"secret",android.util.Base64.encodeToString(data,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING));
        return Language.text(R.string.secret_created_in_vault)+name+Language.text(R.string.value_is_not_sent_to_the_model);
    }
    private void collect(Object value,List<String> masks,boolean sensitive) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject obj=(JSONObject)value; Iterator<String> keys=obj.keys();
            while(keys.hasNext()) {
                String key=keys.next();
                collect(obj.get(key),masks,sensitive || SecretFields.sensitive(key));
            }
        } else if(value instanceof JSONArray) {
            JSONArray arr=(JSONArray)value; for(int i=0;i<arr.length();i++) collect(arr.get(i),masks,sensitive);
        } else if(sensitive && value instanceof String && !((String)value).isEmpty()) masks.add((String)value);
    }
    synchronized String redact(String text) throws Exception {
        List<String> masks=new ArrayList<>(); JSONObject values=all(); Iterator<String> names=values.keys();
        while(names.hasNext()) {
            JSONObject item=values.getJSONObject(names.next()); String kind=item.optString("kind");
            collect(item.get("value"),masks,kind.equals("secret"));
        }
        masks.sort((a,b) -> Integer.compare(b.length(),a.length()));
        for(String mask:masks) {
            text=text.replace(mask,"[SECRET REDACTED]");
            String escaped=JSONObject.quote(mask);
            text=text.replace(escaped.substring(1,escaped.length()-1),"[SECRET REDACTED]");
        }
        return text;
    }
}
