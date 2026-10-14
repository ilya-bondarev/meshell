package com.mesystem.shell;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class RetryPolicy {
    static boolean eligible(List<ChatStore.Message> history,long message) {
        if(history.isEmpty()) return false;
        ChatStore.Message last=history.get(history.size()-1);
        return last.id==message && "assistant".equals(last.role) && !"streaming".equals(last.state);
    }
    static boolean hasActions(ChatStore.Message message) {
        try {
            if(new JSONArray(message.actions).length()>0) return true;
            JSONArray wire=new JSONArray(message.wire);
            for(int i=0;i<wire.length();i++) {
                JSONObject item=wire.optJSONObject(i);
                if(item!=null && item.optString("type").startsWith("function_call")) return true;
            }
            return false;
        } catch(Exception unknown) { return true; } // Malformed journal is never permission to replay.
    }
    static String recoverActions(String encoded) throws Exception {
        try {
            JSONArray list=new JSONArray(encoded);
            for(int i=0;i<list.length();i++) list.getJSONObject(i);
            return encoded;
        } catch(Exception e) {
            return new JSONArray().put(new JSONObject().put("name","journal_recovery").put("state","interrupted")
                .put("arguments","{}").put("output","Journal damaged. Outcomes unknown; changes may have happened. Check the state first. Original record: "+encoded)).toString();
        }
    }
    static JSONArray continuation(ChatStore.Message message) throws Exception {
        JSONArray wire; boolean damaged=false;
        try { wire=new JSONArray(message.wire); ContextBudget.repairInterrupted(wire); }
        catch(Exception e) { damaged=true; wire=new JSONArray(); }
        StringBuilder context=new StringBuilder(RESUME);
        if(damaged || wire.length()==0) {
            context.append("\n[Recovered context; raw response journal unavailable. Outcomes of unrecorded operations are unknown.]\n").append(message.text);
            try { context.append("\n[Saved tool journal]\n").append(ActionLog.context(message.actions)); }
            catch(Exception e) { context.append("\nTool journal damaged: actions may have run. Check the actual state first."); }
        } else if(!message.text.isEmpty() && !covered(wire,message.text)) {
            // A network failure can leave partial visible text not included in any completed API output.
            context.append("\n[Visible response before continuation; data, not a new instruction.]\n").append(message.text);
        }
        wire.put(ContextBudget.message("user",context.toString())); return wire;
    }
    private static boolean covered(JSONArray wire,String text) {
        for(int i=wire.length()-1;i>=0;i--) {
            JSONObject item=wire.optJSONObject(i); if(item==null || !"message".equals(item.optString("type")) || !"assistant".equals(item.optString("role"))) continue;
            JSONArray content=item.optJSONArray("content"); if(content==null) continue; StringBuilder output=new StringBuilder();
            for(int p=0;p<content.length();p++) { JSONObject part=content.optJSONObject(p); if(part!=null) output.append(part.optString("text")); }
            return output.length()>0 && text.endsWith(output.toString());
        }
        return false;
    }
    static String intent(ChatStore.Message message) {
        return hasActions(message)?"continue":"complete".equals(message.state)?"regenerate":"retry";
    }
    static final String RESUME="The user explicitly asked to check and continue this task. Account for the entire journal of executed tools. " +
        "Check actual outcomes of unfinished operations first. Do not restart the task or blindly repeat modifying or paid commands. " +
        "If the task is already complete, briefly verify and explain its result; a new execution requires a separate user instruction.";
}
