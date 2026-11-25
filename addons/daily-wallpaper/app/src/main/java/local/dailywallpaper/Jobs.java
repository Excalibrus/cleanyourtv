package local.dailywallpaper;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.PowerManager;

/** Schedules checks with JobScheduler, so a check keeps the process alive until it ends. */
final class Jobs {
    static final int NOW = 1;
    static final int TIMED = 2;
    static final String REASON = "reason";
    /** Wait up to this long for a network (at boot Home asks before Wi-Fi is up), then try anyway. */
    private static final long NETWORK_WAIT_MS = 2 * 60_000L;
    /** A timed check may run this much after its time. */
    private static final long TIMED_SLACK_MS = 10 * 60_000L;

    private Jobs() {
    }

    /** Asks for a check; {@link Schedule} decides whether it runs. */
    static void request(Context c, Schedule.Reason reason) {
        Schedule.State s = Store.get(c).state();
        if (!Schedule.shouldRun(reason, s, System.currentTimeMillis(), interactive(c))) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString(REASON, reason.name());
        schedule(c, new JobInfo.Builder(NOW, new ComponentName(c, CheckJob.class)).setExtras(extras)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setOverrideDeadline(NETWORK_WAIT_MS).build());
    }

    /** The next timed check, at the store's next check time: after Bing's daily change, or a retry. */
    static void scheduleNext(Context c) {
        long delay = Math.max(0, Store.get(c).nextCheck() - System.currentTimeMillis());
        PersistableBundle extras = new PersistableBundle();
        extras.putString(REASON, Schedule.Reason.TIMED.name());
        schedule(c, new JobInfo.Builder(TIMED, new ComponentName(c, CheckJob.class)).setExtras(extras)
                .setMinimumLatency(delay).setOverrideDeadline(delay + TIMED_SLACK_MS).build());
    }

    static boolean interactive(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    private static void schedule(Context c, JobInfo job) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.schedule(job);
    }
}
