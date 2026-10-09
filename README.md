# SimpleHomeland

[![](https://jitpack.io/v/cxnaive/SimpleHomeland.svg)](https://jitpack.io/#cxnaive/SimpleHomeland)

一个基于 Paper/Folia 的 Minecraft 家园插件，为每位玩家提供独立的世界实例，支持维度解锁、访客权限、经济系统和管理员管理。

## 特性

- 每位玩家可拥有多个独立家园世界
- 支持三种地形：默认、空岛、超平坦
- 世界重置（重新生成，可选新地形类型）
- 地狱 / 末地维度解锁与自动加载
- 细粒度访客权限系统（18 种权限开关）
- 公开 / 私有切换 + 玩家邀请机制
- 阶梯式创建与扩展费用
- 金币（XConomy）和点券（PlayerPoints）经济支持
- 完整 GUI 管理界面
- 世界难度设置（和平/简单/普通/困难）
- PlaceholderAPI 变量支持
- Folia 兼容
- 对外传送 API，可供其他插件调用
- 支持 H2（单机）和 MySQL（跨服）数据库
- 空闲世界自动卸载 / 启动时预加载
- 可配置出生高度（默认/空岛/超平坦）

## 依赖

| 插件 | 类型 | 说明 |
|------|------|------|
| [Worlds](https://modrinth.com/plugin/worlds) | 必需 | 世界管理 API |
| [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) | 必需 | 变量解析 |
| [XConomy](https://modrinth.com/plugin/xconomy) | 必需 | 金币经济 |
| [PlayerPoints](https://modrinth.com/plugin/playerpoints) | 可选 | 点券经济 |
| [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) | 可选 | 家具/自定义方块保护 |

## 安装

1. 确保服务端为 Java 25、Paper 26.2+ 或 Folia 26.2+
2. 安装 Worlds 4.4.1、PlaceholderAPI 2.12.3+ 和 XConomy
3. 将 `SimpleHomeland-x.x.x.jar` 放入 `plugins/` 目录
4. 启动服务器，编辑 `plugins/SimpleHomeland/config.yml` 配置

### 从 Worlds 3 升级

2.0 系列要求 Java 25、Paper/Folia 26.2 和 Worlds 4.4.1。升级前停服并备份数据库和世界目录，同时更新依赖插件。服务端要求和家园存储布局均有变化，因此本次升级使用大版本号。

家园使用固定命名空间 `homelands`，目录为 `<主世界>/dimensions/homelands/<world_key>`。数据库里的 `world_key` 不变；首次加载旧家园时，插件复制旧 `homelands/<world_key>` 到迁移副本，再通过 Paper 官方迁移转换存档并校验 UUID。原目录保留为备份，不作为新版活动世界，也不参与孤儿世界扫描。迁移中断时会隔离未完成的副本并重试；已完成迁移后目录缺失会报错，不会从旧备份重新生成家园。

迁移使用 `.pending` / `.complete` 记录完成状态。转换和加载设置完成前，世界即使已出现在 Bukkit 中也不能进入；迁移失败且卸载失败时继续禁止进入。排除故障、成功卸载后可重新加载，重启也会恢复未完成标志。加载状态查询和跨服已加载列表只报告可以使用的世界。

主世界重置复用现有 `overworld_reset_pending` 和 `world_uuid` 记录阶段：准备重置时清空 UUID；旧数据清理、新世界生成及保存成功后记录新 UUID；最后清除 pending。恢复只认可这条生成记录，不能仅凭磁盘 UUID 变化判定成功。2.0.1 没有新增数据库列。

默认仍是首次访问时按需转换。若希望启动时统一转换，在家园主服务器设置 `homeland.migration-on-startup: true` 并重启。插件启用、数据库索引加载完成后，会异步检查所有已登记家园的主世界和已解锁的地狱 / 末地；每次只转换一个维度，写入 UUID 后保存并卸载，再处理下一个。控制台会输出总数、逐个结果，并每 10 秒报告进度、当前世界和耗时；迁移期间暂停家园创建、世界操作和传送。已完成世界会跳过，转换失败会记录 key 并继续其他世界，卸载失败则停止批次以免积压内存。下次启动可重试未完成转换，或关闭选项继续按需转换。此选项与 `preload-worlds` 独立：后者启用时会在转换结束后再预加载主世界。配置重载不会启动全量转换，需重启。

数据库自动新增 `nether_uuid`、`end_uuid` 两列，用于未加载维度的 UUID 查询。已有家园记录、邀请和设置保留。Bukkit 世界名称变为 `homelands_<world_key>`，家园显示名称不变；主世界、地狱和末地的 UUID 在迁移中保留。使用 UUID 的 HuskHomes home、warp、back 记录可继续定位原世界。仅依赖旧 Bukkit 世界名的其他插件需要检查各自的世界映射。

对外传送 API 的方法签名、回调枚举保持兼容。按 UUID 或 key 调用时会保留目标维度和坐标，包含跨服请求；key 支持裸 `world_key`、`homelands:world_key`、旧 `simplehomeland:world_key` 和新版 Bukkit 世界名。传送回调在玩家所在的 region 线程执行。

按名称查询时优先匹配完整家园显示名称，再尝试旧维度别名和 Bukkit 世界名；包含“ - 地狱 / 末地”后缀的真实家园名称不会被截断。Paper 的末地回程使用有效的玩家重生点，无有效重生点时回到家园主世界出生点，并遵守服务端的终末之诗设置。

按需加载后，主世界、地狱、末地共用该家园的空闲计时；加载任意维度都会刷新计时，任意维度有玩家时保留整个家园。插件每 60 秒检查一次，达到 `auto-unload-seconds` 后保存并卸载已加载的维度。再次访问使用转换后的活动目录，原 UUID 和新修改都保留，旧备份不会被重新导入。启用 `preload-worlds` 时关闭自动卸载。

## 命令

### 玩家命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/homeland` | `simplehomeland.use` | 打开主菜单 GUI |
| `/homeland create <名称> [default\|void\|flat]` | `simplehomeland.homeland.create` | 创建家园 |
| `/homeland delete <名称>` | `simplehomeland.homeland.delete` | 删除家园 |
| `/homeland home <名称>` | `simplehomeland.homeland.home` | 传送到家园 |
| `/homeland list` | `simplehomeland.homeland.list` | 查看家园列表 |
| `/homeland border <名称> expand` | `simplehomeland.homeland.border` | 扩展边界 |
| `/homeland unlock <名称> <nether\|end>` | `simplehomeland.homeland.unlock` | 解锁维度 |
| `/homeland lock <名称> <nether\|end>` | `simplehomeland.homeland.unlock` | 删除维度 |
| `/homeland invite <名称> <玩家>` | `simplehomeland.use` | 邀请玩家 |
| `/homeland uninvite <名称> <玩家>` | `simplehomeland.use` | 取消邀请 |
| `/homeland public <名称>` | `simplehomeland.use` | 切换公开/私有 |
| `/homeland back` | `simplehomeland.use` | 返回大厅 |

### 管理员命令

| 命令 | 说明 |
|------|------|
| `/homeland admin` | 打开管理 GUI |
| `/homeland admin create <玩家> <名称> [地形]` | 为玩家创建家园 |
| `/homeland admin delete <玩家> <名称>` | 删除玩家家园 |
| `/homeland admin tp <玩家> <名称>` | 传送到玩家家园 |
| `/homeland admin border <玩家> <名称> expand` | 扩展玩家边界 |
| `/homeland admin unlock <玩家> <名称> <nether\|end>` | 解锁维度 |
| `/homeland admin lock <玩家> <名称> <nether\|end>` | 删除维度 |
| `/homeland reload` | 重载配置 |

别名：`/hl`

## 权限

| 权限 | 默认 | 说明 |
|------|------|------|
| `simplehomeland.use` | OP | 基础命令 |
| `simplehomeland.homeland.create` | OP | 创建家园 |
| `simplehomeland.homeland.delete` | OP | 删除家园 |
| `simplehomeland.homeland.home` | OP | 传送到家园 |
| `simplehomeland.homeland.list` | OP | 查看列表 |
| `simplehomeland.homeland.border` | OP | 扩展边界 |
| `simplehomeland.homeland.unlock` | OP | 解锁维度（含地狱+末地） |
| `simplehomeland.homeland.unlock.nether` | OP | 解锁/删除地狱 |
| `simplehomeland.homeland.unlock.end` | OP | 解锁/删除末地 |
| `simplehomeland.admin` | OP | 管理员权限 |
| `simplehomeland.reload` | OP（继承） | 重载配置 |

## PlaceholderAPI 变量

| 变量 | 说明 |
|------|------|
| `%simplehomeland_count%` | 家园数量 |
| `%simplehomeland_max%` | 最大家园数 |
| `%simplehomeland_list%` | 家园名称列表 |
| `%simplehomeland_has_homeland%` | 是否有家园 |
| `%simplehomeland_border_<名称>%` | 指定家园边界半径 |
| `%simplehomeland_has_nether_<名称>%` | 是否解锁地狱 |
| `%simplehomeland_has_end_<名称>%` | 是否解锁末地 |
| `%simplehomeland_is_public_<名称>%` | 是否公开 |
| `%simplehomeland_current%` | 当前所在世界信息 |
| `%simplehomeland_current_owner%` | 当前家园主人（大厅时显示配置值） |
| `%simplehomeland_difficulty_<名称>%` | 指定家园难度（peaceful/easy/normal/hard） |

## 访客权限（19 种）

| 分类 | 权限 | 默认 |
|------|------|------|
| 方块 | place（放置，含倒桶/放置船、矿车、盔甲架等实体）/ break（破坏，含装桶） | 禁止 |
| 交互 | interact（交互）/ container（容器）/ anvil（铁砧）/ enchant（附魔） | 禁止 |
| 物品 | pickup（拾取）/ drop（丢弃） | 允许 |
| 战斗 | kill_animal（攻击动物）/ kill_monster（攻击怪物）/ pvp（对战） | 禁止(动物)/允许(怪物)/禁止(对战) |
| 其他 | farming（农业）/ vehicle（载具）/ bed（床）/ trade（交易）/ projectile（投掷/射击） | 禁止 |
| 实体/家具 | entity（实体/家具互动：盔甲架换装、展示框旋转、拴绳、坐家具、装桶生物、CraftEngine 家具互动）/ entity_damage（实体/家具破坏：拆除盔甲架、展示框、画、船、矿车、末影水晶、CraftEngine 家具） | 禁止 |

### CraftEngine 集成

安装 CraftEngine 后自动接入其 AntiGrief SPI，无需配置：

- 拆/放 CraftEngine 家具 → 受 `entity_damage` / `place` 约束
- 右键互动家具（坐沙发、切换形态等）→ 受 `entity` 约束
- CraftEngine 自定义方块（门、按钮、压力板、容器、储物家具等）→ 受 `interact` / `container` 约束
- ItemsAdder 等以盔甲架/展示框为载体的家具由通用实体层覆盖，无需额外配置

## 配置

```yaml
# 数据库 (h2 / mysql)
database:
  type: h2
  h2:
    file: homeland
  mysql:
    host: localhost
    port: 3306
    database: homeland
    user: root
    password: ""
    pool-size: 5

# 家园设置
homeland:
  max-homelands: 1
  default-border-radius: 500
  max-border-radius: 5000
  border-expand-step: 100
  world-type: default            # default / void / flat
  default-difficulty: hard       # peaceful / easy / normal / hard
  auto-unload-seconds: 600       # 空闲自动卸载，0 禁用
  preload-worlds: false          # 启动时预加载所有世界（启用后常驻内存）
  migration-on-startup: false    # 可选启动全量转换旧世界，默认仍按需转换

  # 阶梯式创建费用（超出列表取最后一档，设 0 免费）
  creation:
    tiers:
      - money: 1000
        points: 0
      - money: 2000
        points: 0
      - money: 5000
        points: 10

  # 阶梯式扩展费用（按当前半径区间计费）
  expansion:
    tiers:
      - min-radius: 0
        money: 100
        points: 0
      - min-radius: 1000
        money: 200
        points: 0
      - min-radius: 2000
        money: 500
        points: 5

  nether-unlock:
    money: 500
    points: 0
  end-unlock:
    money: 1000
    points: 0

  # 世界重置费用
  reset:
    overworld:
      money: 500
      points: 0
    nether:
      money: 300
      points: 0
    end:
      money: 300
      points: 0

  # 世界生成
  world:
    seed: ""                     # 留空随机
    structures: true
    bonus-chest: false
    spawn-y:
      default: -60               # 自动检测（此值为 fallback）
      void: 63                   # 空岛起始台高度
      flat: -60                  # 超平坦出生高度

# 命令拦截
command-block:
  enabled: false
  blocked-commands:
    - tp
    - tpa
    - tpaccept
    - home
    - spawn
    - warp
    - back
  block-owner: false

# 大厅访客权限 (JSON，留空使用默认值)
lobby-visitor-flags: ""
```

## 对外 API

### 引入依赖

**Gradle Kotlin DSL：**

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.cxnaive:SimpleHomeland:v2.0.1")
}
```

**Maven：**

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.cxnaive</groupId>
    <artifactId>SimpleHomeland</artifactId>
    <version>v2.0.1</version>
    <scope>provided</scope>
</dependency>
```

**plugin.yml：**

```yaml
softdepend: [SimpleHomeland]
```

### 调用示例

```java
import dev.user.homeland.api.SimpleHomelandAPI;
import dev.user.homeland.api.TeleportResult;

// 按 owner UUID + 家园名称传送（传送到出生点）
SimpleHomelandAPI.teleportToHomeland(player, ownerUuid, "家园名", false, result -> {
    if (result == TeleportResult.SUCCESS) {
        player.sendMessage("传送成功！");
    }
});

// 按 worldKey 传送到指定坐标
Location loc = new Location(world, 100, 64, 200);
SimpleHomelandAPI.teleportToHomelandByWorldKey(player, worldKey, false, loc, result -> {
    // ...
});

// 按 worldUUID 传送（支持未加载世界自动加载）
SimpleHomelandAPI.teleportToHomelandByWorldUUID(player, worldUUID, false, result -> {
    // ...
});
```

### TeleportResult

| 值 | 说明 |
|----|------|
| `SUCCESS` | 传送成功（家园世界） |
| `SUCCESS_OTHER_WORLD` | 传送成功（非家园 Bukkit 世界） |
| `ACCESS_DENIED` | 权限不足 |
| `WORLD_NOT_FOUND` | 家园/世界不存在 |
| `WORLD_LOAD_FAILED` | 世界加载失败 |
| `PLAYER_OFFLINE` | 玩家已下线 |

## 构建

```bash
./gradlew shadowJar
```

输出：`build/libs/SimpleHomeland-2.0.1.jar`

## 许可

本项目仅供学习交流使用。
