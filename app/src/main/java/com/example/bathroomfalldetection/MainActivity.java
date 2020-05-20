package com.example.bathroomfalldetection;

import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class MainActivity extends AppCompatActivity {
    final Runnable detector = new Runnable() { public void run() { detection(); } };
    final int BUFFER_SIZE = 4000;
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    ScheduledFuture<?> detectionHandle = null;
    AudioRecord recorder = null;
    Queue<Double> window = new LinkedList<>();
    PriorityQueue<Double> sortedWindow = new PriorityQueue<>();
    PriorityQueue<Double> reversedSortedWindow = new PriorityQueue<>();

    final int L = 30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 0);
        recorder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(44100)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(BUFFER_SIZE)
                    .build();
        scheduleDetection();
    }
    
    public void scheduleDetection() {
        detectionHandle = scheduler.schedule(detector, -1, MILLISECONDS);
    }

    public void detection() {
//        int bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        while (true) {
            final short[] buffer = new short[BUFFER_SIZE];
            try {
                recorder.startRecording();

                // Read until buffer is full
                int offset = 0;
                int length = buffer.length;
                int read;
                while (length > 0) {
                    read = recorder.read(buffer, offset, length);
                    length -= read;
                    offset += read;
                }
                recorder.stop();
            } catch (Exception e) {
                Log.d("In thread", e.toString());
                recorder.stop();
            }

            // Calculates power over recording
            double e = 0;
            for (int i = 0; i < BUFFER_SIZE; i++) {
                e += buffer[i] * buffer[i];
            }
            e /= BUFFER_SIZE;
            Log.d("energy_value", Double.toString(e));

            // Add power to current window and update min/max
            window.add(e);
            sortedWindow.add(e);
            reversedSortedWindow.add(-e);

            // Remove from window if larger than L
            if (window.size() > L) {
                double temp = window.remove();
                sortedWindow.remove(temp);
                reversedSortedWindow.remove(-temp);
            }

            // Calculates average and variance and cut-offs.
            if (window.size() == L) {
                // Calculates normalized energy
                double minimum = sortedWindow.peek();
                double maximum = -reversedSortedWindow.peek();
                double[] normalizedEnergy = new double[L];
                int i = 0;
                double diff = maximum - minimum;
                double sum = 0;
                for (Double eWin : window) {
                    normalizedEnergy[i] = (eWin - minimum) / diff;
                    sum += normalizedEnergy[i];
                    i += 1;
                }
                double average = sum / L;

                Log.d("maximum", Double.toString(maximum));

                // variance calculations
                double variance = 0.0;
                double temp;
                for (i = 0; i < L - 1; i++) {
                    temp = normalizedEnergy[i] - average;
                    variance += temp * temp;
                }
                variance /= (L - 1);
                Log.d("average_normalized_variance", average + " " + variance);
            }
        }
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
