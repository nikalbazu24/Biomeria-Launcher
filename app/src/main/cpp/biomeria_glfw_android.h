#pragma once

struct ANativeWindow;

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((visibility("default"))) void biomeriaGlfwSetSurface(struct ANativeWindow* surface);
__attribute__((visibility("default"))) void biomeriaGlfwSetSize(int width, int height);
__attribute__((visibility("default"))) void biomeriaGlfwInput(int kind, int code, int action, double x, double y);
__attribute__((visibility("default"))) int biomeriaGlfwCursorCaptured(void);
__attribute__((visibility("default"))) int biomeriaGlfwHasPresentedFrame(void);
__attribute__((visibility("default"))) unsigned int biomeriaGlfwPresentedFrames(void);
__attribute__((visibility("default"))) void biomeriaGlfwSetFrameLimit(int fps);

#ifdef __cplusplus
}
#endif
