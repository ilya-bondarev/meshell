package com.mesystem.shell;

import org.json.JSONObject;

final class AgentConfig {
    final String name,model,effort,instructions;
    final int context;
    final boolean tools;
    AgentConfig(String name,String model,String effort,int context,boolean tools,String instructions) {
        this.name=clean(name,48,Language.text(R.string.primary)); this.model=clean(model,100,OpenAiClient.DEFAULT_MODEL);
        this.effort=clean(effort,24,"medium"); this.context=Math.max(4096,Math.min(1048576,context));
        this.tools=tools; this.instructions=instructions==null?"":instructions.trim();
    }
    private static String clean(String text,int max,String fallback) {
        String value=text==null?"":text.trim(); if(value.isEmpty()) return fallback;
        if(value.length()>max) throw new IllegalArgumentException(Language.text(R.string.value_too_long)); return value;
    }
    String encode() {
        try { return new JSONObject().put("name",name).put("model",model).put("effort",effort)
            .put("context",context).put("tools",tools).put("instructions",instructions).toString(); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    static AgentConfig decode(String text,AgentConfig fallback) {
        try { JSONObject o=new JSONObject(text); return new AgentConfig(o.optString("name",fallback.name),o.optString("model",fallback.model),
            o.optString("effort",fallback.effort),o.optInt("context",fallback.context),o.optBoolean("tools",fallback.tools),o.optString("instructions","")); }
        catch(Exception e) { return fallback; }
    }
    AgentConfig model(String selected,String depth) { return new AgentConfig(name,selected,depth,context,tools,instructions); }
    AgentConfig options(int budget,boolean enabled) { return new AgentConfig(name,model,effort,budget,enabled,instructions); }
}
