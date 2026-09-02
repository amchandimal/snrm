# The project dashboard - manual test script

Its own numbering, starting at 1: this covers `features/projects`, where
`../network-editor/MANUAL-TEST.md` covers the canvas.

**Sections A–F: the project archive.** Export a whole experiment, restore it into a
new project, and check that what comes back is what went in. Sections A–B set up an experiment worth
archiving and export it. Section C is the restore and the report it renders - findings with
severities, the counts comparison, and the offer of the restored project. Section D is the round trip
proper: the assertions of `api-tests.http` requests 75–77, read off the screen instead of off a REST
client. Section E is the failures, including the one the backend refuses outright
(`ARCHIVE_UNREADABLE`). Section F is housekeeping.

The backend's `api-tests.http` requests **75–78** are the same walk through the API alone; where a
step corresponds to one, it says so.

**Sections G–N: selecting several networks and acting on the set (FR-23, FR-24).** The
checkbox column and the select-all, the actions menu, reconciliation on reload, the two-group
confirmation, and the per-network outcome of a delete that is refused part-way. **Section M is the
FR-24 round trip**: export three of six networks as a standalone project, import the zip on the
project list, and check that what comes back is exactly those three with their runs, the whole
catalogue, and the cross-network scenario event visibly dangling. Section G can be run on its own
against a fresh project - it does not need the archive sections above.

The menu's third action, **Compare side by side** (FR-25), is checked here only as a menu entry and
its cap; the window it opens has its own script at `../comparison/MANUAL-TEST.md`.

**Sections O–R: a row is one action and a menu, and Duplicate network (FR-26).** The row
reorganised - Open in editor a button, everything else behind one per-row menu - and the new
**Duplicate network**, which is a *fork*: section P is the provenance test that proves it, and
section Q duplicates a **frozen** network, which is ungated. Runnable on its own against a fresh
project.

**Sections T–X: renaming a network from the table (FR-29).** The row menu's
new first entry. **Section V is the one to run if you run one**: rename a network with a completed
run against it *and* the project's baseline flag on it, and confirm the flag did not move - the trap
this feature exists to avoid is a rename that sends the name alone and silently un-baselines the
project. Section U is the validation and the duplicate-name refusal; section W is the panels below
the table keeping up. Runnable on its own against a fresh project.

## Setup

**S1.** Backend running: `mvnw.cmd spring-boot:run` in `../snrm-backend`.
→ <http://localhost:8080/actuator/health> returns `{"status":"UP"}`.

**S2.** Frontend running:

```bash
npm start
```

→ Compiles clean; the app serves at <http://localhost:4200>.

**S3.** Sign in.
→ The project list. It now carries a **Restore a project archive** card beneath the table (or
beneath the "No projects yet" panel - the card is not conditional on having projects, since a
restore is one way to get your first one).

---

## A. An experiment worth archiving

The archive carries what was *found*, not only what was modelled, so it is only worth testing
against a project that holds a completed run.

**1.** Create a project named `Archive Test`. Open it. Press **Products** and add `Gearbox` with unit
value `250`.
→ The amber "no products yet" banner clears.

**2.** Press **Import network (CSV · Excel · XML)** and import a sample from
`../snrm-backend/samples/four-echelon-playback/`, naming it `Baseline`.
→ The wizard reaches step 3 with no errors, and the network opens in the editor.

**3.** Back on the dashboard, press **Disruption scenarios**, create `Plant outage`, and add one
event against `Baseline` - any node, any window inside the horizon.
→ The event bar appears on the timeline.

**4.** Open `Baseline` in the editor and run **both** a baseline run (no scenario) and a run of
`Plant outage` from the run panel. Wait for both to reach DONE.
→ The dashboard's network row now reads **Frozen**: a completed run holds it.

**5.** Optional, and worth doing once: on the dashboard, note the project's exact name. You will need
it in step 14.

---

## B. Export (api-tests 75)

**6.** Scroll to the bottom of the project dashboard.
→ An **Archive this project** card. It states what the file holds - every network as its full XML
document, the catalogue, the variant lineage, every scenario with its events, and every *completed*
run with its parameters, seed, metric results and time series - and that failed, cancelled and
queued runs are left out. It also states that archiving changes nothing here, and that the restore
lives on the project list.

**7.** Press **Export project archive**.
→ A spinner on the button, then a download of **`Archive Test-archive.zip`**. The filename comes
from the server's `Content-Disposition`, not from the browser's URL guess - so it is the project's
own name, with only the characters a filesystem would object to (`\ / : * ? " < > |`) replaced by
`-`. A project named with an accent survives intact: the header carries both `filename` and the
RFC 5987 `filename*`, and the client reads the latter first.

**8.** Open the zip in any file manager.
→ It contains `bundle.json` and `networks/001-Baseline@v1.xml`. The XML is byte-identical to what
the row's **Actions → Export XML** downloads, so one network can be lifted out and imported on its
own.
`bundle.json` holds the manifest - `exportedAt`, `application`, `engineVersion`, `counts` - the
catalogue, the scenarios with their events, and the runs with their `params`, metric results and
time series.

**9.** Stop the backend, then press **Export project archive** again.
→ A red banner at the top of the dashboard reading *"The SNRM API did not answer (HTTP 500). Is the
backend running?"* - dismissible, and the page is otherwise untouched. Restart the backend before
continuing.

---

## C. Restore (api-tests 76)

**10.** Go to **Projects**. Read the restore card before touching it.
→ Two bullets above the drop target: *it always creates a new project* (never a merge, with the
reason), and *every restored run is marked as imported* (with what that means). Both are said here
rather than in the report, because both are irreversible in the ordinary sense.

**11.** Drop `Archive Test-archive.zip` on the target.
→ The file is listed with its size. Dropping a **second** zip **replaces** it rather than adding to
it - unlike the import wizard, where the five canonical CSV files are one upload. Only one archive
can be restored at a time, so accumulating would leave you guessing which is about to be sent.

**12.** Leave the name field blank and press **Restore into a new project**.
→ A spinner, then the report appears below.

**13.** Read the report.

- A green headline naming the project it created and its id: *"Restored into a new project:
  “Archive Test (restored)” (#N)."* The name is suffixed because you already have `Archive Test` -
  the backend never overwrites and never refuses for a name collision.
- **Findings** - one, `PROJECT_RENAMED`, badged **Note** and carrying the server's own sentence.
  Findings are listed most consequential first; severity is grouped, and within a group the server's
  order survives.
- **What arrived** - a table of Networks, Products, Disruption scenarios, Disruption events,
  Simulation runs, Metric results, Time-series rows, each with what the archive claimed against what
  was restored. Every "Missing" cell reads `-`.
- A note that the restored runs are marked as imported, and that each freezes its network exactly as
  a locally computed run does.
- **Open “Archive Test (restored)”** and **Restore another archive**.

**14.** Without leaving the page, look at the project table above.
→ The restored project is already in it: the list re-reads after a successful restore rather than
splicing a row in locally, because the server may have renamed it and the table must show the name
that was actually assigned.

**15.** Press **Restore another archive**.
→ The report and the chosen file both clear, back to an empty drop target.

**16.** Drop the same zip again, type `Replication run` in the name field, and restore.
→ A third project by that name, and **no** `PROJECT_RENAMED` finding this time - the headline reads
*"It reproduced the archived experiment exactly - no findings."*

---

## D. The round trip (api-tests 75–77 read off the screen)

**17.** Press **Open “Replication run”**.
→ The project dashboard. The network table holds `Baseline v1`, marked **Frozen** - restored runs
are genuine `DONE` runs and they freeze their network exactly as locally computed ones do.
Its version number is the archived one, not renumbered from 1.

**18.** Press **Products**.
→ `Gearbox`, unit value `250`. The catalogue is project-scoped and travels whole, including any
product no network references.

**19.** Press **Disruption scenarios**, then open `Plant outage`.
→ The scenario, with its event on the timeline against the restored `Baseline` - the same target
node, the same window, the same severity and recovery profile. Cross-row references travel by
*name*, never by id, which is what makes them survive a move into a new database.

**20.** Back on the dashboard, press **Compare variants** and select `Baseline v1`.
→ A column with the restored run's metric suite. The column header links **run #N**.

**21.** Click **run #N**.
→ The results dashboard, showing the archived numbers: the performance curve, the metric cards, the
criticality table. They are the values the source run computed, not a re-computation.

**22.** Look at the header line and the banner beneath it.
→ A blue **Restored from an archive** badge beside the status, and an info panel: *"These numbers
were computed elsewhere and restored here"*, naming when it was imported, the run id it held where
it was computed (`sourceRunId`), and the simulation engine that wrote it. This is api-tests request
77 - `importedAt` non-null and `sourceRunId` naming the original - read off the screen.

**23.** Open the restored `Baseline` in the editor.
→ The frozen banner. Press **Unlock…**.
→ The discard confirmation counts the runs about to go, lists them as `… (run #N, DONE, restored)`,
and carries a separate ⚠ line: *one of these is a restored archive result … deleting them destroys
the only copy this application holds - the archive file itself is unaffected, and restoring it again
re-creates them.* **Cancel** - this step is about the wording, not about deleting anything.

---

## E. What a restore refuses (api-tests 78)

**24.** Go to **Projects**. Rename any text file on your machine to `not-an-archive.zip` and drop it
on the restore target, then press **Restore into a new project**.
→ A red panel: *"This file could not be read as a project archive."* followed by the **server's own
sentence, verbatim** - not a generic toast. No project is created and the table above is unchanged.
This is the 422 `ARCHIVE_UNREADABLE` of api-tests 78.

**25.** Dismiss the panel with its ✕, then drop a valid archive.
→ The error clears when a new file is chosen, and so does any report from a previous restore: a
report on screen always describes the file named above it.

**26.** Take a valid archive, open `bundle.json` inside it, change `"formatVersion": 1` to `2`, and
re-zip it. Drop it and restore.
→ Refused, with a sentence naming both version numbers and telling you to open it with a build at or
above the one that wrote it. A reader that cannot understand the layout cannot tell you what it got
wrong either - which is exactly the opposite judgement from the *engine* version, below.

**27.** *(Not provokable from the UI on one installation.)* The **engine-version mismatch** needs an
archive written by a different `SimulationParams.ENGINE_VERSION`. When it fires, the report's
headline turns amber, a red panel appears titled *"These results were computed by a different
simulation engine"* carrying the backend's own wording, and the finding is badged **Read this
first** - it qualifies every number in the restore, which is why it outranks everything else and why
it is a warning rather than a refusal. The shaping of all three is pinned by
`archive-report.spec.ts`; the wording is the backend's.

---

## F. Housekeeping

**28.**

```bash
npm test
```

→ Green, including `archive-report.spec.ts`: the severity of each code, the unknown-code default
(**warning**, never a notice), the stable ordering within a severity, the engine sentence taken
verbatim from the server with a fallback when the finding is absent, the count comparison including
a null manifest and a restored count *above* the claimed one, and the four headline branches.

**29.**

```bash
npm run build
```

→ Compiles clean into `dist/snrm-frontend`.

**30.** Delete `Archive Test (restored)` and `Replication run` from the project list.
→ Both go, runs included. Unlike editing a network, deleting a project is not blocked by completed
runs: the freeze exists so results stay interpretable beside their inputs, and removing both together
leaves nothing to misinterpret.

---

# Selecting several networks (FR-23)

Sections G–L. Runnable on its own - nothing here depends on A–F.

## Setup for this half

**31.** Create a project named `Selection Test` and open it. Press **Products**, add `Gearbox` with
unit value `250`, and come back.

**32.** Create **five** networks from the form above the table: `Alpha`, `Bravo`, `Charlie`, `Delta`,
`Echo`. Leave *Baseline* unticked on all five for now.
→ Five rows, all badged **Editable**, each carrying **Open in editor** as a button and an
**Actions** menu beside it (FR-26 - section O is that menu on its own).

**33.** Open `Delta` in the editor, give it one node with demand (or import a sample over it), and
run a **baseline** simulation from the run panel. Wait for `DONE`. Do the same for `Echo`.
→ Back on the dashboard, `Delta` and `Echo` read **Frozen**, and their row menus have **no Delete
entry at all** - a sentence in its place gives the reason and names FR-20's discard as the
way back. `Alpha`, `Bravo`, `Charlie` stay **Editable**. This mix is what sections J, K and Q are
about - do not skip it.

---

## G. The checkbox column and the select-all

**34.** Look at the table before touching anything.
→ A new leading column with a checkbox in the header and one per row. Above the table: an **Actions**
button, *disabled*, and the sentence *"Tick networks to act on several at once. Every row still
carries its own actions."*

**35.** Confirm the row still carries everything it did, and that this menu is a different question.
→ Every row has **Open in editor** as a button, and an **Actions** menu holding its three exports,
**Duplicate network…** and **Delete…** (FR-26). Open `Alpha`'s row menu and press **Export XML** to
prove it still downloads. The menu *above* the table answers *act on these five*; replacing "delete
this" with "select it, then choose delete" would make the common case the longer one.

**36.** Tick `Alpha`.
→ The row tints. The button above becomes enabled and reads **Actions · 1 selected**. Beside it:
*"1 of 5 selected"* and a **Clear selection** link.

**37.** Tick `Bravo` and `Charlie`.
→ **Actions · 3 selected**, *"3 of 5 selected"*. The header checkbox is now in its **indeterminate**
state - a dash, not a tick.

**38.** Press the header checkbox.
→ All five tick, `Delta` and `Echo` included. **Actions · 5 selected**, and the count line adds
*"2 frozen"*. Frozen networks are selectable on purpose: the menu's other two entries do not care
about the freeze, and hiding them from the select-all would make section K's split invisible.

**39.** Press the header checkbox again.
→ All five clear. **Actions** is disabled again and the header checkbox is unticked, not
indeterminate.

**40. Keyboard only.** Click the page heading, then Tab until focus reaches the header checkbox.
Press **Space**. Tab to `Alpha`'s checkbox, press **Space** to untick it.
→ Both respond. They are real checkboxes with real labels - a screen reader announces the header one
as *"Select all networks in this project"* and each row as *"Select Alpha version 1"*.

---

## H. The actions menu

**41.** With three networks ticked, press **Actions · 3 selected**.
→ A dropdown opens beneath it with three entries: **Delete…**, then a divider, then **Export as a
project…** (FR-24, section M) and **Compare side by side…** (FR-25). Beneath the last one, a
sentence stating its cap: *"Up to 12 networks at once - 12 fills a grid of four columns and three
rows, so the panes are small and the last row scrolls, and a thirteenth would take a fourth row.
Above 6 panes each one opens as a title and a miniature, with its structural metrics collapsed and
one click away."* (The cap was six, then ten; FR-27's collapse is what pays for the larger grid,
and twelve is the count that fills it.) All three are enabled.

**42.** Hover the last two entries.
→ **Export as a project…** - the ordinary project archive narrowed to the selected networks,
restorable as a separate project; it copies and never moves. **Compare side by side…** - the selected
networks side by side, one read-only miniature per pane, sharing a by-name element selection.
`../comparison/MANUAL-TEST.md` walks that view; this section only checks the menu.

**42a.** Tick a **thirteenth** network (duplicate rows with FR-26's *Duplicate network* if the
project has fewer) and reopen the menu.
→ **Compare side by side…** is now *disabled*, and the sentence under it has changed to name the
overage: *"…Untick 1 to open the rest side by side."* **The cap is stated where the action is
offered, before any window exists** (FR-25). Untick back to twelve - twelve is offered now where
seven and eleven were once refused.

**43.** Press **Escape**.
→ The menu closes and focus is back on the **Actions** button - not lost to the page body.

**44. Keyboard only.** With focus on **Actions**, press **↓**.
→ The menu opens with **Delete…** focused. Press **↓** repeatedly: focus walks **Export as a
project…**, then **Compare side by side…**, then wraps back to **Delete…**. It never lands on the cap
sentence - that is a description, not an item. With thirteen ticked, it never lands on the disabled
**Compare side by side…** either, because a menu skips what it cannot offer. **End** and **Home** do
the same. Press **Escape** to close.

**44a.** Open the menu with **↓** again, then press **Tab**.
→ The menu closes and focus lands on the *next control after it* - not back at the top of the page.
(Closing on the keydown would have removed the focused button before the browser moved off it, which
resets focus to `<body>`; the menu closes on focus leaving instead.) **Shift-Tab** back onto
**Actions** and press **Enter**: it opens again.

**45.** Open the menu and click anywhere else on the page - the breadcrumb, say.
→ It closes. Bootstrap's JavaScript is not in this bundle (`angular.json` has `scripts: []`), so this
is Angular's own document listener, the same way `shared/confirm-dialog` is Bootstrap markup without
Bootstrap's modal.

**46.** Open the menu, then press **Clear selection** - reach it with Tab if you like.
→ The menu closes on its own. A menu offering actions over an empty selection has no subject.

---

## I. Reconciliation on reload (the rule FR-23 turns on)

**47.** Tick `Alpha`, `Bravo` and `Charlie`. Press **Refresh** (top right).
→ The count line blinks through *"Loading networks…"* and comes back reading **3 of 5 selected**. A
reload keeps what is still there - it does not clear the selection.

**48.** Now delete one of them *from its own row*: open `Bravo`'s **Actions** menu, press
**Delete…**, type the project name `Selection Test`, confirm.
→ The row goes, and the count drops to **2 of 4 selected**. `Bravo` left the selection with its row;
nothing had to be un-ticked by hand, and the menu can no longer be aimed at it.

**49. The interesting one.** Tick `Alpha` and `Charlie`. Open a **second browser tab** on the same
project, and in that tab delete `Charlie` from its row. Come back to the first tab - do not touch
anything - and press **Refresh**.
→ `Charlie`'s row is gone and the count reads **1 of 4 selected**. This is the rule the view holds
to, tested: *"a network that is no longer there leaves the selection with it, so an action can never
be aimed at a row that has gone."* Without it, the next **Delete** would have issued a request for a
network that no longer exists.

**50.** Navigate to **Projects** and back into `Selection Test`.
→ Nothing is selected. A selection describes one project's list.

---

## J. Deleting a set - all editable

**51.** Re-create `Bravo` and `Charlie` so you have `Alpha`, `Bravo`, `Charlie` editable and `Delta`,
`Echo` frozen. Tick `Alpha`, `Bravo` and `Charlie`.

**52.** **Actions · 3 selected → Delete…**
→ The confirmation. Read it in order, top to bottom:

- Title: **Delete these 3 networks?**
- The sentence: *"Delete these 3 networks and everything in them. This cannot be undone."* - with no
  mention of anything frozen, because nothing selected is.
- The details: what goes with each network (nodes, links, per-product rows; variant records), that
  **products are project-scoped and are not deleted**, and that *"the 3 deletes are issued one
  network at a time and each is reported on its own terms"*.
- **These 3 will be deleted** - `Alpha v1 (#N)`, `Bravo v1 (#N)`, `Charlie v1 (#N)`, each named.
- Then, and only then, the phrase field: *Type the project name `Selection Test` to confirm*.
- The confirm button reads **Delete 3 networks** and is **disabled**.

**53.** Type `selection test` (wrong case).
→ The field goes red and the button stays disabled. The check is exact after trimming; "close enough"
would defeat the delay it exists for.

**54.** Correct it to `Selection Test` and press **Delete 3 networks**.
→ The button spins, a line beneath the lists counts *"Deleting one network at a time - 1 of 3
answered for"*, then 2, then 3. The dialog closes.

**55.** Read the panel that is now above the table.
→ **All 3 networks deleted.** with three green **Deleted** rows naming `Alpha v1 (#N)`,
`Bravo v1 (#N)`, `Charlie v1 (#N)`. The table below holds `Delta` and `Echo` only, and nothing is
selected. Dismiss the panel with its ✕.

---

## K. Deleting a mixed set - the split FR-15 forces (**the step to run first if you run one**)

**56.** Re-create `Alpha` and `Bravo`. Press the header checkbox to select **all four**: `Alpha` and
`Bravo` (editable), `Delta` and `Echo` (frozen).
→ **Actions · 4 selected**, *"4 of 4 selected · 2 frozen"*.

**57.** **Actions · 4 selected → Delete…**
→ The confirmation, and this is the one FR-23 exists for. Before the phrase field there are **two
named groups**:

- **These 2 will be deleted** - `Alpha v1 (#N)`, `Bravo v1 (#N)`.
- On a tinted panel: **These 2 are frozen and will not be deleted** - `Delta v1 (#N)`,
  `Echo v1 (#N)` - followed by *"A completed simulation run holds them, and selecting
  several does not relax that rule. To delete one anyway, open it in the editor and discard its runs
  from the frozen banner's Unlock… (FR-20); it becomes editable, and then deletable, as soon as the
  last run holding it is gone."*

The title reads **Delete 2 of the 4 selected networks?** and the sentence adds *"2 more selected
networks are frozen and will be left exactly as they are."* The confirm button reads **Delete 2
networks**.

**58.** Confirm that the phrase field is *below* both lists and that the button is still disabled.
→ It is. The user confirms a split they have already read; a dialog that accepted four and silently
deleted two would be reporting the shortfall after the irreversible half.

**59.** Type `Selection Test` and press **Delete 2 networks**.
→ Two green **Deleted** rows in the report. `Delta` and `Echo` are **still in the table**, still
**Frozen**, still selected - untouched, exactly as the dialog promised.

**60.** Now select `Delta` and `Echo` alone and press **Actions · 2 selected → Delete…**
→ **No dialog.** An amber banner above the table instead: *"All 2 selected networks are frozen by a
simulation run, so nothing would be deleted (Delta v1 (#N); Echo v1 (#N))… Discard a network's runs
from its editor - the frozen banner's Unlock… (FR-20) - and it becomes deletable again."* There is
nothing to confirm, so nothing is asked; the same judgement the editor makes before a discard it
knows the server would refuse.

**61.** Untick `Echo`.
→ The banner clears on its own: it described one selection, and that selection has changed.

**62.** Follow the remedy: open `Delta` in the editor, press **Unlock…** on the frozen banner, type
`Delta v1`, discard. Back on the dashboard, press **Refresh**.
→ `Delta` reads **Editable**. Select it and **Actions · 1 selected → Delete…** → the dialog now has
one group only, titled **Delete this network?** with **This will be deleted**, and confirms with the
project name as before.

---

## L. A delete that is refused part-way (the per-network outcome)

The point of this section is that a refusal in the middle of a set does **not** abort the rest and is
**not** reported as a failure of the whole.

**63.** Create `Foxtrot`, `Golf` and `Hotel`. Tick all three.

**64.** Open the actions menu but **do not confirm yet**. In a second browser tab, open `Golf` in the
editor and start a simulation run against it. Wait for `DONE` - `Golf` is now frozen, and the first
tab does not know.

**65.** Back in the first tab, press **Delete…**, type `Selection Test`, and confirm.
→ The counter runs 1, 2, 3. The dialog closes. The report reads:

- **2 of 3 networks deleted; 1 was refused and is still here.** - the headline names what did *not*
  go as well as what did.
- Green **Deleted** `Foxtrot v1 (#N)`, red **Refused** `Golf v1 (#N)` carrying the **server's own
  sentence** for the 409 `NETWORK_IMMUTABLE`, and green **Deleted** `Hotel v1 (#N)`.
- Beneath the refusal, its remedy: *"A simulation run froze this network between the list being read
  and the request being sent. Refresh to see its state, and discard its runs from the
  editor (FR-20) if you still want it gone."*
- The panel's border is red, because something was refused.

**66.** Check the table beneath the report.
→ `Golf` is still there. `Foxtrot` and `Hotel` are gone. `Golf` is still ticked - it is still a row,
so it is still a selection. The report and the table agree, and neither claims the set succeeded.

**67.** Press **Refresh**.
→ `Golf` now reads **Frozen**. The report stays on screen; it is an account of what happened, not a
view of the list.

**68.** Stop the backend, tick a network, and delete it through the menu.
→ Every network in the set comes back **Refused** with *"The SNRM API did not answer (HTTP 500). Is
the backend running?"*, the headline reads *"None of the N networks was deleted - the server refused
every one."*, and the table is unchanged. Restart the backend.

---

## M. Exporting a selection as a standalone project - the FR-24 round trip

This is the section to run if you run one. It is the whole feature: **select some of a project's
networks, export, restore, and find a separate project holding exactly those.** Backend
`api-tests.http` requests **98–104** are the same walk through the API alone.

**Set the fixture up first.** In `Selection Test` you need **six** networks, at least one with a
completed run, one forked from another, and a scenario whose event targets a network you will
*leave behind*. From wherever sections G–L left you:

**69.** Make sure the table holds six networks. Re-create any you deleted, and add
`India` and `Juliett` if you are short. Import a real sample over **two** of them (say `Alpha` and
`India`) from `../snrm-backend/samples/four-echelon-playback/`, so they have nodes,
links and demand.
→ Six rows.

**70.** Open `Alpha` in the editor and run a **baseline** simulation. Wait for `DONE`.
→ `Alpha` reads **Frozen** on the dashboard. This is the run that has to travel with it.

**71.** On `Alpha`'s row open the **Actions** menu and press **Duplicate network…** (FR-26). Keep
the prefilled name `Alpha` and confirm - it becomes `Alpha v2`. (`Alpha` is frozen by step 70's run,
and it duplicates anyway; that is section Q's subject.)
→ Seven rows now, and the provenance tree beneath the table draws `Alpha v2` under `Alpha v1`. That
fork note is what step 78 is about.

**72.** Press **Disruption scenarios**, create `Cross-network outage`, and add one event **against
`India`** - any node of it, any window inside the horizon.
→ The event bar appears. `India` is the network you are about to *not* select.

**73.** Back on the dashboard, tick **three** networks: `Alpha v2`, `Bravo` and `Charlie`. Leave
`Alpha v1`, `India` and `Juliett` unticked.
→ **Actions · 3 selected**.

**74.** **Actions · 3 selected → Export as a project…**
→ A confirmation, and read it before pressing anything. It is **not** the delete dialog:

- Title: **Export these 3 networks as a project?** and a sentence saying it downloads them as one
  project archive - the ordinary archive of this project narrowed to what you ticked - and that
  restoring it gives you a separate project holding exactly them.
- Two bullets, and these are the two that must be there. **It copies and never moves.** *The
  selected networks, their runs and their results stay in the project that computed them - nothing
  here is changed or removed by archiving.* **Restoring it always creates a new project.** *Nothing
  existing is read or changed - a merge would have to decide what happens when the archive's
  "Baseline v1" meets one of yours…* Both are the restore card's own sentences, from one definition
  (`archive-rules.ts`); they are not a paraphrase written for this dialog.
- **These 3 networks will travel** - `Alpha v2 (#N)`, `Bravo v1 (#N)`, `Charlie v1 (#N)`, each
  named. A frozen one would be annotated *- frozen, and exported with its runs*.
- On a tinted panel, **What the file carries with them**: each network in full; the **whole product
  catalogue**, including any product none of them uses, with the reason; **every** disruption
  scenario, with the warning that an event aimed at a network you did not tick is restored
  unresolved so that scenario visibly refuses to run; and that fork notes whose parent you did not
  tick are dropped and counted.
- **No phrase field**, and the confirm button (**Export 3 networks**) is enabled immediately. A copy
  is not an irreversible act; the typed discipline of FR-15 is sized to something else.

**75.** Press **Export 3 networks**.
→ A download of **`Selection Test-3-networks-archive.zip`** - note the count in the name, which is
what keeps it apart from the whole-project `Selection Test-archive.zip` in a downloads folder. The
name comes from the server's `Content-Disposition`, through the same service the whole-project card
uses. The dialog closes.

**76. THE COPY ASSERTION - do not skip it.** Look at the table you are standing on.
→ All seven networks are still there, `Alpha v2`, `Bravo` and `Charlie` included, each with the same
badge it had. Open `Alpha v1` in the editor: its run is still in the run panel, still `DONE`, still
freezing the network. FR-24 copies and never moves, and this is that read off the screen.

**77.** Go to **Projects**, drop `Selection Test-3-networks-archive.zip` on the restore card, name it
`Three networks`, and restore.
→ 201, and the report appears.

**78.** Read the report's **Findings**.
→ A **Note** badged `ARCHIVE_IS_SUBSET`, saying the archive held 3 of the project's 7 networks, that
**it is a copy** and the source still holds all of them, that the whole catalogue travelled (so an
unused product is expected rather than a fault), that every scenario travelled - and then the two
counted consequences: that **1 event targeted a network that did not travel** and was restored
dangling, and that **1 configuration-variant fork note was dropped** because the network `Alpha v2`
was forked from was not selected. Beside it, a **Warning** badged `EVENT_TARGET_UNRESOLVED` naming
the scenario. A subset is a notice, not a warning: nothing went wrong, and where a consequence *is*
a warning it gets a finding of its own.

**79.** Read **What arrived**.
→ **Networks 3 of 3**, and every "Missing" cell reads `-`. There is no shortfall: the manifest
counted what the subset holds, so the arithmetic agrees. The finding is the only sign, which is
exactly why it exists.

**80.** Press **Open "Three networks"** and check the four things the round trip is for.

- **Exactly those three networks** - `Alpha v2`, `Bravo`, `Charlie`, and nothing else. `Alpha v1`,
  `India` and `Juliett` are not here.
- **Their runs.** `Alpha v2` carries the runs it had, marked **Restored from an archive** on the
  results dashboard with the source run id and the engine that wrote them.
- **The full catalogue.** Press **Products**: every product of `Selection Test` is here, including
  ones only `India` used. Unused entries are harmless; a dangling reference would not have been.
- **The dangling event.** Press **Disruption scenarios** and open `Cross-network outage`. The
  scenario is here and its event is here, but its target does not resolve - it points at a node of
  `India`, which did not travel. Try to run it: pick any network and submit → **422
  `EVENT_TARGET_UNRESOLVED`**, with the server's own sentence. That refusal is the feature working.
  Repoint the event at a node of a network that *did* travel and it runs.

**80a.** Back on the *original* `Selection Test`, open the scenario builder.
→ `Cross-network outage` is untouched and its event still targets `India` correctly. Nothing about
exporting a subset reached back into the project it copied from.

---

## N. Housekeeping for this half

**81.**

```bash
npm test
```

→ Green, including `network-selection.spec.ts`: reconciliation dropping a vanished network and
returning the *same set instance* when nothing changed, the `editable` split in both directions, the
two-group wording with its counts and singulars, the typed phrase being the project's name unchanged
from the single-network dialog, the "everything is frozen" blocker naming both the networks and
FR-20, the outcome headline for a clean run / a mixed run / a wholly refused run / a run abandoned
part-way, and the `NETWORK_IMMUTABLE` and `NOT_FOUND` remedies with an unknown code adding nothing -
plus, for FR-24, the export confirmation's two rules compared **against the `archive-rules.ts`
constants themselves** so a reword fails the test rather than the reader, its counts and singulars,
all three carry rules, the frozen-network line, and the absence of any typed phrase.
`archive-report.spec.ts` gains `ARCHIVE_IS_SUBSET` ranking as a notice.

**82.**

```bash
npm run build
```

→ Compiles clean into `dist/snrm-frontend`.

**83.** Delete the `Selection Test` and `Three networks` projects from the project list.

---

# A row is one action and a menu, and Duplicate network (FR-26)

Sections O–R. Runnable on its own - nothing here depends on A–N.

## Setup for this half

**84.** Create a project named `Duplicate Test` and open it. Press **Products**, add `Gearbox` with
unit value `250`, and come back.

**85.** Press **Import network (CSV · Excel · XML)** and import a sample from
`../snrm-backend/samples/four-echelon-playback/`, naming it `Baseline`. Back on the
dashboard, create one more empty network called `Sketch`.
→ Two rows, both **Editable**.

---

## O. The row reorganised

**86.** Look at a row before touching anything.
→ **Open in editor** is a *button*, and beside it a single **Actions** menu. The three export
formats and Delete are no longer on the row. This is FR-26's whole reason: the row's controls grew
one at a time until opening the network - the thing a reader does most often - was one button among
five with no visual claim to being first.

**87.** Open `Baseline`'s **Actions** menu.
→ **Rename…** first, then a divider, then a small **Export** heading, **Export XML**,
**Export XLSX**, **Export CSV**, a divider, **Duplicate network…**, a divider, **Delete…**. Every
export entry keeps the tooltip its button had. (Rename is first because it is what a reader reaches
for most after a batch import - sections T–X are that feature.)

**88.** Press **Export XML**.
→ The same download as before, named by the server's `Content-Disposition`. The menu closed and
focus is back on the **Actions** button. While the download is in flight the toggle shows a spinner
and is disabled - the per-row busy state the individual buttons used to show.

**89. Keyboard only.** Tab to a row's **Actions** button and press **↓**.
→ The menu opens with **Rename…** focused. **↓** walks to Export XML, XLSX, CSV, Duplicate, Delete,
then wraps. It never lands on the *Export* heading - that is a label, not an item.
**Home** and **End** jump to the ends. **Escape** closes and puts focus back on the **Actions**
button.

**89a.** Open a row menu with **↓**, then press **Tab**.
→ It closes and focus lands on the *next* control, not at the top of the page. (The menu closes on
focus leaving rather than on the Tab keydown; closing on the keydown would remove the focused button
before the browser moved off it.) **Shift-Tab** back and **Enter** opens it again.

**90. One menu at a time - the FR-26-scale rule.** Open row 1's menu, then click row 2's **Actions**
button directly.
→ Row 1's menu closes and row 2's opens. There is never more than one open: "which menu is open" is
a single value the whole page shares, not a flag per row, so opening one *is* closing the others.
The same holds against the **Actions** menu above the table - open that one, then a row's, and the
first closes.

**91.** Open the menu on the **last** row of a long table (create a few spare networks if you need
one), scrolled so the row is near the bottom of the window.
→ The menu is fully visible - it opens *upward* when there is no room below, and it is never clipped
by the table's own scroll box. Now **scroll the page** with the menu open.
→ It closes. It is pinned to the toggle's position on screen, so a page that moved under it would
leave it pointing at the wrong row.

**92.** Open a menu and click anywhere else - the breadcrumb, say.
→ It closes. Bootstrap's JavaScript is not in this bundle (`angular.json` has `scripts: []`); this
is Angular's own listener, the same one the selection menu has always used.

---

## P. Duplicate is a fork, and the tree is the test of it

**93.** On `Baseline`'s row, **Actions → Duplicate network…**
→ A confirmation. Read it before pressing anything:

- Title **Duplicate this network?** and a sentence saying it forks `Baseline v1 (#N)` into a new
  configuration variant, that it is recorded against this network so it appears in the lineage
  beneath the table under the one it came from - *a duplicate is a variant, not an untracked copy* -
  and that **you stay on this page**.
- Details: everything the copy carries (nodes, links, per-product rows, canvas layout), that it
  inherits the base network's **clock** and why that is not a lever, that a
  **configuration-variant record** is written pointing back at `Baseline v1 (#N)`, and that the copy
  carries **no runs and no results** while nothing about the original changes.
- **Name for the copy**, *prefilled* with `Baseline` - not a placeholder. Under it: keeping it takes
  the next version number, so the copy is `Baseline v2` and sorts directly beside its base; a
  different name starts that name at v1 and sorts elsewhere.
- **What is this variant meant to change?** - empty, optional, the same lever note the editor's fork
  prompt collects.
- **No phrase field.** The confirm button (**Duplicate network**) is enabled immediately: nothing is
  destroyed, and FR-15's typed discipline is sized to something else.

**94.** Type `+20% capacity at PLANT-1` in the lever field, leave the name as it is, and press
**Duplicate network**.
→ The button spins briefly, the dialog closes, and **you are still on the dashboard.** Three things
happened at once and all three are on screen:

- A card above the table: **Duplicated.** - *"`Baseline v2 (#N)` was forked from `Baseline v1 (#M)`.
  It shares its base network's name and took the next version number, so it sits directly beneath it
  in the table. It is in the lineage below as a fork of Baseline v1."*
- A new row, **directly beneath `Baseline v1`**, badged **New copy** - which is how you pick it out
  of a table where two rows now share a name and differ only by a version number.
- **The lineage panel below the table redrew itself, with no Refresh**: `Baseline v2` is indented
  under `Baseline v1`, and the note `+20% capacity at PLANT-1` sits beside it.

**95. The provenance assertion - this is the step to run if you run one.** Scroll to *Where these
configurations came from*.
→ `Baseline v2` hangs under `Baseline v1` with *Forked from Baseline v1* in words beneath it, and
`Baseline v1` reads *1 variant forked from it*. `Sketch` is a root, because it was created rather
than forked. **A copy that were not a fork would have appeared here as a second root with no parent
anyone could name** - that is what FR-26 is buying, and this panel is where you see it.

**96.** Read the lever note on that row.
→ `+20% capacity at PLANT-1`, beside `Baseline v2` in the lineage - the same annotation the editor's
fork prompt records, written by the same field into the same `lever_changes_json`. Once both
configurations have completed runs, the **Compare variants** matrix prints it under `Baseline v2`'s
column, which is what turns "this one recovers faster" into a statement about a lever.

**97.** Duplicate `Baseline v1` again, this time **changing** the name to `Buffered`.
→ A new row `Buffered v1`, sorted under `B` by name rather than beside `Baseline`, and the outcome
card says so: *"You named it differently, so it starts its own version series… the lineage below
still shows it under the network it came from."* Check the lineage: `Buffered v1` is still indented
under `Baseline v1`. The name is a name; the fork is the fork.

**98.** Duplicate `Baseline v1` a third time with the lever field **left empty**.
→ It works, and in the lineage that row reads *"No lever note was recorded at this fork, so this
variant is unattributed in the comparison view."* An empty note records *nothing*, which is shown
differently from an annotation that says nothing.

**99.** Press **Refresh**.
→ The table and the lineage are unchanged - everything you just saw came from the server, not from a
local splice that a reload would correct. The **New copy** badge and the *Duplicated.* card survive a
refresh and clear when you dismiss the card or leave the project.

---

## Q. A frozen network duplicates like any other

**100.** Open `Baseline v1` in the editor and run a **baseline** simulation. Wait for `DONE`. Back
on the dashboard, press **Refresh**.
→ `Baseline v1` reads **Frozen**.

**101.** Open its **Actions** menu.
→ **Rename…**, **Export XML / XLSX / CSV** and **Duplicate network…** are all there, all enabled.
**Delete… is absent** - and in its place a sentence: *"No delete. A simulation run has frozen this network.
Discard its runs from the editor's frozen banner - Unlock… (FR-20) - and the delete comes
back. Everything above still works: exporting and duplicating read the network rather than changing
it."* The reason is **shown**, not hidden in a tooltip on a greyed-out control.

**102.** Press **Duplicate network…**
→ The dialog opens, ungated, and carries one extra detail line: *"This network is frozen by a
simulation run, and it duplicates like any other. The freeze is about **edits**, and
reading a configuration to copy it is not one - forking a frozen network is the remedy the freeze
itself advertises…"* Confirm it.
→ A new variant, editable, with no runs. **This is the case FR-26 exists for**: a researcher forks a
frozen network precisely because it has results worth building on, and gating it would have closed
the exit the freeze itself advertises.

**103.** Check the row badges.
→ The copy is **Editable**; `Baseline v1` is still **Frozen** and still has its run. Open it in the
editor to confirm the run is still in the run panel. Duplicating copies and changes nothing.

---

## R. Delete, unchanged by having moved

**104.** On an editable row with no runs (`Sketch`), **Actions → Delete…**
→ The same confirmation as before the move: it names the network with its version and id, lists what
goes with it - *including* that a fork note recorded against a network that is *staying* goes if the
network it was forked from is going - says products are project-scoped and are not deleted, and then
asks for the **owning project's name** typed exactly. The button stays disabled until it matches.

**105.** Type `duplicate test` (wrong case).
→ Red, and the button stays disabled. Exact after trimming, as it always was.

**106.** Correct it and delete.
→ The row goes. If it had children in the lineage, they surface as roots on the next sweep.

**107.** Compare the wording with the set delete: tick two editable networks and use the **Actions**
menu *above* the table.
→ The first three detail lines are word for word the row dialog's, at a plural count. They come from
one function (`network-selection.deletionDetails`), which is what step 108's spec pins.

---

## S. Housekeeping for this half

**108.**

```bash
npm test
```

→ Green, including the four specs this feature touches:

- `core/fork-request.spec.ts` - an empty name and a name equal to the base network's resolving to
  the *same* omitted `name`, which is the editor's placeholder and this dialog's prefilled value
  meeting; trimming; case sensitivity; a blank note recording nothing rather than an empty
  annotation; and `cloneBody` omitting rather than nulling.
- `features/projects/network-duplicate.spec.ts` - the dialog naming its base through
  `describeNetwork`, claiming *variant, not untracked copy*, promising no navigation, stating the
  variant record and the inherited clock, naming the version number the prefilled name will take,
  the frozen-only extra line, the absence of any typed phrase, and both branches of the outcome
  sentence.
- `features/projects/network-actions-menu/action-menu-registry.spec.ts` - one open menu at a time
  with nothing called on the one being replaced, outside click versus inside click, Escape restoring
  focus, scroll and resize closing quietly, and nothing firing once nothing is open.
- `features/projects/network-selection.spec.ts` - its FR-23 assertions unchanged, plus
  `deletionDetails` agreeing with its count and being **what the set confirmation lists**, asserted
  against the function rather than against copied strings.

**109.**

```bash
npm run build
```

→ Compiles clean into `dist/snrm-frontend`.

**110.** Delete the `Duplicate Test` project from the project list.

---

# Renaming a network from the table (FR-29)

Sections T–X. Runnable on its own - nothing here depends on A–S.

**What this feature is about.** A name assigned at creation, or taken from a file name by a batch
import (FR-28), is a label rather than a decision - `Baseline_v3_FINAL.xlsx` becomes a row in a
table that will outlive the file. Two things make it worth a script of its own. **A frozen network
renames** (the freeze covers what a result was computed *from*, and a name is not among them),
which is the *common* case after a batch import rather than the exception.
And **`PUT /networks/{id}` replaces the name and the baseline flag together**, so a rename that sent
the name alone would silently un-baseline the project - section V is that assertion and is the one
to run if you run one.

## Setup for this half

**111.** Create a project named `Rename Test` and open it. Press **Products**, add `Gearbox` with
unit value `250`, and come back.

**112.** Press **Import network (CSV · Excel · XML)** and import a sample from
`../snrm-backend/samples/four-echelon-playback/`. **Name it `Baseline_v3_FINAL`** -
the name a batch import would have given it, and the reason FR-29 exists. Back on the dashboard,
create two more empty networks: `Sketch` and `Zulu`.
→ Three rows, all **Editable**, sorted `Baseline_v3_FINAL`, `Sketch`, `Zulu`.

**113.** Tick the **Baseline** box on… nothing yet - instead, use the create form to add a fourth
network named `Reference` with **Baseline ticked**.
→ Four rows; `Reference` carries a blue **Baseline** badge. This is the flag section V is about.

---

## T. The entry, and what the dialog says

**114.** Open `Baseline_v3_FINAL`'s **Actions** menu.
→ **Rename…** is the **first** entry, above the *Export* heading and its divider. It is
what a reader reaches for most after an import, which is the argument FR-29 makes for itself.

**115.** Press **Rename…** and read the dialog before typing anything.

- Title **Rename this network?** and a sentence naming `Baseline_v3_FINAL v1 (#N)`, saying a name
  taken from a file is a label rather than a decision, and that **you stay on this page; the row
  updates in place**.
- Details, and the first one is the point of the whole feature: *"The baseline flag is sent
  unchanged with the name - the two are replaced together by one request. This network is not the
  project's baseline, and renaming it will not make it one, or disturb whichever network is."*
- Then: nothing else moves (same nodes, links, per-product rows, clock, canvas layout, runs and
  results); **the version number stays v1**, because a rename replaces the name alone where creating
  and duplicating take the next number; and the name **does not have to be unique here** - a name
  plus a version number is what has to be unique and the server decides, refusing the rename and
  changing nothing if that pair is taken.
- **New name**, prefilled with `Baseline_v3_FINAL`, and under it a hint naming the current name, the
  160-character limit and the fact that variants of one name sort together. The hint stays on screen
  in every state - the dialog *opens* blocked, so a hint that gave way to the blocker would only
  ever show the limit to somebody who had already gone past it.
- **No phrase field.** Nothing is destroyed and nothing structural changes, so FR-15's typed
  discipline would be friction; a rename is undone by renaming it back.

**116.** Look at the confirm button before touching the field.
→ **Rename network** is *disabled*, and beneath the hint, in italics rather than red:
*"This is the name it already has. Type a different one to rename it, or cancel - nothing is sent
either way."* The reason a control is unavailable is on screen, which is the rule the frozen row's
missing Delete already follows.

**117.** Check that the dialog is still leavable in that state.
→ **Cancel**, the ✕ and a click on the surround all work. (Blocking the confirm is not the same
signal as *a request is in flight* - that one disables leaving too, and this dialog *opens* blocked.)

---

## U. Validation, and the one refusal the server owns

**118.** Reopen the rename dialog on `Baseline_v3_FINAL` and clear the field entirely.
→ The field turns red and the note becomes an error: *"A network needs a name…"*. The button stays
disabled. Type three spaces - same result: it is blank once trimmed, which is what `@NotBlank` on
the server means.

**119.** Paste 161 characters into the field.
→ Red, and the message counts: *"That is 161 characters. A network name is at most 160… shorten it
by 1."* Delete one character → the error clears and the button enables. The limit is the server's
own `@Size(max = 160)`, restated here so it is not discovered from a 400 after pressing a button.

**120.** Type `  Dual sourcing, EU  ` with spaces at both ends and confirm.
→ It renames to `Dual sourcing, EU`, trimmed. (The name is trimmed *before* it is sent, so the
string this screen checked is the string the server checked - a 160-character name followed by a
space would otherwise pass here and be refused there.)

**121. The duplicate name.** Rename `Sketch` to `Zulu` - a name another network already has at
version 1.
→ **Refused, and the dialog stays open with `Zulu` still in the field.** Inside it, a red panel: the
server's own sentence (*"The request conflicts with data that already exists, or violates a schema
constraint."*), and beneath it the remedy, which is the useful half: *"A network in this project
already has that name at this version number, and the pair has to be unique. A rename does not take
the next version number - creating and duplicating do - so either choose a name that is
free at this version, or use Duplicate network to make a copy under the existing name, which does
renumber. Nothing was changed."*

**Note what was *not* done here: the duplicate name was sent.** Nothing checks a name against the
project client-side (the rule the import wizard already follows) - uniqueness is
`uq_network (project_id, name, version)`'s to enforce, and a check here would be a second
implementation of it, free to disagree.

**122.** Correct the field to `Zulu variant` and press **Rename network**.
→ It renames. The refusal panel is gone. Check the table: `Zulu` is still there, untouched.

**123.** Open a rename dialog again on any row.
→ It opens clean - no leftover refusal from step 121.

---

## V. THE TRAP - a frozen network, and a baseline flag that must not move

**This is the section to run if you run one.**

**124.** Open `Reference` - the network carrying the **Baseline** badge - in the editor and run a
**baseline** simulation from the run panel. Wait for `DONE`. Back on the dashboard, press
**Refresh**.
→ `Reference` now carries **both** badges: blue **Baseline** and grey **Frozen**. This is the row
FR-29 was written for - a completed run against it, the project's baseline flag on it, and a name
somebody wants to change.

**125.** Open its **Actions** menu.
→ **Rename…** is there, enabled, exactly as it is on every other row. **Delete… is absent** with its
FR-15 sentence. The freeze took the delete and did not take the rename, and no gate, tooltip or
warning suggests otherwise.

**126.** Press **Rename…** and read the dialog on this row.
→ Two things, and both matter:

- The baseline detail has switched branch: *"This network is the project's baseline, and it stays
  the baseline. The name and the baseline flag are replaced together by one request, so the flag is
  sent exactly as it stands here - renaming cannot move it, and the comparison view keeps the column
  it measures the others against."*
- One extra line at the end: *"A simulation run has frozen this network, and it renames like any
  other. The freeze covers what a result was computed *from* - nodes, links, per-product
  rows, the time base - and a name is none of those… There is nothing to fork and nothing to discard
  first, and the run against this network is untouched."*

**Nothing in this dialog calls it an edit, offers to fork, or offers to discard the runs.** There is
no fork prompt and no *Unlock…* branch, because neither applies to a label.

**127.** Rename it to `EU reference case` and confirm.
→ The button spins briefly, the dialog closes, **and you are still on the dashboard.**

**128. The assertion.** Look at the row.
→ It is named `EU reference case`, it **still carries the blue Baseline badge**, and it is **still
Frozen**. The card above the table says so in words: *"…is now EU reference case v1 (#N). The table
sorts by name, so its row has moved… **It is still the project's baseline - the flag travelled with
the name, which is the whole reason the two are sent together.** It is still frozen by its runs, and
they are untouched: a name is not structure."*

**129. Confirm it from a second direction - the flag really did not move.** Look at the create form
above the table.
→ Its **Baseline** checkbox is still disabled and reads *(already set)*, which is driven by *some*
network in the project holding the flag. Now press **Compare variants**.
→ The comparison view still has its baseline column. Had the rename sent the name alone, the flag
would have been cleared by this request and neither of these would still be true - with nothing on
screen having mentioned it.

**130. And the run is untouched.** Open `EU reference case` in the editor.
→ The frozen banner is there, and the run panel still lists the `DONE` run - same id, same
timestamp, same results. Open its report: the numbers are the ones it computed. Renaming a network
does not disturb a result computed against it, because the name was never an input to one.

**131.** Press **Refresh** on the dashboard.
→ Everything above survives it: the name, the Baseline badge, the Frozen badge. It came from the
server, not from a local patch.

---

## W. The table and the panels below it keep up

**132.** Rename `Zulu` to `Alpha network`.
→ Three things at once, no reload:

- The **row moves** - the table sorts by name, so it jumps from the bottom to the top. This is why
  the outcome card exists and why the row carries a **Renamed** badge: a reader who pressed a button
  on the last row and found the last row unchanged would be looking at a defect.
- The outcome card says where it went: *"The table sorts by name, so its row has moved to where the
  new name falls…"*
- The lineage panel below the table, if this project has forks, redraws with the new name. (If it
  has none, make one first: **Actions → Duplicate network…** on any row, then rename the *base* and
  watch the tree follow.)

**133. The selection does not drop** (FR-23). Tick three networks, note the count line, then rename
one of them through its row menu.
→ The count is unchanged - *"3 of N selected"* - and the renamed row is **still ticked**, now
reading its new name. A rename changes a row, not which rows exist, so nothing is reconciled away.
Open **Actions · 3 selected → Delete…** and confirm the renamed network is listed under *These 3
will be deleted* **by its new name**. Cancel.

**134.** Dismiss the *Renamed.* card with its ✕, then navigate to **Projects** and back.
→ The card and the badge are gone. Both describe one project's last action.

---

## X. Housekeeping for this half

**135.**

```bash
npm test
```

→ Green, including `features/projects/network-rename.spec.ts`:

- **`renameRequest`** - the flag sent as `true` from a baseline row and `false` from an ordinary
  one, always present as a property (an omitted key and an explicit `false` are the same to `===`
  and opposite on the wire), taken from the network and never from the typed text, and the name
  trimmed so the string validated here is the string the server validates.
- **`renameBlocker`** - empty and whitespace-only refused, exactly 160 accepted and 161 refused,
  length measured after trimming, the over-limit count named, the unchanged name blocked *without*
  being an error, a case-only change treated as a real rename, and **a duplicate name never
  blocked**.
- **`renameConfirm`** - the baseline sentence in both branches, structure/runs/results untouched,
  the version number named as staying, uniqueness left to the server, the frozen-only extra line,
  the absence of any typed phrase, and that **no wording calls a rename an edit in either branch**.
- **`renameOutcome`** - both labels through `describeNetwork`, the row having moved, the flag
  confirmed as surviving, the version read from the *answer* rather than the request, and a frozen
  network reported as still frozen with its runs untouched.
- **`renameRefusalNote`** - `CONSTRAINT_VIOLATION` explained as the (name, version) pair being taken
  and pointing at Duplicate network, `DUPLICATE_NAME` answered identically, `NETWORK_IMMUTABLE` read
  as an out-of-date backend rather than as a mistake here, `NOT_FOUND` answered with the refresh,
  and an unknown code adding nothing.

**136.**

```bash
npm run build
```

→ Compiles clean into `dist/snrm-frontend`.

**137.** Delete the `Rename Test` project from the project list.
