package com.mesystem.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** Public HTTPS downloads: cancellable, bounded, redirects followed only within HTTPS. */
final class Http {
    private Http() { }
    static HttpsURLConnection open(String url,String agent,OpenAiClient.Cancel cancel) throws IOException {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(20000); connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent",agent); cancel.attach(connection);
        int code=connection.getResponseCode();
        if(code!=200) { cancel.detach(connection); connection.disconnect(); throw new IOException("HTTP "+code+": "+url); }
        return connection;
    }
    static byte[] get(String url,String agent,int limit,OpenAiClient.Cancel cancel) throws IOException {
        HttpsURLConnection connection=open(url,agent,cancel);
        try(InputStream in=connection.getInputStream()) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[65536]; int n;
            while((n=in.read(buffer))>0) { cancel.check(); out.write(buffer,0,n); if(out.size()>limit) throw new IOException(Language.text(R.string.response_too_large)+url); }
            return out.toByteArray();
        } finally { cancel.detach(connection); connection.disconnect(); }
    }
}
