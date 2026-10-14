package com.mesystem.shell;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.security.SecureRandom;

public final class BootstrapProvider extends ContentProvider {
    static synchronized String token(Context context) {
        android.content.SharedPreferences prefs=context.getSharedPreferences("broker",Context.MODE_PRIVATE);
        String existing=prefs.getString("token",null);
        if(existing!=null) return existing;
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        char[] hex=new char[64], digits="0123456789abcdef".toCharArray();
        for(int i=0;i<32;i++) { hex[i*2]=digits[(bytes[i]&255)>>>4]; hex[i*2+1]=digits[bytes[i]&15]; }
        String value=new String(hex);
        if(!prefs.edit().putString("token",value).commit()) throw new IllegalStateException("Token storage failed");
        return value;
    }
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method,String argument,Bundle extras) {
        int uid=Binder.getCallingUid();
        if(uid!=2000 && uid!=0) throw new SecurityException("ADB shell only");
        if("chat-status".equals(method)) {
            ChatEngine chat=((MeShellApp)getContext().getApplicationContext()).chat(); Bundle result=new Bundle();
            result.putBoolean("ready",chat.ready); result.putBoolean("busy",chat.busy); result.putString("task_state",chat.taskState);
            result.putLong("agent_step",chat.agentStep); result.putString("model",chat.model); result.putBoolean("limits_loading",chat.limitsLoading);
            result.putLong("limits_updated",chat.limitsUpdated); result.putString("limits_error",chat.limitsError);
            result.putString("model_choices",new org.json.JSONArray(chat.modelChoices()).toString()); return result;
        }
        if("vault-list".equals(method)) {
            try { Bundle result=new Bundle(); result.putString("entries",((MeShellApp)getContext().getApplicationContext()).vault().list().toString()); return result; }
            catch(Exception error) { throw new IllegalStateException("Vault unavailable",error); }
        }
        if("module-start".equals(method) || "module-job".equals(method) || "module-list".equals(method) || "module-install".equals(method) || "module-manage".equals(method)) {
            try {
                ModuleHub hub=((MeShellApp)getContext().getApplicationContext()).modules(); Bundle result=new Bundle();
                if("module-install".equals(method)) { hub.install(new org.json.JSONObject(argument)); result.putBoolean("done",true); }
                else if("module-manage".equals(method)) {
                    org.json.JSONObject args=new org.json.JSONObject(argument); String id=args.getString("module"),op=args.getString("operation");
                    if(op.equals("clear")) hub.clear(id); else if(op.equals("remove")) hub.remove(id);
                    else if(op.equals("enable") || op.equals("disable")) hub.enabled(id,op.equals("enable")); else throw new IllegalArgumentException("Unknown operation");
                    result.putBoolean("done",true);
                }
                else if("module-start".equals(method)) result.putString("job",hub.startJob(new org.json.JSONObject(argument)));
                else result.putString("result","module-list".equals(method)?hub.list().toString():hub.job(argument).toString());
                return result;
            } catch(Exception error) { throw new IllegalArgumentException("Module request failed",error); }
        }
        if(!"bootstrap".equals(method)) throw new IllegalArgumentException("Unknown method");
        Bundle result=new Bundle(); result.putString("token",token(getContext())); return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws java.io.FileNotFoundException {
        if((Binder.getCallingUid()!=2000 && Binder.getCallingUid()!=0) || !"w".equals(mode))
            throw new SecurityException("Only ADB shell can provision credentials");
        if(!"/vault-import".equals(uri.getPath())) throw new SecurityException("Unknown import route");
        try {
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();
            new Thread(() -> {
                try(java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
                    byte[] buffer=new byte[4096]; int count;
                    while((count=in.read(buffer))!=-1) {
                        if(out.size()+count>512000) throw new java.io.IOException("Credential payload too large");
                        out.write(buffer,0,count);
                    }
                    org.json.JSONObject entry=new org.json.JSONObject(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
                    ((MeShellApp)getContext().getApplicationContext()).vault().put(entry.getString("name"),entry.getString("kind"),entry.get("value"));
                } catch(Exception error) { try { pipe[0].closeWithError("Credential import failed"); } catch(Exception ignored) { } }
            },"meshell-vault-import").start();
            return pipe[1];
        } catch(java.io.IOException error) { throw new java.io.FileNotFoundException("Cannot create credential pipe"); }
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
