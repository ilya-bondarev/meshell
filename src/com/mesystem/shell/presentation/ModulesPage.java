package com.mesystem.shell;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

final class ModulesPage extends LinearLayout {
    interface Work { JSONObject run(OpenAiClient.Cancel cancel) throws Exception; }
    private final Activity activity;
    private final ModuleHub hub;
    private final Runnable pickModule;
    private final Consumer<String> toChat;
    private final Consumer<JSONObject> saveModule;
    private final FrameLayout stage;
    private final TextView title,subtitle;
    private final IconView back,add;
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final Handler main=new Handler(Looper.getMainLooper());
    private String current="";
    private MonitorPage monitor;
    private boolean active;
    private Runnable leave;
    private long screenEpoch;

    ModulesPage(Activity activity,ModuleHub hub,Runnable pickModule,Consumer<String> toChat,Consumer<JSONObject> saveModule) {
        super(activity); this.activity=activity; this.hub=hub; this.pickModule=pickModule; this.toChat=toChat; this.saveModule=saveModule;
        setOrientation(VERTICAL);
        LinearLayout heading=Ui.row(activity); Ui.pad(heading,8,10,8,6);
        back=new IconView(activity,IconView.BACK,Language.text(R.string.module_list),v -> open("")); heading.addView(back,Ui.size(activity,44,44));
        LinearLayout labels=Ui.column(activity); Ui.pad(labels,12,0,0,0); title=Ui.bold(activity,Language.text(R.string.modules),24,Ui.INK); title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END); labels.addView(title); Ui.gap(labels,5);
        subtitle=Ui.text(activity,"",12,Ui.MUTED); subtitle.setSingleLine(true); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(subtitle); heading.addView(labels,new LayoutParams(0,-2,1));
        add=new IconView(activity,IconView.PLUS,Language.text(R.string.add_module),v -> addModule()); heading.addView(add,Ui.size(activity,44,44)); addView(heading);
        stage=new FrameLayout(activity); addView(stage,new LayoutParams(-1,0,1)); open("");
    }
    void active(boolean value) {
        boolean resumed=value && !active; active=value; if(monitor!=null) monitor.active(value); if(!value && leave!=null) leave.run();
        if(resumed && current.equals("mascot")) open(current);
    }
    boolean back() { if(current.isEmpty()) return false; open(""); return true; }
    void open(String id) {
        if(leave!=null) { leave.run(); leave=null; }
        if(monitor!=null) { monitor.active(false); monitor=null; }
        current=id; screenEpoch++; stage.removeAllViews();
        back.setVisibility(id.isEmpty()?GONE:VISIBLE); add.setVisibility(id.isEmpty()?VISIBLE:GONE);
        ((View)title.getParent()).setPadding(Ui.dp(activity,id.isEmpty()?12:4),0,0,0);
        if(id.isEmpty()) { title.setText(Language.text(R.string.modules)); subtitle.setText(""); catalog(); return; }
        if(!hub.enabled(id)) { hub.enabled(id,true); }
        String label=id; try { JSONArray list=hub.list(); for(int i=0;i<list.length();i++) if(id.equals(list.getJSONObject(i).optString("id"))) label=list.getJSONObject(i).optString("title"); } catch(Exception ignored) { }
        title.setText(label); subtitle.setText(Language.text(R.string.meshell));
        switch(id) {
            case "notes": notes(); break;
            case "device": monitor=new MonitorPage(activity,hub); stage.addView(monitor); monitor.active(active); break;
            case "mascot": mascot(); break;
            case "github": github(); break;
            default: extension(id); break;
        }
    }
    private LinearLayout body() {
        ScrollView scroll=new ScrollView(activity); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=Ui.bounded(activity); Ui.pad(body,18,8,18,28); scroll.addView(body,Ui.centred(-2)); stage.addView(scroll); Ui.reveal(scroll); return body;
    }
    private TextView status(LinearLayout parent,String text) { TextView v=Ui.caption(activity,text); v.setTextSize(13); parent.addView(v); Ui.gap(parent,14); return v; }
    private Button action(LinearLayout parent,String label,boolean primary,Runnable run) {
        Button b=Ui.button(activity,label,primary,v -> run.run()); parent.addView(b,Ui.size(activity,-1,50)); Ui.gap(parent,10); return b;
    }
    private static IconView badge(Activity activity,int icon,int color) {
        IconView glyph=new IconView(activity,icon,null,null); glyph.color(color); glyph.glyphSize(20); glyph.setMinimumWidth(0); glyph.setMinimumHeight(0);
        glyph.setBackground(Ui.shape(activity,color==Ui.LAVENDER?Ui.LAVENDER_SOFT:Ui.MINT_SOFT,12,0)); return glyph;
    }
    private LinearLayout card(LinearLayout parent,String heading,String note,int icon,Runnable run) {
        LinearLayout card=Ui.row(activity); card.setGravity(Gravity.TOP); Ui.pad(card,14,14,14,14);
        if(run!=null) { Ui.pressable(card,Ui.PANEL,20,Ui.BORDER); card.setOnClickListener(v -> run.run()); card.setContentDescription(heading); }
        else card.setBackground(Ui.shape(activity,Ui.PANEL,20,Ui.BORDER));
        card.addView(badge(activity,icon,Ui.MINT),Ui.size(activity,38,38));
        LinearLayout texts=Ui.column(activity); Ui.pad(texts,12,2,0,0);
        TextView name=Ui.bold(activity,heading,15,Ui.INK); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); texts.addView(name); Ui.gap(texts,5);
        TextView text=Ui.text(activity,note,12,Ui.MUTED); text.setMaxLines(4); text.setLineSpacing(Ui.dp(activity,2),1); text.setEllipsize(android.text.TextUtils.TruncateAt.END); texts.addView(text);
        card.addView(texts,new LayoutParams(0,-2,1));
        if(run!=null) { IconView next=new IconView(activity,IconView.CHEVRON,null,null); next.color(Ui.FAINT); next.glyphSize(16); card.addView(next,Ui.size(activity,24,38)); }
        LayoutParams lp=Ui.size(activity,-1,-2); lp.bottomMargin=Ui.dp(activity,10); parent.addView(card,lp); return card;
    }
    private void catalog() {
        LinearLayout body=body();
        Ui.Grid grid=new Ui.Grid(activity,150,12,4); body.addView(grid,Ui.size(activity,-1,-2));
        try {
            JSONArray modules=hub.list();
            for(int i=0;i<modules.length();i++) {
                JSONObject module=modules.getJSONObject(i); String id=module.getString("id");
                LinearLayout tile=Ui.column(activity); Ui.pad(tile,14,14,14,16); Ui.pressable(tile,Ui.PANEL,20,Ui.BORDER);
                boolean enabled=module.optBoolean("enabled"); tile.setAlpha(enabled?1:.5f);
                tile.addView(badge(activity,module.optInt("icon",IconView.TERMINAL),i%3==0?Ui.LAVENDER:Ui.MINT),Ui.size(activity,40,40)); Ui.gap(tile,14);
                TextView name=Ui.bold(activity,module.optString("title"),15,Ui.INK); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); tile.addView(name); Ui.gap(tile,6);
                TextView copy=Ui.text(activity,enabled?"":Language.text(R.string.disabled),12,Ui.MUTED); copy.setMaxLines(1); copy.setVisibility(enabled?GONE:VISIBLE);
                copy.setEllipsize(android.text.TextUtils.TruncateAt.END); copy.setLineSpacing(Ui.dp(activity,2),1); tile.addView(copy);
                tile.setOnClickListener(v -> open(id)); tile.setContentDescription(Language.text(R.string.module)+module.optString("title")); tile.setOnLongClickListener(v -> { manage(module); return true; });
                grid.addView(tile);
            }
        } catch(Exception e) { status(body,e.getMessage()); }
    }
    private void manage(JSONObject module) {
        String id=module.optString("id"); boolean enabled=hub.enabled(id);
        String[] choices=hub.builtin(id)?new String[]{enabled?Language.text(R.string.disable):Language.text(R.string.enable),Language.text(R.string.clear_module_data)}:
            new String[]{enabled?Language.text(R.string.disable):Language.text(R.string.enable),Language.text(R.string.clear_module_data),Language.text(R.string.remove_custom_module),Language.text(R.string.export_module)};
        new AlertDialog.Builder(activity).setTitle(module.optString("title")).setItems(choices,(d,n) -> {
            if(n==0) { hub.enabled(id,!enabled); open(""); }
            else if(n==1) new AlertDialog.Builder(activity).setTitle(Language.text(R.string.clear_data_for)+module.optString("title")+"»?")
                .setMessage(Language.text(R.string.local_module_data_will_be_deleted))
                .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.clear),(x,y) -> { try { hub.clear(id); open(""); } catch(Exception e) { toast(e.getMessage()); } }).show();
            else if(n==2) { hub.remove(id); open(""); }
            else exportModule(id);
        }).show();
    }
    private void addModule() {
        new AlertDialog.Builder(activity).setTitle(Language.text(R.string.add_module)).setItems(new String[]{Language.text(R.string.import_json_file),Language.text(R.string.paste_json),Language.text(R.string.ask_gpt_to_create_a_module)},(d,n) -> {
            if(n==0) pickModule.run();
            else if(n==2) toChat.accept(Language.text(R.string.create_a_meshell_module_for_me_ask_about_its_purpose_first_t));
            else {
                EditText text=edit("JSON: id, title, description, actions[{id,label,command}]",true); text.setMinLines(8);
                AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(Language.text(R.string.module_definition)).setView(text).setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.add),null).create();
                dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> { try { JSONObject value=hub.prepare(new JSONObject(text.getText().toString())); dialog.dismiss(); confirmModule(value); } catch(Exception e) { toast(e.getMessage()); } })); dialog.show();
            }
        }).show();
    }
    void importModule(String text) { try { confirmModule(hub.prepare(new JSONObject(text))); } catch(Exception e) { toast(Language.text(R.string.module_not_added)+e.getMessage()); } }
    private ScrollView modulePreview(JSONObject value) throws Exception {
        TextView text=Ui.text(activity,value.toString(2),12,Ui.INK); text.setTypeface(android.graphics.Typeface.MONOSPACE); text.setTextIsSelectable(true); Ui.pad(text,20,12,20,12);
        ScrollView scroll=new ScrollView(activity); scroll.addView(text); return scroll;
    }
    private void confirmModule(JSONObject value) throws Exception {
        new AlertDialog.Builder(activity).setTitle(Language.text(R.string.import_replace_module)).setView(modulePreview(value))
            .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.add),(d,w) -> {
                try { hub.install(value); open(""); toast(Language.text(R.string.module_added)); } catch(Exception e) { toast(e.getMessage()); }
            }).show();
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
    private void job(TextView status,Work work,Consumer<JSONObject> done) {
        long epoch=screenEpoch; OpenAiClient.Cancel cancel=new OpenAiClient.Cancel(); status.setText(Language.text(R.string.loading));
        io.execute(() -> {
            try { JSONObject result=work.run(cancel); main.post(() -> { if(epoch==screenEpoch) { status.setText(""); done.accept(result); } }); }
            catch(Exception e) { main.post(() -> { if(epoch==screenEpoch) status.setText(e.getMessage()); }); }
        });
    }
    private void details(String title,String value) {
        TextView text=Ui.text(activity,value,13,Ui.INK); text.setLineSpacing(Ui.dp(activity,3),1); Ui.pad(text,22,12,22,20); text.setTextIsSelectable(true); ScrollView scroll=new ScrollView(activity); scroll.addView(text);
        new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton(Language.text(R.string.close),null).setNeutralButton(Language.text(R.string.send_to_chat),(d,w) -> toChat.accept(value)).show();
    }
    private void toast(String text) { Toast.makeText(activity,text,Toast.LENGTH_LONG).show(); }

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
            String output=githubOutput(result);
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
        job(status,c -> hub.execute("github",action,repo==null?new JSONObject():new JSONObject().put("repo",repo),c),result -> details(label,githubOutput(result)));
    }
    /** gh prints the raw API body before its own one-line error; keep only that line on failure. */
    private static String githubOutput(JSONObject result) {
        String output=result.optString("output",result.toString()).trim();
        if(result.optInt("exit_code")==0) return output;
        int at=output.lastIndexOf("gh: "); return at>=0?output.substring(at+4).trim():output;
    }
    private void githubToken() {
        EditText token=edit("github_pat_…",false); token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(Language.text(R.string.github_add_token)).setView(token)
            .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.save),null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            try { hub.github.connect(token.getText().toString()); dialog.dismiss(); open("github"); } catch(Exception e) { toast(e.getMessage()); }
        })); dialog.show();
    }
    private void extension(String id) {
        LinearLayout body=body(); TextView status=status(body,"");
        try { JSONArray modules=hub.extensions(); for(int i=0;i<modules.length();i++) { JSONObject module=modules.getJSONObject(i); if(!id.equals(module.optString("id"))) continue;
            status(body,module.optString("description")); JSONArray actions=module.getJSONArray("actions");
            for(int n=0;n<actions.length();n++) { JSONObject action=actions.getJSONObject(n); String name=action.getString("id");
                action(body,action.optString("label",name),false,() -> job(status,c -> hub.execute(id,name,new JSONObject(),c),result -> details(action.optString("label",name),result.optString("output",result.toString())))); }
        } } catch(Exception e) { status.setText(e.getMessage()); }
    }
}
