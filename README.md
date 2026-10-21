# EarthRPGItem

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.5%20~%201.21.x-green.svg)](https://www.minecraft.net/)
[![Paper](https://img.shields.io/badge/Paper-API-blue.svg)](https://papermc.io/)

一个面向 Minecraft **Paper 1.20.5 ~ 1.21.x** 服务端的 RPG 物品框架插件：RPG 武器 / 防具 / 工具 / 饰品、技能系统、强化台、宝石镶嵌、采集系统、伤害演示假人。

> **开发中版本** `3.2bate`：接口与配置仍可能变化，请在生产环境使用前充分测试。

---

## 特性

### 物品系统
- **属性化物品**：攻击、防御、暴击、暴伤、攻速、吸血、元素伤害（冰 / 火 / 光 / 暗）、闪避、格挡、招架、反击、真伤、背刺等数十种属性
- **随机属性**：每次使用重新 roll 数值区间
- **多品质 / 强化**：强化台提升等级与词条
- **宝石镶嵌**：独立宝石系统，影响属性与技能等级
- **饰品栏**：独立槽位，支持副手绑定（`deputy`）
- **灵魂绑定**：物品绑定拥有者，防转手
- **耐久扩展**：自定义额外耐久与损毁上限
- **自定义外观**：通过 `custom-model-data` 挂接资源包模型

### 技能系统
- **独立技能文件**：每个技能一个 YAML（`plugins/EarthRPGItem/skills/*.yml`），文件名即技能 ID，**放进目录即自动注册**，无需改 config
- **技能组互斥**：`普通攻击` / `技能组1` ~ `技能组4` 为互斥槽位，配置在哪个组由该组键位触发
- **8 个触发位**：`Q` / `F` / `R` / `L` 及其 `Shift+` 变体
- **等级成长**：任意技能的任意数值字段都可按等级成长（`leveling.per-level`），**不改代码即可让新技能支持升级**
- **升级资源双模式**：
  - 接入 EarthExp → 消耗天赋点
  - 未接入 → 消耗宝石
- **内置技能类型**：近战挥砍、剑气、弓/短弓、投射物爆炸、范围爆发、法阵、护盾、位移、召唤等

### 其他
- **采集系统**：资源点、采掘等级门槛、采集经验事件
- **伤害假人**：可生成、可配置的测试目标
- **斩击 / 拖尾粒子**：高度可配置
- **中英文双语注释**：源码内注释与文档均为中文，配置项提供英文别名

---

## 环境要求

| 项 | 版本 |
|---|---|
| 服务端 | **Paper 1.20.5 ~ 1.21.x**（或兼容的 fork，如 Purpur） |
| Java | **21**（Paper 1.20.5+ 的 API 本身要求 Java 21，无法降低） |
| 构建工具 | Gradle 8.x（仓库自带 wrapper） |
| 可选 | [EarthExp](https://github.com/) 0.5.1+（天赋点模式） |

> **跨版本说明**：本项目以 **1.20.6 的 paper-api 编译**，在 1.21.x 上运行。
> 这是刻意为之 —— 用最低支持版本编译，任何误用高版本专有 API 的代码都会在**编译期**报错，
> 而不是等到用户的服务器上才抛 `NoSuchMethodError`。
>
> 已处理的版本差异集中在 `util/AttributeModifiers.java`：1.21 起 `AttributeModifier`
> 改用 `NamespacedKey` 作标识，1.20.x 只有 `UUID`。该适配层在运行时自动选择正确的构造方式。
> 另外 `DamageCause.CAMPFIRE` 只在 1.21+ 存在，已改为按名字解析、老版本自动跳过。

---

## 构建

```bash
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

产物：`build/libs/EarthRPGItem-3.2bate.jar`

> `settings.gradle` 配置了阿里云 / 腾讯云镜像，国内构建更快。若你在墙外或想用官方源，可自行删掉这几行 `maven { url = ... }`。

---

## 安装

1. 把 jar 放入 `plugins/`
2. 启动服务器（首次启动会生成 `plugins/EarthRPGItem/` 配置与 `skills/` 示例技能）
3. 修改配置后执行 `/rpgitem reload`

### 依赖说明

- **EarthExp 是可选附属**，不装也能正常使用（技能升级改为消耗宝石）
- 本插件与 EarthExp **互为 `softdepend`（成环）**，加载顺序由 Bukkit 任意决定
- 插件不依赖加载顺序：通过 EarthExp **自己的类加载器**解析类，并监听其启用事件**自动重试接入**
- 接入状态查看：
  - 启动日志会写明「已接入 / 未检测到 / 服务没注册」
  - 游戏内 `/rpgitem skillcheck`

---

## 配置

主配置：`plugins/EarthRPGItem/config.yml`

### 目录结构

```
plugins/EarthRPGItem/
├── config.yml              # 主配置（功能开关、EarthExp 段等）
├── skill-leveling.yml      # 技能升级消耗
├── enhancement.yml         # 强化台
├── gemstones.yml           # 宝石定义
├── gathering.yml           # 采集系统
├── collection_level_points.yml
├── collection_level_tools.yml
├── skills/                 # 技能文件，文件名 = 技能 ID
│   └── 示例技能.yml
└── Weapon/                 # 武器定义
```

### 添加自定义技能

在 `skills/` 下新建 `你的技能名.yml`：

```yaml
display-name: "&d你的技能"
material: ENCHANTED_BOOK
skill-group: "技能组1"
supported-weapons: [SWORD]
cooldown-enabled: true
cooldown-name: "你的技能"

# 等级成长：Lv.1 用文件里的原值，每级 +5%
leveling:
  max-level: 10
  per-level:
    swing.weapon-percent: 5

description:
  - "&7技能说明第一行"
  - "&7技能说明第二行"
```

**不需要修改 config.yml** —— 插件会自动扫描 `skills/` 目录并注册。

---

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/rpgitem my` | 查看自身属性 | `earthrpgitem.my` |
| `/rpgitem skills` | 打开技能面板 | `earthrpgitem.skills` |
| `/rpgitem skillforge` | 技能强化台 | `earthrpgitem.skillforge` |
| `/rpgitem enhance` | 装备强化台 | `earthrpgitem.enhance` |
| `/rpgitem gems` | 宝石台 | `earthrpgitem.gem` |
| `/rpgitem accessorygui` | 饰品管理 | `earthrpgitem.accessory.admin` |
| `/rpgitem gathering` | 采集信息 | — |
| `/rpgitem dummy` | 伤害假人 | `earthrpgitem.dummy` |
| `/rpgitem gui` | 管理界面 | `earthrpgitem.gui` |
| `/rpgitem reload` | 重载配置 | `earthrpgitem.admin` |

别名：`/erpgitem`、`/rpgitem`

---

## 与 EarthExp 的集成

当 EarthExp 已接入时：

- **等级门槛**：物品的 `Level` 要求比对的是 **EarthExp 的战斗等级**（`Stat.COMBAT`），而非原版等级
- **未接入时自动回退**：比对原版等级 `Player#getLevel()`，保证没装 EarthExp 的服务器一切正常
- **技能升级**：消耗天赋点（默认 `COMBAT` 轨道）而非宝石

相关配置：

```yaml
features:
  level: true          # 等级门槛总开关

earthexp:
  enabled: true        # false = 强制宝石模式
  skill-point-stat: COMBAT   # 技能点走哪条轨道（COMBAT / ADVENTURE）
  api-class: ""        # 留空 = 自动探测
  stat-class: ""       # 留空 = 自动探测
```

---

## 资源包

本插件通过 `custom-model-data` 为物品提供自定义图标，**资源包需自行制作/分发给玩家**。

- 技能 YAML 支持 `custom-model-data` 字段
- 物品 YAML 支持 `custom-model-data` 字段
- 材质包需包含对应的 `models/item/*.json` 与贴图

> ⚠️ **资源路径必须全为小写 ASCII**（`[a-z0-9_.-/]`）。使用中文路径会导致整个模型文件解析失败，物品显示为紫黑格子。

---

## 开源协议

本项目采用 **[MIT License](LICENSE)**。

```
Copyright (c) 2026 Earth_lin
```

你可以自由地使用、修改、分发、商用，只需保留版权声明与许可声明。

---

## 贡献

欢迎提 Issue 与 PR。

- 提交前请确保 `gradlew build` 通过
- 新增配置项请同时更新 `src/main/java/EarthRPGItem/tips/` 下的文档
- 大改动建议先开 Issue 讨论

---

## 致谢

- [PaperMC](https://papermc.io/) —— 高性能服务端 API
- 所有提交 Issue 与建议的玩家
