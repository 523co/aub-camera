/*
 * Aub Camera - a fork of Open Camera by Mark Harman.
 * This file is part of Aub Camera and is licensed under the GNU General Public License v3 or later.
 */
package net.sourceforge.opencamera.aub;

/** Small, allocation-conscious image helpers shared by the Aub Camera effects.
 *  Nothing in here depends on the Android framework, so the effects can be run and tested on a
 *  desktop JVM as well as on the device.
 */
public final class AubImage {
    private AubImage() {
    }

    /** Row-strip access to a large image (e.g. a full resolution android.graphics.Bitmap), so that
     *  effects never need a second full-size copy of the photo in memory.
     *  Pixels are packed 0xAARRGGBB, with a row stride equal to the image width.
     */
    public interface RowAccess {
        int width();
        int height();
        void getRows(int [] dst, int y0, int n_rows);
        void setRows(int [] src, int y0, int n_rows);
    }

    /** A simple in-memory image. */
    public static final class Img implements RowAccess {
        public final int w;
        public final int h;
        public final int [] px;

        public Img(int w, int h) {
            this(w, h, new int[w * h]);
        }

        public Img(int w, int h, int [] px) {
            if( px.length < w * h )
                throw new IllegalArgumentException("pixel array too small");
            this.w = w;
            this.h = h;
            this.px = px;
        }

        @Override public int width() { return w; }
        @Override public int height() { return h; }

        @Override
        public void getRows(int [] dst, int y0, int n_rows) {
            System.arraycopy(px, y0 * w, dst, 0, n_rows * w);
        }

        @Override
        public void setRows(int [] src, int y0, int n_rows) {
            System.arraycopy(src, 0, px, y0 * w, n_rows * w);
        }
    }

    /** Number of rows to process per strip, keeping strips to roughly 4 MB. */
    public static int stripRows(int width) {
        return Math.max(1, Math.min(512, (1024 * 1024) / Math.max(1, width)));
    }

    public static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    public static float smoothstep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3.0f - 2.0f * t);
    }

    public static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Rec. 709 luma of 0..1 floats. */
    public static float luma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    public static int pack(float r, float g, float b) {
        int ir = (int)(clamp01(r) * 255.0f + 0.5f);
        int ig = (int)(clamp01(g) * 255.0f + 0.5f);
        int ib = (int)(clamp01(b) * 255.0f + 0.5f);
        return 0xff000000 | (ir << 16) | (ig << 8) | ib;
    }

    /** Hue in degrees [0,360) of 0..1 floats; returns 0 for greys. */
    public static float hue(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        if( d <= 1.0e-6f )
            return 0.0f;
        float h;
        if( max == r )
            h = (g - b) / d;
        else if( max == g )
            h = (b - r) / d + 2.0f;
        else
            h = (r - g) / d + 4.0f;
        h *= 60.0f;
        if( h < 0.0f )
            h += 360.0f;
        return h;
    }

    static int clampIndex(int i, int n) {
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }

    /** Box blur of a single-channel float image, in place, edges clamped. Separable, O(1) per pixel. */
    public static void boxBlur(float [] a, int w, int h, int r) {
        boxBlurH(a, w, h, r);
        boxBlurV(a, w, h, r);
    }

    /** Horizontal-only box blur of a single-channel float image, in place. */
    public static void boxBlurH(float [] a, int w, int h, int r) {
        if( r <= 0 || w <= 0 || h <= 0 )
            return;
        float [] tmp = new float[w];
        float inv = 1.0f / (2 * r + 1);
        for(int y=0;y<h;y++) {
            int off = y * w;
            float sum = 0.0f;
            for(int i=-r;i<=r;i++) {
                sum += a[off + clampIndex(i, w)];
            }
            for(int x=0;x<w;x++) {
                tmp[x] = sum * inv;
                sum += a[off + clampIndex(x + r + 1, w)] - a[off + clampIndex(x - r, w)];
            }
            System.arraycopy(tmp, 0, a, off, w);
        }
    }

    /** Vertical-only box blur of a single-channel float image, in place. */
    public static void boxBlurV(float [] a, int w, int h, int r) {
        if( r <= 0 || w <= 0 || h <= 0 )
            return;
        float [] tmp = new float[h];
        float inv = 1.0f / (2 * r + 1);
        for(int x=0;x<w;x++) {
            float sum = 0.0f;
            for(int i=-r;i<=r;i++) {
                sum += a[clampIndex(i, h) * w + x];
            }
            for(int y=0;y<h;y++) {
                tmp[y] = sum * inv;
                sum += a[clampIndex(y + r + 1, h) * w + x] - a[clampIndex(y - r, h) * w + x];
            }
            for(int y=0;y<h;y++) {
                a[y * w + x] = tmp[y];
            }
        }
    }

    /** Approximates a Gaussian blur with standard deviation sigma using three box blurs. */
    public static void gaussianBlur(float [] a, int w, int h, float sigma) {
        if( sigma < 0.3f )
            return;
        // three passes of a box of radius r have variance r(r+1)
        int r = Math.max(1, Math.round((float)Math.sqrt(sigma * sigma + 0.25f) - 0.5f));
        boxBlur(a, w, h, r);
        boxBlur(a, w, h, r);
        boxBlur(a, w, h, r);
    }

    /** Splits packed ARGB pixels into three float planes in 0..1. */
    public static void unpack(int [] px, int n, float [] r, float [] g, float [] b) {
        for(int i=0;i<n;i++) {
            int c = px[i];
            r[i] = ((c >> 16) & 0xff) * (1.0f / 255.0f);
            g[i] = ((c >> 8) & 0xff) * (1.0f / 255.0f);
            b[i] = (c & 0xff) * (1.0f / 255.0f);
        }
    }

    /** Bilinear sample of a float plane of size w x h at continuous coordinates (fx, fy) in plane pixels. */
    public static float sample(float [] a, int w, int h, float fx, float fy) {
        if( fx < 0.0f ) fx = 0.0f;
        if( fy < 0.0f ) fy = 0.0f;
        if( fx > w - 1 ) fx = w - 1;
        if( fy > h - 1 ) fy = h - 1;
        int x0 = (int)fx;
        int y0 = (int)fy;
        int x1 = Math.min(x0 + 1, w - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        float tx = fx - x0;
        float ty = fy - y0;
        float a00 = a[y0 * w + x0], a10 = a[y0 * w + x1];
        float a01 = a[y1 * w + x0], a11 = a[y1 * w + x1];
        float top = a00 + (a10 - a00) * tx;
        float bot = a01 + (a11 - a01) * tx;
        return top + (bot - top) * ty;
    }

    /** Area-averaging downscale, reading the source in row strips (so the source can be a huge bitmap).
     *  Each source pixel contributes to exactly one destination pixel, which is a good approximation of
     *  a box filter for the large reduction factors used here, and avoids the aliasing of bilinear scaling.
     */
    public static Img downscaleArea(RowAccess src, int dw, int dh) {
        final int sw = src.width(), sh = src.height();
        dw = Math.max(1, Math.min(dw, sw));
        dh = Math.max(1, Math.min(dh, sh));
        int [] sr = new int[dw * dh], sg = new int[dw * dh], sb = new int[dw * dh], cnt = new int[dw * dh];
        int [] xmap = new int[sw];
        for(int x=0;x<sw;x++) {
            xmap[x] = (int)((long)x * dw / sw);
        }
        int strip = stripRows(sw);
        int [] buf = new int[strip * sw];
        for(int y0=0;y0<sh;y0+=strip) {
            int n = Math.min(strip, sh - y0);
            src.getRows(buf, y0, n);
            for(int j=0;j<n;j++) {
                int oy = (int)((long)(y0 + j) * dh / sh);
                int orow = oy * dw;
                int irow = j * sw;
                for(int x=0;x<sw;x++) {
                    int c = buf[irow + x];
                    int o = orow + xmap[x];
                    sr[o] += (c >> 16) & 0xff;
                    sg[o] += (c >> 8) & 0xff;
                    sb[o] += c & 0xff;
                    cnt[o]++;
                }
            }
        }
        Img out = new Img(dw, dh);
        for(int i=0;i<dw*dh;i++) {
            int k = Math.max(1, cnt[i]);
            int half = k / 2;
            out.px[i] = 0xff000000 | (((sr[i] + half) / k) << 16) | (((sg[i] + half) / k) << 8) | ((sb[i] + half) / k);
        }
        return out;
    }

    /** Nearest-neighbour resample (crisp, aliased - used deliberately for the retro looks). */
    public static Img resizeNearest(Img src, int dw, int dh) {
        Img out = new Img(dw, dh);
        for(int y=0;y<dh;y++) {
            int sy = (int)((long)y * src.h / dh);
            int srow = sy * src.w;
            int orow = y * dw;
            for(int x=0;x<dw;x++) {
                out.px[orow + x] = src.px[srow + (int)((long)x * src.w / dw)];
            }
        }
        return out;
    }

    /** Scales (w, h) so that the long side is at most max_long, preserving aspect ratio. */
    public static int [] fitLongSide(int w, int h, int max_long) {
        int l = Math.max(w, h);
        if( l <= max_long )
            return new int[]{w, h};
        double s = (double)max_long / l;
        return new int[]{ Math.max(1, (int)Math.round(w * s)), Math.max(1, (int)Math.round(h * s)) };
    }

    /** Scales (w, h) so that the short side equals short_side (never upscales). */
    public static int [] fitShortSide(int w, int h, int short_side) {
        int s0 = Math.min(w, h);
        if( s0 <= short_side )
            return new int[]{w, h};
        double s = (double)short_side / s0;
        return new int[]{ Math.max(1, (int)Math.round(w * s)), Math.max(1, (int)Math.round(h * s)) };
    }

    /** Fast deterministic pseudo random generator (xorshift), so effects are reproducible. */
    public static final class Rng {
        private int s;

        public Rng(int seed) {
            s = seed == 0 ? 0x9e3779b9 : seed;
        }

        /** Uniform in [0,1). */
        public float next() {
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            return (s >>> 8) * (1.0f / 16777216.0f);
        }

        /** Roughly Gaussian with mean 0, sd 1 (sum of uniforms). */
        public float gauss() {
            return (next() + next() + next() + next() - 2.0f) * 1.7320508f;
        }
    }
}
