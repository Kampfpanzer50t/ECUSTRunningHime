package cn.huati.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.json.JSONArray;

public final class MainActivity extends Activity {
    private static final int TEAL=UiKit.TEAL,INK=UiKit.INK,MUTED=UiKit.MUTED;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<Button> buttons=new ArrayList<>();
    private RunEngine engine;
    private TextView account,status,points,summary,notice,dailyStatus,dailyTimeTitle;
    private Switch dailySwitch,roadSwitch,autoRoadSwitch,mapPointsSwitch;
    private final ScrollView[] pages=new ScrollView[5];
    private final TextView[] navigation=new TextView[5];
    private final LinearLayout[] navigationTiles=new LinearLayout[5];
    private final UiIcon[] navigationIcons=new UiIcon[5];
    private final ArrayList<ModuleCard> modules=new ArrayList<>();
    private ArtImageView wallpaper;
    private int visualSeed;
    private TextView pageTitle,pageSubtitle,liveDistance,liveClock,liveBadge,routeInfo,recordRouteInfo;
    private RoutePreviewView routeMap,recordMap,campusMap;
    private TextView mapCatalogStatus,mapSelectedPoint,mapPointList;
    private boolean bindingMap,pointListExpanded;
    private ProgressBar liveProgress;
    private int selectedPage;
    private boolean bindingRoad;
    private EditText kilometres,minutes,seconds,milliseconds,steps,latitude,longitude,dailyHour,dailyMinute;
    private Button getPoints,start,finish,pause,resume,discard,acceptInvalid,export,alarmPermission,dailyLocationPermission;
    private boolean busy;
    private boolean bindingDaily;
    private volatile boolean foreground;
    private long dailyFailureTarget;
    private String dailyFailure="";
    private String locationLoaded="",dailyTimeLoaded="";
    private JSONArray recordGeometry;
    private String recordGeometryId="";
    private String dailyLaunch="";
    private LocationListener locationListener;
    private final Runnable refreshUi=new Runnable(){@Override public void run(){if(!foreground)return;render();checkDaily();ui.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);engine=RunEngine.get(this);
        visualSeed=saved==null?getIntent().getIntExtra("art_seed",java.util.concurrent.ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE)):saved.getInt("art_seed");
        FrameLayout backdrop=new FrameLayout(this);backdrop.setBackgroundColor(UiKit.CANVAS);
        wallpaper=new ArtImageView(this);backdrop.addView(wallpaper,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        backdrop.addView(root,new FrameLayout.LayoutParams(-1,-1));
        if(Build.VERSION.SDK_INT>=35)root.setOnApplyWindowInsetsListener((v,insets)->{var bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(22),dp(12),dp(22),dp(8));header.setBackgroundColor(Color.argb(220,255,255,255));root.addView(header);
        LinearLayout brand=row(header);brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.brand_head);mark.setScaleType(ImageView.ScaleType.CENTER_CROP);mark.setBackground(background(Color.WHITE,12));mark.setClipToOutline(true);mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);brand.addView(mark,new LinearLayout.LayoutParams(dp(38),dp(38)));
        LinearLayout brandText=new LinearLayout(this);brandText.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams brandTextParams=new LinearLayout.LayoutParams(0,-2,1);brandTextParams.leftMargin=dp(10);brand.addView(brandText,brandTextParams);
        brandText.addView(text(getString(R.string.app_name),16,INK,true));TextView brandCaption=text("CAMPUS COMPANION",9,MUTED,false);brandCaption.setPadding(0,0,0,0);brandText.addView(brandCaption);
        TextView campusChip=text("奉贤校区",11,TEAL,true);campusChip.setPadding(dp(12),dp(7),dp(12),dp(7));campusChip.setBackground(background(UiKit.PALE,14));brand.addView(campusChip);
        pageTitle=text("",24,INK,true);pageTitle.setPadding(0,dp(13),0,dp(4));header.addView(pageTitle);pageSubtitle=UiKit.copy(this,"");header.addView(pageSubtitle);
        FrameLayout content=new FrameLayout(this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout[] sections=new LinearLayout[5];
        for(int i=0;i<pages.length;i++){
            ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            LinearLayout section=new LinearLayout(this);section.setOrientation(LinearLayout.VERTICAL);section.setPadding(dp(16),0,dp(16),dp(24));
            scroll.addView(section);pages[i]=scroll;sections[i]=section;content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        }
        LinearLayout manual=sections[0],automatic=sections[1],records=sections[2],mapPage=sections[3],accountPage=sections[4];
        LinearLayout auth=module(accountPage,"登录与会话");
        account=text("尚未导入会话",14,MUTED,false);auth.addView(account);
        auth.addView(button("手机登录并获取会话",v->startActivityForResult(new Intent(this,LoginActivity.class),13),true));
        auth.addView(button("选择会话文件",v->{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.setType("*/*");pick.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,10);},false));
        export=button("导出会话文件到手机",v->exportSession(),false);auth.addView(export);
        UiKit.note(this,auth,"会话保存在本机","使用绑定手机号和短信验证码，或华体账号密码登录。需要会话文件时，可导出 JSON 保存到手机。");
        auth.addView(button("清除本机会话",v->new AlertDialog.Builder(this).setTitle("清除本机会话")
            .setMessage("这会清除手机里的登录信息和已完成记录缓存。服务器记录不受影响。")
            .setNegativeButton("保留",null).setPositiveButton("清除",(d,w)->work(()->engine.clearConfig(),"本机会话已清除",null)).show(),false));

        LinearLayout intro=module(manual,"模拟目标");
        LinearLayout heroMetrics=row(intro);heroMetrics.setPadding(0,dp(3),0,dp(6));heroMetric(heroMetrics,"2.2 km","默认目标");heroMetric(heroMetrics,"15 min","默认时长");heroMetric(heroMetrics,"3 个点","必经点位");
        intro.addView(button("查看当前记录",v->selectPage(2),false));
        LinearLayout setup=module(manual,"01  起点与考核点");
        LinearLayout coords=row(setup);latitude=field(coords,"起点纬度","",true);longitude=field(coords,"起点经度","",true);
        details(setup,"起点使用说明","可使用导入文件中的起点，或读取手机当前位置。位置仅用于请求本次点位。自动计划会自行准备起点，无需在这里填写。");
        setup.addView(button("读取手机当前位置",v->requestLocation(),false));
        getPoints=button("获取随机三点",v->{double lat=coordinate(latitude),lng=coordinate(longitude);work(()->engine.loadPoints(lat,lng),"本次随机三点已刷新",()->syncOriginFields());},false);setup.addView(getPoints);
        points=text("尚未获取本次点位",13,MUTED,false);points.setPadding(dp(13),dp(12),dp(13),dp(12));points.setBackground(background(UiKit.CANVAS,12));LinearLayout.LayoutParams pointsLayout=new LinearLayout.LayoutParams(-1,-2);pointsLayout.topMargin=dp(12);setup.addView(points,pointsLayout);

        LinearLayout parameters=module(manual,"02  模拟参数");
        LinearLayout values=row(parameters);kilometres=field(values,"公里","2.20",true);steps=field(values,"步数","2600",false);
        LinearLayout durationFields=row(parameters);minutes=field(durationFields,"分钟","15",false);seconds=field(durationFields,"秒 · 0–59","0",false);milliseconds=field(durationFields,"毫秒 · 0–999","0",false);
        roadSwitch=new Switch(this);styleSwitch(roadSwitch,"校园道路路线");parameters.addView(roadSwitch);
        details(parameters,"路线与里程规则","开启后沿校园道路经过三点。手动模拟允许目标里程 ±0.5 公里，实际至少 2.1 公里；每日自动只要求经过三点且至少 2.1 公里。两种运行共用此开关，修改不影响当前记录。");
        parameters.addView(button("预览本次路线",v->{try{
            int metres=(int)Math.round(Double.parseDouble(kilometres.getText().toString())*1000);long duration=manualDuration();
            double originLat=coordinate(latitude),originLng=coordinate(longitude);
            work(()->{if(engine.preview()==null)engine.loadPoints(originLat,originLng);engine.previewRouteMillis(metres,duration);},"路线预览已生成，不会创建服务器记录",()->syncOriginFields());
        }catch(Exception e){error("请先获取三点并填写有效参数。");}},false));
        routeMap=new RoutePreviewView(this);parameters.addView(routeMap,new LinearLayout.LayoutParams(-1,dp(250)));
        routeInfo=text("路线预览将在此显示",13,MUTED,false);parameters.addView(routeInfo);
        start=button("开始模拟记录",v->confirmStart(),true);parameters.addView(start);
        LinearLayout daily=module(automatic,"每日计划");
        dailyTimeTitle=text("",15,TEAL,true);daily.addView(dailyTimeTitle);
        LinearLayout selectedTime=row(daily);
        dailyHour=field(selectedTime,"启动小时","06",false);
        dailyMinute=field(selectedTime,"启动分钟","15",false);
        daily.addView(button("保存自动启动时间",v->saveDailyTime(),false));
        daily.addView(button("用时钟选择启动时间",v->new android.app.TimePickerDialog(this,(picker,hour,minute)->{dailyHour.setText(String.format(Locale.CHINA,"%02d",hour));dailyMinute.setText(String.format(Locale.CHINA,"%02d",minute));},DailySchedule.hour(readDailySettings()),DailySchedule.minute(readDailySettings()),true).show(),false));
        UiKit.note(this,daily,"启动时间","北京时间 06:00–21:00。选择时间后，点击“保存自动启动时间”生效。");
        dailySwitch=new Switch(this);styleSwitch(dailySwitch,"开启每日自动模拟");daily.addView(dailySwitch);
        autoRoadSwitch=new Switch(this);styleSwitch(autoRoadSwitch,"校园道路路线");daily.addView(autoRoadSwitch);
        UiKit.note(this,daily,"本次自动计划","随机 12–16 分钟，精确到毫秒；路线至少 2.1 公里，计时结束直接提交。");
        dailyStatus=text("尚未开启",14,MUTED,false);daily.addView(dailyStatus);
        dailyStatus.setPadding(dp(13),dp(13),dp(13),dp(13));dailyStatus.setBackground(background(UiKit.CANVAS,14));
        details(daily,"自动计划运行规则","每天提前 2 分钟读取位置，并刷新校园跑、重新获取当天随机三点。服务北京时间 06:00 开放；准备时间早于 06:00 时先保存起点，开放后再刷新三点。联网失败每 30 秒重试，关闭开关可取消。定位失败或校外时使用校内随机模拟起点。\n\n每次自动开始时，随机选择大于 12 分钟、小于 16 分钟的运行时长，精确到毫秒。本次时长固定保存，暂停、继续、重开不会重新选择；计满后直接提交。启动时间仍按保存的计划，手动模拟仍使用填写的时长。\n\n今天已运行后，可改成今天尚未到的新时间再次全自动运行。每个日期和启动时间只执行一次；新任务开始前需没有未完成记录。自动路线至少 2.1 公里，不限制与 2.2 公里目标的误差，无效成绩也会提交。");
        LinearLayout permissions=module(automatic,"锁屏与后台");
        dailyLocationPermission=button("允许锁屏自动定位",v->requestDailyLocationPermission(),false);permissions.addView(dailyLocationPermission);
        alarmPermission=button("允许锁屏定时",v->requestAlarmPermission(),false);permissions.addView(alarmPermission);
        permissions.addView(button("电池与后台运行",v->openBatterySettings(),false));
        permissions.addView(button("通知与启动管理",v->openAppSettings(),false));
        UiKit.note(this,permissions,"先完成系统权限","允许精确定时、通知、后台联网，以及位置权限中的“始终允许”和“精确位置”。关闭省电限制，再按下方品牌说明设置。");
        details(permissions,"荣耀 / 华为 · 启动与电池","① 在系统设置中搜索“应用启动管理”或“启动管理”，找到华理跑步姬，关闭自动管理。\n\n② 改为手动管理，开启“允许自启动”“允许关联启动”“允许后台活动”。\n\n③ 在电池或应用耗电管理中，将本应用设为“不受限制”或“不优化”；允许移动数据、WLAN 和后台联网。\n\n④ 在最近任务列表锁定本应用，避免被一键清理。菜单名称可能因 MagicOS / EMUI 版本不同而变化。");
        details(permissions,"vivo · 自启动与后台耗电","① 在设置中搜索“自启动”，或进入 i 管家 → 应用管理 → 权限管理，允许华理跑步姬自启动；若有“关联启动”，也一并允许。\n\n② 在设置 → 电池 → 后台耗电管理中找到本应用，选择“允许后台高耗电”或“不限制后台运行”。\n\n③ 在应用联网管理中允许移动数据、WLAN 和后台联网；允许通知、精确定时与后台位置。\n\n④ 在最近任务中锁定本应用，避免清理。不同 OriginOS 版本的入口名称可能不同，可在设置中搜索上述关键词。");
        details(permissions,"OPPO · 自启动与耗电管理","① 在设置中搜索“自启动管理”，允许华理跑步姬自启动；若有“关联启动”，也一并允许。\n\n② 进入应用信息 → 耗电管理或电池使用情况，开启“允许后台活动”“允许自启动”；若提供电池优化选项，选择“不优化”或“不限制”。\n\n③ 允许本应用移动数据、WLAN 和后台联网；允许通知、精确定时与后台位置。\n\n④ 在最近任务中锁定本应用，避免清理。不同 ColorOS 版本的入口可能在设置 → 应用 → 应用管理，或手机管家中。");
        UiKit.note(this,permissions,"保持任务可运行","保持手机开机、定位与网络可用。强行停止 App 或关机时无法执行。关闭每日开关可取消后续开始；当前记录请到“记录”页处理。上述步骤适用于可安装本安卓 App 的系统，具体菜单以手机为准。");
        LinearLayout diagnostics=module(accountPage,"工具与排查");
        diagnostics.addView(button("一分钟锁屏自检",v->scheduleProbe(),false));
        diagnostics.addView(button("后台排查报告",v->showDiagnostics(),false));
        UiKit.note(this,diagnostics,"仅检查运行环境","锁屏自检不创建跑步记录。排查报告不包含登录会话、账号或位置坐标。");
        LinearLayout about=module(accountPage,getString(R.string.app_name)+"  "+BuildConfig.VERSION_NAME);
        about.addView(UiKit.copy(this,"个人辅助工具 · 非官方客户端\n模拟数据不代表真实运动，成绩以服务器判定为准。\n地图 © OpenStreetMap contributors，ODbL 1.0。\n字体 Campus Sans（基于 Noto Sans SC），OFL 1.1。"));
        about.addView(button("校园地图数据来源",v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright"))),false));
        dailySwitch.setOnCheckedChangeListener((button,enabled)->changeDaily(enabled));
        roadSwitch.setOnCheckedChangeListener((v,enabled)->changeRoadMode(enabled));autoRoadSwitch.setOnCheckedChangeListener((v,enabled)->changeRoadMode(enabled));
        LinearLayout live=module(records,"当前记录");
        liveBadge=text("等待开始",13,TEAL,true);live.addView(liveBadge);
        LinearLayout metrics=row(live);LinearLayout distanceBox=metric(metrics,"模拟里程 / km"),clockBox=metric(metrics,"剩余时间");
        liveDistance=text("0.00",36,INK,true);distanceBox.addView(liveDistance);liveClock=text("--:--.---",26,INK,true);clockBox.addView(liveClock);
        liveProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);liveProgress.setMax(1000);
        liveProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(TEAL));live.addView(liveProgress,new LinearLayout.LayoutParams(-1,dp(8)));
        recordMap=new RoutePreviewView(this);LinearLayout.LayoutParams mapParams=new LinearLayout.LayoutParams(-1,dp(260));mapParams.topMargin=dp(16);live.addView(recordMap,mapParams);
        recordRouteInfo=text("",13,MUTED,false);live.addView(recordRouteInfo);
        status=text("尚未开始",14,MUTED,false);live.addView(status);
        notice=text("",14,Color.rgb(167,90,20),false);live.addView(notice);
        pause=button("暂停本次模拟",v->work(()->engine.pauseSimulation(),"模拟已暂停，倒计时和进度已停止",()->{stopTimer();showPausedChoice();}),false);live.addView(pause);
        resume=button("继续本次模拟",v->continueSimulation(),true);live.addView(resume);
        discard=button("舍弃本次记录",v->confirmDiscard(),false);live.addView(discard);
        details(live,"暂停与舍弃说明","暂停时间不计入模拟进度；服务器仍按实际开始至结束的时间判定成绩。舍弃会停止本地任务，已创建的服务器记录保留。");
        finish=button("立即提交当前记录",v->work(()->engine.finish(),"本次记录已处理，请查看服务器结果",null),true);live.addView(finish);
        acceptInvalid=button("提交旧版未结束的无效记录",v->work(()->engine.finish(),"无效记录已提交",null),false);live.addView(acceptInvalid);
        LinearLayout results=module(records,"服务器成绩");summary=text("登录后可查询累计成绩和最近记录。",14,MUTED,false);results.addView(summary);
        results.addView(button("查询服务器记录状态",v->work(()->engine.refresh(),"服务器状态已更新",null),false));
        LinearLayout mapCard=module(mapPage,"奉贤校园全图");
        mapCard.addView(UiKit.copy(this,"双指缩放 · 双击放大 · 放大后拖动"));
        mapPointsSwitch=new Switch(this);styleSwitch(mapPointsSwitch,"显示已收集的跑步点位");mapCard.addView(mapPointsSwitch);
        campusMap=new RoutePreviewView(this);mapCard.addView(campusMap,new LinearLayout.LayoutParams(-1,dp(420)));
        LinearLayout mapControls=row(mapCard);mapControl(mapControls,"−",()->campusMap.zoomBy(0.7f));mapControl(mapControls,"校园全图",()->campusMap.resetViewport());mapControl(mapControls,"＋",()->campusMap.zoomBy(1.4f));
        mapSelectedPoint=text("点击地图上的圆点查看名称。关闭目录开关时，只显示本次三点。",13,TEAL,false);mapCard.addView(mapSelectedPoint);
        campusMap.onPointSelected(value->mapSelectedPoint.setText(value));
        LinearLayout catalog=module(mapPage,"跑步点位目录");
        mapCatalogStatus=text("",13,MUTED,false);catalog.addView(mapCatalogStatus);
        catalog.addView(button("展开 / 收起点位名单",v->{pointListExpanded=!pointListExpanded;render();},false));
        mapPointList=text("",13,INK,false);catalog.addView(mapPointList);
        catalog.addView(button("导入校园点位表",v->{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.setType("*/*");pick.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,20);},false));
        details(catalog,"点位来源说明","当前接口只返回随机三点，无法据此确认全校点位总数。成功获取的点位会自动加入目录；导入完整点位表后可显示表内全部点位。导入不更换当前考核三点。绿色圆点为本次三点，深色圆点为点位目录。");
        catalog.addView(button("点位表格式说明",v->new AlertDialog.Builder(this).setTitle("校园点位表 JSON")
            .setMessage("campus：华东理工大学奉贤校区\ncoordinate_system：GCJ02 或 WGS84\nsource：点位表来源\ncomplete：完整目录填 true，部分目录填 false\npoints：点位数组，每项含 point_name、lat、lng，可含 id。\n\n只接受本校区点位。完整性由导入文件声明，App 无法向服务器验证总数。")
            .setPositiveButton("知道了",null).show(),false));
        mapPointsSwitch.setOnCheckedChangeListener((v,enabled)->{if(!bindingMap)work(()->engine.setMapPointsVisible(enabled),enabled?"已显示点位目录":"已隐藏点位目录，仅显示本次三点",null);});
        LinearLayout nav=new LinearLayout(this);nav.setPadding(dp(12),dp(8),dp(12),dp(8));nav.setBackgroundColor(Color.argb(235,255,255,255));nav.setElevation(dp(3));root.addView(nav);
        String[] labels={"模拟","自动","记录","地图","账号"};
        for(int i=0;i<pages.length;i++){
            final int index=i;LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.CENTER);tile.setPadding(dp(4),0,dp(4),dp(6));tile.setMinimumHeight(dp(82));tile.setFocusable(true);
            tile.setContentDescription(new String[]{"模拟跑步","每日自动运行","记录与服务器成绩","校园地图与跑步点位","账号与工具"}[i]);tile.setOnClickListener(v->selectPage(index));
            FrameLayout iconSlot=new FrameLayout(this);iconSlot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tile.addView(iconSlot,new LinearLayout.LayoutParams(-1,dp(56)));
            ImageView iconView=new ImageView(this);iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);iconView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            float iconSize=i==0?52:i==4?48*0.8f:28;
            if(i==0||i==4)iconView.setImageResource(i==0?R.drawable.nav_running:R.drawable.nav_account);
            else{UiIcon icon=new UiIcon(i,MUTED);icon.setBounds(0,0,dp(28),dp(28));navigationIcons[i]=icon;iconView.setImageDrawable(icon);}
            iconSlot.addView(iconView,new FrameLayout.LayoutParams(dp(iconSize),dp(iconSize),Gravity.CENTER));
            TextView label=text(labels[i],12,MUTED,true);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(3),0,dp(3));label.setLineSpacing(0,1);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tile.addView(label,new LinearLayout.LayoutParams(-1,-2));
            nav.addView(tile,new LinearLayout.LayoutParams(0,-2,1));navigation[i]=label;navigationTiles[i]=tile;
        }
        setContentView(backdrop);selectPage(saved==null?initialPage():saved.getInt("selected_page",0));render();
    }
    private int initialPage(){try{return getIntent().getIntExtra("selected_page",RunEngine.unresolved(engine.run())?2:0);}catch(Exception ignored){return 0;}}
    private void styleSwitch(Switch input,String label){input.setText(label);input.setTextSize(14);input.setTextColor(INK);input.setTypeface(UiKit.font(this,true));input.setPadding(0,dp(14),0,dp(14));input.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{TEAL,Color.WHITE}));input.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Color.rgb(160,177,218),Color.rgb(204,211,228)}));}
    private LinearLayout metric(LinearLayout row,String title){LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);row.addView(group,new LinearLayout.LayoutParams(0,-2,1));group.addView(text(title,12,MUTED,false));return group;}
    private void selectPage(int index){
        View focused=getCurrentFocus();if(focused!=null){focused.clearFocus();getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(),0);}
        selectedPage=Math.max(0,Math.min(pages.length-1,index));String[] titles={"模拟跑步","每日自动","跑步记录","校园地图","账号与工具"};
        String[] subtitles={"先预览，再开始你的模拟记录","设定时间 · 提前准备 · 自动提交","当前进度与服务器判定","查看校园全图与跑步点位","登录会话、锁屏自检与后台排查"};
        pageTitle.setText(titles[selectedPage]);pageSubtitle.setText(subtitles[selectedPage]);
        showWallpaper();
        for(int i=0;i<pages.length;i++){
            boolean selected=i==selectedPage;pages[i].setVisibility(selected?View.VISIBLE:View.GONE);navigation[i].setTextColor(selected?TEAL:MUTED);
            if(navigationIcons[i]!=null)navigationIcons[i].colour(selected?TEAL:MUTED);
            navigationTiles[i].setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(25,82,103,155)),background(selected?UiKit.PALE:Color.TRANSPARENT,18),background(Color.WHITE,18)));
            navigationTiles[i].setSelected(selected);
        }
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("selected_page",selectedPage);state.putInt("art_seed",visualSeed);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.hasExtra("art_seed")){visualSeed=intent.getIntExtra("art_seed",visualSeed);for(int i=0;i<modules.size();i++)modules.get(i).artwork(pairForModule(i));showWallpaper();}if(intent.hasExtra("selected_page"))selectPage(intent.getIntExtra("selected_page",2));render();}
    private void changeRoadMode(boolean enabled){if(bindingRoad)return;work(()->engine.setRoadMode(enabled),enabled?"校园道路路线已开启，应用于后续手动和自动记录":"已切回基础模拟路线",null);}
    private int dp(float value){return(int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String content,int size,int colour,boolean bold){return UiKit.text(this,content,size,colour,bold);}
    private GradientDrawable background(int colour,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(colour);shape.setCornerRadius(dp(radius));return shape;}
    private ModuleCard module(LinearLayout page,String title){ModuleCard card=UiKit.module(this,page,title,pairForModule(modules.size()));modules.add(card);return card;}
    private int pairForModule(int index){return (int)Math.floorMod((long)visualSeed+index*7L,ArtRepository.pairCount(this));}
    private void showWallpaper(){int scene=(int)Math.floorMod((long)visualSeed+selectedPage,ArtRepository.backgroundCount(this));ArtRepository.show(wallpaper,ArtRepository.background(this,scene),1536,null);}
    private LinearLayout row(LinearLayout parent){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;}
    private EditText field(LinearLayout row,String label,String value,boolean decimal){LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(0,dp(7),dp(7),dp(7));row.addView(group,lp);group.addView(text(label,11,MUTED,true));EditText input=new EditText(this);UiKit.input(this,input);input.setText(value);input.setTextSize(decimal?14:20);input.setSelectAllOnFocus(true);input.setContentDescription(label);input.setInputType(InputType.TYPE_CLASS_NUMBER|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED:0));group.addView(input);return input;}
    private Button button(String label,View.OnClickListener click,boolean primary){Button b=UiKit.button(this,label,primary);b.setOnClickListener(click);buttons.add(b);return b;}
    private void heroMetric(LinearLayout row,String value,String label){LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);row.addView(group,new LinearLayout.LayoutParams(0,-2,1));group.addView(text(value,18,INK,true));group.addView(text(label,12,MUTED,false));}
    private void details(LinearLayout parent,String title,String copy){
        LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);group.setPadding(dp(13),dp(4),dp(13),dp(4));
        GradientDrawable shape=background(Color.argb(230,244,248,253),14);shape.setStroke(dp(1),Color.rgb(222,232,244));group.setBackground(shape);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=dp(12);parent.addView(group,layout);
        TextView toggle=text(getString(R.string.detail_toggle,title,"＋"),13,TEAL,true),body=UiKit.copy(this,copy);body.setVisibility(View.GONE);
        toggle.setPadding(0,dp(10),0,dp(10));toggle.setMinHeight(dp(48));toggle.setFocusable(true);toggle.setContentDescription(title+"，已收起");
        toggle.setOnClickListener(v->{boolean expanded=body.getVisibility()!=View.VISIBLE;body.setVisibility(expanded?View.VISIBLE:View.GONE);toggle.setText(getString(R.string.detail_toggle,title,expanded?"−":"＋"));toggle.setContentDescription(title+(expanded?"，已展开":"，已收起"));});group.addView(toggle);group.addView(body);
    }
    private void mapControl(LinearLayout row,String label,Runnable click){Button control=button(label,v->click.run(),false);control.setContentDescription(label.equals("−")?"缩小地图":label.equals("＋")?"放大地图":"回到校园全图");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,label.equals("校园全图")?2:1);lp.setMargins(dp(3),dp(10),dp(3),0);row.addView(control,lp);}
    private JSONObject readDailySettings(){try{return engine.dailySettings();}catch(Exception ignored){return null;}}
    private void error(String message){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("处理提示").setMessage(message).setPositiveButton("知道了",null).show();}
    private interface Operation{void run()throws Exception;}
    private void work(Operation operation,String success,Runnable after){
        if(busy)return;busy=true;render();
        worker.execute(()->{
            String failure=null;try{operation.run();}catch(Exception e){failure=e.getMessage()==null?"请求未完成，请查询服务器记录状态":e.getMessage();}
            String problem=failure;ui.post(()->{busy=false;if(isFinishing()||isDestroyed())return;render();if(problem!=null)error(problem);else{Toast.makeText(this,success,Toast.LENGTH_SHORT).show();if(after!=null)after.run();}});
        });
    }
    private void confirmStart(){
        try{
            double km=Double.parseDouble(kilometres.getText().toString());long time=manualDuration();int count=Integer.parseInt(steps.getText().toString());
            if(!Double.isFinite(km)||km<2||km>30||count<=0||count>100000)throw new IllegalArgumentException();
            int metres=(int)Math.round(km*1000);
            double originLat=coordinate(latitude),originLng=coordinate(longitude);
            CompanionDialog confirmation=new CompanionDialog(this,"创建模拟记录","确认本次参数，准备开始计时");
            confirmation.metrics(new String[]{String.format(Locale.CHINA,"%.2f",metres/1000.0),DailyDuration.label(time),String.valueOf(count)},new String[]{"目标 / 公里","设置时长","模拟步数"});
            if(engine.roadMode()){
                String route=String.format(Locale.CHINA,"沿校园道路经过三点，实际里程在 %.2f–%.2f 公里之间，提交以实际路线为准。",RoadRoute.minimum(metres)/1000.0,RoadRoute.maximum(metres)/1000.0);
                JSONObject preview=engine.routePreview();if(preview!=null&&preview.optInt("requested_distance")==metres)route+=String.format(Locale.CHINA,"\n\n已预览路线：%.2f 公里。",preview.getInt("distance")/1000.0);
                confirmation.section("路线与里程",route);
            }
            confirmation.section("结束后的处理","计时结束后立即校验并提交；服务器判定无效时，也会提交并结束本条记录。");
            confirmation.show("创建并等待",()->{
                    if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);
                    work(()->{if(engine.preview()==null)engine.loadPoints(originLat,originLng);engine.createMillis(metres,time,count);},"已创建记录，开始计时",()->{syncOriginFields();startTimer();selectPage(2);});
                },"返回修改");
        }catch(Exception e){error("请检查参数：里程 2–30 公里、时长 1–120 分钟，步数为正数。");}
    }
    private void stopTimer(){stopService(new Intent(this,RunService.class));getSystemService(android.app.NotificationManager.class).cancel(1);}
    private void continueSimulation(){work(()->engine.resumeSimulation(),"已从暂停前的进度继续模拟",this::startTimer);}
    private void showPausedChoice(){
        if(isFinishing()||isDestroyed())return;
        new AlertDialog.Builder(this).setTitle("本次模拟已暂停")
            .setMessage("倒计时和模拟进度已停住。你可以继续本次模拟，也可以舍弃；稍后决定时会保持暂停。")
            .setPositiveButton("继续模拟",(d,w)->continueSimulation())
            .setNegativeButton("舍弃本次",(d,w)->confirmDiscard())
            .setNeutralButton("稍后决定",null).show();
    }
    private void confirmDiscard(){
        try{
            JSONObject current=engine.run();if(!RunClock.canDiscard(current)){error("本次记录正在处理或已经完成，不能舍弃。请查询服务器状态。");return;}
            long expected=current.optLong("record_id",0);String extra=current.optString("status").endsWith("unknown")?"此前操作的服务器结果尚不确定，请在官方小程序核实。":"已创建的服务器占位记录会保留，可以查询其状态。";
            new AlertDialog.Builder(this).setTitle("舍弃本次记录？")
                .setMessage("舍弃后不能继续本次模拟，App 会停止计时并取消后续提交。"+extra+"\n\n如要开始新一次模拟，需要重新获取随机三点。")
                .setNegativeButton("保留本次",null).setPositiveButton("确认舍弃",(d,w)->work(()->engine.discardSimulation(expected),"本次模拟已舍弃，后续提交已取消",this::stopTimer)).show();
        }catch(Exception e){error(e.getMessage());}
    }
    private void startTimer(){try{startForegroundService(new Intent(this,RunService.class));}
        catch(Exception e){work(()->engine.pauseSimulation(),"后台计时未启动，模拟已暂停",null);error("系统未允许后台计时。本次模拟已暂停，请重新打开 App 后选择继续或舍弃。");}}
    private void saveDailyTime(){
        if(busy)return;
        try{
            int hour=Integer.parseInt(dailyHour.getText().toString().trim());
            int minute=Integer.parseInt(dailyMinute.getText().toString().trim());
            DailySchedule.validateTime(hour,minute);
            work(()->engine.setDailyTime(hour,minute),"自动启动时间已保存，请查看下一次定时",()->{
                dailyHour.clearFocus();dailyMinute.clearFocus();
                dailyLaunch="";dailyFailureTarget=0;dailyFailure="";render();checkDaily();
            });
        }catch(NumberFormatException e){error("请填写整数小时和分钟，例如 07 和 30；可选时间为北京时间 06:00–21:00。");}
        catch(Exception e){error(e.getMessage());}
    }
    private void changeDaily(boolean enabled){
        if(bindingDaily)return;
        if(busy){render();return;}
        try{
            if(enabled){
                if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);
            }
            if(!enabled&&!RunEngine.unresolved(engine.run()))stopService(new Intent(this,RunService.class));
            work(()->engine.setDailyEnabled(enabled,0,0),enabled?"每日自动运行已开启，将提前 2 分钟准备位置和三点":"每日自动运行已关闭",()->{
                dailyLaunch="";
                dailyFailureTarget=0;dailyFailure="";render();if(enabled&&!RunAlarms.permitted(this))requestAlarmPermission();
            });
        }catch(Exception e){render();error("请先登录并验证账号，再开启每日自动运行。");}
    }
    private void requestDailyLocationPermission(){
        if(!PhoneLocation.precise(this)){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},15);return;
        }
        if(!PhoneLocation.background(this)){
            new AlertDialog.Builder(this).setTitle("允许锁屏自动读取当前位置")
                .setMessage("华理跑步姬在每天设定时间前 2 分钟读取一次当前位置，用于请求本次随机三点。请在位置权限中选择“始终允许”，并开启“使用精确位置”。定位失败或位置在校外时，会改用随机的校内模拟起点。\n\n只在自动准备阶段读取位置，准备完成后关闭定位；不持续记录真实运动轨迹。")
                .setNegativeButton("稍后设置",null).setPositiveButton("打开权限设置",(d,w)->{
                    if(Build.VERSION.SDK_INT==29)requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},16);
                    else openAppSettings();
                }).show();return;
        }
        Toast.makeText(this,"锁屏自动定位权限已允许，请保持手机定位开启",Toast.LENGTH_LONG).show();
    }
    private void requestAlarmPermission(){
        if(RunAlarms.permitted(this)){Toast.makeText(this,"系统已允许锁屏定时",Toast.LENGTH_SHORT).show();return;}
        if(Build.VERSION.SDK_INT>=31){
            try{startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}
            catch(Exception e){openAppSettings();}
        }
    }
    private void openAppSettings(){
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
        catch(Exception e){error("请在手机设置中找到华理跑步姬，允许定时、通知和后台运行。");}
    }
    private void openBatterySettings(){
        new AlertDialog.Builder(this).setTitle("允许锁屏后台运行")
            .setMessage("请在系统电池设置中找到华理跑步姬，将电池策略设为“不受限制”或“不优化”，并允许后台联网。\n\n荣耀手机：在设置中搜索“应用启动管理”，找到华理跑步姬，关闭自动管理，改为手动管理，并打开“允许自启动”“允许关联启动”“允许后台活动”。闹钟权限和电池优化设置不能替代这些开关。\n\n建议在最近任务列表锁定华理跑步姬，避免一键清理。")
            .setNegativeButton("稍后设置",null).setPositiveButton("打开电池设置",(d,w)->{
                try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
                catch(Exception e){openAppSettings();}
            }).show();
    }
    private void scheduleProbe(){
        if(busy)return;
        try{
            if(RunEngine.unresolved(engine.run())){error("请先处理当前记录，锁屏自检需要在没有未完成任务时进行。");return;}
            JSONObject preparation=engine.dailySettings();long target=preparation==null?0:preparation.optLong("prep_target_ms");
            if(DailyPreparation.ready(preparation,System.currentTimeMillis(),target)||DailyPreparation.waiting(preparation,System.currentTimeMillis(),target)){
                error("每日自动任务正在准备或等待开始，请完成后再进行锁屏自检。");return;
            }
            if(!RunAlarms.permitted(this)){requestAlarmPermission();return;}
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);
            long when=RunAlarms.scheduleProbe(this);
            CompanionDialog probe=new CompanionDialog(this,"已设置锁屏自检","现在锁屏，稍后回来查看结果");
            probe.section("自检时间",RunMath.date(when)+"\n到此时间后再等约一分钟，打开 App 查看“后台排查报告”。");
            probe.section("检查范围","仅验证系统唤醒和到华体服务器的 HTTPS 连通性，不携带账号会话，不创建或提交跑步记录。");
            probe.section("等待期间","系统省电可能延迟自检。请保持联网，避免短时间重复测试。");
            probe.show("知道了",null,null);
        }catch(Exception error){error("自检未设置，请检查定时权限。");}
    }
    private void showDiagnostics(){
        final String[] report={""};
        work(()->report[0]=RunDiagnostics.report(this,engine),"后台排查报告已读取",()->{
            CompanionDialog diagnostics=new CompanionDialog(this,"后台排查报告","检查权限、系统唤醒与联网情况");
            diagnostics.section("报告内容","报告不包含登录会话、账号或位置坐标。可长按选择文字，也可复制完整报告。");
            diagnostics.report(report[0]);
            diagnostics.show("复制报告",()->{
                    getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("华理跑步姬后台排查报告",report[0]));
                    Toast.makeText(this,"报告已复制，不含登录会话或坐标",Toast.LENGTH_LONG).show();
                },"关闭");
        });
    }
    private void checkDaily(){
        if(!foreground||busy)return;
        long now=System.currentTimeMillis(),target=0;
        try{
            JSONObject settings=engine.dailySettings();target=DailyPreparation.target(settings,now);
            if(target==dailyFailureTarget)return;
            String action=DailyPreparation.due(settings,now,target)?RunAlarms.PREPARE:
                DailySchedule.due(settings,now)?RunAlarms.DAILY:"";
            if(action.isEmpty())return;
            String launch=action+target;if(launch.equals(dailyLaunch))return;
            startForegroundService(new Intent(this,RunService.class).setAction(action)
                .putExtra(RunAlarms.TARGET_AT,target).putExtra(RunAlarms.SCHEDULED_AT,target));dailyLaunch=launch;
        }catch(Exception error){RunDiagnostics.error(this,"service_launch_failed",error);dailyFailureTarget=target;dailyFailure="本次自动任务未能启动，请检查后台排查报告";}
    }
    private void renderDaily(JSONObject settings,JSONObject config,JSONObject current)throws Exception{
        boolean enabled=settings!=null&&settings.optBoolean("enabled",false);
        bindingDaily=true;dailySwitch.setChecked(enabled);bindingDaily=false;
        dailySwitch.setEnabled(!busy&&(config!=null||enabled));
        String selectedTime=DailySchedule.timeLabel(settings);
        // Refresh saved values only when they change, so the one-second UI timer preserves drafts.
        if(!dailyTimeLoaded.equals(selectedTime)){
            dailyTimeLoaded=selectedTime;
            dailyHour.setText(String.format(Locale.CHINA,"%02d",DailySchedule.hour(settings)));
            dailyMinute.setText(String.format(Locale.CHINA,"%02d",DailySchedule.minute(settings)));
        }
        dailyHour.setEnabled(!busy);dailyMinute.setEnabled(!busy);
        dailyTimeTitle.setText(getString(R.string.daily_time_title,selectedTime));
        String message=enabled?"已开启 · 每天北京时间 "+selectedTime+" 自动开始":"尚未开启 · 已保存启动时间 "+selectedTime;
        if(enabled)message+="\n提前 2 分钟读取位置、刷新和获取三点；早于 06:00 时先等待服务开放";
        boolean locationAllowed=PhoneLocation.precise(this)&&PhoneLocation.background(this);
        dailyLocationPermission.setText(locationAllowed?"锁屏自动定位权限已允许":"允许锁屏自动定位");
        if(enabled&&!locationAllowed)message+="\n未允许锁屏精确定位，将使用随机校内模拟起点";
        if(settings!=null&&!settings.optString("prep_message").isEmpty())message+="\n\n准备状态："+settings.optString("prep_message");
        if(settings!=null&&!settings.optString("origin_source").isEmpty())message+="\n起点来源："+settings.optString("origin_source");
        if(settings!=null&&!settings.optString("origin_name").isEmpty())message+=" · "+settings.optString("origin_name");
        boolean permitted=RunAlarms.permitted(this);alarmPermission.setText(permitted?"锁屏定时权限已允许":"允许锁屏定时");
        if(enabled){
            if(!permitted)message+="\n\n锁屏定时尚未允许，当前仅首页前台触发";
            else if(!settings.optString("alarm_error").isEmpty())message+="\n\n"+settings.optString("alarm_error");
            else if(settings.optLong("next_alarm_ms",0)>0)message+="\n\n锁屏定时已设置 · 下一次 "+RunMath.date(settings.getLong("next_alarm_ms"))+"（北京时间）";
            else message+="\n\n正在恢复系统定时，请稍候";
            if(settings.optLong("next_prep_ms")>0)message+="\n提前准备时间："+RunMath.date(settings.getLong("next_prep_ms"));
            if(!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()))
                message+="\n电池优化仍开启，锁屏联网可能延迟；建议允许后台运行";
            long now=System.currentTimeMillis(),target=DailyPreparation.target(settings,now);String today=DailySchedule.day(now);
            boolean preparing=DailyPreparation.waiting(settings,now,target)||DailyPreparation.ready(settings,now,target);
            if(!preparing&&settings.optLong("last_missed_target_ms")==target&&!DailySchedule.reserved(settings,target))message+="\n\n本次定时时间已过，但没有自动开始记录。可以另设后续启动时间，或进行锁屏自检。";
            else if(!preparing&&!DailySchedule.reserved(settings,target)&&java.time.Instant.ofEpochMilli(now).atZone(RunMath.ZONE).toLocalTime().isAfter(java.time.LocalTime.of(DailySchedule.hour(settings),DailySchedule.minute(settings)).plusMinutes(DailySchedule.ALARM_GRACE_MS/60000)))
                message+="\n\n今天的这个启动时间尚无尝试记录；可另设后续时间。";
        }
        if(settings!=null&&!settings.optString("last_day").isEmpty()){
            String latest=settings.optString("last_message");
            if(current!=null&&settings.optLong("last_record_id",0)>0&&settings.optLong("last_record_id")==current.optLong("record_id")){
                switch(current.optString("status")){
                    case"paused":latest="自动记录已暂停，可继续或舍弃";break;
                    case"discarded":latest="自动记录已舍弃；可另设后续启动时间";break;
                    case"check_failed":latest="自动记录校验未通过，请查看当前记录原因";break;
                    case"finished":latest=current.optBoolean("accepted_invalid")?"自动记录已提交，服务器判定无效":"自动记录已提交，请查看服务器判定";break;
                    case"create_unknown":case"finish_unknown":latest="自动记录结果待核实，请查询服务器";break;
                    default:break;
                }
            }
            message+="\n\n最近计划 "+(settings.optLong("last_target_ms")>0?RunMath.date(settings.getLong("last_target_ms")):settings.getString("last_day"))+"："+latest;
            if(settings.optLong("last_duration_ms")>0)message+="\n本次随机时长："+DailyDuration.label(settings.getLong("last_duration_ms"));
            if(DailySchedule.day(System.currentTimeMillis()).equals(settings.optString("attempt_day"))&&settings.optJSONArray("attempted_times")!=null)
                message+="\n今天已尝试的启动时间："+settings.getJSONArray("attempted_times").join("、").replace("\"","");
        }
        if(!dailyFailure.isEmpty())message+="\n"+dailyFailure;
        dailyStatus.setText(message);
    }
    private void renderRoutes(JSONObject preview,JSONObject current)throws Exception{
        boolean road=engine.roadMode();bindingRoad=true;roadSwitch.setChecked(road);autoRoadSwitch.setChecked(road);bindingRoad=false;
        roadSwitch.setEnabled(!busy);autoRoadSwitch.setEnabled(!busy);
        JSONObject plan=engine.routePreview();
        if(plan==null||preview==null||!plan.optString("point_snapshot").equals(preview.toString())){
            routeMap.show(null,null,0);routeInfo.setText("先获取本次三点，再点击预览。预览不会创建服务器记录。");
        }else{
            routeMap.show(plan.optBoolean("road_mode")?plan.optJSONArray("route_samples"):plan.optJSONArray("preview_path"),plan.optJSONArray("points"),0);
            String info=String.format(Locale.CHINA,"%s · 预计 %.2f 公里\n目标 %.2f 公里 · 偏差 %+.2f 公里",plan.optBoolean("road_mode")?"校园道路路线":"基础模拟路线",plan.getInt("distance")/1000.0,plan.getInt("requested_distance")/1000.0,(plan.getInt("distance")-plan.getInt("requested_distance"))/1000.0);
            try{int target=(int)Math.round(Double.parseDouble(kilometres.getText().toString())*1000);if(target!=plan.getInt("requested_distance"))info+="\n目标已修改，开始时将按新目标重新规划。";}catch(Exception ignored){}
            routeInfo.setText(info);
        }
        if(current==null||!current.has("start_ms")){
            recordGeometryId="";recordGeometry=null;recordMap.show(null,null,0);recordRouteInfo.setText("暂无当前路线");
            liveDistance.setText(R.string.zero_distance);liveClock.setText("--:--");liveBadge.setText("等待开始");liveProgress.setProgress(0);return;
        }
        String phase=current.optString("status"),id=current.optLong("record_id")+":"+current.getLong("start_ms");
        if(!id.equals(recordGeometryId)){
            recordGeometry=current.optBoolean("road_mode")?current.getJSONArray("route_samples"):
                RunMath.path(current.getJSONArray("points"),current.getDouble("lat"),current.getDouble("lng"),0,current.getInt("distance"),current.getInt("duration"));
            recordGeometryId=id;
        }
        long now=System.currentTimeMillis();double progress=Math.max(0,Math.min(1,RunClock.movingMillis(current,now)/(double)RunClock.durationMillis(current)));
        if(phase.equals("finished"))progress=1;
        long remaining=RunClock.remainingMillis(current,now);recordMap.show(recordGeometry,current.optJSONArray("points"),(float)progress);
        liveDistance.setText(String.format(Locale.CHINA,"%.2f",current.getInt("distance")*progress/1000.0));
        liveClock.setText(String.format(Locale.CHINA,"%02d:%02d.%03d",remaining/60000,remaining/1000%60,remaining%1000));liveProgress.setProgress((int)(progress*1000));
        String badge;int colour=TEAL;
        switch(phase){
            case"created":badge="模拟进行中";break;case"paused":badge="已暂停";colour=Color.rgb(167,90,20);break;
            case"finished":badge=current.optBoolean("accepted_invalid")?"已提交 · 无效":"已提交";if(current.optBoolean("accepted_invalid"))colour=Color.rgb(167,90,20);break;
            case"discarded":badge="已舍弃";colour=MUTED;break;
            case"checking":case"finish_pending":case"record_updated":badge="正在校验与提交";break;
            case"create_pending":badge="正在创建";break;
            case"create_unknown":case"finish_unknown":badge="结果待核实";colour=Color.rgb(167,90,20);break;
            default:badge="等待处理";colour=MUTED;
        }
        liveBadge.setText(badge);liveBadge.setTextColor(colour);
        int actual=current.getInt("distance"),requested=current.optInt("requested_distance",actual);
        String details=String.format(Locale.CHINA,"%s · 总里程 %.2f 公里\n目标 %.2f 公里 · 偏差 %+.2f 公里",current.optBoolean("road_mode")?"校园道路模拟":"基础模拟路线",actual/1000.0,requested/1000.0,(actual-requested)/1000.0);
        if(current.has("scheduled_day"))details+="\n自动路线：经过三点且至少 2.1 公里，不限制里程误差。";
        if(current.has("duration_ms")){
            details+="\n本次模拟时长："+DailyDuration.label(RunClock.durationMillis(current));
            if(phase.equals("created"))details+="\n预计开始提交："+RunMath.preciseDate(RunClock.finishAt(current,now));
        }
        if(!current.optString("preflight_warning").isEmpty())details+="\n"+current.getString("preflight_warning");
        if(current.optBoolean("accepted_invalid"))details+="\n服务器判定无效："+current.optString("invalid_reason","详见服务器成绩");
        recordRouteInfo.setText(details);
    }
    private void render(){
        if(account==null)return;
        try{
            JSONObject c=engine.config(),r=engine.run(),p=engine.preview(),out=engine.results();
            renderCompanions(r);
            for(Button b:buttons)b.setEnabled(!busy);export.setEnabled(!busy&&c!=null);account.setText(c==null?"尚未登录，可在手机上获取会话":"已获取会话 · 账号尾号 "+tail(c.optString("student_id")));
            if(c!=null){JSONObject loc=c.optJSONObject("location");String imported=c.optString("student_id")+(loc==null?"":loc.toString());
                if(!locationLoaded.equals(imported)){locationLoaded=imported;if(loc!=null){latitude.setText(loc.optString("lat"));longitude.setText(loc.optString("lng"));}}}
            renderDaily(engine.dailySettings(),c,r);
            renderRoutes(p,r);
            renderCampusMap(p,r);
            JSONArray targets=r!=null&&RunEngine.unresolved(r)?r.optJSONArray("points"):p==null?null:p.optJSONArray("points");
            if(targets==null)points.setText("尚未获取本次点位");else{StringBuilder names=new StringBuilder();for(int i=0;i<targets.length();i++)names.append(i==0?"":"\n").append(i+1).append("  ").append(targets.getJSONObject(i).optString("point_name","考核点"));points.setText(names);}
            String phase=r==null?"none":r.optString("status");boolean pending=RunEngine.unresolved(r),created=phase.equals("created"),paused=phase.equals("paused"),invalid=phase.equals("check_failed");
            getPoints.setEnabled(!busy&&c!=null&&!pending);start.setEnabled(!busy&&c!=null&&!pending);
            pause.setVisibility(created?View.VISIBLE:View.GONE);
            resume.setVisibility(paused?View.VISIBLE:View.GONE);
            discard.setVisibility(RunClock.canDiscard(r)?View.VISIBLE:View.GONE);
            long now=System.currentTimeMillis(),left=(created||paused)?RunClock.remainingMillis(r,now):0;
            finish.setVisibility(created&&left==0?View.VISIBLE:View.GONE);acceptInvalid.setVisibility(invalid?View.VISIBLE:View.GONE);
            notice.setText(r==null?"":r.optString("error"));notice.setVisibility(notice.getText().length()>0?View.VISIBLE:View.GONE);
            String prefix=r==null?"":String.format(Locale.CHINA,"模拟 %.2f 公里 · %d 步\n",r.optInt("distance")/1000.0,r.optInt("steps"));
            if(r!=null&&r.has("start_ms"))prefix+="开始时间 "+RunMath.date(r.getLong("start_ms"))+"\n来源："+(r.has("scheduled_day")?"每日自动":"手动开始")+"\n";
            if(created||paused){double fraction=Math.min(1,RunClock.movingMillis(r,now)/(double)RunClock.durationMillis(r));prefix+=String.format(Locale.CHINA,"当前进度 %.2f 公里 · %d 步\n",r.getInt("distance")*fraction/1000.0,(int)(r.getInt("steps")*fraction));}
            String label;
            switch(phase){case"created":label="模拟中 · 剩余 "+DailyDuration.label(left);break;
                case"paused":label="模拟已暂停 · 剩余 "+DailyDuration.label(left)+"\n请选择继续或舍弃";break;
                case"discarded":label="本次模拟已舍弃，不会继续提交；重新获取三点后可开始新一次模拟";break;
                case"check_failed":label="旧版校验未通过，可提交并结束记录";break;case"finished":label=r.optBoolean("accepted_invalid")?"无效记录已提交，轨迹上传完成":"提交与轨迹上传已完成，请查看下方服务器结果";break;
                case"checking":label="正在进行服务器校验，此时无法暂停或舍弃";break;
                case"create_pending":label="正在创建记录";break;case"finish_pending":case"record_updated":label="正在提交，请稍候";break;
                case"create_unknown":case"finish_unknown":label="结果待核实，请查询服务器，并在小程序检查；不会重复操作";break;default:label="尚未开始";}
            status.setText(getString(R.string.progress_status,busy?"正在处理…\n":"",prefix,label));
            if(out!=null){JSONObject stats=out.getJSONObject("statistics"),detail=out.optJSONObject("record");
                String result=String.format(Locale.CHINA,"累计有效  %.2f 公里\n累计无效  %.2f 公里\n累计次数  %d 次",stats.optDouble("valid_grades"),stats.optDouble("invalid_grades"),stats.optInt("run_num"));
                if(detail!=null){boolean finalised=detail.optInt("running_time")>1;String verdict=finalised?(detail.optInt("status")==1?"有效":"无效"):"尚未结束";
                    result+=String.format(Locale.CHINA,"\n\n查询记录：%.2f 公里 · %s\n%s",detail.optDouble("mileage")/1000.0,verdict,detail.optString("start_time"));
                    if(!detail.optString("warning").isEmpty())result+="\n原因："+detail.optString("warning");}
                summary.setText(getString(R.string.results_with_time,result,RunMath.date(out.getLong("checked_at"))));
            }else summary.setText("导入会话后可查询累计成绩和最近记录。");
        }catch(Exception e){status.setText("本地配置无法读取，请重新导入会话。");}
    }
    private String tail(String id){return id.length()>4?id.substring(id.length()-4):id;}
    private void renderCompanions(JSONObject current){
        boolean running=CompanionState.running(current,System.currentTimeMillis());
        for(ModuleCard module:modules)module.running(running);
    }
    private void renderCampusMap(JSONObject preview,JSONObject current)throws Exception{
        JSONObject catalog=engine.campusPointCatalog();boolean show=engine.mapPointsVisible(),complete=catalog.optBoolean("complete");
        bindingMap=true;mapPointsSwitch.setChecked(show);bindingMap=false;mapPointsSwitch.setEnabled(!busy);
        mapPointsSwitch.setText(complete?"显示全部跑步点位（导入表）":"显示已收集的跑步点位");
        JSONObject preparation=engine.dailySettings();long now=System.currentTimeMillis(),target=DailyPreparation.target(preparation,now);
        JSONObject selected=RunEngine.unresolved(current)?current:DailyPreparation.ready(preparation,now,target)?preparation.optJSONObject("prepared_preview"):preview;
        JSONArray active=selected==null?null:selected.optJSONArray("points"),directory=catalog.getJSONArray("points");
        campusMap.showCampus(show?directory:null,active);
        String message=(complete?"导入表声明的全部点位":"已收集点位，尚无法确认是否齐全")+" · "+directory.length()+" 个\n来源："+catalog.optString("source");
        if(complete)message+="\n完整性由导入文件声明，服务器未提供总数验证。";
        if(catalog.optBoolean("completeness_changed"))message+="\n服务器返回了表外点位，目录完整性需重新核实。";
        if(catalog.optLong("updated_at")>0)message+="\n目录更新："+RunMath.date(catalog.getLong("updated_at"));
        if(selected!=null&&selected.optLong("captured_at")>0)message+="\n本次三点获取："+RunMath.date(selected.getLong("captured_at"));
        mapCatalogStatus.setText(message);mapPointList.setVisibility(pointListExpanded?View.VISIBLE:View.GONE);
        if(pointListExpanded){StringBuilder names=new StringBuilder();for(int i=0;i<directory.length();i++)names.append(i==0?"":"\n").append(i+1).append("  ").append(directory.getJSONObject(i).getString("point_name"));mapPointList.setText(names);}
    }
    @Override protected void onResume(){
        super.onResume();foreground=true;ui.removeCallbacks(refreshUi);ui.post(refreshUi);
        try{
            if(RunRecovery.shouldRestore(engine.run(),System.currentTimeMillis())){
                // Starting from the visible Activity preserves Android's user-interaction exemption.
                startForegroundService(new Intent(this,RunService.class));RunDiagnostics.event(this,"timer_recovered");
            }else{
                JSONObject settings=engine.dailySettings();long target=settings==null?0:settings.optLong("prep_target_ms");
                if(DailyPreparation.ready(settings,System.currentTimeMillis(),target)||DailyPreparation.waiting(settings,System.currentTimeMillis(),target))
                    startForegroundService(new Intent(this,RunService.class).setAction(RunAlarms.PREPARE).putExtra(RunAlarms.TARGET_AT,target));
            }
        }catch(Exception error){RunDiagnostics.error(this,"service_launch_failed",error);}
        worker.execute(()->{
            try{engine.syncDailyAlarm();RunAlarms.syncFinish(this,engine.run(),System.currentTimeMillis());}
            catch(Exception e){ui.post(()->{if(!isFinishing()&&!isDestroyed()){dailyFailure="系统定时恢复失败，请检查权限和本地会话";render();}});}
        });
    }
    @Override protected void onPause(){foreground=false;ui.removeCallbacks(refreshUi);super.onPause();}
    @Override protected void onDestroy(){foreground=false;ui.removeCallbacks(refreshUi);worker.shutdown();removeLocationListener();super.onDestroy();}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==13&&result==RESULT_OK){render();work(()->engine.refresh(),"会话已就绪，服务器状态已更新",null);return;}
        if(request==14&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            var destination=data.getData();work(()->{
                String text=engine.exportSession();
                try(var output=getContentResolver().openOutputStream(destination,"wt")){
                    if(output==null)throw new IllegalStateException("无法保存文件，请重新选择保存位置");
                    output.write(text.getBytes(StandardCharsets.UTF_8));
                }
            },"huati-session.json 已保存到你选择的位置",null);return;
        }
        if(request==20&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            var uri=data.getData();work(()->{
                try(InputStream input=getContentResolver().openInputStream(uri);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    if(input==null)throw new IllegalArgumentException("无法打开点位表");byte[] buffer=new byte[4096];int n;
                    while((n=input.read(buffer))!=-1){if(bytes.size()+n>256*1024)throw new IllegalArgumentException("点位表超过 256 KB");bytes.write(buffer,0,n);}
                    String text=bytes.toString("UTF-8");if(text.startsWith("\uFEFF"))text=text.substring(1);engine.importCampusPoints(text);engine.setMapPointsVisible(true);
                }
            },"校园点位表已导入并显示，完整性请查看目录状态",()->selectPage(3));return;
        }
        if(request!=10||result!=RESULT_OK||data==null||data.getData()==null)return;
        var uri=data.getData();work(()->{
            try(InputStream input=getContentResolver().openInputStream(uri);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                if(input==null)throw new IllegalArgumentException("无法打开会话文件");byte[] chunk=new byte[4096];int n;
                while((n=input.read(chunk))!=-1){if(bytes.size()+n>65536)throw new IllegalArgumentException("文件过大，请选择会话 JSON 文件");bytes.write(chunk,0,n);}
                String text=bytes.toString("UTF-8");if(text.startsWith("\uFEFF"))text=text.substring(1);engine.importConfig(text);engine.refresh();
            }
        },"会话已导入并验证",null);
    }
    private void exportSession(){
        new AlertDialog.Builder(this).setTitle("保存会话文件")
            .setMessage("导出的 JSON 文件包含你的登录会话。请保存到自己的手机，不要分享给其他人。")
            .setNegativeButton("取消",null).setPositiveButton("选择保存位置",(d,w)->{
                Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.setType("application/json");save.addCategory(Intent.CATEGORY_OPENABLE);save.putExtra(Intent.EXTRA_TITLE,"huati-session.json");startActivityForResult(save,14);
            }).show();
    }
    private void requestLocation(){
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){fallbackLocation("未允许定位");return;}
        locate();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==11){if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)locate();else fallbackLocation("未允许定位");}
        if(request==15&&PhoneLocation.precise(this))requestDailyLocationPermission();
        if(request==15||request==16)render();
    }
    private void locate(){
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){fallbackLocation("未允许定位");return;}
        try{
            LocationManager manager=getSystemService(LocationManager.class);removeLocationListener();
            String provider=manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)?LocationManager.NETWORK_PROVIDER:LocationManager.GPS_PROVIDER;
            if(!manager.isProviderEnabled(provider)){fallbackLocation("定位开关关闭");return;}
            Location last=manager.getLastKnownLocation(provider);
            if(last!=null&&validLocation(last)){setLocation(last);return;}
            Toast.makeText(this,"正在读取手机位置…",Toast.LENGTH_SHORT).show();
            locationListener=new LocationListener(){
                @Override public void onLocationChanged(Location location){setLocation(location);removeLocationListener();}
                @Override public void onStatusChanged(String provider,int status,Bundle extras){}
                @Override public void onProviderEnabled(String provider){}
                @Override public void onProviderDisabled(String provider){removeLocationListener();fallbackLocation("定位服务关闭");}
            };
            manager.requestSingleUpdate(provider,locationListener,Looper.getMainLooper());
            ui.postDelayed(()->{if(locationListener!=null){removeLocationListener();fallbackLocation("定位超时");}},30000);
        }catch(SecurityException e){fallbackLocation("未允许定位");}
        catch(Exception e){fallbackLocation("定位失败");}
    }
    private long manualDuration(){return ManualDuration.parse(minutes.getText().toString(),seconds.getText().toString(),milliseconds.getText().toString());}
    private double coordinate(EditText field){try{return Double.parseDouble(field.getText().toString());}catch(Exception ignored){return Double.NaN;}}
    private void syncOriginFields(){try{JSONObject p=engine.preview();if(p!=null){latitude.setText(String.format(Locale.US,"%.8f",p.getDouble("lat")));longitude.setText(String.format(Locale.US,"%.8f",p.getDouble("lng")));}}catch(Exception ignored){}}
    private boolean validLocation(Location fix){return fix!=null&&fix.hasAccuracy()&&FreshLocation.acceptable(fix.getLatitude(),fix.getLongitude(),fix.getAccuracy(),fix.getElapsedRealtimeNanos(),android.os.SystemClock.elapsedRealtimeNanos());}
    private void fallbackLocation(String reason){try{JSONObject p=CampusArea.randomOrigin(new java.security.SecureRandom(),reason);latitude.setText(String.format(Locale.US,"%.8f",p.getDouble("lat")));longitude.setText(String.format(Locale.US,"%.8f",p.getDouble("lng")));Toast.makeText(this,p.getString("source"),Toast.LENGTH_LONG).show();}catch(Exception error){error("未能准备校内起点，请重试");}}
    private void setLocation(Location location){
        if(!validLocation(location)){fallbackLocation("位置过期或精度不足");return;}
        try{JSONObject p=CampusArea.choose(()->new JSONObject().put("lat",location.getLatitude()).put("lng",location.getLongitude()),new java.security.SecureRandom());latitude.setText(String.format(Locale.US,"%.8f",p.getDouble("lat")));longitude.setText(String.format(Locale.US,"%.8f",p.getDouble("lng")));Toast.makeText(this,p.getString("source"),Toast.LENGTH_LONG).show();}catch(Exception error){fallbackLocation("定位失败");}
    }
    private void removeLocationListener(){if(locationListener!=null){try{getSystemService(LocationManager.class).removeUpdates(locationListener);}catch(Exception ignored){}locationListener=null;}}
}
