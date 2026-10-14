package com.mesystem.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

public final class TerminalView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final char[] glyph=new char[2];
    private ShellService service;
    private float cellWidth, cellHeight, baseline, downY, lastY;
    private int scroll;
    private boolean moved, control;
    private final float inset;

    public TerminalView(Context context) {
        super(context);
        inset=6*getResources().getDisplayMetrics().density;
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(13*getResources().getDisplayMetrics().scaledDensity);
        cellWidth=paint.measureText("M");
        Paint.FontMetrics metrics=paint.getFontMetrics();
        cellHeight=(float)Math.ceil(metrics.descent-metrics.ascent+2);
        baseline=-metrics.ascent;
        setFocusable(true); setFocusableInTouchMode(true); setClickable(true);
        setContentDescription(Language.text(R.string.meshell_terminal_tap_for_keyboard_scroll_for_history));
    }
    void attach(ShellService value) { service=value; measureTerminal(); invalidate(); }
    void refreshed() { scroll=Math.min(scroll,service==null?0:service.terminal.historySize()); postInvalidateOnAnimation(); }
    void setControl(boolean value) { control=value; }
    private void measureTerminal() {
        if(service==null || getWidth()==0 || getHeight()==0) return;
        int cols=Math.max(10,Math.min(500,(int)((getWidth()-2*inset)/cellWidth)));
        int rows=Math.max(2,Math.min(500,(int)((getHeight()-2*inset)/cellHeight)));
        service.resize(rows,cols);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { measureTerminal(); }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(TerminalModel.DEFAULT_BG);
        if(service==null) return;
        TerminalModel model=service.terminal;
        synchronized(model) {
            for(int row=0;row<model.rows();row++) {
                TerminalModel.Line line=model.visibleLine(row,scroll);
                if(line==null) continue;
                int count=Math.min(model.columns(),line.cp.length);
                float top=inset+row*cellHeight;
                for(int col=0;col<count;col++) {
                    int foreground=line.fg[col], background=line.bg[col], flags=line.attributes[col];
                    if((flags&4)!=0) { int swap=foreground; foreground=background; background=swap; }
                    float left=inset+col*cellWidth;
                    if(background!=TerminalModel.DEFAULT_BG) {
                        paint.setColor(background); canvas.drawRect(left,top,left+cellWidth,top+cellHeight,paint);
                    }
                    int cp=line.cp[col];
                    if(cp<=32) continue;
                    paint.setColor(foreground); paint.setFakeBoldText((flags&1)!=0);
                    paint.setUnderlineText((flags&2)!=0);
                    int length=Character.toChars(cp,glyph,0);
                    canvas.drawText(glyph,0,length,left,top+baseline,paint);
                }
            }
            paint.setFakeBoldText(false); paint.setUnderlineText(false);
            if(scroll==0 && model.cursorVisible) {
                float left=inset+model.cursorX()*cellWidth, top=inset+model.cursorY()*cellHeight;
                paint.setColor(service.connected?0xff63e6be:0xff6d8190);
                canvas.drawRect(left,top+cellHeight-2,left+cellWidth,top+cellHeight,paint);
            }
            if(scroll>0) {
                paint.setColor(0xff63e6be); canvas.drawRect(getWidth()-3,inset,getWidth(),inset+24,paint);
            }
        }
    }
    boolean send(String text) {
        scroll=0; invalidate();
        return service!=null && service.send(text);
    }
    void paste(String text) {
        if(service==null) return;
        text=text.replace("\u0000","");
        synchronized(service.terminal) {
            if(service.terminal.bracketedPaste) text="\033[200~"+text+"\033[201~";
        }
        send(text);
    }
    String visibleText() { return service==null?"":service.terminal.text(scroll); }
    void showKeyboard() {
        requestFocus(); getContext().getSystemService(InputMethodManager.class).showSoftInput(this,InputMethodManager.SHOW_IMPLICIT);
    }
    String arrow(char letter) {
        if(service==null) return "\033["+letter;
        synchronized(service.terminal) { return service.terminal.applicationCursor?"\033O"+letter:"\033["+letter; }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN) {
            downY=lastY=event.getY(); moved=false; return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE) {
            if(Math.abs(event.getY()-downY)>cellHeight/2) moved=true;
            if(moved && service!=null) {
                int lines=(int)((event.getY()-lastY)/cellHeight);
                if(lines!=0) {
                    scroll=Math.max(0,Math.min(service.terminal.historySize(),scroll+lines));
                    lastY=event.getY(); invalidate();
                }
            }
            return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_UP) {
            if(!moved) performClick(); return true;
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); showKeyboard(); return true; }
    @Override public boolean onCheckIsTextEditor() { return true; }
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        info.imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_FULLSCREEN|EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this,false) {
            private String composing="";
            private void replaceComposing(String next) {
                int remove=composing.codePointCount(0,composing.length());
                if(remove>0) TerminalView.this.send(repeatDelete(remove));
                inputText(next); composing=next;
            }
            @Override public boolean commitText(CharSequence text,int position) {
                replaceComposing(text.toString()); composing=""; return true;
            }
            @Override public boolean setComposingText(CharSequence text,int position) {
                replaceComposing(text.toString()); return true;
            }
            @Override public boolean finishComposingText() { composing=""; return true; }
            @Override public boolean deleteSurroundingText(int before,int after) {
                composing="";
                if(before>0) TerminalView.this.send(repeatDelete(Math.min(before,256)));
                if(after>0) for(int i=0;i<Math.min(after,256);i++) TerminalView.this.send("\033[3~");
                return true;
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int before,int after) { return deleteSurroundingText(before,after); }
            @Override public boolean sendKeyEvent(KeyEvent event) {
                if(event.getAction()==KeyEvent.ACTION_DOWN) { composing=""; return key(event); }
                return true;
            }
            @Override public boolean performEditorAction(int action) { composing=""; TerminalView.this.send("\r"); return true; }
        };
    }
    private static String repeatDelete(int n) { char[] value=new char[n]; java.util.Arrays.fill(value,'\u007f'); return new String(value); }
    private void inputText(String value) {
        if(control && !value.isEmpty()) {
            int cp=value.codePointAt(0);
            if(cp>='a' && cp<='z') cp-=32;
            if(cp>=64 && cp<=95) value=String.valueOf((char)(cp&31))+value.substring(Character.charCount(value.codePointAt(0)));
        }
        send(value.replace("\n","\r"));
    }
    @Override public boolean onKeyDown(int code,KeyEvent event) { return key(event) || super.onKeyDown(code,event); }
    private boolean key(KeyEvent event) {
        String value;
        switch(event.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: value="\r"; break;
            case KeyEvent.KEYCODE_DEL: value="\u007f"; break;
            case KeyEvent.KEYCODE_FORWARD_DEL: value="\033[3~"; break;
            case KeyEvent.KEYCODE_TAB: value="\t"; break;
            case KeyEvent.KEYCODE_ESCAPE: value="\033"; break;
            case KeyEvent.KEYCODE_DPAD_UP: value=arrow('A'); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: value=arrow('B'); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: value=arrow('C'); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: value=arrow('D'); break;
            case KeyEvent.KEYCODE_MOVE_HOME: value="\033[H"; break;
            case KeyEvent.KEYCODE_MOVE_END: value="\033[F"; break;
            case KeyEvent.KEYCODE_PAGE_UP: value="\033[5~"; break;
            case KeyEvent.KEYCODE_PAGE_DOWN: value="\033[6~"; break;
            default:
                int cp=event.getUnicodeChar(event.getMetaState()&~KeyEvent.META_CTRL_MASK);
                if(cp==0 || (cp&KeyCharacterMap.COMBINING_ACCENT)!=0) return false;
                if(event.isCtrlPressed() || control) {
                    if(cp>='a' && cp<='z') cp-=32;
                    if(cp>=64 && cp<=95) cp&=31;
                }
                value=new String(Character.toChars(cp));
                if(event.isAltPressed()) value="\033"+value;
        }
        send(value); return true;
    }
}
