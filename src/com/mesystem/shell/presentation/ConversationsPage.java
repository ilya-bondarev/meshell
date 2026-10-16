package com.mesystem.shell;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

final class ConversationsPage extends LinearLayout {
    private final ChatEngine chat;
    private final Runnable opened,settings;
    private final EditText search;
    private final Button chats,agents,filter;
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final TextView summary,task,loadError;
    private final LinearLayout taskRow;
    private final IconView stop;
    private boolean visible,agentTab,archive,loading;
    private int generation,limit=50;
    private long rendered=-1;
    private final Runnable query=this::runQuery;
    private void runQuery() { rendered=-1; limit=50; scroll.scrollTo(0,0); refresh(); }
    ConversationsPage(Context c,ChatEngine engine,Runnable opened,Runnable settings) {
        super(c); chat=engine; this.opened=opened; this.settings=settings; setOrientation(VERTICAL);
        LinearLayout header=Ui.row(c); Ui.pad(header,20,10,8,8);
        header.addView(Ui.bold(c,Language.text(R.string.chats),24,Ui.INK),new LayoutParams(0,-2,1));
        header.addView(new IconView(c,IconView.SETTINGS,Language.text(R.string.settings),v -> settings.run()),Ui.size(c,44,44));
        header.addView(new IconView(c,IconView.PLUS,Language.text(R.string.create_chat_or_agent_profile),v -> add()),Ui.size(c,44,44)); addView(header);
        chats=Ui.button(c,Language.text(R.string.chats),true,v -> tab(false)); agents=Ui.button(c,Language.text(R.string.agents),false,v -> tab(true));
        LinearLayout tabs=Ui.tabs(c,chats,agents);
        LayoutParams tl=Ui.size(c,-1,46); tl.setMargins(Ui.dp(c,16),0,Ui.dp(c,16),Ui.dp(c,10)); addView(tabs,tl);
        taskRow=Ui.row(c); Ui.pressable(taskRow,Ui.MINT_SOFT,16,0); taskRow.setOnClickListener(v -> AgentDialogs.tasks(c,chat,opened));
        task=Ui.text(c,"",12,Ui.MINT); task.setLineSpacing(Ui.dp(c,3),1); Ui.pad(task,14,10,8,10); taskRow.addView(task,new LayoutParams(0,-2,1));
        stop=new IconView(c,IconView.STOP,Language.text(R.string.stop_running_task),v -> AgentDialogs.tasks(c,chat,opened)); stop.color(Ui.DANGER); taskRow.addView(stop,Ui.size(c,48,48));
        LayoutParams trp=Ui.size(c,-1,-2); trp.setMargins(Ui.dp(c,16),0,Ui.dp(c,16),Ui.dp(c,10)); addView(taskRow,trp);
        search=AgentDialogs.entry(c,"",Language.text(R.string.search_titles_and_messages),InputType.TYPE_CLASS_TEXT);
        LinearLayout searchBox=Ui.searchBox(c,search);
        LayoutParams sp=Ui.size(c,-1,48); sp.setMargins(Ui.dp(c,16),0,Ui.dp(c,16),0); addView(searchBox,sp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { generation++; removeCallbacks(query); postDelayed(query,180); }
            public void afterTextChanged(Editable e) { }
        });
        LinearLayout controls=Ui.row(c); Ui.pad(controls,16,8,16,8);
        summary=Ui.text(c,"",12,Ui.MUTED); controls.addView(summary,new LayoutParams(0,-2,1));
        filter=Ui.button(c,Language.text(R.string.archive),false,v -> { archive=!archive; generation++; query.run(); }); filter.setTextSize(12); Ui.pad(filter,14,0,14,0);
        controls.addView(filter,Ui.size(c,-2,36)); addView(controls);
        loadError=Ui.text(c,"",13,Ui.DANGER); loadError.setBackground(Ui.shape(c,Ui.DANGER_SOFT,14,0)); Ui.pad(loadError,14,10,14,10); loadError.setVisibility(GONE);
        LayoutParams lp=Ui.size(c,-1,-2); lp.setMargins(Ui.dp(c,16),0,Ui.dp(c,16),Ui.dp(c,8)); addView(loadError,lp);
        scroll=new ScrollView(c); scroll.setVerticalScrollBarEnabled(false); rows=Ui.bounded(c); Ui.pad(rows,16,0,16,18); scroll.addView(rows,Ui.centred(-2)); addView(scroll,new LayoutParams(-1,0,1));
    }
    void active(boolean value) { visible=value; if(value) { rendered=-1; refresh(); } else { generation++; removeCallbacks(query); } }
    private void tab(boolean value) { agentTab=value; generation++; loading=false; query.run(); }
    private void add() {
        if(!chat.ready) return;
        if(agentTab) AgentDialogs.edit(getContext(),chat,Language.text(R.string.new_agent),chat.configuration(),a -> chat.saveProfile(UUID.randomUUID().toString(),a));
        else new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.new_chat))
            .setItems(new String[]{Language.text(R.string.default_agent),Language.text(R.string.choose_agent)},(d,n) -> {
                if(n==0) chat.newThread(null,opened); else AgentDialogs.choose(getContext(),chat,p -> chat.newThread(p.id,opened));
            }).setNegativeButton(Language.text(R.string.cancel),null).show();
    }
    void refresh() {
        if(!visible) return;
        loadError.setText(chat.error); loadError.setVisibility(chat.error.isEmpty()?GONE:VISIBLE);
        taskRow.setVisibility(chat.busy?VISIBLE:GONE); stop.setEnabled(!chat.stopping()); task.setText(Language.text(R.string.active_tasks_2)+chat.activeTasks().size()+Language.text(R.string.select_chat_stop));
        filter.setText(archive?Language.text(R.string.all_chats):Language.text(R.string.archive)); filter.setVisibility(agentTab?GONE:VISIBLE); ((View)search.getParent()).setVisibility(agentTab?GONE:VISIBLE);
        Ui.selected(chats,!agentTab); Ui.selected(agents,agentTab);
        long revision=chat.managementRevision(); if(!chat.ready || revision==rendered) return;
        if(agentTab) { rendered=revision; showAgents(); return; }
        if(loading) return; loading=true;
        final int ticket=generation; final int y=scroll.getScrollY(); final String text=search.getText().toString();
        chat.history(text,archive,history -> {
            loading=false; if(!visible || agentTab || ticket!=generation) { if(visible) refresh(); return; }
            rendered=revision; showChats(history); scroll.post(() -> { if(visible && ticket==generation) scroll.scrollTo(0,y); });
            if(chat.managementRevision()!=revision) post(this::refresh);
        });
    }
    private void card(View v) { LayoutParams lp=Ui.size(getContext(),-1,-2); lp.bottomMargin=Ui.dp(getContext(),10); rows.addView(v,lp); }
    private void showChats(List<ChatStore.Conversation> history) {
        Context c=getContext(); rows.removeAllViews(); summary.setText((archive?Language.text(R.string.archive_2):"")+history.size()+" "+Language.chats(history.size()));
        if(history.isEmpty()) {
            FoxView fox=new FoxView(c,false); fox.mood(search.length()>0?FoxView.THINKING:FoxView.IDLE); rows.addView(fox,Ui.size(c,-1,120)); Ui.gap(rows,12);
            TextView title=Ui.bold(c,search.length()>0?Language.text(R.string.no_results):archive?Language.text(R.string.archive_empty):Language.text(R.string.start_a_chat),20,Ui.INK); title.setGravity(android.view.Gravity.CENTER); rows.addView(title,Ui.size(c,-1,-2)); Ui.gap(rows,10);
            return;
        }
        for(int i=0;i<Math.min(limit,history.size());i++) {
            ChatStore.Conversation t=history.get(i); LinearLayout card=Ui.column(c);
            Ui.pressable(card,t.id==chat.threadId()?Ui.PANEL2:Ui.PANEL,20,t.id==chat.threadId()?0x8070edc8:Ui.BORDER); Ui.pad(card,16,10,6,14);
            LinearLayout heading=Ui.row(c); TextView title=Ui.bold(c,(t.pinned?"★ ":"")+t.title,16,Ui.INK);
            title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END); heading.addView(title,new LayoutParams(0,-2,1));
            IconView more=new IconView(c,IconView.MORE,Language.text(R.string.chat_actions)+t.title+"»",v -> menu(t)); more.color(Ui.FAINT);
            heading.addView(more,Ui.size(c,44,44)); card.addView(heading);
            TextView preview=Ui.text(c,plain(t.preview),13,Ui.MUTED); preview.setMaxLines(2); preview.setLineSpacing(Ui.dp(c,2),1); preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Ui.pad(preview,0,0,10,0); card.addView(preview); Ui.gap(card,12);
            boolean running=chat.running(t.id);
            AgentConfig a=AgentConfig.decode(t.settings,chat.configuration());
            String state=running?Language.text(R.string.running_3):!t.draft.isEmpty()?Language.text(R.string.draft):t.unread?Language.text(R.string.new_result):"interrupted".equals(t.state)?Language.text(R.string.interrupted):"stopped".equals(t.state)?Language.text(R.string.stopped):"error".equals(t.state)?Language.text(R.string.error_2):"";
            String date=new SimpleDateFormat("dd.MM HH:mm",Language.locale()).format(new Date(t.updated));
            LinearLayout meta=Ui.row(c);
            if(!state.isEmpty()) { boolean bad=Language.text(R.string.error_2).equals(state) || Language.text(R.string.interrupted).equals(state);
                int tone=running||t.unread?Ui.MINT:bad?Ui.DANGER:Language.text(R.string.draft).equals(state)?Ui.LAVENDER:Ui.MUTED;
                meta.addView(Ui.chip(c,state.replace("● ",""),tone,running||t.unread?Ui.MINT_SOFT:bad?Ui.DANGER_SOFT:Language.text(R.string.draft).equals(state)?Ui.LAVENDER_SOFT:Ui.PANEL3));
                meta.addView(new android.view.View(c),Ui.size(c,8,1)); }
            TextView when=Ui.text(c,a.name+" · "+date,11,Ui.FAINT); when.setSingleLine(true); when.setEllipsize(android.text.TextUtils.TruncateAt.END); meta.addView(when);
            card.addView(meta);
            card.setOnClickListener(v -> chat.selectThread(t.id,opened)); card.setOnLongClickListener(v -> { menu(t); return true; }); card(card);
        }
        if(history.size()>limit) { Button more=Ui.button(c,Language.text(R.string.show_more)+(history.size()-limit)+")",false,v -> { limit+=50; rendered=-1; refresh(); });
            LayoutParams mp=Ui.size(c,-1,48); mp.bottomMargin=Ui.dp(c,10); rows.addView(more,mp); }
    }
    static String plain(String text) {
        return text.replaceAll("```[a-zA-Z0-9]*","").replaceAll("\\*\\*|__|`|~~","")
            .replaceAll("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+[.)])\\s+","").replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)","$1").replaceAll("\\s*\\|\\s*"," ").replaceAll("\\s+"," ").trim();
    }
    private void menu(ChatStore.Conversation t) {
        Context c=getContext(); new AlertDialog.Builder(c).setTitle(t.title)
            .setItems(new String[]{Language.text(R.string.open), Language.text(R.string.rename),t.pinned?Language.text(R.string.unpin):Language.text(R.string.pin),t.archived?Language.text(R.string.unarchive):Language.text(R.string.archive),Language.text(R.string.delete)},(d,n) -> {
                try {
                    if(n==0) chat.selectThread(t.id,opened);
                    else if(n==1) rename(t);
                    else if(n==2) chat.pinThread(t.id,!t.pinned);
                    else if(n==3) chat.archiveThread(t.id,!t.archived);
                    else new AlertDialog.Builder(c).setTitle(Language.text(R.string.delete_chat)).setMessage("«"+t.title+Language.text(R.string.messages_response_versions_drafts_and_local_attachments_will))
                        .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.delete_2),(x,w) -> {
                            try { chat.deleteThread(t.id); refresh(); } catch(Exception e) { AgentDialogs.toast(c,e.getMessage()); }
                        }).show();
                    refresh();
                } catch(Exception e) { AgentDialogs.toast(c,e.getMessage()); }
            }).setNegativeButton(Language.text(R.string.close),null).show();
    }
    private void rename(ChatStore.Conversation t) {
        Context c=getContext(); EditText name=AgentDialogs.entry(c,t.title,Language.text(R.string.title),InputType.TYPE_CLASS_TEXT); name.setSelectAllOnFocus(true);
        AlertDialog dialog=new AlertDialog.Builder(c).setTitle(Language.text(R.string.chat_title)).setView(name).setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.save),null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { chat.renameThread(t.id,name.getText().toString()); dialog.dismiss(); refresh(); } catch(Exception e) { name.setError(e.getMessage()); }
        })); dialog.show();
    }
    private void showAgents() {
        Context c=getContext(); rows.removeAllViews(); List<ChatStore.Profile> list=chat.profiles(); summary.setText(list.size()+Language.text(R.string.profiles));
        for(ChatStore.Profile p:list) {
            AgentConfig a=p.config; LinearLayout card=Ui.card(c);
            LinearLayout top=Ui.row(c); IconView glyph=new IconView(c,IconView.FOX,null,null); glyph.color(a.tools?Ui.MINT:Ui.LAVENDER);
            glyph.setBackground(Ui.shape(c,a.tools?Ui.MINT_SOFT:Ui.LAVENDER_SOFT,12,0)); top.addView(glyph,Ui.size(c,40,40));
            TextView name=Ui.bold(c,a.name,17,Ui.INK); Ui.pad(name,12,0,8,0); top.addView(name,new LayoutParams(0,-2,1));
            if(p.id.equals(chat.defaultProfile())) top.addView(Ui.chip(c,Language.text(R.string.default_profile),Ui.MINT,Ui.MINT_SOFT));
            card.addView(top); Ui.gap(card,12);
            card.addView(Ui.caption(c,a.model+" · "+a.effort+"\n"+(a.tools?Language.text(R.string.tools_enabled):Language.text(R.string.chat_only))+Language.text(R.string.context)+a.context)); Ui.gap(card,14);
            LinearLayout actions=Ui.row(c); Button start=Ui.button(c,Language.text(R.string.new_chat),true,v -> chat.newThread(p.id,opened)); start.setTextSize(13); actions.addView(start,new LayoutParams(0,Ui.dp(c,42),1));
            Button edit=Ui.button(c,Language.text(R.string.edit_2),false,v -> AgentDialogs.edit(c,chat,Language.text(R.string.agent_profile),a,next -> chat.saveProfile(p.id,next))); edit.setTextSize(13);
            LayoutParams ep=new LayoutParams(0,Ui.dp(c,42),1); ep.leftMargin=Ui.dp(c,8); actions.addView(edit,ep);
            actions.addView(new IconView(c,IconView.MORE,Language.text(R.string.agent_actions)+a.name+"»",v -> new AlertDialog.Builder(c).setTitle(a.name)
                .setItems(new String[]{Language.text(R.string.set_as_default),Language.text(R.string.duplicate),Language.text(R.string.delete_profile)},(d,n) -> {
                    if(n==0) chat.defaultProfile(p.id);
                    else if(n==1) AgentDialogs.edit(c,chat,Language.text(R.string.profile_copy),a,next -> chat.saveProfile(UUID.randomUUID().toString(),next));
                    else new AlertDialog.Builder(c).setTitle(Language.text(R.string.delete_profile_2)+a.name+"»?").setMessage(Language.text(R.string.chats_and_their_settings_are_kept))
                        .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.delete_2),(x,w) -> chat.deleteProfile(p.id)).show();
                }).setNegativeButton(Language.text(R.string.close),null).show()),Ui.size(c,44,44)); card.addView(actions); card(card);
        }
        Button add=Ui.button(c,Language.text(R.string.create_agent),false,v -> add()); LayoutParams ap=Ui.size(c,-1,50); ap.bottomMargin=Ui.dp(c,10); rows.addView(add,ap);
    }
}
