package com.example.bathroomfalldetection;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class MainActivity extends AppCompatActivity {
    final Runnable detector = new Runnable() { public void run() { detection(); } };
    final int READ_SIZE = 4000;
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    ScheduledFuture<?> detectionHandle = null;
    AudioRecord recorder = null;
    Queue<Double> window = new LinkedList<>();
    PriorityQueue<Double> sortedWindow = new PriorityQueue<>();
    PriorityQueue<Double> reversedSortedWindow = new PriorityQueue<>();
    List<Double> median_list = new ArrayList<>();

    final int L = 30;
    final double THRESHOLD = 0.0000000225;
    final int SAMPLE_RATE = 44100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 0);
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
            for (int i = 0; i < READ_SIZE; i++) {
//                buffer[i] += 2000;
                e += buffer[i] * buffer[i];
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
                double median = (median_list.get(median_list.size() / 2) + median_list.get(median_list.size() / 2 + 1)) / 2;

                // Variance method
                // Calculates normalized energy
                double minimum = sortedWindow.peek();
                double maximum = -reversedSortedWindow.peek();
                double[] normalizedEnergy = new double[L];
                int i = 0;
                double diff = maximum - minimum;
                double sum = 0;
                for (Double eWin : window) {
                    normalizedEnergy[i] = (eWin - minimum) / diff;
                    if (i != window.size() - 1)
                        sum += normalizedEnergy[i];
                    i += 1;
                }
                double average = sum / (L - 1);

//                Log.d("maximum", Double.toString(maximum));

                // variance calculations
                double variance = 0.0;
                double temp;
                for (i = 0; i < L - 1; i++) {
                    temp = normalizedEnergy[i] - average;
                    variance += temp * temp;
                }
                variance /= (L - 1);
                Log.d("average_normalized_variance", average + " " + variance);
                if (variance < THRESHOLD) {
//                    changeText("alert");
                    Log.d("alert_below_thresh", variance + " is below threshold");
                } else {
//                    changeText("no_alert");
                }
            }
        }
    }

    public void changeText(String text) {
        ((TextView) findViewById(R.id.displayBox)).setText(text);
    }





 /*
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
