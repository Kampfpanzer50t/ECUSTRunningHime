package cn.huati.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Decode only the requested art, off the UI thread, with a bounded bitmap cache. */
final class ArtRepository {
    private static final Handler UI=new Handler(Looper.getMainLooper());
    private static final ExecutorService DECODER=Executors.newSingleThreadExecutor();
    private static final int CACHE_BYTES=(int)Math.min(24*1024*1024,Runtime.getRuntime().maxMemory()/8);
    private static final LruCache<String,Bitmap> CACHE=new LruCache<>(CACHE_BYTES){
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private static JSONObject catalog;
    private static synchronized JSONObject catalog(Context context){
        if(catalog==null){
            try(var stream=context.getAssets().open("art/catalog.json")){
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
                while((count=stream.read(buffer))!=-1)out.write(buffer,0,count);
                catalog=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }catch(Exception ignored){catalog=new JSONObject();}
        }
        return catalog;
    }
    static int wallpaperCount(Context context){JSONArray values=catalog(context).optJSONArray("wallpapers");return values==null?1:Math.max(1,values.length());}
    static int backgroundCount(Context context){JSONArray values=catalog(context).optJSONArray("backgrounds");return values==null?1:Math.max(1,values.length());}
    static int pairCount(Context context){JSONArray values=catalog(context).optJSONArray("pairs");return values==null?1:Math.max(1,values.length());}
    static String wallpaper(Context context,int index){
        JSONArray values=catalog(context).optJSONArray("wallpapers");
        return "art/"+(values==null||values.length()==0?"wallpapers/scene_01.webp":values.optString(Math.floorMod(index,values.length())));
    }
    static String background(Context context,int index){
        JSONArray values=catalog(context).optJSONArray("backgrounds");
        return "art/"+(values==null||values.length()==0?"backgrounds/geometry_01.webp":values.optString(Math.floorMod(index,values.length())));
    }
    static String mascot(Context context,int index,boolean running){
        JSONArray values=catalog(context).optJSONArray("pairs");JSONObject pair=values==null||values.length()==0?null:values.optJSONObject(Math.floorMod(index,values.length()));
        return "art/"+(pair==null?"mascots/01_01_"+(running?"running":"normal")+".webp":pair.optString(running?"running":"normal"));
    }
    static void show(ImageView target,String path,int maxPixels,Runnable loaded){
        String key=path+":"+maxPixels;if(key.equals(target.getTag()))return;
        target.setTag(key);Context context=target.getContext().getApplicationContext();Bitmap cached=CACHE.get(key);
        if(cached!=null){target.setImageBitmap(cached);if(loaded!=null)loaded.run();return;}
        DECODER.execute(()->{
            Bitmap bitmap=null;
            try{
                BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;
                try(var input=context.getAssets().open(path)){BitmapFactory.decodeStream(input,null,size);}
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                while(Math.max(size.outWidth,size.outHeight)/options.inSampleSize>maxPixels*2)options.inSampleSize*=2;
                try(var input=context.getAssets().open(path)){bitmap=BitmapFactory.decodeStream(input,null,options);}
                if(bitmap!=null)CACHE.put(key,bitmap);
            }catch(Exception ignored){}
            Bitmap result=bitmap;
            UI.post(()->{if(!key.equals(target.getTag()))return;if(result!=null)target.setImageBitmap(result);if(loaded!=null)loaded.run();});
        });
    }
}
