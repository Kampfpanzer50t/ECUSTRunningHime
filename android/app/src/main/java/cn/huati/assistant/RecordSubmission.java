package cn.huati.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

/** Known invalid verdicts are submitted too; network uncertainty is never blindly retried. */
final class RecordSubmission {
    interface Environment {
        JSONObject request(String path,JSONObject body)throws Exception;
        void save(JSONObject state)throws Exception;
        void event(String event);
    }
    private final Environment environment;
    RecordSubmission(Environment environment){this.environment=environment;}
    void submit(JSONObject state,JSONObject payload,JSONArray path,long end)throws Exception{
        if(!state.optString("status").equals("created")&&!state.optString("status").equals("check_failed"))
            throw new IllegalStateException("当前记录已处理或结果待核实，不会重复提交");
        JSONObject encrypted=new JSONObject().put("a",RunMath.encrypt(payload)),checked;int verdict;
        state.put("status","checking").put("auto_submit",false);environment.save(state);
        try{
            JSONObject user=(JSONObject)ApiClient.success(environment.request("/xcxapi/getUserInfo/",null));
            if(!String.valueOf(user.get("id")).equals(state.getString("student_id")))throw new IllegalStateException("登录账号与本条记录不同");
            environment.event("check_request");checked=environment.request("/xcxapi/checkRecord/",encrypted);
            JSONObject detail=(JSONObject)ApiClient.success(checked);verdict=detail.optInt("status",-1);
            if(verdict!=0&&verdict!=1)throw new IllegalStateException("服务器校验状态未知，请先查询记录");
            state.put("check",checked).put("accepted_invalid",verdict==0);
            if(verdict==0)state.put("invalid_reason",detail.optString("warning",detail.optString("message",checked.optString("message","服务器判定无效"))));
        }catch(Exception error){
            state.put("status","created");RunClock.pause(state,end);state.put("error",error.getMessage());environment.save(state);throw error;
        }
        state.put("status","finish_pending").put("finish_payload",payload);environment.save(state);
        try{
            environment.event("update_request");ApiClient.success(environment.request("/xcxapi/updateRecordNew/",encrypted));
            state.put("status","record_updated");environment.save(state);environment.event("upload_request");
            for(int i=0;i<path.length();i+=50){
                JSONArray batch=new JSONArray();for(int j=i;j<Math.min(i+50,path.length());j++)batch.put(path.getJSONObject(j));
                JSONObject body=new JSONObject().put("record_id",state.getLong("record_id")).put("path_point",batch).put("path_image","");
                ApiClient.success(environment.request("/xcxapi/uploadPathPointV3/",new JSONObject().put("img_url",RunMath.encrypt(body))));
                state.put("uploaded_points",Math.min(i+50,path.length()));environment.save(state);
            }
            long activeMillis=RunClock.movingMillis(state,end);
            state.put("status","finished").put("end_ms",end).put("actual_duration",payload.getInt("running_time"))
                .put("actual_duration_ms",end-state.getLong("start_ms")).put("active_duration_ms",activeMillis);state.remove("error");environment.save(state);
            environment.event("finished");
        }catch(Exception error){
            state.put("status","finish_unknown").put("error","提交或轨迹上传结果待核实，请查询服务器；不会重复提交");environment.save(state);
            throw new IllegalStateException(state.getString("error"),error);
        }
    }
}
