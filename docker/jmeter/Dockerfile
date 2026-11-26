# Build context is the repository root. One image serves both the CLI runner and the web controller.
# The jar is platform-independent: always build it natively, even for cross-platform images.
FROM --platform=$BUILDPLATFORM maven:3-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY samplers/pom.xml samplers/
COPY controller/pom.xml controller/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY samplers/src samplers/src
COPY controller/src controller/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package

# JMeter itself is platform-independent too: download and verify it natively. Extracting under
# QEMU emulation fails on Apple Silicon ("Cannot open: Function not implemented").
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jre AS jmeter
ARG JMETER_VERSION=5.6.3
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
 && base="https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" \
 && curl -fsSL "$base" -o /tmp/jmeter.tgz \
 && curl -fsSL "$base.sha512" | awk '{print $1"  /tmp/jmeter.tgz"}' | sha512sum -c - \
 && tar -xzf /tmp/jmeter.tgz -C /opt \
 && mv /opt/apache-jmeter-${JMETER_VERSION} /opt/apache-jmeter

FROM eclipse-temurin:21-jre
ENV JMETER_HOME=/opt/apache-jmeter
COPY --from=jmeter /opt/apache-jmeter ${JMETER_HOME}
COPY --from=build /src/samplers/target/ad-loadtest.jar ${JMETER_HOME}/lib/ext/
# Web controller: same image, started with the "controller" entrypoint override in compose.
COPY --from=build /src/controller/target/ad-loadtest-controller.jar /opt/ad-loadtest/controller.jar
# Baked-in test plan so Kubernetes deployments only need to supply properties and krb5.conf.
COPY jmeter/ad-auth.jmx /opt/ad-loadtest/plan/ad-auth.jmx
ENV PATH="${JMETER_HOME}/bin:${PATH}" \
    KRB5_CONF=/config/krb5.conf \
    HEAP="-Xms512m -Xmx2g"
WORKDIR /work
ENTRYPOINT ["/bin/sh", "-c", "export JVM_ARGS=\"-Djava.security.krb5.conf=${KRB5_CONF} -Djavax.security.auth.useSubjectCredsOnly=true ${JVM_EXTRA:-}\"; exec jmeter \"$@\"", "jmeter"]
