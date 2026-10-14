package cn.huati.assistant;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A readable translucent module with a bounded, decorative companion. */
final class ModuleCard extends LinearLayout {
    private final ImageView companion;
    private int pairIndex;
    private boolean running;

    ModuleCard(Context context){this(context,"",0);}

    ModuleCard(Context context,String title,int pair){
        super(context);setOrientation(VERTICAL);UiKit.cardStyle(context,this);
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams headingLayout=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);
        headingLayout.bottomMargin=UiKit.dp(context,6);addView(heading,headingLayout);
        TextView label=UiKit.text(context,title,18,UiKit.INK,true);
        if(android.os.Build.VERSION.SDK_INT>=28)label.setAccessibilityHeading(true);
        heading.addView(label,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        companion=new ImageView(context);companion.setScaleType(ImageView.ScaleType.FIT_CENTER);
        companion.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Explicit bounds keep the full-resolution artwork out of wrap-content measurement.
        LayoutParams art=new LayoutParams(UiKit.dp(context,62*1.2f),UiKit.dp(context,68*1.2f));
        art.leftMargin=UiKit.dp(context,12);heading.addView(companion,art);
        artwork(pair);
    }

    void artwork(int pair){pairIndex=pair;showCompanion();}
    void running(boolean active){if(running==active)return;running=active;showCompanion();}
    private void showCompanion(){ArtRepository.show(companion,ArtRepository.mascot(getContext(),pairIndex,running),256,null);}
}
