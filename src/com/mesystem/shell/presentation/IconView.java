package com.mesystem.shell;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

final class IconView extends View {
    static final int HOME=1,CHAT=2,TERMINAL=3,GRID=4,SETTINGS=5,PLUS=6,CLIP=7,MIC=8,SEND=9,STOP=10,
        COPY=11,HISTORY=12,BACK=13,REFRESH=14,PLAY=15,POWER=16,UP=17,DOWN=18,LEFT=19,RIGHT=20,
        SAVE=21,TRASH=22,WIFI=23,CPU=24,SENSOR=25,CHART=26,BLUETOOTH=27,NOTES=28,PHONE=29,TV=30,MORE=31,
        FOX=32,SEARCH=33,KEY=34,CHEVRON=35;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private int icon,color=Ui.INK;
    private int size=22;
    IconView(Context context,int icon,String description,OnClickListener action) {
        super(context); this.icon=icon; setContentDescription(description);
        setMinimumWidth(Ui.dp(context,44)); setMinimumHeight(Ui.dp(context,44));
        if(action!=null) {
            setOnClickListener(action); setFocusable(true);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x256fecc8),Ui.shape(context,0x00000000,14,0),null));
        }
    }
    void icon(int value) { if(icon!=value) { icon=value; invalidate(); } }
    void color(int value) { if(color!=value) { color=value; invalidate(); } }
    void glyphSize(int value) { size=value; invalidate(); }
    @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    private void line(Canvas c,float... xy) {
        path.reset(); path.moveTo(xy[0],xy[1]); for(int i=2;i<xy.length;i+=2) path.lineTo(xy[i],xy[i+1]); c.drawPath(path,paint);
    }
    private void rect(Canvas c,float l,float t,float r,float b,float radius) { c.drawRoundRect(new RectF(l,t,r,b),radius,radius,paint); }
    @Override protected void onDraw(Canvas c) {
        float side=Ui.dp(getContext(),size),scale=side/24; c.save(); c.translate((getWidth()-side)/2,(getHeight()-side)/2); c.scale(scale,scale);
        paint.setColor(isEnabled()?color:Ui.MUTED); paint.setAlpha(isEnabled()?(isPressed()?160:255):90); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.65f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        switch(icon) {
            case MORE: paint.setStyle(Paint.Style.FILL); c.drawCircle(5,12,1.5f,paint); c.drawCircle(12,12,1.5f,paint); c.drawCircle(19,12,1.5f,paint); break;
            case HOME: line(c,3,10,12,3,21,10); line(c,5,9,5,21,10,21,10,15,14,15,14,21,19,21,19,9); break;
            case CHAT: rect(c,3,4,21,18,4); line(c,7,18,5,22,12,18); line(c,7,9,17,9); line(c,7,13,14,13); break;
            case TERMINAL: line(c,4,6,10,12,4,18); line(c,13,18,21,18); break;
            case GRID: for(int x=3;x<=14;x+=11) for(int y=3;y<=14;y+=11) rect(c,x,y,x+7,y+7,2); break;
            case SETTINGS: c.drawCircle(12,12,4,paint); for(int i=0;i<8;i++) { double a=i*Math.PI/4; line(c,12+(float)Math.cos(a)*8,12+(float)Math.sin(a)*8,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10); } c.drawCircle(12,12,8,paint); break;
            case PLUS: line(c,12,5,12,19); line(c,5,12,19,12); break;
            case CLIP: path.moveTo(8,13); path.lineTo(15,6); path.cubicTo(19,2,24,7,20,11); path.lineTo(10,21); path.cubicTo(4,27,-2,18,3,13); path.lineTo(13,3); c.drawPath(path,paint); break;
            case MIC: rect(c,8,2,16,15,4); c.drawArc(new RectF(5,7,19,19),0,180,false,paint); line(c,12,19,12,22); line(c,8,22,16,22); break;
            case SEND: line(c,5,10,12,3,19,10); line(c,12,3,12,21); break;
            case STOP: paint.setStyle(Paint.Style.FILL); rect(c,5,5,19,19,3); break;
            case COPY: rect(c,8,7,21,21,2); line(c,5,16,3,16,3,3,16,3,16,5); break;
            case HISTORY: c.drawArc(new RectF(4,4,22,22),-120,300,false,paint); line(c,4,3,4,9,10,9); line(c,13,8,13,13,17,15); break;
            case BACK: line(c,10,4,3,12,10,20); line(c,3,12,21,12); break;
            case REFRESH: c.drawArc(new RectF(4,4,20,20),35,300,false,paint); line(c,21,5,21,11,15,11); break;
            case PLAY: line(c,7,3,21,12,7,21,7,3); break;
            case POWER: c.drawArc(new RectF(3,4,21,22),-50,280,false,paint); line(c,12,2,12,12); break;
            case UP: line(c,4,16,12,8,20,16); break;
            case DOWN: line(c,4,8,12,16,20,8); break;
            case LEFT: line(c,16,4,8,12,16,20); break;
            case RIGHT: line(c,8,4,16,12,8,20); break;
            case SAVE: line(c,3,3,18,3,21,6,21,21,3,21,3,3); rect(c,7,3,16,9,1); rect(c,7,14,17,21,1); break;
            case TRASH: line(c,4,6,20,6); line(c,8,6,8,3,16,3,16,6); line(c,6,7,7,21,17,21,18,7); line(c,10,10,10,18); line(c,14,10,14,18); break;
            case WIFI: c.drawArc(new RectF(-1,3,25,27),-140,100,false,paint); c.drawArc(new RectF(3,8,21,26),-140,100,false,paint); c.drawArc(new RectF(7,13,17,23),-140,100,false,paint); c.drawCircle(12,21,0.8f,paint); break;
            case CPU: rect(c,5,5,19,19,3); rect(c,9,9,15,15,1); for(int n=8;n<=16;n+=4) { line(c,n,2,n,5); line(c,n,19,n,22); line(c,2,n,5,n); line(c,19,n,22,n); } break;
            case SENSOR: c.drawCircle(12,12,3,paint); c.drawArc(new RectF(5,5,19,19),-60,120,false,paint); c.drawArc(new RectF(5,5,19,19),120,120,false,paint); c.drawArc(new RectF(1,1,23,23),-60,120,false,paint); c.drawArc(new RectF(1,1,23,23),120,120,false,paint); break;
            case CHART: line(c,3,3,3,21,22,21); line(c,6,16,11,11,15,14,21,5); break;
            case BLUETOOTH: line(c,12,2,20,8,5,19); line(c,5,5,20,16,12,22,12,2); break;
            case NOTES: rect(c,4,2,20,22,3); line(c,8,7,16,7); line(c,8,12,16,12); line(c,8,17,13,17); break;
            case PHONE: rect(c,6,2,18,22,3); line(c,10,5,14,5); c.drawCircle(12,19,0.6f,paint); break;
            case TV: rect(c,2,4,22,18,3); line(c,12,18,12,22); line(c,8,22,16,22); break;
            case FOX: line(c,4,3,9,8,15,8,20,3,20,12,12,21,4,12,4,3); paint.setStyle(Paint.Style.FILL); c.drawCircle(9,12.5f,1.3f,paint); c.drawCircle(15,12.5f,1.3f,paint); c.drawCircle(12,16.5f,1.1f,paint); break;
            case SEARCH: c.drawCircle(10.5f,10.5f,6.5f,paint); line(c,15.5f,15.5f,21,21); break;
            case KEY: c.drawCircle(7.5f,14.5f,4.5f,paint); line(c,11,11,20,2); line(c,16,6,19,9); line(c,14,8,16,10); break;
            case CHEVRON: line(c,9,5,16,12,9,19); break;
            default: c.drawCircle(12,12,8,paint);
        }
        path.reset(); c.restore();
    }
}
