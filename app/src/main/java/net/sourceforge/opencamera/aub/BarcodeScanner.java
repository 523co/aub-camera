package net.sourceforge.opencamera.aub;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.TextureView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.ReaderException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import net.sourceforge.opencamera.MyDebug;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/** Aub Camera: finds QR codes and barcodes in viewfinder frames, entirely on-device (ZXing).
 *  Only the square in the middle of the frame is searched, matching the frame drawn on screen.
 */
public class BarcodeScanner {
    private static final String TAG = "BarcodeScanner";

    /** Size of the square that is searched, as a fraction of the shorter side of the viewfinder. */
    public static final float scan_area_fraction = 0.75f;
    /** Longest side the viewfinder frame is downscaled to before searching. */
    private static final int max_frame_size = 1280;

    private final MultiFormatReader reader = new MultiFormatReader();
    private int frame_count;

    public BarcodeScanner() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.AZTEC,
                BarcodeFormat.PDF_417,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A,
                BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.CODE_93,
                BarcodeFormat.ITF,
                BarcodeFormat.CODABAR));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);
    }

    /** Searches the current viewfinder frame. Should be called on a background thread.
     *  @return The decoded result, or null if nothing was found.
     */
    public Result scan(TextureView textureView) {
        int view_w = textureView.getWidth();
        int view_h = textureView.getHeight();
        if( view_w <= 0 || view_h <= 0 )
            return null;
        float scale = Math.min(1.0f, max_frame_size / (float)Math.max(view_w, view_h));
        int frame_w = Math.max(1, Math.round(view_w * scale));
        int frame_h = Math.max(1, Math.round(view_h * scale));
        Bitmap frame = textureView.getBitmap(frame_w, frame_h);
        if( frame == null )
            return null;
        try {
            int size = (int)(Math.min(frame_w, frame_h) * scan_area_fraction);
            int left = (frame_w - size)/2;
            int top = (frame_h - size)/2;
            int [] pixels = new int[size*size];
            frame.getPixels(pixels, 0, size, left, top, size, size);
            LuminanceSource source = new RGBLuminanceSource(size, size, pixels);
            // every other frame, also look for light-on-dark codes
            if( (frame_count++ % 2) == 1 )
                source = source.invert();
            return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
        }
        catch(ReaderException e) {
            // nothing found in this frame
            return null;
        }
        catch(RuntimeException e) {
            Log.e(TAG, "failed to scan frame", e);
            return null;
        }
        finally {
            reader.reset();
            frame.recycle();
            if( MyDebug.LOG )
                Log.d(TAG, "scanned frame " + frame_w + " x " + frame_h);
        }
    }
}
