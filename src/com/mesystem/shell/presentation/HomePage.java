package com.mesystem.shell;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Calendar;
import java.util.function.Consumer;

final class HomePage extends ScrollView {
    private final FoxView fox;
    private final TextView greeting,presenceLabel,mood,moodDetail,resumeTitle,taskTitle,taskState,terminalCaption;
    private final View presenceDot;
    private final Button primary;
    private final LinearLayout resume,task;
    private final QuotaCard quota;
    private final IconView taskIcon,stop,terminalIcon;
    private boolean wasBusy;

    HomePage(Context c,ChatEngine engine,Runnable openChat,Runnable newChat,Runnable account,Runnable settings,Runnable terminal,Consumer<String> module) {
        super(c); setFillViewport(true); setVerticalScrollBarEnabled(false);
        LinearLayout body=Ui.bounded(c); Ui.pad(body,20,10,20,28); addView(body,Ui.centred(-2));

        LinearLayout header=Ui.row(c); LinearLayout names=Ui.column(c);
        greeting=Ui.text(c,"",13,Ui.MUTED); names.addView(greeting); Ui.gap(names,4);
        names.addView(Ui.bold(c,"MeShell",28,Ui.INK)); header.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout presence=Ui.row(c); Ui.pad(presence,10,0,12,0); Ui.pressable(presence,Ui.PANEL,999,Ui.BORDER);
        presenceDot=new View(c); presence.addView(presenceDot,Ui.size(c,8,8));
        presenceLabel=Ui.bold(c,"",12,Ui.INK); Ui.pad(presenceLabel,7,0,0,0); presence.addView(presenceLabel);
        presence.setOnClickListener(v -> account.run()); header.addView(presence,Ui.size(c,-2,36));
        IconView gear=new IconView(c,IconView.SETTINGS,Language.text(R.string.settings),v -> settings.run()); gear.color(Ui.MUTED);
        LinearLayout.LayoutParams gp=Ui.size(c,44,44); gp.leftMargin=Ui.dp(c,4); header.addView(gear,gp); body.addView(header); Ui.gap(body,16);

        LinearLayout hero=Ui.card(c); Ui.pad(hero,12,8,12,14);
        fox=new FoxView(c,true);
        hero.addView(fox,Ui.size(c,-1,196));
        mood=Ui.bold(c,"",17,Ui.INK); mood.setGravity(Gravity.CENTER); hero.addView(mood,Ui.size(c,-1,-2)); Ui.gap(hero,6);
        moodDetail=Ui.caption(c,""); moodDetail.setGravity(Gravity.CENTER); moodDetail.setMaxLines(2); moodDetail.setEllipsize(TextUtils.TruncateAt.END);
        Ui.pad(moodDetail,12,0,12,0); hero.addView(moodDetail,Ui.size(c,-1,-2)); Ui.gap(hero,16);
        primary=Ui.button(c,"",true,v -> { if(engine.authenticated) newChat.run(); else account.run(); }); primary.setTextSize(16);
        LinearLayout.LayoutParams pp=Ui.size(c,-1,54); pp.setMargins(Ui.dp(c,4),0,Ui.dp(c,4),0); hero.addView(primary,pp);
        resume=Ui.row(c); Ui.pad(resume,14,0,8,0); Ui.pressable(resume,Ui.PANEL2,16,0);
        IconView chatGlyph=new IconView(c,IconView.CHAT,null,null); chatGlyph.color(Ui.MINT); resume.addView(chatGlyph,Ui.size(c,28,44));
        LinearLayout resumeText=Ui.column(c); Ui.pad(resumeText,10,0,6,0);
        resumeText.addView(Ui.text(c,Language.text(R.string.continue_chat),11,Ui.MUTED)); Ui.gap(resumeText,4);
        resumeTitle=Ui.bold(c,"",14,Ui.INK); resumeTitle.setSingleLine(true); resumeTitle.setEllipsize(TextUtils.TruncateAt.END); resumeText.addView(resumeTitle);
        resume.addView(resumeText,new LinearLayout.LayoutParams(0,-2,1));
        IconView next=new IconView(c,IconView.CHEVRON,null,null); next.color(Ui.FAINT); next.glyphSize(18); resume.addView(next,Ui.size(c,28,44));
        resume.setOnClickListener(v -> openChat.run());
        LinearLayout.LayoutParams rp=Ui.size(c,-1,58); rp.setMargins(Ui.dp(c,4),Ui.dp(c,10),Ui.dp(c,4),0); hero.addView(resume,rp);
        body.addView(hero); Ui.gap(body,12);

        task=Ui.row(c); Ui.pad(task,14,8,6,8); Ui.pressable(task,Ui.PANEL,18,Ui.BORDER);
        taskIcon=new IconView(c,IconView.HISTORY,null,null); task.addView(taskIcon,Ui.size(c,28,40));
        LinearLayout taskText=Ui.column(c); Ui.pad(taskText,10,0,6,0);
        taskTitle=Ui.bold(c,"",14,Ui.INK); taskTitle.setSingleLine(true); taskTitle.setEllipsize(TextUtils.TruncateAt.END); taskText.addView(taskTitle); Ui.gap(taskText,4);
        taskState=Ui.text(c,"",12,Ui.MUTED); taskState.setMaxLines(2); taskState.setEllipsize(TextUtils.TruncateAt.END); taskText.addView(taskState);
        task.addView(taskText,new LinearLayout.LayoutParams(0,-2,1));
        stop=new IconView(c,IconView.STOP,Language.text(R.string.stop_agent_task),v -> AgentDialogs.tasks(c,engine,openChat)); stop.color(Ui.DANGER); task.addView(stop,Ui.size(c,48,48));
        task.setOnClickListener(v -> { if(engine.busy) AgentDialogs.tasks(c,engine,openChat); else if(engine.taskThread>0) engine.openTask(openChat); });
        body.addView(task,Ui.size(c,-1,-2)); Ui.gap(body,22);

        body.addView(Ui.section(c,Language.text(R.string.quick_access)));
        Ui.Grid grid=new Ui.Grid(c,150,12,4); body.addView(grid,Ui.size(c,-1,-2));
        tile(grid,IconView.NOTES,Ui.LAVENDER,Language.text(R.string.notes),Language.text(R.string.local_text_notes),() -> module.accept("notes"));
        tile(grid,IconView.CPU,Ui.MINT,Language.text(R.string.device),Language.text(R.string.cpu_memory_and_sensors),() -> module.accept("device"));
        tile(grid,IconView.GRID,Ui.MINT,Language.text(R.string.modules),Language.text(R.string.tools_2),() -> module.accept(""));
        LinearLayout shell=tile(grid,IconView.TERMINAL,Ui.MINT,Language.text(R.string.terminal),"",terminal);
        terminalIcon=(IconView)((LinearLayout)shell.getChildAt(0)).getChildAt(0); terminalCaption=(TextView)shell.getChildAt(shell.getChildCount()-1);
        Ui.gap(body,22);
        body.addView(Ui.section(c,Language.text(R.string.subscription_2)));
        quota=new QuotaCard(c,engine::refreshLimits); body.addView(quota,Ui.size(c,-1,-2));
    }

    private LinearLayout tile(Ui.Grid grid,int icon,int color,String label,String note,Runnable action) {
        Context c=getContext(); LinearLayout tile=Ui.column(c); Ui.pad(tile,14,14,14,14); Ui.pressable(tile,Ui.PANEL,20,Ui.BORDER);
        LinearLayout badge=Ui.row(c); badge.setGravity(Gravity.CENTER); badge.setBackground(Ui.shape(c,color==Ui.LAVENDER?Ui.LAVENDER_SOFT:Ui.MINT_SOFT,14,0));
        IconView glyph=new IconView(c,icon,null,null); glyph.color(color); glyph.glyphSize(22); badge.addView(glyph,Ui.size(c,40,40));
        tile.addView(badge,Ui.size(c,40,40)); Ui.gap(tile,14);
        tile.addView(Ui.bold(c,label,15,Ui.INK)); Ui.gap(tile,5);
        TextView caption=Ui.text(c,note,12,Ui.MUTED); caption.setMaxLines(2); tile.addView(caption);
        tile.setContentDescription(label); tile.setOnClickListener(v -> action.run()); grid.addView(tile); return tile;
    }

    private static String greeting() {
        int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour>=5 && hour<12?Language.text(R.string.good_morning):hour>=12 && hour<17?Language.text(R.string.good_afternoon):hour>=17 && hour<23?Language.text(R.string.good_evening):Language.text(R.string.good_night);
    }

    void refresh(ChatEngine chat,boolean shell) {
        greeting.setText(greeting());
        presenceDot.setBackground(Ui.shape(getContext(),chat.authenticated?Ui.MINT:Ui.WARN,10,0));
        presenceLabel.setText(chat.authenticated?"ChatGPT":Language.text(R.string.sign_in));
        ((View)presenceLabel.getParent()).setContentDescription(chat.authenticated?Language.text(R.string.chatgpt_connected_account):Language.text(R.string.sign_in_to_chatgpt));
        int state=!chat.authenticated?FoxView.SLEEPING:chat.busy?FoxView.THINKING:!chat.error.isEmpty()?FoxView.ALERT:FoxView.IDLE;
        fox.mood(state); if(wasBusy && !chat.busy) fox.cheer(); wasBusy=chat.busy;
        switch(state) {
            case FoxView.SLEEPING: mood.setText(Language.text(R.string.not_signed_in)); moodDetail.setText(""); break;
            case FoxView.THINKING: mood.setText(chat.activeTasks().size()>1?Language.text(R.string.tasks_running):Language.text(R.string.task_running)); moodDetail.setText(chat.phase); break;
            case FoxView.ALERT: mood.setText(Language.text(R.string.something_went_wrong)); moodDetail.setText(chat.error); break;
            default: mood.setText(Language.text(R.string.ready)); moodDetail.setText("");
        }
        moodDetail.setOnClickListener(state==FoxView.ALERT?v -> new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.error_2)).setMessage(chat.error)
            .setPositiveButton(Language.text(R.string.ok),(d,w) -> chat.clearError()).show():null);
        primary.setText(chat.authenticated?Language.text(R.string.new_chat):Language.text(R.string.connect_chatgpt));
        primary.setContentDescription(chat.authenticated?Language.text(R.string.start_chat):Language.text(R.string.connect_chatgpt));
        boolean hasThread=chat.hasConversation();
        resume.setVisibility(hasThread?VISIBLE:GONE); resumeTitle.setText(chat.threadTitle);

        boolean failed=chat.lastTaskFailed();
        // A finished task in the open conversation is already covered by the resume action.
        boolean duplicate=hasThread && !failed && chat.taskThread==chat.threadId();
        boolean showTask=chat.busy || (!chat.taskTitle.isEmpty() && !duplicate); task.setVisibility(showTask?VISIBLE:GONE);
        if(chat.busy) {
            int count=chat.activeTasks().size();
            taskTitle.setText(count>1?Language.text(R.string.active_tasks)+count:chat.taskTitle.isEmpty()?Language.text(R.string.agent_running):chat.taskTitle);
            taskState.setText(count>1?Language.text(R.string.select_chat_or_stop):chat.phase);
            taskIcon.icon(IconView.FOX); taskIcon.color(Ui.MINT);
        } else {
            taskTitle.setText(chat.taskTitle); taskState.setText(chat.taskState);
            taskIcon.icon(failed?IconView.STOP:IconView.HISTORY); taskIcon.color(failed?Ui.WARN:Ui.MUTED);
        }
        stop.setVisibility(chat.busy?VISIBLE:GONE);
        task.setContentDescription(chat.busy?Language.text(R.string.active_agent_tasks):Language.text(R.string.last_task)+chat.taskTitle);
        quota.refresh(chat);
        terminalIcon.color(shell?Ui.MINT:Ui.MUTED); terminalCaption.setText(shell?Language.text(R.string.shell_connected):Language.text(R.string.start_through_adb));
        terminalCaption.setTextColor(shell?Ui.MUTED:Ui.WARN);
    }
}
