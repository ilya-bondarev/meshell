package com.mesystem.shell;

import android.content.res.Resources;
import org.json.JSONArray;
import org.json.JSONObject;

final class ModuleTests {
    static int run(Resources resources) throws Exception {
        ModuleDefinition definitions=new ModuleDefinition(resources);
        JSONObject action=new JSONObject().put("id","status").put("command","getprop ro.build.version.release")
            .put("cwd","/private/workspace").put("env_secrets",new JSONObject().put("API_TOKEN","personal-alias"));
        JSONObject value=new JSONObject().put("id","system-status").put("title","System").put("actions",new JSONArray().put(action));
        definitions.validate(value);
        value.put("enabled",true).put("hidden_password","never-export");
        JSONObject portable=definitions.portable(value);
        if(portable.has("hidden_password") || portable.has("enabled") || portable.getJSONArray("actions").getJSONObject(0).has("cwd")
            || !portable.getJSONArray("actions").getJSONObject(0).getJSONObject("env_secrets").getString("API_TOKEN").equals("API_TOKEN"))
            throw new AssertionError("portable schema projection");
        boolean rejected=false; try { definitions.validate(value); } catch(IllegalArgumentException error) { rejected=true; }
        if(!rejected) throw new AssertionError("unknown import fields must fail visibly");
        portable.getJSONArray("actions").put(portable.getJSONArray("actions").getJSONObject(0));
        rejected=false; try { definitions.validate(portable); } catch(IllegalArgumentException error) { rejected=true; }
        if(!rejected) throw new AssertionError("duplicate action identity");
        if(!GitHubModule.command("issues",new JSONObject().put("repo","octo-org/hello.world")).equals("gh issue list -R 'octo-org/hello.world' --limit 30"))
            throw new AssertionError("GitHub repository command");
        for(String repo:new String[]{"","owner","owner/name;rm -rf ~","../x/y","a/b/c"}) {
            rejected=false; try { GitHubModule.command("repo",new JSONObject().put("repo",repo)); } catch(IllegalArgumentException error) { rejected=true; }
            if(!rejected) throw new AssertionError("GitHub repository accepted: "+repo);
        }
        if(services.quota.QuotaRules.weeklyRemaining(18000,90,604800,35)!=65 || !Double.isNaN(services.quota.QuotaRules.weeklyRemaining(18000,10,0,0)))
            throw new AssertionError("weekly allowance window");
        System.out.println("MODULE_TESTS_OK 5"); return 5;
    }
}
