/*
 * Copyright (C) 2025 The Mistify Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.mist;

import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.SystemProperties;
import android.os.UserHandle;

import com.android.internal.R;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Helper bag for the Mistify spoof UI and the launcher-permission bypass
 * paths. Build-field overrides live in
 * {@link android.security.pixelprops.PixelPropsSpoofService}; this class
 * is just a stateless collection of predicates and lookups.
 *
 * @hide
 */
public final class PixelPropsUtils {

    private static final String PACKAGE_GMS            = "com.google.android.gms";
    private static final String PACKAGE_NEXUS_LAUNCHER = "com.google.android.apps.nexuslauncher";

    // Tensor devices: Pixel 6 and above.
    private static final Pattern TENSOR_PIXEL_PATTERN =
            Pattern.compile("^Pixel (([6-9]|[1-9][0-9])[a-zA-Z ]*)$");

    // Mainline (first-party SoC) devices: Pixel 8 and above.
    private static final Pattern MAINLINE_PIXEL_PATTERN =
            Pattern.compile("^Pixel (([89]|[1-9][0-9])([a-zA-Z].*)?)$");

    // Any supported Pixel: Pixel 3 and above.
    private static final Pattern SUPPORTED_PIXEL_PATTERN =
            Pattern.compile("^Pixel ([3-9]|[1-9][0-9])([a-zA-Z ].*)?$");

    private static final boolean sIsCustomForkBuild = detectCustomFork();
    private static final boolean sIsMainlineDevice  = detectMainlinePixelDevice();

    private static volatile Set<String> sLauncherPkgs;
    private static volatile Set<String> sExemptedUidPkgs;

    private PixelPropsUtils() {}

    // ---- build / device predicates --------------------------------------

    public static boolean isCustomForkBuild() {
        return sIsCustomForkBuild;
    }

    public static boolean isMainlinePixelDevice() {
        return sIsMainlineDevice;
    }

    public static boolean isTensorPixelDevice() {
        return isGooglePixelSoC()
                && TENSOR_PIXEL_PATTERN.matcher(productModel()).matches();
    }

    public static boolean isSupportedPixelDevice() {
        return SUPPORTED_PIXEL_PATTERN.matcher(productModel()).matches();
    }

    public static boolean isPackageGoogle(String pkg) {
        return pkg != null && pkg.toLowerCase().contains("google");
    }

    // ---- fingerprint parsers --------------------------------------------

    public static String getBuildID(String fingerprint) {
        if (fingerprint == null) return "";
        final java.util.regex.Matcher m = Pattern.compile(
                "([A-Za-z0-9]+\\.\\d+\\.\\d+\\.\\w+)").matcher(fingerprint);
        return m.find() ? m.group(1) : "";
    }

    public static String getDeviceName(String fingerprint) {
        if (fingerprint == null) return "";
        final String[] parts = fingerprint.split("/");
        return parts.length >= 2 ? parts[1] : "";
    }

    // ---- launcher identity ----------------------------------------------

    public static boolean isNexusLauncher(Context context) {
        try {
            return PACKAGE_NEXUS_LAUNCHER.equals(
                    context.getPackageManager().getNameForUid(Binder.getCallingUid()));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isSystemLauncher(Context context) {
        try {
            return isSystemLauncherInternal(
                    context.getPackageManager().getNameForUid(Binder.getCallingUid()));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isSystemLauncher(int callingUid) {
        try {
            return isSystemLauncherInternal(
                    ActivityThread.getPackageManager().getNameForUid(callingUid));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isSystemLauncherInternal(String callerPackage) {
        return getLauncherPkgs().contains(callerPackage);
    }

    // ---- permission bypass helpers --------------------------------------

    public static boolean shouldBypassTaskPermission(int callingUid) {
        for (String pkg : getExemptedUidPkgs()) {
            try {
                final ApplicationInfo appInfo = ActivityThread.getPackageManager()
                        .getApplicationInfo(pkg, 0, UserHandle.getUserId(callingUid));
                if (appInfo != null && appInfo.uid == callingUid) return true;
            } catch (Exception ignored) {
                // Best effort; fall through to next pkg.
            }
        }
        return false;
    }

    public static boolean shouldBypassManageActivityTaskPermission(Context context) {
        final int uid = Binder.getCallingUid();
        return isSystemLauncher(uid)
                || isPackageGoogle(context.getPackageManager().getNameForUid(uid));
    }

    public static boolean shouldBypassMonitorInputPermission(Context context) {
        final int uid = Binder.getCallingUid();
        return shouldBypassTaskPermission(uid)
                || isPackageGoogle(context.getPackageManager().getNameForUid(uid));
    }

    public static boolean shouldBypassFGSValidation(String packageName) {
        return Arrays.asList(getStringArrayResSafely(
                R.array.config_fgsTypeValidationBypassPackages)).contains(packageName);
    }

    public static boolean shouldBypassAlarmManagerValidation(String packageName) {
        return Arrays.asList(getStringArrayResSafely(
                R.array.config_alarmManagerValidationBypassPackages)).contains(packageName);
    }

    public static boolean shouldBypassBroadcastReceiverValidation(String packageName) {
        return Arrays.asList(getStringArrayResSafely(
                R.array.config_broadcastReceiverValidationBypassPackages)).contains(packageName);
    }

    // ---- internals -------------------------------------------------------

    private static Set<String> getLauncherPkgs() {
        Set<String> cached = sLauncherPkgs;
        if (cached != null && !cached.isEmpty()) return cached;
        synchronized (PixelPropsUtils.class) {
            cached = sLauncherPkgs;
            if (cached == null || cached.isEmpty()) {
                cached = new HashSet<>(Arrays.asList(
                        getStringArrayResSafely(R.array.config_launcherPackages)));
                sLauncherPkgs = cached;
            }
            return cached;
        }
    }

    private static Set<String> getExemptedUidPkgs() {
        Set<String> cached = sExemptedUidPkgs;
        if (cached != null && !cached.isEmpty()) return cached;
        synchronized (PixelPropsUtils.class) {
            cached = sExemptedUidPkgs;
            if (cached == null || cached.isEmpty()) {
                cached = new HashSet<>();
                cached.add(PACKAGE_GMS);
                cached.addAll(getLauncherPkgs());
                sExemptedUidPkgs = cached;
            }
            return cached;
        }
    }

    private static String[] getStringArrayResSafely(int resId) {
        try {
            final String[] arr = Resources.getSystem().getStringArray(resId);
            return arr != null ? arr : new String[0];
        } catch (Exception ignored) {
            return new String[0];
        }
    }

    private static boolean isGooglePixelSoC() {
        return "Google".equalsIgnoreCase(
                SystemProperties.get("ro.soc.manufacturer", ""));
    }

    private static String productModel() {
        return SystemProperties.get("ro.product.model", "").trim();
    }

    private static boolean detectMainlinePixelDevice() {
        return isGooglePixelSoC()
                && MAINLINE_PIXEL_PATTERN.matcher(productModel()).matches();
    }

    private static boolean detectCustomFork() {
        final char[] k = {'d', 'e', 'v', 'o', 'l', 'u', 't', 'i', 'o', 'n'};
        final String needle = new String(k);
        final String[] props = {
                SystemProperties.get("ro.build.display.id", ""),
                SystemProperties.get("ro.modversion", ""),
                SystemProperties.get("ro.mist.version_display", ""),
                SystemProperties.get("ro.mist.version.base", ""),
                SystemProperties.get("ro.build.flavor", ""),
        };
        for (String p : props) {
            if (p != null && p.toLowerCase().contains(needle)) return true;
        }
        return false;
    }
}
