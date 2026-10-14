package com.mesystem.shell;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

final class ShellBroker {
    static final String ROOT="/data/local/tmp/meshell",HOME=ROOT+"/home",TOOLS=ROOT+"/toolusr",SOURCE=HOME+"/source";
    private final Context context;
    ShellBroker(Context context) { this.context=context; }
    static String quote(String value) { return "'"+value.replace("'","'\\''")+"'"; }
    /** Packs small private files as a ustar stream for a command's stdin; the receiving command extracts them. */
    static byte[] tar(Map<String,byte[]> files) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(Map.Entry<String,byte[]> entry:files.entrySet()) {
            byte[] h=new byte[512],name=entry.getKey().getBytes(StandardCharsets.UTF_8); if(name.length>99 || entry.getKey().contains("..")) throw new IllegalArgumentException("Invalid tar entry");
            System.arraycopy(name,0,h,0,name.length); octal(h,100,8,0600); octal(h,108,8,2000); octal(h,116,8,2000);
            octal(h,124,12,entry.getValue().length); octal(h,136,12,System.currentTimeMillis()/1000);
            java.util.Arrays.fill(h,148,156,(byte)' '); h[156]='0'; System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII),0,h,257,5); h[263]='0'; h[264]='0';
            long sum=0; for(byte b:h) sum+=b&255; octal(h,148,8,sum);
            out.write(h); out.write(entry.getValue()); int padding=(512-entry.getValue().length%512)%512; out.write(new byte[padding]);
        }
        out.write(new byte[1024]); return out.toByteArray();
    }
    private static void octal(byte[] target,int at,int size,long value) {
        byte[] s=Long.toOctalString(value).getBytes(StandardCharsets.US_ASCII);
        java.util.Arrays.fill(target,at,at+size-1,(byte)'0'); System.arraycopy(s,0,target,at+size-1-s.length,s.length);
    }
    static final class Result {
        final String output; final int exitCode; final boolean timedOut,truncated;
        Result(String output,int exitCode,int flags) { this.output=output; this.exitCode=exitCode; timedOut=(flags&1)!=0; truncated=(flags&2)!=0; }
        JSONObject json() throws Exception { return new JSONObject().put("output",output).put("exit_code",exitCode).put("timed_out",timedOut).put("truncated",truncated); }
    }
    private static byte[] mac(byte[] key,String role,byte[] nonce) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256"));
        mac.update(role.getBytes(StandardCharsets.US_ASCII)); return mac.doFinal(nonce);
    }
    Result run(String command,String cwd,byte[] input,Map<String,String> env,int timeout,OpenAiClient.Cancel cancel) throws Exception {
        if(command.indexOf('\0')>=0 || cwd.indexOf('\0')>=0) throw new IOException(Language.text(R.string.nul_is_not_allowed_in_command_path));
        try(Socket socket=new Socket()) {
            cancel.attachSocket(socket);
            try {
                socket.connect(new InetSocketAddress("127.0.0.1",38765),2500); socket.setTcpNoDelay(true); socket.setSoTimeout(5000);
                DataInputStream in=new DataInputStream(socket.getInputStream()); DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                byte[] nonce=new byte[64],proof=new byte[32],key=new byte[32];
                byte[] first=new byte[32]; new SecureRandom().nextBytes(first); System.arraycopy(first,0,nonce,0,32);
                String hex=BootstrapProvider.token(context);
                for(int i=0;i<32;i++) key[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
                out.write("MESHL001".getBytes(StandardCharsets.US_ASCII)); out.write(first); out.flush();
                in.readFully(nonce,32,32); in.readFully(proof);
                if(!MessageDigest.isEqual(proof,mac(key,"server",nonce))) throw new IOException(Language.text(R.string.broker_key_does_not_match));
                out.write(mac(key,"client",nonce)); out.writeInt(0); out.flush(); java.util.Arrays.fill(key,(byte)0);
                int initial=in.readUnsignedByte(),len=in.readInt();
                if(initial!='S' || len<1 || len>256) throw new IOException(Language.text(R.string.updated_exec_v1_broker_required));
                byte[] status=new byte[len]; in.readFully(status);
                if(!new String(status,StandardCharsets.UTF_8).contains("exec-v1")) throw new IOException(Language.text(R.string.broker_does_not_support_agent_commands));
                ByteArrayOutputStream envBytes=new ByteArrayOutputStream();
                if(env!=null) for(Map.Entry<String,String> entry:env.entrySet()) {
                    if(!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*") || entry.getValue().indexOf('\0')>=0) throw new IOException(Language.text(R.string.invalid_environment_variable));
                    envBytes.write((entry.getKey()+"="+entry.getValue()+"\0").getBytes(StandardCharsets.UTF_8));
                }
                byte[] directory=cwd.getBytes(StandardCharsets.UTF_8),script=command.getBytes(StandardCharsets.UTF_8),variables=envBytes.toByteArray();
                if(input==null) input=new byte[0]; timeout=Math.max(100,Math.min(900000,timeout));
                int length=20+directory.length+script.length+input.length+variables.length;
                if(length>2*1024*1024) throw new IOException(Language.text(R.string.command_exceeds_2_mib));
                out.writeByte('E'); out.writeInt(length); out.writeInt(timeout);
                out.writeInt(directory.length); out.writeInt(script.length); out.writeInt(input.length); out.writeInt(variables.length);
                out.write(directory); out.write(script); out.write(input); out.write(variables); out.flush();
                socket.setSoTimeout(timeout+12000);
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                while(true) {
                    cancel.check(); int type=in.readUnsignedByte(),size=in.readInt();
                    if(size<0 || size>65536) throw new IOException(Language.text(R.string.damaged_shell_response));
                    byte[] data=new byte[size]; in.readFully(data);
                    if(type=='D') {
                        if(output.size()+size>512*1024) throw new IOException(Language.text(R.string.shell_response_too_large));
                        output.write(data);
                    } else if(type=='X' && size==12) {
                        java.nio.ByteBuffer values=java.nio.ByteBuffer.wrap(data);
                        return new Result(new String(output.toByteArray(),StandardCharsets.UTF_8),values.getInt(),values.getInt());
                    } else throw new IOException(Language.text(R.string.unknown_shell_response));
                }
            } finally { cancel.detachSocket(socket); }
        }
    }
    Result run(String command,int timeout,OpenAiClient.Cancel cancel) throws Exception {
        return run(command,HOME,null,null,timeout,cancel);
    }
}
