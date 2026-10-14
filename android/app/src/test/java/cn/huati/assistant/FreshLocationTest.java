package cn.huati.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class FreshLocationTest {
    private static final long NOW=100000000000L;
    @Test public void freshPreciseFixIsAcceptedIncludingAgeBoundary(){
        assertTrue(FreshLocation.acceptable(30.8335,121.499,20,NOW,NOW));
        assertTrue(FreshLocation.acceptable(30.8335,121.499,100,NOW-FreshLocation.MAX_AGE_NANOS,NOW));
    }
    @Test public void staleFutureAndMissingFixTimesAreRejected(){
        assertFalse(FreshLocation.acceptable(30.8335,121.499,20,NOW-FreshLocation.MAX_AGE_NANOS-1,NOW));
        assertFalse(FreshLocation.acceptable(30.8335,121.499,20,NOW+1,NOW));
        assertFalse(FreshLocation.acceptable(30.8335,121.499,20,0,NOW));
    }
    @Test public void CoarseOrInvalidCoordinatesCannotCountAsCurrentCampusLocation(){
        assertFalse(FreshLocation.acceptable(30.8335,121.499,101,NOW,NOW));
        assertFalse(FreshLocation.acceptable(30.8335,121.499,Float.NaN,NOW,NOW));
        assertFalse(FreshLocation.acceptable(91,121.499,20,NOW,NOW));
        assertFalse(FreshLocation.acceptable(Double.NaN,121.499,20,NOW,NOW));
    }
}
