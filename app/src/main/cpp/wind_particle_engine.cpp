/**
 * wind_particle_engine.cpp — Native C++ Wind Particle Engine
 *
 * All hot-path computation is here: streamline integration (RK4),
 * cursor advancement, geo→screen projection, vertex buffer fill.
 *
 * Performance target: <0.5ms per frame for 1500 particles on ARM64.
 */
#include "wind_particle_engine.h"

#include <cmath>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <android/log.h>

#define TAG "WindParticleNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace windparticle {

// Speed→color ramp (vivid Windy-style)
static constexpr float SPEED_COLORS[][3] = {
    {0.30f, 0.90f, 0.40f},  // 0-2 m/s: green
    {1.00f, 1.00f, 0.30f},  // 2-5 m/s: yellow
    {1.00f, 0.65f, 0.15f},  // 5-10 m/s: orange
    {1.00f, 0.20f, 0.20f},  // 10-15 m/s: red
    {0.90f, 0.40f, 1.00f},  // 15+ m/s: magenta
};
static constexpr float SPEED_THRESHOLDS[] = {2.f, 5.f, 10.f, 15.f};
static constexpr int   NUM_SPEED_BANDS = 5;

// ══════════════════════════════════════════════════════════════════════════

ParticleEngine::ParticleEngine()
    : rng_(std::random_device{}())
{
}

ParticleEngine::~ParticleEngine() = default;

// ── Wind data ─────────────────────────────────────────────────────────────

void ParticleEngine::setWindField(
        const double* speed, const double* direction,
        int rows, int cols,
        double north, double south, double west, double east)
{
    gridRows_ = rows;
    gridCols_ = cols;
    gridN_ = north; gridS_ = south; gridW_ = west; gridE_ = east;

    int n = rows * cols;
    windSpeed_.assign(speed, speed + n);
    windDir_.assign(direction, direction + n);
    hasData_ = true;

    computeStreamlines();
    initParticles();
    {
        std::lock_guard<std::mutex> lock(streamlineMutex_);
        initParticlesDirect();  // Protected — advanceGeo() also holds this lock
    }
}

void ParticleEngine::clearWindField() {
    hasData_ = false;
    windSpeed_.clear();
    windDir_.clear();
    streamlines_.clear();
    vertexCount_ = 0;
}

// ── Viewport bounds ───────────────────────────────────────────────────────

void ParticleEngine::setViewportBounds(double north, double south, double west, double east) {
    vpNorth_ = north; vpSouth_ = south; vpWest_ = west; vpEast_ = east;
    hasViewport_ = true;
}

// ── Projection ────────────────────────────────────────────────────────────

void ParticleEngine::setProjection(
        double centerLat, double centerLon,
        double rotation, double tilt, double scale,
        float vpW, float vpH, float focusX, float focusY)
{
    proj_.centerLat = centerLat;
    proj_.centerLon = centerLon;
    proj_.mapRotation = rotation;
    proj_.mapTilt = tilt;
    proj_.mapScale = scale;
    proj_.vpWidth = vpW;
    proj_.vpHeight = vpH;
    proj_.focusX = focusX;
    proj_.focusY = focusY;
}

// ── Configuration ─────────────────────────────────────────────────────────

void ParticleEngine::setParticleCount(int n) {
    n = std::clamp(n, 10, 10000);
    if (n != particleCount_) {
        particleCount_ = n;
        // Reinit both modes
        if (!streamlines_.empty()) initParticles();
        if (hasData_) initParticlesDirect();
    }
}
void ParticleEngine::setParticleSpeed(float s)   { particleSpeed_ = std::clamp(s, 0.1f, 5.0f); }
void ParticleEngine::setTrailLength(int t) {
    t = std::clamp(t, 5, 200);
    if (t != trailLength_) {
        trailLength_ = t;
        // Reallocate buffers for new trail length
        int maxSegs = particleCount_ * (trailLength_ + 2);
        vertexBuf_.resize(maxSegs * 2 * 2);
        colorBuf_.resize(maxSegs * 2 * 4);
    }
}
void ParticleEngine::setLineWidth(float w)       { lineWidth_ = std::clamp(w, 0.5f, 4.0f); }
void ParticleEngine::setColorIntensity(float v)  { intensity_ = std::clamp(v, 0.0f, 1.0f); }
void ParticleEngine::setColorSaturation(float v) { saturation_ = std::clamp(v, 0.0f, 1.0f); }
void ParticleEngine::setColorBrightness(float v) { brightness_ = std::clamp(v, 0.0f, 1.5f); }

// ══════════════════════════════════════════════════════════════════════════
// Streamline computation (RK4)
// ══════════════════════════════════════════════════════════════════════════

void ParticleEngine::computeStreamlines() {
    if (!hasData_) return;

    double latRange = gridN_ - gridS_;
    double lonRange = gridE_ - gridW_;
    double midLat = (gridN_ + gridS_) * 0.5;
    double degPerMeterLat = 1.0 / 111320.0;
    double degPerMeterLon = 1.0 / (111320.0 * cos(midLat * DEG2RAD));

    int seedRows = std::max(2, (int)sqrt(NUM_STREAMLINES * latRange / std::max(lonRange, 0.001)));
    int seedCols = std::max(2, NUM_STREAMLINES / seedRows);

    std::vector<Streamline> result;
    result.reserve(seedRows * seedCols);

    std::uniform_real_distribution<double> jitter(0.1, 0.9);

    for (int sr = 0; sr < seedRows; sr++) {
        for (int sc = 0; sc < seedCols; sc++) {
            double lat = gridS_ + (sr + jitter(rng_)) * latRange / seedRows;
            double lon = gridW_ + (sc + jitter(rng_)) * lonRange / seedCols;

            Streamline sl = integrateOne(lat, lon);
            if (sl.length > 10) {
                result.push_back(std::move(sl));
            }
        }
    }

    std::lock_guard<std::mutex> lock(streamlineMutex_);
    streamlines_ = std::move(result);
    LOGI("Streamlines: %zu computed, grid %dx%d, bounds [%.4f,%.4f]-[%.4f,%.4f]",
         streamlines_.size(), gridRows_, gridCols_, gridS_, gridW_, gridN_, gridE_);
}

Streamline ParticleEngine::integrateOne(double startLat, double startLon) const {
    Streamline sl;
    sl.lat.resize(STEPS_PER_LINE);
    sl.lon.resize(STEPS_PER_LINE);
    sl.speed.resize(STEPS_PER_LINE);

    double midLat = (gridN_ + gridS_) * 0.5;
    double dpmLat = 1.0 / 111320.0;
    double dpmLon = 1.0 / (111320.0 * cos(midLat * DEG2RAD));

    double lat = startLat, lon = startLon;
    int valid = 0;

    for (int step = 0; step < STEPS_PER_LINE; step++) {
        if (lat < gridS_ || lat > gridN_ || lon < gridW_ || lon > gridE_) break;

        double ws, wd;
        interpolateWind(lat, lon, ws, wd);
        if (ws < 0.1) break;

        sl.lat[step] = lat;
        sl.lon[step] = lon;
        sl.speed[step] = static_cast<float>(ws);
        valid = step + 1;

        // RK4 integration
        double h = STEP_SIZE_M;
        auto velocity = [&](double la, double lo, double& dlat, double& dlon) {
            double s, d;
            interpolateWind(la, lo, s, d);
            double bearRad = ((d + 180.0) * DEG2RAD);  // wind FROM → move opposite
            bearRad = fmod(bearRad, 6.28318530718);
            dlat = cos(bearRad) * dpmLat;
            dlon = sin(bearRad) * dpmLon;
        };

        double k1lat, k1lon;
        velocity(lat, lon, k1lat, k1lon);

        double k2lat, k2lon;
        velocity(lat + 0.5*h*k1lat, lon + 0.5*h*k1lon, k2lat, k2lon);

        double k3lat, k3lon;
        velocity(lat + 0.5*h*k2lat, lon + 0.5*h*k2lon, k3lat, k3lon);

        double k4lat, k4lon;
        velocity(lat + h*k3lat, lon + h*k3lon, k4lat, k4lon);

        lat += h/6.0 * (k1lat + 2*k2lat + 2*k3lat + k4lat);
        lon += h/6.0 * (k1lon + 2*k2lon + 2*k3lon + k4lon);
    }

    sl.length = valid;
    return sl;
}

void ParticleEngine::interpolateWind(double lat, double lon,
                                      double& outSpeed, double& outDir) const {
    outSpeed = 0; outDir = 0;
    if (windSpeed_.empty()) return;

    double fracRow = (lat - gridS_) / (gridN_ - gridS_) * (gridRows_ - 1);
    double fracCol = (lon - gridW_) / (gridE_ - gridW_) * (gridCols_ - 1);

    int r0 = std::clamp((int)fracRow, 0, gridRows_ - 2);
    int c0 = std::clamp((int)fracCol, 0, gridCols_ - 2);
    double fr = fracRow - r0;
    double fc = fracCol - c0;

    auto idx = [&](int r, int c) { return r * gridCols_ + c; };

    // Speed: simple bilinear
    outSpeed = windSpeed_[idx(r0,c0)]   * (1-fr)*(1-fc)
             + windSpeed_[idx(r0,c0+1)] * (1-fr)*fc
             + windSpeed_[idx(r0+1,c0)] * fr*(1-fc)
             + windSpeed_[idx(r0+1,c0+1)]* fr*fc;

    // Direction: via sin/cos for 360° wrap
    double d00 = windDir_[idx(r0,c0)]   * DEG2RAD;
    double d01 = windDir_[idx(r0,c0+1)] * DEG2RAD;
    double d10 = windDir_[idx(r0+1,c0)] * DEG2RAD;
    double d11 = windDir_[idx(r0+1,c0+1)]* DEG2RAD;

    double sinS = sin(d00)*(1-fr)*(1-fc) + sin(d01)*(1-fr)*fc
                + sin(d10)*fr*(1-fc) + sin(d11)*fr*fc;
    double cosS = cos(d00)*(1-fr)*(1-fc) + cos(d01)*(1-fr)*fc
                + cos(d10)*fr*(1-fc) + cos(d11)*fr*fc;
    outDir = atan2(sinS, cosS) / DEG2RAD;
    if (outDir < 0) outDir += 360.0;
}

// ══════════════════════════════════════════════════════════════════════════
// Particle initialization
// ══════════════════════════════════════════════════════════════════════════

void ParticleEngine::initParticles() {
    cursorStreamline_.resize(particleCount_);
    cursorPosition_.resize(particleCount_);
    cursorAge_.resize(particleCount_);

    {
        std::lock_guard<std::mutex> lock(streamlineMutex_);
        for (int i = 0; i < particleCount_; i++) {
            respawn(i);
        }
    }

    // Pre-allocate output buffers (worst case: each particle has trailLength segments)
    int maxSegs = particleCount_ * (trailLength_ + 2);
    vertexBuf_.resize(maxSegs * 2 * 2);  // 2 verts × 2 floats(x,y)
    colorBuf_.resize(maxSegs * 2 * 4);   // 2 verts × 4 floats(r,g,b,a)
    LOGI("Particles: count=%d, trail=%d, bufSize=%d floats",
         particleCount_, trailLength_, (int)vertexBuf_.size());
}

/** Respawn particle. Caller MUST hold streamlineMutex_ (or be in initParticles). */
void ParticleEngine::respawn(int i) {
    // NOTE: no lock here — called from initParticles (pre-lock) and advanceAndBuild (already locked)
    if (streamlines_.empty()) return;

    std::uniform_int_distribution<int> slDist(0, static_cast<int>(streamlines_.size()) - 1);
    int slIdx = slDist(rng_);
    const auto& sl = streamlines_[slIdx];

    cursorStreamline_[i] = slIdx;
    // Spread particles across the FULL streamline length, not just the first third.
    // This prevents clustering at the origin — particles appear distributed
    // along the entire flow path from the start.
    std::uniform_real_distribution<float> posDist(0.0f, static_cast<float>(sl.length - 1));
    cursorPosition_[i] = posDist(rng_);
    std::uniform_real_distribution<float> ageDist(particleLife_ * 0.5f, particleLife_);
    cursorAge_[i] = ageDist(rng_);
}

// ══════════════════════════════════════════════════════════════════════════
// Frame update — THE HOT PATH
// ══════════════════════════════════════════════════════════════════════════

int ParticleEngine::advanceAndBuild() {
    auto t0 = std::chrono::high_resolution_clock::now();

    std::lock_guard<std::mutex> lock(streamlineMutex_);
    if (streamlines_.empty()) { vertexCount_ = 0; return 0; }

    float advanceRate = 1.5f * particleSpeed_;
    int numSL = static_cast<int>(streamlines_.size());
    float maxLife = particleLife_;

    int vIdx = 0;  // index into vertexBuf_ (floats)
    int cIdx = 0;  // index into colorBuf_ (floats)
    int maxVerts = static_cast<int>(vertexBuf_.size());
    int maxColors = static_cast<int>(colorBuf_.size());

    float vpW = proj_.vpWidth;
    float vpH = proj_.vpHeight;
    double ppd = proj_.mapScale;

    // Adaptive trail: at high zoom (large ppd), each 50m step is sub-pixel,
    // so we need many more streamline steps to get a visible trail.
    // At low zoom (small ppd), fewer steps cover more screen distance.
    // Target: trail covers ~100-200 pixels on screen.
    //
    // 50m step = 50/111320 degrees ≈ 0.000449 degrees
    // Screen pixels per step = 0.000449 * ppd
    // To get ~150px trail: need 150 / (0.000449 * ppd) steps
    double pixPerStep = 0.000449 * ppd;
    int targetTrailPx = 150;
    int adaptiveTrail = (pixPerStep > 0.01)
            ? std::clamp(static_cast<int>(targetTrailPx / pixPerStep), 10, 450)
            : trailLength_;

    // Use the larger of user-requested trail and adaptive trail
    int effectiveTrail = std::max(trailLength_, adaptiveTrail);

    // Subsample: ensure we draw at most ~40 segments per particle (perf limit)
    int trailSubsample = std::max(1, effectiveTrail / 40);

    for (int i = 0; i < particleCount_; i++) {
        // Advance cursor
        cursorAge_[i] -= 1.0f;
        cursorPosition_[i] += advanceRate;

        int slIdx = cursorStreamline_[i];
        if (slIdx < 0 || slIdx >= numSL) { respawn(i); continue; }

        const Streamline& sl = streamlines_[slIdx];
        float fPos = cursorPosition_[i];

        if (fPos >= sl.length - 2 || cursorAge_[i] <= 0) {
            // Re-spawn inline (avoid mutex re-lock since we already hold it)
            std::uniform_int_distribution<int> slDist(0, numSL - 1);
            slIdx = slDist(rng_);
            cursorStreamline_[i] = slIdx;
            const auto& newSl = streamlines_[slIdx];
            std::uniform_real_distribution<float> pd(0.0f, newSl.length * 0.33f);
            cursorPosition_[i] = pd(rng_);
            std::uniform_real_distribution<float> ad(maxLife * 0.5f, maxLife);
            cursorAge_[i] = ad(rng_);
            continue;
        }

        int headIdx = static_cast<int>(fPos);
        float headFrac = fPos - headIdx;

        // Trail bounds — adaptive to zoom level
        int trailStart = std::max(0, headIdx - effectiveTrail);
        if (headIdx - trailStart < trailSubsample) continue;

        // Alpha
        float ageFrac = cursorAge_[i] / maxLife;
        float baseAlpha = std::min(1.0f, ageFrac * 3.0f) * intensity_;

        // Color from speed at head
        float spd = (headIdx < sl.length) ? sl.speed[headIdx] : 0.0f;
        float cr, cg, cb;
        speedToColor(spd, cr, cg, cb);

        // Apply saturation + brightness
        if (saturation_ < 1.0f) {
            float grey = 0.299f * cr + 0.587f * cg + 0.114f * cb;
            cr = grey + saturation_ * (cr - grey);
            cg = grey + saturation_ * (cg - grey);
            cb = grey + saturation_ * (cb - grey);
        }
        cr = std::min(1.0f, cr * brightness_);
        cg = std::min(1.0f, cg * brightness_);
        cb = std::min(1.0f, cb * brightness_);

        // Project subsampled trail
        float prevX, prevY;
        bool hasPrev = false;
        int trailLen = headIdx - trailStart;

        for (int t = trailStart; t <= headIdx; t += trailSubsample) {
            float sx, sy;
            proj_.forward(sl.lat[t], sl.lon[t], sx, sy);

            if (hasPrev && vIdx + 4 <= maxVerts && cIdx + 8 <= maxColors) {
                // Off-screen cull
                if (!(prevX < -100 && sx < -100) && !(prevX > vpW+100 && sx > vpW+100)
                 && !(prevY < -100 && sy < -100) && !(prevY > vpH+100 && sy > vpH+100)) {
                    float trailFrac = static_cast<float>(t - trailStart) / trailLen;
                    float alpha = baseAlpha * (0.2f + 0.8f * trailFrac);

                    vertexBuf_[vIdx++] = prevX; vertexBuf_[vIdx++] = prevY;
                    vertexBuf_[vIdx++] = sx;    vertexBuf_[vIdx++] = sy;
                    colorBuf_[cIdx++] = cr; colorBuf_[cIdx++] = cg;
                    colorBuf_[cIdx++] = cb; colorBuf_[cIdx++] = alpha;
                    colorBuf_[cIdx++] = cr; colorBuf_[cIdx++] = cg;
                    colorBuf_[cIdx++] = cb; colorBuf_[cIdx++] = alpha;
                }
            }
            prevX = sx; prevY = sy;
            hasPrev = true;
        }

        // Interpolated head point (smooth between path steps)
        if (hasPrev && headIdx + 1 < sl.length && vIdx + 4 <= maxVerts && cIdx + 8 <= maxColors) {
            double hLat = sl.lat[headIdx] + headFrac * (sl.lat[headIdx+1] - sl.lat[headIdx]);
            double hLon = sl.lon[headIdx] + headFrac * (sl.lon[headIdx+1] - sl.lon[headIdx]);
            float hx, hy;
            proj_.forward(hLat, hLon, hx, hy);

            vertexBuf_[vIdx++] = prevX; vertexBuf_[vIdx++] = prevY;
            vertexBuf_[vIdx++] = hx;    vertexBuf_[vIdx++] = hy;
            colorBuf_[cIdx++] = cr; colorBuf_[cIdx++] = cg;
            colorBuf_[cIdx++] = cb; colorBuf_[cIdx++] = baseAlpha;
            colorBuf_[cIdx++] = cr; colorBuf_[cIdx++] = cg;
            colorBuf_[cIdx++] = cb; colorBuf_[cIdx++] = baseAlpha;
        }
    }

    vertexCount_ = vIdx / 2;  // number of float pairs = vertex count

    auto t1 = std::chrono::high_resolution_clock::now();
    lastFrameUs_ = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();

    // Debug log every 60 frames
    static int frameCounter = 0;
    if (++frameCounter % 60 == 0) {
        LOGD("Frame: verts=%d, particles=%d, streamlines=%zu, ppd=%.1f, vp=%.0fx%.0f, "
             "trail=%d(eff=%d,sub=%d), pxPerStep=%.3f, time=%.0fus",
             vertexCount_, particleCount_, streamlines_.size(),
             proj_.mapScale, proj_.vpWidth, proj_.vpHeight,
             trailLength_, effectiveTrail, trailSubsample, pixPerStep, lastFrameUs_);
    }

    return vertexCount_;
}

// ══════════════════════════════════════════════════════════════════════════
// V4 Hybrid: advanceGeo() — Windy-style direct advection
//
// Each particle owns its (lat,lon) and moves by interpolating wind at its
// position each frame. No streamlines needed. This gives uniform density
// across the entire wind field, exactly like Windy.com.
//
// Particle lifecycle:
//   1. Spawn at random position within wind grid bounds
//   2. Each frame: interpolate wind → move in wind direction → age decrements
//   3. Respawn when: age expired, left grid, or random drop
// ══════════════════════════════════════════════════════════════════════════

void ParticleEngine::initParticlesDirect() {
    partLat_.resize(particleCount_);
    partLon_.resize(particleCount_);
    partPrevLat_.resize(particleCount_);
    partPrevLon_.resize(particleCount_);
    partAge_.resize(particleCount_);
    partSpeed_.resize(particleCount_);
    for (int i = 0; i < particleCount_; i++) {
        respawnDirect(i);
    }
}

void ParticleEngine::respawnDirect(int i) {
    // Spawn within INTERSECTION of viewport and grid bounds.
    // This ensures particles appear where the user is looking, not
    // across the entire multi-thousand-km grid.
    double sN = gridN_, sS = gridS_, sW = gridW_, sE = gridE_;
    if (hasViewport_) {
        sN = std::min(sN, vpNorth_);
        sS = std::max(sS, vpSouth_);
        sW = std::max(sW, vpWest_);
        sE = std::min(sE, vpEast_);
        // If viewport doesn't intersect grid, fall back to full grid
        if (sN <= sS || sE <= sW) {
            sN = gridN_; sS = gridS_; sW = gridW_; sE = gridE_;
        }
    }

    std::uniform_real_distribution<double> latDist(sS, sN);
    std::uniform_real_distribution<double> lonDist(sW, sE);
    double lat = latDist(rng_);
    double lon = lonDist(rng_);
    partLat_[i] = lat;
    partLon_[i] = lon;
    partPrevLat_[i] = lat;
    partPrevLon_[i] = lon;
    std::uniform_real_distribution<float> ageDist(particleLife_ * 0.3f, particleLife_);
    partAge_[i] = ageDist(rng_);
    partSpeed_[i] = 0;
}

int ParticleEngine::advanceGeo() {
    auto t0 = std::chrono::high_resolution_clock::now();

    if (!hasData_) { geoCount_ = 0; return 0; }

    // Initialize direct-mode particles if needed (thread-safe check)
    if (static_cast<int>(partLat_.size()) != particleCount_) {
        std::lock_guard<std::mutex> lock(streamlineMutex_);
        if (static_cast<int>(partLat_.size()) != particleCount_) {
            initParticlesDirect();
        }
    }

    // Ensure output buffer sized: 6 floats per particle
    // {prevLat, prevLon, curLat, curLon, speed, ageFrac}
    int needed = particleCount_ * 6;
    if (static_cast<int>(geoBuf_.size()) < needed) {
        geoBuf_.resize(needed, 0.0f);
    }

    // Physics constants for this frame
    double midLat = (gridN_ + gridS_) * 0.5;
    double dpmLat = 1.0 / 111320.0;
    double dpmLon = 1.0 / (111320.0 * cos(midLat * DEG2RAD));

    // Step size in meters: base 200m * speed multiplier
    // At 5 m/s wind and 30fps, this gives ~200m/frame = 6km/s visual speed
    // which looks good for animation. Real displacement would be 5m/frame
    // but that's invisible — we exaggerate for visual effect.
    double stepM = 200.0 * particleSpeed_;

    // Drop rate: Windy technique — faster wind = faster respawn
    float dropBase = 0.003f;
    std::uniform_real_distribution<float> dist01(0.0f, 1.0f);

    int outIdx = 0;

    for (int i = 0; i < particleCount_; i++) {
        partAge_[i] -= 1.0f;

        // Respawn if age expired
        if (partAge_[i] <= 0) {
            respawnDirect(i);
        }

        double lat = partLat_[i];
        double lon = partLon_[i];

        // Bounds check — respawn if outside grid OR viewport (with margin)
        double margin = hasViewport_ ? std::max(vpNorth_ - vpSouth_, vpEast_ - vpWest_) * 0.2 : 0;
        bool outsideGrid = (lat < gridS_ || lat > gridN_ || lon < gridW_ || lon > gridE_);
        bool outsideViewport = hasViewport_ && (
            lat < vpSouth_ - margin || lat > vpNorth_ + margin ||
            lon < vpWest_ - margin || lon > vpEast_ + margin);
        if (outsideGrid || outsideViewport) {
            respawnDirect(i);
            lat = partLat_[i];
            lon = partLon_[i];
        }

        // Interpolate wind at current position
        double ws, wd;
        interpolateWind(lat, lon, ws, wd);

        if (ws < 0.05) {
            // Calm — skip (don't output, don't move)
            partSpeed_[i] = 0;
            continue;
        }

        // Speed-based random drop (Windy technique)
        float effectiveDrop = dropBase + static_cast<float>(ws) * 0.0001f;
        if (dist01(rng_) < effectiveDrop) {
            respawnDirect(i);
            continue; // skip this frame — new position will be used next frame
        }

        // Save previous position for line segment
        partPrevLat_[i] = lat;
        partPrevLon_[i] = lon;

        // Move particle in wind direction
        // Wind direction is "from" — particles move opposite (+180°)
        double bearRad = fmod((wd + 180.0) * DEG2RAD, 6.28318530718);
        double dLat = cos(bearRad) * dpmLat * stepM;
        double dLon = sin(bearRad) * dpmLon * stepM;

        double newLat = lat + dLat;
        double newLon = lon + dLon;
        partLat_[i] = newLat;
        partLon_[i] = newLon;
        partSpeed_[i] = static_cast<float>(ws);

        // Output: {prevLat, prevLon, curLat, curLon, speed, ageFrac}
        float ageFrac = partAge_[i] / particleLife_;
        geoBuf_[outIdx++] = static_cast<float>(lat);       // prev
        geoBuf_[outIdx++] = static_cast<float>(lon);
        geoBuf_[outIdx++] = static_cast<float>(newLat);    // current
        geoBuf_[outIdx++] = static_cast<float>(newLon);
        geoBuf_[outIdx++] = static_cast<float>(ws);
        geoBuf_[outIdx++] = ageFrac;
    }

    geoCount_ = outIdx / 6;  // 6 floats per particle

    auto t1 = std::chrono::high_resolution_clock::now();
    lastFrameUs_ = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();

    return geoCount_;
}

// ── Color lookup ──────────────────────────────────────────────────────────

void ParticleEngine::speedToColor(float speed, float& r, float& g, float& b) const {
    for (int i = 0; i < 4; i++) {
        if (speed < SPEED_THRESHOLDS[i]) {
            if (i == 0) { r = SPEED_COLORS[0][0]; g = SPEED_COLORS[0][1]; b = SPEED_COLORS[0][2]; return; }
            float t = (speed - SPEED_THRESHOLDS[i-1]) / (SPEED_THRESHOLDS[i] - SPEED_THRESHOLDS[i-1]);
            r = SPEED_COLORS[i-1][0] + t * (SPEED_COLORS[i][0] - SPEED_COLORS[i-1][0]);
            g = SPEED_COLORS[i-1][1] + t * (SPEED_COLORS[i][1] - SPEED_COLORS[i-1][1]);
            b = SPEED_COLORS[i-1][2] + t * (SPEED_COLORS[i][2] - SPEED_COLORS[i-1][2]);
            return;
        }
    }
    r = SPEED_COLORS[NUM_SPEED_BANDS-1][0];
    g = SPEED_COLORS[NUM_SPEED_BANDS-1][1];
    b = SPEED_COLORS[NUM_SPEED_BANDS-1][2];
}

} // namespace windparticle
