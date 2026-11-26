#pragma once

/* Graphics library chosen for this game: libmobileglues.so (default) or libltw.so.
 * GLFW loads it as both its EGL and OpenGL library, and LWJGL resolves GL functions from it. */
#ifdef __cplusplus
extern "C" {
#endif
const char* biomeria_renderer_library(void);
#ifdef __cplusplus
}
#endif
