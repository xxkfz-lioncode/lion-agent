# ============================================================
# 后端镜像：Spring Boot 4 + JDK 21（只含 JRE，jar 由外部提前打包好）
#
# 前置步骤：先在本机打包（或用 bin/package-backend.bat），产出 target/*.jar
#   mvn -B clean package -DskipTests
#
# 构建镜像（构建上下文必须是项目根目录，因为要读 target/*.jar）：
#   docker build -t lion-agent-backend:1.0.0 .
#
# 运行（更推荐 docker compose --profile app up -d，见 docker-compose.yml）：
#   docker run -d --name lion-backend -p 8080:8080 \
#     -e DB_URL='jdbc:mysql://host:3306/lion_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true' \
#     -e DB_USERNAME=xxx -e DB_PASSWORD=xxx \
#     -e REDIS_HOST=redis -e REDIS_PASSWORD=xxx \
#     -e MILVUS_HOST=milvus-standalone -e QWEN_API_KEY=sk-xxx \
#     -e LOG_LEVEL=info -e SPRINGDOC_ENABLED=false \
#     -v lion-upload:/app/upload \
#     lion-agent-backend:1.0.0
#
# 配置说明：application.yml 是全环境公共配置，application-dev.yml 是本地开发档。
# 容器内如需彻底禁用 dev 档，设 SPRING_PROFILES_ACTIVE=default。
# ============================================================

FROM eclipse-temurin:21-jre-jammy

# 时区 / 中文编码（日志、文档解析涉及中文）；上传目录对应 lion.upload.path
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LION_UPLOAD_PATH=/app/upload/ \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app

# 拷入本机已打好的可执行 jar（target/*.jar 只匹配 fat jar，不含 .jar.original；
# 若目录里有多个 jar，请把通配符换成确切文件名）
COPY target/*.jar /app/app.jar

# 上传文件持久化目录（知识库文档 / 多模态图片）
RUN mkdir -p /app/upload && chmod 755 /app/upload
VOLUME /app/upload

EXPOSE 8080

# 配置档与业务参数全部走环境变量（Spring 自动做宽松绑定，无需 -D 传递）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

# 端口探活（不依赖 curl/wget）：SpringBoot 启动较慢，留足 120s 启动期
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
    CMD bash -c 'echo > /dev/tcp/127.0.0.1/8080' || exit 1
