# SNRM - Supply Network Resilience Modelling

A research tool for modelling supply networks and measuring how well they survive disruption.
You draw or import a network, describe a disruption scenario, run a Monte Carlo simulation over
it, and read the structural and simulated resilience metrics back - for one network, or side by
side across configuration variants.

The repository holds the two halves of the application:

| Folder | What it is |
|---|---|
| [`snrm-backend/`](snrm-backend/) | Spring Boot 3.3 REST API on Java 21. MySQL 8 with Flyway migrations, JGraphT for the structural metrics, a virtual-thread Monte Carlo engine for the simulated ones. |
| [`snrm-frontend/`](snrm-frontend/) | Angular 18 single-page app. Cytoscape network editor, CSV/Excel/XML import wizard, disruption scenario builder, results dashboard and variant comparison. |

They run as two processes: the API on port **8080**, the app on port **4200**. Start the backend
first - the frontend is useless without it.

---

## Prerequisites

| | Version | Notes |
|---|---|---|
| **JDK** | 21 | `java -version` must report 21. The Maven wrapper (`mvnw`) is committed, so Maven itself is not needed. |
| **MySQL** | 8 | Listening on `localhost:3306`, with an administrator account you can log in as (usually `root`). |
| **Node.js** | 22 LTS | The Angular 18 toolchain accepts `^18.19.1 \|\| ^20.11.1 \|\| >=22.0.0`. npm ships with it. |
| **Docker** | - | Optional. Only for the containerised path and for the backend's Testcontainers tests. |

---

## Quick start

Five commands, once the prerequisites are in place. Each is explained in full below.

```bash
mysql -u root -p < snrm-backend/db/bootstrap.sql
```

```bash
cd snrm-backend && ./mvnw spring-boot:run
```

```bash
cd snrm-frontend && npm install
```

```bash
npm start
```

Then open <http://localhost:4200> and log in as `researcher` with the password the backend
printed in its startup log.

---

## Setting up the backend

Steps 1 and 2 are one-time. Step 3 is the everyday loop.

### 1. Create the schema and the application account

Open **`snrm-backend/db/bootstrap.sql`** and replace both occurrences of
`CHANGE_ME_snrm_app_password` with a password of your own. Then run the script once as a MySQL
administrator - in MySQL Workbench, connect as `root` and execute the whole file; or from a
shell:

```bash
mysql -u root -p < snrm-backend/db/bootstrap.sql
```

**What you should see.** The two verification queries at the end of the script print the `snrm`
schema with `utf8mb4` / `utf8mb4_0900_ai_ci`, followed by a `GRANT ... ON snrm.* TO
snrm_app@localhost` row. If the schema row is missing the script did not run; `ERROR 1044
(Access denied)` means you are not connected as an administrator.

The script creates **no tables** - Flyway does that at first startup.

### 2. Point the application at your database

Copy the committed template to your own profile file. `application-local.properties` is
git-ignored, so your password never reaches the repository.

```bash
cp snrm-backend/src/main/resources/application-local.properties.example snrm-backend/src/main/resources/application-local.properties
```

On Windows `cmd`:

```bat
copy snrm-backend\src\main\resources\application-local.properties.example snrm-backend\src\main\resources\application-local.properties
```

Then supply the credentials through two environment variables, using the password you chose in
step 1:

```bash
export SNRM_DB_USER=snrm_app
```

```bash
export SNRM_DB_PASSWORD='your-password-from-step-1'
```

On Windows, `setx SNRM_DB_USER snrm_app` writes them permanently but only affects **new**
shells - close the terminal and open a fresh one. For the current shell only, use
`set SNRM_DB_USER=snrm_app` (cmd) or `$env:SNRM_DB_USER='snrm_app'` (PowerShell).

> Prefer not to use environment variables? Edit the two `spring.datasource.*` lines in your
> `application-local.properties` directly. The `${SNRM_DB_USER:snrm_app}` syntax means
> "environment variable, else this fallback".

### 3. Start it

```bash
cd snrm-backend
```

```bash
./mvnw spring-boot:run
```

On Windows: `mvnw.cmd spring-boot:run`. The first run downloads dependencies and takes a few
minutes.

**What you should see**, in this order:

- `The following 1 profile is active: "local"`
- `HikariPool-1 - Start completed.` - the datasource connected
- `Successfully validated N migrations`, then either `Migrating schema snrm to version ...`
  (first run) or `Schema snrm is up to date`
- `Started SnrmApplication in N seconds`
- two boxed warnings announcing a **generated password** for the user `researcher` and a
  generated JWT signing key

**Copy that generated password.** It is what you log in with, and it changes on every restart.
[Giving it a stable value](#a-stable-login-password) is a one-off step described below.

Stop the app with `Ctrl+C`.

**Common failures**

| Log message | Cause |
|---|---|
| `Access denied for user 'snrm_app'@'localhost'` | Password mismatch between steps 1 and 2, or the shell predates `setx` |
| `Unknown database 'snrm'` | Step 1 was not run, or ran against a different MySQL instance |
| `Communications link failure` | MySQL is not running on port 3306 |
| `Schema-validation: missing table` | An entity has no matching migration - add a `V<n>__*.sql`, never turn on `ddl-auto` |

### 4. Confirm it is healthy

- <http://localhost:8080/actuator/health> - public, no login. Expect `{"status":"UP", ...}` with
  a `db` component also `UP`.
- <http://localhost:8080/swagger-ui.html> - the live API contract, titled *SNRM API*. The raw
  OpenAPI document is at <http://localhost:8080/v3/api-docs>.

Everything except `POST /api/v1/auth/login`, the health endpoint and the Swagger UI needs a
bearer token. In Swagger: expand **Authentication → POST /api/v1/auth/login**, *Try it out*, send
`{"username": "researcher", "password": "<the generated password>"}`, copy the `token` from the
response, then press **Authorize** at the top of the page and paste it.

---

## Setting up the frontend

With the backend running:

```bash
cd snrm-frontend
```

```bash
npm install
```

```bash
npm start
```

The app serves at <http://localhost:4200>. `ng serve` proxies `/api` to `http://localhost:8080`
(see `proxy.conf.json`), so the browser sees a single origin and the backend needs no CORS
configuration. If the backend moves, repoint the proxy - never put an absolute API URL in the
environment files.

Log in with `researcher` and the password from the backend's startup log.

### Building for production

```bash
npm run build
```

Output lands in `dist/snrm-frontend/browser/`.

---

## Running the tests

**Backend**

```bash
cd snrm-backend && ./mvnw test
```

Repository and application-context tests use a throwaway Testcontainers MySQL, which needs
Docker. Without Docker they are **skipped, not failed**, so the build stays green; they start
running as soon as a Docker daemon is available.

The engine tests need none of that - every metric and every step of the simulation loop is a
function of an in-memory network snapshot, so they run against fixtures with no Spring context,
no database and no Docker. To run only those:

```bash
./mvnw test -Dtest='com.snrm.metrics.**,com.snrm.simulation.**,com.snrm.scenario.RecoveryProfileTest,com.snrm.common.JobServiceTest,com.snrm.network.NetworkGraphTest'
```

**Frontend**

```bash
cd snrm-frontend && npm test
```

Karma launches Chrome. `npm test -- --watch=false --browsers=ChromeHeadless` runs once and exits,
which is what you want on CI.

---

## A stable login password

The generated credential changes on every restart, which is fine for a first look and annoying
after that. To fix one in place, produce a BCrypt hash without starting the app:

```bash
cd snrm-backend && ./mvnw test -Dtest=BcryptHashToolTest -Dsnrm.password=your-password -DfailIfNoTests=false
```

Set the printed hash as `SNRM_AUTH_PASSWORD_HASH`, add a `SNRM_JWT_SECRET` of at least 32
characters, and restart. Both are read as environment variables; neither is ever committed.

| Variable | Purpose | Default |
|---|---|---|
| `SNRM_DB_USER` | Database account | `snrm_app` |
| `SNRM_DB_PASSWORD` | Database password | the bootstrap placeholder |
| `SNRM_AUTH_USERNAME` | Login name | `researcher` |
| `SNRM_AUTH_PASSWORD_HASH` | BCrypt hash of the login password | generated at startup |
| `SNRM_JWT_SECRET` | HMAC-SHA256 signing key, ≥ 32 characters | generated at startup |
| `SNRM_JWT_TTL` | Token lifetime, ISO-8601 duration | `PT8H` |
| `SPRING_PROFILES_ACTIVE` | Configuration profile | `local` |

---

## Optional: the containerised path

`docker-compose.yml` and `Dockerfile` in `snrm-backend/` are a later deployment option, not the
primary local setup. They are unused unless you install Docker and run:

```bash
cd snrm-backend && docker compose up --build -d
```

The compose MySQL publishes on host port **3307** so it cannot collide with a locally installed
MySQL on 3306.

To use Docker only for the database while running the app from source:

```bash
docker compose up -d mysql
```

---

## Working with the tool

**Import a network** instead of drawing it. `POST /api/v1/networks/import` accepts a set of five
CSV files (`network_meta`, `nodes`, `links`, `products`, `node_products`), one `.xlsx` workbook
with those sheet names, or one `.xml` interchange document. Imports are all-or-nothing, and a
rejected file comes back as `200` with a report naming every finding by sheet, line and column.
Prefer XML for anything you want to keep: it is one self-describing file carrying the structure,
the units, the canvas layout and the network's own name.

Sample datasets ship in [`snrm-backend/samples/`](snrm-backend/samples/) - a minimal six-node
network, the same one as XML, a fifty-node multi-echelon one, one mixing hours, days and weeks,
the networks the verification documents work through, and a deliberately broken copy carrying an
instance of every validation class.
[`samples/README.md`](snrm-backend/samples/README.md) says what each should report, down to the
line numbers.

**Units are explicit.** Every duration and rate carries its own unit - a lead time is
`{"value": 36, "unit": "HOUR"}`, a capacity `{"value": 400, "timeUnit": "WEEK"}` - and the
network's period length decides what those become in simulation steps.

**Simulation is asynchronous.** `POST /api/v1/simulations` answers `202` with a `jobId` to poll
and the `runId` where results will appear. Accepting a run freezes its network: editing is then
refused with `NETWORK_IMMUTABLE` and must fork a variant. Every run stores the fully resolved
parameter set including the seed, so re-submitting it reproduces the run exactly.

**A whole experiment is archivable.** `GET /api/v1/projects/{id}/archive` returns one zip holding
the project, its networks, every variant's lineage, every scenario and every completed run with
its parameters, metrics and time series. `POST /api/v1/projects/archive/import` rebuilds it into
a new project.

`snrm-backend/api-tests.http` runs the whole flow outside the browser - login through import,
simulation, comparison and archive, plus deliberate failure cases - in the VS Code REST Client
extension.

---

## Where things are

**Backend** - `snrm-backend/src/main/java/com/snrm/`

| Package | Role |
|---|---|
| `auth/` | JWT bearer authentication for the single research user |
| `project/` | Projects, product catalogue, variant lineage |
| `network/` | Networks, nodes, links, node-products; the immutability guard |
| `dataimport/` | CSV / XLSX / XML import and export, two-stage validation |
| `scenario/` | Disruption scenarios, events, recovery profiles |
| `simulation/` | The Monte Carlo engine, flow allocation, inventory and pipeline |
| `metrics/` | Metric SPI plus the topological and simulated calculators |
| `archive/` | Whole-experiment archive and restore |
| `common/` | Units, durations and rates, async jobs, RFC 7807 error handling |

Schema changes are new `V<n>__*.sql` files under `src/main/resources/db/migration/`, applied by
Flyway at startup. Never hand-edit the schema, never edit an applied migration - Flyway validates
checksums and will refuse to start.

Verification documents in [`snrm-backend/docs/`](snrm-backend/docs/) work the metrics and the
simulation loop through by hand, one arithmetic step at a time, and state the exact value each
endpoint must return. The test suite asserts against those hand-derived numbers rather than
against values read back from the code.

**Frontend** - `snrm-frontend/src/app/`

| Folder | Role |
|---|---|
| `core/` | `ApiService`, JWT token store, auth interceptor and guard, `JobPollingService`, metric and time formatting |
| `core/models/` | Typed models mirroring the API DTOs - every feature imports from here |
| `shared/` | `confirm-dialog`, `unit-value`, `metric-badge`, `ci-value`, `file-drop` |
| `features/auth/` | Login |
| `features/projects/` | Project list, dashboard, product catalogue, variant provenance tree |
| `features/network-editor/` | The Cytoscape modelling surface: canvas, property / metrics / disruptions / run panels, visual playback |
| `features/data-import/` | Three-step import wizard and the export service |
| `features/scenario-builder/` | Disruption scenario list and the Gantt-style event timeline |
| `features/simulations/` | Run launcher, job monitor, results dashboard, performance curve |
| `features/comparison/` | Variant-by-metric matrix, radar chart, export |

Each feature folder carries a README describing its endpoints and the decisions behind it, and
most carry a `MANUAL-TEST.md` interaction script. `CLICK-THROUGH.md` at the frontend root walks
the whole application end to end.

Conventions worth knowing before changing anything: all HTTP goes through `core/ApiService`, so
components never inject `HttpClient`; all async jobs go through `core/JobPollingService`, so no
component writes its own polling loop; frontend models mirror API DTOs, never database shapes;
and errors arrive as RFC 7807 `problem+json`, read with `core/problem-details.ts`.