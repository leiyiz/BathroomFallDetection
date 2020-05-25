package com.example.bathroomfalldetection;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
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
 *
 * Workflow:
 * When app is turned on, start fall detection mechanisms.
 * If fall detected -> start sound alert and timer for contacting emergency contact
 *
 * If fall detected and time runs out to cancel -> Contact emergency contact.
 *  - If response from contact, parse response somehow or something
 *  - If no response from contact after a few minutes, contact emergency services
 *
 * If fall detected and muted -> Goes back to fall detection mode
 *
 * Stops detecting when user quits the app.
 *
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
    LinkedList<Double> window = new LinkedList<>();
    PriorityQueue<Double> sortedWindow = new PriorityQueue<>();
    PriorityQueue<Double> reversedSortedWindow = new PriorityQueue<>();
    List<Double> median_list = new ArrayList<>();

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
    final double GAUSSIAN_SCALE = 800;

    final int DELAY = (L - 1) / 2;
    final double CMF_TH = 1.6e7;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // TODO: Load contact information from file if available, else ask or something
        // TODO: Also load address

        // TODO: Pair with bluetooth device if available.

        // Gets recording permissions
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 0);

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
                boolean fallDetected = false;

                // Starts timer to contact emergency contact
                // Changes text of the fall detection status
                // Starts contact timers after
                 if (fallDetected && !(contactRunning | emergencyRunning)) {
                      ((TextView) findViewById(R.id.fall_detection_status)).setText(R.string.status_fall_detected);
                      contactRunning = true;
                      contactTimer = new CountDownTimer(CONTACT_COUNTDOWN, PERIODIC_ALERT) {
                            @Override
                            public void onTick(long millisUntilFinished) {
                                // Play a sound
                                Log.d("contactTimer", "Time left: " + millisUntilFinished);
                                // Play a sound
                                alertSound();
                                // TODO: Update the contact countdown timer text.
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
                        }.start();
                 }
            }
        }
    }

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

        ((TextView)findViewById(R.id.fall_detection_status)).setText(R.string.status_no_fall_detected);
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
     * TODO: Saves emergency contact and address information
     */
    public void saveContactHandler(View view) {
        // Check that a valid number was entered
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
     * TODO: Plays a sound that lasts 0.5 seconds during the timer
     * Note: Should bypass the user settings to still alert while muted.
     * Maybe also throw in some flashing lights for the sake of accessibility.
     */
    public void alertSound() {

    }

    /**
       Utility methods
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
        FileOutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(f, true);
            outputStream.write(data.getBytes());
            outputStream.close();
        } catch (IOException e) {
            Log.e("Exception", "File write failed: " + e.toString());
        }
    }
}
