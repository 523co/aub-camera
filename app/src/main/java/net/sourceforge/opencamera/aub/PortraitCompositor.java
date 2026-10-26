/*
 * Aub Camera - a fork of Open Camera by Mark Harman.
 * This file is part of Aub Camera and is licensed under the GNU General Public License v3 or later.
 */
package net.sourceforge.opencamera.aub;

import static net.sourceforge.opencamera.aub.AubImage.*;

/** The framework independent half of portrait mode: given a low resolution subject mask (from the
 *  on-device segmentation model), refines its edges against the photo, renders a lens-like
 *  background blur, and composites at full resolution.
 */
public final class PortraitCompositor {
    private PortraitCompositor() {
    }

    /** Long side of the working resolution used for mask refinement and the background blur. */
    public static final int WORK_LONG_SIDE = 1024;

    /** Edge-aware refinement of a soft mask using the guided filter (He et al.) with the photo's
     *  luminance as the guide. This snaps the mask to real edges (hair, ears, fingers) that the low
     *  resolution model output misses.
     */
    public static float [] refineMask(Img guide_img, float [] mask, int radius, float eps) {
        final int w = guide_img.w, h = guide_img.h, n = w * h;
        float [] I = new float[n];
        for(int i=0;i<n;i++) {
            int c = guide_img.px[i];
            I[i] = (0.299f * ((c >> 16) & 0xff) + 0.587f * ((c >> 8) & 0xff) + 0.114f * (c & 0xff)) / 255.0f;
        }
        float [] meanI = I.clone();
        float [] meanP = mask.clone();
        float [] corrII = new float[n];
        float [] corrIP = new float[n];
        for(int i=0;i<n;i++) {
            corrII[i] = I[i] * I[i];
            corrIP[i] = I[i] * mask[i];
        }
        boxBlur(meanI, w, h, radius);
        boxBlur(meanP, w, h, radius);
        boxBlur(corrII, w, h, radius);
        boxBlur(corrIP, w, h, radius);
        float [] a = corrII; // reuse arrays
        float [] b = corrIP;
        for(int i=0;i<n;i++) {
            float varI = corrII[i] - meanI[i] * meanI[i];
            float covIP = corrIP[i] - meanI[i] * meanP[i];
            float ai = covIP / (varI + eps);
            a[i] = ai;
            b[i] = meanP[i] - ai * meanI[i];
        }
        boxBlur(a, w, h, radius);
        boxBlur(b, w, h, radius);
        float [] q = new float[n];
        for(int i=0;i<n;i++) {
            q[i] = clamp01(a[i] * I[i] + b[i]);
        }
        return q;
    }

    /** Result of rendering the blurred background at working resolution. */
    public static final class Background {
        public final int w, h;
        public final float [] r, g, b;

        Background(int w, int h, float [] r, float [] g, float [] b) {
            this.w = w;
            this.h = h;
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    /** Renders the out of focus background.
     *  Uses normalised convolution so the subject's colours don't bleed into the background as a halo,
     *  and works in a linear-ish space with boosted highlights so bright points bloom like real bokeh.
     *  @param sigma Blur sigma in working-resolution pixels.
     */
    public static Background renderBackground(Img img, float [] alpha, float sigma) {
        final int w = img.w, h = img.h, n = w * h;
        float [] r = new float[n], g = new float[n], b = new float[n], wt = new float[n];
        for(int i=0;i<n;i++) {
            int c = img.px[i];
            float pr = ((c >> 16) & 0xff) / 255.0f, pg = ((c >> 8) & 0xff) / 255.0f, pb = (c & 0xff) / 255.0f;
            // to linear (gamma 2.2 approximation)
            pr = pr * pr * (0.75f + 0.25f * pr) ;
            pg = pg * pg * (0.75f + 0.25f * pg);
            pb = pb * pb * (0.75f + 0.25f * pb);
            float L = luma(pr, pg, pb);
            float boost = 1.0f + 2.5f * smoothstep(0.55f, 1.0f, L); // highlights bloom into bokeh discs
            float bw = 1.0f - alpha[i];
            bw = bw * bw;
            wt[i] = bw;
            r[i] = pr * boost * bw;
            g[i] = pg * boost * bw;
            b[i] = pb * boost * bw;
        }
        gaussianBlur(r, w, h, sigma);
        gaussianBlur(g, w, h, sigma);
        gaussianBlur(b, w, h, sigma);
        gaussianBlur(wt, w, h, sigma);
        for(int i=0;i<n;i++) {
            float inv = 1.0f / Math.max(wt[i], 1.0e-3f);
            r[i] = toGamma(r[i] * inv);
            g[i] = toGamma(g[i] * inv);
            b[i] = toGamma(b[i] * inv);
        }
        return new Background(w, h, r, g, b);
    }

    private static float toGamma(float v) {
        // inverse of the approximation above, with a soft shoulder for boosted highlights
        if( v > 1.0f )
            v = 1.0f + (1.0f - (float)Math.exp(-(v - 1.0f)));
        float g = (float)Math.pow(Math.max(v, 0.0f), 1.0f / 2.2f);
        return clamp01(g);
    }

    /** Composites in place at full resolution: subject from the original, background from bg. */
    public static void composite(RowAccess image, float [] alpha, Background bg) {
        final int W = image.width(), H = image.height();
        final float sx = (float)bg.w / W, sy = (float)bg.h / H;
        int strip = stripRows(W);
        int [] buf = new int[strip * W];
        for(int y0=0;y0<H;y0+=strip) {
            int nrows = Math.min(strip, H - y0);
            image.getRows(buf, y0, nrows);
            for(int j=0;j<nrows;j++) {
                float fy = (y0 + j + 0.5f) * sy - 0.5f;
                int row = j * W;
                for(int x=0;x<W;x++) {
                    float fx = (x + 0.5f) * sx - 0.5f;
                    float a = sample(alpha, bg.w, bg.h, fx, fy);
                    if( a >= 0.999f )
                        continue;
                    int c = buf[row + x];
                    float pr = ((c >> 16) & 0xff) / 255.0f, pg = ((c >> 8) & 0xff) / 255.0f, pb = (c & 0xff) / 255.0f;
                    float br = sample(bg.r, bg.w, bg.h, fx, fy);
                    float bgc = sample(bg.g, bg.w, bg.h, fx, fy);
                    float bb = sample(bg.b, bg.w, bg.h, fx, fy);
                    buf[row + x] = pack(mix(br, pr, a), mix(bgc, pg, a), mix(bb, pb, a));
                }
            }
            image.setRows(buf, y0, nrows);
        }
    }

    /** Blur sigma for the strength setting, as a fraction of the working image's long side. */
    public static float sigmaForStrength(int long_side, float strength) {
        // 0..1 -> roughly f/2.8 .. f/1.2 looks
        float frac = 0.006f + 0.016f * clamp01(strength);
        return Math.max(1.0f, long_side * frac);
    }

    /** Fraction of pixels considered subject. */
    public static float coverage(float [] mask) {
        int c = 0;
        for(float v : mask) {
            if( v > 0.5f )
                c++;
        }
        return mask.length == 0 ? 0.0f : (float)c / mask.length;
    }

    /** Whole pipeline given the working-resolution image and its raw subject mask.
     *  @param full The full resolution photo to modify in place (may be the same object as work).
     */
    public static void process(RowAccess full, Img work, float [] raw_mask, float strength, boolean fast) {
        float [] alpha;
        int long_side = Math.max(work.w, work.h);
        if( fast ) {
            alpha = raw_mask.clone();
            boxBlur(alpha, work.w, work.h, 1);
        }
        else {
            // feather slightly first, so the guided filter has a smooth input to snap to edges
            float [] m = raw_mask.clone();
            boxBlur(m, work.w, work.h, Math.max(1, long_side / 512));
            alpha = refineMask(work, m, Math.max(2, long_side / 128), 1.0e-3f);
            // tighten: the refined mask is soft in the transition region, steepen it a little
            for(int i=0;i<alpha.length;i++) {
                alpha[i] = smoothstep(0.15f, 0.85f, alpha[i]);
            }
        }
        Background bg = renderBackground(work, alpha, sigmaForStrength(long_side, strength));
        composite(full, alpha, bg);
    }
}
