/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.server.spoof;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.NtServiceInjector;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class AxSpoofManager implements IAxSpoofManager {
    private static final String TAG = "AxSpoofManager";

    private static final String[] WATCHED_KEYS = {
            Settings.Secure.SPOOF_PIF_CONFIG,
            Settings.Secure.SPOOF_GAMEPROPS_CONFIG,
            Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
            Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
            Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
    };

    /**
     * Mistify booleans that map onto JSON keys inside {@link Settings.Secure#SPOOF_PIF_CONFIG}.
     * Order is {legacy Settings.Secure key, JSON field in spoof_pif_config}.
     */
    private static final String[][] LEGACY_PIF_BRIDGE = {
            { Settings.Secure.PI_PHOTOS_SPOOF,  "spoofPhotos" },
            { Settings.Secure.PI_VENDING_SPOOF, "spoofVendingBuild" },
    };

    private final Map<String, String> mCache = new ConcurrentHashMap<>();
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final AtomicBoolean mApplyingBridge = new AtomicBoolean(false);

    private Context mContext;
    private ContentResolver mResolver;
    private ContentObserver mObserver;
    private ContentObserver mLegacyObserver;
    private volatile boolean mReady = false;

    public AxSpoofManager() {
        mHandlerThread = new HandlerThread("AxSpoofManager");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @Override
    public void systemReady() {
        mContext = NtServiceInjector.getCtx();
        if (mContext == null) {
            Log.w(TAG, "Context unavailable, deferring init");
            return;
        }
        mResolver = mContext.getContentResolver();

        for (String key : WATCHED_KEYS) {
            refreshKey(key);
        }

        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (uri == null) return;
                final String last = uri.getLastPathSegment();
                if (last == null) return;
                refreshKey(last);
                Log.i(TAG, "Spoof config refreshed: " + last);
            }
        };
        for (String key : WATCHED_KEYS) {
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }

        mLegacyObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (selfChange) return;
                if (mApplyingBridge.get()) return;
                applyLegacyPifBridge();
            }
        };
        for (String[] entry : LEGACY_PIF_BRIDGE) {
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(entry[0]), false, mLegacyObserver,
                    UserHandle.USER_ALL);
        }
        applyLegacyPifBridge();

        mReady = true;
        Log.i(TAG, "AxSpoofManager ready");
    }

    /**
     * Translate the legacy Mistify boolean toggles into the equivalent JSON keys
     * inside spoof_pif_config. Mistify writes Settings.Secure booleans; we patch
     * those into the JSON config that PlayIntegritySpoofService actually reads.
     * Existing JSON keys not in the bridge map are preserved.
     */
    private void applyLegacyPifBridge() {
        if (mResolver == null) return;
        mApplyingBridge.set(true);
        try {
            final String existing = Settings.Secure.getStringForUser(
                    mResolver, Settings.Secure.SPOOF_PIF_CONFIG, UserHandle.USER_SYSTEM);
            final JSONObject json;
            try {
                json = TextUtils.isEmpty(existing)
                        ? new JSONObject() : new JSONObject(existing);
            } catch (JSONException e) {
                Log.w(TAG, "spoof_pif_config not parseable, skipping legacy bridge", e);
                return;
            }

            boolean changed = false;
            for (String[] entry : LEGACY_PIF_BRIDGE) {
                final int v = Settings.Secure.getIntForUser(
                        mResolver, entry[0], 0, UserHandle.USER_SYSTEM);
                final String desired = v == 1 ? "true" : "false";
                if (!desired.equals(json.optString(entry[1], ""))) {
                    try {
                        json.put(entry[1], desired);
                        changed = true;
                    } catch (JSONException ignored) { }
                }
            }
            if (changed) {
                Settings.Secure.putStringForUser(
                        mResolver, Settings.Secure.SPOOF_PIF_CONFIG,
                        json.toString(), UserHandle.USER_SYSTEM);
                Log.i(TAG, "Legacy Mistify toggles synced into spoof_pif_config");
            }
        } finally {
            mApplyingBridge.set(false);
        }
    }

    private void refreshKey(String key) {
        if (mResolver == null) return;
        final String value = Settings.Secure.getStringForUser(
                mResolver, key, UserHandle.USER_SYSTEM);
        if (value == null) {
            mCache.remove(key);
        } else {
            mCache.put(key, value);
        }
    }

    private String getCached(String key) {
        return mCache.get(key);
    }

    @Override
    public String getPifConfig() {
        return getCached(Settings.Secure.SPOOF_PIF_CONFIG);
    }

    @Override
    public String getGamePropsConfig() {
        return getCached(Settings.Secure.SPOOF_GAMEPROPS_CONFIG);
    }

    @Override
    public String getTrickyStoreTarget() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
    }

    @Override
    public String getTrickyStoreKeyBox() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
    }

    @Override
    public String getTrickyStorePatch() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_PATCH);
    }
}
