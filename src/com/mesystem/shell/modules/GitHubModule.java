package com.mesystem.shell;

import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** GitHub through the official gh CLI from Termux; the token stays in the vault and reaches gh only as GH_TOKEN. */
final class GitHubModule {
    static final String TOKEN="github-token";
    static final String TOKEN_PAGE="https://github.com/settings/personal-access-tokens/new";
    private static final String REPOSITORY="[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}";
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
        String target=repository(args.optString("repo"));
        switch(action) {
            case "repo": return "gh repo view "+target;
            case "issues": return "gh issue list -R "+target+" --limit 30";
            case "prs": return "gh pr list -R "+target+" --limit 30";
            case "runs": return "gh run list -R "+target+" --limit 20";
            default: throw new IllegalArgumentException(Language.text(R.string.unknown_module_action)+"github/"+action);
        }
    }
    private static String repository(String value) {
        String repo=value.trim();
        if(!repo.matches(REPOSITORY)) throw new IllegalArgumentException(Language.text(R.string.github_repository_format));
        return ShellBroker.quote(repo);
    }
    /** gh prints the raw API body before its own one-line error; keep only that line on failure. */
    static String output(JSONObject result) {
        String output=result.optString("output",result.toString()).trim();
        if(result.optInt("exit_code")==0) return output;
        int at=output.lastIndexOf("gh: "); return at>=0?output.substring(at+4).trim():output;
    }

    JSONObject run(String action,JSONObject args,OpenAiClient.Cancel cancel) throws Exception { return exec(command(action,args),new byte[0],cancel); }
    /** Opens an issue with the connected account and returns its URL; the body travels on stdin. */
    String issue(String repo,String title,String body,OpenAiClient.Cancel cancel) throws Exception {
        JSONObject result=exec("gh issue create -R "+repository(repo)+" --title "+ShellBroker.quote(title)+" --body-file -",body.getBytes(StandardCharsets.UTF_8),cancel);
        String output=output(result);
        if(result.optInt("exit_code")!=0) throw new IOException(output);
        String[] lines=output.split("\n"); return lines[lines.length-1].trim();
    }
    private JSONObject exec(String command,byte[] input,OpenAiClient.Cancel cancel) throws Exception {
        Object token=connected()?vault.get(TOKEN):null;
        if(token==null) throw new IllegalStateException(Language.text(R.string.github_not_connected));
        new Packages(context).ensure(Collections.singletonList("gh"),cancel);
        Map<String,String> env=new HashMap<>();
        env.put("GH_TOKEN",(String)token); env.put("GH_CONFIG_DIR",ShellBroker.HOME+"/.config/gh");
        env.put("GH_PROMPT_DISABLED","1"); env.put("GH_PAGER","cat"); env.put("NO_COLOR","1"); env.put("GH_NO_UPDATE_NOTIFIER","1");
        return shell.run(command,ShellBroker.HOME,input,env,120000,cancel).json();
    }
}
