#include "mmd_physics.h"

#include <BulletCollision/BroadphaseCollision/btDbvtBroadphase.h>
#include <BulletCollision/CollisionDispatch/btCollisionDispatcher.h>
#include <BulletCollision/CollisionDispatch/btDefaultCollisionConfiguration.h>
#include <BulletCollision/CollisionShapes/btBoxShape.h>
#include <BulletCollision/CollisionShapes/btCapsuleShape.h>
#include <BulletCollision/CollisionShapes/btSphereShape.h>
#include <BulletCollision/CollisionShapes/btStaticPlaneShape.h>
#include <BulletDynamics/ConstraintSolver/btGeneric6DofSpringConstraint.h>
#include <BulletDynamics/ConstraintSolver/btSequentialImpulseConstraintSolver.h>
#include <BulletDynamics/Dynamics/btRigidBody.h>
#include <android/log.h>

#define PHYS_LOG(...) __android_log_print(ANDROID_LOG_INFO, "MmdPhysics", __VA_ARGS__)

namespace mmd {

namespace {

btTransform offsetTransform(const btVector3& position, const btVector3& euler) {
    btQuaternion rot;
    rot.setEulerZYX(euler.z(), euler.y(), euler.x());
    return btTransform(rot, position);
}

} // namespace

MmdPhysics::MmdPhysics() = default;

MmdPhysics::~MmdPhysics() {
    constraints_.clear();
    bodies_.clear();
    shapes_.clear();
}

void MmdPhysics::init(const PmxModel& model, const std::vector<btTransform>& boneBindWorld) {
    collisionConfig_ = std::make_unique<btDefaultCollisionConfiguration>();
    dispatcher_ = std::make_unique<btCollisionDispatcher>(collisionConfig_.get());
    broadphase_ = std::make_unique<btDbvtBroadphase>();
    solver_ = std::make_unique<btSequentialImpulseConstraintSolver>();
    world_ = std::make_unique<btDiscreteDynamicsWorld>(
        dispatcher_.get(), broadphase_.get(), solver_.get(), collisionConfig_.get());
    // PMX units are roughly decimeters, so MMD's -9.8 m/s^2 lands as -98 here.
    world_->setGravity(btVector3(0, -98.f, 0));

    // Ground plane at y=0 so hair/skirts/clothes rest instead of falling out of the world.
    groundShape_ = std::make_unique<btStaticPlaneShape>(btVector3(0, 1, 0), 0.f);
    ground_ = std::make_unique<btRigidBody>(btRigidBody::btRigidBodyConstructionInfo(0.f, nullptr, groundShape_.get()));
    ground_->setCollisionShape(groundShape_.get());
    ground_->setWorldTransform(btTransform::getIdentity());
    world_->addRigidBody(ground_.get(), 0x0001, 0xFFFF);

    const size_t bodyCount = model.rigidBodies.size();
    shapes_.reserve(bodyCount);
    bodies_.reserve(bodyCount);
    bodyOffsets_.resize(bodyCount);
    modes_.resize(bodyCount);

    for (size_t i = 0; i < bodyCount; i++) {
        const PmxRigidBody& rb = model.rigidBodies[i];

        btCollisionShape* shape = nullptr;
        switch (rb.shape) {
            case 0: shapes_.push_back(std::make_unique<btSphereShape>(rb.size.x())); break;
            case 1: shapes_.push_back(std::make_unique<btBoxShape>(rb.size)); break;
            default: shapes_.push_back(std::make_unique<btCapsuleShape>(rb.size.x(), rb.size.y())); break;
        }
        shape = shapes_.back().get();

        // rb.position/rotation are PMX-absolute (model-space bind pose), but syncKinematic
        // recomposes boneWorld * bodyOffsets_ every frame — so what's stored here must be the
        // offset relative to the bone's OWN bind pose, not the absolute transform itself.
        btTransform absoluteBind = offsetTransform(rb.position, rb.rotation);
        bool hasBone = rb.bone >= 0 && rb.bone < static_cast<int>(boneBindWorld.size());
        bodyOffsets_[i] = hasBone ? boneBindWorld[rb.bone].inverse() * absoluteBind : absoluteBind;
        modes_[i] = rb.mode;

        bool kinematic = rb.mode == PmxRigidBody::MODE_KINEMATIC || rb.mass <= 0.f;
        btVector3 inertia(0, 0, 0);
        float mass = kinematic ? 0.f : rb.mass;
        if (!kinematic) shape->calculateLocalInertia(mass, inertia);

        auto body = std::make_unique<btRigidBody>(
            btRigidBody::btRigidBodyConstructionInfo(mass, nullptr, shape, inertia));
        body->setCollisionShape(shape);
        body->setDamping(rb.linearDamping, rb.angularDamping);
        body->setRestitution(rb.restitution);
        body->setFriction(rb.friction);
        body->setWorldTransform(absoluteBind); // bind pose; syncKinematic fixes it next frame
        if (kinematic) {
            body->setCollisionFlags(body->getCollisionFlags() | btCollisionObject::CF_KINEMATIC_OBJECT);
        } else {
            body->setActivationState(DISABLE_DEACTIVATION);
        }
        // PMX collisionMask bits mark groups this body does NOT collide with.
        short groupBit = static_cast<short>(1 << (rb.group & 15));
        short mask = static_cast<short>(~rb.collisionMask & 0xFFFF);
        world_->addRigidBody(body.get(), groupBit, mask);
        bodies_.push_back(std::move(body));
    }

    for (const auto& joint : model.joints) {
        if (joint.bodyA < 0 || joint.bodyA >= static_cast<int>(bodyCount)) continue;
        if (joint.bodyB < 0 || joint.bodyB >= static_cast<int>(bodyCount)) continue;
        btRigidBody* a = bodies_[joint.bodyA].get();
        btRigidBody* b = bodies_[joint.bodyB].get();

        btTransform jointWorld = offsetTransform(joint.position, joint.rotation);
        btTransform frameInA = a->getWorldTransform().inverse() * jointWorld;
        btTransform frameInB = b->getWorldTransform().inverse() * jointWorld;

        auto constraint = std::make_unique<btGeneric6DofSpringConstraint>(*a, *b, frameInA, frameInB, true);
        constraint->setLinearLowerLimit(joint.linearLower);
        constraint->setLinearUpperLimit(joint.linearUpper);
        constraint->setAngularLowerLimit(joint.angularLower);
        constraint->setAngularUpperLimit(joint.angularUpper);
        for (int axis = 0; axis < 3; axis++) {
            if (joint.linearSpring[axis] > 0.f) {
                constraint->enableSpring(axis, true);
                constraint->setStiffness(axis, joint.linearSpring[axis]);
            }
            if (joint.angularSpring[axis] > 0.f) {
                constraint->enableSpring(axis + 3, true);
                constraint->setStiffness(axis + 3, joint.angularSpring[axis]);
            }
        }
        world_->addConstraint(constraint.get(), true);
        constraints_.push_back(std::move(constraint));
    }

    PHYS_LOG("world ready: %zu bodies, %zu joints", bodyCount, model.joints.size());
}

void MmdPhysics::syncKinematic(const PmxModel& model, const std::vector<btTransform>& boneWorld) {
    for (size_t i = 0; i < bodies_.size(); i++) {
        const PmxRigidBody& rb = model.rigidBodies[i];
        btTransform bone = (rb.bone >= 0 && rb.bone < static_cast<int>(boneWorld.size()))
                               ? boneWorld[rb.bone]
                               : btTransform::getIdentity();
        btTransform target = bone * bodyOffsets_[i];
        if (modes_[i] == PmxRigidBody::MODE_KINEMATIC) {
            bodies_[i]->setWorldTransform(target);
        } else if (modes_[i] == PmxRigidBody::MODE_DYNAMIC_FOLLOW) {
            // Follows the bone's position but keeps its simulated rotation.
            btTransform current = bodies_[i]->getWorldTransform();
            current.setOrigin(target.getOrigin());
            bodies_[i]->setWorldTransform(current);
        }
    }
}

void MmdPhysics::step(float dt) {
    world_->stepSimulation(dt, 5, 1.f / 60.f);
}

std::vector<int> MmdPhysics::writeBack(const PmxModel& model, std::vector<btTransform>& boneWorld) {
    std::vector<int> overridden;
    for (size_t i = 0; i < bodies_.size(); i++) {
        if (modes_[i] == PmxRigidBody::MODE_KINEMATIC) continue;
        const PmxRigidBody& rb = model.rigidBodies[i];
        if (rb.bone < 0 || rb.bone >= static_cast<int>(boneWorld.size())) continue;
        boneWorld[rb.bone] = bodies_[i]->getWorldTransform() * bodyOffsets_[i].inverse();
        overridden.push_back(rb.bone);
    }
    return overridden;
}

} // namespace mmd
