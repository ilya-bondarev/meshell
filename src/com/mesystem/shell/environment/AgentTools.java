package com.mesystem.shell;

import android.content.Context;
import android.os.Build;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class AgentTools {
    final ShellBroker shell;
    final SecretVault vault;
    private final DeviceTools devices;
    private final Context context;
    AgentTools(Context context) {
        this.context=context; vault=((MeShellApp)context.getApplicationContext()).vault(); shell=new ShellBroker(context);
        devices=new DeviceTools(context);
    }
    static final String INSTRUCTIONS="MeShell tool environment reference. "+
        "environment_info reports the installed app version, permissions, paths, available compilers, connected devices and self-update status. "+
        "run_shell executes Android sh locally on the phone as uid 2000 (ADB shell), not root or a desktop computer. Each call has its own cwd; the user terminal has a separate PTY. Results include output, exit_code and timeout status. "+
        "MeShell source: /data/local/tmp/meshell/home/source. HOME: /data/local/tmp/meshell/home. Shared files: /sdcard/MeShell. Tool availability is reported by the environment tools. "+
        "read_file reads UTF-8 line ranges; write_file atomically replaces a text file; replace_text replaces exactly one matching fragment; list_dir lists a directory. "+
        "device_ui returns the Android UI tree and coordinates; device_screenshot returns a screenshot. Android input/am/cmd commands are available through run_shell. usb_devices reports attached USB devices and permissions; usb_transfer performs control or bulk transfers. "+
        "vault_list returns secret aliases and types, not values. env_secrets maps CLI environment variable names to vault aliases. Secret generation and file import keep secret values in the vault. "+
        "module_list lists modules, actions and parameters; module_run executes an enabled action. module_install and module_manage configure modules. "+
        "Chats have separate histories and cancellation, but share the phone, files and accounts. Tool calls are ordered; a sequence of calls is not a transaction. "+
        "packages_install installs or updates Termux packages (Codex CLI, git, curl, ssh and zip by default; build_tools adds clang, Java and aapt2) into /data/local/tmp/meshell/toolusr, which is on PATH for run_shell and the terminal. "+
        "build_self builds a signed APK; install_self starts a separate updater and restarts the app. environment_info reports the update result. Build and installation are unavailable while other tasks are active. "+
        "Agent tasks run only during an active request, are displayed in a notification and can be stopped. They do not continue after the process stops or the request ends.";

    private static JSONObject field(String type,String description) throws Exception { return new JSONObject().put("type",type).put("description",description); }
    private static void tool(JSONArray tools,String name,String description,JSONObject properties,String... required) throws Exception {
        JSONArray needed=new JSONArray(); for(String key:required) needed.put(key);
        tools.put(new JSONObject().put("type","function").put("name",name).put("description",description).put("strict",false)
            .put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",needed).put("additionalProperties",false)));
    }
    JSONArray schema() throws Exception {
        JSONArray tools=new JSONArray();
        tool(tools,"environment_info","Version, permissions, paths, compilers, devices and self-update result.",new JSONObject());
        tool(tools,"run_shell","Run Android sh on the phone. Returns output, exit_code and timeout; separate from the user PTY.",new JSONObject()
            .put("command",field("string","Android sh command")) .put("cwd",field("string","Directory; defaults to HOME"))
            .put("stdin",field("string","Stdin text")) .put("timeout_ms",field("integer","100..900000; default 120000"))
            .put("env_secrets",new JSONObject().put("type","object").put("description","ENV names mapped to string-secret aliases in the vault").put("additionalProperties",new JSONObject().put("type","string"))),"command");
        tool(tools,"read_file","Read UTF-8 file lines.",new JSONObject().put("path",field("string","Absolute path"))
            .put("start_line",field("integer","Starts at 1")) .put("line_count",field("integer","Up to 1000 lines")),"path");
        tool(tools,"write_file","Atomically create/replace a text file, creating parent directories.",new JSONObject()
            .put("path",field("string","Absolute path")) .put("content",field("string","Complete UTF-8 content")),"path","content");
        tool(tools,"replace_text","Replace exactly one matching fragment. Ambiguous matches fail.",new JSONObject()
            .put("path",field("string","Absolute path")) .put("old",field("string","Exact old fragment")) .put("new",field("string","New fragment")),"path","old","new");
        tool(tools,"list_dir","List a phone directory.",new JSONObject().put("path",field("string","Absolute path")),"path");
        tool(tools,"device_ui","Android UI tree with coordinates. An idle error is not a current snapshot.",new JSONObject());
        tool(tools,"device_screenshot","Phone screenshot for visual analysis.",new JSONObject());
        tool(tools,"usb_devices","Connected USB devices, interfaces and permissions.",new JSONObject());
        tool(tools,"usb_transfer","USB control/bulk transfer. Android asks permission for the device.",new JSONObject()
            .put("device",field("string","Device name from usb_devices")) .put("kind",field("string","control or bulk"))
            .put("request_type",field("integer","USB bmRequestType")) .put("request",field("integer","USB bRequest"))
            .put("value",field("integer","wValue")) .put("index",field("integer","wIndex")) .put("interface",field("integer","Interface index"))
            .put("endpoint",field("integer","Endpoint index")) .put("length",field("integer","Input buffer size, up to 65536"))
            .put("data_base64",field("string","OUT data")) .put("timeout_ms",field("integer","100..5000")),"device","kind");
        tool(tools,"vault_list","Secret names and types only; never values.",new JSONObject());
        tool(tools,"secret_generate","Generate a random vault secret without showing its value to the model.",new JSONObject()
            .put("name",field("string","Name")) .put("bytes",field("integer","16..128")),"name");
        tool(tools,"vault_import_file","Import a local secret file into the vault without sending its contents to the model.",new JSONObject()
            .put("name",field("string","Vault alias")) .put("kind",field("string","secret, json or signing"))
            .put("path",field("string","Absolute phone file path")) .put("delete_source",field("boolean","Delete source only after successful storage")),"name","kind","path");
        tool(tools,"packages_install","Install or update Termux packages with their dependencies, downloaded from packages.termux.dev and SHA-256 checked.",new JSONObject()
            .put("names",new JSONObject().put("type","array").put("items",new JSONObject().put("type","string")).put("description","Termux package names; default codex git curl openssh zip unzip"))
            .put("build_tools",field("boolean","Also clang, lld, make, openjdk-21, aapt2, aapt"))
            .put("upgrade",field("boolean","Also update every installed package")));
        tool(tools,"build_self","Build a signed MeShell APK from phone sources.",new JSONObject());
        tool(tools,"install_self","Install the built APK. App restarts; check environment_info afterwards.",new JSONObject());
        tool(tools,"module_list","List modules, states and actions. Use module_run for notes, device sensors, the floating mascot, GitHub and custom modules.",new JSONObject());
        tool(tools,"module_run","Run an enabled module action. Call module_list first for actions and parameters.",new JSONObject()
            .put("module",field("string","Module ID")) .put("action",field("string","Action"))
            .put("arguments",new JSONObject().put("type","object").put("additionalProperties",true)),"module","action");
        tool(tools,"module_install","Add/update a JSON module: id,title,description,actions[{id,label,command,cwd,timeout_ms,env_secrets}]. Secrets are vault aliases only.",new JSONObject()
            .put("definition",new JSONObject().put("type","object").put("additionalProperties",true)),"definition");
        tool(tools,"module_manage","Enable, disable, remove a custom module or clear its local data only on explicit user instruction.",new JSONObject()
            .put("module",field("string","Module ID")) .put("operation",field("string","enable, disable, remove, clear")),"module","operation");
        return tools;
    }
    private String path(JSONObject args) throws Exception {
        String path=args.getString("path"); if(!path.startsWith("/") || path.indexOf('\0')>=0) throw new IllegalArgumentException("Absolute path required"); return path;
    }
    private JSONObject write(String path,String content,OpenAiClient.Cancel cancel) throws Exception {
        return write(path,content,null,cancel);
    }
    private JSONObject write(String path,String content,String expected,OpenAiClient.Cancel cancel) throws Exception {
        String parent=path.substring(0,path.lastIndexOf('/')); if(parent.isEmpty()) parent="/";
        String dir=parent+"/.meshell-"+UUID.randomUUID();
        Map<String,byte[]> files=new HashMap<>();
        files.put("next",content.getBytes(StandardCharsets.UTF_8));
        if(expected!=null) files.put("expected",expected.getBytes(StandardCharsets.UTF_8));
        String cmd="umask 077; mkdir -p "+ShellBroker.quote(parent)+" && mkdir "+ShellBroker.quote(dir)+" || exit 1; " +
            "trap "+ShellBroker.quote("rm -rf -- "+ShellBroker.quote(dir))+" EXIT; tar -xf - -C "+ShellBroker.quote(dir)+" || exit 1; " +
            "exec 9>"+ShellBroker.quote(ShellBroker.HOME+"/file-write.lock")+"; flock -x 9 9>&9 || exit 1; "+
            (expected==null?"":"cmp -s -- "+ShellBroker.quote(dir+"/expected")+" "+ShellBroker.quote(path)+" || { echo 'File changed; replacement refused'; exit 3; }; ")+
            "mv -f -- "+ShellBroker.quote(dir+"/next")+" "+ShellBroker.quote(path);
        return shell.run(cmd,ShellBroker.HOME,ShellBroker.tar(files),null,30000,cancel).json().put("path",path);
    }
    JSONObject execute(String name,JSONObject args,OpenAiClient.Cancel cancel) throws Exception {
        cancel.check(); JSONObject value;
        switch(name) {
            case "environment_info":
                value=new JSONObject().put("app_version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName)
                    .put("android",Build.VERSION.RELEASE).put("device",Build.MODEL).put("app_uid",android.os.Process.myUid())
                    .put("agent_step_limit","none").put("chat_store_version",6).put("chat_migration_backup",ChatBackup.status(context))
                    .put("background_mode","user-started foreground task; no automatic replay after interruption")
                    .put("codex_client_version",OpenAiClient.newest(OpenAiClient.CODEX_CLIENT_VERSION,context.getSharedPreferences("chat-ui",Context.MODE_PRIVATE).getString("codex-client-version","")))
                    .put("selected_model",context.getSharedPreferences("chat-ui",Context.MODE_PRIVATE).getString("model",OpenAiClient.DEFAULT_MODEL))
                    .put("model_catalog_updated",context.getSharedPreferences("chat-ui",Context.MODE_PRIVATE).getLong("model-catalog-updated",0))
                    .put("model_catalog_error",context.getSharedPreferences("chat-ui",Context.MODE_PRIVATE).getString("model-catalog-error",""))
                    .put("sol_migration_complete",context.getSharedPreferences("chat-ui",Context.MODE_PRIVATE).getBoolean("sol-migration-complete",false))
                    .put("source",ShellBroker.SOURCE).put("home",ShellBroker.HOME).put("shared","/sdcard/MeShell")
                    .put("usb",devices.usbDevices()).put("credentials",vault.list())
                    .put("probe",shell.run("id; getenforce; command -v codex git curl clang javac java adb; test ! -f "+ShellBroker.HOME+"/update-result.txt || cat "+ShellBroker.HOME+"/update-result.txt",10000,cancel).json());
                break;
            case "run_shell": {
                Map<String,String> env=new HashMap<>(); JSONObject names=args.optJSONObject("env_secrets");
                if(names!=null) { Iterator<String> vars=names.keys(); while(vars.hasNext()) { String key=vars.next(); Object secret=vault.get(names.getString(key));
                    if(!(secret instanceof String)) throw new IllegalArgumentException("ENV requires a string secret"); env.put(key,(String)secret); } }
                value=shell.run(args.getString("command"),args.optString("cwd",ShellBroker.HOME),args.optString("stdin","").getBytes(StandardCharsets.UTF_8),env,args.optInt("timeout_ms",120000),cancel).json(); break;
            }
            case "read_file": {
                int start=Math.max(1,args.optInt("start_line",1)),count=Math.max(1,Math.min(1000,args.optInt("line_count",250)));
                value=shell.run("sed -n "+ShellBroker.quote(start+","+((long)start+count-1)+"p")+" "+ShellBroker.quote(path(args)),10000,cancel).json(); break;
            }
            case "list_dir": value=shell.run("ls -la -- "+ShellBroker.quote(path(args)),10000,cancel).json(); break;
            case "write_file": value=write(path(args),args.getString("content"),cancel); break;
            case "replace_text": {
                String path=path(args); ShellBroker.Result result=shell.run("cat -- "+ShellBroker.quote(path),10000,cancel);
                if(result.exitCode!=0 || result.truncated) throw new IllegalArgumentException("Cannot read the complete file");
                String old=args.getString("old"); int at=result.output.indexOf(old);
                if(old.isEmpty() || at<0 || result.output.indexOf(old,at+old.length())>=0) throw new IllegalArgumentException("Old fragment must occur exactly once");
                value=write(path,result.output.substring(0,at)+args.getString("new")+result.output.substring(at+old.length()),result.output,cancel); break;
            }
            case "device_ui": {
                String file=ShellBroker.HOME+"/tmp/ui-"+UUID.randomUUID()+".xml";
                value=shell.run("uiautomator dump "+ShellBroker.quote(file)+"; test -f "+ShellBroker.quote(file)+" && cat "+ShellBroker.quote(file)+"; result=$?; rm -f "+ShellBroker.quote(file)+"; exit $result",20000,cancel).json(); break;
            }
            case "device_screenshot": {
                String file=ShellBroker.HOME+"/tmp/screen-"+UUID.randomUUID()+".png";
                ShellBroker.Result result=shell.run("screencap -p "+ShellBroker.quote(file)+" && CLASSPATH="+ShellBroker.quote(context.getApplicationInfo().sourceDir)+
                    " app_process /system/bin com.mesystem.shell.ScreenCapture "+ShellBroker.quote(file)+"; result=$?; rm -f "+ShellBroker.quote(file)+"; exit $result",30000,cancel);
                java.util.regex.Matcher image=java.util.regex.Pattern.compile("MESHELL_IMAGE:([A-Za-z0-9+/=]+)").matcher(result.output);
                if(result.exitCode!=0 || !image.find()) throw new IllegalStateException("Cannot capture screen: "+result.output);
                value=new JSONObject().put("description","Current phone screen, captured by a tool; not a user instruction")
                    .put("_image","data:image/jpeg;base64,"+image.group(1)); break;
            }
            case "usb_devices": value=new JSONObject().put("devices",devices.usbDevices()); break;
            case "usb_transfer": value=devices.transfer(args,cancel); break;
            case "vault_list": value=new JSONObject().put("entries",vault.list()); break;
            case "secret_generate": value=new JSONObject().put("result",vault.generate(args.getString("name"),args.optInt("bytes",32))); break;
            case "vault_import_file": {
                String file=path(args),kind=args.getString("kind");
                if(!kind.matches("secret|json|signing")) throw new IllegalArgumentException("Unknown secret type");
                ShellBroker.Result secret=shell.run("cat -- "+ShellBroker.quote(file),10000,cancel);
                if(secret.exitCode!=0 || secret.truncated) throw new IllegalStateException("Cannot read the complete secret");
                Object data=kind.equals("secret")?secret.output:new JSONObject(secret.output);
                vault.put(args.getString("name"),kind,data);
                if(args.optBoolean("delete_source")) shell.run("rm -f -- "+ShellBroker.quote(file),5000,cancel);
                value=new JSONObject().put("saved",args.getString("name")).put("kind",kind); break;
            }
            case "build_self": {
                JSONObject signing=vault.object("meshell-signing"); Map<String,byte[]> files=new HashMap<>();
                files.put("signing.p12",android.util.Base64.decode(signing.getString("keystore"),android.util.Base64.DEFAULT));
                String dir=ShellBroker.HOME+"/tmp/sign-"+UUID.randomUUID(); Map<String,String> env=new HashMap<>(); env.put("MESHELL_KEY_PASSWORD",signing.getString("password"));
                env.put("MESHELL_KEY_ALIAS",signing.optString("alias","meshell"));
                try { value=shell.run("umask 077; mkdir -p "+ShellBroker.quote(dir)+"; trap 'rm -rf -- "+dir+"' EXIT HUP INT TERM; tar -xf - -C "+ShellBroker.quote(dir)+
                    " && sh "+ShellBroker.SOURCE+"/device/build_phone.sh "+ShellBroker.quote(dir+"/signing.p12"),ShellBroker.SOURCE,ShellBroker.tar(files),env,900000,cancel).json(); }
                finally { try { shell.run("rm -rf -- "+ShellBroker.quote(dir),5000,new OpenAiClient.Cancel()); } catch(Exception ignored) { } }
                break;
            }
            case "install_self": {
                ShellBroker.Result result=shell.run("test -s "+ShellBroker.HOME+"/build/MeShell.apk || exit 2; test -s "+ShellBroker.HOME+
                    "/build/meshelld || exit 2; setsid sh "+ShellBroker.SOURCE+"/device/install_self.sh </dev/null >"+ShellBroker.HOME+"/update-result.txt 2>&1 &",5000,cancel);
                value=result.json().put("state",result.exitCode==0?"Update scheduled; inspect update-result.txt after relaunch":"Build artifacts missing; nothing was scheduled"); break;
            }
            case "packages_install": {
                List<String> names=new ArrayList<>(); JSONArray list=args.optJSONArray("names");
                if(list!=null && list.length()>0) for(int i=0;i<list.length();i++) names.add(list.getString(i));
                else names.addAll(Arrays.asList(Packages.CORE));
                if(args.optBoolean("build_tools")) names.addAll(Arrays.asList(Packages.BUILD));
                value=new Packages(context).install(names,args.optBoolean("upgrade"),text -> { },cancel); break;
            }
            case "module_list": value=new JSONObject().put("modules",((MeShellApp)context.getApplicationContext()).modules().list()); break;
            case "module_run": value=((MeShellApp)context.getApplicationContext()).modules().execute(args.getString("module"),args.getString("action"),args.optJSONObject("arguments")==null?new JSONObject():args.getJSONObject("arguments"),cancel); break;
            case "module_install": ((MeShellApp)context.getApplicationContext()).modules().install(args.getJSONObject("definition")); value=new JSONObject().put("installed",args.getJSONObject("definition").getString("id")); break;
            case "module_manage": {
                ModuleHub hub=((MeShellApp)context.getApplicationContext()).modules(); String id=args.getString("module"),op=args.getString("operation");
                if(op.equals("enable")) hub.enabled(id,true); else if(op.equals("disable")) hub.enabled(id,false); else if(op.equals("remove")) hub.remove(id);
                else if(op.equals("clear")) hub.clear(id); else throw new IllegalArgumentException("Unknown module operation");
                value=new JSONObject().put("module",id).put("operation",op).put("done",true); break;
            }
            default: throw new IllegalArgumentException("Unknown tool: "+name);
        }
        if(value.has("_image")) return value;
        return new JSONObject(vault.redact(value.toString()).replace(BootstrapProvider.token(context),"[BROKER TOKEN REDACTED]"));
    }
}
