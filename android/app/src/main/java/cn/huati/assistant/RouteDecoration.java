package cn.huati.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Decorative route motif; contains no generated or real running data. */
final class RouteDecoration extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    RouteDecoration(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.save();canvas.scale(getWidth()/160f,getHeight()/140f);
        paint.setStyle(Paint.Style.STROKE);paint.setColor(Color.argb(20,255,255,255));paint.setStrokeWidth(1);
        for(int i=0;i<4;i++)canvas.drawCircle(128,57,29+i*24,paint);
        path.reset();path.moveTo(25,113);path.cubicTo(32,78,64,110,69,83);path.cubicTo(74,50,118,92,118,47);path.cubicTo(120,31,100,33,108,17);
        paint.setColor(Color.rgb(118,204,173));paint.setStrokeWidth(3);paint.setStrokeCap(Paint.Cap.ROUND);canvas.drawPath(path,paint);
        float[][] positions={{25,113},{69,83},{118,47}};
        for(int i=0;i<positions.length;i++){float[] p=positions[i];paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(201,226,215));canvas.drawCircle(p[0],p[1],7,paint);paint.setColor(Color.rgb(9,79,71));paint.setTypeface(UiKit.font(getContext(),true));paint.setTextSize(8);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(Integer.toString(i+1),p[0],p[1]+3,paint);}
        canvas.restore();
    }
}
