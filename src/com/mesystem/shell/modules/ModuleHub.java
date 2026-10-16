package com.mesystem.shell;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    final Marketplace market;
    private final Context context;
    private final java.util.concurrent.ExecutorService jobsExecutor=java.util.concurrent.Executors.newFixedThreadPool(2);
    private final java.util.LinkedHashMap<String,JSONObject> jobs=new java.util.LinkedHashMap<>();
    final SharedPreferences prefs;
    /** id, title, description, icon, permissions. Built-in permissions use the module vocabulary plus Android's overlay. */
    private String[][] builtins() { return new String[][]{
        {"notes",Language.text(R.string.notes),Language.text(R.string.local_text_notes),"28",""},
        {"device",Language.text(R.string.device),Language.text(R.string.cpu_memory_and_sensors),"24",""},
        {"mascot",Language.text(R.string.mascot_title),Language.text(R.string.mascot_description),"32","overlay"},
        {"github","GitHub",Language.text(R.string.github_description),"36","network,secrets,packages"}
    }; }
    ModuleHub(Context context) {
        this.context=context; definitions=new ModuleDefinition(context.getResources());
        store=new ModuleStore(context); vault=((MeShellApp)context).vault(); shell=new ShellBroker(context);
        prefs=context.getSharedPreferences("modules",Context.MODE_PRIVATE);
        telemetry=new TelemetryModule(context,shell); github=new GitHubModule(context,shell,vault); market=new Marketplace(context,this);
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
            .put("icon",Integer.parseInt(value[3])).put("enabled",enabled(value[0])).put("builtin",true).put("actions",description(value[0]))
            .put("permissions",new JSONArray(value[4].isEmpty()?new String[0]:value[4].split(","))).put("missing",new JSONArray()).put("source","builtin").put("author","MeShell"));
        JSONArray custom=store.extensions();
        for(int i=0;i<custom.length();i++) {
            JSONObject item=custom.getJSONObject(i); String id=item.optString("id"); if(builtin(id)) continue;
            JSONObject meta=store.meta(id);
            item.put("enabled",enabled(id)).put("builtin",false).put("permissions",permissions(item)).put("missing",new JSONArray(missing(item,meta)))
                .put("source",meta==null?"local":meta.optString("source","local"));
            if(meta!=null) for(String key:new String[]{"version","author","github","category"}) if(meta.has(key)) item.put(key,meta.get(key));
            if(meta!=null && meta.has("icon")) item.put("icon_name",meta.getString("icon"));
            if(meta!=null) Marketplace.localize(item,Marketplace.translation(meta));
            out.put(item);
        }
        return out;
    }
    /** Permissions the user has not approved yet; legacy modules keep the trust they were installed with. */
    private List<String> missing(JSONObject definition,JSONObject meta) throws Exception {
        if(meta==null) return new ArrayList<>();
        List<String> out=definitions.permissions(definition); JSONArray granted=meta.optJSONArray("granted");
        if(granted!=null) for(int i=0;i<granted.length();i++) out.remove(granted.getString(i));
        return out;
    }
    JSONArray permissions(JSONObject definition) throws Exception { return new JSONArray(definitions.permissions(definition)); }
    /** Records the user's consent to every permission the module currently needs. */
    void grant(String id) throws Exception {
        JSONObject definition=definition(id),meta=store.meta(id); if(definition==null || meta==null) return;
        meta.put("granted",permissions(definition)); store.meta(id,meta);
    }
    JSONObject definition(String id) throws Exception {
        JSONArray values=store.extensions();
        for(int i=0;i<values.length();i++) if(id.equals(values.getJSONObject(i).optString("id"))) return values.getJSONObject(i);
        return null;
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
    /** Agent and file installs. With consent the user approved the shown permissions; otherwise earlier approvals carry over. */
    void install(JSONObject definition,boolean consent) throws Exception {
        JSONObject checked=prepare(definition); String id=checked.getString("id"); JSONObject old=definition(id),previous=store.meta(id);
        JSONArray granted=consent?permissions(checked):previous!=null?previous.optJSONArray("granted"):old!=null?permissions(old):new JSONArray();
        store.extension(checked,new JSONObject().put("source","local").put("granted",granted==null?new JSONArray():granted)); enabled(id,true);
    }
    void install(JSONObject definition) throws Exception { install(definition,false); }
    /** Catalogue installs: the consent dialog listed these permissions before the download. */
    void installCatalog(JSONObject definition,JSONObject meta) throws Exception {
        JSONObject checked=prepare(definition);
        store.extension(checked,meta.put("source","catalog").put("granted",permissions(checked)).put("installed",System.currentTimeMillis())); enabled(checked.getString("id"),true);
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
                    List<String> missing=missing(list.getJSONObject(i),store.meta(id));
                    if(!missing.isEmpty()) throw new IllegalStateException(Language.text(R.string.module_needs_consent)+String.join(", ",missing));
                    JSONArray packages=list.getJSONObject(i).optJSONArray("packages");
                    if(packages!=null && packages.length()>0) { List<String> names=new ArrayList<>(); for(int k=0;k<packages.length();k++) names.add(packages.getString(k)); new Packages(context).ensure(names,cancel); }
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
