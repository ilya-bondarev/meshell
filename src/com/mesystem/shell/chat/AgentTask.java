package com.mesystem.shell;

import java.util.concurrent.atomic.AtomicInteger;

final class AgentTask {
    private static final AtomicInteger ids=new AtomicInteger(100);
    final int notificationId=ids.incrementAndGet();
    final long started=System.currentTimeMillis(),thread;
    final OpenAiClient.Cancel token;
    final ChatStore.Message answer;
    final AgentConfig config;
    volatile String title,phase=Language.text(R.string.responding),state=Language.text(R.string.running),actualModel="";
    volatile long step;
    volatile int totalTokens,lastInputTokens,contextEstimate;
    AgentTask(ChatStore.Message answer,AgentConfig config,OpenAiClient.Cancel token,String title) {
        this.answer=answer; this.thread=answer.thread; this.config=config; this.token=token; this.title=title;
    }
    String label() { return config.name+" · "+title; }
}
