# Results dashboard - manual test script (FR-22)

The element-aware dashboard: a read-only miniature of the run's network in the top-left corner,
clicking a node, a link or empty space to scope the page, an element's per-period series as full
stepwise charts in place of the curve, and - since part two - one **period cursor** threaded through
every chart, every per-period figure and the miniature's tints.

Its own numbering, starting at 1: this covers `features/simulations`, where
`../network-editor/MANUAL-TEST.md` covers the canvas and the element inspector. Where a step asserts
the same figure as one of that script's, it says so - the two surfaces read one run, and the whole
point of sharing `sparkline-geometry.ts`, `network-series.ts`, `playback-channels.ts` and the palette
is that they cannot disagree.

Sections A–B are the inspector and the scope gesture. Sections C–D are a **baseline** run read
element by element, and every figure they assert is derived by hand in
`../../../../snrm-backend/samples/four-echelon-playback/README.md` §6.5. Section E is
the **disruption** of that document's §8, which is where an element scope earns its place: the outage
sits on one arc and the loss appears one period later at the customer. Section F is the absences -
the three that are sentences rather than zeros. **Section G is the period cursor**, and step 39 is the
one to run if you only run one: scrubbing across the disruption window with every surface moving in
lockstep. **Section G′**: every per-period series charted, and the double-click that enlarges
one. Section H is what the scope and the cursor deliberately do *not* change. Section I is
housekeeping.

## Setup

**S1.** Backend running: `mvnw.cmd spring-boot:run` in `../snrm-backend`.
→ <http://localhost:8080/actuator/health> returns `{"status":"UP"}`.

**S2.** Frontend:

```bash
npm start
```

→ Compiles clean; the app serves at <http://localhost:4200>.

**S3.** Sign in, create a project named `Dashboard Test`, press **Products** and add `WIDGET-A` with
unit value `20`.
→ The amber "no products yet" banner clears.

**S4.** Press **Import network (CSV · Excel · XML)** and import
`../snrm-backend/samples/four-echelon-playback/network.xml`, naming it `Playback`.
→ The wizard reaches step 3 with no errors and the network opens in the editor: `SUP-1`, `PLANT-1`,
`DC-1`, `CUST-1` in a chain, each at the coordinates the document carries.

**S5.** In the editor's run panel, run a **baseline** (no scenario) and wait for `DONE`. Then press
**Full dashboard ↗**.
→ `/projects/{p}/simulations/{runId}`. Keep the devtools **Network** tab open from here on: several
steps assert on which requests are made and which are not.

---

## A. The network inspector (FR-22)

> **Regression note (FR-25).** This component is now input-driven - `features/comparison`'s
> side-by-side window draws one per pane - and every input defaults to what this page was already
> doing. Section A is therefore the check that the generalisation changed nothing here: the legend,
> the scope line, the arrangement note and the four tint sentences must all read exactly as they did
> before, and sections B and G must behave identically.

**1.** Look at the top-left card, headed **Network**.
→ A miniature of the four-echelon chain: four dots and three arcs, `SUP-1` to `CUST-1`, in the same
left-to-right arrangement the editor canvas shows. Under it, a four-swatch legend (Supplier, Plant,
DC, Customer) and the line *"Showing the **whole network**. Select a node or a link to read its
series."*

**2.** Compare the dot colours with the editor's canvas, side by side if you can.
→ Identical: slate supplier, purple plant, teal DC, amber customer. Both surfaces read
`echelon-rules.NODE_TYPE_PROFILES` - the miniature exists to be recognised as the network you
arranged, and a second palette would defeat that.

**3.** Check the Network tab for what drew it.
→ Exactly two requests, `GET /api/v1/networks/{n}/nodes` and `GET /api/v1/networks/{n}/links`, both
on page open. **No `GET /simulations/{runId}/timeseries/elements`** - see step 12.

**4.** Look at an arc.
→ It stops short of both dots and ends in a solid arrow head pointing at the target. The direction is
drawn rather than implied: arcs are directed, and which way material moves is most of the
reading.

**5.** Hover a dot, then an arc.
→ `SUP-1 - SUPPLIER` and `SUP-1 → PLANT-1` as tooltips. On a network this small the names are also
drawn above the dots; above fourteen nodes they are on hover alone, or the labels collide.

**6.** Press **Refresh** in the page header, then watch the Network tab.
→ `GET /simulations/{runId}` goes again, and **neither** `/nodes` nor `/links` does. A completed run
freezes its network, so its structure cannot have changed under this page.

**7. The fallback arrangement.** Import the same file again as `Playback (unplaced)`, then use the
editor to clear the coordinates - or, faster, import a CSV pair with no `pos_x`/`pos_y` columns -
run a baseline against it, and open its dashboard.
→ The miniature draws four columns left to right in echelon order, each column centred on its middle
row, and the grey line *"Some nodes carry no canvas coordinates, so this is laid out by echelon -
supplier to customer, left to right. It is computed here and never saved."*
→ Re-open the same dashboard: the picture is **identical**, not reshuffled. Rows within a column are
ordered by name so one network draws one way.
→ Open that network in the editor afterwards: its nodes still have no coordinates. The fallback is a
drawing, never a write - check the Network tab if you want it in writing, there is no
`PATCH …/nodes/positions`.

---

## B. Scoping the page (FR-22)

**8.** Back on the `Playback` baseline dashboard, click the `DC-1` dot.
→ Three things at once: a blue ring around the dot, the line under the miniature changing to *"Scoped
to **DC-1** - show the whole network"*, and the **whole right-hand column** replacing the performance
curve with a block headed `DC-1` and a grid of charts.
→ The metric cards, the criticality table and the structure strip are **unchanged**. The scope
switches one column.

**9.** Click empty space inside the miniature.
→ Back to the performance curve, the ring gone, the line back to *"Showing the whole network"*. The
same is true of the **Whole network** button at the top of the element block.

**10.** Click a criticality row in the **Node criticality** table.
→ That row gains a blue left bar and a tinted background, the matching dot in the miniature gains its
ring, and the element charts open for it. One selection, two representations - the row and the dot
write the same signal, so they can never name two different nodes.

**11.** With a node scoped, click its row in the criticality table again, then press <kbd>Tab</kbd>
to a dot and hit <kbd>Enter</kbd>.
→ Both gestures work and neither double-fetches (step 12). The dots, the arcs and the background are
all keyboard-reachable buttons with a visible focus ring.

**12. The element series is fetched once, at the first gesture that needs it.** Clear the Network tab,
then: click `DC-1`, then arc `DC-1 → CUST-1`, then empty space, then `CUST-1`.
→ **Exactly one** `GET /api/v1/simulations/{runId}/timeseries/elements`, on the first click. The whole
horizon is in memory afterwards, so every later selection costs nothing.
→ Reload the page and neither scope an element **nor touch the period cursor**: the request never
happens. It is `horizon × (nodes + links)` numbers - the reason it is kept out of
`GET /simulations/{runId}` - and a reader who only wants the curve should not pay for it.
→ Reload again and this time drag the cursor's slider from end to end without clicking any element:
**one** request, on the first movement, not one per period. The tints are the one thing on the page
that needs the series, so moving the cursor is a third gesture that asks for it - see step 37.

---

## C. A node, read period by period (§6.5, baseline run)

**13.** Scope to `DC-1` and read the chart grid top to bottom.
→ Eight charts in this order: **On hand, In transit, Arrivals, Served, Unserved, Throughput,
Availability, Inbound lead**. Served and unserved are one quantity but are drawn as two charts,
because only one of them has a baseline in the schema (step 16).

**14. The inventory filling and draining.** Look at **On hand**.
→ A **step** line, not a smooth one: flat across each period's band with a vertical riser where the
value changes. It starts at **15** for two periods and drops to **5** for the rest of the horizon -
`DC-1` holding 15 through periods 0–1 because `CUST-1` opened at its target and ordered nothing
(§6.5.1), then settling on its steady state.
→ The caption reads **5–15 over 30 periods**, the value axis is labelled `15 / 10 / 5` top to bottom,
and the period axis runs 0 … 29 under it with the label **Period (1 day)**.
→ The header reads **mean 5.67**, and a faint dotted line crosses the chart at that height. It is the
same 5.67 the editor's element inspector prints for `DC-1` (`../network-editor/MANUAL-TEST.md` §U),
because both come from `seriesMean` over the same array.

**15.** Scope to `SUP-1`, then `CUST-1`, reading **On hand** each time.
→ `SUP-1`: 10, 10, **20**, then 10 for the rest - the pulse travelling back up the chain - with
**mean 10.33**.
→ `CUST-1`: **10** in period 0 and **0** thereafter, **mean 0.33**. That is a pass-through, not a
shortage: it receives 10 and serves 10 every period, which the Served chart shows flat at 10.

**16. The overlay, and the charts that honestly have none.** On `DC-1`, look at **On hand** and
**Served**, then at the other six.
→ On-hand and Served each carry a **dashed grey line running exactly under the solid one**. This is a
baseline run, so `V9__element_timeseries.sql` copies the run's own series into the baseline columns
(§6.5.5) and the two coincide **by definition** - an absent overlay here is the defect, a coincident
one is correct.
→ The other six have no dashed line, and one grey footnote sits under the whole grid: *"Charts without
a dashed overlay have no undisrupted twin to draw: the run's element table records a baseline for
on-hand, served and flow alone…"*. Said once, not eight times, and never faked by overlaying a series
on a copy of itself.

**17. Throughput is 0 at three of the four nodes, and that is correct (§6.5.1).**
→ `DC-1`, `PLANT-1` and `CUST-1` show a flat line at 0 across the whole horizon, drawn **through the
middle** of the plot with the caption `0 over 30 periods` - one number, because there is one, and a
single value tick beside the line. A flat series has no range, so a line at the top would read as a
maximum and one at the floor as an empty node.
→ `SUP-1` shows **10 in every period except period 3**, where it is 0 - it was already full - with
**mean 9.67**. The chart's own hint says what the column is: flow across the node's *own capacity
arc*, which is production at a supply origin and pass-through elsewhere.

**18. Arrivals, and the offset a reader is most likely to call a bug (§6.5.3).** On `CUST-1`, read
**Arrivals**, then scope to the arc `DC-1 → CUST-1` and read **Flow**.
→ Arrivals: **0, 0, then 10** for the rest. Flow on arc **c**: **0, then 10** for the rest - one
period earlier. A ribbon leaving in period *t* is a bar arriving in period *t+1*, and the chart's hint
says so rather than leaving the reader to find it.

**19. Availability on an undisrupted run.**
→ Flat at **100.0%** for every node and every arc, captioned `100.0% over 30 periods`. The axis is
written as percentages because the quantity is a ratio.

---

## D. A link, read period by period (§6.5.2, baseline run)

**20.** Scope to the arc `DC-1 → CUST-1`.
→ The block is headed `DC-1 → CUST-1`, and there are **three** charts: **Flow, Utilisation,
Availability**.

**21.** Read **Flow**.
→ **0 in period 0**, then 10 for the remaining twenty-nine - the rung whose downstream neighbour
opened at its target and asked for nothing. **mean 9.67**, and the dashed baseline runs exactly under
it (§6.5.5 again).

**22.** Read **Utilisation**.
→ **0.0%** in period 0 and **10.0%** thereafter, against a capacity of 100. Note the 0: an idle arc at
full availability is *idle*, and the engine sends a real 0.0 rather than a null (§6.5.2). There is no
gap in the line and no absence sentence under it.

---

## E. The disruption, read at element scope (§8)

**23.** Back in the editor for `Playback`, author the scenario of §8.1 - target the **link**
`DC-1 → CUST-1`, start offset `10 d`, duration `3 d`, severity `1.0`, profile `STEP` - run it, and
open its dashboard. (`../network-editor/MANUAL-TEST.md` step 185 is the same scenario.)
→ The page opens on the network scope with the resilience triangle shaded on the curve, exactly as
before FR-22.

**24. The outage, on the arc it struck.** Scope to `DC-1 → CUST-1` and read the three charts.
→ **Availability**: 100.0% until period 9, **0.0% in periods 10, 11 and 12**, back to 100.0% from
period 13. Two values and two vertical risers - `STEP` holds availability at `1 − severity` for the
whole window and returns it at once.
→ **Flow**: 10 until period 9, **0 in periods 10–12**, then the recovery surge - **20 in period 13**,
double a steady-state shipment and the largest single flow in the run - **0 again at period 14**,
where the surge is still landing and the customer has nothing left to ask for, and 10 from period 15
on. The dashed baseline stays flat at 10 throughout, so the gap between the two lines *is* the
disruption.
→ **Utilisation**: the line **breaks** over periods 10–12 rather than dropping to zero, and under the
chart: *"**3 of 30 periods** - A gap is a period in which no capacity was available…"*. A dark arc is
not an idle one, and the engine sends `null` for exactly that reason. Period 13 reads
**20.0%**.

**25. The loss lands one period later, and at another element.** Scope to `CUST-1` and read
**Served** and **Unserved**.
→ Served: 10 through **period 10** - the units served then were dispatched by `DC-1` in period 9, and
in-flight material is never stopped - then **0 in periods 11, 12 and 13**, then 10 again. The dashed
baseline stays at 10 across all four, which is the resilience triangle drawn at element scope.
→ Unserved: 0 everywhere except **10 in each of periods 11, 12 and 13**, with no overlay and the
footnote explaining why.
→ Put beside step 24: the **event window is 10–12 and the loss window is 11–13**. Two different
intervals, on two different elements, which is the whole visual argument of the sample (§8.7) and the
thing a whole-network curve alone cannot show.

**26. The blockage backing up the chain.** Scope to `DC-1` and read **On hand**.
→ 5 until period 9, **15 at period 10**, **25 at periods 11 and 12**, back to 5 at period 13. The
dashed baseline holds flat at 5 the whole way: the stock is the disruption, and the overlay is what
makes that legible rather than a number to remember.
→ Scope to `SUP-1`: its on-hand is drained to **0 at period 15** - the only period in either run where
a node is empty - and refills to 20 at period 16.

**27. The absent inbound.** On `CUST-1`, read **Inbound lead**.
→ The line sits at **1 period (1 day)** wherever arc **c** dispatched, and **breaks** where it did not
- period 0 on the baseline run, and periods 10, 11 and 12 here as well. Under it: *"**4 of 30
periods** - A gap is a period in which nothing was dispatched toward this node…"*.
→ The header reads **dispatch-weighted mean 1 period (1 day)**, not a plain mean: `inboundLead` is
already dispatch-weighted within a period, and averaging the per-period figures unweighted would give
a period that moved one unit the same say as one that moved a hundred. It is the same figure the
editor's inspector prints as *Average lead time*.

---

## F. Absences that are sentences, never zeros (FR-18)

**28. A node nothing is ever dispatched to.** Scope to `SUP-1` and read **Inbound lead**.
→ **No chart**, and the sentence *"This run recorded no value for this series in any period - absent,
never zero"*, followed by the same explanation the gaps carry. A supply origin has no inbound arc at
all, so its inbound lead is
undefined in every period of every run this network can produce (§6.5.4) - and a flat line at 0 would
say material arrived here instantly, which is a different and false statement.

**29. An uncapped arc.** In the editor for `Playback`, take the fork the frozen banner offers (**Fork
a variant**), clear arc `DC-1 → CUST-1`'s capacity in the property panel - leave the field empty, which
routes through `PUT /links/{id}` - run a baseline on the variant, and scope its dashboard to that arc.
→ **Utilisation shows no chart at all**, and in its place: *"Uncapped - this arc has no declared
capacity, so there is no fraction for it to be at. A 0% here would say it was idle."*
→ Flow and Availability are drawn as usual. The distinction is the point: *uncapped* and *no capacity
available* are two different claims about an arc, and both arrive as the same `null`.

**30. A run with no element detail at all.** Take a run recorded before `V9__element_timeseries.sql`,
or force one - devtools → Network → right-click any request → **Block request URL**, pattern
`*/timeseries/elements` - then open its dashboard and click any node.
→ The miniature works, the selection works, and where the charts would be:
*"element detail unavailable for this run (recorded before V9)"* - or, for the blocked read,
*"element detail could not be read for this run"* - followed by *"The whole-network scope is
unaffected…"*.
→ **No error banner, no zeros, no empty axes.** This is the editor's wording, verbatim from
`core/element-series.ts`: three surfaces now say it, and a reworded copy would read as a third,
different problem.
→ Click empty space: the curve, the metric cards, the criticality table and the structure strip are
all **completely usable**. Withdrawing a working feature because a newer one has no data would be the
worse answer.
→ Unblock the URL and click an element again: the read is **retried** and the charts appear. A failed
read may succeed; `available: false` is a durable fact about the run and is not asked about twice.

---

## G. The period cursor (FR-22)

One cursor for the page: every chart carries its line at the same period, every per-period figure is
read at it, and the miniature is tinted with that period's availability and fill. Steps 31–38 are on
the **baseline** run of section C; step 39 is the disruption run of section E and is where the whole
feature earns its place.

**31. It is there, and it opens at the end of the run.** Open the `Playback` baseline dashboard.
→ A band across the page above the panels: ⏮, a slider, ⏭, the readout **Period 29 of 30 - 29 days**,
and an **End of run** link (disabled, because that is where it already is).
→ Under it, one line: *"One cursor for the whole page: every chart carries its line at this period…"*
→ The slider is at its right-hand end. That is deliberate: every metric card on this page is a horizon
figure, so opening at period 0 would have the per-period column describing the warm-up while the cards
described the whole run. The end state is what "the result" means until you scrub.

**32. The readout is the run's clock, not an index.** Read it, then drag the slider to the middle.
→ **Period 15 of 30 - 15 days**, never a bare `15`. The same 30-period horizon is a month on this
network and 30 hours on one stepping in hours, and the restatement is the **run's** own
period length, so a later time-base change on a forked variant cannot relabel this run.
→ It is the same sentence the editor's playback transport prints for the same period of the same run
(`../network-editor/MANUAL-TEST.md` §T) - one string, `core/metric-display.periodReadout`.

**33. The three controls, and the two that are missing.**
→ ⏮ and ⏭ step exactly one period and disable at the ends. The slider moves it anywhere. **End of run**
returns to the last period.
→ There is **no play button and no speed select** anywhere on this page. That is the design, not an
omission: the dashboard navigates a run and animating one is playback on the editor canvas - one
`requestAnimationFrame` loop in the application, not two. If you ever see a period advance
on its own here, something has grown a timer.

**34. The arrow keys, and where they must not fire.** Click on empty page background, then press
<kbd>←</kbd> and <kbd>→</kbd> a few times.
→ The cursor steps one period per press, from anywhere on the page - including with the page scrolled
down to the criticality table, which is why the keys are on the page rather than on the bar.
→ Now click **into the slider** and press <kbd>←</kbd>: it moves by exactly **one** period, not two.
The page-level listener excludes form controls (`core/text-entry.ts`, the editor's own guard), so the
browser's native range handling is the only thing acting.
→ Press **Delete run**, and with the typed confirmation open press <kbd>←</kbd> and type the phrase.
→ The cursor does **not** move - neither from the arrow key nor from any character of the phrase.
Cancel the dialog.

**35. Every chart carries the line, at the same period.** Put the cursor at period 15, then look at
the performance curve; scope to `DC-1` and look at all eight charts; scope to arc `DC-1 → CUST-1`.
→ A grey vertical line on the curve at period 15's own x-tick, and one on **every** chart of the
element scope. On the step charts it falls through the **centre** of period 15's band - a period is an
interval, so its marker is not on an edge - which is the same rule the period axis is labelled by, so
each line falls through its own tick.
→ Count them if you like: eight charts on a node scope, three on a link scope, all at one period.
Any chart whose line sits a band to the side is the failure this guards against - the same period in
two places on one screen.

**36. Every figure restates itself.** With the cursor on the **network** scope, read the figures under
the chart grid, then step ⏭ once.
→ Seven of them: **Fill rate, Served / demand, Period cost, Cost to this period, Unmet demand to this
period, Total on-hand, In pipeline** - and every one changes with the step, under the line *"Read at
Period 16 of 30 - 16 days"*.
→ *Cost to this period* is inclusive of the period on screen, and at period 29 it equals the run's
`TOTAL_COST` card. *Unmet demand to this period* is likewise a running total, and its hint says why it
is cumulative: the engine carries no demand forward, so nothing later in the run makes it up.
→ These are the same seven the editor's canvas dashboard prints at its playback clock
(`../network-editor/MANUAL-TEST.md` §V), from the same module - put the two screens on period 16 of
this run and they must agree digit for digit. Two labels read differently on purpose: what the canvas
calls *Cumulative cost* and *Cumulative unmet units* is named *to this period* here, because here the
reader chooses where the total stops.

**37. The map tints at the cursor.** On the baseline run, watch the miniature as you scrub.
→ Before you touch anything the dots are plain and the line under the legend reads *"Move the period
cursor to tint this map with each period's availability and fill."* After the first movement the dots
fill: each node is filled bottom-up to its own on-hand against its **own** horizon maximum, in the
darker form of its type colour. The hue never changes, so the echelon stays readable while the stock
moves.
→ `DC-1` is a fifth full through most of the run and full at its own peak. Two dots at the same height
are **not** holding the same quantity - the gauge answers "how full for it", exactly as the canvas's
does (`playback-channels`), and the charts answer "how much".
→ No halo anywhere: nothing is struck on a baseline run.

**38. Scope changes do not move it.** Put the cursor at period 12, then click `DC-1`, then the arc,
then empty space, then a criticality row.
→ The readout stays **Period 12 of 30 - 12 days** throughout. Changing what the page is about is not
changing which period it is showing, and comparing an element against the whole network *at one
period* is what the scope is for.
→ Press **Refresh**: the run re-reads and the cursor stays at 12. A poll settling while a run finishes
must not throw a reader out of the window they are looking at.

**39. The disruption, scrubbed. (The step to run if you run one.)** Open the disruption run of step 23
and put the cursor at period 9, then step forward one period at a time to period 16, watching the
whole page.
→ **Period 9** - nothing struck: the arc `DC-1 → CUST-1` is plain, `CUST-1` is holding, the curve's
cursor sits at 100% fill, and the figures read served 10 of 10.
→ **Periods 10, 11, 12** - the struck arc **dims and gains a red halo** on the miniature, the same
channel and the same colour the canvas draws an outage in (FR-16's halo, made temporal by FR-18).
`DC-1` visibly **fills up** as the blockage backs stock into it - 5 → 15 → 25 - while `CUST-1` drains.
→ **Period 11 onward** - the loss lands: the curve's cursor is now under the shaded triangle, *Fill
rate* reads 0.0%, *Unmet demand to this period* starts climbing 10, 20, 30. Note the offset - the
**event window is 10–12 and the loss window is 11–13** - and note that both are on screen at once,
which is the argument of the sample's §8.7 and the thing a whole-network curve alone cannot make.
→ **Period 13** - the arc's halo is **gone** and the flow chart's recovery surge (20) is at the cursor.
The dot recovers as the number does; nothing lags.
→ Now scope to the arc and step back and forth across period 12 with <kbd>←</kbd>/<kbd>→</kbd>.
→ The **Utilisation** chart's at-cursor figure reads *no capacity available* - three words, not 0% -
and the line is broken there rather than dipped to the floor. Scope to `CUST-1` and do the same over
period 0: **Inbound lead** reads *no inbound this period*, never 0. Those are the element inspector's
own words for the same two absences (FR-18).
→ Every one of those steps repaints the map, the eight charts and the seven figures with **no**
animation: the picture only ever changes when you move.

**40. A run with no element detail keeps its cursor.** Repeat step 30's blocked read, then move the
cursor.
→ The transport works, the curve's line moves, all seven network figures restate themselves - they
come from `RUN_TIMESERIES`, which every completed run has - and the miniature stays **untinted** with
*"element detail unavailable for this run (recorded before V9)"* (or *"…could not be read…"*) followed
by *"The map is drawn untinted; every chart and figure on the page still moves with the cursor."*
→ The first sentence is **verbatim** the one part 1 showed and the one the editor shows. Three
surfaces, one situation, one phrasing (`core/element-series.ts`).

**41. A run that has no periods to move through.** Open a `QUEUED` or `RUNNING` run's dashboard
(submit one from the editor and follow the link immediately).
→ **No transport at all**, and the miniature says *"This run has no periods to move through yet, so
the map is drawn untinted."* A scrub bar over a declared horizon whose series is not written would be
a control that answers nothing.
→ When it finishes and the page refreshes itself, the transport appears and opens at the last period.

---

## G′. Every series charted, and the double-click

**41a. Seven charts, not one.** Open the `Playback` baseline dashboard and look at the
**Performance over the horizon** card.
→ A grid of charts: the **fill-rate curve**, enlarged and spanning the card, and beneath it six small
stepwise charts - **Served demand, Period cost, Cost to date, Unmet demand to date, Total on-hand, In
pipeline**. That is every per-period series `RUN_TIMESERIES` records for this run.
→ Under the set, one grey footnote: *"On-hand and pipeline have no dashed overlay…"*. Said once, not
twice, and never faked by overlaying a series on a copy of itself.
→ **Served demand** and both cost charts carry a dashed overlay; on this baseline run it runs exactly
under the solid line, which is correct (§6.5.5) - a baseline run *is* its own baseline.

**41b. The figures agree with the charts.** Compare the small **Period cost** chart against the
*Period cost* figure below the grid at the same cursor period.
→ Same number. Both come from `network-series.ts`, which is also what the editor canvas's live block
draws - one derivation, three surfaces (`../network-editor/MANUAL-TEST.md` §V).

**41c. Cost to date reads as a total, not as a mean.** Look at the **Cost to date** chart's head.
→ It prints **no mean and draws no dotted reference line**, unlike the other charts. A running total's
mean is an artefact of where the horizon ends, and its last point is already on screen as the
`TOTAL_COST` card. Its line climbs and never comes down; so does **Unmet demand to date**.

**41d. Double-click to enlarge.** Double-click the small **Total on-hand** chart.
→ It grows to the full width of the card, its value axis gains labels, its period axis goes from about
four labels to eight, and its explanatory sentence appears beneath it. The fill-rate curve **stays
enlarged** - several charts can be open at once, which is the point: put *Period cost* and *Unmet
demand to date* side by side at one cursor period and the trade is on one screen.
→ The axis text is the **same size on screen** as it was small. The two sizes are two drawing boxes,
not one picture scaled, so nothing shrinks into illegibility.

**41e. Double-click again to put it back.** Double-click the enlarged **Total on-hand**.
→ Back to its place in the grid, at its original size, with its sentence back on the tooltip. The
cursor line, the at-cursor figure and the fitted range never left - a small chart hides prose, never a
claim about the run.

**41f. It works without a mouse.** <kbd>Tab</kbd> to a chart (each cell takes a visible focus ring) and
press <kbd>Enter</kbd> or <kbd>Space</kbd>.
→ Same toggle. Note also that double-clicking a chart does **not** select the text under the pointer.

**41g. The gesture is the same at element scope.** Scope to `DC-1` and double-click **On hand**, then
scope back to the whole network.
→ The element chart enlarges and restores exactly as the network's do. And the network's **Total
on-hand** is unaffected by having enlarged the *node's* **On hand**: two charts of two different
quantities that happen to share a key, kept apart by scope.
→ Open a different run: the charts you left enlarged are still enlarged. How you want the page laid
out is a reading preference, not a fact about the run.

---

## H. What the scope and the cursor do not change

**42.** With an element scoped, look at the rest of the page.
→ **Metric cards unchanged**, with their confidence intervals. A horizon scalar has no per-period
form - the charts *are* the series those scalars summarise, and an element-scoped
`FILL_RATE` card would be a number this run never computed.
→ **Criticality table unchanged**, except for the marked row. It is a property of the network, not of
one node.
→ **Export, Delete run, Open network and Refresh** all behave exactly as they did.

**43.** With an element scoped, press **Refresh**.
→ The run re-reads, the scope **stays** where it was, the **cursor stays** where it was, and the
element series is not re-fetched.

**44.** Open a *different* run from the run list, then look at the miniature.
→ It opens on the **network** scope with the new run's own network, its cursor at **its own** last
period, and the element series is fetched again the first time you scope an element or move the
cursor. Node 12 of one run is not node 12 of another, and period 40 of one run may not exist in the
other at all.

**45. The scalars do not move with the cursor either.** Scrub the whole horizon while watching the
**Simulated metrics** cards and the **Node criticality** table.
→ Neither changes - the same rule step 42 asserts against the *scope*, now against the *period*. A
`FILL_RATE` card that moved with the cursor would be a number this run never computed, and the
`LOSS_AREA` note under the curve stays exactly as it was: it is about the whole horizon, not about the
period on screen.

---

## I. Housekeeping

**46.**

```bash
npm test
```

→ Green, including the five suites this feature owns - `curve-geometry.spec.ts`,
`mini-map-layout.spec.ts`, `element-charts.spec.ts`, `network-charts.spec.ts`,
`period-cursor.spec.ts` - plus `run-results.store.spec.ts` and `core/text-entry.spec.ts`. The editor's
`sparkline-geometry.spec.ts` must be green **unchanged in its existing expectations**: the four
gutters the dashboard's axes need are optional and default to `pad`, so every path string that suite
pinned before FR-22 is still the path string it pins now. `network-series.spec.ts`,
`playback-channels.spec.ts` and `metric-display.spec.ts` must also be green unchanged - the cursor
reuses all three rather than re-deriving anything they own.

**47.**

```bash
npm run build
```

→ Compiles clean, and the bundle does **not** grow by a graph library: the miniature is hand-drawn
SVG, and `cytoscape` stays in the editor's chunk. If the results-dashboard chunk suddenly
carries it, something in this feature has imported a component from `network-editor/` rather than one
of its two pure modules.
