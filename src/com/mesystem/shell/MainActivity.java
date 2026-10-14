package com.mesystem.shell;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    @Override protected void attachBaseContext(Context base) {
        Context localized=Language.wrap(base); super.attachBaseContext(localized); Language.initialize(localized.getResources());
    }
    private ShellService service;
    private ChatEngine chat;
    private TerminalView terminal;
    private TextView status;
    private HomePage home;
    private ChatPage conversation;
    private ConversationsPage conversations;
    private long pendingAttachmentThread,requestedThread;
    private String pendingModuleExport="";
    private AuthPage auth;
    private SettingsPage settings;
    private ModulesPage modules;
    private FrameLayout stage;
    private View terminalPage;
    private final LinearLayout[] navigation=new LinearLayout[4];
    private final View[] navPills=new View[4];
    private final IconView[] navIcons=new IconView[4];

    private static final int[] NAV_TARGETS={0,6,5,2};
    private int page;
    private boolean bound,control;
    private Button ctrlKey;
    private final ServiceConnection binding=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            service=((ShellService.LocalBinder)binder).get(); terminal.attach(service);
            service.observe(() -> { status.setText(service.status); terminal.refreshed(); refresh(); });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service=null; status.setText(Language.text(R.string.service_stopped_reopen_the_app)); refresh();
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(Ui.BG);
        chat=((MeShellApp)getApplication()).chat();
        LinearLayout root=Ui.column(this); root.setBackgroundColor(Ui.BG);
        stage=new FrameLayout(this); root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        terminalPage=buildTerminal();
        home=new HomePage(this,chat,() -> show(chat.authenticated?1:3),() -> chat.newThread(null,() -> show(1)),
            () -> show(3),() -> show(4),() -> show(2),this::openModule);
        conversation=new ChatPage(this,chat,() -> show(2),() -> show(3),this::attachMenu,() -> show(6),() -> chat.openTask(() -> show(1)));
        conversations=new ConversationsPage(this,chat,() -> show(1),() -> show(4));
        pendingModuleExport=saved==null?"":saved.getString("module-export","");
        pendingAttachmentThread=saved==null?0:saved.getLong("attachment-thread",0);
        requestedThread=getIntent().getLongExtra("open-thread",0);
        auth=new AuthPage(this,chat,() -> show(1),() -> show(0));
        settings=new SettingsPage(this,chat,() -> show(3),() -> show(0),() -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),41));
        stage.addView(home); stage.addView(conversation); stage.addView(conversations); stage.addView(terminalPage); stage.addView(auth); stage.addView(settings);
        Ui.divider(root);
        LinearLayout nav=Ui.row(this); Ui.pad(nav,8,6,8,6); nav.setBackgroundColor(Ui.BG);
        String[] labels={Language.text(R.string.home),Language.text(R.string.chats),Language.text(R.string.modules),Language.text(R.string.terminal)}; int[] icons={IconView.HOME,IconView.CHAT,IconView.GRID,IconView.TERMINAL};
        for(int i=0;i<4;i++) {
            final int target=NAV_TARGETS[i]; navigation[i]=Ui.column(this); navigation[i].setGravity(Gravity.CENTER);
            navigation[i].setOnClickListener(v -> show(target)); navigation[i].setContentDescription(labels[i]);
            navigation[i].setBackground(Ui.ripple(this,Ui.shape(this,0x00000000,16,0),16));
            FrameLayout pill=new FrameLayout(this); navPills[i]=pill;
            navIcons[i]=new IconView(this,icons[i],null,null); navIcons[i].glyphSize(21);
            pill.addView(navIcons[i],new FrameLayout.LayoutParams(-1,-1)); navigation[i].addView(pill,Ui.size(this,56,30));

            nav.addView(navigation[i],new LinearLayout.LayoutParams(0,Ui.dp(this,58),1));
        }
        root.addView(nav); setContentView(root);
        show(getIntent().getBooleanExtra("open-chat",false)?1:saved==null?0:saved.getInt("page",0));
        chat.observe(this::refresh);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1);
        Intent intent=new Intent(this,ShellService.class); startForegroundService(intent);
        bound=bindService(intent,binding,Context.BIND_AUTO_CREATE);
    }
    private void show(int target) {
        View focused=getCurrentFocus();
        if(focused!=null) { getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(),0); focused.clearFocus(); }
        if(target==5) ensureModules();
        conversation.active(target==1); conversations.active(target==6); boolean moved=page!=target; page=target;
        home.setVisibility(page==0?View.VISIBLE:View.GONE);
        conversation.setVisibility(page==1?View.VISIBLE:View.GONE);
        conversations.setVisibility(page==6?View.VISIBLE:View.GONE);
        terminalPage.setVisibility(page==2?View.VISIBLE:View.GONE);
        auth.setVisibility(page==3?View.VISIBLE:View.GONE);
        settings.setVisibility(page==4?View.VISIBLE:View.GONE);
        if(modules!=null) { modules.setVisibility(page==5?View.VISIBLE:View.GONE); modules.active(page==5); }
        for(int i=0;i<4;i++) {
            if(navigation[i]==null) continue;
            boolean selected=page==NAV_TARGETS[i] || (i==1 && page==1);
            navIcons[i].color(selected?Ui.MINT:Ui.MUTED);
            navPills[i].setBackground(Ui.shape(this,selected?Ui.MINT_SOFT:0x00000000,999,0));
            navigation[i].setSelected(selected);
        }
        if(moved) { View shown=current(); if(shown!=null) Ui.reveal(shown); }
        refresh();
    }
    private View current() {
        switch(page) { case 0: return home; case 1: return conversation; case 2: return terminalPage; case 3: return auth;
            case 4: return settings; case 5: return modules; case 6: return conversations; default: return null; }
    }
    private void ensureModules() {
        if(modules!=null) return;
        modules=new ModulesPage(this,((MeShellApp)getApplication()).modules(),
            () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),42),
            text -> { show(1); conversation.insertText(text); },this::saveModule);
        stage.addView(modules);
    }
    private void openModule(String id) { show(5); modules.open(id); }
    private void refresh() {
        if(home==null) return;
        home.refresh(chat,service!=null && service.connected);
        if(requestedThread>0 && chat.ready) { long id=requestedThread; requestedThread=0; chat.selectThread(id,() -> show(1)); }
        if(page==1) conversation.refresh(); if(page==6) conversations.refresh(); if(page==3) auth.refresh(); if(page==4) settings.refresh();
    }
    private View buildTerminal() {
        LinearLayout root=Ui.column(this); root.setBackgroundColor(Ui.BG);
        LinearLayout heading=Ui.row(this); Ui.pad(heading,20,10,8,6);
        LinearLayout names=Ui.column(this); names.addView(Ui.bold(this,Language.text(R.string.terminal),24,Ui.INK)); Ui.gap(names,5);
        status=Ui.text(this,Language.text(R.string.connecting),12,Ui.MUTED); status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END); names.addView(status);
        heading.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(new IconView(this,IconView.REFRESH,Language.text(R.string.reconnect_terminal),v -> { if(service!=null) service.reconnect(); }),Ui.size(this,44,44));
        heading.addView(new IconView(this,IconView.MORE,Language.text(R.string.actions_and_permissions),v -> terminalMenu()),Ui.size(this,44,44)); root.addView(heading);
        FrameLayout screen=new FrameLayout(this); screen.setBackground(Ui.shape(this,TerminalModel.DEFAULT_BG,18,Ui.BORDER));
        screen.setClipToOutline(true); Ui.pad(screen,4,6,4,6);
        terminal=new TerminalView(this); screen.addView(terminal,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,0,1); sp.setMargins(Ui.dp(this,12),Ui.dp(this,4),Ui.dp(this,12),Ui.dp(this,8)); root.addView(screen,sp);
        HorizontalScrollView bar=new HorizontalScrollView(this); bar.setHorizontalScrollBarEnabled(false);
        LinearLayout keys=Ui.row(this); Ui.pad(keys,10,0,10,0);
        ctrlKey=addKey(keys,"Ctrl",Language.text(R.string.toggle_control),v -> { control=!control; terminal.setControl(control); keycap(ctrlKey,control); });
        addKey(keys,"Esc","Escape",v -> terminal.send("\033")); addKey(keys,"Tab",Language.text(R.string.autocomplete),v -> terminal.send("\t"));
        addKey(keys,"^C",Language.text(R.string.interrupt_command),v -> terminal.send("\u0003"));
        addKey(keys,"↑",Language.text(R.string.previous_command),v -> terminal.send(terminal.arrow('A')));
        addKey(keys,"↓",Language.text(R.string.next_command),v -> terminal.send(terminal.arrow('B')));
        addKey(keys,"←",Language.text(R.string.cursor_left),v -> terminal.send(terminal.arrow('D')));
        addKey(keys,"→",Language.text(R.string.cursor_right),v -> terminal.send(terminal.arrow('C')));
        addKey(keys,"/",Language.text(R.string.slash),v -> terminal.send("/")); addKey(keys,"|",Language.text(R.string.pipe),v -> terminal.send("|"));
        addKey(keys,"~",Language.text(R.string.home_directory),v -> terminal.send("~"));
        bar.addView(keys); root.addView(bar,Ui.size(this,-1,48)); Ui.gap(root,6); return root;
    }
    private void keycap(Button key,boolean on) {
        key.setTextColor(on?Ui.BG:Ui.INK); key.setBackground(Ui.ripple(this,Ui.shape(this,on?Ui.MINT:Ui.PANEL2,12,on?0:Ui.BORDER),12));
    }
    private Button addKey(LinearLayout parent,String label,String description,View.OnClickListener listener) {
        Button key=Ui.button(this,label,false,listener); key.setTextSize(14); key.setContentDescription(description);
        key.setPadding(0,0,0,0); key.setTypeface(android.graphics.Typeface.MONOSPACE); keycap(key,false);
        LinearLayout.LayoutParams kp=Ui.size(this,label.length()>2?54:44,40); kp.rightMargin=Ui.dp(this,6); parent.addView(key,kp); return key;
    }
    private void terminalMenu() {
        new AlertDialog.Builder(this).setTitle(Language.text(R.string.terminal)).setItems(new String[]{
            Language.text(R.string.paste), Language.text(R.string.copy_screen), Language.text(R.string.discuss_screen_with_gpt), Language.text(R.string.check_permissions), Language.text(R.string.help_directories), Language.text(R.string.keyboard), Language.text(R.string.about)
        },(dialog,which) -> {
            ClipboardManager clipboard=getSystemService(ClipboardManager.class);
            switch(which) {
                case 0: ClipData clip=clipboard.getPrimaryClip();
                    if(clip!=null && clip.getItemCount()>0) terminal.paste(clip.getItemAt(0).coerceToText(this).toString()); break;
                case 1: clipboard.setPrimaryClip(ClipData.newPlainText("MeShell",terminal.visibleText())); toast(Language.text(R.string.screen_copied)); break;
                case 2: attachTerminal(); break;
                case 3: terminal.send("\u0015device-info\r"); break;
                case 4: terminal.send("\u0015help\r"); break;
                case 5: terminal.showKeyboard(); break;
                case 6: about(); break;
                default: break;
            }
        }).show();
    }
    private void saveModule(org.json.JSONObject value) {
        try {
            pendingModuleExport=value.toString(2)+"\n";
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE,value.getString("id")+".json"),44);
        } catch(Exception e) { pendingModuleExport=""; toast(e.getMessage()); }
    }
    private void attachMenu() {
        new AlertDialog.Builder(this).setTitle(Language.text(R.string.add_attachment))
            .setItems(new String[]{Language.text(R.string.photo_image),Language.text(R.string.file_pdf_text_code_csv_json),Language.text(R.string.terminal_screen)},(dialog,which) -> {
                if(which==2) attachTerminal();
                else { conversation.saveDraftNow(); pendingAttachmentThread=chat.threadId(); startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(which==0?"image/*":"*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),40); }
            }).show();
    }
    private void attachTerminal() {
        if(service==null || !service.connected) { toast(Language.text(R.string.connect_the_terminal_first)); return; }
        String screen=terminal.visibleText(); show(1); conversation.attach(Language.text(R.string.android_terminal_screen),screen);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==44) {
            String text=pendingModuleExport; pendingModuleExport="";
            if(result==RESULT_OK && data!=null && data.getData()!=null && !text.isEmpty()) {
                android.net.Uri destination=data.getData();
                new Thread(() -> {
                    try(java.io.OutputStream output=getContentResolver().openOutputStream(destination,"wt")) {
                        if(output==null) throw new java.io.IOException(Language.text(R.string.cannot_open_file));
                        output.write(text.getBytes(StandardCharsets.UTF_8));
                        runOnUiThread(() -> toast(Language.text(R.string.module_exported)));
                    } catch(Exception e) { runOnUiThread(() -> toast(e.getMessage())); }
                },"meshell-module-export").start();
            } return;
        }
        if((request<40 || request>42) || result!=RESULT_OK || data==null) return;
        if(request==40) {
            java.util.ArrayList<android.net.Uri> selected=new java.util.ArrayList<>();
            if(data.getClipData()!=null) for(int i=0;i<data.getClipData().getItemCount();i++) selected.add(data.getClipData().getItemAt(i).getUri());
            else if(data.getData()!=null) selected.add(data.getData());
            if(selected.size()>4) { toast(Language.text(R.string.select_up_to_four_attachments)); return; }
            final long thread=pendingAttachmentThread>0?pendingAttachmentThread:chat.threadId(); pendingAttachmentThread=0; toast(Language.text(R.string.preparing_attachments));
            new Thread(() -> {
                for(android.net.Uri picked:selected) try {
                    org.json.JSONObject item=Attachments.importUri(this,picked);
                    runOnUiThread(() -> {
                        boolean added=chat.addDraftAttachment(thread,item);
                        if(isFinishing() || isDestroyed()) return;
                        if(!added) toast(Language.text(R.string.cannot_attach_chat_deleted_or_four_files_already_attached));
                        else if(chat.threadId()!=thread || page!=1) toast(Language.text(R.string.attachment_saved_in_the_original_chat));
                        else conversation.refresh();
                    });
                } catch(Exception e) { runOnUiThread(() -> toast(Language.text(R.string.cannot_attach)+e.getMessage())); }
            },"meshell-import").start(); return;
        }
        if(data.getData()==null) return;
        android.net.Uri uri=data.getData();
        new Thread(() -> {
            try {
                String name=Language.text(R.string.text_file);
                try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) {
                    if(cursor!=null && cursor.moveToFirst()) name=cursor.getString(0);
                }
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=getContentResolver().openInputStream(uri)) {
                    if(in==null) throw new java.io.IOException(Language.text(R.string.cannot_open_file));
                    byte[] buffer=new byte[4096]; int n;
                    while((n=in.read(buffer))!=-1) {
                        if(bytes.size()+n>(request==41?256000:request==42?((MeShellApp)getApplication()).modules().importLimitBytes():48000)) throw new java.io.IOException(Language.text(R.string.file_exceeds_the_size_limit));
                        bytes.write(buffer,0,n);
                    }
                }
                String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                if(text.indexOf('\0')>=0) throw new java.io.IOException(Language.text(R.string.not_a_utf_8_text_file));
                final String title=name;
                runOnUiThread(() -> { if(!isFinishing() && !isDestroyed()) {
                    if(request==42) { show(5); modules.importModule(text); }
                    else if(request==41) { show(4); String safe=title.replaceAll("[^A-Za-z0-9_.-]","_"); settings.imported(safe.substring(0,Math.min(64,safe.length())),text); }
                    else { show(1); conversation.attach(title,text); }
                } });
            } catch(Exception e) { runOnUiThread(() -> toast(Language.text(R.string.cannot_attach_file)+e.getMessage())); }
        },"meshell-attachment").start();
    }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private void about() {
        String version="";
        try { version=" · "+getPackageManager().getPackageInfo(getPackageName(),0).versionName; } catch(PackageManager.NameNotFoundException ignored) { }
        new AlertDialog.Builder(this).setTitle("MeShell"+version)
            .setMessage("ADB shell · uid 2000\n"+ShellBroker.SOURCE)
            .setPositiveButton(Language.text(R.string.ok),null).show();
    }
    @Override public void onBackPressed() { if(page==5 && modules!=null && modules.back()) return; if(page==1) show(6); else if(page!=0) show(0); else super.onBackPressed(); }
    @Override protected void onResume() { super.onResume(); if(chat!=null) chat.refreshLimits(); if(terminal!=null) terminal.refreshed(); if(modules!=null) modules.active(page==5); if(conversation!=null) conversation.active(page==1); if(conversations!=null) conversations.active(page==6); refresh(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); requestedThread=intent.getLongExtra("open-thread",0); if(intent.getBooleanExtra("open-chat",false)) show(1); refresh(); }
    @Override protected void onPause() { if(modules!=null) modules.active(false); if(conversation!=null) conversation.active(false); if(conversations!=null) conversations.active(false); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("page",page); state.putLong("attachment-thread",pendingAttachmentThread); state.putString("module-export",pendingModuleExport); conversation.saveDraftNow(); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() {
        if(chat!=null) chat.observe(null); if(service!=null) service.observe(null);
        if(modules!=null) modules.active(false);
        if(bound) unbindService(binding); super.onDestroy();
    }
}
