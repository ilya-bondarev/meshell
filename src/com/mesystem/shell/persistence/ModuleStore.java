package com.mesystem.shell;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

final class ModuleStore extends SQLiteOpenHelper {
    ModuleStore(Context c) { super(c,"modules.db",null,2); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,body TEXT NOT NULL,updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE extensions(id TEXT PRIMARY KEY,definition TEXT NOT NULL,meta TEXT)");
    }
    /** Version 2 adds install metadata; NULL marks a module installed before permission consent existed. */
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int version) { if(oldVersion<2) db.execSQL("ALTER TABLE extensions ADD COLUMN meta TEXT"); }
    JSONArray notes() throws Exception {
        JSONArray out=new JSONArray(); try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,body,updated FROM notes ORDER BY updated DESC",null)) {
            while(c.moveToNext()) out.put(new JSONObject().put("id",c.getLong(0)).put("title",c.getString(1)).put("body",c.getString(2)).put("updated",c.getLong(3)));
        } return out;
    }
    long saveNote(long id,String title,String body) {
        ContentValues v=new ContentValues(); v.put("title",title.trim().isEmpty()?Language.text(R.string.untitled):title.trim()); v.put("body",body); v.put("updated",System.currentTimeMillis());
        if(id==0) return getWritableDatabase().insertOrThrow("notes",null,v);
        getWritableDatabase().update("notes",v,"id=?",new String[]{Long.toString(id)}); return id;
    }
    void deleteNote(long id) { getWritableDatabase().delete("notes","id=?",new String[]{Long.toString(id)}); }
    JSONArray extensions() throws Exception {
        JSONArray out=new JSONArray(); try(Cursor c=getReadableDatabase().rawQuery("SELECT definition FROM extensions ORDER BY id",null)) {
            while(c.moveToNext()) out.put(new JSONObject(c.getString(0)));
        } return out;
    }
    void extension(JSONObject value,JSONObject meta) throws Exception {
        ContentValues v=new ContentValues(); v.put("id",value.getString("id")); v.put("definition",value.toString()); v.put("meta",meta==null?null:meta.toString());
        getWritableDatabase().insertWithOnConflict("extensions",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    /** Install source, catalogue version and granted permissions; null for legacy modules. */
    JSONObject meta(String id) throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT meta FROM extensions WHERE id=?",new String[]{id})) {
            return c.moveToNext() && !c.isNull(0)?new JSONObject(c.getString(0)):null;
        }
    }
    void meta(String id,JSONObject meta) {
        ContentValues v=new ContentValues(); v.put("meta",meta.toString()); getWritableDatabase().update("extensions",v,"id=?",new String[]{id});
    }
    void removeExtension(String id) { getWritableDatabase().delete("extensions","id=?",new String[]{id}); }
    void clear(String id) { if(id.equals("notes")) getWritableDatabase().delete("notes",null,null); }
}
