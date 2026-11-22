cmake_minimum_required(VERSION 3.22.1)
project("prissma_jvm_bridge")

add_library(
    prissma_jvm_bridge
    SHARED
    prissma_jvm_bridge.cpp
    prissma_audio.cpp
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

include(PrissmaGlfw.cmake)
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

target_link_libraries(prissma_jvm_bridge OpenAL)
