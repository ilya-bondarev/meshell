package com.mesystem.shell;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Installs Termux packages into the shell prefix without the Termux app. Each package is checked against the
 * SHA-256 in the repository index, its fixed prefix is rewritten to the MeShell prefix (equal length, so ELF
 * offsets are preserved), and the result is extracted by the ADB-shell broker.
 */
final class Packages {
    static final String MIRROR="https://packages.termux.dev/apt/termux-main/";
    static final String[] CORE={"codex","git","curl","openssh","zip","unzip"};
    static final String[] BUILD={"clang","lld","make","openjdk-21","aapt2","aapt","sshpass","android-tools"};
    private static final Set<String> SKIP=new HashSet<>(Arrays.asList("termux-tools","termux-am","termux-am-socket","termux-keyring"));
    private static final byte[] OLD="/data/data/com.termux/files/usr".getBytes(StandardCharsets.US_ASCII),NEW=ShellBroker.TOOLS.getBytes(StandardCharsets.US_ASCII);
    private static final String PREFIX="data/data/com.termux/files/usr/",STAGE=ShellBroker.ROOT+"/.packages",MANIFEST=ShellBroker.TOOLS+"/toolchain.json";
    private static final Pattern NAME=Pattern.compile("[a-z0-9][a-z0-9+.-]*");
    private static final int CHUNK=1792*1024;
    private static final String AGENT="MeShell-packages";
    private static final AtomicBoolean running=new AtomicBoolean();
    interface Progress { void update(String text); }

    static final class Item {
        final String name,version,file,sha256,depends; final long size,installed;
        Item(Map<String,String> f) {
            name=f.get("Package"); version=f.get("Version"); file=f.get("Filename"); sha256=f.get("SHA256").toLowerCase(Locale.ROOT);
            depends=(f.containsKey("Pre-Depends")?f.get("Pre-Depends")+",":"")+(f.containsKey("Depends")?f.get("Depends"):"");
            size=number(f.get("Size")); installed=number(f.get("Installed-Size"))*1024;
        }
        private static long number(String value) { try { return Long.parseLong(value.trim()); } catch(Exception e) { return 0; } }
    }

    private final Context context;
    private final ShellBroker shell;
    Packages(Context context) { this.context=context.getApplicationContext(); shell=new ShellBroker(this.context); }
    static boolean busy() { return running.get(); }

    static Map<String,Item> parse(String text) {
        Map<String,Item> result=new HashMap<>();
        for(String block:text.split("\n\n")) {
            Map<String,String> fields=new HashMap<>();
            for(String line:block.split("\n")) {
                int at=line.indexOf(": ");
                if(at>0 && !Character.isWhitespace(line.charAt(0))) fields.put(line.substring(0,at),line.substring(at+2).trim());
            }
            String name=fields.get("Package");
            if(name!=null && fields.containsKey("Version") && fields.containsKey("Filename") && fields.containsKey("SHA256") && !result.containsKey(name))
                result.put(name,new Item(fields));
        }
        return result;
    }
    /** Packages and their dependencies, dependencies first. */
    static List<Item> closure(Map<String,Item> index,Collection<String> names) throws IOException {
        LinkedHashMap<String,Item> selected=new LinkedHashMap<>(); Set<String> visiting=new HashSet<>();
        for(String name:names) visit(index,name,selected,visiting);
        return new ArrayList<>(selected.values());
    }
    private static void visit(Map<String,Item> index,String name,Map<String,Item> selected,Set<String> visiting) throws IOException {
        if(selected.containsKey(name) || SKIP.contains(name) || !visiting.add(name)) return;
        Item item=index.get(name);
        if(item==null) throw new IOException(Language.text(R.string.unknown_package)+name);
        for(String group:item.depends.split(",")) {
            if(group.trim().isEmpty()) continue;
            String found=null;
            for(String alternative:group.split("\\|")) {
                Matcher m=NAME.matcher(alternative.trim());
                if(m.lookingAt() && (index.containsKey(m.group()) || SKIP.contains(m.group()))) { found=m.group(); break; }
            }
            if(found==null) throw new IOException(Language.text(R.string.unknown_package)+group.trim());
            visit(index,found,selected,visiting);
        }
        selected.put(name,item);
    }

    private static String index(OpenAiClient.Cancel cancel) throws IOException {
        return new String(Http.get(MIRROR+"dists/stable/main/binary-aarch64/Packages",AGENT,32*1024*1024,cancel),StandardCharsets.UTF_8);
    }
    private static void download(Item item,File target,OpenAiClient.Cancel cancel) throws Exception {
        if(!item.file.matches("pool/[A-Za-z0-9+._/~:%-]+\\.deb") || item.file.contains("..")) throw new IOException("Invalid package path: "+item.file);
        HttpsURLConnection connection=Http.open(MIRROR+item.file,AGENT,cancel);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=connection.getInputStream(); OutputStream out=new FileOutputStream(target)) {
            byte[] buffer=new byte[65536]; int n; long total=0;
            while((n=in.read(buffer))>0) { cancel.check(); digest.update(buffer,0,n); out.write(buffer,0,n); total+=n; if(total>item.size+1024) throw new IOException("Package is larger than indexed: "+item.name); }
        } finally { cancel.detach(connection); connection.disconnect(); }
        StringBuilder hex=new StringBuilder(); for(byte b:digest.digest()) hex.append(String.format(Locale.ROOT,"%02x",b));
        if(!hex.toString().equals(item.sha256)) throw new IOException(Language.text(R.string.package_checksum_mismatch)+item.name);
    }

    private JSONObject manifest(OpenAiClient.Cancel cancel) throws Exception {
        ShellBroker.Result result=shell.run("cat "+ShellBroker.quote(MANIFEST)+" 2>/dev/null",10000,cancel);
        try { if(result.exitCode==0 && !result.truncated) return new JSONObject(result.output); } catch(Exception ignored) { }
        return new JSONObject();
    }
    private void save(JSONObject packages,OpenAiClient.Cancel cancel) throws Exception {
        byte[] data=new JSONObject().put("prefix",ShellBroker.TOOLS).put("source",MIRROR).put("packages",packages).toString(2).getBytes(StandardCharsets.UTF_8);
        String next=MANIFEST+".next";
        ShellBroker.Result result=shell.run("mkdir -p "+ShellBroker.quote(ShellBroker.TOOLS)+" && cat > "+ShellBroker.quote(next)+" && mv -f "+ShellBroker.quote(next)+" "+ShellBroker.quote(MANIFEST),
            ShellBroker.HOME,data,null,10000,cancel);
        if(result.exitCode!=0) throw new IOException(result.output);
    }
    /** Installed package versions recorded by previous installations. */
    JSONObject installed(OpenAiClient.Cancel cancel) throws Exception {
        JSONObject packages=manifest(cancel).optJSONObject("packages"); return packages==null?new JSONObject():packages;
    }
    private long free(OpenAiClient.Cancel cancel) throws Exception {
        ShellBroker.Result result=shell.run("df -k "+ShellBroker.quote(ShellBroker.ROOT)+" | tail -n 1",10000,cancel);
        String[] columns=result.output.trim().split("\\s+");
        try { return Long.parseLong(columns[3])*1024; } catch(Exception e) { return Long.MAX_VALUE; }
    }

    /**
     * Installs the named packages with their dependencies. With {@code upgrade}, every recorded package is
     * also brought to the index version. Returns what changed.
     */
    JSONObject install(Collection<String> wanted,boolean upgrade,Progress progress,OpenAiClient.Cancel cancel) throws Exception {
        for(String name:wanted) if(!NAME.matcher(name).matches()) throw new IllegalArgumentException(Language.text(R.string.unknown_package)+name);
        if(!running.compareAndSet(false,true)) throw new IllegalStateException(Language.text(R.string.packages_busy));
        File cache=new File(context.getCacheDir(),"packages");
        try {
            JSONObject packages;
            try { packages=installed(cancel); }
            catch(IOException e) { if(cancel.stopped) throw e; throw new IOException(Language.text(R.string.packages_need_shell),e); }
            progress.update(Language.text(R.string.reading_package_index));
            Map<String,Item> index=parse(index(cancel));
            Set<String> names=new LinkedHashSet<>(wanted);
            if(upgrade) { Iterator<String> keys=packages.keys(); while(keys.hasNext()) { String key=keys.next(); if(index.containsKey(key)) names.add(key); } }
            List<Item> todo=new ArrayList<>(); long download=0,unpacked=0,largest=0;
            for(Item item:closure(index,names)) {
                JSONObject current=packages.optJSONObject(item.name);
                if(current!=null && item.version.equals(current.optString("version"))) continue;
                todo.add(item); download+=item.size; unpacked+=item.installed; largest=Math.max(largest,item.installed);
            }
            JSONArray changed=new JSONArray();
            if(todo.isEmpty()) return new JSONObject().put("changed",changed).put("up_to_date",true).put("codex",version(packages,"codex"));
            long need=unpacked+largest+64L*1024*1024,available=free(cancel);
            if(available<need) throw new IOException(String.format(Language.locale(),Language.text(R.string.not_enough_space_for_packages),need>>20,available>>20));
            progress.update(String.format(Language.locale(),Language.text(R.string.packages_plan),todo.size(),download>>20,unpacked>>20));
            ShellBroker.Result stage=shell.run("rm -rf "+ShellBroker.quote(STAGE)+" && mkdir -p "+ShellBroker.quote(STAGE),10000,cancel);
            if(stage.exitCode!=0) throw new IOException(stage.output);
            if(!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Cannot create "+cache);
            File deb=new File(cache,"package.deb");
            for(int i=0;i<todo.size();i++) {
                Item item=todo.get(i); cancel.check();
                progress.update(String.format(Language.locale(),Language.text(R.string.downloading_package),i+1,todo.size(),item.name,item.version,Math.max(1,item.size>>20)));
                download(item,deb,cancel);
                progress.update(String.format(Language.locale(),Language.text(R.string.unpacking_package),i+1,todo.size(),item.name));
                String tar=STAGE+"/"+item.name+".tar";
                try(Upload upload=new Upload(tar,cancel)) { repack(deb,upload); }
                ShellBroker.Result result=shell.run("tar -xf "+ShellBroker.quote(tar)+" -C "+ShellBroker.quote(ShellBroker.ROOT)+"; code=$?; rm -f "+ShellBroker.quote(tar)+"; exit $code",
                    ShellBroker.HOME,null,null,600000,cancel);
                if(result.exitCode!=0) throw new IOException(item.name+": "+result.output);
                packages.put(item.name,new JSONObject().put("version",item.version).put("sha256",item.sha256));
                save(packages,cancel); changed.put(item.name+" "+item.version);
            }
            ShellBroker.Result finish=shell.run("cd "+ShellBroker.quote(ShellBroker.ROOT)+" || exit 1; rm -rf .packages; "+
                "{ [ -e usr ] || [ -L usr ] || ln -s toolusr usr; }; mkdir -p home/tmp home/bin home/source home/build; chmod -R u+rwX toolusr; "+
                "for n in java javac jar javap keytool jdeps jlink; do if [ -x toolusr/lib/jvm/java-21-openjdk/bin/$n ]; then ln -sf ../lib/jvm/java-21-openjdk/bin/$n toolusr/bin/$n; fi; done; true",
                120000,cancel);
            if(finish.exitCode!=0) throw new IOException(finish.output);
            return new JSONObject().put("changed",changed).put("up_to_date",false).put("codex",version(packages,"codex"));
        } finally {
            File[] files=cache.listFiles(); if(files!=null) for(File file:files) file.delete();
            try { shell.run("rm -rf "+ShellBroker.quote(STAGE),10000,new OpenAiClient.Cancel()); } catch(Exception ignored) { }
            running.set(false);
        }
    }
    /** Installs whichever of the named packages are missing; a no-op when all are recorded. */
    void ensure(Collection<String> names,OpenAiClient.Cancel cancel) throws Exception {
        JSONObject packages=installed(cancel); List<String> missing=new ArrayList<>();
        for(String name:names) if(version(packages,name).isEmpty()) missing.add(name);
        if(!missing.isEmpty()) install(missing,false,text -> { },cancel);
    }
    /** Installs Codex and the basic tools once, on an unmetered network, after the terminal connects. */
    static void automatic(Context context) {
        Context app=context.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences("packages",Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        if(prefs.getBoolean("automatic-done",false) || running.get() || now-prefs.getLong("automatic-attempt",0)<6*3600*1000L) return;
        ConnectivityManager network=app.getSystemService(ConnectivityManager.class);
        if(network==null || network.getActiveNetwork()==null || network.isActiveNetworkMetered()) return;
        prefs.edit().putLong("automatic-attempt",now).apply();
        new Thread(() -> {
            Packages packages=new Packages(app);
            try {
                if(!version(packages.installed(new OpenAiClient.Cancel()),"codex").isEmpty()) { prefs.edit().putBoolean("automatic-done",true).apply(); return; }
                packages.install(Arrays.asList(CORE),false,text -> notify(app,text,true),new OpenAiClient.Cancel());
                prefs.edit().putBoolean("automatic-done",true).apply();
                notify(app,Language.text(R.string.codex_cli_ready),false);
            } catch(Exception e) { notify(app,Language.text(R.string.package_installation_failed)+message(e),false); }
        },"meshell-packages").start();
    }
    private static void notify(Context context,String text,boolean active) {
        try {
            PendingIntent open=PendingIntent.getActivity(context,3,new Intent(context,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder builder=new Notification.Builder(context,"terminal").setSmallIcon(R.drawable.notify)
                .setContentTitle(Language.text(active?R.string.installing_codex_and_tools:R.string.packages)).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open)
                .setOnlyAlertOnce(true).setOngoing(active).setAutoCancel(!active).setShowWhen(false);
            if(active) builder.setProgress(0,0,true);
            context.getSystemService(NotificationManager.class).notify(3,builder.build());
        } catch(Exception ignored) { }
    }
    static String message(Exception e) { return e.getMessage()==null?e.toString():e.getMessage(); }
    static String version(JSONObject packages,String name) {
        JSONObject item=packages.optJSONObject(name); return item==null?"":item.optString("version");
    }

    /** Streams relocated tar data to a staging file through the broker, a few MiB per command. */
    private final class Upload extends OutputStream {
        private final String path; private final OpenAiClient.Cancel cancel;
        private final byte[] buffer=new byte[CHUNK]; private int length; private boolean started;
        Upload(String path,OpenAiClient.Cancel cancel) { this.path=path; this.cancel=cancel; }
        @Override public void write(int b) throws IOException { if(length==buffer.length) send(); buffer[length++]=(byte)b; }
        @Override public void write(byte[] data,int offset,int count) throws IOException {
            while(count>0) { if(length==buffer.length) send(); int n=Math.min(count,buffer.length-length); System.arraycopy(data,offset,buffer,length,n); length+=n; offset+=n; count-=n; }
        }
        private void send() throws IOException {
            try {
                ShellBroker.Result result=shell.run((started?"cat >> ":"cat > ")+ShellBroker.quote(path),ShellBroker.HOME,Arrays.copyOf(buffer,length),null,120000,cancel);
                if(result.exitCode!=0) throw new IOException(result.output);
            } catch(IOException e) { throw e; } catch(Exception e) { throw new IOException(e); }
            started=true; length=0;
        }
        @Override public void close() throws IOException { if(length>0 || !started) send(); }
    }

    // Debian package (ar) and tar handling.
    private static final class Bounded extends FilterInputStream {
        private long left;
        Bounded(InputStream in,long left) { super(in); this.left=left; }
        @Override public int read() throws IOException { if(left<=0) return -1; int b=in.read(); if(b>=0) left--; return b; }
        @Override public int read(byte[] b,int off,int len) throws IOException {
            if(left<=0) return -1; int n=in.read(b,off,(int)Math.min(len,left)); if(n>0) left-=n; return n;
        }
        @Override public void close() { }
    }
    private static void fully(InputStream in,byte[] b,int length) throws IOException { fully(in,b,0,length); }
    private static void fully(InputStream in,byte[] b,int offset,int length) throws IOException {
        for(int at=0;at<length;) { int n=in.read(b,offset+at,length-at); if(n<0) throw new EOFException("Truncated package"); at+=n; }
    }
    private static void skip(InputStream in,long count) throws IOException {
        byte[] scratch=new byte[8192];
        while(count>0) { int n=in.read(scratch,0,(int)Math.min(scratch.length,count)); if(n<0) throw new EOFException("Truncated package"); count-=n; }
    }
    /** Opens the data archive of a .deb file as an uncompressed tar stream. */
    static InputStream payload(InputStream deb) throws IOException {
        byte[] magic=new byte[8]; fully(deb,magic,8);
        if(!new String(magic,StandardCharsets.US_ASCII).equals("!<arch>\n")) throw new IOException("Invalid package archive");
        byte[] header=new byte[60];
        while(true) {
            fully(deb,header,60);
            String name=new String(header,0,16,StandardCharsets.US_ASCII).trim(); if(name.endsWith("/")) name=name.substring(0,name.length()-1);
            long size=Long.parseLong(new String(header,48,10,StandardCharsets.US_ASCII).trim());
            if(name.startsWith("data.tar")) {
                InputStream data=new Bounded(deb,size);
                if(name.equals("data.tar.xz")) return new XzStream(data);
                if(name.equals("data.tar.gz")) return new GZIPInputStream(data,65536);
                if(name.equals("data.tar")) return data;
                throw new IOException("Unsupported package compression: "+name);
            }
            skip(deb,size+(size&1));
        }
    }

    static final class Entry { String name="",link=""; char type; int mode; long size,mtime; }
    private static long octal(byte[] h,int at,int length) throws IOException {
        long value=0; int i=at,end=at+length;
        while(i<end && (h[i]==' ' || h[i]==0)) i++;
        for(;i<end && h[i]>='0' && h[i]<='7';i++) value=value*8+(h[i]-'0');
        return value;
    }
    private static String text(byte[] h,int at,int length) {
        int end=at; while(end<at+length && h[end]!=0) end++; return new String(h,at,end-at,StandardCharsets.UTF_8);
    }
    private static String longValue(InputStream in,long size) throws IOException {
        if(size>65536) throw new IOException("Invalid tar entry");
        byte[] data=new byte[(int)size]; fully(in,data,data.length); skip(in,(512-size%512)%512);
        int end=data.length; while(end>0 && data[end-1]==0) end--; return new String(data,0,end,StandardCharsets.UTF_8);
    }
    /** Reads the next tar member header, resolving GNU long names and pax path records; null at the end. */
    static Entry next(InputStream in) throws IOException {
        String longName=null,longLink=null; byte[] h=new byte[512];
        while(true) {
            int got=0; while(got<512) { int n=in.read(h,got,512-got); if(n<0) break; got+=n; }
            if(got==0) return null;
            if(got<512) throw new EOFException("Truncated package");
            boolean zero=true; for(int i=0;i<512;i++) if(h[i]!=0) { zero=false; break; }
            if(zero) return null;
            Entry e=new Entry();
            e.type=(char)h[156]; e.size=octal(h,124,12); e.mode=(int)octal(h,100,8); e.mtime=octal(h,136,12);
            String name=text(h,0,100);
            if("ustar".equals(text(h,257,6)) && h[345]!=0) name=text(h,345,155)+"/"+name;
            if(e.type=='L') { longName=longValue(in,e.size); continue; }
            if(e.type=='K') { longLink=longValue(in,e.size); continue; }
            if(e.type=='x' || e.type=='g') {
                String records=longValue(in,e.size);
                if(e.type=='x') for(String record:records.split("\n")) {
                    int space=record.indexOf(' '),eq=record.indexOf('=');
                    if(space<0 || eq<space) continue;
                    String key=record.substring(space+1,eq),value=record.substring(eq+1);
                    if(key.equals("path")) longName=value; else if(key.equals("linkpath")) longLink=value;
                }
                continue;
            }
            e.name=longName!=null?longName:name; e.link=longLink!=null?longLink:text(h,157,100);
            return e;
        }
    }
    private static void put(byte[] h,int at,int size,long value) {
        String s=Long.toOctalString(value); Arrays.fill(h,at,at+size-1,(byte)'0');
        byte[] b=s.getBytes(StandardCharsets.US_ASCII); System.arraycopy(b,0,h,at+size-1-b.length,b.length); h[at+size-1]=0;
    }
    private static void raw(OutputStream out,String name,char type,int mode,long size,long mtime,String link) throws IOException {
        byte[] h=new byte[512],n=name.getBytes(StandardCharsets.UTF_8),l=link.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n,0,h,0,Math.min(n.length,100)); System.arraycopy(l,0,h,157,Math.min(l.length,100));
        put(h,100,8,mode&07777); put(h,108,8,2000); put(h,116,8,2000); put(h,124,12,size); put(h,136,12,mtime);
        h[156]=(byte)type; System.arraycopy("ustar  \0".getBytes(StandardCharsets.US_ASCII),0,h,257,8);
        Arrays.fill(h,148,156,(byte)' '); long sum=0; for(byte b:h) sum+=b&255;
        String s=Long.toOctalString(sum); Arrays.fill(h,148,155,(byte)'0'); byte[] c=s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(c,0,h,154-c.length,c.length); h[154]=0; h[155]=' ';
        out.write(h);
    }
    private static void longEntry(OutputStream out,char type,String value) throws IOException {
        byte[] data=(value+"\0").getBytes(StandardCharsets.UTF_8);
        raw(out,"././@LongLink",type,0644,data.length,0,""); out.write(data); out.write(new byte[(512-data.length%512)%512]);
    }
    private static void header(OutputStream out,String name,char type,int mode,long size,long mtime,String link) throws IOException {
        if(name.getBytes(StandardCharsets.UTF_8).length>100) longEntry(out,'L',name);
        if(link.getBytes(StandardCharsets.UTF_8).length>100) longEntry(out,'K',link);
        raw(out,name,type,mode,size,mtime,link);
    }
    /** Maps an archive path inside the Termux prefix to the extraction root, or null for anything else. */
    static String target(String original) {
        String path=original; while(path.startsWith("./")) path=path.substring(2); if(path.startsWith("/")) path=path.substring(1);
        if(!path.startsWith(PREFIX)) return null;
        String relative=path.substring(PREFIX.length()); while(relative.endsWith("/")) relative=relative.substring(0,relative.length()-1);
        if(relative.isEmpty()) return null;
        for(String part:relative.split("/")) if(part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        return "toolusr/"+relative;
    }
    /** A relative symlink from one archive path to another; SELinux forbids hard links for the shell user. */
    static String relative(String from,String to) {
        String[] a=from.split("/"),b=to.split("/"); int common=0;
        while(common<a.length-1 && common<b.length-1 && a[common].equals(b[common])) common++;
        StringBuilder link=new StringBuilder();
        for(int i=common;i<a.length-1;i++) link.append("../");
        for(int i=common;i<b.length;i++) { if(i>common) link.append('/'); link.append(b[i]); }
        return link.toString();
    }
    /** Converts a .deb into a tar rooted at {@link ShellBroker#ROOT} with the prefix rewritten. */
    static void repack(File deb,OutputStream out) throws IOException {
        try(InputStream in=payload(new BufferedInputStream(new FileInputStream(deb),1<<16))) { repack(in,out); }
    }
    static void repack(InputStream tar,OutputStream out) throws IOException {
        Set<String> seen=new HashSet<>(); String oldPrefix=new String(OLD,StandardCharsets.US_ASCII),newPrefix=new String(NEW,StandardCharsets.US_ASCII);
        Entry e;
        while((e=next(tar))!=null) {
            long padding=(512-e.size%512)%512;
            String name=target(e.name);
            boolean regular=e.type=='0' || e.type==0 || e.type=='7';
            if(name==null || !seen.add(name) || !(regular || e.type=='5' || e.type=='2' || e.type=='1')) { skip(tar,e.size+padding); continue; }
            if(e.type=='5') { header(out,name,'5',e.mode|0700,0,e.mtime,""); skip(tar,e.size+padding); }
            else if(e.type=='2') { header(out,name,'2',0777,0,e.mtime,e.link.replace(oldPrefix,newPrefix)); skip(tar,e.size+padding); }
            else if(e.type=='1') {
                String to=target(e.link); skip(tar,e.size+padding);
                if(to!=null) header(out,name,'2',0777,0,e.mtime,relative(name,to));
            } else {
                header(out,name,'0',e.mode|0600,e.size,e.mtime,"");
                relocate(tar,e.size,out); out.write(new byte[(int)padding]); skip(tar,padding);
            }
        }
        out.write(new byte[1024]);
    }
    /** Copies file data, rewriting the prefix in ELF files and text; other binary formats keep their checksums. */
    static void relocate(InputStream in,long size,OutputStream out) throws IOException {
        int keep=OLD.length-1; byte[] buffer=new byte[(1<<16)+keep]; int length=(int)Math.min(4096,size);
        fully(in,buffer,length); long left=size-length;
        boolean elf=length>=4 && buffer[0]==0x7F && buffer[1]=='E' && buffer[2]=='L' && buffer[3]=='F',text=true;
        for(int i=0;i<length;i++) if(buffer[i]==0) { text=false; break; }
        if(!elf && !text) {
            out.write(buffer,0,length);
            while(left>0) { int n=(int)Math.min(buffer.length,left); fully(in,buffer,n); out.write(buffer,0,n); left-=n; }
            return;
        }
        while(true) {
            int n=(int)Math.min(buffer.length-length,left); fully(in,buffer,length,n); length+=n; left-=n;
            for(int i=0;i+OLD.length<=length;i++) if(buffer[i]==OLD[0] && matches(buffer,i)) { System.arraycopy(NEW,0,buffer,i,NEW.length); i+=OLD.length-1; }
            if(left==0) { out.write(buffer,0,length); return; }
            int emit=length-keep; out.write(buffer,0,emit); System.arraycopy(buffer,emit,buffer,0,keep); length=keep;
        }
    }
    private static boolean matches(byte[] b,int at) { for(int i=1;i<OLD.length;i++) if(b[at+i]!=OLD[i]) return false; return true; }
}
