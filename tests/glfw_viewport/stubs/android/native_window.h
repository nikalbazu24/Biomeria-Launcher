#pragma once

#include <stdint.h>

typedef struct ANativeWindow {
    int32_t width;
    int32_t height;
    int references;
} ANativeWindow;

static inline void ANativeWindow_acquire(ANativeWindow* window) { ++window->references; }
static inline void ANativeWindow_release(ANativeWindow* window) { --window->references; }
static inline int32_t ANativeWindow_getWidth(ANativeWindow* window) { return window->width; }
static inline int32_t ANativeWindow_getHeight(ANativeWindow* window) { return window->height; }
