//! Bind-relative finger retargeting. Inputs are joint angles, never bone lengths.
use glam::{Mat4, Quat, Vec3};
use crate::skeleton::BoneManager;

#[derive(Clone, Copy)]
struct Joint {
    index: usize,
    longitudinal_axis: Vec3,
    flex_axis: Vec3,
    splay_axis: Vec3,
}

#[derive(Default)]
pub(crate) struct VrFingers {
    initialized: bool,
    joints: [[Option<Joint>; 15]; 2],
    angles: [[f32; 20]; 2],
    valid_hands: u8,
    thumb_twist_radians: [f32; 2],
}

impl VrFingers {
    /// Per-model correction of the thumb flexion plane, in right-handed radians
    /// around each neutral thumb segment from its root toward its tip.
    /// This changes the response to flexion, not the model's zero-angle pose or
    /// the independent splay/opposition axis. Tracking reset keeps calibration.
    pub fn set_thumb_calibration(&mut self, left: f32, right: f32) {
        self.thumb_twist_radians = [left, right].map(|value| {
            if value.is_finite() { value.clamp(-std::f32::consts::PI, std::f32::consts::PI) }
            else { 0.0 }
        });
    }

    pub fn set_input(&mut self, angles: &[f32; 40], valid_hands: u8) {
        self.valid_hands = valid_hands & 3;
        for hand in 0..2 {
            let input = &angles[hand * 20..hand * 20 + 20];
            if !input.iter().all(|v| v.is_finite()) {
                self.valid_hands &= !(1 << hand);
                continue;
            }
            for (i, &value) in input.iter().enumerate() {
                self.angles[hand][i] = if i < 15 { value.clamp(-0.35, 2.65) }
                    else { value.clamp(-1.2, 1.2) };
            }
        }
    }

    pub fn reset(&mut self) {
        self.valid_hands = 0;
        self.angles = [[0.0; 20]; 2];
    }

    pub fn apply(&mut self, bones: &mut BoneManager) {
        if self.valid_hands == 0 { return; }
        if !self.initialized {
            self.joints = [resolve_hand(bones, true), resolve_hand(bones, false)];
            self.initialized = true;
        }
        for hand in 0..2 {
            if self.valid_hands & (1 << hand) == 0 { continue; }
            for (i, joint) in self.joints[hand].iter().enumerate() {
                let Some(joint) = joint else { continue; };
                let flex_axis = if i < 3 && self.thumb_twist_radians[hand] != 0.0 {
                    Quat::from_axis_angle(joint.longitudinal_axis, self.thumb_twist_radians[hand])
                        * joint.flex_axis
                } else { joint.flex_axis };
                let flex = Quat::from_axis_angle(flex_axis, self.angles[hand][i]);
                let splay = if i % 3 == 0 {
                    Quat::from_axis_angle(joint.splay_axis, self.angles[hand][15 + i / 3])
                } else { Quat::IDENTITY };
                bones.set_bone_rotation(joint.index, (splay * flex).normalize());
                // Refresh descendants without re-solving arm or leg IK.
                bones.update_single_bone_global(joint.index);
            }
        }
    }

    pub fn controls_bone(&self, index: usize) -> bool {
        (0..2).any(|hand| self.valid_hands & (1 << hand) != 0
            && self.joints[hand].iter().flatten().any(|joint| joint.index == index))
    }
}

fn normalized_name(name: &str) -> String {
    name.rsplit(':').next().unwrap_or(name).chars().filter_map(|c| {
        if c == '_' || c == '-' || c.is_whitespace() { None }
        else if ('０'..='９').contains(&c) {
            char::from_u32(c as u32 - '０' as u32 + '0' as u32)
        } else { Some(c) }
    }).collect::<String>().to_lowercase().replace("人差指", "人指")
}

fn find(bones: &BoneManager, candidates: &[String]) -> Option<usize> {
    bones.links().position(|b| {
        let name = normalized_name(&b.name);
        candidates.iter().any(|c| name == *c || name.strip_prefix("mixamorig") == Some(c.as_str()))
    })
}

fn resolve_hand(bones: &BoneManager, left: bool) -> [Option<Joint>; 15] {
    let mut result = [None; 15];
    let side = if left { "left" } else { "right" };
    let jp = if left { "左" } else { "右" };
    let Some(wrist) = find(bones, &[format!("{jp}手首"), format!("{side}hand"), format!("hand{}", if left { "l" } else { "r" })]) else { return result; };
    let names = ["thumb", "index", "middle", "ring", "little"];
    let japanese = ["親指", "人指", "中指", "薬指", "小指"];
    let thumb_zero = find(bones, &[format!("{jp}親指0")]).is_some();
    let mut indices = [[None; 3]; 5];
    for finger in 0..5 {
        for joint in 0..3 {
            let number = joint + if finger == 0 && thumb_zero { 0 } else { 1 };
            let segment = if finger == 0 { ["metacarpal", "proximal", "distal"][joint] }
                else { ["proximal", "intermediate", "distal"][joint] };
            let mut candidates = vec![format!("{jp}{}{number}", japanese[finger]),
                format!("{side}{}{segment}", names[finger]),
                format!("{side}{}{}", names[finger], joint + 1),
                format!("{side}hand{}{}", names[finger], joint + 1)];
            if finger == 4 { candidates.push(format!("{side}handpinky{}", joint + 1)); }
            indices[finger][joint] = find(bones, &candidates);
        }
    }
    let neutral = neutral_transforms(bones);
    let rest = |index: usize| neutral[index].w_axis.truncate();
    let roots: Vec<Vec3> = indices[1..].iter().filter_map(|v| v[0].map(rest)).collect();
    // Two distinct knuckles define the palm. Incomplete hands with no reliable
    // plane are deliberately left to their normal animation, not guessed axes.
    if roots.len() < 2 { return result; }
    let center = roots.iter().copied().sum::<Vec3>() / roots.len() as f32;
    let Some(forward) = (center - rest(wrist)).try_normalize() else { return result; };
    let across = roots[0] - roots[roots.len() - 1];
    let Some(thumb_side) = (across - forward * across.dot(forward)).try_normalize() else { return result; };
    let palm_normal = thumb_side.cross(forward) * if left { -1.0 } else { 1.0 };
    for finger in 0..5 {
        for joint in 0..3 {
            let Some(index) = indices[finger][joint] else { continue; };
            let next = indices[finger].iter().skip(joint + 1).flatten().next().copied();
            let previous = indices[finger][..joint].iter().rev().flatten().next().copied();
            let direction = next.map(|n| rest(n) - rest(index))
                .or_else(|| previous.map(|p| rest(index) - rest(p)))
                .unwrap_or(rest(index) - rest(wrist));
            let Some(direction) = direction.try_normalize() else { continue; };
            let Some(flex_axis) = direction.cross(palm_normal).try_normalize() else { continue; };
            let raw_splay = direction.cross(thumb_side);
            let splay_axis = (raw_splay - flex_axis * raw_splay.dot(flex_axis)).try_normalize()
                .unwrap_or(direction.cross(flex_axis).normalize());
            let bone = bones.get_bone(index).unwrap();
            let parent_rotation = bone.parent_id().map(|p| neutral[p].to_scale_rotation_translation().1)
                .unwrap_or(Quat::IDENTITY);
            // compute_local_transform uses p^-1 * animation * p * rest.
            // Convert the neutral world axes to that animation frame so VRM
            // rest rotations and arbitrary imported joint bases are respected.
            let to_animation = bone.parent_rest_rotation * parent_rotation.inverse();
            result[finger * 3 + joint] = Some(Joint {
                index, flex_axis: (to_animation * flex_axis).normalize(),
                longitudinal_axis: (to_animation * direction).normalize(),
                splay_axis: (to_animation * splay_axis).normalize(),
            });
        }
    }
    result
}

fn neutral_transforms(bones: &BoneManager) -> Vec<Mat4> {
    fn resolve(index: usize, bones: &BoneManager, output: &mut [Mat4], state: &mut [u8]) {
        if state[index] != 0 { return; }
        state[index] = 1;
        let bone = bones.get_bone(index).unwrap();
        let parent = bone.parent_id().filter(|&p| p < output.len() && p != index);
        if let Some(parent) = parent { resolve(parent, bones, output, state); }
        let local = Mat4::from_rotation_translation(bone.rest_rotation, bone.body_shift);
        output[index] = parent.map(|p| output[p]).unwrap_or(Mat4::IDENTITY) * local;
        state[index] = 2;
    }
    let mut output = vec![Mat4::IDENTITY; bones.bone_count()];
    let mut state = vec![0; bones.bone_count()];
    for index in 0..bones.bone_count() { resolve(index, bones, &mut output, &mut state); }
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::skeleton::BoneLink;

    fn add(bones: &mut BoneManager, name: String, parent: i32, position: Vec3) -> usize {
        let index = bones.bone_count();
        let mut bone = BoneLink::new(name);
        bone.parent_index = parent;
        bone.initial_position = position;
        bones.add_bone(bone);
        index
    }

    fn rig(style: usize, rotation: Quat, scale: f32) -> BoneManager {
        let mut bones = BoneManager::new();
        for left in [true, false] {
            let side = if left { "left" } else { "right" };
            let jp = if left { "左" } else { "右" };
            let sign = if left { 1.0 } else { -1.0 };
            let wrist_position = Vec3::new(sign * 3.0, 4.0, 0.0);
            let wrist_name = match style { 0 => format!("{jp}手首"), 1 => format!("{side}Hand"),
                _ => format!("mixamorig:{side}Hand") };
            let wrist = add(&mut bones, wrist_name, -1, rotation * wrist_position * scale);
            for (finger, name) in ["thumb", "index", "middle", "ring", "little"].iter().enumerate() {
                let mut parent = wrist;
                for joint in 0..3 {
                    let bone_name = match style {
                        0 => format!("{jp}{}{}", ["親指", "人差指", "中指", "薬指", "小指"][finger],
                            char::from_u32('０' as u32 + joint as u32 + if finger == 0 { 0 } else { 1 }).unwrap()),
                        1 => format!("{side}{name}{}", if finger == 0 { ["Metacarpal", "Proximal", "Distal"][joint] }
                            else { ["Proximal", "Intermediate", "Distal"][joint] }),
                        _ => format!("mixamorig:{side}Hand{name}{}", joint + 1),
                    };
                    // +X anatomical left fingers, thumb side +Z, both palms -Y.
                    let p = wrist_position + Vec3::new(sign * (0.8 + joint as f32 * 0.45), 0.0,
                        0.5 - finger as f32 * 0.25);
                    parent = add(&mut bones, bone_name, parent as i32, rotation * p * scale);
                }
            }
        }
        bones.build_hierarchy();
        bones.begin_update();
        bones.update_transforms(false);
        bones
    }

    fn position(bones: &BoneManager, index: usize) -> Vec3 {
        bones.get_global_transform(index).w_axis.truncate()
    }

    #[test]
    fn curls_follow_both_palms_and_wrist_rotation_without_stretching_all_name_styles() {
        for style in 0..3 {
            for scale in [0.05, 1.0, 12.0] {
                let bind_rotation = Quat::from_rotation_z(0.41) * Quat::from_rotation_x(0.63);
                let wrist_rotation = Quat::from_rotation_y(-0.72);
                let mut bones = rig(style, bind_rotation, scale);
                let mut fingers = VrFingers::default();
                let mut angles = [0.0; 40];
                for hand in 0..2 { for i in 0..15 { angles[hand * 20 + i] = 0.45; } }
                fingers.set_input(&angles, 3);
                for wrist in [0, 16] {
                    bones.set_bone_rotation(wrist, wrist_rotation);
                    bones.update_single_bone_global(wrist);
                }
                let before: Vec<Vec3> = (0..bones.bone_count()).map(|i| position(&bones, i)).collect();
                fingers.apply(&mut bones);
                let palm = wrist_rotation * bind_rotation * -Vec3::Y;
                for hand in 0..2 {
                    let base = hand * 16;
                    for finger in 0..5 {
                        let proximal = base + 1 + finger * 3;
                        let middle = proximal + 1;
                        let distal = proximal + 2;
                        let deflection = position(&bones, distal) - before[distal];
                        assert!(deflection.dot(palm) > scale * 0.1, "style={style}, hand={hand}, finger={finger}");
                        for (a, b) in [(base, proximal), (proximal, middle), (middle, distal)] {
                            assert!((position(&bones, a).distance(position(&bones, b)) - before[a].distance(before[b])).abs() < scale * 1e-4);
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn positive_splay_moves_both_hands_toward_thumb_and_loss_resets_independently() {
        let mut bones = rig(0, Quat::IDENTITY, 1.0);
        let rest: Vec<Vec3> = (0..bones.bone_count()).map(|i| position(&bones, i)).collect();
        let mut fingers = VrFingers::default();
        let mut angles = [0.0; 40];
        angles[16] = 0.3;
        angles[36] = 0.3;
        fingers.set_input(&angles, 3);
        fingers.apply(&mut bones);
        assert!(position(&bones, 6).z > rest[6].z + 0.1);
        assert!(position(&bones, 22).z > rest[22].z + 0.1);
        bones.begin_update();
        bones.update_transforms(false);
        fingers.set_input(&angles, 2);
        fingers.apply(&mut bones);
        assert!(position(&bones, 6).distance(rest[6]) < 1e-6);
        assert!(position(&bones, 22).z > rest[22].z + 0.1);
        fingers.reset();
        bones.begin_update();
        bones.update_transforms(false);
        fingers.apply(&mut bones);
        for i in 0..bones.bone_count() { assert!(position(&bones, i).distance(rest[i]) < 1e-6); }
    }

    #[test]
    fn missing_or_invalid_hands_are_skipped_without_poisoning_the_other_hand() {
        let mut empty = BoneManager::new();
        let mut fingers = VrFingers::default();
        fingers.set_input(&[0.8; 40], 3);
        fingers.apply(&mut empty);
        let mut bones = rig(2, Quat::IDENTITY, 1.0);
        // Missing distal bone: the preceding joints still operate.
        bones.get_bone_mut(6).unwrap().name = "missing".to_string();
        let mut fingers = VrFingers::default();
        let mut input = [0.8; 40];
        input[20] = f32::NAN;
        fingers.set_input(&input, 3);
        fingers.apply(&mut bones);
        assert!(fingers.controls_bone(4));
        assert!(!fingers.controls_bone(20));
        assert!(position(&bones, 5).y < 3.9);
        for i in 0..bones.bone_count() { assert!(bones.get_global_transform(i).is_finite()); }
    }

    #[test]
    fn nonidentity_rest_frames_curl_into_the_neutral_palm() {
        let mut bones = rig(1, Quat::IDENTITY, 1.0);
        let rest_wrist = Quat::from_rotation_x(0.65) * Quat::from_rotation_z(0.3);
        // A common imported wrist rest plus individual finger rest twists.
        for wrist in [0, 16] {
            bones.get_bone_mut(wrist).unwrap().rest_rotation = rest_wrist;
            for i in wrist + 1..wrist + 16 {
                bones.get_bone_mut(i).unwrap().parent_rest_rotation = rest_wrist;
                bones.get_bone_mut(i).unwrap().rest_rotation = Quat::from_rotation_x(0.12);
            }
        }
        bones.begin_update();
        bones.update_transforms(false);
        let before: Vec<Vec3> = (0..bones.bone_count()).map(|i| position(&bones, i)).collect();
        let mut fingers = VrFingers::default();
        let mut input = [0.0; 40];
        // Only proximal joints flex: check the first actual segment direction.
        for hand in 0..2 { for finger in 0..5 { input[hand * 20 + finger * 3] = 0.45; } }
        fingers.set_input(&input, 3);
        fingers.apply(&mut bones);
        for hand in 0..2 {
            for finger in 0..5 {
                let index = hand * 16 + 2 + finger * 3;
                assert!((position(&bones, index) - before[index]).dot(rest_wrist * -Vec3::Y) > 0.1);
            }
        }
        // Zero is exactly the model neutral pose even with nonidentity rest.
        bones.begin_update();
        bones.update_transforms(false);
        fingers.set_input(&[0.0; 40], 3);
        fingers.apply(&mut bones);
        for i in 0..bones.bone_count() { assert!(position(&bones, i).distance(before[i]) < 1e-5); }
    }

    #[test]
    fn thumb_plane_calibration_follows_rest_and_tracked_wrist_without_changing_lengths() {
        let mut bones = rig(1, Quat::from_rotation_z(0.3), 1.0);
        let rest_wrist = Quat::from_rotation_x(0.65) * Quat::from_rotation_z(0.3);
        let tracked_wrist = Quat::from_rotation_y(-0.7);
        for wrist in [0, 16] {
            bones.get_bone_mut(wrist).unwrap().rest_rotation = rest_wrist;
            for index in wrist + 1..wrist + 16 {
                bones.get_bone_mut(index).unwrap().parent_rest_rotation = rest_wrist;
                bones.get_bone_mut(index).unwrap().rest_rotation = Quat::from_rotation_x(0.12);
            }
        }
        let prepare = |bones: &mut BoneManager| {
            bones.begin_update();
            for wrist in [0, 16] { bones.set_bone_rotation(wrist, tracked_wrist); }
            bones.update_transforms(false);
        };
        prepare(&mut bones);
        let neutral: Vec<Vec3> = (0..bones.bone_count()).map(|i| position(&bones, i)).collect();
        let mut fingers = VrFingers::default();
        let mut input = [0.0; 40];
        for hand in 0..2 { for joint in 0..15 { input[hand * 20 + joint] = 0.5; } }
        fingers.set_input(&input, 3);
        fingers.apply(&mut bones);
        let baseline: Vec<Mat4> = (0..bones.bone_count()).map(|i| bones.get_global_transform(i)).collect();
        for hand in 0..2 {
          for twist in [-std::f32::consts::FRAC_PI_2, std::f32::consts::FRAC_PI_2] {
            prepare(&mut bones);
            // Set after cache initialization to test live settings changes.
            fingers.set_thumb_calibration(if hand == 0 { twist } else { 0.0 },
                if hand == 1 { twist } else { 0.0 });
            fingers.apply(&mut bones);
            let thumb = hand * 16 + 1;
            let root = neutral[thumb];
            let axis = (neutral[thumb + 1] - root).normalize();
            let expected = root + Quat::from_axis_angle(axis, twist)
                * (baseline[thumb + 1].w_axis.truncate() - root);
            assert!(position(&bones, thumb + 1).distance(expected) < 1e-5);
            assert!(position(&bones, thumb + 1).distance(baseline[thumb + 1].w_axis.truncate()) > 0.1);
            for index in 0..bones.bone_count() {
                if !(thumb..thumb + 3).contains(&index) {
                    assert!(bones.get_global_transform(index).abs_diff_eq(baseline[index], 1e-6),
                        "uncalibrated bone changed: {index}, calibrated hand: {hand}");
                }
                if let Some(parent) = bones.get_bone(index).unwrap().parent_id() {
                    assert!((position(&bones, index).distance(position(&bones, parent))
                        - neutral[index].distance(neutral[parent])).abs() < 1e-5);
                }
            }
          }
        }
        prepare(&mut bones);
        fingers.set_thumb_calibration(0.0, 0.0);
        fingers.apply(&mut bones);
        for index in 0..bones.bone_count() {
            assert!(bones.get_global_transform(index).abs_diff_eq(baseline[index], 1e-6));
        }
    }

    #[test]
    fn thumb_calibration_keeps_neutral_and_opposition_and_survives_tracking_reset() {
        let mut bones = rig(0, Quat::IDENTITY, 1.0);
        let mut fingers = VrFingers::default();
        let mut input = [0.0; 40];
        input[15] = -0.4;
        input[35] = -0.4;
        fingers.set_input(&input, 3);
        fingers.apply(&mut bones);
        let baseline: Vec<Mat4> = (0..bones.bone_count()).map(|i| bones.get_global_transform(i)).collect();
        fingers.set_thumb_calibration(1.1, -0.9);
        fingers.reset();
        assert_eq!(fingers.thumb_twist_radians, [1.1, -0.9]);
        assert_eq!(fingers.valid_hands, 0);
        fingers.set_input(&input, 3);
        bones.begin_update();
        bones.update_transforms(false);
        fingers.apply(&mut bones);
        for index in 0..bones.bone_count() {
            assert!(bones.get_global_transform(index).abs_diff_eq(baseline[index], 1e-6));
        }
        fingers.set_thumb_calibration(f32::NAN, f32::INFINITY);
        assert_eq!(fingers.thumb_twist_radians, [0.0; 2]);
        fingers.set_thumb_calibration(10.0, -10.0);
        assert_eq!(fingers.thumb_twist_radians, [std::f32::consts::PI, -std::f32::consts::PI]);
    }
}
