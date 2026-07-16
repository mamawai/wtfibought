# sim 主应用：用户/交易/WS 网关（取代旧 Dockerfile-oracle/example）
# JDK 非 JRE：保留 jstack/jmap 诊断工具
FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app
RUN mkdir -p /dumps
COPY target/*.jar app.jar
EXPOSE 8080

# WS 连接多，直接内存 1g；堆 3g
ENV JAVA_XMS=512m \
    JAVA_XMX=3g \
    JAVA_MAX_DIRECT_MEM=1g \
    JAVA_CODE_CACHE=128m \
    JAVA_METASPACE=256m

# sh -c 支持 ${} 替换；ExitOnOOM 崩溃即退，交给 restart 拉起
ENTRYPOINT ["sh", "-c", \
    "java \
    -Xms${JAVA_XMS} -Xmx${JAVA_XMX} \
    -XX:MaxDirectMemorySize=${JAVA_MAX_DIRECT_MEM} \
    -XX:ReservedCodeCacheSize=${JAVA_CODE_CACHE} \
    -XX:MaxMetaspaceSize=${JAVA_METASPACE} \
    -XX:+UseG1GC \
    -XX:+ExitOnOutOfMemoryError \
    -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps \
    -Xlog:gc:file=/tmp/gc.log:time,uptime:filecount=3,filesize=5m \
    ${JAVA_OPTS:-} \
    -jar app.jar"]
