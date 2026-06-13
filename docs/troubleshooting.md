# Troubleshooting Guide

This guide compiles solutions for common issues encountered during setup, compilation, or runtime execution of Cognalytix.

---

## 1. Java 25 / IntelliJ Compilation Issues

If IntelliJ fails to compile the backend or complains about "cannot find symbol" for Lombok-like or domain types, check the following configuration settings:

### SDK & Language Level
- **Problem**: IntelliJ is trying to build with JDK 17 or 21, but the `pom.xml` targets Java **25**.
- **Solution**:
  1. Open **File $\rightarrow$ Project Structure $\rightarrow$ Project**.
  2. Ensure the **SDK** is set to a Java 25 distribution (e.g., Eclipse Temurin 25).
  3. Ensure the **Language level** is set to **25**.
  4. Go to **Settings $\rightarrow$ Build, Execution, Deployment $\rightarrow$ Compiler $\rightarrow$ Java Compiler** and verify the target bytecode version is set to 25.

### Project Root Directory
- **Problem**: Opening the root repository folder (`Cognalytix/`) makes IntelliJ treat it as a generic project rather than a Maven project, leading to import errors.
- **Solution**: Open the **`source/`** directory directly (the folder containing `pom.xml`) as a standalone project in IntelliJ, not the root repository.

---

## 2. Ollama Connectivity Issues (`llm_unreachable`)

The backend REST API records the machine-readable error code `llm_unreachable` in `last_analysis_error` if it cannot reach the Ollama server.

### Running Inside Docker
- **Problem**: The backend container cannot access host-level Ollama at `localhost:11434`.
- **Solution**: 
  - Ensure `OLLAMA_BASE_URL` is set to `http://host.docker.internal:11434`.
  - In `docker-compose.yml`, verify the `backend` service contains:
    ```yaml
    extra_hosts:
      - "host.docker.internal:host-gateway"
    ```
  - Ensure your host firewalls (e.g., `ufw` or Windows Defender) allow connections from the Docker network interface.

### Running Natively
- **Problem**: Backend fails to connect when running natively via `./mvnw spring-boot:run`.
- **Solution**:
  - Verify Ollama is serving requests on the host by opening `http://localhost:11434` in your browser.
  - If you see `Ollama is running`, verify the port matches your backend configurations.

---

## 3. Host Ollama Cold-Start Penalties (2+ Minute Delays)

- **Problem**: When running on machines without GPU acceleration (CPU mode), Ollama has to load models into system RAM from disk, which can take 1–2 minutes on the first request and cause API socket timeouts.
- **Solution**:
  - Cognalytix resolves this by calling a background warmup thread immediately after startup (firing a brief `hi` text to chat models and a `warmup` token to the embedder).
  - Check the backend logs for `Ollama model warmup complete`. If it shows `Model warmup failed`, verify that Ollama is active and the models are pulled.
  - If timeouts still occur during the first few entries, increase the timeout configuration in `application.yml`:
    ```yaml
    app:
      analysis:
        timeout-seconds: 300 # Set higher than 300 seconds if necessary
    ```

---

## 4. Missing Ollama Models

- **Problem**: The logs show errors such as `model "qwen3.5:4b" not found` or `model "nomic-embed-text" not found`.
- **Solution**:
  - Run `ollama list` on your terminal to check downloaded models.
  - Pull the missing model manually:
    ```bash
    ollama pull qwen3.5:4b
    ollama pull nomic-embed-text
    ```
  - Restart the backend to trigger the warmup cycle.

---

## 5. Port Conflicts on Startup

- **Problem**: The database container fails to boot, showing:
  `Bind for 0.0.0.0:5332 failed: port is already allocated`.
- **Solution**:
  - Cognalytix maps PostgreSQL to host port `5332` to avoid conflicts with native instances running on standard port `5432`.
  - If `5332` is occupied, change the host port binding in `docker-compose.yml`:
    ```yaml
    ports:
      - "5339:5432" # Map to an open port
    ```
  - Update `spring.datasource.url` in `application.yml` or set the environment variable `DB_URL` accordingly.

---

## 6. Token Authentication Loops

- **Problem**: The Vite frontend redirects to `/login` immediately after entering correct credentials, or falls into a 401 loop.
- **Solution**:
  - In a dev environment, JWT keys are generated automatically. If you restart the Spring Boot backend, the key rotates, invalidating existing access and refresh tokens. Clear your browser's local storage and log in again.
  - For production setups, ensure you set persistent environment keys:
    ```bash
    export JWT_SECRET=<same-key-across-restarts>
    export REFRESH_STORAGE_SECRET=<same-key-across-restarts>
    ```
