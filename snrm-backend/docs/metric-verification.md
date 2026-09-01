# Topological metrics — a worked verification

*Every number below is derived by hand from the metric definitions; nothing here was produced by
running the application. §9 states the exact values `GET /api/v1/networks/{id}/metrics/topological`
must return for this network, so the implementation can be checked against arithmetic rather than
against itself.*

The network is `samples/metric-verification-6-node/`, in the canonical import schema.
`api-tests.http` requests 57–58 import it and fetch the suite.

---

## 1. The network

Six nodes, seven directed links, one product, on a clock of **1 DAY / 52 periods / NEAREST**.

```text
     SUPPLIER          PLANT                DC                     CUSTOMER

                                      ┌──────────────┐
                                      │    DC-1      │───────────▶  CUST-1
                                  ┌──▶│   cap 350    │──┐            demand 40/day
                                  │   └──────┬───────┘  │
    ┌──────────┐    ┌───────────┐ │          │ 100      │
    │  SUP-1   │───▶│  PLANT-1  │─┤          ▼          └────────▶  CUST-2
    │ cap 500  │    │  cap 400  │ │   ┌──────────────┐  ┌────────▶   demand 25/day
    └──────────┘    └───────────┘ └──▶│    DC-2      │──┘
                                      │   cap 350    │
                                      └──────────────┘
```

### 1.1 Nodes

| # | Name | Type | Capacity | Demand | Processing time |
|---|---|---|---|---|---|
| 1 | `SUP-1` | SUPPLIER | 500 / DAY | — | 0 DAY |
| 2 | `PLANT-1` | PLANT | 400 / DAY | — | 1 DAY |
| 3 | `DC-1` | DC | 350 / DAY | — | 0 DAY |
| 4 | `DC-2` | DC | 350 / DAY | — | 0 DAY |
| 5 | `CUST-1` | CUSTOMER | *(blank → unconstrained)* | 40 / DAY | 0 DAY |
| 6 | `CUST-2` | CUSTOMER | *(blank → unconstrained)* | 25 / DAY | 0 DAY |

The `#` column is the order the rows appear in `nodes.csv`, which is the order the importer assigns
ids in. No metric reads that order — the criticality tie-break of §7.1 ranks by node *name*
precisely so an id assigned by insertion history cannot change a result — but §9 still does: the
`NODE_CRITICALITY` rows come back in exactly this order.

### 1.2 Links

| # | Arc | Lead time | Capacity |
|---|---|---|---|
| a | `SUP-1 → PLANT-1` | 2 DAY | 500 / DAY |
| b | `PLANT-1 → DC-1` | 3 DAY | 300 / DAY |
| c | `PLANT-1 → DC-2` | 4 DAY | 300 / DAY |
| d | `DC-1 → DC-2` | 1 DAY | 100 / DAY |
| e | `DC-1 → CUST-1` | 1 DAY | 200 / DAY |
| f | `DC-2 → CUST-2` | 1 DAY | 200 / DAY |
| g | `DC-1 → CUST-2` | 2 DAY | 150 / DAY |

Arc **d** is the lateral DC-to-DC transshipment link that is permitted with a warning. It is here on
purpose: without it the network has no triangles at all and `CLUSTERING` is 0, which a broken
implementation would also report.

### 1.3 What the design of this network is for

Three things are deliberate, and each removes a source of arithmetic that would be tedious rather
than instructive:

- **Every duration is a whole number of days on a one-day period**, so nothing rounds and the time
  machinery is entirely out of the way. `docs/time-units-worked-example.md` is where
  that is exercised; here it must not interfere.
- **Every capacity comfortably exceeds the demand behind it**, so maximum serviceable demand is
  limited by *demand* rather than by a bottleneck. That makes §6 a question of connectivity plus
  one arithmetic check rather than a min-cut puzzle, without making the capacities decorative — §6.3
  shows where they would start to bind.
- **Total demand is 65 per period** — 40 + 25 — and every number in §6 is a fraction of it.

---

## 2. Conventions the whole suite shares

Stated once here, and in `com.snrm.metrics.topological/package-info.java`:

| Convention | Reading |
|---|---|
| **Connectivity** | *Weak*: components of the graph with arc directions dropped. A layered supply network is never strongly connected, so strong components would report 1 everywhere and measure nothing. |
| **Supply origins** | Supply-side nodes (SUPPLIER or PLANT) with **no inbound arc** — where material enters the model. Here: `{SUP-1}` alone, because `PLANT-1` is fed by arc **a**. The set is computed once on the intact graph and **held fixed**; removing `SUP-1` does not promote `PLANT-1` into an origin. |
| **Demand** | Sits on CUSTOMER nodes, summed over products. Here: `CUST-1` 40, `CUST-2` 25, everything else 0. |
| **Units** | Every figure below is per period. The snapshot converted them once; with a 1-day period and per-DAY rates every conversion is ×1. |

The **undirected** form of the network, used by §5, §7 and nowhere else:

```text
   S–P   P–D1   P–D2   D1–D2   D1–C1   D1–C2   D2–C2          (7 edges)
```

with `S = SUP-1`, `P = PLANT-1`, `D1 = DC-1`, `D2 = DC-2`, `C1 = CUST-1`, `C2 = CUST-2`.
Undirected degrees: **S 1, P 3, D1 4, D2 3, C1 1, C2 2** — summing to 14 = 2 × 7, which is the check
that the edge list above was transcribed correctly.

---

## 3. `DENSITY`

> Definition: standard graph statistics on the directed network.

The fraction of the ordered pairs that carry an arc:

```text
DENSITY = m / ( n · (n − 1) )
```

```text
n = 6,  m = 7

n · (n − 1) = 6 × 5 = 30

DENSITY = 7 / 30 = 0.23333333333333…
```

The denominator is 30 and not 15 because the network is directed: `PLANT-1 → DC-1` and
`DC-1 → PLANT-1` are different arrangements, and only one of them exists. An undirected denominator
would report 0.4667 and make the network look twice as interconnected as it is.

**`DENSITY` = 7/30 = 0.2333333333**

---

## 4. `AVG_PATH`

> Definition: standard graph statistics on the directed network.

The mean number of arcs on a shortest **directed** path, averaged over the ordered pairs that have
one. Unreachable pairs are excluded — most ordered pairs in a layered network have no path at all,
and counting them as infinite makes the metric infinite for every realistic network (see the
calculator's Javadoc for why the alternatives are worse).

Breadth-first search from each node in turn. Distance is **hops**, not lead time and not capacity.

| From | Reachable, with distance | Pairs | Σ d |
|---|---|---|---|
| `SUP-1` | `PLANT-1` 1 · `DC-1` 2 · `DC-2` 2 · `CUST-1` 3 · `CUST-2` 3 | 5 | 11 |
| `PLANT-1` | `DC-1` 1 · `DC-2` 1 · `CUST-1` 2 · `CUST-2` 2 | 4 | 6 |
| `DC-1` | `DC-2` 1 · `CUST-1` 1 · `CUST-2` 1 | 3 | 3 |
| `DC-2` | `CUST-2` 1 | 1 | 1 |
| `CUST-1` | *(nothing — no outbound arc)* | 0 | 0 |
| `CUST-2` | *(nothing)* | 0 | 0 |
| | | **13** | **21** |

Two entries are worth pausing on:

- `SUP-1 → DC-2` is **2**, not 3. There are two routes — `S→P→D2` (2 hops, arc **c**) and
  `S→P→D1→D2` (3 hops, arcs **b**, **d**) — and a shortest path takes the shorter.
- `PLANT-1 → CUST-2` is **2** for the same reason: `P→D1→C2` via **g**, rather than 3 through `D2`.

```text
AVG_PATH = 21 / 13 = 1.615384615384…
```

**`AVG_PATH` = 21/13 = 1.6153846154**

Read it as: material that moves at all crosses about 1.6 arcs, over the 13 of the 30 ordered pairs
that are connected. The 13 is as much of the finding as the 1.6 — a network where fewer pairs were
connected could report a *lower* average while being worse.

---

## 5. `CLUSTERING`

> Definition: standard graph statistics on the directed network.

Average local clustering (Watts–Strogatz): for each node, the fraction of the pairs of its
neighbours that are themselves connected.

```text
C(v)       = ( edges among the neighbours of v ) / ( k(v) · (k(v) − 1) / 2 )
CLUSTERING = ( Σ C(v) ) / n
```

Neighbours are taken **undirected** (§2). A node with fewer than two neighbours has no pair of them
and contributes 0.

| Node | Neighbours | k | Pairs = k(k−1)/2 | Which pairs are joined | Joined | C(v) |
|---|---|---|---|---|---|---|
| `SUP-1` | P | 1 | — | — | — | **0** |
| `PLANT-1` | S, D1, D2 | 3 | 3 | (S,D1) ✗ · (S,D2) ✗ · (D1,D2) ✓ | 1 | **1/3** |
| `DC-1` | P, D2, C1, C2 | 4 | 6 | (P,D2) ✓ · (P,C1) ✗ · (P,C2) ✗ · (D2,C1) ✗ · (D2,C2) ✓ · (C1,C2) ✗ | 2 | **2/6 = 1/3** |
| `DC-2` | P, D1, C2 | 3 | 3 | (P,D1) ✓ · (P,C2) ✗ · (D1,C2) ✓ | 2 | **2/3** |
| `CUST-1` | D1 | 1 | — | — | — | **0** |
| `CUST-2` | D1, D2 | 2 | 1 | (D1,D2) ✓ | 1 | **1** |

```text
Σ C(v) = 0 + 1/3 + 1/3 + 2/3 + 0 + 1
       = (0 + 2 + 2 + 4 + 0 + 6) / 6
       = 14/6
       = 7/3

CLUSTERING = (7/3) / 6 = 7/18 = 0.38888888888…
```

**`CLUSTERING` = 7/18 = 0.3888888889**

`CUST-2` scores 1: both of its suppliers can also supply each other, so either can cover for the
other. That is exactly the redundancy this metric is for. The two leaves — `SUP-1` and `CUST-1` —
contribute 0 and pull the average down; that is the standard convention and the reason the value is
read comparatively between configurations rather than against an absolute scale.

---

## 6. `NODE_CRITICALITY`

> Definition: relative drop in max serviceable demand when the node is removed (computed for every
> node).

```text
criticality(v) = ( D(intact) − D(without v) ) / D(intact)
```

where **D** is the maximum demand the network can serve in one period, under every capacity at once.
That is a maximum flow.

### 6.1 The flow network

Each node is **split** into an in-half and an out-half joined by an arc of the node's own capacity —
without the split there is nowhere to put a node capacity, which constrains everything passing
through the node rather than any one link. A super-source feeds the supply origins; the customers
feed a super-sink through arcs of their demand.

An unconstrained capacity (`CUST-1` and `CUST-2` have blank capacity columns) becomes **65**, the
network's total demand. Flow into the sink can never exceed the sum of the sink arcs, so 65 is a
bound that provably cannot bind — and it is finite, which keeps every subtraction below exact.

| Arc | Capacity | From |
|---|---|---|
| `SOURCE → in(SUP-1)` | 65 | unconstrained; `SUP-1` is the only supply origin (§2) |
| `in(SUP-1) → out(SUP-1)` | 500 | node capacity |
| `in(PLANT-1) → out(PLANT-1)` | 400 | node capacity |
| `in(DC-1) → out(DC-1)` | 350 | node capacity |
| `in(DC-2) → out(DC-2)` | 350 | node capacity |
| `in(CUST-1) → out(CUST-1)` | 65 | unconstrained |
| `in(CUST-2) → out(CUST-2)` | 65 | unconstrained |
| `out(SUP-1) → in(PLANT-1)` | 500 | arc **a** |
| `out(PLANT-1) → in(DC-1)` | 300 | arc **b** |
| `out(PLANT-1) → in(DC-2)` | 300 | arc **c** |
| `out(DC-1) → in(DC-2)` | 100 | arc **d** |
| `out(DC-1) → in(CUST-1)` | 200 | arc **e** |
| `out(DC-2) → in(CUST-2)` | 200 | arc **f** |
| `out(DC-1) → in(CUST-2)` | 150 | arc **g** |
| `out(CUST-1) → SINK` | 40 | demand |
| `out(CUST-2) → SINK` | 25 | demand |

### 6.2 The intact network: D = 65

**Upper bound.** The two sink arcs form a cut of capacity 40 + 25 = **65**. No flow can exceed it.

**A flow that reaches it.** Send 40 along `SOURCE → SUP-1 → PLANT-1 → DC-1 → CUST-1 → SINK` and 25
along `SOURCE → SUP-1 → PLANT-1 → DC-2 → CUST-2 → SINK`. Every arc, checked:

| Arc | Carries | Capacity | |
|---|---|---|---|
| `SOURCE → in(SUP-1)` | 65 | 65 | ✓ saturated |
| `in(SUP-1) → out(SUP-1)` | 65 | 500 | ✓ |
| `out(SUP-1) → in(PLANT-1)` | 65 | 500 | ✓ |
| `in(PLANT-1) → out(PLANT-1)` | 65 | 400 | ✓ |
| `out(PLANT-1) → in(DC-1)` | 40 | 300 | ✓ |
| `in(DC-1) → out(DC-1)` | 40 | 350 | ✓ |
| `out(DC-1) → in(CUST-1)` | 40 | 200 | ✓ |
| `out(CUST-1) → SINK` | 40 | 40 | ✓ saturated |
| `out(PLANT-1) → in(DC-2)` | 25 | 300 | ✓ |
| `in(DC-2) → out(DC-2)` | 25 | 350 | ✓ |
| `out(DC-2) → in(CUST-2)` | 25 | 200 | ✓ |
| `out(CUST-2) → SINK` | 25 | 25 | ✓ saturated |

Flow 65 = cut 65, so **D(intact) = 65**. The network is demand-limited, not capacity-limited: the
tightest capacity on the route through `PLANT-1` is its own 400, six times the demand behind it.

### 6.3 One removal at a time

| Removed | What survives | D | Working |
|---|---|---|---|
| `SUP-1` | Nothing can enter | **0** | The origin set is `{SUP-1}` and is fixed (§2). With it gone the source has no outgoing arc, so no flow leaves it. `PLANT-1` is *not* promoted to an origin — a plant with no supplier cannot produce, and the alternative reading would report that losing a sole supplier costs nothing. |
| `PLANT-1` | Nothing can leave `SUP-1` | **0** | Arc **a** is `SUP-1`'s only outbound arc, and it ends at `in(PLANT-1)`. Removing the node removes the arc. |
| `DC-1` | Only `SUP-1 → PLANT-1 → DC-2 → CUST-2` | **25** | `CUST-1`'s only inbound arc is **e**, which goes with `DC-1`, so its 40 is unreachable. `CUST-2` is fed through **c** and **f**: the path's tightest capacity is min(65 source, 500, 400, 300 **c**, 350, 200 **f**) = 200, and demand caps it at **25**. The `out(CUST-2) → SINK` arc alone is then a cut of 25. |
| `DC-2` | Everything that matters | **65** | `CUST-2` re-routes onto arc **g** (`DC-1 → CUST-2`, capacity 150 ≥ 25). `DC-1` now carries both customers: 40 + 25 = 65, against its own capacity 350 and against **b**'s 300. Both hold, so nothing is lost. This is the redundancy arc **g** exists to provide. |
| `CUST-1` | `CUST-2` only | **25** | Removing a customer removes its demand. The only sink arc left has capacity 25 and is reachable, so D = 25. |
| `CUST-2` | `CUST-1` only | **40** | Likewise: the remaining sink arc has capacity 40 and 40 is deliverable through **b** and **e**. |

### 6.4 The criticalities

```text
SUP-1    (65 − 0)  / 65 = 65/65 = 1
PLANT-1  (65 − 0)  / 65 = 65/65 = 1
DC-1     (65 − 25) / 65 = 40/65 = 8/13 = 0.615384615…
DC-2     (65 − 65) / 65 =  0/65 = 0
CUST-1   (65 − 25) / 65 = 40/65 = 8/13 = 0.615384615…
CUST-2   (65 − 40) / 65 = 25/65 = 5/13 = 0.384615384…
```

| Node | Criticality | |
|---|---|---|
| `SUP-1` | **1** | Sole supplier. |
| `PLANT-1` | **1** | Sole plant, and the only route out of the supplier. |
| `DC-1` | **8/13 ≈ 0.6154** | Sole route to `CUST-1`; `CUST-2` survives it. |
| `DC-2` | **0** | Fully redundant — arc **g** covers everything it carries. |
| `CUST-1` | **8/13 ≈ 0.6154** | See below. |
| `CUST-2` | **5/13 ≈ 0.3846** | See below. |

**Two readings that surprise people, and are the definition working correctly:**

1. **A customer's criticality is its own share of demand.** `CUST-1` scores 40/65 and `CUST-2`
   25/65, and those are exactly their shares. Removing a customer removes the demand it existed to
   represent, so the "drop in serviceable demand" is that demand. It is a real and useful ranking —
   it is exposure by customer — but it is not the same quantity as `DC-1`'s identical 8/13, which
   says something about routing. A table that puts the two side by side should say so, which is why
   the panel carries the definition next to the numbers.
2. **`DC-1` and `CUST-1` tie at 8/13 for unrelated reasons.** `DC-1` because it is the only way to
   reach `CUST-1`; `CUST-1` because it *is* 40 of the 65. That two different mechanisms land on the
   same number is a coincidence of this network, and a useful one for testing: an implementation
   that confused the node-removal path with the demand-removal path would still produce 8/13 for
   both, so check `DC-2` (0) and `CUST-2` (5/13) too.

---

## 7. `ROBUSTNESS_RANDOM` and `ROBUSTNESS_TARGETED`

> Definition: mean normalised largest-connected-component size over the removal sequence, as nodes
> are removed randomly / by descending criticality (Schneider et al. 2011; Lou et al. 2020).

Remove nodes one at a time. After *k* removals let **S(k)** be the size of the largest weakly
connected component of what remains. Normalise by the intact size **S(0)** and average over the
*n* removal steps:

```text
R = (1/n) · Σ S(k) / S(0)                       k = 1 … n
```

This is the robustness measure of Schneider et al. (2011) as adopted for supply networks by Lou
et al. (2020) — the SLR source the metric catalog cites for the Rr/Rt pair. **The k = 0 term is
not a summand**: S(0) enters only as the normaliser. (The trapezoidal area under the whole curve
differs from R by exactly `(S(0) − S(n)) / (2n·S(0))` = `1/(2n)` on a connected network — a fixed
offset, but reporting the literature's own discretisation is what keeps a figure quoted from this
tool comparable with the papers it cites.) One deliberate reading is recorded: the component is
the plain weakly connected LCC, not the all-role LACC of Lou's SLACC refinement.

On a network whose intact form is a single weak component — S(0) = n, which is true here and of
every sample in this repository — R lies in [0, (n−1)/2n], just under ½ for a network that stays
whole until the last removal; a network that fragments immediately contributes almost nothing. (In
general the ceiling is (2n − S(0) − 1)/2n: a fragmented start *raises* it, because removals outside
the main component leave S(k) pinned at S(0) while k advances.) Higher is more robust.

With n = 6 the working is easier in units of *nodes* rather than fractions. S(0) = 6 here (the
intact network is one component, §2), so the tables below list **S(k)** and the division by 6
twice happens at the end:

```text
R = ( Σ S(k) ) / 36                             k = 1 … 6
```

### 7.1 `ROBUSTNESS_TARGETED` — descending criticality

The order is the ranking of §6.4, computed **once on the intact network** and then followed to the
end (removal is "by descending criticality" — an ordering of the network as it stands). Ties
break by **node name ascending**, which is arbitrary but must be *something*: two runs over the
same network cannot be allowed to disagree because a tie fell differently. Name, not
node id: ids are assigned by the receiving database, so the same network restored from a project
archive or re-imported from its own export would rank ties by its insertion history and
could report a different Rt for an identical topology. Names are unique per network (`uq_node`) and
survive every export format, so a name-ordered result is the one a verification workbook — or
another instance of the tool — can reproduce.

| Rank | Node | Criticality | Tie broken by |
|---|---|---|---|
| 1 | `PLANT-1` | 1 | name: PLANT-1 < SUP-1 |
| 2 | `SUP-1` | 1 | |
| 3 | `CUST-1` | 8/13 | name: CUST-1 < DC-1 |
| 4 | `DC-1` | 8/13 | |
| 5 | `CUST-2` | 5/13 | |
| 6 | `DC-2` | 0 | |

Now the curve, on the undirected edge list of §2:

| k | Just removed | Remaining | Edges among them | Components | S(k) |
|---|---|---|---|---|---|
| 0 | — | S, P, D1, D2, C1, C2 | all 7 | one | **6** |
| 1 | `PLANT-1` | S, D1, D2, C1, C2 | D1–D2, D1–C1, D1–C2, D2–C2 | {D1,D2,C1,C2}, {S} | **4** |
| 2 | `SUP-1` | D1, D2, C1, C2 | D1–D2, D1–C1, D1–C2, D2–C2 | one | **4** |
| 3 | `CUST-1` | D1, D2, C2 | D1–D2, D1–C2, D2–C2 | one | **3** |
| 4 | `DC-1` | D2, C2 | D2–C2 | one | **2** |
| 5 | `CUST-2` | D2 | none | {D2} | **1** |
| 6 | `DC-2` | — | — | — | **0** |

Summing the removal steps:

```text
Σ S(k) = 4 + 4 + 3 + 2 + 1 + 0 = 14             k = 1 … 6

ROBUSTNESS_TARGETED = 14 / 36 = 7 / 18 = 0.3888888888…
```

**`ROBUSTNESS_TARGETED` = 7/18 = 0.3888888889**

**One coincidence to know about when testing.** This network cannot detect the tie-break rule: the
retired id-ascending order (`SUP-1`, `PLANT-1`, `DC-1`, `CUST-1`, `CUST-2`, `DC-2` — the
`nodes.csv` insertion order) walks a different curve, S = 5, 4, 2, 2, 1, 0, whose sum is *also* 14,
so both rules report 7/18 here. §9's value therefore does not pin the tie-break; the hub fixture in
`RobustnessCalculatorsTest` does (3/16 by name, 5/16 by id).

### 7.2 `ROBUSTNESS_RANDOM` — the exact expectation

"Randomly" cannot mean "differently on each request": reproducibility is a
research-validity requirement, and a variant comparison whose two halves used different removal
orders would be partly a comparison of seeds. What is computed is the **expected** value of R over
uniformly random removal orders.

Two facts make that exactly computable here:

1. **R is linear in the S(k)**, so the expected value of R is R evaluated on the expected curve:
   `E[R]` = the same sum over `E[S(k)]`.
2. **S(k) depends only on *which* k nodes are gone, not on the order they went in.** So `E[S(k)]` is
   the plain average of S over all C(6,k) subsets of size k — no ordering to enumerate, 2⁶ = 64
   cases in total.

For n ≤ 16 the implementation enumerates exactly this and involves no randomness at all; above that
it samples seeded orders (`ComponentCurve`). With n = 6 the answer below is exact.

**k = 0** — one case, the whole network: S = 6. **E[S(0)] = 6.**

**k = 1** — six cases:

| Removed | Remaining | Components | S |
|---|---|---|---|
| S | P, D1, D2, C1, C2 | one | 5 |
| P | S, D1, D2, C1, C2 | {D1,D2,C1,C2}, {S} | 4 |
| D1 | S, P, D2, C1, C2 | {S,P,D2,C2}, {C1} | 4 |
| D2 | S, P, D1, C1, C2 | one | 5 |
| C1 | S, P, D1, D2, C2 | one | 5 |
| C2 | S, P, D1, D2, C1 | one | 5 |

```text
Σ S = 5 + 4 + 4 + 5 + 5 + 5 = 28          E[S(1)] = 28/6
```

**k = 2** — fifteen cases:

| Removed | Remaining | Components | S |
|---|---|---|---|
| S, P | D1, D2, C1, C2 | one | 4 |
| S, D1 | P, D2, C1, C2 | {P,D2,C2}, {C1} | 3 |
| S, D2 | P, D1, C1, C2 | one | 4 |
| S, C1 | P, D1, D2, C2 | one | 4 |
| S, C2 | P, D1, D2, C1 | one | 4 |
| P, D1 | S, D2, C1, C2 | {D2,C2}, {S}, {C1} | 2 |
| P, D2 | S, D1, C1, C2 | {D1,C1,C2}, {S} | 3 |
| P, C1 | S, D1, D2, C2 | {D1,D2,C2}, {S} | 3 |
| P, C2 | S, D1, D2, C1 | {D1,D2,C1}, {S} | 3 |
| D1, D2 | S, P, C1, C2 | {S,P}, {C1}, {C2} | 2 |
| D1, C1 | S, P, D2, C2 | one | 4 |
| D1, C2 | S, P, D2, C1 | {S,P,D2}, {C1} | 3 |
| D2, C1 | S, P, D1, C2 | one | 4 |
| D2, C2 | S, P, D1, C1 | one | 4 |
| C1, C2 | S, P, D1, D2 | one | 4 |

```text
Σ S = (4+3+4+4+4) + (2+3+3+3) + (2+4+3) + (4+4) + 4
    =    19        +    11     +    9    +   8   + 4
    = 51                                        E[S(2)] = 51/15
```

**k = 3** — twenty cases. With three nodes left, S is 3 if the induced graph is connected, 2 if it
has an edge but is not, and 1 if it has no edge at all. Listed by what *remains*:

| Remaining | Edges among them | S |
|---|---|---|
| S, P, D1 | S–P, P–D1 | 3 |
| S, P, D2 | S–P, P–D2 | 3 |
| S, P, C1 | S–P | 2 |
| S, P, C2 | S–P | 2 |
| S, D1, D2 | D1–D2 | 2 |
| S, D1, C1 | D1–C1 | 2 |
| S, D1, C2 | D1–C2 | 2 |
| S, D2, C1 | none | 1 |
| S, D2, C2 | D2–C2 | 2 |
| S, C1, C2 | none | 1 |
| P, D1, D2 | P–D1, P–D2, D1–D2 | 3 |
| P, D1, C1 | P–D1, D1–C1 | 3 |
| P, D1, C2 | P–D1, D1–C2 | 3 |
| P, D2, C1 | P–D2 | 2 |
| P, D2, C2 | P–D2, D2–C2 | 3 |
| P, C1, C2 | none | 1 |
| D1, D2, C1 | D1–D2, D1–C1 | 3 |
| D1, D2, C2 | D1–D2, D1–C2, D2–C2 | 3 |
| D1, C1, C2 | D1–C1, D1–C2 | 3 |
| D2, C1, C2 | D2–C2 | 2 |

```text
Σ S = (3+3+2+2+2+2+2+1+2+1) + (3+3+3+2+3+1+3+3+3+2)
    =        20             +        26
    = 46                                        E[S(3)] = 46/20
```

**k = 4** — fifteen cases, two nodes left. S = 2 if that pair is one of the 7 edges, 1 otherwise, and
there are C(6,2) = 15 pairs:

```text
Σ S = 7 × 2 + 8 × 1 = 22                        E[S(4)] = 22/15
```

**k = 5** — six cases, one node left, always its own component: `Σ S = 6`, **E[S(5)] = 1**.

**k = 6** — nothing left: **E[S(6)] = 0**.

Collecting, over a common denominator of 60:

| k | E[S(k)] | as a fraction | /60 |
|---|---|---|---|
| 0 | 6 | 6 | 360/60 |
| 1 | 28/6 | 14/3 | 280/60 |
| 2 | 51/15 | 17/5 | 204/60 |
| 3 | 46/20 | 23/10 | 138/60 |
| 4 | 22/15 | 22/15 | 88/60 |
| 5 | 1 | 1 | 60/60 |
| 6 | 0 | 0 | 0/60 |

The sum runs over the removal steps k = 1 … 6 — the k = 0 row is the normaliser, not a summand:

```text
Σ E[S(k)] = (280 + 204 + 138 + 88 + 60 + 0) / 60          k = 1 … 6
          = 770 / 60
          = 77 / 6

ROBUSTNESS_RANDOM = (77/6) / 36 = 77 / 216 = 0.35648148148…
```

**`ROBUSTNESS_RANDOM` = 77/216 = 0.3564814815**

### 7.3 Rt is *above* Rr here, and that is not a bug

```text
ROBUSTNESS_TARGETED  7/18  = 84/216 = 0.3889
ROBUSTNESS_RANDOM    77/216         = 0.3565
```

Targeted removal is normally the *worse* case, so a reader who has seen the usual Rr/Rt pair will
suspect an error. There is none, and the reason is worth stating because it is the substantive
finding this network produces.

Criticality ranks by **flow**. `PLANT-1` and `SUP-1` come first because without either of them the
network serves nothing at all — and both are structurally peripheral: `SUP-1` has undirected degree
1, `PLANT-1` degree 3 but only as a bridge. Two removals in, the largest component still holds four
of the six nodes, which is the *gentlest* possible start to a removal sequence. Random removal, by
contrast, hits `DC-1` — undirected degree 4, the actual structural hub — one time in six at the
first step, and fragmenting early costs every later term of the sum.

So this network is **fragile in service terms and cohesive in structural ones**: its flow
bottlenecks are exactly its structural leaves. That divergence is why the suite reports criticality
*and* both robustness indices rather than picking one, and it is precisely the "measurement is
fragmented across ≥6 non-convertible families; most studies report only one" gap (RQ5)
showing up in a six-node example.

---

## 8. `SPOF_NODE_COUNT`, `SPOF_ARC_COUNT` and `SPOF_COUNT`

> Definition: number of nodes/links whose single removal disconnects any customer from all supply.

One definition, reported three ways: the nodes it counts, the arcs it counts, and their total. The
test below is run once and the halves are read off it — the total is their sum by construction, since
an element is a node or an arc and never both. The split is reported because the halves suggest
different remedies: an indispensable *facility* is answered with a second site or a second qualified
source, an indispensable *lane* with a second route or a second carrier. The total is what a
configuration is ranked on; the halves are what a reader acts on.

A customer is *supplied* if a directed path reaches it from a supply origin — `{SUP-1}`, fixed (§2).
Both customers are supplied in the intact network: `S→P→D1→C1` and `S→P→D1→C2`. An element is a
single point of failure if removing **it alone** leaves some customer unsupplied that was supplied
before. Removing a customer does not count as disconnecting it — the test asks about the customers
that remain.

This is a question about **connectivity, not capacity**, which is what distinguishes it from §6: an
element can score 0 criticality (an alternative route exists) and still not be a SPOF for the same
reason, but the two can also disagree, and where they do the pair is more informative than either.

### 8.1 Nodes

| Removed | `CUST-1` supplied? | `CUST-2` supplied? | SPOF |
|---|---|---|---|
| `SUP-1` | no — no origin remains | no | **yes** |
| `PLANT-1` | no — `S` has no other outbound arc | no | **yes** |
| `DC-1` | **no** — arc **e** was its only inbound | yes, via **c**, **f** | **yes** |
| `DC-2` | yes | yes, via **g** | no |
| `CUST-1` | *(removed — not asked)* | yes | no |
| `CUST-2` | yes | *(removed)* | no |

**`SPOF_NODE_COUNT` = 3.**

### 8.2 Arcs

| Removed | `CUST-1` supplied? | `CUST-2` supplied? | SPOF |
|---|---|---|---|
| **a** `SUP-1 → PLANT-1` | no — nothing leaves the origin | no | **yes** |
| **b** `PLANT-1 → DC-1` | **no** — `DC-1` is now unreachable (`D2` has no arc back to `D1`) | yes, via **c**, **f** | **yes** |
| **c** `PLANT-1 → DC-2` | yes | yes — `D2` still reached via **b**, **d**; and **g** serves `C2` directly | no |
| **d** `DC-1 → DC-2` | yes | yes, via **c**, **f** and via **g** | no |
| **e** `DC-1 → CUST-1` | **no** — its only inbound arc | yes | **yes** |
| **f** `DC-2 → CUST-2` | yes | yes, via **g** | no |
| **g** `DC-1 → CUST-2` | yes | yes, via **f** | no |

**`SPOF_ARC_COUNT` = 3.**

### 8.3 The total

```text
SPOF_COUNT = SPOF_NODE_COUNT + SPOF_ARC_COUNT = 3 + 3 = 6
```

**`SPOF_COUNT` = 6**

The two halves being equal here is a coincidence of a six-node example and not a property: one
facility can be the source of several indispensable lanes, so the arc figure is usually the larger.
Both are reported because neither follows from the other.

Note that arc **b** is a single point of failure while `DC-2` is not, and that `DC-2`'s criticality
is 0 while arc **b**'s removal strands a customer entirely. Redundancy in this network is
asymmetric: `CUST-2` has two routes and `CUST-1` has one, and no aggregate figure says that — the
SPOF list and the criticality table together do.

---

## 9. The values the API must return

`GET /api/v1/networks/{id}/metrics/topological` on this network returns **fourteen** rows: eight
network-scoped and six `NODE`-scoped criticalities. Every row has `runId: null`,
`ciLow: null` and `ciHigh: null` (topological metrics are exact, not aggregated across
replications) and `displayUnit: null` (nothing in this suite is time-valued).

Rows arrive in **suite order**, which is the order below: the density and the three
single-point-of-failure figures first, because they are what a configuration is judged structurally
on and every surface leads with them, then the path, clustering, criticality and
robustness statistics.

| `metricCode` | `scope` | `scopeName` | Exact value | Decimal (10 s.f.) |
|---|---|---|---|---|
| `DENSITY` | NETWORK | — | 7/30 | 0.2333333333 |
| `SPOF_NODE_COUNT` | NETWORK | — | 3 | 3 |
| `SPOF_ARC_COUNT` | NETWORK | — | 3 | 3 |
| `SPOF_COUNT` | NETWORK | — | 6 | 6 |
| `AVG_PATH` | NETWORK | — | 21/13 | 1.615384615 |
| `CLUSTERING` | NETWORK | — | 7/18 | 0.3888888889 |
| `NODE_CRITICALITY` | NODE | `SUP-1` | 1 | 1.000000000 |
| `NODE_CRITICALITY` | NODE | `PLANT-1` | 1 | 1.000000000 |
| `NODE_CRITICALITY` | NODE | `DC-1` | 8/13 | 0.6153846154 |
| `NODE_CRITICALITY` | NODE | `DC-2` | 0 | 0.000000000 |
| `NODE_CRITICALITY` | NODE | `CUST-1` | 8/13 | 0.6153846154 |
| `NODE_CRITICALITY` | NODE | `CUST-2` | 5/13 | 0.3846153846 |
| `ROBUSTNESS_RANDOM` | NETWORK | — | 77/216 | 0.3564814815 |
| `ROBUSTNESS_TARGETED` | NETWORK | — | 7/18 | 0.3888888889 |

The `NODE_CRITICALITY` rows come back in snapshot node order — the order of `nodes.csv` — not sorted
by value; the panel sorts them for display.

**On comparing.** These are exact rationals; the API returns IEEE doubles. `7/30`, `21/13`, `7/18`,
`8/13`, `5/13` and `77/216` are none of them exactly representable, so compare to a
tolerance — **1 × 10⁻⁹ is far tighter than any of these computations can drift** and far looser than
the last-bit differences that summation order produces. The exact values (`1`, `0`, `3`, `3`, `6`)
should match to the bit; if `SPOF_COUNT` comes back as `5.999999` something is very wrong, and if it
is not exactly `SPOF_NODE_COUNT + SPOF_ARC_COUNT` an element is being counted twice or dropped.

## 10. If a number disagrees

This document is the specification, not a record of what the code did. A mismatch is a defect in one
of the two, and the arithmetic above is checkable by hand, so start by re-deriving the disputed
figure from §3–§8 rather than from the implementation. The most likely places for a genuine
implementation error, in the order they are worth checking:

1. **`DENSITY` reports 0.4667** — the denominator was taken as undirected pairs, `n(n−1)/2`.
2. **`AVG_PATH` is far too high, or infinite** — unreachable pairs were counted rather than
   excluded; or lead time was summed instead of hops, which on this network gives 47/13 ≈ 3.615
   rather than 21/13 ≈ 1.615.
3. **`CLUSTERING` is 0** — directed triangles were required. There are none here and there are none
   in any echelon-respecting network, which is why the metric is defined on the undirected form
   (§5).
4. **`SUP-1` scores 0 criticality** — the supply-origin set was recomputed after the removal, so
   `PLANT-1` was promoted to an origin when its supplier vanished (§2).
5. **`CUST-1` and `CUST-2` score 0** — customers were excluded from the removal loop. Criticality is
   computed for *every* node, and §6.4 explains what a customer's figure means.
6. **`ROBUSTNESS_RANDOM` moves between calls** — a seeded sampler is being used where n ≤ 16 should
   take the exact enumeration branch, or the seed is not fixed.
7. **`SPOF_COUNT` is 8** — the two customers were counted as points of failure against themselves.
   The split says where: it lands in `SPOF_NODE_COUNT`, which reads 5 instead of 3.
8. **Both robustness figures are high by exactly 1/(2n)** — `Rr` reads 95/216 and `Rt` 17/36 —
   the trapezoidal area under the whole curve was taken instead of the literature's
   `Σ S(k)/(n·S(0))` over k = 1 … n: the k = 0 term is the normaliser, not a summand (§7). If
   `Rt` alone is off by some other amount, the tie-break is a suspect — ranking ties by node id
   reproduces the database's insertion history, not the network — but this network happens not to
   expose that defect (§7.1's coincidence note: both orders sum to 14); the hub fixture in
   `RobustnessCalculatorsTest` is what pins it.
