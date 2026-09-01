# `network-editor` - the Cytoscape modelling surface

The primary modelling surface: a complete network can be built with the mouse alone, or an imported
one refined without touching the source files.

## Files

| File | Role |
|---|---|
| `network-editor.component.*` | Route shell - toolbar, keyboard bindings, dialogs, three-pane layout. Provides the store. |
| `network-editor.store.ts` | Signal state and the `CommandContext` implementation. **Authoritative.** |
| `network-editor.guard.ts` | `canDeactivate` - flushes pending edits on the way out. |
| `editor-commands.ts` | `EditorCommand` types, the concrete commands, `CommandStack`, the id remap. |
| `editor-persistence.service.ts` | Debounced coalescing writes to the three bulk PATCH endpoints. |
| `echelon-rules.ts` | Pure link validity, node type defaults, palette colours. Exhaustively unit-tested. |
| `unit-preferences.service.ts` | Which unit each field opens on: period unit for durations, last-used for rates. |
| `disruptions.store.ts` | The scenario being authored against this network: selection, events, region resolution (FR-16). |
| `disruption-overlay.ts` | Pure mapping from a scenario's events to marks on the canvas, with a spec. |
| `element-captions.ts` | Pure: what a caption draws, what emptying one means to a PATCH, and where the second line sits. Specced (FR-30). |
| `editor-run.store.ts` | Run a baseline or a scenario from the editor: submit, poll, provisional figures, the report - and the one path that loads a report, whether the run just finished or was picked out of the history (FR-17, FR-21). |
| `run-history.ts` | Pure tile derivation and the list's refresh rule: when a run ran, what it applied, what it can do. Specced (FR-21). |
| `run-panel/` | The run panel - two buttons, the history of every run of this network, the progress and provisional figures, the report in place. |
| `playback-clock.ts` | Pure playback arithmetic: the speed ladder, the horizon-derived default, the frame accumulator, the pace line. Specced (FR-18). |
| `playback-channels.ts` | Pure channel arithmetic: per-element normalisation, gauge fill, arc width, availability tint. Specced against the 4-echelon sample (FR-18). |
| `sparkline-geometry.ts` | Pure step-line geometry for the inspector's sparklines, plus the mean, the flow-weighted mean and the running total printed beside them. Specced against the same sample (FR-18). |
| `playback-preferences.service.ts` | The playback speed per network, in `localStorage`. Root-provided; a device-local view preference, never model state (FR-18). |
| `playback.store.ts` | The single `requestAnimationFrame` loop, the transport, and the run's per-element series (FR-18). |
| `playback-bar/` | The transport bar over the canvas: play/pause, step, restart, scrub, speed, clock readout (FR-18). |
| `element-inspector/` | The "Simulation" card inside the property panel: the selected element's per-period series, at the period on screen (FR-18). |
| `network-series.ts` | Pure derivation of the dashboard's seven live series from `RUN_TIMESERIES` - fill, served, cost, the two running totals, on-hand, pipeline. Specced against the 4-echelon sample (FR-19). |
| `network-dashboard/` | The property panel's empty-selection state: live playback figures, the run's metric suite, the structural suite (FR-19). |
| `graph-canvas/` | Owns the Cytoscape instance. Renders the store; raises commands. Link labels, tooltips, disruption halos. |
| `node-palette/` | The four HTML5-draggable type tiles. |
| `property-panel/` | Single and bulk attribute editing, including the per-product rows. |
| `disruptions-panel/` | Scenario picker, the event list for this network, and canvas-aimed authoring (FR-16). |
| `time-settings-dialog/` | Period length, horizon, rounding policy, "suggest period". |
| `time-warning-banner/` | Dismissible resolution warnings from `GET /networks/{id}/time-validation`. |
| `fork-prompt/` | The "network is frozen" dialog: fork a variant, discard the runs, or keep reading (FR-20). |

Shared with the rest of the app: `shared/unit-value/` is the number-plus-unit control every duration
and rate field uses, and `core/run-discard.ts` is the wording and the gate of the FR-20 discard -
pure and specced, and in `core/` because the results dashboard renders the same confirmation.

**Two files here are read from outside, and both are read by the results dashboard (FR-22).** Its
network inspector takes `echelon-rules.nodeTypeProfile` and `ECHELON_RANK` - a PLANT cannot be purple
on this canvas and blue on the miniature of the same network - and its element charts take
`sparkline-geometry.sparkline` at chart size, so a step line cannot break at a null here and dip to
zero there. That is the direction `disruption-overlay.ts → scenario-builder/timeline.ts` already runs
in, for the same reason, and it is noted here as well as there because a handover stated at one end
only is one somebody can change alone. Two consequences for anyone editing these files: the palette's
`colour` and `accent` are now drawn on two surfaces, and `SparkBox` carries four **optional** gutters
(`left`/`right`/`top`/`bottom`, each defaulting to `pad`) that the dashboard's axes need and nothing
in this folder passes - every caller here states `pad` alone and is drawn exactly as it was, which is
what keeps `sparkline-geometry.spec.ts` pinning the same hand-worked path strings.

The two sentences a run with no per-element series is described by moved to `core/element-series.ts`
at the same time and are unchanged (`playback.store.spec.ts` still pins them literally): three
surfaces now say them, and one situation must not be phrased three ways.

## Four decisions worth knowing before changing anything

**The store is the truth; Cytoscape is a projection.** `graph-canvas` holds one effect that diffs
`store.nodes()` / `store.links()` against `cy.elements()` inside a `cy.batch()`. Nothing in the
canvas writes to the store except by pushing a command, and a `syncing` flag suppresses the gesture
handlers while the effect runs so a programmatic reposition cannot echo back as a user move. Pan and
zoom deliberately stay inside Cytoscape and out of signals.

**Creates and deletes are immediate; moves and attribute edits are batched.** The API has bulk
*patch* endpoints only - `PATCH /networks/{id}/nodes`, `/nodes/positions` and `/links` - and no bulk
create or delete. So the debounce - PATCHed to the bulk endpoints every 2 s and on blur -
covers moves and edits, and a dropped node is POSTed before it appears on the canvas. That
round-trip buys a canvas that never holds an element without a server id: no provisional ids to
rewrite, and a duplicate name surfaces as an error over empty canvas rather than as a node that
vanishes a moment later.

**Undo of a delete produces new ids, so commands resolve ids late.** There is no soft delete, so
undoing a delete re-creates the row and MySQL hands back a new id. Every command therefore resolves
its ids through `ctx.node(id)` / `ctx.link(id)` at execution time, and a re-create records
`old -> new` in a remap table that is followed transitively. This is the one genuinely subtle part
of `editor-commands.ts`; `editor-commands.spec.ts` pins it.

**The drag handle is ours, not the extension's.** `cytoscape-edgehandles` v4 removed v3's hover
handle: its only built-in trigger is a `tapstart` on a node while *draw mode* is on, and draw mode
calls `cy.autoungrabify(true)` - so turning it on trades away node dragging entirely. The editor needs
both gestures at once, so `graph-canvas` renders its own handles as Cytoscape nodes - one on each
corner of the hovered node's bounding box (`HANDLE_CORNERS`) - and calls `eh.start()` from a
`tapstart` on any of them. They have to be real Cytoscape nodes rather than HTML overlays, because
`eh.start()` only draws a rubber band if Cytoscape is already tracking the drag, and it only tracks
drags that began inside the canvas.

A consequence worth remembering: the render diff removes **only** elements whose id parses as a node
or link id. Unparseable ids belong to that handle and to edgehandles' ghost and preview elements,
and a render firing mid-gesture - a selection change is enough - would otherwise delete the rubber
band out from under the pointer.

**FR-30 puts a third kind of element on this canvas and obeys the same rule from the other side.**
A caption is a companion Cytoscape node whose id is `snrm-caption-<owner>` - parseable as neither a
node nor a link, so `render` leaves it alone exactly as it leaves the handle alone - and
`syncCaptions` removes **only** ids `isCaptionElementId` claims, so it can never reach a real
element, a handle, or edgehandles' ghost. Both halves are what let either pass fire in the middle of
a drag, a box-select or an edge-draw without disturbing it. See *Captions on the canvas* below.

**A PATCH cannot clear a nullable field.** Omitted means "leave alone", which is exactly what bulk
editing needs and exactly why "set capacity to nothing" has no encoding. Clearing routes through
`PUT /nodes/{id}` or `PUT /links/{id}` (`ReplaceNodeCommand`, `ReplaceLinkCommand`) and is disabled
for multi-selections, since a full replacement needs every other field's current value.

**…except a caption, and the exception is the backend's rather than a workaround here (FR-30).**
`caption` is the one field on `NodePatch` / `LinkPatch` where a *present but empty* value is a write:
it clears (`com.snrm.network.Captions`, and the Javadoc on `NodePatch.caption`). So emptying the
caption field in the property panel is an ordinary edit on the ordinary path - one undoable command,
in the same debounced batch as everything else, with no × button and no PUT. That is why "typed a
caption, deleted it, it came back on reload" is not a limit of this editor, and it is also why
`element-captions.previousAttributeValue` exists: the `before` of a caption that *was* null is the
empty string rather than an omission, so Ctrl-Z after typing the first caption onto an element
removes it instead of reporting success and leaving it drawn. The PUT paths still have to carry the
pair - `toNodeRequest` and `clearLinkCapacity` both do - because a full replacement clears what it
omits, and clearing a region would otherwise take the node's caption with it.

**Units are a pair, and the pair is data.** A duration is `{value, unit}` and a rate is
`{value, timeUnit}`, both sent whole - there is no way to change a unit without restating the value,
and nothing sensible to do with half of the pair. Three consequences run through this folder:

- Change detection compares with `sameFieldValue` from `core/time-units.ts`, not `===`. Reference
  equality would call every re-entered value a change and PATCH it.
- Changing a unit **restates**: 36 h becomes 1.5 d, never 36 d. Across a multi-selection each element
  restates its own number, which is why the unit handlers hand the store a function rather than a
  value - and it is still one command, so one Ctrl-Z puts every declared pair back verbatim.
- Nothing in the editor converts a declared value into periods for display. The canvas label, the
  tooltip and the panel all read in the unit the user entered; the only place periods appear is the
  restatement line and the warning banner, both of which say so out loud.

**The warning banner asks the server.** `GET /networks/{id}/time-validation` runs the conversion
checks over every duration in the network, and the same service answers the import wizard - so
the two cannot drift into disagreeing about what a bad conversion is. The editor re-asks after a
completed save, after a command, and after a time-base change, debounced. Dismissal is remembered
against the *content* of the report, so a new problem raises the banner again and an already-read one
does not.

**A time-base change is not on the undo stack.** The stack covers add/move/connect/edit/delete on
canvas elements; the clock is a property of the network, set from a dialog the user confirms, and
refused outright once runs exist - where it routes into the same fork prompt as any other blocked
edit.

**The fork request itself is `core/NetworkCloneService` (FR-26).** `NetworkEditorStore.fork` used to
build the body and post it; the project dashboard's *Duplicate network* is a second caller of the
same clone, so the request and its body rule moved to `core/` and this store kept what only it has
to do around them - dropping the pending canvas writes, clearing the undo stack, closing the prompt.
`ForkRequest` moved with it and is re-exported here, so nothing in this folder changed its imports;
`fork-prompt` now resolves its two fields through the shared `forkRequestFrom`, which is why an
empty name field here and a prefilled one on the dashboard produce the same request.

## Captions on the canvas (FR-30)

`element-captions.ts` + a caption field and a *Show caption on canvas* checkbox in `property-panel/`
+ one effect and one `position` handler in `graph-canvas`. A node or an arc carries a short caption,
drawn beneath the label it already has, in a smaller and lower-contrast type: a node's under its
name, an arc's under its declared lead time.

Seven things carry it, and the first is the constraint Cytoscape imposes.

**Cytoscape draws one label per element at one font size**, which is already why an arc's label
carries its lead time and nothing else (see `edgeLabel`'s comment). A *smaller* line beneath that
label is therefore not a style on it - it needs its own drawn thing. It is a **companion Cytoscape
node**, which is the approach this canvas has already taken once for the corner drag handles, and it
inherits pan and zoom for free where an HTML overlay would have to be re-projected on every viewport
change. The element draws nothing of itself: 1 × 1, transparent, no border, and its whole purpose is
to carry a label at 9 px in `#6c757d` against the name's 11 px in `#212529`.

**It is inert, and `events: 'no'` is what says so.** Not `selectable: false` - that would stop it
being *selected* while it went on swallowing pointer events over its own box. With `events: 'no'` the
element is not hit-tested at all: never a click target, never hovered, never a box-select candidate,
never a snap target for edgehandles, and a pointer press over one starts a canvas drag exactly as if
it were not there. `selectable: false` and `grabbable: false` say it again at the element level, and
`canConnect` rejects it a third time because its id parses as no node - the same guard the handles
rely on. `z-index: 0` against the `node` rule's `1` keeps it behind whatever it reaches under.

**The render diff owns it.** See the note above: `render` removes only parseable ids, `syncCaptions`
removes only caption ids, and neither can reach the other's elements or edgehandles'. The caption
effect is registered **immediately after** the render effect and reads the same two signals, so on
any change to the network the two run back to back in one flush - elements first, at their sizes,
then the captions anchored beneath them.

**An empty caption draws nothing whatever the flag says.** `drawnCaption` is the one place that is
decided, and the trim in it is not decoration: the store holds the empty string for the two seconds
between a clear and the flush that turns it into a null, and a canvas that drew a blank second line
in that window would be a defect nobody could reproduce on reload.

**A caption follows its element at frame rate, through `position`.** The store hears about a drag
only when it *ends* - one `MoveNodesCommand` for the whole moved selection - so a caption that waited
for `render` would sit where the node used to be for the length of the gesture. `position` is the one
event that fires for every way an element moves: a user drag, the auto-layout writing coordinates
back, and `render` correcting a position after a save. An arc's caption moves with **either**
endpoint, so a node's incident edges are re-anchored with it, and a caption's own `position` event is
ignored or the handler would feed itself. The guard on that hot path is a **counter**, not a
`cy.$('.snrm-caption')` query: a selector there would make dragging *n* nodes cost a pass over the
whole canvas *n* times a frame, and zero is the ordinary state of a network nobody has annotated.

**The criticality encoding moves it too, and nothing else would have noticed.** A caption sits a
fixed distance below the *bottom* of its node, and the encoding writes a per-node diameter
between 30 and 74 px. A size change fires no `position` event, so `applyCriticality` re-anchors
explicitly; without it the encoding would leave captions inside the nodes it grew and floating under
the ones it shrank. `nodeCaptionOffsetY` is a function of the drawn height for exactly that reason,
and its spec pins both ends of the range.

**The auto-layout runs over the network and nothing else.** `cy.layout()` takes every element on the
canvas, and a caption is an unconnected point node - dagre would lay each one out as its own
component and make room for it, distorting the arrangement of the real network to accommodate labels
that exist only to sit underneath it. `runAutoLayout` filters the collection first. The drag handles
are excluded on the same terms; they merely happen not to be on the canvas when the toolbar button is
pressed, which is luck rather than a rule.

Two more, on the interactions that were already here. **Playback leaves captions alone**: every
playback pass keys off an id that parses (`applyPlayback` skips them, `stripPlayback` finds none of
its data keys on them), so `clearPlayback`'s teardown cannot strand or duplicate one - and its
re-stamp of the criticality sizing re-anchors them, which is the correct thing to do rather than an
accident. And **a caption is an ordinary edit**: it joins the debounced
bulk PATCH, it is one undoable command, and it raises the fork prompt on a frozen network exactly
like a capacity. No exemption was added, and `blockIfReadOnly` needed no change to make that true.

**Deliberately not built, and worth saying so.** The dashboard's miniature (FR-22) and the FR-25
side-by-side panes draw no captions, and there is no global *show all captions* toggle in the editor.
The first two are read-only structural views whose scale is the argument against a second line of
type; the third is worth revisiting only if hiding captions one selection at a time proves tedious in
practice - the checkbox already bulk-applies, and Ctrl-A then unticking it is the whole gesture
today.

## Disruptions on the canvas (FR-16)

`disruptions.store.ts` + `disruptions-panel/` + the halos `graph-canvas` draws. An event's target is
a node, a link, or a region resolved to the nodes carrying its tag - every one of them a thing on
this canvas - so the target is picked by **selecting it**, not from a dropdown on another screen.

Four things carry it, and the first is the one to be careful about.

**Authoring an event is not an edit of the network.** A scenario is a different aggregate.
No event write touches `EditorPersistenceService`, none can trip the immutability guard, and
`DisruptionsStore` contains no `readOnly()` check at all - deliberately, and the panel says so on
screen. A network frozen by a completed run is the **common** case here, not the edge case: it is
precisely a configuration already evaluated, which is when a new question about it is worth asking.
The one thing the store does reach into the editor for is `flush()`, before resolving a region tag,
so a node retagged two seconds ago is on the server before we ask what its tag covers - a read after
a flush, the same move `TopologicalMetricsStore` makes.

**The event editor is the scenario builder's own component.** `features/scenario-builder/event-editor`
is hosted by both surfaces, so severity, window, recovery profile and probability have one
implementation and cannot drift. What this feature added to it is *aiming*: an `aimedAt` input, and a
`saved` output that emits **one request per aimed target** - one window and one severity applied to
every element that was selected, because retyping the same window three times is how three events
meant to be identical stop being. `openAs` is the other half of that: a region has nothing to select,
so it is chosen in the picker rather than aimed at, and the picker opens there.

**The badge has a channel of its own.** Node colour is the type, node size may be criticality, the
border is the selection, and a dashed orange arc is an echelon warning. The disruption halo is the
Cytoscape **underlay** - drawn *behind* the element, so all of the above stay exactly as legible with
one under them. The window and severity are on hover, which is also why arcs grew a tooltip: a
disrupted one has something to say that its lead-time label does not. The live preview of a region
tag being typed uses the **overlay** instead, the last free channel: it is transient, it is loud, and
it must not read as an event already in the scenario.

**Regions are resolved by the server, always.** `core/RegionNodesService` is the one caller of
`GET /networks/{id}/region-nodes`, shared with the scenario builder. Filtering `node.region` in the
browser would answer the same question and would be a second implementation of it, free to disagree
with the one a run will use. What *is* client-side is the decision to re-ask:
`DisruptionsStore.regionFingerprint` is a cache key over the tags on the canvas, not an answer.

`disruption-overlay.ts` places each badge through `scenario-builder/timeline.ts`'s own `placeBar`, so
a window cannot read "periods 28–38" on the canvas and "periods 28–39" on the Gantt chart. That is
the reason for the cross-feature import; `disruption-overlay.spec.ts` pins it with an assertion
against `placeBar` itself.

## Running from the editor (FR-17)

`editor-run.store.ts` + `run-panel/`. A configuration is judged where it is built: two buttons - the
**baseline** (no scenario, `POST /simulations` with `scenarioId` omitted; N replications, not 2N)
and any **scenario** in the project, defaulting to the one open in the disruptions panel next door.

Four rules carry it. **Everything asynchronous goes through `JobPollingService`** - the store
submits, hands over the `jobId`, and renders the poll; no timer anywhere. **The provisional figures
of FR-17** ride the poll's new `partial` object and are labelled *provisional* wherever they appear
(the amber block, deliberately not styled like results): streaming statistics over the completed
replications, replaced by the persisted suite on completion, never persisted or exported. **The
freeze is shown at the 202, not discovered at the next edit** - `NetworkEditorStore.
refreshNetworkRecord()` re-reads the network row alone when a run is accepted and again when it
settles, because the server (not the editor) knows whether other runs still hold the network; a
cancelled or failed run releases it and the banner clears the same way. The panel states all of this
before the button is pressed, as the standalone launcher does - running from the editor is not a way
around immutability, it is a way of reaching it sooner. And **the report in place reuses the
dashboard's parts** - `performance-curve`, `ci-value`, `metric-badge`, the same `curve-geometry`
functions - so the editor cannot draw a different triangle from the same run.

Two completed runs open side by side through the comparison view's `?runIds=` mode (FR-17) - the
only selector that can seat two runs of one network as two columns, which is exactly what
baseline-versus-disruption is. Which two are on offer is the run history's answer now; see the next
section.

The panel deliberately has no parameter form: replications, seed and noise belong to the scenario
and to the standalone launcher's override form, which is one link away. A baseline run from here
uses the engine defaults; the launcher's *Baseline - no scenario* option is the place to override
them.

## The run history - every run of this network, watchable (FR-21)

`run-history.ts` + the history block in `run-panel/` + `EditorRunStore.selectRun`. The panel's
memory used to be the session's: a run submitted an hour ago from another tab existed in the
database and nowhere on screen, and the only way to see what a configuration did was to run it
again - which costs minutes and, on a stochastic scenario, answers a subtly different question.

Six things carry it.

**The list is not a new one.** It is `NetworkEditorStore.runs()`, from `GET /networks/{id}/runs`,
which the FR-20 discard confirmation has counted all along, and the rule it follows is
*one list, two readers*. A second fetch into a second signal would let the panel offer a run the
dialog had already destroyed, and both would be right about their own copy. `loadNetworkRuns()`
gained one thing for this: its failure now lands on `runsError` rather than on the canvas action
banner, because the panel that reads it most often has somewhere of its own to say so. The unlock
gesture, which does not, still raises the banner - `openDiscardPrompt` copies the message across.

**A tile is derived, not formatted in the template.** `run-history.ts` is pure and specced for the
reason `disruption-overlay.ts` and `run-discard.ts` are: every field of a tile is a *reading* of a
nullable DTO field, and each reading is a claim. When it ran is `finishedAt`, else `startedAt`, else
*queued* - three states rather than a nullable date, because "not finished" and "not started" are
different facts and a blank cell states neither. What it applied comes from `runScenarioLabel`,
split out of `describeRun` so the tile and the deletion dialog cannot name one run two ways. The
status is badged **only** where it is not `DONE`, since a badge on every tile would make the
ordinary case shout as loudly as the exceptional one. And `restored` is `importedAt` - the field
the wire omits, which is why `core/api-nulls.ts` is on the path and why the spec pins that case.

**Selecting a `DONE` tile does what finishing a run does - through the same code.**
`EditorRunStore.loadReport` is the only thing in this feature that writes `report()`, and both
entrances call it: the poll settling, and a tile being picked. One `GET /simulations/{runId}`, one
normalisation, one bump of `autoPlayToken` - so playback arms at the configured speed for a run from
last Tuesday exactly as it does for one that finished a moment ago, and the panel and the canvas
cannot end up describing different runs. A second loader would be the one nobody watches while
developing, because it is the one that does not run when you press the button. **Re-selecting the
tile already loaded returns before the fetch**, so a click on the run that is currently playing does
not throw the clock back to period 0; switching *between* tiles mid-read is allowed, and
`reportRequest` drops the answer for the tile the viewer has left, which is `PlaybackStore`'s own
rule one level up. A run recorded before `V9__element_timeseries.sql` degrades exactly as FR-18
specifies - clock and curve play, canvas channels dark, the transport bar's sentence verbatim.

**Every other status selects to its status and stops there, and nothing polls it.** There is no
report to load: the suite and all three series are written in one transaction with `DONE`.
An active one is not followed live either - **its job id was issued to its submitter alone**,
so there is nothing this tab could poll even if it wanted to, and inventing a status
loop against `GET /simulations/{runId}` would be a polling loop this application does without.
The tile says the status and offers **Refresh**.

**The refresh rule is three gestures and no timer.** The panel opening (`setActive`), a run
*settling*, and a deletion. The list refreshes when a run settles and after every
discard, and at no other time. The settle half is `settlesHistory`, pure and specced: the transition
**into** a terminal state and nothing else, so every `QUEUED → RUNNING` tick and every re-emitted
`DONE` costs no request, and all three terminal states qualify because a `FAILED` run is a tile too.
The whole-network discard needs no entry at all: `discardRuns` empties the list on the 204, which is
the same answer a re-read would give and one request cheaper.

**Delete is per tile, behind the confirmation that already existed.** `discardRunConfirm` from
`core/run-discard.ts`, one dialog for both entrances - the report's **Delete run** and a tile's 🗑 -
because one irreversible act must not be described two ways. A tile whose run is `QUEUED` or
`RUNNING` offers a disabled button and says why: the server refuses it with `RUN_ACTIVE` (409), and
the editor does not open a confirmation it knows will fail. `deleteRun` drops the report **only when
the deleted run was the one on screen** - deleting some other tile must not stop the playback of a
run that still exists - and re-reads both the list and the network row, since deleting the last
locking run releases the freeze and whether it was the last is the server's answer.

**The session accumulator is gone, and that is the point.** `EditorRunStore.completedRuns` held the
runs this session watched finish and was the sole source of the `?runIds=` comparison candidates.
The history is a strict superset of it - every run it held is in the list, and the list refreshes
when a run settles - so keeping it would have meant two differently-derived answers to "which runs
can this one be compared against", free to disagree in exactly the situation FR-21 was written for:
a baseline run from Monday and a disruption run from Tuesday, comparable, with no session that saw
both. `comparisonCandidates` derives them from the history's `DONE` tiles instead, and refuses a
non-`DONE` one for the server's reason rather than for tidiness - `?runIds=` answers `RUN_NOT_DONE`
(409) rather than seating an empty column, so offering one would be offering a link that 409s.
`EditorRunStore.reportLabel` replaced the accumulator's stored label at the same time: it reads
`scenarioName` off the run record, which is the only answer available for a run this session never
launched and the better one for a run whose scenario has since been renamed.

## Discarding the runs - the freeze's documented exit (FR-20)

`core/run-discard.ts` + `NetworkEditorStore.openDiscardPrompt/discardRuns` + the third button on
`fork-prompt/` + **Unlock…** on the frozen banner + **Delete run** on `run-panel/`'s report.

A network froze from the moment a run was accepted, and offered exactly one way onward: fork a
variant. That is the right trade for a result worth keeping and the wrong one for a run submitted
only to see whether the model behaves at all - which, during iterative model building, is most of
them. FR-20 adds the other trade, and the fork prompt now states the choice as what it is: **fork to
keep the result, discard to admit it was a test.**

Five things carry it.

**Nothing here unlocks anything.** The freeze is derived on the server from the run rows - the
mutation guard asks "does a locking run exist" on every write - so discarding is an ordinary
`DELETE /networks/{id}/runs`, and the banner clears because `refreshNetworkRecord()` reads
`editable: true` back. That is the same re-read FR-17 introduced at the 202, and it is here for the
same reason: whether *other* runs still hold the network is not a question this client can answer, so
it does not try. There is no `unlocked` flag anywhere, client or server.

**A run that is kept is exactly as freezing as before.** No "run without freezing" mode was added and
none should be: a `DONE` run whose network mutated beneath it is the precise record the freeze exists to
prevent. `DONE` is the one status that locks the network *and* deletes - that asymmetry is the whole
feature.

**The confirmation is typed, and it counts.** `shared/confirm-dialog` with a `requiredPhrase`, per
FR-15's discipline - `Baseline v2` for a whole network, `run 12` for one run. Name-plus-version
answers FR-15's own objection rather than inheriting its phrase: FR-15 types the *project* name
because a network shares its name with every variant, and name plus version is unique within the
project, so it names the configuration whose results are going. The dialog lists the runs, and gives
a **restored archive result** (`importedAt`) a warning line of its own: deleting it destroys the only
copy this installation holds, though the archive file is untouched.

**The editor does not open a dialog it knows will fail.** The whole-network delete is refused *whole*
while any run is `QUEUED` or `RUNNING` (`RUN_ACTIVE`, 409) - a half-discard would destroy the
finished results and leave the network frozen by the unfinished one, which is worse than either whole
answer. So `openDiscardPrompt()` re-reads `GET /networks/{id}/runs` first and answers an active run
with the sentence `activeRunsBlocker` writes, in the editor's own action banner, rather than with a
confirmation that would 409 after the phrase had been typed. The re-read happens every time for a
second reason: a count taken when the editor opened would be wrong the moment a run completed beside
it.

**Deleting the reported run drops the report, and playback with it.** `EditorRunStore.reset()` runs
on success and `PlaybackStore` follows `report()`, so the canvas stops replaying a run that no longer
exists. Since FR-21 the reset is *conditional* - only when the deleted run is the one on screen, as
a tile's 🗑 can now name any run of the network - and the history is re-read either way, so a
side-by-side link can never offer a `?runIds=` column the server would refuse.

## Visual playback - the clock and its speed (FR-18)

`playback-clock.ts` + `playback-preferences.service.ts` + `playback.store.ts`. The clock and the
**speed setting**; the canvas channels that ride on them are the section after this one. The setting
lives in the time-settings dialog, as a fourth row deliberately outside that dialog's own
all-three-fields-together contract, and again on the transport bar - both writing through
`PlaybackStore.setSpeed` into the one root preference service.

**The speed is a device preference, not model state.** It changes no simulated number, so
it is not a network column, not a `TimeBaseRequest` field and not an export field. That is not
tidiness: a network is frozen exactly when playback matters - playback replays a *completed* run -
so a speed carried on the network would be refused by `NETWORK_IMMUTABLE` on every network it
applies to. It is stored in `localStorage` under `snrm.playbackSpeed`, keyed per network because a
pace suits a horizon. It is the second use of `localStorage` in the app and the first for a
preference; `core/TokenStore` is the other, and this file follows its discipline - every read and
write wrapped, denied storage degrading to an in-memory signal rather than throwing into a dialog.
An absent key means "use the horizon-derived default", which is a live answer that follows a
time-base change rather than a stale copy of one.

**The default is chosen by ratio, not by difference.** `defaultSpeedFor` aims a whole run at about
30 s and picks the ladder entry minimising `|ln(speed / ideal)|` - playing a run in half the ideal
time is exactly as wrong as playing it in twice. The linear rule disagrees on 104 periods, where it
would prefer a 52 s playback to a 21 s one; `playback-clock.spec.ts` pins that case by name along
with 52 → 2, 365 → 10 and 10 → 0.5.

**One loop, and it is the store's.** `JobPollingService` exists because no component implements its
own polling loop; `PlaybackStore` is the same rule applied to animation. The clock
advances by **timestamp delta**, never by frame count, so a 144 Hz monitor and a 30 Hz one play a
run in the same wall-clock time, and the delta is clamped at 0.25 s so a backgrounded tab *resumes*
rather than leaping - there is no live thing to catch up with. `simTime` is a plain private field
and only `currentPeriod` is a signal, written when the floor actually changes, and the loop runs
outside the Angular zone: playback must not schedule change detection sixty times a second to redraw
the same period. Playback is stepwise, and deliberately so - the engine is
discrete-time, and an interpolated inventory level is a number no replication produced.

**The clock is the run's, never the live network's.** `periodLength` and `horizonPeriods` come from
the report (`SimulationRun.periodLength`), so a later time-base change on a forked
variant cannot relabel an old run's playback. The horizon is the shorter of what the run declares
and what its series carry, because a `QUEUED` or `RUNNING` run answers with empty lists.

## Visual playback - what moves on the canvas (FR-18)

`playback-channels.ts` + `playback-bar/` + one effect in `graph-canvas`. The clock above says *which
period*; the per-element series says what that period looked like, element by element.

**Click Run, watch it simulate.** `EditorRunStore.onSettled` bumps `autoPlayToken` when a completed
run's report lands, and `PlaybackStore` reads that as "rewind and play" - once per run. It is a token
rather than a call because `PlaybackStore` injects the run store to read the report, so the run store
cannot inject it back; a monotonic counter also makes the trigger idempotent, so a re-emitted report
does not restart the animation. The provisional phase is untouched (FR-17): partial figures stay as
they are while the job runs, and there is nothing to play until it is DONE.

**The series is fetched once per report, and `available: false` is not an empty series.**
`GET /simulations/{runId}/timeseries/elements` is asked for exactly where it is drawn - the split keeps
`horizon × (nodes + links)` numbers out of `GET /simulations/{runId}` for that reason - and the whole
horizon then sits in memory, which is what lets the clock step twenty periods a second with no
request in sight. A run recorded before `V9__element_timeseries.sql`, or one submitted with
`recordElementTimeseries: false`, answers `available: false`: **playback stays enabled** - the clock,
the transport, the scrub bar and the performance curve all read `RUN_TIMESERIES`, which every
completed run has - and only the canvas channels go dark, with the transport bar saying so in a line.
Withdrawing a working feature because a newer one has no data would be the worse answer.

**Three channels, each with exactly one meaning, and every existing channel keeps its own.** Colour
is still the node type, size may still be criticality, the border is still the selection and a dashed
orange arc is still an echelon warning - so playback took channels of its own:

- **Node inventory → the node's own fill.** A linear gradient with a hard boundary, in the node's
  *accent* - the darkened form of its type colour that the border and palette tile already use - so
  the gauge is a second reading of the hue rather than a new colour to learn.
- **Link flow → arc width**, 2–9 px. An arc carrying nothing is dashed and faded at the minimum width
  rather than removed: a stalled chain is a finding, and it is unreadable if the arcs vanish with the
  flow. The `edge.snrm-playback` rules sit **before** `edge:selected` and `edge[?warned]`, which is
  what keeps blue-and-thick and orange-and-dashed meaning what they always meant.
- **Availability → the disruption underlay, made temporal**, and a stockout → the overlay. See below.

**Normalisation is per element, and that is a trade rather than an oversight.** A node's gauge is its
own stock against its own maximum over the horizon; normalising across the network would flatten
every node beside the largest, and the thing playback exists to show - *this* element filling and
draining - would be invisible on all but one. Two full gauges therefore do not hold the same
quantity; the tooltip and the element series answer "how much".

**The underlay has one owner at a time.** FR-16's authoring halo says *this element is struck
somewhere in the horizon*; playback's tint says *it is dark **now***. While a run plays the second
wins, purely by selector order - `snrm-playback` after `[?disrupted]`, with an opacity of 0 at full
availability - so the static halos go quiet without their data being touched and return the moment
the class is removed. `disruption-overlay.ts` carries the same note from the other side, because a
handover stated at one end only is one somebody can change alone.

**Leaving playback is a real teardown.** `clearPlayback` removes every key and class the feature
wrote and re-stamps the criticality sizing exactly as `render()` does, so a canvas cannot be left
showing stale gauges or uniform nodes with the encoding on. `render()` re-stamps playback for the
symmetric reason, untracked in both directions.

**Nothing here writes a signal per frame.** The effect watches `currentPeriod`, which the store
writes when the *period* changes; inside it, each element's data is compared before it is written, so
a steady-state network repaints almost nothing from one period to the next.

## Visual playback - the element inspector (FR-18)

`sparkline-geometry.ts` + `element-inspector/`, rendered by `property-panel/`. The canvas answers
*how full, how busy, how dark* for the whole network at once; the inspector answers **how much** for
the one element under the pointer. The two are complementary by construction: every canvas channel is
normalised against that element's own maximum, so two full gauges are not two equal quantities, and
this card is where the quantity is stated.

**It is a card in the property panel, not a fourth panel.** The panel is already where a selected
element is described and it is always open, so the readings sit above the attribute form in the same
aside: structure below, behaviour above, one scroll and one selection gesture. A second surface would
put an element's attributes and its behaviour in different places for the same click. The card gates
itself - playback enabled, and exactly one node or one link selected - so the panel's own `@switch`
over the selection kind is untouched, and a **multi-selection gets no card** rather than a merged
one. An inventory averaged across four nodes is a number the run never produced.

**Absent is absent, in four places, and each is a different sentence.** A null `inboundLead` reads
*no inbound this period* - a 0 would say material arrived instantly; a node or arc with no
declared capacity reads *uncapped*; one an outage took to zero available capacity reads *no capacity
available*, because a dark element is not an idle one; and a run with no element detail at all shows
the **transport bar's own sentence** in place of every row, verbatim rather than reworded, since two
surfaces phrasing one situation differently read as two problems.

**The sparkline is stepwise and its scale is fitted, which is the opposite of the performance
curve's.** Stepwise because the engine is discrete-time - the same reason the clock does
not interpolate - so a period is drawn as a *band* and a null breaks the line rather than dipping it
to the floor. Fitted because a stock or a flow has no meaningful floor or ceiling, unlike a fill
rate, and against a zero-anchored axis every steady state flattens into the one thing playback exists
to show; the range is printed under the line so the scale is never implicit, and both series share it
so the gap between them means something. `curve-geometry.scaleFor` argues the opposite case for the
opposite quantity.

**The mean is one number with two readings**, printed in the row and drawn as the reference line
through `valueY`, so the line cannot sit somewhere the figure does not. The **average lead time** is
flow-weighted rather than plain: `inboundLead` is already dispatch-weighted within a period, and
averaging those figures unweighted would give a period that moved one unit the same say as one that
moved a hundred. Its weights are the inbound arcs' `flow` series, which align index for index - a
link's flow in period *t* is what was dispatched then, and the node's lead for *t* is the lead of
that dispatch.

**Criticality is read, never requested.** The header carries `NODE_CRITICALITY` when
`TopologicalMetricsStore` already holds it, and nothing otherwise - the suite costs one maximum-flow
computation per node and the store recomputes only while something is looking at it (see above), so
selecting an element during playback must not become a reason to recompute. No figure is not a zero.

**Geometry per selection, cursor per period.** `sparkline()` depends on the element and the run;
`cursorX()` depends on the clock. They are separate computeds so a run playing at twenty periods a
second re-runs the second and not the first - the canvas effect's rule, applied to the panel.

## The network dashboard (FR-19)

`network-series.ts` + `network-dashboard/`, rendered by `property-panel/` in its `none` branch. The
element inspector answers *how much* for one element; this answers it for the **whole network** -
and, unlike the inspector, it answers something even when there is no run at all.

**It is the empty selection, not a new gesture.** Clicking empty canvas already unselects everything
and Escape already does the same, so both already produced `selectionKind() === 'none'`.
That branch used to say *Nothing selected*; it now says what *is* selected, which is the network. No
binding was added, nothing on the canvas behaves differently, and **deselect-only still works by
construction** - the dashboard is passive. It reads three stores and writes exactly one thing, the
selection, and only when a criticality row is clicked.

**Three blocks, each gated on what it needs, in the order the reading goes.** *Live* needs a playable
run and shows seven rows at the playback clock's period - fill rate, served over demand, period cost,
cumulative cost, cumulative unmet units, and the two Stage-2 columns `V9__element_timeseries.sql`
added: total on-hand and in-pipeline. *Run suite* needs a loaded report and renders the
`NETWORK`-scoped rows, now including `AVG_INVENTORY` and `AVG_PIPELINE`. *Structure* needs **nothing**
- the topological suite is computed from the graph, so it is the block a researcher sees on
a network just drawn, and it is why the dashboard is worth opening before the first run rather than
after it.

**Two of the seven live rows have no baseline overlay, and the schema is why.** `RUN_TIMESERIES`
carries `baseline_served_demand` and `baseline_cost` and nothing else (`V6__run_timeseries_baseline.sql`),
so fill, served, cost and unmet each have an undisrupted twin to draw under them and **on-hand and
pipeline do not**. They are drawn bare rather than overlaid with a copy of themselves, which would
claim the disruption moved no stock. The honest comparison for those two is the `AVG_INVENTORY` and
`AVG_PIPELINE` cards below, which carry proper cross-replication intervals.

**Geometry per report, figures per period.** `network-series.ts` derives the seven arrays once per
report; `sparkline()` turns them into paths once per report; only the printed figures and the shared
cursor move with the clock. One cursor for all seven lines, because they share a box and a period
count and any drift would put the same period in two places on one screen. It is the inspector's
split, applied to a stack of lines.

**The structural suite is live here, and that cost is deliberate.** `TopologicalMetricsStore`
recomputes only while something is reading it, because `NODE_CRITICALITY` is one maximum-flow
computation per node (FR-04). The dashboard counts as a reader, so
`network-editor.component.ts` folds `selectionKind() === 'none'` into `setActive` beside the panel
toggle and the size-by-criticality encoding. The consequence is that deselecting on a large network
schedules the same debounced recompute opening the metrics panel has always scheduled - and in
return the figures refresh as the network is edited and carry the store's stale marker while a
recompute is in flight, rather than describing whatever the network looked like the last time
somebody opened a panel. The dashboard shows the **worst three** nodes and points at the metrics
panel for the rest; clicking a row selects that node, the same gesture that table and the resolution
banner already use.

**Absent renders absent, on both sides of the line.** A baseline run omits `TTR`, `LOSS_AREA`,
`DISRUPTION_COST_DELTA` and `RESILIENCE_INDEX` and the block says so in a sentence rather than
showing four zeros (FR-17). `AVG_INVENTORY` and `AVG_PIPELINE` are the opposite case and the note
says that too: they describe what the network *held*, which a run with no scenario answers as well as
any other, so they are present - and an `AVG_PIPELINE` of 0.0 on a network with no lead times is a
measurement, not a gap (`docs/simulation-verification.md` §6.6).

## Known limits, and why

- **Disruption events are still not listed in the delete confirm**, though the reason has changed:
  the `/scenarios` endpoints exist now and the disruptions panel reads them. What the editor
  knows is the events of the **one scenario selected in the panel**, and deleting a node affects
  events in every scenario of the project. A confirm that named only the open scenario's would
  under-report in exactly the case where the count matters. `disruption_event` carries
  `ix_event_target` for this question; a `GET /nodes/{id}/events` (or a count on the deletion-impact
  response) is what would answer it honestly, and is the same trade `product-usage.service.ts`
  records for its own sweep.
- **Undoing the first auto-layout of an imported network does not restore "no coordinates."**
  Nothing can: a PATCH cannot write null back. Nodes that had real positions are restored normally.
- **Per-product rows are single-node only.** There is no bulk endpoint for `node_product` and no
  gesture that produces them in bulk, so the panel shows them for one selected node at a time.
- **Undoing a node delete does not restore that node's per-product rows.** `DeleteElementsCommand`
  snapshots nodes and links, not the rows beneath them, so the undo re-creates the node bare. The
  delete confirm says how many rows will go, which is the honest half of the story; restoring them
  would mean snapshotting every row of every deleted node before the confirm is even answered.
- **A `DISRUPTION_EVENT` finding in the warning banner is not clickable.** It names a scenario event,
  which is not an element of this canvas; the row still states the numbers.
- **The disruptions panel does not re-check the overlay when another tab writes an event.** It holds
  what it loaded and what it wrote. Re-picking the scenario in the picker refetches it.

## Testing

`echelon-rules.spec.ts`, `editor-commands.spec.ts`, `editor-persistence.service.spec.ts`,
`disruption-overlay.spec.ts`, `element-captions.spec.ts`, `playback-clock.spec.ts`,
`playback-channels.spec.ts`,
`sparkline-geometry.spec.ts`, `network-series.spec.ts`, `run-history.spec.ts`,
`playback-preferences.service.spec.ts` and
`playback.store.spec.ts` are plain unit tests - no Cytoscape, no TestBed rendering of the canvas.
Headless canvas tests buy little and flake often; the interaction surface is covered by
`MANUAL-TEST.md` instead.

`element-captions.spec.ts` is why FR-30 has a pure module at all: everything about that feature that
is a *rule* or a *number* was moved out of `graph-canvas` so it could be pinned, leaving the
component with adding, moving and removing elements - the part a headless test could only
re-describe. Three groups. What draws, including the rule an empty caption obeys whichever way the
flag points. What an edit means: that a caption's undo is the empty string a PATCH reads as *clear
it* while every other field's is an omission, and that null and `''` are one state for a caption so
tabbing through an empty field costs nothing. And where the second line lands, worked by hand from
the four type metrics the stylesheet states - 46 px below a default node's centre, 38 and 60 at the
two ends of the criticality range, 9 below an arc's midpoint - so a font size changed on one side and
not the other fails here rather than putting a caption a pixel inside the name it sits under. The
id-ownership group is the one that reads oddly and matters most: it asserts that a caption id is
claimed by neither element-id parser and by neither handle prefix, and that `isCaptionElementId`
claims nothing else on the canvas. That is the render-diff contract stated as a test.

`editor-commands.spec.ts` gained the three cases the caption introduced, and its fake context now
models `com.snrm.network.Captions` - trim, blank-is-null, and an omitted flag meaning *visible* - so
a full replacement that forgot the pair fails a test rather than wiping an annotation on the way
past. That is what pins undo of a caption edit, undo of a delete of a captioned element (hidden
captions included), and the checkbox bulk-applying where the text does not. `core/run-discard.spec.ts` belongs to the same set from one folder up: what
it pins is not string equality but the four promises the FR-20 dialog makes about what the server
will do - that it never offers a discard the server would refuse, that it counts what is going, that
a restored archive result gets a warning of its own, and that it claims an unlock only when a run in
the set actually holds the freeze (a network whose only runs `FAILED` was never frozen, and saying
"becomes editable again" there would promise an effect the deletion does not have).

The playback suites are testable at all because the parts that have to be right are separable: the
arithmetic is pure (`playback-clock.spec.ts` pins the ladder, the four default horizons, the frame
clamp and the pace-line wording; `playback-channels.spec.ts` pins the gauge, the ribbon and the
availability tint against `samples/four-echelon-playback/README.md` §6.5, whose numbers are derived
by hand from the per-period loop rather than from a run), the storage degrades rather than throws
(`playback-preferences.service.spec.ts` drives a denied `localStorage` and a key written by a build
with a different ladder), and the loop is driven by injected timestamps -
`playback.store.spec.ts` replaces `requestAnimationFrame` with a queue and hands the store the
frame times it wants, including a ten-second gap standing in for a backgrounded tab. That spec also
covers the element read: the three answers it can get (`available`, `available: false`, a failure),
the stale response it must drop, and auto-play firing once per completed run. No test waits for a
real frame.

`sparkline-geometry.spec.ts` pins every path string of a five-point series against a box whose
arithmetic is exact (slot 20, plot height 12), so the step treads, the risers, the broken gap and the
coincident baseline overlay are all readable in the expectation itself; its means are §6.5's figures
to the same two decimals `MANUAL-TEST.md` §U reads off the screen. The card that draws them has no
spec of its own for the reason the canvas does not: it is composition over signals, and every number
in it comes from that module or from `core/metric-display`, both of which are specced one level
down.

`network-series.spec.ts` does the same one level up for the dashboard, against
`samples/four-echelon-playback/README.md` §5.1, §6.4 and §8.2: the two Stage-2 columns read straight
off the row, the cost accumulator hitting the document's own 1616 subtotal, the stockout's cumulative
unmet counting 0 → 10 → 20 → 30 while the baseline stays flat, and the two readings that must not
become numbers - a period with no demand answering a fill of 1 rather than 0/0, and a period past the
end answering null rather than the last value clamped. The dashboard component itself is composition
over signals and has no spec, for the same reason the inspector has none.

`run-history.spec.ts` follows `run-discard.spec.ts` rather than the geometry suites: what it pins is
not a shape but the four readings a tile has to make, each of which is a nullable DTO field
read one particular way - the finished-then-started-then-queued fallback, the baseline test being
`scenarioId === null` and nothing else, the badge appearing only off the `DONE` path, and the
restored mark surviving a wire response that omits `importedAt` (which it drives through
`normaliseRuns`, since that is the one shape the browser actually receives). Plus the two rules that
decide what a tile can *do* - both delegating to the server's own predicates - and `settlesHistory`,
which is what keeps the list fresh without a second polling loop. The panel that draws them has no
spec, for the reason the inspector and the dashboard have none: it is composition over signals, and
every claim in it comes from this module or from `core/run-discard.ts`.

The unit arithmetic is pinned one level down, in `core/time-units.spec.ts`, against the backend's
`docs/time-units-worked-example.md`: every expectation there is a row of that document, so a drift
between the two ends of the wire fails in a test rather than in a simulation result.
