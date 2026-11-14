/*
 * HEIC (HEVC via Kvazaar) and AVIF (AV1 via libaom) tile encoder for OM Kamera.
 * The app develops the image in bands of TILE rows (8 bit RGB); this file converts each
 * band to YUV 4:2:0 (BT.601, full range) and encodes it tile by tile, one encoder per
 * thread. The HEIF container is written by the Java side (Heif.java).
 * Proof of concept: slow on the camera, but standard files.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>

#include "kvazaar.h"
#include "aom/aom_encoder.h"
#include "aom/aomcx.h"

#define MAXT 4

/* Kvazaar registers its kernels in global tables when an encoder opens: one at a time */
static pthread_mutex_t open_lock = PTHREAD_MUTEX_INITIALIZER;

/* Android 2.3's libm has no log2 (API 18); the encoders need it */
#include <math.h>
double log2(double x)
{
    return log(x) * 1.4426950408889634;
}

/* Thread names are cosmetic; Android 2.3 may not have this function */
int pthread_setname_np(pthread_t t, const char *name)
{
    (void) t;
    (void) name;
    return 0;
}

typedef struct {
    unsigned char *data;
    int len;
} blob_t;

typedef struct {
    int format;        /* 0 HEIC, 1 AVIF */
    int width, tile, quality, threads;
    int cols;
    unsigned char *band;   /* tile rows x width x 3 */
    int bandRows;
    blob_t *tiles;         /* cols outputs of the last band */
    blob_t config;         /* hvcC or av1C payload */
    pthread_mutex_t lock;
    int next;              /* next tile index for the workers */
    int err;
} heif_t;

/* ---- RGB to YUV 4:2:0 for one tile (edge pixels repeated) ---- */

static void tile_yuv(const heif_t *h, int col, unsigned char *y, unsigned char *u, unsigned char *v,
                     int ys, int cs)
{
    int T = h->tile, x0 = col * T, r, c;
    for (r = 0; r < T; r++) {
        int sr = r < h->bandRows ? r : h->bandRows - 1;
        const unsigned char *row = h->band + (size_t) sr * h->width * 3;
        for (c = 0; c < T; c++) {
            int sc = x0 + c < h->width ? x0 + c : h->width - 1;
            const unsigned char *p = row + sc * 3;
            y[r * ys + c] = (unsigned char) ((77 * p[0] + 150 * p[1] + 29 * p[2] + 128) >> 8);
        }
    }
    for (r = 0; r < T / 2; r++) {
        for (c = 0; c < T / 2; c++) {
            int R = 0, G = 0, B = 0, k;
            for (k = 0; k < 4; k++) {
                int rr = 2 * r + (k >> 1), cc = 2 * c + (k & 1);
                int sr = rr < h->bandRows ? rr : h->bandRows - 1;
                int sc = x0 + cc < h->width ? x0 + cc : h->width - 1;
                const unsigned char *p = h->band + ((size_t) sr * h->width + sc) * 3;
                R += p[0];
                G += p[1];
                B += p[2];
            }
            {
                int cb = ((-43 * R - 85 * G + 128 * B) / 4 + 128 * 256 + 128) >> 8;
                int cr = ((128 * R - 107 * G - 21 * B) / 4 + 128 * 256 + 128) >> 8;
                u[r * cs + c] = (unsigned char) (cb < 0 ? 0 : cb > 255 ? 255 : cb);
                v[r * cs + c] = (unsigned char) (cr < 0 ? 0 : cr > 255 ? 255 : cr);
            }
        }
    }
}

/* ---- small helpers for the configuration boxes ---- */

static void put(blob_t *b, const void *d, int n)
{
    b->data = (unsigned char *) realloc(b->data, b->len + n);
    memcpy(b->data + b->len, d, n);
    b->len += n;
}

static void put8(blob_t *b, int v)
{
    unsigned char c = (unsigned char) v;
    put(b, &c, 1);
}

static void put16(blob_t *b, int v)
{
    put8(b, v >> 8);
    put8(b, v);
}

static void put32(blob_t *b, unsigned v)
{
    put16(b, v >> 16);
    put16(b, v & 0xffff);
}

/* ---- HEVC (Kvazaar) ---- */

/* Splits an Annex B stream into NAL units: parameter sets go to ps[0..2], slices into out
 * with 4 byte length prefixes */
static void hevc_split(const unsigned char *s, int n, blob_t *out, blob_t ps[3])
{
    int i = 0, start = -1;
    while (i + 3 <= n) {
        int sc = 0;
        if (s[i] == 0 && s[i + 1] == 0 && s[i + 2] == 1) {
            sc = 3;
        } else if (i + 4 <= n && s[i] == 0 && s[i + 1] == 0 && s[i + 2] == 0 && s[i + 3] == 1) {
            sc = 4;
        }
        if (sc) {
            if (start >= 0) {
                int len = i - start, type = (s[start] >> 1) & 0x3f;
                while (len > 0 && s[start + len - 1] == 0) {
                    len--;
                }
                if (type >= 32 && type <= 34) {
                    if (ps && ps[type - 32].len == 0) {
                        put(&ps[type - 32], s + start, len);
                    }
                } else if (type < 32) {
                    put32(out, len);
                    put(out, s + start, len);
                }
            }
            i += sc;
            start = i;
        } else {
            i++;
        }
    }
    if (start >= 0 && start < n) {
        int len = n - start, type = (s[start] >> 1) & 0x3f;
        if (type >= 32 && type <= 34) {
            if (ps && ps[type - 32].len == 0) {
                put(&ps[type - 32], s + start, len);
            }
        } else if (type < 32) {
            put32(out, len);
            put(out, s + start, len);
        }
    }
}

/* hvcC payload from VPS, SPS, PPS (profile/tier/level copied from the SPS) */
static void hevc_config(blob_t *c, blob_t ps[3])
{
    unsigned char rbsp[16];
    int i, k = 0, zeros = 0;
    const unsigned char *sps = ps[1].data;
    /* Remove emulation prevention bytes from the first part of the SPS (after the 2 byte header) */
    for (i = 2; i < ps[1].len && k < 16; i++) {
        if (zeros >= 2 && sps[i] == 3) {
            zeros = 0;
            continue;
        }
        zeros = sps[i] == 0 ? zeros + 1 : 0;
        rbsp[k++] = sps[i];
    }
    put8(c, 1);
    put(c, rbsp + 1, 12);      /* profile space/tier/idc, compat flags, constraints, level */
    put16(c, 0xf000);
    put8(c, 0xfc);
    put8(c, 0xfd);             /* chroma 4:2:0 */
    put8(c, 0xf8);
    put8(c, 0xf8);
    put16(c, 0);
    put8(c, 0x0f);             /* 1 temporal layer, nested, 4 byte lengths */
    put8(c, 3);
    for (i = 0; i < 3; i++) {
        put8(c, 0x80 | (32 + i));
        put16(c, 1);
        put16(c, ps[i].len);
        put(c, ps[i].data, ps[i].len);
    }
}

static int encode_hevc(heif_t *h, int col, blob_t *out, blob_t ps[3])
{
    const kvz_api *api = kvz_api_get(8);
    kvz_config *cfg = api->config_alloc();
    kvz_encoder *enc;
    kvz_picture *pic;
    kvz_data_chunk *chunks = NULL, *ch;
    uint32_t len = 0;
    char qp[8];
    blob_t raw = {0, 0};
    int i;
    if (!cfg) {
        return 1;
    }
    api->config_init(cfg);
    api->config_parse(cfg, "preset", "ultrafast");
    cfg->width = h->tile;
    cfg->height = h->tile;
    /* Quality 1..100 to QP 40..10 */
    snprintf(qp, sizeof(qp), "%d", 40 - (h->quality * 30) / 100);
    api->config_parse(cfg, "qp", qp);
    api->config_parse(cfg, "period", "1");
    api->config_parse(cfg, "vps-period", "1");
    api->config_parse(cfg, "threads", "0");
    api->config_parse(cfg, "owf", "0");
    api->config_parse(cfg, "gop", "0");
    api->config_parse(cfg, "info", "0");
    pthread_mutex_lock(&open_lock);
    enc = api->encoder_open(cfg);
    pthread_mutex_unlock(&open_lock);
    if (!enc) {
        api->config_destroy(cfg);
        return 2;
    }
    pic = api->picture_alloc(h->tile, h->tile);
    tile_yuv(h, col, pic->y, pic->u, pic->v, pic->stride, pic->stride / 2);
    api->encoder_encode(enc, pic, &chunks, &len, NULL, NULL, NULL);
    for (i = 0; i < 4 && !chunks; i++) {
        api->encoder_encode(enc, NULL, &chunks, &len, NULL, NULL, NULL);
    }
    for (ch = chunks; ch; ch = ch->next) {
        put(&raw, ch->data, ch->len);
    }
    api->chunk_free(chunks);
    api->picture_free(pic);
    pthread_mutex_lock(&open_lock);
    api->encoder_close(enc);
    pthread_mutex_unlock(&open_lock);
    api->config_destroy(cfg);
    if (raw.len == 0) {
        return 3;
    }
    hevc_split(raw.data, raw.len, out, ps);
    free(raw.data);
    return out->len > 0 ? 0 : 4;
}

/* ---- AV1 (libaom) ---- */

static int leb128(const unsigned char *p, int n, int *val)
{
    int i, v = 0;
    for (i = 0; i < 8 && i < n; i++) {
        v |= (p[i] & 0x7f) << (7 * i);
        if (!(p[i] & 0x80)) {
            *val = v;
            return i + 1;
        }
    }
    return -1;
}

/* Keeps all OBUs except temporal delimiters; returns the sequence header OBU in seq */
static void av1_split(const unsigned char *s, int n, blob_t *out, blob_t *seq)
{
    int i = 0;
    while (i < n) {
        int type = (s[i] >> 3) & 0x0f, ext = (s[i] >> 2) & 1, has = (s[i] >> 1) & 1;
        int hl = 1 + ext, size = 0, l = 0;
        if (has) {
            l = leb128(s + i + hl, n - i - hl, &size);
            if (l < 0) {
                break;
            }
        } else {
            size = n - i - hl;
        }
        if (type != 2) {
            put(out, s + i, hl + l + size);
            if (type == 1 && seq && seq->len == 0) {
                put(seq, s + i, hl + l + size);
            }
        }
        i += hl + l + size;
    }
}

typedef struct {
    const unsigned char *p;
    int n, bit;
} bits_t;

static int rb(bits_t *b, int k)
{
    int v = 0;
    while (k--) {
        int byte = b->bit >> 3;
        v = (v << 1) | (byte < b->n ? (b->p[byte] >> (7 - (b->bit & 7))) & 1 : 0);
        b->bit++;
    }
    return v;
}

/* av1C payload: profile, level and tier from the sequence header, 8 bit 4:2:0 */
static void av1_config(blob_t *c, blob_t *seq)
{
    int ext = (seq->data[0] >> 2) & 1, hl = 1 + ext, size = 0;
    int l = (seq->data[0] >> 1) & 1 ? leb128(seq->data + hl, seq->len - hl, &size) : 0;
    bits_t b;
    int profile, still, reduced, level = 31, tier = 0;
    b.p = seq->data + hl + (l > 0 ? l : 0);
    b.n = seq->len - hl - (l > 0 ? l : 0);
    b.bit = 0;
    profile = rb(&b, 3);
    still = rb(&b, 1);
    reduced = rb(&b, 1);
    (void) still;
    if (reduced) {
        level = rb(&b, 5);
    } else if (!rb(&b, 1)) {          /* no timing info */
        int idp = rb(&b, 1);
        rb(&b, 5);                   /* operating points - 1 */
        rb(&b, 12);                  /* operating_point_idc[0] */
        level = rb(&b, 5);
        if (level > 7) {
            tier = rb(&b, 1);
        }
        (void) idp;
    }
    put8(c, 0x81);
    put8(c, (profile << 5) | level);
    put8(c, (tier << 7) | (1 << 3) | (1 << 2));   /* 8 bit, subsampling x and y */
    put8(c, 0);
    put(c, seq->data, seq->len);
}

static int encode_av1(heif_t *h, int col, blob_t *out, blob_t *seq)
{
    aom_codec_iface_t *iface = aom_codec_av1_cx();
    aom_codec_enc_cfg_t cfg;
    aom_codec_ctx_t ctx;
    aom_image_t img;
    const aom_codec_cx_pkt_t *pkt;
    aom_codec_iter_t it = NULL;
    unsigned char *buf;
    int T = h->tile, flushes;
    blob_t raw = {0, 0};
    if (aom_codec_enc_config_default(iface, &cfg, AOM_USAGE_ALL_INTRA) != AOM_CODEC_OK) {
        return 1;
    }
    cfg.g_w = T;
    cfg.g_h = T;
    cfg.g_threads = 1;
    cfg.g_limit = 1;
    cfg.g_lag_in_frames = 0;
    cfg.rc_end_usage = AOM_Q;
    if (aom_codec_enc_init(&ctx, iface, &cfg, 0) != AOM_CODEC_OK) {
        return 2;
    }
    aom_codec_control(&ctx, AOME_SET_CPUUSED, 9);
    /* Quality 1..100 to cq-level 50..10 */
    aom_codec_control(&ctx, AOME_SET_CQ_LEVEL, 50 - (h->quality * 40) / 100);
    aom_codec_control(&ctx, AV1E_SET_COLOR_RANGE, AOM_CR_FULL_RANGE);
    aom_codec_control(&ctx, AV1E_SET_COLOR_PRIMARIES, AOM_CICP_CP_BT_709);
    aom_codec_control(&ctx, AV1E_SET_TRANSFER_CHARACTERISTICS, AOM_CICP_TC_SRGB);
    aom_codec_control(&ctx, AV1E_SET_MATRIX_COEFFICIENTS, AOM_CICP_MC_BT_601);
    buf = (unsigned char *) malloc(T * T * 3 / 2);
    if (!buf) {
        aom_codec_destroy(&ctx);
        return 3;
    }
    aom_img_wrap(&img, AOM_IMG_FMT_I420, T, T, 1, buf);
    img.range = AOM_CR_FULL_RANGE;
    tile_yuv(h, col, img.planes[0], img.planes[1], img.planes[2], img.stride[0], img.stride[1]);
    if (aom_codec_encode(&ctx, &img, 0, 1, 0) != AOM_CODEC_OK) {
        free(buf);
        aom_codec_destroy(&ctx);
        return 4;
    }
    for (flushes = 0; flushes < 2; flushes++) {
        it = NULL;
        while ((pkt = aom_codec_get_cx_data(&ctx, &it)) != NULL) {
            if (pkt->kind == AOM_CODEC_CX_FRAME_PKT) {
                put(&raw, pkt->data.frame.buf, (int) pkt->data.frame.sz);
            }
        }
        if (raw.len) {
            break;
        }
        aom_codec_encode(&ctx, NULL, 0, 0, 0);
    }
    free(buf);
    aom_codec_destroy(&ctx);
    if (!raw.len) {
        return 5;
    }
    av1_split(raw.data, raw.len, out, seq);
    free(raw.data);
    return out->len ? 0 : 6;
}

/* ---- worker threads over the tiles of one band ---- */

static void *tile_worker(void *arg)
{
    heif_t *h = (heif_t *) arg;
    for (;;) {
        int col, err;
        blob_t ps[3] = {{0, 0}, {0, 0}, {0, 0}};
        blob_t seq = {0, 0};
        int needCfg;
        pthread_mutex_lock(&h->lock);
        col = h->next < h->cols && !h->err ? h->next++ : -1;
        needCfg = h->config.len == 0;
        pthread_mutex_unlock(&h->lock);
        if (col < 0) {
            return NULL;
        }
        h->tiles[col].len = 0;
        if (h->format == 0) {
            err = encode_hevc(h, col, &h->tiles[col], needCfg ? ps : NULL);
        } else {
            err = encode_av1(h, col, &h->tiles[col], needCfg ? &seq : NULL);
        }
        pthread_mutex_lock(&h->lock);
        if (err) {
            h->err = err + (h->format ? 100 : 0);
        } else if (h->config.len == 0) {
            if (h->format == 0 && ps[0].len && ps[1].len && ps[2].len) {
                hevc_config(&h->config, ps);
            } else if (h->format == 1 && seq.len) {
                av1_config(&h->config, &seq);
            }
        }
        pthread_mutex_unlock(&h->lock);
        free(ps[0].data);
        free(ps[1].data);
        free(ps[2].data);
        free(seq.data);
    }
}

/* ---- JNI ---- */

JNIEXPORT jlong JNICALL
Java_com_github_ronyx2_omkamera_RawCore_heifNew(JNIEnv *env, jclass cls, jint format, jint width,
        jint tile, jint quality, jint threads)
{
    heif_t *h = (heif_t *) calloc(1, sizeof(heif_t));
    if (!h) {
        return 0;
    }
    h->format = format;
    h->width = width;
    h->tile = tile;
    h->quality = quality;
    h->threads = threads < 1 ? 1 : threads > MAXT ? MAXT : threads;
    h->cols = (width + tile - 1) / tile;
    h->band = (unsigned char *) malloc((size_t) tile * width * 3);
    h->tiles = (blob_t *) calloc(h->cols, sizeof(blob_t));
    pthread_mutex_init(&h->lock, NULL);
    if (!h->band || !h->tiles) {
        free(h->band);
        free(h->tiles);
        free(h);
        return 0;
    }
    return (jlong) (intptr_t) h;
}

/* Copies rows (8 bit RGB, width * 3 bytes each) to the band at row 'at' */
JNIEXPORT void JNICALL
Java_com_github_ronyx2_omkamera_RawCore_heifRows(JNIEnv *env, jclass cls, jlong ptr, jbyteArray jrows,
        jint at, jint n)
{
    heif_t *h = (heif_t *) (intptr_t) ptr;
    (*env)->GetByteArrayRegion(env, jrows, 0, n * h->width * 3, (jbyte *) h->band + (size_t) at * h->width * 3);
}

/* Encodes the band (rows valid rows) into cols tiles; 0 or an error code */
JNIEXPORT jint JNICALL
Java_com_github_ronyx2_omkamera_RawCore_heifEncode(JNIEnv *env, jclass cls, jlong ptr, jint rows)
{
    heif_t *h = (heif_t *) (intptr_t) ptr;
    pthread_t th[MAXT];
    int t, n = h->threads;
    h->bandRows = rows;
    h->next = 0;
    h->err = 0;
    for (t = 0; t < h->cols; t++) {
        free(h->tiles[t].data);
        h->tiles[t].data = NULL;
        h->tiles[t].len = 0;
    }
    for (t = 1; t < n; t++) {
        pthread_create(&th[t], NULL, tile_worker, h);
    }
    tile_worker(h);
    for (t = 1; t < n; t++) {
        pthread_join(th[t], NULL);
    }
    return h->err;
}

JNIEXPORT jbyteArray JNICALL
Java_com_github_ronyx2_omkamera_RawCore_heifTile(JNIEnv *env, jclass cls, jlong ptr, jint col)
{
    heif_t *h = (heif_t *) (intptr_t) ptr;
    blob_t *b = &h->tiles[col];
    jbyteArray a = (*env)->NewByteArray(env, b->len);
    (*env)->SetByteArrayRegion(env, a, 0, b->len, (jbyte *) b->data);
    return a;
}

JNIEXPORT jbyteArray JNICALL
Java_com_github_ronyx2_omkamera_RawCore_heifConfig(JNIEnv *env, jclass cls, jlong ptr)
{
    heif_t *h = (heif_t *) (intptr_t) ptr;
    jbyteArray a = (*env)->NewByteArray(env, h->config.len);
    (*env)->SetByteArrayRegion(env, a, 0, h->config.len, (jbyte *) h->config.data);
    return a;
}

JNIEXPORT void JNICALL
Java_com_github_ronyx2_omkamera_RawCore_heifFree(JNIEnv *env, jclass cls, jlong ptr)
{
    heif_t *h = (heif_t *) (intptr_t) ptr;
    int t;
    if (!h) {
        return;
    }
    for (t = 0; t < h->cols; t++) {
        free(h->tiles[t].data);
    }
    free(h->tiles);
    free(h->band);
    free(h->config.data);
    pthread_mutex_destroy(&h->lock);
    free(h);
}
