#include "mmd_format.h"

#include <android/log.h>
#include <cstring>
#include <fstream>

#define PMX_LOG(...) __android_log_print(ANDROID_LOG_INFO, "PmxParser", __VA_ARGS__)

namespace mmd {

namespace {

struct Reader {
    const uint8_t* data;
    size_t size;
    size_t pos = 0;

    bool ok(size_t need) const { return pos + need <= size; }

    uint8_t u8() { return ok(1) ? data[pos++] : 0; }
    uint16_t u16() { uint16_t v; memcpy(&v, data + pos, 2); pos += 2; return v; }
    int32_t i32() { int32_t v; memcpy(&v, data + pos, 4); pos += 4; return v; }
    uint32_t u32() { uint32_t v; memcpy(&v, data + pos, 4); pos += 4; return v; }
    float f32() { float v; memcpy(&v, data + pos, 4); pos += 4; return v; }

    btVector3 vec3() {
        float x = f32(), y = f32(), z = f32();
        return btVector3(x, y, z);
    }
    btVector4 vec4() {
        float x = f32(), y = f32(), z = f32(), w = f32();
        return btVector4(x, y, z, w);
    }

    // Index with 1/2/4-byte storage; -1 (all bits set in the storage width) stays -1.
    int index(int byteSize) {
        switch (byteSize) {
            case 1: { uint8_t v = u8(); return v == 0xFF ? -1 : v; }
            case 2: { uint16_t v = u16(); return v == 0xFFFF ? -1 : v; }
            default: { int32_t v = i32(); return v; }
        }
    }

    void skip(size_t n) { pos = (pos + n <= size) ? pos + n : size; }
};

std::string readText(Reader& r, bool utf8) {
    int32_t len = r.i32();
    if (len <= 0 || !r.ok(static_cast<size_t>(len))) {
        if (len > 0) r.skip(static_cast<size_t>(len));
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
    out->vertices.resize(static_cast<size_t>(vertexCount));
    for (int i = 0; i < vertexCount; i++) {
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
                btVector4 w = r.vec4();
                v.boneWeight[0] = w.x(); v.boneWeight[1] = w.y();
                v.boneWeight[2] = w.z(); v.boneWeight[3] = w.w();
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
    out->indices.resize(static_cast<size_t>(faceIndexCount));
    for (int i = 0; i < faceIndexCount; i++) {
        out->indices[i] = static_cast<uint32_t>(r.index(vertexIndexSize));
    }

    // ---- textures
    int textureCount = r.i32();
    out->textures.resize(static_cast<size_t>(textureCount));
    for (int i = 0; i < textureCount; i++) {
        std::string texPath = readText(r, utf8);
        for (char& c : texPath) {
            if (c == '\\') c = '/';
        }
        out->textures[i] = texPath;
    }

    // ---- materials
    int materialCount = r.i32();
    out->materials.resize(static_cast<size_t>(materialCount));
    int runningIndexOffset = 0;
    for (int i = 0; i < materialCount; i++) {
        PmxMaterial& m = out->materials[i];
        m.name = readText(r, utf8);
        readText(r, utf8);
        btVector4 diffuse = r.vec4();
        m.diffuse[0] = diffuse.x(); m.diffuse[1] = diffuse.y();
        m.diffuse[2] = diffuse.z(); m.diffuse[3] = diffuse.w();
        btVector3 specular = r.vec3();
        m.specular[0] = specular.x(); m.specular[1] = specular.y(); m.specular[2] = specular.z();
        m.shininess = r.f32();
        btVector3 ambient = r.vec3();
        m.ambient[0] = ambient.x(); m.ambient[1] = ambient.y(); m.ambient[2] = ambient.z();
        m.flags = r.u8();
        btVector4 edgeColor = r.vec4();
        m.edgeColor[0] = edgeColor.x(); m.edgeColor[1] = edgeColor.y();
        m.edgeColor[2] = edgeColor.z(); m.edgeColor[3] = edgeColor.w();
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
        runningIndexOffset += m.indexCount;
    }

    // ---- bones
    int boneCount = r.i32();
    out->bones.resize(static_cast<size_t>(boneCount));
    for (int i = 0; i < boneCount; i++) {
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
        if (b.flags & 0x0800) { // local axes
            r.skip(24);
        }
        if (b.flags & 0x2000) { // external parent
            r.skip(4);
        }
        if (b.flags & PmxBone::FLAG_IK) {
            b.ikTarget = r.index(boneIndexSize);
            b.ikIterations = r.i32();
            b.ikLimitAngle = r.f32();
            int linkCount = r.i32();
            b.ikLinks.resize(static_cast<size_t>(linkCount));
            for (int l = 0; l < linkCount; l++) {
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
    out->morphs.resize(static_cast<size_t>(morphCount));
    for (int i = 0; i < morphCount; i++) {
        PmxMorph& m = out->morphs[i];
        m.name = readText(r, utf8);
        readText(r, utf8);
        m.panel = r.u8();
        m.type = static_cast<MorphType>(r.u8());
        int offsetCount = r.i32();
        switch (m.type) {
            case MorphType::Vertex:
                m.vertexOffsets.resize(static_cast<size_t>(offsetCount));
                for (int o = 0; o < offsetCount; o++) {
                    m.vertexOffsets[o].vertex = r.index(vertexIndexSize);
                    m.vertexOffsets[o].offset = r.vec3();
                }
                break;
            case MorphType::Group:
            case MorphType::Flip:
                m.groupOffsets.resize(static_cast<size_t>(offsetCount));
                for (int o = 0; o < offsetCount; o++) {
                    m.groupOffsets[o].morph = r.index(morphIndexSize);
                    m.groupOffsets[o].ratio = r.f32();
                }
                break;
            case MorphType::Bone:
                r.skip(static_cast<size_t>(offsetCount) * (boneIndexSize + 28));
                break;
            case MorphType::Uv:
            case MorphType::ExtraUv1:
            case MorphType::ExtraUv2:
            case MorphType::ExtraUv3:
            case MorphType::ExtraUv4:
            case MorphType::ExtraUv5:
                r.skip(static_cast<size_t>(offsetCount) * (vertexIndexSize + 16));
                break;
            case MorphType::Material:
                r.skip(static_cast<size_t>(offsetCount) * (materialIndexSize + 1 + 28 + 16 + 16 + 16));
                break;
            case MorphType::Impulse:
                for (int o = 0; o < offsetCount; o++) {
                    r.index(rigidBodyIndexSize);
                    uint8_t local = r.u8();
                    (void)local;
                    r.skip(24);
                }
                break;
        }
    }

    // ---- display frames (parse + discard)
    int frameCount = r.i32();
    for (int i = 0; i < frameCount; i++) {
        readText(r, utf8);
        readText(r, utf8);
        r.u8(); // special flag
        int elementCount = r.i32();
        for (int e = 0; e < elementCount; e++) {
            uint8_t elementType = r.u8();
            if (elementType == 0) r.skip(boneIndexSize);
            else r.skip(morphIndexSize);
        }
    }

    // ---- rigid bodies
    int bodyCount = r.i32();
    out->rigidBodies.resize(static_cast<size_t>(bodyCount));
    for (int i = 0; i < bodyCount; i++) {
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
    out->joints.resize(static_cast<size_t>(jointCount));
    for (int i = 0; i < jointCount; i++) {
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

    PMX_LOG("loaded '%s': %zu vertices, %zu indices, %zu materials, %zu bones, %zu morphs, %zu bodies, %zu joints, %zu textures",
            out->name.c_str(), out->vertices.size(), out->indices.size(), out->materials.size(),
            out->bones.size(), out->morphs.size(), out->rigidBodies.size(), out->joints.size(), out->textures.size());
    return true;
}

} // namespace mmd
