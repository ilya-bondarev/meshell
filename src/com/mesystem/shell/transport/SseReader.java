package com.mesystem.shell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

final class SseReader {
    interface Event { boolean receive(String data) throws Exception; }
    static void read(Reader source,Event event) throws Exception {
        BufferedReader reader=new BufferedReader(source,8192);
        StringBuilder data=new StringBuilder(), line=new StringBuilder();
        boolean first=true;
        int c;
        while((c=reader.read())!=-1) {
            if(first) { first=false; if(c==0xfeff) continue; }
            if(c!='\n') {
                if(line.length()>=1024*1024) throw new IOException(Language.text(R.string.openai_event_too_long));
                line.append((char)c); continue;
            }
            int end=line.length(); if(end>0 && line.charAt(end-1)=='\r') line.setLength(--end);
            if(end==0) {
                if(data.length()>0) {
                    data.setLength(data.length()-1);
                    if(!event.receive(data.toString())) return;
                    data.setLength(0);
                }
            } else if(line.indexOf("data:")==0) {
                int start=line.length()>5 && line.charAt(5)==' '?6:5;
                data.append(line,start,line.length()).append('\n');
                if(data.length()>1024*1024) throw new IOException(Language.text(R.string.openai_event_too_large));
            }
            line.setLength(0);
        }
        // EOF without a completed event is not provider proof of success.
        throw new IOException(Language.text(R.string.connection_ended_before_the_response_completed_you_can_retry));
    }
}
