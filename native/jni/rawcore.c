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
#include <time.h>
#include <stdio.h>




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
    const unsigned char *src;  /* the rows of this job, read before */
    int bpr;                   /* bytes per stored row */
    int rawWidth, width, compression, little;
    const int *curve;
    int rows;
    int *out;      /* rows of 'width' ints */
    int err;
} job_t;

/* Decodes rows that are already in memory */
static void *worker(void *arg)
{
    job_t *j = (job_t *) arg;
    int y;
    for (y = 0; y < j->rows; y++) {
        const unsigned char *buf = j->src + (size_t) y * j->bpr;
        int *o = j->out + (size_t) y * j->width;
        if (j->compression == 32767) {
            decode_row(buf, j->rawWidth, j->width, j->curve, o);
        } else {
            int c;
            for (c = 0; c < j->width; c++) {
                o[c] = j->little ? (buf[2 * c] | buf[2 * c + 1] << 8) : (buf[2 * c] << 8 | buf[2 * c + 1]);
            }
        }
    }
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
/*
 * The read buffer is kept between calls: allocating and freeing some MB for every band
 * (over a thousand times when combining) costs the camera more than the decoding itself.
 * Calls are serialised by the mutex; the rows go straight into the Java array.
 */
static pthread_mutex_t dec_lock = PTHREAD_MUTEX_INITIALIZER;
static unsigned char *dec_buf;
static size_t dec_cap;

JNIEXPORT jint JNICALL
Java_com_github_ronyx2_omkamera_RawCore_decode(JNIEnv *env, jclass cls, jint fd, jlong offset,
        jint rawWidth, jint width, jint compression, jboolean little, jintArray jcurve,
        jint y, jint count, jintArray jout)
{
    int n = cores(), i, err = 0;
    pthread_t th[4];
    job_t jobs[4];
    int bpr = compression == 32767 ? rawWidth : rawWidth * 2;
    size_t total = (size_t) count * bpr, got = 0;
    off_t base = (off_t) (offset + (long long) y * bpr);
    jint *curve, *out;

    if ((*env)->GetArrayLength(env, jout) < count * width) {
        return 4;
    }
    pthread_mutex_lock(&dec_lock);
    if (dec_cap < total + 32) {
        unsigned char *b = (unsigned char *) realloc(dec_buf, total + 32);
        if (!b) {
            pthread_mutex_unlock(&dec_lock);
            return 3;
        }
        dec_buf = b;
        dec_cap = total + 32;
    }
    memset(dec_buf + total, 0, 32);
    /* One sequential read for the whole band */
    while (got < total) {
        ssize_t r = pread(fd, dec_buf + got, total - got, base + (off_t) got);
        if (r <= 0) {
            pthread_mutex_unlock(&dec_lock);
            return 2;
        }
        got += (size_t) r;
    }
    curve = (*env)->GetIntArrayElements(env, jcurve, NULL);
    out = (jint *) (*env)->GetPrimitiveArrayCritical(env, jout, NULL);
    if (!curve || !out) {
        if (out) {
            (*env)->ReleasePrimitiveArrayCritical(env, jout, out, JNI_ABORT);
        }
        if (curve) {
            (*env)->ReleaseIntArrayElements(env, jcurve, curve, JNI_ABORT);
        }
        pthread_mutex_unlock(&dec_lock);
        return 3;
    }
    if (n > count) {
        n = count;
    }
    for (i = 0; i < n; i++) {
        job_t *j = &jobs[i];
        int y0 = count * i / n, y1 = count * (i + 1) / n;
        j->src = dec_buf + (size_t) y0 * bpr;
        j->bpr = bpr;
        j->rawWidth = rawWidth;
        j->width = width;
        j->compression = compression;
        j->little = little;
        j->curve = (const int *) curve;
        j->rows = y1 - y0;
        j->out = (int *) out + (size_t) y0 * width;
        j->err = 0;
    }
    for (i = 1; i < n; i++) {
        if (pthread_create(&th[i], NULL, worker, &jobs[i]) != 0) {
            worker(&jobs[i]);
            jobs[i].err = -1;
        }
    }
    worker(&jobs[0]);
    for (i = 1; i < n; i++) {
        if (jobs[i].err != -1) {
            pthread_join(th[i], NULL);
        }
    }
    (*env)->ReleasePrimitiveArrayCritical(env, jout, out, 0);
    (*env)->ReleaseIntArrayElements(env, jcurve, curve, JNI_ABORT);
    pthread_mutex_unlock(&dec_lock);
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
    int stride = d->format == 3 ? d->w * 3 : d->format == 2 ? 1 + d->w * 3 : d->format == 1 ? 1 + d->w * 6 : d->w * 6;
    for (y = d->r0; y < d->r1; y++) {
        int ry = d->y0 + y;
        const int *prev = d->cfa + (size_t) y * d->fullW;
        const int *cur = prev + d->fullW;
        const int *next = cur + d->fullW;
        unsigned char *o = d->out + (size_t) y * stride;
        int pr = 0, pg = 0, pb = 0;
        if (d->format == 1 || d->format == 2) {
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
                if (d->format == 3) {
                    /* plain 8 bit RGB for the HEIF encoders */
                    o[0] = (unsigned char) (R >> 8);
                    o[1] = (unsigned char) (G >> 8);
                    o[2] = (unsigned char) (B >> 8);
                    o += 3;
                    continue;
                }
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
    int stride = format == 3 ? w * 3 : format == 2 ? 1 + w * 3 : format == 1 ? 1 + w * 6 : w * 6;
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

/* ---- parallel deflate for PNG (like pigz): parts compressed independently, joined ---- */

#include <zlib.h>

typedef struct {
    const unsigned char *in;
    int inLen;
    unsigned char *out;
    int outCap, outLen;
    int level, finish;
    int err;
} zjob_t;

static void *zworker(void *arg)
{
    zjob_t *j = (zjob_t *) arg;
    z_stream s;
    memset(&s, 0, sizeof(s));
    /* raw deflate (no header): the caller writes the zlib header and the Adler-32.
     * Huffman only: on filtered, noisy photo data as small as LZ77 and about 3x faster */
    if (deflateInit2(&s, j->level, Z_DEFLATED, -15, 8, Z_HUFFMAN_ONLY) != Z_OK) {
        j->err = 1;
        return NULL;
    }
    s.next_in = (Bytef *) j->in;
    s.avail_in = j->inLen;
    s.next_out = j->out;
    s.avail_out = j->outCap;
    /* Sync flush ends on a byte boundary without the final bit, so parts can be joined */
    if (deflate(&s, j->finish ? Z_FINISH : Z_SYNC_FLUSH) == Z_STREAM_ERROR || s.avail_in != 0) {
        j->err = 2;
    }
    j->outLen = j->outCap - s.avail_out;
    deflateEnd(&s);
    return NULL;
}

/*
 * Compresses in[0..len) into out, split over all cores. last: the stream ends here.
 * adler[0]: running Adler-32 of all uncompressed data (updated). Returns the number of
 * bytes written or a negative error code.
 */
JNIEXPORT jint JNICALL
Java_com_github_ronyx2_omkamera_RawCore_deflate(JNIEnv *env, jclass cls, jbyteArray jin, jint len,
        jint level, jboolean last, jbyteArray jout, jlongArray jadler)
{
    int n = cores(), t, total = 0, err = 0;
    pthread_t th[4];
    zjob_t jobs[4];
    unsigned char *bufs[4];
    jbyte *in = (*env)->GetByteArrayElements(env, jin, NULL);
    jlong ad;
    int outCap = (*env)->GetArrayLength(env, jout);
    (*env)->GetLongArrayRegion(env, jadler, 0, 1, &ad);
    if (len < 64 * 1024) {
        n = 1;
    }
    for (t = 0; t < n; t++) {
        int a = (int) ((long long) len * t / n), b = (int) ((long long) len * (t + 1) / n);
        jobs[t].in = (const unsigned char *) in + a;
        jobs[t].inLen = b - a;
        jobs[t].outCap = (b - a) + (b - a) / 8 + 1024;
        bufs[t] = (unsigned char *) malloc(jobs[t].outCap);
        jobs[t].out = bufs[t];
        jobs[t].level = level;
        jobs[t].finish = last && t == n - 1;
        jobs[t].err = bufs[t] ? 0 : 3;
        jobs[t].outLen = 0;
    }
    for (t = 1; t < n; t++) {
        if (!jobs[t].err) {
            pthread_create(&th[t], NULL, zworker, &jobs[t]);
        }
    }
    if (!jobs[0].err) {
        zworker(&jobs[0]);
    }
    /* Adler-32 of the uncompressed data while the other threads work */
    ad = adler32((uLong) ad, (const Bytef *) in, len);
    for (t = 1; t < n; t++) {
        if (jobs[t].err != 3) {
            pthread_join(th[t], NULL);
        }
    }
    for (t = 0; t < n; t++) {
        if (jobs[t].err) {
            err = -10 - jobs[t].err;
        } else if (total + jobs[t].outLen > outCap) {
            err = -20;
        } else if (!err) {
            (*env)->SetByteArrayRegion(env, jout, total, jobs[t].outLen, (jbyte *) bufs[t]);
            total += jobs[t].outLen;
        }
        free(bufs[t]);
    }
    (*env)->ReleaseByteArrayElements(env, jin, in, JNI_ABORT);
    (*env)->SetLongArrayRegion(env, jadler, 0, 1, &ad);
    return err ? err : total;
}

/* ---- downscaling rows for HEIC/AVIF: bilinear between two source rows ---- */

/*
 * a, b: neighbouring source rows (8 bit RGB, wIn pixels), fy: weight of b (0..256),
 * out: wOut pixels. Horizontal positions like the vertical ones: (x + 0.5) / s - 0.5.
 */
JNIEXPORT void JNICALL
Java_com_github_ronyx2_omkamera_RawCore_scaleRow(JNIEnv *env, jclass cls, jbyteArray ja, jbyteArray jb,
        jint fy, jint wIn, jint wOut, jbyteArray jout)
{
    unsigned char *a = (*env)->GetPrimitiveArrayCritical(env, ja, NULL);
    unsigned char *b = (*env)->GetPrimitiveArrayCritical(env, jb, NULL);
    unsigned char *o = (*env)->GetPrimitiveArrayCritical(env, jout, NULL);
    int x, c;
    long long step = ((long long) wIn << 16) / wOut;
    for (x = 0; x < wOut; x++) {
        long long sx = ((long long) x << 16) * wIn / wOut + step / 2 - 32768;
        int x0, fx;
        if (sx < 0) {
            sx = 0;
        }
        x0 = (int) (sx >> 16);
        fx = (int) ((sx >> 8) & 0xff);
        if (x0 >= wIn - 1) {
            x0 = wIn - 2;
            fx = 256;
        }
        for (c = 0; c < 3; c++) {
            int p00 = a[x0 * 3 + c], p01 = a[x0 * 3 + 3 + c];
            int p10 = b[x0 * 3 + c], p11 = b[x0 * 3 + 3 + c];
            int top = p00 * (256 - fx) + p01 * fx;
            int bot = p10 * (256 - fx) + p11 * fx;
            o[x * 3 + c] = (unsigned char) ((top * (256 - fy) + bot * fy + 32768) >> 16);
        }
    }
    (*env)->ReleasePrimitiveArrayCritical(env, jout, o, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, jb, b, JNI_ABORT);
    (*env)->ReleasePrimitiveArrayCritical(env, ja, a, JNI_ABORT);
}

/* ---- measurement: reading from the card vs. decoding in memory ---- */

static long long now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long) t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

JNIEXPORT jstring JNICALL
Java_com_github_ronyx2_omkamera_RawCore_bench(JNIEnv *env, jclass cls, jint fd, jlong offset,
        jint rawWidth, jint width, jint rows, jintArray jcurve)
{
    char msg[256];
    size_t total, got = 0;
    if (rows > 1024) {
        rows = 1024;   /* 6 MB in, 24 MB out at most */
    }
    total = (size_t) rows * rawWidth;
    unsigned char *src = (unsigned char *) malloc(total + 32);
    int *out = (int *) malloc((size_t) rows * width * sizeof(int));
    long long t0, t1, t2, t3;
    int n = cores(), i;
    if (!src || !out) {
        free(src);
        free(out);
        return (*env)->NewStringUTF(env, "kein Speicher");
    }
    memset(src + total, 0, 32);
    t0 = now_ms();
    while (got < total) {
        size_t want = total - got > 1048576 ? 1048576 : total - got;
        ssize_t r = pread(fd, src + got, want, (off_t) (offset + (long long) got));
        if (r <= 0) {
            break;
        }
        got += (size_t) r;
    }
    t1 = now_ms();
    jint *curve = (*env)->GetIntArrayElements(env, jcurve, NULL);
    {
        job_t j;
        j.src = src; j.bpr = rawWidth; j.rawWidth = rawWidth; j.width = width; j.compression = 32767;
        j.little = 1; j.curve = curve; j.rows = rows; j.out = out; j.err = 0;
        worker(&j);
    }
    t2 = now_ms();
    {
        pthread_t th[4];
        job_t jobs[4];
        for (i = 0; i < n; i++) {
            int y0 = rows * i / n, y1 = rows * (i + 1) / n;
            jobs[i].src = src + (size_t) y0 * rawWidth; jobs[i].bpr = rawWidth; jobs[i].rawWidth = rawWidth;
            jobs[i].width = width; jobs[i].compression = 32767; jobs[i].little = 1; jobs[i].curve = curve;
            jobs[i].rows = y1 - y0; jobs[i].out = out + (size_t) y0 * width; jobs[i].err = 0;
        }
        for (i = 1; i < n; i++) {
            pthread_create(&th[i], NULL, worker, &jobs[i]);
        }
        worker(&jobs[0]);
        for (i = 1; i < n; i++) {
            pthread_join(th[i], NULL);
        }
    }
    t3 = now_ms();
    (*env)->ReleaseIntArrayElements(env, jcurve, curve, JNI_ABORT);
    snprintf(msg, sizeof msg,
             "%d Zeilen, %.1f MB: lesen %lld ms (%.1f MB/s), entpacken 1 Thread %lld ms, %d Threads %lld ms",
             rows, got / 1048576.0, t1 - t0, t1 > t0 ? got / 1048576.0 / ((t1 - t0) / 1000.0) : 0.0,
             t2 - t1, n, t3 - t2);
    free(src);
    free(out);
    return (*env)->NewStringUTF(env, msg);
}
