package local.newepisodes;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;

/** Runs one {@link Refresher} pass on a background thread. */
public class RefreshJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        RefreshPolicy.Reason reason = RefreshPolicy.Reason.from(params.getExtras().getString(Jobs.REASON));
        // A timed retry waits while the TV is off (the next return to Home catches up), and is dropped when a
        // refresh succeeded or is running meanwhile.
        if (reason == RefreshPolicy.Reason.RETRY && !RefreshPolicy.shouldRun(reason, Store.get(this).state(),
                System.currentTimeMillis(), Jobs.interactive(this))) {
            return false;
        }
        Context app = getApplicationContext();
        new Thread(() -> {
            long retry = Refresher.KEEP_RETRY;
            try {
                retry = Refresher.run(app);
            } finally {
                jobFinished(params, false);
            }
            // Only now: the retry job may be this one, and replacing or cancelling a running job stops it.
            if (retry > 0) Jobs.scheduleRetry(app, retry);
            else if (retry == Refresher.NO_RETRY) Jobs.cancelRetry(app);
        }, "refresh").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The HTTP calls time out by themselves; the store marks a dead run stale after three minutes.
        return false;
    }
}
