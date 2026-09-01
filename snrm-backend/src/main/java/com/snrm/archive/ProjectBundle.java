package com.snrm.archive;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.snrm.common.TimeUnit;
import com.snrm.metrics.MetricScope;
import com.snrm.network.VariantOrigin;
import com.snrm.scenario.DisruptionTargetType;
import com.snrm.scenario.RecoveryProfileType;

import java.util.List;

/**
 * {@code bundle.json} — everything in an archived experiment except the networks' own structure,
 * which travels beside it as the XML documents.
 *
 * <p>Three conventions hold throughout, and each exists because the bundle outlives the database it
 * came from.
 *
 * <p><strong>No database id is ever a reference.</strong> Ids appear only as {@code source*} fields,
 * which are provenance for a human reader and are never resolved on the way back in. Everything that
 * has to be re-joined — an event's target, a metric's scope, a variant's base, a run's network and
 * scenario — travels as a name or as an {@link ElementRef}.
 *
 * <p><strong>Every timestamp is an ISO-8601 string, not a Jackson-mapped {@code Instant}.</strong>
 * The application's date handling is configurable ({@code spring.jackson.*} in
 * {@code application.properties}), and an archive whose meaning depends on the settings of the
 * instance that wrote it is not an archive. {@code Instant.toString()} in, {@code Instant.parse} out.
 *
 * <p><strong>JSON columns travel as JSON, not as escaped text.</strong> {@code params_json} and
 * {@code lever_changes_json} are {@link JsonNode}, so the parameter set of a run is readable in the
 * bundle rather than being a quoted blob. They are re-serialised on import; MySQL normalises a
 * {@code JSON} column's key order on write regardless, so byte-identity was never available and
 * legibility is worth more.
 *
 * @param formatVersion {@link ArchiveSchema#FORMAT_VERSION} — checked before anything else is read
 * @param manifest      what wrote this, when, and under which engine
 * @param project       the archived project's own record
 * @param products      the project's whole product catalogue, including any product no
 *                      network references — a network's XML carries only the products it uses
 * @param networks      one entry per network, each naming the zip entry holding its XML
 * @param variants      configuration-variant lineage, which the XML cannot express
 * @param scenarios     disruption scenarios with their events
 * @param runs          completed runs with their parameter sets, metric results and time series
 * @param networkMetrics topological metric results, which belong to a network rather than to a run
 */
public record ProjectBundle(
        int formatVersion,
        Manifest manifest,
        ProjectRecord project,
        List<ProductRecord> products,
        List<NetworkRecord> networks,
        List<VariantRecord> variants,
        List<ScenarioRecord> scenarios,
        List<RunRecord> runs,
        List<MetricRecord> networkMetrics) {

    /**
     * Provenance of the archive itself.
     *
     * @param exportedAt      when it was written, ISO-8601
     * @param application     the tool that wrote it, so a bundle found on its own identifies itself
     * @param engineVersion   {@link com.snrm.simulation.SimulationParams#ENGINE_VERSION} of the
     *                        exporting instance. The one field the import actively checks — see
     *                        {@code package-info} on why a mismatch warns rather than refuses. Each
     *                        run additionally keeps its own inside {@link RunRecord#params()}, which
     *                        is the authoritative answer for that run; this is the instance's
     *                        version at the moment of export, and the two differ whenever an engine
     *                        was upgraded after a run was computed
     * @param sourceProjectId the project's id where it was exported from — provenance only
     * @param counts          a one-line census, so a reader can see what the bundle claims to hold
     *                        without parsing the rest of it
     * @param selection       present only on a <em>subset</em> archive (FR-24), and
     *                        <strong>null on a whole-project one</strong> — which, under
     *                        {@code spring.jackson.default-property-inclusion=non_null}, means the
     *                        member is not written at all and a whole-project {@code bundle.json} is
     *                        byte-identical to what this tool wrote before subsets existed
     */
    public record Manifest(
            String exportedAt,
            String application,
            String engineVersion,
            Long sourceProjectId,
            Counts counts,
            Selection selection) {
    }

    /**
     * What a subset archive left behind, and what that cost (FR-24).
     *
     * <p>Written only when {@code GET /projects/{id}/archive} was given {@code ?networkIds=}. Its
     * whole job is to stop a narrowed archive from being silently narrower than the caller thinks:
     * the restore turns it into one {@code ARCHIVE_IS_SUBSET} finding, so a researcher opening the
     * restored project reads what did not travel instead of noticing it later.
     *
     * <p>The three counts correspond one-to-one with the three carry rules. The catalogue
     * needs none — it travels whole, so nothing about it can be missing.
     *
     * @param selectedNetworks     how many networks travelled
     * @param projectNetworks      how many the source project held. The pair is the honest statement
     *                             that this is a <em>copy of part of</em> something, and the source
     *                             still holds all of it
     * @param networkKeys          which ones, by {@link ArchiveSchema#networkKey}
     * @param droppedVariantEdges  fork notes dropped because the network they were forked
     *                             <em>from</em> was not selected — a lineage row with no parent to
     *                             hang from. Counted here rather than passed over, because the
     *                             restore's own skip is silent by construction
     * @param excludedEventTargets disruption events whose target sits in a network that did not
     *                             travel. They are archived as unresolved and restore through the
     *                             negative-{@code target_id} convention, so the scenario refuses to
     *                             run rather than running with a disruption missing. Deliberately
     *                             <em>not</em> counting targets that were already dangling before
     *                             the selection: those are the source project's condition, not the
     *                             selection's doing, and each already produces its own finding
     */
    public record Selection(
            int selectedNetworks,
            int projectNetworks,
            List<String> networkKeys,
            int droppedVariantEdges,
            int excludedEventTargets) {
    }

    /** What the bundle holds, for the manifest and for the import report. */
    public record Counts(int networks, int products, int scenarios, int events, int runs,
            int metricResults, int timeseriesRows) {
    }

    /**
     * @param name      the project's name; the import takes it unless the caller renames, and
     *                  suffixes it if the owner already has one by that name ({@code uq_project})
     * @param sourceId  provenance only
     */
    public record ProjectRecord(String name, Long sourceId) {
    }

    /** A product in the project catalogue ({@code PRODUCT}). */
    public record ProductRecord(String name, double unitValue) {
    }

    /**
     * A network's identity and the entry holding its structure.
     *
     * <p>Nothing structural is repeated here — that is all in the XML, which the importer validates
     * exactly as it would a hand-uploaded file. These are the three facts the interchange document
     * has no room for, because it describes one network rather than a network's place in a
     * project.
     *
     * @param key      {@link ArchiveSchema#networkKey}, the bundle-internal identity
     * @param name     the network's name, as {@code uq_network} keys on
     * @param version  its variant generation within the name. Restored exactly; the
     *                 importer would otherwise renumber from 1 upward, which would break every
     *                 {@link VariantRecord} and every citation of "Baseline v3"
     * @param baseline whether it is the project's baseline, which the comparison view
     *                 measures against
     * @param xmlEntry the zip entry holding the document
     * @param sourceId provenance only
     */
    public record NetworkRecord(
            String key,
            String name,
            int version,
            boolean baseline,
            String xmlEntry,
            Long sourceId) {
    }

    /**
     * Provenance of a derived network ({@code CONFIGURATION_VARIANT}).
     *
     * @param networkKey     the network this variant materialises as
     * @param baseNetworkKey the network it was forked from
     * @param generatedBy    {@code MANUAL} for an editor fork, {@code SEARCH} for a Phase 2 candidate
     * @param leverChanges   the structured diff from the base, or null
     */
    public record VariantRecord(
            String networkKey,
            String baseNetworkKey,
            VariantOrigin generatedBy,
            JsonNode leverChanges) {
    }

    /** A disruption scenario and its events. */
    public record ScenarioRecord(
            String name,
            int numReplications,
            Long seed,
            Long sourceId,
            List<EventRecord> events) {
    }

    /**
     * One disruption event ({@code DISRUPTION_EVENT}).
     *
     * <p>Timing is carried as the value and unit the researcher stated, not as a period count, for
     * the reason {@code DisruptionEvent} states: a scenario is replayed against variants that need
     * not share a period length, so "period 4" would mean a different moment in each. The derived
     * second-count is not archived — {@code DurationAmount.refresh} recomputes it on write, and
     * storing it would let the bundle carry an arithmetic disagreement.
     *
     * @param target the element struck, by name (see {@link ElementRef})
     */
    public record EventRecord(
            DisruptionTargetType targetType,
            ElementRef target,
            double startOffsetValue,
            TimeUnit startOffsetUnit,
            double durationValue,
            TimeUnit durationUnit,
            double severity,
            RecoveryProfileType recoveryProfile,
            double probability,
            Long sourceId) {
    }

    /**
     * A reference to a node or a link, by the names that survive an import.
     *
     * <p>{@code uq_node (network_id, name)} makes a node's name unique within its network and the XML
     * document already identifies a link by its two endpoint names, so this is not a new naming
     * scheme — it is the one the interchange format uses, applied to the two places that still held
     * raw ids: an event's target and a metric result's scope.
     *
     * <p>{@link #unresolvedSourceId()} is the honest answer for a reference that was already dangling
     * when the archive was written. {@code disruption_event.target_id} carries no foreign key by
     * design — it points at a node or a link and no constraint can express that — so a node deleted
     * before its scenario leaves an event pointing at nothing. Rather than drop the event, the
     * archive records the number it held and says so on both sides; a restored network that silently
     * shrugged off a disruption it never received is the one failure a resilience study cannot
     * afford.
     *
     * @param networkKey        the network the element belongs to
     * @param nodeName          for a node reference
     * @param linkSource        for a link reference, its source node's name
     * @param linkTarget        for a link reference, its target node's name
     * @param region            for a {@code REGION} event, the tag itself — already a name, so it
     *                          needs no resolution at all
     * @param unresolvedSourceId the raw id, when it resolved to nothing at export time
     */
    public record ElementRef(
            String networkKey,
            String nodeName,
            String linkSource,
            String linkTarget,
            String region,
            Long unresolvedSourceId) {

        public static ElementRef node(String networkKey, String nodeName) {
            return new ElementRef(networkKey, nodeName, null, null, null, null);
        }

        public static ElementRef link(String networkKey, String source, String target) {
            return new ElementRef(networkKey, null, source, target, null, null);
        }

        public static ElementRef region(String region) {
            return new ElementRef(null, null, null, null, region, null);
        }

        /** A reference that pointed at nothing when the archive was written. */
        public static ElementRef unresolved(Long sourceId) {
            return new ElementRef(null, null, null, null, null, sourceId);
        }

        /**
         * Derived, and deliberately not a field of the document.
         *
         * <p>{@code @JsonIgnore} is load-bearing rather than tidy. Jackson introspects a record's
         * ordinary methods as well as its components, and {@code isUnresolved()} matches the
         * {@code isXxx()} getter pattern — so without this it was written into {@code bundle.json}
         * as a seventh member, {@code "unresolved"}, that the canonical constructor has no parameter
         * for. With {@code spring.jackson.deserialization.fail-on-unknown-properties=true} (set
         * deliberately in {@code application.properties}), reading such a bundle back threw
         * {@code UnrecognizedPropertyException} and every archive this tool wrote was refused by it
         * with {@code ARCHIVE_UNREADABLE} — the exact failure {@link ArchiveSchema} says an archive
         * format must never have.
         *
         * <p>The annotation fixes both halves at once. New bundles do not carry the member; and
         * because {@code @JsonIgnore} registers the property as <em>known and ignorable</em> rather
         * than merely absent, a bundle written by the build that had the bug still reads — which
         * matters, because those archives exist and are the only copy of what they hold.
         *
         * <p>Not a format-version bump for the same reason: the change is compatible in both
         * directions, and bumping would refuse precisely the files this repairs.
         */
        @JsonIgnore
        public boolean isUnresolved() {
            return unresolvedSourceId != null;
        }

        /** How the reference reads in a report: {@code Baseline@v1 / Plant A → Depot B}. */
        public String describe() {
            if (isUnresolved()) {
                return "id " + unresolvedSourceId + " (already unresolved when archived)";
            }
            if (region != null) {
                return "region '" + region + "'";
            }
            String element = nodeName != null ? nodeName : linkSource + " → " + linkTarget;
            return networkKey + " / " + element;
        }
    }

    /**
     * One completed run with everything needed to read its results.
     *
     * @param sourceId     the id it held where it was computed. Written to
     *                     {@code simulation_run.source_run_id}, never used as this row's id — see
     *                     {@code package-info} decision 2
     * @param networkKey   the network it evaluated
     * @param scenarioName the scenario it applied
     * @param params       {@code params_json} as JSON, carrying the seed and the engine version that
     *                     produced these numbers. Copied across unaltered: it is the replay
     *                     instruction, and the archive's claim is that it reproduces what
     *                     the source recorded
     * @param metrics      the run's metric suite, with confidence intervals
     * @param timeseries   the per-period curves, both disrupted and baseline
     */
    public record RunRecord(
            Long sourceId,
            String networkKey,
            String scenarioName,
            String startedAt,
            String finishedAt,
            JsonNode params,
            List<MetricRecord> metrics,
            List<TimeseriesRecord> timeseries) {
    }

    /**
     * One metric value ({@code METRIC_RESULT}).
     *
     * @param network     the network it belongs to. Redundant inside a {@link RunRecord}, which
     *                    already names one, and load-bearing in {@link ProjectBundle#networkMetrics()},
     *                    where topological results have no run to inherit it from
     * @param scopeRef    the node or link a scoped value is about; null at {@code NETWORK} scope
     * @param displayUnit the unit {@code value} is read in, or null for a dimensionless metric.
     *                    Note that a time-valued metric is a count of the network's
     *                    <em>periods</em> and this names the period's unit, not the value's
     */
    public record MetricRecord(
            String network,
            String code,
            MetricScope scope,
            ElementRef scopeRef,
            double value,
            Double ciLow,
            Double ciHigh,
            TimeUnit displayUnit) {
    }

    /**
     * One period of a run's curves ({@code RUN_TIMESERIES}, V6).
     *
     * <p>The network's clock is not repeated here. A period's length, the horizon and the rounding
     * policy are in that network's {@code <meta>} element, and a second copy in the bundle is a
     * second thing to disagree with the first.
     */
    public record TimeseriesRecord(
            int period,
            double servedDemand,
            double totalDemand,
            double cost,
            double baselineServedDemand,
            double baselineCost) {
    }
}
