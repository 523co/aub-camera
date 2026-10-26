package net.sourceforge.opencamera.ui;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.MyApplicationInterface;
import net.sourceforge.opencamera.MyDebug;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.R;
import net.sourceforge.opencamera.preview.Preview;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Aub Camera: the GrapheneOS Camera style controls - the strip of capture mode tabs above the
 *  shutter (also changed by swiping across the viewfinder), the zoom chips, keeping the on-screen
 *  icons upright while the screen stays in portrait, and the QR / barcode scan mode.
 */
public class AubControls {
    private static final String TAG = "AubControls";

    /** The capture modes shown as tabs, in the order they appear. */
    public enum Mode {
        NIGHT,
        PORTRAIT,
        CPL,
        PS2,
        DIGICAM,
        DISPOSABLE,
        PHOTO,
        VIDEO,
        SCAN
    }

    private final MainActivity main_activity;

    private final List<Mode> tab_modes = new ArrayList<>();
    private final List<TextView> tab_views = new ArrayList<>();
    private Mode highlighted_mode;
    private boolean scan_mode;

    private float swipe_start_x, swipe_start_y;
    private boolean swipe_cancelled;

    private final List<Float> zoom_chip_ratios = new ArrayList<>();
    private final List<TextView> zoom_chip_views = new ArrayList<>();
    private boolean controls_hidden; // e.g. in immersive mode

    private int device_orientation = -1; // device orientation rounded to a multiple of 90 degrees, or -1 if not yet known
    private int icon_rotation; // rotation to apply to the on-screen icons, so they're upright for how the device is held

    private AlertDialog barcode_dialog;
    private String last_barcode;
    private long last_barcode_time;
    private final static long barcode_repeat_delay_ms = 2000; // don't show the same code again straight after the dialog is closed

    public AubControls(MainActivity main_activity) {
        this.main_activity = main_activity;
    }

    /** Sets up the styling that can't be done from the layout xml. Should be called once the views
     *  exist.
     */
    public void init() {
        // circular gallery thumbnail
        ImageButton galleryButton = main_activity.findViewById(R.id.gallery);
        galleryButton.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        galleryButton.setClipToOutline(true);
    }

    private int dpToPx(float dp) {
        final float scale = main_activity.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f); // convert dps to pixels
    }

    /* Capture modes.
     */

    public boolean isScanMode() {
        return scan_mode;
    }

    /** Whether to look for codes in the viewfinder right now.
     */
    public boolean wantsBarcodeScan() {
        return scan_mode && barcode_dialog == null;
    }

    private static MyApplicationInterface.PhotoMode getPhotoModeForMode(Mode mode) {
        switch( mode ) {
            case NIGHT:
                return MyApplicationInterface.PhotoMode.X_Night;
            case PORTRAIT:
                return MyApplicationInterface.PhotoMode.Portrait;
            case CPL:
                return MyApplicationInterface.PhotoMode.CPL;
            case PS2:
                return MyApplicationInterface.PhotoMode.PS2;
            case DIGICAM:
                return MyApplicationInterface.PhotoMode.Digicam;
            case DISPOSABLE:
                return MyApplicationInterface.PhotoMode.Disposable;
            default:
                return MyApplicationInterface.PhotoMode.Standard;
        }
    }

    private static Mode getModeForPhotoMode(MyApplicationInterface.PhotoMode photo_mode) {
        switch( photo_mode ) {
            case X_Night:
                return Mode.NIGHT;
            case Portrait:
                return Mode.PORTRAIT;
            case CPL:
                return Mode.CPL;
            case PS2:
                return Mode.PS2;
            case Digicam:
                return Mode.DIGICAM;
            case Disposable:
                return Mode.DISPOSABLE;
            default:
                // the other photo modes (HDR, panorama, ...) are chosen from the popup, and count as Photo
                return Mode.PHOTO;
        }
    }

    /** Whether the photo mode has its own tab (so leaving that tab for Photo returns to the standard
     *  photo mode, whereas photo modes chosen from the popup are kept).
     */
    private static boolean hasOwnTab(MyApplicationInterface.PhotoMode photo_mode) {
        return getModeForPhotoMode(photo_mode) != Mode.PHOTO;
    }

    public Mode getCurrentMode() {
        if( scan_mode )
            return Mode.SCAN;
        if( main_activity.getPreview().isVideo() )
            return Mode.VIDEO;
        return getModeForPhotoMode(main_activity.getApplicationInterface().getPhotoMode());
    }

    private String getModeName(Mode mode) {
        switch( mode ) {
            case NIGHT:
                return main_activity.getResources().getString(R.string.aub_mode_night);
            case PORTRAIT:
                return main_activity.getResources().getString(R.string.photo_mode_aub_portrait);
            case CPL:
                return main_activity.getResources().getString(R.string.photo_mode_aub_cpl);
            case PS2:
                return main_activity.getResources().getString(R.string.photo_mode_aub_ps2);
            case DIGICAM:
                return main_activity.getResources().getString(R.string.photo_mode_aub_digicam);
            case DISPOSABLE:
                return main_activity.getResources().getString(R.string.photo_mode_aub_disposable);
            case VIDEO:
                return main_activity.getResources().getString(R.string.aub_mode_video);
            case SCAN:
                return main_activity.getResources().getString(R.string.aub_mode_scan);
            default:
                return main_activity.getResources().getString(R.string.aub_mode_photo);
        }
    }

    private List<Mode> getAvailableModes() {
        List<Mode> modes = new ArrayList<>();
        if( main_activity.getPreview().getCameraController() != null && main_activity.supportsCameraExtension(CameraExtensionCharacteristics.EXTENSION_NIGHT) )
            modes.add(Mode.NIGHT);
        if( main_activity.supportsAubPhotoModes() ) {
            modes.add(Mode.PORTRAIT);
            modes.add(Mode.CPL);
            modes.add(Mode.PS2);
            modes.add(Mode.DIGICAM);
            modes.add(Mode.DISPOSABLE);
        }
        modes.add(Mode.PHOTO);
        modes.add(Mode.VIDEO);
        if( main_activity.supportsPreviewBitmaps() )
            modes.add(Mode.SCAN);
        return modes;
    }

    private void createTabs(List<Mode> modes) {
        if( MyDebug.LOG )
            Log.d(TAG, "createTabs: " + modes);
        LinearLayout container = main_activity.findViewById(R.id.aub_mode_tabs);
        container.removeAllViews();
        tab_modes.clear();
        tab_views.clear();
        highlighted_mode = null;
        for(final Mode mode : modes) {
            TextView tab = new TextView(main_activity);
            tab.setText(getModeName(mode));
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true);
            tab.setPadding(dpToPx(14), 0, dpToPx(14), 0);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectMode(mode);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(32));
            params.setMargins(dpToPx(2), 0, dpToPx(2), 0);
            params.gravity = Gravity.CENTER_VERTICAL;
            container.addView(tab, params);
            tab_modes.add(mode);
            tab_views.add(tab);
        }
    }

    /** Updates the mode tabs (and anything else that depends on the capture mode) to match the
     *  current state. Safe to call often.
     */
    public void updateModeTabs() {
        if( main_activity.getPreview() == null )
            return;
        List<Mode> modes = getAvailableModes();
        if( !modes.equals(tab_modes) ) {
            createTabs(modes);
        }

        // allow the first and last tabs to be scrolled to the middle
        HorizontalScrollView scroll = main_activity.findViewById(R.id.aub_mode_tabs_scroll);
        LinearLayout container = main_activity.findViewById(R.id.aub_mode_tabs);
        int side_padding = main_activity.getResources().getDisplayMetrics().widthPixels/2;
        if( container.getPaddingLeft() != side_padding ) {
            container.setPadding(side_padding, 0, side_padding, 0);
        }

        Mode current_mode = getCurrentMode();
        for(int i=0;i<tab_modes.size();i++) {
            TextView tab = tab_views.get(i);
            boolean selected = tab_modes.get(i) == current_mode;
            tab.setSelected(selected);
            tab.setTextColor(selected ? 0xff000000 : 0xccffffff);
            tab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tab.setBackgroundResource(selected ? R.drawable.aub_tab_selected : 0);
        }
        if( current_mode != highlighted_mode ) {
            final boolean animate = highlighted_mode != null;
            highlighted_mode = current_mode;
            int index = tab_modes.indexOf(current_mode);
            if( index != -1 ) {
                final TextView tab = tab_views.get(index);
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        int x = tab.getLeft() + tab.getWidth()/2 - scroll.getWidth()/2;
                        if( animate )
                            scroll.smoothScrollTo(x, 0);
                        else
                            scroll.scrollTo(x, 0);
                    }
                });
            }
        }

        updateShutterForScanMode();
    }

    /** The user has chosen a capture mode, from a tab or by swiping.
     */
    public void selectMode(Mode mode) {
        if( MyDebug.LOG )
            Log.d(TAG, "selectMode: " + mode);
        Preview preview = main_activity.getPreview();
        if( preview.isVideoRecording() || preview.isTakingPhotoOrOnTimer() || preview.isOpeningCamera() ||
                main_activity.getApplicationInterface().getGyroSensor().isRecording() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "can't change mode now");
            return;
        }
        Mode current_mode = getCurrentMode();
        if( mode == current_mode )
            return;
        main_activity.closePopup();

        boolean was_scan_mode = scan_mode;
        scan_mode = mode == Mode.SCAN;
        if( was_scan_mode != scan_mode ) {
            if( !scan_mode )
                dismissBarcodeDialog();
            main_activity.getApplicationInterface().getDrawPreview().updateSettings();
        }

        if( mode == Mode.VIDEO ) {
            main_activity.switchVideoMode(false);
        }
        else {
            MyApplicationInterface.PhotoMode old_photo_mode = main_activity.getApplicationInterface().getPhotoMode();
            MyApplicationInterface.PhotoMode new_photo_mode;
            if( mode == Mode.PHOTO || mode == Mode.SCAN ) {
                // keep a photo mode chosen from the popup (e.g. HDR), but not one with its own tab
                new_photo_mode = hasOwnTab(old_photo_mode) ? MyApplicationInterface.PhotoMode.Standard : old_photo_mode;
            }
            else {
                new_photo_mode = getPhotoModeForMode(mode);
            }
            boolean photo_mode_changed = new_photo_mode != old_photo_mode;
            if( photo_mode_changed ) {
                setPhotoModePref(new_photo_mode);
                showFirstUseInfo(new_photo_mode);
            }
            if( preview.isVideo() ) {
                // switching to photo also sets up the camera for the new photo mode
                main_activity.switchVideoMode(false);
            }
            else if( photo_mode_changed ) {
                main_activity.getApplicationInterface().getDrawPreview().updateSettings(); // because we cache the photo mode
                main_activity.updateForSettings(true, "", false, true); // empty toast message, as the tabs show the mode
            }
        }
        updateModeTabs();
    }

    private void setPhotoModePref(MyApplicationInterface.PhotoMode photo_mode) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PreferenceKeys.PhotoModePreferenceKey, MyApplicationInterface.getPhotoModePreferenceValue(photo_mode));
        editor.apply();
    }

    private void showFirstUseInfo(MyApplicationInterface.PhotoMode photo_mode) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        if( photo_mode == MyApplicationInterface.PhotoMode.CPL && !sharedPreferences.contains(PreferenceKeys.AubCplInfoPreferenceKey) ) {
            main_activity.getMainUI().showInfoDialog(R.string.photo_mode_aub_cpl_full, R.string.aub_cpl_info, PreferenceKeys.AubCplInfoPreferenceKey);
        }
        else if( photo_mode == MyApplicationInterface.PhotoMode.Portrait && !sharedPreferences.contains(PreferenceKeys.AubPortraitInfoPreferenceKey) ) {
            main_activity.getMainUI().showInfoDialog(R.string.photo_mode_aub_portrait_full, R.string.aub_portrait_info, PreferenceKeys.AubPortraitInfoPreferenceKey);
        }
    }

    /** Swipes across the viewfinder move to the next or previous mode, as in GrapheneOS Camera.
     *  Receives all touch events on the viewfinder.
     */
    public void onPreviewTouch(MotionEvent event) {
        switch( event.getActionMasked() ) {
            case MotionEvent.ACTION_DOWN:
                swipe_start_x = event.getX();
                swipe_start_y = event.getY();
                swipe_cancelled = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // pinch zoom
                swipe_cancelled = true;
                break;
            case MotionEvent.ACTION_UP:
                if( !swipe_cancelled ) {
                    float dx = event.getX() - swipe_start_x;
                    float dy = event.getY() - swipe_start_y;
                    if( Math.abs(dx) > dpToPx(80) && Math.abs(dx) > 2.0f*Math.abs(dy) ) {
                        int index = tab_modes.indexOf(getCurrentMode());
                        // swiping to the left moves to the mode on the right, like scrolling the tabs
                        int new_index = index + (dx < 0 ? 1 : -1);
                        if( index != -1 && new_index >= 0 && new_index < tab_modes.size() ) {
                            selectMode(tab_modes.get(new_index));
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                swipe_cancelled = true;
                break;
        }
    }

    private void updateShutterForScanMode() {
        // no shutter in scan mode - codes are found automatically
        View takePhotoButton = main_activity.findViewById(R.id.take_photo);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        boolean show_shutter = !scan_mode && sharedPreferences.getBoolean(PreferenceKeys.ShowTakePhotoPreferenceKey, true) && !main_activity.getMainUI().inImmersiveMode();
        if( scan_mode && takePhotoButton.getVisibility() == View.VISIBLE ) {
            takePhotoButton.setVisibility(View.INVISIBLE);
        }
        else if( !scan_mode && show_shutter && takePhotoButton.getVisibility() != View.VISIBLE && main_activity.getPreview().getCameraController() != null ) {
            takePhotoButton.setVisibility(View.VISIBLE);
        }
    }

    /* Zoom chips.
     */

    private static String formatZoom(float ratio) {
        float rounded = Math.round(ratio*10.0f)/10.0f;
        if( rounded == (int)rounded )
            return String.valueOf((int)rounded);
        return String.format(Locale.US, "%.1f", rounded);
    }

    public void setControlsHidden(boolean hidden) {
        if( controls_hidden != hidden ) {
            controls_hidden = hidden;
            updateZoomChips();
        }
    }

    public void updateZoomChips() {
        Preview preview = main_activity.getPreview();
        if( preview == null || preview.getCameraController() == null ) {
            return;
        }
        updateZoomChips(preview.getCameraController().getZoom());
    }

    /** Updates the zoom chips for the supplied zoom index (which may be a zoom we're moving to).
     */
    public void updateZoomChips(int zoom_index) {
        LinearLayout container = main_activity.findViewById(R.id.aub_zoom_chips);
        Preview preview = main_activity.getPreview();
        List<Float> ratios = new ArrayList<>();
        if( preview != null && preview.supportsZoom() ) {
            float min_ratio = preview.getMinZoomRatio();
            float max_ratio = preview.getMaxZoomRatio();
            if( min_ratio < 0.95f )
                ratios.add(min_ratio); // ultra wide
            ratios.add(1.0f);
            for(float ratio : Arrays.asList(2.0f, 5.0f, 10.0f)) {
                if( max_ratio >= ratio )
                    ratios.add(ratio);
            }
        }
        if( ratios.size() < 2 || controls_hidden ) {
            container.setVisibility(View.GONE);
            return;
        }
        if( !ratios.equals(zoom_chip_ratios) ) {
            createZoomChips(ratios);
        }
        container.setVisibility(View.VISIBLE);

        float zoom_ratio = preview.getZoomRatio(Math.max(0, Math.min(zoom_index, preview.getMaxZoom())));
        int selected = 0;
        for(int i=0;i<zoom_chip_ratios.size();i++) {
            if( zoom_ratio >= zoom_chip_ratios.get(i)*0.99f )
                selected = i;
        }
        for(int i=0;i<zoom_chip_views.size();i++) {
            TextView chip = zoom_chip_views.get(i);
            boolean is_selected = i == selected;
            chip.setText(is_selected ? formatZoom(zoom_ratio) + "×" : formatZoom(zoom_chip_ratios.get(i)));
            chip.setTextColor(is_selected ? 0xff000000 : 0xffffffff);
            chip.setBackgroundResource(is_selected ? R.drawable.aub_zoom_chip_selected : R.drawable.aub_zoom_chip);
        }
    }

    private void createZoomChips(List<Float> ratios) {
        LinearLayout container = main_activity.findViewById(R.id.aub_zoom_chips);
        container.removeAllViews();
        zoom_chip_ratios.clear();
        zoom_chip_views.clear();
        for(final float ratio : ratios) {
            TextView chip = new TextView(main_activity);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setContentDescription(main_activity.getResources().getString(R.string.aub_zoom_to, formatZoom(ratio)));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    zoomToRatio(ratio);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dpToPx(38), dpToPx(38));
            params.setMargins(dpToPx(4), 0, dpToPx(4), 0);
            container.addView(chip, params);
            chip.setRotation(icon_rotation);
            zoom_chip_ratios.add(ratio);
            zoom_chip_views.add(chip);
        }
    }

    private void zoomToRatio(float ratio) {
        Preview preview = main_activity.getPreview();
        if( preview.getCameraController() == null || !preview.supportsZoom() )
            return;
        int best_index = 0;
        float best_diff = Float.MAX_VALUE;
        for(int i=0;i<=preview.getMaxZoom();i++) {
            float diff = Math.abs(preview.getZoomRatio(i) - ratio);
            if( diff < best_diff ) {
                best_diff = diff;
                best_index = i;
            }
        }
        if( MyDebug.LOG )
            Log.d(TAG, "zoomToRatio: " + ratio + " index: " + best_index);
        // the zoom seekbar's listener does the zooming, except for smooth zoom
        if( preview.hasSmoothZoom() )
            preview.zoomTo(best_index, false, true);
        main_activity.getMainUI().setSeekbarZoom(best_index);
        updateZoomChips(best_index);
    }

    /* Icon rotation - the screen stays in portrait, as in GrapheneOS Camera, so the icons are
     * rotated instead.
     */

    public int getIconRotation() {
        return icon_rotation;
    }

    /** The views that are rotated to stay upright.
     */
    private List<View> getRotatingViews() {
        List<View> views = new ArrayList<>();
        int [] ids = new int[] {
                R.id.gallery,
                R.id.switch_camera,
                R.id.switch_multi_camera,
                R.id.pause_video,
                R.id.take_photo_when_video_recording,
                R.id.cancel_panorama,
                R.id.settings,
                R.id.popup,
                R.id.exposure,
                R.id.exposure_lock,
                R.id.white_balance_lock,
                R.id.cycle_raw,
                R.id.store_location,
                R.id.text_stamp,
                R.id.stamp,
                R.id.focus_peaking,
                R.id.auto_level,
                R.id.cycle_flash,
                R.id.face_detection,
                R.id.audio_control,
                R.id.kraken_icon,
                R.id.trash,
                R.id.share
        };
        for(int id : ids) {
            views.add(main_activity.findViewById(id));
        }
        views.addAll(zoom_chip_views);
        return views;
    }

    public void onOrientationChanged(int orientation) {
        if( orientation == OrientationEventListener.ORIENTATION_UNKNOWN )
            return;
        if( device_orientation != -1 ) {
            int diff = Math.abs(orientation - device_orientation);
            if( diff > 180 )
                diff = 360 - diff;
            // only change orientation when sufficiently changed
            if( diff <= 60 )
                return;
        }
        int new_orientation = ((orientation + 45) / 90 * 90) % 360;
        if( new_orientation == device_orientation )
            return;
        device_orientation = new_orientation;

        int degrees = 0;
        switch( main_activity.getWindowManager().getDefaultDisplay().getRotation() ) {
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }
        // getRotation is anti-clockwise, but the orientation is clockwise, so we add rather than subtract
        int relative_orientation = (device_orientation + degrees) % 360;
        int new_icon_rotation = (360 - relative_orientation) % 360;
        if( MyDebug.LOG )
            Log.d(TAG, "onOrientationChanged: device_orientation " + device_orientation + " icon_rotation " + new_icon_rotation);
        if( new_icon_rotation != icon_rotation ) {
            icon_rotation = new_icon_rotation;
            for(View view : getRotatingViews()) {
                rotateView(view, true);
            }
        }
    }

    /** Sets the view's rotation to keep it upright.
     */
    void rotateView(View view, boolean animate) {
        if( !animate ) {
            view.animate().cancel();
            view.setRotation(icon_rotation);
            return;
        }
        float rotate_by = icon_rotation - view.getRotation();
        while( rotate_by > 180.0f )
            rotate_by -= 360.0f;
        while( rotate_by < -180.0f )
            rotate_by += 360.0f;
        view.animate().rotationBy(rotate_by).setDuration(200).setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    /* QR / barcode scan mode.
     */

    private static boolean canOpen(Uri uri) {
        String scheme = uri.getScheme();
        if( scheme == null )
            return false;
        switch( scheme.toLowerCase(Locale.US) ) {
            case "http":
            case "https":
            case "mailto":
            case "tel":
            case "sms":
            case "smsto":
            case "geo":
            case "market":
                return true;
            default:
                return false;
        }
    }

    private String getFormatName(String format) {
        if( format.equals("QR_CODE") )
            return main_activity.getResources().getString(R.string.aub_scan_qr_code);
        // e.g. "DATA_MATRIX" -> "Data matrix", "EAN_13" -> "EAN-13"
        if( format.startsWith("EAN_") || format.startsWith("UPC_") || format.startsWith("CODE_") )
            return format.replace('_', '-');
        String name = format.replace('_', ' ').toLowerCase(Locale.US);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** A code has been found in the viewfinder.
     */
    public void onBarcodeScanned(final String text, String format) {
        if( MyDebug.LOG )
            Log.d(TAG, "onBarcodeScanned: " + format);
        if( !scan_mode || barcode_dialog != null || TextUtils.isEmpty(text) || main_activity.isFinishing() )
            return;
        if( text.equals(last_barcode) && System.currentTimeMillis() < last_barcode_time + barcode_repeat_delay_ms )
            return;

        View preview_view = main_activity.getPreview().getView();
        preview_view.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);

        final Uri uri = Uri.parse(text.trim());
        final boolean can_open = canOpen(uri);

        TextView message = new TextView(main_activity);
        message.setText(text);
        message.setTextIsSelectable(true);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        int padding = dpToPx(24);
        message.setPadding(padding, dpToPx(12), padding, 0);

        AlertDialog.Builder builder = new AlertDialog.Builder(main_activity);
        builder.setTitle(getFormatName(format));
        builder.setView(message);
        if( can_open ) {
            builder.setPositiveButton(R.string.aub_scan_open, (dialog, which) -> {
                try {
                    main_activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
                }
                catch(ActivityNotFoundException e) {
                    MyDebug.logStackTrace(TAG, "no activity to open scanned code", e);
                    Toast.makeText(main_activity, R.string.aub_scan_no_app, Toast.LENGTH_SHORT).show();
                }
            });
        }
        builder.setNeutralButton(R.string.aub_scan_copy, (dialog, which) -> {
            ClipboardManager clipboard = (ClipboardManager)main_activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(getFormatName(format), text));
            if( Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ) {
                // Android 13+ shows its own confirmation
                Toast.makeText(main_activity, R.string.aub_scan_copied, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(R.string.aub_scan_share, (dialog, which) -> {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            main_activity.startActivity(Intent.createChooser(intent, null));
        });
        barcode_dialog = builder.create();
        barcode_dialog.setOnDismissListener(dialog -> {
            barcode_dialog = null;
            last_barcode = text;
            last_barcode_time = System.currentTimeMillis();
        });
        barcode_dialog.show();
    }

    public void dismissBarcodeDialog() {
        if( barcode_dialog != null ) {
            barcode_dialog.dismiss();
            barcode_dialog = null;
        }
    }
}
