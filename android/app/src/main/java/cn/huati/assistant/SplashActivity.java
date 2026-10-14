package cn.huati.assistant;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.concurrent.ThreadLocalRandom;

/** Launcher artwork only. Alarm and notification entry points bypass this screen. */
// The user requested a full illustration and tips for three seconds; Android's
// icon-only system splash cannot provide this welcome page. Dismiss it promptly.
@SuppressLint("CustomSplashScreen")
public final class SplashActivity extends Activity {
    private static final long SHOW_MS=3000;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private int scene,tip,seed;
    private long shownMillis,visibleSince;
    private boolean visible,ready,opened;
    private ProgressBar progress;
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(!visible||!ready||isFinishing())return;
        long elapsed=elapsed();progress.setProgress((int)Math.min(SHOW_MS,elapsed));
        if(elapsed>=SHOW_MS)openMain();else ui.postDelayed(this,50);
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=31)getSplashScreen().setOnExitAnimationListener(splash->splash.remove());
        scene=state==null?ThreadLocalRandom.current().nextInt(ArtRepository.wallpaperCount(this)):state.getInt("art_scene");
        String[] tips=getResources().getStringArray(R.array.running_tips);
        tip=state==null?ThreadLocalRandom.current().nextInt(tips.length):Math.floorMod(state.getInt("art_tip"),tips.length);
        seed=state==null?ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE):state.getInt("art_seed");shownMillis=state==null?0:Math.min(SHOW_MS,state.getLong("art_shown_ms"));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(UiKit.CANVAS);
        if(Build.VERSION.SDK_INT>=35)root.setOnApplyWindowInsetsListener((v,insets)->{var bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        FrameLayout art=new FrameLayout(this);root.addView(art,new LinearLayout.LayoutParams(-1,0,1));
        ImageView picture=new ImageView(this);picture.setScaleType(ImageView.ScaleType.FIT_CENTER);picture.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);art.addView(picture,new FrameLayout.LayoutParams(-1,-1));
        art.addView(new SplashTitleView(this),new FrameLayout.LayoutParams(-1,-1));
        LinearLayout footer=new LinearLayout(this);footer.setOrientation(LinearLayout.VERTICAL);footer.setGravity(Gravity.CENTER);footer.setPadding(dp(28),dp(17),dp(28),dp(24));footer.setBackgroundColor(Color.WHITE);root.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        TextView name=UiKit.text(this,getString(R.string.app_name),24,UiKit.INK,true);name.setGravity(Gravity.CENTER);footer.addView(name);
        TextView subtitle=UiKit.text(this,"ECUST · CAMPUS COMPANION",9,UiKit.TEAL,true);subtitle.setGravity(Gravity.CENTER);footer.addView(subtitle);
        TextView hint=UiKit.copy(this,getString(R.string.splash_tip,tips[tip]));hint.setGravity(Gravity.CENTER);footer.addView(hint);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax((int)SHOW_MS);progress.setImportantForAccessibility(ProgressBar.IMPORTANT_FOR_ACCESSIBILITY_NO);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(UiKit.TEAL));LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(dp(72),dp(3));line.topMargin=dp(13);footer.addView(progress,line);
        setContentView(root);
        ArtRepository.show(picture,ArtRepository.wallpaper(this,scene),1536,()->{if(isFinishing()||isDestroyed())return;ready=true;if(visible){visibleSince=SystemClock.uptimeMillis();ui.removeCallbacks(tick);ui.post(tick);}});
    }
    private int dp(float value){return UiKit.dp(this,value);}
    private long elapsed(){return shownMillis+(visible&&ready?Math.max(0,SystemClock.uptimeMillis()-visibleSince):0);}
    @Override protected void onStart(){super.onStart();visible=true;if(ready){visibleSince=SystemClock.uptimeMillis();ui.post(tick);}}
    @Override protected void onStop(){shownMillis=elapsed();visible=false;ui.removeCallbacks(tick);super.onStop();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("art_scene",scene);state.putInt("art_tip",tip);state.putInt("art_seed",seed);state.putLong("art_shown_ms",elapsed());}
    private void openMain(){
        if(opened||isFinishing())return;opened=true;
        startActivity(new Intent(this,MainActivity.class).putExtra("art_seed",seed).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();
    }
    @Override protected void onDestroy(){ui.removeCallbacks(tick);super.onDestroy();}
}
