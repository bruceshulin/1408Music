package com.android.music;

import android.app.Application;
import java.util.LinkedList;
import java.util.List;
import android.app.Activity;
/* SPRD we use this to unified manage Activity 399553@{*/
public class MusicApplication extends Application {
    private List<Activity> mActivityList = new LinkedList<Activity>();
    private static MusicApplication instance;

    public static MusicApplication getInstance() {
        if (instance == null) {
            instance = new MusicApplication();
        }
        return instance;
    }

    public void addActivity(Activity activity) {
       mActivityList.add(activity);
    }
    /* SPRD 428688 @{ */
    public void removeActivity(Activity activity) {
       mActivityList.remove(activity);
    }
    /* @} */
    public void exit() {
        for (Activity activity : mActivityList) {
            if (activity != null) {
                activity.finish();
            }
        }
        mActivityList.clear();
    }
}
/* @} */
