package com.mesystem.shell;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import services.market.MarketRules;

/**
 * Community catalogue client. The index and statistics come from the catalogue's GitHub Pages site; installs download
 * the GitHub release asset, which GitHub counts, and must match the SHA-256 published in the index.
 */
final class Marketplace {
    static final String REPOSITORY="ilya-bondarev/meshell-tools";
    static final String SITE="https://ilya-bondarev.github.io/meshell-tools/";
    static final String PROJECT="https://github.com/"+REPOSITORY;
    private static final String AGENT="MeShell-market";
    private static final int CATALOG_LIMIT=4*1024*1024;
    /** Statistics are rebuilt hourly; an older copy is refreshed in the background when the catalogue opens. */
    static final long STALE=30*60*1000L;
    private final Context context;
    private final ModuleHub hub;
    private final File file;
    private JSONObject catalog;

    Marketplace(Context context,ModuleHub hub) { this.context=context.getApplicationContext(); this.hub=hub; file=new File(this.context.getFilesDir(),"market.json"); }

    /** Last downloaded catalogue, or null before the first successful refresh. */
    synchronized JSONObject cached() {
        if(catalog==null && file.isFile()) try { catalog=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)); } catch(Exception ignored) { }
        return catalog;
    }
    long fetched() { JSONObject value=cached(); return value==null?0:value.optLong("fetched"); }
    JSONObject refresh(OpenAiClient.Cancel cancel) throws Exception {
        String fresh="?t="+System.currentTimeMillis()/60000;
        JSONObject index=new JSONObject(new String(Http.get(SITE+"index.json"+fresh,AGENT,CATALOG_LIMIT,cancel),StandardCharsets.UTF_8));
        if(index.optInt("format")<2 || index.optJSONArray("tools")==null) throw new IOException(Language.text(R.string.catalog_unavailable));
        JSONObject stats;
        try { stats=new JSONObject(new String(Http.get(SITE+"stats.json"+fresh,AGENT,CATALOG_LIMIT,cancel),StandardCharsets.UTF_8)); }
        catch(IOException e) { if(cancel.stopped) throw e; stats=new JSONObject(); }
        JSONObject value=new JSONObject().put("index",index).put("stats",stats).put("fetched",System.currentTimeMillis());
        File next=new File(file.getPath()+".next");
        try(FileOutputStream out=new FileOutputStream(next)) { out.write(value.toString().getBytes(StandardCharsets.UTF_8)); }
        if(!next.renameTo(file)) throw new IOException("Cannot save "+file);
        synchronized(this) { catalog=value; } return value;
    }

    /** The catalogue language block matching the UI language, or null. */
    static JSONObject translation(JSONObject entry) {
        JSONObject values=entry.optJSONObject("translations");
        return values==null?null:values.optJSONObject(Language.locale().getLanguage());
    }
    /** Replaces the texts the target already has with their translations: title, description, README, requirements and action labels. */
    static void localize(JSONObject target,JSONObject local) throws Exception {
        if(local==null) return;
        for(String key:new String[]{"title","description","readme"}) if(target.has(key) && !local.optString(key).trim().isEmpty()) target.put(key,local.getString(key));
        if(target.has("requirements") && local.optJSONArray("requirements")!=null) target.put("requirements",local.getJSONArray("requirements"));
        JSONObject labels=local.optJSONObject("actions"); JSONArray actions=target.optJSONArray("actions");
        for(int i=0;labels!=null && actions!=null && i<actions.length();i++) {
            JSONObject action=actions.optJSONObject(i); if(action!=null && !labels.optString(action.optString("id")).trim().isEmpty()) action.put("label",labels.getString(action.optString("id")));
        }
    }
    /** Catalogue entries with statistics, translations and the local install state. */
    JSONArray tools() throws Exception {
        JSONObject value=cached(); JSONArray out=new JSONArray(); if(value==null) return out;
        JSONArray tools=value.getJSONObject("index").getJSONArray("tools"); JSONObject stats=value.getJSONObject("stats").optJSONObject("tools");
        Map<String,JSONObject> installed=new HashMap<>(); JSONArray modules=hub.list();
        for(int i=0;i<modules.length();i++) installed.put(modules.getJSONObject(i).getString("id"),modules.getJSONObject(i));
        for(int i=0;i<tools.length();i++) {
            JSONObject tool=new JSONObject(tools.getJSONObject(i).toString()); String id=tool.getString("id");
            JSONObject numbers=stats==null?null:stats.optJSONObject(id); if(numbers==null) numbers=new JSONObject();
            tool.put("downloads",numbers.optLong("downloads")).put("rating",numbers.optDouble("rating",0)).put("votes",numbers.optInt("votes"))
                .put("histogram",numbers.optJSONArray("histogram")==null?new JSONArray("[0,0,0,0,0]"):numbers.getJSONArray("histogram"))
                .put("reviews",numbers.optJSONArray("reviews")==null?new JSONArray():numbers.getJSONArray("reviews")).put("packages_size",numbers.optLong("packages_size"));
            StringBuilder search=new StringBuilder(tool.optString("title")+" "+tool.optString("description")+" "+tool.optString("author")+" "+tool.optString("github")+" "+id);
            localize(tool,translation(tool));
            tool.put("search",search.append(' ').append(tool.optString("title")).append(' ').append(tool.optString("description")).toString());
            JSONObject module=installed.get(id);
            if(module!=null) tool.put("installed",true).put("installed_source",module.optString("source")).put("installed_version",module.optString("version"));
            out.put(tool);
        }
        return out;
    }
    /** Open module requests from the catalogue statistics, most wanted first. */
    JSONArray requests() {
        JSONObject value=cached(),stats=value==null?null:value.optJSONObject("stats");
        JSONArray out=stats==null?null:stats.optJSONArray("requests"); return out==null?new JSONArray():out;
    }
    JSONObject tool(String id) throws Exception {
        JSONArray tools=tools(); for(int i=0;i<tools.length();i++) if(id.equals(tools.getJSONObject(i).getString("id"))) return tools.getJSONObject(i);
        return null;
    }
    static boolean update(JSONObject tool) {
        return "catalog".equals(tool.optString("installed_source")) && MarketRules.newer(tool.optString("version"),tool.optString("installed_version"));
    }
    String appVersion() {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName; } catch(Exception e) { return "0.0.0"; }
    }
    boolean supported(JSONObject tool) { return MarketRules.compareVersions(appVersion(),tool.optString("min_app_version","0.0.0"))>=0; }

    /** Downloads a definition: the counted release asset to install, the Pages copy to preview. Both must match the index digest. */
    JSONObject definition(JSONObject tool,boolean install,OpenAiClient.Cancel cancel) throws Exception {
        String url=install?tool.getString("download"):SITE+tool.getString("definition");
        if(!url.startsWith(PROJECT+"/releases/download/") && !url.startsWith(SITE)) throw new IOException(Language.text(R.string.catalog_unavailable));
        byte[] bytes=Http.get(url,AGENT,hub.importLimitBytes(),cancel);
        StringBuilder hex=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.ROOT,"%02x",b));
        if(!hex.toString().equals(tool.optString("sha256"))) throw new IOException(Language.text(R.string.module_checksum_mismatch));
        JSONObject definition=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(!tool.getString("id").equals(definition.optString("id"))) throw new IOException(Language.text(R.string.module_checksum_mismatch));
        return definition;
    }
    void install(JSONObject tool,JSONObject definition) throws Exception {
        JSONObject meta=new JSONObject();
        for(String key:new String[]{"version","author","github","category","icon","sha256","translations"}) if(tool.has(key)) meta.put(key,tool.get(key));
        hub.installCatalog(definition,meta);
    }

    /** Popular or matching modules for the agent; refreshes a stale copy when the network allows. */
    JSONObject search(String query,OpenAiClient.Cancel cancel) throws Exception {
        if(System.currentTimeMillis()-fetched()>STALE) try { refresh(cancel); } catch(IOException e) { if(cancel.stopped) throw e; }
        JSONArray tools=tools(),out=new JSONArray();
        for(int index:MarketRules.arrange(listings(tools),query,"",MarketRules.Order.POPULAR)) {
            JSONObject tool=tools.getJSONObject(index),item=new JSONObject();
            for(String key:new String[]{"id","title","description","version","author","github","category","permissions","packages","rating","votes","downloads","size","installed"}) if(tool.has(key)) item.put(key,tool.get(key));
            out.put(item); if(out.length()==20) break;
        }
        return new JSONObject().put("modules",out).put("install","Modules → Catalogue; installation needs the user's consent");
    }
    static List<MarketRules.Listing> listings(JSONArray tools) throws Exception {
        List<MarketRules.Listing> out=new ArrayList<>();
        for(int i=0;i<tools.length();i++) { JSONObject t=tools.getJSONObject(i);
            out.add(new MarketRules.Listing(t.getString("id"),t.optString("category"),t.optString("search"),t.optString("updated"),t.optLong("downloads"),t.optDouble("rating",0),t.optInt("votes"))); }
        return out;
    }

    /** Files the rating with the connected GitHub account; returns the issue URL. */
    String rate(JSONObject tool,int stars,String review,OpenAiClient.Cancel cancel) throws Exception {
        return hub.github.issue(REPOSITORY,MarketRules.ratingTitle(tool.getString("id")),MarketRules.ratingBody(tool.getString("id"),tool.optString("version"),stars,review),cancel);
    }
    static String rateLink(JSONObject tool,int stars,String review) throws Exception {
        return MarketRules.issueLink(REPOSITORY,MarketRules.ratingTitle(tool.getString("id")),MarketRules.ratingBody(tool.getString("id"),tool.optString("version"),stars,review));
    }
    /** A catalogue submission of an installed custom module: portable export plus catalogue metadata. */
    JSONObject submission(String id,String category,String version,String about) throws Exception {
        if(!MarketRules.version(version)) throw new IllegalArgumentException(Language.text(R.string.version_format));
        JSONObject module=hub.export(id);
        JSONObject metadata=new JSONObject().put("version",version).put("category",category).put("license","MIT-0").put("min_app_version",appVersion());
        return new JSONObject().put("format",1).put("module",module).put("catalog",metadata).put("readme",about.trim().isEmpty()?module.optString("description",module.getString("title")):about.trim());
    }
    String submit(JSONObject submission,OpenAiClient.Cancel cancel) throws Exception {
        return hub.github.issue(REPOSITORY,MarketRules.submissionTitle(submission.getJSONObject("module").getString("id")),MarketRules.submissionBody(submission.toString(2)),cancel);
    }
    /** Browser fallback; a body too long for a link is left on the clipboard by the caller. */
    static String submitLink(JSONObject submission) throws Exception {
        String title=MarketRules.submissionTitle(submission.getJSONObject("module").getString("id"));
        String link=MarketRules.issueLink(REPOSITORY,title,MarketRules.submissionBody(submission.toString(2)));
        return link.length()<=MarketRules.LINK_LIMIT?link:MarketRules.issueLink(REPOSITORY,title,Language.text(R.string.paste_submission));
    }
    static String requestLink(String text) { return MarketRules.issueLink(REPOSITORY,MarketRules.requestTitle(text.isEmpty()?Language.text(R.string.module_request):text),Language.text(R.string.module_request_body)); }
    static String reportLink(JSONObject tool) throws Exception { return MarketRules.issueLink(REPOSITORY,MarketRules.reportTitle(tool.getString("id")),Language.text(R.string.module_report_body)); }
    static String sourceLink(JSONObject tool) throws Exception { return PROJECT+"/tree/main/tools/"+tool.getString("id"); }
    static String reviewsLink(JSONObject tool) throws Exception { return PROJECT+"/issues?q="+java.net.URLEncoder.encode("is:issue \"[rating] "+tool.getString("id")+"\" in:title","UTF-8"); }
    static String profileLink(String login) { return "https://github.com/"+login; }
    /** The catalogue web page entry: readable without the app and links to MeShell. */
    static String shareLink(JSONObject tool) throws Exception { return SITE+"#"+tool.getString("id"); }
}
