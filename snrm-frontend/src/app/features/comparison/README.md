# `comparison` - two readings of one question

This feature folder now holds **two** views, and the pairing is deliberate.

| | Route | Question | Requirements |
|---|---|---|---|
| **Metric matrix** | `/projects/:projectId/comparison` | How did these configurations *perform*? | FR-10 |
| **Side by side** | `/projects/:projectId/comparison/structure?ids=…` | How are these configurations *shaped*? | FR-25, FR-27, FR-31 |

Endpoints: `GET /projects/{id}/comparison?networkIds=…&scenarioId=…` and its `/export` for the
matrix; `GET /projects/{id}/networks`, `/networks/{id}/nodes`, `/links` and
`/networks/{id}/metrics/topological` for the side-by-side view.

The two views are kept distinct on purpose - the structural view points at the metric matrix for the
performance question it deliberately does not answer - so each says on screen that the other exists
and what it is for. The sections below the divider are the matrix; `side-by-side.component.ts` and
the two pure modules beside it are the structural view, and its own account is at the foot of this
file.

---

## The server decides three things, and this feature must not re-decide them

**Which cell wins.** Direction is the calculator's own declaration - `MetricCalculator.direction()`,
returning `HIGHER_IS_BETTER`, `LOWER_IS_BETTER` or `NEUTRAL` - so the registry can answer
it and a table of codes in the client cannot fall out of step. Ties are all marked rather than
broken, or the highlighting would depend on the order the columns were requested in.

**What unit the time-valued rows are in.** `TTR` is stored as a count of *its own network's* periods,
so two columns clocked differently hold numbers on different scales. The server converts every such
value to the finest period unit among the compared variants and sends the raw period
count beside it, so the converted figure stays traceable to the run.

**What is uneven about the comparison.** `mixedTimeBases` and `mixedScenarios` are the two ways a
comparison can be arithmetically correct and still misleading, and neither is visible in the numbers.
They arrive as `notes`, and the view shows them above the matrix rather than filtering the data -
comparing a daily model against an hourly one is legitimate, it just may not be silent.

Because the export is built from the same object, the spreadsheet and the screen cannot disagree
about any of the three.

## `NEUTRAL` is a decision, not a gap

A denser network is better connected *and* more expensive; a shorter average path is faster *and*
less redundant. Neither is an objective of the model, so `DENSITY`, `AVG_PATH` and `CLUSTERING`
have no winner, are shown without highlighting, and are **excluded from the radar** - an axis whose
outward direction means nothing is worse than a missing axis.

## The radar's normalisation is the chart's own, and is argued in `radar-geometry.ts`

Min–max across the compared set (there is no absolute scale for a cost), inverted on
`LOWER_IS_BETTER` axes so outward is always better, tied axes drawn at full radius rather than
dividing by zero, and a variant with no values at all omitted rather than plotted at the centre
looking like the worst in the study. The chart carries its own caveat on screen: a metric the
variants barely differ on still spans the full radius, so the matrix is the finding and the radar is
the shape of it.

## The lever annotations are the point of the view

`lever_changes_json` is the structured diff from the network a variant was forked from. A
matrix without it reports that variant 3 recovers two periods faster; with it, the matrix reports
that *+20% capacity at PLANT-1* recovers two periods faster - which is the claim a thesis can make,
and is what the column exists for. It sits under the header rather than in a tooltip so
a reader scanning for the winner gets the cause in the same glance as the effect.

The diff is free-form by design - the persistence layer stays agnostic of the lever
vocabulary and that vocabulary arrives in Phase 2 - so `flattenLevers` renders it generically
(dotted keys, joined arrays) rather than parsing it against a shape this build would have to invent.
It lives in `core/lever-changes.ts` because the project dashboard's provenance tree annotates each
fork with the same field, and one diff rendered two ways would be a defect nobody would notice.

---

## Networks side by side (FR-25, FR-27, FR-31)

`/projects/:projectId/comparison/structure?ids=3,4,5` - one pane per network, each drawing that
network with the **read-only miniature of FR-22** above its identity and its topological suite, all
sharing one **by-name element selection**. Up to **twelve** panes, each pane's suite
collapsible (FR-27), and a **checkbox per metric** above the grid choosing which of the suite the
panes print (FR-31). Reached from the FR-23 actions menu on the project
dashboard. `MANUAL-TEST.md` walks it.

Files: `pane-grid.ts`, `element-matching.ts` and `metric-visibility.ts` (pure, each with a spec),
`side-by-side.store.ts`, `side-by-side/` (the window) and `network-pane/` (one pane).

### The ids are in the URL, and that is what makes it a place rather than a handoff

Nothing is passed in memory. The dashboard routes to `/projects/5/comparison/structure?ids=3,4,5`;
from that moment the view is an ordinary address that survives a reload, can be bookmarked, and can
be mailed to a supervisor who will see the same panes.

It is a **navigation in place, not a second window**, and that is a deployment decision rather than
a preference: opening a window is a fresh document request, which only a server configured to fall
back to `index.html` can answer for a client-side route. `project-dashboard.openComparison` carries
the argument.

The consequence is that `?ids=` is untrusted input in the ordinary sense, and `parsePaneIds` answers
every way a link can be wrong without an error page: a network deleted since (the pane says so in
its own header), a token that is not a number, a repeat, or more ids than the window draws. A cap is
a cap and not a refusal - the first twelve are drawn and the rest are named.

### The cap is twelve, and the grid rule is unchanged

`columns = ⌈√n⌉`, `rows = ⌈n / columns⌉` - so two are left and right, four are a 2 × 2, three are two
over one, and twelve is **four columns and three rows**. The rule did not move; the number fed to it
did, twice.

**The cap was six first, argued twice**, and both arguments were right. Six is the largest n
whose grid stays within three columns and *two* rows, and a third row at any height a readable pane
can be is a row below the fold - the scrolling between configurations that this view exists to
replace. And at three columns a pane gets about what the results dashboard gives its own miniature
(`col-xl-4`), the size that drawing is known to read at, with its 9-unit node labels legible; a
fourth column puts them at the limit.

**Ten answered that argument rather than deleting it**, because it assumed a pane always carries its
metrics and FR-27 removes the assumption. What the fourth column and the third row cost is stated in
`pane-grid.ts` where they are chosen: smaller panes, a last row that scrolls, and one
`GET /networks/{id}/metrics/topological` per pane - `NODE_CRITICALITY` being one maximum-flow
computation per node, which is why the suites are fetched one at a time. What pays for it
is the collapse: a collapsed pane is a title and a miniature, and a wall of shapes is the readable
form at this count, with the numbers one click away for the two or three panes a reader is actually
weighing.

**Twelve then replaced ten and moved nothing else**, because ten and twelve draw the *same grid*.
Four columns and three rows is what ten already paid for and it has twelve cells; ten filled ten of
them and left the bottom row ragged, which reads as a window that ran out rather than one that was
capped. No new column, so the labels are no smaller; no new row, so nothing is further down; two
more suites, which is the only marginal cost there is. **Thirteen is the first count that takes a
fourth row** (⌈√13⌉ = 4, ⌈13/4⌉ = 4) - the same argument reaching its next limit - and that is what
the cap sits against now. `pane-grid.spec.ts` asserts the whole of that in two lines:
`paneGrid(10)` equals `paneGrid(12)`, and `columns × rows === PANE_LIMIT`.

The cap lives in `pane-grid.ts` because **two surfaces state it**. FR-25 asks for it to be said where
the menu offers the action, not only after the window opens, so `compareBlocker` is what the menu
renders and `PANE_LIMIT` is what the route enforces. Two copies of a limit is how a limit comes to
differ from itself.

### The suites collapse, and the default follows the count (FR-27)

**Each pane's suite has its own disclosure**, a real `<button>` with `aria-expanded` and an
`aria-controls` naming the region it opens - the semantics `network-actions-menu`'s toggle already
uses here. It is the *suite's* header rather than the card's, because **the miniature never
collapses**: it is what the window exists to show, and a control beside the name and badges would
read as offering to fold the shape away too. A collapsed pane is a title, a miniature and a closed
heading.

**One control above the grid moves all of them**, and its label names the state it will *produce*:
`Collapse all` normally, `Expand all` once every pane is collapsed. **A mixed window reads
`Collapse all`** - pressing it must always take every pane somewhere, and a control that flipped each
pane would leave the reader working out which half moved. That is a rule about wording, so it is a
pure function with a spec (`collapseAllControl`) rather than a ternary in a template, for the reason
`network-selection.ts` gives for its own sentences.

**The default is derived from the grid rule, not typed beside it.** `suitesExpandedByDefault(count)`
is `paneGrid(count).rows <= 2` - the rule "expanded up to six panes" *is* "within two rows", which
is the same argument the old cap was chosen by. Written as a literal six it would be a second rule
free to drift the next time either moves; written this way the hinge follows the layout, and the spec
pins that the answer today is still six. **The cap has moved twice since and the hinge has not moved
at all**, which is the derivation earning its keep rather than a coincidence: it was never a count of
networks.

**The state is the window's, and there is no `localStorage`.** It lives on `SideBySideStore`, which
dies with the route. This window is opened per comparison from the dashboard's menu, on a set of
networks chosen for one reading, so a collapse remembered across openings would be a preference
nobody set - the next comparison would open in a shape its reader never asked for. That is the
opposite call from the playback speed, which *is* remembered, and for the opposite reason:
a speed applies to a network the researcher returns to, and this does not. The window says so in its
footer as well as in the store.

**Collapsing fetches nothing and cancels nothing.** The suite is requested when the window opens
whatever the pane looks like, and the collapsed region is `hidden` rather than removed - so
expanding is a disclosure of numbers already held, never a spinner as the reward for a click. A
collapsed pane that skipped its request would arrive empty at the moment it was opened, which is the
one way this feature could have made the larger cap *worse* than the old one.

### Which metrics the panes print, chosen once for the window (FR-31)

**A checkbox per metric above the grid, with `Select all` and `Select none` beside it**, and the
choice reaches every pane at once. One set for the window rather than one per pane, for the reason
the element selection is one key rather than twelve: a comparison in which pane 3 prints a figure
pane 4 has hidden is not a comparison. The rules are `metric-visibility.ts`, pure and specced.

**The boxes are built from the codes the panes returned**, not from a list written in the client.
Calculators are discovered at runtime, so a build whose backend has a tenth calculator
offers a tenth box, and a window whose panes answered with six of the nine known codes offers six
rather than three that govern nothing. The consequence is that the list grows as the suites land -
they arrive one pane at a time - which is exactly why the **stored state is the *hidden* set**: a
code nobody has decided about arrives shown. Storing what is *shown* would have made a metric the
reader has never seen invisible by default, which is a filter making a decision on their behalf. It
is `collapsedSuites`' shape and `collapsedSuites`' argument, one signal along.

**Two controls, where the collapse has one, and that is deliberate.** `collapseAllControl` is a
single button naming the state it will produce, because a window is normally uniform and one press
has to take every pane somewhere. A filter is the opposite - **mixed is its working state**, five of
nine ticked is what using it looks like - and from there a reader wants both "show me everything
again" and "clear it, I will pick two" at one press each. A single toggling control would have made
one of those two presses, and it would have chosen against whichever the reader wanted. Each is dead
exactly where it would do nothing, and says so.

**`NODE_CRITICALITY` is in the list, and it governs the *Most critical nodes* table.** That metric is
per-node, so it is never one of the network-scoped rows beside it - `networkScoped` filters it out
and `topCriticality` picks it up - and a filter reaching only the rows would have left the largest
block of figures in every pane outside the control that claims to choose which metrics are shown.

**The checkbox order is the row order, and it is one comparator.** `compareMetricCodes` sorts both
the boxes and `networkScoped`'s rows, so the third box down governs the third row down; two orders
would be a filter nobody could aim. The rank inside it is `TOPOLOGICAL_METRIC_CODES` - the backend's
`@Order` transcribed - with an unknown code last rather than dropped, and the **tie-break by name**
is what stops "last" from meaning two different things on the two surfaces.

**Filtering fetches nothing, exactly as collapsing fetches nothing**, and the footer says so: a pane
holds its whole suite from the moment the window opened, so unticking `DENSITY` makes no request
cheaper and ticking it back costs none. The state is the window's - no `localStorage`, cleared for a
new set of ids, and *kept* across **Try again**, which is the same split the collapse makes and for
the same reason: a retry is the same ask on the same networks.

**It is not in the URL, and that was a choice.** The ids are the window's *subject* and have to
survive a bookmark; which metrics are on screen right now is an arrangement of the reading, like the
collapse, and every tick would otherwise push a history entry to press Back through. If sharing a
filtered reading turns out to be wanted, a `?metrics=` parsed by the same pure module is the shape of
it.

**A pane that has numbers and is showing none of them says why**, in a sentence distinct from "no
structural metrics were returned for this network": one is a fact about the network, the other the
consequence of a control the reader is holding, and reporting the second as the first would blame the
data for the filter.

### The matching rule is the feature, and it is by name

Ids do not survive a fork: `POST /networks/{id}/clone` makes new `NODE` rows, so `DC-1` in
the baseline and `DC-1` in the variant are two ids for one thing. `uq_node (network_id, name)` is
what makes the name well defined - at most one node per network, so a match is a node and never a
set. A link has no name of its own, so its identity is its **two endpoint names**, which is what the
XML document and the archive's event targets already resolve on; direction is part of it,
because the network's arcs are directed and a reversed arc is a structural change rather than a match.

Matching is **exact**. `DC-1` does not match `dc-1`, because the archive resolves the stored string
and a looser rule here would let one screen claim two elements are the same when nothing else in the
tool would. The cost is a false absence when two variants were imported from differently capitalised
files, so that case is named rather than passed over: the pane says "…though it has `dc-1`, which
differs only in case", and likewise for a reversed arc.

Everything else follows from there. A pane with no match says **"DC-1 is not in Baseline v3"** -
naming the configuration and not the pane, because the absence is the finding. The header tallies
*in 3 of 4*, counted over the panes whose structure has loaded, so the number does not flick as the
last request lands.

### Read once, and never on a selection

Four reads per window plus three per pane, all on open: the project (its name), the project's
networks (every pane's identity in one request, and the answer to whether an id in the link is still
one of them), then each pane's nodes and links **in parallel** and each pane's topological suite
**one at a time**. The sequencing of the suites is the deliberate part - twelve maximum-flow-per-node
computations at once would make every pane slow rather than the first pane fast, the same judgement
`NetworksStore.deleteMany` and `ProvenanceStore` make for their own loops, with the same escape (a
project-scoped suite endpoint would replace the loop and change nothing on screen). The raised cap is
what turns that from tidy into load-bearing.

Selecting an element re-reads **nothing**, and neither does collapsing one or hiding a metric. The
selection is a name,
the panes already hold every node and link to match it against, and a structural suite is a property
of the network rather than of what is selected in it. `TopologicalMetricsStore` states the discipline
for the editor, where edits can invalidate a suite; here nothing can, so nothing recomputes.

### The miniature is imported, not copied

`simulations/network-inspector` draws every pane. FR-25 asks for the FR-22 miniature by name and for
Cytoscape to stay out of this bundle for the reason it stays out of the dashboard's, so a second
implementation would have been a second place for both decisions to drift. What the import needed -
a component that draws *a* network rather than *the run's* - was done **in place**: the network, the
selection and the tints are inputs now, every one of them defaulting to what the dashboard was
already doing, and `results-dashboard.component.html` passes the signals the component used to reach
in and take. A pane passes no element series and no note, so its dots draw plain: there is no run
behind a structural comparison, and a tint would be a claim about a simulation nobody ran.

### Nothing here writes

No edit, no run, no delete, no `POST`, `PUT`, `PATCH` or `DELETE` anywhere in the feature - a
disclosure is not an exception, since it changes what is on screen and nothing about a
configuration. The frozen
badge on a pane is information about a configuration, not a gate - there is nothing in this
window a freeze could refuse. It is also why `SideBySideStore` reads the network list itself instead
of sharing `NetworksStore`, which owns the dashboard's selection and its `deleteMany`: a read-only
screen should not have those one injection away.
