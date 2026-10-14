package com.mesystem.shell;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownView {
    private static final Pattern INLINE=Pattern.compile("\\*\\*(.+?)\\*\\*|__([^_]+)__|`([^`\\n]+)`|~~(.+?)~~|\\[([^]\\n]+)]\\((https?://[^)\\s]+)\\)|(?<!\\*)\\*([^*\\n]+)\\*");
    private static final Pattern FENCE=Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final class Block {
        final String kind,text,language;
        Block(String kind,String text,String language) { this.kind=kind; this.text=text; this.language=language; }
    }
    private static final class Rendered {
        String kind="",text="",language="";
        LinearLayout container;
        TextView textView,label;
        boolean tail;
    }
    private static final class State { final List<Rendered> blocks=new ArrayList<>(); }
    private static CharSequence inline(String source,boolean streaming) {
        if(streaming) {
            int code=0,bold=0;
            for(int i=0;i<source.length();i++) {
                if(source.charAt(i)=='`') { code++; continue; }
                if(code%2!=0) continue;
                if(i+1<source.length() && source.charAt(i)=='*' && source.charAt(i+1)=='*') { bold++; i++; }
            }
            if(code%2!=0) source+='`';
            if(bold%2!=0) source+="**";
        }
        SpannableStringBuilder result=new SpannableStringBuilder(); Matcher m=INLINE.matcher(source); int from=0;
        while(m.find()) {
            result.append(source,from,m.start()); int start=result.length();
            String value=m.group(1)!=null?m.group(1):m.group(2)!=null?m.group(2):m.group(3)!=null?m.group(3):m.group(4)!=null?m.group(4):m.group(5)!=null?m.group(5):m.group(7);
            result.append(value); int end=result.length();
            if(m.group(1)!=null || m.group(2)!=null) result.setSpan(new StyleSpan(Typeface.BOLD),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else if(m.group(3)!=null) {
                result.setSpan(new TypefaceSpan("monospace"),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new RelativeSizeSpan(.9f),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new ForegroundColorSpan(Ui.MINT),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new BackgroundColorSpan(Ui.PANEL),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if(m.group(4)!=null) result.setSpan(new StrikethroughSpan(),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else if(m.group(5)!=null) result.setSpan(new URLSpan(m.group(6)),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else result.setSpan(new StyleSpan(Typeface.ITALIC),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from=m.end();
        }
        result.append(source,from,source.length()); return result;
    }
    private static void flush(List<Block> blocks,StringBuilder prose) {
        String text=prose.toString().trim(); if(!text.isEmpty()) blocks.add(new Block("text",text,"")); prose.setLength(0);
    }
    private static List<Block> parse(String source) {
        List<Block> result=new ArrayList<>(); StringBuilder prose=new StringBuilder(),code=new StringBuilder(); String fence=null,language="";
        String[] lines=source.split("\n",-1);
        for(int row=0;row<lines.length;row++) {
            String line=lines[row];
            if(fence==null && row+1<lines.length && line.contains("|") && MarkdownTables.separator(lines[row+1],MarkdownTables.cells(line).size())) {
                flush(result,prose); StringBuilder table=new StringBuilder(line).append('\n').append(lines[++row]);
                while(row+1<lines.length && !lines[row+1].trim().isEmpty() && lines[row+1].contains("|") && !FENCE.matcher(lines[row+1]).matches()) table.append('\n').append(lines[++row]);
                result.add(new Block("table",table.toString(),"")); continue;
            }
            Matcher match=FENCE.matcher(line);
            if(fence!=null) {
                if(match.matches() && match.group(1).charAt(0)==fence.charAt(0) && match.group(1).length()>=fence.length() && match.group(2).trim().isEmpty()) {
                    result.add(new Block("code",code.toString(),language)); code.setLength(0); fence=null;
                } else code.append(line).append('\n');
            } else if(match.matches()) { flush(result,prose); fence=match.group(1); language=match.group(2).trim(); }
            else if(line.matches("^#{1,6} .+")) {
                flush(result,prose); int level=line.indexOf(' '); result.add(new Block("h"+level,line.substring(level+1),""));
            } else if(line.matches("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")) { flush(result,prose); result.add(new Block("rule","","")); }
            else if(line.trim().isEmpty()) flush(result,prose);
            else prose.append(line.replaceFirst("^(\\s*)[-*] ","$1• ").replaceFirst("^> ?","│ ")).append('\n');
        }
        if(fence!=null) result.add(new Block("code",code.toString(),language)); else flush(result,prose);
        return result;
    }
    static void update(LinearLayout parent,String source,Runnable terminal,boolean streaming) {
        State state=parent.getTag() instanceof State?(State)parent.getTag():new State();
        if(parent.getTag()!=state) { parent.removeAllViews(); parent.setTag(state); }
        List<Block> blocks=parse(source);
        for(int i=0;i<blocks.size();i++) {
            Block block=blocks.get(i); Rendered r=i<state.blocks.size()?state.blocks.get(i):null;
            if(r==null || !r.kind.equals(block.kind)) {
                if(r!=null) parent.removeView(r.container);
                r=create(parent.getContext(),block.kind,terminal); parent.addView(r.container,i);
                if(i<state.blocks.size()) state.blocks.set(i,r); else state.blocks.add(r);
            }
            boolean tail=streaming && i==blocks.size()-1;
            if(!r.text.equals(block.text) || !r.language.equals(block.language) || r.tail!=tail) {
                r.text=block.text; r.language=block.language;
                r.tail=tail;
                if(block.kind.equals("table")) table(r.container,block.text);
                if(r.textView!=null) r.textView.setText(block.kind.equals("code")?trimLastNewline(block.text):inline(block.text,streaming && i==blocks.size()-1));
                if(r.label!=null) r.label.setText(block.language.isEmpty()?Language.text(R.string.code):block.language);
            }
        }
        while(state.blocks.size()>blocks.size()) { Rendered removed=state.blocks.remove(state.blocks.size()-1); parent.removeView(removed.container); }
    }
    private static void table(LinearLayout parent,String source) {
        Context c=parent.getContext();
        int previous=parent.getChildCount()>0 && parent.getChildAt(0) instanceof HorizontalScrollView?((HorizontalScrollView)parent.getChildAt(0)).getScrollX():0;
        parent.removeAllViews();
        String[] rows=source.split("\n"); List<String> headers=MarkdownTables.cells(rows[0]);
        List<String> align=MarkdownTables.cells(rows[1]); int columns=headers.size();
        int[] widths=new int[columns];
        for(int col=0;col<columns;col++) widths[col]=Ui.dp(c,Math.max(130,Math.min(240,headers.get(col).length()*8+28)));
        HorizontalScrollView scroll=new HorizontalScrollView(c); scroll.setFillViewport(false);
        android.widget.TableLayout grid=new android.widget.TableLayout(c);
        grid.setBackground(Ui.shape(c,Ui.PANEL,12,Ui.BORDER));
        for(int row=0;row<rows.length;row++) {
            if(row==1) continue; List<String> cells=MarkdownTables.cells(rows[row]);
            android.widget.TableRow line=new android.widget.TableRow(c);
            for(int col=0;col<columns;col++) {
                TextView cell=Ui.text(c,"",14,Ui.INK); cell.setText(inline(col<cells.size()?cells.get(col):"",false));
                cell.setTextIsSelectable(true); cell.setLinkTextColor(Ui.MINT); Ui.pad(cell,12,10,12,10);
                String a=align.get(col); cell.setGravity(android.view.Gravity.TOP|(a.endsWith(":")?(a.startsWith(":")?android.view.Gravity.CENTER_HORIZONTAL:android.view.Gravity.RIGHT):android.view.Gravity.LEFT));
                if(row==0) cell.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
                cell.setBackground(Ui.shape(c,row==0?Ui.PANEL2:Ui.PANEL,0,Ui.BORDER));
                line.addView(cell,new android.widget.TableRow.LayoutParams(widths[col],-1));
            }
            grid.addView(line);
        }
        scroll.addView(grid); parent.addView(scroll); if(previous>0) scroll.post(() -> scroll.scrollTo(previous,0));
    }
    private static String trimLastNewline(String text) { return text.endsWith("\n")?text.substring(0,text.length()-1):text; }
    private static Rendered create(Context c,String kind,Runnable terminal) {
        Rendered r=new Rendered(); r.kind=kind; r.container=Ui.column(c);
        LinearLayout.LayoutParams cp=Ui.size(c,-1,-2); cp.bottomMargin=Ui.dp(c,kind.startsWith("h")?9:13); r.container.setLayoutParams(cp);
        if(kind.equals("table")) return r;
        if(kind.equals("rule")) { android.view.View rule=new android.view.View(c); rule.setBackgroundColor(Ui.BORDER); r.container.addView(rule,Ui.size(c,-1,1)); return r; }
        if(kind.equals("code")) {
            r.container.setBackground(Ui.shape(c,Ui.PANEL,15,0)); Ui.pad(r.container,13,5,8,13);
            LinearLayout top=Ui.row(c); r.label=Ui.text(c,Language.text(R.string.code),11,Ui.MUTED); top.addView(r.label,new LinearLayout.LayoutParams(0,-2,1));
            IconView copy=new IconView(c,IconView.COPY,Language.text(R.string.copy_code),v -> {
                c.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(Language.text(R.string.code),trimLastNewline(r.text)));
                Toast.makeText(c,Language.text(R.string.code_copied),Toast.LENGTH_SHORT).show();
            }); top.addView(copy,Ui.size(c,40,40));
            IconView open=new IconView(c,IconView.TERMINAL,Language.text(R.string.copy_code_and_open_terminal),v -> {
                c.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(Language.text(R.string.code),trimLastNewline(r.text))); terminal.run();
                Toast.makeText(c,Language.text(R.string.code_in_clipboard),Toast.LENGTH_SHORT).show();
            }); top.addView(open,Ui.size(c,40,40)); r.container.addView(top);
            HorizontalScrollView scroll=new HorizontalScrollView(c); r.textView=Ui.text(c,"",13,0xffc7e5de);
            r.textView.setTypeface(Typeface.MONOSPACE); r.textView.setTextIsSelectable(true); r.textView.setLineSpacing(Ui.dp(c,3),1);
            scroll.addView(r.textView); r.container.addView(scroll); return r;
        }
        int size=kind.equals("h1")?25:kind.equals("h2")?22:kind.startsWith("h")?18:16;
        r.textView=kind.startsWith("h")?Ui.bold(c,"",size,Ui.INK):Ui.text(c,"",size,Ui.INK);
        r.textView.setTextIsSelectable(true); r.textView.setLinkTextColor(Ui.MINT); r.textView.setLineSpacing(Ui.dp(c,5),1);
        r.container.addView(r.textView); return r;
    }
}
