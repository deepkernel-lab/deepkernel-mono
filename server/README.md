# DeepKernel Server

Spring Boot-based orchestration server for the DeepKernel platform. Implements Ports & Adapters (Hexagonal) architecture.

## Overview

The server is the central hub that:
- Receives syscall windows from agents
- Extracts feature vectors from raw traces
- Scores windows using ML models (local or remote)
- Performs LLM-powered triage
- Generates and enforces security policies
- Streams events to frontend via WebSocket

## Architecture

```
server/
├── core/               # Main application, API controllers, services
├── contracts/          # Shared DTOs and port interfaces
├── anomaly-engine/     # Anomaly detection adapters (local, remote, hybrid)
├── triage-service/     # LLM triage adapter (Gemini)
├── cicd-integration/   # CI/CD context adapter (GitHub)
├── policy-engine/      # Policy generation adapter
└── ui-frontend/        # React dashboard
```

## Prerequisites

- Java 17+
- Gradle 8.5+
- Docker (for agent communication)

## Building

```bash
cd server

# Build all modules
./gradlew build

# Run server
./gradlew :core:bootRun
```

Server starts on `http://localhost:9090`

## Configuration

### application.yml

```yaml
deepkernel:
  agent:
    base-url: http://localhost:7070  # Agent HTTP server URL
  anomaly:
    mode: HYBRID  # LOCAL, REMOTE, or HYBRID
  ml-service:
    url: http://localhost:8081  # Python ML service URL
  gemini:
    api-key: ${GEMINI_API_KEY}  # Gemini API key (optional)
    model: gemini-1.5-flash
  ws:
    allowed-origins: http://localhost:3000  # Frontend origin
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `9090` | Server HTTP port |
| `ANOMALY_ENGINE_MODE` | `HYBRID` | `LOCAL`, `REMOTE`, or `HYBRID` |
| `ML_SERVICE_URL` | `http://localhost:8081` | ML service URL |
| `GEMINI_API_KEY` | - | Google Gemini API key (for LLM triage) |
| `AGENT_BASE_URL` | `http://localhost:7070` | Agent HTTP server base URL |
| `WS_ALLOWED_ORIGINS` | `http://localhost:3000` | WebSocket CORS origins |

## API Reference

### Health & Info

#### Health Check
```http
GET /api/health
```

Returns server health status and component availability.

**Response:**
```json
{
  "status": "UP",
  "timestamp": "2025-12-12T10:00:00Z",
  "uptime_seconds": 3600,
  "components": {
    "anomaly_engine": "UP",
    "triage_service": "UP",
    "policy_engine": "UP"
  }
}
```

---

#### Service Info
```http
GET /api/info
```

Returns server version and feature flags.

**Response:**
```json
{
  "application": "DeepKernel Server",
  "version": "0.1.0-SNAPSHOT",
  "description": "eBPF-based container runtime security platform",
  "features": {
    "anomaly_detection": true,
    "llm_triage": true,
    "policy_enforcement": true,
    "websocket_events": true
  }
}
```

---

### Agent Ingestion (Called by Agent)

#### Ingest Syscall Window
```http
POST /api/v1/agent/windows
Content-Type: application/json
```

Receives a 5-second syscall window from the agent for real-time analysis.

**Request:**
```json
{
  "version": 1,
  "agent_id": "node-1",
  "container_id": "bachat-backend",
  "window_start_ts_ns": 1702396800000000000,
  "records": [
    {
      "delta_ts_us": 1234,
      "syscall_id": 257,
      "arg_class": 1,
      "arg_bucket": 2
    },
    ...
  ]
}
```

**Processing Pipeline:**
1. Auto-register container
2. Extract 594-dim feature vector
3. Score with ML model
4. Perform LLM triage
5. Generate policy if threat detected
6. Apply policy to agent
7. Broadcast events via WebSocket

**Response:**
```json
{
  "status": "accepted"
}
```

**Status:** `202 Accepted`

---

#### Long Dump Complete Notification
```http
POST /api/v1/agent/dump-complete
Content-Type: application/json
```

Agent notifies server when a long dump (baseline recording) is complete.

**Request:**
```json
{
  "agent_id": "node-1",
  "container_id": "bachat-backend",
  "dump_path": "/var/deepkernel/dumps/bachat-backend.bin",
  "start_ts_ns": 1702396800000000000,
  "duration_sec": 300,
  "record_count": 15000
}
```

**Response:**
```json
{
  "status": "received",
  "container_id": "bachat-backend",
  "message": "Long dump received; training will be triggered"
}
```

---

### UI/Frontend APIs

#### List Containers
```http
GET /api/ui/containers
```

Returns all registered containers with their current status.

**Response:**
```json
[
  {
    "id": "bachat-backend",
    "namespace": "default",
    "node": "node-1",
    "status": "Running",
    "agentConnected": true,
    "modelStatus": "READY",
    "lastVerdict": "SAFE",
    "lastScore": -0.15,
    "policyStatus": null,
    "lastActivity": "2025-12-12T10:00:00Z"
  },
  ...
]
```

---

#### Get Container Models
```http
GET /api/ui/containers/{container_id}/models
```

Returns model training history for a container.

**Response:**
```json
[
  {
    "model_id": "model-bachat-backend",
    "container_id": "bachat-backend",
    "version": 2,
    "feature_version": "v1",
    "trained_at": "2025-12-10T20:00:00Z",
    "status": "READY",
    "parameters": {...}
  },
  ...
]
```

---

#### List Events
```http
GET /api/ui/events?limit=100
```

Returns recent live events (window scored, triage results, policies).

**Response:**
```json
[
  {
    "type": "WINDOW_SCORED",
    "container_id": "bachat-backend",
    "timestamp": "2025-12-12T10:00:00Z",
    "details": {
      "ml_score": -0.15,
      "is_anomalous": false,
      "record_count": 234
    }
  },
  {
    "type": "TRIAGE_RESULT",
    "container_id": "bachat-backend",
    "timestamp": "2025-12-12T10:00:01Z",
    "details": {
      "verdict": "SAFE",
      "risk_score": 0.2,
      "explanation": "Normal web application behavior..."
    }
  },
  ...
]
```

---

#### Get Events by Container
```http
GET /api/ui/events/container/{container_id}?limit=100
```

Returns events for a specific container.

---

#### Trigger Training
```http
POST /api/ui/train/{container_id}
Content-Type: application/json (optional)
```

Manually trigger model training for a container.

**Request (optional):**
```json
{
  "reason": "manual_retrain",
  "min_records_per_window": 20
}
```

**Status:** `202 Accepted`

---

### Admin APIs

#### Clear All Containers
```http
POST /api/admin/containers/clear
```

Clear all containers from the in-memory registry.

**Response:**
```json
{
  "status": "cleared",
  "containersRemoved": 15
}
```

---

#### Delete Containers by Pattern
```http
DELETE /api/admin/containers?pattern=host-.*
```

Delete containers matching a regex pattern.

**Response:**
```json
{
  "status": "deleted",
  "pattern": "host-.*",
  "containersRemoved": 12
}
```

---

#### Delete Single Container
```http
DELETE /api/admin/containers/{container_id}
```

Delete a specific container.

**Response:**
```json
{
  "status": "deleted",
  "containerId": "bachat-backend"
}
```

**Status:** `200 OK` or `404 Not Found`

---

#### Clear All Events
```http
POST /api/admin/events/clear
```

Clear all events from the event log.

**Response:**
```json
{
  "status": "cleared",
  "eventsRemoved": 1234
}
```

---

#### Get Stats
```http
GET /api/admin/stats
```

Get current registry statistics.

**Response:**
```json
{
  "containerCount": 5,
  "eventCount": 1234
}
```

---

### WebSocket (Real-time Events)

#### Subscribe to Events
```
Endpoint: ws://localhost:9090/ws
Topic: /topic/events
```

Connect to WebSocket to receive real-time events:
- `WINDOW_SCORED` - Window analysis complete
- `TRIAGE_RESULT` - LLM triage verdict
- `POLICY_APPLIED` - Policy enforced on container
- `LONG_DUMP_COMPLETE` - Baseline recording finished

**Example (JavaScript):**
```javascript
const socket = new SockJS('http://localhost:9090/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({}, () => {
  stompClient.subscribe('/topic/events', (message) => {
    const event = JSON.parse(message.body);
    console.log('Event:', event.type, event.details);
  });
});
```

---

## API Summary Table

| Category | Method | Endpoint | Description |
|----------|--------|----------|-------------|
| **Health** | `GET` | `/api/health` | Server health check |
| | `GET` | `/api/info` | Service information |
| **Agent** | `POST` | `/api/v1/agent/windows` | Ingest syscall window |
| | `POST` | `/api/v1/agent/dump-complete` | Long dump notification |
| **UI** | `GET` | `/api/ui/containers` | List containers |
| | `GET` | `/api/ui/containers/{id}/models` | Container model history |
| | `GET` | `/api/ui/events` | List recent events |
| | `GET` | `/api/ui/events/container/{id}` | Events by container |
| | `POST` | `/api/ui/train/{containerId}` | Trigger training |
| **Admin** | `POST` | `/api/admin/containers/clear` | Clear all containers |
| | `DELETE` | `/api/admin/containers?pattern=...` | Delete by pattern |
| | `DELETE` | `/api/admin/containers/{id}` | Delete single container |
| | `POST` | `/api/admin/events/clear` | Clear all events |
| | `GET` | `/api/admin/stats` | Get statistics |
| **WebSocket** | `WS` | `/ws` | Real-time event stream |

---

## Integration

### With Agent
- Agent sends windows to `/api/v1/agent/windows` every 5 seconds
- Server scores → triages → generates policy → sends to agent
- Agent receives policies via its HTTP server on port 7070

### With ML Service
- Server extracts features, then calls ML service `/api/ml/score`
- Uses `HYBRID` mode: tries remote ML service, falls back to local Java

### With Frontend
- Frontend polls `/api/ui/containers` and `/api/ui/events`
- Subscribes to WebSocket `/ws` for real-time updates

---

## Running Tests

```bash
# All tests
./gradlew test

# Specific module
./gradlew :core:test

# Integration tests
./gradlew :core:integrationTest
```

---

## Troubleshooting

### Server won't start
```
Address already in use (port 9090)
```
**Fix:** Change port via `SERVER_PORT=9091 ./gradlew :core:bootRun`

### Agent can't connect
```
Connection refused to localhost:9090
```
**Fix:** Check server is running, firewall allows port 9090

### ML scoring fails
```
ML service unavailable
```
**Fix:** Start ML service on port 8081, or set `ANOMALY_ENGINE_MODE=LOCAL`

---

## License

See the main repository LICENSE file.

