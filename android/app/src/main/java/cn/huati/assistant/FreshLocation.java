package cn.huati.assistant;

/** A location must be recent on the monotonic clock and sufficiently precise for campus points. */
final class FreshLocation {
    static final long MAX_AGE_NANOS=60*1000000000L;
    static final float MAX_ACCURACY=100;
    static boolean acceptable(double lat,double lng,float accuracy,long fixNanos,long nowNanos){
        return Double.isFinite(lat)&&Double.isFinite(lng)&&lat>=-90&&lat<=90&&lng>=-180&&lng<=180
            &&Float.isFinite(accuracy)&&accuracy>=0&&accuracy<=MAX_ACCURACY
            &&fixNanos>0&&nowNanos>=fixNanos&&nowNanos-fixNanos<=MAX_AGE_NANOS;
    }
}
