#define GLFW_INCLUDE_NONE
#include <GLFW/glfw3.h>
#include "prissma_glfw_android.h"
#include <android/native_window.h>
#include <assert.h>

static int framebufferChanges;
static int windowChanges;
static int lastWidth;
static int lastHeight;

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

    surface.width = 1600;
    surface.height = 900;
    glfwPollEvents();
    assertSize(window, 1600, 900);
    assert(framebufferChanges == 1 && windowChanges == 1);
    assert(lastWidth == 1600 && lastHeight == 900);
    glfwSetWindowMonitor(window, glfwGetPrimaryMonitor(), 0, 0, 854, 480, 60);
    assertSize(window, 1600, 900);
    assert(framebufferChanges == 1 && windowChanges == 1);

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
