# quant 量化/AI agent：策略实盘 + Graph 并行 + MCP。三进程最吃内存
# JDK 非 JRE：保留 jstack/jmap 诊断工具
FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app
RUN mkdir -p /dumps
COPY target/*.jar app.jar
EXPOSE 8082

# AI 动态代理多，Metaspace 384m；堆 4g
ENV JAVA_XMS=512m \
    JAVA_XMX=4g \
    JAVA_MAX_DIRECT_MEM=512m \
    JAVA_CODE_CACHE=128m \
    JAVA_METASPACE=384m

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
