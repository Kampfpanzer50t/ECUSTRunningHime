package cn.huati.assistant;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Random;

/** Each automatic record chooses one millisecond in the open interval (12,16) minutes. */
final class DailyDuration {
    static final long MIN_MS=12*60*1000L,MAX_MS=16*60*1000L;
    private static final SecureRandom RANDOM=new SecureRandom();
    static long randomMillis(){return randomMillis(RANDOM);}
    static long randomMillis(Random random){return MIN_MS+1+random.nextInt((int)(MAX_MS-MIN_MS-1));}
    static void validate(long millis){if(millis<=MIN_MS||millis>=MAX_MS)throw new IllegalArgumentException("自动模拟时长需大于 12 分钟且小于 16 分钟");}
    static String label(long millis){return String.format(Locale.CHINA,"%d 分 %02d.%03d 秒",millis/60000,millis/1000%60,millis%1000);}
}
