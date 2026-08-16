// Per-frame MMD evaluation: VMD sampling, procedural face params (blink / lip sync),
// bone hierarchy + grant + CCD-IK solving, Bullet physics and CPU skinning into one
// interleaved float buffer the renderer uploads. Runs on the GL thread (see mmd_jni).
#pragma once

#include "mmd_format.h"
#include "mmd_physics.h"

#include <memory>
#include <vector>

namespace mmd {

// Mirrors the Kotlin CharacterUiState ordinal (Idle, Listening, Thinking, Speaking, Error).
enum class CharacterState : int { Idle = 0, Listening = 1, Thinking = 2, Speaking = 3, Error = 4 };

class MmdEngine {
public:
    bool loadModel(const std::string& pmxPath, std::string* error);
    bool loadMotion(const std::string& vmdPath, const VmdNameDecoder& decoder, std::string* error);

    // Advances animation + physics and re-skins. [characterState]/[mouthOpen]/[lipSync] come
    // from the overlay — mouthOpen is the current TTS audio's amplitude envelope (0..1, silence
    // to loudest), resampled by the caller from the playing clip's actual waveform, not a fixed
    // shape — so the mouth tracks what's actually being said instead of a canned loop.
    void update(float dt, CharacterState state, float mouthOpen, bool lipSync);

    const PmxModel& model() const { return model_; }
    bool hasModel() const { return loaded_; }

    // Interleaved pos3/normal3/uv2, vertexCount() * 8 floats long.
    const float* skinnedVertices() const { return interleaved_.data(); }
    int vertexCount() const { return static_cast<int>(model_.vertices.size()); }

    // Bounding info computed at load, for camera framing.
    btVector3 boundsCenter() const { return boundsCenter_; }
    float boundsHeight() const { return boundsHeight_; }
    float boundsWidth() const { return boundsWidth_; }

private:
    void computeBindPose();
    void sampleAnimation(float frame);
    void sampleMorphWeights(float frame, CharacterState state, float mouthOpen, bool lipSync, float dt);
    void applyMorphs();
    void solveSkeleton();
    void solveIk(int ikBone);
    // [physicsBones] marks bones writeBack just set this frame — recomputeSubtree must not
    // stomp those with an animation-derived guess, only propagate through non-physics ones.
    void recomputeSubtree(int bone, const std::vector<uint8_t>& physicsBones);
    void skinVertices();
    int resolveMorphByName(const std::vector<std::string>& candidates) const;

    PmxModel model_;
    VmdMotion motion_;
    bool loaded_ = false;
    bool hasMotion_ = false;

    std::vector<std::vector<int>> children_;
    // All-zero, sized to bones.size(): physics hasn't run yet during IK solving, so nothing is
    // protected from recomputeSubtree's animation-derived overwrite at that point.
    std::vector<uint8_t> noPhysicsBones_;
    std::vector<btTransform> bindWorld_;
    std::vector<btTransform> boneWorld_;
    std::vector<btQuaternion> animRot_;
    std::vector<btVector3> animPos_;
    std::vector<float> morphWeights_;
    std::vector<btVector3> morphedPositions_;
    std::vector<float> interleaved_;

    int mouthMorph_ = -1;
    int blinkMorph_ = -1;

    float timeFrames_ = 0.f;
    float blinkTimer_ = 0.f;
    float blinkValue_ = 0.f;
    // Smoothed toward the caller's mouthOpen sample each frame (see sampleMorphWeights) — the
    // Kotlin side updates that sample far slower than this runs, so smoothing here is what turns
    // a staircase of envelope buckets into continuous mouth motion.
    float mouthOpenSmoothed_ = 0.f;

    btVector3 boundsCenter_{0, 10, 0};
    float boundsHeight_ = 20.f;
    float boundsWidth_ = 20.f;

    std::unique_ptr<MmdPhysics> physics_;
};

} // namespace mmd
