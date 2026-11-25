package local.newepisodes;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.PowerManager;

/** Schedules refreshes with JobScheduler, so a refresh keeps the process alive until it ends. */
final class Jobs {
    static final int NOW = 1;
    static final int RETRY = 2;
    static final String REASON = "reason";
    /** Without a network, a refresh waits up to this long for one before trying anyway. */
    private static final long NETWORK_WAIT_MS = 2 * 60_000L;

    private Jobs() {
    }

    /** Asks for a refresh; {@link RefreshPolicy} decides whether it runs. */
    static void request(Context c, RefreshPolicy.Reason reason) {
        RefreshPolicy.State s = Store.get(c).state();
        if (!RefreshPolicy.shouldRun(reason, s, System.currentTimeMillis(), interactive(c))) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString(REASON, reason.name());
        JobInfo.Builder b = new JobInfo.Builder(NOW, new ComponentName(c, RefreshJob.class)).setExtras(extras);
        if (reason == RefreshPolicy.Reason.MANUAL) {
            // The person is looking at this app: offline, the refresh fails at once and the screen says so.
            b.setOverrideDeadline(0);
        } else {
            // Home starts before Wi-Fi is up after a restart: wait for a network rather than fail and show a retry.
            // With a network the job runs at once; still offline after the wait, it fails and the retries take over.
            b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setOverrideDeadline(NETWORK_WAIT_MS);
        }
        schedule(c, b.build());
    }

    static void scheduleRetry(Context c, long delayMs) {
        PersistableBundle extras = new PersistableBundle();
        extras.putString(REASON, RefreshPolicy.Reason.RETRY.name());
        schedule(c, new JobInfo.Builder(RETRY, new ComponentName(c, RefreshJob.class)).setExtras(extras)
                .setMinimumLatency(delayMs).setOverrideDeadline(delayMs + 60_000L).build());
    }

    static void cancelRetry(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(RETRY);
    }

    static void cancelAll(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.cancelAll();
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
