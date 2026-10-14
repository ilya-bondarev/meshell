package com.mesystem.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class SparkView extends View {
    private final float[][] values=new float[3][120];
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); private final Path path=new Path();
    private int count,head,series=1; private boolean percent;
    SparkView(Context c,boolean percent) { super(c); this.percent=percent; setMinimumHeight(Ui.dp(c,85)); }
    void add(float... point) {
        series=Math.min(3,point.length); for(int i=0;i<series;i++) values[i][head]=point[i];
        head=(head+1)%120; count=Math.min(120,count+1); invalidate();
    }
    void set(float[] points) { count=head=0; for(float p:points) { values[0][head]=p; head=(head+1)%120; count=Math.min(120,count+1); } series=1; invalidate(); }
    @Override protected void onDraw(Canvas c) {
        float inset=Ui.dp(getContext(),4),width=getWidth()-2*inset,height=getHeight()-2*inset;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(getContext(),1)); paint.setColor(Ui.BORDER);
        for(int i=1;i<4;i++) c.drawLine(inset,inset+height*i/4,inset+width,inset+height*i/4,paint);
        if(count==0) return;
        float min=percent?0:Float.MAX_VALUE,max=percent?100:-Float.MAX_VALUE;
        if(!percent) for(int s=0;s<series;s++) for(int i=0;i<count;i++) { float v=values[s][(head-count+i+120)%120]; min=Math.min(min,v); max=Math.max(max,v); }
        if(max-min<.001f) { max+=1; min-=1; }
        int[] colors={Ui.MINT,Ui.LAVENDER,0xffefc48c};
        for(int s=0;s<series;s++) {
            path.reset();
            for(int i=0;i<count;i++) {
                float x=inset+width*i/Math.max(1,count-1),y=inset+height*(1-(values[s][(head-count+i+120)%120]-min)/(max-min));
                if(i==0) path.moveTo(x,y); else path.lineTo(x,y);
            }
            paint.setStrokeWidth(Ui.dp(getContext(),1.6f)); paint.setColor(colors[s]); c.drawPath(path,paint);
        }
    }
}
