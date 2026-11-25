package local.dailywallpaper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** A check after the TV starts, once it has a network, when the picture is not today's. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        // A check marked in progress before the restart is over.
        Store.get(context).stopRunning();
        Jobs.request(context, Schedule.Reason.BOOT);
    }
}
