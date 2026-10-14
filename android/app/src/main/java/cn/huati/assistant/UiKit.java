package cn.huati.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared visual language for the main interface and login form. */
final class UiKit {
    static final int TEAL=Color.rgb(82,103,155),INK=Color.rgb(36,46,69),MUTED=Color.rgb(91,106,128);
    static final int CANVAS=Color.rgb(245,246,251),LINE=Color.rgb(226,230,242),PALE=Color.rgb(234,239,250);
    private static Typeface regular,bold;
    static int dp(Context context,float value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    static synchronized Typeface font(Context context,boolean strong){
        if(regular==null){try{regular=Typeface.createFromAsset(context.getAssets(),"fonts/campus-sans.ttf");}
            catch(Exception ignored){regular=Typeface.create("sans-serif",Typeface.NORMAL);}
            bold=Typeface.create(regular,Typeface.BOLD);}
        return strong?bold:regular;
    }
    static GradientDrawable surface(Context context,int colour,int radius){
        GradientDrawable out=new GradientDrawable();out.setColor(colour);out.setCornerRadius(dp(context,radius));return out;
    }
    static TextView text(Context context,String value,int size,int colour,boolean strong){
        TextView view=new TextView(context);view.setText(value);view.setTextSize(size);view.setTextColor(colour);
        view.setTypeface(font(context,strong));view.setIncludeFontPadding(false);view.setLineSpacing(dp(context,4),1);
        view.setPadding(0,dp(context,5),0,dp(context,7));
        return view;
    }
    static TextView copy(Context context,String value){
        TextView view=text(context,value,13,Color.rgb(76,92,115),false);
        view.setLineSpacing(dp(context,6),1.08f);view.setLetterSpacing(.01f);
        return view;
    }
    static void note(Context context,LinearLayout parent,String title,String body){
        LinearLayout note=new LinearLayout(context);note.setOrientation(LinearLayout.VERTICAL);
        note.setPadding(dp(context,14),dp(context,11),dp(context,14),dp(context,10));
        GradientDrawable shape=surface(context,Color.argb(238,244,248,253),14);
        shape.setStroke(dp(context,1),Color.rgb(222,232,244));note.setBackground(shape);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=dp(context,12);parent.addView(note,layout);
        TextView heading=text(context,title,12,TEAL,true);heading.setPadding(0,0,0,dp(context,6));note.addView(heading);
        note.addView(copy(context,body));
    }
    static void cardStyle(Context context,LinearLayout out){
        out.setPadding(dp(context,18),dp(context,12),dp(context,18),dp(context,20));
        GradientDrawable shape=surface(context,Color.argb(210,255,255,255),22);
        shape.setStroke(dp(context,1),Color.argb(180,255,255,255));
        out.setBackground(shape);out.setElevation(dp(context,1));
    }
    static ModuleCard module(Context context,LinearLayout parent,String title,int pair){
        ModuleCard out=new ModuleCard(context,title,pair);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=dp(context,16);parent.addView(out,layout);return out;
    }
    static Drawable actionBackground(Context context,boolean primary){
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},surface(context,Color.rgb(237,239,246),14));
        GradientDrawable normal=primary?new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{TEAL,Color.rgb(64,80,126)}):surface(context,PALE,14);
        normal.setCornerRadius(dp(context,14));
        states.addState(new int[]{},new RippleDrawable(ColorStateList.valueOf(primary?Color.argb(45,255,255,255):Color.argb(25,82,103,155)),normal,surface(context,Color.WHITE,14)));
        return states;
    }
    static Button button(Context context,String label,boolean primary){
        Button view=new Button(context);view.setText(label);view.setAllCaps(false);view.setTextSize(14);
        view.setTypeface(font(context,true));view.setIncludeFontPadding(false);view.setGravity(Gravity.CENTER);
        view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{Color.rgb(147,157,182),primary?Color.WHITE:TEAL}));
        view.setBackground(actionBackground(context,primary));view.setStateListAnimator(null);
        view.setMinHeight(dp(context,50));view.setMinimumHeight(dp(context,50));view.setPadding(dp(context,12),dp(context,13),dp(context,12),dp(context,13));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=dp(context,10);view.setLayoutParams(layout);return view;
    }
    static void input(Context context,EditText view){
        view.setTextColor(INK);view.setHintTextColor(Color.rgb(143,153,176));view.setTextSize(16);view.setTypeface(font(context,false));view.setIncludeFontPadding(false);
        view.setSingleLine(true);view.setPadding(dp(context,12),dp(context,14),dp(context,12),dp(context,14));
        StateListDrawable states=new StateListDrawable();
        GradientDrawable focus=surface(context,Color.rgb(249,250,255),12);focus.setStroke(dp(context,2),TEAL);
        GradientDrawable normal=surface(context,CANVAS,12);normal.setStroke(dp(context,1),LINE);
        states.addState(new int[]{android.R.attr.state_focused},focus);states.addState(new int[]{},normal);view.setBackground(states);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=dp(context,3);layout.bottomMargin=dp(context,8);view.setMinHeight(dp(context,52));view.setLayoutParams(layout);
    }
}
