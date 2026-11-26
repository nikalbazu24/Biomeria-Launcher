include(FetchContent)
set(GLFW_LIBRARY_TYPE SHARED CACHE STRING "" FORCE)
set(GLFW_BUILD_X11 OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_WAYLAND OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(GLFW_INSTALL OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    biomeria_glfw
    URL https://codeload.github.com/glfw/glfw/tar.gz/7b6aead9fb88b3623e3b3725ebb42670cbe4c579
    URL_HASH SHA256=169d49096e339caabbc12e1762273c5cb9130a535a282ba46cf5c14ab6e55694
)
FetchContent_MakeAvailable(biomeria_glfw)
set_target_properties(glfw PROPERTIES OUTPUT_NAME glfw VERSION "" SOVERSION "")
set_property(SOURCE "${biomeria_glfw_SOURCE_DIR}/src/null_init.c" TARGET_DIRECTORY glfw
             APPEND PROPERTY COMPILE_DEFINITIONS _glfwConnectNull=biomeriaConnectNullBase)
target_sources(glfw PRIVATE "${CMAKE_CURRENT_LIST_DIR}/biomeria_glfw_android.c")
target_include_directories(glfw PRIVATE "${biomeria_glfw_SOURCE_DIR}/src")
# The renderer library is picked per game (MobileGlues or LTW), so GLFW asks for it at run time.
target_compile_definitions(glfw PRIVATE
    "_GLFW_EGL_LIBRARY=biomeria_renderer_library()"
    "_GLFW_OPENGL_LIBRARY=biomeria_renderer_library()"
)
target_compile_options(glfw PRIVATE "SHELL:-include ${CMAKE_CURRENT_LIST_DIR}/biomeria_renderer.h")
