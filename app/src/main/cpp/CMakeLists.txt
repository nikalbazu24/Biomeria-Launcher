cmake_minimum_required(VERSION 3.22.1)
project("prissma_jvm_bridge")

add_library(
    prissma_jvm_bridge
    SHARED
    prissma_jvm_bridge.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv3-lib GLESv3)

target_link_libraries(
    prissma_jvm_bridge
    ${android-lib}
    ${log-lib}
    ${egl-lib}
    ${glesv3-lib}
    dl
)

include(FetchContent)
set(GLFW_LIBRARY_TYPE SHARED CACHE STRING "" FORCE)
set(GLFW_BUILD_X11 OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_WAYLAND OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GLFW_BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(GLFW_INSTALL OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    prissma_glfw
    URL https://codeload.github.com/glfw/glfw/tar.gz/7b6aead9fb88b3623e3b3725ebb42670cbe4c579
    URL_HASH SHA256=169d49096e339caabbc12e1762273c5cb9130a535a282ba46cf5c14ab6e55694
)
FetchContent_MakeAvailable(prissma_glfw)
set_target_properties(glfw PROPERTIES OUTPUT_NAME glfw VERSION "" SOVERSION "")
set_property(SOURCE "${prissma_glfw_SOURCE_DIR}/src/null_init.c" TARGET_DIRECTORY glfw
             APPEND PROPERTY COMPILE_DEFINITIONS _glfwConnectNull=prissmaConnectNullBase)
target_sources(glfw PRIVATE prissma_glfw_android.c)
target_include_directories(glfw PRIVATE "${prissma_glfw_SOURCE_DIR}/src")
target_compile_definitions(glfw PRIVATE
    _GLFW_EGL_LIBRARY="libmobileglues.so"
    _GLFW_OPENGL_LIBRARY="libmobileglues.so"
)
target_link_libraries(glfw PRIVATE ${android-lib})
target_link_libraries(prissma_jvm_bridge glfw)

set(ALSOFT_UTILS OFF CACHE BOOL "" FORCE)
set(ALSOFT_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_TESTS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL OFF CACHE BOOL "" FORCE)
set(ALSOFT_UPDATE_BUILD_VERSION OFF CACHE BOOL "" FORCE)
set(ALSOFT_REQUIRE_OPENSL ON CACHE BOOL "" FORCE)
set(LIBTYPE SHARED CACHE STRING "" FORCE)
FetchContent_Declare(
    openal_soft
    URL https://codeload.github.com/kcat/openal-soft/tar.gz/dc7d7054a5b4f3bec1dc23a42fd616a0847af948
    URL_HASH SHA256=dd733d74c33b106b90f83a0e0e9917b39aa401162ff8da23d7948615dcb92ef7
)
FetchContent_MakeAvailable(openal_soft)
set_target_properties(OpenAL PROPERTIES VERSION "" SOVERSION "")
