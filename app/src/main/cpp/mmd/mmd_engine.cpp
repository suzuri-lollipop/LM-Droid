#include "mmd_engine.h"

#include <LinearMath/btMatrix3x3.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>

#define MMD_LOG(...) __android_log_print(ANDROID_LOG_INFO, "MmdEngine", __VA_ARGS__)

namespace mmd {

namespace {

constexpr float kFramesPerSecond = 30.f; // MMD's timeline rate
constexpr float kPi = 3.14159265358979323846f;

// Bullet's btClamp is an in-place void helper, so scalar clamps go through this instead.
float clampf(float value, float lo, float hi) {
    return value < lo ? lo : (value > hi ? hi : value);
}

btTransform makeLocalDelta(const btVector3& animPos, const btQuaternion& animRot) {
    return btTransform(animRot, animPos);
}

// Decompose q = Rx(x)*Ry(y)*Rz(z) into XYZ euler angles (PMX's IK limit convention).
btVector3 quaternionToXyzEuler(const btQuaternion& q) {
    btMatrix3x3 m(q);
    const btVector3& r0 = m.getRow(0);
    const btVector3& r1 = m.getRow(1);
    const btVector3& r2 = m.getRow(2);
    float y = btAsin(clampf(r0.z(), -1.f, 1.f));
    float x = btAtan2(-r1.z(), r2.z());
    float z = btAtan2(-r0.y(), r0.x());
    return btVector3(x, y, z);
}

btQuaternion eulerXyzToQuaternion(const btVector3& e) {
    btQuaternion qx(btVector3(1, 0, 0), e.x());
    btQuaternion qy(btVector3(0, 1, 0), e.y());
    btQuaternion qz(btVector3(0, 0, 1), e.z());
    return qx * qy * qz;
}

// Samples a bone's VMD track at [frame]: interpolated position/rotation with per-axis
// bezier easing. Returns false when the bone has no track (stays at its bind pose).
bool sampleBoneTrack(const VmdMotion& motion, const std::string& name, float frame,
                     btVector3* outPos, btQuaternion* outRot) {
    auto it = motion.boneFrames.find(name);
    if (it == motion.boneFrames.end() || it->second.empty()) return false;
    const auto& frames = it->second;
    if (frame <= frames.front().frame) {
        *outPos = frames.front().position;
        *outRot = frames.front().rotation;
        return true;
    }
    if (frame >= frames.back().frame) {
        *outPos = frames.back().position;
        *outRot = frames.back().rotation;
        return true;
    }
    size_t hi = 0;
    while (hi < frames.size() && frames[hi].frame < frame) hi++;
    const VmdBoneFrame& a = frames[hi - 1];
    const VmdBoneFrame& b = frames[hi];
    float span = static_cast<float>(b.frame - a.frame);
    float x = span > 0 ? (frame - a.frame) / span : 0.f;
    // Interpolation byte layout (per VMD convention): for axis k (X,Y,Z,R):
    // x1 = I[k], y1 = I[k+4], x2 = I[k+8], y2 = I[k+12], each /127.
    auto curve = [&a](int k, float t) {
        return VmdInterpolate(a.interpolation[k] / 127.f, a.interpolation[k + 4] / 127.f,
                              a.interpolation[k + 8] / 127.f, a.interpolation[k + 12] / 127.f, t);
    };
    float tx = curve(0, x), ty = curve(1, x), tz = curve(2, x), tr = curve(3, x);
    *outPos = btVector3(
        a.position.x() + (b.position.x() - a.position.x()) * tx,
        a.position.y() + (b.position.y() - a.position.y()) * ty,
        a.position.z() + (b.position.z() - a.position.z()) * tz);
    *outRot = a.rotation.slerp(b.rotation, tr);
    return true;
}

float sampleMorphTrack(const VmdMotion& motion, const std::string& name, float frame) {
    auto it = motion.morphFrames.find(name);
    if (it == motion.morphFrames.end() || it->second.empty()) return 0.f;
    const auto& frames = it->second;
    if (frame <= frames.front().frame) return frames.front().weight;
    if (frame >= frames.back().frame) return frames.back().weight;
    size_t hi = 0;
    while (hi < frames.size() && frames[hi].frame < frame) hi++;
    const VmdMorphFrame& a = frames[hi - 1];
    const VmdMorphFrame& b = frames[hi];
    float span = static_cast<float>(b.frame - a.frame);
    float t = span > 0 ? (frame - a.frame) / span : 0.f;
    return a.weight + (b.weight - a.weight) * t; // VMD morphs interpolate linearly
}

} // namespace

bool MmdEngine::loadModel(const std::string& pmxPath, std::string* error) {
    loaded_ = false;
    if (!LoadPmx(pmxPath, &model_, error)) return false;

    const size_t boneCount = model_.bones.size();
    children_.assign(boneCount, {});
    for (size_t i = 0; i < boneCount; i++) {
        int parent = model_.bones[i].parent;
        if (parent >= 0 && parent < static_cast<int>(boneCount)) {
            children_[parent].push_back(static_cast<int>(i));
        }
    }

    bindWorld_.resize(boneCount);
    boneWorld_.resize(boneCount);
    noPhysicsBones_.assign(boneCount, 0);
    animRot_.assign(boneCount, btQuaternion(0, 0, 0, 1));
    animPos_.assign(boneCount, btVector3(0, 0, 0));
    morphWeights_.assign(model_.morphs.size(), 0.f);
    morphedPositions_.resize(model_.vertices.size());
    interleaved_.resize(model_.vertices.size() * 8);
    computeBindPose();

    // Bounding box of the bind pose, for camera framing.
    btVector3 minB(1e9f, 1e9f, 1e9f), maxB(-1e9f, -1e9f, -1e9f);
    for (const auto& v : model_.vertices) {
        minB.setMin(v.position);
        maxB.setMax(v.position);
    }
    boundsCenter_ = (minB + maxB) * 0.5f;
    boundsHeight_ = btMax(maxB.y() - minB.y(), 1.f);
    boundsWidth_ = btMax(maxB.x() - minB.x(), 1.f);

    mouthMorph_ = resolveMorphByName({"あ", "ア", "a", "A"});
    if (mouthMorph_ < 0) {
        for (size_t i = 0; i < model_.morphs.size(); i++) {
            if (model_.morphs[i].panel == 3 && model_.morphs[i].type == MorphType::Vertex) {
                mouthMorph_ = static_cast<int>(i);
                break;
            }
        }
    }
    blinkMorph_ = resolveMorphByName({"まばたき", "blink", "Blink", "両目"});
    if (blinkMorph_ < 0) {
        for (size_t i = 0; i < model_.morphs.size(); i++) {
            if (model_.morphs[i].panel == 2 && model_.morphs[i].type == MorphType::Vertex) {
                blinkMorph_ = static_cast<int>(i);
                break;
            }
        }
    }
    MMD_LOG("mouth morph=%d blink morph=%d", mouthMorph_, blinkMorph_);

    if (!model_.rigidBodies.empty()) {
        physics_ = std::make_unique<MmdPhysics>();
        physics_->init(model_, bindWorld_);
    }

    loaded_ = true;
    return true;
}

bool MmdEngine::loadMotion(const std::string& vmdPath, const VmdNameDecoder& decoder, std::string* error) {
    motion_ = VmdMotion();
    hasMotion_ = LoadVmd(vmdPath, &motion_, error, decoder);
    return hasMotion_;
}

int MmdEngine::resolveMorphByName(const std::vector<std::string>& candidates) const {
    for (const auto& name : candidates) {
        int idx = model_.findMorph(name);
        if (idx >= 0 && model_.morphs[idx].type == MorphType::Vertex) return idx;
    }
    return -1;
}

void MmdEngine::computeBindPose() {
    for (size_t i = 0; i < model_.bones.size(); i++) {
        const PmxBone& bone = model_.bones[i];
        if (bone.parent >= 0 && bone.parent < static_cast<int>(i)) {
            btVector3 offset = bone.position - model_.bones[bone.parent].position;
            bindWorld_[i] = bindWorld_[bone.parent] * btTransform(btQuaternion(0, 0, 0, 1), offset);
        } else {
            bindWorld_[i] = btTransform(btQuaternion(0, 0, 0, 1), bone.position);
        }
    }
}

void MmdEngine::update(float dt, CharacterState state, float mouthOpen, bool lipSync) {
    if (!loaded_) return;

    timeFrames_ += dt * kFramesPerSecond;
    float frame = timeFrames_;
    if (hasMotion_ && motion_.maxFrame > 0) {
        frame = fmodf(timeFrames_, static_cast<float>(motion_.maxFrame));
    }

    sampleAnimation(frame);
    sampleMorphWeights(frame, state, mouthOpen, lipSync, dt);
    applyMorphs();
    solveSkeleton();

    if (physics_) {
        physics_->syncKinematic(model_, boneWorld_);
        physics_->step(dt);
        std::vector<int> overridden = physics_->writeBack(model_, boneWorld_);
        // A multi-link chain (skirt/hair/breast bones with 2+ segments) has each link as its own
        // physics body AND as the bone-hierarchy child of the previous link. writeBack already
        // set every one of them correctly; recomputeSubtree must not re-derive a child that's in
        // this set from animation, or it silently throws away that link's own physics result
        // every frame — only the chain's root would ever visibly move.
        std::vector<uint8_t> physicsBones(model_.bones.size(), 0);
        for (int bone : overridden) physicsBones[bone] = 1;
        for (int bone : overridden) recomputeSubtree(bone, physicsBones);
    }

    skinVertices();
}

void MmdEngine::sampleAnimation(float frame) {
    const size_t boneCount = model_.bones.size();
    for (size_t i = 0; i < boneCount; i++) {
        btVector3 pos(0, 0, 0);
        btQuaternion rot(0, 0, 0, 1);
        if (hasMotion_) {
            sampleBoneTrack(motion_, model_.bones[i].name, frame, &pos, &rot);
        }
        if (!(model_.bones[i].flags & PmxBone::FLAG_ROTATABLE)) rot = btQuaternion(0, 0, 0, 1);
        if (!(model_.bones[i].flags & PmxBone::FLAG_MOVABLE)) pos = btVector3(0, 0, 0);
        animPos_[i] = pos;
        animRot_[i] = rot;
    }
}

void MmdEngine::sampleMorphWeights(float frame, CharacterState state, float mouthOpen, bool lipSync, float dt) {
    std::fill(morphWeights_.begin(), morphWeights_.end(), 0.f);

    if (hasMotion_) {
        for (const auto& [name, frames] : motion_.morphFrames) {
            int idx = model_.findMorph(name);
            if (idx >= 0) morphWeights_[idx] = sampleMorphTrack(motion_, name, frame);
        }
    }

    // Procedural blink: a short close-and-open every few seconds, unless the motion drives one.
    blinkTimer_ += dt;
    if (blinkTimer_ > 3.2f) blinkTimer_ = 0.f;
    const float blinkDuration = 0.16f;
    float blink = 0.f;
    if (blinkTimer_ < blinkDuration) {
        blink = sinf(kPi * (blinkTimer_ / blinkDuration));
    }
    blinkValue_ = blink;
    if (blinkMorph_ >= 0) {
        morphWeights_[blinkMorph_] = btMax(morphWeights_[blinkMorph_], blink);
    }

    // Procedural lip sync: oscillate the mouth morph while the overlay speaks; the VMD's own
    // mouth track wins when it's wider open than the oscillator.
    if (state == CharacterState::Speaking && lipSync && mouthMorph_ >= 0) {
        float oscillation = 0.55f + 0.45f * sinf(timeFrames_ * 1.05f);
        float weight = mouthOpen * oscillation;
        morphWeights_[mouthMorph_] = btMax(morphWeights_[mouthMorph_], weight);
    }
}

void MmdEngine::applyMorphs() {
    // Effective weights start from the sampled ones; group/flip morphs redistribute into others.
    std::vector<float> effective = morphWeights_;
    for (size_t m = 0; m < model_.morphs.size(); m++) {
        const PmxMorph& morph = model_.morphs[m];
        float weight = morphWeights_[m];
        if (weight == 0.f) continue;
        if (morph.type == MorphType::Group || morph.type == MorphType::Flip) {
            for (const auto& offset : morph.groupOffsets) {
                if (offset.morph >= 0 && offset.morph < static_cast<int>(effective.size())) {
                    effective[offset.morph] += weight * offset.ratio;
                }
            }
            effective[m] = 0.f; // the container itself has no geometry
        }
    }

    for (size_t i = 0; i < model_.vertices.size(); i++) {
        morphedPositions_[i] = model_.vertices[i].position;
    }
    for (size_t m = 0; m < model_.morphs.size(); m++) {
        const PmxMorph& morph = model_.morphs[m];
        if (morph.type != MorphType::Vertex || effective[m] == 0.f) continue;
        for (const auto& offset : morph.vertexOffsets) {
            if (offset.vertex >= 0 && offset.vertex < static_cast<int>(morphedPositions_.size())) {
                morphedPositions_[offset.vertex] += offset.offset * effective[m];
            }
        }
    }
}

void MmdEngine::solveSkeleton() {
    const size_t boneCount = model_.bones.size();
    std::vector<btTransform> delta(boneCount);

    // Base pass. PMX orders parents before children in practice, so one index-order sweep
    // resolves the hierarchy; grant bones pick up their parent's already-computed delta.
    for (size_t i = 0; i < boneCount; i++) {
        const PmxBone& bone = model_.bones[i];
        btQuaternion rot = animRot_[i];
        btVector3 pos = animPos_[i];
        if ((bone.flags & (PmxBone::FLAG_GRANT_ROTATION | PmxBone::FLAG_GRANT_TRANSLATION)) &&
            bone.grantParent >= 0 && bone.grantParent < static_cast<int>(boneCount)) {
            if (bone.flags & PmxBone::FLAG_GRANT_ROTATION) {
                rot = rot.slerp(rot * delta[bone.grantParent].getRotation(), bone.grantRatio);
            }
            if (bone.flags & PmxBone::FLAG_GRANT_TRANSLATION) {
                pos += delta[bone.grantParent].getOrigin() * bone.grantRatio;
            }
        }
        delta[i] = makeLocalDelta(pos, rot);
        if (bone.parent >= 0 && bone.parent < static_cast<int>(boneCount)) {
            btVector3 offset = bone.position - model_.bones[bone.parent].position;
            boneWorld_[i] = boneWorld_[bone.parent] * btTransform(btQuaternion(0, 0, 0, 1), offset) * delta[i];
        } else {
            boneWorld_[i] = btTransform(btQuaternion(0, 0, 0, 1), bone.position) * delta[i];
        }
    }

    // IK pass — leg/foot chains mostly, resolved after the base pose.
    for (size_t i = 0; i < boneCount; i++) {
        if (model_.bones[i].flags & PmxBone::FLAG_IK) {
            solveIk(static_cast<int>(i));
        }
    }
}

void MmdEngine::solveIk(int ikBone) {
    const PmxBone& ik = model_.bones[ikBone];
    if (ik.ikTarget < 0 || ik.ikTarget >= static_cast<int>(model_.bones.size())) return;

    for (int iter = 0; iter < btMax(ik.ikIterations, 1); iter++) {
        const btVector3 target = boneWorld_[ikBone].getOrigin();
        bool converged = true;
        for (const auto& link : ik.ikLinks) {
            if (link.index < 0 || link.index >= static_cast<int>(boneWorld_.size())) continue;
            const btVector3 effectorPos = boneWorld_[ik.ikTarget].getOrigin();
            const btVector3 linkOrigin = boneWorld_[link.index].getOrigin();
            btVector3 toEffector = effectorPos - linkOrigin;
            btVector3 toTarget = target - linkOrigin;
            if (toEffector.length2() < 1e-8f || toTarget.length2() < 1e-8f) continue;
            toEffector.normalize();
            toTarget.normalize();
            float angle = btAcos(clampf(toEffector.dot(toTarget), -1.f, 1.f));
            if (angle < 1e-4f) continue;
            converged = false;
            angle = btMin(angle, btMax(ik.ikLimitAngle, 0.01f));
            btVector3 axis = toEffector.cross(toTarget);
            if (axis.length2() < 1e-12f) continue;
            axis.normalize();

            // Candidate rotation applied in world space around the link's origin.
            btQuaternion worldRot = btQuaternion(axis, angle) * boneWorld_[link.index].getRotation();

            if (link.hasLimit) {
                // Clamp the link's total local rotation to its PMX euler box.
                int parent = model_.bones[link.index].parent;
                btQuaternion parentRot = (parent >= 0 && parent < static_cast<int>(boneWorld_.size()))
                                             ? boneWorld_[parent].getRotation()
                                             : btQuaternion(0, 0, 0, 1);
                btVector3 euler = quaternionToXyzEuler(parentRot.inverse() * worldRot);
                euler.setX(clampf(euler.x(), link.lowerLimit.x(), link.upperLimit.x()));
                euler.setY(clampf(euler.y(), link.lowerLimit.y(), link.upperLimit.y()));
                euler.setZ(clampf(euler.z(), link.lowerLimit.z(), link.upperLimit.z()));
                worldRot = parentRot * eulerXyzToQuaternion(euler);
            }

            boneWorld_[link.index].setRotation(worldRot.normalize());
            recomputeSubtree(link.index, noPhysicsBones_);
        }
        if (converged) break;
    }
}

void MmdEngine::recomputeSubtree(int bone, const std::vector<uint8_t>& physicsBones) {
    for (int child : children_[bone]) {
        // This child has its own physics body and writeBack already gave it the correct
        // transform this frame — leave it alone, just recurse to carry it to non-physics
        // descendants (e.g. a chain's second link still needs to reposition a third that has
        // no rigid body of its own).
        if (!physicsBones[child]) {
            const PmxBone& childBone = model_.bones[child];
            btVector3 offset = childBone.position - model_.bones[bone].position;
            btQuaternion rot(0, 0, 0, 1);
            btVector3 pos(0, 0, 0);
            // Re-derive the child's local delta from its (possibly granted) animation sample.
            // Re-running the grant logic here would risk cycles, so grants keep their base sample.
            rot = animRot_[child];
            pos = animPos_[child];
            if (!(childBone.flags & PmxBone::FLAG_ROTATABLE)) rot = btQuaternion(0, 0, 0, 1);
            if (!(childBone.flags & PmxBone::FLAG_MOVABLE)) pos = btVector3(0, 0, 0);
            boneWorld_[child] = boneWorld_[bone] * btTransform(btQuaternion(0, 0, 0, 1), offset) *
                                makeLocalDelta(pos, rot);
        }
        recomputeSubtree(child, physicsBones);
    }
}

void MmdEngine::skinVertices() {
    const size_t boneCount = model_.bones.size();
    // Skinning matrices: current world relative to the bind pose.
    std::vector<btTransform> skin(boneCount);
    for (size_t i = 0; i < boneCount; i++) {
        skin[i] = boneWorld_[i] * bindWorld_[i].inverse();
    }

    float* out = interleaved_.data();
    for (size_t i = 0; i < model_.vertices.size(); i++) {
        const PmxVertex& v = model_.vertices[i];
        const btVector3 p = morphedPositions_[i];
        btVector3 skinnedPos(0, 0, 0);
        btVector3 skinnedNormal(0, 0, 0);

        auto valid = [&](int idx) { return idx >= 0 && idx < static_cast<int>(boneCount); };

        switch (v.deform) {
            case DeformType::BDEF1: {
                int b = valid(v.boneIndex[0]) ? v.boneIndex[0] : 0;
                skinnedPos = skin[b](p);
                skinnedNormal = quatRotate(skin[b].getRotation(), v.normal);
                break;
            }
            case DeformType::BDEF2:
            case DeformType::BDEF4:
            case DeformType::QDEF: {
                for (int k = 0; k < ((v.deform == DeformType::BDEF2) ? 2 : 4); k++) {
                    float w = v.boneWeight[k];
                    if (w <= 0.f || !valid(v.boneIndex[k])) continue;
                    skinnedPos += skin[v.boneIndex[k]](p) * w;
                    skinnedNormal += quatRotate(skin[v.boneIndex[k]].getRotation(), v.normal) * w;
                }
                break;
            }
            case DeformType::SDEF: {
                int b0 = valid(v.boneIndex[0]) ? v.boneIndex[0] : 0;
                int b1 = valid(v.boneIndex[1]) ? v.boneIndex[1] : b0;
                float w = v.boneWeight[0];
                btVector3 c0 = skin[b0](v.sdefC);
                btVector3 c1 = skin[b1](v.sdefC);
                btVector3 center = c0 * w + c1 * (1.f - w);
                btQuaternion rot = skin[b1].getRotation().slerp(skin[b0].getRotation(), w);
                skinnedPos = center + quatRotate(rot, p - v.sdefC);
                skinnedNormal = quatRotate(rot, v.normal);
                break;
            }
        }

        skinnedNormal.safeNormalize();
        out[0] = skinnedPos.x(); out[1] = skinnedPos.y(); out[2] = skinnedPos.z();
        out[3] = skinnedNormal.x(); out[4] = skinnedNormal.y(); out[5] = skinnedNormal.z();
        out[6] = v.uv[0]; out[7] = v.uv[1];
        out += 8;
    }
}

} // namespace mmd
