/*
 * Aub Camera - a fork of Open Camera by Mark Harman.
 * This file is part of Aub Camera and is licensed under the GNU General Public License v3 or later.
 */
package net.sourceforge.opencamera;

import android.os.Bundle;
import android.util.Log;

/** Settings for the Aub Camera photo modes (Portrait, CPL, PS2, Digicam, Disposable). */
public class PreferenceSubAubModes extends PreferenceSubScreen {
    private static final String TAG = "PreferenceSubAubModes";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences_sub_aub_modes);
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate done");
    }
}
