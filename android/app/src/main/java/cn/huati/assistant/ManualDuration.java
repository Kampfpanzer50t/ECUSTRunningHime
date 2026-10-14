package cn.huati.assistant;

final class ManualDuration {
    static long parse(String minutes,String seconds,String milliseconds){
        int m=part(minutes),s=part(seconds),ms=part(milliseconds);
        if(m>120||s>59||ms>999)throw new IllegalArgumentException("秒需为 0–59，毫秒需为 0–999");
        long total=m*60000L+s*1000L+ms;validate(total);return total;
    }
    private static int part(String text){
        if(text==null||!text.trim().matches("[0-9]+"))throw new IllegalArgumentException("请填写整数分钟、秒和毫秒");
        try{return Integer.parseInt(text.trim());}catch(NumberFormatException error){throw new IllegalArgumentException("模拟时长超出范围");}
    }
    static void validate(long value){if(value<60000||value>7200000)throw new IllegalArgumentException("模拟时长需为 1–120 分钟");}
}
