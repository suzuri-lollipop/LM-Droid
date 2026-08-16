#include "mmd_physics.h"

#include <BulletCollision/BroadphaseCollision/btDbvtBroadphase.h>
#include <BulletCollision/CollisionDispatch/btCollisionDispatcher.h>
#include <BulletCollision/CollisionDispatch/btDefaultCollisionConfiguration.h>
#include <BulletCollision/CollisionShapes/btBoxShape.h>
#include <BulletCollision/CollisionShapes/btCapsuleShape.h>
#include <BulletCollision/CollisionShapes/btSphereShape.h>
#include <BulletCollision/CollisionShapes/btStaticPlaneShape.h>
#include <BulletDynamics/ConstraintSolver/btGeneric6DofSpringConstraint.h>
#include <BulletDynamics/ConstraintSolver/btPoint2PointConstraint.h>
#include <BulletDynamics/ConstraintSolver/btSequentialImpulseConstraintSolver.h>
#include <BulletDynamics/Dynamics/btRigidBody.h>
#include <android/log.h>
#include <algorithm>
#include <set>
#include <utility>

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

    // Same idea as the joint-stop softening below, applied to plain body-vs-body contacts: a
    // kinematic body (bone-driven, never yields) permanently overlapping a dynamic one (pulled
    // back in every step by gravity or a joint spring) makes a perfectly rigid contact response
    // fight that overlap forever instead of settling — visible as jitter that never stops as
    // long as the two stay interfering. A little CFM softens that tug-of-war, and more solver
    // iterations help it actually converge each step instead of leaving residual penetration for
    // the next step to fight again.
    //
    // The iteration count is load-bearing for the joint springs too, and NOT in the intuitive
    // direction — see kMaxSpringServoGain below. btGeneric6DofSpringConstraint divides its
    // spring target velocity by this number, so lowering it back toward Bullet's default of 10
    // would DOUBLE every spring's per-step gain; on this project's test model that takes the
    // stiffest joints (the hair ornaments, stiffness 100) from a gain of 1.5 to 3.0, i.e. from
    // a decaying two-frame oscillation to a growing one. Raise it, never lower it.
    btContactSolverInfo& solverInfo = world_->getSolverInfo();
    solverInfo.m_globalCfm = 0.02f;
    solverInfo.m_numIterations = 20;

    // The two solver constants below are lengths/speeds, and Bullet's defaults for them assume a
    // world where 1 unit == 1 metre. This one runs at MMD's scale — 1 unit is roughly 10cm, which
    // is why gravity above is -98 and not -9.8 — so both defaults are an order of magnitude too
    // small here and misbehave specifically on bodies that stay in contact.
    //
    // A zero linear slop asks the solver to drive every contact to *exactly* zero penetration.
    // Two bodies the rig keeps pressed together never get there, so a correction impulse is
    // regenerated every step forever instead of the pair settling. A small tolerance (~3mm at this
    // model's scale, invisible) lets a resting or deliberately-overlapping pair come to rest.
    solverInfo.m_linearSlop = 0.03f;
    // Below this relative speed restitution is ignored, which is what stops a resting body from
    // bouncing in place. At the default 0.2 a single frame of this world's gravity (98/60 ≈ 1.6
    // units/s) already clears the threshold, so any model authoring a non-zero 反発力 would have
    // its resting contacts bounce forever. Scaled to match the unit scale it guards again.
    solverInfo.m_restitutionVelocityThreshold = 2.f;

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
    followPivots_.resize(bodyCount, nullptr);

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
        // PMX's 16-bit field is the set of groups this body DOES collide with — a set bit means
        // "collides" — despite the spec calling it 非衝突グループフラグ ("non-collision group
        // flags"): that name describes the editor's checkboxes, which are stored already inverted.
        // It is a Bullet collision mask as-is, so inverting it here turned every non-collision
        // relationship the model author authored into a forced collision and vice versa. That is
        // the "interfering bodies twitch forever" bug: a rig's overlapping parts are made to
        // overlap on purpose and then excluded from colliding, so once the exclusion flips into a
        // requirement, the solver has to separate bodies that the rig puts back inside each other
        // every frame — a conflict with no solution, re-fought every step. On this project's test
        // model the inversion turned the 28 hair/ribbon bodies in group 4 (authored 0xFFEF, "hit
        // everything except my own group", precisely because they interpenetrate in the bind pose)
        // into "hit ONLY my own group", and turned the kinematic body core (0xFFFF, "hit
        // everything") into 0x0000 so the real body-vs-hair collisions stopped happening at all.
        // Counting unjointed body pairs that already interpenetrate in that model's bind pose,
        // the inversion put 52 of them under a collision requirement they can never satisfy,
        // where the authored masks leave 14. int rather than short: group 15 would make 1 << 15
        // a negative short and sign-extend into every high mask bit.
        int groupBit = 1 << (rb.group & 15);
        int mask = rb.collisionMask;
        world_->addRigidBody(body.get(), groupBit, mask);

        // A DYNAMIC/DYNAMIC_FOLLOW body with mass<=0 was just flagged CF_KINEMATIC_OBJECT above
        // (kinematic, true) — Bullet ignores constraints on kinematic bodies entirely, so a pivot
        // here would be a silent no-op. Guard on the same condition so we don't create one.
        if (modes_[i] == PmxRigidBody::MODE_DYNAMIC_FOLLOW && !kinematic) {
            // Pins this body's origin to the bone through the solver instead of teleporting it in
            // syncKinematic. A teleport is a hard override applied before the solve even starts,
            // so a body interfering with anything got reset back into the same overlap every
            // single frame regardless of what contact resolution had just done — the two fought
            // forever instead of settling, showing up as jitter that never stopped as long as the
            // interference lasted. Pinning through a constraint lets the same LCP solve that
            // resolves collisions also resolve this position pull, so the two settle together.
            auto follow = std::make_unique<btPoint2PointConstraint>(*body, btVector3(0, 0, 0));
            followPivots_[i] = follow.get();
            world_->addConstraint(follow.get(), true);
            constraints_.push_back(std::move(follow));
        }

        bodies_.push_back(std::move(body));
    }

    // Some exported rigs (e.g. cord/tassel chains) carry a second joint between the same body
    // pair with bodyA/bodyB swapped — a mirrored "return" constraint some engines tolerate.
    // Bullet's solver doesn't: two 6DOF springs pulling the same pair toward independently
    // computed target frames fight every step, showing up as velocity that never decays
    // (persistent jitter). Keep only the first joint seen for each pair.
    std::set<std::pair<int, int>> jointedPairs;
    for (const auto& joint : model.joints) {
        if (joint.bodyA < 0 || joint.bodyA >= static_cast<int>(bodyCount)) continue;
        if (joint.bodyB < 0 || joint.bodyB >= static_cast<int>(bodyCount)) continue;
        auto pairKey = std::minmax(joint.bodyA, joint.bodyB);
        if (!jointedPairs.insert(pairKey).second) continue;
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
        // Bullet's default limit stop is nearly rigid (low CFM). Under a constant load (gravity,
        // every step, forever) a rigid stop chatters — traced this rig's persistent non-decaying
        // velocity here by disabling the springs outright and finding the jitter unchanged, so it
        // was never the springs' damping; it was the limit stops themselves vibrating against
        // gravity. Softening every axis (even ones without a limit configured — harmless, they
        // have nothing to stop against) gives the stop a little give instead of a hard wall.
        //
        // How much give, though, is not free, and the previous pair of values here (CFM 0.3 /
        // ERP 0.6) bought none of it. Working the stop row out of the solver (get_limit_motor_info2
        // writes constraintError = -fps*stopERP*violation with cfm = stopCFM, and joint rows use
        // cfm raw — unlike contact rows it is not pre-multiplied by jacDiagABInv, so it acts as a
        // plain relative-compliance factor) the per-step recurrence for a body held against a stop
        // by a constant load is
        //
        //     violation_next = violation * (1 - stopERP/(1 + stopCFM))  +  dv * dt * stopCFM/(1 + stopCFM)
        //
        // where dv is the velocity that load injects per substep. Two consequences:
        //   * the stop's stiffness is stopERP/(1 + stopCFM), so tripling ERP to 0.6 more than
        //     cancelled the CFM softening — 0.6/1.3 = 0.46 is over TWICE as rigid as Bullet's
        //     own default of 0.2, the exact opposite of what the paragraph above intends; and
        //   * the stop settles at a permanent violation of  dv * dt * stopCFM/stopERP, so that
        //     CFM/ERP ratio (0.5) is a direct multiplier on how far every joint in the model
        //     hangs outside the range its rigger authored.
        //
        // That second term is the one that singles out the parts still visibly misbehaving. It
        // scales with dv = torque/inertia, and the small head accessories have by far the worst
        // ratio in this rig — the ahoge's tip has an inertia of 0.005 against a 0.5 mass on a
        // 0.37 lever, ~10x worse than the skirt panels. At 0.5 it left the ahoge sitting 26
        // degrees outside its authored +-5 degree cone (measured and predicted, they agree to
        // three digits), the hair bell 5.8 degrees outside +-10, the bell ribbons 3.3 degrees
        // outside their +5; the skirt, whose limits are +-160 degrees, never reaches a stop at
        // all and so looked fine throughout. A body parked that far outside its cone has several
        // angular stops violated at once, all fighting through one tiny inertia.
        //
        // Dropping CFM alone fixed the hair ornaments but not the ahoge, because the ERP term is
        // also the loop gain of a feedback path that the recurrence above — being single-axis —
        // cannot see. A stop row does not apply a position correction; it commands a VELOCITY of
        // fps*stopERP*violation, and that velocity survives the solve into integrateTransforms.
        // On a body with several stops engaged at once the correction each row injects re-violates
        // the others (the three angular rows ride Bullet's Euler-XYZ axes, which are neither the
        // body's principal axes nor even mutually orthogonal — see calculateAngleInfo), so the
        // corrections chase each other around the axes. Above a gain threshold that round trip has
        // loop gain >= 1 and the body never comes to rest.
        //
        // Exactly one body in this rig closes that loop: the ahoge tip. Everything else has at
        // most one angular axis actually pressed against a stop — the hard-locked axes of the hair
        // ribbons sit at zero violation because those bodies just hang in plane. The ahoge has all
        // three engaged simultaneously (X hard-locked at 0, Y driven past its ±5° cone, Z resting
        // on its [-20°, 0] bound) plus the three linear locks, through an inertia tensor that is
        // 8.5:1 anisotropic (a capsule: 0.0437 across, 0.0051 about its own long axis). Simulating
        // the full six-row solve for this joint — static anchor, one 3 rad/s kick, then left alone
        // — the hair ribbon and bell ribbon decay to exactly zero at every ERP tried, while the
        // ahoge sustains a limit cycle forever at ERP 0.6 and 0.3 and settles to exactly zero at
        // 0.15 and below. Gyroscopic coupling was the other candidate and is not involved:
        // Bullet does apply an implicit-body gyroscopic impulse by default, but disabling it
        // changes this joint's residual motion by ~0.1%.
        //
        // So ERP wants to be small — bounded below only by how fast a limit must be recovered
        // after a fast head turn. At 0.1 (half of Bullet's own default, and a stop stiffness of
        // 0.1/1.005 ≈ 0.0995, i.e. finally the "little give" this comment has claimed since the
        // first round) the worst limit overshoot under a deliberately violent 25°-at-1.5Hz head
        // shake stays under 2.4° on every accessory, with 1.5x margin below the 0.15 settling
        // threshold. Pairing it with a smaller CFM keeps the static-violation multiplier at
        // 0.05 — still better than the 0.067 that fixed the ornaments, and 10x better than the
        // 0.5 this started at.
        constexpr float kStopCfm = 0.005f;
        constexpr float kStopErp = 0.1f;
        for (int axis = 0; axis < 6; axis++) {
            constraint->setParam(BT_CONSTRAINT_STOP_CFM, kStopCfm, axis);
            constraint->setParam(BT_CONSTRAINT_STOP_ERP, kStopErp, axis);
        }
        // btGeneric6DofSpringConstraint's damping scale is inverted from what its name suggests
        // (1 == no damping, per its own header) and defaults to 1 — PMX carries no separate
        // spring-damping field, so every enabled spring here was running fully undamped until
        // explicitly set.
        //
        // It is not a damping coefficient at all, though: internalUpdateSprings turns the spring
        // into a velocity motor with target = (fps * damping / numIterations) * stiffness * error
        // and an impulse clamp of +-|stiffness * error| / fps. Away from that clamp the row is a
        // dead-beat position servo, and its per-step contraction works out to
        //
        //     g = damping * stiffness / numIterations
        //
        // with dt, mass and inertia all cancelling — so g, not `damping`, is the number that
        // decides whether a joint settles. g < 1 decays monotonically; 1 < g < 2 overshoots the
        // equilibrium and REVERSES THE JOINT ANGLE'S SIGN EVERY FRAME, which is what jitter looks
        // like; g >= 2 diverges. A single flat damping value cannot control g, because stiffness
        // is per-joint and authored: at 0.3/20 this model's joints ran from g = 0.07 (fine) up to
        // g = 1.05 on its 96 stiffness-70 axes and g = 1.5 on the 14 stiffness-100 axes — which
        // are the bell ribbons in the hair, i.e. the second part reported as still jittering. Six
        // more axes carry the "locked" idiom of stiffness 100000, g = 1500.
        //
        // Deriving damping from the joint's own stiffness pins g instead of leaving it to whatever
        // the rigger typed. Taking the smaller of the two also means no joint ends up with less
        // damping than the flat value gave it (only stiffness > 66 is affected here), so nothing
        // that already settles can regress. It cancels numIterations out of g as well, so the
        // rig stops depending on a solver tuning knob.
        constexpr float kSpringDamping = 0.3f;
        constexpr float kMaxSpringServoGain = 1.f;
        const float iterations = static_cast<float>(solverInfo.m_numIterations);
        auto springDamping = [&](float stiffness) {
            return std::min(kSpringDamping, kMaxSpringServoGain * iterations / stiffness);
        };
        for (int axis = 0; axis < 3; axis++) {
            if (joint.linearSpring[axis] > 0.f) {
                constraint->enableSpring(axis, true);
                constraint->setStiffness(axis, joint.linearSpring[axis]);
                constraint->setDamping(axis, springDamping(joint.linearSpring[axis]));
            }
            if (joint.angularSpring[axis] > 0.f) {
                constraint->enableSpring(axis + 3, true);
                constraint->setStiffness(axis + 3, joint.angularSpring[axis]);
                constraint->setDamping(axis + 3, springDamping(joint.angularSpring[axis]));
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
        // isKinematicObject(), not modes_[i] == MODE_KINEMATIC: init() also flags a
        // DYNAMIC/DYNAMIC_FOLLOW body kinematic when its PMX mass is <= 0 (Bullet can't derive
        // an inertia tensor for it, so it can't simulate). modes_[i] alone misses that case,
        // which left such a body's transform never touched here — frozen at bind pose forever
        // (and see writeBack, which was then copying that frozen pose back onto the bone).
        if (bodies_[i]->isKinematicObject()) {
            bodies_[i]->setWorldTransform(target);
        } else if (modes_[i] == PmxRigidBody::MODE_DYNAMIC_FOLLOW && followPivots_[i]) {
            // Moves the pin, not the body — the solver pulls the body's origin toward it next
            // step, blended with any contacts it's touching, instead of the body being placed
            // there directly. Rotation stays fully simulated, as before.
            followPivots_[i]->setPivotB(target.getOrigin());
        }
    }
}

void MmdPhysics::step(float dt) {
    world_->stepSimulation(dt, 5, 1.f / 60.f);

    // Extra velocity decay on top of each body's own PMX damping and the joint springs' own
    // damping: under gravity, every step, forever, a rigid limit stop chatters (confirmed by
    // disabling the springs outright and finding the residual jitter unchanged — softening the
    // stops via BT_CONSTRAINT_STOP_CFM/ERP above helped but didn't fully settle it). This is a
    // blanket safety net on top of that: applied uniformly post-step so it damps the whole system
    // the same way regardless of which joint is still chattering, rather than retuning each one.
    constexpr float kExtraDampingPerSecond = 10.f;
    const float decay = expf(-kExtraDampingPerSecond * dt);
    // Exponential decay only ever approaches zero, never reaches it, and every dynamic body has
    // DISABLE_DEACTIVATION set (so it stays responsive to bone motion instead of needing a wake-up
    // we might miss) — meaning Bullet's own sleep-when-still mechanism, which is what normally
    // zeroes out this kind of leftover solver noise, never gets a chance to fire. Without it, a
    // body resting against something it interferes with settles toward an amplitude too small to
    // see rather than an amplitude of exactly zero, and keeps visibly twitching indefinitely.
    // Snapping sub-threshold velocity to zero gives it the same "close enough, stop" that
    // deactivation would have.
    constexpr float kRestSpeedSq = 1e-3f;
    for (size_t i = 0; i < bodies_.size(); i++) {
        if (bodies_[i]->isKinematicObject()) continue;
        btVector3 linear = bodies_[i]->getLinearVelocity() * decay;
        btVector3 angular = bodies_[i]->getAngularVelocity() * decay;
        if (linear.length2() < kRestSpeedSq) linear.setZero();
        if (angular.length2() < kRestSpeedSq) angular.setZero();
        bodies_[i]->setLinearVelocity(linear);
        bodies_[i]->setAngularVelocity(angular);
    }
}

std::vector<int> MmdPhysics::writeBack(const PmxModel& model, std::vector<btTransform>& boneWorld) {
    std::vector<int> overridden;
    for (size_t i = 0; i < bodies_.size(); i++) {
        // isKinematicObject(), not modes_[i] == MODE_KINEMATIC (see syncKinematic): a
        // DYNAMIC/DYNAMIC_FOLLOW body with PMX mass <= 0 is kinematic too, and was never moved by
        // physics — without this, its untouched bind-pose transform got written over the bone's
        // animated transform every single frame, freezing that bone in place.
        if (bodies_[i]->isKinematicObject()) continue;
        const PmxRigidBody& rb = model.rigidBodies[i];
        if (rb.bone < 0 || rb.bone >= static_cast<int>(boneWorld.size())) continue;
        boneWorld[rb.bone] = bodies_[i]->getWorldTransform() * bodyOffsets_[i].inverse();
        overridden.push_back(rb.bone);
    }
    return overridden;
}

} // namespace mmd
