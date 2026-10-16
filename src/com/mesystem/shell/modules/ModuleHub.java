package com.mesystem.shell;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

final class ModuleHub {
    private final ModuleStore store;
    private final ModuleDefinition definitions;
    final SecretVault vault;
    final ShellBroker shell;
    final TelemetryModule telemetry;
    final GitHubModule github;
    private final Context context;
    private final java.util.concurrent.ExecutorService jobsExecutor=java.util.concurrent.Executors.newFixedThreadPool(2);
    private final java.util.LinkedHashMap<String,JSONObject> jobs=new java.util.LinkedHashMap<>();
    final SharedPreferences prefs;
    private String[][] builtins() { return new String[][]{
        {"notes",Language.text(R.string.notes),Language.text(R.string.local_text_notes),"28"},
        {"device",Language.text(R.string.device),Language.text(R.string.cpu_memory_and_sensors),"24"},
        {"mascot",Language.text(R.string.mascot_title),Language.text(R.string.mascot_description),"32"},
        {"github","GitHub",Language.text(R.string.github_description),"36"}
    }; }
    ModuleHub(Context context) {
        this.context=context; definitions=new ModuleDefinition(context.getResources());
        store=new ModuleStore(context); vault=((MeShellApp)context).vault(); shell=new ShellBroker(context);
        prefs=context.getSharedPreferences("modules",Context.MODE_PRIVATE);
        telemetry=new TelemetryModule(context,shell); github=new GitHubModule(context,shell,vault);
    }
    JSONArray extensions() throws Exception { return store.extensions(); }
    JSONArray notes() throws Exception { return store.notes(); }
    long saveNote(long id,String title,String body) { return store.saveNote(id,title,body); }
    void deleteNote(long id) { store.deleteNote(id); }
    boolean enabled(String id) { return prefs.getBoolean("enabled-"+id,true); }
    synchronized String startJob(JSONObject request) throws Exception {
        if(jobs.size()>=16) {
            java.util.Iterator<java.util.Map.Entry<String,JSONObject>> entries=jobs.entrySet().iterator();
            while(entries.hasNext()) if(!"running".equals(entries.next().getValue().optString("state"))) { entries.remove(); break; }
            if(jobs.size()>=16) throw new IllegalStateException(Language.text(R.string.module_queue_busy));
        }
        String id=java.util.UUID.randomUUID().toString(); jobs.put(id,new JSONObject().put("state","running"));
        jobsExecutor.execute(() -> {
            JSONObject result;
            try {
                JSONObject value=execute(request.getString("module"),request.getString("action"),request.optJSONObject("arguments")==null?new JSONObject():request.getJSONObject("arguments"),new OpenAiClient.Cancel());
                String encoded=vault.redact(value.toString());
                result=new JSONObject().put("state","done").put("result",encoded.length()>300000?new JSONObject().put("truncated",true).put("preview",encoded.substring(0,300000)):new JSONObject(encoded));
            } catch(Exception e) { result=new JSONObject(); try { result.put("state","error").put("error",e.getMessage()==null?e.toString():e.getMessage()); } catch(Exception ignored) { } }
            synchronized(ModuleHub.this) { jobs.put(id,result); }
        }); return id;
    }
    synchronized JSONObject job(String id) throws Exception { JSONObject value=jobs.get(id); return value==null?new JSONObject().put("state","unknown"):new JSONObject(value.toString()); }
    void enabled(String id,boolean value) { prefs.edit().putBoolean("enabled-"+id,value).apply(); }
    boolean builtin(String id) { for(String[] value:builtins()) if(value[0].equals(id)) return true; return false; }
    JSONArray list() throws Exception {
        JSONArray out=new JSONArray();
        for(String[] value:builtins()) out.put(new JSONObject().put("id",value[0]).put("title",value[1]).put("description",value[2])
            .put("icon",Integer.parseInt(value[3])).put("enabled",enabled(value[0])).put("builtin",true).put("actions",description(value[0])));
        JSONArray custom=store.extensions();
        for(int i=0;i<custom.length();i++) { JSONObject item=custom.getJSONObject(i); if(builtin(item.optString("id"))) continue; item.put("enabled",enabled(item.getString("id"))).put("builtin",false); out.put(item); }
        return out;
    }
    private String description(String id) {
        switch(id) {
            case "notes": return "list {}; save {id?:number,title,text}; delete {id}";
            case "device": return "sample {} (CPU needs two samples); sensors {}";
            case "mascot": return "status {}; start {}; stop {}";
            case "github": return "status {}; repos {}; repo {repo:owner/name}; issues {repo}; prs {repo}; runs {repo}";
            default: return "";
        }
    }
    JSONObject prepare(JSONObject definition) throws Exception {
        definitions.validate(definition);
        if(builtin(definition.getString("id"))) throw new IllegalArgumentException(Language.text(R.string.use_a_custom_module_id_a_z_0_9));
        return new JSONObject(definition.toString());
    }
    int importLimitBytes() { return definitions.maxCharacters()*4; }
    void install(JSONObject definition) throws Exception {
        JSONObject checked=prepare(definition); store.extension(checked); enabled(checked.getString("id"),true);
    }
    JSONObject export(String id) throws Exception {
        JSONArray values=store.extensions();
        for(int i=0;i<values.length();i++) if(id.equals(values.getJSONObject(i).optString("id"))) {
            JSONObject result=definitions.portable(values.getJSONObject(i)); String text=result.toString();
            if(!vault.redact(text).equals(text)) throw new IllegalArgumentException(Language.text(R.string.module_contains_secret));
            return result;
        }
        throw new IllegalArgumentException(Language.text(R.string.module_not_found));
    }
    void remove(String id) { if(builtin(id)) enabled(id,false); else store.removeExtension(id); }
    JSONObject execute(String id,String action,JSONObject args,OpenAiClient.Cancel cancel) throws Exception {
        if(!enabled(id)) throw new IllegalStateException(Language.text(R.string.module_disabled)+id);
        JSONObject result;
        switch(id) {
            case "notes":
                if(action.equals("list")) return new JSONObject().put("notes",store.notes());
                if(action.equals("save")) return new JSONObject().put("id",store.saveNote(args.optLong("id"),args.optString("title",Language.text(R.string.note)),args.getString("text")));
                if(action.equals("delete")) { store.deleteNote(args.getLong("id")); return new JSONObject().put("deleted",true); }
                break;
            case "device":
                if(action.equals("sample")) return telemetry.sample(cancel);
                if(action.equals("sensors")) return new JSONObject().put("sensors",telemetry.sensors());
                break;
            case "mascot":
                boolean allowed=android.provider.Settings.canDrawOverlays(context);
                android.content.Intent mascot=new android.content.Intent(context,MascotService.class);
                if(action.equals("status")) return new JSONObject().put("overlay_allowed",allowed).put("running",MascotService.running);
                if(action.equals("stop")) { context.stopService(mascot); return new JSONObject().put("stopped",true); }
                if(action.equals("start")) {
                    if(!allowed) throw new IllegalStateException(Language.text(R.string.mascot_needs_overlay));
                    context.startForegroundService(mascot); return new JSONObject().put("started",true);
                }
                break;
            case "github": return new JSONObject(vault.redact(github.run(action,args,cancel).toString()));
        }
        if(!builtin(id)) {
            JSONArray list=store.extensions();
            for(int i=0;i<list.length();i++) if(id.equals(list.getJSONObject(i).optString("id"))) {
                JSONArray actions=list.getJSONObject(i).getJSONArray("actions");
                for(int n=0;n<actions.length();n++) {
                    JSONObject definition=actions.getJSONObject(n); if(!action.equals(definition.getString("id"))) continue;
                    Map<String,String> env=new HashMap<>(); JSONObject names=definition.optJSONObject("env_secrets");
                    if(names!=null) { java.util.Iterator<String> keys=names.keys(); while(keys.hasNext()) { String key=keys.next(); Object value=vault.get(names.getString(key));
                        if(!(value instanceof String)) throw new IllegalArgumentException(Language.text(R.string.environment_variable_requires_a_string_secret)); env.put(key,(String)value); } }
                    result=shell.run(definition.getString("command"),definition.optString("cwd",ShellBroker.HOME),args.optString("stdin","").getBytes(java.nio.charset.StandardCharsets.UTF_8),env,
                        Math.max(1000,Math.min(900000,definition.optInt("timeout_ms",30000))),cancel).json();
                    return new JSONObject(vault.redact(result.toString()));
                }
            }
        }
        throw new IllegalArgumentException(Language.text(R.string.unknown_module_action)+id+"/"+action);
    }
    void clear(String id) { store.clear(id); prefs.edit().remove("cache-"+id).apply(); }
}
