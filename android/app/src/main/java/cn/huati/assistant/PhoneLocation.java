package cn.huati.assistant;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

final class PhoneLocation {
    static boolean precise(Context context){return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    static boolean background(Context context){return Build.VERSION.SDK_INT<29||context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    static void require(Context context){
        if(!precise(context))throw new IllegalStateException("请先允许精确位置权限");
        if(!background(context))throw new IllegalStateException("锁屏自动定位需要位置权限设为“始终允许”");
    }
    static JSONObject read(Context context,BooleanSupplier allowed)throws Exception{
        require(context);
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("定位必须在后台任务中进行");
        LocationManager manager=context.getSystemService(LocationManager.class);
        LinkedBlockingQueue<Location> fixes=new LinkedBlockingQueue<>();ArrayList<String> providers=new ArrayList<>();
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER})
            if(manager.isProviderEnabled(provider))providers.add(provider);
        if(providers.isEmpty())throw new IllegalStateException("请开启手机定位开关，自动定位未取得位置");
        LocationListener listener=new LocationListener(){
            @Override public void onLocationChanged(Location fix){
                if(fix.hasAccuracy()&&FreshLocation.acceptable(fix.getLatitude(),fix.getLongitude(),fix.getAccuracy(),fix.getElapsedRealtimeNanos(),SystemClock.elapsedRealtimeNanos()))fixes.offer(new Location(fix));
            }
            @Override public void onStatusChanged(String provider,int status,Bundle extras){}
            @Override public void onProviderEnabled(String provider){}
            @Override public void onProviderDisabled(String provider){}
        };
        try{
            for(String provider:providers)manager.requestLocationUpdates(provider,1000,0,listener,Looper.getMainLooper());
            long deadline=SystemClock.elapsedRealtime()+60000;
            while(SystemClock.elapsedRealtime()<deadline){
                if(!allowed.getAsBoolean())throw new IllegalStateException("本次自动定位已停止");
                require(context);
                Location fix=fixes.poll(Math.min(1000,Math.max(1,deadline-SystemClock.elapsedRealtime())),TimeUnit.MILLISECONDS);
                if(fix!=null&&FreshLocation.acceptable(fix.getLatitude(),fix.getLongitude(),fix.getAccuracy(),fix.getElapsedRealtimeNanos(),SystemClock.elapsedRealtimeNanos()))
                    return new JSONObject().put("lat",fix.getLatitude()).put("lng",fix.getLongitude());
            }
            throw new IllegalStateException("60 秒内未获得新位置（需精度在 100 米内）；请检查定位和后台权限");
        }catch(SecurityException error){throw new IllegalStateException("位置权限已被关闭，将使用随机校内模拟起点",error);}
        finally{try{manager.removeUpdates(listener);}catch(SecurityException ignored){}}
    }
}
