# --- Build Stage ---
FROM clojure:tools-deps AS build

WORKDIR /app

# Copy deps.edn and download dependencies
COPY deps.edn ./
RUN clojure -P

# Copy the rest of the app
COPY . .

# Create secrets.edn from sample for the build
RUN cp resources/secrets.sample.edn resources/secrets.edn

# Build the uberjar
RUN clojure -X:build uber

# --- Run Stage ---
# Temurin 25 is LTS; 23 is end-of-life and no longer gets security fixes.
FROM eclipse-temurin:25-jre-alpine

# eMudhra intermediate CA key compromise (https://emudhra.we-have-a-problem.com/,
# disclosed 2026-10-20). The JVM ignores the OS trust store and uses its own
# cacerts; Temurin 23 still trusts emSign ECC Root CA - G3, 25.0.5+ doesn't.
# Fail the build if an older base ever brings it back.
RUN if keytool -list -v -cacerts -storepass changeit | grep -q '^Owner: CN=emSign ECC Root CA - G3,'; then \
      echo "emSign ECC Root CA - G3 is in the JVM truststore; use a newer base image" >&2; exit 1; \
    fi

WORKDIR /app

# Copy the uberjar from the build stage
COPY --from=build /app/target/chatgpt-on-telegram-0.0.1-standalone.jar /app/app.jar

# Run the app
CMD ["java", "-jar", "/app/app.jar"]
