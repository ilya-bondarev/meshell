package com.mesystem.shell;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Floating fox showing the weekly ChatGPT allowance; started only from the Mascot module. */
public final class MascotService extends Service {
    static volatile boolean running;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WindowManager manager;
    private LinearLayout overlay;
    private TextView balance;
    private FoxView fox;
    private ChatEngine chat;
    private WindowManager.LayoutParams position;
    private float downX,downY;
    private int startX,startY;
    private boolean dragged;
    private final Runnable pulse=new Runnable() { public void run() {
        if(!Settings.canDrawOverlays(MascotService.this)) { stopSelf(); return; }
        double left=chat.weeklyRemaining();
        balance.setText(chat.authenticated && !Double.isNaN(left)?String.format(Language.locale(),"%.0f%%",left):"??");
        fox.invalidate();
        handler.postDelayed(this,1000);
    }};
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("mascot",Language.text(R.string.mascot_title),NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,73,new Intent(this,MascotService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"mascot").setSmallIcon(R.drawable.notify).setContentTitle(Language.text(R.string.mascot_title)).setContentText(Language.text(R.string.mascot_description)).setOngoing(true).addAction(new Notification.Action.Builder(null,Language.text(R.string.stop),stop).build()).build();
        if(Build.VERSION.SDK_INT>=34) startForeground(73,notification,0x40000000); else startForeground(73,notification);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent!=null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if(!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        if(overlay!=null) return START_NOT_STICKY;
        chat=((MeShellApp)getApplication()).chat();
        manager=getSystemService(WindowManager.class);
        overlay=Ui.column(this);
        fox=new FoxView(this,false); overlay.addView(fox,Ui.size(this,112,96));
        balance=Ui.text(this,"??",11,Ui.MINT); balance.setGravity(Gravity.CENTER);
        balance.setShadowLayer(Ui.dp(this,2),0,Ui.dp(this,1),0xcc000000);
        overlay.addView(balance,Ui.size(this,112,-2));
        position=new WindowManager.LayoutParams(Ui.dp(this,112),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        position.gravity=Gravity.TOP|Gravity.LEFT; position.x=0; position.y=Ui.dp(this,160);
        View.OnTouchListener touch=(view,event) -> {
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: downX=event.getRawX(); downY=event.getRawY(); startX=position.x; startY=position.y; dragged=false; return true;
                case MotionEvent.ACTION_MOVE:
                    float dx=event.getRawX()-downX,dy=event.getRawY()-downY;
                    if(Math.abs(dx)+Math.abs(dy)>ViewConfiguration.get(this).getScaledTouchSlop()) dragged=true;
                    if(dragged) {
                        android.graphics.Rect bounds=manager.getCurrentWindowMetrics().getBounds();
                        position.x=Math.max(0,Math.min(bounds.width()-overlay.getWidth(),startX+(int)dx));
                        position.y=Math.max(0,Math.min(bounds.height()-overlay.getHeight(),startY+(int)dy));
                        manager.updateViewLayout(overlay,position);
                    } return true;
                case MotionEvent.ACTION_UP: if(!dragged) view.performClick(); return true;
                case MotionEvent.ACTION_CANCEL: return true;
                default: return false;
            }
        };
        fox.setOnTouchListener(touch); balance.setOnTouchListener(touch);
        View.OnClickListener tapped=v -> {
            fox.cheer();
            fox.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            chat.refreshLimits();
        };
        fox.setOnClickListener(tapped); balance.setOnClickListener(tapped);
        manager.addView(overlay,position); running=true; handler.post(pulse); chat.refreshLimits();
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        running=false; handler.removeCallbacksAndMessages(null);
        if(overlay!=null) manager.removeView(overlay);
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
