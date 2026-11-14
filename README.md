# 钱去哪了 · LedgerFlow

本地记账与现金流预测 Android 应用。记录已经花掉的钱，也安排未来要花的钱。

**配套应用 / Companion app**  
[啥时候有钱（Show Me the Money）](https://github.com/7panni/show-me-the-money)。本项目侧重日常记账、分类统计、借贷与未来计划；「啥时候有钱」保留为独立的轻量现金流预测应用，继续独立维护。两者不需要登录或云同步。

## 功能

- 快捷记账：紧凑分类、四则计算器、原位备注、人民币/美元切换；已有银行/微信/支付宝账户直接点选，其他账户下拉选择，保存前必须选账户。已有账目可用同一计算器编辑。
- 账目列表支持长按操作和左滑删除，删除前确认。
- 按月、季度、年度查看收支统计与分类饼图，可配置每月账期。
- 分类管理、按人查看借入/借出及还款明细、归档已结清往来。
- 总资产卡、人民币现金流走势和近期未来款项；账户余额可手动校准。
- 美元及数字币手动设置估值，并选择是否计入首页总资产。
- 周期收支、分期、预算和临时计划。
- 本机备份恢复；记账与现金流模块分别导入、导出、清除。
- Android 桌面固定圆形加号小部件，默认1x1，直接打开快速记账。

默认账户顺序设置：新建记账按微信→银行→支付宝→其余，跳过零余额，可在管理中上移/下移调整；全量备份保留，分域导入/清除保留本机偏好。现已纳入0.2.16并覆盖安装成功，交互仍待用户实际体验。

## 平台与构建

当前版本：`0.2.19-linked-refund`，Android 8（API 26）及以上，目标 Android 15（API 35）。应用名为「钱去哪了」，包名继续使用 `com.qiye.ledgerflow`。旧安装包冻结保留，新构建使用「钱去哪了」名称。计划表单可直接选择季度、半年或年度频率。长按支出账目可记全额或部分退款。

需要 JDK 17、Android SDK 35 和首次解析依赖时的网络连接。设置 `ANDROID_HOME`，或在本机未跟踪的 `local.properties` 中指定 `sdk.dir`。

```sh
./gradlew :shared:jvmTest :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

APK 输出：`app/build/outputs/apk/release/app-release.apk`。当前 release 使用本机构建环境的 debug 签名；自行构建的签名可能与已安装版本不同。发布密钥不在仓库内。

- `app/`：Android Compose 界面、存储、导入导出和桌面小部件。
- `shared/`：无 Android 依赖的金额、日期、借贷与预测业务层。整数最小货币单位；数字币使用 1e-8 单位。
- iPhone：保留 Kotlin Multiplatform 业务层边界，可用 `-PenableIos=true` 启用 Apple 目标；尚无本项目可安装的 iPhone 应用。

## 数据与隐私

数据存于本机，无账号、无云同步。仓库不收录用户账本、备份、数据库、密钥、手机日志、私人迁入材料或内部开发记录。测试使用自制数据；两个可选私有探针仅在显式提供环境变量时运行，常规测试会跳过它们。

原生全量/模块备份用于保留完整字段；兼容 CSV/XLSX 和「啥时候有钱」v6 JSON 交换。具体格式有不能无损表达的字段，导入前应查看预览，不能假定所有格式完全互通。详见 [数据说明](docs/DATA.md)。

首页总资产为可见账户的人民币估值合计；人民币现金流预测另有独立基点。汇率估值不会自动生成实际收支。

## 当前状态

这是持续开发中的个人应用。0.2.14 的定向业务/持久化检查及签名构建已通过，并在 Android 15 实机覆盖安装。备注键盘与列表手势仍需更多实际体验；这些记录不代表完整设备覆盖或正式商店验收。

**开发者 / Developer**  
[尚宸鸣 · Vijjādassī Shang](https://github.com/7panni)

**开发协助 / Developed with**  
Codex

**博客 / Blog**  
[7panni.com](https://7panni.com)

**项目 / Project**  
[github.com/7panni/ledgerflow](https://github.com/7panni/ledgerflow)

许可证：[MIT](LICENSE)。复用的预测核心来自用户自有 MIT 项目「啥时候有钱」；第三方说明见 [NOTICE](NOTICE) 和 [依赖清单](docs/DEPENDENCIES.md)。
