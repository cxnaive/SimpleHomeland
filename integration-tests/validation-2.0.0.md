# 2.0.0 发布验证

验证日期：2026-10-10。此前开发构建标记为 1.10.0 / 1.10.1 / 1.10.2；正式版本统一升为 2.0.0，因为 Java / Minecraft 最低要求、Worlds API 和家园存储布局都已改变。此次版本调整不改变已经完成验证的迁移、加载、传送或保存实现。

正式要求为 Java 25、Paper/Folia 26.2、Worlds 4.4.1、PlaceholderAPI 2.12.3+，XConomy 改为硬依赖。家园命名空间固定为 homelands，数据库 world_key 和旧世界 UUID 保留，活动目录改为主世界 dimensions 下的标准布局。默认首次访问时按需复制和转换，启动全量迁移通过 migration-on-startup 显式启用；原始目录始终保留为备份。

验证依据：

- [迁移、数据库、API、HuskHomes、容器保护和传送门验证](validation-1.10.0.md)
- [可选启动全量迁移、串行卸载、中断恢复和 UUID 补写验证](validation-1.10.1.md)
- [真实 home / warp 命令、自动卸载、再次进入、重启和刚转换世界停服保存验证](validation-1.10.2.md)

2.0.0 构建执行 Gradle build / integrationProbeJar，37 项单元测试通过；检查 JAR 内的 plugin.yml 版本为 2.0.0。历史记录保留实际运行时的版本标记。

测试范围仍为本地 Folia 26.2 + H2，以及此前记录中的 MariaDB / MySQL 驱动验证。Paper、完整代理跨服链路与未接入家园 API 的其他插件没有追加运行验证。
