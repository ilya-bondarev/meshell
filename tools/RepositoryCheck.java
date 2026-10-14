import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

public final class RepositoryCheck {
    private static void fail(String message) { throw new IllegalStateException(message); }
    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path),StandardCharsets.UTF_8); }
    private static Map<String,String> resources(Path path) throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        NodeList nodes=factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement().getChildNodes();
        Map<String,String> out=new TreeMap<>();
        for(int i=0;i<nodes.getLength();i++) if(nodes.item(i) instanceof Element) {
            Element item=(Element)nodes.item(i); String key=item.getTagName()+":"+item.getAttribute("name");
            if(out.put(key,item.getTextContent())!=null) fail("Duplicate resource: "+key);
        }
        return out;
    }
    private static List<String> formats(String text) {
        List<String> out=new ArrayList<>(); Matcher match=Pattern.compile("%(?!%)[0-9$.+-]*[a-zA-Z]").matcher(text.replace("%%",""));
        while(match.find()) out.add(match.group()); return out;
    }
    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[0]).toAbsolutePath();
        List<Path> files=new ArrayList<>();
        try(java.util.stream.Stream<Path> walk=Files.walk(root)) {
            walk.filter(Files::isRegularFile).filter(p -> !p.startsWith(root.resolve(".git")) && !p.startsWith(root.resolve("build")) && !p.startsWith(root.resolve(".local"))).forEach(files::add);
        }
        StringBuilder code=new StringBuilder(read(root.resolve("AndroidManifest.xml")));
        Set<String> used=new HashSet<>();
        Pattern literal=Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\""),secret=Pattern.compile("-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----|\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{20,}|AIza[A-Za-z0-9_-]{30,}|AKIA[A-Z0-9]{16})");
        for(Path path:files) {
            String relative=root.relativize(path).toString().replace('\\','/');
            if(relative.matches(".*\\.(apk|apksig|class|dex|jar|zip|bundle|p12|jks|keystore|pem|key|db|sqlite|log|pyc)$") || path.getFileName().toString().startsWith(".env")) fail("Local artifact: "+relative);
            String text=read(path);
            if(secret.matcher(text).find()) fail("Possible secret: "+relative);
            if(relative.startsWith("src/") && relative.endsWith(".java")) {
                code.append(text);
                Matcher match=Pattern.compile("R\\.string\\.([a-zA-Z0-9_]+)").matcher(text); while(match.find()) used.add(match.group(1));
                match=literal.matcher(text); while(match.find()) if(Pattern.compile("\\p{IsCyrillic}").matcher(match.group()).find()) fail("Hardcoded translation: "+relative);
                if(relative.startsWith("src/services/") && Pattern.compile("import (android|com\\.mesystem)").matcher(text).find()) fail("Domain platform dependency: "+relative);
                if(!relative.contains("/persistence/") && Pattern.compile("rawQuery\\(|execSQL\\(|get(?:Readable|Writable)Database\\(").matcher(text).find()) fail("Storage outside persistence: "+relative);
                if(relative.contains("/presentation/") && Pattern.compile("100-used|left<=|optInt\\(\"enabled\"\\).*>=|1073741824|optDouble\\(\"(?:rx|tx)_per_second\".*?/1024").matcher(text).find()) fail("Rule in rendering: "+relative);
                if(text.contains("setTooltipText(")) fail("Tooltip: "+relative);
                if(Pattern.compile("static final .*Language\\.text").matcher(text).find()) fail("Locale-dependent constant: "+relative);
            }
        }
        Map<String,String> en=resources(root.resolve("res/values/strings.xml")),ru=resources(root.resolve("res/values-ru/strings.xml"));
        if(!en.keySet().equals(ru.keySet())) fail("Language keys differ");
        for(String key:en.keySet()) {
            if(!formats(en.get(key)).equals(formats(ru.get(key)))) fail("Format mismatch: "+key);
            if(key.startsWith("string:") && !used.contains(key.substring(7))) fail("Unused string: "+key);
        }
        for(String name:used) if(!en.containsKey("string:"+name)) fail("Missing string: "+name);
        for(Path path:files) if(root.relativize(path).startsWith("src") && path.toString().endsWith(".java")) {
            String name=path.getFileName().toString().replace(".java","");
            String others=code.toString().replace(read(path),"");
            if(!Pattern.compile("\\b"+Pattern.quote(name)+"\\b").matcher(others).find()) fail("Unreferenced source: "+name);
        }
        System.out.println("REPOSITORY_CHECK_OK: languages, ownership, references, artifacts, secret signatures");
    }
}
