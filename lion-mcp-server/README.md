# lion-mcp-server

> `lion-agent` 聚合工程的子模块，parent 为根目录的 `lion-parent`。
> 在工程根目录执行 `mvn clean package` 会连同主应用、lion-a2a-server 一起构建；
> 只构建本模块用 `mvn -pl lion-mcp-server -am clean package`。

MCP Server 演示项目。同一份代码、同一个 jar，通过 profile 切换 **stdio** 与 **streamable-http** 两种传输方式，配合 `lion-agent`（MCP Client）演示完整调用链路。

- Spring Boot `4.0.7` + Spring AI `2.0.1`（与 lion-agent 严格对齐，避免协议/序列化差异）
- Java 21

## 提供的工具

| 工具名 | 参数 | 说明 |
| --- | --- | --- |
| `getServerConfig` | 无 | 返回服务端运行配置（名称/版本/profile/传输方式/端点），并打印调用日志 |
| `calculate` | `left` `right` `operator` | 两数四则运算 |
| `textTransform` | `text` `mode` | 大小写转换 / 反转 |
| `whoAmI` | 无 | 打印客户端透传过来的 `_meta`（含 `user-id`），用于验证上下文透传 |

## 构建

```bash
mvn clean package -DskipTests
# 产物：target/lion-mcp-server.jar（finalName 已固定为 lion-mcp-server）
```

## 启动方式一：stdio

客户端以**子进程**方式拉起本 jar，通过 stdin/stdout 交换 JSON-RPC 消息。无需端口、无需单独部署。

```bash
java -jar target/lion-mcp-server.jar --spring.profiles.active=stdio
```

> stdio 模式下 stdout 是协议通道，日志全部走 stderr + `./logs/lion-mcp-server.log`（见 `logback-spring.xml`）。
> **不要在代码里用 `System.out.println`**，否则客户端解析失败。

## 启动方式二：streamable-http

独立监听 `8085`，客户端通过 URL 连接。

```bash
java -jar target/lion-mcp-server.jar --spring.profiles.active=http
```

验证服务已就绪：

```bash
curl http://localhost:8085/mcp
```

## 在 lion-agent（Client）中接入

编辑 `lion-agent/src/main/resources/application.yml` 的 `spring.ai.mcp.client` 节点。

**方式 A：stdio（连接定义放在 JSON 里）**

`lion-agent/src/main/resources/mcp-servers.json`（随 jar 打包）：

```json
{
  "mcpServers": {
    "lion-demo-stdio": {
      "command": "java",
      "args": [
        "-Dspring.profiles.active=stdio",
        "-jar",
        "E:/xxkfz/lion-agent/lion-mcp-server/target/lion-mcp-server.jar"
      ]
    }
  }
}
```

`application.yml` 引用它：

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers-configuration: classpath:mcp-servers.json
```

> 必须是**两层**结构，带 `"mcpServers"` 外层——Spring AI 按
> `Map<String, Map<String, Parameters>>` 解析，少这层会启动失败：
> `Cannot construct instance of Parameters ... from String value ('java')`。
> 顶层是分组名（沿用 Claude Desktop 约定），代码会把所有分组展平合并。
> 也不要给子进程设 `JAVA_TOOL_OPTIONS`——JVM 会把 `Picked up ...` 打到 stdout，污染 JSON-RPC 通道。

**方式 B：streamable-http**

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            lion-demo-http:
              url: http://localhost:8085
              endpoint: /mcp
```

> **两种方式不要同时启用**：同一批工具会被注册两遍，Spring AI 会抛
> `Multiple tools with the same name`。要对比时，注释掉其中一种再启动 lion-agent。

## 验证连通

启动后在 lion-agent 的对话里问：

- “这个 MCP 服务是怎么配置的” → 命中 `getServerConfig`
- “123 乘以 456 等于多少” → 命中 `calculate`
- “把 Hello MCP 转成大写” → 命中 `textTransform`
- “我是谁” → 命中 `whoAmI`，应能看到客户端透传的 `user-id`

观察 lion-agent 日志中 `[AI-TOOL]` 段，会打印模型发起的工具调用与工具返回结果。

## 上下文透传链路

```
ChatLlmSupport  .toolContext(Map.of(USER_ID_KEY, userId))
        ↓
ToolContextToMcpMetaConverter（lion-agent/AiConfig）
        ↓  返回 Map<String,Object>
MCP 请求的 _meta 字段
        ↓
DemoTools.whoAmI(McpMeta meta)
```

注意 `lion-agent` 侧必须调用 `.toolContext(...)` 放入 `user_id`，`whoAmI` 才拿得到值。

## 目录结构

```
lion-mcp-server
├── pom.xml
├── README.md
├── build.bat
└── src/main
    ├── java/com/lion/mcp/server
    │   ├── McpServerApplication.java
    │   ├── tool/DemoTools.java
    │   └── auth/McpAuthVerifier.java
    └── resources
        ├── application.yml          # 公共：服务名/版本/类型/注解扫描
        ├── application-stdio.yml    # stdio：关 Web 容器 + 关 Banner
        ├── application-http.yml     # http：端口 8085 + /mcp 端点
        └── logback-spring.xml       # 按 profile 分流日志输出目标
```
