package com.mesystem.shell;

import java.util.*;
import org.json.*;

final class CompactTests {
    static int run() throws Exception {
        List<ChatStore.Message> messages=new ArrayList<>();
        for(int i=1;i<=8;i++) messages.add(new ChatStore.Message(i,1,i%2==1?"user":"assistant","Текст "+i,"complete",""));
        if(CompactContext.cutoff(messages)!=4) throw new AssertionError("keep last two whole turns");
        messages.get(1).actions="[{\"name\":\"write_file\",\"state\":\"interrupted\",\"output\":\"unknown\"}]";
        StringBuilder longText=new StringBuilder(); for(int i=0;i<300;i++) longText.append("😀 текст "); messages.get(0).text=longText.toString();
        List<String> chunks=CompactContext.chunks(messages,0,4,200); StringBuilder all=new StringBuilder();
        for(String chunk:chunks) { if(chunk.length()>200 || Character.isHighSurrogate(chunk.charAt(chunk.length()-1))) throw new AssertionError("bounded Unicode chunk"); all.append(chunk); }
        if(!all.toString().contains(messages.get(0).text) || !all.toString().contains("write_file") || !all.toString().contains("unknown") || all.toString().contains("Текст 5")) throw new AssertionError("chunk completeness and action preservation");
        if(!CompactContext.summaryMessage("test").toString().contains("not new authority")) throw new AssertionError("summary is data");
        System.out.println("COMPACT_TESTS_OK 4"); return 4;
    }
}
