-- ============================================================================
-- V5__event_region_target.sql — give a REGION-scoped disruption event somewhere
-- to name its region.
--
-- THE OPEN POINT THIS CLOSES
--
-- A REGION target resolves to node sets via the region tag on nodes, but the
-- ER model types the target as a single
-- `bigint target_id`, and node.region is a VARCHAR(60) string. V2 followed the
-- ER model as written and left target_id NULL for REGION rows, which made the
-- constant unusable: an event could say it struck a region but not which one.
-- Both V2's comment on disruption_event and the Javadoc on
-- DisruptionTargetType.REGION recorded it as an amendment to make rather than a
-- decision to take in a migration. This is that amendment.
--
-- `target_region VARCHAR(60)` matches node.region exactly — same length, same
-- collation — because the resolution is literally
--
--     SELECT * FROM node
--     WHERE network_id = ? AND region = <event.target_region>
--
-- and a mismatch in either would make that comparison lie about which nodes are
-- hit. GET /networks/{id}/region-nodes runs precisely that query, so the
-- preview the scenario builder shows is the resolution itself and not a second
-- implementation of it.
--
-- A region lookup table was the alternative and is rejected for Phase 1: a
-- region is a free-text tag a researcher types on a node or imports from a
-- spreadsheet column, not an entity with attributes of its own. A
-- table would add a join, a second place a region can be created, and a
-- referential-integrity question — what becomes of the region row when its last
-- node is retagged — with nothing gained while the tag carries no data.
--
-- WHY THE TARGET IS STILL NOT A FOREIGN KEY
--
-- Unchanged from V2 and unchangeable: target_id points at node.id or link.id
-- depending on target_type, and no foreign key can express a reference whose
-- table is chosen by another column. target_region cannot have one either,
-- since there is no region table to point at. Both are checked in the service
-- layer against the network the event is authored against
-- (DisruptionScenarioService), which is also the only place that knows which
-- network that is — scenarios are project-scoped so one can be replayed against
-- every variant.
--
-- EXISTING ROWS
--
-- ck_event_target has required an id for NODE and LINK since V2, so no existing
-- row can be a REGION event carrying a target; and nothing has ever written a
-- REGION row, because the scenario endpoints arrive with this release
-- and an import carries no scenario. Step 2 below is therefore a
-- no-op in practice. If a hand-written REGION row does exist it fails there with
-- "Check constraint 'ck_event_target' is violated", and the repair is to give it
-- a region or delete it — this migration deliberately does not guess one.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. The column.
--
-- Placed after target_id so the two halves of the polymorphic reference read
-- together in a DESCRIBE, and nullable because exactly one of them is set on
-- any given row.
-- ---------------------------------------------------------------------------
ALTER TABLE disruption_event
  ADD COLUMN target_region VARCHAR(60) NULL AFTER target_id;


-- ---------------------------------------------------------------------------
-- 2. Exactly one half of the reference, matched to the target type.
--
-- V2's ck_event_target only said "NODE and LINK need an id". It is tightened in
-- both directions now that the other half exists:
--
--   NODE, LINK  -> target_id set,     target_region null
--   REGION      -> target_region set, target_id null
--
-- Forbidding the unused half is not tidiness. A NODE event carrying a stale
-- target_region would be a row whose two answers to "what does this hit"
-- disagree, and the loser would be whichever column the reader happened to
-- consult — the timeline groups its rows by one and the engine will resolve the
-- other. The service rejects the combination with
-- EVENT_TARGET_INVALID before it reaches here; this is what makes the invariant
-- true of the table rather than of the code path that fills it.
-- ---------------------------------------------------------------------------
ALTER TABLE disruption_event DROP CHECK ck_event_target;

ALTER TABLE disruption_event
  ADD CONSTRAINT ck_event_target CHECK (
    (target_type IN ('NODE','LINK') AND target_id IS NOT NULL AND target_region IS NULL)
    OR
    (target_type = 'REGION' AND target_region IS NOT NULL AND target_id IS NULL));


-- ---------------------------------------------------------------------------
-- 3. The query path.
--
-- ix_event_target (target_type, target_id) from V2 answers "which events target
-- this node?" — the dependency list the editor lists before a delete.
-- It cannot answer the same question for a region, because target_id is NULL on
-- every row that has one, so region-scoped events would cost a full scan. That
-- list matters most for regions: retagging or deleting the last node of a region
-- silently empties every REGION event naming it, which is exactly what the
-- confirmation exists to surface.
--
-- One column, not (target_type, target_region): a non-null target_region already
-- implies target_type = 'REGION' under ck_event_target above, so leading with
-- the type would only add a byte to every entry.
-- ---------------------------------------------------------------------------
CREATE INDEX ix_event_region ON disruption_event (target_region);
