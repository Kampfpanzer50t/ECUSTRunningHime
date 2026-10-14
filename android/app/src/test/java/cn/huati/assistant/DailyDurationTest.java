package cn.huati.assistant;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class DailyDurationTest {
    @Test public void randomRangeExcludesBothMinuteEndpointsAtMillisecondResolution(){
        assertEquals(720001,DailyDuration.randomMillis(new Random(){@Override public int nextInt(int bound){return 0;}}));
        assertEquals(959999,DailyDuration.randomMillis(new Random(){@Override public int nextInt(int bound){return bound-1;}}));
        DailyDuration.validate(720001);DailyDuration.validate(959999);
        for(long value:new long[]{-1,0,720000,960000,960001}){
            try{DailyDuration.validate(value);fail("Excluded duration: "+value);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void manyPlansVaryBothSecondsAndMillisecondFractions(){
        Random random=new Random(4321);java.util.HashSet<Long> durations=new java.util.HashSet<>(),fractions=new java.util.HashSet<>();
        for(int i=0;i<2000;i++){
            long value=DailyDuration.randomMillis(random);assertTrue(value>720000&&value<960000);
            durations.add(value);fractions.add(value%1000);
        }
        assertTrue(durations.size()>1900);assertTrue(fractions.size()>800);
    }
    @Test public void chosenDurationIsDisplayedWithoutLosingMilliseconds(){
        assertEquals("12 分 00.001 秒",DailyDuration.label(720001));
        assertEquals("13 分 42.581 秒",DailyDuration.label(822581));
        assertEquals("15 分 59.999 秒",DailyDuration.label(959999));
    }
}
