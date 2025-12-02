package com.example.hr_eda_measure_average;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

public class BackgroundService extends Service {

    private static final String CHANNEL_ID = "SensorServiceChannel";
    private boolean isMeasuring = false;
    private String user = "P1";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sensor Service")
                .setContentText("Running...")
                .build();
        startForeground(1, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            isMeasuring = intent.getBooleanExtra("isMeasuring", false);
            user = intent.getStringExtra("User");
            if (user == null || user.isEmpty()) {
                user = "DefaultUser";
            }

            if (isMeasuring) {
                String type = intent.getStringExtra("type");
                float value = intent.getFloatExtra("value", -1f);
                if (type != null && value != -1f) {
                    uploadData(type, value, user);
                } else {
                    Log.e("BackgroundService", "Type or value is null");
                }
            } else {
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Sensor Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void uploadData(String type, float value, String user) {
        Map<String, Object> data = new HashMap<>();
        data.put("TimeStamp", System.currentTimeMillis());
        data.put("Value", value);

        FirebaseDatabase database = FirebaseDatabase.getInstance("https://nedo-esports-eda-default-rtdb.firebaseio.com/");
        DatabaseReference reference = database.getReference(user).child("Data").child(type);

        reference.setValue(data)
                .addOnSuccessListener(aVoid -> Log.d("BackgroundService", "Data uploaded successfully"))
                .addOnFailureListener(e -> Log.e("BackgroundService", "Failed to upload data", e));
    }
}