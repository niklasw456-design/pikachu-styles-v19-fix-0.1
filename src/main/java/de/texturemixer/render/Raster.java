package de.texturemixer.render;

import java.util.Arrays;

/**
 * Z-Buffer-Rasterizer mit Supersampling. Zeichnet texturierte Dreiecke (Orthogonalprojektion).
 * Bildschirmkoordinaten werden in Bildpunkten des fertigen Frames angegeben, z: größer = näher.
 * Texturen werden mit Cutout-Regel gezeichnet (Alpha < 10 % wird verworfen), wie bei Entities im Spiel.
 */
public final class Raster {
    /** Textur; baseH = Texturhöhe im Referenzraum (64 für Skins, 32 für Rüstungen, sonst Pixelhöhe). */
    public record Tex(int[] argb, int w, int h, int baseW, int baseH) {
        public static Tex plain(int[] argb, int w, int h) {
            return new Tex(argb, w, h, w, h);
        }

        double sx() {
            return w / (double) baseW;
        }

        double sy() {
            return h / (double) baseH;
        }
    }

    private final int fw, fh, ss, bw, bh;
    private final int[] color;
    private final double[] z;
    private boolean smooth;

    Raster(int frameW, int frameH, int ss) {
        this.fw = frameW;
        this.fh = frameH;
        this.ss = ss;
        this.bw = frameW * ss;
        this.bh = frameH * ss;
        this.color = new int[bw * bh];
        this.z = new double[bw * bh];
        clear();
    }

    /** Weiche (bilineare) Texturfilterung statt scharfer Pixel. */
    void setSmooth(boolean on) {
        this.smooth = on;
    }

    void clear() {
        Arrays.fill(color, 0);
        Arrays.fill(z, -1e18);
    }

    /** Viereck aus zwei Dreiecken. p: 4 Punkte {x,y,z} in Frame-Pixeln, uv: 4 Punkte {u,v} im Referenzraum. */
    void quad(double[][] p, double[][] uv, double[] rect, Tex tex, double shade) {
        tri(p[0], p[1], p[2], uv[0], uv[1], uv[2], rect, tex, shade);
        tri(p[0], p[2], p[3], uv[0], uv[2], uv[3], rect, tex, shade);
    }

    private void tri(double[] a0, double[] b0, double[] c0, double[] ua, double[] ub, double[] uc,
                     double[] rect, Tex tex, double shade) {
        double[] a = {a0[0] * ss, a0[1] * ss, a0[2]};
        double[] b = {b0[0] * ss, b0[1] * ss, b0[2]};
        double[] c = {c0[0] * ss, c0[1] * ss, c0[2]};

        int x0 = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int x1 = Math.min(bw - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int y1 = Math.min(bh - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));

        double den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        if (Math.abs(den) < 1e-9) return;

        double sx = tex.sx(), sy = tex.sy();
        double eps = 0.001;

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double px = x + 0.5, py = y + 0.5;
                double w0 = ((b[1] - c[1]) * (px - c[0]) + (c[0] - b[0]) * (py - c[1])) / den;
                double w1 = ((c[1] - a[1]) * (px - c[0]) + (a[0] - c[0]) * (py - c[1])) / den;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-9 || w1 < -1e-9 || w2 < -1e-9) continue;
                double zz = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int idx = y * bw + x;
                if (zz <= z[idx]) continue;

                double u = w0 * ua[0] + w1 * ub[0] + w2 * uc[0];
                double v = w0 * ua[1] + w1 * ub[1] + w2 * uc[1];
                u = Math.max(rect[0] + eps, Math.min(rect[0] + rect[2] - eps, u));
                v = Math.max(rect[1] + eps, Math.min(rect[1] + rect[3] - eps, v));
                int texel;
                if (smooth) {
                    texel = bilinear(tex, u, v, rect, sx, sy);
                    if ((texel >>> 24) < 115) continue;
                } else {
                    int tx = Math.max(0, Math.min(tex.w() - 1, (int) Math.floor(u * sx)));
                    int ty = Math.max(0, Math.min(tex.h() - 1, (int) Math.floor(v * sy)));
                    texel = tex.argb()[ty * tex.w() + tx];
                    if ((texel >>> 24) < 26) continue;
                }
                z[idx] = zz;
                if (shade != 1.0) {
                    int r = Math.min(255, (int) (((texel >> 16) & 0xFF) * shade));
                    int g = Math.min(255, (int) (((texel >> 8) & 0xFF) * shade));
                    int bl = Math.min(255, (int) ((texel & 0xFF) * shade));
                    texel = (r << 16) | (g << 8) | bl;
                }
                color[idx] = texel | 0xFF000000;
            }
        }
    }

    private static int c255(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) Math.round(v);
    }

    /** Bilinearer Zugriff, bleibt innerhalb der Fläche (rect), damit nichts aus Nachbarflächen hereinläuft. */
    private static int bilinear(Tex tex, double u, double v, double[] rect, double sx, double sy) {
        int w = tex.w(), h = tex.h();
        int[] px = tex.argb();
        int rx0 = Math.max(0, (int) Math.floor(rect[0] * sx + 1e-6));
        int rx1 = Math.min(w - 1, (int) Math.ceil((rect[0] + rect[2]) * sx - 1e-6) - 1);
        int ry0 = Math.max(0, (int) Math.floor(rect[1] * sy + 1e-6));
        int ry1 = Math.min(h - 1, (int) Math.ceil((rect[1] + rect[3]) * sy - 1e-6) - 1);
        if (rx1 < rx0) rx1 = rx0;
        if (ry1 < ry0) ry1 = ry0;
        double x = u * sx - 0.5, y = v * sy - 0.5;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy;
        int xa = Math.max(rx0, Math.min(rx1, ix)), xb = Math.max(rx0, Math.min(rx1, ix + 1));
        int ya = Math.max(ry0, Math.min(ry1, iy)), yb = Math.max(ry0, Math.min(ry1, iy + 1));
        int c00 = px[ya * w + xa], c10 = px[ya * w + xb], c01 = px[yb * w + xa], c11 = px[yb * w + xb];
        double a00 = (c00 >>> 24) * (1 - fx) * (1 - fy), a10 = (c10 >>> 24) * fx * (1 - fy);
        double a01 = (c01 >>> 24) * (1 - fx) * fy, a11 = (c11 >>> 24) * fx * fy;
        double a = a00 + a10 + a01 + a11;
        if (a < 1e-6) return 0;
        double r = (((c00 >> 16) & 0xFF) * a00 + ((c10 >> 16) & 0xFF) * a10 + ((c01 >> 16) & 0xFF) * a01 + ((c11 >> 16) & 0xFF) * a11) / a;
        double g = (((c00 >> 8) & 0xFF) * a00 + ((c10 >> 8) & 0xFF) * a10 + ((c01 >> 8) & 0xFF) * a01 + ((c11 >> 8) & 0xFF) * a11) / a;
        double b = ((c00 & 0xFF) * a00 + (c10 & 0xFF) * a10 + (c01 & 0xFF) * a01 + (c11 & 0xFF) * a11) / a;
        return (c255(a) << 24) | (c255(r) << 16) | (c255(g) << 8) | c255(b);
    }

    /** Rechnet den aktuellen Puffer herunter und schreibt ihn als Frame f in einen senkrechten Streifen. */
    void resolveInto(int[] strip, int frameIndex) {
        int area = ss * ss;
        for (int y = 0; y < fh; y++) {
            for (int x = 0; x < fw; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < ss; dy++) {
                    for (int dx = 0; dx < ss; dx++) {
                        int c = color[(y * ss + dy) * bw + (x * ss + dx)];
                        int ca = c >>> 24;
                        if (ca == 0) continue;
                        a += ca;
                        r += (long) ((c >> 16) & 0xFF) * ca;
                        g += (long) ((c >> 8) & 0xFF) * ca;
                        b += (long) (c & 0xFF) * ca;
                    }
                }
                int idx = (frameIndex * fh + y) * fw + x;
                if (a == 0) {
                    strip[idx] = 0;
                } else {
                    int oa = (int) (a / area);
                    strip[idx] = (oa << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
                }
            }
        }
    }
}
