package com.gathin.btconnect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The tile and module card open this: a small panel over Home with the paired speakers and headphones, the connected
 * one first. OK on one connects it, and the panel closes once it has; OK on the connected one disconnects it. The
 * TV's own Bluetooth settings only offer Connect and Unpair.
 */
public class MainActivity extends Activity {
    /** A speaker that is off or out of range may never answer: stop saying Working after this long. */
    private static final long GIVE_UP_MS = 15000;
    /** How long a pair that is letting go gets before the connect goes ahead anyway. */
    private static final long LET_GO_MS = 4000;
    /** How long the panel shows Connected before it closes itself. */
    private static final long CLOSE_MS = 800;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;
    /** Addresses asked to change a moment ago, shown as "Working…" until the change has happened. */
    private final Set<String> pending = new HashSet<>();
    /** The device being connected: its arrival closes the panel, its failure says so. */
    private BluetoothDevice target;
    /** What went wrong last, shown instead of the hint until the next OK. */
    private String note;
    private final Runnable connectTarget = this::connectTarget;
    private final Runnable giveUp = () -> {
        pending.clear();
        if (target != null) fail(target);
        refresh();
    };
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
            if (device != null) settled(device, state);
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.panel_background);
        int pad = Math.round(20 * dp);
        panel.setPadding(pad, pad, pad, Math.round(12 * dp));
        TextView title = new TextView(this);
        title.setText(R.string.panel_title);
        title.setTextSize(20);
        title.setTextColor(0xFFFFFFFF);
        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(0xFF8B949E);
        status.setPadding(0, 0, 0, Math.round(10 * dp));
        panel.addView(title);
        panel.addView(status);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Under the top bar's right end, where the launcher's Inputs menu drops.
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(Math.round(400 * dp),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        at.setMargins(0, Math.round(110 * dp), Math.round(44 * dp), Math.round(30 * dp));
        FrameLayout page = new FrameLayout(this);
        page.addView(panel, at);
        setContentView(page);
        adapter = getSystemService(BluetoothManager.class).getAdapter();
    }

    /** Nearby devices came with Android 12; before that the manifest's Bluetooth permissions are granted at install. */
    private boolean needsPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (needsPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else status.setText("Needs the Nearby devices permission: run install.sh once, or allow it in the app's settings.");
    }

    private void start() {
        if (adapter == null || !adapter.isEnabled()) {
            status.setText("Bluetooth is off. Turn it on in the TV's settings.");
            return;
        }
        registerReceiver(changed, new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED));
        if (a2dp != null) {
            refresh();
            return;
        }
        adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                a2dp = (BluetoothA2dp) proxy;
                refresh();
            }
            @Override public void onServiceDisconnected(int profile) {
                a2dp = null;
            }
        }, BluetoothProfile.A2DP);
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(changed);
        } catch (IllegalArgumentException notRegistered) {
        }
        main.removeCallbacksAndMessages(null);
        pending.clear();
        target = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        super.onDestroy();
    }

    /** A device reached a state: only connected and disconnected end its Working, the steps between do not. */
    @SuppressLint("MissingPermission")
    private void settled(BluetoothDevice device, int state) {
        if (state != BluetoothProfile.STATE_CONNECTED && state != BluetoothProfile.STATE_DISCONNECTED) return;
        pending.remove(device.getAddress());
        boolean isTarget = target != null && target.getAddress().equals(device.getAddress());
        if (state == BluetoothProfile.STATE_CONNECTED && isTarget) {
            main.removeCallbacksAndMessages(null);
            target = null;
            main.postDelayed(this::finish, CLOSE_MS);
        } else if (isTarget && pending.isEmpty() && a2dp != null) {
            main.removeCallbacks(giveUp);
            fail(device);
        } else if (target != null && a2dp != null && a2dp.getConnectedDevices().isEmpty()) {
            // The other pair has let go: now the one that was asked for.
            main.removeCallbacks(connectTarget);
            connectTarget();
        }
    }

    @SuppressLint("MissingPermission")
    private void fail(BluetoothDevice device) {
        target = null;
        note = "Couldn't reach " + device.getName() + ". Is it on and near the TV?";
    }

    /** Only runs after onResume has checked the Nearby devices permission. */
    @SuppressLint("MissingPermission")
    private void refresh() {
        if (a2dp == null) return;
        int focus = 0;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocused()) focus = i;
        list.removeAllViews();
        Set<String> on = new HashSet<>();
        for (BluetoothDevice d : a2dp.getConnectedDevices()) on.add(d.getAddress());
        List<BluetoothDevice> paired = new ArrayList<>(adapter.getBondedDevices());
        paired.sort((a, b) -> Boolean.compare(on.contains(b.getAddress()), on.contains(a.getAddress())));
        for (BluetoothDevice device : paired) {
            if (!Link.isAudio(device.getBluetoothClass())) continue;
            String address = device.getAddress();
            boolean connected = on.contains(address);
            Row row = new Row(this, device.getName() == null ? address : device.getName(),
                    pending.contains(address) ? "Working…" : connected ? "Connected" : "Not connected",
                    () -> toggle(device, connected));
            row.setValue(connected ? "Disconnect" : "Connect");
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        status.setText(note != null ? note : list.getChildCount() == 0
                ? "No paired speakers. Pair one in the TV's Bluetooth settings first."
                : "OK connects or disconnects.");
        if (list.getChildCount() > 0) list.getChildAt(Math.min(focus, list.getChildCount() - 1)).requestFocus();
    }

    @SuppressLint("MissingPermission")
    private void toggle(BluetoothDevice device, boolean connected) {
        if (a2dp == null || !pending.isEmpty()) return;
        note = null;
        if (connected) {
            change(device, false);
            return;
        }
        target = device;
        List<BluetoothDevice> others = a2dp.getConnectedDevices();
        if (others.isEmpty()) {
            connectTarget();
            return;
        }
        // The TV plays to one pair at a time, so the other lets go first; its broadcast starts the connect.
        for (BluetoothDevice other : others) change(other, false);
        main.postDelayed(connectTarget, LET_GO_MS);
    }

    private void connectTarget() {
        if (target != null && a2dp != null) change(target, true);
    }

    private void change(BluetoothDevice device, boolean connect) {
        if (!Link.set(a2dp, device, connect)) {
            target = null;
            note = "Android refused. Use the TV's Bluetooth settings: "
                    + Settings.ACTION_BLUETOOTH_SETTINGS.substring(Settings.ACTION_BLUETOOTH_SETTINGS.lastIndexOf('.') + 1);
            refresh();
            return;
        }
        pending.add(device.getAddress());
        refresh();
        main.removeCallbacks(giveUp);
        main.postDelayed(giveUp, GIVE_UP_MS);
    }
}
