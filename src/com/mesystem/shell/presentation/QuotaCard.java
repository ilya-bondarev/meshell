package com.mesystem.shell;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.text.SimpleDateFormat;

final class QuotaCard extends LinearLayout {
    private final TextView updated,warning;
    private final LinearLayout windows;
    private final IconView refresh;
    private String revision="",problem="";
    QuotaCard(Context c,Runnable reload) {
        super(c); setOrientation(VERTICAL); setBackground(Ui.shape(c,Ui.PANEL,22,Ui.BORDER)); Ui.pad(this,16,6,8,14);
        LinearLayout header=Ui.row(c); LinearLayout names=Ui.column(c);
        names.addView(Ui.bold(c,Language.text(R.string.chatgpt_limits),15,Ui.INK)); Ui.gap(names,4);
        updated=Ui.text(c,"",11,Ui.FAINT); names.addView(updated); header.addView(names,new LayoutParams(0,-2,1));
        refresh=new IconView(c,IconView.REFRESH,Language.text(R.string.refresh_limits),v -> reload.run()); refresh.color(Ui.MINT);
        header.addView(refresh,Ui.size(c,48,48)); addView(header); Ui.gap(this,6);
        windows=Ui.column(c); Ui.pad(windows,0,0,8,0); addView(windows);
        warning=Ui.text(c,"",12,Ui.WARN); warning.setSingleLine(true); warning.setEllipsize(TextUtils.TruncateAt.END);
        warning.setBackground(Ui.shape(c,0x1fefc48c,12,0)); Ui.pad(warning,12,9,12,9);
        warning.setOnClickListener(v -> new AlertDialog.Builder(c).setTitle(Language.text(R.string.limits_not_refreshed)).setMessage(problem).setPositiveButton(Language.text(R.string.ok),null).show());
        LayoutParams wp=Ui.size(c,-1,-2); wp.setMargins(0,Ui.dp(c,10),Ui.dp(c,8),0); addView(warning,wp);
    }
    void refresh(ChatEngine chat) {
        refresh.setEnabled(chat.authenticated && !chat.limitsLoading); refresh.setAlpha(chat.limitsLoading?0.4f:1f);
        String key=chat.authenticated+"|"+chat.limits.toString();
        if(!key.equals(revision)) {
            revision=key; windows.removeAllViews();
            if(chat.authenticated) {
                JSONObject rate=chat.limits.optJSONObject("rate_limit");
                if(rate!=null) { window(Language.text(R.string.primary),rate.optJSONObject("primary_window")); window(Language.text(R.string.secondary_window),rate.optJSONObject("secondary_window")); }
                JSONArray additional=chat.limits.optJSONArray("additional_rate_limits");
                if(additional!=null) for(int i=0;i<additional.length();i++) {
                    JSONObject a=additional.optJSONObject(i); if(a==null) continue; JSONObject r=a.optJSONObject("rate_limit");
                    if(r!=null) { String label=a.optString("limit_name",Language.text(R.string.additional)); window(label,r.optJSONObject("primary_window")); window(label+Language.text(R.string.secondary_window_2),r.optJSONObject("secondary_window")); }
                }
            }
            if(windows.getChildCount()==0) windows.addView(Ui.text(getContext(),chat.authenticated?Language.text(R.string.no_data_yet):Language.text(R.string.connect_chatgpt),13,Ui.MUTED));
        }
        updated.setText(chat.limitsLoading?Language.text(R.string.refreshing):chat.limitsUpdated>0?Language.text(R.string.updated_at)+new SimpleDateFormat("HH:mm",Language.locale()).format(new Date(chat.limitsUpdated)):Language.text(R.string.not_updated_yet));
        problem=chat.limitsError+(chat.limitsUpdated>0?Language.text(R.string.previous_data_shown):"");
        warning.setText("⚠  "+chat.limitsError); warning.setVisibility(chat.limitsError.isEmpty()?GONE:VISIBLE);
    }
    private void window(String label,JSONObject w) {
        if(w==null) return; Context c=getContext(); double used=w.optDouble("used_percent",Double.NaN);
        long seconds=w.optLong("limit_window_seconds",0),reset=w.optLong("reset_at",0);
        String duration=seconds==0?"":" · "+services.quota.QuotaRules.duration(seconds)+(services.quota.QuotaRules.days(seconds)?Language.text(R.string.d):Language.text(R.string.h));
        LinearLayout line=Ui.row(c); line.addView(Ui.text(c,label+duration,13,Ui.INK),new LayoutParams(0,-2,1));
        double left=services.quota.QuotaRules.remaining(used); int tone;
        switch(services.quota.QuotaRules.level(left)) { case LOW: tone=Ui.DANGER; break; case WARNING: tone=Ui.WARN; break; case NORMAL: tone=Ui.MINT; break; default: tone=Ui.MUTED; }
        line.addView(Ui.bold(c,Double.isNaN(left)?"—":String.format(Language.locale(),"%.0f%%",left),13,tone)); windows.addView(line); Ui.gap(windows,8);
        if(!Double.isNaN(used)) {
            ProgressBar bar=new ProgressBar(c,null,android.R.attr.progressBarStyleHorizontal); bar.setMax(1000);
            bar.setProgress(services.quota.QuotaRules.progress(left));
            bar.setProgressDrawable(meter(c,tone)); windows.addView(bar,Ui.size(c,-1,6));
        }
        if(reset>0) { TextView when=Ui.text(c,Language.text(R.string.remaining_reset)+new SimpleDateFormat("dd.MM HH:mm",Language.locale()).format(new Date(reset*1000)),11,Ui.FAINT);
            when.setGravity(Gravity.END); Ui.pad(when,0,6,0,0); windows.addView(when,Ui.size(c,-1,-2)); }
        Ui.gap(windows,10);
    }
    private static android.graphics.drawable.Drawable meter(Context c,int tone) {
        android.graphics.drawable.ClipDrawable fill=new android.graphics.drawable.ClipDrawable(Ui.shape(c,tone,99,0),Gravity.START,android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{Ui.shape(c,Ui.PANEL3,99,0),fill});
        layers.setId(0,android.R.id.background); layers.setId(1,android.R.id.progress); return layers;
    }
}
