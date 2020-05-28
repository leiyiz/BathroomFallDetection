package com.example.bathroomfalldetection;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

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
    final Runnable detector = new Runnable() {
        public void run() {
            detection();
        }
    };
    final int READ_SIZE = 4000;
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    ScheduledFuture<?> detectionHandle = null;
    AudioRecord recorder = null;
    AudioTrack audioTrack = null;
    LinkedList<Double> window = new LinkedList<>();
    PriorityQueue<Double> sortedWindow = new PriorityQueue<>();
    PriorityQueue<Double> reversedSortedWindow = new PriorityQueue<>();
    List<Double> median_list = new ArrayList<>();

    private SensorManager sensorManager;
    private Sensor accelSensor;
    private double ACCEL_THRESHOLD = 3.0; // TODO: Choose accelerometer threshold
    private int fallCount = 0;

    CountDownTimer contactTimer;
    private boolean contactRunning;
    final int CONTACT_COUNTDOWN = 1 * 60 * 1000; // in ms
    final int PERIODIC_ALERT = 1 * 1000; // in ms

    CountDownTimer emergencyTimer;
    private boolean emergencyRunning;
    final int EMERGENCY_COUNTDOWN = 4 * 60 * 1000; // in ms

    final int L = 17;
    final double THRESHOLD = 0.000225;
    final int SAMPLE_RATE = 44100;
    final double DURATION = 0.8;
    final int NUM_SAMPLE = (int) (DURATION * SAMPLE_RATE);
    final int frequency = 500; //Hz
    final double GAUSSIAN_SCALE = 800;

    final int DELAY = (L - 1) / 2;
    final double CMF_TH = 1.6e7;

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

        // Gets recording permissions
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 0);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        // Gets recording parameters
        int minSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        Log.d("Recording", "buffer size is " + minSize);
        recorder = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setBufferSizeInBytes(4 * minSize)
                .build();

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
                    // Play a sound
                    Log.d("contactTimer", "Time left: " + millisUntilFinished);
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
                    // TODO: Contact emergency contact
                    contactSavedContact(); // TODO: Maybe provide a message

                    // Start new timer for waiting for response or something when timer runs out, contact emergency services
                    // TODO: Update the contact countdown timer text.
                    // TODO: Update status
                    emergencyTimer = new CountDownTimer(EMERGENCY_COUNTDOWN, PERIODIC_ALERT) {
                        @Override
                        public void onTick(long millisUntilFinished) {
                            Log.d("emergencyTimer", "Time left: " + millisUntilFinished);
                            // Play a sound
                            alertSound();
                            // TODO: Update the contact countdown timer text.
                        }

                        @Override
                        public void onFinish() {
                            // TODO: Text police or something with address
                            // TODO: Start listener for response?
                            // If that doesn't work, maybe have it play an audio file saying that the person fell and may be unconscious
                            // TODO: Update status

                        }
                    }.start();
                }
            };


        scheduleDetection();
    }

    public void scheduleDetection() {
        detectionHandle = scheduler.schedule(detector, -1, MILLISECONDS);
    }

    public void detection() {
//        int bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        recorder.startRecording();
        while (true) {
            final short[] buffer = new short[READ_SIZE];
            try {

                // Read until buffer is full
                int offset = 0;
                int length = buffer.length;
                int read;
                while (length > 0) {
                    read = recorder.read(buffer, offset, length);
                    length -= read;
                    offset += read;
                }
//                recorder.stop();
            } catch (Exception e) {
                Log.d("In thread", e.toString());
//                recorder.stop();
            }

            // Calculates power over recording
            double e = 0;
            Random r = new Random();
            for (int i = 0; i < READ_SIZE; i++) {
//                buffer[i] += 2000;
//                double signal = buffer[i] + r.nextGaussian() * GAUSSIAN_SCALE;
                double signal = buffer[i];
                e += signal * signal;
            }
            e /= READ_SIZE;
//            e = Math.sqrt(e);
            Log.d("energy_value", Double.toString(e));

            // Add power to current window and update min/max
            window.add(e);
            sortedWindow.add(e);
            reversedSortedWindow.add(-e);
            int index = Collections.binarySearch(median_list, e);
            if (index < 0) {
                index = -index - 1;
            }
            median_list.add(index, e);

            // Remove from window if larger than L
            if (window.size() > L) {
                double temp = window.remove();
                sortedWindow.remove(temp);
                reversedSortedWindow.remove(-temp);
                index = Collections.binarySearch(median_list, temp);
                if (index < 0) {
                    Log.d("WTF", "binary search on doubles went really wrong, want " + temp + " got " + median_list.get(index));
                    index = -index - 1;
                }
                median_list.remove(index);
            }

            // Calculates average and variance and cut-offs.
            if (window.size() == L) {
                // Median method
                // TODO: Use a different L for medians or something so that we can get median detection and find pulses.
                double median = median_list.size() % 2 == 0
                        ? (median_list.get(median_list.size() / 2) + median_list.get(median_list.size() / 2 + 1)) / 2
                        : median_list.get((median_list.size() / 2));

                double energyDelayed = window.get(window.size() - 1 - DELAY);
                double cmfk = Math.abs(median - energyDelayed) > CMF_TH
                        ? median
                        : energyDelayed;

                double pk = energyDelayed - cmfk;

                Log.d("condition_median_filter", "median=" + median + " energyDelayed=" + energyDelayed + " cmfk=" + cmfk + " pk=" + pk);

                if (pk > 0.1) {
                    Log.d("alert_above_thresh", pk + " is above threshold");
                }


                // Variance method
                // Calculates normalized energy
//                double minimum = sortedWindow.peek();
//                double maximum = -reversedSortedWindow.peek();
//                double[] normalizedEnergy = new double[L];
//                int i = 0;
//                double diff = maximum - minimum;
//                double sum = 0;
//                for (Double eWin : window) {
//                    normalizedEnergy[i] = (eWin - minimum) / diff;
//                    if (i != window.size() - 1)
//                        sum += normalizedEnergy[i];
//                    i += 1;
//                }
//                double average = sum / (L - 1);
//
////                Log.d("maximum", Double.toString(maximum));
//
//                // variance calculations
//                double variance = 0.0;
//                double temp;
//                for (i = 0; i < L - 1; i++) {
//                    temp = normalizedEnergy[i] - average;
//                    variance += temp * temp;
//                }
//                variance /= (L - 1);
//                Log.d("average_normalized_variance", average + " " + variance);
//                if (variance < THRESHOLD) {
////                    changeText("alert");
//                    Log.d("alert_below_thresh", variance + " is below threshold");
//                } else {
////                    changeText("no_alert");
//                }

                // TODO: Fall detected
                // TODO: Accelerometer
                boolean fallDetected = false;

                // Starts timer to contact emergency contact
                // Changes text of the fall detection status
                // Starts contact timers after
                if (fallDetected && !(contactRunning | emergencyRunning)) {
                    Log.d("What", "Does it even get in here");
                    Log.d("Does it set", "set");
                    contactRunning = true;
                    Log.d("Nani, fam", "nani");
                    contactTimer.start();
                    Log.d("What the fuck", "Timer");
                }
            }
        }
    }

    public void onResume() {
        super.onResume();
        sensorManager.registerListener(accelListener, accelSensor, SensorManager.SENSOR_DELAY_NORMAL);
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
            Log.d("Accelerometer", Arrays.toString(event.values) + " length: " + length);
            // TODO: Do something
            if (length < ACCEL_THRESHOLD) {
                boolean fallDetected = true;
                fallCount += 1;
                Log.d("Accelerometer", "Fall detected!!!!!");
                // If falling motion is consecutive
                // TODO: Make it adaptive
                if (fallCount > 3 && !(contactRunning | emergencyRunning)) {
                    contactRunning = true;
                    contactTimer.start();
                }
            } else {
                fallCount = 0;
            }
        }
    };


    public void changeText(String text) {
        ((TextView) findViewById(R.id.fall_detection_status)).setText(text);
    }

    /**
     * Cancels the alert
     */
    public void muteHandler(View view) {
        mute();
    }

    /**
     * TODO: Handles mute from bluetooth device somehow
     */
    public void handleBluetoothMute() {
        // Might need something related to a dedicated listener
        mute();
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
        if (emergencyTimer != null && emergencyRunning) {
            emergencyRunning = false;
            emergencyTimer.cancel();
            // TODO: Maybe send a text like "Wait, nvm" to the saved contact

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
        }
    }

    /**
     * TODO: Contacts saved contact
     */
    public void contactSavedContact() {
        // TODO: Has to listen for reply or something. Also send address
    }

    /**
     * TODO: Text emergency services
     */
    public void textEmergencyServices() {
        // TODO: Has to listen for bounce-back or something. Also send address
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
            Log.d("alertSound", "We out here");
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
