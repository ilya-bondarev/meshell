package com.mesystem.shell;

import android.content.*;
import android.database.sqlite.*;
import android.os.*;
import android.view.ContextThemeWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Run via app_process under shell, with isolated files/database; no account or network access. */
public final class FeatureTests {
    private static int checks;
    private static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label); checks++; System.out.println("PASS "+label); }
    private static class Sandbox extends ContextWrapper {
        final File dir;
        Sandbox(Context c,File d) { super(c); dir=d; dir.mkdirs(); }
        @Override public File getFilesDir() { return dir; }
        @Override public File getDatabasePath(String name) { return new File(dir,name); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler); }
    }
    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            java.lang.reflect.Method fonts=android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap"); fonts.setAccessible(true); fonts.invoke(null);
            Class<?> at=Class.forName("android.app.ActivityThread"); Object thread=at.getMethod("systemMain").invoke(null);
            Context system=(Context)at.getMethod("getSystemContext").invoke(thread);
            for(String field:new String[]{"mBasePackageName","mOpPackageName"}) { java.lang.reflect.Field f=system.getClass().getDeclaredField(field); f.setAccessible(true); f.set(system,"com.android.shell"); }
            java.lang.reflect.Field attribution=system.getClass().getDeclaredField("mAttributionSource"); attribution.setAccessible(true);
            attribution.set(system,new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build());
            Context base=system.createPackageContext("com.android.shell",Context.CONTEXT_IGNORE_SECURITY);
            android.app.Application application=new android.app.Application();
            java.lang.reflect.Method attach=android.app.Application.class.getDeclaredMethod("attach",Context.class); attach.setAccessible(true); attach.invoke(application,base);
            java.lang.reflect.Field initial=at.getDeclaredField("mInitialApplication"); initial.setAccessible(true); initial.set(thread,application);
            java.lang.reflect.Field source=ContentResolver.class.getDeclaredField("mPackageName"); source.setAccessible(true); source.set(base.getContentResolver(),"com.android.shell");
            File root=new File(args[0]); Sandbox c=new Sandbox(new ContextThemeWrapper(base,android.R.style.Theme_Material),root);
            checks+=LanguageTests.run(base,args[1]);
            checks+=ModuleTests.run(LanguageTests.load(base,args[1],"en"));
            Language.initialize(LanguageTests.load(base,args[1],args.length>2?args[2]:"en"));
            check(MarkdownTables.cells("| ``a|b`` | c \\| d |").equals(Arrays.asList("``a|b``","c | d")),"code and escaped pipes");
            Attachments.init(c);
            File text=new File(root,"sample.txt"); try(FileOutputStream out=new FileOutputStream(text)) { out.write("Привет\nКод: x=1".getBytes(StandardCharsets.UTF_8)); }
            JSONObject txt=Attachments.importUri(c,android.net.Uri.fromFile(text)); check(txt.getString("kind").equals("text"),"UTF-8 file import");
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(32,24,android.graphics.Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff336699);
            File image=new File(root,"sample.png"); try(FileOutputStream out=new FileOutputStream(image)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); } bitmap.recycle();
            JSONObject img=Attachments.importUri(c,android.net.Uri.fromFile(image)); check(img.getString("kind").equals("image"),"image import and JPEG normalization");
            File pdf=new File(root,"sample.pdf"); android.graphics.pdf.PdfDocument document=new android.graphics.pdf.PdfDocument();
            android.graphics.pdf.PdfDocument.Page pdfPage=document.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(200,300,1).create());
            pdfPage.getCanvas().drawColor(0xffccddff); document.finishPage(pdfPage);
            try(FileOutputStream out=new FileOutputStream(pdf)) { document.writeTo(out); } finally { document.close(); }
            JSONObject doc=Attachments.importUri(c,android.net.Uri.fromFile(pdf)); check(doc.getString("kind").equals("pdf") && doc.getJSONArray("pages").length()==1,"PDF imported and all pages rasterized");
            File binary=new File(root,"binary.bin"); try(FileOutputStream out=new FileOutputStream(binary)) { out.write(new byte[]{0,1,2,3}); }
            boolean rejected=false; try { Attachments.importUri(c,android.net.Uri.fromFile(binary)); } catch(Exception e) { rejected=true; } check(rejected,"binary file rejected explicitly");
            boolean oversized=false; try { Attachments.read(new ByteArrayInputStream(new byte[20]),10); } catch(IOException e) { oversized=true; } check(oversized,"import size bound");
            JSONArray content=new JSONArray(); Attachments.append(content,new JSONArray().put(txt).put(img).put(doc));
            check(content.getJSONObject(3).getString("type").equals("input_image"),"image API payload");
            java.lang.reflect.Method wal=Class.forName("android.database.sqlite.SQLiteCompatibilityWalFlags").getDeclaredMethod("init",String.class); wal.setAccessible(true); wal.invoke(null,"");
            ChatStore db=new ChatStore(c); long id=db.create(); ChatStore.Message user=db.add(id,"user","Посмотри","complete");
            user.attachments=new JSONArray().put(img).toString(); db.save(user);
            ChatStore.Message answer=db.add(id,"assistant","Начато","streaming");
            answer.actions=new JSONArray().put(new JSONObject().put("name","write_file").put("state","running")).toString();
            answer.wire="[{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"write_file\"}]"; db.save(answer);
            db.recover(); List<ChatStore.Message> stored=db.messages(id);
            check(stored.get(1).state.equals("interrupted"),"process death marked interrupted");
            check(new JSONArray(stored.get(1).actions).getJSONObject(0).getString("state").equals("interrupted"),"running action outcome unknown");
            check(stored.get(0).attachments.equals(user.attachments),"attachment metadata persisted");
            JSONArray history=ContextBudget.history(stored,answer.id,6000);
            check(ContextBudget.estimate(history)<6000,"image budgeting excludes base64 size");
            ContextBudget.materializeAttachments(history);
            check(history.getJSONObject(0).getJSONArray("content").getJSONObject(2).getString("type").equals("input_image"),"historical attachments materialized");
            check(!history.getJSONObject(0).has("_attachments"),"internal metadata excluded from API");
            JSONArray wire=new JSONArray(answer.wire); ContextBudget.repairInterrupted(wire); ContextBudget.repairInterrupted(wire);
            check(wire.length()==2 && wire.getJSONObject(1).getString("output").contains("do not blindly repeat"),"interrupted call repaired once; no replay");
            db.delete(id); check(!Attachments.file(img).exists(),"conversation delete removes local image"); db.close();
            ChatStore large=new ChatStore(new Sandbox(c,new File(root,"large-history")));
            long bigThread=large.create();
            large.add(bigThread,"user","ordinary first row","complete");
            StringBuilder hugeBuilder=new StringBuilder();
            for(int i=0;i<350000;i++) hugeBuilder.append("Я🙂漢abc");
            String huge=hugeBuilder.toString();
            ChatStore.Message big=large.add(bigThread,"assistant",huge,"complete");
            big.wire=new JSONArray().put(huge).toString(); big.actions=new JSONArray().put(new JSONObject().put("output",huge)).toString();
            big.attachments=new JSONArray().put(new JSONObject().put("note",huge)).toString(); big.error=huge; large.save(big);
            List<ChatStore.Message> bigRows=large.messages(bigThread); ChatStore.Message restored=bigRows.get(1);
            check(restored.text.equals(huge) && restored.error.equals(huge),"oversized text and error retain exact UTF-8 including emoji");
            check(restored.wire.equals(big.wire) && restored.actions.equals(big.actions) && restored.attachments.equals(big.attachments),"oversized wire, journal and attachment metadata retained exactly");
            large.close();
            Sandbox old=new Sandbox(c,new File(root,"migration")); SQLiteDatabase sql=old.openOrCreateDatabase("chats.db",0,null);
            sql.execSQL("CREATE TABLE threads(id INTEGER PRIMARY KEY,title TEXT NOT NULL,updated INTEGER NOT NULL)");
            sql.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY,thread INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE,role TEXT NOT NULL,text TEXT NOT NULL,state TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',wire TEXT NOT NULL DEFAULT '[]',actions TEXT NOT NULL DEFAULT '[]')");
            sql.execSQL("INSERT INTO threads VALUES(1,'old',0)"); sql.execSQL("INSERT INTO messages(id,thread,role,text,state) VALUES(1,1,'user','retained','complete')"); sql.setVersion(3); sql.close();
            ChatStore upgraded=new ChatStore(old); check(upgraded.messages(1).get(0).text.equals("retained") && upgraded.messages(1).get(0).attachments.equals("[]"),"DB v3 to v6 migration retains history"); upgraded.close();
            OpenAiClient.Cancel cancel=new OpenAiClient.Cancel(); java.net.Socket socket=new java.net.Socket(); cancel.attachSocket(socket); cancel.stop();
            boolean stopped=false; try { cancel.check(); } catch(IOException e) { stopped=true; } check(stopped && socket.isClosed(),"stop closes socket and prevents next action");
            checks+=ManagementTests.run(c,new File(root,"management"));
            checks+=CompactTests.run();
            checks+=DomainTests.run();
            checks+=PackageTests.run();
            for(String name:new String[]{"api_key","private_key","Authorization"})
                check(SecretFields.sensitive(name),"secret field classified: "+name);
            check(!SecretFields.sensitive("public_key"),"public key remains visible");
            check(OpenAiClient.newest("0.159.1","0.162.1").equals("0.162.1") && OpenAiClient.newest("0.159.1","0.99.9").equals("0.159.1")
                && OpenAiClient.newest("0.159.1","1.0.0-alpha").equals("0.159.1"),"Codex update never downgrades the client version");
            System.out.println("FEATURE_TESTS_OK "+checks); System.exit(0);
        } catch(Throwable e) { e.printStackTrace(); System.exit(1); }
    }
}
