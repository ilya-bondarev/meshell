package com.mesystem.shell;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG=0xff0a131d,PANEL=0xff111f2c,PANEL2=0xff182a3b,PANEL3=0xff203649,BORDER=0xff243a4d,
        INK=0xffeef4fa,MUTED=0xff93a8bb,FAINT=0xff60778b,MINT=0xff70edc8,MINT_SOFT=0x2470edc8,
        LAVENDER=0xffb4baff,LAVENDER_SOFT=0x24b4baff,DANGER=0xffffb1a6,DANGER_SOFT=0x24ffb1a6,WARN=0xffefc48c;
    static final int MAX_CONTENT=680;

    static int dp(Context c,float n) { return (int)(c.getResources().getDisplayMetrics().density*n+0.5f); }
    static GradientDrawable shape(Context c,int color,int radius,int border) {
        GradientDrawable bg=new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(c,radius));
        if(border!=0) bg.setStroke(dp(c,1),border); return bg;
    }
    static Drawable ripple(Context c,Drawable content,int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x2c70edc8),content,shape(c,0xffffffff,radius,0));
    }
    static <T extends View> T pressable(T view,int color,int radius,int border) {
        Context c=view.getContext(); view.setBackground(ripple(c,shape(c,color,radius,border),radius));
        StateListAnimator press=new StateListAnimator();
        press.addState(new int[]{android.R.attr.state_pressed},scaleTo(.97f,90));
        press.addState(new int[0],scaleTo(1f,160));
        view.setStateListAnimator(press); view.setClickable(true); view.setFocusable(true); return view;
    }
    private static ObjectAnimator scaleTo(float value,int duration) {
        ObjectAnimator animator=new ObjectAnimator();
        animator.setValues(PropertyValuesHolder.ofFloat("scaleX",value),PropertyValuesHolder.ofFloat("scaleY",value));
        animator.setDuration(duration); animator.setInterpolator(new DecelerateInterpolator()); return animator;
    }
    static TextView text(Context c,String value,int size,int color) {
        TextView view=new TextView(c); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("kern"); view.setIncludeFontPadding(false); return view;
    }
    static TextView bold(Context c,String value,int size,int color) {
        TextView v=text(c,value,size,color); v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return v;
    }
    static TextView caption(Context c,String value) {
        TextView v=text(c,value,12,MUTED); v.setLineSpacing(dp(c,3),1); return v;
    }
    static TextView section(Context c,String value) {
        TextView v=bold(c,value.toUpperCase(java.util.Locale.ROOT),11,FAINT); v.setLetterSpacing(.08f); pad(v,4,0,4,8); return v;
    }
    static Button button(Context c,String label,boolean primary,View.OnClickListener listener) {
        Button b=new Button(c); b.setText(label); b.setTextSize(15); b.setAllCaps(false);
        b.setTextColor(primary?BG:INK); b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        pressable(b,primary?MINT:PANEL2,16,primary?0:BORDER);
        b.setPadding(dp(c,16),0,dp(c,16),0); b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setOnClickListener(listener); return b;
    }
    static void selected(Button b,boolean on) {
        Context c=b.getContext(); b.setTextColor(on?BG:MUTED);
        b.setBackground(ripple(c,shape(c,on?MINT:0x00000000,12,0),12));
    }
    static TextView chip(Context c,String label,int color,int fill) {
        TextView v=bold(c,label,11,color); v.setGravity(Gravity.CENTER_VERTICAL); v.setSingleLine(true);
        v.setBackground(shape(c,fill,999,0)); pad(v,10,5,10,5); return v;
    }
    static LinearLayout card(Context c) {
        LinearLayout card=column(c); card.setBackground(shape(c,PANEL,22,BORDER)); pad(card,16,14,16,16); return card;
    }
    static EditText input(Context c,String hint,boolean multiline) {
        EditText e=new EditText(c); e.setTextColor(INK); e.setHintTextColor(FAINT); e.setTextSize(15); e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES:0));
        e.setBackground(shape(c,PANEL,16,BORDER)); pad(e,14,12,14,12); cursor(e); return e;
    }
    static void cursor(TextView field) {
        GradientDrawable caret=new GradientDrawable(); caret.setColor(MINT); caret.setSize(dp(field.getContext(),2),dp(field.getContext(),20));
        field.setTextCursorDrawable(caret); field.setHighlightColor(0x5570edc8);
    }
    static void divider(LinearLayout parent) {
        View v=new View(parent.getContext()); v.setBackground(new ColorDrawable(BORDER)); parent.addView(v,new LinearLayout.LayoutParams(-1,1));
    }
    static LinearLayout column(Context c) { LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context c) { LinearLayout l=new LinearLayout(c); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static LinearLayout bounded(Context c) {
        LinearLayout l=new LinearLayout(c) {
            @Override protected void onMeasure(int width,int height) {
                int limit=dp(c,MAX_CONTENT);
                if(MeasureSpec.getSize(width)>limit) width=MeasureSpec.makeMeasureSpec(limit,MeasureSpec.EXACTLY);
                super.onMeasure(width,height);
            }
        };
        l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    static android.widget.FrameLayout.LayoutParams centred(int height) {
        return new android.widget.FrameLayout.LayoutParams(-1,height,Gravity.CENTER_HORIZONTAL);
    }
    static void gap(LinearLayout parent,int height) { View v=new View(parent.getContext()); parent.addView(v,new LinearLayout.LayoutParams(1,dp(parent.getContext(),height))); }
    static LinearLayout.LayoutParams size(Context c,int width,int height) {
        return new LinearLayout.LayoutParams(width<0?width:dp(c,width),height<0?height:dp(c,height));
    }
    static void pad(View v,int left,int top,int right,int bottom) {
        Context c=v.getContext(); v.setPadding(dp(c,left),dp(c,top),dp(c,right),dp(c,bottom));
    }
    static void reveal(View v) {
        v.animate().cancel(); v.setAlpha(0f); v.setTranslationY(dp(v.getContext(),10));
        v.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    static final class Grid extends ViewGroup {
        private final int cell,spacing,maxColumns;
        Grid(Context c,int minCellDp,int spacingDp,int maxColumns) { super(c); cell=dp(c,minCellDp); spacing=dp(c,spacingDp); this.maxColumns=maxColumns; }
        private int columns(int width) { return Math.max(1,Math.min(maxColumns,(width+spacing)/(cell+spacing))); }
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int width=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight(),cols=columns(width);
            int each=(width-spacing*(cols-1))/cols,height=0,inRow=0,rowHeight=0;
            int exact=MeasureSpec.makeMeasureSpec(each,MeasureSpec.EXACTLY),free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED);
            View[] row=new View[cols];
            for(int i=0;i<=getChildCount();i++) {
                View child=i<getChildCount()?getChildAt(i):null;
                if(child!=null && child.getVisibility()==GONE) continue;
                if(child!=null) { child.measure(exact,free); rowHeight=Math.max(rowHeight,child.getMeasuredHeight()); row[inRow++]=child; }
                if(inRow==cols || (child==null && inRow>0)) {
                    // Tiles in one row share the tallest height so captions never leave ragged edges.
                    for(int n=0;n<inRow;n++) if(row[n].getMeasuredHeight()!=rowHeight) row[n].measure(exact,MeasureSpec.makeMeasureSpec(rowHeight,MeasureSpec.EXACTLY));
                    height+=(height>0?spacing:0)+rowHeight; rowHeight=0; inRow=0;
                }
            }
            setMeasuredDimension(MeasureSpec.getSize(widthSpec),resolveSize(height+getPaddingTop()+getPaddingBottom(),heightSpec));
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
            int width=r-l-getPaddingLeft()-getPaddingRight(),cols=columns(width),each=(width-spacing*(cols-1))/cols;
            int x=0,y=getPaddingTop(),index=0,rowHeight=0;
            for(int i=0;i<getChildCount();i++) {
                View child=getChildAt(i); if(child.getVisibility()==GONE) continue;
                int left=getPaddingLeft()+x*(each+spacing); child.layout(left,y,left+each,y+child.getMeasuredHeight());
                rowHeight=Math.max(rowHeight,child.getMeasuredHeight());
                if(++index%cols==0) { y+=rowHeight+spacing; rowHeight=0; x=0; } else x++;
            }
        }
    }
}
