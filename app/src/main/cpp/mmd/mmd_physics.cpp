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
    // Union-find over the bodies, grown as the constraints are created, so each joint can be
    // asked whether its two bodies were ALREADY connected by the joints before it — see the
    // redundant-closure handling further down.
    std::vector<int> connected(bodyCount);
    for (size_t i = 0; i < bodyCount; i++) connected[i] = static_cast<int>(i);
    auto findRoot = [&connected](int x) {
        while (connected[x] != x) {
            connected[x] = connected[connected[x]];
            x = connected[x];
        }
        return x;
    };

    for (const auto& joint : model.joints) {
        if (joint.bodyA < 0 || joint.bodyA >= static_cast<int>(bodyCount)) continue;
        if (joint.bodyB < 0 || joint.bodyB >= static_cast<int>(bodyCount)) continue;
        auto pairKey = std::minmax(joint.bodyA, joint.bodyB);
        if (!jointedPairs.insert(pairKey).second) continue;
        btRigidBody* a = bodies_[joint.bodyA].get();
        btRigidBody* b = bodies_[joint.bodyB].get();

        // A joint whose two bodies the constraint graph already connects adds a second, parallel
        // path between them. That is fine when the joint has somewhere to give — the skirt and
        // back-hair cross-links let their bodies travel (±0.5 and ±0.1 on their linear axes), so
        // the two paths can both be satisfied. It is not fine when the closure is a RIGID pin:
        // zero travel on all three linear axes means two independent rigid paths between the same
        // pair, which sequential impulse cannot satisfy simultaneously, and the springs' authority
        // becomes the loop gain of the resulting fight.
        //
        // In this project's test model exactly one joint is both: 左胸_J, which pins the left
        // breast rigidly to the right one at the chest midline — 0.728 units from either body's
        // centre, so its linear rows convert a small translation error into a large rotation of
        // both bodies, while both are already spring-constrained to the same torso. Measured on
        // the settle test (anchors still, released at bind, 20 s of gravity, mean second
        // difference of pose over the last 7 s; every other part in the rig scores exactly 0),
        // scaling just this joint's authored stiffness:
        //
        //     scale   1.0      0.8      0.7      0.6     0.5      0.35     0.2
        //     breast  0.01226  0.00415  0.00199  0.00116 0.00085  0.00086  0.00102
        //     cord    0.01297  0.00648  0.00380  0.00274 0.00211  0.00214  0.00261
        //
        // 0.5 is the minimum and sits in a flat basin (0.35-0.6 all within 40%), so it has margin
        // on both sides. It also beats deleting the joint outright (which scores 0.00225) while
        // keeping what the joint is there for: under an asymmetric torso twist the breasts' peak
        // excursion and their left/right correlation are unchanged from the authored value
        // (0.42/0.41 and -0.37, versus 0.43/0.41 and -0.36), whereas deleting it drops the
        // excursion 19% and flips the correlation to +0.17.
        int rootA = findRoot(joint.bodyA);
        int rootB = findRoot(joint.bodyB);
        bool closesLoop = rootA == rootB;
        if (!closesLoop) connected[rootA] = rootB;
        bool rigidPin = joint.linearLower.x() == joint.linearUpper.x() &&
                        joint.linearLower.y() == joint.linearUpper.y() &&
                        joint.linearLower.z() == joint.linearUpper.z();
        constexpr float kRedundantClosureScale = 0.5f;
        const float springScale = (closesLoop && rigidPin) ? kRedundantClosureScale : 1.f;

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
        // One class of joint needs more compliance than that, and it is identifiable from the
        // authored data rather than by waiting for someone to report it. PMX almost always locks a
        // joint's three linear axes (linearLower == linearUpper), which makes them three bilateral
        // rows that simply pin a point: mutually consistent, consistent with the angular rows, and
        // needing no compliance at all. A joint that instead gives a linear axis real travel AND a
        // spring is a different animal — that axis's row is a motor and a unilateral stop at the
        // same time, and because Bullet builds linear rows with the offset formulation
        // (m_useOffsetForConstraintFrame) the row also carries an angular jacobian term relA x ax.
        // So sprung translation is precisely the case where a joint's translational and rotational
        // corrections are cross-coupled inside one joint and the row set can go inconsistent, and
        // CFM — constraint force mixing, i.e. compliance — is the term that regularises exactly
        // that. Here it is 21 of 160 joints: the two breast joints, the skirt and back-hair
        // cross-links, and the front-hair strands.
        //
        // Measured on the chest (上半身2 -> 左胸/右胸, cross-pinned to each other, each carrying a
        // cord), which is where this showed up: anchors held still, bodies released at bind, 20 s
        // under gravity, scoring the mean second difference of pose over the last 7 s (0 == fully
        // at rest). At a flat 0.005 the chest sustains a limit cycle at 0.0572 — about 1 rad/s of
        // permanent angular velocity on both breast bodies. Giving just this class 0.02 takes it
        // to 0.0130, a 4.4x reduction, while every other part measured (ahoge, hair ribbon, bell
        // ribbon, hair bell, skirt loop, sleeve chain) stays at exactly 0.000000 because none of
        // them is in the class and none of their values change. Raising the flat value to 0.02
        // instead reaches a similar 0.0120, but it multiplies the static limit violation of
        // everything else by four (the CFM/ERP term above: the ahoge would hang 10.5° outside its
        // ±5° cone instead of 2.6°), which is why this is keyed per joint and not global.
        //
        // Two candidate explanations were tested and refuted rather than assumed: clamping each
        // spring's equilibrium into its own authored range (the breast joint's linear Y range is
        // [0.010, 0.502], which excludes the equilibrium at 0) changes the result in no digit; and
        // treating this as classic over-constraint from the closed 上半身2 -> 左胸 -> 右胸 loop,
        // giving loop-closing joints (16 of 112 after dedup, found by union-find over the joint
        // graph) extra CFM, does nothing at all across 0.005 to 0.8. The breast-to-breast pin
        // amplifies the problem — removing it drops the residual 140x — but the compliance that
        // settles it belongs on the two torso-to-breast joints, which is what this keys on.
        // One more class wants a softer stop still, and it is the one behind the pose-dependent
        // trembling of the sleeve ribbons. A unilateral stop only misbehaves while the joint is
        // actually sitting on it. Most joints rest somewhere inside their range and only touch a
        // stop in passing; a few are authored so that the rest configuration (the springs'
        // equilibrium, which is always 0 because setEquilibriumPoint is never called) lies exactly
        // ON a bound, so that stop is engaged from frame one. When that is true of all three
        // angular axes at once, all three rows sit on the switching boundary simultaneously, and
        // because they ride Bullet's non-orthogonal Euler axes the switching is coupled — whether
        // the loop actually closes then depends on which of the three gravity happens to load,
        // i.e. on the anchor's orientation. That is exactly the reported symptom: trembling at
        // some poses and not others, rather than the steady buzz every earlier round chased.
        //
        // Counting joints by how many angular axes rest on a bound gives 93 with none, 57 with
        // one, 5 with two and 5 with three — and the five are 左紐_A plus all four 袖_リボンA,
        // the reported bodies. The B ribbons, whose X axis is instead free over ±180°, have only
        // two and settle fine, which is the control that makes the count the right key rather
        // than some property of ribbons generally.
        //
        // Measured with a pose-change test (anchor holds, sweeps 120° in 0.2 s, holds; worst case
        // over eight anchor orientations; residual scored 4-6 s after the sweep):
        //
        //                       residual    residual w    peak
        //     sleeve ribbons   0.000458 -> 0.000142   0 -> 0   0.323 -> 0.377
        //     back cord        0.013791 -> 0.008156   0.471 -> 0.237   unchanged
        //     the other eight systems      identical in every column
        //
        // 3.2x less residual on the reported part and 1.7x on the back cord (whose 左紐_A is in
        // the same class), for a 17% larger peak swing during the pose change itself — the same
        // trade a softer stop always makes, and the peak is the ribbon moving, which is wanted.
        constexpr float kStopCfm = 0.005f;
        constexpr float kSprungTranslationCfm = 0.02f;
        constexpr float kStopErp = 0.1f;
        constexpr float kRestOnBoundErp = 0.02f;
        bool sprungTranslation = false;
        int restOnBound = 0;
        for (int axis = 0; axis < 3; axis++) {
            if (joint.linearSpring[axis] > 0.f && joint.linearUpper[axis] > joint.linearLower[axis]) {
                sprungTranslation = true;
            }
            if (joint.angularLower[axis] < joint.angularUpper[axis] &&
                (joint.angularLower[axis] == 0.f || joint.angularUpper[axis] == 0.f)) {
                restOnBound++;
            }
        }
        const float stopCfm = sprungTranslation ? kSprungTranslationCfm : kStopCfm;
        const float stopErp = restOnBound == 3 ? kRestOnBoundErp : kStopErp;
        for (int axis = 0; axis < 6; axis++) {
            constraint->setParam(BT_CONSTRAINT_STOP_CFM, stopCfm, axis);
            constraint->setParam(BT_CONSTRAINT_STOP_ERP, stopErp, axis);
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
        // springScale is 1 for every joint except a redundant rigid loop closure (see above).
        // Scaling before springDamping() keeps the servo-gain bound consistent with the stiffness
        // the constraint actually gets.
        for (int axis = 0; axis < 3; axis++) {
            if (joint.linearSpring[axis] > 0.f) {
                const float stiffness = joint.linearSpring[axis] * springScale;
                constraint->enableSpring(axis, true);
                constraint->setStiffness(axis, stiffness);
                constraint->setDamping(axis, springDamping(stiffness));
            }
            if (joint.angularSpring[axis] > 0.f) {
                const float stiffness = joint.angularSpring[axis] * springScale;
                constraint->enableSpring(axis + 3, true);
                constraint->setStiffness(axis + 3, stiffness);
                constraint->setDamping(axis + 3, springDamping(stiffness));
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
    // 120 Hz internally, not 60. Four rounds of jitter reports (hair ornaments, ahoge, chest,
    // back cord) were each chased down to a different solver detail, but the three that were
    // still moving after all of those turn out to share one cause the parameters cannot reach:
    // their dynamics are simply not resolved at a 60 Hz step. The discriminator is sharp. Driving
    // the anchor 20 degrees at 0.8 Hz and scoring the per-frame twitch of the result, halving the
    // step changes the reported parts and leaves the never-reported ones alone:
    //
    //     system         60 Hz     120 Hz    240 Hz
    //     back cord      0.05597   0.02687   0.01896     2.1x
    //     chest          0.02660   0.01169   0.00902     2.3x
    //     ahoge          0.01507   0.00769   0.00309     2.0x
    //     skirt chain    0.00807   0.00789   0.00784     1.0x
    //     sleeve chain   0.00798   0.00772   0.00753     1.0x
    //     hair ribbon    0.00384   0.00370   0.00368     1.0x
    //
    // Which is exactly what the per-axis numbers predicted all along: the ahoge's stiffest spring
    // axis runs at dt*omega = 1.64 and the chest cord's at 2.37 — the latter past the symplectic-
    // Euler stability limit of 2 — while the back cord is a six-link chain of ±5° joints whose
    // high bending modes sit near Nyquist at 60 Hz. The parts that never got reported are the
    // ones already well resolved (wide limits, soft springs), and they do not care.
    //
    // On the settle test (anchors held still, released at bind, then left alone) the chest, which
    // had resisted every CFM/ERP/stiffness combination tried across three rounds and always kept
    // ~0.45 rad/s of residual, reaches exactly 0.000000 here for the first time. Nothing else
    // regresses: every other system was already 0.000000 and stays there.
    //
    // Halving the step also quarters the static limit violation derived below in init(): that
    // term is dv*dt*stopCFM/stopERP and dv itself scales with dt, so the ahoge's residual droop
    // outside its ±5° cone goes from ~2.6° to ~0.65°.
    //
    // maxSubSteps rises to 10 to keep the same real-time budget the old 5-at-1/60 had, i.e. the
    // simulation only starts shedding time once a frame exceeds ~83 ms.
    world_->stepSimulation(dt, 10, 1.f / 120.f);

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
