package com.mesystem.shell;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class AuthPage extends ScrollView {
    private final ChatEngine chat;
    private final FoxView fox;
    private final TextView account,code,detail,error;
    private final Button primary,browser,copy,continueButton;
    private int styled=-1;
    AuthPage(Context context,ChatEngine engine,Runnable openChat,Runnable back) {
        super(context); chat=engine; setFillViewport(true); setVerticalScrollBarEnabled(false);
        LinearLayout body=Ui.bounded(context); Ui.pad(body,20,6,20,28); addView(body,Ui.centred(-2));
        LinearLayout header=Ui.row(context);
        header.addView(new IconView(context,IconView.BACK,Language.text(R.string.back),v -> back.run()),Ui.size(context,44,44));
        header.addView(Ui.bold(context,Language.text(R.string.account),18,Ui.INK)); body.addView(header);
        fox=new FoxView(context,true); body.addView(fox,Ui.size(context,-1,170)); Ui.gap(body,6);
        TextView title=Ui.bold(context,"ChatGPT",27,Ui.INK); title.setLineSpacing(Ui.dp(context,2),1); body.addView(title); Ui.gap(body,12);
        LinearLayout status=Ui.card(context);
        account=Ui.bold(context,"",16,Ui.INK); status.addView(account); Ui.gap(status,10);
        code=Ui.bold(context,"",32,Ui.MINT); code.setTypeface(Typeface.MONOSPACE); code.setGravity(Gravity.CENTER); code.setLetterSpacing(.12f);
        code.setTextIsSelectable(true); Ui.pad(code,8,20,8,20); code.setBackground(Ui.shape(context,Ui.BG,16,Ui.MINT_SOFT));
        LinearLayout.LayoutParams cp=Ui.size(context,-1,-2); cp.bottomMargin=Ui.dp(context,12); status.addView(code,cp);
        detail=Ui.caption(context,""); detail.setTextSize(13); status.addView(detail);
        body.addView(status); Ui.gap(body,16);
        continueButton=Ui.button(context,Language.text(R.string.open_chat_2),true,v -> openChat.run());
        body.addView(continueButton,Ui.size(context,-1,52)); Ui.gap(body,10);
        primary=Ui.button(context,Language.text(R.string.get_sign_in_code),true,v -> {
            if(chat.authenticated) new AlertDialog.Builder(context).setTitle(Language.text(R.string.sign_out_of_chatgpt))
                .setMessage(Language.text(R.string.chats_stay_on_this_phone)).setNegativeButton(Language.text(R.string.cancel),null)
                .setPositiveButton(Language.text(R.string.sign_out),(d,w) -> chat.logout()).show();
            else if(chat.loggingIn) chat.cancel(); else chat.login();
        }); body.addView(primary,Ui.size(context,-1,52)); Ui.gap(body,10);
        browser=Ui.button(context,Language.text(R.string.open_openai),true,v -> {
            if(!chat.loginCode.isEmpty()) {
                context.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(Language.text(R.string.chatgpt_sign_in_code),chat.loginCode));
                context.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(OpenAiClient.VERIFY)));
            }
        }); body.addView(browser,Ui.size(context,-1,52)); Ui.gap(body,10);
        copy=Ui.button(context,Language.text(R.string.copy_code),false,v -> context.getSystemService(ClipboardManager.class)
            .setPrimaryClip(ClipData.newPlainText(Language.text(R.string.chatgpt_sign_in_code),chat.loginCode)));
        body.addView(copy,Ui.size(context,-1,48)); Ui.gap(body,14);
        error=Ui.text(context,"",13,Ui.DANGER); error.setTextIsSelectable(true); error.setBackground(Ui.shape(context,Ui.DANGER_SOFT,14,0));
        Ui.pad(error,14,12,14,12); body.addView(error,Ui.size(context,-1,-2)); Ui.gap(body,18);
        refresh();
    }
    void refresh() {
        boolean hasCode=!chat.loginCode.isEmpty();
        fox.mood(chat.authenticated?FoxView.IDLE:chat.loggingIn?FoxView.THINKING:FoxView.SLEEPING);
        account.setText(chat.authenticated?chat.authLabel:chat.loggingIn?Language.text(R.string.waiting_for_sign_in):Language.text(R.string.account_not_connected));
        code.setVisibility(hasCode?VISIBLE:GONE); code.setText(chat.loginCode);
        detail.setText(chat.authenticated?Language.text(R.string.connected):hasCode?
            Language.text(R.string.enter_the_code_on_openai_then_return_here):
            chat.loggingIn?Language.text(R.string.requesting_sign_in_code):"");
        primary.setText(chat.authenticated?Language.text(R.string.sign_out):chat.loggingIn?Language.text(R.string.cancel_sign_in):Language.text(R.string.get_sign_in_code));
        int look=chat.authenticated?1:0;
        if(look!=styled) { styled=look; primary.setTextColor(look==1?Ui.DANGER:Ui.BG); Ui.pressable(primary,look==1?Ui.PANEL2:Ui.MINT,16,look==1?Ui.BORDER:0); }
        browser.setVisibility(hasCode?VISIBLE:GONE); copy.setVisibility(hasCode?VISIBLE:GONE);
        continueButton.setVisibility(chat.authenticated?VISIBLE:GONE);
        error.setText(chat.error); error.setVisibility(chat.error.isEmpty()?GONE:VISIBLE);
    }
}
