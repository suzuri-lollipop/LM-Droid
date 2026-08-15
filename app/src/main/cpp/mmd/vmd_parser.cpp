#include "mmd_format.h"

#include <algorithm>
#include <android/log.h>
#include <cstring>
#include <fstream>

#define VMD_LOG(...) __android_log_print(ANDROID_LOG_INFO, "VmdParser", __VA_ARGS__)

namespace mmd {

namespace {

struct Reader {
    const uint8_t* data;
    size_t size;
    size_t pos = 0;

    bool ok(size_t need) const { return pos + need <= size; }
    uint8_t u8() { return ok(1) ? data[pos++] : 0; }
    int32_t i32() { int32_t v = 0; if (ok(4)) { memcpy(&v, data + pos, 4); pos += 4; } return v; }
    float f32() { float v = 0; if (ok(4)) { memcpy(&v, data + pos, 4); pos += 4; } return v; }

    // Shift-JIS fixed-length name fields: NUL/space padded, not necessarily terminated.
    // The raw bytes are handed to [decode] (the JNI layer transcodes via Java's Shift_JIS
    // charset — a native SJIS table would be thousands of entries for no other benefit).
    std::string fixedName(size_t len, const VmdNameDecoder& decode) {
        if (!ok(len)) { pos = size; return {}; }
        size_t end = 0;
        while (end < len && data[pos + end] != 0) end++;
        std::string name = decode(reinterpret_cast<const char*>(data + pos), end);
        while (!name.empty() && (name.back() == ' ' || name.back() == '\0')) name.pop_back();
        pos += len;
        return name;
    }
};

} // namespace

float VmdInterpolate(float x1, float y1, float x2, float y2, float x) {
    if (x <= 0.f) return 0.f;
    if (x >= 1.f) return 1.f;
    // Solve the cubic bezier x(t) = x for t with bisection (monotonic for sane control points),
    // then evaluate y(t). Cheap and stable — VMD curves never need Newton precision.
    float lo = 0.f, hi = 1.f;
    for (int i = 0; i < 16; i++) {
        float t = (lo + hi) * 0.5f;
        float it = 1.f - t;
        float xt = 3.f * it * it * t * x1 + 3.f * it * t * t * x2 + t * t * t;
        if (xt < x) lo = t; else hi = t;
    }
    float t = (lo + hi) * 0.5f;
    float it = 1.f - t;
    return 3.f * it * it * t * y1 + 3.f * it * t * t * y2 + t * t * t;
}

bool LoadVmd(const std::string& path, VmdMotion* out, std::string* error, const VmdNameDecoder& nameDecoder) {
    std::ifstream file(path, std::ios::binary);
    if (!file) {
        *error = "cannot open " + path;
        return false;
    }
    std::vector<uint8_t> buffer((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    Reader r{buffer.data(), buffer.size()};

    if (!r.ok(30) || memcmp(r.data, "Vocaloid Motion Data", 20) != 0) {
        *error = "not a VMD file (bad magic)";
        return false;
    }
    r.pos = 30;
    r.fixedName(20, nameDecoder); // model name

    int32_t boneFrameCount = r.i32();
    for (int32_t i = 0; i < boneFrameCount && r.ok(111); i++) {
        std::string name = r.fixedName(15, nameDecoder);
        VmdBoneFrame frame;
        frame.frame = r.i32();
        frame.position = btVector3(r.f32(), r.f32(), r.f32());
        frame.rotation = btQuaternion(r.f32(), r.f32(), r.f32(), r.f32());
        if (!r.ok(64)) break;
        memcpy(frame.interpolation, r.data + r.pos, 64);
        r.pos += 64;
        out->boneFrames[name].push_back(frame);
        out->maxFrame = std::max(out->maxFrame, frame.frame);
    }

    if (r.ok(4)) {
        int32_t morphFrameCount = r.i32();
        for (int32_t i = 0; i < morphFrameCount && r.ok(23); i++) {
            std::string name = r.fixedName(15, nameDecoder);
            VmdMorphFrame frame;
            frame.frame = r.i32();
            frame.weight = r.f32();
            out->morphFrames[name].push_back(frame);
            out->maxFrame = std::max(out->maxFrame, frame.frame);
        }
    }

    for (auto& [name, frames] : out->boneFrames) {
        std::sort(frames.begin(), frames.end(), [](const VmdBoneFrame& a, const VmdBoneFrame& b) {
            return a.frame < b.frame;
        });
    }
    for (auto& [name, frames] : out->morphFrames) {
        std::sort(frames.begin(), frames.end(), [](const VmdMorphFrame& a, const VmdMorphFrame& b) {
            return a.frame < b.frame;
        });
    }

    VMD_LOG("loaded: %zu bone tracks, %zu morph tracks, max frame %d",
            out->boneFrames.size(), out->morphFrames.size(), out->maxFrame);
    return true;
}

} // namespace mmd
