/*
 * Aub Camera - a fork of Open Camera by Mark Harman.
 * This file is part of Aub Camera and is licensed under the GNU General Public License v3 or later.
 */
package net.sourceforge.opencamera.aub;

import static net.sourceforge.opencamera.aub.AubImage.*;

/** Single-use 35mm film camera look (fixed-focus plastic lens, built-in flash, ISO 400 consumer
 *  colour film, scanned prints):
 *  <ul>
 *  <li>Flash look (optional): the subject in the middle of the frame is lit hard and bright, and the
 *      background falls away into darkness, keeping the warm colour of the room light.</li>
 *  <li>Plastic lens: soft corners with colour fringing, and heavy vignetting.</li>
 *  <li>Film colour: warm overall, yellow highlights and slightly green shadows, highlights that roll
 *      off gently rather than clipping, and lifted, faded blacks.</li>
 *  <li>Halation: a red-orange glow around bright highlights.</li>
 *  <li>Grain: clumpy luma grain, strongest in the midtones, plus a little colour grain.</li>
 *  <li>Light leak (optional): an orange-red streak bleeding in from one end of the frame, different
 *      for every photo.</li>
 *  </ul>
 *  The image is modified in place, in row strips. Everything that needs neighbouring pixels (halation,
 *  lens softness) comes from lower resolution copies, and the grain and light leak are functions of the
 *  pixel position in the whole image, so there are no seams between strips.
 */
public final class DisposableFilter {
    private DisposableFilter() {
    }

    private static final int ANALYSIS_LONG_SIDE = 320;
    private static final int SOFT_LONG_SIDE = 1000;

    /** @param image Image to modify (read and written in strips).
     *  @param flash Whether to simulate the built-in flash.
     *  @param leak  Whether to add a light leak.
     *  @param seed  Seed for the grain and the light leak.
     */
    public static void apply(RowAccess image, boolean flash, boolean leak, int seed) {
        final int W = image.width(), H = image.height();
        if( W < 2 || H < 2 )
            return;
        // effect sizes are defined relative to a 3000px scan, so the look is the same at any size
        final float unit = Math.max(W, H) / 3000.0f;

        // --- halation: glow around highlights, at low resolution ---
        int [] dims = fitLongSide(W, H, ANALYSIS_LONG_SIDE);
        Img small = downscaleArea(image, dims[0], dims[1]);
        final int aw = small.w, ah = small.h;
        float [] glow = new float[aw * ah];
        for(int y=0;y<ah;y++) {
            for(int x=0;x<aw;x++) {
                int i = y * aw + x;
                int c = small.px[i];
                float L = luma(((c >> 16) & 0xff) / 255.0f, ((c >> 8) & 0xff) / 255.0f, (c & 0xff) / 255.0f);
                // the flash makes the middle of the frame brighter, so more of it glows
                if( flash )
                    L *= mix(0.6f, 1.25f, flashFalloff((x + 0.5f) / aw, (y + 0.5f) / ah));
                glow[i] = smoothstep(0.72f, 0.97f, L);
            }
        }
        small = null;
        gaussianBlur(glow, aw, ah, Math.max(1.0f, Math.max(aw, ah) / 110.0f));

        // --- lens softness: a blurred copy at reduced resolution, bilinearly sampled ---
        int soft_long = Math.max(2, Math.min(SOFT_LONG_SIDE, Math.max(W, H) / 3));
        dims = fitLongSide(W, H, soft_long);
        Img soft = downscaleArea(image, dims[0], dims[1]);
        final int sw = soft.w, sh = soft.h, sn = sw * sh;
        float [] sr = new float[sn], sg = new float[sn], sb = new float[sn];
        unpack(soft.px, sn, sr, sg, sb);
        soft = null;
        gaussianBlur(sr, sw, sh, 1.0f);
        gaussianBlur(sg, sw, sh, 1.0f);
        gaussianBlur(sb, sw, sh, 1.0f);

        final Leak lk = leak ? new Leak(new Rng(seed ^ 0x5bd1e995), W >= H) : null;
        final float grain_cell = Math.max(1.0f, 1.3f * unit);
        final float ca = 0.006f; // lateral chromatic aberration in the corners, as a fraction of the radius

        final float cx = (W - 1) * 0.5f, cy = (H - 1) * 0.5f;
        final float inv_r2 = 1.0f / (cx * cx + cy * cy);
        final float ax = (float)aw / W, ay = (float)ah / H;
        final float bx = (float)sw / W, by = (float)sh / H;

        int strip = stripRows(W);
        int [] buf = new int[strip * W];
        for(int y0=0;y0<H;y0+=strip) {
            int nrows = Math.min(strip, H - y0);
            image.getRows(buf, y0, nrows);
            for(int j=0;j<nrows;j++) {
                final int y = y0 + j;
                final float v = (y + 0.5f) / H;
                final float dy = y - cy;
                int row = j * W;
                for(int x=0;x<W;x++) {
                    final float u = (x + 0.5f) / W;
                    final float dx = x - cx;
                    final float rr = (dx * dx + dy * dy) * inv_r2; // 0 in the centre, 1 in the corners
                    int c = buf[row + x];
                    float pr = ((c >> 16) & 0xff) * (1.0f / 255.0f);
                    float pg = ((c >> 8) & 0xff) * (1.0f / 255.0f);
                    float pb = (c & 0xff) * (1.0f / 255.0f);

                    // plastic lens: soft corners, with red and blue landing at slightly different radii
                    float k_soft = smoothstep(0.15f, 0.85f, rr);
                    if( k_soft > 0.0f ) {
                        float fx = (x + 0.5f) * bx - 0.5f, fy = (y + 0.5f) * by - 0.5f;
                        float ox = dx * bx * ca, oy = dy * by * ca;
                        pr = mix(pr, sample(sr, sw, sh, fx + ox, fy + oy), k_soft);
                        pg = mix(pg, sample(sg, sw, sh, fx, fy), k_soft);
                        pb = mix(pb, sample(sb, sw, sh, fx - ox, fy - oy), k_soft);
                    }

                    // work in (roughly) linear light for exposure
                    pr *= pr; pg *= pg; pb *= pb;

                    if( flash ) {
                        // subject lit by the flash, background falling into the dark; the background keeps
                        // the warm colour of the room light, the flash itself is neutral
                        float f = flashFalloff(u, v);
                        float L = luma(pr, pg, pb);
                        float gain = mix(0.28f, 2.0f, f);
                        gain *= mix(mix(0.55f, 1.0f, smoothstep(0.01f, 0.12f, L)), 1.0f, f); // crush dark background
                        float warm = 0.16f * (1.0f - f);
                        pr *= gain * (1.0f + warm);
                        pg *= gain;
                        pb *= gain * (1.0f - 1.6f * warm);
                    }

                    // heavy vignetting
                    float vig = 1.0f - 0.42f * rr;
                    pr *= vig; pg *= vig; pb *= vig;

                    // halation: red-orange glow bleeding from the highlights
                    float k_hal = sample(glow, aw, ah, (x + 0.5f) * ax - 0.5f, (y + 0.5f) * ay - 0.5f);
                    if( k_hal > 0.001f ) {
                        k_hal = Math.min(1.0f, k_hal * 1.8f) * 0.22f;
                        pr += k_hal;
                        pg += k_hal * 0.32f;
                        pb += k_hal * 0.10f;
                    }

                    // back to display values, then the film's soft highlight shoulder
                    pr = filmCurve((float)Math.sqrt(pr));
                    pg = filmCurve((float)Math.sqrt(pg));
                    pb = filmCurve((float)Math.sqrt(pb));

                    // film colour: warm, yellow highlights, slightly green shadows, saturation that
                    // fades towards the extremes
                    float L = luma(pr, pg, pb);
                    float sat = 1.0f + 0.16f * (1.0f - Math.abs(2.0f * L - 1.0f));
                    pr = L + (pr - L) * sat;
                    pg = L + (pg - L) * sat;
                    pb = L + (pb - L) * sat;
                    float hi = L * L, sh2 = (1.0f - L) * (1.0f - L);
                    pr = pr * 1.03f + 0.035f * hi - 0.012f * sh2;
                    pg = pg + 0.018f * hi + 0.014f * sh2;
                    pb = pb * 0.93f - 0.035f * hi + 0.010f * sh2;

                    if( lk != null ) {
                        float k = lk.amount(u, v);
                        if( k > 0.001f ) {
                            // hot yellow in the middle of the leak, deep orange-red at its edges; screen blend
                            float lr = 1.0f, lg = mix(0.28f, 0.80f, k * k), lb = mix(0.04f, 0.35f, k * k * k);
                            pr = 1.0f - (1.0f - pr) * (1.0f - k * lr);
                            pg = 1.0f - (1.0f - pg) * (1.0f - k * lg);
                            pb = 1.0f - (1.0f - pb) * (1.0f - k * lb);
                        }
                    }

                    // grain: two octaves of clumpy luma grain, strongest in the midtones, plus fine colour grain
                    L = clamp01(luma(pr, pg, pb));
                    float amp = 0.055f * (0.35f + 0.65f * 4.0f * L * (1.0f - L));
                    float n = valueNoise(x / grain_cell, y / grain_cell, seed)
                            + 0.5f * valueNoise(x / (2.3f * grain_cell), y / (2.3f * grain_cell), seed + 101);
                    float grain = n * amp;
                    float cn_r = (hashFloat(x, y, seed + 202) - 0.5f) * amp * 0.35f;
                    float cn_b = (hashFloat(x, y, seed + 303) - 0.5f) * amp * 0.35f;
                    pr += grain + cn_r;
                    pg += grain;
                    pb += grain + cn_b;

                    // faded print: the blacks never get to black
                    pr = 0.045f + pr * 0.955f;
                    pg = 0.050f + pg * 0.950f;
                    pb = 0.050f + pb * 0.950f;

                    buf[row + x] = pack(pr, pg, pb);
                }
            }
            image.setRows(buf, y0, nrows);
        }
    }

    /** How strongly the flash lights the point (u, v) in 0..1 image coordinates: 1 for the subject in the
     *  middle of the frame (centred a little above the middle, where faces usually are), 0 for the far
     *  background towards the edges.
     */
    static float flashFalloff(float u, float v) {
        float dx = (u - 0.5f) * 1.6f, dy = (v - 0.46f) * 1.8f;
        return 1.0f - smoothstep(0.12f, 1.05f, (float)Math.sqrt(dx * dx + dy * dy));
    }

    /** Film print tone curve: gentle S in the mids and a soft shoulder, so highlights roll off creamy
     *  instead of clipping. Input may exceed 1 (overexposed by the flash).
     */
    static float filmCurve(float v) {
        v = Math.max(0.0f, v);
        float s = Math.min(v, 1.0f);
        v += 0.22f * (s * s * (3.0f - 2.0f * s) - s);
        // exponential shoulder from the knee, scaled so that an input of v_max (a face blown out by the
        // flash, plus halation) only just reaches white
        final float knee = 0.70f, v_max = 1.3f, k = 1.25f;
        if( v > knee ) {
            final float range = 1.0f - knee;
            final float norm = 1.0f - (float)Math.exp(-k * (v_max - knee) / range);
            v = knee + range * (1.0f - (float)Math.exp(-k * (v - knee) / range)) / norm;
        }
        return v;
    }

    /** Integer hash of a lattice point, so noise only depends on position (no seams between strips). */
    private static int hash(int x, int y, int seed) {
        int h = seed * 0x27d4eb2d ^ x * 0x165667b1 ^ y * 0x1b873593;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    /** Uniform in [0,1) for a lattice point. */
    static float hashFloat(int x, int y, int seed) {
        return (hash(x, y, seed) >>> 8) * (1.0f / 16777216.0f);
    }

    /** Smooth value noise in [-1,1], with a feature size of 1 unit. */
    static float valueNoise(float fx, float fy, int seed) {
        int x0 = (int)Math.floor(fx), y0 = (int)Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        tx = tx * tx * (3.0f - 2.0f * tx);
        ty = ty * ty * (3.0f - 2.0f * ty);
        float a = hashFloat(x0, y0, seed), b = hashFloat(x0 + 1, y0, seed);
        float c = hashFloat(x0, y0 + 1, seed), d = hashFloat(x0 + 1, y0 + 1, seed);
        float top = a + (b - a) * tx, bot = c + (d - c) * tx;
        return (top + (bot - top) * ty) * 2.0f - 1.0f;
    }

    /** A light leak entering from one end of the frame. On 35mm film these come from the ends of the
     *  frame along the direction the film travels, i.e. the short edges of a landscape frame.
     */
    static final class Leak {
        private final boolean along_x; // leak enters from the left or right edge (else top or bottom)
        private final boolean far_edge; // right or bottom edge
        private final float centre;     // position along the edge, 0..1
        private final float spread;     // extent along the edge
        private final float depth;      // how far into the frame it reaches
        private final float strength;
        private final float [] wobble = new float[33]; // irregular extent along the edge

        Leak(Rng rng, boolean landscape) {
            along_x = landscape;
            far_edge = rng.next() < 0.5f;
            centre = 0.2f + 0.6f * rng.next();
            spread = 0.18f + 0.22f * rng.next();
            depth = 0.10f + 0.16f * rng.next();
            strength = 0.55f + 0.40f * rng.next();
            for(int i=0;i<wobble.length;i++) {
                wobble[i] = 0.65f + 0.7f * rng.next();
            }
            // smooth the wobble so the leak's edge is irregular but not noisy
            for(int pass=0;pass<2;pass++) {
                float prev = wobble[0];
                for(int i=1;i<wobble.length-1;i++) {
                    float cur = wobble[i];
                    wobble[i] = 0.25f * prev + 0.5f * cur + 0.25f * wobble[i + 1];
                    prev = cur;
                }
            }
        }

        /** Leak strength at (u, v) in 0..1 image coordinates. */
        float amount(float u, float v) {
            float across = along_x ? u : v; // distance into the frame from the leaking edge
            float along = along_x ? v : u;
            if( far_edge )
                across = 1.0f - across;
            float fw = along * (wobble.length - 1);
            int i0 = Math.min(wobble.length - 2, (int)fw);
            float wob = mix(wobble[i0], wobble[i0 + 1], fw - i0);
            float reach = depth * wob;
            if( across > reach * 4.0f )
                return 0.0f;
            float d_along = (along - centre) / spread;
            float k_along = (float)Math.exp(-d_along * d_along * 2.0f);
            float k_across = (float)Math.exp(-across / reach);
            return clamp01(strength * k_along * k_across * 1.3f);
        }
    }
}
