package com.mesystem.shell;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.Locale;

final class LanguageTests {
    static Resources load(Context base,String apk,String code) throws Exception {
        AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();
        int cookie=(Integer)AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,apk);
        if(cookie==0) throw new AssertionError("test resources not loaded");
        Configuration config=new Configuration(base.getResources().getConfiguration()); config.setLocale(Locale.forLanguageTag(code));
        return new Resources(assets,base.getResources().getDisplayMetrics(),config);
    }
    static int run(Context base,String apk) throws Exception {
        Language.initialize(load(base,apk,"en"));
        if(!Language.text(R.string.settings).equals("Settings")) throw new AssertionError("English settings");
        if(!Language.chats(2).equals("chats")) throw new AssertionError("English plural");
        Language.initialize(load(base,apk,"ru"));
        if(!Language.text(R.string.settings).equals("Настройки")) throw new AssertionError("Russian settings");
        if(!Language.chats(2).equals("чата")) throw new AssertionError("Russian plural");
        System.out.println("LANGUAGE_TESTS_OK 4"); return 4;
    }
}
