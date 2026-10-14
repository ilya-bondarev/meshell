package com.mesystem.shell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PackageTests {
    // GNU tar inside .xz (LZMA2, lc=1 lp=1 pb=1): Termux-prefixed directory, long path, ELF, jar, hard link, symlink and two escapes.
    private static final String FIXTURE="/Td6WFoAAATm1rRGAgAhARYAAAB0L+Wj4E//AZw3ABcLyW+dyw9BfBqCfGTHUZeNWt5rLOIcj15XZDcYfeer6jDZ9/37QkoCsh222sdQCYtF5WJQwLSP8l26zaM4LYj5ery+VjjH80xOsIXO8horgc/+WzTLmZYfTKESON7Ob/piXZY8/7+qqSU9ipGddxRaPm+JFLGPcxhVsR1UEfCPatbJL9tSFLgbxxzuHvNlWnDGKhEYoVuXdrxPMUGEoqTMU60nub7lxCv8IkmN9VEEw0dUNZ0EYooyfUG6O38re9vwt0Pw8sAkOKb1pZM/+usdpOC27OA+9Y4XZmM7rL/z9HgDWFyP6JMa0XDbMEi7iPs36MK9gio0ineQuaOwa69iYJudBY8kqBQ6JamXRnWWhx8JSUvUnenPTjQvi4kJhh4q+4EEfIjuZEuitmMQWmwJ45Pp8ijxR4hjT8AuJO2kDprQWbJFdjFt2j8HJJlR1IvAo20o7WfDbHmpPPoWbMg1ZJpkE1cxK8koV0qm5cbrjMxf1y0P5BnRdmY299KP2jsut5NdQPUQQoUs9G4NJxKWwsRa9yleg+Gx5AAAFRY35h8vCXYAAbgDgKABAA/lVl+xxGf7AgAAAAAEWVo=";
    private static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label); }
    static int run() throws Exception {
        byte[] tar;
        try(XzStream in=new XzStream(new ByteArrayInputStream(android.util.Base64.decode(FIXTURE,android.util.Base64.DEFAULT)))) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); Packages.repack(in,out); tar=out.toByteArray();
        }
        Map<String,Packages.Entry> entries=new HashMap<>(); Map<String,byte[]> data=new HashMap<>();
        ByteArrayInputStream in=new ByteArrayInputStream(tar); Packages.Entry e;
        while((e=Packages.next(in))!=null) {
            byte[] content=new byte[(int)e.size]; in.read(content,0,content.length); in.skip((512-e.size%512)%512);
            entries.put(e.name,e); data.put(e.name,content);
        }
        String deep="toolusr/include/directory00/directory01/directory02/directory03/directory04/directory05/directory06/directory07/directory08/directory09/header.h";
        check(entries.size()==6 && entries.containsKey(deep) && !entries.containsKey("toolusr/../escape"),"package paths stay inside the prefix");
        String header=new String(data.get(deep),StandardCharsets.UTF_8),elf=new String(data.get("toolusr/bin/tool"),StandardCharsets.ISO_8859_1);
        check(header.contains(ShellBroker.TOOLS+"/include") && !header.contains("com.termux"),"text prefix relocated");
        check(elf.split(ShellBroker.TOOLS,-1).length==201 && !elf.contains("com.termux"),"ELF prefix relocated in place");
        check(new String(data.get("toolusr/lib/blob.jar"),StandardCharsets.ISO_8859_1).contains("com.termux"),"binary containers unchanged");
        check(entries.get("toolusr/bin/hard").type=='2' && entries.get("toolusr/bin/hard").link.equals("tool"),"hard link becomes relative symlink");
        check(entries.get("toolusr/bin/abs").link.equals(ShellBroker.TOOLS+"/bin/tool"),"absolute symlink relocated");

        Map<String,Packages.Item> index=Packages.parse(
            "Package: app\nVersion: 1\nDepends: libb (>= 2), liba | libz, termux-tools\nFilename: pool/a.deb\nSHA256: AA\nSize: 10\n\n"+
            "Package: liba\nVersion: 3\nPre-Depends: libb\nFilename: pool/l.deb\nSHA256: bb\nSize: 5\nInstalled-Size: 2\n\n"+
            "Package: libb\nVersion: 2\nFilename: pool/b.deb\nSHA256: cc\nDescription: first line\n continued: line\n");
        List<String> order=new ArrayList<>(); for(Packages.Item item:Packages.closure(index,Arrays.asList("app"))) order.add(item.name);
        check(order.equals(Arrays.asList("libb","liba","app")) && index.get("liba").installed==2048 && index.get("app").sha256.equals("aa"),"dependency closure");
        check(Packages.relative("toolusr/a/b/c","toolusr/x/y").equals("../../x/y") && Packages.target("./data/data/com.termux/files/usr/./x")==null,"link and path helpers");
        System.out.println("PACKAGE_TESTS_OK 9"); return 9;
    }
}
