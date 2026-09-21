# ============================================================
# 后端镜像：Spring Boot 4 + JDK 21（多阶段构建，最终镜像只含 JRE）
#
# 构建（构建上下文必须是项目根目录，即 pom.xml 所在目录）：
#   docker build -t lion-agent-backend:1.0.0 .
#
# 运行（示例，连本机中间件需换成对应地址）：
#   docker run -d --name lion-backend -p 8080:8080 \
#     -e SPRING_PROFILES_ACTIVE=prod \
#     -e DB_URL='jdbc:mysql://mysql:3306/lion_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true' \
#     -e DB_USERNAME=root -e DB_PASSWORD=root123456 \
#     -e REDIS_HOST=redis -e REDIS_PASSWORD=123456 \
#     -e MILVUS_HOST=milvus-standalone -e QWEN_API_KEY=sk-xxx \
#     -v lion-upload:/app/upload \
#     lion-agent-backend:1.0.0
# ============================================================

# ---------------- 阶段一：Maven 编译 ----------------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 先只复制 pom.xml 拉依赖，利用 Docker 层缓存：依赖不变时不会重复下载
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

# 再复制源码打包（跳过测试，与 bin/package-backend.bat 一致）
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests


# ---------------- 阶段二：运行 ----------------
FROM eclipse-temurin:21-jre-jammy

# 中文字体/时区/UTF-8 编码（日志、文档解析涉及中文）
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LION_UPLOAD_PATH=/app/upload/

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app

# fat jar 直接拷入（target/*.jar 只匹配可执行 jar，不含 .jar.original）
COPY --from=builder /build/target/*.jar /app/app.jar

# 上传文件持久化目录（知识库文档 / 多模态图片），对应 lion.upload.path
RUN mkdir -p /app/upload && chmod 755 /app/upload
VOLUME /app/upload

EXPOSE 8080

# JVM 参数可用 JAVA_OPTS 覆盖；默认 prod 环境（dev/prod 见 application-*.yml）
ENV JAVA_OPTS=-Xms512m -Xmx1024m -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom
ENV SPRING_PROFILES_ACTIVE=prod

# 配置文件：把宿主 .env 挂到 /app/.env 即可被 spring.config.import 读取
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} -jar /app/app.jar"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD bash -c 'echo > /dev/tcp/127.0.0.1/8080' || exit 1
