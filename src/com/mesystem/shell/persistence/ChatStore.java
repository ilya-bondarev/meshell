package com.mesystem.shell;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

final class ChatStore extends SQLiteOpenHelper {
    static final class Message {
        final long id,thread;
        final String role;
        String text,state,error,wire="[]",actions="[]",attachments="[]";
        int versions,attempt;
        Message(long id,long thread,String role,String text,String state,String error) {
            this.id=id; this.thread=thread; this.role=role; this.text=text; this.state=state; this.error=error;
        }
        Message copy() { Message m=new Message(id,thread,role,text,state,error); m.wire=wire; m.actions=actions; m.attachments=attachments; m.versions=versions; m.attempt=attempt; return m; }
    }
    static final class Conversation {
        final long id,updated; final String title,preview,state,profile,settings,draft;
        final boolean pinned,archived,unread; final int count;
        Conversation(long id,String title,long updated,String preview,String state,String profile,String settings,String draft,boolean pinned,boolean archived,boolean unread,int count) {
            this.id=id; this.title=title; this.updated=updated; this.preview=preview; this.state=state; this.profile=profile;
            this.settings=settings; this.draft=draft; this.pinned=pinned; this.archived=archived; this.unread=unread; this.count=count;
        }
    }
    static final class Profile {
        final String id; final AgentConfig config;
        Profile(String id,AgentConfig config) { this.id=id; this.config=config; }
    }
    ChatStore(Context context) { super(context,"chats.db",null,6); ChatBackup.beforeOpen(context); setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE threads (id INTEGER PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL,"+
            "pinned INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0, renamed INTEGER NOT NULL DEFAULT 0,"+
            "seen INTEGER NOT NULL DEFAULT 0, seen_attempt INTEGER NOT NULL DEFAULT 0, profile TEXT NOT NULL DEFAULT '', settings TEXT NOT NULL DEFAULT '',"+
            "draft TEXT NOT NULL DEFAULT '', draft_files TEXT NOT NULL DEFAULT '[]', draft_extra TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, thread INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE, "+
            "role TEXT NOT NULL, text TEXT NOT NULL, state TEXT NOT NULL, error TEXT NOT NULL DEFAULT '', wire TEXT NOT NULL DEFAULT '[]', actions TEXT NOT NULL DEFAULT '[]', attachments TEXT NOT NULL DEFAULT '[]', attempt INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX thread_messages ON messages(thread,id)"); managementTables(db); counters(db);
    }
    private static void managementTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE agent_profiles (id TEXT PRIMARY KEY, settings TEXT NOT NULL, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE message_versions (id INTEGER PRIMARY KEY, message INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE, text TEXT NOT NULL, state TEXT NOT NULL, error TEXT NOT NULL, saved INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX message_attempts ON message_versions(message,id)");
    }
    private static void counters(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chat_counters (name TEXT PRIMARY KEY, value INTEGER NOT NULL)");
        db.execSQL("INSERT INTO chat_counters SELECT 'thread',COALESCE(MAX(id),0) FROM threads");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<2) db.execSQL("ALTER TABLE messages ADD COLUMN wire TEXT NOT NULL DEFAULT '[]'");
        if(oldVersion<3) db.execSQL("ALTER TABLE messages ADD COLUMN actions TEXT NOT NULL DEFAULT '[]'");
        if(oldVersion<4) db.execSQL("ALTER TABLE messages ADD COLUMN attachments TEXT NOT NULL DEFAULT '[]'");
        if(oldVersion<5) {
            for(String definition:new String[]{"pinned INTEGER NOT NULL DEFAULT 0","archived INTEGER NOT NULL DEFAULT 0","renamed INTEGER NOT NULL DEFAULT 0","seen INTEGER NOT NULL DEFAULT 0","profile TEXT NOT NULL DEFAULT ''","settings TEXT NOT NULL DEFAULT ''","draft TEXT NOT NULL DEFAULT ''","draft_files TEXT NOT NULL DEFAULT '[]'","draft_extra TEXT NOT NULL DEFAULT ''"})
                db.execSQL("ALTER TABLE threads ADD COLUMN "+definition);
            managementTables(db);
        }
        if(oldVersion<6) {
            db.execSQL("ALTER TABLE threads ADD COLUMN seen_attempt INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0");
            counters(db);
        }
    }
    void migrateUi(SharedPreferences prefs,AgentConfig defaults) {
        if(prefs.getBoolean("chat-management-migrated",false)) return;
        SQLiteDatabase database=getWritableDatabase(); database.beginTransaction();
        try {
            try(Cursor rows=database.rawQuery("SELECT id,settings FROM threads",null)) {
                while(rows.moveToNext()) {
                    long id=rows.getLong(0); ContentValues v=new ContentValues();
                    if(rows.getString(1).isEmpty()) { v.put("settings",defaults.encode()); v.put("profile","primary"); }
                    if(prefs.contains("draft-"+id)) v.put("draft",prefs.getString("draft-"+id,""));
                    if(prefs.contains("attachments-"+id)) v.put("draft_files",prefs.getString("attachments-"+id,"[]"));
                    if(v.size()>0) database.update("threads",v,"id=?",new String[]{Long.toString(id)});
                }
            }
            // Existing answers are not suddenly labelled unread after upgrading.
            database.execSQL("UPDATE threads SET seen=COALESCE((SELECT MAX(id) FROM messages WHERE thread=threads.id),0)");
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        SharedPreferences.Editor edit=prefs.edit().putBoolean("chat-management-migrated",true);
        for(String key:prefs.getAll().keySet()) if(key.startsWith("draft-") || key.startsWith("attachments-")) edit.remove(key);
        edit.apply();
    }
    void recover() {
        SQLiteDatabase database=getWritableDatabase();
        try(Cursor rows=database.rawQuery("SELECT id,text FROM messages WHERE role='assistant' AND actions='[]' AND text LIKE '%**▶ %'",null)) {
            while(rows.moveToNext()) try {
                JSONArray actions=new JSONArray(); String text=ActionLog.migrate(rows.getString(1),actions);
                if(actions.length()>0) { ContentValues values=new ContentValues(); values.put("text",text); values.put("actions",actions.toString()); database.update("messages",values,"id=?",new String[]{rows.getString(0)}); }
            } catch(Exception ignored) { }
        }
        database.execSQL("UPDATE messages SET state='interrupted',error='Task interrupted: process stopped or app updated. Changes may have happened; check the state before continuing.' WHERE state='streaming'");
        try(Cursor rows=database.rawQuery("SELECT id,actions FROM messages WHERE state='interrupted'",null)) {
            while(rows.moveToNext()) try {
                JSONArray log=new JSONArray(rows.getString(1));
                for(int i=0;i<log.length();i++) if("running".equals(log.getJSONObject(i).optString("state"))) log.getJSONObject(i).put("state","interrupted").put("output",Language.text(R.string.outcome_unconfirmed_check_the_actual_state_first));
                ContentValues v=new ContentValues(); v.put("actions",log.toString()); database.update("messages",v,"id=?",new String[]{rows.getString(0)});
            } catch(Exception ignored) { }
        }
    }
    static String shortText(String text,int length) {
        String value=text==null?"":text.replaceAll("\\s+"," ").trim();
        int n=value.codePointCount(0,value.length()); return n<=length?value:value.substring(0,value.offsetByCodePoints(0,length))+"…";
    }
    long create() {
        SQLiteDatabase database=getWritableDatabase(); database.beginTransaction();
        try {
            long next;
            try(Cursor c=database.rawQuery("SELECT MAX(value,COALESCE((SELECT MAX(id) FROM threads),0))+1 FROM chat_counters WHERE name='thread'",null)) { c.moveToFirst(); next=c.getLong(0); }
            ContentValues v=new ContentValues(); v.put("id",next); v.put("title",""); v.put("updated",System.currentTimeMillis());
            database.insertOrThrow("threads",null,v);
            database.execSQL("UPDATE chat_counters SET value=? WHERE name='thread'",new Object[]{next});
            database.setTransactionSuccessful(); return next;
        } finally { database.endTransaction(); }
    }
    long blank(AgentConfig config,String profile) {
        // One reusable internal placeholder. Truly empty chats never appear in history.
        long id=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM threads WHERE NOT EXISTS(SELECT 1 FROM messages WHERE thread=threads.id) AND draft='' AND draft_files='[]' AND draft_extra='' AND archived=0 AND pinned=0 AND renamed=0 ORDER BY id LIMIT 1",null)) { if(c.moveToFirst()) id=c.getLong(0); }
        if(id==0) id=create(); settings(id,config,profile); return id;
    }
    boolean exists(long id) { try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM threads WHERE id=?",new String[]{Long.toString(id)})) { return c.moveToFirst(); } }
    private static final String DETAILS="SELECT t.id,t.title,t.updated,t.pinned,t.archived,t.profile,t.settings,t.draft,t.draft_files,t.draft_extra,t.seen,"+
        "(SELECT COUNT(*) FROM messages WHERE thread=t.id),"+
        "COALESCE((SELECT substr(text,1,121) FROM messages WHERE thread=t.id AND text<>'' ORDER BY id DESC LIMIT 1),''),"+
        "COALESCE((SELECT state FROM messages WHERE thread=t.id ORDER BY id DESC LIMIT 1),''),"+
        "COALESCE((SELECT MAX(id) FROM messages WHERE thread=t.id AND role='assistant' AND state<>'streaming'),0),"+
        "COALESCE((SELECT attempt FROM messages WHERE thread=t.id AND role='assistant' AND state<>'streaming' ORDER BY id DESC LIMIT 1),0),t.seen_attempt,t.renamed FROM threads t ";
    private Conversation conversation(Cursor c) {
        int count=c.getInt(11); String title=c.getString(1),draft=c.getString(7),preview=c.getString(12);
        boolean hasDraft=!draft.isEmpty() || !"[]".equals(c.getString(8)) || !c.getString(9).isEmpty();
        if(count==0 && c.getInt(17)==0) title=hasDraft?(draft.trim().isEmpty()?Language.text(R.string.attachment_draft):shortText(draft,64)):Language.text(R.string.new_chat);
        if(!draft.isEmpty()) preview=shortText(draft,120); else if(preview.isEmpty() && hasDraft) preview=Language.text(R.string.attachments_not_sent_yet);
        return new Conversation(c.getLong(0),title,c.getLong(2),shortText(preview,120),c.getString(13),c.getString(5),c.getString(6),
            hasDraft?Language.text(R.string.draft):"",c.getInt(3)!=0,c.getInt(4)!=0,c.getLong(14)>c.getLong(10) || (c.getLong(14)>0 && c.getLong(14)==c.getLong(10) && c.getInt(15)>c.getInt(16)),count);
    }
    Conversation conversation(long id) {
        try(Cursor c=getReadableDatabase().rawQuery(DETAILS+"WHERE t.id=?",new String[]{Long.toString(id)})) { return c.moveToFirst()?conversation(c):null; }
    }
    List<Conversation> conversations() { return conversations("",false); }
    List<Conversation> conversations(String query,boolean archived) {
        List<Conversation> result=new ArrayList<>(); String needle=query.trim().toLowerCase(java.util.Locale.ROOT);
        String where="WHERE t.archived=? AND (EXISTS(SELECT 1 FROM messages WHERE thread=t.id) OR t.draft<>'' OR t.draft_files<>'[]' OR t.draft_extra<>'') ";
        try(Cursor c=getReadableDatabase().rawQuery(DETAILS+where+"ORDER BY t.pinned DESC,t.updated DESC,t.id DESC",new String[]{archived?"1":"0"})) {
            while(c.moveToNext()) {
                Conversation row=conversation(c);
                // SQLite's built-in NOCASE/LIKE folds ASCII only. Russian search must also fold Cyrillic.
                if(needle.isEmpty() || (row.title+"\n"+c.getString(7)).toLowerCase(java.util.Locale.ROOT).contains(needle) || messageMatches(row.id,needle)) result.add(row);
            }
        }
        return result;
    }
    private boolean messageMatches(long thread,String needle) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT text FROM messages WHERE thread=?",new String[]{Long.toString(thread)})) {
            while(c.moveToNext()) if(c.getString(0).toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
        } catch(android.database.sqlite.SQLiteBlobTooBigException oversized) {
            for(Message m:largeMessages(thread)) if(m.text.toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }
    List<Message> messages(long thread) {
        List<Message> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT m.id,role,text,state,error,wire,actions,attachments,(SELECT COUNT(*) FROM message_versions WHERE message=m.id),attempt FROM messages m WHERE thread=? ORDER BY m.id",new String[]{Long.toString(thread)})) {
            while(c.moveToNext()) { Message m=new Message(c.getLong(0),thread,c.getString(1),c.getString(2),c.getString(3),c.getString(4)); m.wire=c.getString(5); m.actions=c.getString(6); m.attachments=c.getString(7); m.versions=c.getInt(8); m.attempt=c.getInt(9); result.add(m); }
        } catch(android.database.sqlite.SQLiteBlobTooBigException oversized) {
            // Tool journals and wire payloads can exceed Android's CursorWindow.
            // Keep the fast path for ordinary chats; never truncate stored history.
            return largeMessages(thread);
        }
        return result;
    }
    private List<Message> largeMessages(long thread) {
        SQLiteDatabase sql=getReadableDatabase(); sql.beginTransactionNonExclusive();
        try {
            List<Message> result=new ArrayList<>();
            try(Cursor c=sql.rawQuery("SELECT m.id,role,state,(SELECT COUNT(*) FROM message_versions WHERE message=m.id),attempt FROM messages m WHERE thread=? ORDER BY m.id",new String[]{Long.toString(thread)})) {
                while(c.moveToNext()) {
                    long id=c.getLong(0);
                    Message m=new Message(id,thread,c.getString(1),largeText(sql,"messages","text",id),c.getString(2),largeText(sql,"messages","error",id));
                    m.wire=largeText(sql,"messages","wire",id); m.actions=largeText(sql,"messages","actions",id); m.attachments=largeText(sql,"messages","attachments",id);
                    m.versions=c.getInt(3); m.attempt=c.getInt(4); result.add(m);
                }
            }
            sql.setTransactionSuccessful(); return result;
        } finally { sql.endTransaction(); }
    }
    private static String largeText(SQLiteDatabase sql,String table,String column,long id) {
        // Slice UTF-8 bytes, then decode once so multibyte characters crossing
        // a chunk boundary (including emoji) survive exactly.
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        final int chunk=64*1024;
        for(long offset=1;;offset+=chunk) {
            byte[] part;
            try(Cursor c=sql.rawQuery("SELECT substr(CAST("+column+" AS BLOB),?,?) FROM "+table+" WHERE id=?",new String[]{Long.toString(offset),Integer.toString(chunk),Long.toString(id)})) {
                if(!c.moveToFirst() || c.isNull(0)) return "";
                part=c.getBlob(0);
            }
            bytes.write(part,0,part.length);
            if(part.length<chunk) break;
        }
        return new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
    }
    Message add(long thread,String role,String text,String state) {
        SQLiteDatabase database=getWritableDatabase();
        ContentValues v=new ContentValues(); v.put("thread",thread); v.put("role",role); v.put("text",text); v.put("state",state);
        long id=database.insertOrThrow("messages",null,v);
        ContentValues update=new ContentValues(); update.put("updated",System.currentTimeMillis());
        if("user".equals(role)) {
            try(Cursor c=database.rawQuery("SELECT COUNT(*),COALESCE((SELECT renamed FROM threads WHERE id=?),0) FROM messages WHERE thread=? AND role='user'",new String[]{Long.toString(thread),Long.toString(thread)})) {
                c.moveToFirst(); if(c.getInt(0)==1 && c.getInt(1)==0) update.put("title",shortText(text,64));
            }
        }
        database.update("threads",update,"id=?",new String[]{Long.toString(thread)});
        return new Message(id,thread,role,text,state,"");
    }
    List<Message> addTurn(long thread,String prompt,String files) {
        SQLiteDatabase database=getWritableDatabase(); database.beginTransaction();
        try {
            Message user=add(thread,"user",prompt,"complete"); user.attachments=files; save(user);
            Message answer=add(thread,"assistant","","streaming"); draft(thread,"","[]",""); flag(thread,"archived",false);
            database.setTransactionSuccessful(); List<Message> pair=new ArrayList<>(); pair.add(user); pair.add(answer); return pair;
        } finally { database.endTransaction(); }
    }
    void save(Message m) {
        ContentValues v=new ContentValues(); v.put("text",m.text); v.put("state",m.state); v.put("error",m.error);
        v.put("wire",m.wire); v.put("actions",m.actions); v.put("attachments",m.attachments); v.put("attempt",m.attempt);
        getWritableDatabase().update("messages",v,"id=?",new String[]{Long.toString(m.id)});
    }
    void regenerate(Message m) {
        if(RetryPolicy.hasActions(m)) throw new IllegalArgumentException(Language.text(R.string.tool_commands_cannot_be_regenerated_as_a_normal_reply));
        SQLiteDatabase database=getWritableDatabase(); database.beginTransaction();
        try {
            ContentValues old=new ContentValues(); old.put("message",m.id); old.put("text",m.text); old.put("state",m.state); old.put("error",m.error); old.put("saved",System.currentTimeMillis());
            database.insertOrThrow("message_versions",null,old);
            m.versions++; m.attempt++; m.text=""; m.error=""; m.wire="[]"; m.actions="[]"; m.state="streaming"; save(m); touch(m.thread);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }
    List<Message> versions(long message,long thread) {
        List<Message> list=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,text,state,error FROM message_versions WHERE message=? ORDER BY id DESC",new String[]{Long.toString(message)})) {
            while(c.moveToNext()) list.add(new Message(c.getLong(0),thread,"assistant",c.getString(1),c.getString(2),c.getString(3)));
        } catch(android.database.sqlite.SQLiteBlobTooBigException oversized) {
            list.clear(); SQLiteDatabase sql=getReadableDatabase(); sql.beginTransactionNonExclusive();
            try {
                try(Cursor c=sql.rawQuery("SELECT id,state FROM message_versions WHERE message=? ORDER BY id DESC",new String[]{Long.toString(message)})) {
                    while(c.moveToNext()) list.add(new Message(c.getLong(0),thread,"assistant",largeText(sql,"message_versions","text",c.getLong(0)),c.getString(1),largeText(sql,"message_versions","error",c.getLong(0))));
                }
                sql.setTransactionSuccessful();
            } finally { sql.endTransaction(); }
        }
        return list;
    }
    String field(long id,String field,String fallback) {
        if(!java.util.Arrays.asList("draft","draft_files","draft_extra","settings","profile").contains(field)) throw new IllegalArgumentException("field");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT "+field+" FROM threads WHERE id=?",new String[]{Long.toString(id)})) { return c.moveToFirst()?c.getString(0):fallback; }
    }
    void draft(long id,String text,String files,String extra) {
        if(text!=null && text.trim().isEmpty()) text="";
        ContentValues v=new ContentValues(); if(text!=null) v.put("draft",text); if(files!=null) v.put("draft_files",files); if(extra!=null) v.put("draft_extra",extra);
        if((text!=null && !text.isEmpty()) || (files!=null && !"[]".equals(files)) || (extra!=null && !extra.isEmpty())) v.put("updated",System.currentTimeMillis());
        if(v.size()>0) getWritableDatabase().update("threads",v,"id=?",new String[]{Long.toString(id)});
    }
    void settings(long id,AgentConfig config,String profile) {
        ContentValues v=new ContentValues(); v.put("settings",config.encode()); v.put("profile",profile==null?"":profile);
        getWritableDatabase().update("threads",v,"id=?",new String[]{Long.toString(id)});
    }
    void rename(long id,String name) {
        name=name.trim(); if(name.isEmpty()) throw new IllegalArgumentException(Language.text(R.string.title_cannot_be_empty));
        if(name.codePointCount(0,name.length())>100) throw new IllegalArgumentException(Language.text(R.string.title_up_to_100_characters));
        ContentValues v=new ContentValues(); v.put("title",name); v.put("renamed",1); getWritableDatabase().update("threads",v,"id=?",new String[]{Long.toString(id)});
    }
    void flag(long id,String field,boolean value) {
        if(!field.equals("pinned") && !field.equals("archived")) throw new IllegalArgumentException("flag");
        ContentValues v=new ContentValues(); v.put(field,value?1:0); getWritableDatabase().update("threads",v,"id=?",new String[]{Long.toString(id)});
    }
    void touch(long id) {
        ContentValues v=new ContentValues(); v.put("updated",System.currentTimeMillis()); getWritableDatabase().update("threads",v,"id=?",new String[]{Long.toString(id)});
    }
    void seen(long id,long message) { seen(id,message,0); }
    void seen(long id,long message,int attempt) {
        getWritableDatabase().execSQL("UPDATE threads SET seen=?,seen_attempt=? WHERE id=? AND (seen<? OR (seen=? AND seen_attempt<?))",new Object[]{message,attempt,id,message,message,attempt});
    }
    List<Profile> profiles(AgentConfig fallback) {
        List<Profile> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,settings FROM agent_profiles ORDER BY updated,id",null)) { while(c.moveToNext()) result.add(new Profile(c.getString(0),AgentConfig.decode(c.getString(1),fallback))); }
        return result;
    }
    void profile(String id,AgentConfig config) {
        ContentValues v=new ContentValues(); v.put("id",id); v.put("settings",config.encode()); v.put("updated",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("agent_profiles",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    void removeProfile(String id) {
        SQLiteDatabase database=getWritableDatabase(); database.beginTransaction();
        try { database.delete("agent_profiles","id=?",new String[]{id}); database.execSQL("UPDATE threads SET profile='' WHERE profile=?",new Object[]{id}); database.setTransactionSuccessful(); }
        finally { database.endTransaction(); }
    }
    void delete(long thread) {
        for(Message m:messages(thread)) removeFiles(m.attachments);
        removeFiles(field(thread,"draft_files","[]"));
        getWritableDatabase().delete("threads","id=?",new String[]{Long.toString(thread)});
    }
    private static void removeFiles(String encoded) { try { JSONArray a=new JSONArray(encoded); for(int i=0;i<a.length();i++) Attachments.remove(a.getJSONObject(i)); } catch(Exception ignored) { } }
}
