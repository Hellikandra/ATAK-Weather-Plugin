/**
 * wind_particle_jni.cpp — JNI bridge for native particle engine
 *
 * Thin layer: copies data in, exposes buffer pointers out.
 * The engine singleton is managed via create/destroy calls.
 */
#include "wind_particle_engine.h"

#include <jni.h>
#include <android/log.h>
#include <cstring>

#define TAG "WindParticleJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static windparticle::ParticleEngine* gEngine = nullptr;

extern "C" {

// ══════════════════════════════════════════════════════════════════════════
// Lifecycle
// ══════════════════════════════════════════════════════════════════════════

JNIEXPORT jlong JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nCreate(
        JNIEnv*, jclass) {
    auto* engine = new windparticle::ParticleEngine();
    gEngine = engine;
    LOGI("Native particle engine created");
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nDestroy(
        JNIEnv*, jclass, jlong ptr) {
    auto* engine = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (engine == gEngine) gEngine = nullptr;
    delete engine;
    LOGI("Native particle engine destroyed");
}

// ══════════════════════════════════════════════════════════════════════════
// Wind data
// ══════════════════════════════════════════════════════════════════════════

JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nSetWindField(
        JNIEnv* env, jclass, jlong ptr,
        jdoubleArray speedArr, jdoubleArray dirArr,
        jint rows, jint cols,
        jdouble north, jdouble south, jdouble west, jdouble east) {
    auto* engine = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (!engine) return;

    jdouble* speed = env->GetDoubleArrayElements(speedArr, nullptr);
    jdouble* dir   = env->GetDoubleArrayElements(dirArr, nullptr);

    engine->setWindField(speed, dir, rows, cols, north, south, west, east);

    env->ReleaseDoubleArrayElements(speedArr, speed, JNI_ABORT);
    env->ReleaseDoubleArrayElements(dirArr, dir, JNI_ABORT);

    LOGI("Wind field set: %dx%d, streamlines=%d", rows, cols, engine->streamlineCount());
}

JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nClearWindField(
        JNIEnv*, jclass, jlong ptr) {
    auto* engine = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (engine) engine->clearWindField();
}

// ══════════════════════════════════════════════════════════════════════════
// Projection (called each frame from GL thread)
// ══════════════════════════════════════════════════════════════════════════

JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nSetProjection(
        JNIEnv*, jclass, jlong ptr,
        jdouble centerLat, jdouble centerLon,
        jdouble rotation, jdouble tilt, jdouble scale,
        jfloat vpW, jfloat vpH, jfloat focusX, jfloat focusY) {
    auto* engine = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (engine) {
        engine->setProjection(centerLat, centerLon, rotation, tilt, scale,
                              vpW, vpH, focusX, focusY);
    }
}

// ══════════════════════════════════════════════════════════════════════════
// Frame update + GL draw (called from GL thread)
// ══════════════════════════════════════════════════════════════════════════

/**
 * Advance all particles and fill vertex+color buffers in native C++.
 * Returns the number of vertices to draw (GL_LINES pairs).
 * GL drawing is done on the Java side using GLES20FixedPipeline.
 */
JNIEXPORT jint JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nAdvance(
        JNIEnv*, jclass, jlong ptr) {
    auto* engine = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (!engine || !engine->hasData()) return 0;
    return engine->advanceAndBuild();
}

/**
 * Copy the native vertex buffer into a Java direct FloatBuffer.
 * Called after nAdvance() to get data for GL rendering on Java side.
 */
JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nCopyVertexBuffer(
        JNIEnv* env, jclass, jlong ptr, jobject directBuffer) {
    auto* engine = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (!engine) return;
    int count = engine->vertexCount();
    if (count <= 0) return;
    float* dst = static_cast<float*>(env->GetDirectBufferAddress(directBuffer));
    if (!dst) return;
    // vertex buffer has count vertices × 2 floats (x,y)
    memcpy(dst, engine->vertexBuffer(), count * 2 * sizeof(float));
}

/**
 * Copy the native color buffer into a Java direct FloatBuffer.
 */
JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nCopyColorBuffer(
        JNIEnv* env, jclass, jlong ptr, jobject directBuffer) {
    auto* engine = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (!engine) return;
    int count = engine->vertexCount();
    if (count <= 0) return;
    float* dst = static_cast<float*>(env->GetDirectBufferAddress(directBuffer));
    if (!dst) return;
    // color buffer has count vertices × 4 floats (r,g,b,a)
    memcpy(dst, engine->colorBuffer(), count * 4 * sizeof(float));
}

// ══════════════════════════════════════════════════════════════════════════
// Configuration
// ══════════════════════════════════════════════════════════════════════════

JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nSetParticleCount(
        JNIEnv*, jclass, jlong ptr, jint n) {
    auto* e = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (e) e->setParticleCount(n);
}

JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nSetParticleSpeed(
        JNIEnv*, jclass, jlong ptr, jfloat s) {
    auto* e = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (e) e->setParticleSpeed(s);
}

JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nSetTrailLength(
        JNIEnv*, jclass, jlong ptr, jint t) {
    auto* e = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (e) e->setTrailLength(t);
}

JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nSetColorIntensity(
        JNIEnv*, jclass, jlong ptr, jfloat v) {
    auto* e = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (e) e->setColorIntensity(v);
}

JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nSetColorSaturation(
        JNIEnv*, jclass, jlong ptr, jfloat v) {
    auto* e = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (e) e->setColorSaturation(v);
}

JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nSetColorBrightness(
        JNIEnv*, jclass, jlong ptr, jfloat v) {
    auto* e = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (e) e->setColorBrightness(v);
}

JNIEXPORT jfloat JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nGetLastFrameTimeUs(
        JNIEnv*, jclass, jlong ptr) {
    auto* e = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    return e ? e->lastFrameTimeUs() : 0.0f;
}

JNIEXPORT jint JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nGetStreamlineCount(
        JNIEnv*, jclass, jlong ptr) {
    auto* e = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    return e ? e->streamlineCount() : 0;
}

// ══════════════════════════════════════════════════════════════════════════
// V4 Hybrid: Geo-space output for bitmap View rendering
// ══════════════════════════════════════════════════════════════════════════

/**
 * Advance particles and output HEAD positions as geo coordinates.
 * Returns number of active particles (each = 3 floats: lat, lon, speed).
 * No screen projection — Java does mapView.forward() for full-screen accuracy.
 */
JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nSetViewportBounds(
        JNIEnv*, jclass, jlong ptr,
        jdouble north, jdouble south, jdouble west, jdouble east) {
    auto* e = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (e) e->setViewportBounds(north, south, west, east);
}

JNIEXPORT jint JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nAdvanceGeo(
        JNIEnv*, jclass, jlong ptr) {
    auto* engine = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (!engine || !engine->hasData()) return 0;
    return engine->advanceGeo();
}

/**
 * Copy the native geo buffer into a Java direct FloatBuffer.
 * Buffer format: [lat0, lon0, speed0, lat1, lon1, speed1, ...]
 */
JNIEXPORT void JNICALL
Java_com_atakmap_android_weather_overlay_wind_NativeWindParticle_nCopyGeoBuffer(
        JNIEnv* env, jclass, jlong ptr, jobject directBuffer) {
    auto* engine = reinterpret_cast<windparticle::ParticleEngine*>(ptr);
    if (!engine) return;
    int count = engine->geoParticleCount();
    if (count <= 0) return;
    float* dst = static_cast<float*>(env->GetDirectBufferAddress(directBuffer));
    if (!dst) return;
    // geo buffer: count particles × 6 floats (prevLat, prevLon, curLat, curLon, speed, ageFrac)
    memcpy(dst, engine->geoBuffer(), count * 6 * sizeof(float));
}

} // extern "C"
