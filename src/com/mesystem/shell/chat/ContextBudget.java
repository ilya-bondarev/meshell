package com.mesystem.shell;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class ContextBudget {
    static int estimate(Object value) throws Exception {
        if(value instanceof String) {
            String s=(String)value;
            if(s.startsWith("data:image/")) return 2048;
            return Math.max(1,(s.getBytes(StandardCharsets.UTF_8).length+2)/3);
        }
        if(value instanceof JSONArray) { int sum=0; JSONArray a=(JSONArray)value; for(int i=0;i<a.length();i++) sum+=estimate(a.get(i)); return sum; }
        if(value instanceof JSONObject) {
            int sum=12; JSONObject o=(JSONObject)value; java.util.Iterator<String> names=o.keys();
            while(names.hasNext()) { String name=names.next(); if(name.startsWith("_attachment")) continue; sum+=name.length()/3+estimate(o.get(name)); } return sum+o.optInt("_attachment_cost");
        }
        return 1;
    }
    static JSONObject message(String role,String text) throws Exception {
        return new JSONObject().put("role",role).put("content",new JSONArray().put(new JSONObject()
            .put("type",role.equals("user")?"input_text":"output_text").put("text",text)));
    }
    static JSONArray history(List<ChatStore.Message> messages,long exclude,int budget) throws Exception {
        List<JSONArray> turns=new ArrayList<>(); JSONArray turn=null;
        for(ChatStore.Message message:messages) {
            if(message.id==exclude) continue;
            if("user".equals(message.role)) { turn=new JSONArray(); turns.add(turn); }
            String text=message.text;
            if(!"[]".equals(message.actions)) text+="\n[Executed tools]"+ActionLog.context(message.actions);
            if(turn!=null && (!text.isEmpty() || !message.attachments.equals("[]"))) {
                JSONObject entry=message(message.role,text);
                if("user".equals(message.role) && !message.attachments.equals("[]")) {
                    JSONArray files=new JSONArray(message.attachments);
                    entry.put("_attachments",files).put("_attachment_cost",Attachments.estimate(files));
                }
                turn.put(entry);
            }
        }
        int first=turns.size(),used=0;
        while(first>0) {
            int cost=estimate(turns.get(first-1));
            if(used+cost>budget) break;
            used+=cost; first--;
        }
        if(first==turns.size() && !turns.isEmpty()) throw new IllegalArgumentException("The last message exceeds the context window. Increase the budget in settings.");
        JSONArray result=new JSONArray();
        for(int i=first;i<turns.size();i++) append(result,turns.get(i));
        return result;
    }
    static void materializeAttachments(JSONArray input) throws Exception {
        for(int i=0;i<input.length();i++) {
            JSONObject entry=input.getJSONObject(i); JSONArray files=entry.optJSONArray("_attachments");
            entry.remove("_attachments"); entry.remove("_attachment_cost");
            if(files!=null) Attachments.append(entry.getJSONArray("content"),files);
        }
    }
    static void append(JSONArray target,JSONArray source) throws Exception { for(int i=0;i<source.length();i++) target.put(source.get(i)); }
    static void repairInterrupted(JSONArray wire) throws Exception {
        java.util.LinkedHashSet<String> pending=new java.util.LinkedHashSet<>();
        for(int i=0;i<wire.length();i++) {
            JSONObject item=wire.getJSONObject(i);
            if("function_call".equals(item.optString("type"))) pending.add(item.getString("call_id"));
            if("function_call_output".equals(item.optString("type"))) pending.remove(item.getString("call_id"));
        }
        for(String call:pending) wire.put(new JSONObject().put("type","function_call_output").put("call_id",call)
            .put("output","Execution was interrupted. Outcome unconfirmed. Check the actual state; do not blindly repeat a modifying command."));
    }
}
