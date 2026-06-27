package net.blocklegends;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import androidx.credentials.provider.CredentialEntry;
import androidx.work.WorkRequest;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.google.firebase.messaging.FirebaseMessaging;
import com.tiktok.TikTokBusinessSdk;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.Thread;
import java.util.Locale;
import java.util.Properties;
import net.blocklegends.attribution.InstallReferrerTracker;
import net.blocklegends.natives.GNatives;

/* loaded from: classes4.dex */
public class MainApp extends Application {
    private static final String CONFIG_FILE = ".blocklegends_config.properties";
    private static final String FIREBASE_SESSIONS_DATASTORE_DIR = "datastore/firebaseSessions";
    private static final String TAG = "MainApp";
    private static volatile MainApp app;
    public static volatile String fcmTokenCached;
    private Properties configProperties;
    private static final String[] FIREBASE_SESSIONS_DATASTORE_FILES = {"sessionConfigsDataStore.data", "sessionDataStore.data"};
    private static final String[] FIREBASE_SESSIONS_COORDINATOR_SUFFIXES = {".lock", ".version"};
    private static volatile boolean appInitialized = false;
    private volatile boolean firebaseInitialized = false;
    private final Object configLock = new Object();
    private volatile boolean configLoaded = false;
    private volatile String fcmToken = null;

    private void cleanupStaleWebViewDirs(final String str) {
        new Thread(new Runnable() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                MainApp.this.m2104lambda$cleanupStaleWebViewDirs$12$netblocklegendsMainApp(str);
            }
        }, "WebView-DirCleanup").start();
    }

    private void createDefaultConfigUnsafe() {
        if (this.configProperties == null) {
            this.configProperties = new Properties();
        }
        this.configProperties.setProperty("vulkan_enabled", CredentialEntry.TRUE_STRING);
        this.configProperties.setProperty("config_version", "1");
        saveConfigUnsafe();
    }

    private static boolean deleteRecursive(File file) {
        File[] listFiles;
        if (file == null) {
            return false;
        }
        if (file.isDirectory() && (listFiles = file.listFiles()) != null) {
            for (File file2 : listFiles) {
                deleteRecursive(file2);
            }
        }
        return file.delete();
    }

    private boolean ensureDirectory(File file) {
        if (file == null) {
            return false;
        }
        if (file.isDirectory()) {
            return true;
        }
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Directory path file olarak var ve silinemedi: " + file);
            return false;
        }
        if (file.mkdirs() || file.isDirectory()) {
            return true;
        }
        Log.w(TAG, "Directory olusturulamadi: " + file);
        return false;
    }

    private void ensureFileExists(File file) {
        if (file == null) {
            return;
        }
        File parentFile = file.getParentFile();
        if ((parentFile == null || ensureDirectory(parentFile)) && !file.isFile()) {
            if (file.isDirectory() && !deleteRecursive(file)) {
                Log.w(TAG, "Coordinator file path dir olarak var ve silinemedi: " + file);
                return;
            }
            for (int r1 = 0; r1 < 2; r1++) {
                try {
                } catch (IOException e) {
                    if (r1 != 0 || parentFile == null) {
                        Log.w(TAG, "Coordinator file olusturulamadi: " + file + " / " + e.getMessage());
                        return;
                    }
                    ensureDirectory(parentFile);
                }
                if (file.createNewFile() || file.isFile()) {
                    return;
                }
            }
        }
    }

    public static MainApp getApp() {
        return app;
    }

    public static MainApp getAppSafe() {
        if (appInitialized && app != null) {
            return app;
        }
        Log.w(TAG, "getAppSafe called before app initialization");
        return null;
    }

    private void initializeFCM() {
        try {
            final FirebaseMessaging firebaseMessaging = FirebaseMessaging.getInstance();
            firebaseMessaging.getToken().addOnCompleteListener(new OnCompleteListener<String>() { // from class: net.blocklegends.MainApp.1
                @Override // com.google.android.gms.tasks.OnCompleteListener
                public void onComplete(Task<String> task) {
                    if (!task.isSuccessful()) {
                        Log.w(MainApp.TAG, "FCM token alınamadı", task.getException());
                        return;
                    }
                    String result = task.getResult();
                    MainApp.this.fcmToken = result;
                    if (result == null || result.length() <= 20) {
                        Log.d(MainApp.TAG, "FCM Token received (short)");
                    } else {
                        Log.d(MainApp.TAG, "FCM Token received: " + result.substring(0, 10) + "..." + result.substring(result.length() - 10));
                    }
                    if (MainApp.this.firebaseInitialized && result != null) {
                        try {
                            FirebaseCrashlytics.getInstance().setCustomKey("fcm_token_hash", String.valueOf(result.hashCode()));
                        } catch (Exception e) {
                            Log.e(MainApp.TAG, "FCM token Crashlytics'e kaydedilemedi: " + e.getMessage());
                        }
                    }
                    MainApp.fcmTokenCached = result;
                }
            });
            new Thread(new Runnable() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda10
                @Override // java.lang.Runnable
                public final void run() {
                    MainApp.lambda$initializeFCM$6(FirebaseMessaging.this);
                }
            }, "FCM-Topic-Sub").start();
            Log.i(TAG, "FCM başlatıldı");
        } catch (Exception e) {
            Log.e(TAG, "FCM başlatma hatası: " + e.getMessage(), e);
        }
    }

    private void initializeFirebase() {
        if (this.firebaseInitialized) {
            return;
        }
        synchronized (this) {
            if (this.firebaseInitialized) {
                return;
            }
            try {
                FirebaseApp.initializeApp(this);
                FirebaseCrashlytics firebaseCrashlytics = FirebaseCrashlytics.getInstance();
                if (firebaseCrashlytics != null) {
                    firebaseCrashlytics.setCrashlyticsCollectionEnabled(true);
                    firebaseCrashlytics.log("Crashlytics initialized in MainApp");
                    this.firebaseInitialized = true;
                    installFirebaseSafetyHandler();
                    Log.i(TAG, "Firebase initialized successfully");
                } else {
                    Log.w(TAG, "FirebaseCrashlytics instance is null");
                }
            } catch (Exception e) {
                Log.e(TAG, "Firebase initialization failed: " + e.getMessage(), e);
                this.firebaseInitialized = false;
            }
        }
    }

    private void installFirebaseSafetyHandler() {
        final Thread.UncaughtExceptionHandler defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda4
            @Override // java.lang.Thread.UncaughtExceptionHandler
            public final void uncaughtException(Thread thread, Throwable th) {
                MainApp.this.m2105lambda$installFirebaseSafetyHandler$11$netblocklegendsMainApp(defaultUncaughtExceptionHandler, thread, th);
            }
        });
    }

    public static boolean isAppInitialized() {
        return appInitialized && app != null;
    }

    private boolean isDataStoreCoordinatorCrash(Throwable th) {
        for (StackTraceElement stackTraceElement : th.getStackTrace()) {
            String className = stackTraceElement.getClassName();
            if (className.startsWith("androidx.datastore.core.MultiProcessCoordinator") || className.startsWith("androidx.datastore.core.SharedCounter")) {
                return true;
            }
        }
        return false;
    }

    private boolean isFirebaseInfrastructureCrash(Throwable th) {
        String message;
        if (th == null) {
            return false;
        }
        if ((th instanceof FileNotFoundException) && (message = th.getMessage()) != null && message.contains("/proc/self/task/") && message.contains("/comm")) {
            return true;
        }
        if (!(th instanceof IOException) || !isDataStoreCoordinatorCrash(th)) {
            return isFirebaseInfrastructureCrash(th.getCause());
        }
        String message2 = th.getMessage();
        return message2 == null || message2.contains("No such file or directory") || message2.contains("Unable to create parent directories") || message2.contains("Failed to create directory");
    }

    private static boolean isPidAlive(int r3) {
        return new File("/proc/" + r3).exists();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$initializeFCM$4(Task task) {
        if (task.isSuccessful()) {
            Log.d(TAG, "FCM 'all' topic'ine abone olundu");
        } else {
            Log.w(TAG, "FCM 'all' topic aboneliği başarısız", task.getException());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$initializeFCM$5(String str, Task task) {
        if (task.isSuccessful()) {
            Log.d(TAG, "FCM '" + str + "' topic'ine abone olundu");
        } else {
            Log.w(TAG, "FCM '" + str + "' topic aboneliği başarısız", task.getException());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$initializeFCM$6(FirebaseMessaging firebaseMessaging) {
        try {
            Thread.sleep(WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS);
            final String str = "lang_" + Locale.getDefault().getLanguage();
            firebaseMessaging.subscribeToTopic("all").addOnCompleteListener(new OnCompleteListener() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda12
                @Override // com.google.android.gms.tasks.OnCompleteListener
                public final void onComplete(Task task) {
                    MainApp.lambda$initializeFCM$4(task);
                }
            });
            firebaseMessaging.subscribeToTopic(str).addOnCompleteListener(new OnCompleteListener() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda13
                @Override // com.google.android.gms.tasks.OnCompleteListener
                public final void onComplete(Task task) {
                    MainApp.lambda$initializeFCM$5(str, task);
                }
            });
        } catch (InterruptedException unused) {
            Thread.currentThread().interrupt();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$subscribeToTopic$9(String str, Task task) {
        if (task.isSuccessful()) {
            Log.d(TAG, "FCM '" + str + "' topic'ine abone olundu");
        } else {
            Log.w(TAG, "FCM '" + str + "' topic aboneliği başarısız", task.getException());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$unsubscribeFromTopic$10(String str, Task task) {
        if (task.isSuccessful()) {
            Log.d(TAG, "FCM '" + str + "' topic aboneliği iptal edildi");
        } else {
            Log.w(TAG, "FCM '" + str + "' abonelik iptali başarısız", task.getException());
        }
    }

    private void loadConfig() {
        File filesDir;
        synchronized (this.configLock) {
            this.configProperties = new Properties();
            try {
                filesDir = getFilesDir();
            } catch (Exception e) {
                Log.e(TAG, "Config load failed, using defaults", e);
                createDefaultConfigUnsafe();
            }
            if (filesDir == null) {
                Log.e(TAG, "Files directory is null, using defaults");
                createDefaultConfigUnsafe();
                return;
            }
            File file = new File(filesDir, CONFIG_FILE);
            if (file.exists()) {
                try {
                    FileInputStream fileInputStream = new FileInputStream(file);
                    try {
                        this.configProperties.load(fileInputStream);
                        Log.i(TAG, "Config loaded from: " + file.getAbsolutePath());
                        fileInputStream.close();
                    } catch (Throwable th) {
                        try {
                            fileInputStream.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                        throw th;
                    }
                } catch (Exception e2) {
                    Log.e(TAG, "Config file corrupted, deleting and recreating", e2);
                    try {
                        file.delete();
                    } catch (Exception unused) {
                    }
                    this.configProperties = new Properties();
                    createDefaultConfigUnsafe();
                }
            } else {
                createDefaultConfigUnsafe();
                Log.i(TAG, "Default config created");
            }
        }
    }

    private void logToFirebase(String str, boolean z) {
        if (this.firebaseInitialized) {
            try {
                FirebaseCrashlytics firebaseCrashlytics = FirebaseCrashlytics.getInstance();
                if (firebaseCrashlytics != null) {
                    firebaseCrashlytics.setCustomKey(str, z);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to log to Firebase: " + e.getMessage());
            }
        }
    }

    private void prepareFirebaseSessionsDataStoreFiles() {
        try {
            File filesDir = getFilesDir();
            if (filesDir == null) {
                return;
            }
            File file = new File(filesDir, FIREBASE_SESSIONS_DATASTORE_DIR);
            if (ensureDirectory(file)) {
                for (String str : FIREBASE_SESSIONS_DATASTORE_FILES) {
                    File file2 = new File(file, str);
                    if (!file2.isDirectory() || deleteRecursive(file2)) {
                        for (String str2 : FIREBASE_SESSIONS_COORDINATOR_SUFFIXES) {
                            ensureFileExists(new File(file, str + str2));
                        }
                    } else {
                        Log.w(TAG, "Conflicting Firebase Sessions DataStore dir silinemedi: " + file2);
                    }
                }
            }
        } catch (Throwable th) {
            Log.w(TAG, "prepareFirebaseSessionsDataStoreFiles failed: " + th.getMessage());
        }
    }

    private static String sanitizeSuffix(String str) {
        StringBuilder sb = new StringBuilder(str.length());
        for (int r1 = 0; r1 < str.length(); r1++) {
            char charAt = str.charAt(r1);
            if ((charAt < 'A' || charAt > 'Z') && ((charAt < 'a' || charAt > 'z') && ((charAt < '0' || charAt > '9') && charAt != '_'))) {
                sb.append('_');
            } else {
                sb.append(charAt);
            }
        }
        return sb.length() == 0 ? "main" : sb.toString();
    }

    private void saveConfig() {
        synchronized (this.configLock) {
            saveConfigUnsafe();
        }
    }

    /* JADX WARN: Not initialized variable reg: 3, insn: 0x00d3: MOVE (r2 I:??[OBJECT, ARRAY]) = (r3 I:??[OBJECT, ARRAY]), block:B:69:0x00d3 */
    /* JADX WARN: Removed duplicated region for block: B:72:0x00d6 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void saveConfigUnsafe() {
        /*
            r8 = this;
            java.lang.String r0 = "MainApp"
            java.lang.String r1 = "Config saved atomically to: "
            r2 = 0
            java.io.File r3 = r8.getFilesDir()     // Catch: java.lang.Throwable -> Lb8 java.lang.Exception -> Lba
            if (r3 != 0) goto L11
            java.lang.String r8 = "Files directory is null, cannot save config"
            android.util.Log.e(r0, r8)     // Catch: java.lang.Throwable -> Lb8 java.lang.Exception -> Lba
            return
        L11:
            java.util.Properties r4 = r8.configProperties     // Catch: java.lang.Throwable -> Lb8 java.lang.Exception -> Lba
            if (r4 != 0) goto L1b
            java.lang.String r8 = "Config properties is null, cannot save"
            android.util.Log.e(r0, r8)     // Catch: java.lang.Throwable -> Lb8 java.lang.Exception -> Lba
            return
        L1b:
            java.io.File r4 = new java.io.File     // Catch: java.lang.Throwable -> Lb8 java.lang.Exception -> Lba
            java.lang.String r5 = ".blocklegends_config.properties"
            r4.<init>(r3, r5)     // Catch: java.lang.Throwable -> Lb8 java.lang.Exception -> Lba
            java.io.File r5 = new java.io.File     // Catch: java.lang.Throwable -> Lb8 java.lang.Exception -> Lba
            java.lang.String r6 = ".blocklegends_config.properties.tmp"
            r5.<init>(r3, r6)     // Catch: java.lang.Throwable -> Lb8 java.lang.Exception -> Lba
            java.io.FileOutputStream r3 = new java.io.FileOutputStream     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            r3.<init>(r5)     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            java.util.Properties r8 = r8.configProperties     // Catch: java.lang.Exception -> Lb2 java.lang.Throwable -> Ld2
            java.lang.String r6 = "BlockLegends Renderer Config"
            r8.store(r3, r6)     // Catch: java.lang.Exception -> Lb2 java.lang.Throwable -> Ld2
            java.io.FileDescriptor r8 = r3.getFD()     // Catch: java.lang.Exception -> Lb2 java.lang.Throwable -> Ld2
            r8.sync()     // Catch: java.lang.Exception -> Lb2 java.lang.Throwable -> Ld2
            r3.close()     // Catch: java.lang.Exception -> Lb2 java.lang.Throwable -> Ld2
            boolean r8 = r5.renameTo(r4)     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            if (r8 == 0) goto L5a
            java.lang.StringBuilder r8 = new java.lang.StringBuilder     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            r8.<init>(r1)     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            java.lang.String r1 = r4.getAbsolutePath()     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            java.lang.StringBuilder r8 = r8.append(r1)     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            java.lang.String r8 = r8.toString()     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            android.util.Log.i(r0, r8)     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            return
        L5a:
            java.io.FileInputStream r8 = new java.io.FileInputStream     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            r8.<init>(r5)     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            java.io.FileOutputStream r1 = new java.io.FileOutputStream     // Catch: java.lang.Throwable -> La8
            r1.<init>(r4)     // Catch: java.lang.Throwable -> La8
            r3 = 1024(0x400, float:1.435E-42)
            byte[] r3 = new byte[r3]     // Catch: java.lang.Throwable -> L9e
        L68:
            int r6 = r8.read(r3)     // Catch: java.lang.Throwable -> L9e
            if (r6 <= 0) goto L73
            r7 = 0
            r1.write(r3, r7, r6)     // Catch: java.lang.Throwable -> L9e
            goto L68
        L73:
            java.io.FileDescriptor r3 = r1.getFD()     // Catch: java.lang.Throwable -> L9e
            r3.sync()     // Catch: java.lang.Throwable -> L9e
            r1.close()     // Catch: java.lang.Throwable -> La8
            r8.close()     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            r5.delete()     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            java.lang.StringBuilder r8 = new java.lang.StringBuilder     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            r8.<init>()     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            java.lang.String r1 = "Config saved (fallback) to: "
            java.lang.StringBuilder r8 = r8.append(r1)     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            java.lang.String r1 = r4.getAbsolutePath()     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            java.lang.StringBuilder r8 = r8.append(r1)     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            java.lang.String r8 = r8.toString()     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            android.util.Log.i(r0, r8)     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
            return
        L9e:
            r3 = move-exception
            r1.close()     // Catch: java.lang.Throwable -> La3
            goto La7
        La3:
            r1 = move-exception
            r3.addSuppressed(r1)     // Catch: java.lang.Throwable -> La8
        La7:
            throw r3     // Catch: java.lang.Throwable -> La8
        La8:
            r1 = move-exception
            r8.close()     // Catch: java.lang.Throwable -> Lad
            goto Lb1
        Lad:
            r8 = move-exception
            r1.addSuppressed(r8)     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
        Lb1:
            throw r1     // Catch: java.lang.Exception -> Lb4 java.lang.Throwable -> Lb8
        Lb2:
            r8 = move-exception
            goto Lb6
        Lb4:
            r8 = move-exception
            r3 = r2
        Lb6:
            r2 = r5
            goto Lbc
        Lb8:
            r8 = move-exception
            goto Ld4
        Lba:
            r8 = move-exception
            r3 = r2
        Lbc:
            java.lang.String r1 = "Config save failed"
            android.util.Log.e(r0, r1, r8)     // Catch: java.lang.Throwable -> Ld2
            if (r2 == 0) goto Lcc
            boolean r8 = r2.exists()     // Catch: java.lang.Throwable -> Ld2
            if (r8 == 0) goto Lcc
            r2.delete()     // Catch: java.lang.Throwable -> Ld2
        Lcc:
            if (r3 == 0) goto Ld1
            r3.close()     // Catch: java.lang.Exception -> Ld1
        Ld1:
            return
        Ld2:
            r8 = move-exception
            r2 = r3
        Ld4:
            if (r2 == 0) goto Ld9
            r2.close()     // Catch: java.lang.Exception -> Ld9
        Ld9:
            throw r8
        */
        throw new UnsupportedOperationException("Method not decompiled: net.blocklegends.MainApp.saveConfigUnsafe():void");
    }

    public static void sendFCMTokenToServer() {
        String str = fcmTokenCached;
        if (str == null || str.isEmpty()) {
            Log.w(TAG, "FCM token bos, gonderilemedi");
            return;
        }
        if (str.length() > 20) {
            Log.d(TAG, "FCM Token native tarafa gonderiliyor: " + str.substring(0, 10) + "...");
        } else {
            Log.d(TAG, "FCM Token native tarafa gonderiliyor (short token)");
        }
        try {
            GNatives.onFCMTokenReceived(str);
            Log.d(TAG, "FCM Token native tarafa basariyla gonderildi");
        } catch (Exception e) {
            Log.e(TAG, "FCM token native tarafa gonderilemedi: " + e.getMessage());
        } catch (UnsatisfiedLinkError unused) {
            Log.w(TAG, "Native library henuz yuklenmedi, FCM token daha sonra gonderilecek");
        }
    }

    public String getConfigPath() {
        try {
            File filesDir = getFilesDir();
            if (filesDir != null) {
                return new File(filesDir, CONFIG_FILE).getAbsolutePath();
            }
            Log.e(TAG, "Files directory is null in getConfigPath");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting config path: " + e.getMessage());
            return null;
        }
    }

    public String getFCMToken() {
        return this.fcmToken;
    }

    public boolean isVulkanEnabled() {
        synchronized (this.configLock) {
            try {
                try {
                    Properties properties = this.configProperties;
                    if (properties == null) {
                        Log.w(TAG, "Config properties is null in isVulkanEnabled, returning default true");
                        return true;
                    }
                    return Boolean.parseBoolean(properties.getProperty("vulkan_enabled", CredentialEntry.TRUE_STRING));
                } catch (Exception e) {
                    Log.e(TAG, "Error reading Vulkan config: " + e.getMessage());
                    return true;
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$cleanupStaleWebViewDirs$12$net-blocklegends-MainApp, reason: not valid java name */
    public /* synthetic */ void m2104lambda$cleanupStaleWebViewDirs$12$netblocklegendsMainApp(String str) {
        try {
            Thread.sleep(60000L);
            String str2 = getApplicationInfo().dataDir;
            if (str2 == null) {
                return;
            }
            File file = new File(str2);
            if (file.isDirectory()) {
                String str3 = "app_webview_" + str;
                File[] listFiles = file.listFiles();
                if (listFiles == null) {
                    return;
                }
                for (File file2 : listFiles) {
                    if (file2.isDirectory()) {
                        String name = file2.getName();
                        if (name.startsWith("app_webview_p") && !name.equals(str3)) {
                            try {
                                if (!isPidAlive(Integer.parseInt(name.substring("app_webview_p".length()))) && deleteRecursive(file2)) {
                                    Log.i(TAG, "Stale WebView dir cleaned: " + name);
                                }
                            } catch (NumberFormatException unused) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable th) {
            Log.w(TAG, "cleanupStaleWebViewDirs failed: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$installFirebaseSafetyHandler$11$net-blocklegends-MainApp, reason: not valid java name */
    public /* synthetic */ void m2105lambda$installFirebaseSafetyHandler$11$netblocklegendsMainApp(Thread.UncaughtExceptionHandler uncaughtExceptionHandler, Thread thread, Throwable th) {
        if (isFirebaseInfrastructureCrash(th)) {
            Log.w(TAG, "Firebase infrastructure crash suppressed on " + thread.getName() + ": " + th);
        } else if (uncaughtExceptionHandler != null) {
            uncaughtExceptionHandler.uncaughtException(thread, th);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$onCreate$0$net-blocklegends-MainApp, reason: not valid java name */
    public /* synthetic */ void m2106lambda$onCreate$0$netblocklegendsMainApp() {
        try {
            TikTokBusinessSdk.initializeSdk(new TikTokBusinessSdk.TTConfig(this, "TTaLmfWUaDBm8ZZbPSFhse4pMu2vZ3ta").setAppId("net.blocklegends").setTTAppId("7623335695234498567").enableAutoIapTrack());
            Log.i(TAG, "TikTok SDK initialized (deferred)");
        } catch (Throwable th) {
            Log.e(TAG, "TikTok SDK init failed: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$onCreate$1$net-blocklegends-MainApp, reason: not valid java name */
    public /* synthetic */ void m2107lambda$onCreate$1$netblocklegendsMainApp() {
        try {
            prepareFirebaseSessionsDataStoreFiles();
            initializeFirebase();
            if (this.configLoaded) {
                Log.i(TAG, "Vulkan Status: ".concat(isVulkanEnabled() ? "ENABLED" : "DISABLED"));
                logToFirebase("vulkan_enabled", isVulkanEnabled());
            }
            initializeFCM();
        } catch (Exception e) {
            Log.e(TAG, "Background Firebase init failed", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$onCreate$3$net-blocklegends-MainApp, reason: not valid java name */
    public /* synthetic */ void m2108lambda$onCreate$3$netblocklegendsMainApp() {
        try {
            Class.forName("android.webkit.WebView");
            Class.forName("android.webkit.WebViewClient");
            WebView.startSafeBrowsing(this, new ValueCallback() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda11
                @Override // android.webkit.ValueCallback
                public final void onReceiveValue(Object obj) {
                    Log.i(MainApp.TAG, "WebView Chromium provider warmed: " + ((Boolean) obj));
                }
            });
        } catch (Throwable th) {
            Log.w(TAG, "WebView pre-warm failed: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$onTrimMemory$13$net-blocklegends-MainApp, reason: not valid java name */
    public /* synthetic */ void m2109lambda$onTrimMemory$13$netblocklegendsMainApp(int r2) {
        try {
            super.onTrimMemory(r2);
        } catch (Throwable th) {
            Log.w(TAG, "onTrimMemory error: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$refreshFCMToken$7$net-blocklegends-MainApp, reason: not valid java name */
    public /* synthetic */ void m2110lambda$refreshFCMToken$7$netblocklegendsMainApp() {
        try {
            initializeFCM();
        } catch (Exception e) {
            Log.e(TAG, "FCM reinit failed: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$refreshFCMToken$8$net-blocklegends-MainApp, reason: not valid java name */
    public /* synthetic */ void m2111lambda$refreshFCMToken$8$netblocklegendsMainApp(Task task) {
        if (!task.isSuccessful()) {
            Log.w(TAG, "FCM token silinemedi", task.getException());
        } else {
            Log.d(TAG, "Eski FCM token silindi, yeni token alınıyor...");
            new Thread(new Runnable() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    MainApp.this.m2110lambda$refreshFCMToken$7$netblocklegendsMainApp();
                }
            }, "FCM-Refresh-Thread").start();
        }
    }

    @Override // android.app.Application
    public void onCreate() {
        super.onCreate();
        app = this;
        try {
            String processName = getProcessName();
            String sanitizeSuffix = (processName == null || processName.equals(getPackageName())) ? "p" + Process.myPid() : sanitizeSuffix(processName);
            WebView.setDataDirectorySuffix(sanitizeSuffix);
            cleanupStaleWebViewDirs(sanitizeSuffix);
        } catch (Exception e) {
            Log.e(TAG, "WebView setDataDirectorySuffix failed: " + e.getMessage());
        }
        installFirebaseSafetyHandler();
        prepareFirebaseSessionsDataStoreFiles();
        loadConfig();
        this.configLoaded = true;
        appInitialized = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                MainApp.this.m2106lambda$onCreate$0$netblocklegendsMainApp();
            }
        }, 8000L);
        InstallReferrerTracker.capture(this);
        new Thread(new Runnable() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda5
            @Override // java.lang.Runnable
            public final void run() {
                MainApp.this.m2107lambda$onCreate$1$netblocklegendsMainApp();
            }
        }, "Firebase-Init-Thread").start();
        new Thread(new Runnable() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                MainApp.this.m2108lambda$onCreate$3$netblocklegendsMainApp();
            }
        }, "WebView-Warmup").start();
    }

    @Override // android.app.Application, android.content.ComponentCallbacks2
    public void onTrimMemory(final int r3) {
        if (r3 == 20 || r3 >= 40) {
            new Thread(new Runnable() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    MainApp.this.m2109lambda$onTrimMemory$13$netblocklegendsMainApp(r3);
                }
            }, "TrimMemory").start();
        } else {
            super.onTrimMemory(r3);
        }
    }

    public void refreshFCMToken() {
        try {
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener(new OnCompleteListener() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda2
                @Override // com.google.android.gms.tasks.OnCompleteListener
                public final void onComplete(Task task) {
                    MainApp.this.m2111lambda$refreshFCMToken$8$netblocklegendsMainApp(task);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "FCM token yenileme hatası: " + e.getMessage(), e);
        }
    }

    public void reloadConfig() {
        try {
            loadConfig();
            Log.i(TAG, "Config reloaded");
        } catch (Exception e) {
            Log.e(TAG, "Error reloading config: " + e.getMessage(), e);
        }
    }

    public void setVulkanEnabled(boolean z) {
        synchronized (this.configLock) {
            try {
                if (this.configProperties == null) {
                    Log.e(TAG, "Config properties is null in setVulkanEnabled");
                    this.configProperties = new Properties();
                }
                this.configProperties.setProperty("vulkan_enabled", String.valueOf(z));
                saveConfigUnsafe();
                Log.i(TAG, "Vulkan ".concat(z ? "enabled" : "disabled"));
                logToFirebase("vulkan_enabled", z);
            } catch (Exception e) {
                Log.e(TAG, "Error setting Vulkan config: " + e.getMessage(), e);
            }
        }
    }

    public void subscribeToTopic(final String str) {
        if (str == null || str.isEmpty()) {
            Log.w(TAG, "subscribeToTopic: topic null veya bos");
            return;
        }
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(str).addOnCompleteListener(new OnCompleteListener() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda8
                @Override // com.google.android.gms.tasks.OnCompleteListener
                public final void onComplete(Task task) {
                    MainApp.lambda$subscribeToTopic$9(str, task);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Topic abonelik hatası: " + e.getMessage(), e);
        }
    }

    public void unsubscribeFromTopic(final String str) {
        if (str == null || str.isEmpty()) {
            Log.w(TAG, "unsubscribeFromTopic: topic null veya bos");
            return;
        }
        try {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(str).addOnCompleteListener(new OnCompleteListener() { // from class: net.blocklegends.MainApp$$ExternalSyntheticLambda9
                @Override // com.google.android.gms.tasks.OnCompleteListener
                public final void onComplete(Task task) {
                    MainApp.lambda$unsubscribeFromTopic$10(str, task);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Topic abonelik iptal hatası: " + e.getMessage(), e);
        }
    }
}
