// PMX (model) and VMD (motion) binary format structures + loaders for the
// NDK-native MMD renderer (Phase 4, method B of the character plan).
// Uses Bullet's LinearMath types throughout so physics integration is direct.
#pragma once

#include <LinearMath/btVector3.h>
#include <LinearMath/btQuaternion.h>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>
#include <unordered_map>

namespace mmd {

// ---------------------------------------------------------------- PMX

enum class DeformType : uint8_t { BDEF1 = 0, BDEF2 = 1, BDEF4 = 2, SDEF = 3, QDEF = 4 };

struct PmxVertex {
    btVector3 position;
    btVector3 normal;
    float uv[2];
    DeformType deform;
    int boneIndex[4];
    float boneWeight[4];    // BDEF2: [0]=w0, [1]=1-w0; BDEF4/QDEF: all four; SDEF: [0]=w
    btVector3 sdefC, sdefR0, sdefR1;
    float edgeScale;
};

struct PmxMaterial {
    std::string name;
    float diffuse[4];       // rgba; a < 1 => drawn in the transparent pass
    float specular[3];
    float shininess;
    float ambient[3];
    uint8_t flags;          // 0x01 double-sided, 0x10 edge enabled (see PMX spec)
    float edgeColor[4];
    float edgeSize;
    int textureIndex;       // -1 none
    int sphereIndex;        // -1 none
    uint8_t sphereMode;     // 0 none, 1 multiply, 2 add, 3 sub-texture (treated as add)
    uint8_t toonFlag;       // 0: toonIndex references textures[], 1: shared toon (toonIndex 0-9)
    int toonIndex;
    int indexOffset;        // first face index of this material
    int indexCount;         // number of face indices (multiple of 3)

    static constexpr uint8_t FLAG_DOUBLE_SIDED = 0x01;
    static constexpr uint8_t FLAG_EDGE = 0x10;
};

struct PmxBone {
    std::string name;
    btVector3 position;     // bind position (model space)
    int parent;             // -1 root
    uint16_t flags;
    btVector3 tailOffset;   // when (flags & FLAG_TAIL_IS_INDEX) == 0
    int grantParent;
    float grantRatio;
    btVector3 fixedAxis;

    // IK (valid when flags & FLAG_IK)
    int ikTarget;
    int ikIterations;
    float ikLimitAngle;     // radians, per-link cap
    struct IkLink {
        int index;
        bool hasLimit;
        btVector3 lowerLimit, upperLimit;
    };
    std::vector<IkLink> ikLinks;

    static constexpr uint16_t FLAG_TAIL_IS_INDEX = 0x0001;
    static constexpr uint16_t FLAG_ROTATABLE = 0x0002;
    static constexpr uint16_t FLAG_MOVABLE = 0x0004;
    static constexpr uint16_t FLAG_GRANT_ROTATION = 0x0100;
    static constexpr uint16_t FLAG_GRANT_TRANSLATION = 0x0200;
    static constexpr uint16_t FLAG_FIXED_AXIS = 0x0400;
    static constexpr uint16_t FLAG_IK = 0x0020;
};

enum class MorphType : uint8_t {
    Group = 0, Vertex = 1, Bone = 2, Uv = 3,
    ExtraUv1 = 4, ExtraUv2 = 5, ExtraUv3 = 6, ExtraUv4 = 7, ExtraUv5 = 8,
    Material = 9, Flip = 10, Impulse = 11
};

struct PmxMorph {
    std::string name;
    uint8_t panel;          // 1 brow, 2 eye, 3 mouth, 4 other
    MorphType type;
    struct VertexOffset { int vertex; btVector3 offset; };
    struct GroupOffset { int morph; float ratio; };
    std::vector<VertexOffset> vertexOffsets;
    std::vector<GroupOffset> groupOffsets;
};

struct PmxRigidBody {
    std::string name;
    int bone;               // associated bone, -1 for none
    uint8_t group;
    uint16_t collisionMask; // bits of groups this body does NOT collide with
    uint8_t shape;          // 0 sphere, 1 box, 2 capsule
    btVector3 size;         // sphere: x=radius; box: half extents; capsule: x=radius, y=length
    btVector3 position;     // offset from the bone's bind position
    btVector3 rotation;     // offset rotation (radians)
    float mass, linearDamping, angularDamping, restitution, friction;
    uint8_t mode;           // 0 kinematic (follows bone), 1 dynamic, 2 dynamic + follow bone

    static constexpr uint8_t MODE_KINEMATIC = 0;
    static constexpr uint8_t MODE_DYNAMIC = 1;
    static constexpr uint8_t MODE_DYNAMIC_FOLLOW = 2;
};

struct PmxJoint {
    std::string name;
    int bodyA, bodyB;
    btVector3 position, rotation;
    btVector3 linearLower, linearUpper;
    btVector3 angularLower, angularUpper;
    btVector3 linearSpring, angularSpring;
};

struct PmxModel {
    std::string name;
    std::string modelDir;                       // directory of the .pmx, for resolving textures
    std::vector<PmxVertex> vertices;
    std::vector<uint32_t> indices;
    std::vector<std::string> textures;          // paths as stored (relative to modelDir, usually)
    std::vector<PmxMaterial> materials;
    std::vector<PmxBone> bones;
    std::vector<PmxMorph> morphs;
    std::vector<PmxRigidBody> rigidBodies;
    std::vector<PmxJoint> joints;
    int findBone(const std::string& boneName) const;
    int findMorph(const std::string& morphName) const;
};

bool LoadPmx(const std::string& path, PmxModel* out, std::string* error);

// ---------------------------------------------------------------- VMD

struct VmdBoneFrame {
    int frame;
    btVector3 position;
    btQuaternion rotation;
    uint8_t interpolation[64];
};

struct VmdMorphFrame {
    int frame;
    float weight;
};

struct VmdMotion {
    std::unordered_map<std::string, std::vector<VmdBoneFrame>> boneFrames;   // sorted by frame
    std::unordered_map<std::string, std::vector<VmdMorphFrame>> morphFrames; // sorted by frame
    int maxFrame = 0;
};

// Transcodes a raw Shift-JIS name field (bytes, length) into UTF-8 — supplied by the caller
// because the JNI layer has Java's charset support while native code doesn't.
using VmdNameDecoder = std::function<std::string(const char*, size_t)>;

bool LoadVmd(const std::string& path, VmdMotion* out, std::string* error, const VmdNameDecoder& nameDecoder);

// Bezier interpolation curve evaluation for VMD keyframes: given the four
// control values (each 0..127 scaled to 0..1) and a progress x in [0,1],
// returns the interpolated y in [0,1].
float VmdInterpolate(float x1, float y1, float x2, float y2, float x);

} // namespace mmd
