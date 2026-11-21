#pragma once

struct ANativeWindow;

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((visibility("default"))) void prissmaGlfwSetSurface(struct ANativeWindow* surface);
__attribute__((visibility("default"))) void prissmaGlfwPointer(double x, double y, int buttonAction);

#ifdef __cplusplus
}
#endif
