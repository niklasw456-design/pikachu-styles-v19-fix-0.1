package de.texturemixer.render;

import java.util.stream.IntStream;

/**
 * Rendert einen drehenden End Crystal als Bildfolge (Software-Renderer, rein Java, keine Minecraft-Klassen).
 * Eingabe ist die Entity-Textur (64x32 bzw. ein Vielfaches davon), Ausgabe ein senkrechter Streifen
 * aus FRAMES Bildern der Größe frame x frame (ARGB).
 *
 * Glatte Darstellung: hohe Auflösung (passend zur Anzeigegröße), weiche Texturfilterung (bilinear, ohne
 * Überlaufen auf Nachbarflächen), Beleuchtung pro Fläche, leuchtender Kern, Lichthof in der Farbe des Kerns
 * und gerechnetes Supersampling an den Kanten.
 */
public final class CrystalRenderer {
    /** Standardgröße eines Bildes (wird nur verwendet, wenn keine Größe angegeben wird). */
    public static final int FRAME = 128;
    public static final int FRAMES = 36;

    private static final double PITCH = Math.toRadians(22);
    private static final double CRYSTAL_HEIGHT = 9.5;
    private static final double SHRINK = 0.875;

    private CrystalRenderer() {}

    private static final class Face {
        final double[][] pos = new double[4][3]; // TL, TR, BR, BL
        final double[][] uv = new double[4][2];
        final double[] rect = new double[4];      // ux, uy, uw, uh (Texturraum 64x32)
        final int part;

        Face(int part) {
            this.part = part;
        }
    }

    // ---------- Geometrie ----------

    private static void addCuboid(java.util.List<Face> out, int part, double hx, double hy, double hz, double u0, double v0) {
        double w = 2 * hx, h = 2 * hy, d = 2 * hz;
        add(out, part, new double[][]{{-hx, hy, hz}, {hx, hy, hz}, {hx, -hy, hz}, {-hx, -hy, hz}}, u0 + d, v0 + d, w, h);
        add(out, part, new double[][]{{hx, hy, -hz}, {-hx, hy, -hz}, {-hx, -hy, -hz}, {hx, -hy, -hz}}, u0 + 2 * d + w, v0 + d, w, h);
        add(out, part, new double[][]{{hx, hy, hz}, {hx, hy, -hz}, {hx, -hy, -hz}, {hx, -hy, hz}}, u0 + d + w, v0 + d, d, h);
        add(out, part, new double[][]{{-hx, hy, -hz}, {-hx, hy, hz}, {-hx, -hy, hz}, {-hx, -hy, -hz}}, u0, v0 + d, d, h);
        add(out, part, new double[][]{{-hx, hy, -hz}, {hx, hy, -hz}, {hx, hy, hz}, {-hx, hy, hz}}, u0 + d, v0, w, d);
        add(out, part, new double[][]{{-hx, -hy, hz}, {hx, -hy, hz}, {hx, -hy, -hz}, {-hx, -hy, -hz}}, u0 + d + w, v0, w, d);
    }

    private static void add(java.util.List<Face> out, int part, double[][] corners, double ux, double uy, double uw, double uh) {
        Face f = new Face(part);
        for (int i = 0; i < 4; i++) f.pos[i] = corners[i];
        f.uv[0] = new double[]{ux, uy};
        f.uv[1] = new double[]{ux + uw, uy};
        f.uv[2] = new double[]{ux + uw, uy + uh};
        f.uv[3] = new double[]{ux, uy + uh};
        f.rect[0] = ux;
        f.rect[1] = uy;
        f.rect[2] = uw;
        f.rect[3] = uh;
        out.add(f);
    }

    // ---------- Rendern ----------

    public static int[] render(int[] tex, int tw, int th) {
        return render(tex, tw, th, FRAME);
    }

    /** frame = Kantenlänge eines Bildes in Pixeln (wird auf 48..192 begrenzt). */
    public static int[] render(int[] tex, int tw, int th, int frame) {
        final int FR = Math.max(48, Math.min(192, frame));
        final int SS = FR <= 96 ? 4 : FR <= 140 ? 3 : 2;

        java.util.List<Face> faces = new java.util.ArrayList<>();
        addCuboid(faces, 0, 6, 2, 6, 0, 16);   // Sockel (Platte), UV (0,16)
        addCuboid(faces, 1, 4, 4, 4, 0, 0);    // äußeres Glas
        addCuboid(faces, 2, 4, 4, 4, 0, 0);    // inneres Glas
        addCuboid(faces, 3, 4, 4, 4, 32, 0);   // Kern

        double[] tilt = Mat.rotAxis(Math.sqrt(0.5), 0, Math.sqrt(0.5), Math.toRadians(60));
        double[] cam = Mat.rotX(PITCH);
        double[] plateM = Mat.mul(cam, Mat.translate(0, -2, 0));

        double[][][] mats = new double[FRAMES][4][];
        for (int f = 0; f < FRAMES; f++) {
            double a = 2 * Math.PI * f / FRAMES;
            double[] p = Mat.mul(Mat.rotY(a), Mat.mul(Mat.translate(0, CRYSTAL_HEIGHT, 0), tilt));
            double[] m2 = Mat.mul(p, Mat.mul(Mat.scale(SHRINK), Mat.mul(tilt, Mat.rotY(a))));
            double[] m3 = Mat.mul(m2, Mat.mul(Mat.scale(SHRINK), Mat.mul(tilt, Mat.rotY(a))));
            mats[f][0] = plateM;
            mats[f][1] = Mat.mul(cam, p);
            mats[f][2] = Mat.mul(cam, m2);
            mats[f][3] = Mat.mul(cam, m3);
        }

        // Ausschnitt so wählen, dass in jedem Bild alles sichtbar bleibt (Bildmitte bleibt fest)
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (int f = 0; f < FRAMES; f++) {
            for (Face face : faces) {
                for (double[] c : face.pos) {
                    double[] q = Mat.apply(mats[f][face.part], c);
                    minX = Math.min(minX, q[0]);
                    maxX = Math.max(maxX, q[0]);
                    minY = Math.min(minY, q[1]);
                    maxY = Math.max(maxY, q[1]);
                }
            }
        }
        double margin = FR * 0.06;
        double sc = Math.min((FR - 2 * margin) / (maxX - minX), (FR - 2 * margin) / (maxY - minY));
        double cx = FR / 2.0 - (minX + maxX) / 2.0 * sc;
        double cy = FR / 2.0 + (minY + maxY) / 2.0 * sc;

        // Farbe des Kerns (Mittel der Kern-Textur) für den Lichthof
        final int[] glow = coreColor(tex, tw, th);
        double[] qc = Mat.apply(cam, new double[]{0, CRYSTAL_HEIGHT, 0});
        final double gx = qc[0] * sc + cx, gy = cy - qc[1] * sc;

        final int n = FR * SS;
        final double ssc = sc * SS, scx = cx * SS, scy = cy * SS;
        final double[] LIGHT = norm(-0.45, 0.75, 0.55);

        final int[] out = new int[FR * FRAMES * FR];
        final double fsx = tw / 64.0, fsy = th / 32.0;

        IntStream.range(0, FRAMES).parallel().forEach(f -> {
            int[] color = new int[n * n];
            double[] zbuf = new double[n * n];
            java.util.Arrays.fill(zbuf, -1e18);

            for (Face face : faces) {
                double[][] s = new double[4][3];
                double[][] cs = new double[4][];
                for (int i = 0; i < 4; i++) {
                    double[] q = Mat.apply(mats[f][face.part], face.pos[i]);
                    cs[i] = q;
                    s[i][0] = q[0] * ssc + scx;
                    s[i][1] = scy - q[1] * ssc;
                    s[i][2] = q[2];
                }
                // Normale im Kameraraum -> Beleuchtung (Kern leuchtet selbst)
                double[] nrm = cross(sub(cs[3], cs[0]), sub(cs[1], cs[0]));
                double nl = Math.sqrt(nrm[0] * nrm[0] + nrm[1] * nrm[1] + nrm[2] * nrm[2]);
                double shade;
                if (face.part == 3) {
                    shade = 1.12;
                } else if (nl < 1e-9) {
                    shade = 1.0;
                } else {
                    double dot = (nrm[0] * LIGHT[0] + nrm[1] * LIGHT[1] + nrm[2] * LIGHT[2]) / nl;
                    shade = face.part == 0 ? 0.55 + 0.55 * Math.max(0, dot) : 0.50 + 0.62 * Math.max(0, dot);
                }
                tri(color, zbuf, n, s[0], s[1], s[2], face.uv[0], face.uv[1], face.uv[2], face.rect, tex, tw, th, fsx, fsy, shade);
                tri(color, zbuf, n, s[0], s[2], s[3], face.uv[0], face.uv[2], face.uv[3], face.rect, tex, tw, th, fsx, fsy, shade);
            }

            // herunterrechnen (Boxfilter) und über den Lichthof legen
            for (int y = 0; y < FR; y++) {
                for (int x = 0; x < FR; x++) {
                    long a = 0, r = 0, g = 0, b = 0;
                    for (int dy = 0; dy < SS; dy++) {
                        for (int dx = 0; dx < SS; dx++) {
                            int c = color[(y * SS + dy) * n + (x * SS + dx)];
                            int ca = c >>> 24;
                            if (ca == 0) continue;
                            a += ca;
                            r += (long) ((c >> 16) & 0xFF) * ca;
                            g += (long) ((c >> 8) & 0xFF) * ca;
                            b += (long) (c & 0xFF) * ca;
                        }
                    }
                    double ca = a / (255.0 * SS * SS);
                    double cr = 0, cg = 0, cb = 0;
                    if (a > 0) {
                        cr = (double) r / a;
                        cg = (double) g / a;
                        cb = (double) b / a;
                    }
                    // Lichthof: weich nach außen auslaufend, hinter dem Crystal
                    double dxg = (x + 0.5 - gx) / (FR * 0.46), dyg = (y + 0.5 - gy) / (FR * 0.46);
                    double d2 = dxg * dxg + dyg * dyg;
                    double ga = d2 >= 1 ? 0 : (1 - d2) * (1 - d2) * 0.34;
                    double oa = ca + ga * (1 - ca);
                    int idx = (f * FR + y) * FR + x;
                    if (oa <= 0.002) {
                        out[idx] = 0;
                        continue;
                    }
                    double rr = (cr * ca + glow[0] * ga * (1 - ca)) / oa;
                    double gg = (cg * ca + glow[1] * ga * (1 - ca)) / oa;
                    double bb = (cb * ca + glow[2] * ga * (1 - ca)) / oa;
                    out[idx] = ((int) Math.round(oa * 255) << 24)
                            | (clamp255(rr) << 16) | (clamp255(gg) << 8) | clamp255(bb);
                }
            }
        });
        return out;
    }

    private static int clamp255(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) Math.round(v);
    }

    private static double[] norm(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /** Mittlere Farbe der Kern-Textur (Bereich u 32..64, v 0..16), etwas aufgehellt; Standard: warmes Weiß. */
    private static int[] coreColor(int[] tex, int tw, int th) {
        double sx = tw / 64.0, sy = th / 32.0;
        int x0 = (int) (32 * sx), x1 = Math.min(tw, (int) (64 * sx));
        int y0 = 0, y1 = Math.min(th, (int) (16 * sy));
        long r = 0, g = 0, b = 0, cnt = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = tex[y * tw + x];
                if ((c >>> 24) < 128) continue;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                cnt++;
            }
        }
        if (cnt == 0) return new int[]{255, 235, 200};
        double lift = 0.22;
        return new int[]{
                clamp255(r / (double) cnt * (1 - lift) + 255 * lift),
                clamp255(g / (double) cnt * (1 - lift) + 255 * lift),
                clamp255(b / (double) cnt * (1 - lift) + 255 * lift)};
    }

    /** Bilinear gefilterter Texturzugriff, bleibt innerhalb der Fläche (rect) und verwischt nicht in Nachbarflächen. */
    private static int sample(int[] tex, int tw, int th, double u, double v, double[] rect, double sx, double sy) {
        int rx0 = Math.max(0, (int) Math.floor(rect[0] * sx + 1e-6));
        int rx1 = Math.min(tw - 1, (int) Math.ceil((rect[0] + rect[2]) * sx - 1e-6) - 1);
        int ry0 = Math.max(0, (int) Math.floor(rect[1] * sy + 1e-6));
        int ry1 = Math.min(th - 1, (int) Math.ceil((rect[1] + rect[3]) * sy - 1e-6) - 1);
        if (rx1 < rx0) rx1 = rx0;
        if (ry1 < ry0) ry1 = ry0;

        double x = u * sx - 0.5, y = v * sy - 0.5;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy;
        int xa = Math.max(rx0, Math.min(rx1, ix)), xb = Math.max(rx0, Math.min(rx1, ix + 1));
        int ya = Math.max(ry0, Math.min(ry1, iy)), yb = Math.max(ry0, Math.min(ry1, iy + 1));
        int c00 = tex[ya * tw + xa], c10 = tex[ya * tw + xb], c01 = tex[yb * tw + xa], c11 = tex[yb * tw + xb];
        double w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy), w01 = (1 - fx) * fy, w11 = fx * fy;
        double a00 = (c00 >>> 24) * w00, a10 = (c10 >>> 24) * w10, a01 = (c01 >>> 24) * w01, a11 = (c11 >>> 24) * w11;
        double a = a00 + a10 + a01 + a11;
        if (a < 1e-6) return 0;
        double r = (((c00 >> 16) & 0xFF) * a00 + ((c10 >> 16) & 0xFF) * a10 + ((c01 >> 16) & 0xFF) * a01 + ((c11 >> 16) & 0xFF) * a11) / a;
        double g = (((c00 >> 8) & 0xFF) * a00 + ((c10 >> 8) & 0xFF) * a10 + ((c01 >> 8) & 0xFF) * a01 + ((c11 >> 8) & 0xFF) * a11) / a;
        double b = ((c00 & 0xFF) * a00 + (c10 & 0xFF) * a10 + (c01 & 0xFF) * a01 + (c11 & 0xFF) * a11) / a;
        return ((int) Math.round(a) << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static void tri(int[] color, double[] zbuf, int n,
                            double[] a, double[] b, double[] c,
                            double[] ua, double[] ub, double[] uc,
                            double[] rect, int[] tex, int tw, int th, double sx, double sy, double shade) {
        double minX = Math.min(a[0], Math.min(b[0], c[0]));
        double maxX = Math.max(a[0], Math.max(b[0], c[0]));
        double minY = Math.min(a[1], Math.min(b[1], c[1]));
        double maxY = Math.max(a[1], Math.max(b[1], c[1]));
        int x0 = Math.max(0, (int) Math.floor(minX));
        int x1 = Math.min(n - 1, (int) Math.ceil(maxX));
        int y0 = Math.max(0, (int) Math.floor(minY));
        int y1 = Math.min(n - 1, (int) Math.ceil(maxY));

        double den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        if (Math.abs(den) < 1e-9) return;
        double inv = 1.0 / den;
        double eps = 0.0005;

        for (int y = y0; y <= y1; y++) {
            double py = y + 0.5;
            for (int x = x0; x <= x1; x++) {
                double px = x + 0.5;
                double w0 = ((b[1] - c[1]) * (px - c[0]) + (c[0] - b[0]) * (py - c[1])) * inv;
                double w1 = ((c[1] - a[1]) * (px - c[0]) + (a[0] - c[0]) * (py - c[1])) * inv;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-9 || w1 < -1e-9 || w2 < -1e-9) continue;
                double z = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int idx = y * n + x;
                if (z <= zbuf[idx]) continue;

                double u = w0 * ua[0] + w1 * ub[0] + w2 * uc[0];
                double v = w0 * ua[1] + w1 * ub[1] + w2 * uc[1];
                u = Math.max(rect[0] + eps, Math.min(rect[0] + rect[2] - eps, u));
                v = Math.max(rect[1] + eps, Math.min(rect[1] + rect[3] - eps, v));
                int texel = sample(tex, tw, th, u, v, rect, sx, sy);
                if ((texel >>> 24) < 115) continue; // Cutout (Kanten glättet das Supersampling)
                zbuf[idx] = z;
                int r = clamp255(((texel >> 16) & 0xFF) * shade);
                int g = clamp255(((texel >> 8) & 0xFF) * shade);
                int bl = clamp255((texel & 0xFF) * shade);
                color[idx] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
        }
    }
}
