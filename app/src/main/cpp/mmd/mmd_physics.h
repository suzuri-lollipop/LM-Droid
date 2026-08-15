// Bullet-backed PMX rigid body / joint simulation. Kinematic bodies follow their bones;
// dynamic ones simulate and write their transform back over the bone each frame (hair,
// skirts, accessories). Ground plane included so dynamic parts can't fall through the floor.
#pragma once

#include "mmd_format.h"

#include <BulletDynamics/Dynamics/btDiscreteDynamicsWorld.h>
#include <memory>
#include <vector>

class btRigidBody;
class btCollisionShape;
class btTypedConstraint;
class btDefaultCollisionConfiguration;
class btCollisionDispatcher;
class btDbvtBroadphase;
class btSequentialImpulseConstraintSolver;

namespace mmd {

class MmdPhysics {
public:
    MmdPhysics();
    ~MmdPhysics();

    // [boneBindWorld] is each bone's bind-pose world transform (MmdEngine's bindWorld_, valid
    // after computeBindPose()) — needed to convert each rigid body's PMX-absolute bind position
    // into an offset relative to its bone, since syncKinematic recomposes bone * offset every
    // frame as the bone moves.
    void init(const PmxModel& model, const std::vector<btTransform>& boneBindWorld);
    void syncKinematic(const PmxModel& model, const std::vector<btTransform>& boneWorld);
    void step(float dt);
    // Overrides dynamic bodies' bones in [boneWorld] and returns which bones changed,
    // so the caller can recompute their descendant subtrees.
    std::vector<int> writeBack(const PmxModel& model, std::vector<btTransform>& boneWorld);

private:
    std::unique_ptr<btDefaultCollisionConfiguration> collisionConfig_;
    std::unique_ptr<btCollisionDispatcher> dispatcher_;
    std::unique_ptr<btDbvtBroadphase> broadphase_;
    std::unique_ptr<btSequentialImpulseConstraintSolver> solver_;
    std::unique_ptr<btDiscreteDynamicsWorld> world_;

    std::vector<std::unique_ptr<btCollisionShape>> shapes_;
    std::vector<std::unique_ptr<btRigidBody>> bodies_;
    std::vector<std::unique_ptr<btTypedConstraint>> constraints_;
    std::vector<btTransform> bodyOffsets_; // body transform = boneWorld * offset
    std::vector<uint8_t> modes_;

    std::unique_ptr<btCollisionShape> groundShape_;
    std::unique_ptr<btRigidBody> ground_;
};

} // namespace mmd
