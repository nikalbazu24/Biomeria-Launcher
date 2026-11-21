#include "internal.h"
#include "prissma_glfw_android.h"

#include <android/native_window.h>
#include <pthread.h>
#include <stdio.h>

typedef struct {
    double x;
    double y;
    int buttonAction;
} PointerEvent;

static pthread_mutex_t eventLock = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow* pendingSurface;
static ANativeWindow* activeSurface;
static _GLFWwindow* activeWindow;
static PointerEvent pointerQueue[64];
static unsigned int pointerCount;
static PFN_eglSwapBuffers swapBuffersEgl;
static GLFWbool firstFramePresented;

void prissmaGlfwSetSurface(ANativeWindow* surface)
{
    pthread_mutex_lock(&eventLock);
    if (surface)
        ANativeWindow_acquire(surface);
    if (pendingSurface)
        ANativeWindow_release(pendingSurface);
    pendingSurface = surface;
    pointerCount = 0;
    pthread_mutex_unlock(&eventLock);
}

void prissmaGlfwPointer(double x, double y, int buttonAction)
{
    pthread_mutex_lock(&eventLock);
    if (buttonAction < 0 && pointerCount > 0 && pointerQueue[pointerCount - 1].buttonAction < 0)
        pointerQueue[pointerCount - 1] = (PointerEvent){x, y, buttonAction};
    else if (pointerCount < sizeof(pointerQueue) / sizeof(pointerQueue[0]))
        pointerQueue[pointerCount++] = (PointerEvent){x, y, buttonAction};
    else
        pointerQueue[pointerCount - 1] = (PointerEvent){x, y, buttonAction};
    pthread_mutex_unlock(&eventLock);
}

static EGLBoolean APIENTRY reportSwapBuffers(EGLDisplay display, EGLSurface surface)
{
    const EGLBoolean result = swapBuffersEgl(display, surface);
    if (result && !firstFramePresented)
    {
        firstFramePresented = GLFW_TRUE;
        puts("[PRISSMA] Primer fotograma EGL presentado.");
        fflush(stdout);
    }
    return result;
}

static EGLNativeWindowType getNativeWindow(_GLFWwindow* window)
{
    (void) window;
    return (EGLNativeWindowType) activeSurface;
}

static GLFWbool createWindow(_GLFWwindow* window, const _GLFWwndconfig* requestedWindow,
                             const _GLFWctxconfig* requestedContext, const _GLFWfbconfig* fbconfig)
{
    if (activeWindow)
    {
        _glfwInputError(GLFW_PLATFORM_ERROR, "Android: only one game window is available");
        return GLFW_FALSE;
    }

    pthread_mutex_lock(&eventLock);
    activeSurface = pendingSurface;
    if (activeSurface)
        ANativeWindow_acquire(activeSurface);
    pthread_mutex_unlock(&eventLock);
    if (!activeSurface)
    {
        _glfwInputError(GLFW_PLATFORM_ERROR, "Android: the game surface is not available");
        return GLFW_FALSE;
    }

    _GLFWwndconfig windowConfig = *requestedWindow;
    _GLFWctxconfig contextConfig = *requestedContext;
    windowConfig.width = ANativeWindow_getWidth(activeSurface);
    windowConfig.height = ANativeWindow_getHeight(activeSurface);
    windowConfig.xpos = 0;
    windowConfig.ypos = 0;
    contextConfig.source = GLFW_EGL_CONTEXT_API;

    activeWindow = window;
    if (!_glfwCreateWindowNull(window, &windowConfig, &contextConfig, fbconfig))
        return GLFW_FALSE;
    if (contextConfig.client != GLFW_NO_API && _glfw.egl.SwapBuffers != reportSwapBuffers)
    {
        firstFramePresented = GLFW_FALSE;
        swapBuffersEgl = _glfw.egl.SwapBuffers;
        _glfw.egl.SwapBuffers = reportSwapBuffers;
    }
    return GLFW_TRUE;
}

static void destroyWindow(_GLFWwindow* window)
{
    _glfwDestroyWindowNull(window);
    if (window == activeWindow)
    {
        ANativeWindow_release(activeSurface);
        activeSurface = NULL;
        activeWindow = NULL;
    }
}

static void pollEvents(void)
{
    if (!activeWindow)
        return;

    PointerEvent events[64];
    pthread_mutex_lock(&eventLock);
    const unsigned int count = pointerCount;
    for (unsigned int index = 0; index < count; ++index)
        events[index] = pointerQueue[index];
    pointerCount = 0;
    const GLFWbool surfaceChanged = pendingSurface != activeSurface;
    pthread_mutex_unlock(&eventLock);

    _glfwPollEventsNull();
    if (surfaceChanged)
    {
        _glfwInputWindowCloseRequest(activeWindow);
        return;
    }

    for (unsigned int index = 0; index < count; ++index)
    {
        _glfwInputCursorPos(activeWindow, events[index].x, events[index].y);
        if (events[index].buttonAction >= 0)
            _glfwInputMouseClick(activeWindow, GLFW_MOUSE_BUTTON_LEFT, events[index].buttonAction, 0);
    }
}

static GLFWbool getVideoMode(_GLFWmonitor* monitor, GLFWvidmode* mode)
{
    _glfwGetVideoModeNull(monitor, mode);
    pthread_mutex_lock(&eventLock);
    if (pendingSurface)
    {
        mode->width = ANativeWindow_getWidth(pendingSurface);
        mode->height = ANativeWindow_getHeight(pendingSurface);
    }
    pthread_mutex_unlock(&eventLock);
    return GLFW_TRUE;
}

static GLFWvidmode* getVideoModes(_GLFWmonitor* monitor, int* count)
{
    GLFWvidmode* modes = _glfwGetVideoModesNull(monitor, count);
    if (modes)
        getVideoMode(monitor, &modes[0]);
    return modes;
}

GLFWbool prissmaConnectNullBase(int platformID, _GLFWplatform* platform);

GLFWbool _glfwConnectNull(int platformID, _GLFWplatform* platform)
{
    if (!prissmaConnectNullBase(platformID, platform))
        return GLFW_FALSE;

    platform->getEGLNativeWindow = getNativeWindow;
    platform->createWindow = createWindow;
    platform->destroyWindow = destroyWindow;
    platform->getVideoMode = getVideoMode;
    platform->getVideoModes = getVideoModes;
    platform->pollEvents = pollEvents;
    return GLFW_TRUE;
}
