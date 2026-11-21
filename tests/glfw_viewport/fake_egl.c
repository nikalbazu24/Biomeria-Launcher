#include "stubs/android/native_window.h"

#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

static void* pending = (void*)0x31;
static void* surface = (void*)0x32;
static int failNextSwap;

void mockFailNextSwap(void) { failNextSwap = 1; }

void* eglGetDisplay(void* unused) { return pending; }
int eglInitialize(void* display, int* major, int* minor)
{
    assert(display == pending);
    *major = 1;
    *minor = 5;
    return 1;
}
int eglGetError(void) { return 0x3000; }
const char* eglQueryString(void* display, int name)
{
    return name == 0x3055 ? "EGL_KHR_create_context" : "1.5";
}
int eglGetConfigs(void* display, void** config, int cap, int* count)
{
    *count = 1;
    if (config && cap) *config = (void*)0x33;
    return 1;
}
int eglGetConfigAttrib(void* display, void* config, int attribute, int* value)
{
    assert(config == (void*)0x33);
    switch (attribute)
    {
        case 0x3033: *value = 4; break;  // EGL_WINDOW_BIT
        case 0x303F: *value = 0x308E; break; // EGL_RGB_BUFFER
        case 0x3040: *value = 8; break;  // EGL_OPENGL_BIT
        case 0x3031: *value = 0; break;
        default: *value = 8; break;
    }
    return 1;
}
int eglBindAPI(unsigned api) { assert(api == 0x30a2); return 1; }
void* eglCreateContext(void* display, void* config, void* share, const int* attributes)
{
    return (void*)0x34;
}
void* eglCreateWindowSurface(void* display, void* config, void* window, const int* attributes)
{
    assert(window && ANativeWindow_getWidth(window) == 1920);
    return surface;
}
int eglMakeCurrent(void* display, void* draw, void* read, void* context) { return 1; }
int eglSwapBuffers(void* display, void* target)
{
    assert(target == surface);
    const int success = !failNextSwap;
    failNextSwap = 0;
    return success;
}
int eglSwapInterval(void* display, int interval) { return 1; }
int eglDestroyContext(void* display, void* context) { return 1; }
int eglDestroySurface(void* display, void* target) { return 1; }
int eglTerminate(void* display) { return 1; }

const unsigned char* glGetString(unsigned name)
{
    return name == 0x1F02 ? (const unsigned char*)"3.2 PRISSMA test" : (const unsigned char*)"";
}
const unsigned char* glGetStringi(unsigned name, unsigned index) { return NULL; }
void glGetIntegerv(unsigned name, int* result) { *result = name == 0x9126 ? 1 : 0; }
void glClear(unsigned mask) {}
void (*eglGetProcAddress(const char* name))(void)
{
    if (strcmp(name, "glGetString") == 0) return (void(*)(void))glGetString;
    if (strcmp(name, "glGetStringi") == 0) return (void(*)(void))glGetStringi;
    if (strcmp(name, "glGetIntegerv") == 0) return (void(*)(void))glGetIntegerv;
    if (strcmp(name, "glClear") == 0) return (void(*)(void))glClear;
    return NULL;
}
