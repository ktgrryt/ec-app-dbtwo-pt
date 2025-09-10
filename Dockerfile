### ---------- build stage ----------
FROM maven:3.8-openjdk-17 AS builder
WORKDIR /app

COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
RUN mvn -q package -DskipTests

# --- Db2 JDBC driver を取得（classifier なしで OK）---
RUN mvn -q dependency:copy \
      -Dartifact=com.ibm.db2:jcc:11.5.9.0 \
      -DoutputDirectory=target/db2

### ---------- runtime stage ----------
FROM icr.io/appcafe/websphere-liberty:kernel-java17-openj9-ubi

# Db2 ドライバーを shared lib へ配置
COPY --chown=1001:0 --from=builder /app/target/db2/jcc-11.5.9.0.jar \
     /opt/ol/wlp/usr/shared/resources/db2/

# Liberty 設定
COPY --chown=1001:0 src/main/liberty/config/ /config/
RUN features.sh

# アプリ WAR
COPY --chown=1001:0 --from=builder /app/target/*.war /config/apps/
RUN configure.sh
