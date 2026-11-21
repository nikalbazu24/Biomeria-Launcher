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
