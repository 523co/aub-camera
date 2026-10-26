/*
 * Aub Camera - a fork of Open Camera by Mark Harman.
 * This file is part of Aub Camera and is licensed under the GNU General Public License v3 or later.
 */
package net.sourceforge.opencamera.aub;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import net.sourceforge.opencamera.MyDebug;
import net.sourceforge.opencamera.PreferenceKeys;

import java.io.ByteArrayOutputStream;
import java.util.Date;

/** Entry point for the Aub Camera photo modes (Portrait, CPL, PS2, Digicam and Disposable): turns preferences
 *  into effect parameters, and applies effects to android.graphics.Bitmaps, both for saved photos
 *  and for the live look preview in the viewfinder.
 */
public final class AubEffects {
    private static final String TAG = "AubEffects";

    private AubEffects() {
    }

    public enum Effect {
        NONE,
        PORTRAIT,
        CPL,
        PS2,
        DIGICAM,
        DISPOSABLE
    }

    public enum CplType {
        SIMULATED,          // no filter attached: simulate the look
        PHYSICAL,           // real filter attached: show the glare meter, don't alter the photo
        PHYSICAL_PLUS_SIM   // real filter attached, plus a lighter simulated boost
    }

    /** Immutable snapshot of the settings for one capture. */
    public static final class Params {
        public final Effect effect;
        public final float strength; // 0..1, meaning depends on the effect
        public final CplType cpl_type;
        public final boolean ps2_scanlines;
        public final boolean digicam_authentic_res;
        public final int digicam_stamp;
        public final boolean disposable_flash;
        public final boolean disposable_leak;
        public final Date date;

        public Params(Effect effect, float strength, CplType cpl_type, boolean ps2_scanlines, boolean digicam_authentic_res, int digicam_stamp, boolean disposable_flash, boolean disposable_leak, Date date) {
            this.effect = effect;
            this.strength = strength;
            this.cpl_type = cpl_type;
            this.ps2_scanlines = ps2_scanlines;
            this.digicam_authentic_res = digicam_authentic_res;
            this.digicam_stamp = digicam_stamp;
            this.disposable_flash = disposable_flash;
            this.disposable_leak = disposable_leak;
            this.date = date;
        }

        /** Whether this effect changes the photo at all (a physical-only CPL doesn't). */
        public boolean altersImage() {
            if( effect == Effect.NONE )
                return false;
            return !(effect == Effect.CPL && cpl_type == CplType.PHYSICAL);
        }

        public String filenameSuffix() {
            switch( effect ) {
                case PORTRAIT: return "_Portrait";
                case CPL: return "_CPL";
                case PS2: return "_PS2";
                case DIGICAM: return "_Digicam";
                case DISPOSABLE: return "_Disposable";
                default: return "";
            }
        }

        /** Strength actually used by the CPL simulation, taking the filter type into account. */
        float cplSimStrength() {
            switch( cpl_type ) {
                case PHYSICAL: return 0.0f;
                case PHYSICAL_PLUS_SIM: return strength * 0.45f;
                default: return strength;
            }
        }
    }

    /** Reads the current settings for the given effect. */
    public static Params readParams(SharedPreferences sp, Effect effect, Date date) {
        float strength;
        if( effect == Effect.PORTRAIT )
            strength = parsePercent(sp.getString(PreferenceKeys.AubPortraitBlurPreferenceKey, "50"));
        else
            strength = parsePercent(sp.getString(PreferenceKeys.AubCplStrengthPreferenceKey, "60"));
        CplType cpl_type;
        switch( sp.getString(PreferenceKeys.AubCplTypePreferenceKey, "simulated") ) {
            case "physical":
                cpl_type = CplType.PHYSICAL;
                break;
            case "physical_plus_sim":
                cpl_type = CplType.PHYSICAL_PLUS_SIM;
                break;
            default:
                cpl_type = CplType.SIMULATED;
                break;
        }
        boolean scanlines = sp.getBoolean(PreferenceKeys.AubPs2ScanlinesPreferenceKey, true);
        boolean authentic = sp.getBoolean(PreferenceKeys.AubDigicamAuthenticResPreferenceKey, true);
        int stamp;
        switch( sp.getString(PreferenceKeys.AubDigicamStampPreferenceKey, "real") ) {
            case "off":
                stamp = DigicamFilter.STAMP_OFF;
                break;
            case "retro":
                stamp = DigicamFilter.STAMP_RETRO;
                break;
            default:
                stamp = DigicamFilter.STAMP_REAL;
                break;
        }
        boolean flash = sp.getBoolean(PreferenceKeys.AubDisposableFlashPreferenceKey, false);
        boolean leak = sp.getBoolean(PreferenceKeys.AubDisposableLeakPreferenceKey, true);
        return new Params(effect, strength, cpl_type, scanlines, authentic, stamp, flash, leak, date == null ? new Date() : date);
    }

    private static float parsePercent(String s) {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(s))) / 100.0f;
        }
        catch(NumberFormatException e) {
            return 0.5f;
        }
    }

    /** RowAccess over an android Bitmap, so effects can work in strips without copying the whole photo. */
    static final class BitmapRows implements AubImage.RowAccess {
        private final Bitmap bitmap;

        BitmapRows(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override public int width() { return bitmap.getWidth(); }
        @Override public int height() { return bitmap.getHeight(); }

        @Override
        public void getRows(int [] dst, int y0, int n_rows) {
            bitmap.getPixels(dst, 0, bitmap.getWidth(), 0, y0, bitmap.getWidth(), n_rows);
        }

        @Override
        public void setRows(int [] src, int y0, int n_rows) {
            bitmap.setPixels(src, 0, bitmap.getWidth(), 0, y0, bitmap.getWidth(), n_rows);
        }
    }

    private static Bitmap toMutableBitmap(AubImage.Img img) {
        Bitmap bitmap = Bitmap.createBitmap(img.w, img.h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(img.px, 0, img.w, 0, 0, img.w, img.h);
        return bitmap;
    }

    /** Applies the effect to an upright photo before it is saved.
     *  @param bitmap The photo; must be mutable ARGB_8888 (as returned by ImageSaver.loadBitmapWithRotation(data, true)).
     *  @return The processed photo: either the same bitmap modified in place, or a new bitmap (in which case
     *          the input has been recycled).
     */
    public static Bitmap applyToPhoto(Context context, Bitmap bitmap, Params params) {
        long time_s = System.currentTimeMillis();
        if( MyDebug.LOG )
            Log.d(TAG, "applyToPhoto: " + params.effect + " size " + bitmap.getWidth() + "x" + bitmap.getHeight());
        if( !bitmap.isMutable() || bitmap.getConfig() != Bitmap.Config.ARGB_8888 ) {
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            bitmap.recycle();
            bitmap = copy;
        }
        Bitmap result = bitmap;
        switch( params.effect ) {
            case CPL: {
                float s = params.cplSimStrength();
                if( s > 0.0f )
                    CplFilter.apply(new BitmapRows(bitmap), s, true);
                break;
            }
            case PS2: {
                AubImage.Img out = Ps2Filter.apply(new BitmapRows(bitmap), params.ps2_scanlines, true);
                result = toMutableBitmap(out);
                break;
            }
            case DIGICAM: {
                int seed = (int)(params.date.getTime() ^ (params.date.getTime() >>> 32));
                AubImage.Img out = DigicamFilter.apply(new BitmapRows(bitmap), params.digicam_authentic_res, params.digicam_stamp, params.date, seed);
                result = jpegCrunch(toMutableBitmap(out), 64);
                break;
            }
            case DISPOSABLE: {
                int seed = (int)(params.date.getTime() ^ (params.date.getTime() >>> 32));
                DisposableFilter.apply(new BitmapRows(bitmap), params.disposable_flash, params.disposable_leak, seed);
                break;
            }
            case PORTRAIT: {
                int [] dims = AubImage.fitLongSide(bitmap.getWidth(), bitmap.getHeight(), PortraitCompositor.WORK_LONG_SIDE);
                AubImage.Img work = AubImage.downscaleArea(new BitmapRows(bitmap), dims[0], dims[1]);
                Bitmap work_bitmap = toMutableBitmap(work);
                float [] mask = PortraitSegmenter.subjectMask(context, work_bitmap, true);
                work_bitmap.recycle();
                if( mask == null ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "portrait: no subject found, leaving photo unchanged");
                    break;
                }
                PortraitCompositor.process(new BitmapRows(bitmap), work, mask, params.strength, false);
                break;
            }
            default:
                break;
        }
        if( result != bitmap )
            bitmap.recycle();
        if( MyDebug.LOG )
            Log.d(TAG, "applyToPhoto took " + (System.currentTimeMillis() - time_s) + "ms");
        return result;
    }

    /** Re-encodes as a lower quality JPEG and decodes again, to bake in era-appropriate compression artifacts. */
    private static Bitmap jpegCrunch(Bitmap bitmap, int quality) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            byte [] data = bos.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inMutable = true;
            Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if( decoded != null ) {
                bitmap.recycle();
                return decoded;
            }
        }
        catch(RuntimeException e) {
            Log.e(TAG, "jpegCrunch failed", e);
        }
        return bitmap;
    }

    /** Applies a fast version of the effect to a small live preview frame (already rotated for display).
     *  Returns a new bitmap, or null if there's nothing to show (e.g. physical-only CPL, or no subject found).
     *  The input is never modified or recycled.
     */
    public static Bitmap applyToPreview(Context context, Bitmap frame, Params params) {
        if( !params.altersImage() )
            return null;
        AubImage.Img img = new AubImage.Img(frame.getWidth(), frame.getHeight());
        frame.getPixels(img.px, 0, img.w, 0, 0, img.w, img.h);
        switch( params.effect ) {
            case CPL:
                // the preview frame isn't necessarily upright in the world, so don't assume sky is at the top
                CplFilter.apply(img, params.cplSimStrength(), false);
                return toMutableBitmap(img);
            case PS2: {
                AubImage.Img out = Ps2Filter.apply(img, false, false);
                return toMutableBitmap(out);
            }
            case DIGICAM: {
                AubImage.Img out = DigicamFilter.apply(img, false, DigicamFilter.STAMP_OFF, params.date, 1234);
                return toMutableBitmap(out);
            }
            case DISPOSABLE:
                // no light leak, as it's different for every photo; a new grain pattern each frame looks like film
                DisposableFilter.apply(img, params.disposable_flash, false, (int)System.nanoTime());
                return toMutableBitmap(img);
            case PORTRAIT: {
                int [] dims = AubImage.fitLongSide(img.w, img.h, 320);
                AubImage.Img work = dims[0] == img.w && dims[1] == img.h ? img : AubImage.downscaleArea(img, dims[0], dims[1]);
                Bitmap work_bitmap = toMutableBitmap(work);
                float [] mask = PortraitSegmenter.subjectMask(context, work_bitmap, false);
                work_bitmap.recycle();
                if( mask == null )
                    return null;
                PortraitCompositor.process(img, work, mask, params.strength, true);
                return toMutableBitmap(img);
            }
            default:
                return null;
        }
    }
}
