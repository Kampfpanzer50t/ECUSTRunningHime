package cn.huati.assistant;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Resolution-independent icons replace font-dependent navigation symbols. */
final class UiIcon extends Drawable {
    static final int RUN=0,AUTO=1,RECORD=2,MAP=3,ACCOUNT=4;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final int kind;
    private int colour;
    UiIcon(int kind,int colour){this.kind=kind;this.colour=colour;}
    void colour(int value){colour=value;invalidateSelf();}
    @Override public void draw(Canvas canvas){
        int save=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        paint.setColor(colour);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);path.reset();
        switch(kind){
            case RUN:
                canvas.drawCircle(15,4.4f,1.7f,paint);line(canvas,9,10,13,7.6f);line(canvas,13,7.6f,16,11);line(canvas,16,11,20,11);
                line(canvas,13,7.6f,10.5f,13);line(canvas,10.5f,13,15,16);line(canvas,15,16,14.5f,21);line(canvas,10.5f,13,8,18);line(canvas,8,18,3.5f,18);break;
            case AUTO:
                canvas.drawCircle(12,12,9,paint);line(canvas,12,6.5f,12,12);line(canvas,12,12,16,14.5f);break;
            case RECORD:
                canvas.drawRoundRect(new RectF(5,3,19,21),2,2,paint);line(canvas,9,8,15,8);line(canvas,9,12,15,12);line(canvas,9,16,13,16);break;
            case MAP:
                path.moveTo(3,5);path.lineTo(9,3);path.lineTo(15,5);path.lineTo(21,3);path.lineTo(21,19);path.lineTo(15,21);path.lineTo(9,19);path.lineTo(3,21);path.close();canvas.drawPath(path,paint);line(canvas,9,3,9,19);line(canvas,15,5,15,21);break;
            case ACCOUNT:
                canvas.drawCircle(12,8,3.5f,paint);canvas.drawArc(new RectF(4,14,20,26),180,180,false,paint);break;
            default:break;
        }
        canvas.restoreToCount(save);
    }
    private void line(Canvas canvas,float x1,float y1,float x2,float y2){canvas.drawLine(x1,y1,x2,y2,paint);}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
