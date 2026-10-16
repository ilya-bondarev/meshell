package com.mesystem.shell;

import android.content.Context;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** GitHub through the official gh CLI from Termux; the token stays in the vault and reaches gh only as GH_TOKEN. */
final class GitHubModule {
    static final String TOKEN="github-token";
    static final String TOKEN_PAGE="https://github.com/settings/personal-access-tokens/new";
    private final Context context;
    private final ShellBroker shell;
    private final SecretVault vault;

    GitHubModule(Context context,ShellBroker shell,SecretVault vault) { this.context=context.getApplicationContext(); this.shell=shell; this.vault=vault; }

    boolean connected() { try { return vault.get(TOKEN) instanceof String; } catch(Exception e) { return false; } }
    void connect(String token) throws Exception {
        String value=token.trim();
        if(!value.matches("(github_pat_|gh[pousr]_)[A-Za-z0-9_]{20,250}")) throw new IllegalArgumentException(Language.text(R.string.github_token_format));
        vault.put(TOKEN,"secret",value);
    }
    void disconnect() throws Exception { vault.remove(TOKEN); }

    static String command(String action,JSONObject args) {
        switch(action) {
            case "status": return "gh api user --jq '{login,name,html_url}'";
            case "repos": return "gh repo list --limit 50";
        }
        String repo=args.optString("repo").trim();
        if(!repo.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")) throw new IllegalArgumentException(Language.text(R.string.github_repository_format));
        String target=ShellBroker.quote(repo);
        switch(action) {
            case "repo": return "gh repo view "+target;
            case "issues": return "gh issue list -R "+target+" --limit 30";
            case "prs": return "gh pr list -R "+target+" --limit 30";
            case "runs": return "gh run list -R "+target+" --limit 20";
            default: throw new IllegalArgumentException(Language.text(R.string.unknown_module_action)+"github/"+action);
        }
    }

    JSONObject run(String action,JSONObject args,OpenAiClient.Cancel cancel) throws Exception {
        String command=command(action,args);
        Object token=connected()?vault.get(TOKEN):null;
        if(token==null) throw new IllegalStateException(Language.text(R.string.github_not_connected));
        Packages packages=new Packages(context);
        if(Packages.version(packages.installed(cancel),"gh").isEmpty()) packages.install(Collections.singletonList("gh"),false,text -> { },cancel);
        Map<String,String> env=new HashMap<>();
        env.put("GH_TOKEN",(String)token); env.put("GH_CONFIG_DIR",ShellBroker.HOME+"/.config/gh");
        env.put("GH_PROMPT_DISABLED","1"); env.put("GH_PAGER","cat"); env.put("NO_COLOR","1"); env.put("GH_NO_UPDATE_NOTIFIER","1");
        return shell.run(command,ShellBroker.HOME,new byte[0],env,120000,cancel).json();
    }
}
