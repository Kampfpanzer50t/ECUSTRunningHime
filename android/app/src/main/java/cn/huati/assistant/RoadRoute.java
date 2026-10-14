package cn.huati.assistant;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline road routing. Checkpoints are visited before any optional distance extension is trimmed. */
final class RoadRoute {
    private final double[][] nodes;
    private final boolean campusRestricted;
    private final ArrayList<ArrayList<Integer>> neighbours=new ArrayList<>();
    private static final int[][] ORDERS={{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
    RoadRoute(JSONObject graph)throws Exception{
        campusRestricted=graph.optString("campus").equals("fengxian");
        JSONArray data=graph.getJSONArray("nodes");nodes=new double[data.length()][2];
        for(int i=0;i<nodes.length;i++){
            JSONArray p=data.getJSONArray(i);nodes[i]=MapCoordinates.gcj(p.getDouble(0),p.getDouble(1));neighbours.add(new ArrayList<>());
        }
        JSONArray edges=graph.getJSONArray("edges");
        for(int i=0;i<edges.length();i++){
            JSONArray edge=edges.getJSONArray(i);int a=edge.getInt(0),b=edge.getInt(1);
            if(a<0||b<0||a>=nodes.length||b>=nodes.length||a==b)throw new IllegalArgumentException("校园道路数据无效");
            neighbours.get(a).add(b);neighbours.get(b).add(a);
        }
    }
    static int minimum(int requested){return Math.max(2100,requested-500);}
    static int maximum(int requested){return requested+500;}
    private static double distance(double[] a,double[] b){return RunMath.distance(a[0],a[1],b[0],b[1]);}
    private static double length(List<double[]> path){double total=0;for(int i=1;i<path.size();i++)total+=distance(path.get(i-1),path.get(i));return total;}
    private int nearest(double[] point){
        if(campusRestricted){double[] wgs=MapCoordinates.wgs(point[0],point[1]);if(!CampusArea.containsWgs(wgs[0],wgs[1]))throw new IllegalArgumentException("起点或考核点不在奉贤校区边界内，无法生成校园道路路线；请重新读取起点或获取三点");}
        int best=-1;double min=Double.POSITIVE_INFINITY;
        for(int i=0;i<nodes.length;i++){double d=distance(point,nodes[i]);if(d<min){min=d;best=i;}}
        if(min>120)throw new IllegalArgumentException("起点或考核点距已知校园道路超过 120 米，无法生成道路路线；请更换起点或关闭道路路线");
        return best;
    }
    private static long key(int a,int b){return ((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);}
    private ArrayList<Integer> shortest(int from,int to,Set<Long> used,double penalty){
        double[] cost=new double[nodes.length];Arrays.fill(cost,Double.POSITIVE_INFINITY);cost[from]=0;
        int[] parent=new int[nodes.length];Arrays.fill(parent,-1);
        PriorityQueue<double[]> queue=new PriorityQueue<>(Comparator.comparingDouble(a->a[1]));queue.add(new double[]{from,0});
        while(!queue.isEmpty()){
            double[] item=queue.remove();int a=(int)item[0];if(item[1]>cost[a])continue;if(a==to)break;
            for(int b:neighbours.get(a)){
                double value=cost[a]+distance(nodes[a],nodes[b])*(used.contains(key(a,b))?penalty:1);
                if(value<cost[b]){cost[b]=value;parent[b]=a;queue.add(new double[]{b,value});}
            }
        }
        if(!Double.isFinite(cost[to]))throw new IllegalArgumentException("校园道路未连通，无法连接本次三个点位");
        ArrayList<Integer> path=new ArrayList<>();for(int at=to;at>=0;at=parent[at]){path.add(at);if(at==from)break;}
        Collections.reverse(path);return path;
    }
    private void appendRoad(ArrayList<double[]> line,ArrayList<Integer> road,Set<Long> used){
        for(int i=0;i<road.size();i++){
            int node=road.get(i);append(line,nodes[node]);if(i>0)used.add(key(road.get(i-1),node));
        }
    }
    private static void append(ArrayList<double[]> line,double[] point){
        if(line.isEmpty()||distance(line.get(line.size()-1),point)>0.02)line.add(point.clone());
    }
    JSONObject plan(JSONArray checkpoints,double lat,double lng,int requested,boolean scheduled,Random random)throws Exception{
        if(checkpoints.length()!=3||requested<2000||requested>30000)throw new IllegalArgumentException("需要三个点位及 2–30 公里的目标里程");
        double[] origin={lat,lng};RunMath.coordinate(lat,lng);int start=nearest(origin);
        double[][] stops=new double[3][2];int[] snapped=new int[3];
        for(int i=0;i<3;i++){
            JSONObject p=checkpoints.getJSONObject(i);stops[i]=new double[]{p.getDouble("lat"),p.getDouble("lng")};
            RunMath.coordinate(stops[i][0],stops[i][1]);snapped[i]=nearest(stops[i]);
            for(int j=0;j<i;j++)if(distance(stops[i],stops[j])<0.1)throw new IllegalArgumentException("三个考核点必须不同");
        }
        ArrayList<double[]> best=null;Set<Long> bestUsed=null;int last=0;double bestScore=Double.POSITIVE_INFINITY;
        int upper=scheduled?Integer.MAX_VALUE:maximum(requested),lower=scheduled?2100:minimum(requested);
        // Different visit orders reduce avoidable backtracking; prefer a route close to the requested length.
        for(int[] order:ORDERS){
            ArrayList<double[]> line=new ArrayList<>();Set<Long> used=new HashSet<>();append(line,origin);append(line,nodes[start]);int at=start;
            for(int index:order){
                appendRoad(line,shortest(at,snapped[index],used,1),used);
                append(line,stops[index]);append(line,nodes[snapped[index]]);at=snapped[index];
            }
            double metres=length(line);if(Math.ceil(metres)>upper)continue;
            double score=(scheduled?metres:Math.abs(metres-requested)+(metres>requested?200:0))+random.nextDouble()*30;
            if(score<bestScore){best=line;bestUsed=used;last=at;bestScore=score;}
        }
        if(best==null)throw new IllegalArgumentException("沿道路经过本次三点会超出目标里程 +0.5 公里，请增加目标里程后预览");
        double mandatory=length(best);
        double desired=Math.max(mandatory,Math.max(lower+2,Math.min(upper-2,requested+random.nextDouble()*200-100)));
        int cycles=0;
        while(length(best)<desired-0.01){
            if(++cycles>100)throw new IllegalStateException("无法在本次道路范围内补足里程，请重新预览");
            int destination=random.nextInt(nodes.length);
            if(distance(nodes[last],nodes[destination])<120)continue;
            ArrayList<Integer> extension=shortest(last,destination,bestUsed,2.2);
            for(int i=1;i<extension.size();i++){
                double remaining=desired-length(best);double[] a=best.get(best.size()-1),b=nodes[extension.get(i)];double segment=distance(a,b);
                if(segment<=remaining){append(best,b);bestUsed.add(key(extension.get(i-1),extension.get(i)));last=extension.get(i);}
                else{double ratio=remaining/segment;append(best,new double[]{a[0]+(b[0]-a[0])*ratio,a[1]+(b[1]-a[1])*ratio});break;}
            }
        }
        JSONArray samples=new JSONArray();ArrayList<double[]> sampled=new ArrayList<>();append(sampled,best.get(0));
        for(int i=1;i<best.size();i++){
            double[] a=best.get(i-1),b=best.get(i);int count=Math.max(1,(int)Math.ceil(distance(a,b)/8));
            for(int k=1;k<=count;k++){double ratio=k/(double)count;append(sampled,new double[]{a[0]+(b[0]-a[0])*ratio,a[1]+(b[1]-a[1])*ratio});}
        }
        double metres=length(sampled);int actual=(int)Math.ceil(metres);
        if(campusRestricted)for(double[] point:sampled){double[] wgs=MapCoordinates.wgs(point[0],point[1]);if(!CampusArea.containsWgs(wgs[0],wgs[1]))throw new IllegalStateException("本次路线越过已知校区边界，请重新预览或关闭道路路线");}
        if(metres<lower||actual>upper)throw new IllegalStateException(scheduled?"道路路线未达到 2.1 公里":"道路路线里程未满足 2.1 公里下限及 ±0.5 公里范围");
        double phase=random.nextDouble()*Math.PI*2,totalWeight=0,travelled=0;double[] weights=new double[sampled.size()];
        for(int i=1;i<sampled.size();i++){
            double segment=distance(sampled.get(i-1),sampled.get(i));travelled+=segment;
            double speed=1+0.08*Math.sin(travelled/90+phase)+0.04*Math.cos(travelled/210+phase);
            totalWeight+=segment/speed;weights[i]=totalWeight;
        }
        for(int i=0;i<sampled.size();i++){
            double[] p=sampled.get(i);samples.put(new JSONObject().put("lat",p[0]).put("lng",p[1]).put("fraction",weights[i]/totalWeight));
        }
        return new JSONObject().put("distance",actual).put("measured_metres",metres).put("requested_distance",requested)
            .put("road_mode",true).put("scheduled_route",scheduled).put("route_samples",samples).put("source","OpenStreetMap 奉贤校园道路");
    }
    static JSONArray timed(JSONArray samples,long start,long duration)throws Exception{
        if(samples==null||samples.length()<2||duration<=0)throw new IllegalArgumentException("道路路线或计时时长无效");
        JSONArray out=new JSONArray();long previous=start-1;
        for(int i=0;i<samples.length();i++){
            JSONObject p=samples.getJSONObject(i);double fraction=p.getDouble("fraction");
            long time=i==samples.length()-1?start+duration:Math.max(previous+1,start+Math.round(fraction*duration));
            if(time>start+duration)throw new IllegalStateException("道路轨迹采样数超过可用时长");
            out.put(new JSONObject().put("name","").put("lat",p.getDouble("lat")).put("lng",p.getDouble("lng"))
                .put("timestamp",time).put("accuracy",5));previous=time;
        }
        return out;
    }
}
