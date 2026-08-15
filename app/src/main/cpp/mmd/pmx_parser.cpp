#include "mmd_format.h"

#include <android/log.h>
#include <cstring>
#include <fstream>

#define PMX_LOG(...) __android_log_print(ANDROID_LOG_INFO, "PmxParser", __VA_ARGS__)

namespace mmd {

namespace {

// Upper bounds used to reject structurally broken files early instead of allocating gigabytes
// from garbage counts. Generous enough for the largest models in common circulation.
constexpr int kMaxVertices = 5'000'000;
constexpr int kMaxIndices = 30'000'000;
constexpr int kMaxElements = 1'000'000;

// Every read is bounds-checked: a malformed/truncated PMX must surface as a load error, never
// as an out-of-bounds read (that crashed the GL thread — see the LoadPmx SIGSEGV fix).
struct Reader {
    const uint8_t* data;
    size_t size;
    size_t pos = 0;
    bool overflow = false;

    bool ok(size_t need) const { return pos + need <= size; }

    uint8_t u8() {
        if (!ok(1)) { overflow = true; return 0; }
        return data[pos++];
    }
    uint16_t u16() {
        if (!ok(2)) { overflow = true; return 0; }
        uint16_t v; memcpy(&v, data + pos, 2); pos += 2; return v;
    }
    int32_t i32() {
        if (!ok(4)) { overflow = true; return 0; }
        int32_t v; memcpy(&v, data + pos, 4); pos += 4; return v;
    }
    float f32() {
        if (!ok(4)) { overflow = true; return 0.f; }
        float v; memcpy(&v, data + pos, 4); pos += 4; return v;
    }

    btVector3 vec3() {
        float x = f32(), y = f32(), z = f32();
        return btVector3(x, y, z);
    }

    // Index with 1/2/4-byte storage; -1 (all bits set in the storage width) stays -1.
    int index(int byteSize) {
        switch (byteSize) {
            case 1: { uint8_t v = u8(); return v == 0xFF ? -1 : v; }
            case 2: { uint16_t v = u16(); return v == 0xFFFF ? -1 : v; }
            default: return i32();
        }
    }

    void skip(size_t n) {
        if (!ok(n)) { overflow = true; pos = size; return; }
        pos += n;
    }
};

std::string readText(Reader& r, bool utf8) {
    int32_t len = r.i32();
    if (len < 0 || !r.ok(static_cast<size_t>(len))) {
        r.overflow = true;
        return {};
    }
    if (utf8) {
        std::string s(reinterpret_cast<const char*>(r.data + r.pos), static_cast<size_t>(len));
        r.pos += static_cast<size_t>(len);
        return s;
    }
    // UTF-16LE → naive BMP-only UTF-8 conversion (model names/paths in the wild are BMP).
    std::string out;
    for (int32_t i = 0; i + 1 < len; i += 2) {
        uint16_t c = static_cast<uint16_t>(r.data[r.pos + i] | (r.data[r.pos + i + 1] << 8));
        if (c < 0x80) {
            out += static_cast<char>(c);
        } else if (c < 0x800) {
            out += static_cast<char>(0xC0 | (c >> 6));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else {
            out += static_cast<char>(0xE0 | (c >> 12));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    r.pos += static_cast<size_t>(len);
    return out;
}

bool saneCount(int count, int ceiling) {
    return count >= 0 && count <= ceiling;
}

} // namespace

int PmxModel::findBone(const std::string& boneName) const {
    for (size_t i = 0; i < bones.size(); i++) {
        if (bones[i].name == boneName) return static_cast<int>(i);
    }
    return -1;
}

int PmxModel::findMorph(const std::string& morphName) const {
    for (size_t i = 0; i < morphs.size(); i++) {
        if (morphs[i].name == morphName) return static_cast<int>(i);
    }
    return -1;
}

bool LoadPmx(const std::string& path, PmxModel* out, std::string* error) {
    std::ifstream file(path, std::ios::binary);
    if (!file) {
        *error = "cannot open " + path;
        return false;
    }
    std::vector<uint8_t> buffer((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    Reader r{buffer.data(), buffer.size()};
    if (!r.ok(4) || memcmp(r.data, "PMX ", 4) != 0) {
        *error = "not a PMX file (bad magic)";
        return false;
    }
    r.pos = 4;
    float version = r.f32();
    if (version < 1.9f || version > 2.2f) {
        *error = "unsupported PMX version";
        return false;
    }

    const auto dirPos = path.find_last_of("/\\");
    out->modelDir = (dirPos == std::string::npos) ? "" : path.substr(0, dirPos + 1);

    uint8_t globalsCount = r.u8();
    uint8_t encoding = r.u8();      // 1 = UTF-8, 0 = UTF-16LE
    const bool utf8 = encoding != 0;
    int additionalVec4 = r.u8();
    int vertexIndexSize = r.u8();
    int textureIndexSize = r.u8();
    int materialIndexSize = r.u8();
    int boneIndexSize = r.u8();
    int morphIndexSize = r.u8();
    int rigidBodyIndexSize = r.u8();
    (void)materialIndexSize;
    (void)morphIndexSize;
    // Old 2.0 files store 8 globals; skip any extras defensively.
    if (globalsCount > 8) r.skip(globalsCount - 8);

    out->name = readText(r, utf8);
    readText(r, utf8);  // english name
    readText(r, utf8);  // comment
    readText(r, utf8);  // english comment

    // ---- vertices
    int vertexCount = r.i32();
    if (!saneCount(vertexCount, kMaxVertices)) {
        *error = "invalid vertex count";
        return false;
    }
    out->vertices.resize(static_cast<size_t>(vertexCount));
    for (int i = 0; i < vertexCount && !r.overflow; i++) {
        PmxVertex& v = out->vertices[i];
        v.position = r.vec3();
        v.normal = r.vec3();
        v.uv[0] = r.f32();
        v.uv[1] = r.f32();
        for (int a = 0; a < additionalVec4; a++) r.skip(16);
        v.deform = static_cast<DeformType>(r.u8());
        for (int b = 0; b < 4; b++) { v.boneIndex[b] = -1; v.boneWeight[b] = 0.f; }
        switch (v.deform) {
            case DeformType::BDEF1:
                v.boneIndex[0] = r.index(boneIndexSize);
                v.boneWeight[0] = 1.f;
                break;
            case DeformType::BDEF2: {
                v.boneIndex[0] = r.index(boneIndexSize);
                v.boneIndex[1] = r.index(boneIndexSize);
                float w = r.f32();
                v.boneWeight[0] = w;
                v.boneWeight[1] = 1.f - w;
                break;
            }
            case DeformType::BDEF4:
            case DeformType::QDEF: {
                for (int b = 0; b < 4; b++) v.boneIndex[b] = r.index(boneIndexSize);
                float wx = r.f32(), wy = r.f32(), wz = r.f32(), ww = r.f32();
                v.boneWeight[0] = wx; v.boneWeight[1] = wy;
                v.boneWeight[2] = wz; v.boneWeight[3] = ww;
                break;
            }
            case DeformType::SDEF: {
                v.boneIndex[0] = r.index(boneIndexSize);
                v.boneIndex[1] = r.index(boneIndexSize);
                float w = r.f32();
                v.boneWeight[0] = w;
                v.boneWeight[1] = 1.f - w;
                v.sdefC = r.vec3();
                v.sdefR0 = r.vec3();
                v.sdefR1 = r.vec3();
                break;
            }
        }
        v.edgeScale = r.f32();
    }

    // ---- faces
    int faceIndexCount = r.i32();
    if (!saneCount(faceIndexCount, kMaxIndices) || faceIndexCount % 3 != 0) {
        *error = "invalid face index count";
        return false;
    }
    out->indices.resize(static_cast<size_t>(faceIndexCount));
    bool badIndex = false;
    for (int i = 0; i < faceIndexCount && !r.overflow; i++) {
        int idx = r.index(vertexIndexSize);
        if (idx < 0 || idx >= vertexCount) badIndex = true;
        out->indices[i] = static_cast<uint32_t>(idx < 0 ? 0 : idx);
    }
    if (badIndex) {
        *error = "face index references out-of-range vertex";
        return false;
    }

    // ---- textures
    int textureCount = r.i32();
    if (!saneCount(textureCount, kMaxElements)) {
        *error = "invalid texture count";
        return false;
    }
    out->textures.resize(static_cast<size_t>(textureCount));
    for (int i = 0; i < textureCount && !r.overflow; i++) {
        std::string texPath = readText(r, utf8);
        for (char& c : texPath) {
            if (c == '\\') c = '/';
        }
        out->textures[i] = texPath;
    }

    // ---- materials
    int materialCount = r.i32();
    if (!saneCount(materialCount, kMaxElements)) {
        *error = "invalid material count";
        return false;
    }
    out->materials.resize(static_cast<size_t>(materialCount));
    int runningIndexOffset = 0;
    for (int i = 0; i < materialCount && !r.overflow; i++) {
        PmxMaterial& m = out->materials[i];
        m.name = readText(r, utf8);
        readText(r, utf8);
        m.diffuse[0] = r.f32(); m.diffuse[1] = r.f32();
        m.diffuse[2] = r.f32(); m.diffuse[3] = r.f32();
        m.specular[0] = r.f32(); m.specular[1] = r.f32(); m.specular[2] = r.f32();
        m.shininess = r.f32();
        m.ambient[0] = r.f32(); m.ambient[1] = r.f32(); m.ambient[2] = r.f32();
        m.flags = r.u8();
        m.edgeColor[0] = r.f32(); m.edgeColor[1] = r.f32();
        m.edgeColor[2] = r.f32(); m.edgeColor[3] = r.f32();
        m.edgeSize = r.f32();
        m.textureIndex = r.index(textureIndexSize);
        m.sphereIndex = r.index(textureIndexSize);
        m.sphereMode = r.u8();
        m.toonFlag = r.u8();
        if (m.toonFlag == 0) {
            m.toonIndex = r.index(textureIndexSize);
        } else {
            m.toonIndex = r.u8(); // shared toon 0-9
        }
        readText(r, utf8); // comment
        m.indexCount = r.i32();
        m.indexOffset = runningIndexOffset;
        if (m.indexCount < 0 || runningIndexOffset + m.indexCount > faceIndexCount) {
            *error = "material face range exceeds index buffer";
            return false;
        }
        runningIndexOffset += m.indexCount;
    }

    // ---- bones
    int boneCount = r.i32();
    if (!saneCount(boneCount, kMaxElements)) {
        *error = "invalid bone count";
        return false;
    }
    out->bones.resize(static_cast<size_t>(boneCount));
    for (int i = 0; i < boneCount && !r.overflow; i++) {
        PmxBone& b = out->bones[i];
        b.name = readText(r, utf8);
        readText(r, utf8);
        b.position = r.vec3();
        b.parent = r.index(boneIndexSize);
        r.skip(4); // layer
        b.flags = r.u16();
        b.grantParent = -1;
        b.grantRatio = 0.f;
        b.ikTarget = -1;
        b.ikIterations = 0;
        b.ikLimitAngle = 0.f;
        if (b.flags & PmxBone::FLAG_TAIL_IS_INDEX) {
            b.tailOffset = btVector3(0, 0, 0);
            r.index(boneIndexSize);
        } else {
            b.tailOffset = r.vec3();
        }
        if (b.flags & (PmxBone::FLAG_GRANT_ROTATION | PmxBone::FLAG_GRANT_TRANSLATION)) {
            b.grantParent = r.index(boneIndexSize);
            b.grantRatio = r.f32();
        }
        if (b.flags & PmxBone::FLAG_FIXED_AXIS) {
            b.fixedAxis = r.vec3();
        }
        if (b.flags & PmxBone::FLAG_LOCAL_AXES) {
            r.skip(24); // local X axis + Z axis, 2 x vec3 (Y is derived via cross product)
        }
        if (b.flags & PmxBone::FLAG_EXTERNAL_PARENT) {
            r.skip(4);
        }
        if (b.flags & PmxBone::FLAG_IK) {
            b.ikTarget = r.index(boneIndexSize);
            b.ikIterations = r.i32();
            b.ikLimitAngle = r.f32();
            int linkCount = r.i32();
            if (!saneCount(linkCount, kMaxElements)) {
                *error = "invalid IK link count";
                return false;
            }
            b.ikLinks.resize(static_cast<size_t>(linkCount));
            for (int l = 0; l < linkCount && !r.overflow; l++) {
                PmxBone::IkLink& link = b.ikLinks[l];
                link.index = r.index(boneIndexSize);
                link.hasLimit = r.u8() != 0;
                if (link.hasLimit) {
                    link.lowerLimit = r.vec3();
                    link.upperLimit = r.vec3();
                } else {
                    link.lowerLimit = link.upperLimit = btVector3(0, 0, 0);
                }
            }
        }
    }

    // ---- morphs
    int morphCount = r.i32();
    if (!saneCount(morphCount, kMaxElements)) {
        *error = "invalid morph count";
        return false;
    }
    out->morphs.resize(static_cast<size_t>(morphCount));
    for (int i = 0; i < morphCount && !r.overflow; i++) {
        PmxMorph& m = out->morphs[i];
        m.name = readText(r, utf8);
        readText(r, utf8);
        m.panel = r.u8();
        m.type = static_cast<MorphType>(r.u8());
        int offsetCount = r.i32();
        if (!saneCount(offsetCount, kMaxVertices)) {
            *error = "invalid morph offset count";
            return false;
        }
        switch (m.type) {
            case MorphType::Vertex:
                m.vertexOffsets.resize(static_cast<size_t>(offsetCount));
                for (int o = 0; o < offsetCount && !r.overflow; o++) {
                    int vertex = r.index(vertexIndexSize);
                    m.vertexOffsets[o].vertex = vertex;
                    m.vertexOffsets[o].offset = r.vec3();
                    if (vertex < 0 || vertex >= vertexCount) {
                        *error = "vertex morph references out-of-range vertex";
                        return false;
                    }
                }
                break;
            case MorphType::Group:
            case MorphType::Flip:
                m.groupOffsets.resize(static_cast<size_t>(offsetCount));
                for (int o = 0; o < offsetCount && !r.overflow; o++) {
                    m.groupOffsets[o].morph = r.index(morphIndexSize);
                    m.groupOffsets[o].ratio = r.f32();
                }
                break;
            case MorphType::Bone:
                r.skip(static_cast<size_t>(offsetCount) * (static_cast<size_t>(boneIndexSize) + 28));
                break;
            case MorphType::Uv:
            case MorphType::ExtraUv1:
            case MorphType::ExtraUv2:
            case MorphType::ExtraUv3:
            case MorphType::ExtraUv4:
                r.skip(static_cast<size_t>(offsetCount) * (static_cast<size_t>(vertexIndexSize) + 16));
                break;
            case MorphType::Material:
                // materialIndex + op(1) + diffuse(16) + specular(12) + shininess(4) +
                // ambient(12) + edgeColor(16) + edgeSize(4) + texture(16) + sphere(16) + toon(16)
                r.skip(static_cast<size_t>(offsetCount) * (static_cast<size_t>(materialIndexSize) + 113));
                break;
            case MorphType::Impulse:
                for (int o = 0; o < offsetCount && !r.overflow; o++) {
                    r.index(rigidBodyIndexSize);
                    r.u8(); // local flag
                    r.skip(24);
                }
                break;
        }
    }

    // ---- display frames (parse + discard)
    int frameCount = r.i32();
    if (!saneCount(frameCount, kMaxElements)) {
        *error = "invalid display frame count";
        return false;
    }
    for (int i = 0; i < frameCount && !r.overflow; i++) {
        readText(r, utf8);
        readText(r, utf8);
        r.u8(); // special flag
        int elementCount = r.i32();
        if (!saneCount(elementCount, kMaxElements)) {
            *error = "invalid display frame element count";
            return false;
        }
        for (int e = 0; e < elementCount && !r.overflow; e++) {
            uint8_t elementType = r.u8();
            if (elementType == 0) r.skip(boneIndexSize);
            else r.skip(morphIndexSize);
        }
    }

    // ---- rigid bodies
    int bodyCount = r.i32();
    if (!saneCount(bodyCount, kMaxElements)) {
        *error = "invalid rigid body count";
        return false;
    }
    out->rigidBodies.resize(static_cast<size_t>(bodyCount));
    for (int i = 0; i < bodyCount && !r.overflow; i++) {
        PmxRigidBody& rb = out->rigidBodies[i];
        rb.name = readText(r, utf8);
        readText(r, utf8);
        rb.bone = r.index(boneIndexSize);
        rb.group = r.u8();
        rb.collisionMask = r.u16();
        rb.shape = r.u8();
        rb.size = r.vec3();
        rb.position = r.vec3();
        rb.rotation = r.vec3();
        rb.mass = r.f32();
        rb.linearDamping = r.f32();
        rb.angularDamping = r.f32();
        rb.restitution = r.f32();
        rb.friction = r.f32();
        rb.mode = r.u8();
    }

    // ---- joints
    int jointCount = r.i32();
    if (!saneCount(jointCount, kMaxElements)) {
        *error = "invalid joint count";
        return false;
    }
    out->joints.resize(static_cast<size_t>(jointCount));
    for (int i = 0; i < jointCount && !r.overflow; i++) {
        PmxJoint& j = out->joints[i];
        j.name = readText(r, utf8);
        readText(r, utf8);
        r.u8(); // type (0: spring 6DOF; others are PMX2.1 extensions we don't simulate)
        j.bodyA = r.index(rigidBodyIndexSize);
        j.bodyB = r.index(rigidBodyIndexSize);
        j.position = r.vec3();
        j.rotation = r.vec3();
        j.linearLower = r.vec3();
        j.linearUpper = r.vec3();
        j.angularLower = r.vec3();
        j.angularUpper = r.vec3();
        j.linearSpring = r.vec3();
        j.angularSpring = r.vec3();
    }

    if (r.overflow) {
        *error = "unexpected end of file (truncated or malformed PMX)";
        return false;
    }

    PMX_LOG("loaded '%s': %zu vertices, %zu indices, %zu materials, %zu bones, %zu morphs, %zu bodies, %zu joints, %zu textures",
            out->name.c_str(), out->vertices.size(), out->indices.size(), out->materials.size(),
            out->bones.size(), out->morphs.size(), out->rigidBodies.size(), out->joints.size(), out->textures.size());
    return true;
}

} // namespace mmd
