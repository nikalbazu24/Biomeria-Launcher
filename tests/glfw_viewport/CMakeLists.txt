cmake_minimum_required(VERSION 3.22)
project(BiomeriaGlfwViewportTest LANGUAGES C CXX)

get_filename_component(bridge_dir "../../app/src/main/cpp" ABSOLUTE)
include(${bridge_dir}/BiomeriaGlfw.cmake)
target_include_directories(glfw PRIVATE stubs)

add_library(mock_egl SHARED fake_egl.c)
set_target_properties(mock_egl PROPERTIES OUTPUT_NAME mobileglues)
add_executable(glfw_viewport_test viewport_test.c)
target_include_directories(glfw_viewport_test PRIVATE stubs "${bridge_dir}")
target_link_libraries(glfw_viewport_test PRIVATE glfw mock_egl)

enable_testing()
add_test(NAME glfw_viewport_test COMMAND glfw_viewport_test)
set_tests_properties(glfw_viewport_test PROPERTIES ENVIRONMENT "LD_LIBRARY_PATH=$<TARGET_FILE_DIR:mock_egl>")

add_executable(java_log_redaction_test java_log_redaction_test.cpp)
target_include_directories(java_log_redaction_test PRIVATE "${bridge_dir}")
add_test(NAME java_log_redaction_test COMMAND java_log_redaction_test)
