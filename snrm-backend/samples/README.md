# Import samples

Datasets for the CSV/XLSX import and the matching export. Each is a directory of CSV
files named after the canonical sheets, so a whole dataset is imported by attaching every `.csv` in
one directory to `POST /api/v1/networks/import`.

| Directory | Nodes | What it tests |
|---|---|---|
| `minimal-6-node/` | 6 | The happy path, and that a **plain numeric file still imports unchanged**: no `*_unit` column anywhere except `network_meta`, so every value is read in the network's period unit. Expect 0 errors, 0 warnings. |
| `metric-verification-6-node/` | 6 | Not an import test at all — the network `docs/metric-verification.md` computes the **topological metric suite** for, by hand. Imports cleanly (0 errors, 0 warnings) so that `GET /networks/{id}/metrics/topological` can be checked against arithmetic somebody can follow. |
| `xml-6-node/` | 6 | The **XML interchange format**: the same network as one self-describing file, carrying the canvas layout and its own name. No mapping step; expect 0 errors, 0 warnings. |
| `multi-echelon-50-node/` | 50 | Scale and structure: 6 suppliers → 4 plants → 3 central DCs → 5 regional DCs → 32 customers, 64 links, 2 products, 63 node-product rows. Uses `*_unit` columns with `DAY` and `WEEK`, all exact on a 1-day period. Expect 0 errors and 8 lateral-link warnings (below). |
| `mixed-units-10-node/` | 10 | **Hours, days and weeks in one network**, including abbreviated and mixed-case unit tokens — `h`, `hr`, `hrs`, `d`, `wk`, `Weeks`, `min`, `HOUR`. Period is 1 hour. Expect 0 errors and 4 warnings. |
| `broken-6-node/` | 11 rows | A deliberately broken copy of `minimal-6-node` carrying at least one instance of every validation class the importer can raise from a file. Expect 17 errors and 11 warnings, and **no network created**. |

Everything below is what each dataset should produce. If the actual report differs, the report is
right and this file is stale — say so.

---

## `minimal-6-node/`

`SUP-1 → PLANT-1 → {DC-1, DC-2} → {CUST-1, CUST-2}`, with `DC-1 → CUST-2` as a second path to CUST-2
so the network has some redundancy to measure.

The point of this one is the **absence** of unit columns. `nodes.csv` has `capacity_value` but no
`capacity_time_unit`; `links.csv` has `lead_time_value` but no `lead_time_unit`. Every such value is
read in the period unit from `network_meta` (1 DAY), which is the rule that keeps
pre-units files working. Export it afterwards and the unit columns come back filled in with `DAY` —
that is the round trip being unit-preserving from the first import onward.

`nodes.csv` also carries the **captions of FR-30**, and the same three ways `xml-6-node/` does:
`DC-1` states `caption` and `caption_visible=true`; `PLANT-1` states a caption and
`caption_visible=false`, so it is written and kept but not drawn; `SUP-1` states a caption and
leaves `caption_visible` **empty**, which reads as *visible*. `DC-1`'s caption contains a
comma and is quoted, which is worth keeping — it is what proves the delimiter sniff survives a
caption with punctuation in it. Export and the empty cell comes back as an explicit `true`: the
export writes both halves of the pair, as it does for units.

Expected: `valid: true`, `committed: true`, 0 errors, 0 warnings, 6 nodes, 6 links, 1 product,
6 node-product rows.

## `metric-verification-6-node/`

The companion to `docs/metric-verification.md`, which works every topological metric
through this network one arithmetic step at a time and states the exact value the API must return.
Import it, call `GET /api/v1/networks/{id}/metrics/topological`, and diff the response against §9 of
that document — `api-tests.http` requests 57–58 do exactly that.

```text
                      ┌──────────────▶ DC-1 ──────────────▶ CUST-1
                      │                 │  │
   SUP-1 ──▶ PLANT-1 ─┤                 │  └────────────┐
                      │                 ▼               ▼
                      └──────────────▶ DC-2 ──────────▶ CUST-2
```

Six nodes, seven links. It is `minimal-6-node` with one arc added — the lateral `DC-1 → DC-2`
transshipment link, legal-with-a-warning — and that arc is there to give the network
triangles, without which `CLUSTERING` is 0 and the metric proves nothing when it is right.

Everything about it is chosen so the arithmetic stays checkable: every duration is a whole number of
days on a 1-day period, so nothing rounds and the time-unit machinery is out of the way;
every capacity and demand is a round number; and total demand (65 per period) is below every
capacity on the path to it, so maximum serviceable demand is demand-limited rather than a bottleneck
puzzle.

Expected: `valid: true`, `committed: true`, 0 errors, **1 warning** — the `LATERAL_LINK` notice on
`DC-1 → DC-2` — 6 nodes, 7 links, 1 product, 6 node-product rows.

## `simulation-verification-3-node/`

The companion to `docs/simulation-verification.md`, which works the per-period loop
through this network by hand — capacity, demand, served, inventory and cost, period by period — and
derives `TTR`, `LOSS_AREA` and `RESILIENCE_INDEX` from the resulting table. Import it, define the one
event, submit the run, and diff the response against §8 of that document. `api-tests.http` requests
60–68 do exactly that.

```text
   SUP-1 ────────────▶ PLANT-1 ────────────▶ CUST-1
   cap 100             cap 80                demand 50/day
   var 2               var 3                 unit value 20
   fixed 100           fixed 200
                       stock 100
```

Three nodes, two links, one product, ten periods of one day. This is an analytically solvable
micro-network: *a 3-node chain whose TTR and loss area are hand-computable.*

Six things about it are deliberate, and the verification document's §1.6 states each in full:

- **Both lead times are zero**, so a unit produced at `SUP-1` reaches `CUST-1` in the same period.
  That keeps the pipeline machinery out of the arithmetic — the pipeline is exercised by
  `InventoryPipelineTest` instead, where a warm-up ramp does not have to be carried through all
  eleven metrics. It is also why `AVG_PIPELINE` is 0.0 here and 29.0 on `four-echelon-playback/`.
- **`PLANT-1` opens with 100 units**, exactly two periods of demand, so the inventory column drains
  visibly and the cost column changes regime at period 2 when production takes over from stock.
- **`PLANT-1`'s capacity is 80 against a demand of 50** — slack until the event halves it to 40, at
  which point it becomes the bottleneck and the shortfall is exactly 10 a period.
- **All `failure_prob` are 0**, and the run sets `demandNoiseCv` and `timingJitterPeriods` to 0 and
  `safetyStockPriority` to 0, so with one replication and a fixed seed every number is reproducible
  to the bit.
- **`WIDGET-A` is worth 20**, which is what a unit of *unmet* demand costs — and the only reason the
  disruption appears in `TOTAL_COST` at all.
- **The event is deterministic**: `NODE` → `PLANT-1`, day 3, three days, severity 0.5, `STEP`,
  probability 1.0. `STEP` makes the performance curve a rectangle, so `TTR` is the window and
  `LOSS_AREA` is width × depth.

Expected on import: `valid: true`, `committed: true`, 0 errors, 0 warnings — 3 nodes, 2 links, 1
product, 3 node-product rows. Nothing rounds, nothing is lateral, and every node is on a path from a
supplier to a customer.

Expected from the run: eleven metric rows, `FILL_RATE` 0.94, `SERVICE_LEVEL` 0.7, `MIN_FILL_RATE`
0.8, `TTR` 3 (`DAY`), `LOSS_AREA` 0.6, `TOTAL_COST` 6315, `DISRUPTION_COST_DELTA` 390, `CVAR_COST`
6315, `RESILIENCE_INDEX` 32/35, `AVG_INVENTORY` 5.0, `AVG_PIPELINE` **0.0**. Every `ciLow` and
`ciHigh` null at one replication.

That last **0.0 is a row, not an absence** — the network's zero lead times mean nothing is ever in
flight, which is a measurement (`docs/simulation-verification.md` §6.6). The rows that really are
absent are the four disruption-relative metrics on a baseline run, and the two must not be rendered
alike.

**The scenario is not in the sample directory**, because the canonical import schema
carries networks and not scenarios — a scenario is project-scoped and replayable across variants,
so it has no place in a network file. Request 62–63 create it.

## `four-echelon-playback/`

One `network.xml`: `SUP-1 → PLANT-1 → DC-1 → CUST-1`, every leg **one period long**, on a clock of
1 DAY × 30. [Its own README](four-echelon-playback/README.md) is the worked example — every number in
it derived by hand from the per-period loop rather than read back from a run, like the two
verification documents.

**It exists because `simulation-verification-3-node/` cannot answer the questions it answers.** Both
lead times there are zero and `safetyStockPriority` is 0, so nothing is ever in flight and nothing is
ever pulled down the chain — the two things a playback view (FR-18) draws and the two things the
inventory metrics (FR-19) measure. This is the first sample in the repository where
the pipeline and the order-up-to pull are both switched on and both hand-derived.

Expected on import: `valid: true`, `committed: true`, 0 errors, 0 warnings — 4 nodes, 3 links, 1
product, 4 node-product rows.

Expected from a **baseline** run (no `scenarioId`): seven metric rows — `FILL_RATE` 1.0,
`SERVICE_LEVEL` 1.0, `MIN_FILL_RATE` 1.0, `TOTAL_COST` 12094, `CVAR_COST` 12094, `AVG_INVENTORY`
**22.0**, `AVG_PIPELINE` **29.0** — and the four disruption-relative metrics **absent**. At the
default 100 replications every interval is zero-width, because every replication of this network is
identical; at one replication every interval is null. Its README §6.1 derives the inventory pair,
§6.4 the per-period columns behind it, and §8.5 the same pair for the Stage-7 disruption run
(27.333… and 26.0 — a cut link leaves material standing).

## `xml-6-node/`

`minimal-6-node` again, as a single `network.xml`. Attach the one file; there is no mapping step,
because the document names its own fields.

What it demonstrates that the CSV form cannot:

- **Layout survives.** Every node carries `posX`/`posY`, so the imported network opens in the editor
  arranged left to right by echelon rather than auto-laid-out. Move a node, export, re-import — the
  move comes back. (The CSV and XLSX forms carry `pos_x`/`pos_y` too; XML is simply where
  it is always the case, because an export always writes them.)
- **The file names the network.** `<meta name="XML 6-node">` means the import needs no name typed —
  the wizard pre-fills it, and the API accepts the request without a `name` field.
- **Units are structurally paired.** `<leadTime value="2" unit="DAY"/>` cannot be separated by an edit
  the way two adjacent columns can.
- **Captions travel (FR-30).** `DC-1` carries `caption` and `captionVisible="true"`, `PLANT-1` a
  caption with `captionVisible="false"` — written, kept, not drawn — and `SUP-1` plus the
  `PLANT-1 → DC-1` link carry a caption with **no** `captionVisible`, which reads as
  visible. Re-export and those last two come back with an explicit `captionVisible="true"`: the same
  meaning, no longer resting on a default.
- **Unconstrained is expressible.** The two customers carry `<capacity timeUnit="DAY"/>` — a unit with
  no value, which is exactly how an uncapped node is stored.

Expected: `valid: true`, `committed: true`, 0 errors, 0 warnings, 6 nodes, 6 links, 1 product,
6 node-product rows — the same network `minimal-6-node` produces, plus the positions.

**Worth trying:** change `schemaVersion="1"` to `2` and re-import. The document is refused with a
message naming the version this build reads, rather than being parsed optimistically until something
stops making sense.

## `multi-echelon-50-node/`

A realistic two-tier distribution network with a 120-period horizon: European suppliers on 2–5 day
road legs, APAC and Americas suppliers on 2–4 week ocean legs, and every customer reachable by at
least one path (five of them by two, which is what makes a disruption interesting).

Expected: `valid: true`, `committed: true`, 0 errors, **8 warnings** — all `LATERAL_LINK`, one per
central-DC → regional-DC arc. Both tiers are `DC` nodes, so by the echelon order those
links are lateral, and this is exactly the flag that should fire: DC→DC lateral transshipment is
allowed, with a warning badge. Worth seeing: with only four node types, a multi-tier DC network cannot
avoid it, and the warning is the tool saying so rather than a defect in the data.

## `mixed-units-10-node/`

`{SUP-EU, SUP-ASIA} → PLT-DE → DC-HUB → {DC-NORTH, DC-SOUTH} → 4 customers`, on a **1-hour period**
so that everything from a 4-hour delivery leg to a 3-week ocean leg is representable at once:

| Where | Value | Why it is there |
|---|---|---|
| `SUP-ASIA → PLT-DE` | `3 WEEK` | 504 periods — the coarse end of the range. |
| `DC-HUB → DC-SOUTH` | `36 hrs` | An abbreviation, and a duration that is not a whole number of days. |
| `DC-SOUTH → CUS-D` | `4 H` | Upper-case abbreviation; 4 periods exactly. |
| `PLT-DE.processing_time` | `90 min` | 1.5 periods — the one deliberate rounding warning. |
| `SUP-ASIA.capacity` | `2000 Weeks` | A rate over a coarse denominator; rates rescale exactly and never warn. |
| `CUS-C.demand` | `2 hr` | A rate over the finest denominator in the file. |

Expected: `valid: true`, `committed: true`, 0 errors, **4 warnings**:

1. `DURATION_ROUNDING_ERROR` — "Processing time 90.0 MINUTE rounds to 2 periods (+33%)". Under
   `NEAREST` on a 1-hour period, 1.5 rounds to 2. The report also carries `suggestedPeriod`, which
   will be **30 MINUTE**: the coarsest period that keeps every duration in this file within 10%.
2. Three `LATERAL_LINK` warnings — `DC-HUB → DC-NORTH`, `DC-HUB → DC-SOUTH` and
   `DC-NORTH → DC-SOUTH`. The first two are the hub-and-spoke structure and the third is deliberate
   transshipment; all three are `DC → DC` and so lateral by the echelon order.

**Worth doing once:** re-import this dataset with `periodLengthValue=1&periodLengthUnit=DAY` on the
request. The wizard's confirmed time base overrides `network_meta`, the period becomes a day, and the
6-hour and 4-hour legs now convert to zero periods — `DURATION_ROUNDS_TO_ZERO`, which is an **error**
during import and a warning in the editor. That single change is the clearest
demonstration of why the resolution checks exist.

## `broken-6-node/`

`minimal-6-node` with damage. Attach all six files including `assumptions.csv`.

Expected: `valid: false`, `committed: false`, `graphComplete: false`, 17 errors, 11 warnings, and no
network in the database. Four of the eleven rows in `nodes.csv` are dropped and three of the nine in
`links.csv`, so stage 2 sees seven nodes and six links — which is what `graphComplete: false` is
warning you about.

### Errors (each blocks the import)

| Code | Where | What was done to it |
|---|---|---|
| `MISSING_REQUIRED_COLUMN` | `network_meta` | `horizon_periods` column deleted. The import falls back to 52 for the rest of the checks so nothing else cascades. |
| `REQUIRED_VALUE_MISSING` | `nodes` line 9 | `name` left blank. |
| `UNKNOWN_ENUM_VALUE` | `nodes` line 10 | `type = WAREHOUSE`. |
| `NOT_A_NUMBER` | `nodes` line 11 | `capacity_value = n/a`. |
| `UNKNOWN_TIME_UNIT` | `nodes` line 11 | `processing_time_unit = fortnight`. |
| `PROBABILITY_OUT_OF_RANGE` | `nodes` line 11 | `failure_prob = 1.4`. |
| `DUPLICATE_KEY` | `nodes` line 6 | `DC-1` declared twice. |
| `DUPLICATE_KEY` | `links` line 4 | `PLANT-1 → DC-1` declared twice. |
| `DUPLICATE_KEY` | `node_products` line 3 | `(CUST-1, WIDGET-A)` declared twice. |
| `SELF_LOOP` | `links` line 8 | `DC-2 → DC-2`. |
| `UNKNOWN_NODE_REFERENCE` | `links` line 9, `node_products` line 10 | Both reference a node `DC-9` that does not exist. |
| `UNKNOWN_PRODUCT_REFERENCE` | `node_products` line 9 | `WIDGET-Z`. |
| `OUT_OF_RANGE` | `products` line 3 | `WIDGET-B` has `unit_value = -5`. |
| `CUSTOMER_UNREACHABLE` | network level | The only link into `CUST-2` came from the non-existent `DC-9`, so that row is dropped and CUST-2 is stranded. |
| `DURATION_ROUNDS_TO_ZERO` | `links` line 6 | `DC-1 → CUST-1` has a `6 HOUR` lead time on a 1-day period: under NEAREST it becomes instantaneous. An error at import, a warning in the editor. |

Note how three of the errors sit on one row (`nodes` line 11) and are all reported: the row is read
completely before it is dropped, so one bad row does not hide its own other problems.

### Warnings (each is something a researcher might mean)

| Code | Where | Why |
|---|---|---|
| `UNRECOGNISED_SHEET` | `assumptions.csv` | Not one of the five canonical names; skipped, not fatal. |
| `UNMAPPED_COLUMN` | `nodes.notes` | A column the schema has no home for. It is ignored — and this is the one warning the **mapping step** can act on. |
| `ROW_WIDTH_MISMATCH` | `nodes` line 2 | A stray 13th cell (`STRAY`) on a row whose header has 12 columns. The row still imports; the surplus is dropped. |
| `LATERAL_LINK` | `links` line 7 | `DC-1 → DC-2` transshipment. |
| `ECHELON_DIRECTION` | `links` line 10 | `CUST-1 → SUP-1` runs upstream. |
| `DURATION_ROUNDING_ERROR` | `links` line 7 | `30 HOUR` on a 1-day period rounds to 1 day, −20%. |
| `PERIOD_TOO_FINE` | `links` line 10 | The same upstream link carries a `4 YEAR` lead time: 1,460 periods to span it, past the 1,000× limit. |
| `ORPHAN_NODE` | `nodes` lines 8, 12 | `CUST-2` (its only link was dropped) and `ORPHAN-1` (never linked at all). |
| `DEMAND_ON_NON_CUSTOMER` | `node_products` line 7 | `PLANT-1` carries a demand of 10; the engine puts demand on customers only. |
| `NETWORK_CHECKS_ON_PARTIAL_GRAPH` | network level | Raised automatically because rows were dropped, so the structural findings above describe the graph without them. |

### Classes no single file can carry

Five checks cannot be provoked from this dataset, because each contradicts something else it has to
demonstrate. How to see them:

- **`NO_SUPPLY_NODE`** — delete the `SUP-1` and `PLANT-1` rows from `minimal-6-node/nodes.csv`.
- **`NO_CUSTOMER_NODE`** — delete the two `CUST-*` rows.
- **`NO_DEMAND_DECLARED`** — set every `demand_value` in `minimal-6-node/node_products.csv` to 0.
- **`DUPLICATE_SHEET`** — attach `minimal-6-node/nodes.csv` alongside an exported `.xlsx` of the same
  network; both carry a `nodes` sheet.
- **`UNREADABLE_FILE`** — attach anything that is not `.csv`/`.xlsx`, e.g. a `.xls` or a PDF.
- **`AMBIGUOUS_COLUMN`** — needs an explicit mapping, since auto-matching never claims a canonical
  column twice: send `mapping={"nodes":{"notes":"region"}}` against `broken-6-node`, which puts two
  source columns on `region`.
- **`PRODUCT_ALREADY_IN_PROJECT`** — import `minimal-6-node` twice into one project after editing
  `WIDGET-A`'s `unit_value` between the two runs. The first import's product wins; products are
  project-scoped and shared by every variant.
- **`PRODUCT_CREATED_IMPLICITLY`** — import `minimal-6-node` without `products.csv`.
- **`EVENT_EXCEEDS_HORIZON`** — not reachable from an import at all: it compares a
  disruption scenario's timeline against the horizon, and an import carries no scenario. It is
  exercised through `GET /networks/{id}/time-validation?scenarioId=…` once scenarios exist.

---

## The Excel path, and the round trip

The tabular samples are CSV so they can be read and diffed as text. The workbook path is exercised
through the export, which writes exactly the same schema:

```bash
curl -H "Authorization: Bearer $TOKEN" -o roundtrip.xlsx "http://localhost:8080/api/v1/networks/1/export?format=xlsx"
```

That file has the five canonical sheets. Re-import it as a single upload and it produces the same
network — under the same name, as the next version, which is the "export → edit in Excel → re-import
as a new variant" workflow. `?format=csv` returns the same content as a zip of the five
files, which is how to regenerate any of these directories from a network built in the editor.

`?format=xml` returns the document. All three are rendered from one set of rows, so
exporting the same network three ways describes it three times rather than three slightly different
ways — worth checking once by exporting as XML and as CSV and comparing them by eye.

The exact commands for all of the above are in `../api-tests.http` (requests 44–56); the metric suite
is 57–58 and the simulation run is 60–68.
