package com.mesystem.shell;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

final class ChatScroll extends ScrollView {
    static final class Policy {
        boolean following=true;
        int generation;
        void userPosition(int distance,int threshold) { following=distance<=threshold; generation++; }
        void jump() { following=true; generation++; }
        void restore(boolean follow) { following=follow; generation++; }
        void pause() { generation++; }
        boolean permit(int ticket,boolean touching) { return following && !touching && ticket==generation; }
    }
    final Policy policy=new Policy();
    private final android.os.Handler gestures=new android.os.Handler(android.os.Looper.getMainLooper());
    boolean touching;
    Runnable changed;
    private final Runnable follow=() -> {
        if(policy.following && !touching && isShown()) { super.scrollTo(0,range()); notifyPosition(); }
    };
    ChatScroll(Context context) {
        super(context); setFillViewport(true); setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        setOnScrollChangeListener((v,x,y,oldX,oldY) -> {
            if(touching) policy.userPosition(distance(),Ui.dp(getContext(),40));
            notifyPosition();
        });
    }
    int range() { return getChildCount()==0?0:Math.max(0,getChildAt(0).getHeight()-getHeight()+getPaddingTop()+getPaddingBottom()); }
    int distance() { return range()-getScrollY(); }
    private void notifyPosition() { if(changed!=null) changed.run(); }
    void scheduleFollow() {
        removeCallbacks(follow);
        final int ticket=policy.generation;
        post(() -> { if(policy.permit(ticket,touching) && isShown()) follow.run(); });
    }
    void jumpToEnd() { policy.jump(); touching=false; scheduleFollow(); }
    void restorePosition(int y,boolean following) {
        policy.restore(following); final int ticket=policy.generation;
        post(() -> { if(ticket!=policy.generation || touching) return;
            super.scrollTo(0,following?range():Math.min(y,range())); notifyPosition();
        });
    }
    void inactive() { gesture++; policy.pause(); touching=false; removeCallbacks(follow); gestures.removeCallbacksAndMessages(null); }
    @Override protected void onDetachedFromWindow() { inactive(); super.onDetachedFromWindow(); }
    private int gesture;
    private void beginGesture() { gesture++; touching=true; policy.pause(); removeCallbacks(follow); }
    private void endGesture() {
        policy.userPosition(distance(),Ui.dp(getContext(),40)); final int ticket=gesture;
        settle(ticket,getScrollY(),0);
    }
    private void settle(int ticket,int y,int passes) {
        gestures.postDelayed(() -> {
            if(ticket!=gesture) return;
            if(getScrollY()!=y && passes<25) { settle(ticket,getScrollY(),passes+1); return; }
            policy.userPosition(distance(),Ui.dp(getContext(),40)); touching=false; notifyPosition();
            if(policy.following) scheduleFollow();
        },100);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // Track the whole dispatch, even if a selectable TextView disallows interception.
        // Otherwise its long-press/selection gesture could leave following paused forever.
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN) beginGesture();
        boolean result=super.dispatchTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL) endGesture();
        return result;
    }
    @Override public boolean executeKeyEvent(android.view.KeyEvent event) {
        int code=event.getKeyCode(); boolean navigation=code==android.view.KeyEvent.KEYCODE_DPAD_UP || code==android.view.KeyEvent.KEYCODE_DPAD_DOWN || code==android.view.KeyEvent.KEYCODE_SPACE || code==android.view.KeyEvent.KEYCODE_PAGE_UP || code==android.view.KeyEvent.KEYCODE_PAGE_DOWN || code==android.view.KeyEvent.KEYCODE_MOVE_HOME || code==android.view.KeyEvent.KEYCODE_MOVE_END;
        if(!navigation) return super.executeKeyEvent(event);
        beginGesture(); boolean result=super.executeKeyEvent(event); endGesture(); return result;
    }
    @Override public boolean performAccessibilityAction(int action,android.os.Bundle arguments) {
        boolean navigation=action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD || action==android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId() || action==android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
        if(!navigation) return super.performAccessibilityAction(action,arguments);
        beginGesture(); boolean result=super.performAccessibilityAction(action,arguments); endGesture(); return result;
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if(event.getActionMasked()!=MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(event);
        beginGesture(); boolean result=super.onGenericMotionEvent(event); endGesture(); return result;
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        int y=getScrollY(); super.onLayout(changed,l,t,r,b);
        if(!policy.following) super.scrollTo(0,y);
    }
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
        int y=getScrollY(); super.onSizeChanged(w,h,oldW,oldH);
        if(policy.following) scheduleFollow(); else super.scrollTo(0,y);
    }
    @Override public boolean requestChildRectangleOnScreen(View child,Rect rectangle,boolean immediate) { return false; }
    @Override public void requestChildFocus(View child,View focused) {
        int y=getScrollY(); super.requestChildFocus(child,focused);
        if(!policy.following) super.scrollTo(0,y);
    }
}
