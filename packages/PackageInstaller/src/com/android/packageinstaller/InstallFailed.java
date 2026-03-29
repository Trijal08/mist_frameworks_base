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
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Installation failed: Return status code to the caller or display failure UI to user
 */
public class InstallFailed extends ComponentActivity {

    private static final String LOG_TAG = InstallFailed.class.getSimpleName();

    /**
     * Label of the app that failed to install
     */
    private CharSequence mLabel;

    private AlertDialog mDialog;

    /**
     * Unhide the appropriate label for the statusCode.
     *
     * @param statusCode The status code from the package installer.
     */

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setFinishOnTouchOutside(true);

        Intent intent = getIntent();
        int statusCode = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE);
        boolean returnResult = intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false);
        int legacyStatus = intent.getIntExtra(PackageInstaller.EXTRA_LEGACY_STATUS,
                PackageManager.INSTALL_FAILED_INTERNAL_ERROR);

        // TODO (b/346655018): Use INSTALL_FAILED_ABORTED legacyCode in the condition
        // statusCode can be STATUS_FAILURE_ABORTED if:
        // 1. GPP blocks an install.
        // 2. User denies ownership update explicitly.
        // InstallFailed dialog must not be shown only when the user denies ownership update. We
        // must show this dialog for all other install failures.
        boolean userDenied = statusCode == PackageInstaller.STATUS_FAILURE_ABORTED
                && legacyStatus != PackageManager.INSTALL_FAILED_VERIFICATION_TIMEOUT
                && legacyStatus != PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE;

        if (returnResult) {
            // Return result if requested
            Intent result = new Intent();
            result.putExtra(Intent.EXTRA_INSTALL_RESULT, legacyStatus);
            setResult(Activity.RESULT_FIRST_USER, result);
            finish();
        } else if (userDenied) {
            finish();
        } else {
            // Set header icon and title
            PackageUtil.AppSnippet as = intent.getParcelableExtra(
                PackageInstallerActivity.EXTRA_APP_SNIPPET, PackageUtil.AppSnippet.class);

            // Store label for dialog
            mLabel = as.label;

            com.android.packageinstaller.ui.PackageInstallerComposeBridge.setPackageInstallerContent(
                this,
                as != null && as.label != null ? as.label.toString() : "",
                as != null ? as.icon : null,
                "",
                "",
                (String) null,
                0L,
                0,
                0,
                null,
                com.android.packageinstaller.ui.InstallerPhase.INSTALL_FAILED,
                () -> { return kotlin.Unit.INSTANCE; },
                () -> { return kotlin.Unit.INSTANCE; },
                () -> {
                    finish();
                    return kotlin.Unit.INSTANCE;
                },
                false
            );

            if (statusCode == PackageInstaller.STATUS_FAILURE_STORAGE) {
                (new OutOfSpaceDialog()).show(getFragmentManager(), "outofspace");
            }
        }
    }

    /**
     * Dialog shown when we ran out of space during installation. This contains a link to the
     * "manage applications" settings page.
     */
    public static class OutOfSpaceDialog extends DialogFragment {

        private InstallFailed mActivity;

        @Override
        public void onAttach(Context context) {
            super.onAttach(context);

            mActivity = (InstallFailed) context;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return new AlertDialog.Builder(mActivity)
                .setTitle(R.string.out_of_space_dlg_title)
                .setMessage(getString(R.string.out_of_space_dlg_text, mActivity.mLabel))
                .setPositiveButton(R.string.manage_applications, (dialog, which) -> {
                    // launch manage applications
                    Intent intent = new Intent("android.intent.action.MANAGE_PACKAGE_STORAGE");
                    startActivity(intent);
                    mActivity.finish();
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> mActivity.finish())
                .create();
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            super.onCancel(dialog);

            mActivity.finish();
        }
    }
}
