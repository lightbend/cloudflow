FROM lightbend/sparkoperator:2.0.7-cloudflow-spark-2.4.5-1.1.2-scala-2.12

RUN rm -rf /opt/java/openjdk; \
    curl -LfsSo /tmp/openjdk.tar.gz https://github.com/AdoptOpenJDK/openjdk8-binaries/releases/download/jdk8u242-b08/OpenJDK8U-jre_x64_linux_hotspot_8u242b08.tar.gz; \
    mkdir -p /opt/java/openjdk; \
    cd /opt/java/openjdk; \
    tar -xf /tmp/openjdk.tar.gz --strip-components=1; \
    rm -rf /tmp/openjdk.tar.gz;
