package com.mesystem.shell;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

final class ModuleHttp {
    static JSONObject get(String url,String authorization,OpenAiClient.Cancel cancel) throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(url).openConnection(); cancel.attach(connection);
        try {
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept","application/json"); if(authorization!=null) connection.setRequestProperty("Authorization",authorization);
            return response(connection,cancel);
        } finally { cancel.detach(connection); connection.disconnect(); }
    }
    static JSONObject response(HttpsURLConnection connection,OpenAiClient.Cancel cancel) throws Exception {
        int status=connection.getResponseCode(); InputStream source=status>=400?connection.getErrorStream():connection.getInputStream();
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        if(source!=null) try(InputStream in=source) {
            byte[] part=new byte[8192]; int n;
            while((n=in.read(part))!=-1) { cancel.check(); if(buffer.size()+n>4*1024*1024) throw new java.io.IOException(Language.text(R.string.response_exceeds_4_mib)); buffer.write(part,0,n); }
        }
        String text=new String(buffer.toByteArray(),StandardCharsets.UTF_8);
        if(status<200 || status>=300) {
            String description="";
            try { JSONObject value=new JSONObject(text); description=value.optString("message"); JSONObject error=value.optJSONObject("error");
                if(error!=null) description=error.optString("message"); } catch(Exception ignored) { }
            if(description.length()>300) description=description.substring(0,300);
            throw new java.io.IOException("HTTP "+status+(description.isEmpty()?"":" · "+description));
        }
        return new JSONObject(text);
    }
}
