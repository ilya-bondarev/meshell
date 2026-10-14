package com.mesystem.shell;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;

final class AgentDialogs {
    static void toast(Context c,String text) { Toast.makeText(c,text,Toast.LENGTH_LONG).show(); }
    static EditText entry(Context c,String value,String hint,int type) {
        EditText e=new EditText(c); e.setTextColor(Ui.INK); e.setHintTextColor(Ui.MUTED); e.setTextSize(14);
        e.setInputType(type); e.setText(value); e.setHint(hint); return e;
    }
    static void edit(Context c,ChatEngine chat,String title,AgentConfig value,Consumer<AgentConfig> save) {
        LinearLayout form=Ui.column(c); Ui.pad(form,20,12,20,18);
        form.addView(Ui.text(c,Language.text(R.string.agent_name),12,Ui.MUTED));
        EditText name=entry(c,value.name,Language.text(R.string.name),InputType.TYPE_CLASS_TEXT); form.addView(name);
        form.addView(Ui.text(c,Language.text(R.string.model),12,Ui.MUTED)); Spinner model=new Spinner(c);
        List<String> models=new ArrayList<>(chat.modelChoices()); if(!models.contains(value.model)) models.add(value.model);
        model.setAdapter(new ArrayAdapter<>(c,android.R.layout.simple_spinner_dropdown_item,models.toArray(new String[0])));
        model.setSelection(models.indexOf(value.model)); form.addView(model);
        form.addView(Ui.text(c,Language.text(R.string.reasoning_effort),12,Ui.MUTED)); Spinner effort=new Spinner(c); form.addView(effort);
        Runnable levels=() -> { String selected=models.get(model.getSelectedItemPosition()); List<String> list=chat.effortsFor(selected);
            if(selected.equals(value.model) && !list.contains(value.effort)) list.add(value.effort);
            effort.setAdapter(new ArrayAdapter<>(c,android.R.layout.simple_spinner_dropdown_item,list));
            effort.setSelection(Math.max(0,list.indexOf(value.effort))); };
        model.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p,View v,int n,long id) { levels.run(); }
            public void onNothingSelected(AdapterView<?> p) { }
        }); levels.run();
        Ui.gap(form,10); form.addView(Ui.text(c,Language.text(R.string.context_budget_estimated_tokens),12,Ui.MUTED));
        EditText budget=entry(c,Integer.toString(value.context),"64000",InputType.TYPE_CLASS_NUMBER); form.addView(budget);
        CheckBox tools=new CheckBox(c); tools.setText(Language.text(R.string.phone_tools)); tools.setTextColor(Ui.INK); tools.setTextSize(13); tools.setChecked(value.tools); form.addView(tools);
        Ui.gap(form,8); form.addView(Ui.text(c,Language.text(R.string.agent_instructions),12,Ui.MUTED));
        EditText instructions=entry(c,value.instructions,Language.text(R.string.instructions),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        instructions.setMinLines(3); instructions.setMaxLines(6); form.addView(instructions);
        ScrollView scroll=new ScrollView(c); scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(c).setTitle(title).setView(scroll).setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.save),null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                if(name.getText().toString().trim().isEmpty()) throw new IllegalArgumentException(Language.text(R.string.enter_an_agent_name));
                int tokens=Integer.parseInt(budget.getText().toString()); String selected=models.get(model.getSelectedItemPosition());
                int maximum=chat.modelInfo(selected).optInt("context_window",262144);
                if(tokens<4096 || tokens>maximum) throw new IllegalArgumentException(Language.text(R.string.context_4096)+maximum+Language.text(R.string.tokens));
                AgentConfig config=new AgentConfig(name.getText().toString(),selected,effort.getSelectedItem().toString(),tokens,tools.isChecked(),instructions.getText().toString());
                save.accept(config); dialog.dismiss();
            } catch(Exception e) { toast(c,e.getMessage()==null?Language.text(R.string.cannot_save):e.getMessage()); }
        })); dialog.show();
    }
    static void choose(Context c,ChatEngine chat,Consumer<ChatStore.Profile> selected) {
        List<ChatStore.Profile> profiles=chat.profiles();
        if(profiles.isEmpty()) { toast(c,Language.text(R.string.no_agent_profiles)); return; }
        String[] names=new String[profiles.size()]; for(int i=0;i<names.length;i++) {
            AgentConfig a=profiles.get(i).config; names[i]=a.name+" · "+(a.tools?Language.text(R.string.tools):Language.text(R.string.chat_only_2));
        }
        new AlertDialog.Builder(c).setTitle(Language.text(R.string.agent_profile)).setItems(names,(d,n) -> selected.accept(profiles.get(n)))
            .setNegativeButton(Language.text(R.string.cancel),null).show();
    }
    static void tasks(Context c,ChatEngine chat,Runnable opened) {
        List<AgentTask> tasks=chat.activeTasks(); if(tasks.isEmpty()) return;
        String[] labels=new String[tasks.size()]; for(int i=0;i<labels.length;i++) labels[i]=tasks.get(i).label()+" · "+tasks.get(i).phase;
        new AlertDialog.Builder(c).setTitle(Language.text(R.string.active_tasks)+tasks.size()).setItems(labels,(d,n) -> {
            AgentTask task=tasks.get(n);
            new AlertDialog.Builder(c).setTitle(task.label()).setMessage(task.phase)
                .setPositiveButton(Language.text(R.string.open_chat),(x,w) -> chat.selectThread(task.thread,opened))
                .setNeutralButton(Language.text(R.string.stop_task),(x,w) -> chat.cancel(task.thread,task.notificationId)).setNegativeButton(Language.text(R.string.close),null).show();
        }).setNegativeButton(Language.text(R.string.close),null).setNeutralButton(Language.text(R.string.stop_all),(d,w) ->
            new AlertDialog.Builder(c).setTitle(Language.text(R.string.stop_all_tasks)).setMessage(Language.text(R.string.commands_may_have_run_stopping_does_not_undo_changes))
                .setPositiveButton(Language.text(R.string.stop),(x,n) -> chat.cancelAll()).setNegativeButton(Language.text(R.string.cancel),null).show()).show();
    }
    static void chat(Context c,ChatEngine chat) {
        if(!chat.ready) return; final long thread=chat.threadId(); final AgentConfig current=chat.configuration();
        new AlertDialog.Builder(c).setTitle(current.name+Language.text(R.string.this_chat))
            .setItems(new String[]{Language.text(R.string.select_agent_profile),Language.text(R.string.edit_chat_settings),Language.text(R.string.save_as_new_profile),Language.text(R.string.compact_context),"AutoCompact: "+(chat.autoCompact(thread)?Language.text(R.string.on):Language.text(R.string.off)),Language.text(R.string.context_summary_reset)},(d,n) -> {
                if(n==0) choose(c,chat,p -> { chat.setConfiguration(thread,p.config,p.id); toast(c,Language.text(R.string.profile_applied_to_this_chat)); });
                else if(n==1) edit(c,chat,Language.text(R.string.chat_settings),current,a -> chat.setConfiguration(thread,a,""));
                else if(n==2) edit(c,chat,Language.text(R.string.new_profile),current,a -> { chat.saveProfile(UUID.randomUUID().toString(),a); toast(c,Language.text(R.string.profile_saved)); });
                else if(n==3) new AlertDialog.Builder(c).setTitle(Language.text(R.string.compact)).setMessage(Language.text(R.string.compact_old_messages_history_is_kept_uses_chatgpt_quota))
                    .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.compact),(x,w) -> chat.compact(thread,text -> toast(c,text))).show();
                else if(n==4) { chat.autoCompact(thread,!chat.autoCompact(thread)); toast(c,"AutoCompact "+(chat.autoCompact(thread)?Language.text(R.string.on_at_80_of_budget_uses_chatgpt_quota):Language.text(R.string.off))); }
                else new AlertDialog.Builder(c).setTitle(Language.text(R.string.chat_context)).setMessage(chat.compactStatus(thread))
                    .setNegativeButton(Language.text(R.string.close),null).setPositiveButton(Language.text(R.string.reset_summary),(x,w) -> { if(chat.busyHere()) toast(c,Language.text(R.string.stop_this_chat_s_task_first)); else { chat.resetCompact(thread); toast(c,Language.text(R.string.next_request_uses_original_history)); } }).show();
            }).setNegativeButton(Language.text(R.string.close),null).show();
    }
}
