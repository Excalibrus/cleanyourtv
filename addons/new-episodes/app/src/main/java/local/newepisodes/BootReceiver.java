package local.newepisodes;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * One refresh after the TV starts, once it has a network, unless Home already made one. A run still marked in
 * progress from before the restart does not count: {@link Store#state()} ignores it.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Jobs.request(context, RefreshPolicy.Reason.BOOT);
    }
}
