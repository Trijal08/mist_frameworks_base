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
import android.content.ContentResolver;
import android.os.Build;
import android.provider.Settings;
import android.security.gameprops.GamePropsSpoofService;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Non-invasive backend for Mistify's legacy "Pixel props" spoof UI.
 *
 * Reads {@link Settings.Secure} keys written by the Mistify Settings UI
 * (the per-app picker, the Snapchat toggle, the Pixel-props master switch
 * and the Tensor-targets screen) and applies the matching {@link Build}
 * field overrides at process startup.
 *
 * <p>Stays out of the way of the AxionAOSP spoof stack:
 * <ul>
 *   <li>Skips {@code com.android.vending} and {@code com.google.android.gms.unstable}
 *       (owned by {@code PlayIntegritySpoofService}).</li>
 *   <li>Skips {@code com.google.android.apps.photos} (owned by the PIF
 *       photo-spoof path bridged from {@code pi_photos_spoof}).</li>
 *   <li>Skips any package that {@link GamePropsSpoofService} has a config for.</li>
 * </ul>
 *
 * @hide
 */
public final class PixelPropsSpoofService {
    private static final String TAG = "PixelPropsSpoof";

    private static final String PACKAGE_VENDING    = "com.android.vending";
    private static final String PACKAGE_DROIDGUARD = "com.google.android.gms.unstable";
    private static final String PACKAGE_PHOTOS     = "com.google.android.apps.photos";
    private static final String PACKAGE_SNAPCHAT   = "com.snapchat.android";

    private static final String TENSOR_TARGETS_KEY = "tensor_targets";

    /** Apps spoofed to the latest Pixel when the master toggle is on. */
    private static final Set<String> CURATED_RECENT_PIXEL_PACKAGES = Collections.unmodifiableSet(
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

    /** Pixel XL props used for the Snapchat shortcut. */
    private static final Map<String, String> PIXEL_XL_PROPS;
    /** Recent Pixel props for the curated-list master toggle. */
    private static final Map<String, String> RECENT_PIXEL_PROPS;
    /** PIXEL_*_EXPERIENCE strings that look like Tensor features. */
    private static final Set<String> TENSOR_FEATURE_NAMES;

    static {
        Map<String, String> xl = new HashMap<>();
        xl.put("BRAND",        "google");
        xl.put("MANUFACTURER", "Google");
        xl.put("DEVICE",       "marlin");
        xl.put("PRODUCT",      "marlin");
        xl.put("HARDWARE",     "marlin");
        xl.put("MODEL",        "Pixel XL");
        xl.put("ID",           "QP1A.191005.007.A3");
        xl.put("FINGERPRINT",
                "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
        PIXEL_XL_PROPS = Collections.unmodifiableMap(xl);

        Map<String, String> recent = new HashMap<>();
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

        Set<String> features = new HashSet<>(Arrays.asList(
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
        ));
        TENSOR_FEATURE_NAMES = Collections.unmodifiableSet(features);
    }

    private static volatile PixelPropsSpoofService sInstance;

    private PixelPropsSpoofService() {}

    /** @hide */
    public static synchronized PixelPropsSpoofService getInstance() {
        if (sInstance == null) sInstance = new PixelPropsSpoofService();
        return sInstance;
    }

    /**
     * Apply Build-field overrides for {@code packageName} if the Mistify UI
     * has configured a profile for it. Caller is responsible for ensuring
     * this is invoked once during process startup. Skips packages owned by
     * the AxionAOSP PIF and game-spoof services.
     *
     * @hide
     */
    public void spoofForPackage(String packageName) {
        if (TextUtils.isEmpty(packageName)) return;
        if (isAxionOwned(packageName)) return;
        if (isGameSpoofed(packageName)) return;

        final Map<String, String> props = chooseProps(packageName);
        if (props == null) return;
        for (Map.Entry<String, String> e : props.entrySet()) {
            applyField(e.getKey(), e.getValue(), packageName);
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
        if (!isTensorSpoofEnabled()) return null;
        final String pkg = ActivityThread.currentPackageName();
        if (pkg == null) return null;
        if (!getTensorTargets().contains(pkg)) return null;
        if (TENSOR_FEATURE_NAMES.contains(name)) return Boolean.TRUE;
        return null;
    }

    // ---- selection -------------------------------------------------------

    private Map<String, String> chooseProps(String packageName) {
        // Per-app user-defined profile takes precedence.
        final Map<String, String> profile = lookupPerAppProfile(packageName);
        if (profile != null) return profile;

        if (PACKAGE_SNAPCHAT.equals(packageName) && isSnapchatSpoofEnabled()) {
            return PIXEL_XL_PROPS;
        }

        if (isMasterSpoofEnabled() && CURATED_RECENT_PIXEL_PACKAGES.contains(packageName)) {
            return RECENT_PIXEL_PROPS;
        }
        return null;
    }

    private Map<String, String> lookupPerAppProfile(String packageName) {
        if (!isPerAppSpoofEnabled()) return null;
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
                final Map<String, String> p = new HashMap<>();
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

    // ---- Settings.Secure readers ----------------------------------------

    private boolean isMasterSpoofEnabled() {
        return getSecureInt(Settings.Secure.PI_PP_SPOOF, 1) == 1;
    }

    private boolean isSnapchatSpoofEnabled() {
        return getSecureInt(Settings.Secure.PI_SNAPCHAT_SPOOF, 0) == 1;
    }

    private boolean isPerAppSpoofEnabled() {
        return getSecureInt(Settings.Secure.PER_APPS_DEVICE_SPOOF_ENABLED, 1) == 1;
    }

    private boolean isTensorSpoofEnabled() {
        return getSecureInt(Settings.Secure.PI_TENSOR_SPOOF, 0) == 1;
    }

    private Set<String> getTensorTargets() {
        final ContentResolver cr = getResolver();
        if (cr == null) return Collections.emptySet();
        final String csv = Settings.Secure.getString(cr, TENSOR_TARGETS_KEY);
        if (TextUtils.isEmpty(csv)) return Collections.emptySet();
        final Set<String> out = new HashSet<>();
        for (String p : csv.split(",")) {
            if (!p.isEmpty()) out.add(p.trim());
        }
        return out;
    }

    private int getSecureInt(String key, int defaultValue) {
        final ContentResolver cr = getResolver();
        if (cr == null) return defaultValue;
        return Settings.Secure.getInt(cr, key, defaultValue);
    }

    private static ContentResolver getResolver() {
        final ActivityThread at = ActivityThread.currentActivityThread();
        if (at == null) return null;
        final android.app.Application app = at.getApplication();
        if (app == null) return null;
        return app.getContentResolver();
    }

    // ---- Build-field reflection -----------------------------------------

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
