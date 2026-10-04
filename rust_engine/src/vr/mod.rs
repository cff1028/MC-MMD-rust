//! VR 联动模块 - 独立于现有 IK 求解器

pub mod vr_ik;
pub(crate) mod locomotion;
pub(crate) mod fingers;

pub use vr_ik::VrIkSolver;
pub(crate) use locomotion::{VrLocomotion, VrLocomotionInput};
pub(crate) use fingers::VrFingers;
pub(crate) use vr_ik::{VrDebugState, VrTrackedPose, VrTrackingFrame, XR_TO_MODEL_SCALE};
