/*
 * Copyright (C) 2025-2026 The Mistify Project
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

package android.security.pixelprops;

import android.app.ActivityThread;
import android.app.Application;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.security.gameprops.GamePropsSpoofService;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.mist.PixelPropsUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Non-invasive backend for Mistify's "Pixel props" spoof UI. Functionally
 * matches what {@code PixelPropsUtils.setProps()} did on 16.2 HEAD but
 * runs alongside the AxionAOSP spoof stack instead of replacing it:
 * <ul>
 *   <li>Skips {@code com.android.vending} and {@code com.google.android.gms.unstable}
 *       (owned by {@link android.security.pif.PlayIntegritySpoofService}).</li>
 *   <li>Skips {@code com.google.android.apps.photos} (owned by PIF's photo-spoof
 *       path that {@code AxSpoofManager} bridges from {@code pi_photos_spoof}).</li>
 *   <li>Skips any package that {@link GamePropsSpoofService} has a config for.</li>
 * </ul>
 *
 * @hide
 */
public final class PixelPropsSpoofService {
    private static final String TAG = "PixelPropsSpoof";
    private static final boolean DEBUG = false;

    private static final String PACKAGE_ARCORE     = "com.google.ar.core";
    private static final String PACKAGE_DROIDGUARD = "com.google.android.gms.unstable";
    private static final String PACKAGE_PHOTOS     = "com.google.android.apps.photos";
    private static final String PACKAGE_SI         = "com.google.android.settings.intelligence";
    private static final String PACKAGE_SNAPCHAT   = "com.snapchat.android";
    private static final String PACKAGE_VENDING    = "com.android.vending";

    private static final String TENSOR_TARGETS_KEY = "tensor_targets";

    private static final String sDeviceFingerprint =
            SystemProperties.get("ro.product.fingerprint", Build.FINGERPRINT);

    /** Always-on baseline props applied to every spoofed process. */
    private static final Map<String, String> GENERIC_PROPS;
    /** Pixel XL preset (used for Snapchat and historically Photos). */
    private static final Map<String, String> PIXEL_XL_PROPS;
    /** Recent Pixel handset preset. */
    private static final Map<String, String> RECENT_PIXEL_PROPS;
    /** Pixel Tablet preset (when smallestWidth >= 600dp). */
    private static final Map<String, String> PIXEL_TABLET_PROPS;
    /** Curated apps that get the recent-Pixel preset under the master toggle. */
    private static final Set<String> CURATED_RECENT_PIXEL_PACKAGES;
    /** Google-Camera-clone packages that opt out of all build-field spoofing. */
    private static final Set<String> CUSTOM_GOOGLE_CAMERA_PACKAGES;
    /** PIXEL_*_EXPERIENCE strings that look like Tensor-only features. */
    private static final Set<String> TENSOR_FEATURE_NAMES;

    static {
        Map<String, String> generic = new LinkedHashMap<>();
        generic.put("TYPE", "user");
        generic.put("TAGS", "release-keys");
        GENERIC_PROPS = Collections.unmodifiableMap(generic);

        Map<String, String> xl = new LinkedHashMap<>();
        xl.put("BRAND",        "google");
        xl.put("MANUFACTURER", "Google");
        xl.put("DEVICE",       "marlin");
        xl.put("PRODUCT",      "marlin");
        xl.put("HARDWARE",     "marlin");
        xl.put("ID",           "QP1A.191005.007.A3");
        xl.put("MODEL",        "Pixel XL");
        xl.put("FINGERPRINT",
                "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
        PIXEL_XL_PROPS = Collections.unmodifiableMap(xl);

        Map<String, String> recent = new LinkedHashMap<>();
        recent.put("BRAND",        "google");
        recent.put("BOARD",        "mustang");
        recent.put("MANUFACTURER", "Google");
        recent.put("DEVICE",       "mustang");
        recent.put("PRODUCT",      "mustang");
        recent.put("HARDWARE",     "mustang");
        recent.put("MODEL",        "Pixel 10 Pro XL");
        recent.put("ID",           "CP1A.260505.005");
        recent.put("FINGERPRINT",
                "google/mustang/mustang:16/CP1A.260505.005/15081906:user/release-keys");
        RECENT_PIXEL_PROPS = Collections.unmodifiableMap(recent);

        Map<String, String> tablet = new LinkedHashMap<>();
        tablet.put("BRAND",        "google");
        tablet.put("BOARD",        "tangorpro");
        tablet.put("MANUFACTURER", "Google");
        tablet.put("DEVICE",       "tangorpro");
        tablet.put("PRODUCT",      "tangorpro");
        tablet.put("HARDWARE",     "tangorpro");
        tablet.put("MODEL",        "Pixel Tablet");
        tablet.put("ID",           "CP1A.260505.005");
        tablet.put("FINGERPRINT",
                "google/tangorpro/tangorpro:16/CP1A.260505.005/15081906:user/release-keys");
        PIXEL_TABLET_PROPS = Collections.unmodifiableMap(tablet);

        CURATED_RECENT_PIXEL_PACKAGES = Collections.unmodifiableSet(
                new HashSet<>(Arrays.asList(
                        "com.amazon.avod.thirdpartyclient",
                        "com.android.chrome",
                        "com.breel.wallpapers20",
                        "com.disney.disneyplus",
                        "com.google.android.aicore",
                        "com.google.android.apps.accessibility.magnifier",
                        "com.google.android.apps.aiwallpapers",
                        "com.google.android.apps.bard",
                        "com.google.android.apps.customization.pixel",
                        "com.google.android.apps.emojiwallpaper",
                        "com.google.android.apps.pixel.agent",
                        "com.google.android.apps.pixel.creativeassistant",
                        "com.google.android.apps.pixel.nowplaying",
                        "com.google.android.apps.pixel.psi",
                        "com.google.android.apps.pixel.subzero",
                        "com.google.android.apps.pixel.support",
                        "com.google.android.apps.privacy.wildlife",
                        "com.google.android.apps.subscriptions.red",
                        "com.google.android.apps.wallpaper",
                        "com.google.android.apps.wallpaper.pixel",
                        "com.google.android.apps.weather",
                        "com.google.android.googlequicksearchbox",
                        "com.google.android.pcs",
                        "com.google.android.wallpaper.effects",
                        "com.google.pixel.livewallpaper",
                        "com.microsoft.android.smsorganizer",
                        "com.nhs.online.nhsonline",
                        "com.nothing.smartcenter",
                        "com.realme.link",
                        "in.startv.hotstar",
                        "jp.id_credit_sp2.android"
                )));

        CUSTOM_GOOGLE_CAMERA_PACKAGES = Collections.unmodifiableSet(
                new HashSet<>(Arrays.asList(
                        "com.google.android.MTCL83",
                        "com.google.android.UltraCVM",
                        "com.google.android.apps.cameralite"
                )));

        TENSOR_FEATURE_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                "com.google.android.feature.PIXEL_2026_EXPERIENCE",
                "com.google.android.feature.PIXEL_2026_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2025_EXPERIENCE",
                "com.google.android.feature.PIXEL_2025_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2024_EXPERIENCE",
                "com.google.android.feature.PIXEL_2024_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2023_EXPERIENCE",
                "com.google.android.feature.PIXEL_2023_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2022_EXPERIENCE",
                "com.google.android.feature.PIXEL_2022_MIDYEAR_EXPERIENCE",
                "com.google.android.feature.PIXEL_2021_EXPERIENCE"
        )));
    }

    private static volatile PixelPropsSpoofService sInstance;

    // ContentObserver-cached settings — avoids per-call Settings.Secure reads
    // on the hot process-startup path. Refreshed when the user toggles in UI.
    private volatile boolean mPpSpoofEnabled       = true;
    private volatile boolean mSnapchatSpoofEnabled = false;
    private volatile boolean mTensorSpoofEnabled   = false;
    private volatile boolean mPerAppSpoofEnabled   = true;
    private volatile boolean mObserverInstalled    = false;
    private volatile String  mLastProcessName;

    private PixelPropsSpoofService() {}

    /** @hide */
    public static synchronized PixelPropsSpoofService getInstance() {
        if (sInstance == null) sInstance = new PixelPropsSpoofService();
        return sInstance;
    }

    /**
     * Apply Build-field overrides for {@code packageName}. Idempotent —
     * safe to call multiple times from the same process. No-op on isolated
     * processes, custom forks, and packages owned by other spoof services.
     *
     * @hide
     */
    public void spoofForPackage(String packageName) {
        if (PixelPropsUtils.isCustomForkBuild()) {
            if (DEBUG) Log.d(TAG, "custom fork → no spoof");
            return;
        }
        if (Process.isIsolated()) {
            if (DEBUG) Log.d(TAG, "isolated process → no spoof");
            return;
        }
        if (TextUtils.isEmpty(packageName)) return;
        if (isAxionOwned(packageName)) return;
        if (isGameSpoofed(packageName)) return;
        if (CUSTOM_GOOGLE_CAMERA_PACKAGES.contains(packageName)) return;
        if (packageName.contains("GoogleCamera")) return;

        mLastProcessName = Application.getProcessName();
        ensureObserverInstalled();

        // Always-on baseline (Build.TYPE=user, Build.TAGS=release-keys).
        applyProps(GENERIC_PROPS, packageName);

        // 1. Per-app device profile (most specific).
        final Map<String, String> perAppProfile = lookupPerAppProfile(packageName);
        if (perAppProfile != null) {
            applyProps(perAppProfile, packageName);
            return;
        }

        // 2. Snapchat shortcut.
        if (PACKAGE_SNAPCHAT.equals(packageName) && mSnapchatSpoofEnabled) {
            applyProps(PIXEL_XL_PROPS, packageName);
            return;
        }

        // 3. SettingsIntelligence: just set FINGERPRINT to a non-spoofed value
        //    so search indexing doesn't get confused.
        if (PACKAGE_SI.equals(packageName)) {
            applyField("FINGERPRINT", String.valueOf(Build.TIME), packageName);
            return;
        }

        // 4. ARCore: keep the real device fingerprint so AR features work.
        if (PACKAGE_ARCORE.equals(packageName)) {
            applyField("FINGERPRINT", sDeviceFingerprint, packageName);
            return;
        }

        // 5. Curated list — recent-Pixel preset, tablet variant on tablets.
        //    Skipped on mainline Pixel devices (we already are the device).
        if (mPpSpoofEnabled
                && CURATED_RECENT_PIXEL_PACKAGES.contains(packageName)
                && !PixelPropsUtils.isMainlinePixelDevice()) {
            applyProps(isDeviceTablet() ? PIXEL_TABLET_PROPS : RECENT_PIXEL_PROPS, packageName);
        }
    }

    /**
     * Returns {@code Boolean.TRUE} if Tensor-feature spoofing is on for the
     * caller's package and {@code name} is one of the PIXEL_*_EXPERIENCE
     * strings, or {@code null} when this service has nothing to say.
     *
     * @hide
     */
    public Boolean hasTensorFeature(String name) {
        if (name == null) return null;
        if (!mTensorSpoofEnabled) return null;
        final String pkg = ActivityThread.currentPackageName();
        if (pkg == null) return null;
        if (!getTensorTargets().contains(pkg)) return null;
        if (TENSOR_FEATURE_NAMES.contains(name)) return Boolean.TRUE;
        return null;
    }

    // ---- non-invasiveness ------------------------------------------------

    private static boolean isAxionOwned(String packageName) {
        return PACKAGE_VENDING.equals(packageName)
                || PACKAGE_DROIDGUARD.equals(packageName)
                || PACKAGE_PHOTOS.equals(packageName);
    }

    private static boolean isGameSpoofed(String packageName) {
        try {
            return GamePropsSpoofService.getInstance().hasConfigForPackage(packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- per-app device profile -----------------------------------------

    private Map<String, String> lookupPerAppProfile(String packageName) {
        if (!mPerAppSpoofEnabled) return null;
        final ContentResolver cr = getResolver();
        if (cr == null) return null;

        final String map = Settings.Secure.getString(
                cr, Settings.Secure.PER_APPS_DEVICE_SPOOF);
        if (TextUtils.isEmpty(map)) return null;

        String profileId = null;
        for (String entry : map.split(",")) {
            final int sep = entry.indexOf(':');
            if (sep <= 0 || sep >= entry.length() - 1) continue;
            if (entry.substring(0, sep).equals(packageName)) {
                profileId = entry.substring(sep + 1);
                break;
            }
        }
        if (profileId == null) return null;

        return readProfileById(profileId);
    }

    private Map<String, String> readProfileById(String profileId) {
        final ContentResolver cr = getResolver();
        if (cr == null) return null;
        final String json = Settings.Secure.getString(
                cr, Settings.Secure.CUSTOM_SPOOF_PROFILES);
        if (TextUtils.isEmpty(json)) return null;
        try {
            final JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.getJSONObject(i);
                if (!profileId.equals(o.optString("id"))) continue;
                final Map<String, String> p = new LinkedHashMap<>();
                putIfPresent(p, "BRAND",        o.optString("brand"));
                putIfPresent(p, "MANUFACTURER", o.optString("manufacturer"));
                putIfPresent(p, "DEVICE",       o.optString("device"));
                putIfPresent(p, "MODEL",        o.optString("model"));
                putIfPresent(p, "FINGERPRINT",  o.optString("fingerprint"));
                putIfPresent(p, "PRODUCT",      o.optString("product"));
                return p;
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse custom_spoof_profiles", e);
        }
        return null;
    }

    private static void putIfPresent(Map<String, String> dst, String key, String value) {
        if (!TextUtils.isEmpty(value)) dst.put(key, value);
    }

    // ---- settings observer & cached toggles -----------------------------

    private void ensureObserverInstalled() {
        if (mObserverInstalled) return;
        synchronized (this) {
            if (mObserverInstalled) return;
            final ContentResolver cr = getResolver();
            if (cr == null) {
                refreshCachedToggles();
                return;
            }
            final ContentObserver observer = new ContentObserver(null) {
                @Override public void onChange(boolean selfChange, Uri uri) {
                    refreshCachedToggles();
                }
            };
            try {
                cr.registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.PI_PP_SPOOF),
                        false, observer);
                cr.registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.PI_SNAPCHAT_SPOOF),
                        false, observer);
                cr.registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.PI_TENSOR_SPOOF),
                        false, observer);
                cr.registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.PER_APPS_DEVICE_SPOOF_ENABLED),
                        false, observer);
                mObserverInstalled = true;
            } catch (Throwable t) {
                // Stay at safe defaults; per-call reads will still work.
            }
            refreshCachedToggles();
        }
    }

    private void refreshCachedToggles() {
        final ContentResolver cr = getResolver();
        if (cr == null) return;
        try {
            mPpSpoofEnabled =
                    Settings.Secure.getInt(cr, Settings.Secure.PI_PP_SPOOF, 1) == 1;
            mSnapchatSpoofEnabled =
                    Settings.Secure.getInt(cr, Settings.Secure.PI_SNAPCHAT_SPOOF, 0) == 1;
            mTensorSpoofEnabled =
                    Settings.Secure.getInt(cr, Settings.Secure.PI_TENSOR_SPOOF, 0) == 1;
            mPerAppSpoofEnabled =
                    Settings.Secure.getInt(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF_ENABLED, 1) == 1;
        } catch (Throwable t) {
            // Cache stays at last known good values.
        }
    }

    private Set<String> getTensorTargets() {
        final ContentResolver cr = getResolver();
        if (cr == null) return Collections.emptySet();
        final String csv = Settings.Secure.getString(cr, TENSOR_TARGETS_KEY);
        if (TextUtils.isEmpty(csv)) return Collections.emptySet();
        final Set<String> out = new HashSet<>();
        for (String p : csv.split(",")) {
            final String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static ContentResolver getResolver() {
        final ActivityThread at = ActivityThread.currentActivityThread();
        if (at == null) return null;
        final Application app = at.getApplication();
        if (app == null) return null;
        return app.getContentResolver();
    }

    // ---- tablet detection -----------------------------------------------

    private static boolean isDeviceTablet() {
        try {
            final Resources r = Resources.getSystem();
            if (r == null) return false;
            final Configuration c = r.getConfiguration();
            if (c == null) return false;
            return c.smallestScreenWidthDp >= 600;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- Build-field reflection -----------------------------------------

    private static void applyProps(Map<String, String> props, String packageName) {
        for (Map.Entry<String, String> e : props.entrySet()) {
            applyField(e.getKey(), e.getValue(), packageName);
        }
    }

    private static void applyField(String fieldName, String value, String packageName) {
        if (TextUtils.isEmpty(value)) return;
        try {
            Field field = findField(Build.class, fieldName);
            if (field == null) field = findField(Build.VERSION.class, fieldName);
            if (field == null) return;
            field.setAccessible(true);
            final Class<?> type = field.getType();
            Object boxed;
            if (type == String.class)       boxed = value;
            else if (type == int.class)     boxed = Integer.parseInt(value);
            else if (type == long.class)    boxed = Long.parseLong(value);
            else if (type == boolean.class) boxed = Boolean.parseBoolean(value);
            else return;
            field.set(null, boxed);
            if (DEBUG) Log.d(TAG, "[" + packageName + "] " + fieldName + " = " + value);
        } catch (Exception e) {
            Log.w(TAG, "Failed to spoof " + fieldName + " for " + packageName, e);
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }
}
