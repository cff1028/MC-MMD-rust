# MC-MMD-rust

在 Minecraft 1.21.4 中实现 MMD（MikuMikuDance）/ VRM 模型渲染、动画、物理模拟和 Vivecraft VR 交互的 Mod，支持 Fabric 与 NeoForge。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## 当前分支与下载

- Minecraft：**1.21.4**；分支：[`1.21.4`](https://github.com/cff1028/MC-MMD-rust/tree/1.21.4)。
- 当前模组版本：**1.0.5-1.21.4-1**。
- [下载 Windows x64 Release](https://github.com/cff1028/MC-MMD-rust/releases/tag/1.0.5-1.21.4-1-windows-x64)：按加载器选择 Fabric 或 NeoForge JAR，只安装其中一个。
- 本次附件仅包含 **Windows x64 的 Rust 原生引擎**，需要 64 位 Java 21。其他操作系统或 CPU 架构需要另行构建对应原生库。

## 功能特性

- **模型加载**: 支持 PMX / PMD，以及 VRM 模型运行时
- **VMD 动画播放**: 支持骨骼和表情变形的 MMD 动画播放
- **物理模拟**: 使用 Bullet3 实现 MMD 刚体与关节物理效果
- **GPU 蒙皮**: 通过 Compute Shader 实现高性能顶点蒙皮
- **多层动画**: 支持多个动画同时混合播放
- **VR 模型交互**: Vivecraft 头手追踪、手臂 IK、手指追踪、身高与臂长校准、移动步态
- **空间菜单**: 在 VR 中管理模型、材质、动作、表情、舞台、世界与设置
- **VR 指针、镜子与键盘**: 双手指针、可移动镜子、虚拟键盘及 Windows 输入法桥接

### 本次更新（1.0.5-1.21.4-1）

本次源码更新基于 `92450b2`，主要变化如下：

- 新增基于 NanoVG 的 Lumen 空间菜单，接入实际模型、材质、动作、舞台和世界列表，并支持预览、滚动及原版设置界面衔接。
- 新增 VR 双手指针、独立菜单按键、手柄调试与手部设置；支持 SteamVR 手部骨骼数据和手指姿态驱动。
- 新增 T 字站姿校准、头显前方校准提示、模型眼位与手持物品调整；改进手臂 IK、转身和移动步态，保留模型骨骼比例。
- 新增可移动 VR 镜子及 `off / low / high` 模式，并加入场景镜面渲染和 Sodium 兼容处理。
- 新增射线、手部、手指和自动模式的 VR 键盘，以及 Windows 文本输入、输入法组合文本和候选词桥接。
- 更新 Fabric / NeoForge 的渲染集成和 NanoVG 原生库打包，补充相关回归测试，并完善 Bullet3 源码缺失时的构建提示。

## 架构

本项目由两个主要部分组成：

1. **rust_engine**: 基于 Rust 的 MMD 物理和动画引擎
   - PMX / PMD / VMD 解析与 VRM 运行时
   - 骨骼层次管理
   - 物理模拟（Bullet3 C++ 源码与 Rust 包装层）
   - VR IK、手指和移动步态、Windows 键盘文本服务
   - JNI 绑定用于 Java 交互

2. **Minecraft Mod**（Common/Fabric/NeoForge）: 基于 Java 的渲染和集成
   - OpenGL 模型渲染
   - Compute Shader 蒙皮
   - Iris 光影兼容
   - Vivecraft 交互与 NanoVG 空间菜单

## 使用教程

### 安装

1. 准备 **Minecraft 1.21.4、64 位 Java 21 和 Windows x64**，选择一种加载器：
   - **Fabric**：本次构建使用 Fabric Loader `0.17.2`；安装适用于 1.21.4 的 Fabric API（构建依赖为 `0.107.3+1.21.4`）。
   - **NeoForge**：本次构建使用 NeoForge `21.4.157`。
2. 从上述 Release 下载对应的 `*-windows-x64.jar`，放入当前游戏实例的 `mods/` 目录。JAR 已内嵌 Architectury API、Cloth Config、NanoVG 和 Windows x64 引擎，无需另行复制 DLL。
3. 若使用 VR 功能，额外安装与 Minecraft 和加载器版本匹配的 **Vivecraft**；手指追踪取决于设备及 SteamVR 提供的数据。
4. 启动游戏，模组会自动创建 `3d-skin` 资源目录。将模型和动画放入对应目录（详见下文）。

升级时替换旧版 MMD Skin JAR，保留 `3d-skin/` 和 `config/mmdskin/` 中的模型、动画与配置。

### 目录结构

模组使用 `.minecraft/3d-skin/` 作为资源根目录，结构如下：

```
.minecraft/
└── 3d-skin/
    ├── EntityPlayer/          # 玩家模型目录
    │   ├── 模型A/             # 每个子文件夹是一个模型，文件夹名就是模型名
    │   │   ├── model.pmx      # 模型文件（支持 .pmx/.pmd/.vrm）
    │   │   ├── *.png          # 贴图文件
    │   │   ├── anims/         # 模型专属动画子文件夹（推荐）
    │   │   │   ├── idle.vmd   # 覆盖该模型的待机动画
    │   │   │   └── walk.vmd   # 覆盖该模型的行走动画
    │   │   ├── animations.json # 动画槽位映射配置（UI 自动生成）
    │   │   ├── dance.vmd      # 根目录动画（向后兼容）
    │   │   └── smile.vpd      # 模型专属表情（可选）
    │   └── 模型B/
    │       └── ...
    ├── DefaultAnim/           # 系统预设动画（模组自动释放）
    ├── CustomAnim/            # 用户自定义动画
    ├── DefaultMorph/          # 系统预设表情
    ├── CustomMorph/           # 用户自定义表情
    └── Shader/                # 自定义着色器
```

### 文件夹详解

#### `EntityPlayer/` - 玩家模型目录

存放玩家可用的 MMD 模型。**每个模型必须放在独立的子文件夹中**。

| 文件类型 | 扩展名 | 说明 |
|---------|--------|------|
| 模型文件 | `.pmx` / `.pmd` / `.vrm` | 必需，扫描顺序为 PMX、PMD、VRM |
| 贴图文件 | `.png` / `.jpg` / `.bmp` / `.tga` | 模型引用的贴图 |
| 专属动画 | `.vmd` | 可选，推荐放入 `anims/` 子文件夹 |
| 动画映射 | `animations.json` | 可选，通过 UI 自动生成 |
| 专属表情 | `.vpd` | 可选，仅该模型可用 |

**模型识别规则**：
- 扫描每个子文件夹，按 `.pmx`、`.pmd`、`.vrm` 顺序查找模型文件
- 若文件夹内有多个模型文件，优先选择 `model.pmx` 或 `model.pmd`
- 若无 `model.*`，则按文件名排序选择第一个

下文的 VMD / VPD 动画和表情示例以 MMD 模型为主；VRM 的骨骼与表情由对应运行时处理。

**示例**：
```
EntityPlayer/
├── miku/
│   ├── model.pmx          # 被加载
│   ├── body.png
│   └── face.png
└── shiroha/
    ├── cirno_v2.pmx       # 被加载（文件夹内唯一 pmx）
    ├── cirno_old.pmd      # 忽略（pmx 优先）
    └── texture.png
```

#### `DefaultAnim/` - 系统预设动画

模组首次启动时自动从内置资源释放，包含游戏状态对应的基础动画：

| 动画文件名 | 触发条件 |
|-----------|----------|
| `idle.vmd` | 站立静止 |
| `walk.vmd` | 行走 |
| `sprint.vmd` | 疾跑 |
| `sneak.vmd` | 潜行 |
| `swim.vmd` | 游泳 |
| `crawl.vmd` | 匍匐 |
| `sleep.vmd` | 睡觉 |
| `die.vmd` | 死亡 |
| `elytraFly.vmd` | 鞘翅飞行 |
| `onClimbable.vmd` | 攀爬（静止） |
| `onClimbableUp.vmd` | 攀爬（上） |
| `onClimbableDown.vmd` | 攀爬（下） |
| `onHorse.vmd` / `ride.vmd` | 骑乘 |
| `lieDown.vmd` | 躺下 |
| `swingLeft.vmd` | 左手挥动 |
| `swingRight.vmd` | 右手挥动 |
| `itemActive_*.vmd` | 物品使用动画 |

> **注意**：可直接替换这些文件来自定义基础动画，但建议备份原文件。

#### `CustomAnim/` - 用户自定义动画

存放用户添加的 `.vmd` 动画文件，可通过动作轮盘手动触发播放。

**命名建议**：文件名即为显示名称，建议使用有意义的名称如 `跳舞.vmd`、`打招呼.vmd`。

#### `DefaultMorph/` - 系统预设表情

存放系统预设的 `.vpd` 表情文件（如眨眼、微笑等）。

#### `CustomMorph/` - 用户自定义表情

存放用户添加的 `.vpd` 表情文件，可通过表情轮盘手动触发。

### 动画加载优先级

当需要播放动画时，模组按以下顺序查找（从高到低）：

| 优先级 | 来源 | 路径 | 说明 |
|:---:|------|------|------|
| 1 | **animations.json 映射** | `EntityPlayer/模型名/animations.json` | 用户通过 UI 显式配置 |
| 2 | **anims/ 子文件夹** | `EntityPlayer/模型名/anims/*.vmd` | 同名自动匹配 |
| 3 | **模型根目录** | `EntityPlayer/模型名/*.vmd` | 同名自动匹配（向后兼容） |
| 4 | **自定义动画目录** | `CustomAnim/*.vmd` | 全局自定义动画 |
| 5 | **默认动画目录** | `DefaultAnim/*.vmd` | 系统预设（最低） |

只需配置想要覆盖的槽位，未配置的自动 fallback 到低优先级来源。

**示例**：若 `EntityPlayer/初音未来/anims/idle.vmd` 存在，则该模型的待机动画使用此文件，而非 `DefaultAnim/idle.vmd`。

### 模型专属动画配置

为特定模型定制动画有两种方式：

#### 方式一：自动同名匹配（零配置）

在模型文件夹下创建 `anims/` 子文件夹，放入与动画槽位同名的 VMD 文件即可：

```
EntityPlayer/
└── 初音未来/
    ├── model.pmx
    └── anims/
        ├── idle.vmd      # 自动替代默认待机动画
        ├── walk.vmd      # 自动替代默认行走动画
        └── sprint.vmd    # 自动替代默认疾跑动画
```

可用的槽位名称（即文件名）：

| 槽位名 | 触发条件 | 槽位名 | 触发条件 |
|--------|----------|--------|----------|
| `idle` | 站立静止 | `walk` | 行走 |
| `sprint` | 疾跑 | `sneak` | 潜行 |
| `air` | 空中 | `swim` | 游泳 |
| `crawl` | 匍匐 | `sleep` | 睡觉 |
| `die` | 死亡 | `ride` | 骑乘 |
| `elytraFly` | 鞘翅飞行 | `onHorse` | 骑马 |
| `onClimbable` | 攀爬（静止） | `onClimbableUp` | 攀爬（上） |
| `onClimbableDown` | 攀爬（下） | `lieDown` | 趴下 |
| `swingRight` | 右手挥动 | `swingLeft` | 左手挥动 |
| `itemRight` | 右手物品 | `itemLeft` | 左手物品 |
| `ridden` | 被骑乘 | `driven` | 驾驶 |

#### 方式二：UI 映射（自定义文件名）

当 VMD 文件名与槽位名不同时（如 `我的待机动画_v3.vmd`），可通过游戏内 UI 配置映射：

1. 将 VMD 文件放入模型的 `anims/` 文件夹
2. 进入游戏，打开**模型设置** → 点击底部 **「动画」** 按钮
3. 点击槽位名称（如「待机」）→ 展开下拉列表 → 选择对应的 VMD 文件
4. 点击 **「保存」**

映射关系会自动保存到模型目录下的 `animations.json`：

```json
{
  "idle": "我的待机动画_v3.vmd",
  "walk": "走路_custom.vmd"
}
```

> **提示**：也可以手动编辑 `animations.json`，格式为 `"槽位名": "VMD文件名"`。

### 模型设置

在模型选择界面中选中模型后，可进入模型独立设置界面，包含以下功能：

| 设置项 | 说明 |
|--------|------|
| **眼球追踪** | 开启/关闭眼球追踪，调整最大转动角度 |
| **模型缩放** | 调整模型整体大小（0.5x ~ 2.0x） |
| **快捷绑定** | 将模型绑定到快捷键槽位（1~4），按键快速切换 |
| **动画配置** | 打开动画映射界面，为模型配置专属动画（详见上文） |

### 快捷键操作

#### 主配置轮盘（按住 `Alt` 键）

按住 `Alt` 键打开主配置轮盘，移动鼠标选择功能，松开 `Alt` 确认：

| 选项 | 功能 |
|------|------|
| 🎭 **模型切换** | 打开模型选择界面，切换玩家使用的 MMD 模型 |
| 🎬 **动作选择** | 打开动作轮盘，手动播放 `CustomAnim/` 中的动画 |
| 😊 **表情选择** | 打开表情轮盘，应用 `CustomMorph/` 中的表情 |
| 👕 **材质控制** | 控制模型各部位材质的显示/隐藏 |
| ⚙ **模组设置** | 打开模组配置界面 |

#### 女仆配置轮盘（对准女仆按 `B` 键）

若安装了 TouhouLittleMaid 模组，对准女仆实体按 `B` 键可打开女仆专用配置轮盘。

> **提示**：快捷键可在游戏设置 → 控制 → MMD Skin 分类中自定义。

### 模型切换界面

按住 `Alt` → 选择「模型切换」进入：

- 显示 `EntityPlayer/` 下所有可用模型
- 显示模型格式、文件名、文件大小
- 点击模型卡片立即切换
- 选择「默认」恢复原版玩家皮肤渲染
- 点击「刷新」重新扫描模型目录

### 动作/表情轮盘配置

动作轮盘和表情轮盘支持自定义槽位：

1. 打开对应轮盘界面
2. 点击右下角 ⚙ 按钮进入配置界面
3. 从可用列表中添加/移除槽位
4. 拖拽调整顺序

配置保存在 `.minecraft/config/mmdskin/` 目录下。

### Vivecraft VR 使用

进入 Vivecraft VR 模式并选择模型后，可使用以下功能：

- **空间菜单**：将右手摇杆向前推至触发区打开快捷面板；用手柄射线瞄准、扳机选择，摇杆滚动列表。菜单中的模型、材质、动作、表情与设置连接游戏中的实际数据。
- **模型校准**：在 VR 模型设置中启动「T 字站姿：校准身高与臂长」。站稳并平伸双臂，先松开扳机，按头显前方提示等待姿势稳定后确认；返回设置后预览并保存。也可手动调整眼位、模型缩放和手持物品。
- **手部与指针**：使用手部设置、指针设置与手柄调试界面调整交互。手指跟随需要兼容设备提供追踪数据。
- **镜子**：从 MMD 的 VR 功能页开启镜子，按界面提示移动或切换 `off / low / high`。场景镜面与当前渲染器不兼容时会退回 `low` 模式。
- **虚拟键盘**：支持自动、射线、手部和手指模式。Windows 原生桥接提供输入法组合文本与候选词；具体可用性取决于系统输入法与当前输入焦点。

VR 输入跟随 Vivecraft / SteamVR 的控制器绑定；不同手柄的物理按键可能不同。

## 构建

### 前置要求

- Rust stable（用于 rust_engine）
- JDK 21（设置 `JAVA_HOME`，用于 Gradle 和 Minecraft mod）
- 项目自带的 Gradle Wrapper（8.11.1）
- C++ 编译工具链（Windows：Visual Studio 的“使用 C++ 的桌面开发”，包含 MSVC 和 Windows SDK）

### 准备 Bullet3 源码依赖

Rust 引擎通过 `build.rs` 编译 Bullet3 C++ 源码，必须存在
`rust_engine/deps/bullet3/src/LinearMath` 等目录；只有空的 `bullet3` 目录无法构建。
IDEA 的 Cargo 同步和 NeoForge Client 启动都会执行此脚本。

如果项目是 Git 克隆，在项目根目录执行：

```bash
git submodule update --init --recursive
```

如果项目来自源码 ZIP、没有 `.git`，且 `rust_engine/deps/bullet3` 为空或不存在，
在项目根目录执行以下命令。这里固定到上游 `v1.0.5-1.21.4` 使用的 Bullet3 提交，
稀疏检出仅下载构建所需的 `src` 和仓库根目录文件：

```bash
git init rust_engine/deps/bullet3
git -C rust_engine/deps/bullet3 remote add origin https://github.com/bulletphysics/bullet3.git
git -C rust_engine/deps/bullet3 config remote.origin.promisor true
git -C rust_engine/deps/bullet3 config remote.origin.partialclonefilter blob:none
git -C rust_engine/deps/bullet3 sparse-checkout init --cone
git -C rust_engine/deps/bullet3 sparse-checkout set src
git -C rust_engine/deps/bullet3 fetch --depth=1 --filter=blob:none origin 63c4d67e337017f9d8b298c900e9aabdb69296e7
git -C rust_engine/deps/bullet3 checkout --detach FETCH_HEAD
```

如果 Git 下载失败，也可下载同一提交的
[Bullet3 源码归档](https://api.github.com/repos/bulletphysics/bullet3/tarball/63c4d67e337017f9d8b298c900e9aabdb69296e7)，
将归档内顶层目录中的 `src`、`LICENSE.txt` 和 `VERSION` 放入
`rust_engine/deps/bullet3/`。注意不要多嵌套一层归档目录。
已有完整依赖时无需重复初始化或下载。

依赖准备好后，可运行 `cargo check --manifest-path rust_engine/Cargo.toml` 验证，
再在 IDEA 中重新加载 Cargo 项目和 Gradle 项目。

### 构建 rust_engine

```bash
cd rust_engine
cargo build --release
```

### 构建 Minecraft Mod

在项目根目录运行：

```bash
./gradlew :fabric:build :neoforge:build
```

Windows PowerShell 使用：

```powershell
.\gradlew.bat :fabric:build :neoforge:build
```

构建会编译 Rust release 引擎，并自动将当前系统的原生库复制进 JAR。Windows x64 构建需要 x64 Rust/MSVC 工具链；在 Windows 上直接构建得到的引擎不支持其他操作系统。

可安装的重映射产物为：

- `fabric/build/libs/mmdskin-fabric-1.0.5-1.21.4-1.jar`
- `neoforge/build/libs/mmdskin-neoforge-1.0.5-1.21.4-1.jar`

`-dev`、`-dev-shadow` 和 `-sources` JAR 用于开发，不作为安装包。Release 为两个安装包添加 `-windows-x64` 文件名后缀，内嵌模组版本保持不变。

运行已有回归测试：

```bash
./gradlew :common:test
cargo test --manifest-path rust_engine/Cargo.toml --lib
```

## 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件。

## 致谢

本项目基于众多开源项目和贡献者的工作。
完整致谢请参阅 [ACKNOWLEDGMENTS.md](ACKNOWLEDGMENTS.md)。

### 核心依赖

| 库 | 许可证 | 说明 |
|----|--------|------|
| [Bullet3](https://github.com/bulletphysics/bullet3) | Zlib | MMD 刚体与关节物理 |
| [glam](https://github.com/bitshifter/glam-rs) | MIT/Apache-2.0 | 3D 数学库 |
| [mmd-rs](https://github.com/aankor/mmd-rs) | BSD-2-Clause | MMD 格式解析器 |
| [LWJGL](https://www.lwjgl.org/) / [NanoVG](https://github.com/memononen/nanovg) | BSD-3-Clause / Zlib | 空间菜单绘制与原生绑定 |

### 设计参考

| 项目 | 许可证 | 参考内容 |
|------|--------|----------|
| [KAIMyEntity](https://github.com/kjkjkAIStudio/KAIMyEntity) | MIT | 原始 Minecraft MMD 模组 |
| [KAIMyEntity-C](https://github.com/Gengorou-C/KAIMyEntity-C) | MIT | 本项目的直接前身（二次开发基础） |
| [Saba](https://github.com/benikabocha/saba) | MIT | 物理系统架构 |
| [nphysics](https://github.com/dimforge/nphysics) | Apache-2.0 | 骨骼层次设计 |
| [mdanceio](https://github.com/ReaNAiveD/mdanceio) | MIT | 动画系统 |

完整的第三方许可证信息请参阅 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

## 相关项目

- [MikuMikuDance](https://sites.google.com/view/vpvp/) - 樋口優开发的原版 MMD 软件
- [Saba](https://github.com/benikabocha/saba) - C++ MMD 库
- [Bullet3](https://github.com/bulletphysics/bullet3) - 物理引擎
