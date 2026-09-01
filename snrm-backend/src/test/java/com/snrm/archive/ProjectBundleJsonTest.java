package com.snrm.archive;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.snrm.archive.ProjectBundle.Counts;
import com.snrm.archive.ProjectBundle.ElementRef;
import com.snrm.archive.ProjectBundle.EventRecord;
import com.snrm.archive.ProjectBundle.Manifest;
import com.snrm.archive.ProjectBundle.MetricRecord;
import com.snrm.archive.ProjectBundle.ScenarioRecord;
import com.snrm.archive.ProjectBundle.Selection;
import com.snrm.common.TimeUnit;
import com.snrm.metrics.MetricScope;
import com.snrm.scenario.DisruptionTargetType;
import com.snrm.scenario.RecoveryProfileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code bundle.json} survives its own writer.
 *
 * <p>Needs no database, no Spring and no Docker: the bundle is a tree of records and Jackson, which
 * is exactly why this test can exist and why its absence was expensive. A full archive round trip
 * is the test that should land next; this is the half of it that needs nothing
 * but the two.
 *
 * <p><strong>What it exists to prevent.</strong> {@link ElementRef} carried a derived predicate,
 * {@code isUnresolved()}. Jackson introspects a record's ordinary methods as well as its components,
 * so the {@code isXxx()} pattern made it a seventh member of the written document — one the
 * canonical constructor has no parameter for. With
 * {@code spring.jackson.deserialization.fail-on-unknown-properties=true}, reading such a bundle back
 * threw, and <em>every archive the tool wrote was refused by it</em> with {@code ARCHIVE_UNREADABLE}.
 * {@link ArchiveSchema} says an archive that cannot be re-read is worse than no archive at all, and
 * this is that failure. The rule the tests below pin is therefore general rather than about one
 * annotation: <strong>a bundle record's JSON shape is its components and nothing else.</strong>
 *
 * <p>The mapper is configured the way the application configures its own rather than being taken
 * from Spring: the strictness is the whole point of the test, and a default that quietly tolerated
 * an extra member would make this pass while the application still refused the file. Both settings
 * are mirrored from {@code application.properties} —
 * {@code deserialization.fail-on-unknown-properties=true} and
 * {@code default-property-inclusion=non_null} — and the second is load-bearing as of FR-24: a
 * whole-project archive is byte-identical to what this tool wrote before subsets existed only
 * because a null {@link Selection} is not written at all.
 */
@DisplayName("ProjectBundle — what is written is what can be read")
class ProjectBundleJsonTest {

    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    @DisplayName("an ElementRef writes its six components and no derived member")
    void elementRefWritesOnlyItsComponents() throws Exception {
        String written = json.writeValueAsString(ElementRef.node("Baseline@v1", "PLANT-1"));

        // The defect, stated as the assertion that catches it: `unresolved` is derived from
        // `unresolvedSourceId` and must never reach the document.
        assertThat(written).doesNotContain("\"unresolved\"");
        assertThat(written).contains("\"networkKey\"", "\"nodeName\"");
    }

    @Test
    @DisplayName("every shape of reference round-trips unchanged")
    void everyReferenceShapeRoundTrips() throws Exception {
        List<ElementRef> refs = List.of(
                ElementRef.node("Baseline@v1", "PLANT-1"),
                ElementRef.link("Baseline@v1", "PLANT-1", "DC-2"),
                ElementRef.region("Kanto"),
                ElementRef.unresolved(4_242L));

        for (ElementRef ref : refs) {
            ElementRef read = json.readValue(json.writeValueAsString(ref), ElementRef.class);
            assertThat(read).isEqualTo(ref);
        }
    }

    @Test
    @DisplayName("the derived predicate still answers after a round trip")
    void unresolvedIsStillDerivedOnTheWayBack() throws Exception {
        // Dropping it from the document must not drop it from the object: `ProjectRestoreService`
        // branches on this to write the negative sentinel target id, and a reference that came back
        // claiming to be resolved would restore an event pointing at a live node of the new project.
        ElementRef dangling = json.readValue(
                json.writeValueAsString(ElementRef.unresolved(4_242L)), ElementRef.class);

        assertThat(dangling.isUnresolved()).isTrue();
        assertThat(dangling.unresolvedSourceId()).isEqualTo(4_242L);

        ElementRef resolved = json.readValue(
                json.writeValueAsString(ElementRef.node("Baseline@v1", "PLANT-1")), ElementRef.class);

        assertThat(resolved.isUnresolved()).isFalse();
    }

    @Test
    @DisplayName("an archive written by the build that had the bug still reads")
    void bundlesAlreadyWrittenAreStillReadable() {
        // Those files exist, and for a restored experiment the archive is the only copy of what it
        // holds — so the fix has to make them readable rather than merely stop producing more.
        // `@JsonIgnore` registers the member as known-and-ignorable, not merely absent, which is the
        // difference between skipping it and throwing on it.
        String writtenByTheBrokenBuild = """
                {
                  "networkKey" : "Baseline@v1",
                  "nodeName" : "PLANT-1",
                  "linkSource" : null,
                  "linkTarget" : null,
                  "region" : null,
                  "unresolvedSourceId" : null,
                  "unresolved" : false
                }""";

        assertThatCode(() -> {
            ElementRef read = json.readValue(writtenByTheBrokenBuild, ElementRef.class);
            assertThat(read).isEqualTo(ElementRef.node("Baseline@v1", "PLANT-1"));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a scenario with a targeted event round-trips, references and all")
    void aScenarioCarryingReferencesRoundTrips() throws Exception {
        ScenarioRecord scenario = new ScenarioRecord("Plant outage", 100, 42L, 7L, List.of(
                new EventRecord(DisruptionTargetType.NODE,
                        ElementRef.node("Baseline@v1", "PLANT-1"),
                        2, TimeUnit.DAY, 5, TimeUnit.DAY, 0.6,
                        RecoveryProfileType.LINEAR, 1.0, 11L),
                new EventRecord(DisruptionTargetType.LINK,
                        ElementRef.link("Baseline@v1", "PLANT-1", "DC-2"),
                        0, TimeUnit.WEEK, 1, TimeUnit.WEEK, 1.0,
                        RecoveryProfileType.STEP, 0.5, 12L)));

        ScenarioRecord read =
                json.readValue(json.writeValueAsString(scenario), ScenarioRecord.class);

        assertThat(read).isEqualTo(scenario);
    }

    @Test
    @DisplayName("a scoped metric result round-trips, which is the other ElementRef site")
    void aScopedMetricRoundTrips() throws Exception {
        MetricRecord record = new MetricRecord("Baseline@v1", "NODE_CRITICALITY", MetricScope.NODE,
                ElementRef.node("Baseline@v1", "PLANT-1"), 0.42, 0.40, 0.44, null);

        assertThat(json.readValue(json.writeValueAsString(record), MetricRecord.class))
                .isEqualTo(record);
    }

    // ------------------------------------------------------- the subset manifest (FR-24)

    @Test
    @DisplayName("a whole-project manifest writes no selection member at all")
    void aWholeProjectManifestCarriesNoSelection() throws Exception {
        Manifest manifest = new Manifest("2026-08-08T09:00:00Z", "SNRM", "1.0", 7L,
                new Counts(3, 2, 1, 1, 1, 4, 52), null);

        String written = json.writeValueAsString(manifest);

        // The property FR-24 must not cost: the reproducibility artefact does not change
        // shape because a second caller appeared. `non_null` inclusion is what makes it true, so it
        // is asserted here rather than trusted from a properties file two modules away.
        assertThat(written).doesNotContain("selection");
        assertThat(json.readValue(written, Manifest.class)).isEqualTo(manifest);
    }

    @Test
    @DisplayName("a subset manifest round-trips with its three counts intact")
    void aSubsetManifestRoundTrips() throws Exception {
        Manifest manifest = new Manifest("2026-08-08T09:00:00Z", "SNRM", "1.0", 7L,
                new Counts(2, 2, 1, 1, 1, 4, 52),
                new Selection(2, 3, List.of("Alpha@v1", "Charlie@v1"), 1, 1));

        Manifest read = json.readValue(json.writeValueAsString(manifest), Manifest.class);

        assertThat(read).isEqualTo(manifest);
        assertThat(read.selection().droppedVariantEdges())
                .as("the count the restore turns into ARCHIVE_IS_SUBSET; nothing on that side "
                        + "could re-derive it, since the excluded half is not in the file")
                .isEqualTo(1);
        assertThat(read.selection().excludedEventTargets()).isEqualTo(1);
    }

    @Test
    @DisplayName("an archive written before subsets existed still reads")
    void aManifestWithoutASelectionMemberStillReads() {
        // Every archive this tool has written so far. A missing record component binds to its
        // default, which for a reference is null — the same value the whole-project path writes
        // today, so an old bundle and a new whole-project one are the same object.
        String writtenBeforeFr24 = """
                {
                  "exportedAt" : "2026-08-08T09:00:00Z",
                  "application" : "SNRM",
                  "engineVersion" : "1.0",
                  "sourceProjectId" : 7,
                  "counts" : {
                    "networks" : 3, "products" : 2, "scenarios" : 1, "events" : 1,
                    "runs" : 1, "metricResults" : 4, "timeseriesRows" : 52
                  }
                }""";

        assertThatCode(() -> {
            Manifest read = json.readValue(writtenBeforeFr24, Manifest.class);
            assertThat(read.selection()).isNull();
            assertThat(read.counts().networks()).isEqualTo(3);
        }).doesNotThrowAnyException();
    }
}
