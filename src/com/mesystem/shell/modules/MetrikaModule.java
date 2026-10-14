package com.mesystem.shell;

import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

final class MetrikaModule {
    private final SecretVault vault;
    MetrikaModule(SecretVault vault) { this.vault=vault; }
    JSONArray profiles() throws Exception {
        JSONArray out=new JSONArray(),entries=vault.list();
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);
            if("metrika".equals(entry.optString("kind"))) out.put(new JSONObject().put("name",entry.getString("name"))
                .put("label",vault.object(entry.getString("name")).optString("label",entry.getString("name"))));
        } return out;
    }
    JSONObject counters(OpenAiClient.Cancel cancel) throws Exception {
        JSONArray all=new JSONArray(),profiles=profiles(),errors=new JSONArray();
        for(int i=0;i<profiles.length();i++) {
            String name=profiles.getJSONObject(i).getString("name");
            try {
                JSONObject result=ModuleHttp.get("https://api-metrika.yandex.net/management/v1/counters?per_page=1000","OAuth "+vault.object(name).getString("token"),cancel);
                JSONArray list=result.optJSONArray("counters");
                if(list!=null) for(int n=0;n<list.length();n++) { JSONObject counter=list.getJSONObject(n);
                    all.put(new JSONObject().put("id",counter.getLong("id")).put("name",counter.optString("name")).put("site",counter.optString("site")).put("profile",name)); }
            } catch(Exception error) { errors.put(new JSONObject().put("profile",name).put("error",error.getMessage())); }
        }
        return new JSONObject().put("counters",all).put("errors",errors);
    }
    JSONObject report(JSONObject args,OpenAiClient.Cancel cancel) throws Exception {
        String profile=args.getString("profile"); long id=args.getLong("counter"); int days=Math.max(1,Math.min(90,args.optInt("days",7)));
        String metrics="ym:s:visits,ym:s:users,ym:s:pageviews,ym:s:bounceRate,ym:s:avgVisitDurationSeconds";
        String url="https://api-metrika.yandex.net/stat/v1/data?ids="+id+"&metrics="+URLEncoder.encode(metrics,"UTF-8")+
            "&dimensions=ym%3As%3Adate&date1="+days+"daysAgo&date2=yesterday&sort=ym%3As%3Adate&accuracy=full&limit=100";
        return ModuleHttp.get(url,"OAuth "+vault.object(profile).getString("token"),cancel).put("counter",id).put("days",days);
    }
}
