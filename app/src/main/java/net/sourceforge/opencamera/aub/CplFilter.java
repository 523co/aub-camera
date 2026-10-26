/*
 * Aub Camera - a fork of Open Camera by Mark Harman.
 * This file is part of Aub Camera and is licensed under the GNU General Public License v3 or later.
 */
package net.sourceforge.opencamera.aub;

import static net.sourceforge.opencamera.aub.AubImage.*;

/** Simulated circular polarising filter.
 *
 *  A real CPL works optically, before light reaches the sensor, so its effect cannot be recovered
 *  exactly in software. This filter imitates the three things a CPL is used for:
 *  <ul>
 *  <li>Deeper, darker blue sky (strongest near the top of the frame, where sky usually is).</li>
 *  <li>Reduced glare: bright, colourless specular highlights on non-sky surfaces (water, leaves,
 *      glass, wet roads) are pulled back towards the colour of their surroundings.</li>
 *  <li>Less haze and richer foliage: a black point / dehaze step plus vibrance, extra for greens.</li>
 *  </ul>
 *  Everything is driven from a low resolution analysis of the frame, then applied at full
 *  resolution in strips, with masks bilinearly upsampled so there is no blockiness.
 */
public final class CplFilter {
    private CplFilter() {
    }

    private static final int ANALYSIS_LONG_SIDE = 320;

    /** Applies the simulated CPL look in place.
     * @param image    Image to modify (read and written in strips).
     * @param strength 0..1.
     * @param upright  Whether the image is known to be upright (top of image is up in the world), which
     *                 lets the sky detector favour the top of the frame. Use false for raw preview frames.
     */
    public static void apply(RowAccess image, float strength, boolean upright) {
        final int W = image.width(), H = image.height();
        if( W < 2 || H < 2 || strength <= 0.0f )
            return;
        final float s = clamp01(strength);

        // --- analysis at low resolution ---
        int [] dims = fitLongSide(W, H, ANALYSIS_LONG_SIDE);
        Img small = downscaleArea(image, dims[0], dims[1]);
        final int sw = small.w, sh = small.h, n = sw * sh;
        float [] r = new float[n], g = new float[n], b = new float[n];
        unpack(small.px, n, r, g, b);

        // sky mask
        float [] sky = new float[n];
        for(int y=0;y<sh;y++) {
            float pos = upright ? 1.0f - smoothstep(0.30f, 0.78f, (y + 0.5f) / sh) : 0.85f;
            for(int x=0;x<sw;x++) {
                int i = y * sw + x;
                float max = Math.max(r[i], Math.max(g[i], b[i]));
                float min = Math.min(r[i], Math.min(g[i], b[i]));
                float sat = max > 1.0e-4f ? (max - min) / max : 0.0f;
                float h = hue(r[i], g[i], b[i]);
                float w_hue = smoothstep(165.0f, 195.0f, h) * (1.0f - smoothstep(245.0f, 268.0f, h));
                float w_sat = smoothstep(0.05f, 0.16f, sat) * (1.0f - smoothstep(0.85f, 1.0f, sat));
                float w_val = smoothstep(0.30f, 0.55f, max);
                sky[i] = w_hue * w_sat * w_val * pos;
            }
        }
        gaussianBlur(sky, sw, sh, Math.max(1.0f, sw / 120.0f));

        // local mean colour, for glare recovery
        float [] mr = r.clone(), mg = g.clone(), mb = b.clone();
        float sigma = Math.max(1.5f, sw / 45.0f);
        gaussianBlur(mr, sw, sh, sigma);
        gaussianBlur(mg, sw, sh, sigma);
        gaussianBlur(mb, sw, sh, sigma);

        // black point per channel (0.5th percentile), for dehaze
        float bp_r = percentile(r, 0.005f), bp_g = percentile(g, 0.005f), bp_b = percentile(b, 0.005f);
        float bp = Math.min(bp_r, Math.min(bp_g, bp_b)); // use a common value so we don't shift colour balance
        final float haze = Math.min(bp, 0.12f) * 0.75f * s;
        final float haze_scale = 1.0f / (1.0f - haze);

        // --- apply at full resolution in strips ---
        final float sx = (float)sw / W, sy = (float)sh / H;
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
                    int c = buf[row + x];
                    float pr = ((c >> 16) & 0xff) * (1.0f / 255.0f);
                    float pg = ((c >> 8) & 0xff) * (1.0f / 255.0f);
                    float pb = (c & 0xff) * (1.0f / 255.0f);

                    // dehaze
                    pr = (pr - haze) * haze_scale;
                    pg = (pg - haze) * haze_scale;
                    pb = (pb - haze) * haze_scale;

                    float k_sky = sample(sky, sw, sh, fx, fy) * s;
                    float max = Math.max(pr, Math.max(pg, pb));
                    float min = Math.min(pr, Math.min(pg, pb));
                    float sat = max > 1.0e-4f ? (max - min) / max : 0.0f;

                    // glare: bright, low saturation, not sky - and much brighter than its surroundings, so that
                    // specular highlights are affected but large white objects (which a polariser leaves alone) aren't
                    float k_glare = smoothstep(0.60f, 0.92f, max) * (1.0f - smoothstep(0.06f, 0.28f, sat)) * (1.0f - k_sky / Math.max(s, 1.0e-3f)) * s * 0.75f;
                    if( k_glare > 0.001f ) {
                        float lr = sample(mr, sw, sh, fx, fy), lg = sample(mg, sw, sh, fx, fy), lb = sample(mb, sw, sh, fx, fy);
                        float lmax = Math.max(lr, Math.max(lg, lb));
                        k_glare *= smoothstep(0.10f, 0.30f, max - lmax);
                    }
                    if( k_glare > 0.001f ) {
                        float lr = sample(mr, sw, sh, fx, fy), lg = sample(mg, sw, sh, fx, fy), lb = sample(mb, sw, sh, fx, fy);
                        // keep some of the brightness, but take the hue of the surroundings, a bit darker
                        float lmax = Math.max(lr, Math.max(lg, lb));
                        float scale = lmax > 1.0e-3f ? Math.min(1.0f, 0.5f * (max + lmax) / lmax) * 0.92f : 0.92f;
                        pr = mix(pr, lr * scale, k_glare);
                        pg = mix(pg, lg * scale, k_glare);
                        pb = mix(pb, lb * scale, k_glare);
                    }

                    // sky: richer and darker, with red/green cut more than blue so it goes deep blue
                    if( k_sky > 0.001f ) {
                        float L = luma(pr, pg, pb);
                        float sat_boost = 1.0f + 0.55f * k_sky;
                        pr = L + (pr - L) * sat_boost;
                        pg = L + (pg - L) * sat_boost;
                        pb = L + (pb - L) * sat_boost;
                        pr *= 1.0f - 0.46f * k_sky;
                        pg *= 1.0f - 0.33f * k_sky;
                        pb *= 1.0f - 0.16f * k_sky;
                    }

                    // vibrance everywhere else, extra for foliage (a CPL removes the sheen on leaves)
                    float not_sky = 1.0f - Math.min(1.0f, k_sky / Math.max(s, 1.0e-3f));
                    float vib = 0.30f * s * not_sky * (1.0f - sat);
                    float h = hue(pr, pg, pb);
                    float foliage = smoothstep(55.0f, 80.0f, h) * (1.0f - smoothstep(150.0f, 175.0f, h)) * smoothstep(0.08f, 0.2f, sat);
                    vib += 0.22f * s * foliage * not_sky;
                    if( vib > 0.0f ) {
                        float L = luma(pr, pg, pb);
                        pr = L + (pr - L) * (1.0f + vib);
                        pg = L + (pg - L) * (1.0f + vib);
                        pb = L + (pb - L) * (1.0f + vib);
                    }
                    if( foliage > 0.0f ) {
                        float d = 1.0f - 0.07f * s * foliage * not_sky;
                        pr *= d; pg *= d; pb *= d;
                    }
                    buf[row + x] = pack(pr, pg, pb);
                }
            }
            image.setRows(buf, y0, nrows);
        }
    }

    static float percentile(float [] a, float p) {
        int [] hist = new int[256];
        for(float v : a) {
            hist[Math.min(255, Math.max(0, (int)(v * 255.0f)))]++;
        }
        long target = (long)(a.length * p);
        long acc = 0;
        for(int i=0;i<256;i++) {
            acc += hist[i];
            if( acc > target )
                return i / 255.0f;
        }
        return 1.0f;
    }

    /** Glare score for the live "physical CPL" helper: the mean brightness of the brightest 3% of
     *  pixels relative to the median. Rotating a real polariser to the angle that cuts the most
     *  reflections/sky glare minimises this. Auto exposure tends to keep the median steady, so the
     *  ratio tracks the polariser, not the exposure. Works on any orientation.
     */
    public static float glareScore(int [] px, int n) {
        int [] hist = new int[256];
        int count = 0;
        for(int i=0;i<n;i+=2) {
            int c = px[i];
            int l = (54 * ((c >> 16) & 0xff) + 183 * ((c >> 8) & 0xff) + 19 * (c & 0xff)) >> 8;
            hist[l]++;
            count++;
        }
        if( count == 0 )
            return 0.0f;
        int median = 0;
        long acc = 0;
        for(int i=0;i<256;i++) {
            acc += hist[i];
            if( acc >= count / 2 ) {
                median = i;
                break;
            }
        }
        long top_target = Math.max(1, (long)(count * 0.03));
        long top_count = 0;
        long top_sum = 0;
        for(int i=255;i>=0 && top_count<top_target;i--) {
            long take = Math.min(hist[i], top_target - top_count);
            top_count += take;
            top_sum += take * i;
        }
        float top_mean = (float)top_sum / top_count;
        return top_mean / Math.max(median, 8);
    }
}
