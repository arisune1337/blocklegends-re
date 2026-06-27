package net.blocklegends;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/* loaded from: classes3.dex */
public class FCMService extends FirebaseMessagingService {
    public static final String CHANNEL_ID = "blocklegends_notifications";
    public static final String CHANNEL_NAME = "BlockLegends Bildirimleri";
    private static final String TAG = "FCMService";
    public static volatile boolean isAppInForeground = false;
    private static int notificationId;

    private void createNotificationChannel() {
        try {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, 4);
            notificationChannel.setDescription("BlockLegends oyun bildirimleri");
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(-16776961);
            notificationChannel.enableVibration(true);
            notificationChannel.setVibrationPattern(new long[]{0, 250, 250, 250});
            notificationChannel.setShowBadge(true);
            NotificationManager notificationManager = (NotificationManager) getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(notificationChannel);
            }
        } catch (Exception e) {
            Log.e(TAG, "Bildirim kanalı oluşturulurken hata: " + e.getMessage(), e);
        }
    }

    private synchronized int getNextNotificationId() {
        int r0;
        r0 = notificationId;
        notificationId = r0 + 1;
        return r0;
    }

    private void handleAction(String str, Map<String, String> map) {
        str.hashCode();
        char c = 65535;
        switch (str.hashCode()) {
            case -838846263:
                if (str.equals("update")) {
                    c = 0;
                    break;
                }
                break;
            case 96891546:
                if (str.equals(NotificationCompat.CATEGORY_EVENT)) {
                    c = 1;
                    break;
                }
                break;
            case 1545944263:
                if (str.equals("open_game")) {
                    c = 2;
                    break;
                }
                break;
        }
        switch (c) {
            case 0:
                Log.d(TAG, "Action: Güncelleme kontrolü");
                return;
            case 1:
                Log.d(TAG, "Action: Etkinlik - " + map.get("event_id"));
                return;
            case 2:
                Log.d(TAG, "Action: Oyun açılıyor");
                return;
            default:
                Log.d(TAG, "Bilinmeyen action: " + str);
                return;
        }
    }

    private void handleDataMessage(Map<String, String> map) {
        try {
            String str = map.get("title");
            String str2 = map.get("body");
            String str3 = map.get("action");
            if (str != null && str2 != null && !isAppInForeground) {
                showNotification(str, str2, map);
            }
            if (str3 != null) {
                handleAction(str3, map);
            }
        } catch (Exception e) {
            Log.e(TAG, "Data mesajı işlenirken hata: " + e.getMessage(), e);
        }
    }

    private void showNotification(String str, String str2, Map<String, String> map) {
        Bitmap decodeResource;
        try {
            createNotificationChannel();
            Intent intent = new Intent(this, (Class<?>) MainActivity.class);
            intent.addFlags(805306368);
            if (map != null && !map.isEmpty()) {
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    intent.putExtra(entry.getKey(), entry.getValue());
                }
            }
            PendingIntent activity = PendingIntent.getActivity(this, notificationId, intent, 201326592);
            Uri defaultUri = RingtoneManager.getDefaultUri(2);
            try {
                Drawable applicationIcon = getPackageManager().getApplicationIcon(getApplicationInfo());
                int r3 = (int) (getResources().getDisplayMetrics().density * 64.0f);
                decodeResource = Bitmap.createBitmap(r3, r3, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(decodeResource);
                applicationIcon.setBounds(0, 0, r3, r3);
                applicationIcon.draw(canvas);
            } catch (Exception unused) {
                decodeResource = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            }
            NotificationCompat.Builder contentIntent = new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification_2).setLargeIcon(decodeResource).setContentTitle(str != null ? str : "BlockLegends").setContentText(str2 != null ? str2 : "").setAutoCancel(true).setSound(defaultUri).setPriority(1).setDefaults(-1).setContentIntent(activity);
            if (str2 != null && str2.length() > 50) {
                contentIntent.setStyle(new NotificationCompat.BigTextStyle().bigText(str2));
            }
            NotificationManager notificationManager = (NotificationManager) getSystemService("notification");
            if (notificationManager != null) {
                notificationManager.notify(getNextNotificationId(), contentIntent.build());
                Log.d(TAG, "Bildirim gösterildi: " + str);
            }
        } catch (Exception e) {
            Log.e(TAG, "Bildirim gösterilirken hata: " + e.getMessage(), e);
        }
    }

    @Override // com.google.firebase.messaging.FirebaseMessagingService
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Log.d(TAG, "Mesaj alındı: " + remoteMessage.getFrom());
        if (!remoteMessage.getData().isEmpty()) {
            Log.d(TAG, "Data payload: " + remoteMessage.getData());
            handleDataMessage(remoteMessage.getData());
        }
        if (remoteMessage.getNotification() == null || isAppInForeground) {
            return;
        }
        String title = remoteMessage.getNotification().getTitle();
        String body = remoteMessage.getNotification().getBody();
        Log.d(TAG, "Notification - Title: " + title + ", Body: " + body);
        showNotification(title, body, remoteMessage.getData());
    }

    @Override // com.google.firebase.messaging.FirebaseMessagingService
    public void onNewToken(String str) {
        super.onNewToken(str);
        Log.d(TAG, "Yeni FCM Token: " + str);
        MainApp.fcmTokenCached = str;
    }
}
