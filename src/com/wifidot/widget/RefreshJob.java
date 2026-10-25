package com.wifidot.widget;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Periodic re-check (~every 15 min) and restart of the monitor if the system stopped it. */
public class RefreshJob extends JobService {
    private static final int JOB_ID = 4242;

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, RefreshJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        try { js.schedule(job); } catch (Throwable ignored) {}
    }

    static void cancel(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        MonitorService.start(this); // restart if the system killed it
        Checker.runAsync(this, new Runnable() {
            public void run() { jobFinished(params, false); }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
