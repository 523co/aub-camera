/*
 * Aub Camera - a fork of Open Camera by Mark Harman.
 * This file is part of Aub Camera and is licensed under the GNU General Public License v3 or later.
 */
package net.sourceforge.opencamera.aub;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.ByteBufferExtractor;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter;
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult;

import net.sourceforge.opencamera.MyDebug;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** On-device subject segmentation for portrait mode, using MediaPipe Tasks (Apache 2.0).
 *  Runs entirely on the phone; no network access.
 *  <ul>
 *  <li>First choice: the multiclass selfie model (people, including hair and clothes).</li>
 *  <li>Fallback when no person is found: DeepLab v3 (PASCAL VOC classes, which includes pets,
 *      plants, bottles, chairs etc), so non-human subjects still get a portrait.</li>
 *  </ul>
 *  The model files are fetched into assets/aub_models at build time (see app/build.gradle).
 */
public final class PortraitSegmenter {
    private static final String TAG = "PortraitSegmenter";

    static final String MODEL_PERSON = "aub_models/selfie_multiclass_256x256.tflite";
    static final String MODEL_GENERAL = "aub_models/deeplab_v3.tflite";

    private static final ReentrantLock lock = new ReentrantLock();
    private static ImageSegmenter person_segmenter;
    private static ImageSegmenter general_segmenter;
    private static boolean failed_person;
    private static boolean failed_general;

    private PortraitSegmenter() {
    }

    private static ImageSegmenter create(Context context, String model) {
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath(model)
                .setDelegate(Delegate.CPU)
                .build();
        ImageSegmenter.ImageSegmenterOptions options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build();
        return ImageSegmenter.createFromOptions(context.getApplicationContext(), options);
    }

    /** Returns the subject mask (1 = subject) for the supplied ARGB_8888 bitmap, at the bitmap's
     *  resolution, or null if no subject was found or segmentation isn't available.
     *  @param wait If false (live preview), gives up immediately if a capture is being processed.
     */
    public static float [] subjectMask(Context context, Bitmap bitmap, boolean wait) {
        try {
            if( wait )
                lock.lock();
            else if( !lock.tryLock(0, TimeUnit.MILLISECONDS) )
                return null;
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            float [] mask = null;
            if( !failed_person ) {
                try {
                    if( person_segmenter == null )
                        person_segmenter = create(context, MODEL_PERSON);
                    mask = run(person_segmenter, bitmap);
                }
                catch(RuntimeException | UnsatisfiedLinkError e) {
                    Log.e(TAG, "person segmentation failed", e);
                    failed_person = true;
                    mask = null;
                }
            }
            float person_coverage = mask == null ? 0.0f : PortraitCompositor.coverage(mask);
            // no person found - or an implausibly full-frame "person", which the selfie model can produce for
            // close-ups of animals and textures - so try the general model too
            if( (person_coverage < 0.01f || person_coverage > 0.90f) && !failed_general ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "person coverage " + person_coverage + ", trying general model");
                try {
                    if( general_segmenter == null )
                        general_segmenter = create(context, MODEL_GENERAL);
                    float [] general = run(general_segmenter, bitmap);
                    float general_coverage = general == null ? 0.0f : PortraitCompositor.coverage(general);
                    if( general_coverage >= 0.01f && general_coverage <= 0.95f )
                        mask = general;
                }
                catch(RuntimeException | UnsatisfiedLinkError e) {
                    Log.e(TAG, "general segmentation failed", e);
                    failed_general = true;
                }
            }
            if( mask != null && PortraitCompositor.coverage(mask) < 0.01f )
                mask = null;
            return mask;
        }
        finally {
            lock.unlock();
        }
    }

    /** For both models, confidence mask 0 is "background", so subject = 1 - background. */
    private static float [] run(ImageSegmenter segmenter, Bitmap bitmap) {
        MPImage image = new BitmapImageBuilder(bitmap).build();
        ImageSegmenterResult result = segmenter.segment(image);
        if( !result.confidenceMasks().isPresent() )
            return null;
        List<MPImage> masks = result.confidenceMasks().get();
        if( masks.isEmpty() )
            return null;
        MPImage bg = masks.get(0);
        int mw = bg.getWidth(), mh = bg.getHeight();
        FloatBuffer fb = ByteBufferExtractor.extract(bg).asFloatBuffer();
        float [] bg_mask = new float[mw * mh];
        fb.get(bg_mask, 0, Math.min(bg_mask.length, fb.remaining()));
        for(MPImage m : masks) {
            m.close();
        }
        image.close();

        final int w = bitmap.getWidth(), h = bitmap.getHeight();
        float [] subject = new float[w * h];
        if( mw == w && mh == h ) {
            for(int i=0;i<subject.length;i++) {
                subject[i] = 1.0f - bg_mask[i];
            }
        }
        else {
            final float sx = (float)mw / w, sy = (float)mh / h;
            for(int y=0;y<h;y++) {
                for(int x=0;x<w;x++) {
                    subject[y * w + x] = 1.0f - AubImage.sample(bg_mask, mw, mh, (x + 0.5f) * sx - 0.5f, (y + 0.5f) * sy - 0.5f);
                }
            }
        }
        return subject;
    }

    /** Frees the models; call when the activity is destroyed. */
    public static void close() {
        lock.lock();
        try {
            if( person_segmenter != null ) {
                person_segmenter.close();
                person_segmenter = null;
            }
            if( general_segmenter != null ) {
                general_segmenter.close();
                general_segmenter = null;
            }
        }
        finally {
            lock.unlock();
        }
    }
}
