package com.mesystem.shell;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class SettingsPage extends ScrollView {
    private final ChatEngine chat;
    private final TextView accountState,modelInfo,usage,limits,secretStatus,scope,packagesState;
    private long configuredThread=-1;
    private final LinearLayout secretRows;
    private final EditText contextSize;
    private final CheckBox agent;
    private final Button model,reset,codex;
    private int secretRevision=-1;
    SettingsPage(Context context,ChatEngine chat,Runnable account,Runnable back,Runnable importFile) {
        super(context); this.chat=chat; setFillViewport(true); setVerticalScrollBarEnabled(false);
        LinearLayout body=Ui.bounded(context); Ui.pad(body,20,6,20,28); addView(body,Ui.centred(-2));
        LinearLayout header=Ui.row(context);
        header.addView(new IconView(context,IconView.BACK,Language.text(R.string.back),v -> back.run()),Ui.size(context,44,44));
        header.addView(Ui.bold(context,Language.text(R.string.settings),24,Ui.INK)); body.addView(header); Ui.gap(body,14);

        body.addView(Ui.button(context,Language.text(R.string.language),false,v ->
            new AlertDialog.Builder(context).setTitle(Language.text(R.string.language)).setItems(context.getResources().getStringArray(R.array.languages),(d,index) -> {
                Language.select(context,index==0?"en":"ru"); chat.refreshLanguage(); ((android.app.Activity)context).recreate();
            }).show()),Ui.size(context,-1,48)); Ui.gap(body,22);
        body.addView(Ui.section(context,Language.text(R.string.account)));
        LinearLayout accountRow=Ui.row(context); Ui.pad(accountRow,14,12,8,12); Ui.pressable(accountRow,Ui.PANEL,20,Ui.BORDER);
        IconView fox=new IconView(context,IconView.FOX,null,null); fox.color(Ui.MINT); accountRow.addView(fox,Ui.size(context,28,40));
        LinearLayout names=Ui.column(context); Ui.pad(names,12,0,0,0); names.addView(Ui.bold(context,Language.text(R.string.chatgpt_account),15,Ui.INK)); Ui.gap(names,4);
        accountState=Ui.text(context,"",12,Ui.MUTED); accountState.setSingleLine(true); accountState.setEllipsize(TextUtils.TruncateAt.END); names.addView(accountState);
        accountRow.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        IconView next=new IconView(context,IconView.CHEVRON,null,null); next.color(Ui.FAINT); next.glyphSize(18); accountRow.addView(next,Ui.size(context,32,40));
        accountRow.setOnClickListener(v -> account.run()); accountRow.setContentDescription(Language.text(R.string.chatgpt_account)); body.addView(accountRow); Ui.gap(body,8);
        codex=Ui.button(context,"",false,v -> {
            v.setEnabled(false); toast(Language.text(R.string.checking_codex));
            chat.updateCodex(result -> { v.setEnabled(true); toast(result); refresh(); });
        }); body.addView(codex,Ui.size(context,-1,46)); Ui.gap(body,22);

        body.addView(Ui.section(context,Language.text(R.string.packages)));
        LinearLayout tools=group(body);
        packagesState=Ui.text(context,Language.text(R.string.packages_checking),12,Ui.MUTED); packagesState.setLineSpacing(Ui.dp(context,3),1); tools.addView(packagesState); Ui.gap(tools,12);
        tools.addView(Ui.button(context,Language.text(R.string.install_packages),false,v -> choosePackages()),Ui.size(context,-1,46)); Ui.gap(body,22);
        loadPackages();

        body.addView(Ui.section(context,Language.text(R.string.this_chat_2)));
        LinearLayout thread=group(body);
        scope=Ui.bold(context,"",14,Ui.MINT); scope.setMaxLines(2); scope.setEllipsize(TextUtils.TruncateAt.END); thread.addView(scope); Ui.gap(thread,6);
        Ui.gap(thread,16); thread.addView(label(Language.text(R.string.model_and_effort)));
        model=Ui.button(context,"",false,v -> chooseModel()); thread.addView(model,Ui.size(context,-1,48)); Ui.gap(thread,6);
        modelInfo=Ui.caption(context,""); thread.addView(modelInfo); Ui.gap(thread,16);
        thread.addView(label(Language.text(R.string.input_context_budget_estimated_tokens)));
        contextSize=Ui.input(context,"",false); contextSize.setInputType(InputType.TYPE_CLASS_NUMBER); contextSize.setText(Integer.toString(chat.contextTokens));
        thread.addView(contextSize,Ui.size(context,-1,48)); Ui.gap(thread,10);
        agent=new CheckBox(context); agent.setText(Language.text(R.string.agent_tools)); agent.setTextColor(Ui.INK); agent.setTextSize(14);
        agent.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.MINT)); agent.setChecked(chat.agentEnabled); thread.addView(agent); Ui.gap(thread,10);
        thread.addView(Ui.button(context,Language.text(R.string.save_chat_settings),true,v -> {
            try { chat.configure(Integer.parseInt(contextSize.getText().toString()),agent.isChecked());
                contextSize.setText(Integer.toString(chat.contextTokens)); toast(Language.text(R.string.settings_saved)); }
            catch(Exception e) { toast(Language.text(R.string.enter_a_context_number)); }
        }),Ui.size(context,-1,48)); Ui.gap(thread,12);
        usage=Ui.caption(context,""); thread.addView(usage); Ui.gap(body,22);

        body.addView(Ui.section(context,Language.text(R.string.background)));
        LinearLayout background=group(body);
        background.addView(Ui.button(context,Language.text(R.string.android_app_settings),false,v -> {
            try { context.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+context.getPackageName()))); }
            catch(Exception e) { toast(Language.text(R.string.open_meshell_battery_settings_manually)); }
        }),Ui.size(context,-1,48)); Ui.gap(body,22);

        body.addView(Ui.section(context,Language.text(R.string.subscription_limits)));
        LinearLayout quota=group(body);
        limits=Ui.text(context,"",13,Ui.INK); limits.setLineSpacing(Ui.dp(context,4),1); limits.setTextIsSelectable(true); quota.addView(limits); Ui.gap(quota,14);
        LinearLayout actions=Ui.row(context);
        actions.addView(Ui.button(context,Language.text(R.string.refresh),false,v -> chat.refreshLimits()),new LinearLayout.LayoutParams(0,Ui.dp(context,46),1));
        reset=Ui.button(context,Language.text(R.string.reset_limit),false,v -> redeem()); reset.setTextSize(14); reset.setSingleLine(true);
        reset.setEllipsize(android.text.TextUtils.TruncateAt.END); reset.setPadding(Ui.dp(context,10),0,Ui.dp(context,10),0);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,Ui.dp(context,46),1); rp.leftMargin=Ui.dp(context,8); actions.addView(reset,rp);
        quota.addView(actions); Ui.gap(body,22);

        body.addView(Ui.section(context,Language.text(R.string.secrets_and_connections)));
        LinearLayout secrets=group(body);
        secretStatus=Ui.text(context,"",12,Ui.DANGER); secrets.addView(secretStatus);
        secretRows=Ui.column(context); secrets.addView(secretRows); Ui.gap(secrets,12);
        secrets.addView(Ui.button(context,Language.text(R.string.add_secret),false,v -> editSecret(null,null,null)),Ui.size(context,-1,46)); Ui.gap(secrets,8);
        secrets.addView(Ui.button(context,Language.text(R.string.import_key_file),false,v -> importFile.run()),Ui.size(context,-1,46)); Ui.gap(secrets,8);
        secrets.addView(Ui.button(context,Language.text(R.string.generate_random_secret),false,v -> {
            EditText name=entry(Language.text(R.string.secret_name),false);
            new AlertDialog.Builder(context).setTitle(Language.text(R.string.new_random_secret)).setView(padded(name)).setNegativeButton(Language.text(R.string.cancel),null)
                .setPositiveButton(Language.text(R.string.create),(d,w) -> { try { chat.tools.vault.generate(name.getText().toString().trim(),32); refreshSecrets(); toast(Language.text(R.string.secret_created)); }
                    catch(Exception e) { toast(e.getMessage()); } }).show();
        }),Ui.size(context,-1,46)); Ui.gap(body,22);

        body.addView(Ui.section(context,Language.text(R.string.device_and_source)));
        LinearLayout device=group(body);
        TextView paths=Ui.text(context,Language.text(R.string.source)+ShellBroker.SOURCE+Language.text(R.string.compilers)+ShellBroker.TOOLS+Language.text(R.string.build)+ShellBroker.HOME+"/build/MeShell.apk",11,Ui.MUTED);
        paths.setTypeface(android.graphics.Typeface.MONOSPACE); paths.setLineSpacing(Ui.dp(context,3),1); paths.setTextIsSelectable(true); device.addView(paths); Ui.gap(device,14);
        device.addView(Ui.button(context,Language.text(R.string.check_environment_and_usb),false,v -> local("environment_info")),Ui.size(context,-1,46)); Ui.gap(device,8);
        device.addView(Ui.button(context,Language.text(R.string.build_meshell_on_phone),false,v -> local("build_self")),Ui.size(context,-1,46)); Ui.gap(device,8);
        device.addView(Ui.button(context,Language.text(R.string.install_built_meshell),false,v -> local("install_self")),Ui.size(context,-1,46));
        refresh();
    }
    private LinearLayout group(LinearLayout body) { LinearLayout card=Ui.card(getContext()); body.addView(card); return card; }
    private TextView label(String text) { TextView v=Ui.bold(getContext(),text,12,Ui.MUTED); Ui.pad(v,2,0,0,8); return v; }
    private LinearLayout padded(android.view.View view) { LinearLayout box=Ui.column(getContext()); Ui.pad(box,22,10,22,4); box.addView(view); return box; }
    private EditText entry(String hint,boolean secret) {
        EditText input=new EditText(getContext()); input.setHint(hint); input.setHintTextColor(Ui.FAINT); input.setTextColor(Ui.INK); input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));
        Ui.cursor(input); return input;
    }
    private void toast(String value) { Toast.makeText(getContext(),value,Toast.LENGTH_LONG).show(); }
    private void chooseModel() {
        List<String> models=chat.modelChoices();
        new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.model)).setItems(models.toArray(new String[0]),(d,index) -> {
            String selected=models.get(index); List<String> levels=chat.effortsFor(selected);
            new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.reasoning_effort)+" · "+selected).setItems(levels.toArray(new String[0]),(x,y) -> {
                chat.selectModel(selected,levels.get(y)); refresh();
            }).show();
        }).setNeutralButton(Language.text(R.string.refresh_list),(d,w) -> {
            toast(Language.text(R.string.loading_model_catalog));
            chat.reloadModels(failure -> { if(!failure.isEmpty()) toast(failure); chooseModel(); });
        }).show();
    }
    private String window(String label,JSONObject window) {
        if(window==null) return "";
        double used=window.optDouble("used_percent",Double.NaN);
        long reset=window.optLong("reset_at");
        return label+": "+(Double.isNaN(used)?Language.text(R.string.percentage_unavailable):String.format(Locale.ROOT,Language.text(R.string.format_remaining),services.quota.QuotaRules.remaining(used)))+
            (reset>0?Language.text(R.string.reset)+new SimpleDateFormat("dd.MM HH:mm",Language.locale()).format(new Date(reset*1000)):"")+"\n";
    }
    private String quota(JSONObject data) {
        JSONObject limit=data.optJSONObject("rate_limit"); StringBuilder text=new StringBuilder();
        if(limit!=null) {
            text.append(window(Language.text(R.string.primary_window),limit.optJSONObject("primary_window")));
            text.append(window(Language.text(R.string.secondary_window),limit.optJSONObject("secondary_window")));
        }
        JSONArray additional=data.optJSONArray("additional_rate_limits");
        if(additional!=null) for(int i=0;i<additional.length();i++) {
            JSONObject item=additional.optJSONObject(i); if(item==null) continue; JSONObject windows=item.optJSONObject("rate_limit");
            if(windows!=null) text.append(window(item.optString("limit_name",Language.text(R.string.additional_window)),windows.optJSONObject("primary_window")));
        }
        JSONObject credits=data.optJSONObject("credits");
        if(credits!=null && credits.has("balance")) text.append(Language.text(R.string.credits)).append(credits.optString("balance")).append('\n');
        if(text.length()==0) text.append(chat.authenticated?Language.text(R.string.no_limit_data):Language.text(R.string.chatgpt_not_connected));
        return text.toString();
    }
    private void redeem() {
        JSONArray entries=chat.resetCredits.optJSONArray("credits");
        if(entries==null) entries=chat.resetCredits.optJSONArray("items");
        final JSONArray list=entries;
        if(list!=null && list.length()>0) {
            String[] labels=new String[list.length()];
            for(int i=0;i<labels.length;i++) {
                JSONObject item=list.optJSONObject(i); String expiry=item==null?"":item.optString("expires_at","");
                try { expiry=new SimpleDateFormat("dd.MM HH:mm",Language.locale()).format(Date.from(java.time.Instant.parse(expiry))); } catch(Exception ignored) { }
                labels[i]=Language.text(R.string.reset_2)+(i+1)+(expiry.isEmpty()?"":Language.text(R.string.until)+expiry);
            }
            new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.available_resets)).setItems(labels,(d,index) -> {
                JSONObject entry=list.optJSONObject(index); confirmReset(entry==null?null:entry.optString("credit_id",entry.optString("id")));
            }).show();
        } else confirmReset(null);
    }
    private void confirmReset(String id) {
        new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.use_one_reset))
            .setMessage(Language.text(R.string.one_quota_reset_will_be_used))
            .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.use),(d,w) -> chat.redeemCredit(id)).show();
    }
    void imported(String name,String value) { editSecret(name,null,value); }
    private void editSecret(String existing,String existingKind,String imported) {
        Context c=getContext(); LinearLayout form=Ui.column(c); Ui.pad(form,22,8,22,8);
        EditText name=entry(Language.text(R.string.secret_name),false); if(existing!=null) name.setText(existing); form.addView(name);
        String[] kinds={"secret","json"}; Spinner type=new Spinner(c);
        type.setAdapter(new ArrayAdapter<>(c,android.R.layout.simple_spinner_dropdown_item,kinds));
        for(int i=0;i<kinds.length;i++) if(kinds[i].equals(existingKind)) type.setSelection(i); form.addView(type);
        EditText value=entry(Language.text(R.string.new_value_or_json),true); value.setMinLines(4); value.setMaxLines(9);
        if(imported!=null) value.setText(imported); form.addView(value);
        AlertDialog dialog=new AlertDialog.Builder(c).setTitle(existingKind==null?Language.text(R.string.add_secret):Language.text(R.string.replace_secret))
            .setView(form).setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.save),null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String key=name.getText().toString().trim(),kind=kinds[type.getSelectedItemPosition()],text=value.getText().toString();
                if(text.isEmpty()) throw new IllegalArgumentException(Language.text(R.string.enter_a_value));
                Object data=kind.equals("secret")?text:new JSONObject(text);
                chat.tools.vault.put(key,kind,data); refreshSecrets(); dialog.dismiss();
            } catch(Exception e) { toast(Language.text(R.string.not_saved)+e.getMessage()); }
        })); dialog.show();
    }
    private void refreshSecrets() {
        try {
            JSONArray names=chat.tools.vault.list(); secretRows.removeAllViews(); secretRevision=chat.tools.vault.revision;
            if(names.length()>0) Ui.gap(secretRows,10);
            for(int i=0;i<names.length();i++) {
                JSONObject item=names.getJSONObject(i); String name=item.getString("name"),kind=item.getString("kind");
                LinearLayout row=Ui.row(getContext()); Ui.pad(row,12,0,8,0); Ui.pressable(row,Ui.PANEL2,14,0);
                IconView key=new IconView(getContext(),IconView.KEY,null,null); key.color(Ui.LAVENDER); key.glyphSize(18); row.addView(key,Ui.size(getContext(),24,44));
                TextView title=Ui.text(getContext(),name,13,Ui.INK); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.MIDDLE); Ui.pad(title,10,0,8,0);
                row.addView(title,new LinearLayout.LayoutParams(0,-2,1)); row.addView(Ui.chip(getContext(),kind,Ui.MUTED,Ui.PANEL3));
                row.setContentDescription(Language.text(R.string.secret)+name+" · "+kind);
                row.setOnClickListener(v -> new AlertDialog.Builder(getContext()).setTitle(name)
                    .setItems(new String[]{Language.text(R.string.replace_value),Language.text(R.string.delete_2)},(d,which) -> {
                        if(which==0) editSecret(name,kind,null);
                        else new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.delete_3)+name+"?").setNegativeButton(Language.text(R.string.cancel),null)
                            .setPositiveButton(Language.text(R.string.delete_2),(x,y) -> { try { chat.tools.vault.remove(name); refreshSecrets(); } catch(Exception e) { toast(e.getMessage()); } }).show();
                    }).show());
                LinearLayout.LayoutParams lp=Ui.size(getContext(),-1,48); lp.bottomMargin=Ui.dp(getContext(),6); secretRows.addView(row,lp);
            }
        } catch(Exception e) { secretStatus.setText(Language.text(R.string.vault_error)+e.getMessage()); }
    }
    private void loadPackages() {
        Context c=getContext();
        new Thread(() -> {
            String text;
            try {
                JSONObject installed=new Packages(c).installed(new OpenAiClient.Cancel()); String codex=Packages.version(installed,"codex");
                text=installed.length()==0?Language.text(R.string.packages_missing)
                    :String.format(Language.locale(),Language.text(R.string.packages_state),codex.isEmpty()?"—":codex,installed.length());
            } catch(Exception e) { text=Language.text(R.string.packages_unavailable); }
            String result=text; post(() -> packagesState.setText(result));
        },"meshell-packages-state").start();
    }
    private void choosePackages() {
        Context c=getContext(); LinearLayout form=Ui.column(c); Ui.pad(form,22,10,22,4);
        TextView about=Ui.text(c,Language.text(R.string.packages_source),13,Ui.MUTED); about.setLineSpacing(Ui.dp(c,3),1); form.addView(about); Ui.gap(form,10);
        CheckBox build=new CheckBox(c); build.setText(Language.text(R.string.include_build_tools)); build.setTextColor(Ui.INK); build.setTextSize(14);
        build.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.MINT)); form.addView(build);
        new AlertDialog.Builder(c).setTitle(Language.text(R.string.packages)).setView(form).setNegativeButton(Language.text(R.string.cancel),null)
            .setPositiveButton(Language.text(R.string.install),(d,w) -> installPackages(build.isChecked())).show();
    }
    private void installPackages(boolean build) {
        OpenAiClient.Cancel cancel=new OpenAiClient.Cancel(); TextView output=Ui.text(getContext(),Language.text(R.string.reading_package_index),13,Ui.INK);
        Ui.pad(output,22,12,22,12); output.setTextIsSelectable(true);
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.packages)).setView(output)
            .setNegativeButton(Language.text(R.string.stop),(d,w) -> cancel.stop()).create();
        dialog.setOnCancelListener(d -> cancel.stop()); dialog.show();
        List<String> names=new java.util.ArrayList<>(java.util.Arrays.asList(Packages.CORE));
        if(build) names.addAll(java.util.Arrays.asList(Packages.BUILD));
        new Thread(() -> {
            String result;
            try {
                JSONObject value=new Packages(getContext()).install(names,true,text -> post(() -> output.setText(text)),cancel);
                result=value.optBoolean("up_to_date")?Language.text(R.string.packages_up_to_date)
                    :String.format(Language.locale(),Language.text(R.string.packages_changed),value.getJSONArray("changed").length())+"\n\n"+Language.text(R.string.codex_cli_ready);
            } catch(Exception e) { result=cancel.stopped?Language.text(R.string.stopped):Language.text(R.string.package_installation_failed)+Packages.message(e); }
            String finalResult=result;
            post(() -> {
                loadPackages();
                if(dialog.isShowing()) { output.setText(finalResult); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(Language.text(R.string.close)); }
            });
        },"meshell-packages").start();
    }
    private void local(String name) {
        if((chat.busy || chat.loggingIn) && !"environment_info".equals(name)) { toast(Language.text(R.string.finish_or_stop_tasks_before_building_installing)); return; }
        OpenAiClient.Cancel cancel=new OpenAiClient.Cancel(); TextView output=Ui.text(getContext(),Language.text(R.string.running_2)+name,12,Ui.INK);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        Ui.pad(output,22,12,22,12); output.setTextIsSelectable(true); ScrollView scroll=new ScrollView(getContext()); scroll.addView(output);
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(name).setView(scroll).setNegativeButton(Language.text(R.string.stop),(d,w) -> cancel.stop()).create();
        dialog.setOnCancelListener(d -> cancel.stop()); dialog.show();
        new Thread(() -> {
            String result;
            try { JSONObject value=chat.tools.execute(name,new JSONObject(),cancel); value.remove("_image");
                result=value.has("output")?"Exit: "+value.optInt("exit_code")+"\n"+value.optString("output"):value.toString(2); }
            catch(Exception e) { result=cancel.stopped?Language.text(R.string.stopped):e.toString(); }
            String finalResult=result;
            post(() -> { if(dialog.isShowing()) { output.setText(finalResult); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(Language.text(R.string.close)); } });
        },"meshell-local-tool").start();
    }
    void refresh() {
        if(chat.ready && configuredThread!=chat.threadId()) {
            configuredThread=chat.threadId(); contextSize.setText(Integer.toString(chat.contextTokens)); agent.setChecked(chat.agentEnabled);
        }
        accountState.setText(chat.authenticated?chat.authLabel:Language.text(R.string.not_connected));
        codex.setText(String.format(Language.locale(),Language.text(R.string.update_codex),chat.codexVersion()));
        scope.setText(chat.threadTitle+" · "+chat.configuration().name);
        model.setText(chat.model+" / "+chat.effort+"  ⌄");
        int max=chat.modelInfo(chat.model).optInt("context_window"); modelInfo.setText(max>0?Language.text(R.string.model_context_limit)+max+Language.text(R.string.tokens):Language.text(R.string.model_context_limit_unavailable));
        usage.setText(Language.text(R.string.context_2)+chat.contextEstimate+Language.text(R.string.input)+chat.lastInputTokens);
        limits.setText(quota(chat.limits)+Language.text(R.string.available_resets_2)+chat.availableResets()+
            (chat.limitsUpdated>0?Language.text(R.string.updated)+new SimpleDateFormat("HH:mm:ss",Language.locale()).format(new Date(chat.limitsUpdated)):"")+
            (chat.limitsError.isEmpty()?"":"\n"+chat.limitsError));
        reset.setEnabled(chat.canResetLimits()); reset.setAlpha(reset.isEnabled()?1:.45f);
        if(secretRevision!=chat.tools.vault.revision) refreshSecrets();
    }
}
