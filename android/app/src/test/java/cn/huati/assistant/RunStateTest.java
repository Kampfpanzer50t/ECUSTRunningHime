package cn.huati.assistant;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RunStateTest {
    @Test public void completedOrDiscardedRecordsPermitCreatingAgain() throws Exception {
        assertFalse(RunEngine.unresolved(null));
        assertFalse(RunEngine.unresolved(new JSONObject().put("status","finished")));
        assertFalse(RunEngine.unresolved(new JSONObject().put("status","discarded")));
        for(String status:new String[]{"created","paused","checking","check_failed","create_pending","finish_pending","record_updated","create_unknown","finish_unknown"})
            assertTrue(status,RunEngine.unresolved(new JSONObject().put("status",status)));
    }
}
