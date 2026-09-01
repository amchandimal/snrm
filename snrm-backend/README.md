# SNRM Backend

Spring Boot backend of the **Supply Network Resilience Modelling tool (SNRM)**.

## Prerequisites

- **JDK 21**
- **MySQL 8** installed on this machine and listening on `localhost:3306`, with an
  administrator account you can log in as (typically `root`)

No Docker is required for the primary setup.

---

## Running locally

Five steps, in order. Steps 1 and 2 are one-time; steps 3–5 are the everyday loop.

### 1. Create the schema and application account

Run **`db/bootstrap.sql`** once, as a MySQL administrator. First open the file and replace
both occurrences of `CHANGE_ME_snrm_app_password` with a password of your own.

MySQL Workbench: open the file, connect as `root`, and execute the whole script (⚡ icon).

Or from the CLI:

```bash
mysql -u root -p < db/bootstrap.sql
```

**What you should see** — the two verification queries at the end of the script print:

| SCHEMA_NAME | DEFAULT_CHARACTER_SET_NAME | DEFAULT_COLLATION_NAME |
|---|---|---|
| snrm | utf8mb4 | utf8mb4_0900_ai_ci |

followed by a `GRANT ... ON \`snrm\`.* TO \`snrm_app\`@\`localhost\`` row. If the schema row is
missing, the script did not run; if you get `ERROR 1044 (Access denied)`, you are not
connected as an administrator.

The script creates **no tables** — that is Flyway's job in step 4.

### 2. Point the app at your database

Copy the committed template to your own profile file. `application-local.properties` is
git-ignored, so your password never reaches the repository.

```bash
copy src\main\resources\application-local.properties.example src\main\resources\application-local.properties
```

Then set the two environment variables, using the password you chose in step 1:

```bash
setx SNRM_DB_USER snrm_app
```

```bash
setx SNRM_DB_PASSWORD "your-password-from-step-1"
```

**What you should see** — `SUCCESS: Specified value was saved.` for each. `setx` writes the
variables permanently but only affects **new** shells: close this terminal and open a fresh
one before step 3. To set them for the current shell only, use
`set SNRM_DB_USER=snrm_app` (cmd) or `$env:SNRM_DB_USER='snrm_app'` (PowerShell).

Verify in the new shell:

```bash
echo %SNRM_DB_USER%
```

It should print `snrm_app`, not `%SNRM_DB_USER%`.

> If you would rather not use environment variables, edit the two
> `spring.datasource.*` lines in your `application-local.properties` directly — the
> `${SNRM_DB_USER:snrm_app}` syntax means "environment variable, else this fallback".

### 3. Start the application

```bash
mvnw.cmd spring-boot:run
```

(`./mvnw spring-boot:run` on Linux/macOS. First run downloads dependencies and takes a few
minutes.)

**What you should see** — in the startup log, in this order:

- `The following 1 profile is active: "local"`
- `HikariPool-1 - Start completed.` — the datasource connected
- `Flyway Community Edition ... by Redgate`
- `Successfully validated 1 migration` and either
  `Migrating schema \`snrm\` to version "1 - baseline"` (first run) or
  `Schema \`snrm\` is up to date. No migration necessary.` (subsequent runs)
- `Started SnrmApplication in N seconds`
- unless `SNRM_AUTH_PASSWORD_HASH` and `SNRM_JWT_SECRET` are set, two boxed warnings:
  `No snrm.auth.password-hash configured — using a generated password for the research
  user 'researcher'`, and the same for the JWT signing key. **Copy that password** — it is
  what you log in with, and it changes on every restart.

Common failures:

| Log message | Cause |
|---|---|
| `Access denied for user 'snrm_app'@'localhost'` | Password mismatch between step 1 and step 2, or the shell predates `setx` |
| `Unknown database 'snrm'` | Step 1 was not run, or ran against a different MySQL instance |
| `Communications link failure` | MySQL service is not running on port 3306 |
| `Schema-validation: missing table` | An entity has no matching migration — add a `V<n>__*.sql`, never `ddl-auto` |

### 4. Check health

Open <http://localhost:8080/actuator/health> (public — no login prompt).

**What you should see** — `{"status":"UP", ...}` with a `db` component also `UP`. A `DOWN`
status with a `db` entry means the application started but cannot reach MySQL.

### 5. Check the API docs and log in

Open <http://localhost:8080/swagger-ui.html>.

**What you should see** — the Swagger UI titled *SNRM API*, with tags for Authentication,
Projects, Networks, Nodes, Links and Products. Every path is under `/api/v1`; the parked
legacy reverse-auction endpoint is gone. The raw contract is at
<http://localhost:8080/v3/api-docs>.

Everything except `POST /api/v1/auth/login`, the health endpoint and this UI needs a bearer
token:

1. Expand **Authentication → POST /api/v1/auth/login**, *Try it out*, and send
   `{"username": "researcher", "password": "<the password from step 3>"}`.
2. Copy the `token` from the response.
3. Press **Authorize** at the top of the page and paste it. Every other endpoint is now callable.

For a stable password instead of the generated one:

```bash
mvnw.cmd test -Dtest=BcryptHashToolTest -Dsnrm.password=your-password -DfailIfNoTests=false
```

then set the printed `SNRM_AUTH_PASSWORD_HASH` (and a `SNRM_JWT_SECRET` of at least 32
characters) as environment variables and restart.

`api-tests.http` in the project root runs the same flow outside the browser — the full happy
path from login through cloning a network, plus deliberate failure cases — in the VS Code REST
Client extension.

Every duration and rate in the API carries its own unit (FR-13): a lead time is
`{"value": 36, "unit": "HOUR"}` and a capacity `{"value": 400, "timeUnit": "WEEK"}`, and the
network's period length decides what those become in simulation steps.
[`docs/time-units-worked-example.md`](docs/time-units-worked-example.md) works a four-node network
through the arithmetic and through the resolution warnings `GET /networks/{id}/time-validation`
returns for it; `api-tests.http` requests 4–25 build that same network.

Stop the app with `Ctrl+C`.

---

## Importing a network from CSV or Excel

A network can be built by hand in the editor or imported whole:

| Endpoint | What it does |
|---|---|
| `POST /api/v1/networks/import/preview` | Parses the upload and answers with its sheets, headers, row counts and a proposed column mapping. Nothing is validated or stored. |
| `POST /api/v1/networks/import` | The two-stage validation and, unless `validateOnly=true`, the network. |
| `GET /api/v1/networks/{id}/export?format=xlsx\|csv\|xml` | The same network back out — one workbook, a zip of the five CSV files, or the XML document. |

Attach one of three things: a set of CSV files named after the canonical sheets — `network_meta.csv`,
`nodes.csv`, `links.csv`, `products.csv`, `node_products.csv`; one `.xlsx` workbook whose sheet names
match; or one `.xml` interchange document. Names are matched case-insensitively and tolerate spaces
and hyphens; CSV delimiters are detected among `,` `;` tab and `|`.

**Which format when.** XLSX to hand-edit a network and re-import it. CSV to diff two variants as
text. **XML to keep one**: it is a single self-describing file carrying everything the network holds,
including the canvas layout and the network's own name, so re-importing it needs nothing typed and no
column mapping. That is the format to attach to a paper or commit beside a results set.

Three things are worth knowing before the first attempt:

- **Imports are all-or-nothing.** One error anywhere and no network is created, so there is never a
  half-imported network to clean up. Read `committed` in the report rather than the status code: a dry
  run that passes is `valid` and not `committed`.
- **A rejected file comes back as 200 with the report**, not as an error document. The report lists every
  finding with its sheet, line and column, which is what the import wizard renders. Only a malformed
  request — no files, an unknown project — is a 4xx.
- **Units are optional per column.** Any `*_unit` column may be left out, and the value is then read in
  the network's period unit, so a plain numeric file works unchanged. Tokens are case-insensitive and
  accept abbreviations (`h`, `hr`, `hrs`, `hour`, `hours`). Export always writes them, so
  export → edit in Excel → re-import preserves the units you chose. The same is true of the canvas
  coordinates `pos_x`/`pos_y`, so a network arranged by hand comes back arranged, and of the
  `caption`/`caption_visible` pair on nodes and links, so a network annotated by hand comes back
  annotated. Omitting `caption_visible` means **visible** — a file carrying only a caption shows it.

Six sample datasets are in [`samples/`](samples/) — a minimal 6-node network, the same one as XML, a
50-node multi-echelon one, one that mixes hours, days and weeks, a deliberately broken copy of the
first carrying one instance of every validation class, and the 6-node network the metric
verification below works through. [`samples/README.md`](samples/README.md) says what each should
report, down to the line numbers; `api-tests.http` requests 44–56 run them all.

## Topological metrics

`GET /api/v1/networks/{id}/metrics/topological` computes the structural half of the metric suite —
`DENSITY`, `SPOF_NODE_COUNT`, `SPOF_ARC_COUNT`, `SPOF_COUNT`, `AVG_PATH`, `CLUSTERING`,
`NODE_CRITICALITY` (one row per node), `ROBUSTNESS_RANDOM` and `ROBUSTNESS_TARGETED`, in that suite
order — from an in-memory JGraphT snapshot of the network, persists
it against the network with `run_id = NULL`, and returns it. No simulation is involved and
nothing is queued: it is a synchronous request, and `computedInMs` in the response reports what it
cost against the two-second budget FR-04 sets at 1,000 nodes.

The suite is also the editor's metrics side panel, where node size can be made to encode
`NODE_CRITICALITY` so structural weak points are visible while editing.

**Verifying it.** [`docs/metric-verification.md`](docs/metric-verification.md) takes the six-node
network in [`samples/metric-verification-6-node/`](samples/metric-verification-6-node/), computes
every metric by hand one arithmetic step at a time, and states the exact value the endpoint must
return for each. `api-tests.http` requests 57–58 import that network and fetch the suite, with the
expected numbers listed inline. That document is the specification: if the two disagree, one of them
is wrong and the arithmetic is the one you can check.

## Simulation

The other half of the metric suite needs a run. `POST /api/v1/simulations` takes
`{networkId, scenarioId, params}`, validates everything it can, records the run, and answers **202**
with a `jobId` to poll and the `runId` whose results will appear at `GET /api/v1/simulations/{runId}`:

```text
POST   /api/v1/simulations                    -> 202 {jobId, runId, params}
GET    /api/v1/jobs/{jobId}                   -> {status, progress: 0..1, message}
DELETE /api/v1/jobs/{jobId}                   -> cooperative cancellation
GET    /api/v1/simulations/{runId}            -> the run, its metrics and its curves
GET    /api/v1/simulations/{runId}/results    -> the metric suite with 95% CIs
GET    /api/v1/simulations/{runId}/timeseries -> the per-period curves, disrupted and baseline
```

Four things are worth knowing before reading a result.

**Every run executes twice the replications you asked for.** An undisrupted baseline replication
set is mandatory, because `TTR`, `LOSS_AREA`, `RESILIENCE_INDEX` and
`DISRUPTION_COST_DELTA` are all defined against it. The baseline replications are paired with the
disrupted ones by index and share their demand realisations and random outages, so those four are
paired differences and their confidence intervals are far tighter than two independent samples'
would be.

**The run freezes its network the moment it is accepted.** A `QUEUED`, `RUNNING` or `DONE` run makes
its network immutable; editing is then refused with `NETWORK_IMMUTABLE` and must fork a
variant. Cancelling releases it.

**Submission is validated before the 202.** Network and scenario must be yours and in the same
project, every event's window must fit *this* network's horizon, every event must resolve to
something this network has (`EVENT_TARGET_UNRESOLVED`), and the network must have demand
(`NETWORK_HAS_NO_DEMAND`). A submission that fails any of these is a 4xx now rather than a `FAILED`
poll later. The job queue is bounded, so a submission past it is a **429** with `JOB_QUEUE_FULL`.

**Reproducibility is a stored seed.** The response carries the fully resolved parameter set —
including the seed actually drawn — and the same object is stored in `simulation_run.params_json`.
Re-submitting it reproduces the run exactly. Demand noise and event-timing jitter both
default to **0**, so a run is deterministic unless you ask for uncertainty.

**Verifying it.** [`docs/simulation-verification.md`](docs/simulation-verification.md) takes the
three-node chain in
[`samples/simulation-verification-3-node/`](samples/simulation-verification-3-node/), works the
per-period loop through it by hand — capacity, demand, served, inventory, cost — derives `TTR`,
`LOSS_AREA` and `RESILIENCE_INDEX` from that table, and states the exact eleven values the endpoint
must return for a fixed seed and one replication. `api-tests.http` requests 60–68 run it end to end
with the expected numbers listed inline. The inventory pair `AVG_INVENTORY` / `AVG_PIPELINE`
(FR-19) has a second worked example in
[`samples/four-echelon-playback/`](samples/four-echelon-playback/) §6.1, because the three-node
chain's lead times are zero and its `AVG_PIPELINE` is therefore a structural 0.0 — a measurement,
not a gap.

**What the engine assumes.** The eight modelling decisions left open to the engine — single
commodity, where supply originates, how lead times become pipeline inventory, what pulls stock
downstream, why the penalty arc is not priced at the goods' value — are collected in
`com.snrm.simulation/package-info.java`, each with the reasoning behind it.
[`docs/multi-commodity-extension.md`](docs/multi-commodity-extension.md) is the feature specification
for lifting the first of them.

## Archiving a whole experiment

```bash
curl -H "Authorization: Bearer $TOKEN" -OJ http://localhost:8080/api/v1/projects/1/archive
```

`GET /api/v1/projects/{id}/archive` returns one `.zip` holding everything needed to reconstruct an
experiment: the project, its product catalogue, every network, every configuration variant's lineage,
every disruption scenario with its events, and every **completed** run with its resolved parameter
set, its metric results and its full time series.

**Why it exists.** The XML export above archives a network's *inputs* — structure, units, layout — and
nothing else: no scenario, no run, no seed, no result. A reader given only that can rebuild what was
modelled but not what was found. Reproducibility asks for more: an entire experiment should be
archivable alongside the thesis, and a full archive is the mitigation against validating on
single-case data.

**What is inside.** `bundle.json` holds the manifest and every experiment-level record.
`networks/*.xml` holds one interchange document per network, byte-identical to what
`GET /networks/{id}/export?format=xml` returns — so a single network can be lifted out of the archive
and imported on its own with `POST /networks/import`.

**Restoring.** `POST /api/v1/projects/archive/import`, as a multipart `file` part, rebuilds the
experiment **into a new project**; nothing existing is read or changed. It answers 201 with an
`ArchiveReport`, and three fields of it are worth reading every time:

| Field | What it tells you |
|---|---|
| `engineMatches` | `false` means the archived results came from a different simulation engine than this installation runs. They are restored, readable and citable — but placing them beside a locally computed run compares two engines as much as two networks. A warning, not a refusal: an archive's purpose is to be legible later, and "later" is exactly when the engine has moved on. |
| `restoredCounts` vs `sourceCounts` | Any shortfall is explained by an entry in `findings`. |
| `findings` | Empty on a clean restore. Otherwise the ways a restore can succeed and still mislead — an unresolvable event target, a metric whose scope no longer names anything, a network that could not keep its version number. |

**Restored runs are marked.** Every run a restore creates carries `importedAt` and the `sourceRunId`
it held where it was computed. A restored run is a genuine `DONE` run — it freezes its network
and it appears in the comparison view — so without the mark, a number computed on another
machine would be indistinguishable from one this installation produced. Identity is re-created rather
than preserved, because ids belong to the database that issued them and preserving them would make
importing the same archive twice impossible.

An unreadable upload — not a zip, no `bundle.json`, or a format version this build does not know — is
a 422 with code `ARCHIVE_UNREADABLE`. `api-tests.http` requests 75–78 exercise the whole path.

## Deleting a network

`DELETE /api/v1/networks/{id}` removes a network and everything beneath it. It is refused with
`NETWORK_IMMUTABLE` (409) once a simulation run references the network: results are only meaningful
next to the exact structure that produced them. In the UI the action lives on the project
dashboard and requires the **project's name to be typed** into the confirmation dialog before it is
enabled — the friction is deliberate, since unlike an edit there is no variant to fork back to
(FR-15).

---

## Tests

```bash
mvnw.cmd test
```

Repository and context tests run against a throwaway Testcontainers MySQL, which
needs Docker. Without Docker they are **skipped, not failed**, so the build stays green on this
machine; they begin running as soon as a Docker daemon is available.

The engine tests need none of that. Every metric and every step of the simulation loop
is a function of the in-memory `NetworkGraph` snapshot and nothing else, so they
run against fixtures built in memory — no Spring context, no database, no Docker:

- `metrics/VerificationNetworkTest` — the whole topological suite against
  [`docs/metric-verification.md`](docs/metric-verification.md) §9. Its expected values are the
  fractions derived by hand in that document, not numbers read back from the code, and each
  assertion names the section that derives it.
- `simulation/SimulationVerificationTest` — the same discipline for the simulated suite, against
  [`docs/simulation-verification.md`](docs/simulation-verification.md) §8: the ten-period table, the
  two horizon totals, and all eleven metric values.
- one class per calculator, over the smallest revealing micro-networks — a three-node chain, a diamond,
  a triangle, a network with two routes and one with none — where the answer can be read straight
  off the definition.
- `simulation/FlowAllocatorTest` — flow conservation as a property, plus the four behavioural claims:
  capacities bind, node splitting makes a node's capacity mean something, the penalty arc
  serves demand even when serving costs more than the goods are worth, and an uncapped element cannot
  be partly disrupted.
- `simulation/InventoryPipelineTest` — lead times, processing dwell and the order-up-to policy, which
  the verification document deliberately keeps out of its own arithmetic.
- `simulation/ReplicationRngTest` and `scenario/RecoveryProfileTest` — the stochastic and recovery
  machinery, including the common random numbers that make the paired metrics paired.
- `common/JobServiceTest` — submit, poll, cancel, and the bounded queue.
- `metrics/MetricCalculatorRegistryTest`, `metrics/MetricContextTest`, `network/NetworkGraphTest` —
  the SPI plumbing: duplicate metric codes refused at startup, the display unit attached from the
  network's clock, the shared criticality derivation computed once, and the JGraphT view's vertices,
  weights and immutability.

To run only those:

```bash
mvnw.cmd test -Dtest='com.snrm.metrics.**,com.snrm.simulation.**,com.snrm.scenario.RecoveryProfileTest,com.snrm.common.JobServiceTest,com.snrm.network.NetworkGraphTest'
```

---

## Configuration layout

| File | Holds | Committed |
|---|---|---|
| `application.properties` | Everything environment-independent: app name, JPA (`ddl-auto=validate`), Flyway, springdoc, Actuator, Jackson. Sets `spring.profiles.active=local`. | ✅ |
| `application-local.properties` | Datasource for local MySQL on `localhost:3306`; credentials from `SNRM_DB_USER` / `SNRM_DB_PASSWORD`. | ❌ git-ignored |
| `application-local.properties.example` | Template for the above. Keep it free of real credentials. | ✅ |
| `application-prod.properties` | Placeholder for a deployed environment; every secret from the environment, no fallbacks. | ✅ |
| `application-docker.properties` | The optional containerised path (see below). | ✅ |

Override the active profile with `SPRING_PROFILES_ACTIVE=prod` or
`mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=prod`.

## Schema changes

Every schema change is a new `V<n>__*.sql` migration under `src/main/resources/db/migration/`,
applied by Flyway at startup. `V1__baseline.sql` is the empty baseline; none of
the tables exist yet.

Never hand-edit the schema in Workbench, never edit an applied migration (Flyway validates
checksums and will refuse to start), and never rely on `ddl-auto` — it is pinned to `validate`.

## Optional: containerised setup

`docker-compose.yml` and `Dockerfile` remain in the repository as the **later** deployment
option described, not as the primary local setup. They are unused unless you install
Docker and run:

```bash
docker compose up --build -d
```

The compose MySQL publishes on host port **3307** so it cannot collide with the local MySQL
instance this README targets on 3306.

## Notes

- Authentication is a JWT bearer token for the single research user. The
  credential lives in `snrm.auth.*`, entirely behind environment-variable placeholders; there is
  no user table, because the domain model has no user entity.
- Errors are RFC 7807 `application/problem+json` via `common/GlobalExceptionHandler`,
  including the ones raised inside the security filter chain. Domain rules add a machine-readable
  `code` member — `NETWORK_IMMUTABLE`, `LINK_SELF_LOOP`, `LINK_DUPLICATE`, `LINK_CROSS_NETWORK`,
  `DUPLICATE_NAME`, `BASELINE_ALREADY_SET`, `PRODUCT_OUT_OF_SCOPE` — so clients branch on the
  rule rather than on a message string.
- DTOs are mapped by MapStruct; no JPA entity appears in a controller signature.
