package com.mesystem.shell;

import android.app.Application;
import android.content.*;
import android.database.sqlite.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Isolated DB, memory preferences, no account, network, real tools or app-private data. */
final class ManagementTests {
    private static int checks;
    private static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label); checks++; System.out.println("PASS "+label); }
    private static final class MemoryPrefs implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();
        public synchronized Map<String,?> getAll() { return new HashMap<>(values); }
        public synchronized String getString(String k,String d) { Object v=values.get(k); return v instanceof String?(String)v:d; }
        @SuppressWarnings("unchecked") public synchronized Set<String> getStringSet(String k,Set<String> d) { Object v=values.get(k); return v instanceof Set?new HashSet<>((Set<String>)v):d; }
        public synchronized int getInt(String k,int d) { Object v=values.get(k); return v instanceof Integer?(Integer)v:d; }
        public synchronized long getLong(String k,long d) { Object v=values.get(k); return v instanceof Long?(Long)v:d; }
        public synchronized float getFloat(String k,float d) { Object v=values.get(k); return v instanceof Float?(Float)v:d; }
        public synchronized boolean getBoolean(String k,boolean d) { Object v=values.get(k); return v instanceof Boolean?(Boolean)v:d; }
        public synchronized boolean contains(String k) { return values.containsKey(k); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        public Editor edit() { return new Editor() {
            Map<String,Object> pending=new HashMap<>(); boolean clear;
            public Editor putString(String k,String v) { pending.put(k,v); return this; }
            public Editor putStringSet(String k,Set<String> v) { pending.put(k,v); return this; }
            public Editor putInt(String k,int v) { pending.put(k,v); return this; }
            public Editor putLong(String k,long v) { pending.put(k,v); return this; }
            public Editor putFloat(String k,float v) { pending.put(k,v); return this; }
            public Editor putBoolean(String k,boolean v) { pending.put(k,v); return this; }
            public Editor remove(String k) { pending.put(k,null); return this; }
            public Editor clear() { clear=true; return this; }
            public boolean commit() { synchronized(MemoryPrefs.this) { if(clear) values.clear(); for(Map.Entry<String,Object> e:pending.entrySet()) { if(e.getValue()==null) values.remove(e.getKey()); else values.put(e.getKey(),e.getValue()); } } return true; }
            public void apply() { commit(); }
        }; }
    }
    private static final class Isolated extends ContextWrapper {
        final File dir; final Map<String,MemoryPrefs> prefs=new HashMap<>(); MeShellApp app;
        Isolated(Context base,File dir) throws Exception {
            super(base); this.dir=dir; dir.mkdirs(); app=new MeShellApp();
            Method attach=Application.class.getDeclaredMethod("attach",Context.class); attach.setAccessible(true); attach.invoke(app,this);
        }
        @Override public Object getSystemService(String name) {
            if(name.equals("autofill") || name.equals("content_capture") || name.equals("textclassification")) return null;
            return super.getSystemService(name);
        }
        boolean allowForeground;
        @Override public ComponentName startForegroundService(Intent intent) {
            if(allowForeground) return new ComponentName(getPackageName(),AgentService.class.getName());
            throw new IllegalStateException("Isolated tests block foreground services");
        }
        @Override public Context getApplicationContext() { return app; }
        @Override public File getFilesDir() { return dir; }
        @Override public File getDatabasePath(String name) { return new File(dir,name); }
        @Override public synchronized SharedPreferences getSharedPreferences(String name,int mode) { MemoryPrefs p=prefs.get(name); if(p==null) { p=new MemoryPrefs(); prefs.put(name,p); } return p; }
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler); }
    }
    private static Object field(Object target,String name) throws Exception { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private static void field(Object target,String name,Object value) throws Exception { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value); }
    private static void pumpUntil(BooleanSupplier done) throws Exception {
        long until=System.currentTimeMillis()+5000; Handler h=new Handler(Looper.getMainLooper());
        Method next=MessageQueue.class.getDeclaredMethod("next"); next.setAccessible(true);
        while(!done.getAsBoolean()) {
            if(System.currentTimeMillis()>until) throw new AssertionError("async operation timeout");
            h.postDelayed(() -> {},20); Message message=(Message)next.invoke(Looper.getMainLooper().getQueue());
            if(message!=null) { message.getTarget().dispatchMessage(message); Method recycle=Message.class.getDeclaredMethod("recycleUnchecked"); recycle.setAccessible(true); recycle.invoke(message); }
        }
    }
    private static void select(ChatEngine engine,long id) throws Exception { engine.selectThread(id); pumpUntil(() -> engine.ready); }
    private static void closeEngine(ChatEngine engine) throws Exception {
        engine.observe(null);
        for(String executor:new String[]{"worker","storage","browser","metadata","quotaWorker"}) ((ExecutorService)field(engine,executor)).shutdownNow();
        ((ChatStore)field(engine,"db")).close();
    }
    private static void migration(Context base,File root,int version,AgentConfig config) throws Exception {
        Isolated c=new Isolated(base,new File(root,"migration-"+version)); SQLiteDatabase sql=c.openOrCreateDatabase("chats.db",0,null);
        String extras=version<5?"":",pinned INTEGER NOT NULL DEFAULT 0,archived INTEGER NOT NULL DEFAULT 0,renamed INTEGER NOT NULL DEFAULT 0,seen INTEGER NOT NULL DEFAULT 0,profile TEXT NOT NULL DEFAULT '',settings TEXT NOT NULL DEFAULT '',draft TEXT NOT NULL DEFAULT '',draft_files TEXT NOT NULL DEFAULT '[]',draft_extra TEXT NOT NULL DEFAULT ''";
        sql.execSQL("CREATE TABLE threads(id INTEGER PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL"+extras+")");
        sql.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY,thread INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE,role TEXT NOT NULL,text TEXT NOT NULL,state TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',wire TEXT NOT NULL DEFAULT '[]',actions TEXT NOT NULL DEFAULT '[]',attachments TEXT NOT NULL DEFAULT '[]')");
        sql.execSQL("INSERT INTO threads(id,title,updated) VALUES(42,'Старая история',1)");
        sql.execSQL("INSERT INTO messages(id,thread,role,text,state) VALUES(99,42,'assistant','Сохранённый ответ','complete')");
        if(version==5) {
            sql.execSQL("CREATE TABLE agent_profiles(id TEXT PRIMARY KEY,settings TEXT NOT NULL,updated INTEGER NOT NULL)");
            sql.execSQL("CREATE TABLE message_versions(id INTEGER PRIMARY KEY,message INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,text TEXT NOT NULL,state TEXT NOT NULL,error TEXT NOT NULL,saved INTEGER NOT NULL)");
            sql.execSQL("INSERT INTO message_versions VALUES(1,99,'Старая попытка','error','timeout',0)");
            sql.execSQL("UPDATE threads SET settings=?,draft='Сохранённый черновик',pinned=1 WHERE id=42",new Object[]{config.encode()});
        }
        sql.setVersion(version); sql.close(); ChatStore upgraded=new ChatStore(c);
        check(upgraded.messages(42).get(0).text.equals("Сохранённый ответ") && upgraded.messages(42).get(0).attempt==0,"DB v"+version+" to v6 preserves messages and initializes attempt identity");
        check(upgraded.create()>42,"DB v"+version+" migration seeds monotonic chat ID above existing history");
        check(ChatBackup.status(c).getBoolean("present"),"DB v"+version+" migration creates private snapshot before upgrading");
        File snapshot=new File(c.getDatabasePath("chats.db").getParentFile(),"chats-before-v6/chats.db"); SQLiteDatabase original=SQLiteDatabase.openDatabase(snapshot.getPath(),null,SQLiteDatabase.OPEN_READONLY);
        check(original.getVersion()==version,"migration snapshot remains original DB v"+version); original.close();
        if(version==5) check(upgraded.versions(99,42).get(0).text.equals("Старая попытка") && upgraded.conversation(42).pinned && upgraded.field(42,"draft","").equals("Сохранённый черновик"),"DB v5 migration preserves attempts, pinned flags and draft");
        upgraded.close();
    }
    static int run(Context base,File root) throws Exception {
        checks=0;
        AgentConfig a=new AgentConfig("Developer","gpt-test-a","high",32000,true,"Check results");
        AgentConfig b=new AgentConfig("Chat","gpt-test-b","low",12000,false,"");
        check(AgentConfig.decode(a.encode(),b).instructions.equals(a.instructions),"profile round trip");
        check(AgentConfig.decode("broken",b)==b,"malformed profile falls back safely");
        migration(base,root,4,a); migration(base,root,5,a);
        Isolated c=new Isolated(base,new File(root,"store")); ChatStore db=new ChatStore(c);
        long blank=db.blank(a,"dev");
        check(db.blank(a,"dev")==blank && db.conversations().isEmpty(),"empty chat reuses one hidden placeholder");
        check(db.conversation(blank).title.equals(Language.text(R.string.new_chat)),"blank title is localized");
        db.draft(blank,"Несохранённая мысль",null,null);
        check(db.conversations("НЕСОХРАНЁННАЯ",false).size()==1,"Cyrillic draft search");
        List<ChatStore.Message> pair=db.addTurn(blank,"Question","[]"); ChatStore.Message answer=pair.get(1);
        check(db.messages(blank).size()==2 && db.field(blank,"draft","?").isEmpty(),"atomic turn clears draft");
        answer.text="Result"; answer.state="complete"; db.save(answer); db.regenerate(answer);
        check(db.versions(answer.id,blank).get(0).text.equals("Result") && db.messages(blank).size()==2,"retry keeps old answer without another user turn");
        answer.state="error"; answer.actions="[{\"name\":\"write_file\",\"state\":\"interrupted\"}]";
        boolean blocked=false; try { db.regenerate(answer); } catch(IllegalArgumentException e) { blocked=true; }
        check(blocked,"tool journal cannot be reset by regenerate");
        answer.actions="broken"; answer.wire="broken";
        check(RetryPolicy.continuation(answer).toString().contains("unknown"),"damaged journal preserves unknown outcome");
        answer.actions="[]"; answer.wire="[]"; answer.state="complete"; db.save(answer);
        db.seen(blank,answer.id,answer.attempt); db.seen(blank,answer.id,answer.attempt-1);
        check(!db.conversation(blank).unread,"stale seen callback cannot reset a newer read attempt");
        db.profile("dev",a); db.settings(blank,a,"dev"); db.profile("dev",b);
        check(AgentConfig.decode(db.field(blank,"settings",""),b).tools,"profile changes do not mutate existing chats");
        long removed=db.create(); db.delete(removed); db.close(); db=new ChatStore(c);
        check(db.create()>removed && !db.exists(removed),"deleted identity is not reused after reopening");
        db.delete(blank); check(db.messages(blank).isEmpty() && db.versions(answer.id,blank).isEmpty(),"chat deletion cascades history"); db.close();

        Isolated ectx=new Isolated(base,new File(root,"engine")); ChatEngine engine=new ChatEngine(ectx);
        pumpUntil(() -> engine.ready); check(!engine.authenticated && engine.error.isEmpty(),"engine boots offline");
        ChatStore store=(ChatStore)field(engine,"db"); long x=engine.threadId();
        ChatStore.Message first=store.addTurn(x,"First","[]").get(1); first.text="Live first answer"; store.save(first); store.settings(x,a,"dev");
        long y=store.create(); store.settings(y,b,"talk"); ChatStore.Message second=store.addTurn(y,"Second","[]").get(1);
        select(engine,x);
        CountDownLatch release=new CountDownLatch(1),started=new CountDownLatch(1);
        ((ExecutorService)field(engine,"worker")).execute(() -> { started.countDown(); try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }); started.await();
        Method begin=ChatEngine.class.getDeclaredMethod("beginTask",ChatStore.Message.class,AgentConfig.class,OpenAiClient.Cancel.class); begin.setAccessible(true);
        Method end=ChatEngine.class.getDeclaredMethod("endTask",OpenAiClient.Cancel.class,ChatStore.Message.class); end.setAccessible(true);
        ectx.allowForeground=true; OpenAiClient.Cancel xtoken=new OpenAiClient.Cancel(),ytoken=new OpenAiClient.Cancel();
        check((Boolean)begin.invoke(engine,first,a,xtoken),"first task admitted");
        select(engine,y);
        check(!engine.busyHere() && engine.busy && !engine.agentEnabled && !engine.snapshot().get(1).text.contains("Live first"),"switch isolates configuration and live answer");
        check((Boolean)begin.invoke(engine,second,b,ytoken) && engine.activeTasks().size()==2,"two chats admitted concurrently");
        check(!(Boolean)begin.invoke(engine,second,b,new OpenAiClient.Cancel()),"one task per chat");
        boolean protectedDelete=false; try { engine.deleteThread(x); } catch(IllegalStateException e) { protectedDelete=true; }
        check(protectedDelete,"nonselected active chat is protected from deletion");
        engine.newThread(); pumpUntil(() -> engine.ready); long z=engine.threadId();
        check(!engine.send("Offline") && store.messages(z).isEmpty(),"blocked send creates no turn");
        field(engine,"selfOperation",true); engine.authenticated=true;
        check(!engine.send("During update") && store.messages(z).isEmpty(),"self update excludes new task admission");
        field(engine,"selfOperation",false); engine.authenticated=false;
        CountDownLatch parallel=new CountDownLatch(1); ((ExecutorService)field(engine,"worker")).execute(parallel::countDown);
        check(parallel.await(2,TimeUnit.SECONDS),"worker remains concurrent while another waits");
        AgentTask old=engine.task(y); engine.cancel(y,old.notificationId+999);
        check(!ytoken.stopped,"stale notification cannot cancel task");
        engine.cancel(y,old.notificationId);
        check(ytoken.stopped && !xtoken.stopped && engine.running(y),"individual stop keeps task until settled");
        end.invoke(engine,ytoken,second);
        check(engine.running(x) && !engine.running(y),"one completion leaves another task running");
        OpenAiClient.Cancel replacement=new OpenAiClient.Cancel(); begin.invoke(engine,second,b,replacement);
        engine.cancel(y,old.notificationId); end.invoke(engine,ytoken,second);
        check(!replacement.stopped && engine.task(y).token==replacement,"stale completion cannot affect replacement");
        java.util.concurrent.locks.ReentrantLock gate=(java.util.concurrent.locks.ReentrantLock)field(engine,"toolGate"); gate.lock();
        CountDownLatch queued=new CountDownLatch(1),settled=new CountDownLatch(1); java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        Method execute=ChatEngine.class.getDeclaredMethod("executeTool",AgentTask.class,String.class,JSONObject.class); execute.setAccessible(true);
        ((ExecutorService)field(engine,"worker")).execute(() -> { queued.countDown(); try { execute.invoke(engine,engine.task(y),"write_file",new JSONObject()); } catch(Exception e) { cancelled.set(true); } finally { settled.countDown(); } });
        queued.await(); replacement.stop();
        check(settled.await(2,TimeUnit.SECONDS) && cancelled.get(),"cancelled tool waiter never executes mutation"); gate.unlock();
        end.invoke(engine,replacement,second); engine.cancelAll(); end.invoke(engine,xtoken,first);
        check(xtoken.stopped && !engine.busy && engine.activeTasks().isEmpty(),"stop all settles all tasks");
        release.countDown(); ectx.allowForeground=false; engine.authenticated=true;
        check(engine.send("Foreground failure") && !engine.busy && engine.snapshot().get(1).state.equals("error"),"foreground startup failure leaves explicit saved error");
        engine.authenticated=false;
        for(ChatStore.Profile profile:engine.profiles()) engine.deleteProfile(profile.id);
        closeEngine(engine); ChatEngine restored=new ChatEngine(ectx); pumpUntil(() -> restored.ready); restored.newThread(); pumpUntil(() -> restored.ready);
        check(restored.profiles().isEmpty() && !restored.agentEnabled,"removed profiles do not return after restart"); closeEngine(restored);
        System.out.println("MANAGEMENT_TESTS_OK "+checks); return checks;
    }
}
