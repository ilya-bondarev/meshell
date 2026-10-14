package com.mesystem.shell;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

final class FoxView extends View {
    static final int IDLE=0,THINKING=1,SLEEPING=2,ALERT=3;
    private static final int RIM=0xff557f87,FACE=0xff98f3d5,EAR=0xff255761,SHADE=0xffb4baff,CHEEK=0xffe1fff3,CHEEK_SHADE=0xffd9e5ff,
        EYE=0xff183443,NOSE=0xff193544;
    private static final float[] RIM_SHAPE={114,102,117,34,151,68,191,68,223,35,225,103,172,145},
        FACE_SHAPE={118,96,122,45,149,76,172,64,196,77,218,46,222,97,173,139},
        EAR_L={124,61,126,90,143,80},EAR_R={213,61,192,81,215,90},
        SHADE_SHAPE={172,64,195,77,220,96,191,111,173,139},
        CHEEK_L={120,97,146,104,172,126,172,140,143,122},CHEEK_R={221,97,196,104,173,126,173,140,201,122},
        NOSE_SHAPE={165,120,181,120,173,128},
        STARS={33,33,80,68,267,121,283,159,106,21,231,167,61,165,303,87};
    // Planets ride the drawn orbit: an ellipse centred at 164,98 (radii 140 and 49) tilted -20 degrees about 166,100.
    private static final float TILT_COS=.9396926f,TILT_SIN=.34202015f,PLANET_A=-2.93f,PLANET_B=-.12f,MOON=-.94f;
    final FoxMotion motion=new FoxMotion();
    private final boolean scenery;
    private final float left,top,width,height;
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),line=new Paint(Paint.ANTI_ALIAS_FLAG),glow=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path(),signal=new Path();
    private final RectF oval=new RectF();
    private final Runnable tick=this::invalidate;
    private float scale=1,offsetX,offsetY,downX,downY,turn,nod,earTilt,px,py;
    private boolean live;
    private int mood=IDLE;

    FoxView(Context context,boolean scenery) {
        super(context); this.scenery=scenery;
        left=scenery?0:96; top=scenery?0:18; width=scenery?320:152; height=scenery?184:140;
        line.setStyle(Paint.Style.STROKE); line.setStrokeCap(Paint.Cap.ROUND); line.setStrokeJoin(Paint.Join.ROUND);
        glow.setShader(new RadialGradient(177,97,123,new int[]{0x404b8fbc,0x08142235,0x0009121c},null,Shader.TileMode.CLAMP));
        signal.moveTo(29,111); signal.cubicTo(43,153,132,170,236,127);
        setClickable(true); describe();
    }

    void mood(int value) {
        if(value==mood) return; int previous=mood; mood=value; long now=SystemClock.uptimeMillis();
        motion.stir(now);
        if(value==SLEEPING) motion.rest(true);
        else if(previous==SLEEPING) { motion.rest(false); motion.tap(now); }
        motion.think(value==THINKING); describe(); invalidate();
    }
    void cheer() { motion.tap(SystemClock.uptimeMillis()); invalidate(); }
    private void describe() {
        setContentDescription(mood==SLEEPING?Language.text(R.string.meshell_fox_asleep_chatgpt_sign_in_required):mood==THINKING?Language.text(R.string.meshell_fox_thinking):
            mood==ALERT?Language.text(R.string.meshell_fox_error):Language.text(R.string.meshell_fox_tap_to_pet));
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible); live=visible;
        if(visible) { motion.stir(SystemClock.uptimeMillis()); invalidate(); } else removeCallbacks(tick);
    }
    @Override protected void onDetachedFromWindow() { live=false; removeCallbacks(tick); super.onDetachedFromWindow(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x=(event.getX()-offsetX)/scale+left,y=(event.getY()-offsetY)/scale+top; long now=SystemClock.uptimeMillis();
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downX=event.getX(); downY=event.getY(); motion.gaze((x-170)/110,(y-92)/80,now); invalidate(); return true;
            case MotionEvent.ACTION_MOVE: motion.gaze((x-170)/110,(y-92)/80,now); invalidate(); return true;
            case MotionEvent.ACTION_UP:
                if(Math.hypot(event.getX()-downX,event.getY()-downY)<Ui.dp(getContext(),16)) {
                    motion.tap(now);
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); performClick();
                }
                invalidate(); return true;
            default: return super.onTouchEvent(event);
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }

    @Override protected void onDraw(Canvas c) {
        long now=SystemClock.uptimeMillis(); boolean animate=live && ValueAnimator.areAnimatorsEnabled();
        motion.step(now,animate);
        if(getWidth()==0 || getHeight()==0) return;
        scale=Math.min(getWidth()/width,getHeight()/height); offsetX=(getWidth()-width*scale)/2; offsetY=(getHeight()-height*scale)/2;
        c.save(); c.translate(offsetX,offsetY); c.scale(scale,scale); c.translate(-left,-top);
        if(scenery) { backdrop(c); planets(c,false); }
        else { line.setStrokeWidth(.8f); line.setColor(0xff35515c); c.drawCircle(170,91,59,line); }
        float lift=motion.bob+motion.hop;
        c.save(); c.translate(0,lift); c.rotate(motion.sway*.35f,172,145); c.scale(motion.scale,motion.scale,172,145);
        head(c);
        c.restore();
        if(scenery) { signal(c); planets(c,true); }
        if(mood==THINKING) thinking(c,lift); else if(mood==SLEEPING) dozing(c);
        c.restore();
        if(animate) {
            removeCallbacks(tick);
            // Display rate for reactions, ~30 fps while drifting, then wake only for the next blink.
            if(motion.busy(now) || mood==THINKING) postInvalidateOnAnimation();
            else if(motion.moving(now)) postOnAnimationDelayed(tick,33);
            else { long wait=motion.untilEvent(now); if(wait>0) postOnAnimationDelayed(tick,wait); }
        }
    }

    private void backdrop(Canvas c) {
        float energy=motion.energy;
        glow.setAlpha((int)Math.max(0,Math.min(255,255-12*energy+6*motion.bob))); c.drawRect(0,0,320,184,glow);
        line.setStrokeWidth(.8f); line.setColor(0xff2a4757);
        c.save(); c.rotate(-20,166,100); oval.set(24,49,304,147); c.drawOval(oval,line); c.restore();
        line.setColor(0xff264039); c.drawCircle(170,91,77,line);
        line.setColor(0xff35515c); c.drawCircle(170,91,59,line);
        line.setStrokeWidth(1.3f);
        for(int i=0;i<STARS.length;i+=2) {
            float dim=energy*(.5f-.5f*(float)Math.sin(motion.clock*.0021+i*1.7)),x=STARS[i],y=STARS[i+1],arm=2-.7f*dim;
            line.setColor(i%4==0?0xff5c849a:0xff7e8bba); line.setAlpha((int)(255*(1-.65f*dim)));
            c.drawLine(x-arm,y,x+arm,y,line); c.drawLine(x,y-arm,x,y+arm,line);
        }
        line.setAlpha(255);
        double moon=MOON-motion.orbit*.6;
        fill.setColor(Ui.LAVENDER); c.drawCircle(170+80*(float)Math.cos(moon),91+80*(float)Math.sin(moon),4,fill);
    }
    private void planets(Canvas c,boolean front) {
        for(int i=0;i<2;i++) {
            double angle=(i==0?PLANET_A:PLANET_B)+motion.orbit; float sin=(float)Math.sin(angle);
            if((sin>0)!=front) continue;
            float dx=-2+140*(float)Math.cos(angle),dy=-2+49*sin;
            float x=166+dx*TILT_COS+dy*TILT_SIN,y=100-dx*TILT_SIN+dy*TILT_COS,r=(i==0?3.3f:2.5f)*(1+.2f*sin);
            fill.setColor(Ui.MINT); c.drawCircle(x,y,r,fill);
            if(motion.energy>0) { fill.setAlpha((int)(40*motion.energy)); c.drawCircle(x,y,r*2.6f,fill); fill.setAlpha(255); }
        }
    }
    private void signal(Canvas c) {
        line.setStrokeWidth(.9f); line.setColor(0xff517989); c.drawPath(signal,line);
        fill.setColor(Ui.MINT); c.drawCircle(236,127,3,fill);
        if(motion.energy==0) return;
        float u=(float)(motion.clock/4200%1),v=1-u,a=v*v*v,b=3*v*v*u,d=3*v*u*u,e=u*u*u;
        fill.setAlpha((int)(230*motion.energy*Math.sin(Math.PI*u)));
        c.drawCircle(a*29+b*43+d*132+e*236,a*111+b*153+d*170+e*127,1.8f,fill); fill.setAlpha(255);
    }

    private void head(Canvas c) {
        turn=motion.lookX*8; nod=motion.lookY*6; earTilt=motion.ear*.017453292f;
        polygon(c,RIM,RIM_SHAPE); polygon(c,FACE,FACE_SHAPE); polygon(c,EAR,EAR_L); polygon(c,EAR,EAR_R);
        polygon(c,SHADE,SHADE_SHAPE); polygon(c,CHEEK,CHEEK_L); polygon(c,CHEEK_SHADE,CHEEK_R);
        if(motion.happy>0) {
            fill.setColor(0xd9b8ff|((int)(150*motion.happy)<<24));
            place(146,114); oval.set(px-8,py-4,px+8,py+4); c.drawOval(oval,fill);
            place(199,114); oval.set(px-8,py-4,px+8,py+4); c.drawOval(oval,fill);
        }
        eyes(c);
        polygon(c,NOSE,NOSE_SHAPE);
        if(motion.happy>.3f) {
            line.setColor(EYE); line.setAlpha((int)(255*Math.min(1,motion.happy*2))); line.setStrokeWidth(1.6f); path.reset();
            place(167,131); path.moveTo(px,py); place(170,134.5f); float qx=px,qy=py; place(173,131); path.quadTo(qx,qy,px,py);
            place(176,134.5f); qx=px; qy=py; place(179,131); path.quadTo(qx,qy,px,py); c.drawPath(path,line); line.setAlpha(255);
        }
    }
    private void eyes(Canvas c) {
        boolean closed=motion.asleep() || mood==SLEEPING,arc=closed || motion.happy>.5f;
        line.setColor(EYE); line.setStrokeWidth(arc?3:3*(.4f+.6f*motion.eye));
        for(int side=0;side<2;side++) {
            float outer=side==0?139:206,inner=side==0?151:194,middle=(outer+inner)/2,cy=95.5f;
            if(arc) {
                float y=closed?cy:cy+2,bend=closed?8:-8;
                path.reset(); place(outer,y); path.moveTo(px,py); place(middle,y+bend); float qx=px,qy=py; place(inner,y); path.quadTo(qx,qy,px,py);
                c.drawPath(path,line);
            } else {
                float slant=(mood==ALERT?-2.5f:2.5f)*motion.eye;
                place(outer,cy-slant); float x0=px,y0=py; place(inner,cy+slant); c.drawLine(x0,y0,px,py,line);
            }
        }
    }
    private void polygon(Canvas c,int color,float[] xy) {
        path.reset();
        for(int i=0;i<xy.length;i+=2) { place(xy[i],xy[i+1]); if(i==0) path.moveTo(px,py); else path.lineTo(px,py); }
        path.close(); fill.setColor(color); c.drawPath(path,fill);
    }
    // The right ear tip swings about its base; the face centre shifts toward the gaze while the outline stays.
    private void place(float x,float y) {
        if(earTilt!=0 && x>186 && y<72) {
            float angle=earTilt*Math.min(1,(72-y)/26),cos=(float)Math.cos(angle),sin=(float)Math.sin(angle),dx=x-205,dy=y-80;
            x=205+dx*cos-dy*sin; y=80+dx*sin+dy*cos;
        }
        float weight=Math.max(0,1-Math.abs(x-172)/58)*Math.max(0,1-Math.abs(y-92)/60);
        px=x+turn*weight; py=y+nod*weight;
    }
    private void thinking(Canvas c,float lift) {
        for(int i=0;i<3;i++) {
            float wave=Math.max(0,(float)Math.sin(motion.clock*.006-i*.9));
            fill.setColor(Ui.MINT); fill.setAlpha((int)(70+185*wave));
            c.drawCircle(228+i*8,42-i*7+lift-wave*2,2.4f+i*.6f,fill);
        }
        fill.setAlpha(255);
    }
    private void dozing(Canvas c) {
        fill.setColor(Ui.MINT);
        for(int i=0;i<2;i++) {
            float phase=(float)((motion.clock/2200+i*.5)%1);
            fill.setAlpha((int)(220*Math.sin(Math.PI*phase))); fill.setTextSize(11+i*4+phase*3);
            c.drawText("z",224+i*9+phase*5,44-i*9-phase*14,fill);
        }
        fill.setAlpha(255);
    }
}
