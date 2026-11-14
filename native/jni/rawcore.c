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
