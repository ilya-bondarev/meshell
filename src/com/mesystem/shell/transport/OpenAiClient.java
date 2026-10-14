package com.mesystem.shell;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

final class OpenAiClient {
    static final String AUTH="https://auth.openai.com";
    static final String VERIFY=AUTH+"/codex/device";
    static final String CLIENT_ID="app_EMoamEEZ73f0CkXaXp7hrann";
    static final String BACKEND="https://chatgpt.com/backend-api/codex";
    static final String CODEX_CLIENT_VERSION="0.159.1";
    private volatile String clientVersion=CODEX_CLIENT_VERSION;
    String clientVersion() { return clientVersion; }
    void clientVersion(String stored) { clientVersion=newest(CODEX_CLIENT_VERSION,stored); }
    private static final String VERSION="\\d{1,5}(\\.\\d{1,6}){2}";
    /** The model catalogue depends on the reported Codex version; a stored update never goes below the built-in one. */
    static String newest(String builtIn,String stored) {
        if(stored==null || !stored.matches(VERSION)) return builtIn;
        String[] a=builtIn.split("\\."),b=stored.split("\\.");
        for(int i=0;i<3;i++) { long x=Long.parseLong(a[i]),y=Long.parseLong(b[i]); if(x!=y) return y>x?stored:builtIn; }
        return builtIn;
    }
    static String latestCodexVersion(Cancel cancel) throws Exception {
        String version=json("https://registry.npmjs.org/@openai/codex/latest","GET",null,null,null,cancel).optString("version");
        if(!version.matches(VERSION)) throw new IOException(Language.text(R.string.unexpected_codex_version)+version);
        return version;
    }
    static final String REQUESTED_MODEL="gpt-6.1-sol";
    static final String DEFAULT_MODEL="gpt-6-astra";
    static final class Cancel {
        volatile boolean stopped;
        private HttpsURLConnection connection;
        private java.net.Socket socket;
        synchronized void attach(HttpsURLConnection value) throws IOException {
            if(stopped) { value.disconnect(); throw new IOException(Language.text(R.string.stopped)); }
            connection=value;
        }
        synchronized void detach(HttpsURLConnection value) { if(connection==value) connection=null; }
        synchronized void attachSocket(java.net.Socket value) throws IOException { check(); socket=value; }
        synchronized void detachSocket(java.net.Socket value) { if(socket==value) socket=null; }
        synchronized void stop() {
            stopped=true; if(connection!=null) connection.disconnect();
            if(socket!=null) try { socket.close(); } catch(IOException ignored) { }
        }
        void check() throws IOException { if(stopped) throw new IOException(Language.text(R.string.stopped)); }
    }
    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status,String message) { super(message); this.status=status; }
    }
    static final class DeviceCode {
        final String id,code; final int interval;
        DeviceCode(JSONObject value) throws Exception {
            id=value.getString("device_auth_id");
            code=value.has("user_code")?value.getString("user_code"):value.getString("usercode");
            interval=Math.max(3,Math.min(30,value.optInt("interval",5)));
        }
    }
    interface Output { void delta(String text); void completed(String model,int tokens); }
    private final SecretStore store;
    volatile JSONArray catalog=new JSONArray();
    OpenAiClient(SecretStore store) { this.store=store; }

    private static HttpsURLConnection open(String url,String method,byte[] body,String contentType,
                                           JSONObject auth,Cancel cancel) throws Exception {
        HttpsURLConnection conn=(HttpsURLConnection)new URL(url).openConnection();
        cancel.attach(conn);
        try {
        conn.setConnectTimeout(15000); conn.setReadTimeout(url.endsWith("/responses")?180000:20000);
        conn.setInstanceFollowRedirects(false); conn.setUseCaches(false); conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent","MeShell/0.4.0 (Android)");
        conn.setRequestProperty("Accept",url.endsWith("/responses")?"text/event-stream":"application/json");
        if(auth!=null) {
            conn.setRequestProperty("Authorization","Bearer "+auth.getString("access_token"));
            String account=auth.optString("account_id");
            if(!account.isEmpty()) conn.setRequestProperty("ChatGPT-Account-Id",account);
            if(url.startsWith(BACKEND)) conn.setRequestProperty("OpenAI-Beta","responses=experimental");
            if(url.startsWith(BACKEND)) conn.setRequestProperty("originator","meshell");
        }
        if(body!=null) {
            conn.setDoOutput(true); conn.setRequestProperty("Content-Type",contentType);
            conn.setFixedLengthStreamingMode(body.length);
            try(java.io.OutputStream out=conn.getOutputStream()) { out.write(body); }
        }
            return conn;
        } catch(Exception error) {
            cancel.detach(conn); conn.disconnect(); throw error;
        }
    }
    private static String read(InputStream input,int limit) throws Exception {
        if(input==null) return "";
        try(InputStream stream=input; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int n;
            while((n=stream.read(buffer))!=-1) {
                if(out.size()+n>limit) throw new IOException(Language.text(R.string.server_response_exceeds_the_limit));
                out.write(buffer,0,n);
            }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static HttpFailure rejected(HttpsURLConnection conn) throws Exception {
        int status=conn.getResponseCode(); String detail="";
        String raw=read(conn.getErrorStream(),65536);
        try {
            JSONObject body=new JSONObject(raw); JSONObject error=body.optJSONObject("error");
            detail=error!=null?error.optString("message"):body.optString("error_description",body.optString("detail",""));
        } catch(Exception ignored) { }
        if(detail.length()>350) detail=detail.substring(0,350);
        String hint=status==401?Language.text(R.string.sign_in_expired_reconnect_chatgpt):
            status==429?Language.text(R.string.quota_exhausted_or_too_many_requests_try_later):
            status==403?Language.text(R.string.openai_denied_access_check_vpn_and_codex_subscription_access):
            status>=500?Language.text(R.string.openai_temporarily_unavailable_retry):Language.text(R.string.openai_returned_an_error);
        return new HttpFailure(status,hint+" (HTTP "+status+")"+(detail.isEmpty()?"":"\n"+detail));
    }
    private static JSONObject json(String url,String method,String body,String type,JSONObject auth,Cancel cancel) throws Exception {
        HttpsURLConnection conn=null;
        try {
            conn=open(url,method,body==null?null:body.getBytes(StandardCharsets.UTF_8),type,auth,cancel);
            int code=conn.getResponseCode();
            if(code<200 || code>=300) throw rejected(conn);
            return new JSONObject(read(conn.getInputStream(),2*1024*1024));
        } finally { if(conn!=null) { cancel.detach(conn); conn.disconnect(); } }
    }
    static DeviceCode deviceCode(Cancel cancel) throws Exception {
        return new DeviceCode(json(AUTH+"/api/accounts/deviceauth/usercode","POST",
            new JSONObject().put("client_id",CLIENT_ID).toString(),"application/json",null,cancel));
    }
    JSONObject poll(DeviceCode code,Cancel cancel) throws Exception {
        long deadline=System.currentTimeMillis()+15*60*1000;
        while(System.currentTimeMillis()<deadline) {
            cancel.check();
            JSONObject grant=null;
            try {
                grant=json(AUTH+"/api/accounts/deviceauth/token","POST",new JSONObject()
                    .put("device_auth_id",code.id).put("user_code",code.code).toString(),"application/json",null,cancel);
            } catch(HttpFailure error) { if(error.status!=403 && error.status!=404) throw error; }
            if(grant!=null) {
                String body=form("grant_type","authorization_code","client_id",CLIENT_ID,
                    "code",grant.getString("authorization_code"),"code_verifier",grant.getString("code_verifier"),
                    "redirect_uri",AUTH+"/deviceauth/callback");
                JSONObject tokens=json(AUTH+"/oauth/token","POST",body,"application/x-www-form-urlencoded",null,cancel);
                JSONObject normalized=normalize(tokens,null); cancel.check(); store.save(normalized); return normalized;
            }
            for(int i=0;i<code.interval*10;i++) { cancel.check(); Thread.sleep(100); }
        }
        throw new IOException(Language.text(R.string.sign_in_code_expired_request_a_new_code));
    }
    private static String form(String... pairs) throws Exception {
        StringBuilder body=new StringBuilder();
        for(int i=0;i<pairs.length;i+=2) {
            if(i>0) body.append('&');
            body.append(URLEncoder.encode(pairs[i],"UTF-8")).append('=').append(URLEncoder.encode(pairs[i+1],"UTF-8"));
        }
        return body.toString();
    }
    private static JSONObject claims(String token) {
        try { return new JSONObject(new String(Base64.decode(token.split("\\.")[1],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8)); }
        catch(Exception ignored) { return new JSONObject(); }
    }
    private static JSONObject normalize(JSONObject tokens,JSONObject previous) throws Exception {
        String access=tokens.getString("access_token");
        JSONObject data=claims(tokens.optString("id_token",access));
        JSONObject auth=data.optJSONObject("https://api.openai.com/auth");
        if(auth==null) auth=claims(access).optJSONObject("https://api.openai.com/auth");
        String account=auth==null?"":auth.optString("chatgpt_account_id");
        if(account.isEmpty() && previous!=null) account=previous.optString("account_id");
        if(account.isEmpty()) throw new IOException(Language.text(R.string.openai_did_not_return_a_chatgpt_account_id));
        long expiry=claims(access).optLong("exp",System.currentTimeMillis()/1000+tokens.optLong("expires_in",3600));
        return new JSONObject().put("access_token",access)
            .put("refresh_token",tokens.optString("refresh_token",previous==null?"":previous.optString("refresh_token")))
            .put("account_id",account).put("expires",expiry)
            .put("email",data.optString("email",previous==null?"":previous.optString("email")))
            .put("plan",auth==null?"ChatGPT":auth.optString("chatgpt_plan_type","ChatGPT"));
    }
    private synchronized JSONObject credential(Cancel cancel,boolean force) throws Exception {
        JSONObject current=store.load();
        if(current==null) throw new IOException(Language.text(R.string.connect_chatgpt_first));
        if(!force && current.optLong("expires")>System.currentTimeMillis()/1000+90) return current;
        String refresh=current.optString("refresh_token");
        if(refresh.isEmpty()) throw new IOException(Language.text(R.string.sign_in_expired_reconnect_chatgpt));
        JSONObject updated=json(AUTH+"/oauth/token","POST",form("grant_type","refresh_token","client_id",CLIENT_ID,
            "refresh_token",refresh),"application/x-www-form-urlencoded",null,cancel);
        JSONObject normalized=normalize(updated,current); cancel.check(); store.save(normalized); return normalized;
    }
    List<String> models(Cancel cancel) throws Exception {
        JSONObject result;
        try { result=json(BACKEND+"/models?client_version="+clientVersion,"GET",null,null,credential(cancel,false),cancel); }
        catch(HttpFailure failure) {
            if(failure.status!=401) throw failure;
            result=json(BACKEND+"/models?client_version="+clientVersion,"GET",null,null,credential(cancel,true),cancel);
        }
        JSONArray items=result.optJSONArray("models"); List<String> models=new ArrayList<>();
        if(items!=null) catalog=items;
        if(items!=null) for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i); if(item==null) continue;
            String slug=item.optString("slug",item.optString("id"));
            if(slug.startsWith("gpt-") && !"hide".equals(item.optString("visibility"))) models.add(slug);
        }
        return models;
    }
    JSONObject accountRequest(String method,String path,JSONObject body,Cancel cancel) throws Exception {
        if(!path.startsWith("/wham/")) throw new IOException("Invalid account endpoint");
        for(int attempt=0;attempt<2;attempt++) {
            try { return json("https://chatgpt.com/backend-api"+path,method,body==null?null:body.toString(),
                "application/json",credential(cancel,attempt>0),cancel); }
            catch(HttpFailure failure) { if(failure.status!=401 || attempt>0) throw failure; }
        }
        throw new IOException(Language.text(R.string.sign_in_expired));
    }
    JSONObject stream(String model,String effort,JSONArray messages,JSONArray tools,String instructions,Cancel cancel,Output output) throws Exception {
        String endpoint=BACKEND+"/responses";
        JSONObject request=new JSONObject().put("model",model).put("stream",true).put("store",false)
            .put("instructions",instructions).put("input",messages).put("reasoning",new JSONObject().put("effort",effort))
            .put("include",new JSONArray().put("reasoning.encrypted_content"));
        if(tools!=null && tools.length()>0) request.put("tools",tools).put("parallel_tool_calls",false);
        final JSONObject[] completed={null};
        for(int attempt=0;attempt<2;attempt++) {
            HttpsURLConnection conn=null;
            try {
                JSONObject auth=credential(cancel,attempt>0);
                conn=open(endpoint,"POST",request.toString().getBytes(StandardCharsets.UTF_8),"application/json",auth,cancel);
                if(conn.getResponseCode()==401 && attempt==0) continue;
                if(conn.getResponseCode()!=200) throw rejected(conn);
                final StringBuilder text=new StringBuilder();
                final java.util.TreeMap<Integer,JSONObject> streamedItems=new java.util.TreeMap<>();
                SseReader.read(new InputStreamReader(conn.getInputStream(),StandardCharsets.UTF_8),data -> {
                    cancel.check();
                    if("[DONE]".equals(data)) throw new IOException(Language.text(R.string.response_completion_not_confirmed));
                    JSONObject event=new JSONObject(data); String type=event.optString("type");
                    if("response.output_item.done".equals(type)) {
                        JSONObject item=event.optJSONObject("item");
                        if(item!=null) streamedItems.put(event.optInt("output_index",streamedItems.size()),item);
                    } else if("response.output_text.delta".equals(type) || "response.refusal.delta".equals(type)) {
                        String delta=event.optString("delta");
                        if(text.length()+delta.length()>500000) throw new IOException(Language.text(R.string.response_reached_the_size_limit));
                        text.append(delta); output.delta(delta);
                    } else if("response.completed".equals(type)) {
                        JSONObject response=event.getJSONObject("response");
                        JSONArray finalItems=response.optJSONArray("output");
                        // The subscription endpoint can omit output from the completion
                        // envelope: full function calls arrive in output_item.done.
                        if(!streamedItems.isEmpty() && (finalItems==null || finalItems.length()<streamedItems.size())) {
                            finalItems=new JSONArray(); for(JSONObject item:streamedItems.values()) finalItems.put(item);
                            response.put("output",finalItems);
                        }
                        completed[0]=response;
                        if(text.length()==0) {
                            JSONArray items=response.optJSONArray("output");
                            if(items!=null) for(int i=0;i<items.length();i++) {
                                JSONArray content=items.getJSONObject(i).optJSONArray("content");
                                if(content!=null) for(int j=0;j<content.length();j++) {
                                    JSONObject part=content.getJSONObject(j); String value=part.optString("text",part.optString("refusal"));
                                    text.append(value); output.delta(value);
                                }
                            }
                        }
                        boolean called=false; JSONArray items=response.optJSONArray("output");
                        if(items!=null) for(int i=0;i<items.length();i++) if("function_call".equals(items.getJSONObject(i).optString("type"))) called=true;
                        if(text.length()==0 && !called) throw new IOException(Language.text(R.string.model_finished_without_a_response_or_action));
                        JSONObject usage=response.optJSONObject("usage");
                        output.completed(response.optString("model",model),usage==null?0:usage.optInt("total_tokens"));
                        return false;
                    } else if("error".equals(type) || "response.failed".equals(type) || "response.incomplete".equals(type)) {
                        JSONObject response=event.optJSONObject("response");
                        JSONObject error=response==null?event.optJSONObject("error"):response.optJSONObject("error");
                        throw new IOException(error==null?event.optString("message",Language.text(R.string.model_response_incomplete)):
                            error.optString("message",Language.text(R.string.openai_error)));
                    }
                    return true;
                });
                if(completed[0]==null) throw new IOException(Language.text(R.string.stream_ended_without_completion_confirmation));
                return completed[0];
            } finally { if(conn!=null) { cancel.detach(conn); conn.disconnect(); } }
        }
        throw new IOException(Language.text(R.string.sign_in_expired_reconnect_chatgpt));
    }
}
