package cn.huati.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.InputFilter;
import android.view.View;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LoginActivity extends Activity {
    private static final int TEAL=UiKit.TEAL,INK=UiKit.INK,MUTED=UiKit.MUTED;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private RunEngine engine;
    private EditText phone,secret;
    private TextView secretLabel,message;
    private RadioButton sms,password;
    private Button send,login,back;
    private CheckBox reveal;
    private final ArrayList<ModuleCard> modules=new ArrayList<>();
    private int visualSeed;
    private boolean busy,byCode=true;
    private long cooldown;
    private final Runnable ticker=new Runnable(){@Override public void run(){render();ui.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);engine=RunEngine.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        try{cooldown=engine.smsNextAllowed();}catch(Exception ignored){}
        visualSeed=state==null?java.util.concurrent.ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE):state.getInt("art_seed");
        FrameLayout backdrop=new FrameLayout(this);backdrop.setBackgroundColor(UiKit.CANVAS);
        ArtImageView wallpaper=new ArtImageView(this);backdrop.addView(wallpaper,new FrameLayout.LayoutParams(-1,-1));
        ArtRepository.show(wallpaper,ArtRepository.background(this,visualSeed),1536,null);
        LinearLayout chrome=new LinearLayout(this);chrome.setOrientation(LinearLayout.VERTICAL);backdrop.addView(chrome,new FrameLayout.LayoutParams(-1,-1));
        if(Build.VERSION.SDK_INT>=35)chrome.setOnApplyWindowInsetsListener((v,insets)->{var bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(22),dp(12),dp(22),dp(8));header.setBackgroundColor(Color.argb(220,255,255,255));chrome.addView(header);
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);header.addView(brand);
        ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.brand_head);mark.setScaleType(ImageView.ScaleType.CENTER_CROP);mark.setBackground(UiKit.surface(this,Color.WHITE,12));mark.setClipToOutline(true);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);brand.addView(mark,new LinearLayout.LayoutParams(dp(38),dp(38)));
        TextView brandName=text(getString(R.string.app_name),16,INK,true);brandName.setPadding(dp(10),0,0,0);brand.addView(brandName);
        TextView title=text("登录华理跑步姬",24,INK,true);title.setPadding(0,dp(13),0,dp(4));header.addView(title);header.addView(UiKit.copy(this,"连接你的账号，开始规划校园路线"));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);chrome.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(16),0,dp(16),dp(30));scroll.addView(page);
        LinearLayout card=module(page,"登录与会话");
        RadioGroup modes=new RadioGroup(this);modes.setOrientation(LinearLayout.HORIZONTAL);modes.setPadding(dp(4),dp(4),dp(4),dp(4));modes.setBackground(UiKit.surface(this,UiKit.CANVAS,14));
        sms=mode("短信验证码");modes.addView(sms,new RadioGroup.LayoutParams(0,-2,1));
        password=mode("账号密码");modes.addView(password,new RadioGroup.LayoutParams(0,-2,1));sms.setChecked(true);card.addView(modes);
        TextView phoneLabel=text("小程序绑定的手机号",12,MUTED,true);phoneLabel.setPadding(0,dp(20),0,dp(7));card.addView(phoneLabel);phone=new EditText(this);UiKit.input(this,phone);phone.setInputType(InputType.TYPE_CLASS_PHONE);phone.setHint("请输入手机号");phone.setFilters(new InputFilter[]{new InputFilter.LengthFilter(11)});phone.setSaveEnabled(false);phone.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);card.addView(phone);
        secretLabel=text("短信验证码",13,MUTED,false);card.addView(secretLabel);
        secret=new EditText(this);UiKit.input(this,secret);secret.setSaveEnabled(false);secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);card.addView(secret);
        reveal=new CheckBox(this);reveal.setText("显示密码");reveal.setTextSize(13);reveal.setTextColor(MUTED);reveal.setTypeface(UiKit.font(this,false));reveal.setButtonTintList(ColorStateList.valueOf(TEAL));reveal.setMinHeight(dp(48));reveal.setOnCheckedChangeListener((button,checked)->{if(!byCode){secret.setInputType(InputType.TYPE_CLASS_TEXT|(checked?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:InputType.TYPE_TEXT_VARIATION_PASSWORD));secret.setTypeface(UiKit.font(this,false));secret.setSelection(secret.length());}});card.addView(reveal);
        send=button("获取验证码",false);send.setOnClickListener(v->sendCode());card.addView(send);
        login=button("登录并获取会话",true);login.setOnClickListener(v->login());card.addView(login);
        message=text("",14,MUTED,false);card.addView(message);
        LinearLayout help=module(page,"登录说明");
        UiKit.note(this,help,"登录信息","使用华体账号绑定的手机号。密码和验证码仅用于本次登录，不会保存在 App 中；登录成功后会话保存到本机。");
        UiKit.note(this,help,"已有会话或尚未绑定","未绑定的手机号请先在官方小程序处理。已有会话文件，也可返回“账号”页导入。");
        back=button("返回账号页",false);back.setOnClickListener(v->finish());page.addView(back);
        modes.setOnCheckedChangeListener((group,id)->setMode(id==sms.getId()));
        setContentView(backdrop);setMode(true);
    }
    private ModuleCard module(LinearLayout page,String title){int pair=(int)Math.floorMod((long)visualSeed+modules.size()*7L,ArtRepository.pairCount(this));ModuleCard card=UiKit.module(this,page,title,pair);modules.add(card);return card;}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("art_seed",visualSeed);}
    private int dp(float value){return(int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String content,int size,int colour,boolean bold){return UiKit.text(this,content,size,colour,bold);}
    private Button button(String label,boolean primary){return UiKit.button(this,label,primary);}
    private RadioButton mode(String label){
        RadioButton view=new RadioButton(this);view.setId(View.generateViewId());view.setText(label);view.setTextSize(13);view.setTypeface(UiKit.font(this,true));view.setButtonDrawable((android.graphics.drawable.Drawable)null);view.setGravity(Gravity.CENTER);view.setMinHeight(dp(48));view.setPadding(dp(6),dp(13),dp(6),dp(13));
        StateListDrawable states=new StateListDrawable();GradientDrawable selected=UiKit.surface(this,Color.WHITE,10);selected.setStroke(dp(1),UiKit.LINE);
        states.addState(new int[]{android.R.attr.state_checked},selected);states.addState(new int[]{},UiKit.surface(this,Color.TRANSPARENT,10));view.setBackground(states);
        view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Color.rgb(147,164,157),TEAL,MUTED}));return view;
    }
    private void setMode(boolean code){
        byCode=code;secret.setText("");reveal.setChecked(false);
        secretLabel.setText(code?"短信验证码":"华体账号密码");secret.setHint(code?"输入收到的验证码":"不是微信密码");
        secret.setInputType(code?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setTypeface(UiKit.font(this,false));
        secret.setFilters(new InputFilter[]{new InputFilter.LengthFilter(code?8:256)});
        send.setVisibility(code?View.VISIBLE:View.GONE);reveal.setVisibility(code?View.GONE:View.VISIBLE);render();
    }
    private void render(){
        if(send==null)return;long left=Math.max(0,(cooldown-System.currentTimeMillis()+999)/1000);
        try{boolean running=CompanionState.running(engine.run(),System.currentTimeMillis());for(ModuleCard module:modules)module.running(running);}catch(Exception ignored){}
        send.setText(left>0?getString(R.string.sms_wait,left):"获取验证码");send.setEnabled(!busy&&left==0);
        login.setEnabled(!busy);back.setEnabled(!busy);phone.setEnabled(!busy);secret.setEnabled(!busy);sms.setEnabled(!busy);password.setEnabled(!busy);reveal.setEnabled(!busy);
    }
    private void error(String text){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("登录提示").setMessage(text).setPositiveButton("知道了",null).show();}
    private void sendCode(){
        if(busy)return;final String number;
        try{number=LoginProtocol.phone(phone.getText().toString());}catch(Exception e){error(e.getMessage());return;}
        busy=true;message.setText("正在请求验证码…");render();
        worker.execute(()->{
            String failure=null;
            try{engine.sendLoginCode(number);}catch(Exception e){failure=e.getMessage()==null?"验证码请求未确认，请稍候检查短信；不会自动重发。":e.getMessage();}
            try{cooldown=engine.smsNextAllowed();}catch(Exception ignored){}
            String problem=failure;
            ui.post(()->{busy=false;if(isFinishing()||isDestroyed())return;render();message.setText(problem==null?"服务器已接受请求，请查看手机短信。":"请求未成功，请查看提示；不会自动重发。");if(problem!=null)error(problem);});
        });
    }
    private void login(){
        if(busy)return;final String number,credential=secret.getText().toString();final boolean useCode=byCode;
        try{number=LoginProtocol.phone(phone.getText().toString());if(useCode)LoginProtocol.codeBody(number,credential);else LoginProtocol.passwordBody(number,credential);}
        catch(Exception e){error(e.getMessage());return;}
        busy=true;message.setText("正在登录并验证账号…");render();
        worker.execute(()->{
            String failure=null;try{engine.phoneLogin(number,credential,useCode);}catch(Exception e){failure=e.getMessage()==null?"登录请求未完成，请稍后重新尝试。":e.getMessage();}
            String problem=failure;
            ui.post(()->{busy=false;if(isFinishing()||isDestroyed())return;secret.setText("");render();
                if(problem!=null){message.setText("会话未更新，请检查登录信息。");error(problem);}
                else{Toast.makeText(this,"登录成功，会话已获取并保存",Toast.LENGTH_LONG).show();setResult(RESULT_OK,new Intent());finish();}});
        });
    }
    @Override public void onBackPressed(){if(busy)Toast.makeText(this,"正在处理登录请求，请稍候",Toast.LENGTH_SHORT).show();else super.onBackPressed();}
    @Override protected void onResume(){super.onResume();ui.removeCallbacks(ticker);ui.post(ticker);}
    @Override protected void onPause(){ui.removeCallbacks(ticker);super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacks(ticker);secret.setText("");worker.shutdown();super.onDestroy();}
}
