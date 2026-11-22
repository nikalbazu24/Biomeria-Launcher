#pragma once

struct ANativeWindow;

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((visibility("default"))) void prissmaGlfwSetSurface(struct ANativeWindow* surface);
__attribute__((visibility("default"))) void prissmaGlfwSetSize(int width, int height);
__attribute__((visibility("default"))) void prissmaGlfwInput(int kind, int code, int action, double x, double y);
__attribute__((visibility("default"))) int prissmaGlfwCursorCaptured(void);
__attribute__((visibility("default"))) int prissmaGlfwHasPresentedFrame(void);
__attribute__((visibility("default"))) unsigned int prissmaGlfwPresentedFrames(void);
__attribute__((visibility("default"))) void prissmaGlfwSetFrameLimit(int fps);

#ifdef __cplusplus
}
#endif
