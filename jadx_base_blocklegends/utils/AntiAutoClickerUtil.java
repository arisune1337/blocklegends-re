package net.blocklegends.utils;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import com.tiktok.appevents.edp.TTEDPEventConstants;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/* loaded from: classes3.dex */
public final class AntiAutoClickerUtil {
    private static final List<String> SHIZUKU_PACKAGES = Arrays.asList("moe.shizuku.privileged.api", "moe.shizuku.manager", "moe.shizuku.api");
    private static final List<String> ROOT_MANAGER_PACKAGES = Arrays.asList("com.topjohnwu.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser", "com.noshufou.android.su");
    private static final List<String> KNOWN_AUTOCLICKER_PACKAGES = Arrays.asList("com.panda.touch", "com.zjx.gamebox", "com.easytouch.assistivetouch", "com.zjx.ztezscreenshot", "com.autoclicker.clicker", "com.truongauto.autoclicker", "com.kok_autoclicker", "com.auto.clicker", "com.maxwell.autoclick");
    private static final List<String> LSPOSED_PACKAGES = Arrays.asList("org.lsposed.manager", "org.lsposed.lspd");
    private static long lastCheckAtMs = 0;
    private static DetectionResult lastResult = new DetectionResult(EnumSet.noneOf(Reason.class), new HashSet());

    /* loaded from: classes4.dex */
    public static final class DetectionResult {
        public final boolean detected;
        public final Set<String> hitPackages;
        public final EnumSet<Reason> reasons;

        DetectionResult(EnumSet<Reason> enumSet, Set<String> set) {
            this.reasons = enumSet;
            this.detected = !enumSet.isEmpty();
            this.hitPackages = set;
        }

        public String toString() {
            return "detected=" + this.detected + " reasons=" + this.reasons + " hits=" + this.hitPackages;
        }
    }

    /* loaded from: classes4.dex */
    public enum Reason {
        SHIZUKU_PACKAGE,
        ROOT_PACKAGE,
        KNOWN_AUTOCLICKER_PACKAGE,
        SUSPICIOUS_ACCESSIBILITY_ENABLED,
        XPOSED_PRESENT,
        LSPOSED_PACKAGE,
        FRIDA_SUSPECTED,
        TEST_KEYS_BUILD,
        SU_BINARY_FOUND,
        MONKEY_OR_TESTHARNESS
    }

    private AntiAutoClickerUtil() {
    }

    private static boolean anyPackageInstalled(Context context, List<String> list, Set<String> set) {
        PackageManager packageManager = context.getPackageManager();
        for (String str : list) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    if (packageManager.getPackageInfo(str, PackageManager.PackageInfoFlags.of(0L)) != null) {
                        set.add(str);
                        return true;
                    }
                } else if (packageManager.getPackageInfo(str, 0) != null) {
                    set.add(str);
                    return true;
                }
            } catch (Throwable unused) {
            }
        }
        return false;
    }

    private static boolean classExists(String str) {
        try {
            Class.forName(str, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (Throwable unused) {
            return false;
        }
    }

    private static boolean containsAutoClickHint(String str, String str2, CharSequence charSequence) {
        StringBuilder append = new StringBuilder().append(str).append(" ");
        if (str2 == null) {
            str2 = "";
        }
        StringBuilder append2 = append.append(str2).append(" ");
        if (charSequence == null) {
            charSequence = "";
        }
        String lowerCase = append2.append((Object) charSequence).toString().toLowerCase();
        return (lowerCase.contains("auto") && lowerCase.contains(TTEDPEventConstants.EDP_EVENT_NAME_CLICK)) || lowerCase.contains("autoclick") || lowerCase.contains("autoclicker") || lowerCase.contains("macro") || lowerCase.contains("tap autom");
    }

    public static DetectionResult detect(Context context) {
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - lastCheckAtMs < 60000) {
            return lastResult;
        }
        EnumSet noneOf = EnumSet.noneOf(Reason.class);
        HashSet hashSet = new HashSet();
        if (anyPackageInstalled(context, SHIZUKU_PACKAGES, hashSet)) {
            noneOf.add(Reason.SHIZUKU_PACKAGE);
        }
        if (anyPackageInstalled(context, ROOT_MANAGER_PACKAGES, hashSet)) {
            noneOf.add(Reason.ROOT_PACKAGE);
        }
        List<String> list = KNOWN_AUTOCLICKER_PACKAGES;
        if (anyPackageInstalled(context, list, hashSet)) {
            noneOf.add(Reason.KNOWN_AUTOCLICKER_PACKAGE);
        }
        if (anyPackageInstalled(context, LSPOSED_PACKAGES, hashSet)) {
            noneOf.add(Reason.LSPOSED_PACKAGE);
        }
        if (isSuspiciousAccessibilityEnabled(context, list, hashSet)) {
            noneOf.add(Reason.SUSPICIOUS_ACCESSIBILITY_ENABLED);
        }
        if (classExists("de.robv.android.xposed.XposedBridge")) {
            noneOf.add(Reason.XPOSED_PRESENT);
        }
        if (looksLikeFridaPresent()) {
            noneOf.add(Reason.FRIDA_SUSPECTED);
        }
        if (Build.TAGS != null && Build.TAGS.toLowerCase().contains("test-keys")) {
            noneOf.add(Reason.TEST_KEYS_BUILD);
        }
        if (hasSuBinary()) {
            noneOf.add(Reason.SU_BINARY_FOUND);
        }
        if (ActivityManager.isUserAMonkey() || (Build.VERSION.SDK_INT >= 30 && ActivityManager.isRunningInUserTestHarness())) {
            noneOf.add(Reason.MONKEY_OR_TESTHARNESS);
        }
        DetectionResult detectionResult = new DetectionResult(noneOf, hashSet);
        lastResult = detectionResult;
        lastCheckAtMs = currentTimeMillis;
        return detectionResult;
    }

    private static boolean hasSuBinary() {
        String[] strArr = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk", "/system/app/SuperSU.apk", "/system/bin/.ext/.su", "/system/usr/we-need-root/su-backup"};
        for (int r2 = 0; r2 < 7; r2++) {
            if (new File(strArr[r2]).exists()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSuspiciousAccessibilityEnabled(Context context, List<String> list, Set<String> set) {
        List<AccessibilityServiceInfo> enabledAccessibilityServiceList;
        try {
            AccessibilityManager accessibilityManager = (AccessibilityManager) context.getSystemService("accessibility");
            if (accessibilityManager != null && (enabledAccessibilityServiceList = accessibilityManager.getEnabledAccessibilityServiceList(-1)) != null) {
                for (AccessibilityServiceInfo accessibilityServiceInfo : enabledAccessibilityServiceList) {
                    if (accessibilityServiceInfo.getResolveInfo() != null && accessibilityServiceInfo.getResolveInfo().serviceInfo != null) {
                        String str = accessibilityServiceInfo.getResolveInfo().serviceInfo.packageName;
                        Iterator<String> it = list.iterator();
                        while (it.hasNext()) {
                            if (str.equalsIgnoreCase(it.next())) {
                                set.add(str);
                                return true;
                            }
                        }
                        if (containsAutoClickHint(str, accessibilityServiceInfo.getResolveInfo().serviceInfo.name, accessibilityServiceInfo.loadDescription(pmOrNull(context)))) {
                            set.add(str);
                            return true;
                        }
                    }
                }
            }
            String string = Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services");
            if (!TextUtils.isEmpty(string)) {
                for (String str2 : string.toLowerCase().split(":")) {
                    for (String str3 : list) {
                        if (str2.contains(str3.toLowerCase())) {
                            set.add(str3);
                            return true;
                        }
                    }
                    if (str2.contains("auto") && (str2.contains(TTEDPEventConstants.EDP_EVENT_NAME_CLICK) || str2.contains("tapper") || str2.contains("macro"))) {
                        set.add(str2);
                        return true;
                    }
                }
            }
        } catch (Throwable unused) {
        }
        return false;
    }

    private static boolean looksLikeFridaPresent() {
        File file = new File("/proc/self/maps");
        if (!file.exists()) {
            return false;
        }
        BufferedReader bufferedReader = null;
        try {
            try {
                BufferedReader bufferedReader2 = new BufferedReader(new FileReader(file));
                int r0 = 0;
                while (true) {
                    try {
                        String readLine = bufferedReader2.readLine();
                        if (readLine == null) {
                            bufferedReader2.close();
                            break;
                        }
                        String lowerCase = readLine.toLowerCase();
                        if (lowerCase.contains("frida") || lowerCase.contains("gum-js") || lowerCase.contains("libfrida")) {
                            r0++;
                            if (r0 >= 2) {
                                try {
                                    bufferedReader2.close();
                                } catch (Throwable unused) {
                                }
                                return true;
                            }
                        }
                    } catch (Throwable unused2) {
                        bufferedReader = bufferedReader2;
                        if (bufferedReader != null) {
                            bufferedReader.close();
                        }
                        return false;
                    }
                }
            } catch (Throwable unused3) {
            }
        } catch (Throwable unused4) {
        }
        return false;
    }

    private static PackageManager pmOrNull(Context context) {
        try {
            return context.getPackageManager();
        } catch (Throwable unused) {
            return null;
        }
    }
}
