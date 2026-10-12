# 实际界面截图记录

这些 JPG 通过浏览器截图 API 保存，没有绘图、业务数值替换或 AI 合成。
源码基线：M5 merge `f90c210ec1fdbadfb45dd30eff59def61daac9d1` 加 M6 启动/交付改动；React UI 本轮未改。
采集：2026-10-11，本机 packaged Spring Boot + Vite，隔离持久化 H2；截图中的 demo.admin/demo.viewer
是 test-classpath 工具产生的随机密码测试账号，**不是生产默认账号**。没有明文密码、Cookie、Token、私有配置或 root 日志。

| 文件               | 上下文                                                                    |
| ------------------ | ------------------------------------------------------------------------- |
| login.jpg          | 空登录表单，未输入凭据                                                    |
| dashboard.jpg      | 本地 Fake 记录统计，不是现场指标                                          |
| create.jpg         | 实际 Fake 表单，填入演示文字但截图时未保存                                |
| execution.jpg      | 实际已持久化 Fake SUCCESS、模拟审计、缺失物理证据说明                     |
| report.jpg         | 实际模拟报告，深色主题；NOT_COLLECTED/INSUFFICIENT_DATA，不用假的图表填空 |
| viewer.jpg         | 实际 VIEWER Session，列表可读、管理入口不可用                             |
| history.jpg        | 独立 history-demo launcher：匿名、无 DB、已结束 M1 原始归档、SHA 校验     |
| history-mobile.jpg | 390×844 响应式检查，原浏览器尺寸随后恢复                                  |

历史页面中的 ID、SHA、CPU 数值来自已公开归档；不代表当前 VM 状态。本次没有连接 VM 或执行真实故障。
截图里的 Fake 记录只是交付验收，不能替代 M1 原始证据，也不会被导入历史归档。
