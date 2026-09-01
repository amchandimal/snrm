# Simulated metrics — a worked verification

*Every number below is derived by hand from the per-period loop and the metric definitions; nothing
here was produced by running the application. §8 states the exact values
`GET /api/v1/simulations/{runId}` must return for this network, so the implementation can be checked
against arithmetic rather than against itself.*

The network is `samples/simulation-verification-3-node/`, in the canonical import schema.
`api-tests.http` requests 60–68 import it, define the scenario, submit the run and fetch the results.

This is an analytically solvable micro-network: *a 3-node chain whose TTR and loss area are
hand-computable.*

---

## 1. The network

Three nodes, two directed links, one product, on a clock of **1 DAY / 10 periods / NEAREST**.

```text
    SUPPLIER                PLANT                    CUSTOMER

   ┌──────────┐  cap 100  ┌───────────┐  cap 100  ┌──────────┐
   │  SUP-1   │──────────▶│  PLANT-1  │──────────▶│  CUST-1  │
   │ cap 100  │  lead 0   │  cap 80   │  lead 0   │ demand 50│
   │ var 2    │  cost 1   │  var 3    │  cost 1   │  per day │
   │ fixed 100│           │ fixed 200 │           │ fixed 0  │
   └──────────┘           │ stock 100 │           └──────────┘
                          └───────────┘
```

### 1.1 Nodes

| # | Name | Type | Capacity | Var. cost | Fixed cost | Opening stock | Holding | Demand |
|---|---|---|---|---|---|---|---|---|
| 1 | `SUP-1` | SUPPLIER | 100 / DAY | 2 | 100 | 0 | 0.5 / DAY | — |
| 2 | `PLANT-1` | PLANT | 80 / DAY | 3 | 200 | **100** | 0.5 / DAY | — |
| 3 | `CUST-1` | CUSTOMER | *(blank → unconstrained)* | 0 | 0 | 0 | 0 | 50 / DAY |

### 1.2 Links

| Arc | Lead time | Capacity | Unit cost |
|---|---|---|---|
| `SUP-1 → PLANT-1` | **0 DAY** | 100 / DAY | 1 |
| `PLANT-1 → CUST-1` | **0 DAY** | 100 / DAY | 1 |

### 1.3 The product

`WIDGET-A`, `unit_value` **20**. That number is what a unit of *unmet* demand costs (§4.5), and it is
the only reason the disruption shows up in `TOTAL_COST` at all.

### 1.4 The scenario

One event, and it is deterministic:

| Field | Value |
|---|---|
| target | `NODE` → `PLANT-1` |
| start offset | 3 DAY |
| duration | 3 DAY |
| severity | **0.5** |
| recovery profile | `STEP` |
| probability | **1.0** |

### 1.5 The run parameters

```json
{ "replications": 1, "seed": 20260802, "demandNoiseCv": 0,
  "timingJitterPeriods": 0, "safetyStockPriority": 0 }
```

### 1.6 What the design of this network is for

Six things are deliberate, and each removes a source of arithmetic that would be tedious rather than
instructive:

- **Every duration is a whole number of days on a one-day period**, so nothing rounds and the time
  machinery is entirely out of the way. `docs/time-units-worked-example.md` is where that
  is exercised; here it must not interfere.
- **Both lead times are zero**, so a unit produced at `SUP-1` reaches `CUST-1` inside the period it
  is produced in. This isolates the allocation and the disruption from the pipeline machinery —
  the pipeline is exercised by `InventoryPipelineTest` instead, where it can be checked
  without dragging a warm-up ramp through every metric below.
- **`safetyStockPriority` is 0**, which switches replenishment off entirely. With it on, the network
  would also pre-position stock toward its order-up-to levels and every period's cost would carry a
  second story. The behaviour is real and is what a working network does; it is simply not what this
  document is checking.
- **`PLANT-1` opens with 100 units of stock**, which is exactly two periods of demand. That gives
  the inventory column something to do, and gives the cost column a visible regime change at period
  2 when the stock runs out and production takes over.
- **All `failure_prob` are 0 and `demandNoiseCv` is 0**, so the run is fully deterministic: with one
  replication and a fixed seed, every number below is reproducible to the bit.
- **The severity is 0.5 on a node whose capacity binds.** `PLANT-1` can move 80 a period against a
  demand of 50, so it has slack — until the event halves it to 40, at which point it becomes the
  bottleneck and the shortfall is exactly 10 a period. A severity that changed nothing would verify
  nothing.

---

## 2. Conventions the whole loop shares

Stated once here, and in `com.snrm.simulation/package-info.java`:

| Convention | Reading |
|---|---|
| **Supply origins** | Material enters at `SUPPLIER` or `PLANT` nodes with **no inbound arc**, fixed on the intact graph. Here: `{SUP-1}` alone — `PLANT-1` is fed by an arc, so its capacity is a *throughput* ceiling and it cannot manufacture from nothing. This is verbatim the convention the topological suite uses (`docs/metric-verification.md` §2). |
| **One commodity** | Per-product rows are summed to the node. There is one product here, so the aggregation is the identity and is invisible. |
| **Demand** | Sits on `CUSTOMER` nodes. Here: `CUST-1` 50, everything else 0. |
| **Fixed cost** | Charged every period for every node, whatever it ships and whatever the disruption did to it. `fixed_cost` is the cost of *having* the node. Here: 100 + 200 + 0 = **300 per period, in every period of both runs**. |
| **Stock bypasses production capacity** | On-hand inventory enters the flow at the node's *dispatch* point, where it acts as an additional supply source — i.e. substitutes for production, which is what a node's capacity limits. |
| **Units** | Every figure is per period. The snapshot converted them once; with a 1-day period and per-DAY rates every conversion is ×1. |

---

## 3. The two runs

Every simulation runs **two** replication sets: the disrupted one, and an undisrupted
**baseline** identical in every respect except that the scenario's events do not occur. With one
replication that is two horizons of ten periods each.

They matter here because four of the nine metrics — `TTR`, `LOSS_AREA`,
`DISRUPTION_COST_DELTA`, `RESILIENCE_INDEX` — are differences or ratios against the baseline and
cannot be computed without it.

---

## 4. The per-period table

### 4.1 Available capacity

`PLANT-1`'s availability follows the `STEP` profile: `1 − severity` for the whole window, then 1.
The window is periods 3, 4 and 5 — start offset 3 DAY ÷ 1 DAY = period **3**, duration 3 DAY ÷ 1 DAY
= **3** periods, so it ends at period 6 and period 6 is already recovered.

| Period | 0 | 1 | 2 | **3** | **4** | **5** | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|---|
| `PLANT-1` availability | 1.0 | 1.0 | 1.0 | **0.5** | **0.5** | **0.5** | 1.0 | 1.0 | 1.0 | 1.0 |
| `PLANT-1` capacity | 80 | 80 | 80 | **40** | **40** | **40** | 80 | 80 | 80 | 80 |

`SUP-1` stays at 100 and both links at 100 throughout. Nothing else is touched.

### 4.2 What the flow chooses

Each period the allocation is one minimum-cost flow. There are exactly three ways a unit
can reach `CUST-1`, and their per-unit costs decide everything:

```text
  from PLANT-1's stock :        0 (stock)  + 1 (link)  + 0 (CUST var)          = 1
  produced at SUP-1    : 2 (SUP var) + 1 (link) + 3 (PLANT var) + 1 (link) + 0 = 7
  not served           :                the penalty arc                        = 8
```

The penalty is **8** because it is set to strictly exceed the most expensive path in the network:
the sum of every variable cost (2 + 3 + 0 = 5) and every unit cost (1 + 1 = 2), plus one. That
ordering — stock, then production, then refusal — is the whole of the routing decision here, and it
is what makes the table below derivable by inspection:

- **while stock lasts**, serve from stock at 1 per unit;
- **once it is gone**, produce at 7 per unit;
- **only when capacity binds**, leave demand unserved at 8 per unit.

Period 3 is the only interesting case. Serving 40 and refusing 10 costs `40×7 + 10×8 = 360`;
refusing all 50 costs `50×8 = 400`. So the network serves everything it physically can, which is
what the dominating penalty is there to guarantee.

### 4.3 The disrupted run, period by period

*Stock is `PLANT-1`'s; every other node holds nothing at any point.*

| t | Plant cap | Demand | Served | Source | Stock (end) | Fixed | Variable | Transport | Holding | Shortage | **Cost** |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 80 | 50 | 50 | stock 50 | 50 | 300 | 0 | 50 | 25 | 0 | **375** |
| 1 | 80 | 50 | 50 | stock 50 | 0 | 300 | 0 | 50 | 0 | 0 | **350** |
| 2 | 80 | 50 | 50 | produce 50 | 0 | 300 | 250 | 100 | 0 | 0 | **650** |
| **3** | **40** | 50 | **40** | produce 40 | 0 | 300 | 200 | 80 | 0 | **200** | **780** |
| **4** | **40** | 50 | **40** | produce 40 | 0 | 300 | 200 | 80 | 0 | **200** | **780** |
| **5** | **40** | 50 | **40** | produce 40 | 0 | 300 | 200 | 80 | 0 | **200** | **780** |
| 6 | 80 | 50 | 50 | produce 50 | 0 | 300 | 250 | 100 | 0 | 0 | **650** |
| 7 | 80 | 50 | 50 | produce 50 | 0 | 300 | 250 | 100 | 0 | 0 | **650** |
| 8 | 80 | 50 | 50 | produce 50 | 0 | 300 | 250 | 100 | 0 | 0 | **650** |
| 9 | 80 | 50 | 50 | produce 50 | 0 | 300 | 250 | 100 | 0 | 0 | **650** |

Working the four cost columns for one period of each regime:

```text
t = 0   (from stock)
  variable  = 0                       nothing passes SUP-1 or PLANT-1's production arc;
                                      CUST-1's var cost is 0
  transport = 50 × 1 = 50             PLANT-1 → CUST-1 only; SUP-1 → PLANT-1 carries nothing
  holding   = 50 × 0.5 = 25           100 opening − 50 dispatched = 50 left at PLANT-1
  shortage  = 0
                                                                        total = 300 + 75 = 375

t = 2   (produced, undisrupted)
  variable  = 50×2 (SUP) + 50×3 (PLANT) + 50×0 (CUST) = 250
  transport = 50×1 + 50×1 = 100
  holding   = 0                       nothing is left anywhere
  shortage  = 0
                                                                        total = 300 + 350 = 650

t = 3   (produced, disrupted)
  served    = min(SUP 100, link 100, PLANT 40, link 100, demand 50) = 40
  variable  = 40×2 + 40×3 + 0 = 200
  transport = 40×1 + 40×1 = 80
  holding   = 0
  shortage  = (50 − 40) × 20 = 200    unit_value of WIDGET-A
                                                                        total = 300 + 480 = 780
```

**Disrupted total cost** = 375 + 350 + 650 + 3×780 + 4×650
= 375 + 350 + 650 + 2340 + 2600 = **6315**

### 4.4 The baseline run, period by period

Identical, except that `PLANT-1` keeps its 80 in periods 3–5, so those periods look exactly like
period 2.

| t | Plant cap | Demand | Served | Stock (end) | **Cost** |
|---|---|---|---|---|---|
| 0 | 80 | 50 | 50 | 50 | **375** |
| 1 | 80 | 50 | 50 | 0 | **350** |
| 2–9 | 80 | 50 | 50 | 0 | **650** each |

**Baseline total cost** = 375 + 350 + 8×650 = 375 + 350 + 5200 = **5925**

### 4.5 The fill-rate curves

| t | 0 | 1 | 2 | **3** | **4** | **5** | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|---|
| disrupted | 1.0 | 1.0 | 1.0 | **0.8** | **0.8** | **0.8** | 1.0 | 1.0 | 1.0 | 1.0 |
| baseline | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 |
| shortfall | 0 | 0 | 0 | **0.2** | **0.2** | **0.2** | 0 | 0 | 0 | 0 |

This is the resilience triangle — here a rectangle, because `STEP` recovers all at once. That is one
of the reasons `STEP` is the profile this document uses.

---

## 5. The recovery metrics, from the table

### 5.1 `TTR`

> Periods from disruption onset until fill rate regains its pre-disruption baseline;
> reported in periods and in the network's period unit.

**Onset** is the first period an event fires: period **3**.

Walking forward from the onset and comparing against the *baseline replication's* fill rate in the
same period:

```text
  t = 3 :  0.8  <  1.0    not recovered
  t = 4 :  0.8  <  1.0    not recovered
  t = 5 :  0.8  <  1.0    not recovered
  t = 6 :  1.0  ≥  1.0    recovered
```

```text
TTR = 6 − 3 = 3 periods
```

**`TTR` = 3 periods, `displayUnit` = `DAY`** — so a client renders it as "3 periods (3 days)".

That it equals the event's duration is a property of `STEP` on a network with no backlog and no
pipeline, not a coincidence to rely on: under `LINEAR` the same event would recover gradually and
`TTR` would still be 3 (the window closes at period 6 either way), while a network with lead times
would take longer because material has to travel before the customer sees it.

### 5.2 `LOSS_AREA`

> The area between baseline and disrupted performance curves (the resilience triangle).

The sum of the shortfall row of §4.5, over the **whole horizon** — not just the event's window, since
a disruption's cost does not stop when the event does:

```text
LOSS_AREA = Σ max(0, baselineFill(t) − disruptedFill(t))
          = 0 + 0 + 0 + 0.2 + 0.2 + 0.2 + 0 + 0 + 0 + 0
          = 0.6
```

**`LOSS_AREA` = 0.6** in fill-rate·periods — a dimensionless height times a count of periods, so the
figure is comparable across networks of different size. (Taken between the *served-demand* curves
instead it would be 30 units·periods, which is the same disruption measured in a unit that scales
with the network.)

**On `3 × 0.2`.** `1.0 − 0.8` is `0.19999999999999996` in IEEE double arithmetic, so the sum of three
of them is `0.5999999999999999`, not `0.6`. Compare to a tolerance — see §8.

### 5.3 `RESILIENCE_INDEX`

> Mean performance during the disruption horizon ÷ undisrupted performance (0–1).

The window runs from the onset to the end of the run — periods 3 to 9, seven periods:

```text
  disrupted mean = (0.8 + 0.8 + 0.8 + 1.0 + 1.0 + 1.0 + 1.0) / 7 = 6.4 / 7
  baseline  mean = (1.0 × 7) / 7                                 = 1.0

  RESILIENCE_INDEX = (6.4/7) / 1.0 = 6.4 / 7
```

**`RESILIENCE_INDEX` = 6.4/7 = 0.9142857142857143**

Note what the choice of window costs: measured over the event's own three periods it would be 0.8,
and over the whole ten-period horizon 0.94. Seven is what "during the disruption horizon" means,
counted from the moment the disruption began — and it is the only one of the three that is
sensitive to how long recovery takes.

---

## 6. The remaining metrics, from the same table

### 6.1 `FILL_RATE`

Served over demanded across the horizon — a **ratio of sums**:

```text
  Σ served = 50×7 + 40×3 = 350 + 120 = 470
  Σ demand = 50 × 10                 = 500

  FILL_RATE = 470 / 500 = 0.94
```

The mean of the per-period fill rates gives the same 0.94 here only because every period has the same
demand. On a network with uneven demand the two differ, and "across the horizon" means the
ratio of sums.

### 6.2 `SERVICE_LEVEL`

The fraction of periods served **in full** (type 1), which is a different question from §6.1:

```text
  periods with demand      = 10
  periods served in full   = 7      (periods 0, 1, 2, 6, 7, 8, 9)

  SERVICE_LEVEL = 7 / 10 = 0.7
```

The gap between 0.94 and 0.7 is the point of carrying both: the network delivered 94% of the units
and failed 30% of the periods.

### 6.3 `MIN_FILL_RATE`

The worst period with demand: **0.8**.

### 6.4 `TOTAL_COST` and `DISRUPTION_COST_DELTA`

```text
  TOTAL_COST            = 6315                (§4.3)
  baseline              = 5925                (§4.4)
  DISRUPTION_COST_DELTA = 6315 − 5925 = 390
```

Worth checking the delta a second way, because it is the metric most easily got wrong. Per disrupted
period the network **loses** the cost of 10 units it no longer produces or moves (10 × 7 = 70) and
**gains** the shortage cost of not serving them (10 × 20 = 200):

```text
  per period : 200 − 70 = 130
  × 3 periods           = 390     ✓
```

This is also why the shortage term has to be in `TOTAL_COST` at all: without it the delta would be
**−210**, and a disruption that destroyed half a plant would score as a saving.

### 6.5 `CVAR_COST` (α = 0.95)

```text
  k = max(1, ⌈N × (1 − α)⌉) = max(1, ⌈1 × 0.05⌉) = 1
  CVAR_COST = mean of the 1 largest replication cost = 6315
```

With one replication the worst 5% is that replication, so `CVAR_COST` equals `TOTAL_COST`. That is
correct and completely uninformative — the metric only says something at a replication count where a
tail exists, and at the default N = 100 it is the mean of the five most expensive runs.

### 6.6 `AVG_INVENTORY` and `AVG_PIPELINE`

> Mean end-of-period total on-hand stock, and mean in-transit (WIP), across the horizon — per
> replication, then mean and 95% CI. Both `NEUTRAL`: leaner versus more buffered is the trade-off
> under study, not a ranking.

Both are plain means over the **whole horizon** of columns §4.3 and §7.1 already carry. The
denominator is the horizon, not the number of periods that held something — a period holding nothing
is a period the configuration ran lean, and dropping it would report the average of the periods in
which the answer was interesting.

The **stock** column of §4.3 is `PLANT-1`'s and nothing else's, because no other node here ever holds
anything:

```text
  endingInventory over t = 0…9 :  50, 0, 0, 0, 0, 0, 0, 0, 0, 0

  AVG_INVENTORY = 50 / 10 = 5.0
```

**`AVG_PIPELINE` is exactly 0, and that is a finding rather than a missing feature.** Both lead times
are zero and no node has a processing dwell, so `pipelineDepth` is 1 and nothing is ever in flight
(§7.2.1) — the same deliberate omission §1.6 makes to keep the allocation isolated from the pipeline
machinery:

```text
  inPipeline over t = 0…9 :  0 × 10

  AVG_PIPELINE = 0 / 10 = 0.0
```

A metric that is 0 is **not** a metric that is absent: `AVG_PIPELINE` is defined on every completed
run and reports a row here saying this network holds no work in process. The four
disruption-relative metrics of §8 are the absent case, and the two must not be rendered alike.

`samples/four-echelon-playback/README.md` §6.1 is where the pipeline figure is non-trivial: every leg
there is one period long, so `AVG_PIPELINE` is 29.0 against an `AVG_INVENTORY` of 22.0, and the pair
is the whole reason that sample exists.

Both are computed over the **disrupted** set, like every other descriptive metric of the suite
(`com.snrm.metrics.simulated/package-info.java`). On this network the baseline set drains the same
100 units over the same first two periods (§7.2.3), so the two sets would give the same 5.0 — which
is exactly why the choice has to be stated rather than inferred from the number.

---

## 7. The per-period series

### 7.1 The network series

`GET /api/v1/simulations/{runId}/timeseries` returns ten points. With one replication the
"replication average" is the value itself.

| `period` | `totalDemand` | `servedDemand` | `cost` | `baselineServedDemand` | `baselineCost` | `endingInventory` | `inPipeline` |
|---|---|---|---|---|---|---|---|
| 0 | 50 | 50 | 375 | 50 | 375 | **50** | 0 |
| 1 | 50 | 50 | 350 | 50 | 350 | 0 | 0 |
| 2 | 50 | 50 | 650 | 50 | 650 | 0 | 0 |
| 3 | 50 | **40** | **780** | 50 | 650 | 0 | 0 |
| 4 | 50 | **40** | **780** | 50 | 650 | 0 | 0 |
| 5 | 50 | **40** | **780** | 50 | 650 | 0 | 0 |
| 6 | 50 | 50 | 650 | 50 | 650 | 0 | 0 |
| 7 | 50 | 50 | 650 | 50 | 650 | 0 | 0 |
| 8 | 50 | 50 | 650 | 50 | 650 | 0 | 0 |
| 9 | 50 | 50 | 650 | 50 | 650 | 0 | 0 |

Both curves are present because `LOSS_AREA` is the area *between* them and the triangle has to be
drawable without recomputation. The ER model had no column for the baseline curve;
`V6__run_timeseries_baseline.sql` adds the two, and its header carries the argument.

`endingInventory` and `inPipeline` are the two columns `V9__element_timeseries.sql` adds. Both were
already on every `PeriodTrace` and both were being discarded. `endingInventory` is `PLANT-1`'s stock
column of §4.3 and nothing else, because no other node here ever holds anything; `inPipeline` is
**0 in every period of both runs**, because both lead times are zero and no node has a processing
dwell, so nothing is ever in flight. That zero is a real finding rather than a missing feature — it
is exactly what §1.6 means by "this isolates the allocation and the disruption from the pipeline
machinery", and it is why `samples/four-echelon-playback/` exists.

The sums check the cost columns: `Σ cost = 6315` and `Σ baselineCost = 5925`, matching §4.3 and §4.4.

### 7.2 The per-element series

`GET /api/v1/simulations/{runId}/timeseries/elements` returns `available: true`, three nodes and two
links (FR-18). Every array is ten long and index *t* is period *t*.

This network is the degenerate case for the pipeline and the interesting one for **throughput**,
which is the only per-element quantity that cannot be read off §4.3.

#### 7.2.1 Why `arrivals` and `inTransit` are 0 everywhere

Both lead times are 0 and no node has a processing dwell, so `pipelineDepth` is 1 and nothing ever
occupies it: a unit produced at `SUP-1` reaches `CUST-1` inside the period it is produced in (§1.6).
So for all three nodes and all ten periods

```text
  arrivals(i, t) = 0        inTransit(i, t) = 0
```

and `Σ inTransit` is the `inPipeline` column of §7.1, which is 0. This is the one place where an
all-zero column is the correct answer and not a defect, which is precisely why it is stated here.

#### 7.2.2 Why `throughput` is not `served`

`throughput` is the flow across a node's **own capacity arc** — `in(i) → mid(i)` in `FlowAllocator`'s
four-vertex split. Two mechanics decide it, and both are already stated in §2:

- **stock bypasses it.** On-hand inventory enters the flow at the node's *dispatch* vertex
  `out(i)`, so material shipped out of stock never crosses the capacity arc. `PLANT-1` serves the
  customer from stock in periods 0 and 1, so its throughput is **0** in those periods even though 50
  units leave it.
- **a lead-0 arc lands at `in(target)`**, so material passing *through* a node does cross that node's
  capacity arc. `CUST-1`'s arc is uncapped and costs nothing, so it is invisible in the cost column —
  but 50 units cross it every period, and its throughput is 50.

| t | `SUP-1` | `PLANT-1` | `CUST-1` | check against §4.3's variable cost |
|---|---|---|---|---|
| 0 | 0 | **0** | 50 | 0×2 + 0×3 + 50×0 = **0** ✓ |
| 1 | 0 | **0** | 50 | **0** ✓ |
| 2 | 50 | 50 | 50 | 50×2 + 50×3 = **250** ✓ |
| **3–5** | **40** | **40** | **40** | 40×2 + 40×3 = **200** ✓ |
| 6–9 | 50 | 50 | 50 | **250** ✓ |

The variable-cost column of §4.3 is the dot product of this table with the nodes' `var` rates, which
is the cheapest available check that throughput is being read off the right arc.

#### 7.2.3 The three nodes in full

`onHand` is the end-of-period stock of §4.3; `served` and `unserved` sit on the customer alone;
`availability` is §4.1's row.

| Node | `onHand` | `served` | `unserved` | `availability` | `inboundLead` | `baselineOnHand` | `baselineServed` |
|---|---|---|---|---|---|---|---|
| `SUP-1` | 0 × 10 | 0 × 10 | 0 × 10 | 1.0 × 10 | **null × 10** | 0 × 10 | 0 × 10 |
| `PLANT-1` | **50, 0**, 0 × 8 | 0 × 10 | 0 × 10 | 1,1,1,**.5,.5,.5**,1,1,1,1 | **null, null**, 0.0 × 8 | **50, 0**, 0 × 8 | 0 × 10 |
| `CUST-1` | 0 × 10 | 50,50,50,**40,40,40**,50,50,50,50 | 0,0,0,**10,10,10**,0,0,0,0 | 1.0 × 10 | **0.0 × 10** | 0 × 10 | 50 × 10 |

Three readings, each of which a naive implementation gets wrong in a different way:

- **`SUP-1`'s `inboundLead` is null in every period, and that is structural.** It has no inbound arc
  at all, so nothing can ever be dispatched toward it. A 0 here would say material reaches it
  instantly, which is a claim about a supply origin that nothing in the model supports.
- **`PLANT-1`'s is null in periods 0 and 1 and 0.0 from period 2.** Arc `SUP-1 → PLANT-1` carries
  nothing while the plant is serving out of its own stock (§4.3's "Source" column), so there is no
  dispatch to weight; from period 2 it carries 40–50 a period at a lead of **0**, and the
  dispatch-weighted mean of a lead of 0 is 0.0. **`null` and `0.0` are different answers to different
  questions** — nothing was sent, versus what was sent arrived the same period — and this is the
  cheapest network in the repository on which to check that they are not being conflated.
- **`baselineOnHand` for `PLANT-1` equals its disrupted column.** The baseline run drains the same
  100 units over the same first two periods, because the disruption starts at period 3 and this
  network holds no stock after period 1. So the two curves differ *nowhere*, and the element-level
  resilience triangle for inventory is empty even though the service-level one is not. That is right:
  the disruption cost service, not stock.

#### 7.2.4 The two links in full

Capacity is 100 on both arcs and neither is targeted by the event, so `availability` is 1.0
throughout and `utilisation` is simply `flow / 100`.

| Link | `flow` | `utilisation` | `availability` | `baselineFlow` |
|---|---|---|---|---|
| `SUP-1 → PLANT-1` | 0, 0, 50, **40, 40, 40**, 50, 50, 50, 50 | 0, 0, .5, **.4, .4, .4**, .5, .5, .5, .5 | 1.0 × 10 | 0, 0, 50, **50, 50, 50**, 50, 50, 50, 50 |
| `PLANT-1 → CUST-1` | 50, 50, 50, **40, 40, 40**, 50, 50, 50, 50 | .5, .5, .5, **.4, .4, .4**, .5, .5, .5, .5 | 1.0 × 10 | 50 × 10 |

Two things to read off it:

- **`SUP-1 → PLANT-1` carries 0 in periods 0 and 1, and that is a true zero**, not a missing value:
  the arc was fully available and simply had nothing to carry, because the plant was shipping stock
  it already held. Its `utilisation` is therefore **0.0** and not null. A null there would say the
  arc could not be measured; a 0.0 says it was idle. The `null` cases — an arc with no declared
  capacity, and an arc an outage has taken to zero capacity, where `0/0` is not 0 — do not arise on
  this network, and are checked in `ElementTimeseriesTest` instead (§9).
- **The `flow` and `baselineFlow` columns differ only in periods 3–5**, by exactly 10 a period,
  which is the same 30 units the served-demand curves differ by. On a network with lead times the two
  differences would sit in different periods; here they coincide, which is what makes this table
  checkable by inspection and `four-echelon-playback` necessary.

---

## 8. The values the API must return

`GET /api/v1/simulations/{runId}/results` on this run returns **eleven** rows, all `NETWORK`-scoped,
all with `runId` set (a simulated metric belongs to its run). Every `ciLow` and `ciHigh`
is **null**, because a single replication has no sample variance and reporting `[x, x]` would state
a certainty that does not exist.

| `metricCode` | Exact value | Decimal (16 s.f.) | `displayUnit` |
|---|---|---|---|
| `FILL_RATE` | 47/50 | 0.94 | null |
| `SERVICE_LEVEL` | 7/10 | 0.7 | null |
| `MIN_FILL_RATE` | 4/5 | 0.8 | null |
| `TTR` | 3 | 3.0 | **`DAY`** |
| `LOSS_AREA` | 3/5 | 0.5999999999999999 | null |
| `TOTAL_COST` | 6315 | 6315.0 | null |
| `DISRUPTION_COST_DELTA` | 390 | 390.0 | null |
| `CVAR_COST` | 6315 | 6315.0 | null |
| `RESILIENCE_INDEX` | 32/35 | 0.9142857142857143 | null |
| `AVG_INVENTORY` | 5 | 5.0 | null |
| `AVG_PIPELINE` | 0 | 0.0 | null |

`CVAR_COST` carries no interval at **any** replication count, not just this one: it is a functional
of the whole replication set rather than a mean of per-replication values, so an interval computed
the way the others are would overstate its precision.

**On comparing.** These are exact rationals; the API returns IEEE doubles. `0.94`, `0.7`, `0.8`,
`0.6` and `32/35` are none of them exactly representable, so compare to a tolerance — **1 × 10⁻⁹ is
far tighter than any of these computations can drift** and far looser than the last-bit differences
that summation order produces. `LOSS_AREA` is the one to watch: it is a sum of three differences and
lands at `0.5999999999999999`, about 1 × 10⁻¹⁶ below 0.6.

The five integer-valued results (`TTR` 3, `TOTAL_COST` 6315, `CVAR_COST` 6315, `AVG_INVENTORY` 5,
`AVG_PIPELINE` 0) should match to the bit. `TOTAL_COST` is a sum of exact halves and integers, and
every intermediate is representable; if it comes back as 6314.999 something is wrong with the
fixed-point grid rather than with the model. `AVG_INVENTORY` is `50 ÷ 10`, both dyadic, so a result
of 4.999… is a defect and not a rounding difference.

### 8.1 The run record

`GET /api/v1/simulations/{runId}` also returns the run itself. `run.params` is the resolved set —
what the request asked for, plus every default:

```json
{
  "replications": 1,
  "seed": 20260802,
  "horizonPeriods": 10,
  "demandNoiseCv": 0.0,
  "timingJitterPeriods": 0,
  "includeRandomFailures": true,
  "baselineSuppressesFailures": false,
  "safetyStockPriority": 0.0,
  "unmetDemandPenalty": null,
  "quantum": 1000,
  "recordElementTimeseries": true,
  "engineVersion": "1.0"
}
```

Re-submitting exactly this reproduces every number above. `includeRandomFailures` is true
and changes nothing, because every `failure_prob` in this network is 0.

`recordElementTimeseries` is the flag `V9__element_timeseries.sql` introduced and it is **true by
default**. It decides whether §7.2's rows are written and nothing else: recording reads the loop's
own state and feeds nothing back into it, so a run with the flag off takes every decision this
document derives and returns every value in the table above. That is why `engineVersion` stays at
`1.0` — the model did not change, only what is kept of it. A `params_json` written before V9 has no
such field and reads back as `true`, which is what re-submitting that document would do; whether a
given run actually holds a series is answered by the endpoint's `available` flag, never by this
field.

---

## 9. What this document deliberately does not check

Each of these is exercised by a unit test instead, for a stated reason:

| Not here | Where | Why not here |
|---|---|---|
| Lead times and the pipeline | `InventoryPipelineTest` | A positive lead time adds a warm-up ramp to *every* period of both runs, and the ramp would have to be carried through all nine metrics without illuminating any of them. |
| Safety-stock replenishment | `FlowAllocatorTest` | With `safetyStockPriority > 0` each period carries a second story — material moving toward order-up-to levels — and the cost column stops being derivable by inspection. |
| `LINEAR` and `EXPONENTIAL` recovery | `RecoveryProfileTest` | The availability curve stops being two values and the table needs seven significant figures a row. |
| Demand noise, timing jitter, `failure_prob` | `ReplicationRngTest` | Nothing stochastic can be checked by hand at N = 1, which is the whole reason this run sets all three to zero. |
| Confidence intervals | `ReplicationStatisticsTest` | One replication has no sample variance. §8's nulls are the assertion. |
| Multi-product aggregation | `SimulationNetworkTest` | One product makes the aggregation the identity function. |
| The two `null` cases of `utilisation` | `ElementTimeseriesTest` | Both of this network's arcs declare a capacity of 100 and neither is targeted by the event, so neither the uncapped case nor the taken-to-zero case can arise here. §7.2.4 checks that a genuinely idle arc reports `0.0`, which is the half that *can* be provoked; the two nulls need an arc with no capacity and an arc under a severity-1.0 event. |
| The baseline-run copy-in | `ElementTimeseriesTest` | This run has a scenario, so it has a real paired baseline set (§3) and the `baseline*` columns carry that set's numbers. The copy-in convention of `V9__element_timeseries.sql` — where a run with *no* scenario writes its own series into them — needs a run with no scenario, which is `samples/four-echelon-playback/README.md` §6.5. |
| `available: false` | `ElementTimeseriesTest` | Needs a run that recorded nothing: one submitted with `recordElementTimeseries: false`, or one older than V9. Neither can be produced by running this document's scenario. |

---

## 10. If a number disagrees

This document is the specification, not a record of what the code did. A mismatch is a defect in one
of the two, and the arithmetic above is checkable by hand, so start by re-deriving the disputed
figure from §4–§6 rather than from the implementation. The most likely places for a genuine
implementation error, in the order they are worth checking:

1. **Nothing is served in any period, of either run** — `FILL_RATE` 0, `TOTAL_COST` 13500 (fixed 300
   + shortage 1000 per period, plus 50 of holding while the untouched stock sits there). The
   allocation is refusing every real path in favour of the penalty arc, which happens when **all arc
   costs are equal**: the penalty arc is one hop and the cheapest real path here is four, so a
   uniform cost makes refusing strictly cheaper than serving.

   The cause is almost always the same one. JGraphT's `CapacityScalingMinimumCostFlow` **ignores
   `MinimumCostFlowProblem.getArcCosts()` and reads costs from `graph.getEdgeWeight(edge)`** — in
   both its `init` and its `finish`. Build the problem on an unweighted graph, or forget to call
   `setEdgeWeight`, and every arc silently costs `Graph.DEFAULT_EDGE_WEIGHT` = 1. `FlowAllocator`
   states this and passes `graph::getEdgeWeight` as the cost function so the two cannot drift.

2. **Every period costs 650 and the stock never drains** — inventory is not reaching the flow
   problem as a supply source, or is entering at the node's *inbound* vertex where its production
   capacity binds instead of at its dispatch vertex.
3. **`TOTAL_COST` is 5685 and `DISRUPTION_COST_DELTA` is −210** — the shortage term is missing from
   the cost accounting, so not serving a customer is free. See §6.4.
4. **`FILL_RATE` is 1.0 in the disrupted periods** — `PLANT-1`'s availability is being applied to
   the link rather than to the node, or the node's capacity is not constraining anything because the
   node was not split into an in-half and an out-half.
5. **`FILL_RATE` is 0 everywhere** — `PLANT-1` is being treated as a supply origin. It has an inbound
   arc, so it is not one (§2); if it were, it could manufacture from nothing and `SUP-1` would be
   decorative — which would give the *opposite* symptom. A zero fill rate more likely means *nothing*
   is an origin, i.e. the inbound test is inverted.
6. **`TTR` is 4** — recovery is being tested from `onset + 1`, or the event's window is being read as
   inclusive of period 6. Start offset 3 with duration 3 covers periods 3, 4 and 5.
7. **`TTR` is 0** — the disrupted run is being compared against its own pre-onset periods rather than
   against the paired baseline replication, and period 3's fill rate is being compared with period
   3's rather than with the baseline's.
8. **`TTR` is 7** — the replication is being censored at the horizon, which means the fill-rate
   comparison never succeeded. Check the tolerance: `1.0 ≥ 1.0` must hold.
9. **`LOSS_AREA` is 30** — the area is being taken between the served-demand curves rather than
   between the fill-rate curves (§5.2).
10. **`LOSS_AREA` is 0** — the baseline replication set is not being run, or is being run *with* the
   events. Every simulation includes an undisrupted set; if it is missing, all four
   baseline-relative metrics collapse together.
11. **`RESILIENCE_INDEX` is 0.94** — the window is the whole horizon rather than `[onset, H)`. If it
    is 0.8, the window is the event's own three periods (§5.3).
12. **`DISRUPTION_COST_DELTA` is 0** — the baseline set is being run with the events applied, which
    would also show as `LOSS_AREA` = 0 and `TTR` = 0.
13. **Everything is off by a few thousandths** — the fixed-point grid of `Quantiser` is coarser than
    it should be, or capacities are rounding up where they must round down.

Four more are specific to the per-element series of §7.2:

14. **`PLANT-1`'s `throughput` is 50 in periods 0 and 1** — throughput is being read as "everything
    that left the node" rather than as the flow across its own capacity arc. Stock enters the flow at
    `out(i)` and never crosses that arc (§7.2.2); if it did, §4.3's variable cost in period 0 would
    be 150 rather than 0, so the two are the same defect seen twice.
15. **`inboundLead` is `0.0` where §7.2.3 says `null`** — a period in which nothing was dispatched
    toward a node is being reported as one in which something arrived instantly. Most likely the
    dispatch-weighted mean is being computed over all inbound arcs rather than over the ones that
    carried flow, or the "nothing dispatched" case is falling through to a zero-initialised array
    rather than to the `NaN` sentinel `ElementTrace` defines. On this network the symptom is loudest
    at `SUP-1`, which has no inbound arc at all and must be `null` in all ten periods.
16. **`utilisation` is `null` for `SUP-1 → PLANT-1` in periods 0 and 1** — the opposite error. That
    arc was fully available and carried nothing, which is a true `0.0`; `null` is reserved for an arc
    with no declared capacity and for one an outage has taken to zero capacity (§7.2.4).
17. **`inPipeline` is non-zero anywhere** — something is being scheduled into the pipeline that
    should have landed in the same period. Both lead times here are 0 and no node has a processing
    dwell, so `pipelineDepth` is 1 and every dispatch settles immediately (§7.2.1). The likeliest
    cause is an arc being routed to `hold(target)` when its lead is 0, which would also delay every
    arrival by a period and drive `FILL_RATE` down.

And two for the inventory pair of §6.6:

18. **`AVG_INVENTORY` is 50, or 25** — 50 means the sum is being reported rather than the mean; 25
    means the denominator is the number of periods that *held* something (here two, if period 1's
    zero is being counted, or one if it is not) rather than the horizon. The horizon is the
    denominator: a period holding nothing is a period this configuration ran lean.
19. **`AVG_PIPELINE` produces no row rather than a 0.0 row** — the calculator is treating an
    all-zero series as an undefined metric. A zero pipeline is a measurement (§7.2.1); the four
    disruption-relative metrics of §8 are the undefined case, and a reader that renders absent as
    zero, or zero as absent, has been given the wrong answer either way.
