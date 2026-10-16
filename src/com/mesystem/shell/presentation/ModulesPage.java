package com.mesystem.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

final class ModulesPage extends LinearLayout {
    interface Work { JSONObject run(OpenAiClient.Cancel cancel) throws Exception; }
    /** Routes besides module IDs: the catalogue, one catalogue entry and the publishing rules. */
    static final String STORE="@store",ITEM="@store:",RULES="@rules";
    final Activity activity;
    final ModuleHub hub;
    final Consumer<String> toChat;
    private final Runnable pickModule;
    private final Consumer<JSONObject> saveModule;
    private final FrameLayout stage;
    private final TextView title,subtitle;
    private final IconView back,add,reload;
    private final Button mine,catalog;
    private final LinearLayout tabs;
    private final MarketPage market;
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> history=new ArrayDeque<>();
    private String current="";
    private MonitorPage monitor;
    private boolean active;
    private Runnable leave;
    private long screenEpoch;

    ModulesPage(Activity activity,ModuleHub hub,Runnable pickModule,Consumer<String> toChat,Consumer<JSONObject> saveModule) {
        super(activity); this.activity=activity; this.hub=hub; this.pickModule=pickModule; this.toChat=toChat; this.saveModule=saveModule;
        setOrientation(VERTICAL); market=new MarketPage(this);
        LinearLayout heading=Ui.row(activity); Ui.pad(heading,8,10,8,6);
        back=new IconView(activity,IconView.BACK,Language.text(R.string.module_list),v -> back()); heading.addView(back,Ui.size(activity,44,44));
        LinearLayout labels=Ui.column(activity); Ui.pad(labels,12,0,0,0); title=Ui.bold(activity,Language.text(R.string.modules),24,Ui.INK); title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END); labels.addView(title); Ui.gap(labels,5);
        subtitle=Ui.text(activity,"",12,Ui.MUTED); subtitle.setSingleLine(true); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(subtitle); heading.addView(labels,new LayoutParams(0,-2,1));
        reload=new IconView(activity,IconView.REFRESH,Language.text(R.string.refresh_catalog),v -> market.refresh()); heading.addView(reload,Ui.size(activity,44,44));
        add=new IconView(activity,IconView.PLUS,Language.text(R.string.add_module),v -> addModule()); heading.addView(add,Ui.size(activity,44,44)); addView(heading);
        mine=Ui.button(activity,Language.text(R.string.my_modules),true,v -> open("")); catalog=Ui.button(activity,Language.text(R.string.catalog),false,v -> open(STORE));
        tabs=Ui.tabs(activity,mine,catalog); LayoutParams tl=Ui.size(activity,-1,46); tl.setMargins(Ui.dp(activity,16),0,Ui.dp(activity,16),Ui.dp(activity,6)); addView(tabs,tl);
        stage=new FrameLayout(activity); addView(stage,new LayoutParams(-1,0,1)); open("");
    }
    void active(boolean value) {
        boolean resumed=value && !active; active=value; if(monitor!=null) monitor.active(value); if(!value && leave!=null) leave.run();
        if(resumed && current.equals("mascot")) show(current);
    }
    String route() { return current; }
    boolean back() {
        if(current.isEmpty()) return false;
        show(!history.isEmpty()?history.pop():current.startsWith(ITEM) || current.equals(RULES)?STORE:""); return true;
    }
    private static boolean top(String id) { return id.isEmpty() || id.equals(STORE); }
    void open(String id) {
        // Reopening a screen that is already behind this one returns to it, so module ↔ catalogue page links do not loop.
        if(top(id)) history.clear();
        else if(history.contains(id)) { while(!history.isEmpty() && !history.pop().equals(id)) { } }
        else if(!id.equals(current)) history.push(current);
        show(id);
    }
    private void show(String id) {
        if(leave!=null) { leave.run(); leave=null; }
        if(monitor!=null) { monitor.active(false); monitor=null; }
        current=id; screenEpoch++; stage.removeAllViews();
        boolean root=top(id);
        back.setVisibility(root?GONE:VISIBLE); add.setVisibility(root?VISIBLE:GONE); reload.setVisibility(id.equals(STORE)?VISIBLE:GONE);
        tabs.setVisibility(root?VISIBLE:GONE); Ui.selected(mine,id.isEmpty()); Ui.selected(catalog,id.equals(STORE));
        ((View)title.getParent()).setPadding(Ui.dp(activity,root?12:4),0,0,0);
        if(id.isEmpty()) { heading(Language.text(R.string.modules),""); installed(); return; }
        if(id.equals(STORE)) { heading(Language.text(R.string.modules),Language.text(R.string.catalog_subtitle)); market.store(body()); return; }
        if(id.startsWith(ITEM)) { heading(Language.text(R.string.catalog),""); market.detail(body(),id.substring(ITEM.length())); return; }
        if(id.equals(RULES)) { heading(Language.text(R.string.rules_title),Language.text(R.string.catalog)); market.rules(body()); return; }
        if(!hub.enabled(id)) { hub.enabled(id,true); }
        JSONObject module=module(id);
        heading(module==null?id:module.optString("title"),module==null?Language.text(R.string.meshell):byline(module));
        switch(id) {
            case "notes": notes(); break;
            case "device": monitor=new MonitorPage(activity,hub); stage.addView(monitor); monitor.active(active); break;
            case "mascot": mascot(); break;
            case "github": github(); break;
            default: extension(id,module); break;
        }
    }
    void heading(String value,String note) { title.setText(value); subtitle.setText(note); }
    void subtitle(String note) { subtitle.setText(note); }
    private JSONObject module(String id) {
        try { JSONArray list=hub.list(); for(int i=0;i<list.length();i++) if(id.equals(list.getJSONObject(i).optString("id"))) return list.getJSONObject(i); } catch(Exception ignored) { }
        return null;
    }
    /** Who stands behind a module: MeShell, a catalogue author or the user. */
    private static String byline(JSONObject module) {
        switch(module.optString("source")) {
            case "builtin": return Language.text(R.string.meshell);
            case "catalog": return (module.optString("github").isEmpty()?module.optString("author"):"@"+module.optString("github"))+(module.optString("version").isEmpty()?"":" · v"+module.optString("version"));
            default: return Language.text(R.string.your_module);
        }
    }
    LinearLayout body() {
        ScrollView scroll=new ScrollView(activity); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=Ui.bounded(activity); Ui.pad(body,18,8,18,28); scroll.addView(body,Ui.centred(-2)); stage.addView(scroll); Ui.reveal(scroll); return body;
    }
    private TextView status(LinearLayout parent,String text) { TextView v=Ui.caption(activity,text); v.setTextSize(13); parent.addView(v); Ui.gap(parent,14); return v; }
    private Button action(LinearLayout parent,String label,boolean primary,Runnable run) {
        Button b=Ui.button(activity,label,primary,v -> run.run()); parent.addView(b,Ui.size(activity,-1,50)); Ui.gap(parent,10); return b;
    }
    private LinearLayout card(LinearLayout parent,String heading,String note,int icon,Runnable run) {
        LinearLayout card=Ui.row(activity); card.setGravity(Gravity.TOP); Ui.pad(card,14,14,14,14);
        if(run!=null) { Ui.pressable(card,Ui.PANEL,20,Ui.BORDER); card.setOnClickListener(v -> run.run()); card.setContentDescription(heading); }
        else card.setBackground(Ui.shape(activity,Ui.PANEL,20,Ui.BORDER));
        card.addView(Ui.badge(activity,icon,Ui.MINT,20),Ui.size(activity,38,38));
        LinearLayout texts=Ui.column(activity); Ui.pad(texts,12,2,0,0);
        TextView name=Ui.bold(activity,heading,15,Ui.INK); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); texts.addView(name); Ui.gap(texts,5);
        TextView text=Ui.text(activity,note,12,Ui.MUTED); text.setMaxLines(4); text.setLineSpacing(Ui.dp(activity,2),1); text.setEllipsize(android.text.TextUtils.TruncateAt.END); texts.addView(text);
        card.addView(texts,new LayoutParams(0,-2,1));
        if(run!=null) { IconView next=new IconView(activity,IconView.CHEVRON,null,null); next.color(Ui.FAINT); next.glyphSize(16); card.addView(next,Ui.size(activity,24,38)); }
        LayoutParams lp=Ui.size(activity,-1,-2); lp.bottomMargin=Ui.dp(activity,10); parent.addView(card,lp); return card;
    }
    /** Installed modules; catalogue updates and pending permission approvals show on the tile. */
    private void installed() {
        LinearLayout body=body();
        Ui.Grid grid=new Ui.Grid(activity,150,12,4); body.addView(grid,Ui.size(activity,-1,-2));
        Map<String,JSONObject> listed=new HashMap<>();
        try { JSONArray tools=hub.market.tools(); for(int i=0;i<tools.length();i++) listed.put(tools.getJSONObject(i).getString("id"),tools.getJSONObject(i)); } catch(Exception ignored) { }
        try {
            JSONArray modules=hub.list(); subtitle(Language.quantity(R.plurals.module_count,modules.length()));
            for(int i=0;i<modules.length();i++) {
                JSONObject module=modules.getJSONObject(i); String id=module.getString("id"); JSONObject tool=listed.get(id);
                LinearLayout tile=Ui.column(activity); Ui.pad(tile,14,14,14,16); Ui.pressable(tile,Ui.PANEL,20,Ui.BORDER);
                boolean enabled=module.optBoolean("enabled"),update=tool!=null && Marketplace.update(tool),waiting=module.optJSONArray("missing")!=null && module.optJSONArray("missing").length()>0;
                tile.setAlpha(enabled?1:.5f);
                tile.addView(Ui.badge(activity,MarketPage.glyph(module),module.has("category")?MarketPage.tint(module):i%3==0?Ui.LAVENDER:Ui.MINT,20),Ui.size(activity,40,40)); Ui.gap(tile,14);
                TextView name=Ui.bold(activity,module.optString("title"),15,Ui.INK); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); tile.addView(name); Ui.gap(tile,6);
                String note=!enabled?Language.text(R.string.disabled):update?Language.text(R.string.update_available):waiting?Language.text(R.string.needs_approval):byline(module);
                TextView copy=Ui.text(activity,note,12,update?Ui.MINT:waiting?Ui.WARN:Ui.MUTED); copy.setMaxLines(1);
                copy.setEllipsize(android.text.TextUtils.TruncateAt.END); tile.addView(copy);
                tile.setOnClickListener(v -> open(id)); tile.setContentDescription(Language.text(R.string.module)+module.optString("title")); tile.setOnLongClickListener(v -> { manage(module); return true; });
                grid.addView(tile);
            }
        } catch(Exception e) { status(body,e.getMessage()); }
        Ui.gap(body,16);
        card(body,Language.text(R.string.catalog),listed.isEmpty()?Language.text(R.string.catalog_invite):Language.quantity(R.plurals.module_count,listed.size())+" · "+Language.text(R.string.catalog_invite),IconView.STORE,() -> open(STORE));
    }
    private void manage(JSONObject module) {
        String id=module.optString("id"); boolean enabled=hub.enabled(id),custom=!hub.builtin(id);
        List<String> labels=new ArrayList<>(); List<Runnable> runs=new ArrayList<>();
        labels.add(enabled?Language.text(R.string.disable):Language.text(R.string.enable)); runs.add(() -> { hub.enabled(id,!enabled); show(current); });
        labels.add(Language.text(R.string.clear_module_data)); runs.add(() -> new AlertDialog.Builder(activity).setTitle(Language.text(R.string.clear_data_for)+module.optString("title")+"»?")
            .setMessage(Language.text(R.string.local_module_data_will_be_deleted))
            .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.clear),(x,y) -> { try { hub.clear(id); show(current); } catch(Exception e) { toast(e.getMessage()); } }).show());
        if(custom) {
            if("catalog".equals(module.optString("source"))) { labels.add(Language.text(R.string.catalog_page)); runs.add(() -> open(ITEM+id)); }
            else { labels.add(Language.text(R.string.publish_to_catalog)); runs.add(() -> market.publish(id)); }
            labels.add(Language.text(R.string.export_module)); runs.add(() -> exportModule(id));
            labels.add(Language.text(R.string.remove_custom_module)); runs.add(() -> { hub.remove(id); show(current); });
        }
        new AlertDialog.Builder(activity).setTitle(module.optString("title")).setItems(labels.toArray(new String[0]),(d,n) -> runs.get(n).run()).show();
    }
    /** Every way to get a module, from the catalogue first to raw JSON last. */
    private void addModule() {
        LinearLayout list=Ui.column(activity); Ui.pad(list,14,16,14,8);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(Language.text(R.string.add_module)).setView(list).setNegativeButton(Language.text(R.string.cancel),null).create();
        Object[][] rows={
            {IconView.STORE,R.string.catalog,R.string.add_from_catalog,(Runnable)() -> open(STORE)},
            {IconView.CHAT,R.string.create_with_gpt,R.string.create_with_gpt_note,(Runnable)() -> toChat.accept(Language.text(R.string.create_a_meshell_module_for_me_ask_about_its_purpose_first_t))},
            {IconView.FOLDER,R.string.import_json_file,R.string.import_json_note,pickModule},
            {IconView.COPY,R.string.paste_json,R.string.paste_json_note,(Runnable)this::pasteModule},
            {IconView.SHIELD,R.string.publish_to_catalog,R.string.publish_rules_note,(Runnable)() -> open(RULES)}
        };
        for(Object[] row:rows) {
            LinearLayout item=Ui.row(activity); Ui.pad(item,10,10,10,10); Ui.pressable(item,0x00000000,16,0);
            item.addView(Ui.badge(activity,(Integer)row[0],Ui.MINT,20),Ui.size(activity,40,40));
            LinearLayout texts=Ui.column(activity); Ui.pad(texts,12,0,0,0);
            texts.addView(Ui.bold(activity,Language.text((Integer)row[1]),15,Ui.INK)); Ui.gap(texts,3); texts.addView(Ui.text(activity,Language.text((Integer)row[2]),12,Ui.MUTED));
            item.addView(texts,new LayoutParams(0,-2,1)); Runnable run=(Runnable)row[3];
            item.setOnClickListener(v -> { dialog.dismiss(); run.run(); }); list.addView(item,Ui.size(activity,-1,-2));
        }
        dialog.show();
    }
    private void pasteModule() {
        EditText text=edit("JSON: id, title, description, permissions, actions[{id,label,command}]",true); text.setMinLines(8);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(Language.text(R.string.module_definition)).setView(text).setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.add),null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> { try { JSONObject value=hub.prepare(new JSONObject(text.getText().toString())); dialog.dismiss(); confirmModule(value); } catch(Exception e) { toast(e.getMessage()); } })); dialog.show();
    }
    void importModule(String text) { try { confirmModule(hub.prepare(new JSONObject(text))); } catch(Exception e) { toast(Language.text(R.string.module_not_added)+e.getMessage()); } }
    private ScrollView modulePreview(JSONObject value) throws Exception {
        TextView text=Ui.text(activity,value.toString(2),12,Ui.INK); text.setTypeface(android.graphics.Typeface.MONOSPACE); text.setTextIsSelectable(true); Ui.pad(text,20,12,20,12);
        ScrollView scroll=new ScrollView(activity); scroll.addView(text); return scroll;
    }
    void preview(String heading,JSONObject value) {
        try { new AlertDialog.Builder(activity).setTitle(heading).setView(modulePreview(value)).setPositiveButton(Language.text(R.string.close),null).show(); } catch(Exception e) { toast(e.getMessage()); }
    }
    /** File and pasted imports show the same permission consent as catalogue installs, with the full JSON one tap away. */
    private void confirmModule(JSONObject value) throws Exception {
        market.consent(Language.text(R.string.import_replace_module),value.optString("title")+" · "+Language.text(R.string.your_module),IconView.TERMINAL,Ui.MINT,hub.permissions(value),value.optJSONArray("packages"),
            Language.text(R.string.add),() -> { try { hub.install(value,true); open(""); toast(Language.text(R.string.module_added)); } catch(Exception e) { toast(e.getMessage()); } },
            Language.text(R.string.show_json),() -> { try { new AlertDialog.Builder(activity).setTitle(value.optString("title")).setView(modulePreview(value))
                .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.back),(d,w) -> { try { confirmModule(value); } catch(Exception e) { toast(e.getMessage()); } }).show(); } catch(Exception e) { toast(e.getMessage()); } });
    }
    private void exportModule(String id) {
        try { JSONObject value=hub.export(id);
            new AlertDialog.Builder(activity).setTitle(Language.text(R.string.export_module)).setView(modulePreview(value))
                .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.save_file),(d,w) -> saveModule.accept(value))
                .setNeutralButton(Language.text(R.string.copy_text),(d,w) -> activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("module.json",value.toString()))).show();
        } catch(Exception e) { toast(e.getMessage()); }
    }
    private EditText edit(String hint,boolean multiline) {
        return Ui.input(activity,hint,multiline);
    }
    void job(TextView status,Work work,Consumer<JSONObject> done) {
        long epoch=screenEpoch; OpenAiClient.Cancel cancel=new OpenAiClient.Cancel(); status.setText(Language.text(R.string.loading));
        io.execute(() -> {
            try { JSONObject result=work.run(cancel); main.post(() -> { if(epoch==screenEpoch) { status.setText(""); done.accept(result); } }); }
            catch(Exception e) { main.post(() -> { if(epoch==screenEpoch) status.setText(e.getMessage()); }); }
        });
    }
    private void details(String title,String value) {
        // Command output keeps its columns: monospace, no wrapping, wide lines scroll sideways.
        TextView text=Ui.text(activity,value,12,Ui.INK); text.setTypeface(android.graphics.Typeface.MONOSPACE); text.setLineSpacing(Ui.dp(activity,2),1); Ui.pad(text,22,12,22,20); text.setTextIsSelectable(true);
        android.widget.HorizontalScrollView wide=new android.widget.HorizontalScrollView(activity); wide.addView(text);
        ScrollView scroll=new ScrollView(activity); scroll.addView(wide);
        new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton(Language.text(R.string.close),null).setNeutralButton(Language.text(R.string.send_to_chat),(d,w) -> toChat.accept(value)).show();
    }
    void toast(String text) { Toast.makeText(activity,text,Toast.LENGTH_LONG).show(); }
    void browse(String url) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); } catch(ActivityNotFoundException e) { toast(url); }
    }

    private void notes() {
        LinearLayout body=body(); action(body,Language.text(R.string.new_note),true,() -> noteEditor(0,"",""));
        try { JSONArray notes=hub.notes();
            if(notes.length()==0) status(body,Language.text(R.string.no_notes));
            for(int i=0;i<notes.length();i++) { JSONObject n=notes.getJSONObject(i); card(body,n.optString("title"),n.optString("body"),IconView.NOTES,() -> noteEditor(n.optLong("id"),n.optString("title"),n.optString("body"))); }
        } catch(Exception e) { status(body,e.getMessage()); }
    }
    private void noteEditor(long initialId,String initialTitle,String initialBody) {
        if(leave!=null) leave.run(); stage.removeAllViews(); screenEpoch++;
        LinearLayout body=Ui.column(activity); Ui.pad(body,16,8,16,14); stage.addView(body);
        EditText name=edit(Language.text(R.string.title),false); name.setText(initialTitle); body.addView(name,Ui.size(activity,-1,52)); Ui.gap(body,10);
        EditText text=edit(Language.text(R.string.write_a_note),true); text.setText(initialBody); text.setGravity(Gravity.TOP); text.setTextSize(17); text.setLineSpacing(Ui.dp(activity,5),1);
        body.addView(text,new LayoutParams(-1,0,1)); Ui.gap(body,10); long[] id={initialId}; boolean[] changed={false};
        TextView state=Ui.text(activity,Language.text(R.string.saved_on_this_phone),11,Ui.MUTED);
        Runnable save=() -> { if(changed[0] && (!name.getText().toString().trim().isEmpty() || !text.getText().toString().trim().isEmpty())) {
            id[0]=hub.saveNote(id[0],name.getText().toString(),text.getText().toString()); changed[0]=false; state.setText(Language.text(R.string.saved_locally));
        } };
        Runnable schedule=() -> { changed[0]=true; state.setText(Language.text(R.string.saving)); main.removeCallbacks(save); main.postDelayed(save,450); };
        TextWatcher watcher=new TextWatcher() { public void beforeTextChanged(CharSequence s,int a,int c,int n) {} public void onTextChanged(CharSequence s,int a,int b,int c) { schedule.run(); } public void afterTextChanged(Editable e) {} };
        name.addTextChangedListener(watcher); text.addTextChangedListener(watcher);
        LinearLayout actions=Ui.row(activity); actions.addView(state,new LayoutParams(0,-2,1));
        actions.addView(new IconView(activity,IconView.CHAT,Language.text(R.string.send_note_to_chat),v -> { save.run(); toChat.accept(text.getText().toString()); }),Ui.size(activity,44,44));
        actions.addView(new IconView(activity,IconView.TRASH,Language.text(R.string.delete_note),v -> new AlertDialog.Builder(activity).setTitle(Language.text(R.string.delete_note_2))
            .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.delete_2),(d,w) -> { main.removeCallbacks(save); changed[0]=false; if(id[0]>0) hub.deleteNote(id[0]); open("notes"); }).show()),Ui.size(activity,44,44));
        body.addView(actions); leave=() -> { main.removeCallbacks(save); save.run(); };
    }
    private void mascot() {
        LinearLayout body=body(); status(body,Language.text(R.string.mascot_description));
        if(!Settings.canDrawOverlays(activity)) {
            status(body,Language.text(R.string.mascot_needs_overlay));
            action(body,Language.text(R.string.allow),true,() -> activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+activity.getPackageName()))));
            return;
        }
        boolean running=MascotService.running;
        action(body,Language.text(running?R.string.mascot_hide:R.string.mascot_show),true,() -> {
            try { hub.execute("mascot",running?"stop":"start",new JSONObject(),new OpenAiClient.Cancel()); } catch(Exception e) { toast(e.getMessage()); }
            main.postDelayed(() -> { if(current.equals("mascot")) open("mascot"); },400);
        });
    }
    private void github() {
        LinearLayout body=body();
        if(!hub.github.connected()) {
            status(body,Language.text(R.string.github_connect_hint));
            action(body,Language.text(R.string.github_create_token),false,() -> activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(GitHubModule.TOKEN_PAGE))));
            action(body,Language.text(R.string.github_add_token),true,this::githubToken);
            return;
        }
        TextView status=status(body,"");
        job(status,c -> hub.execute("github","status",new JSONObject(),c),result -> {
            String output=GitHubModule.output(result);
            try { status.setText(result.optInt("exit_code")==0?Language.text(R.string.github_signed_in_as)+new JSONObject(output).optString("login"):output); } catch(Exception e) { status.setText(output); }
        });
        action(body,Language.text(R.string.github_repositories),false,() -> githubRun(status,"repos",null,Language.text(R.string.github_repositories)));
        EditText repo=edit("owner/repo",false); repo.setText(hub.prefs.getString("github-repo","")); body.addView(repo,Ui.size(activity,-1,52)); Ui.gap(body,10);
        int[] labels={R.string.github_repository,R.string.github_issues,R.string.github_pull_requests,R.string.github_runs};
        String[] names={"repo","issues","prs","runs"};
        for(int i=0;i<names.length;i++) { String name=names[i],label=Language.text(labels[i]);
            action(body,label,false,() -> { String value=repo.getText().toString().trim(); hub.prefs.edit().putString("github-repo",value).apply(); githubRun(status,name,value,label); }); }
        action(body,Language.text(R.string.github_disconnect),false,() -> new AlertDialog.Builder(activity).setTitle(Language.text(R.string.github_disconnect)+"?")
            .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.github_disconnect),(d,w) -> { try { hub.github.disconnect(); } catch(Exception e) { toast(e.getMessage()); } open("github"); }).show());
    }
    private void githubRun(TextView status,String action,String repo,String label) {
        job(status,c -> hub.execute("github",action,repo==null?new JSONObject():new JSONObject().put("repo",repo),c),result -> details(label,GitHubModule.output(result)));
    }
    private void githubToken() {
        EditText token=edit("github_pat_…",false); token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(Language.text(R.string.github_add_token)).setView(token)
            .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.save),null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            try { hub.github.connect(token.getText().toString()); dialog.dismiss(); open("github"); } catch(Exception e) { toast(e.getMessage()); }
        })); dialog.show();
    }
    /** A custom module: pending approvals first, then its actions, its permissions and how it reached this phone. */
    private void extension(String id,JSONObject module) {
        LinearLayout body=body(); TextView status=status(body,"");
        if(module==null) { status.setText(Language.text(R.string.module_not_found)); return; }
        if(!module.optString("description").isEmpty()) status(body,module.optString("description"));
        JSONArray missing=module.optJSONArray("missing"); boolean waiting=missing!=null && missing.length()>0;
        if(waiting) {
            LinearLayout box=Ui.column(activity); box.setBackground(Ui.shape(activity,0x24efc48c,20,0)); Ui.pad(box,16,14,16,14);
            box.addView(Ui.bold(activity,Language.text(R.string.needs_approval),15,Ui.WARN)); Ui.gap(box,10);
            market.permissionRows(box,missing,module.optJSONArray("packages"));
            box.addView(Ui.button(activity,Language.text(R.string.allow),true,v -> { try { hub.grant(id); show(id); } catch(Exception e) { toast(e.getMessage()); } }),Ui.size(activity,-1,46));
            body.addView(box,Ui.size(activity,-1,-2)); Ui.gap(body,14);
        }
        try {
            JSONObject tool=hub.market.tool(id);
            if(tool!=null && Marketplace.update(tool)) { action(body,Language.text(R.string.update_to)+tool.optString("version"),true,() -> market.install(tool,status)); }
        } catch(Exception ignored) { }
        JSONArray actions=module.optJSONArray("actions");
        for(int n=0;actions!=null && n<actions.length();n++) { JSONObject action=actions.optJSONObject(n); String name=action.optString("id");
            Button run=action(body,action.optString("label",name),false,() -> job(status,c -> hub.execute(id,name,new JSONObject(),c),result -> details(action.optString("label",name),result.optString("output",result.toString()))));
            run.setEnabled(!waiting); }
        Ui.gap(body,8); body.addView(Ui.section(activity,Language.text(R.string.permissions)));
        market.permissionRows(body,module.optJSONArray("permissions"),module.optJSONArray("packages"));
        if("catalog".equals(module.optString("source"))) {
            action(body,Language.text(R.string.rate_module),false,() -> { try { JSONObject tool=hub.market.tool(id); if(tool==null) open(ITEM+id); else market.rate(tool); } catch(Exception e) { toast(e.getMessage()); } });
            action(body,Language.text(R.string.catalog_page),false,() -> open(ITEM+id));
        }
        else action(body,Language.text(R.string.publish_to_catalog),false,() -> market.publish(id));
    }
}
