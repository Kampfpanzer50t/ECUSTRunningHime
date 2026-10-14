package cn.huati.assistant;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared branded dialogs; content scrolls while the actions remain reachable. */
final class CompanionDialog {
    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout root,body,actions;
    private final boolean compact;
    private final int availableWidth,maxHeight;

    CompanionDialog(Activity owner,String title,String subtitle){
        activity=owner;dialog=new Dialog(owner);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int width=owner.getResources().getDisplayMetrics().widthPixels;
        int height=owner.getResources().getDisplayMetrics().heightPixels-UiKit.dp(owner,64);
        if(Build.VERSION.SDK_INT>=30){
            var metrics=owner.getWindowManager().getCurrentWindowMetrics();
            var bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            width=metrics.getBounds().width()-bars.left-bars.right;
            height=metrics.getBounds().height()-bars.top-bars.bottom;
        }
        availableWidth=Math.min(UiKit.dp(owner,420),width-UiKit.dp(owner,32));
        maxHeight=Math.max(UiKit.dp(owner,180),(int)(height*.9f));
        compact=height<UiKit.dp(owner,540)||owner.getResources().getConfiguration().fontScale>1.3f;
        root=new LinearLayout(owner);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiKit.surface(owner,Color.rgb(252,253,255),26));root.setClipToOutline(true);

        LinearLayout hero=new LinearLayout(owner);hero.setOrientation(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);hero.setPadding(dp(20),dp(compact?16:20),dp(20),dp(16));
        GradientDrawable wash=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(226,240,254),Color.rgb(245,249,255)});hero.setBackground(wash);
        root.addView(hero,new LinearLayout.LayoutParams(-1,-2));
        FrameLayout halo=new FrameLayout(owner);halo.setBackground(UiKit.surface(owner,Color.argb(220,255,255,255),22));
        int size=compact?56:68;hero.addView(halo,new LinearLayout.LayoutParams(dp(size),dp(size)));
        ImageView avatar=new ImageView(owner);avatar.setImageResource(R.drawable.brand_head);avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        avatar.setBackground(UiKit.surface(owner,Color.WHITE,16));avatar.setClipToOutline(true);avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        halo.addView(avatar,new FrameLayout.LayoutParams(dp(size-8),dp(size-8),Gravity.CENTER));
        LinearLayout heading=new LinearLayout(owner);heading.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headingLayout=new LinearLayout.LayoutParams(compact?0:-1,-2,compact?1:0);
        if(compact)headingLayout.leftMargin=dp(14);else headingLayout.topMargin=dp(10);hero.addView(heading,headingLayout);
        TextView label=UiKit.text(owner,title,22,UiKit.INK,true);label.setGravity(compact?Gravity.START:Gravity.CENTER);
        if(Build.VERSION.SDK_INT>=28)label.setAccessibilityHeading(true);heading.addView(label);
        TextView caption=UiKit.copy(owner,subtitle);caption.setTextSize(12);caption.setGravity(compact?Gravity.START:Gravity.CENTER);
        caption.setPadding(0,dp(3),0,0);heading.addView(caption);

        ScrollView scroll=new ScrollView(owner);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(true);scroll.setClipToPadding(false);
        body=new LinearLayout(owner);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(2),dp(20),dp(6));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,-2,1));
        actions=new LinearLayout(owner);actions.setOrientation(LinearLayout.VERTICAL);actions.setPadding(dp(20),dp(4),dp(20),dp(16));root.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        dialog.setContentView(root);dialog.setCanceledOnTouchOutside(true);
    }
    private int dp(float value){return UiKit.dp(activity,value);}
    void section(String title,String copy){UiKit.note(activity,body,title,copy);}
    void metrics(String[] values,String[] labels){
        LinearLayout row=new LinearLayout(activity);row.setOrientation(compact?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLayout=new LinearLayout.LayoutParams(-1,-2);rowLayout.topMargin=dp(14);body.addView(row,rowLayout);
        for(int i=0;i<values.length;i++){
            LinearLayout tile=new LinearLayout(activity);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(7),dp(11),dp(7),dp(9));tile.setBackground(UiKit.surface(activity,UiKit.PALE,14));
            LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(compact?-1:0,-2,compact?0:1);
            if(i>0){if(compact)layout.topMargin=dp(6);else layout.leftMargin=dp(7);}row.addView(tile,layout);
            TextView value=UiKit.text(activity,values[i],20,UiKit.INK,true);value.setGravity(Gravity.CENTER);tile.addView(value);
            TextView label=UiKit.text(activity,labels[i],12,UiKit.MUTED,false);label.setGravity(Gravity.CENTER);tile.addView(label);
        }
    }
    void report(String text){
        TextView report=UiKit.copy(activity,text);report.setTextSize(13);report.setTextIsSelectable(true);
        report.setPadding(dp(13),dp(14),dp(13),dp(14));report.setBackground(UiKit.surface(activity,Color.rgb(240,246,253),14));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=dp(14);body.addView(report,layout);
    }
    void show(String primary,Runnable action,String secondary){
        if(activity.isFinishing()||activity.isDestroyed())return;
        Button accept=UiKit.button(activity,primary,true);actions.addView(accept);
        accept.setOnClickListener(v->{accept.setEnabled(false);dialog.dismiss();if(action!=null)action.run();});
        if(secondary!=null){
            Button cancel=UiKit.button(activity,secondary,false);actions.addView(cancel);cancel.setOnClickListener(v->dialog.dismiss());
        }
        dialog.show();Window window=dialog.getWindow();if(window==null)return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes=window.getAttributes();attributes.dimAmount=.32f;window.setAttributes(attributes);
        root.measure(View.MeasureSpec.makeMeasureSpec(availableWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST));
        window.setLayout(availableWidth,Math.min(maxHeight,root.getMeasuredHeight()));window.setGravity(Gravity.CENTER);
    }
}
