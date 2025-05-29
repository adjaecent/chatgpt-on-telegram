# Use OpenJDK 23 as base
FROM openjdk:23-slim

# Set environment variables
ENV CLOJURE_VERSION=1.12.0.849 \
    DEPS_INSTALL_DIR=/opt/clojure \
    PATH="/opt/clojure:${PATH}"

# Install dependencies and Clojure CLI tools
RUN apt-get update && apt-get install -y curl bash rlwrap \
 && curl -O https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh \
 && chmod +x linux-install-${CLOJURE_VERSION}.sh \
 && ./linux-install-${CLOJURE_VERSION}.sh \
 && rm linux-install-${CLOJURE_VERSION}.sh \
 && apt-get clean && rm -rf /var/lib/apt/lists/*

# Create and set the working directory
WORKDIR /app

# Copy deps.edn and download dependencies (cache layer)
COPY deps.edn ./
RUN clojure -P

# Copy the rest of the app
COPY . ./

# Run the app (ensure you have :run alias in deps.edn)
CMD ["clojure", "-M:run"]
