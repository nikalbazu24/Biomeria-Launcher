#include "internal.h"
#include "prissma_glfw_android.h"

#include <android/native_window.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>

enum InputKind { Pointer, RelativePointer, MouseButton, Key, Scroll, ResetInput };

typedef struct {
    int kind;
    int code;
    int action;
    double x;
    double y;
} InputEvent;

static pthread_mutex_t eventLock = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow* pendingSurface;
static int pendingWidth;
static int pendingHeight;
static ANativeWindow* activeSurface;
static _GLFWwindow* activeWindow;
static InputEvent inputQueue[256];
static unsigned int inputCount;
static PFN_eglSwapBuffers swapBuffersEgl;
static atomic_int firstFramePresented;
static atomic_uint presentedFrames;
static atomic_int cursorCaptured;
static _GLFWwindow* focusedWindow;

void prissmaGlfwSetSurface(ANativeWindow* surface)
{
    pthread_mutex_lock(&eventLock);
    if (surface)
        ANativeWindow_acquire(surface);
    if (pendingSurface)
        ANativeWindow_release(pendingSurface);
    pendingSurface = surface;
    pendingWidth = 0;
    pendingHeight = 0;
    inputCount = 0;
    pthread_mutex_unlock(&eventLock);
}

void prissmaGlfwSetSize(int width, int height)
{
    pthread_mutex_lock(&eventLock);
    pendingWidth = width;
    pendingHeight = height;
    pthread_mutex_unlock(&eventLock);
}

void prissmaGlfwInput(int kind, int code, int action, double x, double y)
{
    pthread_mutex_lock(&eventLock);
    if (inputCount && (kind == Pointer || kind == RelativePointer) &&
        inputQueue[inputCount - 1].kind == kind)
    {
        InputEvent* previous = &inputQueue[inputCount - 1];
        previous->x = kind == Pointer ? x : previous->x + x;
        previous->y = kind == Pointer ? y : previous->y + y;
    }
    else
    {
        if (inputCount == sizeof(inputQueue) / sizeof(inputQueue[0]))
        {
            // Reset keys and buttons if a stalled render thread overflows the queue.
            inputQueue[0] = (InputEvent){ResetInput, 0, 0, 0, 0};
            inputCount = 1;
        }
        inputQueue[inputCount++] = (InputEvent){kind, code, action, x, y};
    }
    pthread_mutex_unlock(&eventLock);
}

int prissmaGlfwCursorCaptured(void)
{
    return atomic_load_explicit(&cursorCaptured, memory_order_relaxed);
}

static EGLBoolean APIENTRY reportSwapBuffers(EGLDisplay display, EGLSurface surface)
{
    const EGLBoolean result = swapBuffersEgl(display, surface);
    if (result)
        atomic_fetch_add_explicit(&presentedFrames, 1, memory_order_relaxed);
    if (result && !atomic_exchange_explicit(&firstFramePresented, GLFW_TRUE, memory_order_relaxed))
    {
        int width = 0, height = 0;
        glfwGetFramebufferSize((GLFWwindow*) activeWindow, &width, &height);
        printf("[PRISSMA] Primer fotograma EGL presentado · framebuffer %dx%d.\n", width, height);
        fflush(stdout);
    }
    return result;
}

int prissmaGlfwHasPresentedFrame(void)
{
    return atomic_load_explicit(&firstFramePresented, memory_order_relaxed);
}

unsigned int prissmaGlfwPresentedFrames(void)
{
    return atomic_load_explicit(&presentedFrames, memory_order_relaxed);
}

static void readSurfaceExtent(ANativeWindow* surface, int* width, int* height)
{
    if (!surface)
        return;

    const int surfaceWidth = ANativeWindow_getWidth(surface);
    const int surfaceHeight = ANativeWindow_getHeight(surface);
    if (surfaceWidth > 0 && surfaceHeight > 0)
    {
        *width = surfaceWidth;
        *height = surfaceHeight;
    }
}

static void getDrawableSize(_GLFWwindow* window, int* width, int* height)
{
    int drawableWidth = window->null.width;
    int drawableHeight = window->null.height;
    if (window == activeWindow)
    {
        pthread_mutex_lock(&eventLock);
        if (activeSurface == pendingSurface && pendingWidth > 0 && pendingHeight > 0)
        {
            drawableWidth = pendingWidth;
            drawableHeight = pendingHeight;
        }
        else
            readSurfaceExtent(activeSurface, &drawableWidth, &drawableHeight);
        pthread_mutex_unlock(&eventLock);
    }
    if (width)
        *width = drawableWidth;
    if (height)
        *height = drawableHeight;
}

static void refreshDrawableSize(_GLFWwindow* window)
{
    int width, height;
    getDrawableSize(window, &width, &height);
    if (width == window->null.width && height == window->null.height)
        return;

    window->null.width = width;
    window->null.height = height;
    _glfwInputFramebufferSize(window, width, height);
    _glfwInputWindowDamage(window);
    _glfwInputWindowSize(window, width, height);
}

// Android owns the size and origin of the surface. Desktop hints cannot resize it.
static void setWindowSize(_GLFWwindow* window, int width, int height)
{
    (void) width;
    (void) height;
    refreshDrawableSize(window);
}

static void setWindowLimits(_GLFWwindow* window, int minWidth, int minHeight, int maxWidth, int maxHeight)
{
    (void) minWidth;
    (void) minHeight;
    (void) maxWidth;
    (void) maxHeight;
    refreshDrawableSize(window);
}

static void setWindowPos(_GLFWwindow* window, int x, int y)
{
    (void) x;
    (void) y;
    _glfwSetWindowPosNull(window, 0, 0);
}

static void setWindowMonitor(_GLFWwindow* window, _GLFWmonitor* monitor,
                             int x, int y, int width, int height, int refreshRate)
{
    (void) x;
    (void) y;
    const int oldWidth = window->null.width;
    const int oldHeight = window->null.height;
    getDrawableSize(window, &width, &height);
    _glfwSetWindowMonitorNull(window, monitor, 0, 0, width, height, refreshRate);
    if (monitor)
    {
        // The null platform silently assigns its synthetic monitor size here.
        window->null.width = oldWidth;
        window->null.height = oldHeight;
    }
    refreshDrawableSize(window);
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
    const int width = pendingWidth;
    const int height = pendingHeight;
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
    readSurfaceExtent(activeSurface, &windowConfig.width, &windowConfig.height);
    if (width > 0 && height > 0)
    {
        windowConfig.width = width;
        windowConfig.height = height;
    }
    windowConfig.xpos = 0;
    windowConfig.ypos = 0;
    contextConfig.source = GLFW_EGL_CONTEXT_API;

    activeWindow = window;
    atomic_store_explicit(&firstFramePresented, GLFW_FALSE, memory_order_relaxed);
    atomic_store_explicit(&presentedFrames, 0, memory_order_relaxed);
    if (!_glfwCreateWindowNull(window, &windowConfig, &contextConfig, fbconfig))
        return GLFW_FALSE;
    refreshDrawableSize(window);
    if (contextConfig.client != GLFW_NO_API && _glfw.egl.SwapBuffers != reportSwapBuffers)
    {
        swapBuffersEgl = _glfw.egl.SwapBuffers;
        _glfw.egl.SwapBuffers = reportSwapBuffers;
    }
    return GLFW_TRUE;
}

static void destroyWindow(_GLFWwindow* window)
{
    _glfwDestroyWindowNull(window);
    if (window == focusedWindow)
        focusedWindow = NULL;
    if (window == activeWindow)
    {
        ANativeWindow_release(activeSurface);
        activeSurface = NULL;
        activeWindow = NULL;
        atomic_store_explicit(&cursorCaptured, GLFW_FALSE, memory_order_relaxed);
    }
}

static void setCursorMode(_GLFWwindow* window, int mode)
{
    _glfwSetCursorModeNull(window, mode);
    atomic_store_explicit(&cursorCaptured, mode == GLFW_CURSOR_DISABLED, memory_order_relaxed);
}

static void releaseInputs(_GLFWwindow* window)
{
    for (int key = GLFW_KEY_SPACE; key <= GLFW_KEY_LAST; ++key)
    {
        if (window->keys[key] == GLFW_PRESS)
            _glfwInputKey(window, key, key, GLFW_RELEASE, 0);
    }
    for (int button = 0; button <= GLFW_MOUSE_BUTTON_LAST; ++button)
    {
        if (window->mouseButtons[button] == GLFW_PRESS)
            _glfwInputMouseClick(window, button, GLFW_RELEASE, 0);
    }
}

static int modifiers(_GLFWwindow* window)
{
    return (window->keys[GLFW_KEY_LEFT_SHIFT] == GLFW_PRESS ? GLFW_MOD_SHIFT : 0) |
           (window->keys[GLFW_KEY_LEFT_CONTROL] == GLFW_PRESS ? GLFW_MOD_CONTROL : 0) |
           (window->keys[GLFW_KEY_LEFT_ALT] == GLFW_PRESS ? GLFW_MOD_ALT : 0);
}

static void dispatchInput(_GLFWwindow* window, const InputEvent* event)
{
    switch (event->kind)
    {
        case Pointer:
            _glfwSetCursorPosNull(window, event->x, event->y);
            _glfwInputCursorPos(window, event->x, event->y);
            break;
        case RelativePointer:
            _glfwInputCursorPos(window, window->virtualCursorPosX + event->x, window->virtualCursorPosY + event->y);
            break;
        case MouseButton:
            _glfwInputMouseClick(window, event->code, event->action, modifiers(window));
            break;
        case Key:
            _glfwInputKey(window, event->code, event->code, event->action, modifiers(window));
            if (event->action == GLFW_PRESS && event->x >= 32 && !(modifiers(window) & GLFW_MOD_CONTROL))
                _glfwInputChar(window, (unsigned int) event->x, modifiers(window), GLFW_TRUE);
            break;
        case Scroll:
            _glfwInputScroll(window, event->x, event->y);
            break;
        case ResetInput:
            releaseInputs(window);
            break;
    }
}

static void pollEvents(void)
{
    if (!activeWindow)
        return;

    InputEvent events[256];
    pthread_mutex_lock(&eventLock);
    const unsigned int count = inputCount;
    memcpy(events, inputQueue, count * sizeof(InputEvent));
    inputCount = 0;
    const GLFWbool surfaceChanged = pendingSurface != activeSurface;
    pthread_mutex_unlock(&eventLock);

    _glfwPollEventsNull();
    if (surfaceChanged)
    {
        _glfwInputWindowCloseRequest(activeWindow);
        return;
    }

    refreshDrawableSize(activeWindow);
    if (focusedWindow != activeWindow)
    {
        focusedWindow = activeWindow;
        _glfwFocusWindowNull(activeWindow);
    }
    for (unsigned int index = 0; index < count; ++index)
        dispatchInput(activeWindow, &events[index]);
}

static GLFWbool getVideoMode(_GLFWmonitor* monitor, GLFWvidmode* mode)
{
    _glfwGetVideoModeNull(monitor, mode);
    pthread_mutex_lock(&eventLock);
    readSurfaceExtent(pendingSurface, &mode->width, &mode->height);
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
    platform->getWindowSize = getDrawableSize;
    platform->getFramebufferSize = getDrawableSize;
    platform->setWindowSize = setWindowSize;
    platform->setWindowSizeLimits = setWindowLimits;
    platform->setWindowAspectRatio = setWindowSize;
    platform->setWindowPos = setWindowPos;
    platform->setWindowMonitor = setWindowMonitor;
    platform->getVideoMode = getVideoMode;
    platform->getVideoModes = getVideoModes;
    platform->pollEvents = pollEvents;
    platform->setCursorMode = setCursorMode;
    return GLFW_TRUE;
}
