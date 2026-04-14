# SimpleHomeland

[![](https://jitpack.io/v/cxnaive/SimpleHomeland.svg)](https://jitpack.io/#cxnaive/SimpleHomeland)

一个基于 Paper/Folia 的 Minecraft 家园插件，为每位玩家提供独立的世界实例，支持维度解锁、访客权限、经济系统和管理员管理。

## 特性

- 每位玩家可拥有多个独立家园世界
- 支持三种地形：默认、空岛、超平坦
- 地狱 / 末地维度解锁与自动加载
- 细粒度访客权限系统（17 种权限开关）
- 公开 / 私有切换 + 玩家邀请机制
- 金币（XConomy）和点券（PlayerPoints）经济支持
- 完整 GUI 管理界面
- PlaceholderAPI 变量支持
- Folia 兼容
- 对外传送 API，可供其他插件调用
- 支持 H2（单机）和 MySQL（跨服）数据库
- 空闲世界自动卸载

## 依赖

| 插件 | 类型 | 说明 |
|------|------|------|
| [Worlds](https://modrinth.com/plugin/worlds) | 必需 | 世界管理 API |
| [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) | 必需 | 变量解析 |
| [XConomy](https://modrinth.com/plugin/xconomy) | 可选 | 金币经济 |
| [PlayerPoints](https://modrinth.com/plugin/playerpoints) | 可选 | 点券经济 |

## 安装

1. 确保服务端为 Paper 1.21+ 或 Folia 1.21+
2. 安装 Worlds 和 PlaceholderAPI
3. 将 `SimpleHomeland-x.x.x.jar` 放入 `plugins/` 目录
4. 启动服务器，编辑 `plugins/SimpleHomeland/config.yml` 配置

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
  auto-unload-seconds: 600       # 空闲自动卸载，0 禁用
  preload-worlds: false          # 启动时预加载所有世界

  # 费用 (设为 0 表示免费)
  creation:
    money: 1000
    points: 0
  expansion:
    money: 100
    points: 0
  nether-unlock:
    money: 500
    points: 0
  end-unlock:
    money: 1000
    points: 0

  # 世界生成
  world:
    seed: ""                     # 留空随机
    structures: true
    bonus-chest: false

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
    compileOnly("com.github.cxnaive:SimpleHomeland:v1.2.0")
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
    <version>v1.2.0</version>
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

输出：`build/libs/SimpleHomeland-1.2.0.jar`

## 许可

本项目仅供学习交流使用。
