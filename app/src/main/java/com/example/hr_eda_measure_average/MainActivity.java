package com.example.hr_eda_measure_average;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements MessageClient.OnMessageReceivedListener {

    private static final int SENSOR_TYPE_EDA = 65554;
    private static final int REQUEST_BODY_SENSORS = 1001;
    private static final String REALTIME_PATH = "/realtime_eda";
    private static final String PATH_START = "/start_recording";
    private static final String PATH_STOP = "/stop_recording";

    private SensorManager sensorManager;
    private Sensor edaSensor;

    // UIコンポーネント
    private TextView edaValueTextView;
    private TextView edaRawTextView;
    private TextView statusTextView;
    private TextView filenameTextView;
    private Button actionButton;
    private ScrollView mainScrollView;

    private boolean isRecording = false;
    private PowerManager.WakeLock wakeLock;

    private FileWriter csvWriter;
    private File currentFile;
    private long sessionStartTime;
    private String sessionStartTimeStr;

    private String targetNodeId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EDA_Logger::WakeLock");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initUI();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        edaSensor = sensorManager.getDefaultSensor(SENSOR_TYPE_EDA);

        if (edaSensor == null) {
            statusTextView.setText("No Sensor");
            statusTextView.setTextColor(Color.RED);
            actionButton.setEnabled(false);
            Toast.makeText(this, "EDA Sensor not found!", Toast.LENGTH_LONG).show();
        }

        checkPermissions();
        findConnectedNode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // スマホからのメッセージ受信を待機
        Wearable.getMessageClient(this).addListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Wearable.getMessageClient(this).removeListener(this);
    }

    // --- スマホからのメッセージ受信処理 ---
    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        if (path.equals(PATH_START)) {
            runOnUiThread(() -> {
                if (!isRecording) {
                    Toast.makeText(this, "Remote Start", Toast.LENGTH_SHORT).show();
                    startRecording();
                }
            });
        } else if (path.equals(PATH_STOP)) {
            runOnUiThread(() -> {
                if (isRecording) {
                    Toast.makeText(this, "Remote Stop", Toast.LENGTH_SHORT).show();
                    stopRecording();
                }
            });
        }
    }

    private void initUI() {
        edaValueTextView = findViewById(R.id.edaValueTextView);
        edaRawTextView = findViewById(R.id.edaRawTextView);
        statusTextView = findViewById(R.id.statusTextView);
        filenameTextView = findViewById(R.id.filenameTextView);
        actionButton = findViewById(R.id.actionButton);
        mainScrollView = findViewById(R.id.mainScrollView);

        mainScrollView.requestFocus();

        actionButton.setOnClickListener(v -> toggleRecording());
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL &&
                event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {

            float delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL) *
                    ViewConfiguration.get(this).getScaledVerticalScrollFactor();

            mainScrollView.scrollBy(0, Math.round(delta));
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BODY_SENSORS}, REQUEST_BODY_SENSORS);
        } else {
            registerSensors();
        }
    }

    private void toggleRecording() {
        if (!isRecording) {
            startRecording();
        } else {
            stopRecording();
        }
    }

    private void startRecording() {
        findConnectedNode();
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "EDA_" + timeStamp + ".csv";

            currentFile = new File(getExternalFilesDir(null), fileName);
            csvWriter = new FileWriter(currentFile);
            // ヘッダーを変更
            csvWriter.append("Timestamp_ms,Elapsed_sec,Raw_mOhms,Converted_uS\n");

            isRecording = true;
            sessionStartTime = System.currentTimeMillis();
            sessionStartTimeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(sessionStartTime));

            statusTextView.setText("RECORDING");
            statusTextView.setTextColor(Color.parseColor("#E25142"));
            filenameTextView.setText(fileName);

            actionButton.setText("Stop");
            actionButton.getBackground().setTint(Color.parseColor("#E25142"));

            if (!wakeLock.isHeld()) wakeLock.acquire();

        } catch (IOException e) {
            Log.e("CSV", "Error starting recording", e);
            Toast.makeText(this, "File Error", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        isRecording = false;

        try {
            if (csvWriter != null) {
                csvWriter.flush();
                csvWriter.close();
                csvWriter = null;
            }
        } catch (IOException e) {
            Log.e("CSV", "Error closing file", e);
        }

        statusTextView.setText("SAVED");
        statusTextView.setTextColor(Color.parseColor("#81C995"));

        actionButton.setText("Start Rec");
        actionButton.getBackground().setTint(Color.parseColor("#303030"));

        if (wakeLock.isHeld()) wakeLock.release();

        Toast.makeText(this, "Saved to Storage", Toast.LENGTH_SHORT).show();

        if (currentFile != null && currentFile.exists()) {
            sendFileToPhone(currentFile);
        }
    }

    private void sendRealTimeData(float edaValue) {
        if (targetNodeId == null) return;
        new Thread(() -> {
            try {
                byte[] payload = ByteBuffer.allocate(4).putFloat(edaValue).array();
                Tasks.await(Wearable.getMessageClient(this).sendMessage(targetNodeId, REALTIME_PATH, payload));
            } catch (Exception e) {
                Log.e("RealTime", "Send failed", e);
            }
        }).start();
    }

    private void findConnectedNode() {
        new Thread(() -> {
            try {
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                for (Node node : nodes) {
                    if (node.isNearby()) {
                        targetNodeId = node.getId();
                        break;
                    }
                }
                if (targetNodeId == null && !nodes.isEmpty()) {
                    targetNodeId = nodes.get(0).getId();
                }
            } catch (Exception e) {
                Log.e("Node", "Error finding node", e);
            }
        }).start();
    }

    private void sendFileToPhone(File file) {
        new Thread(() -> {
            try {
                String nodeId = targetNodeId;
                if (nodeId == null) {
                    List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());
                    if (!nodes.isEmpty()) nodeId = nodes.get(0).getId();
                }

                if (nodeId != null) {
                    runOnUiThread(() -> Toast.makeText(this, "Sending CSV...", Toast.LENGTH_SHORT).show());
                    sendFileContent(nodeId, file);
                } else {
                    runOnUiThread(() -> Toast.makeText(this, "No Phone Connected", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e("Sender", "Error finding phone", e);
            }
        }).start();
    }

    private void sendFileContent(String nodeId, File file) {
        try {
            ChannelClient.Channel channel = Tasks.await(
                    Wearable.getChannelClient(this).openChannel(nodeId, "/eda_csv")
            );

            Tasks.await(Wearable.getChannelClient(this).sendFile(channel, Uri.fromFile(file)));
            Wearable.getChannelClient(this).close(channel);

            Log.d("Sender", "File sent successfully: " + file.getName());
            runOnUiThread(() -> Toast.makeText(this, "Sent Success!", Toast.LENGTH_SHORT).show());

        } catch (Exception e) {
            Log.e("Sender", "Error sending file", e);
            runOnUiThread(() -> Toast.makeText(this, "Send Failed", Toast.LENGTH_SHORT).show());
        }
    }

    private void registerSensors() {
        if (edaSensor != null) {
            sensorManager.registerListener(edaListener, edaSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    private final SensorEventListener edaListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == SENSOR_TYPE_EDA && event.values.length > 0) {
                float rawValue = event.values[0];
                float resistanceMilliOhms = 0.0f;
                float edaMicrosiemens = 0.0f;

                if (rawValue < 0) {
                    resistanceMilliOhms = Math.abs(rawValue);
                    if (resistanceMilliOhms > 1.0f) {
                        edaMicrosiemens = 1_000_000_000.0f / resistanceMilliOhms;
                    }
                } else {
                    edaMicrosiemens = 0.0f;
                    resistanceMilliOhms = rawValue;
                }

                edaValueTextView.setText(String.format(Locale.US, "%.2f", edaMicrosiemens));
                edaRawTextView.setText(String.format(Locale.US, "(Raw: %.0f mΩ)", rawValue));

                if (isRecording) {
                    if (csvWriter != null) {
                        try {
                            long currentTime = System.currentTimeMillis();
                            double elapsedSeconds = (currentTime - sessionStartTime) / 1000.0;
                            String line = String.format(Locale.US, "%d,%.3f,%.2f,%.2f\n",
                                    currentTime, elapsedSeconds, rawValue, edaMicrosiemens);
                            csvWriter.append(line);
                        } catch (IOException e) {
                            Log.e("CSV", "Write error", e);
                        }
                    }
                    sendRealTimeData(edaMicrosiemens);
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BODY_SENSORS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                registerSensors();
            } else {
                statusTextView.setText("Permission Denied");
                statusTextView.setTextColor(Color.RED);
            }
        }
    }

}