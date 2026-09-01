# End-to-end click-through - import → scenario → run → dashboard → variant → compare

One pass through the whole Phase 1 workflow, in the browser only. Nothing here needs the
REST client; the equivalent API-level walk is `api-tests.http` requests 44–74 in the backend repo.

**Two ways in.** Section **A** imports the sample network and is the fast path. Section **A′** builds
the identical network by hand and never opens the import wizard - it is the walk that exercises the
Usability NFR ("a network of ~50 nodes can be modelled from scratch in under 30 minutes")
and the only one that touches the product catalogue as a screen. Do one or the other; both leave you
with a network called **Baseline v1** carrying the same six nodes, so every section after them reads
the same either way.

**Numbers.** This script tells you what to *look for*, not what the metrics will read. The one place
exact values are stated is step 9's aside, which points at the dataset whose numbers are derived by
hand in `docs/simulation-verification.md`. Anywhere else, a script that asserted "TTR = 7" would be
recording what the engine happens to do rather than what it should.

**Timing.** About 20 minutes end to end, most of it the two simulation runs. Taking A′ instead of A
adds about 15 minutes of typing.

---

## Setup

**S1.** Backend up, against a MySQL that has the `snrm` schema:

```bash
mvnw.cmd spring-boot:run
```

→ <http://localhost:8080/actuator/health> returns `{"status":"UP"}`, and the log lists the metric
suite: `Metric suite: 7 topological, 9 simulated calculator(s)`.

**S2.** Frontend:

```bash
npm install
```

```bash
npm start
```

→ Compiles clean; <http://localhost:4200> serves the login form. No new dependencies were added for
this stage - both charts are hand-drawn SVG.

**S3.** Sign in with the credentials from `SNRM_AUTH_USERNAME` / `SNRM_AUTH_PASSWORD_HASH`. If the
hash is unset the backend prints a generated password at startup.

→ Lands on **Projects**.

**S4.** Create a project - *Resilience walkthrough*.

→ Opens its dashboard. The header carries six actions: **Run simulation**, **Compare variants**,
**Disruption scenarios**, **Products**, **Import network**, **Refresh**.

→ And an amber banner: *"This project has no products yet."* It is there because the catalogue is
the first link in a chain that ends three screens away - demand is recorded per product at a node,
so with an empty catalogue no node can be a demand sink and a run is refused outright with
`NETWORK_HAS_NO_DEMAND`. Section A clears it by importing; section A′ clears it by hand.

---

## A. Import the sample network

**A1.** **Import network (CSV · Excel · XML)** → the three-step wizard.

**A2.** Drop all five `.csv` files from `../snrm-backend/samples/minimal-6-node/`
onto the drop zone at once.

→ Step 1 lists five files. The wizard names the sheets it recognised: `network_meta`, `nodes`,
`links`, `products`, `node_products`.

**A3.** Name the network **Baseline**. Continue.

→ Step 2, the column-mapping step, shows every canonical column already matched - this dataset uses
the canonical headers verbatim. Note that `nodes` has `capacity_value` and **no**
`capacity_time_unit`, and `links` has `lead_time_value` and no `lead_time_unit`. That is the point of
this sample: a plain numeric file with no unit columns still imports, every such value read in the
network's period unit.

**A4.** Continue to the validation report.

→ **`valid: true`, 0 errors, 0 warnings.** 6 nodes, 6 links, 1 product, 6 node-product rows. The
report also echoes the time base it read: **1 DAY**, horizon **52**, rounding **NEAREST**.

**A5.** Confirm the import.

→ The network opens in the editor. Six nodes laid out left to right by echelon - the file carries no
`pos_x`/`pos_y`, so `cytoscape-dagre` auto-laid it out.

**A6.** Open the metrics panel and confirm the structure before disrupting it.

→ The three single-point-of-failure rows are **not** zero: `SUP-1`, `PLANT-1` and `DC-1` are each a
single point of failure, because every path to both customers runs through them, and three arcs are
too - so the panel reads **3 nodes, 3 arcs, 6 total**. `DC-1 → CUST-2` is the one piece of redundancy
in the network, and it is why `CUST-2` has two routes and `CUST-1` has one.

> Worth noticing now, because it predicts everything that follows: the resilience of this network is
> bounded by its single plant, and no inventory lever will change any of the three figures.

---

## A′. The same network from scratch - an alternative to A

**Take this section or section A, not both.** It never opens the import wizard. It ends at the same
place: a network named **Baseline**, version 1, with the six nodes, six links and one product that
section A imports, so B onwards is unchanged.

It exists for two reasons. It is the walk that shows a from-scratch project is actually runnable
(before the catalogue screen existed, it was not - import was the only route to a network with
demand). And it is the closest thing to the Usability NFR that a script can be: six nodes
in about fifteen minutes puts fifty inside half an hour, with the per-node figures being the part
that scales.

### A′1. The catalogue first

**A′1.** Project dashboard → **Products**.

→ The catalogue screen, empty, and it says why that matters rather than just that the table is
blank: no product, no demand, no run.

**A′2.** Add a product - name **WIDGET-A**, unit value **25**.

→ The row appears. Go back to the dashboard: the amber banner is gone and **Products** carries a
badge reading **1**.

> The unit value is what weights unserved demand when service loss is expressed in money.
> It is not used by `FILL_RATE` or `TTR`, but `TOTAL_COST` and `DISRUPTION_COST_DELTA` read it, so
> leaving it at 0 would make the economic half of the suite report zero for every configuration.

**A′3.** *(Optional, and instructive.)* Press **Edit** on the row, clear the name, press **Save**.

→ Refused client-side. Now type a name and look at the third column: *"Both fields are sent - the
endpoint replaces the product, so a name saved alone would reset the unit value to 0."* `PUT` on a
product is a full replacement, which is why the row edits the price beside the name rather than
offering a bare rename.

### A′2. The network

**A′4.** Back on the dashboard, create a network named **Baseline** with **Baseline** ticked.

→ Row appears as `Baseline`, v1, badged **Baseline** and **Editable**.

**A′5.** **Open in editor**.

→ Palette left, canvas centre, property panel right. The toolbar reads **All changes saved** and
carries **⏱ 1 d × 52** - a new network's clock is one day with a 52-period horizon by default, which
is exactly what `network_meta.csv` carries in section A. Nothing to change.

### A′3. Six nodes

**A′6.** Drag one **Supplier**, one **Plant**, two **DC** and two **Customer** tiles onto the canvas,
left to right in that order. Rename each in the panel and set its fields:

| Name | Type | Capacity | Processing time | Fixed cost | Var cost | Failure prob | Region |
|---|---|---|---|---|---|---|---|
| `SUP-1` | SUPPLIER | 500 / d | 0 | 1000 | 2 | 0.02 | `EU-West` |
| `PLANT-1` | PLANT | 400 / d | 1 d | 5000 | 5 | 0.01 | `EU-West` |
| `DC-1` | DC | 350 / d | 0 | 1200 | 1 | 0.01 | `EU-North` |
| `DC-2` | DC | 350 / d | 0 | 1200 | 1 | 0.03 | `EU-South` |
| `CUST-1` | CUSTOMER | *(leave empty)* | 0 | 0 | 0 | 0 | `EU-North` |
| `CUST-2` | CUSTOMER | *(leave empty)* | 0 | 0 | 0 | 0 | `EU-South` |

→ Each field commits on blur, and the toolbar settles back to **All changes saved** within about two
seconds. A customer's capacity stays **unconstrained** - that is not the same as zero, and
the panel's × is how a capacity is cleared if you set one by mistake.

> The regions are worth typing even though nothing here disrupts by region: they are what a REGION
> event resolves through, and `EU-West` holding both `SUP-1` and `PLANT-1` is what makes
> that kind of event interesting on this network.

### A′4. Six links

**A′7.** Draw each arc by dragging from a source node's corner handle onto the target, then set its
fields in the panel:

| From → To | Lead time | Capacity | Unit cost | Failure prob |
|---|---|---|---|---|
| `SUP-1` → `PLANT-1` | 2 d | 500 / d | 3 | 0.01 |
| `PLANT-1` → `DC-1` | 3 d | 300 / d | 4 | 0.02 |
| `PLANT-1` → `DC-2` | 4 d | 300 / d | 4 | 0.02 |
| `DC-1` → `CUST-1` | 1 d | 200 / d | 2 | 0.01 |
| `DC-2` → `CUST-2` | 1 d | 200 / d | 2 | 0.01 |
| `DC-1` → `CUST-2` | 2 d | 150 / d | 3 | 0.02 |

→ Each arc is labelled with its lead time in the unit you typed. The last one is the whole point of
this network: it gives `CUST-2` a second route and `CUST-1` only one.

**A′8.** Try to draw `PLANT-1` → `SUP-1`.

→ The Supplier goes **red** and no link is created. A link into a SUPPLIER is refused by the echelon
rules, client-side, before a request is sent.

### A′5. Demand, which is the part that matters

**A′9.** Select `CUST-1`. In **Products at this node**, pick **WIDGET-A** from **Add a product** and
press **Add**, then set **Demand** to **40** per **day**.

**A′10.** Same on `CUST-2`, with demand **25** per **day**.

**A′11.** Now the supply side. On each of `SUP-1`, `PLANT-1`, `DC-1` and `DC-2`, add **WIDGET-A** and
set its stock figures - demand stays 0 on all four:

| Node | Initial inventory | Safety stock | Holding cost |
|---|---|---|---|
| `SUP-1` | 300 | 100 | 0.02 / d |
| `PLANT-1` | 200 | 80 | 0.05 / d |
| `DC-1` | 120 | 60 | 0.10 / d |
| `DC-2` | 120 | 60 | 0.10 / d |

→ Each figure is one `PUT /nodes/{id}/products/{productId}` carrying **all four** numbers, because
the endpoint is a full replacement. `DC-1` and `DC-2` at 120 are what step F1 raises to 400.

> Inventory on the supply side is not decoration. Replenishment targets `safetyStock + covered
> demand × (1 + delay)`, so a network with lead times and no opening stock cannot move anything at
> all in its first periods - every sample under `samples/` has this shape for that reason.

**A′12.** *(Optional, and the failure this whole section is about.)* If you want to see what an empty
catalogue costs, do A′4–A′8 in a second project without doing A′1–A′2 or A′9–A′11, then run a
simulation against it after section B.

→ **422 `NETWORK_HAS_NO_DEMAND`**. Every simulated metric would report perfect service against every
scenario, which is indistinguishable from a resilient network - so the submission is refused rather
than answered.

### A′6. Same check as A6

**A′13.** Open the metrics panel.

→ The single-point-of-failure rows are **not** zero: `SUP-1` and `PLANT-1` are each a single point of
failure, so the node figure is at least 2. If the total reads 0, a link is missing or pointed the
wrong way - compare against the table in A′7 before going on.

> If you would rather not retype this network next time, open the dashboard row's **Actions** menu
> and press **Export XML** (FR-26 moved the row's secondary controls behind one menu).
> The document carries the units *and* the canvas coordinates, so re-importing it gives you this
> network arranged exactly as you left it.

---

## B. Build the scenario

**B1.** Back to the project → **Disruption scenarios** → **New scenario**.

**B2.** Name it **Plant outage**. Set **replications 1** and **seed 20260803**.

> One replication and a fixed seed make the run exactly reproducible and hand-checkable.
> Step 12 raises it to 100, which is when the confidence intervals start meaning anything.

**B3.** Open the scenario. The timeline asks which network to lay itself against - pick **Baseline
v1**.

→ Rows appear for the network's nodes and links; the axis is 52 periods of 1 day. The
network is a *picker*, not part of the scenario: a scenario is project-scoped so it can be replayed
against every variant.

**B4.** Add an event:

| Field | Value |
|---|---|
| Target | **NODE → PLANT-1** |
| Start offset | **5 DAY** |
| Duration | **10 DAY** |
| Severity | **1.0** |
| Recovery profile | **STEP** |
| Probability | **1.0** |

→ A bar appears on the `PLANT-1` row spanning periods 5–14. The bar's label reads **10 d** - the
unit you typed, not a period count.

**B5.** Deliberate failure, then undo it. Change the duration to **60 DAY**.

→ Refused with **`EVENT_EXCEEDS_HORIZON`**: 5 + 60 = 65 days runs past the 52-period horizon. The
message names the period the event would end in. Set it back to **10 DAY**.

> This is checked twice - here at write time, and again at submission against whatever network the
> run actually names, because a variant may have a different clock.

---

## C. Run the simulation

**C1.** Project dashboard → **Run simulation**.

**C2.** Network **Baseline v1**, scenario **Plant outage**.

→ Under the network picker: *"Steps in 1 day, horizon 52 × 1 day"*, and the warning
**"Submitting freezes this network - later edits must fork a variant. Cancelling releases it."**
Under the replications field: *"The job executes **2** - twice this, because every run includes the
paired undisrupted baseline set…"*.

**C3.** Leave both parameter fields blank.

→ The placeholders show what will be used: **1** replication and seed **20260803**, both from the
scenario. Blank means "use the scenario's", not zero.

**C4.** Open **Show stochastic settings**, confirm demand noise and jitter are both blank (→ 0), then
collapse it again.

> Zero is the default and is a choice: the run is exactly deterministic, which is the only setting
> under which a result can be checked by hand.

**C5.** **Run simulation**.

→ The monitor panel appears with the run id, the job id, a striped progress bar, the resolved seed,
and a **Cancel run** button. Status goes `QUEUED` → `RUNNING` → `DONE`.

**C6.** *(Optional, and worth doing once.)* Submit a second run and press **Cancel run** immediately.

→ Status reaches `CANCELLED` within a replication rather than instantly - cancellation is
cooperative and never interrupts, because a worker killed between writing metric rows and writing
the run's status would leave a half-persisted result that looks complete. The panel then
says *"A cancelled run holds nothing frozen, so its network is editable again."*

---

## D. Read the dashboard

**C5** navigates here automatically on `DONE`; the URL is
`/projects/{id}/simulations/{runId}?jobId=…`.

**D1.** The performance curve.

→ Check five things:

1. **Two curves.** A solid line - the disrupted run - and a dashed grey one, the undisrupted
   baseline set every run includes.
2. **A shaded region between them**, opening around period 5 and closing some periods *after* the
   event ends at 14. That lag is real: the lead times mean the network cannot refill instantly, and
   it is why `TTR` will exceed the 10-day event window.
3. **The x-axis title reads `Period (1 day)`**. A period index alone is meaningless -
   the same 52-point curve is a year on one network and two days on another.
4. **The y-axis runs 0–100% regardless of how far performance fell.** Fitting it to the data would
   redraw the same disruption as a cliff on one variant and a dip on another.
5. **A caption stating the shaded polygon's area** and a sentence explaining why the `LOSS_AREA`
   card may read higher. Read that sentence - see step D4.

**D2.** The metric cards.

→ Nine simulated metrics, colour-dotted by metric family. Each shows its mean, its 95% interval and
a whisker. At one replication **every interval reads `exact`** and no whisker is drawn - an interval
is a property of aggregating replications, and a zero-width one would claim a certainty the run does
not have.

**D3. The `TTR` card is the one to look at closely.**

→ It reads **`N periods (N days)`** - both forms. The period count ties the figure to a
column of the time series you can go and look at; the duration is what makes it mean something to
someone who does not hold this network's clock in their head.

> On a network stepping in `2 DAY` the same card would read `N periods (2N days)`. The conversion
> multiplies by the period's *value* as well as reading its unit - `core/metric-display.ts`,
> `readablePeriods`, with a test for exactly that case, because every sample network here uses a
> 1-unit period and would hide the slip.

**D4.** Compare the `LOSS_AREA` card against the caption under the chart.

→ At **one** replication they agree. They are still two different computations: the metric takes each
replication's shortfall and *then* averages, while the curve is already an average, so the drawing is
a lower bound. Step 12 is where they can diverge.

**D5.** The per-node criticality table.

→ One row per node, worst first, bars scaled to the worst node rather than to 1. `PLANT-1` and
`SUP-1` head it. Exact values, no intervals - these are structural, a property of the network rather
than of the run.

**D6.** **Export .xlsx**.

→ A workbook `Baseline-v1-run{N}.xlsx` with three sheets:

- `run` - including `params_json` verbatim, seed and all. An exported result whose seed is missing
  cannot be re-derived.
- `metrics` - `TTR` written twice: `value` in periods and `value_in_display_unit` beside its unit.
- `timeseries` - both curves plus `fill_rate_loss`, the height of the triangle at each period.

**D7.** Press **.csv**.

→ A zip of the same three tables. A CSV export of three tables is three files, and a browser can be
handed one.

---

## E. Clone the variant

**E1.** From the dashboard header, **Open network**.

→ The editor opens on **Baseline v1**, and it is read-only: the run froze it.

**E2.** Try to change anything - drag a node, or edit a field in the property panel.

→ The **fork prompt** appears rather than the edit failing. It states that the attempted edit is
**not** carried over, which is deliberate: replaying an edit across a fork produces a configuration
nobody explicitly chose.

**E3.** Fill in both fields:

| Field | Value |
|---|---|
| Name for the variant | *(leave empty)* |
| What is this variant meant to change? | **DC buffers raised 120 → 400 at DC-1 and DC-2** |

**E4.** **Fork a variant and edit that.**

→ A new network **Baseline v2** opens, editable. The second field is stored as
`configuration_variant.lever_changes_json` and is what step 9 renders under the variant's column -
the thing that turns "this one recovers faster" into a statement about a lever.

> Leaving that field empty is allowed and costs you the annotation. It is a note rather than a
> structured diff because the fork necessarily happens *before* the edit; the structured vocabulary
> belongs to the Phase 2 configuration engine.

**E5.** Go back to the project dashboard.

→ Under the network table there is now a **Where these configurations came from** panel:
**Baseline v1** with **Baseline v2** indented beneath it, and the note from E3 on the indented row.
It appears only once something has been forked, and it is the only screen that answers *what was
derived from what* - the table's version numbers do not, and the comparison view annotates columns
rather than lineage.

> Fork **Baseline v2** later and the third row hangs under *it*, not under the baseline. That
> distinction is the reason the panel exists.

---

## F. Edit the variant

**F1.** Select **DC-1**. In the property panel's per-product section, set
**initial inventory 120 → 400**.

**F2.** Same for **DC-2**.

**F3.** Watch the dirty indicator settle.

→ Edits are debounced and PATCHed every two seconds and on blur. Do not navigate before it
clears - or do, and let the route guard flush it, which is the same path.

**F4.** *(Optional.)* Re-open the metrics panel.

→ All three single-point-of-failure figures are **unchanged**. Inventory is not a structural lever:
the plant is still the only plant. This is the step that makes the comparison in step 10 interesting rather than a foregone
conclusion - the improvement, if any, will show in the simulated half of the suite and not the
topological half.

---

## G. Re-run against the same scenario

**G1.** Project dashboard → **Run simulation**.

**G2.** Network **Baseline v2**, scenario **Plant outage** - *the same scenario*. Leave parameters
blank so the seed is again 20260803.

> Same scenario and same seed is what makes the two runs a controlled comparison. A different
> scenario, or a drawn seed, and the difference between them is no longer attributable to the lever.

**G3.** Run it. Wait for `DONE` → dashboard.

**G4.** Compare by eye against step D.

→ The shaded region should be **shallower**, and `TTR` shorter - the DCs now hold more days of cover
against a plant that produces nothing. `MIN_FILL_RATE` should be higher. `TOTAL_COST` may well be
*higher* too: 560 extra units sitting in inventory accrue holding cost every period of the horizon.
That trade-off is the finding, and it is the reason the comparison view exists rather than a single
"resilience score".

---

## H. Compare

**H1.** Project dashboard → **Compare variants**.

→ Both networks are ticked and the matrix is built immediately.

**H2.** Set the scenario picker to **Plant outage**.

→ Every column is now pinned to that scenario's most recent completed run, and any
**`MIXED_SCENARIOS`** note disappears. Without pinning, one column can be a plant outage and the next
something else, and the lever change takes the credit for the difference.

**H3.** Read the matrix.

→ Check five things:

1. **Columns in the order you ticked them**, each headed `Baseline v1` / `Baseline v2` with a link to
   its run.
2. **The lever annotation row** under the headers. `Baseline v1` reads *"- (not a variant)"*; it is
   the imported baseline and was forked from nothing. `Baseline v2` carries
   **`note: DC buffers raised 120 → 400 at DC-1 and DC-2`**.
3. **Best-in-row highlighting** - a tint plus a left rule plus bold, never colour alone. Expect the
   two columns to split the wins: v2 on the recovery and absorption rows, v1 on `TOTAL_COST`.
4. **`DENSITY`, `AVG_PATH` and `CLUSTERING` have no winner** and are labelled *not ranked* in
   italics. That is a decision, not a gap: a denser network is better connected *and* more
   expensive, and the suite treats neither as an objective.
5. **The `TTR` row is labelled `· in days`** and each cell shows the converted value with its raw
   period count beneath it.

**H4.** The radar, beside the matrix.

→ Every axis oriented so further out is better, the ranked metrics only. `DENSITY` and friends are
absent for the reason in H3.4 - an axis whose outward direction means nothing is worse than a missing
axis. Read its caveat line: each axis is scaled across *these* variants, so a metric they barely
differ on still spans the full radius.

**H5.** **Export .xlsx**.

→ Three sheets: `comparison` (each variant in three columns - value, `ci_low`, `ci_high` - so every
cell stays a number you can sort and chart), `variants` (with the `lever_changes` column), and
`notes`. The caveats travel with the file deliberately: a matrix separated from its warnings is a
matrix that will be misread.

---

## I. Two things worth provoking

**I1. Mixed time bases.** Clone `Baseline v2`, and in the new variant's editor open
**time settings** and change the period to **6 HOUR**, horizon **208** (the same 52 days). Run it
against the same scenario, then add it to the comparison.

→ The matrix raises **`MIXED_TIME_BASES`**. Every column's header grows a period badge, the `TTR`
row's unit becomes **hours** - the finest period unit among the compared variants, so nothing is
rounded away - and each cell keeps its own raw period count beneath the converted value. The banner
stays up even though the arithmetic is sound: the values are comparable, but the *models* differ in
what they can resolve, and a daily variant cannot express a six-hour recovery at all.

**I2. A variant with no run.** Clone any network and, without running it, add it to the comparison.

→ A **`NO_RUN`** note, and that column's simulated cells are blank while its topological cells are
filled. Blank is *unmeasured*, never zero - rendering it as 0 would put the best possible recovery
time on a configuration nobody tested. The structural half is there because topological metrics are
computed on save, so a fork can be judged structurally before an hour of Monte Carlo is spent on it.

---

## J. Confidence intervals, properly

**J1.** Edit the **Plant outage** scenario: **replications 100**, seed unchanged.

**J2.** Re-run both variants. Each job now executes 200 replications.

**J3.** Back on either dashboard.

→ The cards now show real intervals and drawn whiskers - except `CVAR_COST`, which still reads
`exact`. It is a functional of the whole replication set rather than a mean of per-replication
values, so an interval computed the way the others are would overstate its precision.

> With demand noise still at 0 the 100 replications are identical and the intervals collapse to a
> point. To see them open up, set **demand noise (CV) 0.15** and re-run - and note that at that point
> the `LOSS_AREA` card and the chart caption of step D4 can genuinely diverge, for the reason the
> caption gives.

**J4.** In the comparison matrix, every cell now carries `[low – high]`.

→ **This is the step that decides whether the lever did anything.** If v1 and v2's intervals overlap
on `TTR`, the difference between the two configurations is not distinguishable from replication
noise, whatever the highlighting says. Best-in-row marks the better mean; it does not claim
significance.

---

## Where the numbers are checkable

This walkthrough uses `minimal-6-node` because it has redundancy worth disrupting. It has no
hand-derived expected values.

For a run whose every figure is derived by hand, import
`../snrm-backend/samples/simulation-verification-3-node/` instead and follow
`docs/simulation-verification.md`: a three-node chain, one deterministic event, and §8 states the
exact nine values a fixed-seed single-replication run must return - `FILL_RATE` 0.94,
`SERVICE_LEVEL` 0.7, `MIN_FILL_RATE` 0.8, **`TTR` 3 (`DAY`)**, `LOSS_AREA` 0.6, `TOTAL_COST` 6315,
`DISRUPTION_COST_DELTA` 390, `CVAR_COST` 6315, `RESILIENCE_INDEX` 32/35.

`SimulationVerificationTest` asserts those same numbers, so the dashboard, the export and the test
suite can be diffed against one document. If the UI disagrees with it, the document is right and
something in between is wrong.
