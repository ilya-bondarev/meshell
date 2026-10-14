package com.mesystem.shell;

import android.app.Application;

public final class MeShellApp extends Application {
    @Override public void onCreate() { super.onCreate(); Language.initialize(Language.wrap(this).getResources()); }
    private ChatEngine chat;
    private SecretVault vault;
    private ModuleHub modules;
    synchronized ModuleHub modules() { if(modules==null) modules=new ModuleHub(this); return modules; }
    synchronized SecretVault vault() { if(vault==null) vault=new SecretVault(this); return vault; }
    synchronized ChatEngine chat() { if(chat==null) chat=new ChatEngine(this); return chat; }
}
