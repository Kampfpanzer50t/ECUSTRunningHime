package cn.huati.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.widget.ImageView;

/** Fill the screen, centered horizontally and favoring the upper portrait. */
final class ArtImageView extends ImageView {
    private final Matrix crop=new Matrix();
    ArtImageView(Context context){super(context);setScaleType(ScaleType.MATRIX);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override public void setImageBitmap(Bitmap bitmap){super.setImageBitmap(bitmap);fit();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);fit();}
    private void fit(){
        if(getDrawable()==null||getWidth()==0||getHeight()==0)return;
        float width=getDrawable().getIntrinsicWidth(),height=getDrawable().getIntrinsicHeight();
        if(width<=0||height<=0)return;
        float scale=Math.max(getWidth()/width,getHeight()/height);crop.setScale(scale,scale);
        crop.postTranslate((getWidth()-width*scale)/2,(getHeight()-height*scale)*0.20f);setImageMatrix(crop);
    }
}
