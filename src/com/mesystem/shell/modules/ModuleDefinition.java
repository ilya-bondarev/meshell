package com.mesystem.shell;

import android.content.res.Resources;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class ModuleDefinition {
    private final JSONObject schema;
    ModuleDefinition(Resources resources) {
        try(InputStream input=resources.openRawResource(R.raw.module_schema); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int count;
            while((count=input.read(buffer))!=-1) bytes.write(buffer,0,count);
            schema=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        } catch(Exception error) { throw new IllegalStateException("Module schema unavailable",error); }
    }
    int maxCharacters() { return schema.optInt("x-maxJsonCharacters"); }
    void validate(JSONObject value) throws Exception {
        if(value.toString().length()>maxCharacters()) throw new IllegalArgumentException(Language.text(R.string.module_definition_too_large));
        check(value,schema,"module");
    }
    private static void reject(String path) { throw new IllegalArgumentException(Language.text(R.string.invalid_module_field)+path); }
    private static void check(Object value,JSONObject rule,String path) throws Exception {
        switch(rule.optString("type")) {
            case "object":
                if(!(value instanceof JSONObject)) reject(path);
                JSONObject object=(JSONObject)value,properties=rule.optJSONObject("properties");
                JSONArray required=rule.optJSONArray("required");
                if(required!=null) for(int i=0;i<required.length();i++) if(!object.has(required.getString(i))) reject(path+"."+required.getString(i));
                Iterator<String> keys=object.keys();
                while(keys.hasNext()) {
                    String key=keys.next();
                    if(rule.has("propertyNames")) check(key,rule.getJSONObject("propertyNames"),path+"."+key);
                    if(properties!=null && properties.has(key)) check(object.get(key),properties.getJSONObject(key),path+"."+key);
                    else if(rule.opt("additionalProperties") instanceof JSONObject) check(object.get(key),rule.getJSONObject("additionalProperties"),path+"."+key);
                    else if(!rule.optBoolean("additionalProperties",true)) reject(path+"."+key);
                }
                break;
            case "array":
                if(!(value instanceof JSONArray)) reject(path);
                JSONArray array=(JSONArray)value; Set<String> identities=new HashSet<>();
                if(array.length()<rule.optInt("minItems",0) || array.length()>rule.optInt("maxItems",Integer.MAX_VALUE)) reject(path);
                for(int i=0;i<array.length();i++) {
                    check(array.get(i),rule.getJSONObject("items"),path+"["+i+"]");
                    if(rule.has("x-uniqueBy") && !identities.add(array.getJSONObject(i).getString(rule.getString("x-uniqueBy")))) reject(path+"["+i+"]");
                }
                break;
            case "integer":
                if(!(value instanceof Number)) reject(path);
                double number=((Number)value).doubleValue();
                if(number!=Math.rint(number) || number<rule.optDouble("minimum",-Double.MAX_VALUE) || number>rule.optDouble("maximum",Double.MAX_VALUE)) reject(path);
                break;
            case "string": if(!(value instanceof String)) reject(path); break;
        }
        if(value instanceof String) {
            String text=(String)value;
            if(text.codePointCount(0,text.length())<rule.optInt("minLength",0) || (rule.has("pattern") && !java.util.regex.Pattern.compile(rule.getString("pattern")).matcher(text).find())) reject(path);
        }
    }
    JSONObject portable(JSONObject definition) throws Exception {
        // Schema projection drops stored UI metadata and any ignored legacy fields.
        JSONObject value=(JSONObject)project(definition,schema); validate(value); return value;
    }
    private static Object project(Object value,JSONObject rule) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject source=(JSONObject)value,out=new JSONObject(),properties=rule.optJSONObject("properties"); Iterator<String> keys=source.keys();
            while(keys.hasNext()) {
                String key=keys.next();
                if(rule.optBoolean("x-portableAliases")) out.put(key,key);
                else if(properties!=null && properties.has(key)) {
                    JSONObject child=properties.getJSONObject(key); if(!child.optBoolean("x-local")) out.put(key,project(source.get(key),child));
                }
            }
            return out;
        }
        if(value instanceof JSONArray) {
            JSONArray out=new JSONArray(),source=(JSONArray)value;
            for(int i=0;i<source.length();i++) out.put(project(source.get(i),rule.getJSONObject("items"))); return out;
        }
        return value;
    }
}
