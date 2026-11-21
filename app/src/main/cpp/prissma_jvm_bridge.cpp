#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <deque>
#include <mutex>
#include <sstream>

#define LOG_TAG "PRISSMA-JVM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    ANativeWindow* gWindow = nullptr;
    EGLDisplay gDisplay = EGL_NO_DISPLAY;
    EGLSurface gSurface = EGL_NO_SURFACE;
    EGLContext gContext = EGL_NO_CONTEXT;

    std::mutex gLogMutex;
    std::deque<std::string> gLogQueue;
    bool gStdioRedirected = false;
    bool gJvmRunning = false;
    FILE* gLogFile = nullptr;

    void pushLogLine(const std::string& line) {
        if (line.empty()) return;
        std::lock_guard<std::mutex> lock(gLogMutex);
        if (gLogFile) {
            fprintf(gLogFile, "%s\n", line.c_str());
            fflush(gLogFile);
        }
        if (gLogQueue.size() > 250) {
            gLogQueue.pop_front();
        }
        gLogQueue.push_back(line);
    }

    void closeLogFile() {
        std::lock_guard<std::mutex> lock(gLogMutex);
        if (gLogFile) {
            fflush(gLogFile);
            fclose(gLogFile);
            gLogFile = nullptr;
        }
    }

    std::string describeJavaException(JNIEnv* env) {
        if (!env || !env->ExceptionCheck()) return "";
        jthrowable ex = env->ExceptionOccurred();
        env->ExceptionClear();
        if (!ex) return "Excepción desconocida";

        jclass objCls = env->FindClass("java/lang/Object");
        jmethodID toStringMethod = objCls ? env->GetMethodID(objCls, "toString", "()Ljava/lang/String;") : nullptr;
        std::string result = "Excepción Java";

        if (toStringMethod) {
            auto jStr = static_cast<jstring>(env->CallObjectMethod(ex, toStringMethod));
            if (jStr) {
                const char* chars = env->GetStringUTFChars(jStr, nullptr);
                if (chars) {
                    result = chars;
                    env->ReleaseStringUTFChars(jStr, chars);
                }
                env->DeleteLocalRef(jStr);
            }
        }
        if (objCls) env->DeleteLocalRef(objCls);
        env->DeleteLocalRef(ex);
        return result;
    }

    void preloadLibraryIfExists(const std::string& fullPath) {
        if (access(fullPath.c_str(), F_OK) != 0) return;
        void* handle = dlopen(fullPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (!handle) {
            const char* err = dlerror();
            LOGE("Aviso precargando %s: %s", fullPath.c_str(), err ? err : "desconocido");
        }
    }

    void preloadJreCoreLibraries(const std::string& javaHome) {
        const std::string libDir = javaHome + "/lib/";
        const std::vector<std::string> coreLibs = {
            "libjli.so",
            "jli/libjli.so",
            "libverify.so",
            "libjava.so",
            "libjimage.so",
            "libzip.so",
            "libnet.so",
            "libnio.so",
            "libmanagement.so",
            "libmanagement_ext.so",
            "libextnet.so",
            "libprefs.so",
            "libawt.so",
            "libawt_headless.so"
        };

        for (const auto& name : coreLibs)
