package com.mesystem.shell;

final class FoxMotion {
    private static final long NEVER=Long.MIN_VALUE/4;
    private static final int TAP_MS=650,BLINK_MS=200,GAZE_HOLD_MS=900,GAZE_MS=1500,EAR_MS=320,LIVELY_MS=10000;
    float scale=1,bob,hop,eye=1,lookX,lookY,sway,ear,happy,orbit;
    float energy; double clock;
    private long last=NEVER,tapAt=NEVER,blinkAt=NEVER,gazeAt=NEVER,earAt=NEVER,stirredAt=NEVER,nextBlink,nextEar;
    private float gazeX,gazeY;
    private boolean asleep,thinking;
    private int seed=0x2f6e2b1;

    void stir(long now) { stirredAt=now; }
    void tap(long now) { tapAt=now; stirredAt=now; }
    void gaze(float x,float y,long now) { gazeX=clamp(x); gazeY=clamp(y); gazeAt=now; stirredAt=now; }
    void blink(long now) { if(!asleep) blinkAt=now; }
    void think(boolean value) { thinking=value; }
    boolean asleep() { return asleep; }
    boolean reacting(long now) { return now-tapAt<TAP_MS; }
    boolean busy(long now) { return reacting(now) || now-blinkAt<BLINK_MS || now-gazeAt<GAZE_MS || now-earAt<EAR_MS; }
    boolean lively(long now) { return thinking || now-stirredAt<LIVELY_MS; }
    boolean moving(long now) { return lively(now) || energy>0; }
    long untilEvent(long now) {
        long next=Long.MAX_VALUE; if(nextBlink>0) next=Math.min(next,nextBlink); if(nextEar>0) next=Math.min(next,nextEar);
        return next==Long.MAX_VALUE?-1:Math.max(16,next-now);
    }
    void rest(boolean sleep) {
        asleep=sleep; tapAt=blinkAt=gazeAt=earAt=NEVER; nextBlink=nextEar=0;
        scale=1; bob=hop=lookX=lookY=sway=ear=happy=0; eye=sleep?.1f:1;
    }

    void step(long now,boolean ambient) {
        if(last==NEVER || now<last) { last=now; nextBlink=nextEar=0; }
        float dt=Math.min(100,now-last); last=now;
        if(ambient && !asleep) {
            if(nextBlink==0) nextBlink=now+2200+random(2600);
            if(nextEar==0) nextEar=now+4000+random(4000);
            if(now>=nextBlink) { blinkAt=now; nextBlink=now+2400+random(3400); }
            if(now>=nextEar) { earAt=now; nextEar=now+4500+random(5000); }
        }
        float target=ambient && lively(now)?1:0;
        energy+=(target-energy)*Math.min(1,dt/450); if(Math.abs(energy-target)<.01f) energy=target;
        clock+=dt*energy; double t=clock;
        if(ambient) {
            float breath=asleep?4800:3200;
            bob=(float)Math.sin(t*6.2831853/breath)*(asleep?1.4f:2f)*energy;
            sway=(float)Math.sin(t*6.2831853/(asleep?4200:2300))*(asleep?3:8)*energy;
            orbit=(orbit+dt*energy*(thinking?.0011f:.00035f))%6.2831853f;
        } else { bob=0; sway=0; }

        float p=progress(now,tapAt,TAP_MS);
        if(p<1) {
            float envelope=(float)Math.sin(Math.PI*p);
            scale=1+.03f*envelope*(1-p*.4f);
            hop=-10*(float)Math.abs(Math.sin(2*Math.PI*p))*(1-p);
            happy=p<.8f?1:(1-p)/.2f;
            sway+=(float)Math.sin(p*25)*10*(1-p);
        } else { scale=1; hop=0; happy=0; }

        float e=progress(now,earAt,EAR_MS);
        ear=e<1?(float)Math.sin(Math.PI*e)*9:0;

        if(asleep) eye=.1f;
        else {
            float b=progress(now,blinkAt,BLINK_MS);
            eye=b<1?1-.9f*(1-Math.abs(2*b-1)):1;
        }

        float targetX=0,targetY=0;
        if(now-gazeAt<GAZE_HOLD_MS) { targetX=gazeX; targetY=gazeY; }
        else if(thinking && ambient && !asleep) { targetX=.55f+.25f*(float)Math.sin(t/900); targetY=-.7f; }
        float follow=Math.min(1,dt/110);
        lookX=settle(lookX+(targetX-lookX)*follow,targetX);
        lookY=settle(lookY+(targetY-lookY)*follow,targetY);
    }

    private static float progress(long now,long start,int duration) {
        long elapsed=now-start; return elapsed<0 || elapsed>=duration?1:elapsed/(float)duration;
    }
    private static float settle(float value,float target) { return Math.abs(value-target)<.002f?target:clamp(value); }
    private static float clamp(float value) { return Math.max(-1,Math.min(1,value)); }
    private int random(int bound) { seed=seed*1103515245+12345; return ((seed>>>8)&0xffff)%bound; }
}
