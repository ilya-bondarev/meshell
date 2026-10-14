package com.mesystem.shell;

import java.util.Arrays;

public final class TerminalModel {
    public static final int DEFAULT_FG = 0xffdce6ed, DEFAULT_BG = 0xff0c141b;
    private static final int HISTORY = 1200;
    private static final int[] COLORS = {
        0xff17232e,0xffef767a,0xff63d9a3,0xffedcd7b,0xff7cb7ff,0xffc69bea,0xff6ed8db,0xffdce6ed,
        0xff657789,0xffff9296,0xff91efbe,0xffffdf99,0xff9ccaff,0xffdfb5ff,0xff8df0f3,0xffffffff
    };
    public interface Reply { void send(String text); }
    public static final class Line {
        final int[] cp, fg, bg;
        final byte[] attributes;
        Line(int columns, int foreground, int background) {
            cp = new int[columns]; fg = new int[columns]; bg = new int[columns];
            attributes = new byte[columns];
            clear(0, columns, foreground, background);
        }
        void clear(int from, int to, int foreground, int background) {
            Arrays.fill(cp, from, to, ' '); Arrays.fill(fg, from, to, foreground);
            Arrays.fill(bg, from, to, background); Arrays.fill(attributes, from, to, (byte)0);
        }
        void copy(int from, int to, int length) {
            if (length <= 0) return;
            System.arraycopy(cp, from, cp, to, length); System.arraycopy(fg, from, fg, to, length);
            System.arraycopy(bg, from, bg, to, length); System.arraycopy(attributes, from, attributes, to, length);
        }
        Line resized(int columns) {
            Line result = new Line(columns, DEFAULT_FG, DEFAULT_BG);
            int n = Math.min(columns, cp.length);
            System.arraycopy(cp,0,result.cp,0,n); System.arraycopy(fg,0,result.fg,0,n);
            System.arraycopy(bg,0,result.bg,0,n); System.arraycopy(attributes,0,result.attributes,0,n);
            if (n > 0 && width(result.cp[n-1]) == 2) result.cp[n-1] = ' ';
            return result;
        }
    }
    private static final class Screen {
        Line[] lines;
        int x, y, savedX, savedY, top, bottom;
        boolean wrapped;
        Screen(int rows, int columns) {
            lines = new Line[rows]; bottom = rows-1;
            for (int i=0;i<rows;i++) lines[i] = new Line(columns, DEFAULT_FG, DEFAULT_BG);
        }
    }
    private final Line[] history = new Line[HISTORY];
    private int historyStart, historyCount;
    private Screen screen, primary;
    private int rows, columns, fg=DEFAULT_FG, bg=DEFAULT_BG, attributes;
    private int state, utfNeed, utfValue, utfMinimum;
    private final StringBuilder sequence = new StringBuilder(64);
    private boolean wrap = true, origin, insert;
    public boolean cursorVisible = true, applicationCursor, bracketedPaste;
    private Reply reply;

    public TerminalModel(int rows, int columns) {
        this.rows=rows; this.columns=columns; screen=new Screen(rows,columns);
    }
    public synchronized void setReply(Reply value) { reply=value; }
    public synchronized int rows() { return rows; }
    public synchronized int columns() { return columns; }
    public synchronized int historySize() { return primary == null ? historyCount : 0; }
    public synchronized int cursorX() { return screen.x; }
    public synchronized int cursorY() { return screen.y; }
    public synchronized Line visibleLine(int row, int scroll) {
        int count=historySize(), index=count-scroll+row;
        if (index<0) return null;
        if (index<count) return history[(historyStart+index)%HISTORY];
        index-=count;
        return index<rows ? screen.lines[index] : null;
    }
    public synchronized String text(int scroll) {
        StringBuilder result=new StringBuilder();
        for (int row=0;row<rows;row++) {
            Line line=visibleLine(row,scroll);
            if (line!=null) {
                int end=line.cp.length;
                while (end>0 && line.cp[end-1]==' ') end--;
                for (int col=0;col<end;col++) if (line.cp[col]>0) result.appendCodePoint(line.cp[col]);
            }
            result.append('\n');
        }
        return result.toString();
    }
    public synchronized void reset() {
        screen=new Screen(rows,columns); primary=null;
        Arrays.fill(history,null); historyCount=historyStart=0;
        fg=DEFAULT_FG; bg=DEFAULT_BG; attributes=state=utfNeed=0;
        wrap=cursorVisible=true; origin=insert=applicationCursor=bracketedPaste=false;
        sequence.setLength(0);
    }
    private void remember(Line line) {
        if (historyCount==HISTORY) {
            history[historyStart]=line; historyStart=(historyStart+1)%HISTORY;
        } else history[(historyStart+historyCount++)%HISTORY]=line;
    }
    public synchronized void resize(int newRows, int newColumns) {
        newRows=Math.max(2,Math.min(500,newRows)); newColumns=Math.max(10,Math.min(500,newColumns));
        if (newRows==rows && newColumns==columns) return;
        resizeScreen(screen,newRows,newColumns,primary==null);
        if (primary!=null) resizeScreen(primary,newRows,newColumns,false);
        rows=newRows; columns=newColumns;
    }
    private void resizeScreen(Screen s,int newRows,int newColumns,boolean saveHistory) {
        int shift=Math.max(0,s.y-newRows+1);
        Line[] next=new Line[newRows];
        if (saveHistory) for (int i=0;i<shift;i++) remember(s.lines[i]);
        for (int i=0;i<newRows;i++) next[i]=i+shift<s.lines.length
            ? s.lines[i+shift].resized(newColumns) : new Line(newColumns,DEFAULT_FG,DEFAULT_BG);
        s.lines=next; s.y=Math.max(0,s.y-shift); s.x=Math.min(s.x,newColumns-1);
        s.top=0; s.bottom=newRows-1; s.wrapped=false;
    }
    public synchronized void feed(byte[] data,int length) {
        for (int i=0;i<length;i++) {
            int b=data[i]&255;
            if (utfNeed>0) {
                if ((b&0xc0)==0x80) {
                    utfValue=(utfValue<<6)|(b&63);
                    if (--utfNeed==0) accept(utfValue<utfMinimum || utfValue>0x10ffff ||
                        (utfValue>=0xd800 && utfValue<=0xdfff) ? 0xfffd : utfValue);
                    continue;
                }
                utfNeed=0; accept(0xfffd);
            }
            if (b<0x80) accept(b);
            else if (b>=0xc2 && b<=0xdf) { utfNeed=1; utfValue=b&31; utfMinimum=0x80; }
            else if (b>=0xe0 && b<=0xef) { utfNeed=2; utfValue=b&15; utfMinimum=0x800; }
            else if (b>=0xf0 && b<=0xf4) { utfNeed=3; utfValue=b&7; utfMinimum=0x10000; }
            else accept(0xfffd);
        }
    }
    private void accept(int cp) {
        if (state==3 || state==4) { // OSC and DCS are ignored, including OSC clipboard writes.
            if (cp==7) state=0;
            else if (cp==27) state=4;
            else if (state==4) state=cp=='\\'?0:3;
            return;
        }
        if (cp==27) { state=1; sequence.setLength(0); return; }
        if (cp==0 || cp==7) return;
        if (cp==13) { screen.x=0; screen.wrapped=false; return; }
        if (cp==10 || cp==11 || cp==12) { newline(); return; }
        if (cp==8) { screen.x=Math.max(0,screen.x-1); screen.wrapped=false; return; }
        if (cp==9) { screen.x=Math.min(columns-1,(screen.x/8+1)*8); screen.wrapped=false; return; }
        if (cp<32 || cp==127) return;
        if (state==5) { state=0; return; }
        if (state==1) {
            state=0;
            switch(cp) {
                case '[': state=2; break;
                case ']': case 'P': case '^': case '_': state=3; break;
                case '(': case ')': case '*': case '+': state=5; break;
                case '7': save(); break;
                case '8': restore(); break;
                case 'D': newline(); break;
                case 'E': screen.x=0; newline(); break;
                case 'M': if(screen.y==screen.top) scrollDown(1); else screen.y=Math.max(0,screen.y-1); break;
                case 'c': reset(); break;
                default: break;
            }
            return;
        }
        if (state==2) {
            if (cp>=0x40 && cp<=0x7e) { csi((char)cp); state=0; sequence.setLength(0); }
            else if (sequence.length()<128) sequence.append((char)cp);
            else { state=0; sequence.setLength(0); }
            return;
        }
        put(cp);
    }
    public static int width(int cp) {
        if (cp==0) return 0;
        int type=Character.getType(cp);
        if (type==Character.NON_SPACING_MARK || type==Character.ENCLOSING_MARK || cp==0x200d) return 0;
        return (cp>=0x1100 && (cp<=0x115f || cp==0x2329 || cp==0x232a ||
            (cp>=0x2e80 && cp<=0xa4cf && cp!=0x303f) || (cp>=0xac00 && cp<=0xd7a3) ||
            (cp>=0xf900 && cp<=0xfaff) || (cp>=0xfe10 && cp<=0xfe19) ||
            (cp>=0xfe30 && cp<=0xfe6f) || (cp>=0xff00 && cp<=0xff60) ||
            (cp>=0xffe0 && cp<=0xffe6) || (cp>=0x1f300 && cp<=0x1faff) || cp>=0x20000)) ? 2 : 1;
    }
    private void put(int cp) {
        int width=width(cp);
        if (width==0) return;
        if (screen.wrapped || (width==2 && screen.x==columns-1)) {
            if (wrap) { screen.x=0; newline(); }
            screen.wrapped=false;
        }
        Line line=screen.lines[screen.y];
        if (insert) { line.copy(screen.x,Math.min(columns,screen.x+width),Math.max(0,columns-screen.x-width)); }
        if (line.cp[screen.x]==0 && screen.x>0) line.clear(screen.x-1,screen.x,fg,bg);
        if (width(line.cp[screen.x])==2 && screen.x+1<columns) line.clear(screen.x+1,screen.x+2,fg,bg);
        line.cp[screen.x]=cp; line.fg[screen.x]=fg; line.bg[screen.x]=bg;
        line.attributes[screen.x]=(byte)attributes;
        if (width==2 && screen.x+1<columns) {
            line.cp[screen.x+1]=0; line.fg[screen.x+1]=fg; line.bg[screen.x+1]=bg;
            line.attributes[screen.x+1]=(byte)attributes;
        }
        if (screen.x+width>=columns) { screen.x=columns-1; screen.wrapped=wrap; }
        else screen.x+=width;
    }
    private void newline() {
        screen.wrapped=false;
        if (screen.y==screen.bottom) scrollUp(1);
        else screen.y=Math.min(rows-1,screen.y+1);
    }
    private void scrollUp(int n) {
        n=Math.min(n,screen.bottom-screen.top+1);
        for (int i=0;i<n;i++) {
            Line old=screen.lines[screen.top];
            if (screen.top==0 && screen.bottom==rows-1 && primary==null) remember(old);
            System.arraycopy(screen.lines,screen.top+1,screen.lines,screen.top,screen.bottom-screen.top);
            screen.lines[screen.bottom]=new Line(columns,fg,bg);
        }
    }
    private void scrollDown(int n) {
        n=Math.min(n,screen.bottom-screen.top+1);
        for (int i=0;i<n;i++) {
            System.arraycopy(screen.lines,screen.top,screen.lines,screen.top+1,screen.bottom-screen.top);
            screen.lines[screen.top]=new Line(columns,fg,bg);
        }
    }
    private void save() { screen.savedX=screen.x; screen.savedY=screen.y; }
    private void restore() {
        screen.x=Math.min(columns-1,screen.savedX); screen.y=Math.min(rows-1,screen.savedY); screen.wrapped=false;
    }
    private static int arg(int[] args,int index,int fallback) {
        return index<args.length && args[index]!=0 ? args[index] : fallback;
    }
    private void csi(char command) {
        String body=sequence.toString();
        boolean special=body.startsWith("?");
        if (special || body.startsWith(">")) body=body.substring(1);
        String[] fields=body.split(";",-1);
        int[] args=new int[fields.length];
        for (int i=0;i<fields.length;i++) {
            try { args[i]=Math.min(10000,Math.max(0,Integer.parseInt(fields[i]))); }
            catch(NumberFormatException ignored) { args[i]=0; }
        }
        int n=arg(args,0,1), zero=args[0];
        int low=origin?screen.top:0, high=origin?screen.bottom:rows-1;
        switch(command) {
            case 'A': screen.y=Math.max(low,screen.y-n); break;
            case 'B': case 'e': screen.y=Math.min(high,screen.y+n); break;
            case 'C': case 'a': screen.x=Math.min(columns-1,screen.x+n); break;
            case 'D': screen.x=Math.max(0,screen.x-n); break;
            case 'E': screen.y=Math.min(high,screen.y+n); screen.x=0; break;
            case 'F': screen.y=Math.max(low,screen.y-n); screen.x=0; break;
            case 'G': case '`': screen.x=Math.min(columns-1,n-1); break;
            case 'd': screen.y=Math.min(high,low+n-1); break;
            case 'H': case 'f': screen.y=Math.min(high,low+n-1); screen.x=Math.min(columns-1,arg(args,1,1)-1); break;
            case 'J':
                if (zero==0) { eraseLine(screen.y,screen.x,columns); for(int r=screen.y+1;r<rows;r++) eraseLine(r,0,columns); }
                else if(zero==1) { for(int r=0;r<screen.y;r++) eraseLine(r,0,columns); eraseLine(screen.y,0,screen.x+1); }
                else if(zero==2) for(int r=0;r<rows;r++) eraseLine(r,0,columns);
                else if(zero==3) { Arrays.fill(history,null); historyCount=historyStart=0; }
                break;
            case 'K': eraseLine(screen.y,zero==0?screen.x:0,zero==1?screen.x+1:columns); break;
            case 'X': eraseLine(screen.y,screen.x,Math.min(columns,screen.x+n)); break;
            case 'P': {
                Line line=screen.lines[screen.y]; n=Math.min(n,columns-screen.x);
                line.copy(screen.x+n,screen.x,columns-screen.x-n); line.clear(columns-n,columns,fg,bg); break;
            }
            case '@': {
                Line line=screen.lines[screen.y]; n=Math.min(n,columns-screen.x);
                line.copy(screen.x,screen.x+n,columns-screen.x-n); line.clear(screen.x,screen.x+n,fg,bg); break;
            }
            case 'L': case 'M':
                if(screen.y>=screen.top && screen.y<=screen.bottom) {
                    int top=screen.top; screen.top=screen.y;
                    if(command=='L') scrollDown(n); else scrollUp(n);
                    screen.top=top;
                }
                break;
            case 'S': scrollUp(n); break;
            case 'T': scrollDown(n); break;
            case 'r': {
                int top=n-1, bottom=arg(args,1,rows)-1;
                if(top<bottom && bottom<rows) { screen.top=top; screen.bottom=bottom; screen.x=0; screen.y=origin?top:0; }
                break;
            }
            case 's': save(); break;
            case 'u': restore(); break;
            case 'm': rendition(args); return;
            case 'h': case 'l':
                for(int mode:args) mode(mode,command=='h',special);
                break;
            case 'n':
                if(reply!=null && !special) {
                    if(zero==5) reply.send("\033[0n");
                    else if(zero==6) reply.send("\033["+(screen.y+1)+";"+(screen.x+1)+"R");
                }
                return;
            case 'c': if(reply!=null) reply.send("\033[?1;2c"); return;
            default: return;
        }
        screen.wrapped=false;
    }
    private void eraseLine(int row,int start,int end) { screen.lines[row].clear(start,end,fg,bg); }
    private void mode(int mode,boolean enable,boolean special) {
        if(!special) { if(mode==4) insert=enable; return; }
        switch(mode) {
            case 1: applicationCursor=enable; break;
            case 6: origin=enable; screen.x=0; screen.y=enable?screen.top:0; break;
            case 7: wrap=enable; break;
            case 25: cursorVisible=enable; break;
            case 2004: bracketedPaste=enable; break;
            case 47: case 1047: case 1049:
                if(enable && primary==null) { primary=screen; screen=new Screen(rows,columns); }
                else if(!enable && primary!=null) { screen=primary; primary=null; }
                break;
            default: break;
        }
    }
    private static int color(int n) {
        n=Math.max(0,Math.min(255,n));
        if(n<16) return COLORS[n];
        if(n>=232) { int level=8+(n-232)*10; return 0xff000000|level<<16|level<<8|level; }
        int value=n-16, r=value/36, g=(value/6)%6, b=value%6;
        r=r==0?0:55+40*r; g=g==0?0:55+40*g; b=b==0?0:55+40*b;
        return 0xff000000|r<<16|g<<8|b;
    }
    private void rendition(int[] args) {
        for(int i=0;i<args.length;i++) {
            int a=args[i];
            if(a==0) { fg=DEFAULT_FG; bg=DEFAULT_BG; attributes=0; }
            else if(a==1) attributes|=1;
            else if(a==4) attributes|=2;
            else if(a==7) attributes|=4;
            else if(a==22) attributes&=~1;
            else if(a==24) attributes&=~2;
            else if(a==27) attributes&=~4;
            else if(a>=30 && a<=37) fg=COLORS[a-30];
            else if(a>=40 && a<=47) bg=COLORS[a-40];
            else if(a>=90 && a<=97) fg=COLORS[a-90+8];
            else if(a>=100 && a<=107) bg=COLORS[a-100+8];
            else if(a==39) fg=DEFAULT_FG;
            else if(a==49) bg=DEFAULT_BG;
            else if((a==38 || a==48) && i+2<args.length) {
                int result;
                if(args[i+1]==5) { result=color(args[i+2]); i+=2; }
                else if(args[i+1]==2 && i+4<args.length) {
                    result=0xff000000|Math.min(255,args[i+2])<<16|Math.min(255,args[i+3])<<8|Math.min(255,args[i+4]); i+=4;
                } else continue;
                if(a==38) fg=result; else bg=result;
            }
        }
    }
}
