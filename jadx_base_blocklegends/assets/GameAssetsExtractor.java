package net.blocklegends.assets;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.StatFs;
import android.util.Log;
import androidx.credentials.exceptions.publickeycredential.DomExceptionUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/* loaded from: classes3.dex */
public final class GameAssetsExtractor {
    private static final String ASSETS_DIR_NAME = "game_data";
    private static final String ASSET_PACK_NAME = "game_assets";
    private static final int BUFFER_SIZE = 8192;
    private static final String KEY_EXTRACTED_VERSION = "extracted_version";
    private static final long MIN_REQUIRED_SPACE_MB = 150;
    private static final String PREFS_NAME = "game_assets_prefs";
    private static final String TAG = "GameAssetsExtractor";
    private static final Object LOCK = new Object();
    private static final AtomicBoolean isExtracting = new AtomicBoolean(false);
    private static volatile String extractedPath = null;
    private static volatile boolean cancellationRequested = false;

    /* loaded from: classes3.dex */
    public enum ErrorCode {
        INSUFFICIENT_STORAGE,
        PERMISSION_DENIED,
        IO_ERROR,
        ASSET_PACK_ERROR,
        CANCELLED,
        UNKNOWN
    }

    /* loaded from: classes3.dex */
    public interface ExtractionCallback {
        void onComplete(String str);

        void onError(ErrorCode errorCode, String str);

        void onProgress(int r1, int r2, String str);
    }

    private static void copyApkAsset(AssetManager assetManager, String str, File file) throws IOException {
        byte[] bArr = new byte[8192];
        InputStream open = assetManager.open(str);
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            while (true) {
                try {
                    int read = open.read(bArr);
                    if (read == -1) {
                        break;
                    } else {
                        fileOutputStream.write(bArr, 0, read);
                    }
                } finally {
                }
            }
            fileOutputStream.getFD().sync();
            fileOutputStream.close();
            if (open != null) {
                open.close();
            }
        } catch (Throwable th) {
            if (open != null) {
                try {
                    open.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private static void copyDirectory(File file, File file2, ExtractionCallback extractionCallback, int[] r10, int r11) throws IOException {
        if (isCancelled()) {
            throw new IOException("Extraction cancelled");
        }
        File[] listFiles = file.listFiles();
        if (listFiles == null) {
            return;
        }
        for (File file3 : listFiles) {
            if (isCancelled()) {
                throw new IOException("Extraction cancelled");
            }
            File file4 = new File(file2, file3.getName());
            if (!file3.isDirectory()) {
                copyFile(file3, file4);
                int r5 = r10[0] + 1;
                r10[0] = r5;
                if (extractionCallback != null) {
                    extractionCallback.onProgress(r5, r11, file3.getName());
                }
            } else {
                if (!file4.mkdirs() && !file4.exists()) {
                    throw new IOException("Failed to create directory: " + file4);
                }
                copyDirectory(file3, file4, extractionCallback, r10, r11);
            }
        }
    }

    private static void copyFile(File file, File file2) throws IOException {
        byte[] bArr = new byte[8192];
        FileInputStream fileInputStream = new FileInputStream(file);
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file2);
            while (true) {
                try {
                    int read = fileInputStream.read(bArr);
                    if (read == -1) {
                        fileOutputStream.getFD().sync();
                        fileOutputStream.close();
                        fileInputStream.close();
                        return;
                    }
                    fileOutputStream.write(bArr, 0, read);
                } finally {
                }
            }
        } catch (Throwable th) {
            try {
                fileInputStream.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    private static int countApkAssets(AssetManager assetManager, String str) {
        int r0 = 0;
        try {
            String[] list = assetManager.list(str);
            if (list != null && list.length != 0) {
                int length = list.length;
                int r3 = 0;
                while (r0 < length) {
                    try {
                        String str2 = list[r0];
                        if (!str.isEmpty()) {
                            str2 = str + DomExceptionUtils.SEPARATOR + str2;
                        }
                        String[] list2 = assetManager.list(str2);
                        r3 = (list2 == null || list2.length <= 0) ? r3 + 1 : r3 + countApkAssets(assetManager, str2);
                        r0++;
                    } catch (IOException unused) {
                        r0 = r3;
                        Log.w(TAG, "Error counting APK assets at path: " + str);
                        return r0;
                    }
                }
                return r3;
            }
            return !str.isEmpty() ? 1 : 0;
        } catch (IOException unused2) {
        }
    }

    private static int countFiles(File file) {
        File[] listFiles = file.listFiles();
        if (listFiles == null) {
            return 0;
        }
        int r2 = 0;
        for (File file2 : listFiles) {
            r2 = file2.isDirectory() ? r2 + countFiles(file2) : r2 + 1;
        }
        return r2;
    }

    private static void deleteRecursive(File file) {
        File[] listFiles;
        if (file == null) {
            return;
        }
        if (file.isDirectory() && (listFiles = file.listFiles()) != null) {
            for (File file2 : listFiles) {
                deleteRecursive(file2);
            }
        }
        boolean z = false;
        for (int r1 = 0; r1 < 3 && !z; r1++) {
            z = file.delete();
            if (!z && r1 < 2) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException unused) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (z || !file.exists()) {
            return;
        }
        Log.w(TAG, "Failed to delete after retries: " + file.getAbsolutePath());
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:100:0x039e A[Catch: Exception -> 0x03a4, IOException -> 0x03a6, TRY_LEAVE, TryCatch #12 {IOException -> 0x03a6, Exception -> 0x03a4, blocks: (B:73:0x0210, B:75:0x022f, B:77:0x0241, B:79:0x027d, B:81:0x0288, B:84:0x028f, B:85:0x0296, B:86:0x0297, B:88:0x0350, B:90:0x0356, B:92:0x0363, B:93:0x0366, B:95:0x0379, B:97:0x037c, B:100:0x039e, B:103:0x0247, B:105:0x0262, B:107:0x0274, B:109:0x027a), top: B:69:0x01ff }] */
    /* JADX WARN: Removed duplicated region for block: B:103:0x0247 A[Catch: Exception -> 0x03a4, IOException -> 0x03a6, TryCatch #12 {IOException -> 0x03a6, Exception -> 0x03a4, blocks: (B:73:0x0210, B:75:0x022f, B:77:0x0241, B:79:0x027d, B:81:0x0288, B:84:0x028f, B:85:0x0296, B:86:0x0297, B:88:0x0350, B:90:0x0356, B:92:0x0363, B:93:0x0366, B:95:0x0379, B:97:0x037c, B:100:0x039e, B:103:0x0247, B:105:0x0262, B:107:0x0274, B:109:0x027a), top: B:69:0x01ff }] */
    /* JADX WARN: Removed duplicated region for block: B:113:0x03e4  */
    /* JADX WARN: Removed duplicated region for block: B:116:0x03f9  */
    /* JADX WARN: Removed duplicated region for block: B:133:0x0445  */
    /* JADX WARN: Removed duplicated region for block: B:138:0x03e9  */
    /* JADX WARN: Removed duplicated region for block: B:142:0x03c4  */
    /* JADX WARN: Removed duplicated region for block: B:58:0x01d2 A[Catch: IOException -> 0x0082, Exception -> 0x03b0, TryCatch #0 {Exception -> 0x03b0, blocks: (B:8:0x00a3, B:10:0x00bd, B:12:0x00c3, B:14:0x00c9, B:16:0x00db, B:19:0x00e1, B:21:0x00e7, B:23:0x00ee, B:26:0x00f8, B:28:0x00fe, B:30:0x0104, B:31:0x0109, B:33:0x012d, B:35:0x0134, B:38:0x013c, B:40:0x014a, B:42:0x0162, B:45:0x016a, B:47:0x018a, B:50:0x0191, B:52:0x01b4, B:56:0x01cc, B:58:0x01d2, B:59:0x01d5, B:61:0x01db, B:64:0x01e2, B:65:0x01f6, B:152:0x01bd, B:154:0x01c3, B:158:0x009d), top: B:157:0x009d }] */
    /* JADX WARN: Removed duplicated region for block: B:61:0x01db A[Catch: IOException -> 0x0082, Exception -> 0x03b0, TryCatch #0 {Exception -> 0x03b0, blocks: (B:8:0x00a3, B:10:0x00bd, B:12:0x00c3, B:14:0x00c9, B:16:0x00db, B:19:0x00e1, B:21:0x00e7, B:23:0x00ee, B:26:0x00f8, B:28:0x00fe, B:30:0x0104, B:31:0x0109, B:33:0x012d, B:35:0x0134, B:38:0x013c, B:40:0x014a, B:42:0x0162, B:45:0x016a, B:47:0x018a, B:50:0x0191, B:52:0x01b4, B:56:0x01cc, B:58:0x01d2, B:59:0x01d5, B:61:0x01db, B:64:0x01e2, B:65:0x01f6, B:152:0x01bd, B:154:0x01c3, B:158:0x009d), top: B:157:0x009d }] */
    /* JADX WARN: Removed duplicated region for block: B:71:0x0201 A[Catch: Exception -> 0x03a8, IOException -> 0x03ac, TRY_LEAVE, TryCatch #11 {IOException -> 0x03ac, Exception -> 0x03a8, blocks: (B:68:0x01fb, B:71:0x0201), top: B:67:0x01fb }] */
    /* JADX WARN: Removed duplicated region for block: B:81:0x0288 A[Catch: Exception -> 0x03a4, IOException -> 0x03a6, TryCatch #12 {IOException -> 0x03a6, Exception -> 0x03a4, blocks: (B:73:0x0210, B:75:0x022f, B:77:0x0241, B:79:0x027d, B:81:0x0288, B:84:0x028f, B:85:0x0296, B:86:0x0297, B:88:0x0350, B:90:0x0356, B:92:0x0363, B:93:0x0366, B:95:0x0379, B:97:0x037c, B:100:0x039e, B:103:0x0247, B:105:0x0262, B:107:0x0274, B:109:0x027a), top: B:69:0x01ff }] */
    /* JADX WARN: Removed duplicated region for block: B:88:0x0350 A[Catch: Exception -> 0x03a4, IOException -> 0x03a6, TryCatch #12 {IOException -> 0x03a6, Exception -> 0x03a4, blocks: (B:73:0x0210, B:75:0x022f, B:77:0x0241, B:79:0x027d, B:81:0x0288, B:84:0x028f, B:85:0x0296, B:86:0x0297, B:88:0x0350, B:90:0x0356, B:92:0x0363, B:93:0x0366, B:95:0x0379, B:97:0x037c, B:100:0x039e, B:103:0x0247, B:105:0x0262, B:107:0x0274, B:109:0x027a), top: B:69:0x01ff }] */
    /* JADX WARN: Removed duplicated region for block: B:92:0x0363 A[Catch: Exception -> 0x03a4, IOException -> 0x03a6, TryCatch #12 {IOException -> 0x03a6, Exception -> 0x03a4, blocks: (B:73:0x0210, B:75:0x022f, B:77:0x0241, B:79:0x027d, B:81:0x0288, B:84:0x028f, B:85:0x0296, B:86:0x0297, B:88:0x0350, B:90:0x0356, B:92:0x0363, B:93:0x0366, B:95:0x0379, B:97:0x037c, B:100:0x039e, B:103:0x0247, B:105:0x0262, B:107:0x0274, B:109:0x027a), top: B:69:0x01ff }] */
    /* JADX WARN: Removed duplicated region for block: B:98:0x0365  */
    /* JADX WARN: Type inference failed for: r9v1, types: [java.lang.String] */
    /* JADX WARN: Type inference failed for: r9v10 */
    /* JADX WARN: Type inference failed for: r9v11 */
    /* JADX WARN: Type inference failed for: r9v2 */
    /* JADX WARN: Type inference failed for: r9v3 */
    /* JADX WARN: Type inference failed for: r9v4 */
    /* JADX WARN: Type inference failed for: r9v5 */
    /* JADX WARN: Type inference failed for: r9v6 */
    /* JADX WARN: Type inference failed for: r9v9 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static boolean doExtraction(android.content.Context r29, net.blocklegends.assets.GameAssetsExtractor.ExtractionCallback r30) {
        /*
            Method dump skipped, instructions count: 1097
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: net.blocklegends.assets.GameAssetsExtractor.doExtraction(android.content.Context, net.blocklegends.assets.GameAssetsExtractor$ExtractionCallback):boolean");
    }

    private static void extractApkAssets(AssetManager assetManager, String str, File file, ExtractionCallback extractionCallback, int[] r15, int r16) throws IOException {
        if (isCancelled()) {
            throw new IOException("Extraction cancelled");
        }
        String[] list = assetManager.list(str);
        if (list == null || list.length == 0) {
            return;
        }
        for (String str2 : list) {
            if (isCancelled()) {
                throw new IOException("Extraction cancelled");
            }
            String str3 = str.isEmpty() ? str2 : str + DomExceptionUtils.SEPARATOR + str2;
            Thread.yield();
            String[] list2 = assetManager.list(str3);
            if (list2 == null || list2.length <= 0) {
                copyApkAsset(assetManager, str3, new File(file, str2));
                int r2 = r15[0] + 1;
                r15[0] = r2;
                if (extractionCallback != null) {
                    extractionCallback.onProgress(r2, r16, str2);
                }
            } else {
                String str4 = str3;
                File file2 = new File(file, str2);
                if (!file2.mkdirs() && !file2.exists()) {
                    throw new IOException("Failed to create directory: " + file2);
                }
                extractApkAssets(assetManager, str4, file2, extractionCallback, r15, r16);
            }
        }
    }

    public static boolean extractIfNeeded(Context context) {
        return extractIfNeeded(context, null);
    }

    public static boolean extractIfNeeded(Context context, ExtractionCallback extractionCallback) {
        AtomicBoolean atomicBoolean = isExtracting;
        if (atomicBoolean.compareAndSet(false, true)) {
            try {
                resetCancellation();
                boolean doExtraction = doExtraction(context, extractionCallback);
                atomicBoolean.set(false);
                Object obj = LOCK;
                synchronized (obj) {
                    obj.notifyAll();
                }
                return doExtraction;
            } catch (Throwable th) {
                isExtracting.set(false);
                Object obj2 = LOCK;
                synchronized (obj2) {
                    obj2.notifyAll();
                    throw th;
                }
            }
        }
        Log.w(TAG, "Extraction already in progress, waiting...");
        synchronized (LOCK) {
            while (isExtracting.get()) {
                try {
                    LOCK.wait(100L);
                } catch (InterruptedException unused) {
                    Thread.currentThread().interrupt();
                    if (extractionCallback != null) {
                        extractionCallback.onError(ErrorCode.UNKNOWN, "Interrupted while waiting for extraction");
                    }
                    return false;
                }
            }
        }
        if (extractedPath == null) {
            return false;
        }
        if (extractionCallback != null) {
            extractionCallback.onComplete(extractedPath);
        }
        return true;
    }

    private static long getAvailableSpaceMB(File file) {
        try {
            StatFs statFs = new StatFs(file.getPath());
            return (statFs.getAvailableBlocksLong() * statFs.getBlockSizeLong()) / 1048576;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get available space", e);
            return Long.MAX_VALUE;
        }
    }

    public static String getExtractedPath() {
        return extractedPath;
    }

    private static boolean isCancelled() {
        return cancellationRequested;
    }

    private static boolean isStorageWritable(File file) {
        if (file != null && file.exists()) {
            File file2 = new File(file, ".write_test_" + System.currentTimeMillis());
            try {
                if (!file2.createNewFile()) {
                    return false;
                }
                file2.delete();
                return true;
            } catch (IOException e) {
                Log.w(TAG, "Storage write test failed: " + e.getMessage());
            }
        }
        return false;
    }

    private static void logDirectoryStructure(File file, int r8, int r9) {
        if (file == null || !file.exists() || r8 > r9) {
            return;
        }
        String str = "";
        for (int r2 = 0; r2 < r8; r2++) {
            str = str + "  ";
        }
        File[] listFiles = file.listFiles();
        if (listFiles == null) {
            return;
        }
        for (File file2 : listFiles) {
            if (file2.isDirectory()) {
                Log.d(TAG, str + "[DIR] " + file2.getName());
                logDirectoryStructure(file2, r8 + 1, r9);
            } else {
                Log.d(TAG, str + "[FILE] " + file2.getName());
            }
        }
    }

    public static void requestCancellation() {
        cancellationRequested = true;
        Log.i(TAG, "Extraction cancellation requested");
    }

    private static void resetCancellation() {
        cancellationRequested = false;
    }
}
