# Worlds 4 升级验证

探针仅用于独立测试服务器，会创建、重置或删除测试家园。不要放进正式服。

构建：`./gradlew integrationProbeJar`，输出 `build/libs/WorldsMigrationProbe.jar`。

准备 Java 25、Folia 1.21.11 + Worlds 3.12.4 + SimpleHomeland 1.9.1 的独立服务器，安装 PlaceholderAPI、XConomy 和探针。将 `homeland.max-homelands` 设为 3，使用 JVM 参数 `-Dhomeland.probe.create=true` 启动。探针创建 default、flat、void 家园及其地狱、末地，放置钻石块、装有 17 个绿宝石的箱子和带名字的盔甲架；记录 9 个世界 UUID，完成后自动关服。旧版本测试构建仅补上 H2 表名元数据大小写识别和 XConomy 缺失检查，以便测试启动和重启，不改世界创建/存储代码。

复制整个服务器到另一个独立目录，保留数据库、`homelands/` 和 `probe-*.uuid` 文件。升级 Folia 到 26.2、Worlds 到 4.4.1、SimpleHomeland 到 2.0.0、PlaceholderAPI 到 2.12.3；去掉 create 参数启动。探针按 UUID 查询并加载全部世界，检查 UUID、目录、方块、箱子物品和实体。重启后重复验证，确认迁移不再次执行。成功标记为 `PROBE_MIGRATION_PASS worlds=9`。

默认按需迁移验证：保留旧配置（不写 `migration-on-startup`），使用 `-Dhomeland.probe.lazy=true -Dhomeland.probe.shutdown=true`。探针在首次 API 加载前确认 9 个目标目录均未创建，输出 `PROBE_DEFAULT_LAZY_PASS` 后验证全部内容并关服。

启动全量迁移验证：在另一份旧存档副本设置 `homeland.migration-on-startup: true`，使用 `-Dhomeland.probe.startup=true -Dhomeland.probe.shutdown=true`。探针等到迁移完成，先检查 9 个目标目录、完成标记和旧备份；确认所有世界均已卸载、迁移期间加载峰值为 1，然后按 UUID 加载检查原内容。成功标记 `PROBE_STARTUP_MIGRATION_PASS worlds=9 peak=1`。重启加 `-Dhomeland.probe.startup-restart=true`，预期需要处理 0 个维度且峰值为 0。

中断恢复验证：在测试服停服状态，为一个旧世界写入对应 `.pending`，在 journal 的 `staging/<key>` 和活动目标目录放置不完整副本；已存在的活动世界和 `.complete` 须先移到测试备份目录。用 startup 参数重启，检查只重试该维度，不完整副本移入 `world-migration/interrupted/`，9 个 UUID 和内容仍全部通过。完成迁移后在停服状态把测试数据库某个 `nether_uuid` 清空，再次启动应只处理该维度并补回 UUID，避免数据库写入失败后被完成标记永久跳过。

安装相邻 HuskHomes 项目 `target/` 下包含 common 模块的完整 JAR。探针会先卸载地狱，再接入两个客户端，用户名为 `MigrationOwner` 和 `MigrationVisitor`。它为 Owner 授予测试用管理权限，使用和 HuskHomes 相同的反射签名传送空 world 的 Location，并检查访客被拒绝、回调在玩家 region 上。然后通过真实 HuskHomes BukkitUser，使用旧世界名及原 UUID 再次传送到卸载的地狱。成功标记为 `PROBE_HUSKHOMES_SIGNATURE_PASS`、`PROBE_API_ACCESS_DENIED_PASS`、`PROBE_REAL_HUSKHOMES_UNLOADED_PASS`。

完整命令闭环：另建一份未转换的旧存档副本，安装当前 SimpleHomeland、HuskHomes、ViaVersion / ViaBackwards；保留默认按需迁移、关闭 preload，将 `auto-unload-seconds` 临时设为 20。使用 `-Dhomeland.probe.closed-cycle=true` 启动，看到 `PROBE_CYCLE_CLIENT_READY worlds=9` 后，用 26.1 协议测试客户端登录 `MigrationOwner`。探针为该玩家授予测试服 OP 和家园管理权限，通过 HuskHomes API 建立包含旧世界名、原 UUID 和完整坐标朝向的 home / warp，然后实际执行带 `huskhomes:` 前缀的命令。

闭环先单独加载地狱并停留 65 秒，确保生产定时任务不会卸载有玩家的子维度；再遍历 3 种地形的全部 9 个维度。首轮检查旧内容，并修改一个方块和箱子物品。离开家园后等待生产的 60 秒检查任务自然保存、卸载所有维度，再用相反命令重新进入，核对原 UUID、坐标朝向、旧内容和新修改。第二次离开后再次等自动卸载，成功标记为 `PROBE_CLOSED_CYCLE_PASS commands=18 worlds=9 loads=18 unloads=18`。探针不主动调用卸载，也不缩短生产检查周期。所有完成标记时间不变，证明再次进入不会重新导入备份。

在同一测试服重启时添加 `-Dhomeland.probe.closed-cycle-restart=true` 并再次登录；探针使用已有 home / warp，核对 9 个世界的已保存修改和完成标记，成功标记为 `PROBE_CLOSED_CYCLE_RESTART_PASS commands=9 worlds=9 loads=9`。两轮结束均自动关服。用 `diff -qr` 比较测试服和旧测试服的 `homelands/`，确认旧备份未被修改。

刚转换世界的停服保存：使用另一份未转换的旧存档，启动参数为 `-Dhomeland.probe.closed-cycle=true -Dhomeland.probe.stop-after-import=true`，看到 CLIENT_READY 后登录同一测试客户端。探针通过命令转换全部 9 个维度并修改方块 / 箱子，跳过停留和空闲卸载步骤；确认 9 个世界都仍在内存、卸载事件为 0 后输出 `PROBE_STOP_AFTER_IMPORT_READY worlds=9 loads=9 unloads=0`，直接调用正常服务器停服。检查服务端停服日志保存了这 9 个世界；随后用 closed-cycle 和 closed-cycle-restart 参数重启，再登录验证原 UUID、旧内容及停服前修改。此场景不主动调用 World.save 或卸载。

使用 `-Dhomeland.probe.lifecycle=true` 验证新版创建、维度解锁、主世界重置、维度重置、删除维度、重新解锁和删除家园；可分别使用 H2 和独立 MySQL/MariaDB 数据库。成功标记为 `PROBE_LIFECYCLE_PASS`。

使用 `-Dhomeland.probe.portal=true` 验证传送门：先加载另一家园的地狱和末地，再让本家园的实体进入两种传送门，确认目标都是本家园。成功标记为 `PROBE_PORTAL_ISOLATION_PASS`。

使用 `-Dhomeland.probe.recovery=true` 验证主世界重置恢复。探针分别模拟 prepare 已提交但尚未生成新世界、以及新世界已生成但 finish 尚未提交的状态，检查恢复完成和 UUID，确保第二种情况不会再次重置。成功标记为 `PROBE_RECOVERY_PASS`。

迁移中断重试、目录冲突、原始备份保留、删除后不重新导入、锁定维度 UUID 失效、共享数据库中的维度查询和传送门等待时间由单元测试覆盖。
