package com.mesystem.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

final class ChatEngine {
    interface Listener { void changed(); }
    private final ChatStore db;
    private final SecretStore secrets;
    private final OpenAiClient client;
    final AgentTools tools;
    private final SharedPreferences prefs;
    private final ExecutorService worker=Executors.newCachedThreadPool();
    private final java.util.LinkedHashMap<Long,AgentTask> tasks=new java.util.LinkedHashMap<>();
    private final ThreadLocal<AgentTask> executing=new ThreadLocal<>();
    private final java.util.concurrent.locks.ReentrantLock toolGate=new java.util.concurrent.locks.ReentrantLock(true);
    private AgentTask lastTask;
    private volatile boolean selfOperation;
    synchronized boolean running(long thread) { return tasks.containsKey(thread); }
    synchronized List<AgentTask> activeTasks() { return new ArrayList<>(tasks.values()); }
    synchronized AgentTask task(long thread) { return tasks.get(thread); }
    private synchronized void projectTasks() {
        busy=!tasks.isEmpty();
        AgentTask selected=tasks.get(currentThread);
        if(selected==null && busy) selected=tasks.values().iterator().next();
        if(selected==null) selected=lastTask;
        activeAnswer=selected!=null && tasks.containsKey(selected.thread)?selected.answer:null;
        if(selected==null) return;
        taskThread=selected.thread; taskTitle=selected.title; taskAgent=selected.config.name;
        taskStarted=selected.started; taskState=selected.state; phase=selected.phase; agentStep=selected.step;
        actualModel=selected.actualModel; totalTokens=selected.totalTokens; lastInputTokens=selected.lastInputTokens; contextEstimate=selected.contextEstimate;
    }
    void cancelAll() { for(AgentTask t:activeTasks()) cancel(t.thread,t.notificationId); }
    void cancel(long thread,int identity) {
        AgentTask t=task(thread); if(t==null || t.notificationId!=identity) return;
        t.token.stop(); t.phase=Language.text(R.string.stopping_the_current_command_s_outcome_may_be_unknown); changed();
    }
    private JSONObject executeTool(AgentTask task,String name,JSONObject args) throws Exception {
        task.phase=Language.text(R.string.waiting_for_tools)+name; changed();
        while(!toolGate.tryLock(200,java.util.concurrent.TimeUnit.MILLISECONDS)) task.token.check();
        boolean exclusive="build_self".equals(name) || "install_self".equals(name);
        boolean updating=false;
        try {
            task.token.check();
            synchronized(this) {
                if(exclusive && tasks.size()>1)
                    return new JSONObject().put("error",Language.text(R.string.finish_other_tasks_first_build_update_affects_the_entire_app));
                if(exclusive) selfOperation=true;
            }
            task.phase=Language.text(R.string.running_2)+name; changed();
            JSONObject result=tools.execute(name,args,task.token);
            updating="install_self".equals(name) && result.optInt("exit_code",-1)==0;
            return result;
        } finally { if(exclusive && !updating) selfOperation=false; toolGate.unlock(); }
    }
    private final ExecutorService metadata=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean pending=new AtomicBoolean();
    private volatile Listener listener;
    private volatile OpenAiClient.Cancel job;
    private final List<ChatStore.Message> messages=new ArrayList<>();
    private final List<String> models=new ArrayList<>();
    volatile boolean ready,authenticated,busy,loggingIn;
    volatile String loginCode="",authLabel=Language.text(R.string.not_connected),error="",model,effort,actualModel="",phase=Language.text(R.string.responding),limitsError="";
    volatile int totalTokens,lastInputTokens,contextEstimate,contextTokens;
    volatile long agentStep,taskStarted;
    volatile String taskState=Language.text(R.string.ready);
    volatile boolean limitsLoading;
    private final Context app;
    private final ExecutorService quotaWorker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean quotaPending=new AtomicBoolean();
    volatile boolean agentEnabled;
    volatile JSONObject limits=new JSONObject(),resetCredits=new JSONObject();
    volatile long limitsUpdated;
    private long currentThread;
    private final ExecutorService storage=Executors.newSingleThreadExecutor();
    private final ExecutorService browser=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicLong revision=new java.util.concurrent.atomic.AtomicLong();
    private volatile ChatStore.Message activeAnswer;
    private volatile AgentConfig config,defaults;
    volatile String profileId="",threadTitle=Language.text(R.string.new_chat),taskTitle="",taskAgent="";
    volatile long taskThread;
    private int selection;
    long managementRevision() { return revision.get(); }
    private void managementChanged() { revision.incrementAndGet(); changed(); }
    AgentConfig configuration() { return config; }
    boolean busyHere() { return running(threadId()); }
    synchronized void refreshLanguage() {
        ChatStore.Conversation value=db.conversation(currentThread); if(value!=null) threadTitle=value.title;
    }
    boolean hasConversation() {
        return ready && authenticated && (!snapshot().isEmpty() || !draft().trim().isEmpty() || draftAttachments().length()>0);
    }
    boolean lastTaskFailed() {
        ChatStore.Conversation value=db.conversation(taskThread);
        return value!=null && (value.state.equals("error") || value.state.equals("interrupted"));
    }
    String taskLabel() { return taskAgent+" · "+taskTitle; }

    ChatEngine(Context context) {
        app=context.getApplicationContext(); Attachments.init(app);
        db=new ChatStore(context); secrets=new SecretStore(context); client=new OpenAiClient(secrets); tools=new AgentTools(context);
        prefs=context.getSharedPreferences("chat-ui",Context.MODE_PRIVATE); client.clientVersion(prefs.getString("codex-client-version",""));
        model=prefs.getString("model",OpenAiClient.DEFAULT_MODEL); effort=prefs.getString("effort","medium");
        contextTokens=prefs.getInt("context-tokens",64000); agentEnabled=prefs.getBoolean("agent-enabled",true);
        defaults=new AgentConfig(Language.text(R.string.primary),model,effort,contextTokens,agentEnabled,""); config=defaults;
        taskThread=prefs.getLong("task-thread",0); taskTitle=prefs.getString("task-title",""); taskAgent=prefs.getString("task-agent","");
        taskState=prefs.getBoolean("task-active",false)?Language.text(R.string.interrupted_process_stopped_check_the_result_before_continui):prefs.getString("task-state",Language.text(R.string.ready));
        prefs.edit().remove("max-steps").putBoolean("task-active",false).putString("task-state",taskState).apply();
        try { client.catalog=new JSONArray(prefs.getString("model-catalog","[]")); } catch(Exception ignored) { }
        worker.execute(() -> {
            try {
                db.migrateUi(prefs,defaults); db.recover(); JSONObject auth=secrets.load(); updateAccount(auth);
                if(!prefs.getBoolean("agent-profiles-initialized",false)) {
                    if(db.profiles(defaults).isEmpty()) {
                        db.profile("primary",defaults);
                        db.profile("chat-only",new AgentConfig(Language.text(R.string.chat_only),model,effort,contextTokens,false,""));
                    }
                    prefs.edit().putBoolean("agent-profiles-initialized",true).commit();
                }
                long saved=prefs.getLong("thread",0); List<ChatStore.Conversation> history=db.conversations();
                if(!db.exists(saved)) saved=history.isEmpty()?db.blank(defaults,"primary"):history.get(0).id;
                load(saved); managementChanged();
                List<ChatStore.Message> restored=snapshot();
                if(!restored.isEmpty() && "interrupted".equals(restored.get(restored.size()-1).state)) {
                    taskState=Language.text(R.string.interrupted_process_stopped_or_app_updated_check_the_result);
                    prefs.edit().putString("task-state",taskState).apply();
                }
                ready=true; changed();
                if(authenticated) { refreshLimits(); metadata.execute(() -> refreshModels(new OpenAiClient.Cancel())); }
            } catch(Exception e) { error=message(e); ready=true; changed(); }
        });
    }
    private void updateAccount(JSONObject auth) {
        authenticated=auth!=null;
        authLabel=auth==null?Language.text(R.string.not_connected):"ChatGPT · "+auth.optString("plan",Language.text(R.string.subscription));
    }
    void observe(Listener value) { listener=value; changed(); }
    private void changed() {
        synchronized(this) { projectTasks(); for(AgentTask t:tasks.values()) publish(t.answer); }
        if(pending.compareAndSet(false,true)) main.postDelayed(() -> {
            pending.set(false); Listener current=listener; if(current!=null) current.changed();
        },50);
    }
    private static String message(Exception e) {
        if(e instanceof java.net.UnknownHostException) return Language.text(R.string.cannot_reach_openai_check_internet_and_vpn);
        if(e instanceof java.net.SocketTimeoutException) return Language.text(R.string.openai_timed_out_you_can_retry);
        String text=e.getMessage(); return text==null?Language.text(R.string.request_failed):text;
    }
    synchronized List<ChatStore.Message> snapshot() {
        List<ChatStore.Message> copy=new ArrayList<>(); for(ChatStore.Message m:messages) copy.add(m.copy()); return copy;
    }
    synchronized long threadId() { return currentThread; }
    synchronized List<String> modelChoices() {
        List<String> result=new ArrayList<>(models);
        if(result.isEmpty()) for(int i=0;i<client.catalog.length();i++) {
            JSONObject item=client.catalog.optJSONObject(i); if(item==null) continue;
            String slug=item.optString("slug",item.optString("id"));
            if(slug.startsWith("gpt-") && !"hide".equals(item.optString("visibility")) && !result.contains(slug)) result.add(slug);
        }
        if(!result.contains(model)) result.add(0,model); return result;
    }
    boolean canSend() { return authenticated; }
    void selectModel(String value,String depth) { if(ready) setConfiguration(threadId(),config.model(value,depth),profileId); }
    synchronized void setConfiguration(long thread,AgentConfig value,String profile) {
        if(!db.exists(thread)) return;
        db.settings(thread,value,profile);
        if(thread==currentThread) { profileId=profile==null?"":profile; applyConfiguration(value); }
        managementChanged();
    }
    private void applyConfiguration(AgentConfig value) {
        config=value; model=value.model; effort=value.effort; contextTokens=value.context; agentEnabled=value.tools;
        prefs.edit().putString("model",model).putString("effort",effort).putInt("context-tokens",contextTokens).putBoolean("agent-enabled",agentEnabled).apply();
    }
    List<ChatStore.Profile> profiles() { return db.profiles(defaults.options(defaults.context,false)); }
    void saveProfile(String id,AgentConfig value) { db.profile(id,value); managementChanged(); }
    void deleteProfile(String id) {
        db.removeProfile(id); if(id.equals(profileId)) profileId="";
        if(id.equals(prefs.getString("default-profile","primary"))) prefs.edit().remove("default-profile").apply(); managementChanged();
    }
    void defaultProfile(String id) { prefs.edit().putString("default-profile",id).apply(); managementChanged(); }
    String defaultProfile() {
        String id=prefs.getString("default-profile","primary"); List<ChatStore.Profile> profiles=profiles();
        for(ChatStore.Profile p:profiles) if(p.id.equals(id)) return id;
        return profiles.isEmpty()?"":profiles.get(0).id;
    }
    List<String> effortsFor(String selected) {
        List<String> result=new ArrayList<>();
        JSONObject info=modelInfo(selected);
        JSONArray levels=info.optJSONArray("supported_reasoning_levels");
        if(levels!=null) for(int i=0;i<levels.length();i++) {
            JSONObject level=levels.optJSONObject(i); String value=level==null?levels.optString(i):level.optString("effort");
            if(!value.isEmpty()) result.add(value);
        }
        if(result.isEmpty()) { result.add("low"); result.add("medium"); result.add("high"); }
        return result;
    }
    JSONObject modelInfo(String selected) {
        JSONArray catalog=client.catalog;
        for(int i=0;i<catalog.length();i++) { JSONObject info=catalog.optJSONObject(i); if(info!=null && selected.equals(info.optString("slug",info.optString("id")))) return info; }
        return new JSONObject();
    }
    void configure(int tokens,boolean agent) {
        int maximum=modelInfo(model).optInt("context_window",262144);
        if(ready) setConfiguration(threadId(),config.options(Math.max(4096,Math.min(maximum,tokens)),agent),profileId);
    }
    double weeklyRemaining() {
        JSONObject rate=limits.optJSONObject("rate_limit");
        JSONObject primary=rate==null?null:rate.optJSONObject("primary_window");
        JSONObject secondary=rate==null?null:rate.optJSONObject("secondary_window");
        return services.quota.QuotaRules.weeklyRemaining(
            primary==null?0:primary.optLong("limit_window_seconds",0),
            primary==null?Double.NaN:primary.optDouble("used_percent",Double.NaN),
            secondary==null?0:secondary.optLong("limit_window_seconds",0),
            secondary==null?Double.NaN:secondary.optDouble("used_percent",Double.NaN));
    }
    void refreshLimits() {
        if(!authenticated || !quotaPending.compareAndSet(false,true)) return;
        limitsLoading=true; changed();
        quotaWorker.execute(() -> { try { readLimits(); } finally { limitsLoading=false; quotaPending.set(false); changed(); } });
    }
    private void readLimits() {
        if(!authenticated) return;
        try {
            OpenAiClient.Cancel cancel=new OpenAiClient.Cancel();
            JSONObject fresh=client.accountRequest("GET","/wham/usage",null,cancel);
            if(!authenticated) return;
            limits=fresh; limitsUpdated=System.currentTimeMillis(); limitsError=""; changed();
            try { resetCredits=client.accountRequest("GET","/wham/rate-limit-reset-credits",null,cancel); }
            catch(Exception ignored) { /* Usage is valid even when reset-credit discovery fails. */ }
        } catch(Exception e) { limitsError=message(e); }
        changed();
    }
    int availableResets() { return resetCredits.optInt("available_count",resetCredits.optInt("availableCount",0)); }
    boolean canResetLimits() { return authenticated && availableResets()>0; }
    void redeemCredit(String creditId) {
        metadata.execute(() -> {
            try {
                JSONObject request=new JSONObject().put("redeem_request_id",java.util.UUID.randomUUID().toString());
                if(creditId!=null && !creditId.isEmpty()) request.put("credit_id",creditId);
                JSONObject result=client.accountRequest("POST","/wham/rate-limit-reset-credits/consume",request,new OpenAiClient.Cancel());
                limitsError=Language.text(R.string.reset_result)+result.toString(); changed();
                String outcome=limitsError; readLimits(); limitsError=outcome;
            } catch(Exception e) { limitsError=message(e); }
            changed();
        });
    }
    void saveDraft(String text) { saveDraft(threadId(),text); }
    void saveDraft(long id,String text) {
        if(id<=0 || !db.exists(id) || db.field(id,"draft","").equals(text)) return;
        db.draft(id,text,null,null); if(id==threadId()) threadTitle=db.conversation(id).title; managementChanged();
    }
    String draft() { return db.field(threadId(),"draft",""); }
    void clearError() { error=""; changed(); }
    private synchronized void load(long id) {
        // Read and publish under the same lock as endTask. Otherwise a stale DB read
        // could replace the final answer after activeAnswer has already been cleared.
        List<ChatStore.Message> saved=db.messages(id); ChatStore.Conversation thread=db.conversation(id);
        currentThread=id; messages.clear(); messages.addAll(saved);
        AgentTask live=tasks.get(id); if(live!=null) publish(live.answer);
        projectTasks();
        profileId=thread==null?"":thread.profile; threadTitle=thread==null?Language.text(R.string.new_chat):thread.title;
        applyConfiguration(AgentConfig.decode(thread==null?"":thread.settings,defaults.options(defaults.context,false)));
        prefs.edit().putLong("thread",id).apply(); error="";
    }
    private synchronized void publish(ChatStore.Message answer) {
        if(currentThread!=answer.thread) return;
        for(int i=0;i<messages.size();i++) if(messages.get(i).id==answer.id) { messages.set(i,answer.copy()); return; }
    }
    void history(java.util.function.Consumer<List<ChatStore.Conversation>> callback) { history("",false,callback); }
    void history(String query,boolean archive,java.util.function.Consumer<List<ChatStore.Conversation>> callback) {
        browser.execute(() -> {
            try { List<ChatStore.Conversation> value=db.conversations(query,archive); main.post(() -> callback.accept(value)); }
            catch(Exception e) { error=message(e); changed(); main.post(() -> callback.accept(new ArrayList<>())); }
        });
    }
    synchronized void selectThread(long id) { selectThread(id,null); }
    synchronized void selectThread(long id,Runnable opened) {
        if(!ready) return;
        final int ticket=++selection; ready=false; changed();
        storage.execute(() -> {
            boolean loaded=false;
            try { if(db.exists(id)) { load(id); loaded=true; } else error=Language.text(R.string.this_chat_was_deleted_or_is_unavailable); }
            catch(Exception e) { error=message(e); }
            final boolean found=loaded;
            main.post(() -> {
                if(ticket!=selection) return;
                ready=true; managementChanged();
                if(found) { if(opened!=null) opened.run(); }
                else android.widget.Toast.makeText(app,Language.text(R.string.cannot_open_chat)+error,android.widget.Toast.LENGTH_LONG).show();
            });
        });
    }
    void openTask(Runnable opened) { if(db.exists(taskThread)) selectThread(taskThread,opened); else if(opened!=null) opened.run(); }
    void newThread() { newThread(null,null); }
    synchronized void newThread(String profile,Runnable opened) {
        if(!ready) return;
        String chosen=profile==null?defaultProfile():profile;
        AgentConfig value=defaults.options(defaults.context,false); String found="";
        for(ChatStore.Profile p:profiles()) if(p.id.equals(chosen)) { value=p.config; found=p.id; break; }
        long id=db.blank(value,found); selectThread(id,opened);
    }
    void renameThread(long id,String title) {
        db.rename(id,title); if(threadId()==id) threadTitle=title;
        AgentTask live=task(id); if(live!=null) live.title=title;
        if(taskThread==id) { taskTitle=title; prefs.edit().putString("task-title",title).apply(); } managementChanged();
    }
    void pinThread(long id,boolean value) { db.flag(id,"pinned",value); managementChanged(); }
    void archiveThread(long id,boolean value) {
        if(running(id)) throw new IllegalStateException(Language.text(R.string.stop_this_chat_s_task_first));
        db.flag(id,"archived",value); managementChanged();
    }
    void markRead(long id,long last,int attempt) { db.seen(id,last,attempt); revision.incrementAndGet(); }
    boolean stopping() { AgentTask t=task(threadId()); return t!=null && t.token.stopped; }
    synchronized void deleteThread(long id) {
        if(!ready) return;
        if(running(id)) throw new IllegalStateException(Language.text(R.string.stop_the_task_before_deleting_its_chat));
        db.delete(id); prefs.edit().remove("scroll-"+id).remove("follow-"+id).remove("compact-"+id).remove("autocompact-"+id).apply();
        if(lastTask!=null && lastTask.thread==id) lastTask=null;
        if(taskThread==id) {
            taskThread=0; taskTitle=""; taskAgent=""; taskState=Language.text(R.string.ready);
            prefs.edit().remove("task-thread").remove("task-title").remove("task-agent").putString("task-state",taskState).apply();
        }
        if(threadId()==id) { List<ChatStore.Conversation> saved=db.conversations(); load(saved.isEmpty()?db.blank(defaults,""):saved.get(0).id); }
        managementChanged();
    }
    void scrollPosition(long id,int y,boolean following) { if(id>0 && db.exists(id)) prefs.edit().putInt("scroll-"+id,y).putBoolean("follow-"+id,following).apply(); }
    int scrollPosition(long id) { return prefs.getInt("scroll-"+id,0); }
    boolean follows(long id) { return prefs.getBoolean("follow-"+id,true); }
    List<ChatStore.Message> versions(long message,long thread) { return db.versions(message,thread); }
    synchronized void login() {
        if(busy || loggingIn) return;
        loggingIn=true; error=""; loginCode=""; OpenAiClient.Cancel token=new OpenAiClient.Cancel(); job=token; changed();
        worker.execute(() -> {
            try {
                OpenAiClient.DeviceCode code=OpenAiClient.deviceCode(token); token.check();
                loginCode=code.code; changed();
                JSONObject auth=client.poll(code,token); updateAccount(auth); loginCode="";
                loggingIn=false; changed(); refreshLimits(); metadata.execute(() -> refreshModels(new OpenAiClient.Cancel()));
            } catch(Exception e) { if(!token.stopped) error=message(e); }
            finally { loggingIn=false; loginCode=""; if(job==token) job=null; changed(); }
        });
    }
    void cancel() {
        if(loggingIn) { OpenAiClient.Cancel login=job; if(login!=null) login.stop(); return; }
        AgentTask t=task(threadId()); if(t==null) t=task(taskThread);
        if(t!=null) cancel(t.thread,t.notificationId);
    }
    private synchronized boolean beginTask(ChatStore.Message answer,AgentConfig selected,OpenAiClient.Cancel token) {
        if(tasks.containsKey(answer.thread)) return false;
        ChatStore.Conversation thread=db.conversation(answer.thread);
        AgentTask t=new AgentTask(answer,selected,token,thread==null?threadTitle:thread.title);
        tasks.put(answer.thread,t); projectTasks(); error="";
        try {
            prefs.edit().putBoolean("task-active",true).putString("task-state",taskState).putLong("task-thread",taskThread)
                .putString("task-title",taskTitle).putString("task-agent",taskAgent).commit();
            app.startForegroundService(new android.content.Intent(app,AgentService.class)); managementChanged(); return true;
        } catch(Exception e) {
            answer.state="error"; answer.error=Language.text(R.string.cannot_start_background_task)+message(e);
            if(answer.id>0) db.save(answer); publish(answer);
            endTask(token,answer); return false;
        }
    }
    private synchronized void endTask(OpenAiClient.Cancel token,ChatStore.Message answer) {
        AgentTask t=tasks.get(answer.thread); if(t==null || t.token!=token) return;
        t.state=token.stopped?Language.text(R.string.stopped_check_unfinished_commands):answer.error.isEmpty()?Language.text(R.string.completed):Language.text(R.string.error)+answer.error;
        db.touch(answer.thread); publish(answer); tasks.remove(answer.thread); lastTask=t; executing.remove(); projectTasks();
        prefs.edit().putBoolean("task-active",busy).putString("task-state",taskState).commit(); managementChanged();
        main.post(() -> AgentService.result(app,t));
    }
    void saveAttachments(JSONArray files) { saveAttachments(threadId(),files); }
    void saveAttachments(long id,JSONArray files) { if(db.exists(id)) { db.draft(id,null,files.toString(),null); managementChanged(); } }
    JSONArray draftAttachments() { try { return new JSONArray(db.field(threadId(),"draft_files","[]")); } catch(Exception e) { return new JSONArray(); } }
    void saveExtra(long id,String title,String text) {
        try { if(db.exists(id)) { db.draft(id,null,null,text.isEmpty()?"":new JSONObject().put("title",title).put("text",text).toString()); managementChanged(); } }
        catch(Exception e) { error=message(e); changed(); }
    }
    JSONObject draftExtra() { try { return new JSONObject(db.field(threadId(),"draft_extra","{}")); } catch(Exception e) { return new JSONObject(); } }
    synchronized boolean addDraftAttachment(long id,JSONObject item) {
        try {
            JSONArray current=new JSONArray(db.field(id,"draft_files","[]"));
            if(!db.exists(id) || current.length()>=4) { Attachments.remove(item); return false; }
            current.put(item); db.draft(id,null,current.toString(),null); managementChanged(); return true;
        } catch(Exception e) { Attachments.remove(item); return false; }
    }
    void logout() {
        if(busy) { error=Language.text(R.string.stop_all_tasks_before_signing_out); changed(); return; }
        cancel(); worker.execute(() -> { secrets.clear(); updateAccount(null); limits=new JSONObject(); resetCredits=new JSONObject(); limitsUpdated=0; limitsError=""; error=""; changed(); });
    }
    private int modelRefreshFailures;
    void reloadModels(java.util.function.Consumer<String> callback) {
        metadata.execute(() -> {
            refreshModels(new OpenAiClient.Cancel());
            String failure=prefs.getString("model-catalog-error","");
            main.post(() -> callback.accept(failure));
        });
    }
    String codexVersion() { return client.clientVersion(); }
    void updateCodex(java.util.function.Consumer<String> callback) {
        metadata.execute(() -> {
            String result;
            try {
                String before=client.clientVersion(); client.clientVersion(OpenAiClient.latestCodexVersion(new OpenAiClient.Cancel()));
                prefs.edit().putString("codex-client-version",client.clientVersion()).apply();
                if(authenticated) refreshModels(new OpenAiClient.Cancel());
                String failure=authenticated?prefs.getString("model-catalog-error",""):"";
                result=String.format(Language.locale(),Language.text(before.equals(client.clientVersion())?R.string.codex_is_current:R.string.codex_updated),client.clientVersion())+
                    (authenticated && failure.isEmpty()?Language.text(R.string.model_list_refreshed):"")+
                    (failure.isEmpty()?"":"\n"+failure);
            } catch(Exception e) { result=Language.text(R.string.codex_update_failed)+(e.getMessage()==null?e.toString():e.getMessage()); }
            String text=result; main.post(() -> callback.accept(text));
        });
    }
    private void refreshModels(OpenAiClient.Cancel token) {
        try { List<String> found=client.models(token); synchronized(this) { models.clear(); models.addAll(found); }
            prefs.edit().putString("model-catalog",client.catalog.toString())
                .putLong("model-catalog-updated",System.currentTimeMillis()).putString("model-catalog-error","").apply();
            if(!prefs.getBoolean("sol-migration-complete",false) && found.contains(OpenAiClient.REQUESTED_MODEL) && !busy) {
                List<String> levels=effortsFor(OpenAiClient.REQUESTED_MODEL);
                String depth=levels.contains(effort)?effort:levels.get(0);
                selectModel(OpenAiClient.REQUESTED_MODEL,depth);
                prefs.edit().putBoolean("sol-migration-complete",true).apply();
            }
            changed(); }
        catch(Exception e) {
            prefs.edit().putString("model-catalog-error",message(e)).apply();
            android.util.Log.i("MeShell","Model catalogue unavailable: "+message(e));
            if(++modelRefreshFailures<=3) main.postDelayed(() -> {
                if(authenticated) metadata.execute(() -> refreshModels(new OpenAiClient.Cancel()));
            },15000L*modelRefreshFailures);
        }
    }
    synchronized boolean send(String text) { return send(text,new JSONArray()); }
    synchronized boolean send(String text,JSONArray attachments) {
        text=text.trim();
        if(selfOperation) { error=Language.text(R.string.wait_for_the_app_build_update_to_finish); changed(); return false; }
        if((text.isEmpty() && attachments.length()==0) || busyHere() || loggingIn || !ready) return false;
        if(text.isEmpty()) text=Language.text(R.string.analyze_the_attached_materials);
        if(attachments.length()>4) { error=Language.text(R.string.up_to_four_attachments_per_message); changed(); return false; }
        if(!canSend()) { error=Language.text(R.string.connect_chatgpt_to_send_a_message); changed(); return false; }
        if(text.length()>60000) { error=Language.text(R.string.a_message_is_limited_to_60_000_characters); changed(); return false; }
        final List<ChatStore.Message> pair;
        try { pair=db.addTurn(currentThread,text,attachments.toString()); }
        catch(Exception e) { error=message(e); changed(); return false; }
        messages.addAll(pair); threadTitle=db.conversation(currentThread).title;
        launch(pair.get(1),config); changed(); return true;
    }
    synchronized void retry() { if(!messages.isEmpty()) retry(messages.get(messages.size()-1).id); }
    synchronized boolean retry(long message) {
        if(selfOperation || busyHere() || loggingIn || !ready || !canSend() || !RetryPolicy.eligible(messages,message)) return false;
        ChatStore.Message answer=messages.get(messages.size()-1).copy();
        try {
            if(RetryPolicy.hasActions(answer)) {
                answer.wire=RetryPolicy.continuation(answer).toString(); answer.actions=RetryPolicy.recoverActions(answer.actions);
                if(!answer.text.isEmpty()) answer.text+="\n\n---\n\n";
                answer.attempt++; answer.error=""; answer.state="streaming"; db.save(answer); db.touch(answer.thread);
            } else db.regenerate(answer);
        } catch(Exception e) { error=message(e); changed(); return false; }
        publish(answer); launch(answer,config); changed(); return true;
    }
    private void launch(ChatStore.Message answer,AgentConfig selected) {
        OpenAiClient.Cancel token=new OpenAiClient.Cancel();
        if(!beginTask(answer,selected,token)) return;
        worker.execute(() -> {
            executing.set(task(answer.thread));
            try { respond(answer,selected,token); }
            catch(Exception e) { answer.state=token.stopped?"stopped":"error"; answer.error=message(e); db.save(answer); }
            finally { endTask(token,answer); }
        });
    }
    boolean autoCompact(long thread) { return prefs.getBoolean("autocompact-"+thread,true); }
    void autoCompact(long thread,boolean enabled) { prefs.edit().putBoolean("autocompact-"+thread,enabled).apply(); managementChanged(); }
    private JSONObject compactState(long thread) {
        try { return new JSONObject(prefs.getString("compact-"+thread,"{}")); } catch(Exception e) { return new JSONObject(); }
    }
    String compactStatus(long thread) {
        JSONObject state=compactState(thread); return state.optString("summary").isEmpty()?Language.text(R.string.context_not_compacted):Language.text(R.string.summary_through_message)+state.optLong("through")+" · ~"+state.optInt("tokens")+Language.text(R.string.tokens);
    }
    void resetCompact(long thread) { if(running(thread)) return; prefs.edit().remove("compact-"+thread).apply(); managementChanged(); }
    synchronized void compact(long thread,java.util.function.Consumer<String> callback) {
        if(selfOperation || running(thread) || loggingIn || !ready || !canSend() || thread!=currentThread) { callback.accept(Language.text(R.string.wait_for_the_task_to_finish_and_check_your_chatgpt_connectio)); return; }
        if(CompactContext.cutoff(db.messages(thread))<=compactState(thread).optLong("through")) { callback.accept(Language.text(R.string.no_old_turns_to_compact_the_latest_two_turns_are_kept)); return; }
        final AgentConfig selected=config; final OpenAiClient.Cancel token=new OpenAiClient.Cancel();
        final ChatStore.Message marker=new ChatStore.Message(-1,thread,"assistant","","streaming","");
        if(!beginTask(marker,selected,token)) { callback.accept(marker.error); return; }
        final AgentTask compactTask=task(thread); compactTask.phase=Language.text(R.string.compacting_context); changed();
        worker.execute(() -> {
            executing.set(compactTask);
            String result;
            try { performCompact(thread,selected,token); marker.state="complete"; result=Language.text(R.string.context_compacted_history_and_attachments_kept); }
            catch(Exception e) { marker.state=token.stopped?"stopped":"error"; marker.error=message(e); result=Language.text(R.string.compaction_failed)+marker.error+Language.text(R.string.previous_context_kept); }
            finally { endTask(token,marker); }
            final String status=result; main.post(() -> callback.accept(status));
        });
    }
    private boolean performCompact(long thread,AgentConfig selected,OpenAiClient.Cancel token) throws Exception {
        List<ChatStore.Message> history=db.messages(thread); long through=CompactContext.cutoff(history);
        JSONObject previous=compactState(thread); long after=previous.optLong("through"); if(through<=after) return false;
        int budget=Math.min(selected.context,modelInfo(selected.model).optInt("context_window",262144));
        if(budget<8192) throw new IOException(Language.text(R.string.compaction_requires_at_least_8192_context_tokens));
        List<String> chunks=CompactContext.chunks(history,after,through,Math.min(24000,Math.max(3000,budget/3)));
        String summary=previous.optString("summary");
        for(int i=0;i<chunks.size();i++) {
            token.check(); executing.get().phase=Language.text(R.string.compaction_part)+(i+1)+"/"+chunks.size(); changed();
            JSONArray input=new JSONArray().put(ContextBudget.message("user",Language.text(R.string.update_the_working_summary_preserving_important_facts_from_t)+summary+Language.text(R.string.next_history_fragment)+chunks.get(i)));
            if(ContextBudget.estimate(input)+ContextBudget.estimate(CompactContext.INSTRUCTIONS)+2048>budget) throw new IOException(Language.text(R.string.summary_exceeds_the_budget_increase_context_original_history));
            final StringBuilder output=new StringBuilder();
            client.stream(selected.model,selected.effort,input,new JSONArray(),CompactContext.INSTRUCTIONS,token,new OpenAiClient.Output() {
                public void delta(String text) { output.append(text); }
                public void completed(String model,int tokens) { }
            });
            token.check(); summary=tools.vault.redact(output.toString().trim());
            if(summary.isEmpty() || ContextBudget.estimate(summary)>Math.min(6000,budget/3)) throw new IOException(Language.text(R.string.empty_or_oversized_summary_previous_context_kept));
        }
        token.check(); JSONObject state=new JSONObject().put("summary",summary).put("through",through).put("tokens",ContextBudget.estimate(summary)).put("updated",System.currentTimeMillis());
        if(!prefs.edit().putString("compact-"+thread,state.toString()).commit()) throw new IOException(Language.text(R.string.cannot_save_summary));
        managementChanged(); return true;
    }
    private JSONArray compactHistory(long thread,long exclude,int budget) throws Exception {
        JSONObject state=compactState(thread); String summary=state.optString("summary");
        if(summary.isEmpty()) return ContextBudget.history(db.messages(thread),exclude,budget);
        JSONObject summaryMessage=CompactContext.summaryMessage(summary);
        JSONArray input=new JSONArray().put(summaryMessage);
        ContextBudget.append(input,ContextBudget.history(CompactContext.tail(db.messages(thread),state.optLong("through")),exclude,budget-ContextBudget.estimate(summaryMessage)));
        return input;
    }
    private void respond(ChatStore.Message answer,AgentConfig selected,OpenAiClient.Cancel token) throws Exception {
        final AgentTask task=executing.get();
        try {
            JSONArray wire=new JSONArray(answer.wire); ContextBudget.repairInterrupted(wire);
            JSONArray actions=new JSONArray(answer.actions);
            final StringBuilder result=new StringBuilder(answer.text); final long[] saved={0}; final boolean[] fresh={false};
            final boolean enabled=selected.tools; JSONArray definitions=enabled?tools.schema():new JSONArray();
            String instructions=enabled?AgentTools.INSTRUCTIONS:"MeShell tools are disabled for this conversation.";
            if(!selected.instructions.isEmpty()) instructions+="\n\n"+selected.instructions;
            int budget=Math.min(selected.context,modelInfo(selected.model).optInt("context_window",262144));
            int overhead=ContextBudget.estimate(definitions)+ContextBudget.estimate(instructions)+2048;
            while(true) {
                token.check(); task.step++; task.phase=Language.text(R.string.responding_step)+task.step; changed();
                int available=budget-overhead-ContextBudget.estimate(wire);
                if(autoCompact(answer.thread) && available>=4096) {
                    JSONArray whole=compactHistory(answer.thread,answer.id,Integer.MAX_VALUE/2);
                    if(ContextBudget.estimate(whole)>available*0.8 && CompactContext.cutoff(db.messages(answer.thread))>compactState(answer.thread).optLong("through")) {
                        performCompact(answer.thread,selected,token); task.phase=Language.text(R.string.responding_step)+task.step; changed();
                    }
                }
                JSONArray input=compactHistory(answer.thread,answer.id,available);
                ContextBudget.append(input,wire); task.contextEstimate=overhead+ContextBudget.estimate(input);
                ContextBudget.materializeAttachments(input); fresh[0]=true;
                JSONObject response=client.stream(selected.model,selected.effort,input,definitions,instructions,token,new OpenAiClient.Output() {
                    @Override public void delta(String text) {
                        if(text.isEmpty()) return;
                        // Text from separate agent steps would otherwise run together mid-sentence.
                        if(fresh[0]) { fresh[0]=false; if(result.length()>0 && !Character.isWhitespace(result.charAt(result.length()-1))) result.append("\n\n"); }
                        result.append(text); synchronized(ChatEngine.this) { answer.text=result.toString(); publish(answer); }
                        long now=System.currentTimeMillis(); if(now-saved[0]>1500) { db.save(answer); saved[0]=now; } changed();
                    }
                    @Override public void completed(String name,int tokens) { task.actualModel=name; task.totalTokens+=tokens; }
                });
                JSONObject usage=response.optJSONObject("usage"); if(usage!=null) task.lastInputTokens=usage.optInt("input_tokens");
                JSONArray items=response.optJSONArray("output"); if(items==null) items=new JSONArray();
                ContextBudget.append(wire,items); answer.wire=wire.toString(); db.save(answer);
                boolean called=false;
                for(int i=0;i<items.length();i++) {
                    JSONObject call=items.getJSONObject(i); if(!"function_call".equals(call.optString("type"))) continue;
                    token.check(); called=true; String name=call.getString("name"); JSONObject args=new JSONObject(call.getString("arguments"));
                    task.phase=Language.text(R.string.running_2)+name;
                    long started=System.currentTimeMillis();
                    JSONObject action=new JSONObject().put("name",name).put("arguments",tools.vault.redact(args.toString()))
                        .put("state","running").put("started",started).put("output","");
                    actions.put(action); answer.actions=actions.toString(); db.save(answer); changed();
                    JSONObject output;
                    try {
                        if(!enabled) output=new JSONObject().put("error",Language.text(R.string.tools_are_disabled_for_this_task_this_call_was_not_executed));
                        else { token.check(); output=executeTool(task,name,args); }
                    }
                    catch(Exception e) { if(token.stopped) throw e; output=new JSONObject().put("error",message(e)).put("note",Language.text(R.string.after_connection_loss_check_the_actual_state)); }
                    token.check(); Object payload; String image=output.optString("_image"); output.remove("_image");
                    String visible=tools.vault.redact(output.toString());
                    if(visible.length()>26000) visible=visible.substring(0,26000)+Language.text(R.string.output_truncated_use_read_file_ranges);
                    if(!image.isEmpty()) payload=new JSONArray().put(new JSONObject().put("type","input_text").put("text",visible))
                        .put(new JSONObject().put("type","input_image").put("image_url",image));
                    else payload=visible;
                    wire.put(new JSONObject().put("type","function_call_output").put("call_id",call.getString("call_id")).put("output",payload));
                    action.put("output",visible).put("state",output.has("error") || (output.has("exit_code") && output.optInt("exit_code")!=0)?"error":"done")
                        .put("elapsed_ms",System.currentTimeMillis()-started);
                    synchronized(this) { answer.actions=actions.toString(); answer.wire=wire.toString(); }
                    db.save(answer); changed();
                }
                if(!called) break;

            }
            token.check(); synchronized(this) { answer.state="complete"; }
        } catch(Exception e) {
            synchronized(this) { answer.state=token.stopped?"stopped":"error"; answer.error=token.stopped?Language.text(R.string.response_stopped):message(e); }
            // The failure belongs to this answer, not whichever chat is currently open.
        } finally {
            try {
                JSONArray log=new JSONArray(answer.actions);
                for(int i=0;i<log.length();i++) if("running".equals(log.getJSONObject(i).optString("state")))
                    log.getJSONObject(i).put("state",token.stopped?"stopped":"interrupted")
                        .put("output",Language.text(R.string.execution_interrupted_outcome_unconfirmed_the_action_may_hav));
                answer.actions=log.toString();
            } catch(Exception ignored) { }
            db.save(answer); changed(); refreshLimits();
        }
    }
}
