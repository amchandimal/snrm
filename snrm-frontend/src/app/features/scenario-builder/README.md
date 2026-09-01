# `scenario-builder` - disruption scenarios and the event timeline

This feature folder implements the scenario builder and FR-05.

> "Scenario builder - timeline: rows are targeted nodes/links/regions, bars are events
> (start/duration), with severity and recovery profile per bar."

Two routes:

| Route | Component | What it is |
|---|---|---|
| `/projects/:projectId/scenarios` | `scenario-list/` | The project's scenarios, with create, **duplicate** and delete |
| `/projects/:projectId/scenarios/:scenarioId` | `scenario-builder.component` | The timeline, the network picker, and the editor for one bar |

Endpoints: `CRUD /projects/{id}/scenarios` and `/scenarios/{id}/events`, plus
`POST /scenarios/{id}/duplicate` and the two region reads.

## The three things that shape this feature

### 1. A scenario belongs to the project; a bar is drawn against a network

A scenario names no network, and that is the point: one disruption story is replayed against every
configuration variant, which is what makes the comparison view mean anything. But a
timeline cannot be drawn without a network - the axis counts periods, a row needs a node's name, and
whether a window fits inside the run is a question about a horizon.

So the network is a **picker on the page**, not a route parameter. Switching it re-lays the same
scenario against a different clock and rewrites nothing. It also travels with every event write as
`?networkId=`, because that is where the server resolves the target and measures the window.

Watching one scenario against a 1-day network and then a 2-hour one is the fastest way to see what a
period change costs, and it is a read-only act.

### 2. Bars are positioned in periods and labelled in the declared unit

An event says "starts 4 weeks in, lasts 10 days" - never "period 28", because two variants
need not share a period length and "period 28" would be a different moment in each. `timeline.ts`
converts each event onto the selected network's clock to place the bar, and the bar's text stays what
the researcher typed. Change the network: every bar moves, every label stays.

The event editor uses **`shared/unit-value`**, the same value-plus-unit field the network editor uses
for lead times and processing times. Picking a different unit restates the value -
4 weeks becomes 28 days, not 4 days - and a new event opens on the network's period unit.

### 3. Two refusals the panel warns about before the server does

- **`EVENT_EXCEEDS_HORIZON`** (422) - the window ends after the run does, so its recovery is never
  observed and any metric over it measures the truncation. The editor runs the same
  arithmetic client-side and says so before the save; the server is still the authority, and its
  message carries the periods and the horizon.
- **`EVENT_TARGET_INVALID`** (422) - the target does not resolve in that network. For a REGION event
  that includes a tag no node carries, which is refused rather than warned about: the run would
  complete and the results would show a network shrugging off a disruption it never received.

A scenario legitimately outlives the network its events were written against, so the timeline draws
an unresolved row rather than hiding it, and says so in the gutter.

## Files

```text
scenario-builder/
 ├─ scenarios.store.ts           the project's scenario list: create, duplicate, delete
 ├─ scenario-builder.store.ts    one open scenario, the picked network, and event writes
 ├─ timeline.ts                  pure grouping and geometry - no Angular, no HTTP
 ├─ scenario-list/               the list page
 ├─ event-timeline/              the Gantt chart (presentational)
 └─ event-editor/                the per-bar panel
```

`timeline.ts` is deliberately free of Angular, like `core/time-units.ts` and
`network-editor/echelon-rules.ts`: the conversion from a declared duration to a bar position is the
part that has to be right, and pure functions are the version of it that can be checked.

## `event-editor/` has two hosts (FR-16)

The timeline is one of them; the network editor's disruptions panel is the other. That is the point
of it being a component rather than a page - severity, window, recovery profile and probability have
**one** implementation, so the canvas and the Gantt chart cannot drift into meaning different things
by the same fields.

Two consequences land here.

**It injects no store.** Everything it needs arrives as an input and everything it does leaves as an
output; its one service is `core/RegionNodesService`, which is the server's answer either way.
`ScenarioBuilderStore.previewRegion` moved there when the second host appeared.

**`saved` emits an array.** The editor can be *aimed* at several targets at once (a canvas
multi-selection), and then one draft becomes one event per target - same window, same severity,
different target. Nothing on this page aims it, so the array here is always one request long, and
`scenario-builder.component.saveEvent` reads the first and says why.

## Region previews

A REGION event names a `node.region` tag, and nothing on the event says which nodes that is. The
editor asks `GET /networks/{id}/region-nodes?region=…` through `core/RegionNodesService` as the tag
is picked and lists the matched nodes underneath - server-side rather than filtered from the loaded
nodes, because a client-side filter would be a second implementation of the resolution, free to drift
from the one a simulation run will use. `GET /networks/{id}/regions` is the catalogue the picker
offers.

The editor also raises `regionPreviewed` with each answer. The timeline ignores it; the network
editor forwards it to the canvas, so the matched nodes light up *while the tag is being chosen*.

## Duplicate is the primary action on the list

More than create. A scenario is a story with a shape - three events, particular severities, a
recovery profile chosen for a reason - and exploring "the same fire two weeks later" by retyping it
is how two scenarios end up differing in a way nobody intended. The copy is deep and server-side.

Deleting a scenario asks for the **project's** name, the same typed confirmation the network deletion
uses: the scenario goes with every event in it and there is nothing to fork back to.
Deleting a single event is a plain confirm - one bar is a few fields a researcher can retype.
