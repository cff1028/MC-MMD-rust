//! Grounded, model-proportional VR foot targets and rotation-only leg IK.
//! Tracking still owns the torso/head/hands. A render sample advances this state
//! once; stereo eyes and mirror passes can only reuse the resulting pose.

use std::collections::HashSet;

use glam::{Mat4, Quat, Vec3};

use crate::skeleton::BoneManager;

#[derive(Clone, Copy, Debug)]
pub(crate) struct VrLocomotionInput {
    pub sample_id: i64,
    /// Player-local model units/second, with +Z forward (same basis as Java tracking).
    pub velocity: Vec3,
    /// Right-handed angular velocity about model +Y, radians/second.
    pub turn_rate: f32,
    /// Grounded, and neither riding, swimming, flying nor jumping.
    pub allowed: bool,
    pub crouching: bool,
}

#[derive(Debug)]
struct Leg {
    upper: Vec<usize>,
    lower: Vec<usize>,
    upper_length: f32,
    lower_length: f32,
    rest_foot: Vec3,
    rest_rotation: Quat,
    bend_forward: Vec3,
    parent: Option<usize>,
    rest_parent_inverse: Mat4,
}

#[derive(Debug)]
struct Stance {
    rest_hip_center: Vec3,
    rest_hip_direction: Vec3,
    rest_foot_center: Vec3,
    rest_outward_left: Vec3,
    minimum_half_width: f32,
}

#[derive(Clone, Copy)]
struct FootBoundary {
    center: Vec3,
    outward_left: Vec3,
    minimum_half_width: f32,
}

impl FootBoundary {
    fn constrain(self, target: Vec3, side: usize) -> Vec3 {
        let outward = self.outward_left * if side == 0 { 1.0 } else { -1.0 };
        let distance = (target - self.center).dot(outward);
        target + outward * (self.minimum_half_width - distance).max(0.0)
    }
}

impl Leg {
    fn resolve(bones: &BoneManager, left: bool) -> Option<Self> {
        let (upper_names, lower_names, foot_names, toe_names): (&[&str], &[&str], &[&str], &[&str]) =
            if left {
                (&["左足", "leftUpperLeg", "LeftUpLeg", "left_thigh", "thigh_l"],
                 &["左ひざ", "左膝", "leftLowerLeg", "LeftLeg", "left_calf", "calf_l"],
                 &["左足首", "leftFoot", "left_ankle", "foot_l"],
                 &["左つま先", "leftToes", "LeftToeBase", "toe_l"])
            } else {
                (&["右足", "rightUpperLeg", "RightUpLeg", "right_thigh", "thigh_r"],
                 &["右ひざ", "右膝", "rightLowerLeg", "RightLeg", "right_calf", "calf_r"],
                 &["右足首", "rightFoot", "right_ankle", "foot_r"],
                 &["右つま先", "rightToes", "RightToeBase", "toe_r"])
            };
        let thigh = find_bone(bones, upper_names)?;
        let knee = find_bone(bones, lower_names)?;
        let foot = find_bone(bones, foot_names)?;
        let upper = parent_chain(bones, thigh, knee)?;
        let lower = parent_chain(bones, knee, foot)?;
        let length = |chain: &[usize]| -> f32 {
            chain.windows(2).map(|pair| {
                (bones.get_bone(pair[1]).unwrap().initial_position
                    - bones.get_bone(pair[0]).unwrap().initial_position).length()
            }).sum()
        };
        let upper_length = length(&upper);
        let lower_length = length(&lower);
        if !upper_length.is_finite() || !lower_length.is_finite()
            || upper_length < 1e-5 || lower_length < 1e-5
        {
            return None;
        }
        let thigh_bone = bones.get_bone(thigh)?;
        let knee_position = bones.get_bone(knee)?.initial_position;
        let foot_bone = bones.get_bone(foot)?;
        let rest_foot = foot_bone.initial_position;
        let direction = (rest_foot - thigh_bone.initial_position).normalize_or_zero();
        let knee_offset = knee_position - thigh_bone.initial_position;
        let bend_hint = knee_offset - direction * knee_offset.dot(direction);
        let toe_forward = find_bone(bones, toe_names)
            .and_then(|i| bones.get_bone(i))
            .map(|b| horizontal(b.initial_position - rest_foot))
            .and_then(Vec3::try_normalize)
            .unwrap_or(Vec3::Z);
        let bend_forward = if bend_hint.length() > (upper_length + lower_length) * 0.015 {
            bend_hint.normalize()
        } else {
            toe_forward
        };
        let parent = thigh_bone.parent_id();
        Some(Self {
            upper,
            lower,
            upper_length,
            lower_length,
            rest_foot,
            rest_rotation: foot_bone.inverse_init.inverse().to_scale_rotation_translation().1,
            bend_forward,
            parent,
            rest_parent_inverse: parent
                .and_then(|i| bones.get_bone(i))
                .map(|b| b.inverse_init)
                .unwrap_or(Mat4::IDENTITY),
        })
    }

    fn length(&self) -> f32 { self.upper_length + self.lower_length }

    fn solve(&self, bones: &mut BoneManager, offset: Vec3, weight: f32,
        boundary: Option<FootBoundary>, side: usize) {
        let thigh = self.upper[0];
        let knee = self.lower[0];
        let foot = *self.lower.last().unwrap();
        let parent_delta = self.parent
            .map(|i| bones.get_global_transform(i) * self.rest_parent_inverse)
            .unwrap_or(Mat4::IDENTITY);
        let facing = horizontal(parent_delta.transform_vector3(Vec3::Z))
            .try_normalize().unwrap_or(Vec3::Z);
        let yaw = Quat::from_rotation_y(facing.x.atan2(facing.z));
        let mut neutral = parent_delta.transform_point3(self.rest_foot);
        // The head can lower the pelvis; the planted sole remains on the model's
        // rest floor. Horizontal room-scale movement still follows the body.
        neutral.y = self.rest_foot.y;
        let target = boundary.map(|limit| limit.constrain(neutral + offset, side))
            .unwrap_or(neutral + offset);
        let hip_position = position(bones, thigh);
        let to_target = target - hip_position;
        let direction = to_target.try_normalize().unwrap_or(Vec3::NEG_Y);
        let margin = self.upper_length.min(self.lower_length) * 0.002;
        let distance = to_target.length().clamp(
            (self.upper_length - self.lower_length).abs() + margin,
            self.length() - margin,
        );
        let forward = yaw * self.bend_forward;
        let hint = (forward - direction * forward.dot(direction))
            .try_normalize().unwrap_or_else(|| direction.any_orthonormal_vector());
        let cos_angle = ((self.upper_length * self.upper_length + distance * distance
            - self.lower_length * self.lower_length) / (2.0 * self.upper_length * distance))
            .clamp(-1.0, 1.0);
        let knee_target = hip_position + direction * (cos_angle * self.upper_length)
            + hint * (self.upper_length * (1.0 - cos_angle * cos_angle).max(0.0).sqrt());
        let ankle_target = hip_position + direction * distance;
        rotate_chain(bones, &self.upper, knee_target - hip_position, weight);
        rotate_chain(bones, &self.lower, ankle_target - position(bones, knee), weight);
        let old_rotation = bones.get_global_transform(foot).to_scale_rotation_translation().1;
        set_world_rotation(bones, foot, old_rotation.slerp(yaw * self.rest_rotation, weight));
    }
}

#[derive(Debug, Default)]
pub(crate) struct VrLocomotion {
    input: Option<VrLocomotionInput>,
    last_sample: Option<i64>,
    cached_bone_count: Option<usize>,
    legs: [Option<Leg>; 2],
    stance: Option<Stance>,
    ik_names: Vec<String>,
    owned_bones: HashSet<usize>,
    phase: f32,
    velocity: Vec3,
    turn_rate: f32,
    motion_weight: f32,
    pose_weight: f32,
    offsets: [Vec3; 2],
}

impl VrLocomotion {
    pub fn set_input(&mut self, mut input: VrLocomotionInput) {
        if !input.velocity.is_finite() || !input.turn_rate.is_finite() {
            input.allowed = false;
            input.velocity = Vec3::ZERO;
            input.turn_rate = 0.0;
        }
        input.velocity.y = 0.0;
        input.turn_rate = input.turn_rate.clamp(-6.0, 6.0);
        self.input = Some(input);
    }

    pub fn reset(&mut self) {
        self.input = None;
        self.last_sample = None;
        self.velocity = Vec3::ZERO;
        self.turn_rate = 0.0;
        self.phase = 0.0;
        self.pose_weight = 0.0;
        self.motion_weight = 0.0;
        self.offsets = [Vec3::ZERO; 2];
    }

    /// Called after animation evaluation but before any standard PMX IK pass.
    pub fn prepare(&mut self, bones: &mut BoneManager, elapsed: f32) {
        let Some(input) = self.input else { return; };
        self.ensure_cache(bones);
        if self.last_sample != Some(input.sample_id) {
            self.last_sample = Some(input.sample_id);
            self.advance(input, elapsed);
        }
        if !self.active() { return; }
        for name in &self.ik_names {
            bones.set_ik_enabled_by_name(name, false);
        }
        for leg in self.legs.iter().flatten() {
            for &index in leg.upper.iter().chain(leg.lower.iter()) {
                if let Some(bone) = bones.get_bone_mut(index) {
                    bone.animation_translate = Vec3::ZERO;
                    bone.ik_rotate = Quat::IDENTITY;
                }
            }
        }
    }

    /// Applied after head/hand/body IK. Rotations alone preserve every segment.
    pub fn apply(&self, bones: &mut BoneManager) {
        if !self.active() { return; }
        let boundary = self.foot_boundary(bones);
        for (side, leg) in self.legs.iter().enumerate() {
            if let Some(leg) = leg {
                leg.solve(bones, self.offsets[side], self.pose_weight, boundary, side);
            }
        }
        // Many PMX meshes are weighted to 足D/ひざD copies, not the named IK chain.
        bones.update_transforms(false);
    }

    pub fn controls_bone(&self, index: usize) -> bool {
        self.active() && self.owned_bones.contains(&index)
    }

    fn active(&self) -> bool {
        self.pose_weight > 1e-4 && self.legs.iter().any(Option::is_some)
    }

    fn foot_boundary(&self, bones: &BoneManager) -> Option<FootBoundary> {
        let stance = self.stance.as_ref()?;
        let left = self.legs[0].as_ref()?;
        let right = self.legs[1].as_ref()?;
        let left_hip = position(bones, left.upper[0]);
        let right_hip = position(bones, right.upper[0]);
        let yaw = if let Some(direction) = horizontal(left_hip - right_hip).try_normalize() {
            Quat::from_rotation_y(direction.x.atan2(direction.z)
                - stance.rest_hip_direction.x.atan2(stance.rest_hip_direction.z))
        } else {
            let parent_delta = left.parent
                .map(|i| bones.get_global_transform(i) * left.rest_parent_inverse)
                .unwrap_or(Mat4::IDENTITY);
            let facing = horizontal(parent_delta.transform_vector3(Vec3::Z))
                .try_normalize().unwrap_or(Vec3::Z);
            Quat::from_rotation_y(facing.x.atan2(facing.z))
        };
        Some(FootBoundary {
            center: (left_hip + right_hip) * 0.5
                + yaw * (stance.rest_foot_center - stance.rest_hip_center),
            outward_left: yaw * stance.rest_outward_left,
            minimum_half_width: stance.minimum_half_width,
        })
    }

    fn ensure_cache(&mut self, bones: &BoneManager) {
        if self.cached_bone_count == Some(bones.bone_count()) { return; }
        self.cached_bone_count = Some(bones.bone_count());
        self.legs = [Leg::resolve(bones, true), Leg::resolve(bones, false)];
        self.stance = match (&self.legs[0], &self.legs[1]) {
            (Some(left), Some(right)) => {
                let across_feet = horizontal(left.rest_foot - right.rest_foot);
                across_feet.try_normalize().map(|outward| {
                    let left_hip = bones.get_bone(left.upper[0]).unwrap().initial_position;
                    let right_hip = bones.get_bone(right.upper[0]).unwrap().initial_position;
                    Stance {
                        rest_hip_center: (left_hip + right_hip) * 0.5,
                        rest_hip_direction: horizontal(left_hip - right_hip)
                            .try_normalize().unwrap_or(outward),
                        rest_foot_center: (left.rest_foot + right.rest_foot) * 0.5,
                        rest_outward_left: outward,
                        // Leave a small fraction of the original stance between the
                        // feet, independent of leg length. Outward travel is untouched.
                        minimum_half_width: across_feet.length() * 0.10,
                    }
                })
            }
            _ => None,
        };
        self.owned_bones.clear();
        for leg in self.legs.iter().flatten() {
            self.owned_bones.extend(leg.upper.iter().chain(leg.lower.iter()).copied());
        }
        self.ik_names = bones.links().filter(|bone| {
            bone.ik_config.as_ref().is_some_and(|ik| {
                self.owned_bones.contains(&(ik.target_bone as usize))
                    || ik.links.iter().any(|link| self.owned_bones.contains(&(link.bone_index as usize)))
            })
        }).map(|bone| bone.name.clone()).collect();
        // Include append copies (including multi-level copies) in animation ownership.
        // Hair/skirt children remain physics driven; only explicit append dependencies follow.
        loop {
            let mut changed = false;
            for (index, bone) in bones.links().enumerate() {
                if bone.append_config.as_ref().is_some_and(|a| self.owned_bones.contains(&(a.parent as usize))) {
                    changed |= self.owned_bones.insert(index);
                }
            }
            if !changed { break; }
        }
    }

    fn advance(&mut self, input: VrLocomotionInput, elapsed: f32) {
        let dt = if elapsed.is_finite() { elapsed.clamp(0.0, 0.05) } else { 0.0 };
        let count = self.legs.iter().flatten().count();
        if count == 0 { return; }
        let leg_length = self.legs.iter().flatten().map(Leg::length).sum::<f32>() / count as f32;
        let desired_velocity = if input.allowed && input.velocity.length() > leg_length * 0.025 {
            input.velocity.clamp_length_max(leg_length * 5.0)
        } else { Vec3::ZERO };
        let desired_turn = if input.allowed && input.turn_rate.abs() > 0.06 { input.turn_rate } else { 0.0 };
        let smoothing = 1.0 - (-dt / 0.12).exp();
        self.velocity = self.velocity.lerp(desired_velocity, smoothing);
        self.turn_rate += (desired_turn - self.turn_rate) * smoothing;
        let moving = desired_velocity.length_squared() > 0.0 || desired_turn != 0.0;
        let envelope = 1.0 - (-dt / if moving { 0.12 } else { 0.18 }).exp();
        self.motion_weight += (if moving { 1.0 } else { 0.0 } - self.motion_weight) * envelope;
        self.pose_weight += (if input.allowed { 1.0 } else { 0.0 } - self.pose_weight)
            * (1.0 - (-dt / 0.10).exp());
        let speed = self.velocity.length();
        let activity = (speed / leg_length).max(self.turn_rate.abs() * 0.22);
        let cadence = (0.65 + activity * 0.65).clamp(0.65, 2.4);
        if moving {
            self.phase = (self.phase + cadence * dt).fract();
        } else if self.motion_weight < 0.001 {
            self.phase = 0.0;
            self.motion_weight = 0.0;
            self.velocity = Vec3::ZERO;
            self.turn_rate = 0.0;
        }
        let center = self.legs.iter().flatten().map(|l| l.rest_foot).sum::<Vec3>() / count as f32;
        for (side, leg) in self.legs.iter().enumerate() {
            let Some(leg) = leg else { continue; };
            let radius = horizontal(leg.rest_foot - center);
            let foot_velocity = self.velocity + Vec3::Y.cross(radius) * self.turn_rate;
            let crouch_scale = if input.crouching { 0.65 } else { 1.0 };
            let amplitude = (foot_velocity / (4.0 * cadence))
                .clamp_length_max(leg.length() * 0.36) * crouch_scale;
            let phase = (self.phase + side as f32 * 0.5).fract();
            let (sweep, lift) = if phase < 0.5 {
                let t = phase * 2.0;
                (2.0 * t * t * (3.0 - 2.0 * t) - 1.0,
                 (std::f32::consts::PI * t).sin().powi(2))
            } else {
                (1.0 - (phase - 0.5) * 4.0, 0.0)
            };
            let lift_height = leg.length() * (0.04 + activity * 0.025).min(0.13) * crouch_scale;
            self.offsets[side] = (amplitude * sweep + Vec3::Y * (lift * lift_height)) * self.motion_weight;
        }
    }
}

fn horizontal(v: Vec3) -> Vec3 { Vec3::new(v.x, 0.0, v.z) }
fn position(bones: &BoneManager, index: usize) -> Vec3 { bones.get_global_transform(index).w_axis.truncate() }

fn find_bone(bones: &BoneManager, names: &[&str]) -> Option<usize> {
    names.iter().find_map(|name| bones.find_bone_by_name(name)).or_else(|| {
        bones.links().enumerate().find_map(|(index, bone)| {
            // Mixamo namespaces and case differ, while the anatomical names agree.
            let unqualified = bone.name.rsplit(':').next().unwrap_or(&bone.name);
            names.iter().any(|name| unqualified.eq_ignore_ascii_case(name)).then_some(index)
        })
    })
}

fn parent_chain(bones: &BoneManager, from: usize, to: usize) -> Option<Vec<usize>> {
    let mut chain = vec![to];
    let mut cursor = to;
    for _ in 0..bones.bone_count() {
        if cursor == from { chain.reverse(); return (chain.len() >= 2).then_some(chain); }
        cursor = bones.get_bone(cursor)?.parent_id()?;
        chain.push(cursor);
    }
    None
}

fn rotate_chain(bones: &mut BoneManager, chain: &[usize], direction: Vec3, weight: f32) {
    let Some(desired) = direction.try_normalize() else { return; };
    for pair in chain.windows(2) {
        let current = position(bones, pair[1]) - position(bones, pair[0]);
        let Some(current) = current.try_normalize() else { continue; };
        let old_rotation = bones.get_global_transform(pair[0]).to_scale_rotation_translation().1;
        let new_rotation = Quat::from_rotation_arc(current, desired) * old_rotation;
        set_world_rotation(bones, pair[0], old_rotation.slerp(new_rotation, weight));
    }
}

fn set_world_rotation(bones: &mut BoneManager, index: usize, rotation: Quat) {
    let Some(bone) = bones.get_bone(index) else { return; };
    let parent_rotation = bone.parent_id()
        .map(|i| bones.get_global_transform(i).to_scale_rotation_translation().1)
        .unwrap_or(Quat::IDENTITY);
    let mut local_rotation = parent_rotation.inverse() * rotation;
    if bone.enable_ik() { local_rotation = bone.ik_rotate.inverse() * local_rotation; }
    if bone.is_append_rotate() { local_rotation *= bone.append_rotate.inverse(); }
    let p = bone.parent_rest_rotation;
    let animation = p * local_rotation * bone.rest_rotation.inverse() * p.inverse();
    bones.set_bone_rotation(index, animation.normalize());
    bones.update_single_bone_global(index);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::skeleton::{AppendConfig, BoneFlags, BoneLink, IkConfig, IkLink};

    fn add(bones: &mut BoneManager, name: &str, parent: i32, position: Vec3) -> usize {
        let mut bone = BoneLink::new(name.to_owned());
        bone.parent_index = parent;
        bone.initial_position = position;
        let index = bones.bone_count();
        bones.add_bone(bone);
        index
    }

    // Deliberately includes a nonzero-length helper inside each shin and a
    // separately weighted PMX D chain, plus a torso branch that must not move.
    fn rig(scale: f32, upper_fraction: f32, english: bool) -> BoneManager {
        let mut bones = BoneManager::new();
        add(&mut bones, "全ての親", -1, Vec3::ZERO);
        let hips = add(&mut bones, "下半身", 0, Vec3::new(0.0, 1.08, 0.0) * scale);
        let chest = add(&mut bones, "上半身", hips as i32, Vec3::new(0.0, 1.4, 0.0) * scale);
        add(&mut bones, "頭", chest as i32, Vec3::new(0.0, 1.7, 0.0) * scale);
        add(&mut bones, "skirt", hips as i32, Vec3::new(0.2, 0.7, 0.1) * scale);
        for (left, x) in [(true, 0.12), (false, -0.12)] {
            let names = match (left, english) {
                (true, false) => ["左足", "左ひざ", "左足首", "左つま先"],
                (false, false) => ["右足", "右ひざ", "右足首", "右つま先"],
                (true, true) => ["mixamorig:LeftUpLeg", "mixamorig:LeftLeg", "mixamorig:LeftFoot", "mixamorig:LeftToeBase"],
                (false, true) => ["mixamorig:RightUpLeg", "mixamorig:RightLeg", "mixamorig:RightFoot", "mixamorig:RightToeBase"],
            };
            let thigh_position = Vec3::new(x, 1.08, 0.0) * scale;
            let knee_position = Vec3::new(x, 1.08 - upper_fraction, 0.025) * scale;
            let ankle_position = Vec3::new(x, 0.08, 0.0) * scale;
            let thigh = add(&mut bones, names[0], hips as i32, thigh_position);
            let knee = add(&mut bones, names[1], thigh as i32, knee_position);
            let helper = add(&mut bones, &format!("{}_helper", names[1]), knee as i32,
                knee_position.lerp(ankle_position, 0.2));
            let foot = add(&mut bones, names[2], helper as i32, ankle_position);
            let toe = add(&mut bones, names[3], foot as i32, ankle_position + Vec3::new(0.0, -0.07, 0.16) * scale);
            let mut ik = BoneLink::new(format!("{}_IK", names[0]));
            ik.parent_index = 0;
            ik.initial_position = ankle_position;
            ik.flags |= BoneFlags::IK;
            ik.ik_config = Some(IkConfig {
                target_bone: foot as i32, iterations: 10, limit_angle: 0.5,
                links: vec![IkLink { bone_index: knee as i32, has_limits: false,
                    limit_min: Vec3::ZERO, limit_max: Vec3::ZERO },
                    IkLink { bone_index: thigh as i32, has_limits: false,
                    limit_min: Vec3::ZERO, limit_max: Vec3::ZERO }],
            });
            bones.add_bone(ik);
            let mut toe_ik = BoneLink::new(format!("{}_IK", names[3]));
            toe_ik.parent_index = 0;
            toe_ik.initial_position = bones.get_bone(toe).unwrap().initial_position;
            toe_ik.flags |= BoneFlags::IK;
            toe_ik.ik_config = Some(IkConfig { target_bone: toe as i32,
                iterations: 4, limit_angle: 0.5,
                links: vec![IkLink { bone_index: foot as i32, has_limits: false,
                    limit_min: Vec3::ZERO, limit_max: Vec3::ZERO }] });
            bones.add_bone(toe_ik);
            let mut parent = hips;
            for source in [thigh, knee, helper, foot] {
                let original = bones.get_bone(source).unwrap();
                let mut copy = BoneLink::new(format!("{}D", original.name));
                copy.parent_index = parent as i32;
                copy.initial_position = original.initial_position;
                copy.flags |= BoneFlags::APPEND_ROTATE;
                copy.append_config = Some(AppendConfig { parent: source as i32, rate: 1.0 });
                parent = bones.bone_count();
                bones.add_bone(copy);
            }
        }
        bones.build_hierarchy();
        bones.begin_update();
        bones.update_transforms(false);
        bones
    }

    fn input(id: i64, velocity: Vec3) -> VrLocomotionInput {
        VrLocomotionInput { sample_id: id, velocity, turn_rate: 0.0, allowed: true, crouching: false }
    }

    fn step(gait: &mut VrLocomotion, bones: &mut BoneManager, input: VrLocomotionInput,
        elapsed: f32, body_shift: Vec3) {
        gait.set_input(input);
        bones.begin_update();
        bones.reset_all_ik_enabled();
        gait.prepare(bones, elapsed);
        bones.update_transforms(false);
        let hips = bones.find_bone_by_name("下半身").unwrap();
        let old = bones.get_global_transform(hips);
        bones.set_global_transform(hips,
            Mat4::from_rotation_translation(old.to_scale_rotation_translation().1,
                old.w_axis.truncate() + body_shift));
        let head = bones.find_bone_by_name("頭").unwrap();
        let head_before = bones.get_global_transform(head);
        gait.apply(bones);
        assert!(bones.get_global_transform(head).abs_diff_eq(head_before, 2e-5),
            "gait changed the tracked upper-body branch");
    }

    fn assert_segment_lengths(bones: &BoneManager, tolerance: f32) {
        for bone in bones.links() {
            assert!(bone.local_to_world.is_finite(), "{}", bone.name);
            if let Some(parent) = bone.parent_id() {
                if bone.name == "下半身" { continue; }
                let actual = (bone.local_to_world.w_axis.truncate() - position(bones, parent)).length();
                assert!((actual - bone.body_shift.length()).abs() < tolerance,
                    "{} length {} != {}", bone.name, actual, bone.body_shift.length());
            }
        }
    }

    #[test]
    fn gait_preserves_segments_and_upper_body_for_different_proportions_and_names() {
        for scale in [0.05, 1.0, 12.0] {
            for ratio in [0.25, 0.5, 0.75] {
                for english in [false, true] {
                    let mut bones = rig(scale, ratio, english);
                    let rest_offsets: Vec<_> = bones.links().map(|b| b.body_shift).collect();
                    let mut gait = VrLocomotion::default();
                    let mut lift = [0.0_f32; 2];
                    for frame in 0..120 {
                        let body = Vec3::new(0.08, -0.20, -0.04) * scale;
                        step(&mut gait, &mut bones, input(frame, Vec3::new(0.3, 0.0, 1.2) * scale), 1.0 / 90.0, body);
                        assert_segment_lengths(&bones, scale * 2e-4);
                        for (side, offset) in gait.offsets.iter().enumerate() { lift[side] = lift[side].max(offset.y); }
                        for (bone, rest) in bones.links().zip(&rest_offsets) { assert_eq!(bone.body_shift, *rest); }
                    }
                    assert!(lift.iter().all(|v| *v > 0.02 * scale), "both feet must alternate lifting");
                }
            }
        }
    }

    #[test]
    fn forward_backward_and_strafe_stance_sweep_opposite_to_travel() {
        for direction in [Vec3::Z, Vec3::NEG_Z, Vec3::X, Vec3::NEG_X] {
            let mut bones = rig(1.0, 0.5, false);
            let mut gait = VrLocomotion::default();
            for frame in 0..100 { step(&mut gait, &mut bones, input(frame, direction), 0.016, Vec3::new(0.0, -0.2, 0.0)); }
            gait.phase = 0.65;
            step(&mut gait, &mut bones, input(101, direction), 0.001, Vec3::new(0.0, -0.2, 0.0));
            let foot = *gait.legs[0].as_ref().unwrap().lower.last().unwrap();
            let before = position(&bones, foot);
            step(&mut gait, &mut bones, input(102, direction), 0.02, Vec3::new(0.0, -0.2, 0.0));
            let delta = position(&bones, foot) - before;
            assert!(delta.dot(direction) < -0.003, "stance foot must move backward relative to body: {delta:?}");
            assert!(delta.y.abs() < 1e-4, "stance foot must stay planted vertically");
        }
    }

    #[test]
    fn fast_strafing_keeps_feet_on_their_own_side_of_the_posed_body() {
        for scale in [0.05, 1.0, 12.0] {
            for upper_fraction in [0.3, 0.7] {
                for direction in [-1.0, 1.0] {
                    let mut bones = rig(scale, upper_fraction, false);
                    let mut gait = VrLocomotion::default();
                    let hips = bones.find_bone_by_name("下半身").unwrap();
                    let mut max_outward_distance = [0.0_f32; 2];
                    for frame in 0..300 {
                        // Turn and translate the torso too: a fixed model-X clamp
                        // would pass at yaw=0 but let the feet cross after a turn.
                        let yaw = Quat::from_rotation_y(frame as f32 * 0.011);
                        gait.set_input(input(frame, yaw * Vec3::X * (direction * 10.0 * scale)));
                        bones.begin_update();
                        bones.reset_all_ik_enabled();
                        gait.prepare(&mut bones, 1.0 / 90.0);
                        bones.update_transforms(false);
                        let rest_hip = bones.get_bone(hips).unwrap().initial_position;
                        bones.set_global_transform(hips, Mat4::from_rotation_translation(yaw,
                            rest_hip + Vec3::new(0.3, -0.2, -0.4) * scale));
                        let boundary = gait.foot_boundary(&bones).unwrap();
                        gait.apply(&mut bones);
                        assert_segment_lengths(&bones, 2e-4 * scale);
                        for side in 0..2 {
                            let foot = *gait.legs[side].as_ref().unwrap().lower.last().unwrap();
                            let sign = if side == 0 { 1.0 } else { -1.0 };
                            let distance = (position(&bones, foot) - boundary.center)
                                .dot(boundary.outward_left) * sign;
                            assert!(distance > -1e-4 * scale,
                                "foot crossed center: scale={scale}, ratio={upper_fraction}, frame={frame}, side={side}, distance={distance}");
                            max_outward_distance[side] = max_outward_distance[side].max(distance);
                        }
                    }
                    assert!(max_outward_distance.iter().all(|d| *d > 0.25 * scale),
                        "outward travel must remain available: {max_outward_distance:?}");
                }
            }
        }
    }

    #[test]
    fn in_place_turn_steps_without_translating_torso_and_crouch_reduces_stride() {
        let mut bones = rig(1.0, 0.5, false);
        let mut gait = VrLocomotion::default();
        let mut extent = [Vec3::ZERO; 2];
        for frame in 0..180 {
            step(&mut gait, &mut bones, VrLocomotionInput { turn_rate: 1.8, ..input(frame, Vec3::ZERO) }, 1.0 / 90.0, Vec3::ZERO);
            for side in 0..2 { extent[side] = extent[side].max(gait.offsets[side].abs()); }
        }
        assert!(extent.iter().all(|v| v.y > 0.03 && v.z > 0.015), "turn requires lift and opposite foot travel: {extent:?}");
        gait.phase = 0.25;
        gait.advance(VrLocomotionInput { turn_rate: 1.8, ..input(200, Vec3::ZERO) }, 0.0);
        let standing = gait.offsets;
        gait.advance(VrLocomotionInput { turn_rate: 1.8, crouching: true, ..input(201, Vec3::ZERO) }, 0.0);
        for side in 0..2 { assert!(gait.offsets[side].abs_diff_eq(standing[side] * 0.65, 1e-5)); }
    }

    #[test]
    fn stop_smoothly_returns_both_feet_to_neutral_and_disallowed_states_release_pose() {
        let mut bones = rig(1.0, 0.5, false);
        let mut gait = VrLocomotion::default();
        for frame in 0..100 { step(&mut gait, &mut bones, input(frame, Vec3::Z * 1.4), 1.0 / 90.0, Vec3::new(0.0, -0.15, 0.0)); }
        let phase = gait.phase;
        let mut previous = gait.offsets;
        for frame in 100..280 {
            step(&mut gait, &mut bones, input(frame, Vec3::ZERO), 1.0 / 90.0, Vec3::new(0.0, -0.15, 0.0));
            for side in 0..2 { assert!((gait.offsets[side] - previous[side]).length() < 0.05); }
            previous = gait.offsets;
        }
        assert_ne!(phase, 0.0);
        assert_eq!(gait.offsets, [Vec3::ZERO; 2]);
        assert_eq!(gait.phase, 0.0);
        for frame in 280..400 {
            step(&mut gait, &mut bones, VrLocomotionInput { allowed: false, turn_rate: 4.0,
                ..input(frame, Vec3::Z * 100.0) }, 1.0 / 90.0, Vec3::ZERO);
        }
        assert!(!gait.active(), "airborne/swim/fly/ride must not keep walking");
        assert_eq!(gait.phase, 0.0);
        assert!(bones.links().all(|bone| bone.animation_rotate.abs_diff_eq(Quat::IDENTITY, 1e-4)));
    }

    #[test]
    fn duplicate_stereo_sample_reapplies_pose_without_advancing_phase() {
        let mut bones = rig(1.0, 0.5, false);
        let mut gait = VrLocomotion::default();
        for frame in 0..80 { step(&mut gait, &mut bones, input(frame, Vec3::Z), 1.0 / 90.0, Vec3::ZERO); }
        let phase = gait.phase;
        let offsets = gait.offsets;
        let pose: Vec<_> = bones.links().map(|b| b.local_to_world).collect();
        for _ in 0..5 {
            step(&mut gait, &mut bones, input(79, Vec3::Z), 0.04, Vec3::ZERO);
            assert_eq!(gait.phase, phase);
            assert_eq!(gait.offsets, offsets);
            for (bone, expected) in bones.links().zip(&pose) { assert!(bone.local_to_world.abs_diff_eq(*expected, 1e-5)); }
        }
        step(&mut gait, &mut bones, input(80, Vec3::Z), 0.016, Vec3::ZERO);
        assert_ne!(gait.phase, phase);
        gait.reset();
        assert!(!gait.active());
        assert!(gait.input.is_none());
    }

    #[test]
    fn pmx_append_deform_chain_follows_gait_and_only_leg_related_ik_is_suppressed() {
        let mut bones = rig(12.0, 0.45, false);
        let mut gait = VrLocomotion::default();
        for frame in 0..100 {
            step(&mut gait, &mut bones, input(frame, Vec3::new(-7.0, 0.0, 12.0)), 1.0 / 90.0, Vec3::new(1.0, -2.5, 0.0));
            for name in ["左足", "左ひざ", "左足首", "右足", "右ひざ", "右足首"] {
                let original = bones.find_bone_by_name(name).unwrap();
                let copy = bones.find_bone_by_name(&format!("{name}D")).unwrap();
                assert!((position(&bones, original) - position(&bones, copy)).length() < 0.003, "{name} D copy did not follow");
                assert!(gait.controls_bone(original) && gait.controls_bone(copy));
            }
            assert!(!gait.controls_bone(bones.find_bone_by_name("skirt").unwrap()));
            assert_eq!(gait.ik_names.len(), 4, "both foot and toe IK must be suppressed");
        }
    }

    #[test]
    fn missing_or_disconnected_legs_and_invalid_input_fail_closed() {
        let mut empty = BoneManager::new();
        add(&mut empty, "root", -1, Vec3::ZERO);
        add(&mut empty, "leftUpperLeg", 0, Vec3::Y);
        add(&mut empty, "leftLowerLeg", 0, Vec3::Y * 0.5);
        add(&mut empty, "leftFoot", 0, Vec3::ZERO);
        empty.build_hierarchy();
        let mut gait = VrLocomotion::default();
        gait.set_input(input(1, Vec3::Z));
        gait.prepare(&mut empty, 0.016);
        gait.apply(&mut empty);
        assert!(!gait.active());
        let mut bones = rig(1.0, 0.5, false);
        step(&mut gait, &mut bones, input(2, Vec3::splat(f32::NAN)), 0.016, Vec3::ZERO);
        assert!(!gait.active());
        step(&mut gait, &mut bones, input(3, Vec3::Z), f32::NAN, Vec3::ZERO);
        assert!(!gait.active());
        assert_segment_lengths(&bones, 1e-4);
    }
}
