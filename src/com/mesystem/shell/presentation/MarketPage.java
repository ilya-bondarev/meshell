package com.mesystem.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import services.market.MarketRules;

/** Catalogue screens inside the Modules page: browsing, details, consent, ratings, publishing and rules. */
final class MarketPage {
    static final String[] CATEGORIES={"system","network","files","developer","productivity","media","fun"};
    private static final int[] CATEGORY_LABELS={R.string.category_system,R.string.category_network,R.string.category_files,R.string.category_developer,
        R.string.category_productivity,R.string.category_media,R.string.category_fun};
    private static final String[] ICONS={"terminal","cpu","wifi","folder","notes","chart","image","play","key","branch","phone","sensor","star","fox","search","package","battery","sun","screen","shield","grid"};
    private static final int[] GLYPHS={IconView.TERMINAL,IconView.CPU,IconView.WIFI,IconView.FOLDER,IconView.NOTES,IconView.CHART,IconView.IMAGE,IconView.PLAY,IconView.KEY,
        IconView.BRANCH,IconView.PHONE,IconView.SENSOR,IconView.STAR,IconView.FOX,IconView.SEARCH,IconView.PACKAGE,IconView.BATTERY,IconView.SUN,IconView.SCREEN,IconView.SHIELD,IconView.GRID};
    private final ModulesPage page;
    private final Activity activity;
    private final ModuleHub hub;
    private final Marketplace market;
    private String query="",category="";
    private MarketRules.Order order=MarketRules.Order.POPULAR;
    private LinearLayout results,categories;
    private TextView status;
    private volatile boolean refreshing;

    MarketPage(ModulesPage page) { this.page=page; activity=page.activity; hub=page.hub; market=hub.market; }

    // Shared pieces for module tiles, cards and dialogs.
    static int glyph(JSONObject module) {
        Object icon=module.opt("icon");
        if(icon instanceof Number) return ((Number)icon).intValue();
        String name=module.optString("icon_name",icon instanceof String?(String)icon:"");
        for(int i=0;i<ICONS.length;i++) if(ICONS[i].equals(name)) return GLYPHS[i];
        switch(module.optString("category")) {
            case "system": return IconView.CPU; case "network": return IconView.WIFI; case "files": return IconView.FOLDER;
            case "developer": return IconView.BRANCH; case "productivity": return IconView.NOTES; case "media": return IconView.IMAGE; case "fun": return IconView.FOX;
            default: return IconView.TERMINAL;
        }
    }
    static int tint(JSONObject module) {
        switch(module.optString("category")) {
            case "network": case "developer": return Ui.LAVENDER; case "files": case "media": return Ui.WARN; case "fun": return Ui.DANGER; default: return Ui.MINT;
        }
    }
    static String category(String key) {
        for(int i=0;i<CATEGORIES.length;i++) if(CATEGORIES[i].equals(key)) return Language.text(CATEGORY_LABELS[i]);
        return key;
    }
    private static int permissionIcon(String permission) {
        switch(permission) {
            case "network": return IconView.WIFI; case "storage": return IconView.FOLDER; case "screen": return IconView.SCREEN; case "system": return IconView.SETTINGS;
            case "secrets": return IconView.KEY; case "packages": return IconView.PACKAGE; case "overlay": return IconView.FOX; default: return IconView.SHIELD;
        }
    }
    static String permission(String permission) {
        switch(permission) {
            case "network": return Language.text(R.string.permission_network); case "storage": return Language.text(R.string.permission_storage);
            case "screen": return Language.text(R.string.permission_screen); case "system": return Language.text(R.string.permission_system);
            case "secrets": return Language.text(R.string.permission_secrets); case "packages": return Language.text(R.string.permission_packages);
            case "overlay": return Language.text(R.string.permission_overlay); default: return permission;
        }
    }
    private static String permissionNote(String permission) {
        switch(permission) {
            case "network": return Language.text(R.string.permission_network_note); case "storage": return Language.text(R.string.permission_storage_note);
            case "screen": return Language.text(R.string.permission_screen_note); case "system": return Language.text(R.string.permission_system_note);
            case "secrets": return Language.text(R.string.permission_secrets_note); case "packages": return Language.text(R.string.permission_packages_note);
            case "overlay": return Language.text(R.string.permission_overlay_note); default: return "";
        }
    }
    private static boolean sensitive(String permission) { return permission.equals("system") || permission.equals("secrets") || permission.equals("screen"); }
    static String permissions(JSONArray values) {
        if(values==null || values.length()==0) return Language.text(R.string.no_special_permissions);
        List<String> out=new ArrayList<>(); for(int i=0;i<values.length();i++) out.add(permission(values.optString(i)));
        return String.join(", ",out);
    }
    void permissionRows(LinearLayout parent,JSONArray permissions,JSONArray packages) {
        if(permissions==null || permissions.length()==0) { permissionRow(parent,IconView.SHIELD,Ui.MINT,Language.text(R.string.no_special_permissions),Language.text(R.string.no_special_permissions_note)); return; }
        for(int i=0;i<permissions.length();i++) {
            String name=permissions.optString(i),note=permissionNote(name);
            if(name.equals("packages") && packages!=null && packages.length()>0) note=Language.text(R.string.permission_packages_list)+join(packages);
            permissionRow(parent,permissionIcon(name),sensitive(name)?Ui.WARN:name.equals("packages") || name.equals("overlay")?Ui.MINT:Ui.LAVENDER,permission(name),note);
        }
    }
    private void permissionRow(LinearLayout parent,int icon,int color,String heading,String note) {
        LinearLayout row=Ui.row(activity); row.setGravity(Gravity.TOP);
        row.addView(Ui.badge(activity,icon,color,18),Ui.size(activity,34,34));
        LinearLayout texts=Ui.column(activity); Ui.pad(texts,12,1,0,0);
        texts.addView(Ui.bold(activity,heading,14,Ui.INK)); Ui.gap(texts,3);
        TextView text=Ui.text(activity,note,12,Ui.MUTED); text.setLineSpacing(Ui.dp(activity,2),1); texts.addView(text);
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams lp=Ui.size(activity,-1,-2); lp.bottomMargin=Ui.dp(activity,12); parent.addView(row,lp);
    }
    private static String join(JSONArray values) {
        List<String> out=new ArrayList<>(); for(int i=0;i<values.length();i++) out.add(values.optString(i)); return String.join(", ",out);
    }
    private String count(long value) {
        return android.icu.text.CompactDecimalFormat.getInstance(Language.locale(),android.icu.text.CompactDecimalFormat.CompactStyle.SHORT).format(value);
    }
    private String size(long bytes) { return android.text.format.Formatter.formatShortFileSize(activity,bytes); }
    private static String score(double value) { return String.format(Language.locale(),"%.1f",value); }
    private static String day(String iso) {
        try { return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM,Language.locale()).format(new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).parse(iso.substring(0,10))); }
        catch(Exception e) { return iso; }
    }
    private static String stars(int value) { StringBuilder out=new StringBuilder(); for(int i=1;i<=5;i++) out.append(i<=value?'★':'☆'); return out.toString(); }
    /** "★ 4.6 (23) · ↓ 1.2K · 2 KB" with the star in the rating colour. */
    private CharSequence numbers(JSONObject tool) {
        SpannableStringBuilder out=new SpannableStringBuilder();
        int votes=tool.optInt("votes");
        out.append("★"); out.setSpan(new ForegroundColorSpan(Ui.WARN),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append(votes==0?" "+Language.text(R.string.no_ratings):" "+score(tool.optDouble("rating"))+" ("+count(votes)+")");
        out.append("  ·  ↓ ").append(count(tool.optLong("downloads"))).append("  ·  ").append(size(tool.optLong("size")));
        return out;
    }
    private String author(JSONObject tool) {
        String login=tool.optString("github"),name=tool.optString("author");
        return login.isEmpty()?name:name.isEmpty() || name.equalsIgnoreCase(login)?"@"+login:name+" · @"+login;
    }
    private TextView chip(LinearLayout parent,String label,boolean on,Runnable run) {
        TextView chip=Ui.chip(activity,label,on?Ui.BG:Ui.MUTED,0); chip.setTextSize(12); Ui.pad(chip,14,8,14,8);
        Ui.pressable(chip,on?Ui.MINT:Ui.PANEL2,999,on?0:Ui.BORDER); chip.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams lp=Ui.size(activity,-2,-2); lp.rightMargin=Ui.dp(activity,8); parent.addView(chip,lp); return chip;
    }
    private TextView section(LinearLayout parent,String label) { Ui.gap(parent,18); TextView v=Ui.section(activity,label); parent.addView(v); return v; }

    // Catalogue list.
    void store(LinearLayout body) {
        EditText search=new EditText(activity); search.setTextColor(Ui.INK); search.setTextSize(15); search.setHint(Language.text(R.string.search_modules));
        search.setInputType(InputType.TYPE_CLASS_TEXT); search.setText(query); search.setSelection(query.length());
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int b,int c) { }
            public void onTextChanged(CharSequence s,int a,int b,int c) { query=s.toString(); results(); }
            public void afterTextChanged(Editable e) { }
        });
        body.addView(Ui.searchBox(activity,search),Ui.size(activity,-1,48)); Ui.gap(body,10);
        MarketRules.Order[] orders={MarketRules.Order.POPULAR,MarketRules.Order.TOP,MarketRules.Order.NEW};
        int[] labels={R.string.sort_popular,R.string.sort_top,R.string.sort_new}; Button[] buttons=new Button[orders.length];
        for(int i=0;i<orders.length;i++) { MarketRules.Order value=orders[i]; buttons[i]=Ui.button(activity,Language.text(labels[i]),false,null); buttons[i].setTextSize(13);
            buttons[i].setOnClickListener(v -> { order=value; for(int n=0;n<orders.length;n++) Ui.selected(buttons[n],orders[n]==order); results(); }); }
        body.addView(Ui.tabs(activity,buttons),Ui.size(activity,-1,42)); for(int n=0;n<orders.length;n++) Ui.selected(buttons[n],orders[n]==order); Ui.gap(body,10);
        HorizontalScrollView scroll=new HorizontalScrollView(activity); scroll.setHorizontalScrollBarEnabled(false); categories=Ui.row(activity); scroll.addView(categories);
        body.addView(scroll,Ui.size(activity,-1,-2)); Ui.gap(body,12);
        status=Ui.caption(activity,""); body.addView(status); Ui.gap(body,10);
        results=Ui.column(activity); body.addView(results,Ui.size(activity,-1,-2));
        results();
        if(System.currentTimeMillis()-market.fetched()>Marketplace.STALE) refresh();
    }
    void refresh() {
        if(refreshing || status==null) return; refreshing=true; TextView line=status; String route=page.route();
        page.job(line,c -> {
            try { market.refresh(c); return new JSONObject(); }
            catch(IOException e) { return new JSONObject().put("error",e.getMessage()==null?e.toString():e.getMessage()); }
            finally { refreshing=false; }
        },result -> {
            if(!result.has("error")) { if(route.equals(ModulesPage.STORE)) results(); else page.open(route); }
            else { line.setText(market.cached()==null?Language.text(R.string.catalog_unavailable)+": "+result.optString("error"):Language.text(R.string.catalog_offline)); if(market.cached()==null) results(); }
        });
    }
    private void results() {
        if(results==null) return; results.removeAllViews(); categories.removeAllViews();
        JSONArray tools; try { tools=market.tools(); } catch(Exception e) { status.setText(e.getMessage()); return; }
        if(tools.length()==0) {
            if(!refreshing) { empty(Language.text(R.string.catalog_not_loaded),Language.text(R.string.retry),this::refresh); }
            return;
        }
        Set<String> present=new LinkedHashSet<>(); for(int i=0;i<tools.length();i++) present.add(tools.optJSONObject(i).optString("category"));
        chip(categories,Language.text(R.string.category_all),category.isEmpty(),() -> { category=""; results(); });
        for(String key:CATEGORIES) if(present.contains(key)) chip(categories,category(key),key.equals(category),() -> { category=key.equals(category)?"":key; results(); });
        page.subtitle(Language.text(R.string.catalog_subtitle));
        List<Integer> shown;
        try { shown=MarketRules.arrange(Marketplace.listings(tools),query,category,order); } catch(Exception e) { status.setText(e.getMessage()); return; }
        boolean filtered=!query.trim().isEmpty() || !category.isEmpty();
        if(!refreshing) status.setText((filtered?shown.size()+" / ":"")+Language.quantity(R.plurals.module_count,tools.length())+" · "+Language.text(R.string.catalog_updated)+time(market.fetched()));
        if(shown.isEmpty()) empty(Language.text(R.string.nothing_found),Language.text(R.string.request_module),() -> page.browse(Marketplace.requestLink(query)));
        for(int index:shown) { LinearLayout.LayoutParams lp=Ui.size(activity,-1,-2); lp.bottomMargin=Ui.dp(activity,10); results.addView(listing(tools.optJSONObject(index)),lp); }
        if(!filtered) wanted(results);
        invite(results);
    }
    private static String time(long value) {
        Date date=new Date(value); boolean today=System.currentTimeMillis()-value<20*3600*1000L;
        return (today?java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT,Language.locale()):java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT,Language.locale())).format(date);
    }
    private void empty(String text,String action,Runnable run) {
        LinearLayout box=Ui.card(activity); box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(Ui.badge(activity,IconView.SEARCH,Ui.LAVENDER,22),Ui.size(activity,46,46)); Ui.gap(box,12);
        TextView label=Ui.text(activity,text,14,Ui.INK); label.setGravity(Gravity.CENTER); box.addView(label); Ui.gap(box,12);
        box.addView(Ui.button(activity,action,false,v -> run.run()),Ui.size(activity,-2,42));
        LinearLayout.LayoutParams lp=Ui.size(activity,-1,-2); lp.bottomMargin=Ui.dp(activity,10); results.addView(box,lp);
    }
    /** Requests nobody has built yet: one tap hands the request to GPT, the other adds a vote on GitHub. */
    private void wanted(LinearLayout parent) {
        JSONArray requests=market.requests(); if(requests.length()==0) return;
        Ui.gap(parent,6); parent.addView(Ui.section(activity,Language.text(R.string.wanted_title)));
        TextView note=Ui.text(activity,Language.text(R.string.wanted_note),12,Ui.MUTED); Ui.pad(note,4,0,4,8); parent.addView(note);
        for(int i=0;i<Math.min(5,requests.length());i++) {
            JSONObject request=requests.optJSONObject(i); if(request==null) continue; String title=request.optString("title"),url=request.optString("url");
            LinearLayout row=Ui.row(activity); row.setGravity(Gravity.CENTER_VERTICAL); Ui.pad(row,14,10,10,10); row.setBackground(Ui.shape(activity,Ui.PANEL,18,Ui.BORDER));
            LinearLayout texts=Ui.column(activity);
            TextView name=Ui.bold(activity,title,14,Ui.INK); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); texts.addView(name); Ui.gap(texts,3);
            TextView vote=Ui.bold(activity,"👍 "+request.optInt("votes")+" · "+Language.text(R.string.want_it_too),12,Ui.MINT); texts.addView(vote);
            vote.setOnClickListener(v -> page.browse(url));
            row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
            Button build=Ui.button(activity,Language.text(R.string.build_it),true,v -> page.toChat.accept(Language.text(R.string.build_requested_module)+" "+title));
            build.setTextSize(13); Ui.pad(build,14,0,14,0); row.addView(build,Ui.size(activity,-2,36));
            LinearLayout.LayoutParams lp=Ui.size(activity,-1,-2); lp.bottomMargin=Ui.dp(activity,8); parent.addView(row,lp);
        }
    }
    /** The publish invitation that ends the list: every reader is a potential author. */
    void invite(LinearLayout parent) {
        LinearLayout box=Ui.column(activity); box.setBackground(Ui.shape(activity,Ui.LAVENDER_SOFT,22,0)); Ui.pad(box,18,16,18,16);
        LinearLayout top=Ui.row(activity); top.addView(Ui.badge(activity,IconView.STORE,Ui.LAVENDER,20),Ui.size(activity,40,40));
        LinearLayout texts=Ui.column(activity); Ui.pad(texts,12,0,0,0); texts.addView(Ui.bold(activity,Language.text(R.string.make_your_module),15,Ui.INK)); Ui.gap(texts,4);
        TextView note=Ui.text(activity,Language.text(R.string.make_your_module_note),12,Ui.MUTED); note.setLineSpacing(Ui.dp(activity,2),1); texts.addView(note);
        top.addView(texts,new LinearLayout.LayoutParams(0,-2,1)); box.addView(top); Ui.gap(box,14);
        LinearLayout buttons=Ui.row(activity);
        buttons.addView(Ui.button(activity,Language.text(R.string.create_with_gpt),true,v -> page.toChat.accept(Language.text(R.string.create_a_meshell_module_for_me_ask_about_its_purpose_first_t))),new LinearLayout.LayoutParams(0,Ui.dp(activity,44),1));
        View spacer=new View(activity); buttons.addView(spacer,Ui.size(activity,10,1));
        buttons.addView(Ui.button(activity,Language.text(R.string.how_to_publish),false,v -> page.open(ModulesPage.RULES)),new LinearLayout.LayoutParams(0,Ui.dp(activity,44),1));
        box.addView(buttons); Ui.gap(parent,4); parent.addView(box,Ui.size(activity,-1,-2));
    }
    private View listing(JSONObject tool) {
        String id=tool.optString("id");
        LinearLayout card=Ui.column(activity); Ui.pad(card,14,14,12,14); Ui.pressable(card,Ui.PANEL,20,Ui.BORDER);
        card.setOnClickListener(v -> page.open(ModulesPage.ITEM+id)); card.setContentDescription(tool.optString("title"));
        LinearLayout top=Ui.row(activity); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.badge(activity,glyph(tool),tint(tool),22),Ui.size(activity,46,46));
        LinearLayout names=Ui.column(activity); Ui.pad(names,12,0,8,0);
        TextView name=Ui.bold(activity,tool.optString("title"),15,Ui.INK); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); names.addView(name); Ui.gap(names,3);
        String login=tool.optString("github");
        TextView by=Ui.text(activity,(login.isEmpty()?tool.optString("author"):"@"+login)+" · "+category(tool.optString("category")),12,Ui.MUTED);
        by.setSingleLine(true); by.setEllipsize(android.text.TextUtils.TruncateAt.END); names.addView(by);
        top.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        boolean installed=tool.optBoolean("installed"),update=Marketplace.update(tool);
        Button act=Ui.button(activity,Language.text(update?R.string.update:installed?R.string.open:R.string.get),!installed || update,v -> {
            if(installed && !update) page.open(id); else install(tool,status);
        });
        act.setTextSize(13); Ui.pad(act,14,0,14,0); act.setEnabled(market.supported(tool)); top.addView(act,Ui.size(activity,-2,36));
        card.addView(top);
        // Figures, description and permissions use the full width under the title, aligned with it.
        LinearLayout texts=Ui.column(activity); Ui.pad(texts,58,10,0,0);
        TextView figures=Ui.text(activity,"",12,Ui.INK); figures.setText(numbers(tool)); figures.setSingleLine(true); figures.setEllipsize(android.text.TextUtils.TruncateAt.END); texts.addView(figures); Ui.gap(texts,6);
        TextView about=Ui.text(activity,tool.optString("description"),13,Ui.MUTED); about.setMaxLines(2); about.setEllipsize(android.text.TextUtils.TruncateAt.END); about.setLineSpacing(Ui.dp(activity,2),1); texts.addView(about); Ui.gap(texts,6);
        JSONArray needs=tool.optJSONArray("permissions"); boolean none=needs==null || needs.length()==0;
        TextView rights=Ui.text(activity,permissions(needs),11,none?Ui.MINT:Ui.FAINT); rights.setSingleLine(true); rights.setEllipsize(android.text.TextUtils.TruncateAt.END); texts.addView(rights);
        card.addView(texts);
        return card;
    }

    // Details.
    void detail(LinearLayout body,String id) {
        JSONObject tool; try { tool=market.tool(id); } catch(Exception e) { tool=null; }
        if(tool==null) { body.addView(Ui.caption(activity,Language.text(R.string.not_in_catalog))); return; }
        JSONObject item=tool; page.heading(Language.text(R.string.catalog),category(item.optString("category")));
        LinearLayout hero=Ui.row(activity); hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(Ui.badge(activity,glyph(item),tint(item),30),Ui.size(activity,64,64));
        LinearLayout names=Ui.column(activity); Ui.pad(names,14,0,0,0);
        TextView name=Ui.bold(activity,item.optString("title"),20,Ui.INK); name.setMaxLines(2); names.addView(name); Ui.gap(names,5);
        TextView by=Ui.bold(activity,author(item),13,Ui.MINT);
        if(!item.optString("github").isEmpty()) { by.setOnClickListener(v -> page.browse(Marketplace.profileLink(item.optString("github")))); by.setContentDescription(Language.text(R.string.author_profile)); }
        names.addView(by); Ui.gap(names,5);
        names.addView(Ui.text(activity,"v"+item.optString("version")+" · "+category(item.optString("category")),12,Ui.MUTED));
        hero.addView(names,new LinearLayout.LayoutParams(0,-2,1)); body.addView(hero); Ui.gap(body,16);
        LinearLayout figures=Ui.row(activity); figures.setBackground(Ui.shape(activity,Ui.PANEL,20,Ui.BORDER)); Ui.pad(figures,4,12,4,12);
        int votes=item.optInt("votes");
        figure(figures,votes==0?"—":"★ "+score(item.optDouble("rating")),votes==0?Language.text(R.string.no_ratings):Language.quantity(R.plurals.rating_count,votes));
        figure(figures,count(item.optLong("downloads")),Language.text(R.string.downloads));
        String weight=size(item.optLong("size"));
        if(item.optLong("packages_size")>0) weight+=" + "+size(item.optLong("packages_size"));
        figure(figures,weight,Language.text(item.optLong("packages_size")>0?R.string.size_with_packages:R.string.size));
        body.addView(figures,Ui.size(activity,-1,-2)); Ui.gap(body,14);
        TextView line=Ui.caption(activity,"");
        boolean installed=item.optBoolean("installed"),update=Marketplace.update(item);
        if(!market.supported(item)) { Button need=Ui.button(activity,Language.text(R.string.needs_meshell)+item.optString("min_app_version"),false,null); need.setEnabled(false); body.addView(need,Ui.size(activity,-1,50)); }
        else if(!installed || update) { Button get=Ui.button(activity,Language.text(update?R.string.update_to:R.string.install_size)+(update?item.optString("version"):size(item.optLong("size"))),true,v -> install(item,line)); body.addView(get,Ui.size(activity,-1,50)); }
        if(installed) { Ui.gap(body,update?10:0); body.addView(Ui.button(activity,Language.text(R.string.open),!update,v -> page.open(id)),Ui.size(activity,-1,50)); }
        Ui.gap(body,8); body.addView(line); Ui.gap(body,10);
        TextView about=Ui.text(activity,item.optString("description"),15,Ui.INK); about.setLineSpacing(Ui.dp(activity,4),1); body.addView(about);
        String readme=item.optString("readme").trim();
        if(!readme.isEmpty() && !readme.equals(item.optString("description").trim())) { LinearLayout text=Ui.column(activity); Ui.gap(body,10); body.addView(text); MarkdownView.update(text,readme,() -> page.toast(Language.text(R.string.copied)),false); }
        section(body,Language.text(R.string.permissions));
        permissionRows(body,item.optJSONArray("permissions"),item.optJSONArray("packages"));
        JSONArray actions=item.optJSONArray("actions");
        if(actions!=null && actions.length()>0) {
            section(body,Language.text(R.string.what_it_does));
            for(int i=0;i<actions.length();i++) { JSONObject action=actions.optJSONObject(i);
                LinearLayout row=Ui.row(activity); IconView play=new IconView(activity,IconView.PLAY,null,null); play.color(Ui.MINT); play.glyphSize(14); row.addView(play,Ui.size(activity,24,28));
                row.addView(Ui.text(activity,action.optString("label",action.optString("id")),14,Ui.INK),new LinearLayout.LayoutParams(0,-2,1)); body.addView(row); }
            Ui.gap(body,8);
            TextView commands=Ui.bold(activity,Language.text(R.string.show_commands),13,Ui.MINT); Ui.pad(commands,0,8,0,8);
            commands.setOnClickListener(v -> page.job(line,c -> market.definition(item,false,c),definition -> page.preview(Language.text(R.string.show_commands),definition)));
            body.addView(commands);
        }
        ratings(body,item,installed);
        section(body,Language.text(R.string.about_module));
        fact(body,Language.text(R.string.version),item.optString("version"));
        if(!item.optString("updated").isEmpty()) fact(body,Language.text(R.string.entry_updated),day(item.optString("updated")));
        fact(body,Language.text(R.string.license),item.optString("license"));
        fact(body,Language.text(R.string.minimum_meshell),item.optString("min_app_version"));
        JSONArray requirements=item.optJSONArray("requirements"); if(requirements!=null && requirements.length()>0) fact(body,Language.text(R.string.requirements),join(requirements));
        Ui.gap(body,10);
        body.addView(Ui.button(activity,Language.text(R.string.share),false,v -> share(item)),Ui.size(activity,-1,44)); Ui.gap(body,10);
        LinearLayout links=Ui.row(activity);
        links.addView(Ui.button(activity,Language.text(R.string.source_code),false,v -> { try { page.browse(Marketplace.sourceLink(item)); } catch(Exception ignored) { } }),new LinearLayout.LayoutParams(0,Ui.dp(activity,44),1));
        links.addView(new View(activity),Ui.size(activity,10,1));
        links.addView(Ui.button(activity,Language.text(R.string.report_problem),false,v -> { try { page.browse(Marketplace.reportLink(item)); } catch(Exception ignored) { } }),new LinearLayout.LayoutParams(0,Ui.dp(activity,44),1));
        body.addView(links);
    }
    private void figure(LinearLayout parent,String value,String label) {
        LinearLayout cell=Ui.column(activity); cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView big=Ui.bold(activity,value,16,Ui.INK); big.setSingleLine(true); big.setGravity(Gravity.CENTER); cell.addView(big); Ui.gap(cell,4);
        TextView small=Ui.text(activity,label,11,Ui.MUTED); small.setGravity(Gravity.CENTER); small.setMaxLines(2); cell.addView(small);
        parent.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
    }
    private void fact(LinearLayout parent,String label,String value) {
        if(value.isEmpty()) return;
        LinearLayout row=Ui.row(activity); Ui.pad(row,0,6,0,6);
        row.addView(Ui.text(activity,label,13,Ui.MUTED),new LinearLayout.LayoutParams(0,-2,1));
        TextView text=Ui.text(activity,value,13,Ui.INK); text.setGravity(Gravity.END); row.addView(text,new LinearLayout.LayoutParams(0,-2,1.4f)); parent.addView(row);
    }
    private void ratings(LinearLayout body,JSONObject tool,boolean installed) {
        section(body,Language.text(R.string.ratings_and_reviews));
        LinearLayout summary=Ui.row(activity); summary.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left=Ui.column(activity); left.setGravity(Gravity.CENTER_HORIZONTAL); int votes=tool.optInt("votes");
        left.addView(Ui.bold(activity,votes==0?"—":score(tool.optDouble("rating")),34,Ui.INK)); Ui.gap(left,4);
        left.addView(Ui.text(activity,stars((int)Math.round(tool.optDouble("rating"))),13,Ui.WARN)); Ui.gap(left,4);
        left.addView(Ui.text(activity,votes==0?Language.text(R.string.no_ratings):Language.quantity(R.plurals.rating_count,votes),11,Ui.MUTED));
        summary.addView(left,Ui.size(activity,96,-2));
        LinearLayout bars=Ui.column(activity); Ui.pad(bars,12,0,0,0); JSONArray histogram=tool.optJSONArray("histogram"); int most=1;
        for(int i=0;histogram!=null && i<histogram.length();i++) most=Math.max(most,histogram.optInt(i));
        for(int stars=5;stars>=1;stars--) {
            int value=histogram==null?0:histogram.optInt(stars-1);
            LinearLayout row=Ui.row(activity); row.addView(Ui.text(activity,Integer.toString(stars),11,Ui.MUTED),Ui.size(activity,14,-2));
            LinearLayout track=Ui.row(activity); track.setBackground(Ui.shape(activity,Ui.PANEL2,99,0));
            View fill=new View(activity); fill.setBackground(Ui.shape(activity,Ui.WARN,99,0));
            track.addView(fill,new LinearLayout.LayoutParams(0,-1,value)); track.addView(new View(activity),new LinearLayout.LayoutParams(0,-1,most-value));
            row.addView(track,new LinearLayout.LayoutParams(0,Ui.dp(activity,6),1));
            LinearLayout.LayoutParams lp=Ui.size(activity,-1,-2); lp.bottomMargin=Ui.dp(activity,5); bars.addView(row,lp);
        }
        summary.addView(bars,new LinearLayout.LayoutParams(0,-2,1)); body.addView(summary); Ui.gap(body,14);
        Button rate=Ui.button(activity,Language.text(installed?R.string.rate_module:R.string.install_to_rate),installed,v -> rate(tool));
        rate.setEnabled(installed); body.addView(rate,Ui.size(activity,-1,46)); Ui.gap(body,12);
        JSONArray reviews=tool.optJSONArray("reviews");
        for(int i=0;reviews!=null && i<reviews.length();i++) review(body,reviews.optJSONObject(i));
        if(votes>0) { TextView all=Ui.bold(activity,Language.text(R.string.all_reviews),13,Ui.MINT); Ui.pad(all,0,6,0,6);
            all.setOnClickListener(v -> { try { page.browse(Marketplace.reviewsLink(tool)); } catch(Exception ignored) { } }); body.addView(all); }
    }
    private void share(JSONObject tool) {
        try {
            String text=tool.optString("title")+" — "+tool.optString("description")+"\n"+Marketplace.shareLink(tool);
            activity.startActivity(android.content.Intent.createChooser(new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,text),tool.optString("title")));
        } catch(Exception e) { page.toast(e.getMessage()); }
    }
    private void review(LinearLayout parent,JSONObject review) {
        if(review==null) return;
        LinearLayout box=Ui.card(activity);
        LinearLayout top=Ui.row(activity);
        top.addView(Ui.bold(activity,"@"+review.optString("login"),13,Ui.INK),new LinearLayout.LayoutParams(0,-2,1));
        top.addView(Ui.text(activity,stars(review.optInt("rating")),12,Ui.WARN)); box.addView(top); Ui.gap(box,4);
        box.addView(Ui.text(activity,day(review.optString("date"))+(review.optString("version").isEmpty()?"":" · v"+review.optString("version")),11,Ui.FAINT));
        if(!review.optString("text").isEmpty()) { Ui.gap(box,8); TextView text=Ui.text(activity,review.optString("text"),13,Ui.INK); text.setLineSpacing(Ui.dp(activity,3),1); box.addView(text); }
        LinearLayout.LayoutParams lp=Ui.size(activity,-1,-2); lp.bottomMargin=Ui.dp(activity,10); parent.addView(box,lp);
    }

    // Flows.
    /** Shows what the module asks for; nothing is downloaded or saved until the user accepts. */
    void consent(String heading,String detail,int icon,int color,JSONArray permissions,JSONArray packages,String accept,Runnable accepted,String neutral,Runnable other) {
        LinearLayout view=Ui.column(activity); Ui.pad(view,22,20,22,8);
        LinearLayout top=Ui.row(activity); top.addView(Ui.badge(activity,icon,color,22),Ui.size(activity,46,46));
        LinearLayout names=Ui.column(activity); Ui.pad(names,12,0,0,0);
        TextView title=Ui.bold(activity,heading,17,Ui.INK); title.setMaxLines(2); names.addView(title); Ui.gap(names,4);
        names.addView(Ui.text(activity,detail,12,Ui.MUTED)); top.addView(names,new LinearLayout.LayoutParams(0,-2,1)); view.addView(top); Ui.gap(view,18);
        boolean any=permissions!=null && permissions.length()>0;
        if(any) { view.addView(Ui.section(activity,Language.text(R.string.module_asks_for))); Ui.gap(view,4); }
        permissionRows(view,permissions,packages); Ui.gap(view,4);
        TextView note=Ui.caption(activity,Language.text(R.string.module_trust_note)); view.addView(note);
        ScrollView scroll=new ScrollView(activity); scroll.addView(view);
        AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setView(scroll).setNegativeButton(Language.text(R.string.cancel),null)
            .setPositiveButton(accept,(d,w) -> accepted.run());
        if(neutral!=null) dialog.setNeutralButton(neutral,(d,w) -> other.run());
        dialog.show();
    }
    void install(JSONObject tool,TextView line) {
        String detail=author(tool)+" · "+size(tool.optLong("size"))+(tool.optLong("packages_size")>0?" + "+size(tool.optLong("packages_size")):"");
        consent(Language.text(Marketplace.update(tool)?R.string.update_module:R.string.install_module)+" «"+tool.optString("title")+"»?",detail,glyph(tool),tint(tool),
            tool.optJSONArray("permissions"),tool.optJSONArray("packages"),Language.text(Marketplace.update(tool)?R.string.update:R.string.install),() -> {
                page.job(line,c -> { market.install(tool,market.definition(tool,true,c)); return new JSONObject(); },done -> {
                    page.toast(Language.text(R.string.module_installed)+tool.optString("title")); page.open(tool.optString("id"));
                });
            },null,null);
    }
    void rate(JSONObject tool) {
        LinearLayout view=Ui.column(activity); Ui.pad(view,22,18,22,8);
        TextView heading=Ui.bold(activity,Language.text(R.string.rate_module)+" «"+tool.optString("title")+"»",17,Ui.INK); view.addView(heading); Ui.gap(view,14);
        LinearLayout row=Ui.row(activity); row.setGravity(Gravity.CENTER); int[] chosen={0}; IconView[] stars=new IconView[5];
        TextView label=Ui.text(activity,Language.text(R.string.tap_a_star),13,Ui.MUTED); label.setGravity(Gravity.CENTER);
        int[] words={R.string.rating_1,R.string.rating_2,R.string.rating_3,R.string.rating_4,R.string.rating_5};
        for(int i=0;i<5;i++) { int value=i+1;
            stars[i]=new IconView(activity,IconView.STAR,Language.text(words[i]),v -> { chosen[0]=value; for(int n=0;n<5;n++) { stars[n].icon(n<value?IconView.STAR_FULL:IconView.STAR); stars[n].color(n<value?Ui.WARN:Ui.FAINT); } label.setText(Language.text(words[value-1])); });
            stars[i].glyphSize(30); stars[i].color(Ui.FAINT); row.addView(stars[i],Ui.size(activity,50,50)); }
        view.addView(row); Ui.gap(view,6); view.addView(label); Ui.gap(view,14);
        EditText text=Ui.input(activity,Language.text(R.string.review_hint),true); text.setMinLines(3); text.setGravity(Gravity.TOP); view.addView(text,Ui.size(activity,-1,-2)); Ui.gap(view,10);
        view.addView(Ui.caption(activity,Language.text(hub.github.connected()?R.string.rating_note_connected:R.string.rating_note_browser)));
        ScrollView scroll=new ScrollView(activity); scroll.addView(view);
        AlertDialog dialog=new AlertDialog.Builder(activity).setView(scroll).setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.send),null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if(chosen[0]==0) { page.toast(Language.text(R.string.tap_a_star)); return; }
            dialog.dismiss(); String review=text.getText().toString();
            if(!hub.github.connected()) { browseRating(tool,chosen[0],review); return; }
            page.toast(Language.text(R.string.sending));
            page.job(new TextView(activity),c -> { try { return new JSONObject().put("url",market.rate(tool,chosen[0],review,c)); } catch(Exception e) { return new JSONObject().put("error",e.getMessage()); } },
                result -> { if(result.has("url")) page.toast(Language.text(R.string.rating_sent)); else browseRating(tool,chosen[0],review); });
        }));
        dialog.show();
    }
    private void browseRating(JSONObject tool,int stars,String review) {
        try { page.browse(Marketplace.rateLink(tool,stars,review)); page.toast(Language.text(R.string.confirm_on_github)); } catch(Exception e) { page.toast(e.getMessage()); }
    }
    /** Publishing form for a local custom module: category, version, catalogue text and the author's licence confirmation. */
    void publish(String id) {
        JSONObject module; try { module=hub.definition(id); } catch(Exception e) { module=null; }
        if(module==null) { page.toast(Language.text(R.string.module_not_found)); return; }
        JSONObject definition=module;
        LinearLayout view=Ui.column(activity); Ui.pad(view,22,18,22,8);
        view.addView(Ui.bold(activity,Language.text(R.string.publish_module)+" «"+definition.optString("title")+"»",17,Ui.INK)); Ui.gap(view,8);
        TextView intro=Ui.caption(activity,Language.text(R.string.publish_note)); view.addView(intro);
        view.addView(Ui.section(activity,Language.text(R.string.category))); Ui.gap(view,0);
        HorizontalScrollView scroll=new HorizontalScrollView(activity); scroll.setHorizontalScrollBarEnabled(false); LinearLayout chips=Ui.row(activity); scroll.addView(chips);
        String[] picked={"productivity"};
        Runnable[] draw=new Runnable[1];
        draw[0]=() -> { chips.removeAllViews(); for(String key:CATEGORIES) chip(chips,category(key),key.equals(picked[0]),() -> { picked[0]=key; draw[0].run(); }); };
        draw[0].run(); view.addView(scroll); Ui.gap(view,14);
        view.addView(Ui.section(activity,Language.text(R.string.version)));
        String published=""; try { JSONObject tool=market.tool(id); if(tool!=null) { published=tool.optString("version"); picked[0]=tool.optString("category",picked[0]); draw[0].run(); } } catch(Exception ignored) { }
        EditText version=Ui.input(activity,"1.0.0",false); version.setText(MarketRules.nextVersion(published)); view.addView(version,Ui.size(activity,-1,50)); Ui.gap(view,14);
        view.addView(Ui.section(activity,Language.text(R.string.catalog_text)));
        EditText about=Ui.input(activity,Language.text(R.string.catalog_text_hint),true); about.setText(definition.optString("description")); about.setMinLines(3); about.setGravity(Gravity.TOP);
        view.addView(about,Ui.size(activity,-1,-2)); Ui.gap(view,14);
        view.addView(Ui.section(activity,Language.text(R.string.permissions)));
        try { permissionRows(view,hub.permissions(definition),definition.optJSONArray("packages")); } catch(Exception ignored) { }
        CheckBox own=new CheckBox(activity); own.setText(Language.text(R.string.publish_license)); own.setTextColor(Ui.INK); own.setTextSize(13);
        own.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.MINT)); view.addView(own); Ui.gap(view,6);
        TextView rules=Ui.bold(activity,Language.text(R.string.catalog_rules),13,Ui.MINT); Ui.pad(rules,0,6,0,6); view.addView(rules);
        ScrollView outer=new ScrollView(activity); outer.addView(view);
        AlertDialog dialog=new AlertDialog.Builder(activity).setView(outer).setNegativeButton(Language.text(R.string.cancel),null).setPositiveButton(Language.text(R.string.send_for_review),null).create();
        rules.setOnClickListener(v -> { dialog.dismiss(); page.open(ModulesPage.RULES); });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if(!own.isChecked()) { page.toast(Language.text(R.string.publish_license_required)); return; }
            JSONObject submission;
            try { submission=market.submission(id,picked[0],version.getText().toString().trim(),about.getText().toString()); } catch(Exception e) { page.toast(e.getMessage()); return; }
            dialog.dismiss(); send(submission);
        }));
        dialog.show();
    }
    private void send(JSONObject submission) {
        if(!hub.github.connected()) { browseSubmission(submission); return; }
        page.toast(Language.text(R.string.sending));
        page.job(new TextView(activity),c -> { try { return new JSONObject().put("url",market.submit(submission,c)); } catch(Exception e) { return new JSONObject().put("error",e.getMessage()); } },result -> {
            if(!result.has("url")) { browseSubmission(submission); return; }
            String url=result.optString("url");
            new AlertDialog.Builder(activity).setTitle(Language.text(R.string.submission_sent)).setMessage(Language.text(R.string.submission_sent_note))
                .setPositiveButton(Language.text(R.string.close),null).setNeutralButton(Language.text(R.string.open_on_github),(d,w) -> page.browse(url)).show();
        });
    }
    private void browseSubmission(JSONObject submission) {
        try {
            activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("module submission",MarketRules.submissionBody(submission.toString(2))));
            page.browse(Marketplace.submitLink(submission)); page.toast(Language.text(R.string.submission_copied));
        } catch(Exception e) { page.toast(e.getMessage()); }
    }

    // Rules.
    void rules(LinearLayout body) {
        LinearLayout hero=Ui.column(activity); hero.setBackground(Ui.shape(activity,Ui.LAVENDER_SOFT,22,0)); Ui.pad(hero,18,18,18,18);
        hero.addView(Ui.badge(activity,IconView.STORE,Ui.LAVENDER,24),Ui.size(activity,48,48)); Ui.gap(hero,12);
        hero.addView(Ui.bold(activity,Language.text(R.string.rules_title),18,Ui.INK)); Ui.gap(hero,6);
        TextView lead=Ui.text(activity,Language.text(R.string.rules_lead),13,Ui.MUTED); lead.setLineSpacing(Ui.dp(activity,3),1); hero.addView(lead);
        body.addView(hero,Ui.size(activity,-1,-2));
        section(body,Language.text(R.string.how_to_publish));
        int[] steps={R.string.publish_step_1,R.string.publish_step_2,R.string.publish_step_3,R.string.publish_step_4};
        for(int i=0;i<steps.length;i++) numbered(body,Integer.toString(i+1),Language.text(steps[i]),Ui.MINT);
        section(body,Language.text(R.string.catalog_rules));
        int[] rules={R.string.rule_1,R.string.rule_2,R.string.rule_3,R.string.rule_4,R.string.rule_5,R.string.rule_6,R.string.rule_7,R.string.rule_8};
        for(int rule:rules) numbered(body,"✓",Language.text(rule),Ui.LAVENDER);
        section(body,Language.text(R.string.ratings_and_downloads));
        numbered(body,"★",Language.text(R.string.ratings_rule),Ui.WARN);
        numbered(body,"↓",Language.text(R.string.downloads_rule),Ui.WARN);
        Ui.gap(body,16);
        List<String> local=new ArrayList<>(),names=new ArrayList<>();
        try { JSONArray modules=hub.list(); for(int i=0;i<modules.length();i++) { JSONObject m=modules.getJSONObject(i); if("local".equals(m.optString("source"))) { local.add(m.getString("id")); names.add(m.optString("title")); } } } catch(Exception ignored) { }
        if(!local.isEmpty()) { body.addView(Ui.button(activity,Language.text(R.string.publish_my_module),true,v -> choose(local,names)),Ui.size(activity,-1,50)); Ui.gap(body,10); }
        body.addView(Ui.button(activity,Language.text(R.string.create_with_gpt),local.isEmpty(),v -> page.toChat.accept(Language.text(R.string.create_a_meshell_module_for_me_ask_about_its_purpose_first_t))),Ui.size(activity,-1,50)); Ui.gap(body,10);
        body.addView(Ui.button(activity,Language.text(R.string.rules_on_github),false,v -> page.browse(Marketplace.PROJECT+"/blob/main/CONTRIBUTING.md")),Ui.size(activity,-1,50));
    }
    void choose(List<String> ids,List<String> names) {
        if(ids.size()==1) { publish(ids.get(0)); return; }
        new AlertDialog.Builder(activity).setTitle(Language.text(R.string.publish_my_module)).setItems(names.toArray(new String[0]),(d,n) -> publish(ids.get(n))).show();
    }
    private void numbered(LinearLayout parent,String mark,String text,int color) {
        LinearLayout row=Ui.row(activity); row.setGravity(Gravity.TOP);
        TextView badge=Ui.bold(activity,mark,12,color); badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.shape(activity,color==Ui.LAVENDER?Ui.LAVENDER_SOFT:color==Ui.WARN?0x24efc48c:Ui.MINT_SOFT,999,0)); row.addView(badge,Ui.size(activity,26,26));
        TextView body=Ui.text(activity,text,14,Ui.INK); body.setLineSpacing(Ui.dp(activity,3),1); Ui.pad(body,12,3,0,0); row.addView(body,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams lp=Ui.size(activity,-1,-2); lp.bottomMargin=Ui.dp(activity,12); parent.addView(row,lp);
    }
}
