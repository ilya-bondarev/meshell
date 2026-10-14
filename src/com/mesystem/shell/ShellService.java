package com.mesystem.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ShellService extends Service {
    public final class LocalBinder extends Binder { ShellService get() { return ShellService.this; } }
    public interface Listener { void changed(); }
    final TerminalModel terminal=new TerminalModel(30,80);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean updatePending=new AtomicBoolean();
    private final IBinder binder=new LocalBinder();
    private volatile Connection connection;
    private volatile Listener listener;
    volatile String status=Language.text(R.string.connecting);
    volatile boolean connected;
    private int reconnectsRemaining;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("terminal",Language.text(R.string.terminal),NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,"terminal")
            .setSmallIcon(com.mesystem.shell.R.drawable.notify).setContentTitle("MeShell")
            .setContentText(Language.text(R.string.terminal_agent_tasks_have_separate_notifications))
            .setContentIntent(pending).setOngoing(true).setShowWhen(false).build();
        if(Build.VERSION.SDK_INT>=34) startForeground(1,notification,0x40000000);
        else startForeground(1,notification);
        terminal.setReply(this::send);
        reconnect();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        Connection old=connection; connection=null;
        if(old!=null) old.close();
        listener=null; main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    void observe(Listener value) {
        listener=value;
        if(value!=null && !connected && connection!=null && connection.closed) reconnect();
        notifyChanged();
    }
    private void notifyChanged() {
        if(updatePending.compareAndSet(false,true)) main.postDelayed(() -> {
            updatePending.set(false);
            Listener current=listener;
            if(current!=null) current.changed();
        },16);
    }
    synchronized void reconnect() {
        reconnectsRemaining=8; connect();
    }
    private synchronized void connect() {
        Connection old=connection;
        Connection next=new Connection(); connection=next;
        if(old!=null) old.close();
        connected=false; status=Language.text(R.string.connecting); notifyChanged();
        new Thread(next,"meshell-reader").start();
    }
    boolean send(String value) { return send(value.getBytes(StandardCharsets.UTF_8)); }
    boolean send(byte[] data) {
        Connection current=connection;
        if(!connected || current==null) return false;
        if(data.length>32768) { status=Language.text(R.string.paste_is_limited_to_32_kib); notifyChanged(); return false; }
        return current.enqueue((byte)'D',data);
    }
    void resize(int rows,int columns) {
        terminal.resize(rows,columns);
        Connection current=connection;
        if(connected && current!=null) current.enqueue((byte)'R',new byte[]{
            (byte)(rows>>>8),(byte)rows,(byte)(columns>>>8),(byte)columns});
    }

    private static final class Packet {
        final byte type; final byte[] bytes;
        Packet(byte type,byte[] bytes) { this.type=type; this.bytes=bytes; }
    }
    private final class Connection implements Runnable {
        private final Socket socket=new Socket();
        private final ArrayBlockingQueue<Packet> writes=new ArrayBlockingQueue<>(64);
        private volatile boolean closed;
        private Thread writer;
        private boolean current() { return connection==this && !closed; }
        void close() {
            closed=true;
            try { socket.close(); } catch(IOException ignored) { }
            if(writer!=null) writer.interrupt();
            writes.clear();
        }
        boolean enqueue(byte type,byte[] data) {
            if(!current()) return false;
            if(writes.offer(new Packet(type,data))) return true;
            status=Language.text(R.string.input_queue_full_wait_for_the_command); notifyChanged(); return false;
        }
        private byte[] signature(byte[] key,String role,byte[] nonces) throws Exception {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key,"HmacSHA256"));
            mac.update(role.getBytes(StandardCharsets.US_ASCII)); return mac.doFinal(nonces);
        }
        @Override public void run() {
            try {
                socket.connect(new InetSocketAddress("127.0.0.1",38765),2500);
                socket.setTcpNoDelay(true); socket.setSoTimeout(4000);
                DataInputStream in=new DataInputStream(socket.getInputStream());
                DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                byte[] nonce=new byte[32], nonces=new byte[64], proof=new byte[32], key=new byte[32];
                new SecureRandom().nextBytes(nonce);
                String hex=BootstrapProvider.token(ShellService.this);
                for(int i=0;i<32;i++) key[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
                out.write("MESHL001".getBytes(StandardCharsets.US_ASCII)); out.write(nonce); out.flush();
                System.arraycopy(nonce,0,nonces,0,32); in.readFully(nonces,32,32); in.readFully(proof);
                if(!MessageDigest.isEqual(proof,signature(key,"server",nonces)))
                    throw new IOException(Language.text(R.string.broker_key_does_not_match));
                out.write(signature(key,"client",nonces));
                out.writeShort(terminal.rows()); out.writeShort(terminal.columns()); out.flush();
                java.util.Arrays.fill(key,(byte)0);
                socket.setSoTimeout(0);
                writer=new Thread(() -> {
                    try {
                        while(current()) {
                            Packet packet=writes.take();
                            out.writeByte(packet.type); out.writeInt(packet.bytes.length); out.write(packet.bytes); out.flush();
                        }
                    } catch(Exception error) { try { socket.close(); } catch(IOException ignored) { } }
                },"meshell-writer");
                writer.start();
                byte[] buffer=new byte[65536];
                while(current()) {
                    int type=in.readUnsignedByte(), length=in.readInt();
                    if(length<0 || length>buffer.length) throw new IOException(Language.text(R.string.invalid_packet));
                    in.readFully(buffer,0,length);
                    if(!current()) break;
                    if(type=='D') terminal.feed(buffer,length);
                    else if(type=='S') {
                        status=new String(buffer,0,length,StandardCharsets.UTF_8); connected=true;
                        Packages.automatic(ShellService.this);
                        // The keyboard/view can resize while the handshake is in flight.
                        resize(terminal.rows(),terminal.columns());
                    }
                    else if(type=='X') { status=Language.text(R.string.shell_exited_tap_to_reconnect); connected=false; }
                    else throw new IOException(Language.text(R.string.unknown_packet_type));
                    notifyChanged();
                }
            } catch(Exception error) {
                if(current()) {
                    connected=false;
                    status=Language.text(R.string.start_the_shell_through_adb_then_tap);
                    android.util.Log.w("MeShell","Local broker: "+error.getClass().getSimpleName()+": "+error.getMessage());
                    notifyChanged();
                    main.postDelayed(() -> {
                        if(connection==this && !connected && listener!=null && reconnectsRemaining>0) {
                            reconnectsRemaining--; connect();
                        }
                    },1000);
                }
            } finally { close(); }
        }
    }
}
