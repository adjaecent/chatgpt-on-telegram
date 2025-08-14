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
FROM openjdk:23-slim

WORKDIR /app

# Copy the uberjar from the build stage
COPY --from=build /app/target/chatgpt-on-telegram-0.0.1-standalone.jar /app/app.jar

# Run the app
CMD ["java", "-jar", "/app/app.jar"]
