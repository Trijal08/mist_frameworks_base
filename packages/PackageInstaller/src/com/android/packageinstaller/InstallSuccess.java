/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.packageinstaller;

import android.app.Activity;
import androidx.activity.ComponentActivity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Finish installation: Return status code to the caller or display "success" UI to user
 */
public class InstallSuccess extends ComponentActivity {
    private static final String LOG_TAG = InstallSuccess.class.getSimpleName();

    @Nullable
    private PackageUtil.AppSnippet mAppSnippet;

    @Nullable
    private String mAppPackageName;

    @Nullable
    private Intent mLaunchIntent;

    private AlertDialog mDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setFinishOnTouchOutside(true);

        if (getIntent().getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) {
            // Return result if requested
            Intent result = new Intent();
            result.putExtra(Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_SUCCEEDED);
            setResult(Activity.RESULT_OK, result);
            finish();
        } else {
            Intent intent = getIntent();
            ApplicationInfo appInfo =
                    intent.getParcelableExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO);
            mAppPackageName = appInfo.packageName;
            mAppSnippet = intent.getParcelableExtra(PackageInstallerActivity.EXTRA_APP_SNIPPET,
                    PackageUtil.AppSnippet.class);

            mLaunchIntent = getPackageManager().getLaunchIntentForPackage(mAppPackageName);

            bindUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindUi();
    }

    private void bindUi() {
        if (mAppSnippet == null) {
            return;
        }

        int targetSdk = 0;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(mAppPackageName, 0);
            if (pi != null && pi.applicationInfo != null) {
                targetSdk = pi.applicationInfo.targetSdkVersion;
            }
        } catch (Exception ignored) {}

        com.android.packageinstaller.ui.PackageInstallerComposeBridge.setPackageInstallerContent(
            this,
            mAppSnippet.label != null ? mAppSnippet.label.toString() : "",
            mAppSnippet.icon,
            mAppPackageName != null ? mAppPackageName : "",
            "",
            (String) null,
            0L,
            targetSdk,
            0,
            null,
            com.android.packageinstaller.ui.InstallerPhase.INSTALL_SUCCESS,
            () -> { return kotlin.Unit.INSTANCE; },
            () -> {
                boolean visible = false;
                if (mLaunchIntent != null) {
                    List<ResolveInfo> list = getPackageManager().queryIntentActivities(mLaunchIntent, 0);
                    if (list != null && list.size() > 0) {
                        visible = true;
                    }
                }
                visible = visible && isLauncherActivityEnabled(mLaunchIntent);
                if (visible) {
                    try {
                        startActivity(mLaunchIntent.addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Log.e(LOG_TAG, "Could not start activity", e);
                    }
                }
                finish();
                return kotlin.Unit.INSTANCE;
            },
            () -> {
                finish();
                return kotlin.Unit.INSTANCE;
            },
            false
        );
    }

    private boolean isLauncherActivityEnabled(Intent intent) {
        if (intent == null || intent.getComponent() == null) {
            return false;
        }
        return getPackageManager().getComponentEnabledSetting(intent.getComponent())
            != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
}
