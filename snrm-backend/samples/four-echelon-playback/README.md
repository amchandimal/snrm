# `four-echelon-playback/` — the visual-simulation sample (FR-18, FR-19)

*Companion to the visual-simulation playbook. Every number below is derived by hand from the
per-period loop, the order-up-to policy of `SimulationNetwork.computeReplenishTargets`
and the flow construction of `FlowAllocator`; nothing here was produced by running the application.
§6 and §8 state the exact values the API must return, so each stage of the feature can be checked
against arithmetic rather than against itself.*

The network is one file, `network.xml`, in the XML interchange format. Attach it in the
import wizard; there is no mapping step, because the document names its own fields.

```text
     SUPPLIER            PLANT              DC              CUSTOMER

   ┌──────────┐       ┌───────────┐     ┌──────────┐      ┌──────────┐
   │  SUP-1   │──────▶│  PLANT-1  │────▶│   DC-1   │─────▶│  CUST-1  │
   │ cap 100  │lead 1 │  cap 100  │lead1│ cap 100  │lead 1│ demand 10│
   │ var 0.1  │       │  var 0    │     │  var 0   │      │  per day │
   │ fixed 100│       │ fixed 200 │     │ fixed 100│      │ fixed 0  │
   │ inv 10   │       │  inv 15   │     │  inv 15  │      │  inv 20  │
   │ ss  10   │       │  ss   5   │     │  ss   5  │      │  ss   0  │
   └──────────┘       └───────────┘     └──────────┘      └──────────┘
```

Four nodes, three directed links, one product, on a clock of **1 DAY / 30 periods / NEAREST**.

Expected on import: `valid: true`, `committed: true`, **0 errors, 0 warnings** — 4 nodes, 3 links,
1 product, 4 node-product rows. Nothing rounds, nothing is lateral, every arc runs downstream in the
echelon order, and the one customer is reachable from the one supplier.

---

## 1. What this sample is for

It is the network the **visual-simulation features of FR-18 and FR-19** are verified against, stage
by stage: the animated playback of material moving down the chain, the per-period scrubber, the
inventory bars, the link-flow ribbons, the disruption overlay and the performance curve. Each of
those renders a number that this document derives by hand, so at every stage the question "is the
picture right?" reduces to "does the picture show 10/5/5/0?" rather than to a judgement about
whether an animation looks plausible.

The existing verification samples cannot do this job, and it is worth saying why rather than adding
a fourth network for the sake of it:

| Sample | Why it is not this | 
|---|---|
| `simulation-verification-3-node/` | **Both lead times are zero and `safetyStockPriority` is 0.** A unit produced at `SUP-1` reaches `CUST-1` in the same period, so there is nothing in flight to animate and no stock moving down the chain — the two things a playback view exists to show. `docs/simulation-verification.md` §1.6 states that both are deliberate omissions there, and §9 lists the pipeline and replenishment as checked by unit tests instead. |
| `metric-verification-6-node/` | No run at all; it is a structural sample. |
| `minimal-6-node/`, `xml-6-node/` | Import fixtures. Their capacities and lead times are not chosen to make a run hand-computable. |

So this is the first sample in the repository where **the pipeline and the order-up-to pull are both
switched on and both hand-derived**. That is the whole point: a playback view is a picture of the
pipeline, and until now the pipeline was the one part of the engine with no worked example.

### 1.1 Why every number is reproducible to the bit

Five things are deliberate, and each removes a source of arithmetic that would be tedious rather
than instructive:

- **Every `failureProb` is 0** — on all four nodes and all three links. `includeRandomFailures` is
  true by default and changes nothing, because there is nothing to draw against.
- **`demandNoiseCv` and `timingJitterPeriods` are 0 at engine defaults** (`SimulationParams`
  `DEFAULT_DEMAND_NOISE_CV`, `DEFAULT_TIMING_JITTER`), so demand is exactly 10 in every period of
  every replication and an event's window is exactly where it was authored. `SimulationParams`
  documents both defaults as chosen so that "a hidden default of 0.1 would make every
  hand-checkable example uncheckable" — this is the example that relies on it.
- **Every quantity is a whole number**, and every duration is a whole number of days on a one-day
  period. Nothing rounds, so the time machinery is entirely out of the way and the
  fixed-point grid of `Quantiser` (1/1000 of a unit) carries integers exactly.
- **Capacity never binds on the baseline.** Every node and link can move 100 a period against a
  demand of 10, so the baseline run is a pure question of inventory and lead time. The one place
  capacity matters is the Stage-7 event, which takes a link to zero.
- **The run is deterministic at any replication count.** Every replication is identical, so
  `CVAR_COST` equals `TOTAL_COST` and — at more than one replication — every `ciLow` and `ciHigh`
  comes back **equal to the value**, not null. (Null is the single-replication case; see §6.2.)

### 1.2 The replenishment-dominance constraint the costs satisfy

This is the one non-obvious design constraint, and getting it wrong is the difference between a
network that pulls material down the chain and one that sits still. It comes from `FlowAllocator`,
not from `docs/simulation-verification.md` — that document runs with `safetyStockPriority = 0` and
excludes replenishment from its scope (§9), so it never has to state this.

`FlowAllocator.dominatingPenalty` prices a unit of unmet **demand** at

```text
  unmetPenalty = Σ varCost(nodes) + Σ unitCost(links) + 1  =  C + 1
```

and the replenishment sink's penalty arc at `safetyStockPriority × unmetPenalty` — **0.1 × (C + 1)**
at the default. A simple path visits each node and each arc at most once, so **C** also bounds the
dearest route any unit could take. Replenishment therefore happens wherever it is physically
possible exactly when routing a unit is cheaper than refusing it:

```text
  C  <  0.1 · (C + 1)
  0.9 C  <  0.1
  C  <  1/9  =  0.1111…
```

Here `C` = 0.1 (`SUP-1`'s `varCost`) + 0 + 0 + 0 (nodes) + 0 + 0 + 0 (links) = **0.1**, comfortably
under 1/9. The margin is what makes §5 derivable by inspection: at `SUP-1` a unit of self-replenishment
costs 0.1 to produce against a 0.11 penalty for going without, so the network always produces it, and
every other node's replenishment routes at cost 0 and is never in doubt.

**The constraint is tight, and that is on purpose.** Raise `SUP-1`'s `varCost` to 0.12 and
`C` = 0.12 > 1/9: `SUP-1` would stop refilling its own buffer (0.12 to produce against 0.11 to go
without), the supplier's on-hand would drain, and the steady state of §5.3 would collapse into a
different one. If someone edits this file's costs, they must re-check this inequality first.

---

## 2. The network, in full

### 2.1 Nodes

| # | Name | Type | Capacity | Var. cost | Fixed cost | Processing | Opening stock | Safety stock | Holding | Demand |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `SUP-1` | SUPPLIER | 100 / DAY | **0.1** | 100 | 0 DAY | 10 | 10 | 0.1 / DAY | — |
| 2 | `PLANT-1` | PLANT | 100 / DAY | 0 | 200 | 0 DAY | 15 | 5 | 0.1 / DAY | — |
| 3 | `DC-1` | DC | 100 / DAY | 0 | 100 | 0 DAY | 15 | 5 | 0.1 / DAY | — |
| 4 | `CUST-1` | CUSTOMER | *(blank → unconstrained)* | 0 | 0 | 0 DAY | 20 | 0 | 0 | 10 / DAY |

`CUST-1` carries `<capacity timeUnit="DAY"/>` — a unit with no value, which is how an uncapped node
is stored. It carries no `<holdingCost>` element either, so its holding rate is 0 and
the customer's buffer is free to hold; that keeps the cost column readable while the customer is the
node whose stock the playback view moves most.

The `#` column is the order the rows appear in the document, which is the order the importer assigns
ids in. No metric reads that order: the `ROBUSTNESS_TARGETED` tie-break of §7.5, where every node
ties, ranks by node *name* precisely so that a re-imported copy of this document scores identically
to the original.

### 2.2 Links

| # | Arc | Lead time | Capacity | Unit cost |
|---|---|---|---|---|
| a | `SUP-1 → PLANT-1` | **1 DAY** | 100 / DAY | 0 |
| b | `PLANT-1 → DC-1` | **1 DAY** | 100 / DAY | 0 |
| c | `DC-1 → CUST-1` | **1 DAY** | 100 / DAY | 0 |

Three one-period legs is what makes the chain worth animating: material dispatched in period *t*
lands in period *t+1*, so at any instant there are up to three shipments in flight and the
`pipelineDepth` is 2 (one past the longest delay).

### 2.3 The product

`WIDGET-A`, `unitValue` **20**. That is what a unit of *unmet* demand costs in `TOTAL_COST`
and it is the only reason the Stage-7 disruption shows up in cost at all. It is
deliberately **not** the penalty the flow problem routes on — see `FlowAllocator`'s class note and
§1.2 above.

### 2.4 Conventions this document shares with the rest of the suite

Stated once in `com.snrm.simulation/package-info.java` and in `docs/simulation-verification.md` §2:

| Convention | Reading here |
|---|---|
| **Supply origins** | SUPPLIER or PLANT with **no inbound arc**, fixed on the intact graph: `{SUP-1}` alone. `PLANT-1` is fed by arc **a**, so its capacity is a throughput ceiling and it cannot manufacture from nothing. |
| **One commodity** | One product, so the per-product aggregation of `SimulationNetwork` is the identity. |
| **Demand** | On CUSTOMER nodes only: `CUST-1` 10, everything else 0. |
| **Fixed cost** | Charged every period for every node, whatever it ships: 100 + 200 + 100 + 0 = **400 per period, in every period of both runs**. |
| **Stock bypasses production capacity** | On-hand inventory enters the flow at the node's *dispatch* vertex (`out(i)`), because on-hand stock acts as an additional supply source. A node can always ship what it already holds. |
| **Lead-time arrivals land in the hold** | An arc with lead ≥ 1 lands at `hold(target)` and the engine defers the arrival by the lead time. So a shipment **cannot be re-dispatched in the period it is sent**, and cannot serve demand in that period either. This is the single mechanic the whole document turns on. |

---

## 3. The order-up-to targets

`SimulationNetwork.computeReplenishTargets`:

```text
  coverage(i)        = min( demand of the CUSTOMERs reachable from i , i's own capacity )
  replenishDelay(i)  = longest inbound lead time + i's own processing dwell, in periods
  replenishTarget(i) = safetyStock(i) + coverage(i) × ( 1 + replenishDelay(i) )
```

Every node reaches `CUST-1` (the customer reaches itself), so **coverage is 10 everywhere**:
`min(10, 100)` = 10 at the three capped nodes, and `min(10, ∞)` = 10 at the customer.

| Node | Safety stock | Coverage | Longest inbound lead | Processing | Delay | **Target** |
|---|---|---|---|---|---|---|
| `SUP-1` | 10 | 10 | **0** — no inbound arc | 0 | 0 | 10 + 10 × 1 = **20** |
| `PLANT-1` | 5 | 10 | 1 (arc **a**) | 0 | 1 | 5 + 10 × 2 = **25** |
| `DC-1` | 5 | 10 | 1 (arc **b**) | 0 | 1 | 5 + 10 × 2 = **25** |
| `CUST-1` | 0 | 10 | 1 (arc **c**) | 0 | 1 | 0 + 10 × 2 = **20** |

Each period a node orders `target − onHand − inTransit`, floored at the grid — the base-stock
netting, which is what stops a node with a one-period lead time re-ordering the same
shortfall while its first shipment is still on the wire.

Two consequences drive everything below:

1. **A node whose shortfall is 0 has no `hold(i) → T_R` arc at all** (`FlowAllocator` omits it), so
   its inbound link has no outlet that period and **carries nothing**. That is why the flow columns
   in §5 have zeros in them rather than a steady 10 from period 0.
2. **`CUST-1` opens at its target** (20 on hand against a target of 20), so it orders nothing in
   period 0 and arc **c** is idle in that period. The chain fills from the back forwards.

---

## 4. Why the baseline is derivable by inspection

Each period the allocation is one minimum-cost flow. With every link at unit cost 0 and
only `SUP-1` carrying a variable cost, the entire routing decision reduces to three facts:

- **Serving demand always wins.** The penalty is 1.1 per unit against a routing cost of at most 0.1,
  so `CUST-1` is served from its own stock whenever it holds any.
- **Replenishing always wins where it is physically possible** — §1.2. Moving a unit from an
  upstream node's stock down one link costs 0 against a 0.11 penalty.
- **`SUP-1` produces only what it must.** Its stock is free to dispatch and its production costs 0.1
  a unit, so it ships from stock first and produces only to refill its own buffer (and, in one
  period of the disrupted run, to cover a shortfall its stock could not).

So each period: **serve the customer from the customer's stock; ship each node's shortfall down from
the node above it, out of that node's stock; produce at `SUP-1` only to refill `SUP-1`.**

---

## 5. The baseline run, period by period

`onHand` is *after* the period's arrivals and *before* the allocation. Flows are what is **dispatched**
in the period — they land at the far end of the arc in the period after. Costs are fixed 400 +
variable + transport (always 0) + holding + shortage.

### 5.1 Periods 0–5

| t | on-hand in (S/P/D/C) | demand | served | dispatched a, b, c | **end on-hand (S/P/D/C)** | fill | var | hold | **cost** |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 10 / 15 / 15 / 20 | 10 | 10 | **10, 10, 0** | **10 / 5 / 15 / 10** | 1.0 | 1.0 | 3.0 | **404** |
| 1 | 10 / 15 / 25 / 10 | 10 | 10 | **10, 0, 10** | **10 / 15 / 15 / 0** | 1.0 | 1.0 | 4.0 | **405** |
| 2 | 10 / 25 / 15 / 10 | 10 | 10 | **0, 10, 10** | **20 / 15 / 5 / 0** | 1.0 | 1.0 | 4.0 | **405** |
| 3 | 20 / 15 / 15 / 10 | 10 | 10 | **10, 10, 10** | **10 / 5 / 5 / 0** | 1.0 | 0 | 2.0 | **402** |
| 4 | 10 / 15 / 15 / 10 | 10 | 10 | **10, 10, 10** | **10 / 5 / 5 / 0** | 1.0 | 1.0 | 2.0 | **403** |
| 5 | 10 / 15 / 15 / 10 | 10 | 10 | **10, 10, 10** | **10 / 5 / 5 / 0** | 1.0 | 1.0 | 2.0 | **403** |

**Periods 4–29 are the steady state**: identical in every column, at a cost of **403**.

### 5.2 The working, period by period

```text
t = 0    opening stock, nothing in flight
  shortfalls  SUP 20−10=10 · PLANT 25−15=10 · DC 25−15=10 · CUST 20−20=0
  demand      CUST-1 serves 10 out of its own 20                     served 10
  CUST needs nothing, so hold(CUST) has no sink arc → arc c carries 0
  DC   ← 10 from PLANT-1's stock over arc b                          b = 10
  PLANT← 10 from SUP-1's stock over arc a                            a = 10
  SUP  ← 10 produced through its own throughput arc      variable = 10 × 0.1 = 1.0
  end   SUP 10−10+10=10 · PLANT 15−10=5 · DC 15−0=15 · CUST 20−10=10
  hold  (10+5+15)×0.1 + 10×0 = 3.0                          total = 400+1.0+3.0 = 404

t = 1    arrivals: PLANT +10 (a), DC +10 (b)
  shortfalls  SUP 10 · PLANT 10 · DC 25−25=0 · CUST 20−10=10
  DC is full, so arc b carries 0 this period — the pull skips a rung
  CUST ← 10 from DC-1's stock over arc c                             c = 10
  PLANT← 10 from SUP-1's stock over arc a                            a = 10
  SUP  ← 10 produced                                     variable = 1.0
  end   SUP 10 · PLANT 15−0=15 · DC 25−10=15 · CUST 10−10=0
  hold  (10+15+15)×0.1 = 4.0                                total = 400+1.0+4.0 = 405

t = 2    arrivals: PLANT +10 (a), CUST +10 (c)
  shortfalls  SUP 10 · PLANT 25−25=0 · DC 10 · CUST 10
  PLANT is full, so arc a carries 0 and SUP-1's stock is not drawn
  end   SUP 10−0+10=20 · PLANT 25−10=15 · DC 15−10=5 · CUST 10−10=0
  hold  (20+15+5)×0.1 = 4.0                                 total = 400+1.0+4.0 = 405

t = 3    arrivals: DC +10 (b), CUST +10 (c)
  shortfalls  SUP 20−20=0 · PLANT 10 · DC 10 · CUST 10
  SUP-1 is full for the only time in the run, so it produces nothing  variable = 0
  all three arcs carry 10, out of stock, at cost 0
  end   SUP 20−10=10 · PLANT 15−10=5 · DC 15−10=5 · CUST 10−10=0
  hold  (10+5+5)×0.1 = 2.0                                    total = 400+0+2.0 = 402

t = 4    arrivals: PLANT +10, DC +10, CUST +10 → 10/15/15/10, the same opening as t=3
         except SUP-1 now holds 10 rather than 20, so it produces 10 again  variable = 1.0
  end   10/5/5/0                                            total = 400+1.0+2.0 = 403
```

### 5.3 The steady state

From period 4 the loop is closed: the period **opens** at `10 / 15 / 15 / 10` after arrivals, ships
10 down each of the three arcs, serves 10, and **ends** at `10 / 5 / 5 / 0` with 10 units in flight
on each arc. Period 5 opens on exactly the same state, so periods 4–29 are twenty-six identical
periods.

The three periods before it are the warm-up, and the shape is worth naming because the playback view
draws it: the pull propagates **backwards** one rung a period (the customer is already full at t=0,
so `DC-1` fills at t=1, `PLANT-1` at t=2), while the material flows **forwards**, so the two meet at
period 3 and the network settles from period 4.

### 5.4 The 30-period totals

```text
  Σ served = 10 × 30 = 300          Σ demand = 10 × 30 = 300

  fixed     30 × 400                              = 12000
  variable  29 periods × 10 units × 0.1           =    29     (every period but t = 3)
  transport 0                                     =     0
  holding   3.0 + 4.0 + 4.0 + 2.0 + 26 × 2.0      =    65
  shortage  0                                     =     0
                                                    ─────
  TOTAL_COST                                      = 12094
```

Cross-check against the period column: 404 + 405 + 405 + 402 + 26 × 403 = 1616 + 10478 = **12094**. ✓

**Mass balance.** Every unit is accounted for:

```text
  60 opening (10 + 15 + 15 + 20)  +  290 produced at SUP-1 (29 periods × 10)
    = 350
  300 served  +  50 remaining (20 on hand at t=29 end, 30 in flight on the three arcs)
    = 350                                                                          ✓
```

The 30 in flight is the thing a playback view has to draw and a static table cannot: at the end of
every steady-state period there is exactly one shipment of 10 on each of the three arcs.

---

## 6. The values a baseline run must return

Submit with **no `scenarioId`** — the baseline run of FR-17. Such a run executes N replications
rather than 2N (`MonteCarloRunner.runsPairedBaseline`), and `RUN_TIMESERIES` writes the run's own
curve into the baseline columns.

### 6.1 The metric rows

`GET /api/v1/simulations/{runId}/results` returns **seven** rows, all `NETWORK`-scoped, all with
`runId` set.

| `metricCode` | Exact value | Decimal | `displayUnit` |
|---|---|---|---|
| `FILL_RATE` | 300/300 = 1 | 1.0 | null |
| `SERVICE_LEVEL` | 30/30 = 1 | 1.0 | null |
| `MIN_FILL_RATE` | 1 | 1.0 | null |
| `TOTAL_COST` | 12094 | 12094.0 | null |
| `CVAR_COST` | 12094 | 12094.0 | null |
| `AVG_INVENTORY` | 660/30 = 22 | **22.0** | null |
| `AVG_PIPELINE` | 870/30 = 29 | **29.0** | null |

#### The inventory pair (FR-19)

`AVG_INVENTORY` and `AVG_PIPELINE` are plain means over the whole horizon of the two columns §6.4
tabulates, and **this is the sample they were added for**: on
`docs/simulation-verification.md`'s network every lead time is 0, so its pipeline figure is 0 in
every period and the metric can say nothing about material in flight (§1).

```text
  endingInventory  =  40, 40, 40,  then 20 in every period from t = 3
  AVG_INVENTORY    =  ( 3 × 40  +  27 × 20 ) / 30  =  ( 120 + 540 ) / 30  =  660 / 30  =  22.0

  inPipeline       =  20, 20, 20,  then 30 in every period from t = 3
  AVG_PIPELINE     =  ( 3 × 20  +  27 × 30 ) / 30  =  (  60 + 810 ) / 30  =  870 / 30  =  29.0
```

Both are **exactly representable**: `660 / 30` and `870 / 30` are whole numbers, and every summand is
an integer the fixed-point grid carries exactly (§1.1). Compare them to the bit; a 21.999… here is a
defect, not summation order.

Their **sum is 51**, and that is the third check on §5.4's mass balance: 22 on hand plus 29 in flight
is the 50 units the horizon never serves, plus the one unit of average the three-period warm-up adds
by holding 60 rather than 50 while the chain fills. It is the same 1530 ÷ 30 the two columns of §6.4
sum to, read as two metrics instead of thirty rows.

**Neither carries a winner in the comparison view.** `direction()` is `NEUTRAL` on both, deliberately:
a leaner configuration is cheaper to hold and quicker to run dry, and nothing makes
either end of that trade-off the objective. The rows appear in the matrix like any other and **no
cell is highlighted** — the same treatment `DENSITY`, `AVG_PATH` and `CLUSTERING` get.

### 6.2 The four rows that are absent, and must render absent

`TTR`, `LOSS_AREA`, `DISRUPTION_COST_DELTA` and `RESILIENCE_INDEX` produce **no rows at all** on a
baseline run. Each of their calculators opens with the same guard —

```java
if (traces.replications() == 0 || traces.baseline().isEmpty()) {
    return List.of();
}
```

— because a run with no scenario has no paired baseline set to measure against and nothing to
recover from. This is not a gap in the data and it is not a zero:

> **A `TTR` of 0 and an absent `TTR` are opposite claims.** Zero says the network recovered
> instantly from a disruption; absent says there was no disruption. Rendering the first where the
> second is true would put a resilience finding on screen that the run never made.

So the UI must show these four as **absent** — an em dash, a "not applicable", a greyed row — and
never as 0, never as a bar of length zero, and never omitted so silently that a reader thinks the
metric was not computed. This is the "absent metric rows render absent, never zero" rule, and this
sample is the cheapest way to provoke it: one import, one baseline run, four absent rows.

### 6.3 The confidence intervals

At the default 100 replications every replication is identical, so the sample standard deviation is
0 and `ciLow` = `ciHigh` = **the value itself** (`ReplicationStatistics`). At `replications: 1` both
are **null** instead, because one replication has no sample variance. Both are correct; a checklist
should say which one it expects.

This is the one place a **zero-width** interval is the right answer rather than a suspicious one, and
it is worth saying what a view should do with it: `AVG_INVENTORY` reads `22.0 [22.0 – 22.0]` at
N = 100 and a bare `22.0` at N = 1. Neither draws a whisker of any visible width — `shared/ci-value`
renders a degenerate interval as a mark and an absent one as nothing at all — so on this run **no
metric card shows a whisker**, which is exactly what "this run is deterministic" looks like.

### 6.4 The time series

`GET /api/v1/simulations/{runId}/timeseries` returns thirty points. `baselineServedDemand` and
`baselineCost` carry this run's own curve, which is definitionally true for a baseline run rather
than a stand-in.

| `period` | `totalDemand` | `servedDemand` | `cost` | `endingInventory` | `inPipeline` |
|---|---|---|---|---|---|
| 0 | 10 | 10 | 404 | **40** | **20** |
| 1 | 10 | 10 | 405 | **40** | **20** |
| 2 | 10 | 10 | 405 | **40** | **20** |
| 3 | 10 | 10 | 402 | **20** | **30** |
| 4–29 | 10 | 10 | 403 each | **20** | **30** each |

`Σ cost` = 12094, matching §5.4.

`endingInventory` and `inPipeline` are the two columns `V9__element_timeseries.sql` adds; both were
already on every `PeriodTrace` and both were being discarded. They are the row sums of §5.1's *end
on-hand* column and of the flows in flight, and they are the cheapest check that the two halves of
this document agree:

```text
  t = 0   end on-hand 10 + 5 + 15 + 10 = 40      in flight a=10, b=10, c=0  = 20
  t = 3   end on-hand 10 + 5 +  5 +  0 = 20      in flight a=10, b=10, c=10 = 30
```

The regime change at period 3 is the warm-up ending: until then the chain is still filling and one
of the three arcs is idle each period (§3, consequence 1), so 10 units are sitting as stock instead
of travelling. From period 3 the steady state carries a shipment on every arc — **30 in flight, 20
on hand, in every period to the end of the horizon**. Their sum is 50, which is the 50 units §5.4's
mass balance leaves over after 300 are served, and it is constant because nothing is created or
destroyed once `SUP-1` settles into producing 10 a period.

### 6.5 The per-element series

`GET /api/v1/simulations/{runId}/timeseries/elements` returns `available: true`, four nodes and
three links (FR-18). Every array is thirty long and index *t* is period *t*.

This is the table the playback view is checked against, and it is the whole reason this sample
exists: `docs/simulation-verification.md` §7.2 derives the same series on a network where
`inPipeline` is 0 in every period, so it can say nothing about material in flight.

#### 6.5.1 `onHand` — the inventory bars

Read straight off §5.1's **end on-hand** column, which is the same convention the column stores:
stock after the period's arrivals *and* its dispatches.

| Node | t=0 | t=1 | t=2 | t=3 | t=4 … 29 |
|---|---|---|---|---|---|
| `SUP-1` | 10 | 10 | **20** | 10 | **10** |
| `PLANT-1` | 5 | **15** | **15** | 5 | **5** |
| `DC-1` | **15** | **15** | 5 | 5 | **5** |
| `CUST-1` | 10 | **0** | 0 | 0 | **0** |

Each bulge is a rung of the chain waiting for the rung below it to ask: `DC-1` holds 15 through
periods 0–1 because `CUST-1` opened at its target and ordered nothing at t=0 (§3, consequence 2),
`PLANT-1` holds 15 through periods 1–2 for the same reason one rung up, and `SUP-1` peaks at 20 in
period 2 — its own target — because `PLANT-1` was full that period and drew nothing from it. The
pulse travels **backwards** one rung a period, which is §5.3's warm-up seen from the elements rather
than from the totals.

`CUST-1` ending at 0 from period 1 onward is not a shortage: it receives 10 and serves 10, so its
bar is a pass-through rather than a buffer. Its `served` is 10 in every one of the thirty periods and
its `unserved` is 0 in every one; every other node's `served` and `unserved` are 0 throughout, because
demand sits on `CUSTOMER` nodes alone (§2.4).

**`throughput` is 0 at three of the four nodes, in every period, and that is correct.** It is the
flow across a node's own capacity arc (`in(i) → mid(i)`), and on this network nothing reaches that
arc anywhere but `SUP-1`: every leg has a lead time, so inbound material lands in `hold(target)` and
never passes through the target's capacity (§2.4), while stock a node ships out enters the flow at
its dispatch vertex and never passes through it either. So

| Node | `throughput` |
|---|---|
| `SUP-1` | 10 in every period **except t=3**, where it is **0** |
| `PLANT-1`, `DC-1`, `CUST-1` | **0 in all thirty periods** |

and `SUP-1`'s row is §5.4's variable-cost line divided by its `varCost` of 0.1: 29 periods at 10
units, and nothing at t=3 where it was already full (§5.2). This is the sharpest contrast with
`docs/simulation-verification.md` §7.2.2, where both lead times are 0 and *every* node's throughput
is non-zero — the same column, on two networks, meaning two visibly different things. A view that
labels it "production" is right here and wrong there; "flow across the node's capacity" is right on
both.

#### 6.5.2 `flow` — the link ribbons

Exactly §5.1's **dispatched a, b, c** column, and the same numbers §5.2 works period by period.
`flow` is what was **dispatched**, so each of these lands at the far end in the *next* period.

| Link | t=0 | t=1 | t=2 | t=3 | t=4 … 29 |
|---|---|---|---|---|---|
| **a** `SUP-1 → PLANT-1` | 10 | 10 | **0** | 10 | **10** |
| **b** `PLANT-1 → DC-1` | 10 | **0** | 10 | 10 | **10** |
| **c** `DC-1 → CUST-1` | **0** | 10 | 10 | 10 | **10** |

Each zero is a rung whose downstream neighbour was already at its order-up-to target and therefore
had no `hold(i) → T_R` arc for the inbound link to drain into (§3, consequence 1). The three zeros
walk **up** the chain one period at a time — c at t=0, b at t=1, a at t=2 — and the network is on its
steady state from t=3.

`utilisation` is `flow / 100` on all three arcs in all thirty periods, since every capacity is 100
and the baseline run disrupts nothing: **0.1 wherever the flow is 10, and exactly 0.0 — not null —
wherever it is 0.** An idle arc at full availability is idle, not unmeasurable. `availability` is
`1.0` on every node and every link in every period; nothing here is ever disrupted.

#### 6.5.3 `arrivals` — the offset a playback view has to get right

`arrivals(i, t)` is the previous period's flow on *i*'s inbound arc, because every leg is one period
long. Read it beside §6.5.2 and the animation's contract is visible: **a ribbon leaving in period *t*
becomes a bar arriving in period *t+1*.**

| Node | t=0 | t=1 | t=2 | t=3 | t=4 … 29 |
|---|---|---|---|---|---|
| `SUP-1` | 0 | 0 | 0 | 0 | 0 — nothing is inbound to a supply origin |
| `PLANT-1` | 0 | 10 | 10 | **0** | 10 |
| `DC-1` | 0 | 10 | **0** | 10 | 10 |
| `CUST-1` | 0 | **0** | 10 | 10 | 10 |

These are §5.2's arrival lines, transcribed. `inTransit` is their mirror at the other end of the
period: `PLANT-1`, `DC-1` and `CUST-1` each end period *t* holding whatever their inbound arc
dispatched in *t*, so `inTransit` for each is exactly its own row of §6.5.2 shifted to the target,
and the three sum to the `inPipeline` column of §6.4 (20, 20, 20, 30, 30 …). `SUP-1`'s `inTransit`
is 0 in every period.

#### 6.5.4 `inboundLead` — where `null` and `1.0` are different answers

Every arc has a lead time of 1, so a period in which anything at all is dispatched toward a node
gives a dispatch-weighted mean of exactly **1.0**, and a period in which nothing is gives **null**.
The nulls are therefore the zeros of §6.5.2 read one column across:

| Node | Its inbound arc | `inboundLead` |
|---|---|---|
| `SUP-1` | *(none)* | **null in all thirty periods** |
| `PLANT-1` | **a** | 1.0, 1.0, **null**, 1.0, then 1.0 to t=29 |
| `DC-1` | **b** | 1.0, **null**, 1.0, 1.0, then 1.0 to t=29 |
| `CUST-1` | **c** | **null**, 1.0, 1.0, 1.0, then 1.0 to t=29 |

**A `null` here is a claim, not a gap.** It says nothing was dispatched toward that node in that
period; a `0.0` would say something was, and arrived instantly. On this network the two are trivially
distinguishable because every real lead is 1 — which is exactly why the check belongs here rather
than on `simulation-verification-3-node/`, where every lead is 0 and a defective implementation
returning 0 for "nothing dispatched" would be indistinguishable from a correct one.

`SUP-1`'s row is the structural case: a supply origin has no inbound arc at all, so its inbound lead
is undefined in every period of every run this network can produce.

#### 6.5.5 The baseline columns, and the copy-in convention

This is a **baseline run** — no `scenarioId` — so `MonteCarloRunner` skips the paired set
and there is no undisrupted series to mirror. `V9__element_timeseries.sql` states the convention:
`baseline_on_hand`, `baseline_served` and `baseline_flow` are **copied from the run's own disrupted
series**, exactly as V6's `aggregate` copies `baseline_served_demand` and `baseline_cost` for the
same run.

So on the whole of this table

```text
  baselineOnHand(i, t) = onHand(i, t)      baselineServed(i, t) = served(i, t)
  baselineFlow(e, t)   = flow(e, t)
```

value for value, in all thirty periods, for all four nodes and all three links. That is
definitionally true rather than a stand-in — the paired set, had it run, would have drawn identically
— and it is what keeps a per-element resilience triangle drawable, with an area of zero, on a run
that had nothing to recover from. **A checklist must assert equality here, not absence:** the columns
are populated and the two curves coincide.

#### 6.5.6 If one of these disagrees

Beyond the four failure modes `docs/simulation-verification.md` §10 items 14–17 name, two are
specific to this sample:

1. **`arrivals` and `flow` are in step rather than offset by one period.** Either arrivals are being
   captured after the pipeline slot is cleared (in which case they are all 0 instead), or the
   dispatch is being recorded at the target rather than at the source. §6.5.3 is the whole contract.
2. **`inboundLead` is 1.0 in every period including the three that must be null.** The mean is being
   taken over a node's inbound arcs rather than over the flow dispatched along them — with one
   inbound arc of lead 1, that gives 1.0 whether or not it carried anything.

## 7. The topological metrics

`GET /api/v1/networks/{id}/metrics/topological`, worked by the method of
`docs/metric-verification.md`. The undirected form used by §7.3 and §7.5 is the path
`S – P – D – C` with three edges; undirected degrees **S 1, P 2, D 2, C 1**, summing to 4 = 2 × 3.

### 7.1 `DENSITY`

```text
  n = 4,  m = 3          n · (n − 1) = 4 × 3 = 12
  DENSITY = 3 / 12 = 0.25
```

The denominator is 12 and not 6 because the network is directed. An undirected denominator would
report 0.5 and make a one-way chain look twice as interconnected as it is.

### 7.2 `AVG_PATH`

Shortest **directed** paths, in hops, over the ordered pairs that have one:

| From | Reachable, with distance | Pairs | Σ d |
|---|---|---|---|
| `SUP-1` | `PLANT-1` 1 · `DC-1` 2 · `CUST-1` 3 | 3 | 6 |
| `PLANT-1` | `DC-1` 1 · `CUST-1` 2 | 2 | 3 |
| `DC-1` | `CUST-1` 1 | 1 | 1 |
| `CUST-1` | *(nothing — no outbound arc)* | 0 | 0 |
| | | **6** | **10** |

```text
  AVG_PATH = 10 / 6 = 5/3 = 1.6666666667
```

Six of the twelve ordered pairs are connected — exactly half, which is what a simple chain looks
like. The 6 is as much of the finding as the 1.67.

### 7.3 `CLUSTERING`

Neighbours are taken undirected; a node with fewer than two has no pair of them and contributes 0.

| Node | Neighbours | k | Pairs | Joined | C(v) |
|---|---|---|---|---|---|
| `SUP-1` | P | 1 | — | — | **0** |
| `PLANT-1` | S, D | 2 | (S,D) ✗ | 0 | **0** |
| `DC-1` | P, C | 2 | (P,C) ✗ | 0 | **0** |
| `CUST-1` | D | 1 | — | — | **0** |

```text
  CLUSTERING = 0 / 4 = 0.0
```

**Exactly 0, and here that is the right answer rather than a symptom.** A path graph has no
triangles at all, so there is no redundancy for the metric to find. (`metric-verification-6-node/`
exists precisely because a 0 from a broken implementation and a 0 from a chain are indistinguishable
— that sample carries the lateral `DC-1 → DC-2` arc so `CLUSTERING` is non-zero there. Check both.)

### 7.4 `NODE_CRITICALITY`

`criticality(v) = ( D(intact) − D(without v) ) / D(intact)`, where **D** is the maximum demand
servable in one period — a maximum flow on the split-node graph of `docs/metric-verification.md`
§6.1. An unconstrained capacity becomes the network's total demand, **10**.

**D(intact) = 10.** The single sink arc `out(CUST-1) → SINK` has capacity 10 and is a cut; the path
`SOURCE → SUP-1 → PLANT-1 → DC-1 → CUST-1 → SINK` carries 10 against a tightest capacity of 100.
Flow = cut = 10, so the network is demand-limited.

| Removed | What survives | D | Criticality |
|---|---|---|---|
| `SUP-1` | Nothing can enter — the origin set `{SUP-1}` is fixed and `PLANT-1` is **not** promoted | **0** | **1** |
| `PLANT-1` | Arc **a** is `SUP-1`'s only outbound arc and goes with the node | **0** | **1** |
| `DC-1` | `CUST-1`'s only inbound arc is **c**, which goes with the node | **0** | **1** |
| `CUST-1` | The demand it existed to represent is removed with it | **0** | **1** |

```text
  NODE_CRITICALITY = 1.0 for all four nodes
```

**All four score 1, for two different reasons, and a checklist must not read that as one finding.**
`SUP-1`, `PLANT-1` and `DC-1` score 1 by *removal*: an unbranched chain has no alternative route, so
every intermediate node is indispensable. `CUST-1` scores 1 by the own-demand-share convention of
`docs/metric-verification.md` §6.4 — a customer's criticality is its share of total demand, and with
one customer that share is 10/10. This network is the degenerate case where the two readings
coincide, which makes it a poor test of *telling them apart* and an excellent test of the panel
labelling them: `metric-verification-6-node/` is where the two mechanisms give different numbers.

### 7.5 `ROBUSTNESS_TARGETED`

The index is the Schneider/Lou robustness `R = Σ S(k) / (n·S(0))` over the removal steps
k = 1 … n — the k = 0 term is the normaliser, not a summand (`docs/metric-verification.md` §7).
The order is descending criticality, ties broken by node *name* ascending (two runs
over one network must not disagree because a tie fell differently, and names, unlike ids, survive
a re-import). Every node ties at 1, so the order is name order: `CUST-1`, `DC-1`, `PLANT-1`,
`SUP-1`.

| k | Just removed | Remaining | Components | S(k) |
|---|---|---|---|---|
| 0 | — | S, P, D, C | one | **4** |
| 1 | `CUST-1` | S, P, D | one | **3** |
| 2 | `DC-1` | S, P | one | **2** |
| 3 | `PLANT-1` | S | one | **1** |
| 4 | `SUP-1` | — | — | **0** |

```text
  Σ S(k) = 3 + 2 + 1 + 0 = 6                       k = 1 … 4
  ROBUSTNESS_TARGETED = 6 / (4 · 4) = 6 / 16 = 3/8 = 0.375
```

**Exactly 3/8, which is the theoretical maximum** of the [0, (n−1)/2n] range for n = 4. Peeling a
path graph from one end never fragments it — the remainder is connected after every removal, so
S drops by exactly one node per step — and name order peels this chain from the customer end. A
chain is the *most* robust thing this metric can measure and the *least* robust thing §7.4 can
measure, and that contradiction is the point: `ROBUSTNESS_*` asks about structural cohesion,
`NODE_CRITICALITY` asks about flow. `metric-verification-6-node/` §7.3 makes the same argument on
a network where the divergence is subtler.

### 7.6 `ROBUSTNESS_RANDOM`

The expected value of the same index over uniformly random removal orders. `S(k)` depends only on
*which* k nodes are gone, so `E[S(k)]` is the plain average over the C(4,k) subsets — 2⁴ = 16
cases, and with n = 4 well under `ComponentCurve.EXACT_ENUMERATION_LIMIT` (16) the implementation
enumerates them exactly and involves no randomness at all.

**k = 1** — four cases, listed by what is removed:

| Removed | Remaining | Components | S |
|---|---|---|---|
| S | P, D, C | one | 3 |
| P | S, D, C | {D,C}, {S} | 2 |
| D | S, P, C | {S,P}, {C} | 2 |
| C | S, P, D | one | 3 |

`Σ S = 10`, **E[S(1)] = 10/4 = 5/2**.

**k = 2** — six cases, listed by the pair that remains. S = 2 if that pair is one of the 3 edges,
1 otherwise:

| Remaining | Edge? | S |
|---|---|---|
| S, P | ✓ | 2 |
| S, D | ✗ | 1 |
| S, C | ✗ | 1 |
| P, D | ✓ | 2 |
| P, C | ✗ | 1 |
| D, C | ✓ | 2 |

`Σ S = 9`, **E[S(2)] = 9/6 = 3/2**.

**k = 3** — four cases, one node left, always its own component: **E[S(3)] = 1**.
**k = 0** is the whole network, **4**; **k = 4** is **0**.

```text
  Σ E[S(k)] = 5/2 + 3/2 + 1 + 0 = 5                 k = 1 … 4
  ROBUSTNESS_RANDOM = 5 / (4 · 4) = 5 / 16 = 0.3125
```

Exact — no seed, no sampling, identical on every call.

Note that **Rr < Rt here** (0.3125 < 0.375), the ordering readers expect, and that it holds for the
plainest possible reason: targeted removal peels the chain from one end, random removal sometimes
snaps it in the middle. (`metric-verification-6-node/` inverts the ordering; between the two samples
both cases are covered.)

### 7.7 `SPOF_NODE_COUNT`, `SPOF_ARC_COUNT` and `SPOF_COUNT`

A customer is *supplied* if a directed path reaches it from a supply origin. `CUST-1` is supplied in
the intact network via `S→P→D→C`. Removing a customer does not count as disconnecting it.

| Removed | `CUST-1` supplied? | SPOF |
|---|---|---|
| `SUP-1` | no — no origin remains | **yes** |
| `PLANT-1` | no — `SUP-1` has no other outbound arc | **yes** |
| `DC-1` | no — arc **c** was `CUST-1`'s only inbound | **yes** |
| `CUST-1` | *(removed — not asked)* | no |
| **a** `SUP-1 → PLANT-1` | no | **yes** |
| **b** `PLANT-1 → DC-1` | no | **yes** |
| **c** `DC-1 → CUST-1` | no | **yes** |

```text
  SPOF_NODE_COUNT = 3
  SPOF_ARC_COUNT  = 3
  SPOF_COUNT      = 3 + 3 = 6
```

Every element of an unbranched chain is a single point of failure except the customer itself. **If
this comes back as 7, the customer is being counted against itself** — the same defect
`docs/metric-verification.md` §10 item 7 names, and the split says which half it landed in.

### 7.8 The values the API must return

**Twelve** rows: eight network-scoped and four `NODE`-scoped criticalities, in suite order. Every row
has `runId: null`, `ciLow: null`, `ciHigh: null` and `displayUnit: null`.

| `metricCode` | `scope` | `scopeName` | Exact value | Decimal (10 s.f.) |
|---|---|---|---|---|
| `DENSITY` | NETWORK | — | 1/4 | 0.2500000000 |
| `SPOF_NODE_COUNT` | NETWORK | — | 3 | 3 |
| `SPOF_ARC_COUNT` | NETWORK | — | 3 | 3 |
| `SPOF_COUNT` | NETWORK | — | 6 | 6 |
| `AVG_PATH` | NETWORK | — | 5/3 | 1.666666667 |
| `CLUSTERING` | NETWORK | — | 0 | 0.000000000 |
| `NODE_CRITICALITY` | NODE | `SUP-1` | 1 | 1.000000000 |
| `NODE_CRITICALITY` | NODE | `PLANT-1` | 1 | 1.000000000 |
| `NODE_CRITICALITY` | NODE | `DC-1` | 1 | 1.000000000 |
| `NODE_CRITICALITY` | NODE | `CUST-1` | 1 | 1.000000000 |
| `ROBUSTNESS_RANDOM` | NETWORK | — | 5/16 | 0.3125000000 |
| `ROBUSTNESS_TARGETED` | NETWORK | — | 3/8 | 0.3750000000 |

**On comparing.** `5/3` is the only value here that is not exactly representable as an IEEE double;
compare it to a tolerance of 1 × 10⁻⁹. Every other value — 0.25, 0, 1, 3, 0.3125, 0.375, 6 — is a
dyadic rational or an integer and **should match to the bit**. That is unusual and useful: on this
network an off-by-a-last-bit result is a real defect rather than summation order.

---

## 8. The disruption scenario (Stage 7)

Not in the sample directory: the canonical import schema carries networks and not
scenarios, because a scenario is project-scoped and replayable across variants. Author it
through `POST /projects/{id}/scenarios` and `POST /scenarios/{id}/events?networkId=…`.

### 8.1 The event

| Field | Value |
|---|---|
| target | **`LINK`** → `DC-1 → CUST-1` (arc **c**) |
| start offset | **10 DAY** |
| duration | **3 DAY** |
| severity | **1.0** |
| recovery profile | `STEP` |
| probability | **1.0** |

The window is periods **10, 11 and 12** — offset 10 ÷ 1 = period 10, duration 3 ÷ 1 = 3 periods, so
it ends at period 13 and period 13 is already recovered. `STEP` holds availability at `1 − severity`
= 0 for the whole window and returns it to 1 at once, which makes the availability curve two values
and the arithmetic below derivable.

Severity 1.0 on a *link* is chosen deliberately over a partial outage of a node. Arc **c** has
capacity 100 against a flow of 10, so any severity below 0.9 would change nothing at all and the
event would verify nothing (`FlowAllocator.adjusted` — halving a ceiling that does not bind changes
nothing). A total cut is the only outage this network can feel, and it is also the clearest thing to
draw: the last leg of the chain goes dark for three periods.

### 8.2 What it does, period by period

The run is identical to §5 through period 9 and identical again from period 18. Columns as in §5.1;
`avail c` is arc **c**'s availability multiplier.

| t | avail c | on-hand in (S/P/D/C) | demand | served | dispatched a, b, c | **end on-hand** | fill | var | hold | short | **cost** |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 9 | 1.0 | 10 / 15 / 15 / 10 | 10 | 10 | 10, 10, 10 | 10 / 5 / 5 / 0 | 1.0 | 1.0 | 2.0 | 0 | **403** |
| **10** | **0** | 10 / 15 / 15 / 10 | 10 | **10** | 10, 10, **0** | 10 / 5 / **15** / 0 | **1.0** | 1.0 | 3.0 | 0 | **404** |
| **11** | **0** | 10 / 15 / 25 / **0** | 10 | **0** | 10, **0**, 0 | 10 / 15 / 25 / 0 | **0.0** | 1.0 | 5.0 | **200** | **606** |
| **12** | **0** | 10 / 25 / 25 / 0 | 10 | **0** | **0, 0, 0** | 20 / 25 / 25 / 0 | **0.0** | 1.0 | 7.0 | **200** | **608** |
| 13 | 1.0 | 20 / 25 / 25 / 0 | 10 | **0** | 0, 0, **20** | 20 / 25 / 5 / 0 | **0.0** | 0 | 5.0 | **200** | **605** |
| 14 | 1.0 | 20 / 25 / 5 / **20** | 10 | **10** | 0, 20, 0 | 20 / 5 / 5 / 10 | **1.0** | 0 | 3.0 | 0 | **403** |
| 15 | 1.0 | 20 / 5 / 25 / 10 | 10 | 10 | 20, 0, 10 | **0** / 5 / 15 / 0 | 1.0 | 0 | 2.0 | 0 | **402** |
| 16 | 1.0 | 0 / 25 / 15 / 10 | 10 | 10 | 0, 10, 10 | 20 / 15 / 5 / 0 | 1.0 | **2.0** | 4.0 | 0 | **406** |
| 17 | 1.0 | 20 / 15 / 15 / 10 | 10 | 10 | 10, 10, 10 | **10 / 5 / 5 / 0** | 1.0 | 0 | 2.0 | 0 | **402** |
| 18 | 1.0 | 10 / 15 / 15 / 10 | 10 | 10 | 10, 10, 10 | 10 / 5 / 5 / 0 | 1.0 | 1.0 | 2.0 | 0 | **403** |

Period 18 opens on the steady state of §5.3, so periods 18–29 are twelve periods at 403.

### 8.3 The three things this table shows

**The dip lags the onset by one period.** Period 10 is the first disrupted period and its fill rate
is **1.0**. The units `CUST-1` serves in period 10 were dispatched by `DC-1` in period **9**, and
in-flight material is never stopped — an event reduces a link's *capacity*, so it stops new
dispatches and has no reach over a shipment already on the wire. The customer runs on the last
delivery for one period and then goes dark. This is the single most important thing the playback
view has to render correctly, and it is the thing a viewer is most likely to call a bug.

**The blockage backs up the chain, one rung a period.** With arc **c** cut, `DC-1` ships nothing and
ends period 10 holding 15 instead of 5. By period 11 `DC-1` is at its target of 25 and orders
nothing, so arc **b** carries 0. By period 12 `PLANT-1` is at 25 too, so arc **a** carries 0 as well
and the whole chain is stalled with 20/25/25/0 on hand. The animation should show the stall
propagating **upstream** while the outage sits at the downstream end.

**Recovery is a surge, then an over-correction.** Period 13's arc **c** reopens and `DC-1` ships
**20** — the customer's full accumulated shortfall (target 20 − 0 on hand − 0 in transit), which is
double a steady-state shipment and the largest single flow in the run. That surge lands in period
14, service resumes, and the network then works the bulge back out of the system: `SUP-1` is drained
to **0** in period 15 (the only period in either run where a node is empty), refills by producing
**20** in period 16 (2.0 of variable cost, the run's largest), and is back on the steady state by
period 17.

### 8.4 The cost

Periods 0–9 and 18–29 are identical to the baseline, so the whole difference is in periods 10–17:

```text
  disrupted   404 + 606 + 608 + 605 + 403 + 402 + 406 + 402  = 3836
  baseline    403 × 8                                        = 3224
                                                               ────
  DISRUPTION_COST_DELTA                                      =  612

  TOTAL_COST  = 12094 − 3224 + 3836 = 12706
```

Worth checking the delta a second way, because it is the metric most easily got wrong:

```text
  shortage   3 periods × 10 units × 20            = +600
  variable   baseline 8 × 1.0 = 8.0
             disrupted 1+1+1+0+0+0+2+0 = 5.0      =   −3     the stall produces less
  holding    baseline 8 × 2.0 = 16.0
             disrupted 3+5+7+5+3+2+4+2 = 31.0     =  +15     the stall holds more
                                                    ────
                                                     612     ✓
```

The two non-shortage terms nearly cancel, and that is the substantive finding: **a blocked link
makes a network cheaper to run and worse at its job.** Without the shortage term in `TOTAL_COST` the
delta would be −12 and a severed customer would score as a saving — the same argument
`docs/simulation-verification.md` §6.4 makes, reproduced here on a network where the two terms are
of comparable size.

### 8.5 The metric rows

| `metricCode` | Exact value | Decimal | `displayUnit` |
|---|---|---|---|
| `FILL_RATE` | 270/300 = 9/10 | 0.9 | null |
| `SERVICE_LEVEL` | 27/30 = 9/10 | 0.9 | null |
| `MIN_FILL_RATE` | 0 | 0.0 | null |
| `TTR` | **0** | 0.0 | **`DAY`** |
| `LOSS_AREA` | 3 | 3.0 | null |
| `TOTAL_COST` | 12706 | 12706.0 | null |
| `DISRUPTION_COST_DELTA` | 612 | 612.0 | null |
| `CVAR_COST` | 12706 | 12706.0 | null |
| `RESILIENCE_INDEX` | 17/20 | 0.85 | null |
| `AVG_INVENTORY` | 820/30 = 82/3 | 27.333333333333332 | null |
| `AVG_PIPELINE` | 780/30 = 26 | 26.0 | null |

```text
  FILL_RATE          Σ served / Σ demand = 270 / 300 = 0.9
                     (27 periods at 10, three at 0)

  SERVICE_LEVEL      periods served in full / periods with demand = 27 / 30 = 0.9
                     equal to FILL_RATE only because every period demands the same 10

  MIN_FILL_RATE      the worst period with demand = 0.0   (periods 11, 12, 13)

  LOSS_AREA          Σ max(0, baselineFill(t) − disruptedFill(t))  over the whole horizon
                     = 3 × (1.0 − 0.0) = 3.0     in fill-rate·periods
                     an exact integer, because the dip is total rather than partial —
                     none of the 0.19999999999999996 trouble of the 3-node sample

  RESILIENCE_INDEX   window [onset, H) = periods 10…29, twenty periods
                     disrupted mean = (17 × 1.0 + 3 × 0.0) / 20 = 17/20
                     baseline  mean = 1.0
                     = 0.85
```

The inventory pair, from §8.2's *end on-hand* column and its dispatched flows. Periods 0–9 and 18–29
are the baseline's (§5.1), and the eight periods in between are the whole difference:

```text
  endingInventory  40, 40, 40, then 20 through t = 9        3×40 + 7×20      =  260
                   30, 50, 70, 50, 40, 20, 40, 20  (t = 10…17)               =  320
                   20 in every period t = 18…29             12×20            =  240
  AVG_INVENTORY  =  820 / 30  =  82/3  =  27.333…                            Σ = 820

  inPipeline       20, 20, 20, then 30 through t = 9         3×20 + 7×30     =  270
                   20, 10,  0, 20, 20, 30, 20, 30  (t = 10…17)               =  150
                   30 in every period t = 18…29              12×30           =  360
  AVG_PIPELINE   =  780 / 30  =  26.0                                        Σ = 780
```

**The disruption raises average inventory and lowers average pipeline, and that pairing is the
finding.** A cut link stops material moving and leaves it standing: `AVG_INVENTORY` climbs from 22.0
to 27.3 while `AVG_PIPELINE` falls from 29.0 to 26.0. Their sum still rises — 51 to 53.3 — because
`SUP-1` keeps producing 10 a period through the stall while the customer is served nothing, which is
the same 20 units §8.3 calls the bulge and §8.4 prices as +15 of holding cost.

**Neither number is better than the other's, which is what `NEUTRAL` means.** A view that highlighted
27.3 as a win because it is more buffered, or as a loss because it is dearer to hold, would be making
a claim this suite does not support — that is what `FILL_RATE` (0.9 against 1.0) and `TOTAL_COST` are
for. `AVG_INVENTORY` is the third column of the comparison table, showing *where the material went*.

`820 / 30` is **not** exactly representable; compare it to a tolerance of 1 × 10⁻⁹ like `5/3` in
§7.8. `780 / 30` is 26 exactly and should match to the bit.

### 8.6 `TTR` is 0 here, and that is a known reading rather than a defect

The implemented definition (`TimeToRecoveryCalculator`) is onset-relative:

```text
  TTR = min { p ≥ onset : disruptedFill(p) ≥ baselineFill(p) } − onset
```

The onset is period 10 (`AvailabilityModel.onsetPeriod` — the earliest start among the replication's
active events). Period 10's disrupted fill rate is **1.0**, which is its baseline, so the walk
terminates on its first step:

```text
  t = 10 :  1.0  ≥  1.0    recovered      TTR = 10 − 10 = 0
```

**This is the implementation behaving exactly as specified, on a case the specification does not
distinguish.** `TTR` asks when performance regained its baseline; it does not ask whether
performance had yet left it. Every network in the repository until now had a lead time of zero on
the last leg, so onset and impact coincided and the question never arose. Here they do not, and the
metric reports 0 for a disruption that cost three full periods of service.

Three consequences, recorded rather than worked around:

1. **Do not "fix" this in a checklist by asserting 3.** The value the code produces on this network
   is 0, and a checklist that expects 3 is asserting a definition the engine does not implement.
2. **Lean on `LOSS_AREA` (3.0) and `RESILIENCE_INDEX` (0.85) instead.** Both integrate over the
   horizon rather than testing a single period, so both see the delayed impact in full, and between
   them they carry the depth and the duration that `TTR` misses here.
3. **If the definition is ever amended** — for example to "the first period from onset at which fill
   has recovered *after having fallen*", or to a first-impact-relative onset — then, per the working
   agreement, `docs/simulation-verification.md` and this file change **first** and the tests follow.
   Re-deriving either from a changed calculator would turn the specification into a record of what
   the code does.

### 8.7 The performance curve

The disrupted and baseline fill-rate curves, which is what the resilience triangle is the area
between (§8.5's `LOSS_AREA`):

| t | 0–9 | **10** | **11** | **12** | **13** | 14 | 15–29 |
|---|---|---|---|---|---|---|---|
| disrupted | 1.0 | **1.0** | **0.0** | **0.0** | **0.0** | 1.0 | 1.0 |
| baseline | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 |
| shortfall | 0 | **0** | **1.0** | **1.0** | **1.0** | 0 | 0 |

A rectangle of height 1 and width 3, offset one period to the right of the event's window — the
shape §8.3 explains and the one an overlay has to get right. Note the event window (10–12) and the
loss window (11–13) are **different intervals**, which is the whole visual argument of this sample.

---

## 9. If a number disagrees

This document is the specification, not a record of what the code did. A mismatch is a defect in one
of the two, and the arithmetic above is checkable by hand, so start by re-deriving the disputed
figure from §3–§8 rather than from the implementation. Beyond the thirteen failure modes
`docs/simulation-verification.md` §10 lists — all of which apply here — five are specific to this
network:

1. **Every fill rate is 0 and `TOTAL_COST` is 24000** (fixed 12000 + shortage 30 × 200, less
   holding). Nothing is being pulled down the chain: either `safetyStockPriority` was set to 0 on
   the request, or the replenishment-dominance inequality of §1.2 has been broken by a cost edit.
   Check `C < 1/9` first.
2. **Period 0's flows are 10, 10, 10 rather than 10, 10, 0.** `CUST-1`'s order-up-to shortfall is
   being computed as something other than `20 − 20 − 0 = 0`, most likely because `inTransit` is not
   being netted or the target is missing the `(1 + delay)` factor of §3.
3. **The steady state never settles, and on-hand oscillates between 15 and 5.** The target is one
   period of coverage rather than `1 + replenishDelay` — the failure
   `SimulationNetwork.computeReplenishTargets` names ("orders the shortfall, and waits out the lead
   time with nothing").
4. **The disrupted run dips in period 10 rather than 11.** In-flight material is being cancelled
   when the link's availability drops. An event reduces capacity, which stops new dispatches; a
   shipment already in the pipeline arrives regardless (§8.3).
5. **`ROBUSTNESS_TARGETED` is below 3/8.** The removal order is fragmenting the chain, which on four
   nodes means the criticality tie is not being broken by ascending node *name* — every node ties at
   1, name order peels from the customer end, and any end-peel scores the ceiling. **It reads 0.5**
   — the trapezoidal area is back, counting the intact network as a summand instead of the
   normaliser (§7.5, `docs/metric-verification.md` §7).
