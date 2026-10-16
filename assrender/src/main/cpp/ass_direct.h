#ifndef ASSRENDER_ASS_DIRECT_H
#define ASSRENDER_ASS_DIRECT_H

#include <stddef.h>
#include <stdint.h>

/*
 * Threading: a context is NOT thread-safe. The caller (AssHandler) serializes
 * every call on one context, including render and destroy.
 */

typedef struct AssDirectContext AssDirectContext;

/**
 * Create a new libass-only render context (no FFmpeg).
 */
AssDirectContext *ass_direct_init(int width, int height, float font_scale);

/**
 * Load the ASS header (codec private data from ExoPlayer's Format).
 * This contains [Script Info], [V4+ Styles], etc.
 */
int ass_direct_load_header(AssDirectContext *ctx, const char *header_data, int header_size);

/**
 * Resize the render frame. The bitmap passed to ass_direct_render must match.
 */
int ass_direct_resize(AssDirectContext *ctx, int width, int height);
int ass_direct_width(const AssDirectContext *ctx);
int ass_direct_height(const AssDirectContext *ctx);

/**
 * Add a font (attachment or file contents). Takes effect after the next
 * ass_direct_finalize_fonts(); libass copies the data.
 */
void ass_direct_add_font(AssDirectContext *ctx, const char *name,
                         const char *data, int data_size);

/**
 * Add every .ttf/.otf/.ttc file in a directory (size-bounded, see ass_direct.c).
 * Returns the number of fonts added. libass has no system font provider in
 * this build, so this is how system fonts become available.
 */
int ass_direct_add_font_dir(AssDirectContext *ctx, const char *dir);

/**
 * Rebuild libass' font selector from the fonts added so far. Call once after
 * a batch of ass_direct_add_font / ass_direct_add_font_dir calls.
 */
void ass_direct_finalize_fonts(AssDirectContext *ctx);

/**
 * Process a subtitle chunk (dialogue line from ExoPlayer).
 * @param data    The raw ASS dialogue text.
 * @param size    Size of data.
 * @param start_ms Start time in milliseconds.
 * @param duration_ms Duration in milliseconds.
 */
void ass_direct_process_chunk(AssDirectContext *ctx, const char *data, int size,
                              int64_t start_ms, int64_t duration_ms);

/**
 * Render the subtitle frame at the given timestamp.
 * @param out_pixels Output buffer: RGBA_8888 with PREMULTIPLIED alpha (the
 *                   Android Bitmap convention), at least height rows of
 *                   `stride` bytes.
 * @param stride     Row stride in bytes (>= width*4).
 * @return 1 if content was rendered, 0 if blank.
 */
int ass_direct_render(AssDirectContext *ctx, int64_t time_ms, uint8_t *out_pixels,
                      size_t stride);

/**
 * Process raw ASS data (full dialogue lines with embedded timing).
 */
void ass_direct_process_data(AssDirectContext *ctx, const char *data, int size);

/**
 * Flush all events (e.g. on seek or track change).
 */
void ass_direct_flush(AssDirectContext *ctx);

/**
 * Destroy and free all resources.
 */
void ass_direct_destroy(AssDirectContext *ctx);

#endif // ASSRENDER_ASS_DIRECT_H
