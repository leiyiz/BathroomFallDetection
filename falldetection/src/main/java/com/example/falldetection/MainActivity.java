package com.example.falldetection;

import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.app.ListActivity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Based heavily on:
 * https://github.com/android/connectivity-samples/tree/master/BluetoothChat/#readme
 */
public class MainActivity extends AppCompatActivity {

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
    private static final int REQUEST_ENABLE_BT = 3;

    // Layout Views
    private Button cancelBtn = null;
    private Button sendAlertBtn = null;

    /**
     * Name of the connected device
     */
    private String mConnectedDeviceName = null;

    /**
     * Local Bluetooth adapter
     */
    private BluetoothAdapter mBluetoothAdapter;

    /**
     * Member object for the communication services
     */
    private BluetoothCommunicationService mCommService = null;


     // Fall detection variables
    private SensorManager sensorManager;
    private Sensor accelSensor;
    private double ACCEL_THRESHOLD = 3.0; // TODO: Choose accelerometer threshold
    private int FALL_COUNT_THRESH = 1;
    private int fallCount = 0;
    private boolean alertSent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Use this check to determine whether BLE is supported on the device. Then
        // you can selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        // Initializes Bluetooth adapter.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        assert bluetoothManager != null;
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        cancelBtn = findViewById(R.id.send_cancel);
        sendAlertBtn = findViewById(R.id.send_alert);

        // Accelerometer/sensor setup
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }


    @Override
    public void onStart() {
        super.onStart();
        if (mBluetoothAdapter == null) {
            return;
        }
        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            // Otherwise, setup the chat session
        } else if (mCommService == null) {
            setupComms();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mCommService != null) {
            mCommService.stop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mCommService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mCommService.getState() == BluetoothCommunicationService.STATE_NONE) {
                // Start the Bluetooth chat services
                mCommService.start();
            }
        }
        sensorManager.registerListener(accelListener, accelSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    /**
     * Set up the background operations for communication.
     */

    private void setupComms() {
        Log.d("setUpComms", "setupChat()");

        // Initialize the send button with a listener that for click events
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Send a cancel alert to the listener
                sendMessage("muted by user");
                alertSent = false;
            }
        });

        sendAlertBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Send an alert alert to the listener
                sendMessage("alert");
            }
        });

        // Initialize the BluetoothChatService to perform bluetooth connections
        mCommService = new BluetoothCommunicationService(this, mHandler);
    }

    /**
     * Makes this device discoverable for 300 seconds (5 minutes).
     */
    private void ensureDiscoverable() {
        if (mBluetoothAdapter.getScanMode() !=
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(discoverableIntent);
        }
    }

    /**
     * Sends a message.
     *
     * @param message A string of text to send.
     */
    private void sendMessage(String message) {
        // Check that we're actually connected before trying anything
        if (mCommService.getState() != BluetoothCommunicationService.STATE_CONNECTED) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check that there's actually something to send
        if (message.length() > 0) {
            // Get the message bytes and tell the BluetoothChatService to write
            byte[] send = message.getBytes();
            mCommService.write(send);
        }
    }

    /**
     * Updates the status on the action bar.
     *
     * @param s a string
     */
    private void setStatus(String s) {
        TextView status = findViewById(R.id.status);
        status.setText(s);
    }

    /**
     * The Handler that gets information back from the BluetoothChatService
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothCommunicationService.STATE_CONNECTED:
                            setStatus("Connected to " + mConnectedDeviceName);
                            break;
                        case BluetoothCommunicationService.STATE_CONNECTING:
                            setStatus("Connecting");
                            break;
                        case BluetoothCommunicationService.STATE_LISTEN:
                        case BluetoothCommunicationService.STATE_NONE:
                            setStatus("Not connected");
                            break;
                    }
                    break;
                case Constants.MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    TextView deviceStatus = findViewById(R.id.status);
                    deviceStatus.setText(writeMessage);
                    break;
                case Constants.MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    TextView pairStatus = findViewById(R.id.pair_response);
                    if (readMessage.contains("mute")) {
                        alertSent = false;
                    }
                    pairStatus.setText(readMessage);
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    if (null != MainActivity.this) {
                        Toast.makeText(MainActivity.this, "Connected to "
                                + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case Constants.MESSAGE_TOAST:
                    if (null != MainActivity.this) {
                        Toast.makeText(MainActivity.this, msg.getData().getString(Constants.TOAST),
                                Toast.LENGTH_SHORT).show();
                    }
                    break;
            }
        }
    };

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE_SECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, true);
                }
                break;
            case REQUEST_CONNECT_DEVICE_INSECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, false);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    setupComms();
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d("onActivityResult", "BT not enabled");
                    if (this != null) {
                        Toast.makeText(this, R.string.bt_not_enabled_leaving,
                                Toast.LENGTH_SHORT).show();
                        this.finish();
                    }
                }
        }
    }

    /**
     * Establish connection with other device
     *
     * @param data   An {@link Intent} with {@link DeviceListActivity#EXTRA_DEVICE_ADDRESS} extra.
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    private void connectDevice(Intent data, boolean secure) {
        // Get the device MAC address
        Bundle extras = data.getExtras();
        if (extras == null) {
            return;
        }
        String address = extras.getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
//        String address = null;
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        mCommService.connect(device, secure);
    }

    public void onClickEnableDiscovery(View v) {
        ensureDiscoverable();
    }

    public void onClickPairOptions(View v) {

        Intent serverIntent = new Intent(this, DeviceListActivity.class);
        startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE);
    }


    // TODO: Do something with the accelerometer
    public SensorEventListener accelListener = new SensorEventListener() {
        public void onAccuracyChanged(Sensor sensor, int acc) {
        }

        public void onSensorChanged(SensorEvent event) {
//            double[] copy = new double[3];
            double length = 0.0;
            for (int i = 0; i < 3; i++) {
//                copy[i] = event.values[i];
                length += event.values[i] * event.values[i];
            }
            length = Math.sqrt(length);
//            Log.d("Accelerometer", Arrays.toString(event.values) + " length: " + length);
            // TODO: Do something
            if (length < ACCEL_THRESHOLD) {
                boolean fallDetected = true;
                fallCount += 1;
                Log.d("Accelerometer", "Fall detected!!!!!");
                // If falling motion is consecutive
                // TODO: Make it adaptive
                if (fallCount > FALL_COUNT_THRESH && !alertSent) {
                    // TODO: Send message to bluetooth receiver
                    Log.d("Accelerometer", "Sending message to receiver");
                    sendMessage("alert");
                    alertSent = true;
                }
            } else {
                fallCount = 0;
            }
        }
    };


}