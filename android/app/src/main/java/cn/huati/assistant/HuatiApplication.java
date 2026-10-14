package cn.huati.assistant;

import android.app.Application;

public final class HuatiApplication extends Application {
    private RunEngine engine;
    synchronized RunEngine engine(){if(engine==null)engine=new RunEngine(this);return engine;}
}
