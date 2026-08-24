/**
 * wind_particle_engine.h — Native C++ Wind Particle Engine
 *
 * All particle simulation, streamline integration, geo→screen projection,
 * and vertex buffer construction happens here — zero Java/JNI overhead
 * in the hot path.
 *
 * Architecture:
 *   1. setWindField()  — copies grid data, triggers streamline recompute
 *   2. setProjection()  — copies map scene params each frame from Java
 *   3. advanceAndBuild() — advances cursors + builds GL vertex/color buffers
 *   4. Java GL layer just binds the buffer and calls glDrawArrays
 */
#pragma once

#include <cstdint>
#include <vector>
#include <random>
#include <atomic>
#include <mutex>

namespace windparticle {

// ══════════════════════════════════════════════════════════════════════════
// Streamline — pre-computed path through the wind field
// ══════════════════════════════════════════════════════════════════════════

struct Streamline {
    std::vector<double> lat;   // position along path
    std::vector<double> lon;
    std::vector<float>  speed; // wind speed at each point (for color)
    int length = 0;
};

// ══════════════════════════════════════════════════════════════════════════
// Simple Mercator-like projection (avoids JNI scene.forward() entirely)
// ══════════════════════════════════════════════════════════════════════════

struct MapProjection {
    double centerLat = 0, centerLon = 0;
    double mapRotation = 0;     // degrees
    double mapTilt = 0;         // degrees
    double mapScale = 1.0;      // pixels per degree (approx)
    float  vpWidth = 0, vpHeight = 0;
    float  focusX = 0, focusY = 0;

    // Forward project geo → screen (x,y)
    // Uses simplified Mercator: accurate enough for overlay particles
    inline void forward(double lat, double lon, float& outX, float& outY) const {
        double dLon = lon - centerLon;
        double dLat = lat - centerLat;

        // Apply Mercator-like scaling (latitude stretch)
        double cosCenter = cos(centerLat * 0.01745329251994);
        double px = dLon * mapScale;
        double py = -dLat * mapScale;  // screen Y is down

        // Apply map rotation
        if (mapRotation != 0.0) {
            double rad = mapRotation * 0.01745329251994;
            double c = cos(rad), s = sin(rad);
            double rx = px * c - py * s;
            double ry = px * s + py * c;
            px = rx;
            py = ry;
        }

        outX = static_cast<float>(focusX + px);
        outY = static_cast<float>(focusY + py);
    }
};

// ══════════════════════════════════════════════════════════════════════════
// Particle Engine
// ══════════════════════════════════════════════════════════════════════════

class ParticleEngine {
public:
    ParticleEngine();
    ~ParticleEngine();

    // ── Wind data (called from Java main thread via JNI) ─────────────
    void setWindField(const double* speed, const double* direction,
                      int rows, int cols,
                      double north, double south, double west, double east);
    void clearWindField();
    bool hasData() const { return hasData_; }

    // ── Viewport bounds (called each frame for particle seeding) ─────
    void setViewportBounds(double north, double south, double west, double east);

    // ── Projection (called each frame from GL thread) ────────────────
    void setProjection(double centerLat, double centerLon,
                       double rotation, double tilt, double scale,
                       float vpW, float vpH, float focusX, float focusY);

    // ── Configuration ────────────────────────────────────────────────
    void setParticleCount(int n);
    void setParticleSpeed(float s);
    void setTrailLength(int t);
    void setLineWidth(float w);
    void setColorIntensity(float v);
    void setColorSaturation(float v);
    void setColorBrightness(float v);

    int   getParticleCount() const { return particleCount_; }
    float getParticleSpeed() const { return particleSpeed_; }
    int   getTrailLength()   const { return trailLength_; }

    // ── Frame update (called from GL thread) ─────────────────────────
    // Advances cursors, projects trail points, fills vertex+color buffers.
    // Returns number of vertices to draw (GL_LINES, so pairs).
    int advanceAndBuild();

    // ── V4 Hybrid: Geo-space output (no screen projection) ──────────
    // Advances cursors along streamlines and outputs HEAD positions
    // as {lat, lon, speed} triplets. No projection — Java side does
    // mapView.forward() for full-screen accuracy.
    // Returns number of active particles (each = 3 floats in geoBuf_).
    int advanceGeo();

    // ── Buffer access ────────────────────────────────────────────────
    const float* vertexBuffer() const { return vertexBuf_.data(); }
    const float* colorBuffer()  const { return colorBuf_.data(); }
    const float* geoBuffer()    const { return geoBuf_.data(); }
    int vertexCount() const { return vertexCount_; }
    int geoParticleCount() const { return geoCount_; }

    // ── Stats ────────────────────────────────────────────────────────
    int streamlineCount() const { return static_cast<int>(streamlines_.size()); }
    float lastFrameTimeUs() const { return lastFrameUs_; }

private:
    // Wind grid (owned copy)
    std::vector<double> windSpeed_;
    std::vector<double> windDir_;
    int gridRows_ = 0, gridCols_ = 0;
    double gridN_ = 0, gridS_ = 0, gridW_ = 0, gridE_ = 0;
    std::atomic<bool> hasData_{false};

    // Streamlines
    std::vector<Streamline> streamlines_;
    std::mutex streamlineMutex_;

    // Particles
    int particleCount_ = 1500;
    float particleSpeed_ = 1.0f;
    int trailLength_ = 40;
    float lineWidth_ = 2.0f;
    float particleLife_ = 120.0f;

    // Color
    float intensity_ = 1.0f;
    float saturation_ = 1.0f;
    float brightness_ = 1.2f;

    // Cursor state — streamline mode (SoA layout for cache efficiency)
    std::vector<int>   cursorStreamline_;
    std::vector<float> cursorPosition_;
    std::vector<float> cursorAge_;

    // Direct advection mode (Windy-style): particles own their geo position
    std::vector<double> partLat_;     // current latitude
    std::vector<double> partLon_;     // current longitude
    std::vector<double> partPrevLat_; // previous latitude (for line segment)
    std::vector<double> partPrevLon_; // previous longitude
    std::vector<float>  partAge_;     // remaining life (frames)
    std::vector<float>  partSpeed_;   // cached speed at current position
    bool directMode_ = true;         // use direct advection (not streamlines)

    // Projection
    MapProjection proj_;

    // Viewport geo bounds (for spawning particles within visible area)
    double vpNorth_ = 90, vpSouth_ = -90, vpWest_ = -180, vpEast_ = 180;
    bool hasViewport_ = false;

    // Output buffers (re-filled each frame)
    std::vector<float> vertexBuf_;  // x,y pairs (GL path)
    std::vector<float> colorBuf_;   // r,g,b,a quads (GL path)
    int vertexCount_ = 0;

    // V4 Hybrid: geo output buffer {lat, lon, speed} per active particle
    std::vector<float> geoBuf_;     // [N×3] flat: lat0, lon0, spd0, lat1, lon1, spd1, ...
    int geoCount_ = 0;             // number of active particles in geoBuf_

    // RNG
    std::mt19937 rng_;

    // Stats
    float lastFrameUs_ = 0;

    // ── Internal ─────────────────────────────────────────────────────
    void computeStreamlines();
    Streamline integrateOne(double startLat, double startLon) const;
    void interpolateWind(double lat, double lon, double& outSpeed, double& outDir) const;
    void initParticles();
    void respawn(int i);
    void speedToColor(float speed, float& r, float& g, float& b) const;

    // Direct advection helpers
    void initParticlesDirect();
    void respawnDirect(int i);

    static constexpr int    NUM_STREAMLINES = 200;
    static constexpr int    STEPS_PER_LINE  = 500;
    static constexpr double STEP_SIZE_M     = 50.0;
    // Trail subsample adapts at draw time based on ppd — no constant needed
    static constexpr double DEG2RAD         = 0.01745329251994;
};

} // namespace windparticle
