# 🌍 TripTourWorld (`trip-tour-world`)

**A production-style, multi-agent AI trip planner built with Spring Boot 4, LangGraph4j and Groq.**
A supervisor agent decides which specialists (flights, hotels, weather, budget) are needed, an itinerary agent drafts the plan,
a **human reviews it (human-in-the-loop)**, and a final agent produces the polished answer — all guarded by input/output
guardrails, traced end-to-end in **LangSmith**, persisted in **NeonDB (PostgreSQL)**, evaluated by a built-in **eval harness**,
and shipped as a **Docker** image you can deploy on Render, Hostinger or any container host.

![System architecture](screenshots/architecture.png)

![Multi-agent flow](screenshots/agent-flow.png)

---

## ✨ Features

| Area | What you get |
|---|---|
| **Multi-agent orchestration** | LangGraph4j `StateGraph` with a **supervisor** that dynamically selects Flight / Hotel / Weather / Budget agents; the Itinerary agent always runs |
| **Human-in-the-loop** | Graph is compiled with `interruptBefore("human_approval")`. Reviewer can **approve** or **request changes**; changes loop back to the itinerary agent (capped by `MAX_REVISIONS`) |
| **Restart-safe approvals** | Every pause snapshots the agent state to NeonDB, so a pending approval survives redeploys / free-tier sleep (see [How HITL works](#how-human-in-the-loop-works)) |
| **Guardrails** | Input: empty/length, prompt-injection, unsafe-request and travel-relevance checks (LLM only when ambiguous). Output: secret / PII / card redaction + disclaimer |
| **Observability** | **LangSmith** run tree per trip: `trip → node → groq.chat / tool` with token usage and `thread_id` grouping |
| **Evals** | `/api/evals/run`: guardrail accuracy, supervisor routing accuracy, plan-structure checks, LLM-as-judge quality score. Offline-safe |
| **Security** | API-key auth (constant-time compare), per-IP rate limiting, CSP + hardening headers, strict CORS, input validation, no secrets in code, non-root container |
| **Resilience** | Groq retry/back-off on 429/5xx, tool fallbacks (live data → clearly-labelled general advice), supervisor keyword fallback, DB fallback to in-memory |
| **Persistence** | NeonDB via plain Spring JDBC + Hikari (tables auto-created); parses Neon's `postgresql://…?sslmode=require` URL |
| **Deployability** | Multi-stage Dockerfile, `docker-compose.yml`, Render blueprint (`render.yaml`), `PORT` aware, graceful shutdown |
| **Open source only** | Spring Boot, LangGraph4j, PostgreSQL driver, HikariCP, Groq-hosted open-weight model (`openai/gpt-oss-20b`) |

## 🧱 Tech stack

Java 21 (virtual threads) · Spring Boot **4.0.9** (MVC, Security, Actuator, Validation) · **LangGraph4j 1.8.25** ·
Groq (OpenAI-compatible API, `openai/gpt-oss-20b`) · NeonDB / PostgreSQL · LangSmith · AviationStack · OpenWeather · Tavily ·
Docker · JUnit 5.

## 🗂️ Project structure

```
trip-tour-world/
├── pom.xml  Dockerfile  docker-compose.yml  render.yaml  .env.example
├── docs/                         architecture.png, agent-flow.png (+ Graphviz sources)
└── src/main/java/com/triptourworld/
    ├── TripTourWorldApplication  loads .env (dev) then starts Spring
    ├── config/      Settings (env → typed config), DotenvLoader, NeonUrl, AppConfig (wiring)
    ├── graph/       TravelState (shared state), TripGraphFactory (the StateGraph), TripGraphRunner (start/resume)
    ├── agents/      Guardrail · Supervisor · Flight · Hotel · Weather · Budget · Itinerary · Approval · Final · Routing · TracedNode
    ├── tools/       FlightTool (AviationStack) · WeatherTool (OpenWeather) · WebSearchTool (Tavily) · AirportDirectory
    ├── llm/         ChatModel port + GroqChatModel (retry, think-tag cleanup, LangSmith llm runs)
    ├── guardrails/  InputGuardrail · OutputGuardrail
    ├── obs/         LangSmithTracer (REST run tree, async, best-effort)
    ├── service/     TripService (async runs, HITL, restart recovery)
    ├── persistence/ TripRepository → JdbcTripRepository (NeonDB) | InMemoryTripRepository
    ├── eval/        EvalService, PlanStructure
    ├── security/    ApiKeyAuthFilter, RateLimitFilter, SecurityConfig
    └── web/         TripController, DTOs, ApiExceptionHandler
src/main/resources/  static UI (index.html, app.js, style.css) · schema.sql · airports.csv · evals/cases.json
src/test/java/       unit + integration tests (fake LLM, fake HTTP server, real graph)
```

---

## 🚀 Quick start

### Prerequisites
* **JDK 21** and **Maven 3.9+** (or just Docker)
* Free API keys (only **Groq** is strictly required — everything else degrades gracefully):

| Service | Why | Get it |
|---|---|---|
| Groq | LLM (required) | https://console.groq.com/keys |
| NeonDB | PostgreSQL persistence | https://neon.tech → create project → copy the connection string |
| LangSmith | tracing | https://smith.langchain.com → Settings → API keys |
| AviationStack | flights | https://aviationstack.com (free plan is HTTP-only; handled) |
| OpenWeather | weather | https://openweathermap.org/api |
| Tavily | hotel search | https://tavily.com |

### 1) Configure secrets
```bash
cp .env.example .env        # Windows (cmd):  copy .env.example .env
```
Edit `.env` and replace the `your_…_here` placeholders. **`.env` is git-ignored; only `.env.example` is committed.**
Placeholders are treated as "not configured", so the app boots with only `GROQ_API_KEY` set.

> The app reads `.env` itself (quotes and spaces around `=` are fine). Real environment variables always override it,
> which is exactly how production hosts inject secrets.

### 2a) Run with Maven
```bash
mvn spring-boot:run
# or build a jar
mvn clean package && java -jar target/trip-tour-world.jar
```
Open **http://localhost:8080**. Health check: http://localhost:8080/api/health

### 2b) Run with Docker
```bash
docker build -t trip-tour-world .
# Mount your .env (the app parses it itself, so the quotes/spaces in .env.example are fine):
docker run --rm -p 8080:8080 -v "$(pwd)/.env:/app/.env:ro" trip-tour-world
#   PowerShell:  -v "${PWD}/.env:/app/.env:ro"      cmd.exe:  -v "%cd%/.env:/app/.env:ro"
```
Or use compose, which does the mount for you:
```bash
docker compose up --build -d
docker compose logs -f
```
> Don't use `docker run --env-file .env` with the quoted `.env.example` style: Docker keeps the quotes and spaces literally.
> On a host dashboard (Render etc.) just type plain values into the environment form.

### 3) Run the tests
```bash
mvn test
```
Tests need **no internet and no keys**: they use a scripted fake LLM, an in-process fake HTTP server for Groq / tools / LangSmith,
and the real LangGraph4j graph (interrupt, resume, revision loop, restart recovery).

---

## 🔌 REST API

All `/api/**` calls (except `/api/health`) require `X-API-Key: <APP_API_KEY>` when `APP_API_KEY` is set.

| Method & path | Purpose |
|---|---|
| `POST /api/trips` `{"message": "..."}` | Start a run → **202** + trip (`status: RUNNING`) |
| `GET /api/trips/{id}` | Poll status, progress, agent outputs, draft/final plan |
| `POST /api/trips/{id}/review` `{"approved": true}` or `{"approved": false, "feedback": "..."}` | Human decision |
| `GET /api/trips?limit=10` | Recent trips |
| `POST /api/evals/run?suite=all\|guardrail\|routing\|structure\|quality` | Run evals |
| `GET /api/evals/latest` | Last eval report |
| `GET /api/health` | Model, DB kind, tracing, which live tools are configured |

Status values: `RUNNING → AWAITING_APPROVAL → COMPLETED`, or `BLOCKED` (guardrail) / `FAILED`.

```bash
KEY="your-app-api-key"      # omit the header if APP_API_KEY is empty (local dev)
ID=$(curl -s -X POST localhost:8080/api/trips -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
      -d '{"message":"Plan a 5-day Goa trip from Hyderabad under 40000 INR"}' | sed -E 's/.*"id":"([^"]+)".*/\1/')
curl -s localhost:8080/api/trips/$ID -H "X-API-Key: $KEY"               # poll until AWAITING_APPROVAL
curl -s -X POST localhost:8080/api/trips/$ID/review -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
     -d '{"approved":false,"feedback":"Add a day trip to Dudhsagar Falls"}'   # revision loop
curl -s -X POST localhost:8080/api/trips/$ID/review -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
     -d '{"approved":true}'                                                    # finalize
```
(Windows: use `curl.exe`, or just use the web UI.)

---

## 🧠 How it works

### The graph (`TripGraphFactory`)
```
START → input_guardrail ──blocked──► guardrail_blocked → END
                        └─allowed──► supervisor ─► [flight → hotel → weather → budget → ] itinerary
itinerary → ⏸ human_approval ──approved──► final_agent → END
                            └─changes────► itinerary (revision loop, max MAX_REVISIONS)
```
* **State** — `TravelState` (extends LangGraph4j `AgentState`) holds query, constraints, each agent's output, approval fields and an
  `events` list that uses an **appender channel** (reducer). All values are JSON-friendly so snapshots can be stored in Postgres.
* **Dynamic routing** — the supervisor returns `selected_agents` + `trip_constraints`; `Routing` (pure functions, unit-tested)
  walks the selected agents in canonical order. Bad/unparsable LLM JSON falls back to a keyword router.
* **Tools instead of MCP** — the original used MCP servers; here each tool is a small typed REST client (`FlightTool`, `WeatherTool`,
  `WebSearchTool`) with explicit timeouts and graceful fallbacks, which is simpler to run in a single container.

### How human-in-the-loop works
1. The graph is compiled with `interruptBefore("human_approval")` and a `MemorySaver` checkpointer, so after the itinerary agent the run **pauses**.
2. `TripService` stores the status `AWAITING_APPROVAL` and snapshots the state in NeonDB.
3. `POST /review` writes `approved` / `human_feedback` into the checkpointed state (`updateState`) and resumes with `GraphInput.resume()`.
4. **Restart safety:** if the process was restarted (or the host slept) the in-memory checkpoint is gone. `TripService.recover(...)`
   rebuilds the state from the NeonDB snapshot and runs the remaining nodes directly, so approvals are never lost.

### Observability (LangSmith)
LangSmith has no official Java SDK, so `LangSmithTracer` speaks its REST API (`POST /runs/batch`) and records the same tree the
Python SDK would: **trip → node → `groq.chat` / `aviationstack` / `openweather` / `tavily`**, with token usage and `thread_id`
metadata (use LangSmith's *Threads* view). It is async and best-effort: a LangSmith outage never fails a trip.
Enable with `LANGSMITH_TRACING="true"` + a real `LANGSMITH_API_KEY`; project name comes from `LANGSMITH_PROJECT`.

### Guardrails
* **Input** — empty / >1500 chars → prompt-injection patterns (e.g. "ignore previous instructions", "reveal system prompt") →
  unsafe/illegal requests → travel keywords or known destinations → LLM classifier only for ambiguous prompts.
  User text is also wrapped in `<user_request>` tags in every prompt and treated as data.
* **Output** — redacts API keys, DB URLs, e-mails, card numbers and long phone numbers; guarantees the "verify before booking" disclaimer.

### Evals
`POST /api/evals/run` executes labelled cases from `src/main/resources/evals/cases.json`:

| Suite | Checks |
|---|---|
| `guardrail` | allow/block + category for safe, injection, unsafe, empty and off-topic prompts |
| `routing` | supervisor selects required agents and excludes irrelevant ones (LLM when configured, keyword router otherwise) |
| `structure` | plans contain all 7 sections, the disclaimer and no leaked secrets (golden sample, negative control, recent real plans) |
| `quality` | LLM-as-judge rubric (relevance, feasibility, budget consistency, safety, clarity) on the latest completed plan |

Reports are stored (NeonDB `eval_runs`) and returned as JSON with per-case results and accuracy. Add cases to the JSON to grow the suite.

### Security
API-key auth, per-IP rate limiting (writes are strict because they cost LLM tokens), CSP and Spring Security default headers,
CORS closed unless `ALLOWED_ORIGINS` is set, bean validation, uniform JSON errors without stack traces, secrets only via environment,
keys never included in error messages, container runs as a non-root user.
**Set `APP_API_KEY` before exposing the app publicly** (`openssl rand -hex 24`).

---

## ⚙️ Configuration reference

| Variable | Required | Default | Notes |
|---|---|---|---|
| `GROQ_API_KEY` | ✅ | – | Groq key |
| `GROQ_MODEL_ID` | | `openai/gpt-oss-20b` | any Groq chat model |
| `DATABASE_URL` | | in-memory store | Neon string `postgresql://user:pass@host/db?sslmode=require` (auto-converted to JDBC) |
| `LANGSMITH_TRACING` / `LANGSMITH_API_KEY` / `LANGSMITH_ENDPOINT` / `LANGSMITH_PROJECT` | | off | tracing |
| `AVIATIONSTACK_API_KEY` · `OPENWEATHER_API_KEY` · `TAVILY_API_KEY` | | fallback guidance | live data when set |
| `DEFAULT_ORIGIN_IATA` | | `DEL` | origin airport when the user doesn't name one |
| `APP_API_KEY` | prod | empty (open) | enables API-key auth |
| `ALLOWED_ORIGINS` | | empty | comma-separated CORS origins |
| `RATE_LIMIT_PER_MINUTE` | | `30` | write requests per IP |
| `MAX_REVISIONS` | | `2` | reviewer change-request loops |
| `LLM_MAX_TOKENS` / `LLM_TEMPERATURE` / `LLM_MAX_RETRIES` / `LLM_TIMEOUT_SECONDS` | | `3000` / `0.3` / `4` / `90` | model tuning |
| `PORT` | | `8080` | injected by Render and similar hosts |

---

## ☁️ Deployment

## Run using IDE or Docker at Localhost, then open browser (http://localhost:8080/)

- Landing Page
![landing page](screenshots/1.png)
- Planning the Trip
![planning the trip](screenshots/2.png)
- Neon PostgreSQL DB
![database](screenshots/3.png)
- Langsmith observibility
![langsmith](screenshots/4.png)

### Render (Docker)
1. Push this repo to GitHub (`.env` is git-ignored — it will not be pushed).
2. Render → **New + → Blueprint** → select the repo (uses `render.yaml`), or **New + → Web Service → Docker**.
3. Enter the secrets (`GROQ_API_KEY`, `DATABASE_URL`, `LANGSMITH_API_KEY`, …) in **Environment**. `APP_API_KEY` is auto-generated by the blueprint — copy it from the dashboard and paste it into the UI's *API key* field.
4. Health check path: `/actuator/health`. The first request after a free-tier sleep takes ~30–60 s (cold JVM); pending approvals survive thanks to the NeonDB snapshot.

### Hostinger VPS (or any Docker host)
```bash
git clone <your-repo> && cd trip-tour-world
cp .env.example .env && nano .env        # real credentials live only on the server
docker compose up --build -d
```
Put Nginx/Caddy in front for HTTPS (forward `X-Forwarded-For` so rate limiting sees real client IPs).

---

## 🛠️ Troubleshooting

| Symptom | Fix |
|---|---|
| Header badge shows *LLM not configured* | `GROQ_API_KEY` missing/placeholder |
| `HTTP 429` from Groq in logs | Free-tier token limit; the client retries with back-off. Wait a minute or use a model with higher limits |
| `database=memory` at startup | `DATABASE_URL` missing/invalid or Neon unreachable; the log says why. Neon may need a few seconds to wake — retries are built in |
| 401 from the API | Send `X-API-Key` (UI has a field) or clear `APP_API_KEY` for local dev |
| Flights show "unavailable" | AviationStack free plan limits / no data for the route — the agent falls back to labelled general advice |
| `mvn package` fails in `TripFlowTest` after changing the LangGraph4j version | Behaviour of interrupt/resume differs by version; build with `mvn package -DskipTests` (what the Dockerfile does) and adjust `graph/*` |
| Compile error mentioning a `org.bsc.langgraph4j` import | Library API drift: LangGraph4j is only used in `graph/TravelState`, `TripGraphFactory`, `TripGraphRunner` — adjust there (`langgraph4j.version` in `pom.xml`) |

## ⚠️ Limitations & roadmap
* Live graph checkpoints use LangGraph4j's `MemorySaver`; durability across restarts comes from the NeonDB snapshot + recovery path.
  A natural upgrade is LangGraph4j's PostgreSQL saver (needs SSL parameters for Neon).
* Rate limiting and the concurrent-run cap are per instance (fine for a PoC; use Redis for multi-instance).
* Add MCP clients, streaming (SSE) progress, user accounts, and booking integrations as next steps.

## 📝 Resume bullets (copy & adapt)
* Built **TripTourWorld**, a multi-agent AI trip planner in **Java 21 / Spring Boot 4** using **LangGraph4j** (supervisor routing, 5 specialist agents, shared state with reducers) and Groq's open-weight `gpt-oss-20b`.
* Implemented **human-in-the-loop** approval with graph interrupts, a capped revision loop and **restart-safe recovery** from NeonDB (PostgreSQL) snapshots.
* Designed layered **guardrails** (prompt-injection, unsafe-request, relevance, PII/secret redaction), **API-key auth, rate limiting and CSP** hardening.
* Added **LangSmith** distributed tracing (custom REST tracer: trip → node → LLM/tool runs) and an **eval harness** (guardrail, routing, structure, LLM-as-judge) plus JUnit tests with fake LLM/HTTP servers.
* Containerised with a multi-stage **Docker** build; deployable to Render/Hostinger with secrets supplied only via environment variables.

## 📄 License
MIT — see [LICENSE](LICENSE).
