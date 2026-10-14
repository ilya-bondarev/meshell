package com.mesystem.shell;

import java.util.ArrayList;
import java.util.List;

final class MarkdownTables {
    static List<String> cells(String line) {
        String s=line.trim(); List<String> out=new ArrayList<>(); StringBuilder cell=new StringBuilder();
        int code=0; boolean edge=false;
        for(int i=0;i<s.length();i++) {
            char ch=s.charAt(i);
            if(ch=='\\' && i+1<s.length() && s.charAt(i+1)=='|') { cell.append('|'); i++; continue; }
            if(ch=='`') {
                int end=i; while(end<s.length() && s.charAt(end)=='`') end++;
                int count=end-i; if(code==0) code=count; else if(code==count) code=0;
                cell.append(s,i,end); i=end-1; continue;
            }
            if(ch=='|' && code==0) {
                if(i!=0) out.add(cell.toString().trim());
                cell.setLength(0); edge=i==s.length()-1;
            } else { cell.append(ch); edge=false; }
        }
        if(!edge) out.add(cell.toString().trim()); return out;
    }
    static boolean separator(String line,int columns) {
        List<String> c=cells(line); if(c.size()!=columns || columns<1) return false;
        for(String s:c) if(!s.matches(":?-{3,}:?")) return false;
        return true;
    }
}
