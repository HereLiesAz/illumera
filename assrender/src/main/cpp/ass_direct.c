#include "ass_direct.h"

#include <ctype.h>
#include <dirent.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>
#include <ass/ass.h>

#define LOG_TAG "assrender"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct AssDirectContext {
    int width;
    int height;
    float font_scale;

    ASS_Library *ass_library;
    ASS_Renderer *ass_renderer;
    ASS_Track *ass_track;

    // Bytes of font data handed to ass_add_font so far (libass keeps a copy).
    size_t font_bytes;
};

// libass copies every font passed to ass_add_font and keeps it for the lifetime
// of the library, so scanning system font directories is bounded to avoid
// holding the whole of /system/fonts (CJK collections alone are tens of MB).
#define MAX_FONT_FILE_BYTES   ((size_t)32 * 1024 * 1024)
#define MAX_FONT_TOTAL_BYTES  ((size_t)64 * 1024 * 1024)

// Route libass diagnostics to logcat. Levels: 0 fatal, 1 error, 2 warning,
// 3-4 info, 5 verbose (6-7 are very chatty and dropped).
static void ass_log_cb(int level, const char *fmt, va_list va, void *data) {
    (void)data;
    if (level > 5) return;
    int prio = level <= 1 ? ANDROID_LOG_ERROR
             : level == 2 ? ANDROID_LOG_WARN
             : level <= 4 ? ANDROID_LOG_INFO
                          : ANDROID_LOG_DEBUG;
    __android_log_vprint(prio, "libass", fmt, va);
}

// Last-resort font for glyphs/families nothing else matches. Without a system
// font provider libass has no other fallback source.
static const char *find_default_font(void) {
    static const char *const candidates[] = {
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/Roboto[wdth,wght].ttf",
        "/system/fonts/DroidSans.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        NULL
    };
    for (int i = 0; candidates[i]; i++) {
        if (access(candidates[i], R_OK) == 0) return candidates[i];
    }
    return NULL;
}

// libass here is built WITHOUT fontconfig (see scripts/build-libass.sh), so
// there is no system font provider: only fonts added through ass_add_font
// (attachments and ass_direct_add_font_dir) are available. (Re)build the font
// selector from the fonts added so far.
static void apply_fonts(AssDirectContext *ctx) {
    ass_set_fonts(ctx->ass_renderer, find_default_font(), "sans-serif",
                  ASS_FONTPROVIDER_NONE, NULL, 1);
}

AssDirectContext *ass_direct_init(int width, int height, float font_scale) {
    if (width <= 0 || height <= 0) return NULL;

    AssDirectContext *ctx = calloc(1, sizeof(AssDirectContext));
    if (!ctx) return NULL;

    ctx->width = width;
    ctx->height = height;
    ctx->font_scale = font_scale;

    ctx->ass_library = ass_library_init();
    if (!ctx->ass_library) {
        LOGE("Failed to init libass library");
        free(ctx);
        return NULL;
    }
    ass_set_message_cb(ctx->ass_library, ass_log_cb, NULL);
    ass_set_extract_fonts(ctx->ass_library, 1);

    ctx->ass_renderer = ass_renderer_init(ctx->ass_library);
    if (!ctx->ass_renderer) {
        LOGE("Failed to init libass renderer");
        ass_library_done(ctx->ass_library);
        free(ctx);
        return NULL;
    }

    ass_set_storage_size(ctx->ass_renderer, width, height);
    ass_set_frame_size(ctx->ass_renderer, width, height);
    ass_set_font_scale(ctx->ass_renderer, (double)font_scale);
    // No fonts yet; the caller adds system/attachment fonts and then calls
    // ass_direct_finalize_fonts() once.
    apply_fonts(ctx);

    LOGI("ass_direct initialized: %dx%d, font_scale=%.2f", width, height, font_scale);
    return ctx;
}

int ass_direct_resize(AssDirectContext *ctx, int width, int height) {
    if (!ctx || width <= 0 || height <= 0) return -1;
    ctx->width = width;
    ctx->height = height;
    ass_set_storage_size(ctx->ass_renderer, width, height);
    ass_set_frame_size(ctx->ass_renderer, width, height);
    return 0;
}

int ass_direct_width(const AssDirectContext *ctx) { return ctx ? ctx->width : 0; }
int ass_direct_height(const AssDirectContext *ctx) { return ctx ? ctx->height : 0; }

int ass_direct_load_header(AssDirectContext *ctx, const char *header_data, int header_size) {
    if (!ctx || !header_data || header_size <= 0) return -1;

    // Free previous track if any
    if (ctx->ass_track) {
        ass_free_track(ctx->ass_track);
        ctx->ass_track = NULL;
    }

    ctx->ass_track = ass_new_track(ctx->ass_library);
    if (!ctx->ass_track) {
        LOGE("Failed to create ASS track");
        return -1;
    }

    ass_process_codec_private(ctx->ass_track, (char *)header_data, header_size);

    LOGI("Loaded ASS header: %d bytes, %d styles",
         header_size, ctx->ass_track->n_styles);
    return 0;
}

void ass_direct_add_font(AssDirectContext *ctx, const char *name,
                         const char *data, int data_size) {
    if (!ctx || !data || data_size <= 0) return;
    ass_add_font(ctx->ass_library, name, (char *)data, data_size);
    ctx->font_bytes += (size_t)data_size;
    LOGI("Added font: %s (%d bytes)", name ? name : "unnamed", data_size);
}

void ass_direct_finalize_fonts(AssDirectContext *ctx) {
    if (!ctx) return;
    apply_fonts(ctx);
    LOGI("Fonts finalized (%zu bytes of font data)", ctx->font_bytes);
}

typedef struct {
    char *path;
    const char *name;
    off_t size;
} FontFile;

static int has_font_extension(const char *name) {
    const char *dot = strrchr(name, '.');
    if (!dot) return 0;
    return strcasecmp(dot, ".ttf") == 0 || strcasecmp(dot, ".otf") == 0 ||
           strcasecmp(dot, ".ttc") == 0;
}

static int compare_font_size(const void *a, const void *b) {
    const FontFile *fa = a, *fb = b;
    if (fa->size != fb->size) return fa->size < fb->size ? -1 : 1;
    return strcmp(fa->name, fb->name);
}

int ass_direct_add_font_dir(AssDirectContext *ctx, const char *dir) {
    if (!ctx || !dir) return 0;
    DIR *d = opendir(dir);
    if (!d) return 0;

    FontFile *files = NULL;
    size_t count = 0, cap = 0;
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        if (!has_font_extension(ent->d_name)) continue;
        char *path = NULL;
        size_t path_len = strlen(dir) + 1 + strlen(ent->d_name) + 1;
        path = malloc(path_len);
        if (!path) break;
        snprintf(path, path_len, "%s/%s", dir, ent->d_name);

        struct stat st;
        if (stat(path, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
            (size_t)st.st_size > MAX_FONT_FILE_BYTES) {
            free(path);
            continue;
        }
        if (count == cap) {
            size_t new_cap = cap ? cap * 2 : 64;
            FontFile *grown = realloc(files, new_cap * sizeof(FontFile));
            if (!grown) { free(path); break; }
            files = grown;
            cap = new_cap;
        }
        files[count].path = path;
        files[count].name = path + strlen(dir) + 1;
        files[count].size = st.st_size;
        count++;
    }
    closedir(d);

    // Smallest first, so the byte budget covers as many families as possible
    // before the large CJK collections.
    if (count > 1) qsort(files, count, sizeof(FontFile), compare_font_size);

    int added = 0;
    for (size_t i = 0; i < count; i++) {
        size_t size = (size_t)files[i].size;
        if (ctx->font_bytes + size > MAX_FONT_TOTAL_BYTES) {
            LOGI("Font budget reached, skipping %s", files[i].name);
            continue;
        }
        FILE *f = fopen(files[i].path, "rb");
        if (!f) continue;
        char *buf = malloc(size);
        if (buf && fread(buf, 1, size, f) == size) {
            ass_direct_add_font(ctx, files[i].name, buf, (int)size);
            added++;
        }
        free(buf);
        fclose(f);
    }

    for (size_t i = 0; i < count; i++) free(files[i].path);
    free(files);
    LOGI("Scanned %s: %d fonts added", dir, added);
    return added;
}

void ass_direct_process_chunk(AssDirectContext *ctx, const char *data, int size,
                              int64_t start_ms, int64_t duration_ms) {
    if (!ctx || !ctx->ass_track || !data || size <= 0) return;
    ass_process_chunk(ctx->ass_track, (char *)data, size, start_ms, duration_ms);
}

void ass_direct_process_data(AssDirectContext *ctx, const char *data, int size) {
    if (!ctx || !ctx->ass_track || !data || size <= 0) return;
    ass_process_data(ctx->ass_track, (char *)data, size);
}

int ass_direct_render(AssDirectContext *ctx, int64_t time_ms, uint8_t *out_pixels,
                     size_t stride) {
    if (!ctx || !ctx->ass_track || !ctx->ass_renderer || !out_pixels)
        return 0;
    if (ctx->width <= 0 || ctx->height <= 0 ||
        (size_t)ctx->width > SIZE_MAX / 4u || stride < (size_t)ctx->width * 4u) {
        LOGE("Invalid render dimensions: %dx%d stride=%zu", ctx->width, ctx->height, stride);
        return 0;
    }

    int changed = 0;
    ASS_Image *img = ass_render_frame(ctx->ass_renderer, ctx->ass_track,
                                      time_ms, &changed);

    const size_t row_bytes = (size_t)ctx->width * 4u;
    for (int y = 0; y < ctx->height; y++) {
        memset(out_pixels + (size_t)y * stride, 0, row_bytes);
    }

    if (!img) return 0;

    // Composite all ASS_Image layers into the output buffer. The destination
    // is an Android ARGB_8888 bitmap, which is PREMULTIPLIED (bytes R,G,B,A in
    // memory), so colours are stored already multiplied by alpha and layers
    // are combined with the premultiplied "over" operator. Clip every libass
    // image to the destination frame before doing pointer arithmetic.
    int has_content = 0;
    while (img) {
        if (!img->bitmap || img->stride <= 0 || img->w <= 0 || img->h <= 0) {
            img = img->next;
            continue;
        }

        int src_x = 0;
        int src_y = 0;
        int dst_x = img->dst_x;
        int dst_y = img->dst_y;
        int draw_w = img->w;
        int draw_h = img->h;

        if (dst_x < 0) {
            if (dst_x == INT32_MIN) {
                img = img->next;
                continue;
            }
            src_x = -dst_x;
            if (src_x >= draw_w) {
                img = img->next;
                continue;
            }
            draw_w -= src_x;
            dst_x = 0;
        }
        if (dst_y < 0) {
            if (dst_y == INT32_MIN) {
                img = img->next;
                continue;
            }
            src_y = -dst_y;
            if (src_y >= draw_h) {
                img = img->next;
                continue;
            }
            draw_h -= src_y;
            dst_y = 0;
        }
        if (dst_x >= ctx->width || dst_y >= ctx->height) {
            img = img->next;
            continue;
        }

        if (draw_w > ctx->width - dst_x) draw_w = ctx->width - dst_x;
        if (draw_h > ctx->height - dst_y) draw_h = ctx->height - dst_y;
        if (draw_w <= 0 || draw_h <= 0) {
            img = img->next;
            continue;
        }

        uint32_t r = (img->color >> 24) & 0xFF;
        uint32_t g = (img->color >> 16) & 0xFF;
        uint32_t b = (img->color >> 8) & 0xFF;
        uint32_t a = 255 - (img->color & 0xFF);

        for (int y = 0; y < draw_h; y++) {
            const size_t src_row = (size_t)(src_y + y) * (size_t)img->stride;
            const uint8_t *src = img->bitmap + src_row + (size_t)src_x;
            uint8_t *dst = out_pixels + (size_t)(dst_y + y) * stride + (size_t)dst_x * 4u;

            for (int x = 0; x < draw_w; x++, dst += 4) {
                // Glyph coverage scaled by the layer opacity, 0..255.
                uint32_t sa = ((uint32_t)src[x] * a + 127u) / 255u;
                if (sa == 0) continue;

                has_content = 1;
                // Premultiplied over: out = src * sa + dst * (1 - sa).
                uint32_t inv = 255u - sa;
                dst[0] = (uint8_t)((r * sa + dst[0] * inv + 127u) / 255u);
                dst[1] = (uint8_t)((g * sa + dst[1] * inv + 127u) / 255u);
                dst[2] = (uint8_t)((b * sa + dst[2] * inv + 127u) / 255u);
                dst[3] = (uint8_t)(sa + (dst[3] * inv + 127u) / 255u);
            }
        }
        img = img->next;
    }

    return has_content;
}

void ass_direct_flush(AssDirectContext *ctx) {
    if (!ctx || !ctx->ass_track) return;
    ass_flush_events(ctx->ass_track);
    LOGI("Flushed ASS events");
}

void ass_direct_destroy(AssDirectContext *ctx) {
    if (!ctx) return;

    if (ctx->ass_track)
        ass_free_track(ctx->ass_track);
    if (ctx->ass_renderer)
        ass_renderer_done(ctx->ass_renderer);
    if (ctx->ass_library)
        ass_library_done(ctx->ass_library);

    free(ctx);
    LOGI("ass_direct destroyed");
}
