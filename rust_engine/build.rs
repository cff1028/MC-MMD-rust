/// Bullet3 C++ 编译脚本
///
/// 使用 cc crate 编译 Bullet3 源码和 C Wrapper，
/// 生成静态库链接到 Rust cdylib。
use std::path::{Path, PathBuf};

fn collect_cpp_files(dir: &Path, cpp_files: &mut Vec<PathBuf>) -> Result<(), String> {
    let entries = std::fs::read_dir(dir).map_err(|err| {
        format!(
            "Cannot read Bullet3 source directory {}: {err}",
            dir.display()
        )
    })?;
    for entry in entries {
        let path = entry
            .map_err(|err| format!("Cannot read entry in {}: {err}", dir.display()))?
            .path();
        if path.extension().map_or(false, |e| e == "cpp") {
            cpp_files.push(path);
        }
    }
    Ok(())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let bullet3_dir = manifest_dir.join("deps/bullet3/src");
    let wrapper_dir = manifest_dir.join("bullet_wrapper");

    if !bullet3_dir.join("LinearMath").is_dir() {
        return Err(format!(
            "Bullet3 sources are missing at {}. From a Git checkout, run \
             `git submodule update --init --recursive` in the project root. \
             For a source ZIP without .git, follow the Bullet3 setup instructions in README.md.",
            bullet3_dir.display()
        )
        .into());
    }

    // 收集所有 Bullet3 .cpp 文件
    let mut cpp_files: Vec<PathBuf> = Vec::new();

    // LinearMath
    collect_cpp_files(&bullet3_dir.join("LinearMath"), &mut cpp_files)?;

    // BulletCollision 子目录
    let collision_subdirs = [
        "BroadphaseCollision",
        "CollisionDispatch",
        "CollisionShapes",
        "NarrowPhaseCollision",
        "Gimpact",
    ];
    for subdir in &collision_subdirs {
        let dir = bullet3_dir.join("BulletCollision").join(subdir);
        collect_cpp_files(&dir, &mut cpp_files)?;
    }

    // BulletDynamics 子目录
    let dynamics_subdirs = [
        "Character",
        "ConstraintSolver",
        "Dynamics",
        "Featherstone",
        "MLCPSolvers",
        "Vehicle",
    ];
    for subdir in &dynamics_subdirs {
        let dir = bullet3_dir.join("BulletDynamics").join(subdir);
        collect_cpp_files(&dir, &mut cpp_files)?;
    }

    // 排除依赖缺失头文件的源文件
    let exclude_files: &[&str] = &[
        "btCollisionWorldImporter",
        "btSerializer64", // 64位序列化，不需要
    ];
    cpp_files.retain(|f| {
        !exclude_files
            .iter()
            .any(|ex| f.to_string_lossy().contains(ex))
    });
    cpp_files.sort();

    // C Wrapper
    cpp_files.push(wrapper_dir.join("bw_api.cpp"));

    // 编译 Bullet3 + C Wrapper
    let mut build = cc::Build::new();
    build
        .cpp(true)
        .include(&bullet3_dir)
        .include(&wrapper_dir)
        .warnings(false)
        .opt_level(2);

    // 平台特定设置
    let target = std::env::var("TARGET").unwrap_or_default();
    if target.contains("msvc") {
        build.flag("/EHsc"); // C++ 异常处理
        build.flag("/std:c++17");
    } else {
        build.flag("-std=c++17");
        build.flag("-fno-exceptions");
        build.flag("-fno-rtti");
    }

    for file in &cpp_files {
        build.file(file);
    }

    build.compile("bullet3");

    // 告知 cargo 链接静态库
    println!("cargo:rustc-link-lib=static=bullet3");

    // MSVC 需要链接 C++ 运行时
    if target.contains("msvc") {
        // cc crate 自动处理
    } else if target.contains("apple") {
        println!("cargo:rustc-link-lib=c++");
    } else {
        println!("cargo:rustc-link-lib=stdc++");
    }

    // 重新编译条件
    println!("cargo:rerun-if-changed={}", wrapper_dir.display());
    println!("cargo:rerun-if-changed={}", bullet3_dir.display());
    Ok(())
}
