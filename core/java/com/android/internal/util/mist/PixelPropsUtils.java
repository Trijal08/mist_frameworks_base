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

import android.os.SystemProperties;

import java.util.regex.Pattern;

/**
 * Compatibility stub. The real PixelPropsUtils was removed when we
 * migrated to the AxionAOSP spoof stack (AxSpoofManager + JSON configs).
 * These three predicates are still consumed by the Mistify Settings UI
 * to decide which spoof toggles to show; they read SystemProperties and
 * do not perform any spoofing themselves.
 *
 * @hide
 */
public final class PixelPropsUtils {

    private static final Pattern TENSOR_PIXEL_PATTERN =
            Pattern.compile("^Pixel (([6-9]|[1-9][0-9])[a-zA-Z ]*)$");

    private static final Pattern MAINLINE_PIXEL_PATTERN =
            Pattern.compile("^Pixel (([89]|[1-9][0-9])([a-zA-Z].*)?)$");

    private static final boolean sIsCustomForkBuild = detectCustomFork();

    private PixelPropsUtils() {}

    public static boolean isCustomForkBuild() {
        return sIsCustomForkBuild;
    }

    public static boolean isMainlinePixelDevice() {
        return isGooglePixelSoC()
                && MAINLINE_PIXEL_PATTERN.matcher(
                        SystemProperties.get("ro.product.model", "").trim()).matches();
    }

    public static boolean isTensorPixelDevice() {
        return isGooglePixelSoC()
                && TENSOR_PIXEL_PATTERN.matcher(
                        SystemProperties.get("ro.product.model", "").trim()).matches();
    }

    private static boolean isGooglePixelSoC() {
        return "Google".equalsIgnoreCase(
                SystemProperties.get("ro.soc.manufacturer", ""));
    }

    private static boolean detectCustomFork() {
        final char[] k = {'d', 'e', 'v', 'o', 'l', 'u', 't', 'i', 'o', 'n'};
        final String needle = new String(k);
        final String[] props = {
                SystemProperties.get("ro.build.display.id", ""),
                SystemProperties.get("ro.modversion", ""),
                SystemProperties.get("ro.mist.version", ""),
                SystemProperties.get("ro.build.flavor", ""),
        };
        for (String p : props) {
            if (p != null && p.toLowerCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
