/*
 * Aub Camera - a fork of Open Camera by Mark Harman.
 * This file is part of Aub Camera and is licensed under the GNU General Public License v3 or later.
 */
package net.sourceforge.opencamera.aub;

import static net.sourceforge.opencamera.aub.AubImage.*;

import java.util.Calendar;
import java.util.Date;

/** Early-2000s compact digital camera look (small CCD sensor, 2 megapixels, cheap lens):
 *  <ul>
 *  <li>2 MP output (1600x1200 for 4:3).</li>
 *  <li>Slightly soft lens, then strong in-camera sharpening with visible halos.</li>
 *  <li>Punchy CCD colour with a cool cast, contrasty tone curve and low dynamic range - highlights
 *      clip early and hard.</li>
 *  <li>Purple fringing along edges next to blown highlights.</li>
 *  <li>Blotchy chroma noise in the shadows plus fine luma noise.</li>
 *  <li>Light vignetting.</li>
 *  <li>Optional orange seven-segment date imprint in the corner.</li>
 *  </ul>
 *  JPEG recompression artifacts are added by the caller (it needs the platform encoder).
 */
public final class DigicamFilter {
    private DigicamFilter() {
    }

    public static final int OUTPUT_LONG_SIDE = 1600;

    public static final int STAMP_OFF = 0;
    public static final int STAMP_REAL = 1;
    public static final int STAMP_RETRO = 2; // real day and month, but in 2004

    /** @param image       Source image (only read).
     *  @param authentic_res Whether to output at 2MP; otherwise the output keeps up to 3200px long side.
     *  @param stamp       One of STAMP_*.
     *  @param date        Capture date for the stamp.
     *  @param seed        Seed for the noise pattern.
     */
    public static Img apply(RowAccess image, boolean authentic_res, int stamp, Date date, int seed) {
        int long_side = authentic_res ? OUTPUT_LONG_SIDE : 3200;
        int [] dims = fitLongSide(image.width(), image.height(), long_side);
        Img img = downscaleArea(image, dims[0], dims[1]);
        final int w = img.w, h = img.h, n = w * h;
        // effect sizes are defined relative to a 1600px image, so the look is the same at any size
        final float unit = Math.max(w, h) / 1600.0f;

        float [] r = new float[n], g = new float[n], b = new float[n];
        unpack(img.px, n, r, g, b);

        // --- cheap lens: slight softness ---
        softenPlane(r, w, h, 0.55f * unit);
        softenPlane(g, w, h, 0.55f * unit);
        softenPlane(b, w, h, 0.55f * unit);

        // --- colour + tone: CCD look, low dynamic range ---
        float [] lum = new float[n];
        final float cx = (w - 1) * 0.5f, cy = (h - 1) * 0.5f;
        final float inv_diag2 = 1.0f / (cx * cx + cy * cy);
        for(int y=0;y<h;y++) {
            for(int x=0;x<w;x++) {
                int i = y * w + x;
                float pr = r[i] * 0.965f, pg = g[i] * 1.0f, pb = b[i] * 1.07f; // cool white balance
                float L = luma(pr, pg, pb);
                float sat = 1.22f;
                pr = L + (pr - L) * sat;
                pg = L + (pg - L) * sat;
                pb = L + (pb - L) * sat;
                // reds were famously hot on CCDs
                if( pr > pg && pr > pb ) {
                    float k = Math.min(1.0f, (pr - Math.max(pg, pb)) * 3.0f) * 0.08f;
                    pr += k;
                    pg -= k * 0.4f;
                }
                // contrasty curve with an early, hard highlight clip
                pr = toneCurve(pr);
                pg = toneCurve(pg);
                pb = toneCurve(pb);
                // vignette
                float dx = x - cx, dy = y - cy;
                float v = 1.0f - 0.22f * (dx * dx + dy * dy) * inv_diag2;
                pr *= v; pg *= v; pb *= v;
                r[i] = pr; g[i] = pg; b[i] = pb;
                lum[i] = luma(clamp01(pr), clamp01(pg), clamp01(pb));
            }
        }

        // --- purple fringing next to blown highlights ---
        float [] clip = new float[n];
        for(int i=0;i<n;i++) {
            clip[i] = smoothstep(0.93f, 0.99f, lum[i]);
        }
        float [] fringe = clip.clone();
        int fr = Math.max(1, Math.round(2.0f * unit));
        boxBlur(fringe, w, h, fr);
        boxBlur(fringe, w, h, fr);
        for(int i=0;i<n;i++) {
            float k = Math.min(1.0f, fringe[i] * 3.0f) * (1.0f - clip[i]) * smoothstep(0.85f, 0.35f, lum[i]);
            if( k > 0.0f ) {
                r[i] += 0.16f * k;
                g[i] -= 0.07f * k;
                b[i] += 0.30f * k;
            }
        }
        clip = null;
        fringe = null;

        // --- noise: blotchy chroma noise in shadows + fine luma noise ---
        Rng rng = new Rng(seed);
        int nw = Math.max(1, w / 4), nh = Math.max(1, h / 4);
        float [] cn1 = new float[nw * nh], cn2 = new float[nw * nh];
        for(int i=0;i<nw*nh;i++) {
            cn1[i] = rng.gauss();
            cn2[i] = rng.gauss();
        }
        boxBlur(cn1, nw, nh, 1);
        boxBlur(cn2, nw, nh, 1);
        final float nsx = (float)nw / w, nsy = (float)nh / h;
        for(int y=0;y<h;y++) {
            float fy = (y + 0.5f) * nsy - 0.5f;
            for(int x=0;x<w;x++) {
                int i = y * w + x;
                float fx = (x + 0.5f) * nsx - 0.5f;
                float shadow = 1.0f - smoothstep(0.05f, 0.6f, lum[i]);
                float c1 = sample(cn1, nw, nh, fx, fy) * 0.055f * shadow;
                float c2 = sample(cn2, nw, nh, fx, fy) * 0.055f * shadow;
                float ln = rng.gauss() * (0.010f + 0.018f * shadow);
                r[i] += ln + c1;
                g[i] += ln - 0.5f * (c1 + c2);
                b[i] += ln + c2;
            }
        }
        cn1 = cn2 = null;

        // --- over-eager in-camera sharpening (unsharp mask, visible halos) ---
        sharpenPlane(r, w, h, Math.max(1, Math.round(1.5f * unit)), 0.95f);
        sharpenPlane(g, w, h, Math.max(1, Math.round(1.5f * unit)), 0.95f);
        sharpenPlane(b, w, h, Math.max(1, Math.round(1.5f * unit)), 0.95f);

        for(int i=0;i<n;i++) {
            img.px[i] = pack(r[i], g[i], b[i]);
        }

        if( stamp != STAMP_OFF && date != null ) {
            drawDateStamp(img, date, stamp == STAMP_RETRO);
        }
        return img;
    }

    private static float toneCurve(float v) {
        // gain plus a smooth S-curve, then a hard shoulder: low dynamic range
        v = (v - 0.025f) * 1.10f;
        v = clamp01(v);
        float s = v * v * (3.0f - 2.0f * v);
        v = mix(v, s, 0.35f);
        if( v > 0.86f )
            v = 0.86f + (v - 0.86f) * 1.9f;
        return v;
    }

    private static void softenPlane(float [] a, int w, int h, float sigma) {
        if( sigma < 0.4f ) {
            // too small for a box blur at this size: mix in a 3-tap horizontal/vertical average instead
            float [] t = a.clone();
            boxBlur(t, w, h, 1);
            for(int i=0;i<a.length;i++) {
                a[i] = mix(a[i], t[i], 0.5f);
            }
            return;
        }
        gaussianBlur(a, w, h, sigma);
    }

    private static void sharpenPlane(float [] a, int w, int h, int radius, float amount) {
        float [] blur = a.clone();
        boxBlur(blur, w, h, radius);
        for(int i=0;i<a.length;i++) {
            a[i] = a[i] + amount * (a[i] - blur[i]);
        }
    }

    // ---- seven segment date stamp ----

    // segment bits: a=top, b=top right, c=bottom right, d=bottom, e=bottom left, f=top left, g=middle
    private static final int [] DIGIT_SEGMENTS = {
            0b0111111, // 0: a b c d e f
            0b0000110, // 1: b c
            0b1011011, // 2: a b d e g
            0b1001111, // 3: a b c d g
            0b1100110, // 4: b c f g
            0b1101101, // 5: a c d f g
            0b1111101, // 6: a c d e f g
            0b0000111, // 7: a b c
            0b1111111, // 8
            0b1101111, // 9: a b c d f g
    };

    /** Builds the stamp text in the classic US-model format: "M D 'YY" (month and day without leading zeros). */
    public static String stampText(Date date, boolean retro) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        int year = retro ? 2004 : cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        return month + " " + day + " '" + String.format(java.util.Locale.US, "%02d", year % 100);
    }

    /** Draws an orange seven-segment imprint with a soft glow in the bottom right corner. */
    public static void drawDateStamp(Img img, Date date, boolean retro) {
        String text = stampText(date, retro);
        final int w = img.w, h = img.h;
        final float dh = Math.min(w, h) * 0.052f;        // digit height
        final float dw = dh * 0.52f;                        // digit width
        final float th = Math.max(2.0f, dh * 0.13f);        // segment thickness
        final float gap = dh * 0.20f;                       // gap between characters
        final float slant = 0.10f;                          // italic slant (x shift per unit of height)

        // layout width
        float total = 0.0f;
        for(int i=0;i<text.length();i++) {
            char ch = text.charAt(i);
            total += (ch == ' ' ? dw * 0.55f : ch == '\'' ? th * 2.0f : dw) + gap;
        }
        total -= gap;
        float x0 = w - total - Math.min(w, h) * 0.06f;
        float y0 = h - dh - Math.min(w, h) * 0.055f;

        // rasterise to a coverage mask with a margin for the glow
        final int margin = (int)Math.ceil(dh * 0.35f);
        final int mx0 = Math.max(0, (int)Math.floor(x0) - margin);
        final int my0 = Math.max(0, (int)Math.floor(y0) - margin);
        final int mx1 = Math.min(w, (int)Math.ceil(x0 + total + slant * dh) + margin);
        final int my1 = Math.min(h, (int)Math.ceil(y0 + dh) + margin);
        final int mw = mx1 - mx0, mh = my1 - my0;
        if( mw <= 0 || mh <= 0 )
            return;
        float [] mask = new float[mw * mh];

        float pen = x0;
        for(int i=0;i<text.length();i++) {
            char ch = text.charAt(i);
            if( ch == ' ' ) {
                pen += dw * 0.55f + gap;
                continue;
            }
            if( ch == '\'' ) {
                fillSegment(mask, mw, mh, mx0, my0, pen, y0, pen + th, y0 + dh * 0.28f, slant, y0 + dh);
                pen += th * 2.0f + gap;
                continue;
            }
            int bits = DIGIT_SEGMENTS[ch - '0'];
            float l = pen, rr = pen + dw, t = y0, m = y0 + dh * 0.5f, bt = y0 + dh;
            float inset = th * 0.15f; // small gaps between segments, like a real LCD
            if( (bits & 1) != 0 ) fillSegment(mask, mw, mh, mx0, my0, l + inset, t, rr - inset, t + th, slant, bt);                       // a
            if( (bits & 2) != 0 ) fillSegment(mask, mw, mh, mx0, my0, rr - th, t + inset, rr, m - inset, slant, bt);                      // b
            if( (bits & 4) != 0 ) fillSegment(mask, mw, mh, mx0, my0, rr - th, m + inset, rr, bt - inset, slant, bt);                     // c
            if( (bits & 8) != 0 ) fillSegment(mask, mw, mh, mx0, my0, l + inset, bt - th, rr - inset, bt, slant, bt);                     // d
            if( (bits & 16) != 0 ) fillSegment(mask, mw, mh, mx0, my0, l, m + inset, l + th, bt - inset, slant, bt);                      // e
            if( (bits & 32) != 0 ) fillSegment(mask, mw, mh, mx0, my0, l, t + inset, l + th, m - inset, slant, bt);                       // f
            if( (bits & 64) != 0 ) fillSegment(mask, mw, mh, mx0, my0, l + inset, m - th * 0.5f, rr - inset, m + th * 0.5f, slant, bt);   // g
            pen += dw + gap;
        }

        float [] glow = mask.clone();
        gaussianBlur(glow, mw, mh, dh * 0.10f);
        float [] core = mask.clone();
        boxBlur(core, mw, mh, 1); // soften the edges slightly, as the imprint was never pixel sharp

        final float sr = 1.0f, sg = 0.55f, sb = 0.10f; // imprint orange
        for(int y=0;y<mh;y++) {
            for(int x=0;x<mw;x++) {
                int mi = y * mw + x;
                float a_core = core[mi];
                float a_glow = Math.min(1.0f, glow[mi] * 1.6f) * 0.55f;
                if( a_core <= 0.0f && a_glow <= 0.0f )
                    continue;
                int pi = (my0 + y) * w + (mx0 + x);
                int c = img.px[pi];
                float pr = ((c >> 16) & 0xff) / 255.0f, pg = ((c >> 8) & 0xff) / 255.0f, pb = (c & 0xff) / 255.0f;
                // glow: additive-ish, core: opaque with a hot centre
                pr = 1.0f - (1.0f - pr) * (1.0f - a_glow * sr);
                pg = 1.0f - (1.0f - pg) * (1.0f - a_glow * sg);
                pb = 1.0f - (1.0f - pb) * (1.0f - a_glow * sb);
                pr = mix(pr, 1.0f, a_core);
                pg = mix(pg, 0.68f, a_core);
                pb = mix(pb, 0.22f, a_core);
                img.px[pi] = pack(pr, pg, pb);
            }
        }
    }

    /** Fills an axis-aligned rectangle (in image coordinates), sheared for an italic slant around baseline,
     *  into the coverage mask, with 4x horizontal supersampling for smooth edges.
     */
    private static void fillSegment(float [] mask, int mw, int mh, int mx0, int my0, float x0, float y0, float x1, float y1, float slant, float baseline) {
        int iy0 = Math.max(0, (int)Math.floor(y0) - my0), iy1 = Math.min(mh, (int)Math.ceil(y1) - my0);
        for(int y=iy0;y<iy1;y++) {
            float py = y + my0 + 0.5f;
            float cover_y = Math.min(py + 0.5f, y1) - Math.max(py - 0.5f, y0);
            if( cover_y <= 0.0f )
                continue;
            float shift = (baseline - py) * slant;
            float sx0 = x0 + shift, sx1 = x1 + shift;
            int ix0 = Math.max(0, (int)Math.floor(sx0) - mx0), ix1 = Math.min(mw, (int)Math.ceil(sx1) - mx0);
            for(int x=ix0;x<ix1;x++) {
                float px = x + mx0 + 0.5f;
                float cover_x = Math.min(px + 0.5f, sx1) - Math.max(px - 0.5f, sx0);
                if( cover_x <= 0.0f )
                    continue;
                int mi = y * mw + x;
                mask[mi] = Math.min(1.0f, mask[mi] + Math.min(1.0f, cover_x) * Math.min(1.0f, cover_y));
            }
        }
    }
}
