#define GLFW_INCLUDE_NONE
#include <GLFW/glfw3.h>
#include "prissma_glfw_android.h"
#include <android/native_window.h>
#include <assert.h>

static int framebufferChanges;
static int windowChanges;
static int lastWidth;
static int lastHeight;
static int cursorChanges;
static int keyChanges;
static int buttonChanges;
static int characterChanges;

static void pointerMoved(GLFWwindow* window, double x, double y)
{
    (void) window;
    ++cursorChanges;
}

static void keyChanged(GLFWwindow* window, int key, int scancode, int action, int mods)
{
    assert(key == GLFW_KEY_W);
    ++keyChanges;
}

static void mouseChanged(GLFWwindow* window, int button, int action, int mods)
{
    assert(button == GLFW_MOUSE_BUTTON_LEFT);
    ++buttonChanges;
}

static void typed(GLFWwindow* window, unsigned int codePoint)
{
    assert(codePoint == 'w');
    ++characterChanges;
}

void mockFailNextSwap(void);

static void framebufferChanged(GLFWwindow* window, int width, int height)
{
    (void) window;
    ++framebufferChanges;
    lastWidth = width;
    lastHeight = height;
}

static void windowChanged(GLFWwindow* window, int width, int height)
{
    (void) window;
    ++windowChanges;
    assert(width == lastWidth && height == lastHeight);
}

static void assertSize(GLFWwindow* window, int width, int height)
{
    int actualWidth, actualHeight;
    glfwGetWindowSize(window, &actualWidth, &actualHeight);
    assert(actualWidth == width && actualHeight == height);
    glfwGetFramebufferSize(window, &actualWidth, &actualHeight);
    assert(actualWidth == width && actualHeight == height);
}

int main(void)
{
    ANativeWindow surface = {1920, 1080, 0};
    glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_NULL);
    assert(glfwInit());
    prissmaGlfwSetSurface(&surface);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    GLFWwindow* window = glfwCreateWindow(854, 480, "PRISSMA", NULL, NULL);
    assert(window);
    assertSize(window, 1920, 1080);

    glfwSetFramebufferSizeCallback(window, framebufferChanged);
    glfwSetWindowSizeCallback(window, windowChanged);
    glfwSetWindowSize(window, 854, 480);
    glfwSetWindowMonitor(window, NULL, 120, 60, 854, 480, GLFW_DONT_CARE);
    assertSize(window, 1920, 1080);
    assert(framebufferChanges == 0 && windowChanges == 0);
    int x, y;
    glfwGetWindowPos(window, &x, &y);
    assert(x == 0 && y == 0);

    // Android provides its new buffer extent before ANativeWindow reports it.
    prissmaGlfwSetSize(1600, 900);
    glfwPollEvents();
    assertSize(window, 1600, 900);
    assert(framebufferChanges == 1 && windowChanges == 1);
    assert(lastWidth == 1600 && lastHeight == 900);
    glfwSetWindowMonitor(window, glfwGetPrimaryMonitor(), 0, 0, 854, 480, 60);
    assertSize(window, 1600, 900);
    assert(framebufferChanges == 1 && windowChanges == 1);

    glfwSetCursorPosCallback(window, pointerMoved);
    glfwSetKeyCallback(window, keyChanged);
    glfwSetMouseButtonCallback(window, mouseChanged);
    glfwSetCharCallback(window, typed);
    prissmaGlfwInput(0, 0, 0, 400.0, 225.0);
    prissmaGlfwInput(2, GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 0.0, 0.0);
    prissmaGlfwInput(3, GLFW_KEY_W, GLFW_PRESS, 'w', 0.0);
    glfwPollEvents();
    double cursorX, cursorY;
    glfwGetCursorPos(window, &cursorX, &cursorY);
    assert(cursorX == 400.0 && cursorY == 225.0 && cursorChanges == 1);
    assert(keyChanges == 1 && buttonChanges == 1 && characterChanges == 1);
    assert(glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS);
    assert(glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS);
    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
    assert(prissmaGlfwCursorCaptured());
    prissmaGlfwInput(1, 0, 0, 2.0, 1.0);
    prissmaGlfwInput(1, 0, 0, 3.0, 2.0);
    prissmaGlfwInput(5, 0, 0, 0.0, 0.0);
    glfwPollEvents();
    glfwGetCursorPos(window, &cursorX, &cursorY);
    assert(cursorX == 405.0 && cursorY == 228.0 && cursorChanges == 2);
    assert(keyChanges == 2 && buttonChanges == 2);
    assert(glfwGetKey(window, GLFW_KEY_W) == GLFW_RELEASE);
    assert(glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_RELEASE);
    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
    assert(!prissmaGlfwCursorCaptured());

    glfwMakeContextCurrent(window);
    assert(!prissmaGlfwHasPresentedFrame());
    mockFailNextSwap();
    glfwSwapBuffers(window);
    assert(!prissmaGlfwHasPresentedFrame());
    glfwSwapBuffers(window);
    assert(prissmaGlfwHasPresentedFrame());

    prissmaGlfwSetSurface(NULL);
    glfwPollEvents();
    assert(glfwWindowShouldClose(window));
    glfwDestroyWindow(window);
    glfwTerminate();
    assert(surface.references == 0);
}
