package com.mesystem.shell;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import java.util.*;

public final class AgentService extends Service {
    private final Handler main=new Handler(Looper.getMainLooper());
    private ChatEngine chat;
    private PowerManager.WakeLock wake;
    private long renewed;
    private int lastStartId;
    private boolean stopping;
    private List<AgentTask> observed=new ArrayList<>();
    private final Set<Integer> displayed=new HashSet<>();
    private static Uri identity(AgentTask task) { return Uri.parse("meshell://task/"+task.thread+"/"+task.started+"/"+task.notificationId); }
    private static Notification notification(Context context,AgentTask task,boolean active) {
        Intent openIntent=new Intent(context,MainActivity.class).setData(identity(task))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("open-chat",true).putExtra("open-thread",task.thread);
        PendingIntent open=PendingIntent.getActivity(context,task.notificationId,openIntent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String text=task.label()+"\n"+(active?task.phase+Language.text(R.string.step)+task.step:task.state);
        Notification.Builder b=new Notification.Builder(context,"agent").setSmallIcon(R.drawable.notify)
            .setContentTitle(active?Language.text(R.string.meshell_agent_running):Language.text(R.string.meshell_task_result))
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active).setAutoCancel(!active).setShowWhen(false);
        if(active) {
            Intent stopIntent=new Intent(context,AgentService.class).setData(identity(task)).setAction("stop")
                .putExtra("task-thread",task.thread).putExtra("task-identity",task.notificationId).putExtra("task-started",task.started);
            PendingIntent stop=PendingIntent.getService(context,task.notificationId,stopIntent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null,Language.text(R.string.stop),stop).build());
        }
        return b.build();
    }
    static void result(Context context,AgentTask task) {
        try { context.getSystemService(NotificationManager.class).notify(task.notificationId,notification(context,task,false)); }
        catch(Exception ignored) { }
    }
    private void foreground() {
        List<AgentTask> tasks=chat.activeTasks(); StringBuilder text=new StringBuilder();
        for(AgentTask task:tasks) text.append(task.label()).append(" · ").append(task.phase).append('\n');
        Intent intent=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open-chat",true);
        if(!tasks.isEmpty()) intent.putExtra("open-thread",tasks.get(0).thread);
        PendingIntent open=PendingIntent.getActivity(this,2,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"agent").setSmallIcon(R.drawable.notify)
            .setContentTitle(Language.text(R.string.meshell_active_tasks)+tasks.size()).setContentText(text.length()==0?Language.text(R.string.starting_tasks):text.toString())
            .setStyle(new Notification.BigTextStyle().bigText(text.toString())).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();
        if(Build.VERSION.SDK_INT>=34) startForeground(2,n,0x40000000); else startForeground(2,n);
    }
    private final Runnable pulse=new Runnable() { public void run() {
        observed=chat.activeTasks();
        if(observed.isEmpty()) {
            stopping=true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(lastStartId); return;
        }
        long now=SystemClock.elapsedRealtime();
        if(wake!=null && now-renewed>300000) try { if(wake.isHeld()) wake.release(); wake.acquire(600000); renewed=now; } catch(Exception ignored) { wake=null; }
        foreground(); NotificationManager manager=getSystemService(NotificationManager.class);
        for(AgentTask task:observed) {
            // Engine posts a completion notification when a task ends. Never overwrite it with a stale active snapshot.
            if(chat.task(task.thread)==task) { manager.notify(task.notificationId,notification(AgentService.this,task,true)); displayed.add(task.notificationId); }
        }
        main.postDelayed(this,1000);
    }};
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("agent",Language.text(R.string.agent_tasks),NotificationManager.IMPORTANCE_LOW));
        chat=((MeShellApp)getApplication()).chat(); observed=chat.activeTasks(); foreground();
        try { wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MeShell:Agent"); wake.setReferenceCounted(false); wake.acquire(600000); renewed=SystemClock.elapsedRealtime(); }
        catch(Exception ignored) { wake=null; }
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        lastStartId=id;
        if(intent!=null && "stop".equals(intent.getAction())) {
            AgentTask target=chat.task(intent.getLongExtra("task-thread",-1));
            if(target!=null && target.started==intent.getLongExtra("task-started",-1)) chat.cancel(target.thread,intent.getIntExtra("task-identity",-1));
        }
        stopping=false; observed=chat.activeTasks(); foreground();
        main.removeCallbacks(pulse); main.post(pulse); return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null); if(wake!=null && wake.isHeld()) wake.release();
        if(!stopping && chat!=null) for(AgentTask task:observed) chat.cancel(task.thread,task.notificationId);
        super.onDestroy();
    }
}
