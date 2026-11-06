// Brug tussen Kotlin (NativeSynth.kt) en de geluidsmotor (SynthEngine).
#include <jni.h>

#include "SynthEngine.h"

namespace {
// Eén geluidsmotor voor de hele app. Een lokale static wordt in C++ veilig één keer
// aangemaakt, ook als de UI-thread en de audio-start-thread tegelijk aankloppen.
SynthEngine& engine() {
    static SynthEngine instance;
    return instance;
}
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_projectx_synth_NativeSynth_nativeStart(JNIEnv*, jobject) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_projectx_synth_NativeSynth_nativeLoadWavetables(JNIEnv* env, jobject, jbyteArray data) {
    const jsize n = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return JNI_FALSE;
    const bool ok = engine().loadWavetables(reinterpret_cast<const uint8_t*>(bytes), size_t(n));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);   // alleen gelezen
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_projectx_synth_NativeSynth_nativeStop(JNIEnv*, jobject) {
    engine().stop();
}

JNIEXPORT void JNICALL
Java_com_projectx_synth_NativeSynth_nativeSetParam(JNIEnv*, jobject, jint id, jfloat value) {
    engine().setParam(id, value);
}

JNIEXPORT void JNICALL
Java_com_projectx_synth_NativeSynth_nativeGetStats(JNIEnv* env, jobject, jfloatArray out) {
    float values[STAT_COUNT];
    engine().getStats(values);
    if (env->GetArrayLength(out) >= STAT_COUNT) env->SetFloatArrayRegion(out, 0, STAT_COUNT, values);
}

JNIEXPORT void JNICALL
Java_com_projectx_synth_NativeSynth_nativeResetPeak(JNIEnv*, jobject) {
    engine().resetPeak();
}

JNIEXPORT jint JNICALL
Java_com_projectx_synth_NativeSynth_nativeGetScope(JNIEnv* env, jobject, jfloatArray out) {
    float values[SynthEngine::kScopeSize];
    int n = env->GetArrayLength(out);
    n = engine().getScope(values, n < SynthEngine::kScopeSize ? n : SynthEngine::kScopeSize);
    if (n > 0) env->SetFloatArrayRegion(out, 0, n, values);
    return n;
}

JNIEXPORT void JNICALL
Java_com_projectx_synth_NativeSynth_nativeTakeMeterPeaks(JNIEnv* env, jobject, jfloatArray out) {
    float values[2];
    engine().takeMeterPeaks(values);
    if (env->GetArrayLength(out) >= 2) env->SetFloatArrayRegion(out, 0, 2, values);
}

}  // extern "C"
