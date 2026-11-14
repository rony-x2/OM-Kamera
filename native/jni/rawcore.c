/*
 * Native helpers for OM Kamera (stage 3c): decoding Sony compressed RAW rows.
 * The Java side (RawFile) keeps the TIFF parsing; this file only turns rows of the
 * sensor data into 16 bit values, using all CPU cores. Format knowledge from public
 * descriptions (dcraw/LibRaw); same algorithm as RawFile.decodeArw2.
 */
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>




static void decode_row(const unsigned char *d, int rawWidth, int width, const int *curve, int *out)
{
    int pix[16];
    int col = 0, dp;
    for (dp = 0; col < rawWidth - 30; dp += 16) {
        unsigned val = d[dp] | d[dp + 1] << 8 | d[dp + 2] << 16 | (unsigned) d[dp + 3] << 24;
        int max = val & 0x7ff;
        int min = (val >> 11) & 0x7ff;
        int imax = (val >> 22) & 0x0f;
        int imin = (val >> 26) & 0x0f;
        int sh = 0, bit = 30, i;
        while (sh < 4 && (0x80 << sh) <= max - min) {
            sh++;
        }
        for (i = 0; i < 16; i++) {
            if (i == imax) {
                pix[i] = max;
            } else if (i == imin) {
                pix[i] = min;
            } else {
                int p = dp + (bit >> 3);
                int w = d[p] | d[p + 1] << 8;
                int v = (((w >> (bit & 7)) & 0x7f) << sh) + min;
                pix[i] = v > 0x7ff ? 0x7ff : v;
                bit += 7;
            }
        }
        for (i = 0; i < 16; i++, col += 2) {
            if (col < width) {
                out[col] = curve[pix[i] << 1];
            }
        }
        col -= (col & 1) ? 1 : 31;
    }
}

typedef struct {
    int fd;
    long long offset;
    int rawWidth, width, compression, little;
    const int *curve;
    int y0, y1;
    int *out;      /* rows of 'width' ints */
    int err;
} job_t;

static void *worker(void *arg)
{
    job_t *j = (job_t *) arg;
    int bpr = j->compression == 32767 ? j->rawWidth : j->rawWidth * 2;
    unsigned char *buf = (unsigned char *) malloc(bpr + 16);
    int y;
    if (!buf) {
        j->err = 1;
        return NULL;
    }
    memset(buf, 0, bpr + 16);
    for (y = j->y0; y < j->y1; y++) {
        long long off = j->offset + (long long) y * bpr;
        int got = 0;
        while (got < bpr) {
            ssize_t n = pread(j->fd, buf + got, bpr - got, (off_t) (off + got));
            if (n <= 0) {
                j->err = 2;
                free(buf);
                return NULL;
            }
            got += n;
        }
        int *o = j->out + (size_t) (y - j->y0) * j->width;
        if (j->compression == 32767) {
            decode_row(buf, j->rawWidth, j->width, j->curve, o);
        } else {
            int c;
            for (c = 0; c < j->width; c++) {
                o[c] = j->little ? (buf[2 * c] | buf[2 * c + 1] << 8) : (buf[2 * c] << 8 | buf[2 * c + 1]);
            }
        }
    }
    free(buf);
    return NULL;
}

static int cores(void)
{
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    return n < 1 ? 1 : n > 4 ? 4 : (int) n;
}

JNIEXPORT jint JNICALL
Java_com_github_ronyx2_omkamera_RawCore_cores(JNIEnv *env, jclass cls)
{
    return cores();
}

JNIEXPORT jint JNICALL
Java_com_github_ronyx2_omkamera_RawCore_open(JNIEnv *env, jclass cls, jstring path)
{
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(p, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return fd;
}

JNIEXPORT void JNICALL
Java_com_github_ronyx2_omkamera_RawCore_close(JNIEnv *env, jclass cls, jint fd)
{
    if (fd >= 0) {
        close(fd);
    }
}

/*
 * Decodes rows y .. y+count-1 into out (count * width ints). Returns 0 or an error code.
 */
JNIEXPORT jint JNICALL
Java_com_github_ronyx2_omkamera_RawCore_decode(JNIEnv *env, jclass cls, jint fd, jlong offset,
        jint rawWidth, jint width, jint compression, jboolean little, jintArray jcurve,
        jint y, jint count, jintArray jout)
{
    int n = cores(), i, err = 0;
    pthread_t th[4];
    job_t jobs[4];
    jint *curve = (*env)->GetIntArrayElements(env, jcurve, NULL);
    int *out = (int *) malloc((size_t) count * width * sizeof(int));
    if (!out) {
        (*env)->ReleaseIntArrayElements(env, jcurve, curve, JNI_ABORT);
        return 3;
    }
    if (n > count) {
        n = count;
    }
    for (i = 0; i < n; i++) {
        job_t *j = &jobs[i];
        j->fd = fd;
        j->offset = offset;
        j->rawWidth = rawWidth;
        j->width = width;
        j->compression = compression;
        j->little = little;
        j->curve = curve;
        j->y0 = y + count * i / n;
        j->y1 = y + count * (i + 1) / n;
        j->out = out + (size_t) (j->y0 - y) * width;
        j->err = 0;
    }
    for (i = 1; i < n; i++) {
        if (pthread_create(&th[i], NULL, worker, &jobs[i]) != 0) {
            jobs[i].err = 4;
            th[i] = 0;
        }
    }
    worker(&jobs[0]);
    for (i = 1; i < n; i++) {
        if (jobs[i].err != 4) {
            pthread_join(th[i], NULL);
        }
    }
    for (i = 0; i < n; i++) {
        if (jobs[i].err) {
            err = jobs[i].err;
        }
    }
    if (!err) {
        (*env)->SetIntArrayRegion(env, jout, 0, count * width, out);
    }
    free(out);
    (*env)->ReleaseIntArrayElements(env, jcurve, curve, JNI_ABORT);
    return err;
}

/* ---- combining rows: 0 add, 1 max ---- */

JNIEXPORT void JNICALL
Java_com_github_ronyx2_omkamera_RawCore_combine(JNIEnv *env, jclass cls, jint mode, jintArray jacc,
        jintArray jadd, jint n)
{
    jint *acc = (*env)->GetPrimitiveArrayCritical(env, jacc, NULL);
    jint *add = (*env)->GetPrimitiveArrayCritical(env, jadd, NULL);
    int i;
    if (mode == 1) {
        for (i = 0; i < n; i++) {
            if (add[i] > acc[i]) {
                acc[i] = add[i];
            }
        }
    } else {
        for (i = 0; i < n; i++) {
            acc[i] += add[i];
        }
    }
    (*env)->ReleasePrimitiveArrayCritical(env, jadd, add, JNI_ABORT);
    (*env)->ReleasePrimitiveArrayCritical(env, jacc, acc, 0);
}

/* Mean: (v + count/2) / count. Sum: v - sub, clipped to 0..lim */
JNIEXPORT void JNICALL
Java_com_github_ronyx2_omkamera_RawCore_finish(JNIEnv *env, jclass cls, jint mode, jintArray jacc,
        jint n, jint count, jint sub, jint lim)
{
    jint *a = (*env)->GetPrimitiveArrayCritical(env, jacc, NULL);
    int i, half = count / 2;
    if (mode == 0) {
        for (i = 0; i < n; i++) {
            a[i] = (a[i] + half) / count;
        }
    } else {
        for (i = 0; i < n; i++) {
            int v = a[i] - sub;
            a[i] = v > lim ? lim : v < 0 ? 0 : v;
        }
    }
    (*env)->ReleasePrimitiveArrayCritical(env, jacc, a, 0);
}

/* 16 bit little endian packing of a row (DNG) */
JNIEXPORT void JNICALL
Java_com_github_ronyx2_omkamera_RawCore_pack16(JNIEnv *env, jclass cls, jintArray jrow, jint n, jbyteArray jout)
{
    jint *r = (*env)->GetPrimitiveArrayCritical(env, jrow, NULL);
    unsigned char *o = (*env)->GetPrimitiveArrayCritical(env, jout, NULL);
    int i;
    for (i = 0; i < n; i++) {
        o[2 * i] = (unsigned char) r[i];
        o[2 * i + 1] = (unsigned char) (r[i] >> 8);
    }
    (*env)->ReleasePrimitiveArrayCritical(env, jout, o, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, jrow, r, JNI_ABORT);
}

/* ---- development: bilinear demosaic, white balance, camera matrix, sRGB curve ---- */

typedef struct {
    const int *cfa;          /* (n + 2) rows of fullW raw values, first row = y0 - 1 */
    int fullW, x0, w, y0;    /* y0: sensor row of the first output row */
    const int *pat, *blk, *scale, *mat;
    const unsigned short *lut;
    int format;              /* 0 TIFF (LE), 1 PNG 16 bit, 2 PNG 8 bit (both with filter byte, Sub) */
    unsigned char *out;
    int r0, r1;              /* output rows of this thread */
} devjob_t;

static inline int lin(const devjob_t *d, const int *row, int ry, int x)
{
    int ph = (ry & 1) * 2 + (x & 1);
    int c = d->pat[ph];
    int v = row[x] - d->blk[ph];
    long long s;
    if (v < 0) {
        v = 0;
    }
    s = ((long long) v * d->scale[c]) >> 12;
    return s > 65535 ? 65535 : (int) s;
}

static inline int clamp16(int v)
{
    return v < 0 ? 0 : v > 65535 ? 65535 : v;
}

static void *dev_worker(void *arg)
{
    devjob_t *d = (devjob_t *) arg;
    int y, x;
    int stride = d->format == 2 ? 1 + d->w * 3 : d->format == 1 ? 1 + d->w * 6 : d->w * 6;
    for (y = d->r0; y < d->r1; y++) {
        int ry = d->y0 + y;
        const int *prev = d->cfa + (size_t) y * d->fullW;
        const int *cur = prev + d->fullW;
        const int *next = cur + d->fullW;
        unsigned char *o = d->out + (size_t) y * stride;
        int pr = 0, pg = 0, pb = 0;
        if (d->format >= 1) {
            *o++ = 1;
        }
        for (x = 0; x < d->w; x++) {
            int rx = d->x0 + x;
            int ph = (ry & 1) * 2;
            int c = d->pat[ph + (rx & 1)];
            int r, g, b, v = lin(d, cur, ry, rx);
            if (c == 1) {
                int hc = d->pat[ph + ((rx + 1) & 1)];
                int hv = (lin(d, cur, ry, rx - 1) + lin(d, cur, ry, rx + 1)) >> 1;
                int vv = (lin(d, prev, ry - 1, rx) + lin(d, next, ry + 1, rx)) >> 1;
                g = v;
                if (hc == 0) {
                    r = hv;
                    b = vv;
                } else {
                    r = vv;
                    b = hv;
                }
            } else {
                int dg = (lin(d, cur, ry, rx - 1) + lin(d, cur, ry, rx + 1)
                        + lin(d, prev, ry - 1, rx) + lin(d, next, ry + 1, rx)) >> 2;
                int dd = (lin(d, prev, ry - 1, rx - 1) + lin(d, prev, ry - 1, rx + 1)
                        + lin(d, next, ry + 1, rx - 1) + lin(d, next, ry + 1, rx + 1)) >> 2;
                g = dg;
                if (c == 0) {
                    r = v;
                    b = dd;
                } else {
                    r = dd;
                    b = v;
                }
            }
            {
                const int *m = d->mat;
                int R = d->lut[clamp16((m[0] * r + m[1] * g + m[2] * b) >> 12)];
                int G = d->lut[clamp16((m[3] * r + m[4] * g + m[5] * b) >> 12)];
                int B = d->lut[clamp16((m[6] * r + m[7] * g + m[8] * b) >> 12)];
                if (d->format == 2) {
                    /* 8 bit PNG, filter Sub: each byte minus the byte 3 positions earlier */
                    o[0] = (unsigned char) ((R >> 8) - (pr >> 8));
                    o[1] = (unsigned char) ((G >> 8) - (pg >> 8));
                    o[2] = (unsigned char) ((B >> 8) - (pb >> 8));
                    pr = R;
                    pg = G;
                    pb = B;
                    o += 3;
                    continue;
                }
                if (d->format == 1) {
                    /* PNG filter Sub works on bytes: each byte minus the byte 6 positions earlier */
                    o[0] = (unsigned char) ((R >> 8) - (pr >> 8));
                    o[1] = (unsigned char) ((R & 0xff) - (pr & 0xff));
                    o[2] = (unsigned char) ((G >> 8) - (pg >> 8));
                    o[3] = (unsigned char) ((G & 0xff) - (pg & 0xff));
                    o[4] = (unsigned char) ((B >> 8) - (pb >> 8));
                    o[5] = (unsigned char) ((B & 0xff) - (pb & 0xff));
                    pr = R;
                    pg = G;
                    pb = B;
                } else {
                    o[0] = (unsigned char) R;
                    o[1] = (unsigned char) (R >> 8);
                    o[2] = (unsigned char) G;
                    o[3] = (unsigned char) (G >> 8);
                    o[4] = (unsigned char) B;
                    o[5] = (unsigned char) (B >> 8);
                }
                o += 6;
            }
        }
    }
    return NULL;
}

/*
 * Develops n output rows. cfa holds n + 2 sensor rows (one above, one below).
 * params: x0, w, y0, fullW, format; pat[4], blk[4], scale[3], mat[9]; lut 65536 shorts.
 */
JNIEXPORT jint JNICALL
Java_com_github_ronyx2_omkamera_RawCore_develop(JNIEnv *env, jclass cls, jintArray jcfa, jint n,
        jint fullW, jint x0, jint w, jint y0, jintArray jpat, jintArray jblk, jintArray jscale,
        jintArray jmat, jshortArray jlut, jint format, jbyteArray jout)
{
    int t, nt = cores();
    pthread_t th[4];
    devjob_t jobs[4];
    jint *pat = (*env)->GetIntArrayElements(env, jpat, NULL);
    jint *blk = (*env)->GetIntArrayElements(env, jblk, NULL);
    jint *scale = (*env)->GetIntArrayElements(env, jscale, NULL);
    jint *mat = (*env)->GetIntArrayElements(env, jmat, NULL);
    jshort *lut = (*env)->GetShortArrayElements(env, jlut, NULL);
    jint *cfa = (*env)->GetIntArrayElements(env, jcfa, NULL);
    int stride = format == 2 ? 1 + w * 3 : format == 1 ? 1 + w * 6 : w * 6;
    unsigned char *out = (unsigned char *) malloc((size_t) n * stride);
    if (!out) {
        nt = 0;
    }
    if (nt > n) {
        nt = n;
    }
    for (t = 0; t < nt; t++) {
        devjob_t *d = &jobs[t];
        d->cfa = cfa;
        d->fullW = fullW;
        d->x0 = x0;
        d->w = w;
        d->y0 = y0;
        d->pat = pat;
        d->blk = blk;
        d->scale = scale;
        d->mat = mat;
        d->lut = (const unsigned short *) lut;
        d->format = format;
        d->out = out;
        d->r0 = n * t / nt;
        d->r1 = n * (t + 1) / nt;
    }
    for (t = 1; t < nt; t++) {
        pthread_create(&th[t], NULL, dev_worker, &jobs[t]);
    }
    if (nt > 0) {
        dev_worker(&jobs[0]);
    }
    for (t = 1; t < nt; t++) {
        pthread_join(th[t], NULL);
    }
    if (out) {
        (*env)->SetByteArrayRegion(env, jout, 0, n * stride, (jbyte *) out);
        free(out);
    }
    (*env)->ReleaseIntArrayElements(env, jcfa, cfa, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, jlut, lut, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jmat, mat, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jscale, scale, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jblk, blk, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jpat, pat, JNI_ABORT);
    return out ? 0 : 3;
}
