# Network editor - manual test script

Every interaction the editor offers, numbered, with the expected result for each.

Sections A–K cover the canvas, the panel, undo/redo, persistence and the fork path. Sections L–O
cover units: the value-plus-unit fields, the network time settings, the resolution banner, and what
the canvas labels say (FR-13). Section P is housekeeping - tests and the build. Section Q
covers authoring disruptions from the editor (FR-16), and its
step 145 is the one that matters most: a frozen network must still take a scenario. Section R covers
running from the editor (FR-17) - the baseline run, the provisional figures, the report in place,
and the run-keyed comparison - and, from step 160a, the **run history** of FR-21: every run of the
network listed on screen, a completed one selected and *watched* rather than re-run, and step 160q
as the acceptance test (close the editor, re-open it, the previous runs are listed and watchable).
Section S covers the playback speed of FR-18, and its step 170 is
section S's step 145: a frozen network must still take a playback speed. Section T is the playback
itself - the transport bar, the three canvas channels, and a run that starts animating the moment it
finishes. Section U is the element inspector: the canvas answers *how full*, the inspector answers
*how much*, for the one element selected. Section V is the **network dashboard** of FR-19 - the same
question asked of the whole network, on the property panel's empty selection, and the one section
that has something to check on a network with no run at all. Section W is the **acceptance walk**:
one disruption authored on the canvas, run, played back and read off all four surfaces in a single
sitting, with every figure stated. All of them continue the same numbering, and every figure they
assert is derived by hand in
`../snrm-backend/samples/four-echelon-playback/README.md` §5.1, §6.1, §6.4, §6.5 and
§8.2.

## Setup

**S1.** Backend running: `mvnw.cmd spring-boot:run` in `../snrm-backend`.
→ <http://localhost:8080/actuator/health> returns `{"status":"UP"}`.

**S2.** Frontend dependencies (Cytoscape and its two extensions are new):

```bash
npm install
```

→ `cytoscape`, `cytoscape-dagre`, `cytoscape-edgehandles` and `dagre` appear under `node_modules`;
no peer-dependency errors. There is no `@types/cytoscape`: Cytoscape ships its own `index.d.ts`,
which TypeScript prefers, and the two extensions are typed locally in `src/types/`.

**S3.**

```bash
npm start
```

→ Compiles clean. **No `CommonJS or AMD dependencies can cause optimization bailouts` warning** -
the four packages are allow-listed in `angular.json`.

**S4.** Sign in at <http://localhost:4200>, open a project, create a network named `Editor Test`,
click **Open in editor**.
→ URL is `/projects/{p}/networks/{n}/editor`. Three panes: palette left, canvas centre, property
panel right. Toolbar reads **All changes saved** with a green dot, and carries a **⏱ 1 d × 52**
button - the network's period and horizon. Canvas shows a faint 20 px grid.

**S5.** For sections L and M you need a product in the project catalogue. On the project dashboard
press **Products**, then add `Gearbox` with unit value `250`.

→ 201, and the row appears. The dashboard's amber *"This project has no products yet"* banner is
gone, and its **Products** button now carries a count badge. Re-open the editor afterwards so it
picks the catalogue up - the editor reads it once, at load.

> The panel can also create one without leaving the canvas; step 91a covers that path.

Keep the browser devtools **Network** tab open throughout - several steps assert on requests.

---

## A. Adding nodes - palette drag and drop

**1.** Drag the **Supplier** tile from the palette onto empty canvas.
→ While dragging over the canvas, a blue outline appears around it and the background tints. On drop
the outline clears, a slate rounded-square node labelled `Supplier 1` appears **at the drop point**,
it is selected (blue border), and the property panel shows its fields with **Name focused and its
text selected**. Network tab shows one `POST /api/v1/networks/{n}/nodes` returning **201**.

**2.** Check the POST request body from step 1.
→ `posX`/`posY` are the drop coordinates, not null. `type` is `SUPPLIER`, `fixedCost`/`varCost`/
`failureProb` are `0`. The two unit-bearing fields are **objects, not bare numbers**:
`"capacity": {"value": null, "timeUnit": "DAY"}` - unconstrained, but still stated in a unit - and
`"processingTime": {"value": 0, "unit": "DAY"}`. Both units are the network's period unit.

**3.** Type `Shanghai Supplier` and press Tab.
→ Name updates on the node label. Toolbar goes to **Unsaved changes (1)**, then to **Saving…** and
**All changes saved** within ~2 s. One `PATCH /api/v1/networks/{n}/nodes` with a body of
`{"nodes":[{"nodeId":…,"name":"Shanghai Supplier"}]}` - **only the name**, no other field.

**4.** Drag a **Plant**, a **DC** and a **Customer** tile onto the canvas, left to right.
→ Four distinct colours *and* four distinct shapes: square, hexagon, circle, diamond. Names
auto-increment (`Plant 1`, `DC 1`, `Customer 1`). Each is a separate 201.

**5.** Drag a second **DC** tile on.
→ Named `DC 2`, not `DC 1`. No 409.

**6.** Reload the page (F5).
→ Every node reappears **at the same coordinates**. This is `pos_x`/`pos_y` surviving reload.

## B. Adding nodes - double-click shortcut

**7.** Single-click the **Plant** palette tile (do not drag).
→ The tile takes a highlighted border. No node is created.

**8.** Double-click empty canvas.
→ A `Plant 2` node appears **at the pointer**, selected, panel open with the name focused. This is
the "last-used type" shortcut.

**9.** Drag a **Customer** tile on, then double-click empty canvas again.
→ The new node is a **Customer**, not a Plant: dragging a tile also sets the last-used type.

**10.** Double-click **on an existing node** (not empty canvas).
→ Nothing is created. The double-click shortcut is background-only.

## C. Connecting nodes - edgehandles

**11.** Hover a node.
→ Four small blue dots appear, one on each corner of the node's bounding box. Move the pointer off
the node and they disappear; move from the node onto a dot, or between two dots, and they stay.

**11a.** Hover a Customer (diamond) and a DC (circle).
→ The dots still sit on the bounding-box corners, so on those shapes they float just clear of the
drawn outline. Crossing that gap must not make them vanish.

**11b.** Drag the *body* of a node (not the handle).
→ It moves. Drawing and dragging must both work - this is why draw mode is not used.

**12.** Drag from **any one** of the Supplier's four dots towards the Plant, and pause with the
pointer **over the Plant**. Repeat from a different corner.
→ A dashed rubber-band arrow follows the pointer. The Plant takes a **green** halo. Release.
→ A solid grey arrow with a triangular arrowhead appears from Supplier to Plant, is selected, and
the property panel shows link fields. **The arrow is labelled `1 d`** - one period, in the network's
own unit. One `POST /api/v1/networks/{n}/links` returning **201**, body
`"leadTime": {"value": 1, "unit": "DAY"}`, `"capacity": {"value": null, "timeUnit": "DAY"}`,
`unitCost: 0`, `failureProb: 0`. (A new arc gets one period of transit rather than zero: an arc that
delivers in the period it ships is a degenerate special case.)

**12b.** Drag from a handle out over **empty canvas** and release there.
→ The rubber band disappears cleanly and no link is created. It must **not** stay on the canvas
following the pointer - that was the symptom of `canConnect` throwing on an empty target and
aborting the extension's teardown half-way.

**12c.** Drag from a handle and, **while still holding the mouse button**, press **Esc**.
→ The rubber band vanishes immediately, no link is created, and the source node loses its highlight.
Release the button: nothing further happens. The canvas is fully interactive again - pan, zoom and
node dragging all work, confirming the gesture's viewport locks were released.

**12d.** Press **Esc** with a node selected and no draw in progress.
→ The selection clears. Escape only cancels a draw when there is one to cancel.

**13.** Drag from the Plant's handle and hover over the **Supplier**.
→ The Supplier goes **red** and the cursor shows "not allowed". Release over it.
→ **No link is created**, no request is sent. (A link into a SUPPLIER is blocked.)

**14.** Drag from the **Customer**'s handle and hover over the DC.
→ The DC goes **red**. Release. → No link, no request. (A link out of a CUSTOMER is blocked.)

**15.** Drag from a node's handle back onto **itself**.
→ Red, and no link on release. (Self-loops rejected at the gesture level.)

**16.** Drag Supplier → Plant a **second** time.
→ The Plant goes **red**. Release. → No link, no request, no 409. (Duplicate ordered pair rejected
at the gesture level, before the API sees it.)

**17.** Drag **DC 1 → DC 2** - the lateral transshipment case.
→ DC 2 goes **green**. Release. → The link is created, and drawn **amber and dashed, labelled
`⚠ 1 d`** - the badge shares the label with the declared lead time. The property panel shows
*"Lateral link within the same echelon (e.g. DC-to-DC transshipment)."* This is the "allowed
with a warning badge" case.

**18.** Draw **DC 1 → Plant 1**.
→ Green target, link created, amber dashed, badge reads *"Runs upstream, against the usual echelon
order."*

**19.** Draw **Supplier → Customer 1**.
→ Green, created, amber dashed, badge *"Skips an echelon."*

**20.** Draw **Plant 1 → DC 1**.
→ Green, created, and drawn **solid grey, labelled just `1 d`** with no ⚠ - the textbook forward arc.

**21.** Select `DC 2` and change its **Type** to `PLANT` in the panel.
→ The node changes colour and shape (teal circle → indigo hexagon), **and the DC 1 → DC 2 link's
badge changes by itself**: the arc is now DC → PLANT, so the badge goes from *"Lateral link within
the same echelon"* to *"Runs upstream, against the usual echelon order."* Nobody touched the link -
the badge is derived from current node types, not stored on the link.

## D. Selection

**22.** Click a node.
→ Blue border; panel shows that node alone, headed with its name and `Node #id`.

**23.** Ctrl-click (or Shift-click) two more nodes.
→ All three have blue borders. Panel header reads **3 nodes** and the subtitle says *"Bulk edit -
only the field you change is sent."*

**24.** Click empty canvas.
→ Selection clears. Panel reads **Nothing selected**.

**25.** Click **Box select** in the toolbar, then drag across several nodes on empty canvas.
→ The button is filled (pressed). A rubber-band rectangle appears instead of the canvas panning, and
everything inside it is selected on release.

**26.** With Box select still on, try to drag the empty canvas to pan.
→ It box-selects rather than panning. Turn Box select **off**. → Dragging empty canvas pans again.

**27.** Select one node and one link together (Ctrl-click).
→ Panel reads *"The selection mixes nodes and links… select one kind on its own."* No fields.

## E. Property panel - single and bulk edit

**28.** Select one node. Set **Capacity** to `500` and Tab out.
→ Within 2 s: one `PATCH /networks/{n}/nodes` carrying only
`{"nodeId":…,"capacity":{"value":500,"timeUnit":"DAY"}}` - the field whole, and nothing else.

**29.** Select **three** nodes with different fixed costs.
→ The **Fixed cost** field is empty with a `-` placeholder and the label carries an italic *mixed*
marker. **Name** is not shown at all (names are unique per network, so it is single-selection only).

**30.** With those three selected, type `250` into **Fixed cost** and Tab out.
→ One PATCH with **three** `nodes` entries, each carrying **only** `fixedCost`. Verify in the
request body that no other attribute is present - the nodes' differing capacities must survive.
Click each node in turn: capacities unchanged, fixed cost now 250 on all three.

**31.** With three nodes still selected, look at the **×** beside Capacity.
→ It is **disabled**, with a tooltip explaining that a bulk PATCH cannot clear a field.

**32.** Select a **single** node that has a capacity, and click the **×** beside Capacity.
→ One `PUT /api/v1/nodes/{id}` (not a PATCH), sent immediately. The field goes back to the
`unconstrained` placeholder, **and the unit dropdown keeps its unit** - an unconstrained capacity is
still a well-formed pair, so giving it a number later needs no unit invented for it. Check the PUT
body: it carries `processingTime` too. A PUT clears what it omits, so the panel sends every field it
means to keep.

**33.** Type `-5` into **Fixed cost** and Tab out.
→ Red inline message *"Must not be negative."*, **no request is sent**.

**34.** Type `1.5` into **Failure probability** and Tab out.
→ *"A probability is between 0 and 1."*, no request.

**35.** Select a single link. Set **Lead time** to `3` and Tab out.
→ `PATCH /networks/{n}/links` with only `{"linkId":…,"leadTime":{"value":3,"unit":"DAY"}}`. The
canvas label changes to `3 d`.

**36.** Look at the link panel header.
→ Endpoints are shown as `Source → Target` with the note *"Endpoints cannot be changed - delete the
link and redraw it."* There is no control to repoint them.

## F. Drag-move and grid snap

**37.** Drag one node across the canvas and release.
→ It follows the pointer smoothly. Within 2 s, one
`PATCH /api/v1/networks/{n}/nodes/positions` with a **single** entry.

**38.** Drag a node continuously for ~6 seconds without releasing, then release.
→ At most one positions PATCH per 2 s **and only one entry per node** in each - repeated moves of
the same node coalesce rather than queueing sixty requests.

**39.** Select three nodes, drag one of them.
→ All three move together. On release: **one** positions PATCH with **three** entries, and **one**
undo step (see step 45).

**40.** Turn **Grid snap** on (button fills). Drag a node.
→ On release it snaps to the nearest 20 px intersection of the background grid. The PATCH body
carries the snapped coordinates (multiples of 20), not the raw pointer position.

**41.** Turn Grid snap off and drag again.
→ The node lands wherever it is dropped; coordinates are no longer multiples of 20.

## G. Auto-layout

**42.** Press **Auto-layout** on this network (which now has manual positions).
→ A confirm appears: *"This network already has manual node positions. Auto-layout will overwrite
every one of them."* - because manual positions take precedence. Press **Cancel**.
→ Nothing moves.

**43.** Press **Auto-layout** again and confirm.
→ Nodes rearrange into **four vertical columns, left to right in echelon order**: suppliers,
plants, DCs, customers. One positions PATCH carrying **every** node. The view fits to the graph.

**44.** Press **Ctrl-Z**.
→ Every node returns to where it was before the layout, in **one** step.

**45.** For the "imported network without coordinates" case: create a second network, and insert
nodes through the API (or Swagger) **without** `posX`/`posY`, then open it in the editor.
→ The layout runs **automatically on load**, with no button press and no confirm. Nodes are
echelon-columned. (With coordinates present, it never auto-runs - that is step 42's behaviour.)

## H. Undo / redo

**46.** Hover the **Undo** button.
→ Tooltip names the specific action, e.g. *"Undo: Move 3 nodes"*.

**47.** Drop a new node, then press **Ctrl-Z**.
→ The node disappears. One `DELETE /api/v1/nodes/{id}`.

**48.** Press **Ctrl-Y** (or Ctrl-Shift-Z).
→ The node reappears with the same name and position. One `POST /networks/{n}/nodes`.
**Note the new id in the response** - it is different from the original. That is expected.

**49.** With that re-created node, draw a link from it, then Ctrl-Z twice and Ctrl-Y twice.
→ The link comes back attached to the **re-created** node, not to a stale id. No 404 or
`LINK_CROSS_NETWORK`. (This is the id remap doing its job.)

**50.** Move a node, edit its capacity, connect it to another node - then press Ctrl-Z three times.
→ The connect is undone, then the edit, then the move, in that order. Each is one step.

**51.** After undoing twice, drop a new node.
→ **Redo becomes disabled.** A new action clears the redo branch.

**52.** Press Ctrl-Z with focus inside the **Name** text box.
→ The browser's own text undo applies to the field; the canvas does **not** undo. Editor shortcuts
stand down while you are typing.

**53.** Hold Ctrl-Z down to repeat quickly.
→ Steps unwind one at a time without duplicate DELETEs; the Undo button shows a disabled/spinner
state between network-backed steps rather than firing twice.

## I. Delete with dependent-data confirm

**54.** Select a node that has two links attached, and press **Delete** (or the toolbar button).
→ A confirm dialog headed *"Delete selection"*, whose message notes that undo restores the records
with **new ids**, listing:
- `Nodes: <name>`
- `2 link(s) will be deleted: A → B; C → D`
- `N per-product row(s) on these nodes will be deleted.`
- `Disruption events targeting these nodes cannot be listed: the scenario API is not built yet.`

**55.** Press **Cancel**.
→ Nothing is deleted, nothing is sent.

**56.** Press Delete again and confirm.
→ The node and **both its links** disappear. Requests go out as `DELETE /links/{id}` for each link
**before** `DELETE /nodes/{id}`.

**57.** Press **Ctrl-Z**.
→ The node **and both links** come back, correctly attached. (The delete snapshot captured the
cascading links.)

**58.** Select two links only and press Delete.
→ The confirm lists the two links and **no** per-product or scenario-event lines (those are node
concerns).

**59.** Press Backspace instead of Delete with a selection.
→ Same confirm. With focus in a text field, Backspace deletes characters and no dialog appears.

## J. Persistence, dirty indicator and blur

**60.** Move a node and watch the toolbar.
→ **Unsaved changes (1)** with an amber dot → **Saving…** blue → **All changes saved** green,
within ~2 s.

**61.** Move a node and immediately click on another browser window (blur the tab).
→ The PATCH fires **immediately**, without waiting out the 2 s.

**62.** Move a node and press **Ctrl-S** within the 2 s.
→ Flushes immediately. The browser's own Save-page dialog does **not** open.

**63.** Move a node and click **Save now** while it says *Unsaved changes*.
→ Same immediate flush.

**64.** Stop the backend, then move a node and wait 2 s.
→ Indicator turns red: **Save failed**, and a warning banner explains the failure and says the edits
are still there. **Save now** and **Discard** buttons appear.

**65.** Restart the backend and press **Save now**.
→ The PATCH succeeds and the indicator returns to **All changes saved**. Nothing was lost.

**66.** Repeat step 64, then press **Discard**.
→ The unsent edits are thrown away and the network reloads from the server; the node is back at its
saved position.

**67.** With the backend stopped and a failed save outstanding, click the **← Project** breadcrumb.
→ A dialog asks *"Leave with unsaved edits?"*. **Stay** keeps you in the editor; **Leave and
discard** navigates away.

**68.** With everything saved, click **← Project**.
→ Navigates immediately with no dialog.

**69.** With a failed save outstanding, press F5.
→ The browser's native "leave site?" prompt appears.

**70.** Move a node and, within 2 s, press Ctrl-Z.
→ The node returns, and **at most one** positions PATCH is sent carrying the final (original)
position - the move and its undo collapse in the pending batch.

## K. Fork-to-variant, and discarding the runs, on a frozen network (FR-20)

> **Steps 77a onward are FR-20** and are numbered with letters so the rest of this document keeps its
> numbers. They need the backend at the stage that has `GET /networks/{id}/runs`,
> `DELETE /simulations/{runId}` and `DELETE /networks/{id}/runs`.

**§R runs a real simulation from the editor**, which is the natural way to freeze a network now. The
SQL below is still the quickest way to get a frozen network without waiting for a run, and is what
steps 71–77 assume. Note the network id from the editor URL and run, against the `snrm` schema:

```sql
INSERT INTO disruption_scenario (project_id, name, num_replications, seed)
  SELECT project_id, CONCAT('Freeze probe ', id), 1, 1 FROM network WHERE id = <NETWORK_ID>;
INSERT INTO simulation_run (network_id, scenario_id, status, params_json)
  VALUES (<NETWORK_ID>, LAST_INSERT_ID(), 'DONE', '{}');
```

To thaw it again afterwards (`DONE`, `RUNNING` and `QUEUED` all lock; `FAILED` and `CANCELLED` do
not):

```sql
DELETE FROM simulation_run WHERE network_id = <NETWORK_ID>;
```

**That `DELETE` is now a supported gesture rather than a database workaround** - it is exactly what
FR-20's *Unlock…* does, and steps 77a onward walk it. Keep the SQL for setting up state; do not use
it to thaw once you are testing 77a.

**71.** Reload the editor on that network.
→ A **Frozen** badge sits beside the network name. An amber banner reads *"A simulation run has
frozen this network…"* with a **Fork a variant** button. Palette tiles are greyed and refuse to
drag. Nodes cannot be moved. The property panel is read-only with a matching note. The **Delete**
button is disabled.

**72.** Double-click empty canvas.
→ No node is created; the **fork prompt** opens instead.

**73.** Read the fork dialog.
→ It names the network and version, explains the freeze, and states in bold that **the edit you just
attempted is not carried over**. The name box is optional, with a placeholder offering the next
version.

**74.** Press **Keep viewing read-only**.
→ The dialog closes, the editor stays frozen and read-only. Nothing was sent.

**75.** Press **Fork a variant** in the banner, leave the name empty, and confirm.
→ One `POST /api/v1/networks/{id}/clone` returning **201**. The app navigates to the **new**
network's editor. The Frozen badge is gone, the version number has incremented, and the palette
works again. The nodes and links are all present.

**76.** Confirm the attempted edit was not applied.
→ The variant matches the frozen network exactly; the node you tried to create in step 72 is not
there.

**77.** Reactive path: freeze the *variant* with the same SQL, but **keep the editor open** on it
(do not reload). Now move a node and wait 2 s.
→ The PATCH comes back **409 `NETWORK_IMMUTABLE`**. The editor does **not** retry: the banner
explains, and the fork prompt opens by itself.

### Discarding the runs instead (FR-20)

The other way out. Fork keeps the result and gives you a copy to edit; discarding says the run was a
test. Everything below is on a frozen network - either the SQL one above or, better, one frozen by a
real run from §R.

**77a.** Look at the frozen banner and the fork prompt.
→ The banner now reads *"…Fork a configuration variant to keep the result and carry on - or discard
the runs to edit this network in place (FR-20)"* and carries **two** buttons: **Fork a variant** and
**Unlock…**. The fork prompt (double-click empty canvas to raise it) has gained a third choice below
the lever field: **Discard this network's runs and edit in place…**, under the sentence that frames
the trade. Its footer still offers *Keep viewing read-only* and *Fork a variant and edit that* - the
fork path of steps 71–76 is unchanged.

**77b.** Press **Unlock…** on the banner.
→ One `GET /api/v1/networks/{id}/runs` fires first (check the Network tab), then a typed
confirmation opens: **"Discard this network's runs?"**. It names the network and version, states the
count, and lists the runs as e.g. `Baseline (run #12, DONE)`. Bullets say that each run's metric
suite and **all three** of its time series go with it, that the network's **structural metrics are
not touched**, that the network becomes editable again, and that forking is the opposite trade.

**77c.** Type the wrong phrase - the network's name without the version, say `Baseline`.
→ The field goes red and the danger button stays **disabled**. The label above it reads *Type the
network name and version `Baseline v1` to confirm*. Press Escape / *Keep the runs*: nothing is sent.

**77d.** Press **Unlock…** again, type the phrase exactly, and confirm.
→ One `DELETE /api/v1/networks/{id}/runs` returning **204**, immediately followed by
`GET /api/v1/networks/{id}`. The **Frozen** badge and the amber banner disappear **without a
reload**, the palette tiles drag again, the property panel is editable and the toolbar **Delete**
button is live. Note what did *not* happen: no page reload, and no second endpoint - the banner
cleared because the server answered `editable: true`, not because the client decided it had.

**77e.** Reload the editor.
→ Still editable. The freeze was never a client-side state.

**77f.** Now the derivation, which is the point of the whole design. Run **two** baseline runs from
§R against one network, let both finish, then open the results dashboard of the first and press
**Delete run** (type `run <id>`).
→ 204, and you land back on the simulations list. Return to the editor and reload: **still frozen**.
One locking run is left, and one locking run is enough. Delete the second the same way and reload:
**editable**. Nothing was reset between those two states except a row disappearing.

**77g.** With a run **in flight** (start a 500-replication run from §R and do not wait), press
**Unlock…**.
→ **No dialog opens.** The editor's red action banner reads *"A run is still executing (Baseline
(run #14, RUNNING) is RUNNING), so nothing can be discarded. The request is refused whole rather than
deleting the finished runs and leaving the network frozen by the unfinished one. Cancel it from the
run panel, then try again."* Confirm in the Network tab that **no `DELETE` was sent** - the editor
read the runs and declined to ask.

**77h.** Cancel that run from the run panel, wait for `CANCELLED`, then press **Unlock…** again.
→ The dialog opens and lists the cancelled run. Its unlock bullet reads *"None of these runs was
freezing the network…"* if the cancelled run is the only one - a `CANCELLED` run locks nothing, so
promising an unlock would be claiming an effect the deletion does not have.

**77i.** Per-run delete from the editor. Run a baseline from §R, let the report render in the run
panel, and press **Delete run** at the bottom of it.
→ The confirmation asks for `run <id>` (the id is in the `run #…` chip above it). On confirm: one
`DELETE /api/v1/simulations/{runId}` → **204**, then `GET /networks/{id}`. The report disappears, the
panel returns to its two buttons, **playback stops and the transport bar over the canvas goes away**
- the run it was replaying no longer exists - and the frozen banner clears if that was the last
locking run.

**77j.** Try to delete a run while its job is still running, from the results dashboard
(`/projects/{p}/simulations/{runId}` opens on a `QUEUED` or `RUNNING` run by design).
→ **409 `RUN_ACTIVE`** in the error banner, naming `DELETE /jobs/{jobId}` as the first call - and the
**Cancel** button beside it is exactly that. Nothing was deleted; refresh and the run is still there.

**77k.** A restored archive result. Restore a project archive (§ the project list's import), open one
of the restored runs' networks, and press **Unlock…**.
→ The dialog carries an extra line starting **⚠**: *"One of these is a restored archive result
(imported 2026-08-04): computed by another installation and brought in from a project archive.
Deleting them destroys the only copy this application holds - the archive file itself is unaffected,
and restoring it again re-creates them."* The run is still listed as `…, restored`. Deleting is
**allowed** - refusing would make a restored project permanently uneditable.

**77l.** The documented path to deleting an evaluated network (FR-15). On the project dashboard, a
frozen network offers no **Delete** at all. Discard its runs via 77d, return to the dashboard and
reload.
→ **Delete** is now offered, with its own typed confirmation asking for the **project's** name
(that phrase is FR-15's and is unchanged).

## L. Units in the property panel

Work on the `Editor Test` network, whose clock is still **1 DAY / 52 / NEAREST**.

**78.** Select a link and look at **Lead time**.
→ A number box and a unit dropdown side by side. The dropdown reads `d · days` - the network's
period unit, which is what a duration field defaults to. Under the field: *"= 1 period on this
network's clock"*.

**79.** Open the unit dropdown.
→ Seven options, finest first: `s · seconds` … `y · years (365 d)`. Hovering the dropdown shows
*"A month is always 30 days and a year 365 - fixed lengths, not calendar arithmetic"*.

**80.** Type `36` into the box, Tab out, then change the unit from **days** to **hours**.
→ The box reads **864**, not 36. Changing a unit **re-displays** the value; it never reinterprets
the number - 36 days *is* 864 hours. One `PATCH /networks/{n}/links` with
`{"leadTime":{"value":864,"unit":"HOUR"}}`. The canvas label goes to `864 h`.

**81.** Press **Ctrl-Z** once.
→ Back to `36 d` - value *and* unit - in a **single** step. Hover Undo beforehand: the tooltip reads
*"Undo: Set lead time unit on 1 link"*.

**82.** Set the lead time to `36` **hours** (type 36, pick hours), then read the line under the field.
→ *"= 2 periods on this network's clock"*. 36 h is 1.5 days and NEAREST rounds up. This is the
restatement, not a warning - section N is where the warning appears.

**83.** Set the lead time to `6` **hours**.
→ The line reads *"= 0 periods - the engine treats this as instantaneous"*.

**84.** Set the lead time back to `1` **day**. Now type `12` into the box and, **without tabbing
out**, change the unit to **hours**.
→ The box reads **288** and one PATCH goes out carrying `{"value":288,"unit":"HOUR"}`. Two things to
check here: the typed number travelled *with* the unit change, so this is **one** undo step rather
than two, and it was never committed against the old unit on the way. The rule does not change for a
half-typed number - what is on screen is restated, never reinterpreted. To enter "12 hours" on a
field showing days, pick **hours** first and then type.

**85.** Select **two links with different lead times**.
→ The Lead time box is empty with a `-` placeholder and the label carries an italic *mixed* marker.
The unit dropdown falls back to the period unit. No restatement line (there is no single value to
restate).

**86.** With those two selected, type `2` and Tab out.
→ One PATCH with **two** entries, each `{"leadTime":{"value":2,"unit":"DAY"}}` - the displayed unit
applied to both.

**87.** Select **two links whose lead times agree in number but not in unit** (set one to `6 h` and
one to `6 d` first).
→ Still **mixed**. 6 h and 6 d are different lengths of time; showing "6" for both would invite an
edit that means one of them.

**88.** With two links selected, change the **unit** to weeks.
→ **One** PATCH with two entries, each carrying that link's *own* value restated in weeks
(6 h → `0.0357142857143`, 6 d → `0.857142857143`). Each element restates its own number; the whole
selection is one undo step. A repeating decimal is kept to twelve significant digits - and Ctrl-Z
brings the exact declared pairs back, which is the real answer to conversion precision.

**89.** Select a node. Set **Capacity** to `500` per **day**, then change the unit to **hour**.
→ The box reads **20.8333333333**. A rate scales the other way from a duration: the same flow over a
shorter denominator is a smaller number. The line under the field reads *"= 500 per period"* both
before and after - the throughput never changed, only how it is said.

**90.** Set a node's **Processing time** to `12` hours.
→ Restatement line: *"= 1 period on this network's clock"*. There is **no ×** beside processing
time: zero is its empty form, and it is typed rather than cleared.

**91.** Select a **CUSTOMER** node and find **Products at this node**.
→ Either the rows, or *"No per-product parameters yet…"* plus an **Add a product** picker listing
`Gearbox` from step S5.

**91a.** Below the picker, find **New product**. Type `Gearbox` and press **Create**.
→ Refused inline: *"The project already has a product with that name - pick it above."* No request
is sent; the 409 the API would answer with is one the client can see coming.

**91b.** Type `Housing` instead and press **Create**.
→ **Two** requests, in order: `POST /api/v1/projects/{p}/products` returning **201**, then
`PUT /api/v1/nodes/{id}/products/{productId}`. A `Housing` card appears on the node, and `Housing` is
*absent* from the **Add a product** picker - it is on this node now.

**91c.** Press **Ctrl-Z** once.
→ The `Housing` card goes and one `DELETE /nodes/{id}/products/{productId}` is sent. **No request
deletes the product itself**, and `Housing` reappears in the picker. Undo unwinds putting it on the
node, never the catalogue entry: a product is project-scoped and every variant of the project can
already see it.

**91d.** Open the project's **Products** screen in another tab.
→ `Housing` is listed with unit value **0**. The shortcut creates the entry; pricing it is this
screen's job.

**92.** Back on the node, pick `Gearbox` and press **Add**.
→ One `PUT /api/v1/nodes/{id}/products/{productId}` with `demand` and `holdingCost` as
`{"value":0,"timeUnit":"DAY"}` objects. A card appears with Demand, Holding cost, Initial inventory
and Safety stock.

**93.** Set **Demand** to `120` per **week**.
→ One PUT carrying **all four** figures - the endpoint is a full replacement, so the other three
travel with it. Restatement line: *"= 17.1429 per period"* (120 a week on a one-day clock).

**94.** Change the Holding cost unit while its value is 0.
→ The unit changes, one PUT, no arithmetic surprise. A zero carries nothing the choice could
distort.

**95.** Press **Ctrl-Z** three times.
→ The demand unit, then the demand value, then the row itself unwind; the third undo sends
`DELETE /nodes/{id}/products/{productId}`, because there was no row before it was added.

**96.** Add the product again and set **Demand** to `40` per **day**. Now add a product to a
*different* node.
→ Its Demand dropdown opens on **days** - the last unit picked in that field. Durations do
not work this way: they always open on the period unit.

**97.** Select a node and press the **×** on a product card.
→ One `DELETE`. Ctrl-Z puts the row back with its demand, holding cost, inventory and safety stock
intact.

## M. Network time settings

Build the worked example first, so the numbers below are checkable against
`../snrm-backend/docs/time-units-worked-example.md`: a Supplier, a Plant, a DC and a
Customer in a chain, with lead times **6 h**, **36 h** and **2 wk**, and processing times **12 h** on
the Plant and **4 h** on the DC.

**98.** Press the **⏱ 1 d × 52** button on the toolbar.
→ A dialog headed *"Network time settings"*: period length (value + unit), a **Suggest period**
button, horizon in periods, and a rounding-policy dropdown. A blue line reads *"A run currently
covers 52 d. These settings would cover 52 d."*

**99.** Change the period to `2` **hours** and watch the blue line.
→ It now reads *"…These settings would cover 4 d 8 h"* and a button appears: **Keep the same span
(624 periods)**. This is the trap: the horizon does not follow a period change on
its own, because that would silently redefine the study.

**100.** Press **Keep the same span (624 periods)**.
→ The horizon field becomes `624`. The blue line agrees with the current span again.

**101.** Set the period back to `1 d` and the horizon back to `52`, then press **Suggest period**.
→ One `GET /api/v1/networks/{n}/time-validation`. The period becomes **2 h** and a note explains it
is *"the coarsest period that keeps every declared duration within 10%"* and to check the horizon.
Nothing is saved yet: a period change is a decision about the model, not a correction to it.

**102.** Read the rounding-policy dropdown's hint as you switch between the three.
→ NEAREST *"unbiased across many values"*, UP *"overstates delays - the conservative choice"*, DOWN
*"understates delays, so it flatters the network"*. Below: *"A property of the network, not of each
duration, so two variants compared side by side discretise identically."*

**103.** With **2 h / 624 / NEAREST** in the form, press **Apply time base**.
→ One `PUT /api/v1/networks/{n}/time-base` carrying all three fields together. The dialog closes,
the toolbar button reads **⏱ 2 h × 624**, and the warning banner of section N **empties** - at a
two-hour period every duration in the worked example converts exactly.

**104.** Check what the change did *not* do.
→ Every link label still reads in its own unit: `6 h`, `36 h`, `2 wk`. Declared values are untouched
by a period change - only what the engine makes of them moved.

**105.** Re-open the dialog and press **Apply** without changing anything.
→ The button is **disabled**: there is nothing to apply.

**106.** Set the period to `0` and Tab out.
→ Red inline message *"The period must be greater than zero."*, Apply disabled. Clear the horizon
box → *"Enter a whole number of periods, at least 1."*, Apply disabled.

**107.** Freeze the network with the SQL in section K, reload, and open the time settings.
→ An amber note in the dialog explains that results are stated in periods, so redefining the period
is refused, and that applying will offer to fork a variant.

**108.** Press **Apply time base** on that frozen network.
→ The settings dialog closes and the **fork prompt** opens in its place - not two modals stacked, and
no retry. This is the same path a blocked node edit takes. Cancel it, thaw the network
with the `DELETE` in section K, and reload.

## N. Resolution warnings

Still on the worked-example network of section M, with the lead times and processing times it names.
Set the clock back to **1 DAY / 52 / NEAREST** in the time-settings dialog before starting - that is
the period the four findings below are computed against.

**109.** Look above the canvas.
→ An amber banner: *"Some durations do not survive this network's period of 1 d (NEAREST)."* with
*"4 warnings"* beside it, and one row per finding. Requests show one
`GET /api/v1/networks/{n}/time-validation`.

**110.** Read the rows.
→ Each states **both** numbers - e.g. *"WARNING · SUP-1 → PLANT-1 · leadTime · declared 6 h → engine
uses 0 periods (−100%)"* - with the server's sentence underneath. The 2-week leg is **not** listed:
14 days divides exactly, and the banner lists what changed meaning, not what exists.

**111.** Click the `SUP-1 → PLANT-1` row.
→ That link is **selected on the canvas** (blue) and the property panel opens on it.

**112.** Click a row about a node (the 12 h or 4 h processing time).
→ That node is selected. Node rows and link rows both select; there is nothing else in the list yet.

**113.** Press the **×** on the banner.
→ It disappears. The editor is a workspace - nothing here refuses an edit; the same finding is an
error only during import.

**114.** Change nothing and reload the page.
→ The banner is back: dismissal is a per-session judgement about findings you have read, not a
stored preference.

**115.** Dismiss it again, then set the DC's processing time to `4` **days**.
→ Within about a second the banner **returns**, now without the 4 h finding. Dismissal is remembered
against the *content* of the report, so a report that says something new raises it again.

**116.** Dismiss it, then move a node (a change that touches no duration).
→ It stays dismissed. The findings are identical, so there is nothing new to say.

**117.** Set the `SUP-1 → PLANT-1` lead time to `1` **day**, and watch the banner.
→ Its row disappears within about a second of the save, without a reload. One extra
`GET …/time-validation` per settled burst of edits - not one per keystroke.

**118.** Press **Ctrl-Z**.
→ The row comes back: an undo is as much a change to the findings as the edit was.

**119.** With everything saved, open the time settings and apply a **1 minute** period with a
horizon of 1000.
→ The banner refills from the `PUT` response itself - no follow-up `GET …/time-validation` in the
Network tab - and now carries the `PERIOD_TOO_FINE` finding: *"Horizon of 20160 periods needed to
span a lead time of 2 wk at a period of 1 min; simulation may be slow."* (Apply with unsaved edits
outstanding and you will see one extra `GET`: the flush that precedes the save counts as an edit
landing.)

**120.** Stop the backend and edit a lead time.
→ The banner keeps showing its last findings and **no error banner of its own appears**. A failed
check is not a finding. Restart the backend; the next edit refreshes it.

## O. Labels and tooltips read in declared units

**121.** Look at the arcs of the worked-example network.
→ They read `6 h`, `36 h`, `2 wk` - never `0 periods`, `2 periods`, `14 periods`. The model reads
the way it was entered.

**122.** Open the time settings and change the period to `1 wk` (horizon 8). Look again.
→ The labels are **unchanged**: `6 h`, `36 h`, `2 wk`. A period change moves what the engine will do,
not what the user said.

**123.** Hover a node.
→ A dark tooltip above it: the node's name and type, then *"Processing time 12 h"* and *"Capacity 400
/ wk"* - each in its own unit - plus *"Region …"* if it has one. Never per-period figures.

**124.** Hover a node with no capacity.
→ The tooltip reads *"Capacity unconstrained"*.

**125.** Hover a node, then pan or zoom the canvas.
→ The tooltip disappears rather than drifting away from its node. Start a drag from a corner handle:
the tooltip goes immediately and the rubber band behaves exactly as in section C.

**126.** Hover a node and try to click *through* the tooltip onto the canvas beneath it.
→ The click lands on the canvas. The tooltip never takes pointer events, so it cannot swallow the
gesture it sits on top of.

**127.** Set a link's lead time to `0` days.
→ The label reads `0 d`, not blank. An arc that delivers in the period it ships is a modelling
choice worth seeing - and the banner will not flag it, because zero was declared rather than lost.

## P. Housekeeping

**128.** Run the unit tests:

```bash
npm test
```

→ `time-units.spec.ts`, `echelon-rules.spec.ts`, `editor-commands.spec.ts`,
`editor-persistence.service.spec.ts`, `disruption-overlay.spec.ts`, `element-captions.spec.ts`,
`playback-clock.spec.ts`,
`playback-channels.spec.ts`, `playback-preferences.service.spec.ts` and `playback.store.spec.ts` all
pass. The echelon suite covers every type pair; the persistence suite covers coalescing, the 2 s
ceiling and the failure-retains-pending behaviour; the time-units suite checks every conversion
against the backend's `docs/time-units-worked-example.md`, so a drift between the two ends of the
wire fails here rather than in a simulation result; the overlay suite asserts that a canvas badge
holds the timeline's own `placeBar` result, so the two surfaces cannot start describing the same
window differently; and the four playback suites pin the speed ladder and its four default horizons,
the pace-line wording section S reads back word for word, storage that is denied or tampered with, a
frame loop driven by injected timestamps - including a ten-second gap standing in for a backgrounded
tab - and the gauge, ribbon and availability figures section T reads off the canvas, taken from
`samples/four-echelon-playback/README.md` §6.5 rather than from a run. No test waits for a real
animation frame.

**129.** Production build:

```bash
npm run build
```

→ Succeeds. Cytoscape lands in a **lazy chunk**, not the initial bundle - the initial-bundle budget
(800 kb warn / 1.5 mb error) is not tripped.

**130.** Navigate project → editor → project → editor several times.
→ No console warnings about Cytoscape extensions being registered twice, no leaked listeners, and
the undo stack starts empty each time.

## Q. Disruptions on the canvas (FR-16)

The whole section runs in the editor. Nothing in it should ever produce a canvas save, a fork prompt,
or a change to the dirty indicator - step 145 is the one that checks that on purpose, but keep an eye
on the toolbar throughout.

**131.** Press **⚡ Disruptions** on the toolbar.
→ A fourth column opens between the metrics panel and the property panel. The scenario picker reads
**None selected**; the body says *"Pick a scenario above, or create one."* Devtools shows exactly two
requests: `GET /projects/{p}/scenarios` and `GET /networks/{n}/regions`. Close and re-open the panel:
**no further requests** - they are fetched on the first activation only.

**132.** Press **+ New scenario**, type `Canvas outage`, press Enter.
→ 201 on `POST /projects/{p}/scenarios`, the picker switches to it, and `GET /scenarios/{s}` follows.
The list body now reads *"Nothing in this scenario strikes this network yet."*

**133.** Without selecting anything, look at **⚡ Add disruption**.
→ Disabled, and the line beneath reads *"Select a node or a link on the canvas to aim a disruption at
it."* The hint is on screen, not only in the tooltip.

**134.** Click a node - `PLANT-1` if you have the worked-example network - then press
**⚡ Add disruption**.
→ The event editor opens in the panel. Where the timeline's editor shows a **Strikes** dropdown, this
one shows the node's **name**, with *"Selected on the canvas. Select something else to re-aim."*
There is no id anywhere in the targeting.

**135.** Set **Lasts** to `10 d`, drag severity to `80%`, and press **Add event**.
→ 201 on `POST /scenarios/{s}/events?networkId={n}`. The panel returns to the list with one row,
`PLANT-1`, and one event line reading `80% · 0 d → 10 d · periods 0–10 of 52`.

**136.** Look at the canvas.
→ `PLANT-1` carries a **red halo** behind it. Its fill colour (the echelon), its size and its label
are unchanged. Select it: the blue selection border appears *and* the halo stays - two channels, both
readable.

**137.** Hover `PLANT-1`.
→ The tooltip shows the usual processing time and capacity, then a rule, then
`⚡ 80% · 0 d → 10 d · periods 0–10 of 52`. Compare the wording with the panel's row: identical.

**138.** The toolbar's **⚡ Disruptions** button.
→ Carries a red count badge reading **1** - elements of this network the scenario strikes. Close the
panel: the halo stays on the canvas and so does the badge. The scenario is a property of what is on
screen, not a mode of an open panel.

**139.** Box-select or Ctrl-click **two nodes**, then **⚡ Add disruption to 2**.
→ The aimed summary lists both names, with *"One event per target, identical in every other field."*
The submit button reads **Add 2 events**.

**140.** While that draft is open and half-filled (set severity to `50%`), click a **third** node on
the canvas.
→ The aimed list re-aims to the third node. **The severity stays at 50%** - re-aiming changes the
target and nothing else.

**141.** Press **Add event**.
→ One POST per target, in order. Both new rows appear in the list and both nodes gain halos.

**142.** Press **Region…**.
→ The editor opens with **Strikes** on `REGION` and a free-text tag field with the network's tags in
its datalist. Type `EU-West` (or any tag your nodes carry).
→ `GET /networks/{n}/region-nodes?region=EU-West` fires, the matched names are listed under the
field, **and every matched node on the canvas lights up** with a red tint - a different mark from the
halo, because this is an unsaved draft. Change the tag: the highlight follows.

**143.** Type a tag no node carries, e.g. `MARS`.
→ The highlight clears and the panel warns that the event would strike nothing. Press **Add event**
anyway.
→ **422 `EVENT_TARGET_INVALID`**, shown verbatim. Nothing is added.

**144.** Set the tag back to a real one and save.
→ 201. Every node the *server* resolved the tag to now carries a halo, and hovering one shows
`… · via EU-West`. Press **Cancel** on a later draft and confirm the draft highlight clears.

**145. The constraint, and the reason this feature exists.** Give this network a completed simulation
run - section K's SQL is the quickest way - then reload the editor.
→ The amber *"A simulation run has frozen this network"* banner is at the top and the canvas refuses
edits. Now open the disruptions panel.
→ It is **fully live**, and carries a neutral note: *"A run has frozen this network, and that changes
nothing here."* Select a node, add a disruption, save it.
→ **201.** No fork prompt. No change to the dirty indicator - it still reads *All changes saved*. No
`PATCH` of any kind in the Network tab. Delete the event: also fine.

**146.** With the panel open, press **Timeline ↗**.
→ The scenario builder opens on the same scenario. Its network picker already offers this network;
pick it.
→ The rows and bars are the **same events**, with the same targets and the same period ranges. The
bar tooltip and the canvas tooltip state the same window.

**147. The equality check the feature is judged on.** In the timeline, add an event with the same
target, window, severity, recovery profile and probability as one you authored on the canvas. Then:

```sql
SELECT target_type, target_id, target_region, start_offset_value, start_offset_unit,
       duration_value, duration_unit, severity, recovery_profile, probability
FROM disruption_event WHERE scenario_id = {s} ORDER BY id;
```

→ The two rows are identical in every column but `id`. Both surfaces run the same editor component
against the same endpoint, so this is a check that they still do - not a coincidence to be grateful
for.

**148.** Back in the editor, click an event line in the panel's list.
→ The element it strikes is **selected on the canvas** and the editor opens on that event. A region
row selects every node the tag resolved to.

**149.** Delete a node the open scenario targets, then look at the panel.
→ The row is gone from the list and a line appears beneath it: *"1 more event in this scenario targets
something this network does not have."* The count is honest rather than the row being silently
dropped - the scenario outlives any one network.

**150.** Select a node and change its **region** in the property panel to a tag one of your REGION
events names. Wait about three seconds.
→ The canvas save lands first, then `region-nodes` is re-asked, then the node gains the halo. The
re-ask waits for the flush deliberately: asking sooner would resolve the tag the server still holds.

**151.** Author an event whose window ends past the horizon - start `50 d`, lasts `10 d` on a
52-period network.
→ Refused with **`EVENT_EXCEEDS_HORIZON`**, warned about in the panel before the save, exactly as in
the scenario builder.


## R. Running from the editor, and its run history (FR-17, FR-21)

Needs the backend at the same stage: nullable `scenarioId`, the `partial` object on the job poll,
`?runIds=` on the comparison, and - for the history from step 160a - `GET /networks/{id}/runs` and
`DELETE /simulations/{runId}`, both of which shipped with FR-20. A network with demand (the
worked-example network, or A'/A of `CLICK-THROUGH.md`) and at least one scenario with an event.

**152.** Press **▶ Run** on the toolbar.
→ A panel opens between the disruptions panel and the property panel: **Run baseline**,
**Run scenario** with a scenario picker, and - before any button is pressed - the amber note that
submitting **freezes this network** and that cancelling releases it. The scenario picker defaults to
whatever the disruptions panel has open.

**153.** Press **Run baseline** on a network with a 100-replication default (set replications 100 on
a scenario first if you want the run slow enough to watch; a baseline run here uses the engine
default of 100).
→ `POST /simulations` carries **no `scenarioId` key at all** (check the request body - omitted, not
null). The 202's `replications` reads **100, not 200**: no paired set (FR-17). The frozen banner
appears over the canvas **immediately** - at the 202, not at the first refused edit - and the
toolbar's ▶ Run button shows a live percentage.

**154.** Watch the panel while it runs.
→ A striped progress bar; beneath it an amber block labelled **provisional** showing
`k / 100 replications`, **Fill rate**, **Worst period** and **Total cost**. The figures visibly move
as replications complete. Close the panel: the toolbar button keeps the percentage, so the run is
never invisible. Re-open: the figures are still live.

**155.** Let it finish.
→ The provisional block disappears - on a terminal status the poll's `partial` is null, because the
persisted suite now supersedes it - and the report renders in place: the performance curve (the two
series coincide, the caption says the run *is* the baseline and there is no triangle), and the
metric cards. **TTR, LOSS_AREA, DISRUPTION_COST_DELTA and RESILIENCE_INDEX are absent**, with the
sentence that absent is unmeasured, never zero.

**155a. The link out, watched rather than glanced at.** Open devtools on **Network** *and*
**Console** - set the console filter to **All levels**, not Errors - then press
**Full dashboard ↗**.
→ Four things, and the first three are the ones that were missing:

1. The URL becomes `/projects/{p}/simulations/{runId}`.
2. **`GET /api/v1/simulations/{runId}` appears on the Network tab**, followed by
   `GET /api/v1/networks/{n}/metrics/topological`.
3. The console stays clean. In particular there is **no `NG0600`** - *"Writing to signals is not
   allowed in a `computed` or an `effect` by default"* - and no **unhandled promise rejection**
   carrying it. That refusal is the failure this step exists to catch: it is raised inside an
   `async` store method, so it never reaches the `ErrorHandler` and never prints as an `ERROR` line.
   A console filtered to errors shows nothing at all, which is exactly how it went unnoticed.
4. The page renders the same run: same curve, same cards, same `run #{id}` chip.

**155a-i. Read the header line and the buttons, not just the shape of the page.** They are where a
*rendering* failure shows first, and it looks nothing like a loading failure.
→ The status line reads in full - `DONE · 100 replications … · horizon 30 periods · seed 42` - and
the export button is labelled **Export .xlsx**. **A line that stops at `seed` with no number, beside
a blank button where "Export .xlsx" should be, is the tell**: an expression threw during the view
update, and Angular abandons the rest of that view when it does. Everything below goes blank
together - curve, metric cards, criticality table, structural strip - on a page whose data loaded
perfectly, which is why step 2 above passing is not enough to call this working.

That failure was `record.params.seed` on a run the API answered **without** `params`: the backend
drops null fields (`spring.jackson.default-property-inclusion=non_null`), and
`SimulationService.fromJson` returns null when `params_json` will not deserialise. If you see the
amber **parameters unavailable** line instead, that is the honest rendering of the same situation -
the run is unreproducible and the page says so - and the API log will carry *"Could not read
params_json"*. Report it; the numbers are still valid, the reproducibility record is not.

**155a-ii. A baseline run must read as one, on both surfaces.**
→ The header says **Baseline (no scenario)**, the metric block says TTR / LOSS_AREA /
DISRUPTION_COST_DELTA / RESILIENCE_INDEX are absent *because nothing was disrupted*, and the curve
caption says the run **is** the baseline with no triangle to shade. Compare it against the editor's
own report from step 155: the two must say the same thing. A dashboard that instead offers the
paired-baseline sentence and looks for a triangle is reading `scenarioId` as present - the same
omitted-null defect, one field along (`core/api-nulls.ts`).

**155b. Back, and in again.** Press the browser **Back** button to return to the editor, then press
**Full dashboard ↗** a second time.
→ `GET /simulations/{runId}` is issued **again** - every entry re-reads the run. A second entry that
draws the numbers with no request on the wire is the stale-cache defect: the run's status, metrics
and curves are written while it executes, so a run first opened while `RUNNING` would
otherwise read *"This run has not finished"* for as long as the tab lives.

**155c.** From the dashboard press **Open network** to come back, then **Full dashboard ↗** once
more - this time with a node dragged on the canvas *after* the run finished, so the toolbar reads
**Unsaved changes (1)** and then **Save failed** when the frozen network refuses it.
→ The refused edit does **not** hold the navigation: the editor answers `NETWORK_IMMUTABLE` with the
fork prompt and drops the queue (a write the freeze refused is not unsaved work - `handleImmutable`
in `network-editor.store.ts`), so the `canDeactivate` guard finds nothing pending and leaves without
a dialog. Plain **Back**, the breadcrumb's **← Project** and the report's **Compare against…** link
behave the same way. The *"Leave with unsaved edits?"* dialog belongs to a save that failed for some
other reason, and it must still appear then - stop the backend and drag a node to see it.

**155d. The discard dialog must not claim a result it is not.** Back in the editor, press
**Delete run** on the report - then **cancel** it.
→ The typed confirmation names `run {id}` and says what goes with it. It must **not** carry the
restored-archive warning - *"deleting it destroys the only copy this installation holds"* - for a run
this installation just computed. That warning is selected on `importedAt !== null`, and the API omits
the field entirely on a local run, so an unnormalised response makes it fire on **every** run
(FR-20). `run-discard.spec.ts` cannot catch it: it builds its runs with `importedAt: null`
written out, which is the one shape the browser never receives. `core/api-nulls.spec.ts` is where
that is pinned.

**156.** Press **New run**, pick the scenario, press **Run scenario**.
→ The 202's `replications` is now **2N** - the paired set is back, because there is a disruption to
isolate. On completion the curve shows the baseline overlay and the shaded triangle, and all nine
metric cards are present.

**157.** The panel now offers **Side by side (FR-17)**: *Compare against Baseline (run #…)*.
→ Opens `/projects/{p}/comparison?runIds=…,…`. One column per **run** - the same network twice,
headed `… - baseline` and `… - <scenario>` - with a blue banner explaining the run-keyed mode.
`MIXED_SCENARIOS` fires, correctly: this comparison measures *only* the scenario. The four
disruption-relative rows have a value in the scenario column and **-** in the baseline column.
Tick any network checkbox: the view returns to the ordinary network-keyed matrix.

**158.** Export from that view (.xlsx).
→ The workbook's `comparison` sheet has the two run columns with **distinct headers** -
`Baseline v1 - baseline (run 12)` beside `Baseline v1 - Plant outage (run 13)` - because two
identically named columns would make the `best` cell ambiguous.

**159.** Start another run and press **Cancel run** while it executes.
→ Status reaches `CANCELLED` within a replication (cooperative, never instant). The panel says the
network is editable again, and the frozen banner **clears without a reload** - the store re-read the
network row when the job settled. Drag a node to confirm the canvas accepts edits.

**160.** With a run in flight, try to edit.
→ The fork prompt, exactly as for any frozen network. Running from the editor is not a way around
immutability - it is a way of reaching it sooner, which is the point.

### The run history - every run of this network, watched rather than re-run (FR-21)

Steps 152–160 leave the network with at least three runs: the baseline of 153, the scenario run of
156, and the cancelled one of 159. That is the state this walkthrough starts from. Keep the devtools
**Network** tab open - half of what is asserted here is *which requests do not fire*.

**160a. The panel opens with the list.** Close the run panel (press **▶ Run** on the toolbar again),
then re-open it.
→ Exactly **one** `GET /api/v1/networks/{n}/runs` on the Network tab, on the opening - not on the
closing, and not twice. Under the two run buttons a block headed **Runs of this network** with a
count, a **Refresh** link, and one tile per run **newest first** (highest run id at the top - the
server's `ORDER BY id DESC`, which this client does not re-sort).

**160b. Read one tile.** Look at the baseline run's tile from step 153.
→ Four readings, and each is a separate assertion:

1. The **date and time it finished**, in full (`7 Aug 2026, 09:00:11`) - not the started time, which
   the run also carries.
2. **Baseline - no scenario**, spelled out. The scenario run's tile reads its scenario's name
   instead.
3. **No status badge at all.** `DONE` is the ordinary case and says nothing; only the cancelled run
   of step 159 carries a badge, reading `CANCELLED` in grey.
4. `run #{id}` as a chip, and a 🗑 button on the right.

A tile that reads *"restored"* here is a defect unless you are on a restored project - that mark is
`importedAt`, the API omits the field on every locally computed run, and an unnormalised response
makes it fire on all of them (step 155d is the same defect at the other end; `core/api-nulls.ts`).

**160c. A queued run's tile.** Submit a fresh scenario run and, while the progress bar is still at
0 %, press **Refresh** in the history header.
→ The new run appears at the top with a **QUEUED** badge and, where the date belongs,
*"Queued - not started yet"* - **not a blank**. A second later, Refresh again: it reads
*"…, started"* against the start timestamp. Let it finish, and note what happens *without* pressing
anything: the list re-reads itself the moment the job settles, and the tile becomes a dated `DONE`
one. One `GET /networks/{n}/runs` for that settle, not one per poll tick.

**160d. Nothing polls a run that is not yours.** With the panel open and no run in flight, watch the
Network tab for thirty seconds.
→ **No requests at all.** The history has no timer: it re-reads on opening, on a settle, and after a
deletion, and that is the whole rule. A repeating `GET /networks/{n}/runs` or
`GET /simulations/{id}` here is the polling loop this panel must never run.

**160e. Select a completed run - the report loads in place.** Press **New run** to clear the panel,
then click the **baseline** tile from step 153.
→ One `GET /api/v1/simulations/{runId}` - the *same* endpoint the just-completed run used, and the
only request that fires. The report renders below exactly as it did in step 155: the same curve, the
same cards, the same `run #{id}` chip, the header reading **Baseline**, and the *"this run is the
baseline"* caption with no triangle. The tile is now outlined in blue.

**160f. And playback arms, exactly as a settling run does.** Watch the canvas as that report lands.
→ `GET /api/v1/simulations/{runId}/timeseries/elements` fires once, the transport bar appears, and
the animation **starts by itself at period 0** at the configured speed - the answer FR-21 is for:
what this configuration did last Tuesday is watched, not re-run.

**160g. Re-selecting the same tile must not restart it.** Let the playback reach roughly the middle
of the horizon, then click that same tile again.
→ **Nothing happens.** The clock keeps going from where it was, no request is issued, and the
transport does not jump to period 0. A rewind here is the defect: the auto-play token is idempotent
per bump, not per click.

**160h. Switching to another completed run does rewind.** Click the scenario run's tile from
step 156.
→ One `GET /simulations/{runId}`, one elements read, the clock rewinds to period 0 and plays, and
the report swaps to the disrupted one - baseline overlay, shaded triangle, all nine cards. The blue
outline moves with it.

**160i. A run with no element series degrades, it does not fail.** If the project has a run recorded
before `V9__element_timeseries.sql` (a restored archive, or one submitted with
`recordElementTimeseries: false`), select it.
→ The clock, the transport, the scrub bar and the performance curve all work - they read
`RUN_TIMESERIES`, which every completed run has - the canvas channels stay dark, and the transport
bar carries **"element detail unavailable for this run"** verbatim. Never an error, never zeros
(FR-18). Skip this step if no such run exists.

**160j. A tile in any other state selects to its status only.** Click the **CANCELLED** tile from
step 159.
→ The report disappears (and playback stops with it - a curve from one run must not sit under
another run's selected tile), and a line replaces it: *"run #{id} - CANCELLED. There is no report to
load…"*. **No `GET /simulations/{runId}` is issued.** Repeat with a `QUEUED` or `RUNNING` tile: the
same, plus the sentence that it cannot be followed live here because its job id was issued to
whoever submitted it - and a pointer to **Refresh**. A request on the wire at this step, or a
progress bar appearing, is the foreign-run poll that must never happen.

**160k. Delete one run from its tile.** Press 🗑 on the cancelled run's tile.
→ The same typed confirmation the report's **Delete run** opens: title *"Delete this run?"*, the
phrase `run {id}` to type, the line about the metric suite and all three series going with it, and -
for a `CANCELLED` run - *"This run holds nothing frozen"* rather than a promise to unlock. Type the
phrase and confirm.
→ `DELETE /api/v1/simulations/{id}` → **204**, then `GET /networks/{n}/runs` and
`GET /networks/{n}` - the list re-read and the network row re-read, in that order. The tile is gone
and the count drops by one.

**160l. Deleting a run that is not the one on screen must not disturb it.** Select a completed run
so it is playing, then delete a *different* completed tile.
→ The other tile vanishes, the count drops - and **the playback carries on uninterrupted**. The
report on screen is untouched. A canvas that stops here is dropping the report on any deletion
rather than on the deletion of *its* run.

**160m. Deleting the run on screen clears it honestly.** Now delete the tile that *is* selected.
→ The report block disappears, playback stops and the transport bar goes, the tile is gone from the
list, and the panel is back to its two buttons. A curve drawn from a deleted run is worse than an
empty panel.

**160n. An active run cannot be deleted, and the panel says so before you type.** Submit a run and,
while it executes, look at its tile.
→ The 🗑 is **disabled**, and hovering it reads *"A RUNNING run cannot be deleted - the server
refuses it with `RUN_ACTIVE`. Cancel the job first"*. No dialog opens. This is the same rule the
whole-network **Unlock…** follows (§K): the editor does not open a confirmation it already knows the
server will refuse.

**160o. The comparison pair comes from the history, not from the session.** With a report loaded,
read the **Side by side (FR-17)** list.
→ It offers every *other* `DONE` run of this network - including any this session did not launch -
and offers **no** `QUEUED`, `RUNNING`, `FAILED` or `CANCELLED` run, because `?runIds=` answers those
with `RUN_NOT_DONE` (409) rather than an empty column. Following one opens
`/projects/{p}/comparison?runIds={other},{loaded}` exactly as step 157 does.

**160p. The whole-network discard empties the history.** Press **Unlock…** on the frozen banner and
complete the typed discard of §K.
→ `DELETE /networks/{n}/runs` → 204, then `GET /networks/{n}`. The history block now reads *"No runs
yet. An empty list is an ordinary answer"*, the frozen banner has cleared, and - the point
of the step - **no extra `GET /networks/{n}/runs` fires**: the store empties the list on the 204,
because that is the same answer a re-read would give and one request cheaper.

**160q. The acceptance step: close the editor, come back, and the runs are still there.** Run a
baseline and a scenario run against a network. Navigate away - the breadcrumb's **← Project** is
enough - and then re-open the same network in the editor and press **▶ Run**.
→ Both runs are listed, newest first, dated, named by their scenario. Selecting either loads its
report and starts it playing on the canvas. **This is the whole of FR-21**: before it, this panel
opened empty and the only way back to those numbers was to run them again.

> If the list is empty here, the panel is still reading a session-scoped accumulator rather than
> `GET /networks/{id}/runs` - which is exactly the defect the run history was rebuilt to remove.


## S. Playback speed (FR-18)

The speed at which a completed run replays on the canvas - "N simulation periods = one real-world
second". This section is the **setting and the clock behind it**; section T is the animation itself,
and step 184 checks that the two controls that write this preference agree.

**Two things are true of every step below, and both are the point.** Nothing in this section may
send a request of any kind - keep the devtools **Network** tab open - and nothing in it may enable
**Apply time base** or move the toolbar's dirty indicator. The row sits in the time-settings dialog
but is not part of the time base: it is a device-local view preference (playback speed
changes no simulated number).

**161.** On `Editor Test`, set the clock back to **1 d × 52 / NEAREST** if section M or N left it
elsewhere, then press the **⏱ 1 d × 52** toolbar button.
→ Below the rounding-policy dropdown, **separated from it by a rule**, a fourth row: the label
**Playback speed**, a dropdown reading **2 periods / second**, a blue line reading *"One period
(1 day) plays in 0.5 s; a full run (52 periods) plays in ~26 s."*, and a grey line reading
*"Affects only the visual simulation on the canvas, never results. Saved on this device for this
network - applying the time base is not required."* **Apply time base is disabled**: nothing has
been changed.

**162.** Open the dropdown.
→ Six options, slowest first: `0.5 periods / second`, `1 period / second`, `2 periods / second`,
`5 periods / second`, `10 periods / second`, `20 periods / second`. Half speed is an ordinary member
- a ten-period run at one period a second is over before it can be read. Note the singular at 1.

**163.** Pick **10 periods / second**, watching the Network tab and the Apply button.
→ **No request of any kind.** The pace line becomes *"One period (1 day) plays in 0.1 s; a full run
(52 periods) plays in ~5 s."* **Apply time base stays disabled** and the toolbar still reads *All
changes saved*. Changing the pace is not an edit to anything.

**164.** Devtools → **Application** → Local Storage → your origin.
→ One key, `snrm.playbackSpeed`, holding `{"<networkId>":10}`. That is the entire footprint of the
pick: no `PUT /networks/{n}/time-base`, no network field, nothing on the wire.

**165.** Press **Cancel** on the dialog, then re-open it.
→ Still **10 periods / second**. Cancel cancels the *time base*; the speed was saved the moment it
was picked, the same way the period unit dropdown remembers its unit outside the Apply flow
(step 96).

**166.** Reload the page (F5) and re-open the time settings.
→ Still **10 periods / second**. A device preference survives a reload without ever having reached
the server.

**167.** Open a **different** network of the project in the editor and press its ⏱ button.
→ Its dropdown shows a speed derived from **its own horizon**, not 10 - the key is per network. The
default aims a whole run at about half a minute: a 52-period network opens on **2 periods /
second**, a 30-period one on **1 period / second**, a 365-period one on **10 periods / second**, and
a 10-period one on **0.5 periods / second**.

**168.** On that second network, apply a time base of **1 d × 30** and re-open the dialog.
→ 200 on `PUT /networks/{n}/time-base` as usual, and the pace line now reads *"One period (1 day)
plays in 1 s; a full run (30 periods) plays in ~30 s."* The default **followed the horizon**,
because nothing was remembered for this network to pin it. Pick a speed here and it stops following:
a pick is a decision, a default is an answer.

**169.** Return to `Editor Test` and re-open its dialog.
→ **10 periods / second** again. Two networks, two picks, one key.

**170. The constraint this row exists for.** Freeze a network with section K's SQL (or leave one
frozen by a run from section R), reload the editor, and open the time settings.
→ The amber *"This network is frozen by a simulation run…"* note is there, and **Apply time base**
still leads to the fork prompt. The **Playback speed dropdown is fully live**. Change it.
→ The pace line updates, `localStorage` updates, and: **no fork prompt, no request, and no movement
in the dirty indicator** - it still reads *All changes saved*. This is the normal case for this row,
not the edge case: playback replays a *completed* run, so the networks it applies to are exactly the
frozen ones.

**171.** Still on that frozen network, change the **period** and press **Apply time base**.
→ The fork prompt, exactly as in step 108. The two halves of this dialog behave differently on
purpose, and step 170 next to this one is the check that they still do.

**172.** Throttle the network in devtools (Slow 3G), change the period, and press **Apply time base**
so the in-flight state is visible.
→ Every time-base control greys out while the save is in flight - period, horizon, policy, Suggest
period, Cancel. The **Playback speed dropdown does not**, and can still be changed. Nothing it does
can conflict with a save.

**173.** Tamper check. In Application → Local Storage, set `snrm.playbackSpeed` to
`{"<networkId>":3}` and reload the editor.
→ The dropdown falls back to the **horizon-derived default** - not 3, and not the nearest ladder
entry to it. A speed this build has no control for is discarded, because a stored 3 is not evidence
that anyone wanted 2. Now set the value to `not json` and reload.
→ Same: the default, no console error, and the dialog opens normally.

**174.** Storage-denied check. Open the app in a private window with site data blocked (Chrome:
Settings → Privacy → *Block all cookies*), sign in, and change the playback speed.
→ The row works for the session - the pace line follows the pick - and **nothing throws into the
dialog**. It simply does not survive a reload. A preference that cannot be saved must never be able
to break the dialog it lives in.


## T. Visual playback on the canvas (FR-18)

The animation itself: the transport bar over the canvas, the three per-element channels, and the
run starting to play the moment it finishes.

**Set up once, and use this network for the whole section.** Import
`../snrm-backend/samples/four-echelon-playback/network.xml` through the import wizard
(no mapping step - the document names its own fields). Expected on import: `valid: true`,
`committed: true`, **0 errors, 0 warnings**, 4 nodes, 3 links, 1 product, 4 node-product rows, on a
clock of **1 d × 30 / NEAREST**.

**Every number this section checks is derived by hand** in that sample's `README.md` §6.5 - from the
per-period loop, not from a run - so "is the picture right?" reduces to "does the picture
show 10 / 5 / 5 / 0?" rather than to a judgement about whether an animation looks plausible. A
disagreement is a defect in one of the two, not a rounding difference.

**175.** Open the network in the editor, press **▶ Run**, then **Run baseline**. Let it finish.
→ The report renders in the run panel as in section R **and the canvas starts playing on its own**:
a transport bar appears bottom-centre over the canvas and the clock counts up from period 0. No
second gesture was needed - clicking Run is the request to see it simulate.
→ The speed select on the bar reads **1 period / second** (the horizon-derived default for 30
periods, step 167), so the whole run takes about half a minute.
→ Devtools **Network**: exactly **one** `GET /simulations/{runId}/timeseries/elements`. Watch the
whole run through: **no further requests of any kind**. The horizon is fetched once and animated
from memory.

**176.** Watch `SUP-1` through the first four periods.
→ Its gauge fills from the bottom in a **darker shade of its own supplier blue** - the hue never
changes, so the echelon is still readable. It stands at **half** in periods 0 and 1, is **completely
full at period 2**, and drops back to half at period 3 and stays there.
→ That is §6.5.1: `SUP-1` holds 10, 10, **20**, 10, 10 … and its gauge is its own stock against its
own maximum of 20. It peaks at period 2 because `PLANT-1` was full that period and drew nothing from
it - the pull travelling backwards up the chain.

**177.** Watch `DC-1` over the same periods.
→ **Full at periods 0 and 1, then a visible drop at period 2** to a third of its height, where it
stays for the rest of the run: 15, 15, **5**, 5, 5 … against its own maximum of 15.
→ Two nodes at full gauge are **not** holding the same quantity - `SUP-1`'s full is 20 and `DC-1`'s
is 15. The gauge answers "how full for it"; the property panel and the run's series answer "how
much". Normalising across the network instead would flatten every node beside the largest.

**178.** Watch the three arcs. Scrub to period 0, then step forward with the **→** key.
→ `SUP-1 → PLANT-1` is thick at periods 0 and 1, **thin, dashed and faded at exactly period 2**, and
thick again from period 3 to the end.
→ `DC-1 → CUST-1` is **thin at period 0 only**, and thick in every period after it.
→ `PLANT-1 → DC-1` is thin at period 1 alone.
→ These are §6.5.2's three zeros walking **up** the chain one period at a time - each one a rung
whose downstream neighbour was already at its order-up-to target. An idle arc is faded rather than
removed on purpose: the structure has to stay readable exactly when nothing is moving.

**179.** Read the clock on the bar as it plays.
→ `Period 0 of 30 - 0 days`, `Period 1 of 30 - 1 day`, … `Period 5 of 30 - 5 days`, up to
`Period 29 of 30 - 29 days`. The period index is the series index (period 0 is a real period of the
run), and the restatement is the **run's** clock, not the network's - 1 d × 30 here, so the two
numbers happen to match. On a network stepping in `2 d` period 5 would read *10 days*; that
multiplication is `core/metric-display.ts`'s `readablePeriods` and it has its own test.

**180.** Let the run reach the end.
→ It **stops on period 29** and stays there - the last period, not a blank canvas, and not a loop
back to 0. A run that ends is a finding; looping it would make the end invisible. Press **▶** again:
it restarts from period 0.

**181.** Press **▶** to play, then press **Space** with the pointer over the canvas.
→ Pauses. Space again → resumes from where it stopped, not from a catch-up jump. **→** and **←**
step one period each and pause first. Now click into a text field in the property panel and press
Space and the arrows.
→ They type and move the caret. The transport keys are gated on no input being focused, exactly like
Delete and Ctrl-Z.

**182.** Drag the **scrub bar to period 10**, then press **←** once.
→ The clock reads `Period 9 of 30 - 9 days` and the canvas shows period 9's picture: all three arcs
thick, `SUP-1` at half, `DC-1` at a third. Dragging the scrubber **while it is playing** moves the
clock and leaves it playing; stepping pauses.

**183.** While it plays, change the speed select from **1** to **5 periods / second**.
→ It immediately plays five times faster **from where it was** - no jump in position, no restart.
Change it back mid-run: same. The loop reads the speed every frame, so a change lands on the next
one.

**184.** Open the **⏱ 1 d × 30** dialog while playback is paused.
→ Its **Playback speed** row reads **5 periods / second** - the pick made on the bar. Change it there
to 2, close the dialog: the bar's select reads **2 periods / second**. One preference, two controls,
one `localStorage` key (step 164).

**185. The stockout and the temporal disruption.** Author the scenario of the sample's §8.1 -
`LINK` target `DC-1 → CUST-1`, start `10 d`, lasts `3 d`, severity `1.0`, `STEP`, probability 1 - in
the disruptions panel, then run it from the run panel. When it finishes, scrub through periods 9–14.
→ Period 9: normal. **Periods 10, 11, 12: `DC-1 → CUST-1` carries a red halo** that is absent in
every other period, and the arc is thin - the event has taken its capacity to zero.
→ **Period 10's `CUST-1` still serves in full** - no orange overlay. The units it serves were
dispatched by `DC-1` in period 9 and in-flight material is never stopped. **Periods 11, 12 and 13
show `CUST-1` with an orange overlay**: demand it wanted and did not get.
→ The stockout window (11–13) is **one period later than the event window (10–12)**, and this is the
single thing the animation exists to get right (§8.3). If the two coincide, in-flight material is
being cancelled when the link goes dark, which is a defect.

**186.** Look at the halos while that run is playing, then stop playback (press **New run** in the
run panel, or leave and re-enter the editor with the panel closed).
→ **While playing:** the only red halo on the canvas is the one the *current period* justifies - the
static "this element is struck somewhere in the horizon" halo from the disruptions panel is quiet.
→ **After playback stops:** the transport bar disappears and the static halo is back on
`DC-1 → CUST-1`, exactly as section Q left it. The underlay is one channel with one owner at a time;
neither owner writes the other's data.

**187.** Before running, toggle **📊 Metrics → size by criticality** on, then run a baseline and let
playback finish. Now stop playback.
→ During playback the nodes keep their criticality **sizes** (all four are equal at 1.0 on this
chain, so pick the 6-node network if you want visibly different sizes) and gain gauges; selection
borders still work; an echelon-warning arc, if the network has one, stays **orange and dashed**
whatever its flow is doing.
→ After stopping, the canvas is the ordinary one: no gauges, no faded arcs, uniform arc widths, and
the criticality sizing **still applied**. Nothing is stranded.

**188.** Select a node and an arc while playback runs.
→ The blue selection border and the blue arc appear over the animation and the property panel opens
as usual. Playback does not stop and the canvas does not fight the selection.

**189. Element detail this run does not have.** Take a run submitted before the per-element series
existed - any run in the database from before `V9__element_timeseries.sql`, or force one with
`params.recordElementTimeseries: false` through the standalone launcher - and open its report in the
editor's run panel.
→ The transport bar is **present and fully working**: play, scrub, step and the clock all move, and
the performance curve beside it is complete. **Nothing on the canvas animates**, and the bar carries
the small grey note *"element detail unavailable for this run (recorded before V9)"*. A missing newer
feature must not withdraw a working older one.

**190.** Block the element read: devtools → Network → right-click any request → **Block request
URL**, pattern `*/timeseries/elements`. Then run a baseline.
→ Same shape as the step above - the run completes normally, the report renders, the transport works
- and the note reads *"element detail could not be read for this run"*. **No error banner**: the run
panel owns the errors that matter to a run, and a failed read of a drawing aid must not look like a
failed run. Unblock the URL and run again: the animation is back.

**191.** With playback running, watch the devtools **Performance** tab (or simply the fan).
→ One repaint per **period**, not per frame. At 1 period / second that is one canvas update a second
on a network where nothing else is happening; the clock itself runs on `requestAnimationFrame`
outside Angular's zone and writes a signal only when the period changes.

**192.** Submit a second run while the first is still playing.
→ Playback **stops and rewinds to period 0** at the moment the report clears, the transport bar
disappears while the new run executes, and it comes back - auto-playing from 0 - when the new report
lands. A different report is a different clock; leaving the position would show period 24 of a run
that has not produced it yet.


## U. The element inspector (FR-18)

The **Simulation** card in the property panel: what the *selected* element did, at the period the
clock is on. Section T checks the picture of the whole network; this checks the numbers behind one
element of it.

**Use the same network and the same baseline run as section T** - the four-echelon sample, imported
once and run with **Run baseline**. Every figure below is §6.5 of its `README.md` (and §8.2 from
step 210 on), derived by hand from the per-period loop rather than produced by running
the application, so a disagreement is a defect in one of the two rather than a rounding difference.

**193.** With the run loaded and playback paused at **period 1**, click `DC-1`.
→ A **Simulation** card appears in the property panel **above the Name field**, inside the same
always-open aside - no new panel, no new toggle, and nothing else on screen moves. Its header reads
*Simulation*, under it *Period 1 - 1 day* (the **run's** clock), and its first row **Realtime
inventory (on-hand) 15**.
→ Devtools **Network**: **no request of any kind**. The whole horizon arrived with the one
`GET /simulations/{runId}/timeseries/elements` step 175 counted, and scrubbing reads memory.

**194.** Scrub to **period 3**, or press **→** twice.
→ On-hand **5**, and every other row moves with it. Press **▶**: the numbers follow the animation
and the pink cursor on the sparkline steps across one band per period. The line is **stepwise** -
flat across each period with a riser between them - because the engine is discrete-time and a level
halfway through period 3 is a number no replication produced.

**195.** Read the **Average inventory** row on `DC-1`.
→ **5.67**. `DC-1` holds 15, 15, then 5 for twenty-eight periods: 170 / 30 = 5.6666…, to two
decimals. The dotted amber line across the sparkline sits at exactly that height - one number, two
readings.

**196.** Click `SUP-1`, then `CUST-1`.
→ Averages **10.33** (310 / 30 - 10, 10, **20**, then 10 to the end) and **0.33** (10 / 30 - the
customer holds 10 at t=0 and nothing after).
→ The caption under each sparkline reads `10–20 over 30 periods` and `0–10 over 30 periods`. The
scale is fitted per element and the card says so in numbers; this is the same trade the gauges make
on the canvas (step 177), stated rather than left to the eye.

**197. The null that is not a zero.** With `CUST-1` selected, scrub to **period 0**.
→ **Current lead time** reads *no inbound this period*, in grey italic. **Never `0`.** Step to
period 1.
→ It reads **1 period (1 day)**. §6.5.4: arc **c** dispatched nothing at t=0, and a 0 would say
something was dispatched and arrived instantly - a different and false claim.

**198.** Read **Average lead time** on `CUST-1`.
→ **1 period (1 day)**. The flow-weighted mean over the run: twenty-nine periods at lead 1 carrying
10 units each, and the one period that dispatched nothing contributing to neither half of the
fraction.

**199.** Select `SUP-1`.
→ **Current lead time** *no inbound this period* in **every** period, and **Average lead time**
*nothing dispatched here in this run*. A supply origin has no inbound arc at all, so its inbound lead
is undefined in every period of every run this network can produce (§6.5.4).

**200. The one-period offset.** `CUST-1`, reading **In transit / arrivals** at periods 0, 1 and 2.
→ `0 / 0`, then `10 / 0`, then `10 / 10`. What arc **c** dispatched in period 1 sits in the pipeline
at the end of period 1 and lands in period 2. If the two columns move together, in-flight material is
being recorded at the wrong end of the arc (§6.5.3, and §6.5.6 item 1).

**201. Served, demand and fill.** `CUST-1` on this baseline run, at any period.
→ **10 of 10 · 100.0%**, **no stockout badge**, and **Lost so far 0** under the fixed caption
*lost, not backlogged - the engine models no catch-up*.

**202. Throughput and utilisation.** `SUP-1` at period 4, then at period 3, then `DC-1`, then
`CUST-1`.
→ `SUP-1` **10 · 10.0%** - 10 units across its own capacity arc against 100 per period at full
availability. At **period 3** it is **0 · 0.0%**, the one period in the run where it was already at
its target and produced nothing.
→ `DC-1` **0 · 0.0%** in *every* period. Throughput is the flow across a node's **own capacity
arc**, and on this network every leg has a lead time, so inbound material lands in the hold and never
crosses it (§6.5.1). A row that called this "production" would be right at `SUP-1` and wrong here.
→ `CUST-1` **0 · uncapped**, greyed. It carries a capacity with no value, and 0% would be a fraction
of nothing.

**203.** Read **Availability** on any node and any period of this run.
→ **capacity available: 100.0%**. Nothing in a baseline run of this network is ever disrupted.

**204. The link view.** Click the arc `DC-1 → CUST-1` at **period 0**, then step forward.
→ **Flow 0** at period 0 and **10** in every period after it (§6.5.2). **Average flow 9.67**
(290 / 30). **Utilisation 0.0%** then **10.0%** - exactly 0.0 and not "no capacity available": an
idle arc at full availability is idle, not unmeasurable. **Availability** *capacity available:
100.0%*. **Declared lead time 1 period (1 day)**, under the note *realised transit always equals the
declared lead - disruptions re-route flow, they never delay material in transit.*

**205. The baseline overlay on a baseline run.** Look at the sparkline on any element.
→ The dashed grey line runs **exactly under** the solid blue one, in every period, on every element.
A run with no scenario has its own series copied into the baseline columns (§6.5.5), so the two
coincide **by definition** - an absent or a different overlay here is the defect, not a coincident
one.

**206. Criticality - structure beside behaviour.** Press **📊 Metrics** once (opening the panel is
what computes the suite), then select any node.
→ The card header gains a **Criticality 100.0%** tag beside the title. Close the metrics panel: the
tag stays, because the figure is already in the store.
→ On a network whose suite has never been computed there is **no tag at all** - not a 0. The
inspector *reads* the suite and never asks for it: `NODE_CRITICALITY` costs one maximum-flow
computation per node, and selecting an element during playback is not a request for it.

**207. Multi-selection.** Shift-click a second node.
→ **No Simulation card**, and the panel's bulk-edit form is exactly as section E leaves it. An
inventory averaged across two nodes is a number the run never produced, so there is nothing honest to
show.

**208. Playback off.** Press **New run** in the run panel, or leave and re-enter the editor.
→ The card disappears with the transport bar, and selecting an element shows attributes alone.

**209. Element detail this run does not have.** Repeat step 189 (a pre-V9 run) or step 190 (block
`*/timeseries/elements`), then select an element.
→ The card is **present**, with its header and its clock, and **the transport bar's own sentence in
place of every row**: *element detail unavailable for this run (recorded before V9)*, or *element
detail could not be read for this run*. One wording, two surfaces - a reworded copy would read as a
second, different problem.

**210. The stockout.** Run the scenario of step 185 (`LINK` `DC-1 → CUST-1`, start `10 d`, lasts
`3 d`, severity 1.0, `STEP`), then select `CUST-1` and scrub 10 → 14.
→ **Period 10: 10 of 10 · 100.0%, no badge.** The units served were dispatched by `DC-1` in period 9
and in-flight material is never stopped.
→ **Periods 11, 12 and 13: 0 of 10 · 0.0%**, a red **stockout** badge beside the label, and **Lost so
far** counting **10 → 20 → 30**.
→ **Period 14:** served again, badge gone, and **Lost so far stays 30**. Nothing catches up - which
is what the caption says and what a `FILL_RATE` of 0.9 (270 / 300, §8.5) measures.

**211.** On that same run, select the arc `DC-1 → CUST-1` and scrub 9 → 13.
→ Period 9: flow **10**, *capacity available: 100.0%*.
→ Periods 10, 11, 12: flow **0**, *capacity available: 0.0%*, and **Utilisation reads *no capacity
available*** in grey - not 0.0%. A dark arc is not an idle one, and the engine sends `null` rather
than a fraction for exactly this reason.
→ Period 13: flow **20** - the recovery surge, the largest single flow in the run - and utilisation
**20.0%**.
→ **Declared lead time reads 1 period (1 day) throughout**, outage included. The event takes the
arc's capacity, never its speed.

**212. The per-element resilience triangle.** Still on the disrupted run, select `DC-1` and watch the
sparkline across periods 9–17.
→ The dashed baseline stays **flat at 5** while the solid line climbs to **15** at period 10 and
**25** at periods 11 and 12, then works back down and rejoins it by period 17 (§8.2). The caption
reads `5–25 over 30 periods` - both series on one scale, which is the only way the gap between them
means anything.
→ That gap is §8.3's second finding drawn for one element: the outage sits at the downstream end and
the **stall propagates upstream**, one rung a period.

---

## V. The network dashboard (FR-19)

The property panel's **empty selection**. Sections T and U check the picture of the network and the
numbers behind one element of it; this checks the numbers behind the **whole** network - and, unlike
either of them, it checks what the panel shows when there is no run at all.

**There is no new gesture to learn, and that is the first thing to verify.** Clicking empty canvas
already unselected everything and Escape already did the same (section D); both landed on a panel
reading *Nothing selected*. That branch is now the dashboard. Nothing about the canvas changed.

Steps 213–216 use a **fresh network with no runs** - `Editor Test` from the setup, or any network you
have not run. Steps 217 onward use the **four-echelon sample and its baseline run**, exactly as
sections T and U do, and every figure below is derived by hand in that sample's `README.md` §5.1,
§6.1 and §6.4.

### Before any run

**213. The structure block stands alone.** Open `Editor Test` in the editor with at least three nodes
and two links on the canvas, and click empty canvas.
→ The panel shows a header naming the network - **`Editor Test`** with a `v1 · #{n}` chip and **no
Frozen badge** - then a single block headed **STRUCTURE**, then the one-line footer *Nothing
selected - click an element to inspect it; this view is the network.*
→ **No Live block and no Run suite block.** Neither has anything to say: nothing is playing and no
report is loaded. They are absent rather than empty with a placeholder.
→ If the topological suite has never been computed for this network, the block says so and offers
**Compute it** - press it. The rows appear **in suite order**: Density, Single points of failure -
nodes, Single points of failure - arcs, Single points of failure - total, Average path length,
Clustering, Robustness - random, Robustness - targeted. The three SPOF rows are one census reported
three ways, so the total must equal the two above it; if it does not, the backend is disagreeing
with itself.

**214. It is live, and it says when it is not.** With the dashboard on screen, drag a new node from
the palette onto the canvas and drop it. Then click empty canvas again and keep watching the panel.
→ Within about a second the STRUCTURE header shows an amber dot and **out of date**, then
**computing…**, then the figures update - Density in particular, since its denominator just changed.
→ Devtools **Network**: one `GET /networks/{id}/metrics/topological`, not one per gesture. The suite
is debounced and the editor's pending PATCHes are flushed first, so the figures describe what is on
the canvas rather than what was last sent.
→ Press **📊 Metrics** to open the metrics panel beside it: **the same numbers**, to the digit. Two
surfaces reading one store.

**215. The worst three, and clicking one.** Read the **Most critical nodes** table at the foot of the
block.
→ At most **three rows**, worst first, each with a bar scaled to the worst node in *this* network
rather than to 100%. If more than three nodes carry a criticality row, the line under the table reads
*N more in the Metrics panel.*
→ Click a row. The named node is **selected on the canvas** and the panel switches to that node's
attribute form - the dashboard is gone, because something is now selected. This is the one thing on
the dashboard that writes anything.

**216. Escape lands here.** With that node still selected, press **Escape** with the pointer over the
canvas.
→ The selection clears and the panel returns to the dashboard. Click empty canvas instead: same
result. Neither gesture is new and neither behaves differently from section D - the dashboard is
passive, so deselecting in order to *clear* a selection still does exactly that and nothing else.

### The live block, against the clock

**217.** Open the four-echelon network, press **▶ Run → Run baseline**, let it finish, and **pause**
the playback. Click empty canvas.
→ Three blocks now: **LIVE**, **RUN SUITE**, **STRUCTURE**, in that order, above the same footer.
→ The header carries the network name, its `v1 · #{n}` chip and a **Frozen** badge - the run froze it
at the 202.
→ The LIVE header's right-hand side reads the **run's** clock: *Period 0 - 0 days*.
→ Devtools **Network**: **no request**. The curve arrived with the report; the dashboard reads
memory, exactly as the element inspector does.

**218. Period by period.** Scrub - or press **→** - through periods 0 to 5 and read the seven rows.

| Period | Fill rate | Served / demand | Period cost | Cumulative cost | Cumulative unmet | Total on-hand | In pipeline |
|---|---|---|---|---|---|---|---|
| 0 | **100.0%** | 10 of 10 | 404.00 | 404.00 | **0** | **40** | **20** |
| 1 | 100.0% | 10 of 10 | 405.00 | 809.00 | 0 | 40 | 20 |
| 2 | 100.0% | 10 of 10 | 405.00 | 1,214.00 | 0 | 40 | 20 |
| **3** | 100.0% | 10 of 10 | **402.00** | **1,616.00** | 0 | **20** | **30** |
| **4** | 100.0% | 10 of 10 | **403.00** | 2,019.00 | 0 | **20** | **30** |
| 5 | 100.0% | 10 of 10 | **403.00** | 2,422.00 | 0 | 20 | 30 |

> Costs are grouped and given to two decimals by the browser's own locale and carry **no currency
> symbol** - the model stores no currency (every cost is a bare double). A thousands
> separator that is a space or a dot rather than a comma is your locale, not a defect.

→ **Period cost is 403 in every period from 4 to the end** - that is §5.3's steady state, and the
three periods before it are the warm-up. Period 3 costs 402 because it is the only period in the run
where `SUP-1` was already full and produced nothing.
→ **Total on-hand is 20 and in-pipeline 30 from period 3 onward**, and 40 / 20 before it. The regime
change at period 3 is the warm-up ending: until then one of the three arcs is idle each period, so 10
units sit as stock instead of travelling. Their sum is **50** in every steady-state period, which is
the 50 units §5.4's mass balance leaves over after 300 are served.
→ **Cumulative unmet is 0 in every one of the thirty periods.** Nothing goes short on a baseline run
of this network.

**219. The end of the run.** Scrub to **period 29**.
→ **Cumulative cost 12,094.00**, which is the `TOTAL_COST` card in the block below it. The running
total and the persisted metric agree exactly here because both are linear - unlike the loss area,
where the shaded region is a lower bound on the metric (section R).
→ On-hand **20**, pipeline **30**, cumulative unmet **0**.

**220. Where the baseline overlay is, and where it is not.** Look at the seven sparklines.
→ The first five - fill rate, served, period cost, cumulative cost, cumulative unmet - each carry a
**dashed grey line under the solid blue one**. On this baseline run the two coincide exactly in every
period, so each reads as one line with a halo (§6.4: a baseline run writes its own curve into the
baseline columns). An absent overlay here is the defect, not a coincident one.
→ **Total on-hand and In pipeline carry no dashed line at all**, and each says why in a line of grey
italic under it: the run's per-period table holds no undisrupted twin for those two columns. They are
drawn bare rather than overlaid with a copy of themselves, which would claim a disruption moved no
stock. The honest comparison for them is the two cards below.
→ A single **pink cursor** stands at the same horizontal position on all seven, and moves as one when
you step. Seven cursors that could drift apart would put one period in seven places.

**221. Stepwise, not smoothed.** Play the run and watch the cumulative-cost line.
→ It climbs in **flat treads with risers between them**, one tread per period - never a slope. The
engine is discrete-time, so a cost halfway through period 3 is a number no replication
produced. The same rule the canvas channels and the inspector's sparkline follow.

### The run suite

**222. Seven cards, two of them new.** Read the **RUN SUITE** block.
→ Seven cards, in the registry's own order: **Fill rate 100.0%**, **Service level 100.0%**,
**Worst-period fill rate 100.0%**, **Total cost 12,094.00**, **CVaR cost (α=0.95) 12,094.00**,
**Average inventory 22.0000**, **Average pipeline (WIP) 29.0000**.
→ The two new cards carry the **Absorption** family colour on their badges, and their tooltips read
*Mean end-of-period stock held across the horizon - the standing buffer the configuration pays for
and absorbs shocks with* and *Mean in-transit quantity - material committed but not yet arrived*.
→ **22.0 and 29.0 are exact**, derived by hand in §6.1: `(3 × 40 + 27 × 20) / 30` and
`(3 × 20 + 27 × 30) / 30`. A 21.99 or a 22.0001 is a defect, not a rounding difference - every
summand is an integer.
→ They are the horizon means of the two live rows above them, which is the cheapest cross-check on
this screen: on-hand averages 22 over a series reading 40, 40, 40, then 20; pipeline averages 29 over
20, 20, 20, then 30.

**223. No whisker, and that is what deterministic looks like.** Look at the interval under each card.
→ At the default **100 replications** every card reads its value with `[22.0000 – 22.0000]` beside it
and **no visible whisker bar** - every replication of this network is identical, so the sample
standard deviation is 0 and the interval is zero-width (§6.3). At `replications: 1` the bounds are
**absent** instead, and there is still no bar. Both are correct; neither is a defect.
→ Nowhere on this block is a zero-width interval drawn as a full-width bar, which would claim the
opposite of what it means.

**224. Absent is absent - and the inventory pair is not absent.** Read the note under the cards.
→ *TTR, LOSS_AREA, DISRUPTION_COST_DELTA and RESILIENCE_INDEX are **absent** - they are measured
against a disruption, and nothing here was disrupted. Absent is unmeasured, never zero.* And the
second half: AVG_INVENTORY and AVG_PIPELINE describe what the network *held*, which a run with no
scenario answers as well as any other, so both are present.
→ **There is no card for any of those four**, and no card reading 0. Four zeros would be four
resilience findings the run never made.

**225. The suite matches the run panel exactly.** Press **▶ Run** to open the run panel beside the
dashboard and compare the two lists.
→ Same seven codes, same order, same values, same intervals. One mapping (`core/metric-display`'s
`toMetricCard`), three surfaces - this one, the run panel, and the full results dashboard behind
**Full dashboard ↗**. Open that too: the same seven again.

### The comparison view, over two runs

**226.** Still in the editor, author the Stage-7 disruption of §8.1 against this network - target the
**link** `DC-1 → CUST-1`, start offset `10 d`, duration `3 d`, severity `1.0`, profile `STEP`,
probability `1.0` - then **Run scenario**. Let it finish.
→ The report replaces the baseline's, the canvas restarts playing, and the run panel offers
**Compare against Baseline (run #…) ↗**. Click it.

**227.** Read the matrix at `/projects/{p}/comparison?runIds=…`.
→ Two columns, one per run, in the order named. The **MIXED_SCENARIOS** note fires and says why -
baseline-versus-disruption measures only the scenario.
→ **`AVG_INVENTORY` and `AVG_PIPELINE` appear as ordinary rows**, with both cells filled:
approximately **22.0000 / 27.3333** and **29.0000 / 26.0000** (§8.5 derives the disrupted pair by
hand - a cut link leaves material standing, so average inventory rises while average pipeline falls).
→ **Neither row highlights a winner.** No green tint, no green edge bar and no bold value in either
cell - the treatment `DENSITY`, `AVG_PATH` and `CLUSTERING` already get. Compare with the `FILL_RATE`
row, where the baseline's 100.0% *is* highlighted, and with `TOTAL_COST`, where the cheaper column
is. Those two are ranked; these two decline to rank, because leaner versus more buffered is the
trade-off under study rather than a result.
→ **Neither appears as an axis on the radar chart.** An axis whose outward direction means nothing is
worse than a missing axis. Count the axes: the ranked rows only.

### Back on the canvas

**228. The disrupted live block.** Return to the editor, click empty canvas, and scrub through
periods **9 to 14** of the scenario run.
→ Period 10: fill **100.0%**, served **10 of 10**, cumulative unmet **0**. The dip lags the onset by
one period - the units served in period 10 were dispatched by `DC-1` in period 9, and in-flight
material is never stopped (§8.3).
→ Periods 11, 12 and 13: fill **0.0%**, served **0 of 10**, and **cumulative unmet counting 10 → 20 →
30**.
→ Period 14: served again, and **cumulative unmet stays 30**. Nothing catches up - the engine models
no backlog, which is what the row's own hint says and what a `FILL_RATE` of 0.9 measures.
→ **Total on-hand climbs** - 30, 50, **70** at period 12 - while **In pipeline falls** - 20, 10, **0**
at period 12. That is the stall propagating upstream drawn as two numbers: the chain is holding
everything and moving nothing.
→ On the first five sparklines the dashed baseline now **separates visibly** across those periods. On
the last two it is still absent, for the reason step 220 gives.

**229. Nothing was taken away.** With the dashboard on screen, click a node, then a link, then
shift-click a second node.
→ The panel behaves exactly as sections E and U leave it: the attribute form, the **Simulation** card
during playback, and the bulk-edit form on a multi-selection. The dashboard returns only on an empty
selection. Press **Delete** with a node selected on this frozen network → the fork prompt, unchanged.

---

## W. Disruption playback end-to-end (FR-16 → FR-19)

**The acceptance walk.** Sections Q to V each check one surface against the sample. This section is
the *whole path in one sitting* - author a disruption on the canvas, run it, watch it play, and read
the same event off the canvas, the element inspector, the network dashboard and the comparison
matrix - because the failure this stage exists to catch is not a wrong number on any one of them but
**four surfaces telling four stories about one run**. Nothing here is a new feature: every step names
the section that already covers it in isolation, and adds only what the walk itself asserts.

> **The letter.** The brief for this stage calls this walk "§U". §U was already taken (the element
> inspector) and so was §V (the network dashboard), and both are cited by name from
> `README.md` and `sparkline-geometry.spec.ts`. Renaming them would break those citations, so the
> walk lands where a final section belongs - **§W** - and nothing above it moved.

**Every per-period figure below comes from
`../snrm-backend/samples/four-echelon-playback/README.md` §8**, which derives the
disrupted run by hand from the loop. Where a figure is arithmetic *over* that table - a
horizon mean, a running total, a ribbon width - the working is shown inline so it can be re-checked
without re-reading the code. **No figure here was read off a screen.** A disagreement is a defect in
the application or in that document, and §9 of it says to re-derive before believing either.

**Import fresh.** Do not reuse the network sections T–V left behind: it is frozen, it carries their
runs, and a scenario authored against a network that already has a `DC outage` event would be
measuring two. Import
`../snrm-backend/samples/four-echelon-playback/network.xml` again through the wizard.
→ `valid: true`, `committed: true`, **0 errors, 0 warnings**, 4 nodes, 3 links, 1 product,
4 node-product rows, clock **1 d × 30 / NEAREST**. Open it in the editor.

### Author it on the canvas (FR-16)

**230.** Click the arc **`DC-1 → CUST-1`** on the canvas - the link itself, not either endpoint -
then press **⚡ Disruptions**, **+ New scenario**, type `DC outage`, Enter.
→ 201 on `POST /projects/{p}/scenarios`, the picker switches to it, `GET /scenarios/{s}` follows, and
the body reads *"Nothing in this scenario strikes this network yet."* The arc stays selected
throughout - opening a panel is not a selection gesture.

**231.** Press **⚡ Add disruption**.
→ The button is **enabled** (step 133's hint is what shows when it is not), and the editor opens with
the arc's **name** where the timeline's editor shows a **Strikes** dropdown - `DC-1 → CUST-1`, with
*"Selected on the canvas. Select something else to re-aim."* A link is aimed exactly as a node is;
there is no second path for arcs.

**232.** Fill in the event of §8.1 and press **Add event**:

| Field | Value |
|---|---|
| Starts after | **10 d** |
| Lasts | **3 d** |
| Severity | **1.0** (100%) |
| Recovery profile | **STEP** |
| Probability | **1.0** |

→ 201 on `POST /scenarios/{s}/events?networkId={n}`. One row appears, `DC-1 → CUST-1`, reading
**`100% · 10 d → 3 d · periods 10–13 of 30`**. The **⚡ Disruptions** badge reads **1**, and the arc
on the canvas carries the static red halo of step 136. No `p 100%` on the line - probability appears
only when it is not 1 (`eventLine`).
→ **Read the two halves of that line as different things.** `10 d → 3 d` is *starts after → lasts*,
the two declared durations verbatim - it is **not** an interval, and step 135's `0 d → 10 d` only
looks like one because its offset is zero. `periods 10–13 of 30` is the interval: offset 10 ÷ 1 =
period 10, duration 3 ÷ 1 = 3 periods, so the window is periods **10, 11 and 12** and it *ends at* 13
with period 13 already recovered (§8.1). If the panel prints `10 d → 13 d` here, the bar label has
been changed to an interval and step 135 will have moved with it.
→ Hover the arc: the canvas tooltip states the same window, word for word (step 137).

**233. A scenario write is still not a network edit.** Watch the toolbar and the Network tab across
steps 230–232.
→ **All changes saved** throughout, no `PATCH` of any kind, no fork prompt. This is step 145's
invariant on a link target rather than a node one; if the walk trips it here, the defect is in the
aiming path and not in the guard.

### Run it (FR-17)

**234.** Press **▶ Run**. The scenario picker already reads **DC outage** - it follows the
disruptions panel (step 152). Press **Run scenario**.
→ `POST /simulations` carries `scenarioId`, and the 202's `replications` reads **200**: the paired
set is back, because there is now a disruption to isolate (contrast step 153's baseline,
which is 100). The frozen banner appears over the canvas at the 202.
→ The provisional block behaves as step 154 describes, and on this network it is unusually easy to
read: every replication is identical (§1.1), so the running means should show **0.9 fill and 12,706
cost from the first completed replication onward** and only the `k / 200` counter should move. That
is what `ProvisionalFigures` mirroring its calculators' definitions is meant to buy - the watched
number not jumping when the persisted value replaces it. A figure that climbs into place instead is
worth reporting; it is not noise, because there is none on this run.

**235. It plays by itself.** Let the run finish and do nothing else.
→ The report renders in the panel **and the canvas starts playing from period 0** (step 175). Exactly
**one** `GET /simulations/{runId}/timeseries/elements`; watch the whole run through and there is **no
further request of any kind**.
→ Pause and set the transport's speed to **1 period / second** for the rest of this section - the
periods that matter are 9 to 18 and they pass in a second at the default.

### The canvas, periods 9 → 18 (FR-18)

**236. The dip lags the onset, and this is the one thing the animation exists to get right.** Scrub
to period 9 and step forward with **→**.

| Period | `DC-1 → CUST-1` | `CUST-1` | `DC-1` on-hand (gauge) |
|---|---|---|---|
| 9 | thick, solid, **no halo** | serving, no overlay | 5 (a fifth) |
| **10** | **thin, dashed, red halo** | **serving in full, no overlay** | **15 (three fifths)** |
| **11** | thin, dashed, red halo | **orange stockout overlay** | **25 - gauge full** |
| **12** | thin, dashed, red halo | orange stockout overlay | **25 - gauge full** |
| **13** | **thickest ribbon in the run, no halo** | orange stockout overlay | **5 (a fifth)** |
| 14 | **thin, dashed, no halo** | serving again, overlay gone | 5 (a fifth) |

*(`DC-1`'s gauge is its stock against its own horizon maximum of **25**, so a fifth full is 5 units -
step 177's rule, and the reason the quantity is stated beside it.)*

→ **The event window is 10–12 and the stockout window is 11–13** (§8.7). They are different
intervals, and if they coincide, in-flight material is being cancelled when the link goes dark -
§9 item 4 of the sample. Period 10's customer runs on what `DC-1` dispatched in period 9.
→ **Periods 13 and 14 are the two readings a single "thin arc" would run together.** At 13 the arc is
open and carrying the recovery surge; at 14 it is open and carrying nothing, because `CUST-1` opened
period 14 holding its full target of 20 and ordered nothing (§8.2, the same mechanic that idles arc
**c** at period 0). **Dashed and faded** is the same in both, and **the halo is what differs** -
that is the whole reason availability is a channel of its own rather than a width.

**237. The stall walks upstream while the outage sits downstream.** Step 10 → 12 watching the two
arcs the event does *not* target.
→ `PLANT-1 → DC-1` goes thin and dashed at **period 11**, `SUP-1 → PLANT-1` at **period 12**, and
**neither ever carries a halo** - they are idle, not dark. Both gauges fill as they stall: `PLANT-1`
reaches 25 at period 12, `DC-1` at period 11, and at period 12 the whole chain reads
**20 / 25 / 25 / 0** on hand with nothing moving anywhere (§8.3).

**238. The ribbon widths are per-arc, and this run proves why that has to be said.** Compare arc
**c** at period 15 of *this* run with arc **c** at period 15 of the baseline run of section T.
→ Both carry **10 units** and the disrupted one is drawn at **about half the width**. Every arc in
this run peaks at **20** - `c` at period 13, `b` at 14, `a` at 15 - against a baseline run where all
three peak at 10, and a ribbon is normalised against *its own* horizon maximum
(`playback-channels.flowWidth`: `2 + 7 × 10/20 = 5.5 px` here against `2 + 7 × 10/10 = 9 px` there).
→ **This is the stated cost of per-element normalisation, not a defect** (step 177's rule, on links).
Two runs' ribbons are not comparable by eye; the inspector's numbers are what compares them.

**239. Recovery, and the wave working itself out.** Step 13 → 18.
→ Period 13: `DC-1` ships **20** - the customer's whole accumulated shortfall, and the largest single
flow in either run. Period 14: it lands, `CUST-1` serves again and holds **10**.
→ Period 15: **`SUP-1`'s gauge empties completely** - the only period in either run where a node
holds nothing (§8.3). Period 16: it refills to **20** in one period.
→ Period 17 ends on `10 / 5 / 5 / 0` and **period 18 opens on the steady state of §5.3**. From 18 to
29 the canvas is indistinguishable from the baseline run: three thick arcs, no halo, no overlay.

### The element inspector (FR-18)

**240. `DC-1` - the swell.** Select `DC-1` and read **Realtime inventory (on-hand)** at 9 → 14.

| Period | 9 | **10** | **11** | **12** | **13** | 14 |
|---|---|---|---|---|---|---|
| On-hand | 5 | **15** | **25** | **25** | **5** | 5 |

→ **The baseline overlay diverges from the onset, not from the dip.** The dashed grey line sits flat
at **5** across all six while the solid line climbs - because the event stops `DC-1` shipping in
period 10 even though the customer does not feel it until 11. Onset-relative and impact-relative are
visibly different windows on this one card, which is the argument step 247's `TTR` reading turns on.
→ Caption: **`5–25 over 30 periods`** - both series on one scale.
→ **Average inventory 7.67.** Over §8.2's `DC-1` column: periods 0–9 give `15 + 15 + 8 × 5 = 70`,
periods 10–17 give `15 + 25 + 25 + 5 + 5 + 15 + 5 + 5 = 100`, periods 18–29 give `12 × 5 = 60`;
`230 / 30 = 7.666…`. The dotted amber line sits at that height.
→ **Throughput 0 · 0.0% in every period, period 13 included.** The 20-unit surge leaves from stock,
which enters the flow at the dispatch vertex and never crosses the node's own capacity arc (§6.5.1).
A row that called this "production" would read 20 here and be wrong.

**241. `CUST-1` - the loss that is never made up.** Select `CUST-1` and scrub 10 → 14.
→ Period **10**: **10 of 10 · 100.0%**, **no stockout badge**, **Lost so far 0**.
→ Periods **11, 12, 13**: **0 of 10 · 0.0%**, red **stockout** badge, **Lost so far 10 → 20 → 30**.
→ Period **14**: served again, badge gone, on-hand **10**, and **Lost so far stays 30** - every period
to 29. Under it the fixed caption *lost, not backlogged - the engine models no catch-up*.
→ **In transit / arrivals** at 13 and 14: `20 / 0` then `0 / 20`. The surge is in the pipeline at the
end of 13 and lands in 14 (§6.5.3's offset, at its largest).
→ **Current lead time** reads **1 period (1 day)** at period **13** - the one period in 10–14 that
dispatched anything toward it - and *no inbound this period*, in grey italic, at **10, 11, 12 and
14**. A `0` in any of those four would claim something was dispatched and arrived instantly, which is
a different and false claim (§6.5.4). Period 10 is the sharp one: the customer is **served in full
and has nothing inbound**, which is the whole of §8.3's first finding said in two rows of one card.

**242. `SUP-1` - the far end of the wave.** Select `SUP-1` and read **Throughput · utilisation**.
→ **10 · 10.0%** at periods 9–12, **0 · 0.0%** at 13, 14 and 15, **20 · 20.0%** at **period 16**, and
**0 · 0.0%** at 17. That row is §8.4's variable-cost line divided by `varCost` 0.1
(`1, 1, 1, 0, 0, 0, 2, 0` over periods 10–17), and period 16 is the largest production in either run.
→ On-hand **0 at period 15**, and the gauge empty to match.
→ **Availability 100.0% in every period.** The event is on a link; nothing takes this node's capacity.

**243. The arc.** Select `DC-1 → CUST-1` and scrub 9 → 15.

| Period | 9 | **10** | **11** | **12** | **13** | 14 | 15 |
|---|---|---|---|---|---|---|---|
| Flow | 10 | **0** | **0** | **0** | **20** | **0** | 10 |
| Availability | 100.0% | **0.0%** | **0.0%** | **0.0%** | 100.0% | 100.0% | 100.0% |
| Utilisation | 10.0% | ***no capacity available*** | ***no capacity available*** | ***no capacity available*** | **20.0%** | **0.0%** | 10.0% |

→ **Periods 12 and 14 are the pair that matters.** Both carry zero flow; one reads *no capacity
available* in grey and the other reads **exactly 0.0%**. A dark arc is not an idle one, and the
engine sends `null` rather than a fraction for precisely this - if 14 also greys out, the
null is being derived from the flow instead of from the availability.
→ **Declared lead time reads `1 period (1 day)` in all seven**, outage included, under
*realised transit always equals the declared lead - disruptions re-route flow, they never delay
material in transit.*
→ **Average flow 8.67** - `260 / 30`, against the baseline run's **9.67** (`290 / 30`). Over §8.2's
`c` column: 9 periods at 10 (t=1–9), three at 0, one at **20**, one at 0, then 15 at 10 (t=15–29).
**The arc moved exactly 30 fewer units than it did undisrupted**, which is the same 30 the customer
lost and the same 30 step 244's cumulative-unmet row ends on - three readings of one shortfall, on
three surfaces. The recovery surge re-times material; it does not replace it.

### The network dashboard (FR-19)

**244. The live block, period by period.** Click empty canvas and scrub 9 → 18.

| Period | Fill rate | Served / demand | Period cost | Cumulative cost | Cumulative unmet | Total on-hand | In pipeline |
|---|---|---|---|---|---|---|---|
| 9 | 100.0% | 10 of 10 | 403.00 | 4,034.00 | **0** | 20 | 30 |
| **10** | **100.0%** | **10 of 10** | 404.00 | 4,438.00 | **0** | **30** | **20** |
| **11** | **0.0%** | **0 of 10** | **606.00** | 5,044.00 | **10** | **50** | **10** |
| **12** | **0.0%** | **0 of 10** | **608.00** | 5,652.00 | **20** | **70** | **0** |
| **13** | **0.0%** | **0 of 10** | **605.00** | 6,257.00 | **30** | **50** | **20** |
| 14 | 100.0% | 10 of 10 | 403.00 | 6,660.00 | **30** | 40 | 20 |
| 15 | 100.0% | 10 of 10 | 402.00 | 7,062.00 | 30 | 20 | 30 |
| 16 | 100.0% | 10 of 10 | **406.00** | 7,468.00 | 30 | 40 | 20 |
| 17 | 100.0% | 10 of 10 | 402.00 | 7,870.00 | 30 | 20 | 30 |
| 18 | 100.0% | 10 of 10 | 403.00 | 8,273.00 | 30 | 20 | 30 |

→ The cost column is §8.2's; the two stock columns are §8.5's derivation of the inventory pair; the
cumulative columns are running totals **through** the period on screen, so the 10 lost in period 11 is
already in period 11's figure.
→ **Cumulative unmet reaches 30 at period 13 and never moves again.** Not at 12, and not 40 at 14 -
three periods of 10, and no backlog.
→ **On-hand climbs 30 → 50 → 70 while In pipeline falls 20 → 10 → 0.** Those two lines crossing is
the stall drawn as arithmetic: the chain is holding everything and moving nothing. Their sum is not
constant here - it rises from 50 to 70 - because `SUP-1` keeps producing 10 a period into a network
that is serving nobody (§8.5).
→ On the first five sparklines the dashed baseline **separates visibly** across 11–13; on the last
two there is still no dashed line at all, for step 220's reason.

**245. The end of the run.** Scrub to **period 29**.
→ **Cumulative cost 12,706.00**, equal to the `TOTAL_COST` card below it. On-hand **20**, pipeline
**30**, cumulative unmet **30**.

**246. The run suite - eleven cards, and nothing absent.** Read the **RUN SUITE** block. In the
registry's own order (`@Order` 100 → 200):

| Card | Reads | From |
|---|---|---|
| Fill rate | **90.0%** | 270 / 300 |
| Service level | **90.0%** | 27 / 30 |
| Worst-period fill rate | **0.0%** | periods 11–13 |
| Time to recovery | **0 periods**, over a second line **0 periods (0 days)** | see step 247 |
| Loss area | **3 fill·periods** | 3 × (1.0 − 0.0) |
| Total cost | **12,706.00** | §8.4 |
| Disruption cost | **612.00** | §8.4 |
| CVaR cost (α=0.95) | **12,706.00** | deterministic, so equal to total cost |
| Resilience index | **85.0%** | 17/20 over periods 10–29 |
| Average inventory | **27.3333** | 820 / 30 |
| Average pipeline (WIP) | **26.0000** | 780 / 30 |

→ **All eleven are present.** This is the mirror of step 224: there, four cards were absent because
nothing was disrupted; here nothing is absent, and a card reading `-` would be as wrong as a `0` was
there.
→ **Ten of the eleven show `[value – value]`** - a zero-width interval and **no visible whisker** at
200 replications, because every replication is identical and the sample standard deviation is 0
(§6.3). **`CVaR cost` is the eleventh and reads `exact` instead**: it is a functional of the whole
replication set rather than a mean of per-replication values, so it arrives with both bounds null
and an interval computed the way the others are would overstate its precision. A
zero-width interval on that card would be the defect, not the absence of one.
→ **`TOTAL_COST` − the baseline run's `TOTAL_COST` is 12,706 − 12,094 = 612**, which is what
`DISRUPTION_COST_DELTA` reads. Two independently computed numbers agreeing is the cheapest check on
this screen; if they differ, one of the two runs is not the pair the other thinks it is.
→ **`Loss area` and the shaded region under the performance curve coincide exactly here.** They are
different quantities in general - the metric takes each replication's shortfall then averages, the
drawing averages first - and on a run whose replications are identical the difference is zero. The
sentence on screen says so; a gap between them on *this* run is a defect.

**247. `TTR` reads 0, and that is recorded rather than fixed.** Read the Time to recovery card.
→ **Two lines, not three.** The value reads **`0 periods [0 periods – 0 periods]`** and the line
under it reads **`0 periods (0 days)`** - one dual statement per card. This is the only
time-valued card the suite has, so it is the only place the duplication could show: before this
stage `app-ci-value` was also given the run's `periodLength`, which made it write the dual form into
the value *and* into both interval bounds, and `card.readable` then repeated it a third time. If the
card prints `0 periods (0 days) [0 periods (0 days) – 0 periods (0 days)]`, the binding is back.
→ The implemented definition (`TimeToRecoveryCalculator`)
is onset-relative - `min { p ≥ onset : disruptedFill(p) ≥ baselineFill(p) } − onset` - the onset is
period 10, and period 10's fill rate is **1.0**, so the walk terminates on its first step (§8.6).
> **The delayed-impact caveat.** `TTR` asks when performance regained its baseline; it does not ask
> whether performance had yet left it. Every network in the repository before this one had a lead
> time of zero on its last leg, so onset and impact coincided and the question never arose. Here the
> one-period pipeline separates them, and the metric reports 0 for a disruption that cost three full
> periods of service. **Read `LOSS_AREA` (3.0) and `RESILIENCE_INDEX` (0.85) instead**: both
> integrate over the horizon rather than testing a single period, so both see the delayed impact in
> full, and between them they carry the depth and the duration `TTR` misses here.
→ **If the card reads 4** - the value an impact-relative onset would give (recovery at period 14,
onset at 10) - **record 4 and stop.** That is a different definition from the one the engine
implements and the one §8.6 derives, and a disagreement of definition is settled before either is
changed: raise it as a finding rather than editing the calculator or this step.
→ Either way **do not assert 3 here.** 3 is the *duration of the impact*, which is `LOSS_AREA`, not
`TTR`, and a checklist expecting it is asserting a definition nothing in this repository implements.

### Side by side (FR-17)

**248.** In the run panel press **Compare against Baseline (run #…) ↗** - the baseline run of
section T is the other column. It opens `/projects/{p}/comparison?runIds=…,…`.

| Row | Baseline column | `DC outage` column | Winner |
|---|---|---|---|
| `FILL_RATE` | **100.0%** | 90.0% | baseline |
| `SERVICE_LEVEL` | **100.0%** | 90.0% | baseline |
| `MIN_FILL_RATE` | **100.0%** | 0.0% | baseline |
| `TTR` | **-** | **0 days** | - |
| `LOSS_AREA` | **-** | 3 fill·periods | - |
| `TOTAL_COST` | **12,094.00** | 12,706.00 | baseline |
| `DISRUPTION_COST_DELTA` | **-** | 612.00 | - |
| `CVAR_COST` | **12,094.00** | 12,706.00 | baseline |
| `RESILIENCE_INDEX` | **-** | 85.0% | - |
| `AVG_INVENTORY` | 22.0000 | 27.3333 | **neither** |
| `AVG_PIPELINE` | 29.0000 | 26.0000 | **neither** |

→ **One column per run**, headed `… - baseline` and `… - DC outage`, with the blue banner explaining
the run-keyed mode. The two columns name the **same network**, which is the only thing `?runIds=` can
express and `?networkIds=` cannot.
→ **`MIXED_SCENARIOS` fires, and it is right to** - this comparison measures only the scenario. It is
a note about what is uneven, not a warning that something is wrong.
→ **The four disruption-relative rows carry `-` in the baseline column**, never 0 (§6.2).
→ **`TTR` reads `0 days` here, not `0 periods (0 days)`.** A comparison cell is converted to the
row's common unit server-side and the column header names the period, so the period count would be
the wrong half to print (`cellText` → `formatInUnit`). Two surfaces, two correct renderings of one
stored 0 - and step 247 is where the periods half is read.
→ **The inventory pair highlights no winner** - no green tint, no edge bar, no bold - while
`FILL_RATE` and `TOTAL_COST` above them do. `AVG_INVENTORY` rising as `AVG_PIPELINE` falls is *where
the material went*, not a result: a cut link leaves material standing. **Neither appears as an axis
on the radar** - an axis whose outward direction means nothing is worse than a missing axis (step
227).
→ **The deltas agree with §8:** cost +612, fill −0.1, inventory +5.3333, pipeline −3.0.

**249.** Export that view (.xlsx).
→ Two run columns with **distinct headers** naming the run ids (step 158), and the same eleven rows.

### Regression

**250.** Re-walk **§M** (the time-settings dialog including the playback-speed row), **§Q**, **§R**,
**§S** and **§T** against this network and its two runs.
→ Nothing in sections Q–T changed behaviour because a scenario now exists: the disruptions panel is
still live on the frozen network (step 145), the speed row still sends nothing (steps 163–164), and
the transport still reads the run's clock (step 179).

**251.** Both suites:

```bash
mvnw.cmd test
```

```bash
npm test
```

→ Fully green. The backend suite includes `SimulationVerificationTest` and `ElementTimeseriesTest`,
which transcribe `docs/simulation-verification.md`; the frontend suite includes
`network-series.spec.ts`, `sparkline-geometry.spec.ts` and `playback-channels.spec.ts`, which
transcribe the sample's §6. **A number that fails here and passes above, or the reverse, is the
finding** - the documents are the specification, so change the document first and the test after
(both standing working agreements in this repository).

**252. Performance sanity.** Import `../snrm-backend/samples/multi-echelon-50-node`
(50 nodes, 64 links, **1 d × 120**), run a **baseline**, and let playback start.
→ `GET /simulations/{runId}/timeseries/elements` - one request, carrying 120 periods × 114 elements -
completes in **under about 2 s** on localhost. Set the transport to **10 periods / second**: the
whole run plays in ~12 s, gauges and ribbons update once per period, and the frame loop stays smooth.
→ Devtools **Performance**: still one repaint per **period**, not per frame (step 191), on a canvas
with fourteen times the elements. If it stutters, `writeData`'s change-guard is the first thing to
look at - an unchanged write marks the element dirty and costs a re-render of it.

---

## X. Captions on a node and an arc (FR-30)

**What this section is for.** A caption is an annotation *about the element* - "Nordic hub, 3PL
operated", "single-sourced, 6-week qualification" - written while a diagram is being built and read
by whoever opens it next. FR-30 draws it beneath the label the element already carries, in a smaller
and quieter type, with a checkbox that hides it without losing it.

The canvas half is deliberately not unit-tested (`element-captions.spec.ts` pins the arithmetic and
the rules; the rest is Cytoscape), so **this section is the coverage** for everything below.

### Setup

**253.** Import a network that already carries captions:
`../snrm-backend/samples/xml-6-node/network.xml` (project → **Import**; XML skips the
mapping step). Open it in the editor.
→ Three captions are visible from the first frame, without touching the panel:

| Element | Caption on the canvas |
|---|---|
| `SUP-1` | *Sole source - 6-week qualification* |
| `DC-1` | *Nordic hub - 3PL operated* |
| `PLANT-1 → DC-1` | *Contracted road leg, no alternative carrier* |

→ **`PLANT-1` draws none, though it has one**: its `captionVisible="false"` (the file says so, and
`samples/README.md` explains the three ways it states the pair). Written, kept, not drawn.

**254.** Look at a node's two lines together.
→ The name is above, at the size it has always been; the caption is **beneath it, visibly smaller
and greyer**. It never reads as the element's identity - which is the point, since every other
surface in the tool identifies an element by its name.
→ The arc's caption sits **under its lead-time label**, and unlike that label it is **not rotated**
with the arc.

### The panel - the text is single, the checkbox is bulk

**255.** Select `DC-1`. Scroll the property panel past **Region**.
→ A **Caption** field holding *Nordic hub - 3PL operated*, and beneath it a checked **Show caption on
canvas** box. The hint under the field reads *"Empty the field to remove it"*, and the one under the
box reads *"An empty caption draws nothing whatever this says."*
→ **There is no × button**, unlike Region and Capacity. That is the feature, not an omission - see
step 261.

**256.** Type ` - winter capacity halved` on the end and press Tab.
→ The canvas caption updates. The toolbar goes **Unsaved changes** and, within 2 s, **All changes
saved**; devtools shows **one** `PATCH /networks/{id}/nodes` whose body is
`{"nodes":[{"nodeId":…,"caption":"Nordic hub - 3PL operated - winter capacity halved"}]}` - the
caption and nothing else. It is the same debounced bulk PATCH every other attribute rides.

**257.** Untick **Show caption on canvas**.
→ The caption disappears from the canvas. **The field still holds the text.** One PATCH carrying
`captionVisible: false`.

**258.** Tick it again, then box-select `DC-1`, `SUP-1` **and** `PLANT-1` together.
→ The Caption field is **replaced by a sentence**: *"A caption is prose about one node, so it is
written one node at a time. The box below applies to all 3."*
→ The checkbox is still there, and it is **indeterminate** with a *mixed* marker beside it -
`PLANT-1`'s caption is hidden and the other two are shown.

**259.** With those three still selected, click the checkbox until it is **unticked**.
→ Every caption on the canvas goes, in one gesture. One PATCH, three `nodes` entries, each carrying
`captionVisible: false`. This is what "preparing a figure" means, and it is why the checkbox
bulk-applies where the text does not.

**260.** Ctrl-Z.
→ All three come back - `DC-1` and `SUP-1` shown, **`PLANT-1` still hidden**. The undo restores each
element's own previous flag rather than one value across the selection.

### Clearing - the part a PATCH normally cannot do

**261.** Select `SUP-1`, select all the text in the Caption field, delete it, press Tab.
→ The caption disappears from the canvas. Devtools shows a PATCH whose body carries
`"caption":""` - a **present but empty** string, the one field on this endpoint where that is a
write rather than "leave alone" (`com.snrm.network.Captions`). No `PUT /nodes/{id}` is sent.

**262. Reload the page (F5).**
→ **The caption is still gone.** This is the acceptance criterion for the whole clearing story:
"typed a caption, deleted it, it came back on reload" is not a limit of this editor.

**263.** Ctrl-Z.
→ *Sole source - 6-week qualification* is back on the canvas and in the field. Ctrl-Y removes it
again.

**264.** Type a caption onto **`CUST-1`**, which has none, then Ctrl-Z.
→ The caption `CUST-1` never had is **removed**, not left drawn. The undo sends `caption: ""`; every
*other* nullable field's undo has to leave the value in place, which is the limit
`EditNodesCommand` documents.

**265.** Tab into the empty Caption field of a node that has none, and Tab straight out again.
→ **No PATCH, and no new undo step.** Ctrl-Z still reverses whatever you did before it.

**266.** Paste 250 characters into a Caption field.
→ The input stops accepting at **200** - the column's own width, so the refusal is a field that will
not take another character rather than a 400 from the server.

### The canvas, under gesture

**267. Drag a captioned node right across the canvas.**
→ The caption **follows the node the whole way**, staying the same distance beneath its name - not
snapping into place when the drag ends. The captions of an arc's endpoints move too, and the arc's
own caption slides along to stay at the new midpoint.

**268.** Drag a **multi-selection** of four nodes, two of them captioned.
→ Same, at frame rate. Release: one undo entry, *Move 4 nodes*. Ctrl-Z - the nodes and the captions
go back together.

**269. Box-select over a caption.** Turn on the box-select toolbar toggle and drag a rubber band that
starts **on top of** a caption's text.
→ The rubber band starts normally; the caption is not a click target and does not swallow the
gesture. When the band lands, **the caption is never in the selection** - the panel header counts
only real nodes and links.

**270.** Click a caption directly.
→ Nothing selects. The click lands on the canvas beneath it, so on empty canvas the property panel
shows the network dashboard (FR-19).

**271. Draw an edge past a caption.** Hover a node, drag from a corner handle, and pass the pointer
over a caption on the way to the target.
→ The rubber band is unaffected; the caption is never a snap target and never highlights green or
red. Complete the link over a valid node - it is created as normal. Press Escape mid-gesture on
another draw - the band is cancelled, and **every caption is still on the canvas**.

**272. Auto-layout.** Press **Auto-layout** on the toolbar.
→ The network is laid out left-to-right by echelon exactly as in section G - **the captions take no
part in the layout** (no stray gaps, no columns pushed apart to make room for a label) - and each one
lands under its element. Ctrl-Z restores the previous positions, captions included.

**273. Criticality sizing.** Open the metrics panel and switch on **Size by criticality**.
→ Nodes resize, and **each caption re-anchors** so it stays the same distance below the node's
*bottom edge* - no caption ends up inside a grown node or floating below a shrunken one. Switch it
off: they settle back.

**274. Disruption halos (FR-16).** Author an event against a captioned node from the disruptions
panel (section Q).
→ The red underlay halo is drawn **behind** the node, and the caption is unaffected and still
readable. The node's hover tooltip still shows the window and severity.

### Playback (FR-18), which does a real teardown

**275.** Run a **baseline** from the run panel and let playback start.
→ Captions stay exactly where they are for the whole run - one caption per element, never two, never
gone. Gauges fill, ribbons thicken and halos pulse underneath them.

**276.** Stop playback, or select a run whose element detail is unavailable.
→ `clearPlayback` strips every gauge and class, and **the captions are untouched by it**. If you left
the criticality encoding on at step 273 it is re-stamped, and so are the caption anchors.

### It is an ordinary edit

**277.** On the network frozen by step 275's run, change any caption or tick the checkbox.
→ The **fork prompt** opens, exactly as it does for a capacity: *fork a configuration variant*,
*discard the runs and edit in place*, or *keep reading*. **No caption edit is exempt**: the FR-29
rename carve-out was considered and not extended.

**278.** Fork the variant and edit the caption there.
→ It saves normally, and the copy carries every caption the base network had - the clone is a
server-side copy of the whole configuration.

### Undo of a delete

**279.** Select `DC-1` (captioned and shown) and `PLANT-1` (captioned and **hidden**) and delete
them, confirming the dependent-data dialog.
→ Both nodes go, their arcs cascade, and **their captions go with them** - nothing is left stranded
on the canvas where a deleted node used to be.

**280.** Ctrl-Z.
→ Both nodes come back with their captions: `DC-1`'s **drawn**, `PLANT-1`'s **still hidden**. The
arc between `PLANT-1` and `DC-1` comes back carrying *Contracted road leg, no alternative carrier*.
An undo that re-showed a deliberately hidden caption would be as wrong as one that lost the text.

### Round trip

**281.** Export the network as **XML** from the project dashboard and open the file.
→ Every caption is on its element as `caption="…"`, and **`captionVisible` is written on every row
including the uncaptioned ones** - both halves of the pair stay well-formed, so typing a caption into
an exported workbook and re-importing needs no flag invented for it.

**282.** Re-import that file as a new network and open it in the editor.
→ Identical captions, identical hidden/shown states, identical layout. A round trip that dropped half
the diagram would be a round trip that lost it - the reason `pos_x`/`pos_y` exist at all, and
the reason a caption travels beside them.

### Out of scope, deliberately

**283.** Open the results dashboard for a run of this network (FR-22), and open **Compare side by
side** for two networks (FR-25).
→ **Neither miniature draws captions**, and that is correct: both are read-only structural views at a
scale where a second line of type would compete with the names the panes match on. There is also
**no global "show all captions" toggle** in the editor - Ctrl-A and one click of the checkbox is that
gesture today. Say so if either proves worth building; do not assume it from this section.

**284.** Unit tests:

```bash
npm test
```

→ `element-captions.spec.ts` passes: what draws (including an empty caption drawing nothing whichever
way the flag points), what an edit means (the empty-string undo, and null-versus-`''` being one
state), the id-ownership contract the render diff rests on, and the anchor arithmetic worked by hand
- 46 px below a default node's centre, 38 and 60 at the ends of the criticality range, 9 below an
arc's midpoint. `editor-commands.spec.ts` carries undo of a caption typed onto an element that had
none, the checkbox bulk-applying, and undo of a delete restoring a hidden caption still hidden;
`api-nulls.spec.ts` carries an absent caption on the wire reading as null rather than undefined,
which is what makes that first case work at all.
