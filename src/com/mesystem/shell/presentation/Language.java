package com.mesystem.shell;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.Locale;

final class Language {
    private static volatile Resources resources;
    static Context wrap(Context context) {
        String code=context.getSharedPreferences("appearance",Context.MODE_PRIVATE).getString("language","en");
        Configuration config=new Configuration(context.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(code));
        return context.createConfigurationContext(config);
    }
    static void initialize(Resources value) { resources=value; }
    static String text(int id) { return resources.getString(id); }
    static Locale locale() { return resources.getConfiguration().getLocales().get(0); }
    static String retryLabel(ChatStore.Message message) {
        switch(RetryPolicy.intent(message)) {
            case "continue": return text(R.string.check_and_continue);
            case "regenerate": return text(R.string.regenerate_response);
            default: return text(R.string.retry);
        }
    }
    static String chats(int count) { return resources.getQuantityString(R.plurals.chat_count,count); }
    /** "3 modules": the number followed by its plural noun. */
    static String quantity(int plural,int count) { return count+" "+resources.getQuantityString(plural,count); }
    static void select(Context context,String code) {
        if(!code.equals("en") && !code.equals("ru")) throw new IllegalArgumentException(code);
        context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putString("language",code).apply();
        initialize(wrap(context).getResources());
    }
}
