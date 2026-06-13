# Getting Started

This guide provides instructions to get **Cognalytix** up and running on your local machine, either using the complete containerized stack via Docker Compose or by setting up a local development environment.

---

## Prerequisites

Before starting, ensure your system meets the following requirements:

- **Docker & Docker Compose**: Required for running the database, sidecar, frontend, and backend containers.
- **Ollama (Host System)**: Ollama must be installed and running natively on your host machine (not in Docker) to leverage GPU acceleration.
  - Download Ollama from [ollama.com](https://ollama.com).
- **Java Development Kit (JDK) 25**: Required if running the backend service natively.
- **Node.js 20+ & npm**: Required if running the React frontend natively.

---

## 1. Setting Up Ollama & Pulling Models

Cognalytix uses host-level Ollama for heavy LLM operations (summary generation, mirror narration, and semantic embeddings). Running it on the host ensures optimal performance (e.g. leveraging Apple Silicon Metal, NVIDIA CUDA, or AMD ROCm).

1. **Verify Ollama is running**:
   ```bash
   curl -s http://localhost:11434/api/tags
   ```
   *If this returns a JSON list, Ollama is running.*

2. **Pull the required models**:
   Cognalytix relies on three specific models. Run the following commands to pull them:
   ```bash
   # Primary model used for journal summary, coping tips, and mirror narration
   ollama pull qwen3.5:4b

   # Semantic label vectorizer used for pgvector matching
   ollama pull nomic-embed-text

   # Secondary model reserved for optional classification/label tasks
   ollama pull qwen3.5:0.8b
   ```

3. **Verify the models are available**:
   ```bash
   ollama list
   ```
   Ensure `qwen3.5:4b`, `nomic-embed-text`, and `qwen3.5:0.8b` appear in the output.

---

## 2. Quick Start (Docker Compose)

The fastest way to run the entire Cognalytix stack (PostgreSQL + Python Sidecar + Spring Boot Backend + React Frontend) is using Docker Compose.

### Step A: Spin Up the Stack
From the project root directory (the parent of this backend directory), run:
```bash
docker compose up -d
```
This builds and starts the following services:
- `postgres` (port `5332`) — Vector-capable PostgreSQL 16 image.
- `sidecar` (port `8001`) — Python ONNX classification and keyword extraction API.
- `backend` (port `8000`) — Spring Boot 4 REST API.
- `frontend` (port `5173`) — React frontend served via Nginx.

> [!NOTE]
> The backend connects to the host Ollama server using the address `http://host.docker.internal:11434`. This is automatically configured in the Compose environment.

### Step B: Monitor Health Checks
Wait for all services to pass their health checks. You can monitor their status with:
```bash
docker compose ps
```
All containers should display `healthy` in the status column.

### Step C: Run the Integration Demo
Cognalytix includes an automated shell script that walks through the entire user lifecycle—creating an account, authenticating, writing journal entries, waiting for async AI classification, and retrieving growth mirror insights.

Run the script:
```bash
./scripts/demo.sh
```

---

## 3. Local Development Setup

If you are modifying the code, you should run the backend and/or frontend services natively.

### Step A: Start Infrastructure Containers
You can start PostgreSQL and the Python sidecar via Docker while running the rest of the services locally:
```bash
# Start only Postgres and the Sidecar
docker compose up -d postgres sidecar
```

### Step B: Run the Backend Natively
1. Navigate to the backend directory:
   ```bash
   cd source
   ```
2. Verify you have JDK 25 active:
   ```bash
   java -version
   ```
3. Run the Spring Boot application using the Maven wrapper:
   ```bash
   ./mvnw spring-boot:run
   ```
   *The backend REST API will start at `http://localhost:8000`. Database migrations are applied automatically by Flyway on startup.*

### Step C: Run the Frontend Natively
1. Navigate to the frontend directory:
   ```bash
   cd frontend
   ```
2. Install dependencies:
   ```bash
   npm install
   ```
3. Start the Vite development server:
   ```bash
   npm run dev
   ```
4. Open your browser and navigate to **`http://localhost:5173`**.
   *Vite's development proxy is configured to automatically route any `/api/*` requests to the backend running at `http://localhost:8000`.*

---

## 4. Port Map Reference

| Service | External Port | Internal Port | Protocol | Description |
|---|---|---|---|---|
| **Frontend** | `5173` | `80` | HTTP | React SPA (served via Nginx inside Docker) |
| **Backend** | `8000` | `8000` | HTTP | Spring Boot 4 REST API & AI Scheduler |
| **Sidecar** | `8001` | `8001` | HTTP | FastAPI Python ONNX Model Server |
| **PostgreSQL** | `5332` | `5432` | TCP | pgvector Database |
| **Ollama** | `11434` | `11434` | HTTP | Native Host LLM Engine |

---

## Next Steps

- Explore the [System Architecture](architecture.md) to understand how the dual-AI pipeline and pgvector deduplication work.
- Review the [API Reference](api.md) to inspect endpoints and schemas.
