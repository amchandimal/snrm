# SNRM frontend

Angular SPA of the **Supply Network Resilience Modelling tool**, and the browser half of the
system; the Spring Boot backend sits beside it in `snrm-backend/`.

Angular 18, standalone components, signal-based state in feature-scoped services, no NgRx.

## Running it

The backend must be up first: Spring Boot on <http://localhost:8080>, with MySQL behind it
(`../snrm-backend`).

```bash
npm install
```

```bash
npm start
```

The app serves at <http://localhost:4200>. `ng serve` proxies `/api` to `http://localhost:8080`
(`proxy.conf.json`), so the browser sees one origin and the backend needs no CORS configuration.
Point the proxy elsewhere if the backend moves - never put an absolute API URL in the environment
files.

```bash
npm test
```

```bash
npm run build
```

`npm run build` writes to `dist/snrm-frontend/browser/`.

## Structure

| Folder | Role |
|---|---|
| `core/` | `ApiService`, JWT token store, auth interceptor + guard, `JobPollingService`, metric and time formatting |
| `core/models/` | Typed models for the REST DTO contract - every feature imports from here |
| `shared/` | `confirm-dialog`, `unit-value`, `metric-badge`, `ci-value`, `file-drop` |
| `features/auth/` | Login page |
| `features/projects/` | Project list, dashboard, product catalogue, variant provenance tree |
| `features/network-editor/` | The Cytoscape modelling surface: canvas, property/metrics/disruptions/run panels, and the visual playback of FR-18 |
| `features/data-import/` | Three-step import wizard and the export service |
| `features/scenario-builder/` | Disruption scenario list and the Gantt-style event timeline |
| `features/simulations/` | Run launcher, job monitor, results dashboard and the performance curve |
| `features/comparison/` | Variant-by-metric matrix, radar chart, export |

Each feature folder holds a README naming its endpoints and the decisions behind
it; `features/network-editor/MANUAL-TEST.md` is the interaction script for the canvas.

## Conventions

- All HTTP goes through `core/ApiService`; components never inject `HttpClient`.
- All async jobs go through `core/JobPollingService`; no component writes its own polling loop.
- Frontend models mirror backend **DTOs**, never JPA entities or table shapes.
- Errors arrive as RFC 7807 `problem+json`; read them with `core/problem-details.ts`.
