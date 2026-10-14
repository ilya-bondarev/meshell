package com.mesystem.shell;

import android.content.Context;
import java.io.*;
import org.json.JSONObject;

final class ChatBackup {
    private static File directory(Context c) { return new File(c.getDatabasePath("chats.db").getParentFile(),"chats-before-v6"); }
    static void beforeOpen(Context c) {
        File db=c.getDatabasePath("chats.db"),destination=directory(c);
        if(!db.isFile() || db.length()<100 || destination.isDirectory()) return;
        try {
            int version;
            try(RandomAccessFile input=new RandomAccessFile(db,"r")) { input.seek(60); version=input.readInt(); }
            if(version>=6) return;
            // Called on process startup before SQLite opens this database. The old APK process
            // is already gone after replacement. Preserve WAL as well as the main file.
            File pending=new File(destination.getParentFile(),"chats-before-v6.pending");
            if(pending.exists()) { File[] old=pending.listFiles(); if(old!=null) for(File file:old) if(!file.delete()) throw new IOException("Cannot clear incomplete migration snapshot"); if(!pending.delete()) throw new IOException("Cannot clear snapshot directory"); }
            if(!pending.mkdir()) throw new IOException("Cannot create migration snapshot");
            pending.setReadable(false,false); pending.setWritable(false,false); pending.setExecutable(false,false);
            pending.setReadable(true,true); pending.setWritable(true,true); pending.setExecutable(true,true);
            for(String suffix:new String[]{"","-wal"}) {
                File from=new File(db.getPath()+suffix); if(!from.isFile()) continue;
                File to=new File(pending,"chats.db"+suffix);
                try(FileInputStream input=new FileInputStream(from); FileOutputStream output=new FileOutputStream(to)) {
                    byte[] buffer=new byte[65536]; int count;
                    while((count=input.read(buffer))!=-1) output.write(buffer,0,count);
                    output.getFD().sync();
                }
                to.setReadable(false,false); to.setWritable(false,false); to.setReadable(true,true); to.setWritable(true,true);
            }
            if(!pending.renameTo(destination)) throw new IOException("Cannot finalize migration snapshot");
        } catch(Exception e) {
            // SQLite still migrates transactionally. A failed optional snapshot is never reported successful.
            android.util.Log.w("MeShell","Chat migration snapshot unavailable: "+e.getClass().getSimpleName());
        }
    }
    static JSONObject status(Context c) {
        JSONObject result=new JSONObject();
        try {
            File directory=directory(c),db=new File(directory,"chats.db");
            result.put("present",db.isFile()).put("location","app-private/chats-before-v6").put("vault_included",false);
            if(db.isFile()) { result.put("bytes",db.length()).put("wal_present",new File(directory,"chats.db-wal").isFile()); }
        } catch(Exception ignored) { }
        return result;
    }
}
