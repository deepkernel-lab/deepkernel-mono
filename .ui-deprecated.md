# **DeepKernel – Frontend Component Design**

The UI is structured around three primary screens:

1. **Cluster Overview (Dashboard)**
2. **Container Detail View**
3. **Live Event Stream / Timeline**

And one optional but impactful screen:

4. **ML Model Explorer**

Below is the detailed breakdown.

---

# 1. **Cluster Overview Page (Dashboard)**

### Purpose

Shows everything being monitored by DeepKernel in one glance. This is the “control tower”.

### Data needed from server

* List of active containers being monitored
* Health of each eBPF agent
* Whether model is trained/untrained for each container
* Last anomaly score & verdict
* Last change detected from CI/CD
* Policy enforcement status

### UI Layout

```
 -------------------------------------------------------------
| DeepKernel Dashboard                                       |
 -------------------------------------------------------------
|  Agents Status |  Models Status  |  Pending Events          |
 -------------------------------------------------------------
|  Containers Table:                                           |
|--------------------------------------------------------------|
| Container      | Namespace | Agent | Model | Last Verdict   |
|--------------------------------------------------------------|
| billing-api    | prod      | 🟢     | READY | SAFE 10s ago   |
| payments-svc   | prod      | 🟢     | READY | THREAT 3s ago  |
| inventory-svc  | staging   | 🟡     | TRAIN | SAFE 2m ago    |
| analytics-job  | batch     | 🔴     | NONE  | -              |
|--------------------------------------------------------------|
```

### Features

* Search & filter containers
* Color-coded badges:

  * **Green:** SAFE
  * **Red:** THREAT
  * **Grey/Yellow:** Unknown or model not trained
* Clicking a row opens **Container Detail View**

---

# 2. **Container Detail Page**

This is the most important UI screen for your Shark Tank demonstration.

### What it should show

```
 -------------------------------------------------------------
| Container: billing-api-v1-2                                 |
 -------------------------------------------------------------
|  Status: SAFE (11 seconds ago)                              |
|  Last Deployment: Git commit a9f2dcd pushed 14s ago         |
|  Model: Updated 10 seconds ago (Continuous Learning)        |
|  Agent: Connected (Node: worker-02)                         |
 -------------------------------------------------------------

  [Realtime Anomaly Score Graph - sparkline chart]

  [Flow Visualizer - last 5-second transition diagram]

  [Latest Verdict Box]
    - Verdict: SAFE / THREAT
    - Score: -0.41 (less anomalous)
    - Reason (from LLM):
      “New outbound calls to payments-svc match changed code in commit a9f2dcd”

  [If THREAT]
    - Policy Generated:
         seccomp-deny-connect-inet-high-ports.json
    - Applied on Node: worker-02 at 12:04:21
    - Block Result: 3 attempts blocked

  [Actions]
    - Request New Baseline Dump
    - Download Last Dump
    - View Model Version History
```

---

## CI/CD Integration Block (appears at top after every deploy)

```
Deployment Event:
Triggered by GitHub Action (#92)  
Changed files: billing/db.py, billing/routes/payments.py  
DeepKernel detected anomaly in next window automatically.
```

---

# 3. **Live Event Stream (Timeline View)**

Displays real-time stream of events from the server via WebSocket.

### Layout example:

```
 -------------------------------------------------------------
| Live Event Stream (WebSocket)                               |
 -------------------------------------------------------------

  [12:04:52] billing-api → New window received (148 syscalls)
  [12:04:52] billing-api → ML Score = -0.44 (normal)
  [12:04:53] billing-api → LLM Triage → Verdict: SAFE
  [12:04:53] billing-api → Model Updated (Continuous Learning)
  -------------------------------------------------------------

  [12:06:01] payments-svc → New window received (201 syscalls)
  [12:06:01] payments-svc → ML Score = +0.92 (anomalous)
  [12:06:02] payments-svc → LLM Verdict: THREAT
  [12:06:02] payments-svc → Policy Generated: deny-openat-tmp.json
  [12:06:02] payments-svc → Agent Enforcement Success
  [12:06:05] payments-svc → Block event observed: openat('/tmp/.X11')
```

### Notes

* This is your “wow” element in the presentation.
* Feels like a **security command center**.
* Proves pipeline → anomaly → triage → enforcement → learning happens in real time.

---

# 4. **ML Model Explorer (Optional but VERY powerful for demonstration)**

Shows historical model versions & what changed.

```
 -------------------------------------------------------------
| billing-api → Model Explorer                               |
 -------------------------------------------------------------
| Current model: v7 (Trained 12:04:53)                        |
 -------------------------------------------------------------
| Feature summary:                                             |
|  - Transition entropy: 1.22 → 1.11 after learning           |
|  - Unique syscall pairs: 71                                 |
|  - FileOps ratio: 0.21                                      |
|  - NetOps ratio: 0.34                                       |
 -------------------------------------------------------------
| Compare versions (v6 vs v7):                                |
|   - New learned pattern: open → read → connect(LAN)         |
|   - Removed anomalous outliers: None                        |
|   - Model size: 154 KB                                      |
```

This helps the audience understand the **continuous learning** story.

---

# Required Frontend Components (React)

Below are reusable components you can implement via Cursor/Antigravity:

### 1. `<ContainerTable />`

* Props: `containers[]`
* Renders dashboard list

### 2. `<ContainerStatusCard />`

* Shows summary of one container

### 3. `<AnomalyScoreChart />`

* Sparkline of last N scores (using Recharts)

### 4. `<SyscallFlowGraph />`

* Renders small transition graph between syscalls
* Perfect for eBPF visualization
* Can use Cytoscape.js or D3.js

### 5. `<LiveEventStream />`

* WebSocket subscriber component
* Auto-scroll feed

### 6. `<VerdictCard />`

* Large, color-coded box
* Shows SAFE / THREAT, score, explanation

### 7. `<PolicyCard />`

* Shows details of block policy generated
* Endpoint enforcement status

### 8. `<ModelVersionCard />`

* Displays model metadata
* Compare feature vectors

---

# How the Frontend Talks to Backend

### WebSocket Channels

* `/ws/events` → live anomaly / triage / enforcement events
* `/ws/containers/{id}` → container-specific live updates

### REST / gRPC

* `/containers` → list
* `/containers/{id}` → detail
* `/containers/{id}/model` → model metadata
* `/containers/{id}/baseline` → trigger baseline dump
* `/events?container=id` → event history

# What This Lets Us Demo

### Scenario:

1. Audience sees “some-api” in the dashboard
2. We push a dummy CI/CD change (GitHub Action)
3. Within **10 seconds**:

   * New deployment event appears
   * New syscall window arrives
   * ML scores it
   * LLM determines SAFE
   * **Model auto-updates** (continuous learning)
4. We push a malicious script
5. Demo:

   * Spike in anomaly score
   * LLM says **THREAT**
   * A Seccomp policy gets applied
   * Agent blocks the syscall
   * UI shows **BLOCKED** event live
