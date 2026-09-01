# `simulations` - run launcher, job monitor, results dashboard

Feature folder implementing the run half (FR-06 – FR-08) and the
element-aware dashboard of FR-22.

| Route | Component |
|---|---|
| `/projects/:projectId/simulations` | `run-launcher/` - pick, submit, watch |
| `/projects/:projectId/simulations/:runId?jobId=…` | `results-dashboard/` - map, period cursor, curve or element charts, cards, criticality |

| File | Role |
|---|---|
| `run-results.store.ts` | The page's state: the run, its suite, the criticality table, the network, the **scope**, the element series, and the **period cursor**. **Authoritative.** |
| `curve-geometry.ts` | Pure: the fill-rate curve, the resilience triangle, the scales. Specced. |
| `mini-map-layout.ts` | Pure: the network inspector's layout - stored coordinates or the echelon fallback, fitted, with arcs and arrow heads. Specced (FR-22). |
| `element-charts.ts` | Pure: which series an element scope draws, what its gaps mean, which mean is honest, the two axes, and the at-period reading. Owns the shared `SeriesChart` type and `ChartSize`. Specced (FR-22). |
| `network-charts.ts` | Pure: the six series the network scope charts beside its curve, and which of them the schema pairs. |
| `period-cursor.ts` | Pure: where the cursor may stand, where it opens, what the network figures say at it, and how the miniature is tinted. Specced (FR-22). |
| `performance-curve/` | The fill-rate curve and its triangle. Input-driven, because the editor's run panel renders it too. |
| `network-inspector/` | The read-only miniature, top-left. Hand-drawn SVG; every element a click target, tinted at the cursor (FR-22). **Input-driven since FR-25**, like `performance-curve/` and for the same reason: `features/comparison`'s side-by-side window draws one per pane, so this page passes its network, its scope and its tints in rather than the component reaching into the store. |
| `element-charts/` | What replaces the curve while an element is scoped (FR-22). |
| `series-chart/` | One series as a stepwise chart with its overlay, its axes, its cursor line, its at-cursor figure, and its two sizes (FR-22). |
| `period-cursor/` | The transport that writes the cursor: scrub, step, readout. No play button, deliberately (FR-22). |
| `results-export.service.ts` | The `.xlsx`/`.csv` download, shared with the comparison view. |

Endpoints: `POST /simulations`, `GET /jobs/{jobId}`, `DELETE /jobs/{jobId}`,
`GET /simulations/{runId}`, `GET /simulations/{runId}/export`,
`GET /networks/{id}/metrics/topological` for the criticality table, and - for FR-22 -
`GET /networks/{id}/nodes`, `GET /networks/{id}/links` and
`GET /simulations/{runId}/timeseries/elements`.

`MANUAL-TEST.md` walks the whole page against the four-echelon sample, whose figures are derived by
hand in `../snrm-backend/samples/four-echelon-playback/README.md`.

## Eight things here are load-bearing

**This is the first caller of `core/JobPollingService`, and it stays the only kind.** The launcher
submits, hands the returned `jobId` to `poll()`, and renders what comes back; nothing in the feature
implements a timer. That is what will let the Phase 2 configuration search reuse the loop rather than
grow a second one.

**The job id is only ever given to the submitter.** `GET /simulations/{runId}` does not carry one, so
the dashboard takes it as `?jobId=` from the URL the launcher navigates to. With it, the page shows
live progress and a cancel button; without it - a bookmark, a reload - it degrades to a refresh
button. Inventing a poll of the run endpoint on a timer instead would be the second polling loop the
page must not have.

**The curve is hand-drawn SVG, and `curve-geometry.ts` is why.** The chart draws the resilience
triangle literally: the region *between two curves*, clipped to where one is below the
other and closed at the crossings. No area or band primitive draws that without being lied to about
what the series are. Keeping the geometry in a pure module makes it testable against hand-worked
numbers - `curve-geometry.spec.ts` - and costs the bundle nothing.

**The shaded area is not the `LOSS_AREA` card, and the dashboard says so.** The metric takes each
replication's shortfall and then averages; `RUN_TIMESERIES` holds averages already, so the drawing
computes the same quantity with the mean and the `max(0, …)` in the other order. Convexity makes the
shading a lower bound, and the two coincide exactly on a deterministic run. Showing both numbers
without the sentence would look like a bug.

**`TTR` is shown in both forms** - "14 periods (14 days)". The conversion is
`core/metric-display.ts`, which multiplies by the period's **value** as well as reading its unit: a
network stepping in `2 DAY` carries `displayUnit: DAY`, and 14 of its periods are 28 days. Every
sample network uses a 1-unit period, which is exactly why that multiplication is easy to leave out
and has its own test.

**The dashboard can also discard the run it is showing** (FR-20). *Delete run* opens the typed
confirmation of `core/run-discard.ts` - the same module and the same wording the editor's discard
uses, so the two surfaces cannot ask different questions about one irreversible act. On success the
store empties itself, polling stops and the page navigates to the run list, because this route no
longer resolves. `RUN_ACTIVE` (409) is a real answer here, unlike in the editor's run panel: this
page opens on a `QUEUED` or `RUNNING` run by design, and the problem's own sentence names
`DELETE /jobs/{jobId}` as the first call - which is the **Cancel** button already on the page.
Deleting the last locking run of a network releases the freeze, and the editor discovers
that the next time it reads the network row; nothing here tells it.

**Route entry goes through `RunResultsStore.open`, it re-reads every time, and it takes its own
write permission.** Two defects lived in the two lines this replaced, and both presented as the same
thing on screen - the breadcrumb with nothing under it, because everything the dashboard draws sits
inside one `@if (run(); as record)`.

The first was a *silence*. `load` sets `runId` and `state` on its first line, and it was being called
from an `effect` created without `allowSignalWrites`, which Angular refuses with `NG0600`. Because
the refusal is raised inside an `async` body it never reaches the `ErrorHandler`: it became an
unhandled rejection that `void` discarded, so the effect "succeeded", the fetch never started, and
nothing appeared on the Network tab or in an error banner. `open` therefore performs its state
transition inside `untracked` - a **command** is not a computation, and a store whose usability
depends on an option in a component three files away is a trap laid for the next caller. The callers
pass the flag too; nothing here relies on their remembering.

The second was a *stale cache*. The old entry test, `runId !== store.runId()`, reads like a cache
check over a store that is `providedIn: 'root'` - but a run id says which run the store last pointed
at, never whether what it holds is worth showing. A run first opened while `RUNNING` stayed
unfinished for the life of the tab, and a first load that failed could not be retried by navigating
back to it. The dedupe now belongs to the **component instance**, one per route entry, so entering
the page is always a read; switching runs empties the previous one first (a page must not draw one
run's numbers under another run's id), and re-entering the same run empties nothing, because blanking
a page to redraw the same figures is a flicker rather than a refresh. `run-results.store.spec.ts`
pins both, and its first case deliberately drives the store from a flagless effect.

The launcher and the comparison view carried the same missing flag on their own route effects; the
launcher's `load` is synchronous, so there the refusal was loud - an `NG0600` through the
`ErrorHandler` and an empty picker - rather than silent.

**The third defect was behind the other two, and it is why "the page is empty" was ambiguous.** With
the fetch restored, the dashboard still rendered nothing: `record.params.replications` threw during
the view update, because the API answers `GET /simulations/{runId}` **without** `params` whenever
`SimulationService.fromJson` cannot deserialise `params_json`. Angular abandons the remainder of a
view when a binding throws, so one dereference took the seed, the export button's label and every
embedded block below it - curve, cards, criticality, structure - off a page whose data had arrived
intact. `params` is now `SimulationParams | null` and the template renders its absence as the
reproducibility gap it is.

That one is a special case of a contract mismatch this whole feature sat on: the API runs
`spring.jackson.default-property-inclusion=non_null`, so **every** nullable field arrives absent
rather than null, while the models declare `T | null` and the code tests `=== null`. `undefined`
fails every such test and passes every `!== null` one. `core/api-nulls.ts` reconciles the two at the
boundary - once, where the response arrives - and its spec is written with the fields **omitted**,
the way the wire writes them, because a fixture that spells the nulls out agrees with the models and
disagrees with the browser.

## The element-aware dashboard (FR-22)

`mini-map-layout.ts` + `element-charts.ts` + three components + a scope on the store. In one
sentence: the top-left corner holds a read-only **network inspector**, clicking a
node, a link or empty space **scopes the page**, and an element scope replaces the curve area with
that element's per-period series as full stepwise charts.

Eight things carry it, and most of them are a rule stated elsewhere being obeyed rather than a new
one.

**The miniature is now shared with `features/comparison` (FR-25), and was generalised in place.**
The side-by-side window draws one read-only miniature per pane and reuses *this* one, so
`network-inspector` stopped reading `RunResultsStore` and took the network, the selection and the
tints as inputs - each defaulting to what this page already did, so nothing here renders differently.
Two consequences live in this folder: `MiniMapSelection` moved into `mini-map-layout.ts` and
`DashboardScope` **is** that type (one value, one declaration), and the four-state tint sentence moved
onto `RunResultsStore.tintNote`, because every version of it is a statement about a *run* and a pane
has none. The tint inputs stayed **flat** rather than becoming one object: `fillScales` runs once per
run and `cursorTints` once per period, and an input object rebuilt by a caller's computed would
collapse that split and walk every horizon on every step.

**The network scope is the dashboard as it always was**, with the charts added to it. Nothing was
taken away and no existing panel is gated on the scope: the metric cards are horizon scalars with
confidence intervals and have no per-period form - a horizon scalar is one number for the whole
run - and the criticality table describes the network rather than any one node. What the
scope switches is one column.

**The miniature is hand-drawn, and here is why.** Cytoscape is a graph *engine* - pan, zoom,
layout, edge handles, gesture routing - and this surface deliberately has none of that. It needs a dot
per node, a line per arc and a click target on each, which is `mini-map-layout.ts` and forty lines of
template. It is the charting rule taken one shape further, and it keeps ~400 KB out of a
bundle that would otherwise carry the editor's canvas engine to draw a thumbnail.

**The arrangement is chosen for the whole graph.** Stored `pos_x`/`pos_y` when *every* node carries
both, and the echelon fallback otherwise, with the panel saying which it drew. Mixing them would put
a synthesised position and a researcher's own in one picture, and they are not in the same space: a
node laid out at echelon column 2 among neighbours stored at canvas x = 1840 lands on top of
something or a screen away from everything, and nothing on screen would say which nodes were placed
and which were guessed. The fallback is computed client-side and **never persisted** - this page has
no write path to `PATCH /networks/{id}/nodes/positions` at all, and could not use one: the network is
frozen while a locking run exists.

**One selection, two representations.** The criticality table's rows and the miniature's dots write
the same signal, and each shows the other's state - a marked row, a ringed dot. Two selections that
could disagree would be two answers to "which node is this page about" on one screen. It is the
gesture the editor's network dashboard already uses for its own criticality rows.

**The palette and the step geometry are imported from the editor, and that is the
`disruption-overlay.ts → timeline.ts` precedent rather than a new liberty.** A PLANT cannot be purple
on the canvas and blue on the miniature, and a step line cannot break at a null in the inspector and
dip to zero here; both are *readings of one fact* that two surfaces must agree on, which is exactly
what that precedent is for. Lifting `echelon-rules.ts` into `core/` would move link validity, type
defaults and auto-naming - editor gestures no other feature reads - and splitting the palette out of
it would re-aim two references (`echelon-rules.spec.ts` and `playback-channels.gaugeColours`)
to gain nothing the import does not. `mini-map-layout.spec.ts` asserts
the colours against `nodeTypeProfile` **itself**, as `disruption-overlay.spec.ts` asserts against
`placeBar`: a shared fact pinned at the seam rather than transcribed.

**The step geometry is reused at chart size, so this application still has three of them.**
`sparkline-geometry.ts` grew four optional gutters - a chart has a value axis and a period axis where
a sparkline has neither - and every existing caller states `pad` alone and draws exactly as before,
which is why that module's spec still pins the same hand-worked path strings. A fourth geometry could
only ever be a second implementation of "a period is a band, and a gap is nothing".

**Only three of the eleven element columns have a baseline, and the schema is why.**
`V9__element_timeseries.sql` records `baseline_on_hand`, `baseline_served` and `baseline_flow`. So
on-hand, served and flow carry the paired overlay and the other charts are drawn **bare**, with one
footnote beneath the set rather than eight repetitions of it - never overlaid with a copy of
themselves, which would claim the disruption moved no material. This is `network-series.ts`'s rule for
`endingInventory` and `inPipeline`, one scope down. *Unserved* is the tempting exception and is
deliberately not derived: `served + unserved − baselineServed` would be an undisrupted unserved series
inferred from an invariant the schema does not state at element scope, and if it were ever false
nothing on the page would say so.

**Every FR-18 discipline holds at chart size.** A null breaks the line rather than dipping it to zero;
a gap in `inboundLead` reads *nothing was dispatched toward this node*, counted (`3 of 30 periods`)
so a reader can find it; a gap in `utilisation` reads *no capacity was available*, while an arc with
**no declared capacity** gets the word *uncapped* in place of a chart, since every period of it is
null; and a run whose element series is `available: false` shows the editor's own sentence, verbatim
from `core/element-series.ts`, with the whole network scope still working beside it. None of those is
an error banner - a run that recorded no element detail answered a different question, not a failed
one.

### Two fetches on opposite schedules

**The structure is read on open**, because the inspector is on the page from the moment it renders and
a miniature with nothing in it is a hole rather than a degradation. It is read **once per network id**
and never again: a completed run freezes its network, so a poll settling would spend two
requests to be told the same thing.

**The element series is read at the first gesture that needs it** - an element scope, or a move of the
period cursor - and never for a reader who makes neither. It is `horizon × (nodes + links)` numbers -
the reason it is kept out of `GET /simulations/{runId}` at all - and the default scope at the
default cursor position needs none of it. Once read it stays in memory for the life of the run, so
switching elements and scrubbing the whole horizon cost nothing. That is `PlaybackStore`'s trade taken
at a different moment: the editor's canvas needs the series the instant a run completes, and this page
does not. A read that **failed** is retried by the next element click; `available: false` is not,
because it is a durable fact about the run.

## The shared period cursor (FR-22)

`period-cursor.ts` + `period-cursor/` + one signal on the store, read by four surfaces. What it does
is one sentence long: a period cursor - scrub and step controls shared by every chart on the page -
moves back and forth through the horizon. Every chart carries the cursor line, every per-period
figure restates itself at the cursor's period, and the miniature tints availability and fill at
that period.

The seam the first session left held exactly as written: `series-chart` already took `cursorPeriod`,
`element-charts.readingAt` was already the at-period figure, and `mini-map-layout` still reads no
period at all. What was added is the position, the controls, and the two derivations that were not
already somewhere else.

**It is one signal, and that is the requirement rather than an economy.** `RunResultsStore.cursorPeriod`
is read by the performance curve, by each of an element scope's eight charts, by the figures under the
curve and by the miniature's tints. A cursor per component would be four positions free to drift, and
the failure is a specific one: the same period in two places on one screen.

**It is a cursor and not a clock.** There is no play control, no timer and no `requestAnimationFrame`
anywhere in this feature - `period-cursor.ts` has no `advance` function, which is the one thing
`network-editor/playback-clock.ts` is mostly made of. The dashboard navigates a run; animating one is
playback, and there is one such loop in the application.

**It opens on the last period.** Every scalar on the page is a horizon figure, so a cursor opening at
period 0 would put the per-period column at the warm-up while the cards described the whole run - two
answers to "what is this page showing" before the reader had touched anything. The end state is what
"the result" means until somebody scrubs, and it makes the first useful gesture scrubbing *back* into
the disruption from the outcome it produced. `PlaybackStore` rewinds to 0 for the opposite reason: a
story starts at the beginning, and a question is asked of a finished run.

**The horizon is the run's own record**, `run.horizonPeriods`, like every other reading on this page -
it is what "of 52" restates. The transport renders only where the run has a curve, which is the honest
answer for a `QUEUED` run rather than a scrub bar over periods that do not exist yet; the two cannot
disagree where it matters, because `RUN_TIMESERIES` is written whole at completion.

**The readout is `core/metric-display.periodReadout`**, which the editor's playback transport now
prints too - *"Period 14 of 52 - 14 days"*, restated through the **run's** clock and never a bare
index. The string moved to `core/` when this landed, unchanged, for the reason
`run-discard.ts` lives there: one researcher reads period 14 of one run on two screens.

**The arrow keys are on the page, not on the transport**, so a reader looking at a chart at the bottom
can still step; and they are guarded through `core/text-entry.ts`, the editor's own transport guard,
now shared rather than copied. Never while focus is in a form control - which is what leaves the scrub
slider's native arrow keys to the slider, since it already moves by exactly one period.

**Scoping does not move it.** Switching between the network and an element is a change of subject, not
of period, and comparing an element against the whole network *at one period* is what the scope is
for. A refresh does not move it either: `load` re-defaults only for a run it has not positioned yet,
so the poll settling every few seconds while a run finishes cannot throw a reader out of a disruption
window.

**Nothing here re-derives a series.** The network figures come from `network-editor/network-series.ts`
- the same seven quantities the editor's network dashboard prints at its playback clock - and the
tints from `network-editor/playback-channels.ts` (`fillLevel`, `unavailableOpacity`, `valueAt`,
`seriesMax`). Two more cross-feature imports of pure specced modules, under the
`disruption-overlay.ts → timeline.ts` precedent and for its reason: one run must not have two
derivations of "cost through period 14" or of "how full is `DC-1` at period 11". Two of the seven
labels differ from that panel's - *Cost to this period* rather than *Cumulative cost* - because there
"cumulative" can only mean "through the clock's period" and here the reader chooses where it stops.

**The at-cursor fill rate is `NetworkSeries.fill`, not a fresh `served / demand`.** They differ on a
period nobody demanded anything in, which the engine reads as fully served - so the curve is drawn at
100% there. A figure that dashed under a line drawn at 100% would be exactly the drift the shared
cursor exists to prevent, so the convention is stated in a sentence instead.

**The transport's first use is the third gesture that reads the element series**, beside the two scope
gestures. The tints are the one thing on the page that needs it, and the *default* position is not a
gesture - so a reader who opens a run, reads its curve and leaves still spends nothing on
`horizon × (nodes + links)` numbers. Until it is in memory the miniature draws plain dots and says so
in a line; on a run whose series is `available: false` it says `core/element-series.ts`'s sentence,
verbatim, and every chart and figure on the page keeps moving with the cursor.

## Every series charted, and the double-click

`network-charts.ts` + a `size` on both chart components + an expanded set on the store. A run writes
**seven** per-period series and this page drew one of them as a shape; the other six were readable
only as figures at the cursor, which states a value and cannot show a curve bending. The network scope
now charts all seven.

**The curve is not one of the six, and `network-charts.ts` says why.** Its y-axis is pinned to [0, 1]
where every other chart fits its own range (`sparkline-geometry`'s module note argues both), and the
region *between* it and its baseline is the resilience triangle "rendered literally" - a shape no step
chart draws. It keeps `performance-curve/` and takes part in the gesture through one key,
`FILL_RATE_CHART`.

**The six are `network-series.ts`' arrays, not new ones.** This module chooses which are charted, in
what order, with what sentence and against which undisrupted twin; it computes nothing. So the
dashboard's *Period cost* chart and the editor canvas's *Period cost* sparkline are two drawings of
one array, and `network-charts.spec.ts` asserts identity (`toBe`) rather than value for exactly that
reason.

**Four of the six carry an overlay and two do not**, because `V6__run_timeseries_baseline.sql` records
`baseline_served_demand` and `baseline_cost` alone - the same rule the element charts follow one scope
down, with one footnote beneath the set rather than six repetitions of it.

**A running total prints no mean.** The mean of a monotone line is an artefact of where the horizon
ends, and `TOTAL_COST` - the last point of that same line - is already a card. Two numbers for one
quantity is the failure the shaded-area note on this page already documents.

### The two sizes, and the gesture

**Double-click enlarges a chart; double-click again returns it to the grid.** The state is a `Set` on
`RunResultsStore`, keyed by `chartId(scope, key)` so a node's `onHand` and the network's are two
charts, and it holds a *set* rather than one value because comparing period cost against unmet demand
at one cursor position is exactly the reading a grid exists to make possible. It is not reset when the
run changes: how a reader wants the page arranged is a preference about reading, not a fact about the
run - `PlaybackStore`'s reasoning for keeping the playback speed off the network row.

**The cell owns the gesture, not the chart.** The curve and the step charts are two components and one
gesture; putting `dblclick` on the container is what stops them growing two implementations of it.
Enter and Space sit beside it, because a chart a mouse can enlarge has to be enlargeable without one.

**Two sizes are two boxes, not one box scaled.** A 640-unit box rendered in a third of a column draws
its 10-unit axis text at a third of 10 pixels; a small chart therefore states its own smaller box in
user units, so the text stays the same size on screen and only the picture shrinks. A small chart also
thins its period labels to about four - `curve-geometry.periodTicks` still chooses *which* periods, so
enlarging a chart shows more of the same axis rather than a different one.

**What a small chart hides is prose, never a claim.** The explanatory sentence becomes the figure's
tooltip and is printed in full when the chart is enlarged. The fitted range, the gap count and the
absence sentence stay at both sizes, because those say what the run recorded - which is the FR-18
discipline the whole surface is drawn under.

### Tinting without a loop

The miniature carries **two** channels, and only two: a node's fill (its own on-hand
against its own horizon maximum) and every element's availability, as the red halo FR-16 chose for
that channel and FR-18 made temporal. No third was invented - flow has the arc width on the canvas and
a chart of its own here, and a third meaning on an 8-unit dot would make all three unreadable.

Stepping repaints only what changed, which is the canvas effect's compare-before-write discipline
expressed in a template rather than in an imperative pass:

- `miniMap` is a computed of its own, so **the layout is not rebuilt when the period changes** - every
  coordinate and every arrow polygon survives a step untouched.
- `fillScales` walks the horizon **once per run**; `cursorTints` walks none of it, one lookup per
  element. That split is `playback-channels.indexElements`' own.
- The tints are **rounded** to two decimals of fill and three of opacity, so a difference below a
  device pixel produces the same attribute value and therefore no write. `gaugeStops` rounds to whole
  percent for exactly this reason, at the resolution Cytoscape has.
- An element the run has no series for gets **no tint at all**, not a tint reading empty and fully
  available - `applyPlayback`'s rule for the canvas, applied to a dot.

The fill is drawn as a clip rather than as a gradient, and only the clip rect's `y` moves. Cytoscape
takes gradient stops as a string and SVG takes a clip; what the two surfaces share is the arithmetic,
which is the part that has to agree.

### Known limits, and why

- **The canvas's shape channel is not reproduced.** `echelon-rules.ts` gives each type a Cytoscape
  shape as a colour-blind-safe second channel; at an 8-unit radius the four are not distinguishable,
  so the miniature carries type as colour plus the legend, the label (on networks small enough to
  label) and the tooltip. The scope line names the selected element in words, which is the reading
  that has to survive without colour.
- **Node labels are drawn up to 14 nodes and hidden above it.** A four-echelon sample reads far
  better with `SUP-1` beside its dot; a hundred-node network reads as a wall of overlapping text.
- **The element series is fetched whole, not per element.** The API offers a single-element read, and on
  a thousand-node network over a long horizon that would be the better call. The whole-run read is
  what makes switching elements and scrubbing free, which is the trade FR-18 already took; the
  single-element form is the one to reach for when a network makes this response large.
- **A link's charts need its row from `GET /networks/{id}/links`**, because *uncapped* and *no capacity
  available* are two readings of one null and only the declared capacity separates them. A link whose
  structure read failed therefore falls back to the capped reading. The structure error is on screen
  when that happens.

## What is deliberately not here

The criticality table's data comes from the *topological* endpoint, not from the run:
`NODE_CRITICALITY` is a property of the network rather than of any run. It is fetched
once on open and never on refresh - the network is frozen for the whole life of a locking run, so its
structural metrics cannot have changed. A failure there is held apart from the run's own error,
because a criticality table that would not compute is no reason to withhold the results.

`results-export.service.ts` serves this feature *and* the comparison view. It repeats three small
decisions from `data-import/network-export.service.ts` - blob rather than link (the endpoint is
authenticated), filename from `Content-Disposition`, object URL revoked on the next tick - rather
than sharing them, because a service above both features would couple two things that otherwise have
no reason to know about each other.

**No playback.** The dashboard navigates a run; animating one is playback, and there is a plain
reason it stays in the editor: there is one `requestAnimationFrame` loop in this application, not two.
The period cursor FR-22 asks of this page is scrub and step - a position a reader moves, not a clock
that moves itself. There is no play button on this page and no timer behind it, and
`period-cursor.ts` has no `advance` function for the same reason.

**No cursor on the metric cards.** They are horizon scalars with confidence intervals, and a
horizon scalar has no per-period form; the charts are the series those scalars
summarise. An `AVG_INVENTORY` that changed as the cursor moved would be a number this run never
computed. The criticality table is unmoved by the cursor for the neighbouring reason - it is a
property of the network, not of any period of any run.

**No editing of the network the miniature draws.** Nodes are not draggable and no coordinate is ever
written back. The network is frozen for the life of a locking run, so an editable miniature
would offer a gesture the server refuses; the *Open network* button in the header is the way to the
editor.

## Testing

`curve-geometry.spec.ts`, `mini-map-layout.spec.ts`, `element-charts.spec.ts`, `network-charts.spec.ts`
and `period-cursor.spec.ts` are plain unit tests over pure modules, and `run-results.store.spec.ts`
drives the store against a fake `ApiService`. The four components added for FR-22 have no specs of
their own, for the reason the element inspector and the network dashboard have none: they are
composition over signals, and every number in them comes from a module that is specced one level down.

`network-charts.spec.ts` pins the readings rather than the shapes: which six series are charted and in
what order, that the fill rate is *not* among them (it is the curve), that the arrays are
`network-series.ts`' own objects rather than copies, that exactly served/cost and the two totals
derived from them carry an overlay, that a running total prints no mean, that money is money, that
nothing here can have an absence - `RUN_TIMESERIES` writes every period of every column - and that a
run with no series answers an empty list rather than six empty charts.

`mini-map-layout.spec.ts` pins the arithmetic that has to be right - the aspect-preserving fit and its
three degenerate cases (one node, one vertical line, coincident nodes), the whole-graph arrangement
choice, the deterministic column order, and the trimmed arc with its arrow head - plus the one
assertion that is not a number: that the colours come from `nodeTypeProfile` itself.

`element-charts.spec.ts` pins *readings* rather than shapes, against
`samples/four-echelon-playback/README.md` §6.5: which charts a node and a link get, that exactly
`onHand`, `served` and `flow` carry a baseline, that the on-hand mean is the 5.67 the element
inspector prints for `DC-1`, that the inbound lead's mean is flow-weighted (1.4 where a plain mean
would say 3), that a node nothing was ever dispatched to reads absent rather than 0, that an uncapped
arc's utilisation is a sentence and a capped one's gap is an outage, and that a `utilisation` of 0.0
at full availability stays a real zero.

`period-cursor.spec.ts` pins the three claims a cursor makes: where it may stand (clamped at both
ends, floored onto the band a fractional period sits in, total on a run with no periods), where it
opens (the last period, and 0 on a one-period run), and what the page reads at it - the run's own
fill rate rather than a second division, cumulative cost and unmet demand **through** the cursor
inclusive, nulls past the end of the series rather than the last period's numbers, each node filled
against its own maximum, the canvas's own underlay opacity for a struck arc, the rounding that stops
an unchanged pixel writing an attribute, and no tint at all for an element the run has no series for.

`run-results.store.spec.ts` gained the two schedules, the scope and the cursor: the structure read
once however often the run is re-read, no element request at all for a reader who never selects an
element or moves the cursor, exactly one for a reader who does either several times,
`available: false` answering with the editor's sentence and no error while the network scope keeps
its cards and its table, a failed read retried by the next click, the scope and the structure dropped
when a different run is opened, and - for FR-22 - the cursor opening on the run's last period with no
request spent on it, restated as "Period 3 of 4 - 3 days", absent on a run with no series, clamped at
both ends, preserved across every scope change and across a re-read, and re-defaulted when a different
run is opened.
