package cn.huati.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** A restrained blue wash and two upright title columns over the opening art. */
final class SplashTitleView extends View {
    private final Paint gradient=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint type=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String first,second;

    SplashTitleView(Context context){
        super(context);first=context.getString(R.string.splash_vertical_first);second=context.getString(R.string.splash_vertical_second);
        setContentDescription(first+"，"+second);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        if(android.os.Build.VERSION.SDK_INT>=28)setAccessibilityHeading(true);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        float end=Math.min(w*.56f,UiKit.dp(getContext(),204));
        gradient.setShader(new LinearGradient(0,0,Math.max(1,end),0,
            new int[]{Color.argb(248,221,239,253),Color.argb(222,226,242,253),Color.argb(0,232,245,255)},
            new float[]{0,.44f,1},Shader.TileMode.CLAMP));
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.drawRect(0,0,getWidth(),getHeight(),gradient);
        float safe=Math.min(UiKit.dp(getContext(),24),getHeight()*.1f);
        float step=Math.min(UiKit.dp(getContext(),36),(getHeight()-safe*2)/6f);
        float scaled=getResources().getDisplayMetrics().scaledDensity;
        float primarySize=Math.min(22*scaled,step*.72f),secondarySize=Math.min(18*scaled,step*.65f);
        float left=Math.min(UiKit.dp(getContext(),30),getWidth()*.085f);
        float gap=Math.max(primarySize,secondarySize)+UiKit.dp(getContext(),13);
        float top=getHeight()*.5f-step*2.5f;
        column(canvas,first,left,top,step,primarySize,Color.rgb(44,71,102),true);
        column(canvas,second,left+gap,top,step,secondarySize,Color.rgb(70,95,124),false);
    }
    private void column(Canvas canvas,String value,float x,float top,float step,float size,int colour,boolean strong){
        type.setTypeface(UiKit.font(getContext(),strong));type.setTextSize(size);type.setTextAlign(Paint.Align.CENTER);type.setColor(colour);
        Paint.FontMetrics metrics=type.getFontMetrics();float baseline=-(metrics.ascent+metrics.descent)/2;
        for(int i=0;i<value.length();i++){
            String glyph=value.substring(i,i+1);float y=top+i*step;
            if(glyph.equals("—")){canvas.save();canvas.rotate(90,x,y);canvas.drawText(glyph,x,y+baseline,type);canvas.restore();}
            else canvas.drawText(glyph,x,y+baseline,type);
        }
    }
}
