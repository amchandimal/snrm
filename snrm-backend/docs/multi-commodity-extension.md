# Feature specification — multi-commodity simulation

*Status: **specified, not implemented**. Phase 1 ships a single-commodity engine; this document is
what a later implementer needs in order to make it multi-commodity without re-deriving the
decisions.*

*Written 2026-08-02, alongside the Phase 1 simulation engine.*

---

## 1. What Phase 1 does today, and what it costs

Every `(node, product)` pair has its own demand, opening inventory, safety stock and
holding cost, and `PRODUCT` its own `unit_value`. The data model is fully multi-product.

**The simulation engine is not.** `SimulationNetwork` collapses each node's product rows into four
aggregate numbers before the first period runs:

| Quantity | Aggregation |
|---|---|
| demand, initial inventory, safety stock | sum over products |
| holding cost per unit-period | stock-weighted mean (`initialInventory + safetyStock` as the weight) |
| unit value — the price of unmet demand | demand-weighted mean |

Everything downstream then moves one fungible commodity called "units".

### 1.1 Why

Three reasons, in descending order of how hard they are to argue with.

**A true multi-commodity minimum-cost flow with shared capacities is NP-hard.** The per-period
problem the engine solves — arc and node capacities shared across commodities — is *integer*
multi-commodity flow, which is NP-hard in general. The justification for the aggregation
is that it keeps each period polynomial-time, and that claim does not survive the extension in its
naive form. §4 below is how it is recovered.

**JGraphT has no multi-commodity solver.** The library set is fixed, and adding an LP solver
(the realistic route — §4.2) is a change to that table, not an implementation detail.

**Nothing in Phase 1's output could express the answer.** `RUN_TIMESERIES` has no product column, and
all nine simulated metrics — fill rate, service level, TTR, min fill rate, loss area, the
three cost metrics, the resilience index — are defined over network totals. A per-product allocation
would be computed and then thrown away.

### 1.2 What is actually lost

Not much, arithmetically; quite a lot, analytically.

- **Totals are right.** Where products genuinely share capacity, the aggregate throughput is the same
  number.
- **Attribution is wrong.** A shortage of 10 units is reported without saying which product went
  short. If a plant makes a €200 component and a €5 one, the model cannot say that the disruption
  fell on the expensive one — and `TOTAL_COST` uses a demand-weighted average price, so the *cost* of
  that shortage is averaged too.
- **Product-specific routing disappears.** A network where product A can only be made at plant 1 and
  product B only at plant 2 is modelled as one where either plant can make either. The aggregate
  network therefore looks **more resilient than it is**, which is the one direction a resilience
  study must not err in. This is the strongest argument for the extension.
- **Product-level levers are unavailable.** The flexibility lever family includes "allow product
  reallocation across plants", which is not expressible against a single commodity — the lever's
  whole content is which product goes where.

**Rule of thumb.** Single-commodity is a good approximation when products are substitutable in
production and transport (a family of similar SKUs through the same plants). It is a poor one when
products have disjoint or partly disjoint capable-node sets, which is exactly when the flexibility
lever matters.

---

## 2. Scope of the extension

**In scope**

1. Per-product demand, inventory, safety stock, holding cost and shortage price through the whole
   per-period loop.
2. A per-period allocation that respects node and link capacities shared across products.
3. Per-product bills of materials at `PLANT` nodes — see §6, which is the natural companion and is
   the reason to do this at all rather than run the engine once per product.
4. Per-product results: `RUN_TIMESERIES` and `METRIC_RESULT` rows scoped to a product.
5. A migration path that leaves single-product networks behaving **exactly** as they do today.

**Out of scope**

- Product substitution (serving demand for A with B).
- Backorders. Phase 1 loses unmet demand at the end of the period and this extension does not change
  that.
- Multi-period lookahead. The allocation stays myopic.

---

## 3. Data model changes

### 3.1 New: which nodes can make what

The one thing the current schema cannot express, and the thing that makes the extension worth doing.

```sql
-- V<n>__product_capability.sql
CREATE TABLE node_product_capability (
  node_id      BIGINT NOT NULL REFERENCES node(id) ON DELETE CASCADE,
  product_id   BIGINT NOT NULL REFERENCES product(id) ON DELETE CASCADE,
  -- Units of shared node capacity consumed per unit of this product produced or
  -- handled. 1.0 means the node's capacity is stated in units of this product.
  capacity_rate DOUBLE NOT NULL DEFAULT 1.0,
  var_cost      DOUBLE NULL,   -- overrides node.var_cost for this product
  PRIMARY KEY (node_id, product_id),
  CONSTRAINT ck_capability_rate CHECK (capacity_rate > 0));
```

**Absence means capable.** A node with *no* rows in this table can handle every product, which is
what makes every existing network behave unchanged. A node with at least one row can handle only the
products it names. This is the only rule in the extension that has to be got exactly right: the
opposite default would silently make every stored network infeasible.

`capacity_rate` is what turns a single `node.capacity` into a shared constraint across products:

```text
  Σ_p ( flow(node, p) × capacityRate(node, p) )  ≤  availableCapacity(node)
```

A plant rated at 400 units a day that takes twice as long over product B has
`capacity_rate = 1.0` for A and `2.0` for B.

The same table shape applies to links (`link_product_capability`) where a lane is
product-restricted — refrigerated transport, hazardous goods. Add it only when a dataset needs it;
the node table is the one that changes conclusions.

### 3.2 Changed: results carry a product

```sql
ALTER TABLE run_timeseries
  ADD COLUMN product_id BIGINT NULL REFERENCES product(id),
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (run_id, period, product_id);   -- see the note below

ALTER TABLE metric_result
  MODIFY COLUMN scope ENUM('NETWORK','NODE','LINK','PRODUCT') NOT NULL DEFAULT 'NETWORK';
```

**`product_id IS NULL` means "the whole network"**, and those rows are written *as well as* the
per-product ones. A client that knows nothing about products reads the null rows and behaves exactly
as it does today, which is what keeps the Angular results view working while it is
updated. MySQL treats nulls as distinct in a unique key, so the primary key above needs a generated
`product_key` column (`COALESCE(product_id, 0)`) or a surrogate id — decide when writing the
migration; the ER model's preference for the natural key is worth keeping if it can be.

`METRIC_RESULT.scope` gaining `PRODUCT` costs nothing: `scope_id` is already a nullable bigint and
`MetricScope` is already an enum the DTO passes through opaquely.

### 3.3 Unchanged

`NODE_PRODUCT` already carries everything per-product that the loop needs. `PRODUCT.unit_value` is
already the shortage price. No change.

---

## 4. The allocation problem

This is the whole of the technical difficulty.

### 4.1 What it becomes

Per period, with `P` products:

```text
  minimise   Σ_p Σ_a  cost(a) × flow(a, p)  +  Σ_p penalty × unmet(p)

  subject to
    (1) flow conservation, per product, at every vertex
    (2) Σ_p flow(a, p) ≤ availableCapacity(a)                  for every arc a
    (3) Σ_p flow(node, p) × capacityRate(node, p) ≤ cap(node)  for every node
    (4) flow(a, p) = 0 where p is not capable at a
```

Constraints (2) and (3) are the coupling: without them the problem separates into `P` independent
single-commodity flows and the whole difficulty vanishes. With them it is multi-commodity flow, which
is polynomial as a *linear* program and NP-hard as an *integer* one.

### 4.2 Recommended: solve the LP relaxation

**Drop integrality.** SNRM's quantities are already continuous — a capacity of 120 per week on a
one-day period is 17.142857… per period — and Phase 1 only quantises them because JGraphT's API
demands integers. Nothing in the model needs a whole number of units. So solve the LP, keep the
fractional answer, and delete `Quantiser` from this path entirely.

That makes the per-period problem polynomial again, and restores the guarantee.

**Library.** An LP solver must be added to the stack. Evaluate, in order:

| Candidate | Note |
|---|---|
| **ojAlgo** | Pure Java, Apache 2.0, active, no native dependency. The default recommendation — a research tool that must build on a clean workstation should not need a native library. |
| Google OR-Tools (GLOP) | Faster, but a native dependency and a much larger artefact. |
| Apache Commons Math `SimplexSolver` | **Not recommended**, and no longer the cheap option it once looked like: `commons-math3` left the classpath with the legacy reverse-auction package, so it would now be a new dependency outside the stack — for a solver that is dense-tableau, effectively unmaintained, and slow at a thousand nodes. |

Expect the per-period solve to be roughly `P` times slower than today's, plus LP-vs-network-simplex
overhead. Budget an order of magnitude on a large network, and see §7.

### 4.3 Alternative: sequential per-product allocation

Solve `P` single-commodity min-cost flows in priority order, decrementing shared capacities after
each. Keeps JGraphT, keeps the runtime, and is what many commercial planning systems actually do.

**It is not recommended, and the reason is specific.** The justification for using an
optimal allocation at all is that measured resilience is a property of the configuration, not of a
routing heuristic. A sequential allocation *is* a routing heuristic, and its answer depends on the
product order — so two variants could be ranked differently by nothing but which product happened to
be listed first. That is precisely the defect the optimal allocation avoids.

If it is implemented anyway (as a fast mode for the Phase 2 search, where thousands of candidates are
scored and the ranking is re-checked at full fidelity), it must be a **named, recorded** allocation
mode in `params_json`, so results computed under it are never silently compared with results computed
optimally.

### 4.4 Keep the graph construction

The four-vertex node split, the pipeline arcs, the order-up-to replenishment sink and the dominating
penalty arc all carry over unchanged — see `FlowAllocator`'s Javadoc for what each one is for. What
changes is that each arc gains a per-product flow variable and the capacity constraints become sums.
The lexicographic penalty argument (serve first, minimise cost second) applies per product and is
even more important here, because a price-based penalty would make the *allocation between products*
depend on their prices — turning the fill rate of a cheap product into a modelling artefact.

---

## 5. Engine changes

| Class | Change |
|---|---|
| `SimulationNetwork` | Keep the per-product arrays instead of aggregating. `demand[i][p]`, `initialInventory[i][p]`, and so on. Add `capable[i][p]` and `capacityRate[i][p]` from §3.1. Keep the aggregate arrays too — the null-product result rows need them. |
| `FlowAllocator` | Replace with an LP formulation of §4.1. This is the bulk of the work. |
| `FlowSolution` | Arrays become `[node][product]` and `[link][product]`. |
| `SimulationEngine` | The inventory identity holds per product, unchanged in form: `onHand'(i,p) = onHand(i,p) − stockDrawn(i,p) + heldNow(i,p)`. The pipeline gains a product dimension: `pipeline[slot][node][product]`. |
| `PeriodTrace` | Add per-product `servedDemand`, `totalDemand` and the five cost components. Keep the aggregate fields, computed as sums, so existing calculators need no change on day one. |
| `AvailabilityModel` | **Unchanged.** A disruption strikes an element, not a product. |
| `ReplicationRng` | Demand noise is drawn per `(customer, product)`. Extend the `DEMAND` stream's address with the product index — **do not** consume extra draws from the existing address, or every stored seed changes meaning. |
| `Quantiser` | Delete from the LP path (§4.2). |

**Memory.** The pipeline becomes `depth × nodes × products` doubles per replication. At 1,000 nodes,
20 products and a depth of 5 that is 800 KB per replication, and 200 replications would want 160 MB.
Use a sparse representation (most `(node, product)` pairs are empty) or cap the fan-out. This is a
real constraint and should be measured before the work is scheduled.

---

## 6. Bills of materials

Multi-commodity without a BOM models a plant as a place where products pass through independently,
which is rarely why a plant is in the network. The natural companion:

```sql
CREATE TABLE product_component (
  product_id   BIGINT NOT NULL REFERENCES product(id) ON DELETE CASCADE,
  component_id BIGINT NOT NULL REFERENCES product(id) ON DELETE CASCADE,
  quantity     DOUBLE NOT NULL,
  PRIMARY KEY (product_id, component_id),
  CONSTRAINT ck_component_qty CHECK (quantity > 0));
```

In the LP this is a **transformation constraint** at each capable node: producing one unit of `p`
consumes `quantity` units of each component `c` from that node's on-hand stock. It is a linear
constraint and fits the formulation of §4.1 directly.

It also changes what the tool can say, and this is the payoff that justifies the whole extension: a
disruption to a component supplier propagates to a finished product two echelons downstream, which is
the "propagation" question the RQ1/RQ2 synthesis raises and Phase 1 cannot answer at all.

**Cycle detection is required** — a BOM graph with a cycle makes the LP unbounded or infeasible
depending on formulation. Validate on write, in `NetworkChecks` beside the existing network-level
checks.

---

## 7. Performance

| Factor | Effect |
|---|---|
| `P` products | Roughly linear in the LP's variable count; super-linear in solve time in practice |
| LP vs. network simplex | JGraphT's capacity-scaling min-cost flow is specialised and fast; a general LP is not |
| BOM constraints | Adds constraints, not variables — the cheaper half |

**Mitigations, in the order to try them.**

1. **Implement the incremental re-solve first.** It is a known mitigation and is
   still not implemented in Phase 1: most periods have identical availability to the one before, and
   the undisrupted baseline set — half of every run — has *no* availability change at all after the
   first period. Warm-starting from the previous period's basis is the single largest win available,
   and it is worth having before multi-commodity makes each solve dearer.
2. Skip periods with no demand and no replenishment need. Phase 1 already does this.
3. Reduce the replication count during Phase 2 search (it already uses 20 during search,
   full replication for the final front).
4. Decompose by disconnected component, if any dataset ever has one.

---

## 8. Metric changes

Each calculator gains a per-product loop and emits `MetricScope.PRODUCT` rows **in addition to** the
network-scoped one it emits today. The network-scoped values are defined as follows, and the
definitions matter because two of them are not the obvious choice:

| Metric | Network-scoped value under multi-commodity |
|---|---|
| `FILL_RATE` | `Σ_p served(p) / Σ_p demand(p)` — unchanged in form; already a ratio of sums |
| `SERVICE_LEVEL` | Fraction of periods in which **every** product was served in full. Strictly harsher than today's, and correct: a period in which one product failed was not a period of full service |
| `MIN_FILL_RATE` | Minimum over periods of the **aggregate** fill rate. The per-product minima are separate `PRODUCT`-scoped rows; a network minimum taken over `(period, product)` pairs would report the worst product's worst period and would fall as products are added |
| `TTR`, `LOSS_AREA`, `RESILIENCE_INDEX` | Computed on the aggregate fill-rate curve as today, **plus** one row per product on that product's own curve. The per-product `TTR` is the interesting new number: a disruption can be absorbed in aggregate while one product takes ten periods to come back |
| `TOTAL_COST`, `CVAR_COST`, `DISRUPTION_COST_DELTA` | Unchanged — costs already sum across products. The gain is that the shortage term uses each product's **own** `unit_value` rather than a demand-weighted mean |

`MetricValue` needs no change: `scopeId` already carries a nullable id and `MetricScope` is an enum
the DTO passes through opaquely. That is the extensibility doing its job.

---

## 9. Migration and compatibility

**A single-product network must produce byte-identical results.** With one product the aggregation of
§1 is the identity, every capability table is empty (so everything is capable), and the LP relaxation
of a single-commodity min-cost flow has an integral optimum by total unimodularity — so the answer is
the same one JGraphT gives today, modulo the fixed-point grid Phase 1 rounds onto.

That gives a concrete acceptance test, and it should be written before any of the above:

> `docs/simulation-verification.md` §8's nine values must still hold, to the same 1 × 10⁻⁹ tolerance,
> after the extension. `SimulationVerificationTest` is that test and needs no change.

A **multi-product** verification document should be written beside it — `docs/multi-commodity-verification.md`
— on a network where two products contend for one plant's capacity, with the contention resolved by
hand. Do this *first*, as with the metric and simulation suites: the document is the specification,
and deriving the expected numbers from a finished implementation defeats the point of having it.

---

## 10. Recommended order of work

1. `docs/multi-commodity-verification.md` — the two-product contention example, worked by hand.
2. Choose and add the LP library.
3. `node_product_capability` migration and its import/export columns.
4. Per-product `SimulationNetwork` and `PeriodTrace`, aggregate fields retained.
5. The LP allocator, checked against §9's single-product identity before anything else.
6. Per-product `RUN_TIMESERIES` and `METRIC_RESULT` rows.
7. Per-product metric rows.
8. Bills of materials (§6) — separable, and the largest single gain in what the tool can say.

Steps 1–5 are the coherent minimum: they make the engine multi-commodity without changing any output,
which is the safest possible place to stop if the work has to be interrupted.
