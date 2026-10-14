package com.mesystem.shell;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class ChatPage extends LinearLayout {
    private final ChatEngine chat;
    private final Runnable terminal,account,activeTask;
    private final ChatScroll scroll;
    private final LinearLayout content;
    private final EditText input;
    private final TextView title,status,error,attachment,taskBanner;
    private final Button model,jump;
    private final IconView send;
    private final List<Bubble> bubbles=new ArrayList<>();
    private long displayedThread=-1,lastRead=-1,draftRevision=-1;
    private int lastReadAttempt=-1;
    private String attached="",attachedTitle="",composerSaved="";
    private boolean settingDraft,visible;
    private FoxView emptyFox;
    private JSONArray files=new JSONArray();
    private final Runnable saveDraft;
    private static final class Bubble {
        final long id; final LinearLayout card,body; final TextView meta,actionsRow; final Button retry,previous;
        String text="\u0000",state="",actions="[]",attachments="[]"; AlertDialog dialog; TextView detail; int actionCount;
        Bubble(long id,LinearLayout card,LinearLayout body,TextView meta,TextView actionsRow,Button retry,Button previous) {
            this.id=id; this.card=card; this.body=body; this.meta=meta; this.actionsRow=actionsRow; this.retry=retry; this.previous=previous;
        }
    }
    ChatPage(Context c,ChatEngine engine,Runnable terminal,Runnable account,Runnable attach,Runnable manage,Runnable activeTask) {
        super(c); this.chat=engine; this.terminal=terminal; this.account=account; this.activeTask=activeTask; setOrientation(VERTICAL);
        input=new EditText(c); saveDraft=this::saveDraftNow;
        LinearLayout header=Ui.row(c); Ui.pad(header,8,6,8,4);
        header.addView(new IconView(c,IconView.BACK,Language.text(R.string.chats_and_agents),v -> { saveDraftNow(); manage.run(); }),Ui.size(c,44,44));
        LinearLayout labels=Ui.column(c); title=Ui.bold(c,Language.text(R.string.new_chat),18,Ui.INK); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); labels.addView(title); Ui.gap(labels,5);
        status=Ui.text(c,"",11,Ui.MUTED); status.setMaxLines(2); labels.addView(status); header.addView(labels,new LayoutParams(0,-2,1));
        header.addView(new IconView(c,IconView.SETTINGS,Language.text(R.string.agent_and_chat_settings),v -> AgentDialogs.chat(c,chat)),Ui.size(c,44,44));
        header.addView(new IconView(c,IconView.PLUS,Language.text(R.string.new_chat),v -> { saveDraftNow(); savePosition(); chat.newThread(); }),Ui.size(c,44,44)); addView(header);
        model=Ui.button(c,"",false,v -> AgentDialogs.chat(c,chat)); model.setTextSize(12); model.setTextColor(Ui.MUTED); model.setSingleLine(true);
        model.setEllipsize(android.text.TextUtils.TruncateAt.END); Ui.pressable(model,Ui.PANEL,999,Ui.BORDER); Ui.pad(model,14,0,14,0);
        LayoutParams ml=Ui.size(c,-2,34); ml.setMargins(Ui.dp(c,16),0,Ui.dp(c,16),Ui.dp(c,6)); addView(model,ml);
        taskBanner=Ui.text(c,"",12,Ui.MINT); taskBanner.setLineSpacing(Ui.dp(c,3),1); Ui.pad(taskBanner,14,10,14,10); Ui.pressable(taskBanner,Ui.MINT_SOFT,14,0);
        taskBanner.setOnClickListener(v -> { saveDraftNow(); savePosition(); AgentDialogs.tasks(getContext(),chat,() -> refresh()); });
        LayoutParams bp=Ui.size(c,-1,-2); bp.setMargins(Ui.dp(c,16),0,Ui.dp(c,16),Ui.dp(c,6)); addView(taskBanner,bp);
        Ui.divider(this);
        FrameLayout viewport=new FrameLayout(c); scroll=new ChatScroll(c); content=Ui.bounded(c); Ui.pad(content,0,14,0,12); scroll.addView(content,Ui.centred(-2));
        viewport.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        jump=Ui.button(c,Language.text(R.string.latest),false,v -> scroll.jumpToEnd()); jump.setTextSize(12); Ui.pressable(jump,Ui.PANEL3,999,Ui.BORDER); jump.setElevation(Ui.dp(c,6));
        FrameLayout.LayoutParams jp=new FrameLayout.LayoutParams(-2,Ui.dp(c,40),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL); jp.setMargins(Ui.dp(c,16),0,Ui.dp(c,16),Ui.dp(c,10)); viewport.addView(jump,jp);
        scroll.changed=this::positionChanged; addView(viewport,new LayoutParams(-1,0,1));
        FrameLayout dockFrame=new FrameLayout(c); LinearLayout dock=Ui.bounded(c); dockFrame.addView(dock,Ui.centred(-2)); addView(dockFrame);
        error=Ui.text(c,"",12,Ui.DANGER); error.setLineSpacing(Ui.dp(c,3),1); Ui.pad(error,14,10,14,10); error.setBackground(Ui.shape(c,Ui.DANGER_SOFT,14,0));
        error.setOnClickListener(v -> { if(!chat.canSend()) account.run(); else chat.clearError(); });
        LayoutParams ep=Ui.size(c,-1,-2); ep.setMargins(Ui.dp(c,14),Ui.dp(c,6),Ui.dp(c,14),0); dock.addView(error,ep);
        attachment=Ui.bold(c,"",12,Ui.MINT); attachment.setSingleLine(true); attachment.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ui.pad(attachment,14,9,14,9); Ui.pressable(attachment,Ui.MINT_SOFT,999,0); attachment.setOnClickListener(v -> manageAttachments());
        LayoutParams ap=Ui.size(c,-2,-2); ap.setMargins(Ui.dp(c,14),Ui.dp(c,8),Ui.dp(c,14),0); dock.addView(attachment,ap);
        LinearLayout composer=Ui.row(c); Ui.pad(composer,12,8,12,10); composer.setGravity(Gravity.BOTTOM);
        LinearLayout field=Ui.row(c); field.setGravity(Gravity.BOTTOM); field.setBackground(Ui.shape(c,Ui.PANEL,24,Ui.BORDER)); Ui.pad(field,2,0,2,0);
        IconView clip=new IconView(c,IconView.CLIP,Language.text(R.string.attach_file_or_terminal_screen),v -> { if(chat.ready) attach.run(); }); clip.color(Ui.MUTED); field.addView(clip,Ui.size(c,44,48));
        input.setTextColor(Ui.INK); input.setHintTextColor(Ui.FAINT); input.setTextSize(15); input.setHint(Language.text(R.string.message));
        input.setMinLines(1); input.setMaxLines(6); input.setGravity(Gravity.CENTER_VERTICAL); input.setMinHeight(Ui.dp(c,48));
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI); input.setBackground(null); Ui.pad(input,2,12,2,12); Ui.cursor(input);
        field.addView(input,new LayoutParams(0,-2,1));
        composer.addView(field,new LayoutParams(0,-2,1));
        send=new IconView(c,IconView.SEND,Language.text(R.string.send_message),v -> send()); send.color(Ui.BG); Ui.pressable(send,Ui.MINT,999,0);
        LayoutParams sp=Ui.size(c,48,48); sp.leftMargin=Ui.dp(c,8); composer.addView(send,sp); dock.addView(composer);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { removeCallbacks(saveDraft); if(!settingDraft) postDelayed(saveDraft,350); updateComposer(); }
            public void afterTextChanged(Editable e) { }
        }); refresh();
    }
    void active(boolean value) {
        if(visible && !value) { saveDraftNow(); savePosition(); scroll.inactive(); }
        visible=value; if(value) { refresh(); if(scroll.policy.following) scroll.scheduleFollow(); }
    }
    private void toast(String value) { AgentDialogs.toast(getContext(),value); }
    private void updateComposer() {
        if(send==null) return;
        boolean here=chat.busyHere(),has=!input.getText().toString().trim().isEmpty() || !attached.isEmpty() || files.length()>0;
        send.icon(here?IconView.STOP:IconView.SEND); send.setContentDescription(here?Language.text(R.string.stop_this_chat_s_task):!chat.canSend()?Language.text(R.string.connect_chatgpt):Language.text(R.string.send_message));
        send.setEnabled(chat.ready && !chat.loggingIn && !chat.stopping() && (here || has || !chat.canSend()));
        input.setHint(here?Language.text(R.string.next_message_draft):Language.text(R.string.message));
        input.setEnabled(chat.ready);
    }
    private void send() {
        if(chat.busyHere()) { chat.cancel(); return; }
        if(!chat.canSend()) { account.run(); return; }
        String prompt=input.getText().toString().trim(); if(prompt.isEmpty() && attached.isEmpty() && files.length()==0) return;
        if(!attached.isEmpty()) prompt+=(prompt.isEmpty()?Language.text(R.string.analyze_the_attached_text):"")+Language.text(R.string.attachment_2)+attachedTitle+"]\n"+attached+Language.text(R.string.end_of_attachment);
        removeCallbacks(saveDraft);
        if(chat.send(prompt,files)) {
            settingDraft=true; input.setText(""); settingDraft=false; composerSaved=""; attached=""; attachedTitle=""; files=new JSONArray(); draftRevision=-1;
            scroll.jumpToEnd(); refresh();
        } else saveDraftNow();
    }
    void attach(String title,String text) {
        attachedTitle=title; attached=text; chat.saveExtra(displayedThread,title,text); refresh(); input.requestFocus();
    }
    private void manageAttachments() {
        final long thread=displayedThread; List<String> names=new ArrayList<>();
        for(int i=0;i<files.length();i++) { JSONObject item=files.optJSONObject(i); if(item!=null) names.add(item.optString("name")+Language.text(R.string.remove)); }
        if(!attached.isEmpty()) names.add(attachedTitle+Language.text(R.string.remove));
        new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.next_message_attachments)).setItems(names.toArray(new String[0]),(d,index) -> {
            if(thread!=displayedThread) return;
            if(index<files.length()) { Attachments.remove(files.optJSONObject(index)); files.remove(index); chat.saveAttachments(thread,files); }
            else { attached=""; attachedTitle=""; chat.saveExtra(thread,"",""); } refresh();
        }).setNegativeButton(Language.text(R.string.close),null).show();
    }
    private void attachmentViews(LinearLayout parent,String data) {
        try {
            JSONArray items=new JSONArray(data);
            for(int i=0;i<items.length();i++) {
                JSONObject item=items.getJSONObject(i); TextView label=Ui.text(getContext(),"📎 "+item.optString("name"),12,Ui.MINT); Ui.pad(label,0,8,0,6); parent.addView(label);
                if("image".equals(item.optString("kind"))) {
                    android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options(); o.inSampleSize=4;
                    android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(Attachments.file(item).getPath(),o);
                    if(bitmap!=null) { ImageView preview=new ImageView(getContext()); preview.setImageBitmap(bitmap); preview.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.setContentDescription(item.optString("name")); parent.addView(preview,Ui.size(getContext(),-1,150)); }
                }
            }
        } catch(Exception e) { parent.addView(Ui.text(getContext(),Language.text(R.string.attachment_unavailable),12,Ui.MUTED)); }
    }
    void insertText(String text) {
        int start=Math.max(0,input.getSelectionStart()),end=Math.max(start,input.getSelectionEnd());
        input.getText().replace(start,end,(start>0 && !Character.isWhitespace(input.getText().charAt(start-1))?" ":"")+text); input.requestFocus();
    }
    void saveDraftNow() {
        removeCallbacks(saveDraft); String text=input.getText().toString();
        if(displayedThread>0 && !settingDraft && !text.equals(composerSaved)) { chat.saveDraft(displayedThread,text); composerSaved=text; }
    }
    private void savePosition() { if(displayedThread>0) chat.scrollPosition(displayedThread,scroll.getScrollY(),scroll.policy.following); }
    private Bubble addBubble(ChatStore.Message message) {
        Context c=getContext(); boolean user="user".equals(message.role); LinearLayout card,inner;
        if(user) {
            // Own messages: a right-aligned bubble that wraps short text and never spans the full width.
            card=Ui.row(c); card.setGravity(Gravity.END); Ui.pad(card,56,0,14,0); inner=Ui.column(c); Ui.pad(inner,14,10,14,10);
            android.graphics.drawable.GradientDrawable shape=Ui.shape(c,Ui.PANEL3,0,0); float r=Ui.dp(c,20),tip=Ui.dp(c,6);
            shape.setCornerRadii(new float[]{r,r,r,r,tip,tip,r,r}); inner.setBackground(shape); card.addView(inner,new LayoutParams(-2,-2));
        } else { card=Ui.column(c); Ui.pad(card,18,0,14,0); inner=card; }
        LayoutParams cp=Ui.size(c,-1,-2); cp.bottomMargin=Ui.dp(c,user?14:20); content.addView(card,cp);
        if(!user) {
            LinearLayout line=Ui.row(c); IconView avatar=new IconView(c,IconView.FOX,null,null); avatar.color(Ui.MINT); avatar.glyphSize(16);
            avatar.setBackground(Ui.shape(c,Ui.MINT_SOFT,999,0)); avatar.setMinimumWidth(0); avatar.setMinimumHeight(0); line.addView(avatar,Ui.size(c,26,26));
            TextView name=Ui.bold(c,"GPT",12,Ui.MINT); Ui.pad(name,8,0,0,0); line.addView(name,new LayoutParams(0,-2,1));
            IconView copy=new IconView(c,IconView.COPY,Language.text(R.string.copy_message),v -> { for(ChatStore.Message current:chat.snapshot()) if(current.id==message.id) { c.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("MeShell",current.text)); toast(Language.text(R.string.copied)); break; } });
            copy.color(Ui.FAINT); copy.glyphSize(18); line.addView(copy,Ui.size(c,40,40)); inner.addView(line); Ui.gap(inner,4);
        }
        TextView actions=Ui.bold(c,"",12,Ui.MUTED); actions.setGravity(Gravity.CENTER_VERTICAL); actions.setVisibility(GONE); Ui.pad(actions,12,0,12,0);
        Ui.pressable(actions,Ui.PANEL,12,Ui.BORDER); LayoutParams acp=Ui.size(c,-1,40); acp.bottomMargin=Ui.dp(c,10); inner.addView(actions,acp);
        LinearLayout body=Ui.column(c); inner.addView(body); TextView meta=Ui.text(c,"",12,Ui.MUTED); Ui.pad(meta,0,4,0,4); inner.addView(meta);
        LinearLayout controls=Ui.row(c); Button retry=Ui.button(c,"",false,v -> retry(message.id)); retry.setTextSize(12); controls.addView(retry,new LayoutParams(0,Ui.dp(c,40),1));
        Button previous=Ui.button(c,Language.text(R.string.previous_responses),false,v -> versions(message.id,message.thread)); previous.setTextSize(12);
        LayoutParams vp=new LayoutParams(0,Ui.dp(c,40),1); vp.leftMargin=Ui.dp(c,8); controls.addView(previous,vp); inner.addView(controls);
        retry.setVisibility(GONE); previous.setVisibility(GONE);
        Bubble bubble=new Bubble(message.id,card,body,meta,actions,retry,previous); actions.setOnClickListener(v -> showActions(bubble)); return bubble;
    }
    private void retry(long id) {
        if(chat.busyHere()) return; ChatStore.Message message=null; List<ChatStore.Message> history=chat.snapshot();
        if(!RetryPolicy.eligible(history,id)) { toast(Language.text(R.string.only_the_latest_response_can_be_retried)); return; }
        message=history.get(history.size()-1); final long thread=displayedThread;
        Runnable go=() -> { if(thread==chat.threadId() && chat.retry(id)) { scroll.jumpToEnd(); refresh(); } };
        if(RetryPolicy.hasActions(message)) new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.check_and_continue_2))
            .setMessage(Language.text(R.string.existing_actions_are_kept_verify_unfinished_commands_before))
            .setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.continue_action),(d,w) -> go.run()).show();
        else go.run();
    }
    private void versions(long id,long thread) {
        LinearLayout list=Ui.column(getContext()); Ui.pad(list,18,12,18,18); List<ChatStore.Message> attempts=chat.versions(id,thread);
        for(int i=0;i<attempts.size();i++) { ChatStore.Message m=attempts.get(i); list.addView(Ui.bold(getContext(),Language.text(R.string.attempt)+(attempts.size()-i)+" · "+m.state,12,Ui.MUTED)); Ui.gap(list,8);
            TextView text=Ui.text(getContext(),m.text.isEmpty()?Language.text(R.string.no_text):m.text,14,Ui.INK); text.setTextIsSelectable(true); list.addView(text);
            if(!m.error.isEmpty()) list.addView(Ui.text(getContext(),m.error,12,Ui.DANGER)); Ui.gap(list,20); }
        ScrollView view=new ScrollView(getContext()); view.addView(list); new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.previous_attempts)).setView(view).setPositiveButton(Language.text(R.string.close),null).show();
    }
    private void showActions(Bubble bubble) {
        bubble.detail=Ui.text(getContext(),actionDetails(bubble.actions),12,Ui.INK); bubble.detail.setTypeface(android.graphics.Typeface.MONOSPACE); bubble.detail.setTextIsSelectable(true); Ui.pad(bubble.detail,18,12,18,16);
        ScrollView view=new ScrollView(getContext()); view.addView(bubble.detail); bubble.dialog=new AlertDialog.Builder(getContext()).setTitle(Language.text(R.string.actions_and_results)).setView(view).setPositiveButton(Language.text(R.string.close),null).show();
    }
    private String actionDetails(String encoded) {
        StringBuilder text=new StringBuilder();
        try {
            JSONArray entries=new JSONArray(encoded);
            for(int i=0;i<entries.length();i++) { JSONObject a=entries.getJSONObject(i); text.append(i+1).append(". ").append(a.optString("name")).append(" · ").append(a.optString("state"));
                if(a.has("elapsed_ms")) text.append(" · ").append(a.optLong("elapsed_ms")).append(" ms"); text.append('\n').append(a.optString("arguments")).append('\n'); String output=a.optString("output");
                try { JSONObject value=new JSONObject(output); output=value.has("output")?(value.has("exit_code")?"exit "+value.optInt("exit_code")+"\n":"")+value.optString("output"):value.toString(2); } catch(Exception ignored) { }
                text.append(output).append("\n\n"); }
        } catch(Exception ignored) { text.append(Language.text(R.string.no_details)); } return text.toString();
    }
    private void positionChanged() {
        jump.setVisibility(!scroll.policy.following && scroll.distance()>Ui.dp(getContext(),40)?VISIBLE:GONE);
        if(visible && chat.ready && scroll.policy.following && scroll.distance()<Ui.dp(getContext(),48) && !chat.busyHere()) {
            List<ChatStore.Message> history=chat.snapshot(); if(!history.isEmpty()) { ChatStore.Message last=history.get(history.size()-1);
                if((last.id!=lastRead || last.attempt!=lastReadAttempt) && "assistant".equals(last.role) && !"streaming".equals(last.state)) {
                    lastRead=last.id; lastReadAttempt=last.attempt; chat.markRead(displayedThread,last.id,last.attempt);
                }
            }
        }
    }
    void refresh() {
        if(scroll==null) return; updateComposer();
        if(!chat.ready) { status.setText(Language.text(R.string.loading_chat)); return; }
        long thread=chat.threadId(); boolean switched=thread!=displayedThread;
        if(switched) {
            saveDraftNow(); savePosition(); removeCallbacks(saveDraft); scroll.inactive();
            for(Bubble b:bubbles) if(b.dialog!=null) b.dialog.dismiss(); displayedThread=thread; lastRead=-1; lastReadAttempt=-1;
            bubbles.clear(); content.removeAllViews(); content.setTag(null); draftRevision=-1;
            settingDraft=true; input.setText(chat.draft()); input.setSelection(input.length()); settingDraft=false; composerSaved=input.getText().toString();
            scroll.policy.restore(chat.follows(thread));
        }
        if(draftRevision!=chat.managementRevision()) {
            String persisted=chat.draft();
            if(input.getText().toString().equals(composerSaved) && !persisted.equals(composerSaved)) {
                int start=input.getSelectionStart(),end=input.getSelectionEnd(); boolean atEnd=start==input.length();
                settingDraft=true; input.setText(persisted); input.setSelection(atEnd?input.length():Math.max(0,Math.min(start,input.length())),atEnd?input.length():Math.max(0,Math.min(end,input.length()))); settingDraft=false; composerSaved=persisted;
            }
            files=chat.draftAttachments(); JSONObject extra=chat.draftExtra(); attached=extra.optString("text"); attachedTitle=extra.optString("title"); draftRevision=chat.managementRevision();
        }
        int y=scroll.getScrollY(),offset=0; Bubble anchor=null;
        if(!switched && !scroll.policy.following) for(Bubble b:bubbles) { if(b.card.getTop()<=y) { anchor=b; offset=y-b.card.getTop(); } else break; }
        boolean contentChanged=false; List<ChatStore.Message> messages=chat.snapshot();
        if(messages.isEmpty()) {
            if(!"empty".equals(content.getTag())) { content.removeAllViews(); bubbles.clear(); content.setTag("empty"); Ui.pad(content,20,0,20,12);
                emptyFox=new FoxView(getContext(),true); content.addView(emptyFox,Ui.size(getContext(),-1,180));
                TextView hello=Ui.bold(getContext(),Language.text(R.string.new_chat),26,Ui.INK); hello.setGravity(Gravity.CENTER); content.addView(hello,Ui.size(getContext(),-1,-2)); Ui.gap(content,10);
                contentChanged=true;
            }
            if(emptyFox!=null) emptyFox.mood(!chat.canSend()?FoxView.SLEEPING:chat.busyHere()?FoxView.THINKING:FoxView.IDLE);
        } else {
            Ui.pad(content,0,14,0,12); emptyFox=null;
            if("empty".equals(content.getTag()) || bubbles.size()>messages.size() || (!bubbles.isEmpty() && bubbles.get(0).id!=messages.get(0).id)) { content.removeAllViews(); bubbles.clear(); content.setTag(null); contentChanged=true; }
            for(int i=0;i<messages.size();i++) {
                ChatStore.Message m=messages.get(i); if(i>=bubbles.size()) { bubbles.add(addBubble(m)); contentChanged=true; }
                Bubble b=bubbles.get(i);
                if(!b.text.equals(m.text) || !b.state.equals(m.state) || !b.attachments.equals(m.attachments)) {
                    if("user".equals(m.role)) { b.body.removeAllViews(); TextView text=Ui.text(getContext(),m.text,15,Ui.INK); text.setTextIsSelectable(true); text.setLineSpacing(Ui.dp(getContext(),4),1); b.body.addView(text); attachmentViews(b.body,m.attachments); b.body.setVisibility(m.text.isEmpty() && "[]".equals(m.attachments)?GONE:VISIBLE); }
                    else MarkdownView.update(b.body,m.text,terminal,"streaming".equals(m.state));
                    b.text=m.text; b.state=m.state; b.attachments=m.attachments; contentChanged=true;
                }
                if(!b.actions.equals(m.actions)) { b.actions=m.actions; try { b.actionCount=new JSONArray(m.actions).length(); } catch(Exception ignored) { b.actionCount=0; }
                    if(b.dialog!=null && b.dialog.isShowing()) b.detail.setText(actionDetails(m.actions)); contentChanged=true; }
                b.actionsRow.setText(Language.text(R.string.actions)+b.actionCount+Language.text(R.string.journal_and_results)); b.actionsRow.setVisibility(b.actionCount==0?GONE:VISIBLE); b.actionsRow.setTextColor("streaming".equals(m.state)?Ui.MINT:Ui.MUTED);
                String meta="streaming".equals(m.state)?chat.phase:m.error;
                if(!b.meta.getText().toString().equals(meta)) contentChanged=true; b.meta.setText(meta); b.meta.setVisibility(meta.isEmpty()?GONE:VISIBLE); b.meta.setTextColor(!m.error.isEmpty()?Ui.DANGER:"streaming".equals(m.state)?Ui.MINT:Ui.MUTED);
                boolean latest=RetryPolicy.eligible(messages,m.id);
                int retryVisibility=latest?VISIBLE:GONE,previousVisibility=m.versions>0?VISIBLE:GONE;
                if(b.retry.getVisibility()!=retryVisibility || b.previous.getVisibility()!=previousVisibility) contentChanged=true;
                b.retry.setVisibility(retryVisibility); if(latest) b.retry.setText(Language.retryLabel(m)); b.retry.setEnabled(chat.canSend() && !chat.busyHere() && !chat.loggingIn);
                b.previous.setVisibility(previousVisibility); b.previous.setText(Language.text(R.string.previous)+m.versions+")");
            }
        }
        title.setText(chat.threadTitle); status.setText(chat.busyHere()?chat.phase:!chat.canSend()?Language.text(R.string.sign_in_to_chatgpt):chat.taskThread==thread && !chat.actualModel.isEmpty()?chat.actualModel+" · "+chat.totalTokens+Language.text(R.string.tokens):Language.text(R.string.saved_locally));
        model.setText(chat.configuration().name+" · "+chat.model+" · "+chat.effort+" · "+(chat.agentEnabled?Language.text(R.string.tools):Language.text(R.string.chat_only_2))+"  ⌄");
        taskBanner.setVisibility(chat.busy && !chat.busyHere()?VISIBLE:GONE); taskBanner.setText("●  "+chat.taskLabel()+"\n"+chat.phase+Language.text(R.string.open_running_chat));
        error.setText(chat.error); error.setVisibility(chat.error.isEmpty()?GONE:VISIBLE);
        StringBuilder summary=new StringBuilder(); for(int i=0;i<files.length();i++) { JSONObject item=files.optJSONObject(i); if(item==null) continue; if(summary.length()>0) summary.append(" · "); summary.append(item.optString("name")); }
        if(!attached.isEmpty()) { if(summary.length()>0) summary.append(" · "); summary.append(attachedTitle); }
        attachment.setText("📎  "+summary+Language.text(R.string.edit)); attachment.setVisibility(summary.length()==0?GONE:VISIBLE); updateComposer();
        if(switched) scroll.restorePosition(chat.scrollPosition(thread),chat.follows(thread));
        else if(contentChanged && visible) {
            if(scroll.policy.following) scroll.scheduleFollow();
            else if(anchor!=null && !scroll.touching) { final Bubble a=anchor; final int dy=offset,ticket=scroll.policy.generation;
                scroll.post(() -> { if(visible && displayedThread==thread && ticket==scroll.policy.generation && !scroll.policy.following && !scroll.touching) scroll.scrollTo(0,a.card.getTop()+dy); });
            }
        }
        positionChanged();
    }
}
