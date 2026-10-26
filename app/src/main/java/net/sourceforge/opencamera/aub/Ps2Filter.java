/*
 * Aub Camera - a fork of Open Camera by Mark Harman.
 * This file is part of Aub Camera and is licensed under the GNU General Public License v3 or later.
 */
package net.sourceforge.opencamera.aub;

import static net.sourceforge.opencamera.aub.AubImage.*;

/** "Sixth generation console" look: makes a photo resemble a frame rendered by a 2000-era games
 *  console, as output over composite video.
 *  <ul>
 *  <li>Rendered at 448 lines (the typical NTSC framebuffer height), with jaggy, un-antialiased edges.</li>
 *  <li>Washed-out blacks and soft warm bloom around bright areas, as many games of the era used.</li>
 *  <li>Composite video chroma bleed (colour smeared horizontally, luma stays sharp).</li>
 *  <li>15-bit colour with an ordered (Bayer) dither pattern.</li>
 *  <li>Integer nearest-neighbour upscale with optional CRT-style scanlines.</li>
 *  </ul>
 */
public final class Ps2Filter {
    private Ps2Filter() {
    }

    public static final int FRAMEBUFFER_LINES = 448;
    private static final int OUTPUT_SHORT_SIDE = 1344; // 3x

    private static final float [] BAYER4 = {
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5
    };

    /** @param image     Source image (only read).
     *  @param scanlines Whether to draw scanlines in the upscaled output.
     *  @param upscale   Whether to integer-upscale the output (false for the live preview, or to keep the raw 448p frame).
     *  @return The processed image.
     */
    public static Img apply(RowAccess image, boolean scanlines, boolean upscale) {
        final int W = image.width(), H = image.height();
        // framebuffer resolution; render at 2x then point-sample down for crisp, aliased edges
        int [] fb = fitShortSide(W, H, FRAMEBUFFER_LINES);
        int fw = fb[0], fh = fb[1];
        Img hi = downscaleArea(image, Math.min(W, fw * 2), Math.min(H, fh * 2));
        Img frame = resizeNearest(hi, fw, fh);
        hi = null;
        final int n = fw * fh;

        float [] r = new float[n], g = new float[n], b = new float[n];
        unpack(frame.px, n, r, g, b);

        // --- "low resolution textures": edge-preserving smoothing flattens fine photographic detail
        // (as if surfaces were textured polygons), while object edges stay hard and jaggy ---
        if( upscale ) {
            bilateral(r, g, b, fw, fh, 2, 0.10f);
            bilateral(r, g, b, fw, fh, 2, 0.08f);
        }
        else {
            // live preview: one lighter pass, to keep the frame rate up
            bilateral(r, g, b, fw, fh, 1, 0.10f);
        }

        // --- bloom: blurred bright pass added back with a warm tint ---
        float [] br = new float[n], bg = new float[n], bb = new float[n];
        for(int i=0;i<n;i++) {
            float l = luma(r[i], g[i], b[i]);
            float k = smoothstep(0.62f, 0.95f, l);
            br[i] = r[i] * k;
            bg[i] = g[i] * k;
            bb[i] = b[i] * k;
        }
        float bloom_sigma = Math.max(2.0f, Math.min(fw, fh) / 40.0f);
        gaussianBlur(br, fw, fh, bloom_sigma);
        gaussianBlur(bg, fw, fh, bloom_sigma);
        gaussianBlur(bb, fw, fh, bloom_sigma);

        // --- grade + convert to YIQ for composite chroma bleed ---
        float [] Y = new float[n], I = new float[n], Q = new float[n];
        for(int i=0;i<n;i++) {
            float pr = r[i], pg = g[i], pb = b[i];
            // screen-blend bloom
            pr = 1.0f - (1.0f - pr) * (1.0f - 0.55f * br[i] * 1.06f);
            pg = 1.0f - (1.0f - pg) * (1.0f - 0.55f * bg[i] * 1.00f);
            pb = 1.0f - (1.0f - pb) * (1.0f - 0.55f * bb[i] * 0.88f);
            // gentle game-engine grade: a little saturation, flattened highlights, lifted blacks
            float L = luma(pr, pg, pb);
            pr = L + (pr - L) * 1.12f;
            pg = L + (pg - L) * 1.12f;
            pb = L + (pb - L) * 1.12f;
            pr = 0.055f + 0.90f * clamp01(pr);
            pg = 0.050f + 0.90f * clamp01(pg);
            pb = 0.065f + 0.88f * clamp01(pb);
            Y[i] = 0.299f * pr + 0.587f * pg + 0.114f * pb;
            I[i] = 0.596f * pr - 0.274f * pg - 0.322f * pb;
            Q[i] = 0.211f * pr - 0.523f * pg + 0.312f * pb;
        }
        br = bg = bb = null;
        // chroma bandwidth on composite video is much lower than luma: smear I/Q horizontally
        int chroma_r = Math.max(1, fw / 200);
        boxBlurH(I, fw, fh, chroma_r);
        boxBlurH(I, fw, fh, chroma_r);
        boxBlurH(Q, fw, fh, chroma_r + 1);
        boxBlurH(Q, fw, fh, chroma_r + 1);
        // slight luma ringing/sharpening that composite encoders/TV sets add
        float [] Yb = Y.clone();
        boxBlurH(Yb, fw, fh, 1);

        // --- back to RGB, 15-bit colour with ordered dither ---
        for(int y=0;y<fh;y++) {
            for(int x=0;x<fw;x++) {
                int i = y * fw + x;
                float yy = Y[i] + 0.35f * (Y[i] - Yb[i]);
                float pr = yy + 0.956f * I[i] + 0.621f * Q[i];
                float pg = yy - 0.272f * I[i] - 0.647f * Q[i];
                float pb = yy - 1.106f * I[i] + 1.703f * Q[i];
                float d = (BAYER4[((y & 3) << 2) | (x & 3)] + 0.5f) / 16.0f - 0.5f;
                int qr = quant5(pr, d), qg = quant5(pg, d), qb = quant5(pb, d);
                frame.px[i] = 0xff000000 | (qr << 16) | (qg << 8) | qb;
            }
        }

        if( !upscale )
            return frame;

        int k = Math.max(1, Math.round((float)OUTPUT_SHORT_SIDE / Math.min(fw, fh)));
        Img out = new Img(fw * k, fh * k);
        for(int y=0;y<out.h;y++) {
            int sy = y / k;
            boolean dark_line = scanlines && k >= 2 && (y % k) == k - 1;
            int orow = y * out.w;
            int srow = sy * fw;
            for(int x=0;x<out.w;x++) {
                int c = frame.px[srow + x / k];
                if( dark_line ) {
                    int cr = ((c >> 16) & 0xff) * 184 >> 8;
                    int cg = ((c >> 8) & 0xff) * 184 >> 8;
                    int cb = (c & 0xff) * 184 >> 8;
                    c = 0xff000000 | (cr << 16) | (cg << 8) | cb;
                }
                out.px[orow + x] = c;
            }
        }
        return out;
    }

    /** Simple joint bilateral filter on three planes, in place.
     *  @param radius Spatial radius in pixels (box window).
     *  @param sigma_r Range sigma, in 0..1 colour units.
     */
    static void bilateral(float [] r, float [] g, float [] b, int w, int h, int radius, float sigma_r) {
        final int n = w * h;
        float [] or = r.clone(), og = g.clone(), ob = b.clone();
        final float inv2s2 = 1.0f / (2.0f * sigma_r * sigma_r);
        // lookup table for exp(-d2 * inv2s2) over d2 in [0, 3]
        final int LUT = 1024;
        float [] lut = new float[LUT + 1];
        for(int i=0;i<=LUT;i++) {
            lut[i] = (float)Math.exp(-(3.0f * i / LUT) * inv2s2);
        }
        for(int y=0;y<h;y++) {
            for(int x=0;x<w;x++) {
                int i = y * w + x;
                float cr = or[i], cg = og[i], cb = ob[i];
                float sr = 0, sg = 0, sb = 0, sw = 0;
                for(int dy=-radius;dy<=radius;dy++) {
                    int yy = clampIndex(y + dy, h) * w;
                    for(int dx=-radius;dx<=radius;dx++) {
                        int j = yy + clampIndex(x + dx, w);
                        float er = or[j] - cr, eg = og[j] - cg, eb = ob[j] - cb;
                        float d2 = er * er + eg * eg + eb * eb;
                        float wgt = lut[Math.min(LUT, (int)(d2 * (LUT / 3.0f)))];
                        sr += or[j] * wgt;
                        sg += og[j] * wgt;
                        sb += ob[j] * wgt;
                        sw += wgt;
                    }
                }
                r[i] = sr / sw;
                g[i] = sg / sw;
                b[i] = sb / sw;
            }
        }
    }

    /** Quantises 0..1 to 5 bits with dither offset d (in units of one quantisation step), returned as 0..255. */
    private static int quant5(float v, float d) {
        int q = Math.round(clamp01(v) * 31.0f + d);
        q = q < 0 ? 0 : (q > 31 ? 31 : q);
        return (q << 3) | (q >> 2);
    }
}
