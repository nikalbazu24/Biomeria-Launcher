#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#define GLFW_INCLUDE_NONE
#include <GLFW/glfw3.h>
#include "prissma_glfw_android.h"
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/stat.h>
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

        for (const auto& name : coreLibs) {
            preloadLibraryIfExists(libDir + name);
        }
    }

    void* stdioReaderThread(void* arg) {
        int readFd = *static_cast<int*>(arg);
        delete static_cast<int*>(arg);

        char buffer[512];
        std::string pending;
        while (true) {
            ssize_t count = read(readFd, buffer, sizeof(buffer) - 1);
            if (count <= 0) break;
            buffer[count] = '\0';
            pending += buffer;

            size_t pos;
            while ((pos = pending.find('\n')) != std::string::npos) {
                std::string line = pending.substr(0, pos);
                pending.erase(0, pos + 1);
                LOGI("%s", line.c_str());
                pushLogLine(line);
            }
        }
        close(readFd);
        return nullptr;
    }

    void ensureStdioRedirected() {
        if (gStdioRedirected) return;
        int pipeFds[2];
        if (pipe(pipeFds) == 0) {
            dup2(pipeFds[1], STDOUT_FILENO);
            dup2(pipeFds[1], STDERR_FILENO);
            close(pipeFds[1]);

            int* fdArg = new int(pipeFds[0]);
            pthread_t reader;
            if (pthread_create(&reader, nullptr, stdioReaderThread, fdArg) == 0) {
                pthread_detach(reader);
                gStdioRedirected = true;
            } else {
                delete fdArg;
                close(pipeFds[0]);
            }
        }
    }

    struct JvmLaunchConfig {
        std::string libJvmPath;
        std::string javaHome;
        std::string gameDir;
        std::string cacheDir;
        std::string nativeLibraryDir;
        std::string classpath;
        std::string mainClass;
        std::vector<std::string> gameArgs;
        bool useVulkanZink;
    };

    typedef jint (*JNI_CreateJavaVM_t)(JavaVM**, void**, void*);

    void* jvmWorkerThread(void* rawArg) {
        JvmLaunchConfig* cfg = static_cast<JvmLaunchConfig*>(rawArg);
        gJvmRunning = true;

        {
            std::lock_guard<std::mutex> lock(gLogMutex);
            // stderr se lee en otro hilo y puede seguir llegando tras el retorno de main().
            if (gLogFile) {
                fclose(gLogFile);
                gLogFile = nullptr;
            }
            std::string logPath = cfg->gameDir + "/prissma_last.log";
            gLogFile = fopen(logPath.c_str(), "w");
        }

        pushLogLine("[PRISSMA] Configurando entorno ARM64 y puente gráfico...");

        setenv("JAVA_HOME", cfg->javaHome.c_str(), 1);
        std::string ldPath = cfg->javaHome + "/lib:" + cfg->javaHome + "/lib/server";
        setenv("LD_LIBRARY_PATH", ldPath.c_str(), 1);
        const std::string mobileGluesDir = cfg->cacheDir + "/mobileglues";
        mkdir(mobileGluesDir.c_str(), 0700);
        setenv("MG_DIR_PATH", mobileGluesDir.c_str(), 1);
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_NULL);
        if (cfg->useVulkanZink) pushLogLine("[PRISSMA] Preferencia: Vulkan/Zink. Esta sesión usa MobileGlues.");
        pushLogLine("[PRISSMA] Backend de esta sesión: GLFW Android + MobileGlues (OpenGL ES).");

        chdir(cfg->gameDir.c_str());

        int jarCount = cfg->classpath.empty() ? 0 : 1;
        for (char c : cfg->classpath) {
            if (c == ':') jarCount++;
        }
        pushLogLine("[PRISSMA] Classpath preparado con " + std::to_string(jarCount) + " archivos JAR.");

        pushLogLine("[PRISSMA] Cargando librería nativa: " + cfg->libJvmPath);
        void* jvmHandle = dlopen(cfg->libJvmPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (!jvmHandle) {
            const char* err = dlerror();
            std::string msg = std::string("[ERROR] No se pudo abrir libjvm.so: ") + (err ? err : "desconocido");
            LOGE("%s", msg.c_str());
            pushLogLine(msg);
            gJvmRunning = false;
            delete cfg;
            return nullptr;
        }

        pushLogLine("[PRISSMA] Precargando librerías nativas del JRE 21...");
        preloadJreCoreLibraries(cfg->javaHome);

        auto createJvmFn = reinterpret_cast<JNI_CreateJavaVM_t>(
            dlsym(jvmHandle, "JNI_CreateJavaVM")
        );
        if (!createJvmFn) {
            pushLogLine("[ERROR] Símbolo JNI_CreateJavaVM no encontrado en libjvm.so");
            gJvmRunning = false;
            delete cfg;
            return nullptr;
        }

        std::vector<std::string> optionStrings;
        optionStrings.push_back("-Djava.class.path=" + cfg->classpath);
        optionStrings.push_back("-Djava.home=" + cfg->javaHome);
        optionStrings.push_back("-Djava.library.path=" + cfg->nativeLibraryDir + ":" + ldPath);
        optionStrings.push_back("-Duser.dir=" + cfg->gameDir);
        optionStrings.push_back("-Djava.io.tmpdir=" + cfg->cacheDir);
        optionStrings.push_back("-Djna.tmpdir=" + cfg->cacheDir);
        const std::string classpath = cfg->classpath + ":";
        if (classpath.find("/jna-5.15.0.jar:") != std::string::npos) {
            optionStrings.push_back("-Djna.boot.library.path=" + cfg->nativeLibraryDir);
            optionStrings.push_back("-Djna.nosys=true");
            optionStrings.push_back("-Djna.noclasspath=true");
            pushLogLine("[PRISSMA] JNA 5.15.0 configurada con libjnidispatch de Android ARM64.");
        }
        pushLogLine("[PRISSMA] Directorio temporal de Java: " + cfg->cacheDir);
        optionStrings.push_back("-Djna.debug_load.jna=true");
        optionStrings.push_back("-Dorg.lwjgl.util.DebugLoader=true");
        if (classpath.find("/lwjgl-3.3.3.jar:") != std::string::npos) {
            optionStrings.push_back("-Dorg.lwjgl.librarypath=" + cfg->nativeLibraryDir);
            optionStrings.push_back("-Dorg.lwjgl.system.allocator=system");
            pushLogLine("[PRISSMA] LWJGL 3.3.3 buscará nativos Android en " + cfg->nativeLibraryDir);
        }
        optionStrings.push_back("-Dorg.lwjgl.glfw.libname=" + cfg->nativeLibraryDir + "/libglfw.so");
        optionStrings.push_back("-Dorg.lwjgl.openal.libname=" + cfg->nativeLibraryDir + "/libopenal.so");
        optionStrings.push_back("-Dorg.lwjgl.opengl.libname=" + cfg->nativeLibraryDir + "/libmobileglues.so");
        optionStrings.push_back("-Xmx1536M");
        optionStrings.push_back("-Xms512M");

        std::vector<JavaVMOption> options(optionStrings.size());
        for (size_t i = 0; i < optionStrings.size(); ++i) {
            options[i].optionString = const_cast<char*>(optionStrings[i].c_str());
            options[i].extraInfo = nullptr;
        }

        JavaVMInitArgs vmArgs;
        vmArgs.version = JNI_VERSION_1_6;
        vmArgs.nOptions = static_cast<jint>(options.size());
        vmArgs.options = options.data();
        vmArgs.ignoreUnrecognized = JNI_TRUE;

        JavaVM* jvm = nullptr;
        JNIEnv* jvmEnv = nullptr;
        pushLogLine("[PRISSMA] Encendiendo Máquina Virtual Java 21...");
        jint status = createJvmFn(&jvm, reinterpret_cast<void**>(&jvmEnv), &vmArgs);
        if (status != JNI_OK || !jvmEnv) {
            pushLogLine("[ERROR] JNI_CreateJavaVM falló con código: " + std::to_string(status));
            gJvmRunning = false;
            delete cfg;
            return nullptr;
        }

        pushLogLine("[PRISSMA] ¡JVM encendida! Buscando clase principal: " + cfg->mainClass);

        std::string slashClass = cfg->mainClass;
        for (char& c : slashClass) {
            if (c == '.') c = '/';
        }

        jclass mainCls = jvmEnv->FindClass(slashClass.c_str());
        if (!mainCls) {
            pushLogLine("[ERROR] No se pudo cargar " + cfg->mainClass);
            if (jvmEnv->ExceptionCheck()) jvmEnv->ExceptionDescribe();
            gJvmRunning = false;
            delete cfg;
            return nullptr;
        }

        jmethodID mainMethod = jvmEnv->GetStaticMethodID(mainCls, "main", "([Ljava/lang/String;)V");
        if (!mainMethod) {
            pushLogLine("[ERROR] No se pudo resolver main(String[])");
            if (jvmEnv->ExceptionCheck()) jvmEnv->ExceptionDescribe();
            gJvmRunning = false;
            delete cfg;
            return nullptr;
        }

        jclass stringCls = jvmEnv->FindClass("java/lang/String");
        jobjectArray jArgs = jvmEnv->NewObjectArray(
            static_cast<jsize>(cfg->gameArgs.size()),
            stringCls,
            nullptr
        );
        for (size_t i = 0; i < cfg->gameArgs.size(); ++i) {
            jstring jStr = jvmEnv->NewStringUTF(cfg->gameArgs[i].c_str());
            jvmEnv->SetObjectArrayElement(jArgs, static_cast<jsize>(i), jStr);
            jvmEnv->DeleteLocalRef(jStr);
        }

        pushLogLine("[PRISSMA] Lanzando Minecraft Java Edition...");
        jvmEnv->CallStaticVoidMethod(mainCls, mainMethod, jArgs);

        if (jvmEnv->ExceptionCheck()) {
            pushLogLine("[ERROR] Excepción en Minecraft:");
            jvmEnv->ExceptionDescribe();
        } else {
            pushLogLine("[PRISSMA] Sesión Java finalizada correctamente.");
        }

        gJvmRunning = false;
        delete cfg;
        return nullptr;
    }
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_prissma_launcher_engine_java_NativeJavaBridge_nativeInitGraphicsSurface(
    JNIEnv* env,
    jobject /* thiz */,
    jobject surface,
    jboolean previewOnly
) {
    if (gWindow) {
        ANativeWindow_release(gWindow);
        gWindow = nullptr;
    }

    gWindow = ANativeWindow_fromSurface(env, surface);
    if (!gWindow) {
        return env->NewStringUTF("Error: No se pudo obtener ANativeWindow de la pantalla.");
    }

    int width = ANativeWindow_getWidth(gWindow);
    int height = ANativeWindow_getHeight(gWindow);

    if (!previewOnly) {
        prissmaGlfwSetSurface(gWindow);
        const std::string status = "Superficie Android · " + std::to_string(width) + "x" + std::to_string(height);
        return env->NewStringUTF(status.c_str());
    }

    gDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (gDisplay == EGL_NO_DISPLAY || !eglInitialize(gDisplay, nullptr, nullptr)) {
        return env->NewStringUTF("Error: Falló eglInitialize en la GPU.");
    }

    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs = 0;
    if (!eglChooseConfig(gDisplay, attribs, &config, 1, &numConfigs) || numConfigs <= 0) {
        return env->NewStringUTF("Error: No se encontró configuración EGL compatible.");
    }

    gSurface = eglCreateWindowSurface(gDisplay, config, gWindow, nullptr);
    const EGLint ctxAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    gContext = eglCreateContext(gDisplay, config, EGL_NO_CONTEXT, ctxAttribs);

    if (gSurface == EGL_NO_SURFACE || gContext == EGL_NO_CONTEXT) {
        return env->NewStringUTF("Error: No se pudo crear el contexto gráfico EGL.");
    }

    if (!eglMakeCurrent(gDisplay, gSurface, gSurface, gContext)) {
        const std::string error = "Error: eglMakeCurrent devolvió " + std::to_string(eglGetError());
        return env->NewStringUTF(error.c_str());
    }

    glViewport(0, 0, width, height);
    glClearColor(0.02f, 0.03f, 0.05f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(gDisplay, gSurface);

    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* glVersion = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    std::ostringstream info;
    info << "EGL · " << (renderer ? renderer : "GPU ARM64")
         << " (" << width << "x" << height << ") · "
         << (glVersion ? glVersion : "GLES 3");

    pushLogLine("[GPU] Lienzo nativo listo: " + info.str());
    return env->NewStringUTF(info.str().c_str());
}

JNIEXPORT void JNICALL
Java_com_prissma_launcher_engine_java_NativeJavaBridge_nativeDestroyGraphicsSurface(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    prissmaGlfwSetSurface(nullptr);
    if (gDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(gDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (gContext != EGL_NO_CONTEXT) {
            eglDestroyContext(gDisplay, gContext);
            gContext = EGL_NO_CONTEXT;
        }
        if (gSurface != EGL_NO_SURFACE) {
            eglDestroySurface(gDisplay, gSurface);
            gSurface = EGL_NO_SURFACE;
        }
        eglTerminate(gDisplay);
        gDisplay = EGL_NO_DISPLAY;
    }
    if (gWindow) {
        ANativeWindow_release(gWindow);
        gWindow = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_prissma_launcher_engine_java_NativeJavaBridge_nativeInput(
    JNIEnv*, jobject, jint kind, jint code, jint action, jdouble x, jdouble y
) {
    prissmaGlfwInput(kind, code, action, x, y);
}

JNIEXPORT void JNICALL
Java_com_prissma_launcher_engine_java_NativeJavaBridge_nativeSetViewportSize(
    JNIEnv*, jobject, jint width, jint height
) {
    prissmaGlfwSetSize(width, height);
}

JNIEXPORT jboolean JNICALL
Java_com_prissma_launcher_engine_java_NativeJavaBridge_nativeCursorCaptured(JNIEnv*, jobject) {
    return prissmaGlfwCursorCaptured() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_prissma_launcher_engine_java_NativeJavaBridge_nativeHasPresentedFrame(
    JNIEnv* /* env */, jobject /* thiz */
) {
    return prissmaGlfwHasPresentedFrame() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_prissma_launcher_engine_java_NativeJavaBridge_nativeLaunchJvm(
    JNIEnv* env,
    jobject /* thiz */,
    jstring libJvmPath,
    jstring javaHomePath,
    jstring gameDirPath,
    jstring cacheDirPath,
    jstring nativeLibraryDir,
    jstring classpath,
    jstring mainClass,
    jobjectArray gameArgs,
    jboolean useVulkanZink
) {
    if (gJvmRunning) {
        return 1;
    }

    ensureStdioRedirected();

    auto toStdString = [&](jstring jStr) -> std::string {
        if (!jStr) return "";
        const char* chars = env->GetStringUTFChars(jStr, nullptr);
        std::string res(chars ? chars : "");
        if (chars) env->ReleaseStringUTFChars(jStr, chars);
        return res;
    };

    auto* cfg = new JvmLaunchConfig();
    cfg->libJvmPath = toStdString(libJvmPath);
    cfg->javaHome = toStdString(javaHomePath);
    cfg->gameDir = toStdString(gameDirPath);
    cfg->cacheDir = toStdString(cacheDirPath);
    cfg->nativeLibraryDir = toStdString(nativeLibraryDir);
    cfg->classpath = toStdString(classpath);
    cfg->mainClass = toStdString(mainClass);
    cfg->useVulkanZink = (useVulkanZink == JNI_TRUE);

    jsize argCount = env->GetArrayLength(gameArgs);
    for (jsize i = 0; i < argCount; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(gameArgs, i));
        cfg->gameArgs.push_back(toStdString(item));
        env->DeleteLocalRef(item);
    }

    pthread_t worker;
    if (pthread_create(&worker, nullptr, jvmWorkerThread, cfg) != 0) {
        delete cfg;
        return -1;
    }
    pthread_detach(worker);
    return 0;
}

JNIEXPORT jobjectArray JNICALL
Java_com_prissma_launcher_engine_java_NativeJavaBridge_nativePollLogs(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::vector<std::string> batch;
    {
        std::lock_guard<std::mutex> lock(gLogMutex);
        while (!gLogQueue.empty() && batch.size() < 30) {
            batch.push_back(std::move(gLogQueue.front()));
            gLogQueue.pop_front();
        }
    }

    jclass stringCls = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(batch.size()),
        stringCls,
        nullptr
    );
    for (size_t i = 0; i < batch.size(); ++i) {
        jstring line = env->NewStringUTF(batch[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), line);
        env->DeleteLocalRef(line);
    }
    return result;
}

} // extern "C"
