cmake_minimum_required(VERSION 3.22.1)
project(assrender C)

# Prebuilt library paths (populated by scripts/build-libass.sh)
set(PREBUILT_DIR "${CMAKE_SOURCE_DIR}/../../../prebuilt/${ANDROID_ABI}")

# --- libass and dependencies ---
# libass is built without a system font provider (no fontconfig); fonts are
# supplied at runtime through ass_add_font (see ass_direct.c).
add_library(ass SHARED IMPORTED)
set_target_properties(ass PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_DIR}/lib/libass.so"
)

add_library(freetype SHARED IMPORTED)
set_target_properties(freetype PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_DIR}/lib/libfreetype.so"
)

add_library(fribidi SHARED IMPORTED)
set_target_properties(fribidi PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_DIR}/lib/libfribidi.so"
)

add_library(harfbuzz SHARED IMPORTED)
set_target_properties(harfbuzz PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_DIR}/lib/libharfbuzz.so"
)

# --- Include paths ---
include_directories(
    "${PREBUILT_DIR}/include"
)

# --- Our library ---
add_library(assrender SHARED
    ass_direct.c
    ass_direct_jni.c
)

target_link_libraries(assrender
    ass
    freetype
    fribidi
    harfbuzz
    android
    jnigraphics
    log
)
