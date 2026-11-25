package local.dailywallpaper;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs one {@link Fetcher} check on a background thread, then sets up the next timed one. */
public class CheckJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        Schedule.Reason reason = Schedule.Reason.from(params.getExtras().getString(Jobs.REASON));
        if (reason == Schedule.Reason.TIMED && !Schedule.shouldRun(reason, Store.get(this).state(),
                System.currentTimeMillis(), Jobs.interactive(this))) {
            // The TV is off, or nothing is due: the next return to Home asks again.
            return false;
        }
        new Thread(() -> {
            try {
                Fetcher.run(getApplicationContext());
            } finally {
                // Finished first: rescheduling the job that is still running would cancel it.
                jobFinished(params, false);
                Jobs.scheduleNext(getApplicationContext());
            }
        }, "check").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The HTTP calls time out by themselves; the store marks a dead run stale after three minutes.
        return false;
    }
}
