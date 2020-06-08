package com.example.fallreceiver;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * App for detecting bathroom falls and contacting people if alert isn't muted.
 * <p>
 * Workflow:
 * When app is turned on, start fall detection mechanisms.
 * If fall detected -> start sound alert and timer for contacting emergency contact
 * <p>
 * If fall detected and time runs out to cancel -> Contact emergency contact.
 * - If response from contact, parse response somehow or something
 * - If no response from contact after a few minutes, contact emergency services
 * <p>
 * If fall detected and muted -> Goes back to fall detection mode
 * <p>
 * Stops detecting when user quits the app.
 */
public class MainActivity extends AppCompatActivity {

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
    private static final int REQUEST_ENABLE_BT = 3;

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


    AudioTrack audioTrack = null;

    CountDownTimer contactTimer;
    private boolean contactRunning;
    final int CONTACT_COUNTDOWN = 1 * 60 * 1000; // in ms
    final int PERIODIC_ALERT = 1 * 1000; // in ms

    CountDownTimer emergencyTimer;
    private boolean emergencyRunning;
    final int EMERGENCY_COUNTDOWN = 4 * 60 * 1000; // in ms

    final int L = 17;
    final int SAMPLE_RATE = 44100;
    final double DURATION = 0.8;
    final int NUM_SAMPLE = (int) (DURATION * SAMPLE_RATE);
    final int frequency = 500; //Hz

    final String CONTACT_FILENAME = "EmContact_number_and_address.txt";
    String number = null;
    String address = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Load contact information from file if available, TODO: else ask or something
        // Also load address
        List<String> readResults = readContactAndAddressFromFile(CONTACT_FILENAME);
        if (readResults.size() >= 2) {
            number = readResults.get(0);
            address = readResults.get(1);
            ((EditText) findViewById(R.id.contact_number_text)).setText(number);
            ((EditText) findViewById(R.id.address_text)).setText(address);
        }

        // TODO: Pair with bluetooth device if available.
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

        // Gets recording permissions
//        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS}, 0);

        // Gets recording parameters
//        int minSize = AudioRecord.getMinBufferSize(
//                SAMPLE_RATE,
//                AudioFormat.CHANNEL_IN_MONO,
//                AudioFormat.ENCODING_PCM_16BIT
//        );
//        Log.d("Recording", "buffer size is " + minSize);
//        recorder = new AudioRecord.Builder()
//                .setAudioSource(MediaRecorder.AudioSource.MIC)
//                .setAudioFormat(new AudioFormat.Builder()
//                        .setSampleRate(SAMPLE_RATE)
//                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
//                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
//                        .build())
//                .setBufferSizeInBytes(4 * minSize)
//                .build();

        //gets audiotrack
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                )
                .setAudioFormat(
                        new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                )
                .setBufferSizeInBytes(2 * NUM_SAMPLE)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        audioTrack.setVolume(1.0f);
        short[] shortSample = new short[NUM_SAMPLE];
        genTone(shortSample, frequency);
        audioTrack.write(shortSample, 0, shortSample.length);

        contactTimer = new CountDownTimer(CONTACT_COUNTDOWN, PERIODIC_ALERT) {
            @Override
            public void onTick(long millisUntilFinished) {
//                Log.d("contactTimer", "Time left: " + millisUntilFinished);
                // Play a sound
                alertSound();
                // TODO: Update the contact countdown timer text.
                TextView status_t = findViewById(R.id.fall_detection_status);
                status_t.setText(R.string.status_fall_detected);
            }

            @Override
            public void onFinish() {
                contactRunning = false;
                emergencyRunning = true;
                // Contact emergency contact
                contactSavedContact();

                // Start new timer for waiting for response or something when timer runs out, contact emergency services
                // TODO: Update the contact countdown timer text.
                // TODO: Update status
                emergencyTimer = new CountDownTimer(EMERGENCY_COUNTDOWN, PERIODIC_ALERT) {
                    @Override
                    public void onTick(long millisUntilFinished) {
//                        Log.d("emergencyTimer", "Time left: " + millisUntilFinished);
                        // Play a sound
                        alertSound();
                        // TODO: Update the contact countdown timer text.
                    }

                    @Override
                    public void onFinish() {
                        // TODO: Text police or something with address
                        textEmergencyServices();
                        // TODO: Start listener for response?
                        // If that doesn't work, maybe have it play an audio file saying that the person fell and may be unconscious
                        // TODO: Update status
                    }
                }.start();
            }
        };
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
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mCommService != null) {
            mCommService.stop();
        }
    }

    /**
     * Set up the background operations for communication.
     */

    private void setupComms() {
        Log.d("setUpComms", "setupChat()");

        // Initialize the BluetoothChatService to perform bluetooth connections
        mCommService = new BluetoothCommunicationService(this, mHandler);
    }

    /**
     * Updates the status on the action bar.
     *
     * @param s a string
     */
    private void setStatus(String s) {
        TextView status = findViewById(R.id.fall_detection_status);
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
                    TextView deviceStatus = findViewById(R.id.fall_detection_status);
                    deviceStatus.setText(writeMessage);
                    break;
                case Constants.MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    TextView pairStatus = findViewById(R.id.pair_status);
                    if (readMessage.contains("mute")) {
                        handleBluetoothMute();
                    } else if (readMessage.contains("alert")) {
                        handleBluetoothDetection();
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
        Log.d("Crashing here?", "nani");
        startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE);
    }

    /**
     * Cancels the alert
     */
    public void muteHandler(View view) {
        mute();
        // Send a cancel alert to the listener
        String s = getResources().getString(R.string.muted_by_receiver);
        sendMessage(s);
    }

    /**
     * TODO: Handles mute from bluetooth device somehow
     */
    public void handleBluetoothMute() {
        // Might need something related to a dedicated listener
        mute();
        String s = getResources().getString(R.string.muted_by_user);
        setStatus(s);
    }

    public void handleBluetoothDetection() {
        if (!(contactRunning | emergencyRunning)) {
            contactRunning = true;
            contactTimer.start();
        }
    }

    public void mute() {
        // After fall detected, app changes text box into fall detected
        // Pressing button will change to no fall detected
        // Reset emergency contact timer

        ((TextView) findViewById(R.id.fall_detection_status)).setText(R.string.status_no_fall_detected);
        // disable contact timer
        if (contactTimer != null && contactRunning) {
            contactRunning = false;
            contactTimer.cancel();
        }

        // disable emergency service timer
        cancelEmergencyTimer();
        // TODO: Maybe send a text like "Wait, nvm" to the saved contact
    }

    public void cancelEmergencyTimer() {
        if (emergencyTimer != null && emergencyRunning) {
            emergencyRunning = false;
            emergencyTimer.cancel();
        }
    }

    /**
     * Check if the boxes are both populated and Saves emergency contact and address information
     */
    public void saveContactHandler(View view) {
        // Check that a valid number was entered
        String tempNumber = ((EditText) findViewById(R.id.contact_number_text)).getText().toString();
        String tempAddress = ((EditText) findViewById((R.id.address_text))).getText().toString();

        if (tempNumber.length() == 10 && tempAddress.length() > 0) {
            number = tempNumber;
            address = tempAddress;
            String data = number + "\n" + address;
            writeToFile(data, CONTACT_FILENAME);
        } else {
            AlertDialog alertDialog = new AlertDialog.Builder(MainActivity.this).create();
            alertDialog.setMessage("your number is not 10 digits or your address is empty. please retry");
            alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK",
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
            alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "I guess",
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
            alertDialog.show();
        }
    }

    /**
     * TODO: Contacts saved contact
     */
    public void contactSavedContact() {
        Log.d("texting", "sending text to saved contact number");
        if (number == null) {
            Log.d("texting", "number is null, cannot send message");
            return;
        }
        String message = "Falling detection app detected falling and the alert hasn't been cancelled in " +
                +CONTACT_COUNTDOWN + " seconds.\n My address is " + address + "\n" +
                "send \"cancel\" to stop the app from texting 911";
        SmsManager.getDefault().sendTextMessage(number, null, message, null, null);

        // TODO: listen for reply or something.
        listenContactReply();
        Log.d("texting", "sent text to saved contact number");
    }

    public void listenContactReply() {
        Log.d("texting", "listening for reply");
        SmsListener listener = new SmsListener(number);
        IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        getApplicationContext().registerReceiver(listener, filter);
    }

    class SmsListener extends BroadcastReceiver {

        private String numberListening;

        public SmsListener(String number) {
            this.numberListening = number;
            Log.d("texting", "starting to monitor messages from " + number);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
                for (SmsMessage smsMessage : Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
                    String senderNum = smsMessage.getDisplayOriginatingAddress();
                    if (senderNum.equals("+1" + numberListening)) {
                        String messageBody = smsMessage.getMessageBody();
                        Log.d("texting", "got message from number " + senderNum + " with message: "
                                + messageBody);
                        if (messageBody.equals("cancel")) {
                            cancelEmergencyTimer();
                            String message = "ok, not going to text 911";
                            SmsManager.getDefault().sendTextMessage(number, null, message, null, null);
                            String s = getResources().getString(R.string.muted_by_contact);
                            setStatus(s);
                            sendMessage(s);
                        }
                    }
                }
            }
        }
    }

    /**
     * Text emergency services
     */
    public void textEmergencyServices() {
        Log.d("texting", "Sent text to emergency service");
        // not going to implement because we don't want to actually text 911
        // Has to listen for bounce-back or something. Also send address
    }

    /**
     * TODO: Call emergency services
     */
    public void callEmergencyServices() {
        // TODO: After successful connection, play audio file saying that someone fell or something
        // Still needs to bypass user settings to play audio files
        // TODO: Need available audio file: "User fell in bathroom..."

    }

    /**
     * Plays a sound that lasts 0.5 seconds during the timer
     * Note: Should bypass the user settings to still alert while muted.
     * Maybe also throw in some flashing lights for the sake of accessibility.
     */
    public void alertSound() {
        audioTrack.stop();
        if (audioTrack != null) {
//            Log.d("alertSound", "We out here");
//            audioTrack.reloadStaticData();
            audioTrack.play();
        }
    }

    /**
     * if filename does exist, then delete that file. Create a file named filename
     * and write data to that file.
     *
     * @param data     data needs to be written
     * @param filename filename to write data to
     */
    private void writeToFile(String data, String filename) {
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            //If it isn't mounted - we can't write into it.
            return;
        }
        //        Log.d("Trying to find where the heck I am", );
        File f = new File(this.getExternalFilesDir(null), filename);
        //        File f = new File(this.getFilesDir().getAbsolutePath(), filename);
        if (f.exists()) f.delete();

        try {
            f.createNewFile();
        } catch (IOException e) {
            Log.e("Exception", "File create failed: " + e.toString());
        }

        FileOutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(f, true);
            outputStream.write(data.getBytes());
            outputStream.close();
        } catch (IOException e) {
            Log.e("Exception", "File write failed: " + e.toString());
        }
    }

    /**
     * read contact number and Address from specified file. expect the file has 2 lines
     * the first line being the contact phone number and the second line being address.
     *
     * @param filename the file this method reads from
     * @return string list, the 0th element being number and the 1th being
     * address. if the filename given is not present, the array contains no String.
     */
    private List<String> readContactAndAddressFromFile(String filename) {
        List<String> result = new ArrayList<>();
        File f = new File(this.getExternalFilesDir(null), filename);
        if (f.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line;

                while ((line = br.readLine()) != null) {
                    result.add(line);
                }
                br.close();
            } catch (IOException e) {
                Log.e("Exception", "File read failed: " + e.toString());
            }
        }
        return result;
    }

    /**
     * generate tone of given frequency and populate the corresponding signal to given
     * short array
     *
     * @param shortSample
     * @param freq
     */
    private void genTone(short[] shortSample, int freq) {
        double angle = 0.0;
        double increment = 2 * Math.PI * freq / SAMPLE_RATE; // angular increment
        for (int i = 0; i < shortSample.length; i++) {
            shortSample[i] = (short) (Math.sin(angle) * Short.MAX_VALUE);
            angle += increment;
        }
    }
}
