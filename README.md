# LangGraph4j How-to Notebooks

This project contains Maven conversions of LangGraph4j Java notebooks:

- Adaptive RAG: https://langgraph4j.github.io/langgraph4j/main/how-tos/adaptiverag/
- Agent Executor: https://langgraph4j.github.io/langgraph4j/main/how-tos/agentexecutor/
- Agent Executor + MCP: https://langgraph4j.github.io/langgraph4j/main/how-tos/agentexecutor-mcp/
- Multi-agent supervisor: https://langgraph4j.github.io/langgraph4j/main/how-tos/multi-agent-supervisor/
- Persistence / memory: https://langgraph4j.github.io/langgraph4j/main/how-tos/persistence/
- Subgraph as state graph: https://langgraph4j.github.io/langgraph4j/main/how-tos/subgraph-as-stategraph/
- Time travel: https://langgraph4j.github.io/langgraph4j/main/how-tos/time-travel/
- Wait for User Input: https://langgraph4j.github.io/langgraph4j/main/how-tos/wait-user-input/

The notebook code is organized as normal Java 17 CLI applications.

## Requirements

- JDK 17+
- Maven 3.9+
- `OPENAI_API_KEY` in your environment

## Run

Adaptive RAG:

```bash
mvn exec:java
```

Agent Executor:

```bash
mvn exec:java -Dexec.mainClass=com.example.agentexecutor.AgentExecutorApplication
```

Agent Executor + MCP:

```bash
mvn exec:java -Dexec.mainClass=com.example.agentexecutormcp.AgentExecutorMcpApplication
```

The MCP example expects Docker, the `mcp/postgres` image, a reachable Postgres database, and a running Ollama server. You can override the notebook defaults with:

```bash
MCP_POSTGRES_URL=postgresql://user:password@host.docker.internal:5432/db \
OLLAMA_BASE_URL=http://localhost:11434 \
OLLAMA_MODEL=qwen2.5-coder:latest \
MCP_QUESTION="get all issues names and project" \
mvn exec:java -Dexec.mainClass=com.example.agentexecutormcp.AgentExecutorMcpApplication
```

### Local MCP Postgres Setup

The published notebook uses a Docker image named `mcp/postgres`. If that image is not pullable from Docker Hub, this project includes a compatible local bridge image:

```bash
docker build -t mcp/postgres docker/mcp-postgres
```

Start the sample Postgres database:

```bash
docker run --name langgraph4j-mcp-postgres \
  -e POSTGRES_USER=admin \
  -e POSTGRES_PASSWORD=bsorrentino \
  -e POSTGRES_DB=mcp_db \
  -p 5432:5432 \
  -d postgres:16
```

Seed the notebook sample tables:

```bash
docker exec langgraph4j-mcp-postgres psql -U admin -d mcp_db -v ON_ERROR_STOP=1 -c "DROP TABLE IF EXISTS issues; DROP TABLE IF EXISTS projects; CREATE TABLE projects (id SERIAL PRIMARY KEY, name TEXT NOT NULL); CREATE TABLE issues (id SERIAL PRIMARY KEY, name TEXT NOT NULL, project_id INTEGER NOT NULL REFERENCES projects(id)); INSERT INTO projects (name) VALUES ('LangGraph4j How-to'), ('MCP Demo'); INSERT INTO issues (name, project_id) VALUES ('Convert notebook to Maven project', 1), ('Add Agent Executor example', 1), ('Connect Postgres MCP server', 2);"
```

The default MCP URL then works:

```bash
MCP_POSTGRES_URL=postgresql://admin:bsorrentino@host.docker.internal:5432/mcp_db
```

Multi-agent supervisor:

```bash
mvn -Dexec.mainClass=com.example.multiagentsupervisor.MultiAgentSupervisorApplication exec:java
```

The supervisor example expects Ollama at `http://localhost:11434` with `gpt-oss:20b` and `qwen2.5:7b` available. You can override those defaults:

```bash
OLLAMA_BASE_URL=http://localhost:11434 \
SUPERVISOR_MODEL=gpt-oss:20b \
WORKER_MODEL=qwen2.5:7b \
mvn -Dexec.mainClass=com.example.multiagentsupervisor.MultiAgentSupervisorApplication exec:java
```

Persistence / memory:

```bash
mvn -Dexec.mainClass=com.example.persistence.PersistenceApplication exec:java
```

Subgraph as state graph:

```bash
mvn -Dexec.mainClass=com.example.subgraphasstategraph.SubgraphAsStateGraphApplication exec:java
```

Time travel:

```bash
mvn -Dexec.mainClass=com.example.timetravel.TimeTravelApplication exec:java
```

The Time Travel example defaults to the LangChain4j demo OpenAI-compatible endpoint. You can override the notebook runtime values with:

```bash
TIME_TRAVEL_OPENAI_BASE_URL=http://localhost:11434/v1 \
TIME_TRAVEL_OPENAI_API_KEY=demo \
TIME_TRAVEL_MODEL=qwen2.5:7b \
mvn -Dexec.mainClass=com.example.timetravel.TimeTravelApplication exec:java
```

Wait for User Input:

```bash
mvn -Dexec.mainClass=com.example.waituserinput.WaitUserInputApplication exec:java
```

The Wait for User Input example defaults to `MemorySaver` so it can run without external services. To use the notebook's Postgres saver instead:

```bash
WAIT_USER_INPUT_USE_POSTGRES=true \
WAIT_USER_INPUT_POSTGRES_HOST=localhost \
WAIT_USER_INPUT_POSTGRES_PORT=5432 \
WAIT_USER_INPUT_POSTGRES_USER=admin \
WAIT_USER_INPUT_POSTGRES_PASSWORD=bsorrentino \
WAIT_USER_INPUT_POSTGRES_DATABASE=lg4j-store \
mvn -Dexec.mainClass=com.example.waituserinput.WaitUserInputApplication exec:java
```

## Build

```bash
mvn package
```

## Main Files

- `src/main/java/com/example/adaptiverag/AnswerGrader.java` contains the notebook's grader implementation.
- `src/main/java/com/example/adaptiverag/AdaptiveRagApplication.java` runs the notebook's three sample grading calls.
- `src/main/java/com/example/agentexecutor/AgentExecutorApplication.java` runs the Agent Executor notebook's test tool example with checkpoint history.
- `src/main/java/com/example/agentexecutormcp/AgentExecutorMcpApplication.java` runs the Agent Executor + MCP notebook's Postgres MCP example.
- `src/main/java/com/example/multiagentsupervisor/MultiAgentSupervisorApplication.java` runs the Multi-agent Supervisor notebook's supervisor/researcher/coder graph.
- `src/main/java/com/example/persistence/PersistenceApplication.java` runs the Persistence notebook's message graph with `MemorySaver` and `HazelcastSaver`.
- `src/main/java/com/example/subgraphasstategraph/SubgraphAsStateGraphApplication.java` runs the Subgraph as state graph notebook's parent graph with a child `StateGraph` node.
- `src/main/java/com/example/timetravel/TimeTravelApplication.java` runs the Time Travel notebook's checkpoint, interrupt, resume, history, and replay flow.
- `src/main/java/com/example/waituserinput/WaitUserInputApplication.java` runs the Wait for User Input notebook's interrupt, update-state, and resume flow.
- `pom.xml` contains the notebook dependencies: LangGraph4j `1.8.20`, LangChain4j `1.16.2`, LangChain4j MCP `1.16.2-beta26`, Agent Executor, Hazelcast/Postgres savers, OpenAI/Ollama integrations, PlantUML, and SLF4J JUL logging.
