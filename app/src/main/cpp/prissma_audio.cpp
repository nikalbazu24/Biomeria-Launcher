#include <jni.h>
#include <AL/al.h>
#include <AL/alc.h>

#include <chrono>
#include <cmath>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

extern "C" JNIEXPORT jstring JNICALL
Java_com_prissma_launcher_engine_java_NativeJavaBridge_nativeTestAudio(JNIEnv* env, jobject) {
    static std::mutex audioTest;
    const std::unique_lock<std::mutex> lock(audioTest, std::try_to_lock);
    if (!lock.owns_lock()) return env->NewStringUTF("Ya hay una prueba de audio en curso.");

    const std::unique_ptr<ALCdevice, decltype(&alcCloseDevice)> device(alcOpenDevice(nullptr), alcCloseDevice);
    if (!device) return env->NewStringUTF("OpenAL no pudo abrir la salida de audio.");

    const std::unique_ptr<ALCcontext, decltype(&alcDestroyContext)> context(
        alcCreateContext(device.get(), nullptr), alcDestroyContext);
    if (!context || !alcMakeContextCurrent(context.get())) {
        return env->NewStringUTF("OpenAL no pudo iniciar el contexto de audio.");
    }

    constexpr int sampleRate = 22050;
    constexpr int durationMs = 260;
    constexpr double pi = 3.141592653589793;
    std::vector<ALshort> samples(sampleRate * durationMs / 1000);
    for (size_t sample = 0; sample < samples.size(); ++sample) {
        const double envelope = std::sin(pi * sample / samples.size());
        samples[sample] = static_cast<ALshort>(3500 * envelope *
            std::sin(2.0 * pi * 440.0 * sample / sampleRate));
    }

    ALuint buffer = 0;
    ALuint source = 0;
    alGenBuffers(1, &buffer);
    alGenSources(1, &source);
    alBufferData(buffer, AL_FORMAT_MONO16, samples.data(),
        static_cast<ALsizei>(samples.size() * sizeof(ALshort)), sampleRate);
    alSourcei(source, AL_BUFFER, static_cast<ALint>(buffer));
    alSourcePlay(source);
    ALint state = AL_STOPPED;
    alGetSourcei(source, AL_SOURCE_STATE, &state);
    const bool playing = alGetError() == AL_NO_ERROR && state == AL_PLAYING;
    if (playing) std::this_thread::sleep_for(std::chrono::milliseconds(durationMs + 80));

    if (source) alDeleteSources(1, &source);
    if (buffer) alDeleteBuffers(1, &buffer);
    alcMakeContextCurrent(nullptr);
    return playing ? nullptr : env->NewStringUTF("OpenAL abrió el dispositivo, pero no pudo reproducir el tono.");
}
