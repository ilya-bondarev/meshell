package com.mesystem.shell;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ActionLog {
    private static final Pattern LEGACY=Pattern.compile("(?s)\\n\\n\\*\\*▶ ([a-z_]+)\\*\\*\\n```json\\n(.*?)\\n```\\n(?:```text\\n(.*?)\\n```\\n)?");
    static String migrate(String text,JSONArray actions) throws Exception {
        Matcher match=LEGACY.matcher(text); StringBuffer clean=new StringBuffer();
        while(match.find()) {
            actions.put(new JSONObject().put("name",match.group(1)).put("arguments",match.group(2))
                .put("output",match.group(3)==null?"":match.group(3)).put("state",match.group(3)==null?"interrupted":"done"));
            match.appendReplacement(clean,"");
        }
        match.appendTail(clean); return clean.toString().trim();
    }
    static String context(String encoded) throws Exception {
        JSONArray actions;
        try { actions=new JSONArray(encoded); }
        catch(Exception e) { return "\nTool journal is damaged. Actions may have run: check the actual state first.\n"; }
        StringBuilder out=new StringBuilder();
        for(int i=0;i<actions.length();i++) {
            JSONObject a=actions.optJSONObject(i);
            if(a==null) { out.append("\nDamaged action record; outcome unknown. Check the state first.\n"); continue; }
            String value=a.optString("output");
            if(value.length()>8000) value=value.substring(0,8000)+" [long output truncated]";
            out.append('\n').append(a.optString("name")).append(' ').append(a.optString("arguments"))
                .append(" → ").append(a.optString("state")).append('\n').append(value).append('\n');
        }
        return out.toString();
    }
}
