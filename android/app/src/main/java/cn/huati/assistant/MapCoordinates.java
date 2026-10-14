package cn.huati.assistant;

/** Android GPS and OSM use WGS84; the campus run point coordinates use GCJ-02. */
final class MapCoordinates {
    private static double latitude(double x,double y){
        double value=-100+2*x+3*y+0.2*y*y+0.1*x*y+0.2*Math.sqrt(Math.abs(x));
        value+=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
        value+=(20*Math.sin(y*Math.PI)+40*Math.sin(y/3*Math.PI))*2/3;
        return value+(160*Math.sin(y/12*Math.PI)+320*Math.sin(y*Math.PI/30))*2/3;
    }
    private static double longitude(double x,double y){
        double value=300+x+2*y+0.1*x*x+0.1*x*y+0.1*Math.sqrt(Math.abs(x));
        value+=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
        value+=(20*Math.sin(x*Math.PI)+40*Math.sin(x/3*Math.PI))*2/3;
        return value+(150*Math.sin(x/12*Math.PI)+300*Math.sin(x/30*Math.PI))*2/3;
    }
    static double[] gcj(double lat,double lng){
        RunMath.coordinate(lat,lng);
        if(lng<72.004||lng>137.8347||lat<0.8293||lat>55.8271)return new double[]{lat,lng};
        double rad=lat*Math.PI/180,sin=Math.sin(rad),magic=1-0.00669342162296594323*sin*sin,sqrt=Math.sqrt(magic);
        double dLat=latitude(lng-105,lat-35)*180/((6378245*(1-0.00669342162296594323)/(magic*sqrt))*Math.PI);
        double dLng=longitude(lng-105,lat-35)*180/(6378245/sqrt*Math.cos(rad)*Math.PI);
        return new double[]{lat+dLat,lng+dLng};
    }
    static double[] wgs(double lat,double lng){
        double a=lat,b=lng;
        for(int i=0;i<5;i++){double[] mapped=gcj(a,b);a-=mapped[0]-lat;b-=mapped[1]-lng;}
        return new double[]{a,b};
    }
}
