package com.snrm.network;

import com.snrm.common.DurationAmount;
import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeUnit;
import com.snrm.project.Project;
import com.snrm.scenario.DisruptionEvent;
import com.snrm.scenario.DisruptionScenario;
import com.snrm.scenario.DisruptionScenarioRepository;
import com.snrm.scenario.DisruptionTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The four resolution checks, one nested class per check.
 *
 * <p>Mockito rather than a Spring slice: every rule under test is arithmetic over a network's
 * declared durations, and nothing is learned by making a database produce the entities. The
 * repositories are stubbed to return object graphs built in memory.
 *
 * <p>Ids are set reflectively because the entities have none until JPA assigns one, and the
 * findings carry {@code elementId} — the comparator that orders the banner would fail on a null.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TimeValidationServiceTest {

    private static final long NETWORK_ID = 1L;
    private static final long SCENARIO_ID = 7L;
    private static final long OWNER_ID = 42L;

    @Mock private NetworkLookup lookup;
    @Mock private NodeRepository nodes;
    @Mock private LinkRepository links;
    @Mock private DisruptionScenarioRepository scenarios;

    private TimeValidationService service;
    private Project project;
    private Network network;

    @BeforeEach
    void setUp() {
        service = new TimeValidationService(lookup, nodes, links, scenarios);

        project = new Project("Resilience study", OWNER_ID);
        ReflectionTestUtils.setField(project, "id", 100L);

        network = new Network(project, "Baseline", 1, true);
        ReflectionTestUtils.setField(network, "id", NETWORK_ID);
        network.setPeriodLength(DurationAmount.of(1, TimeUnit.DAY));
        network.setRoundingPolicy(RoundingPolicy.NEAREST);
        network.setHorizonPeriods(52);

        when(lookup.requireNetwork(NETWORK_ID, OWNER_ID)).thenReturn(network);
        when(nodes.findByNetworkId(any())).thenReturn(List.of());
        when(links.findByNetworkId(any())).thenReturn(List.of());
    }

    // ------------------------------------------------ DURATION_ROUNDS_TO_ZERO

    @Nested
    @DisplayName("DURATION_ROUNDS_TO_ZERO")
    class RoundsToZero {

        @Test
        @DisplayName("a 6 h lead time on a 1-day period is reported")
        void sixHoursOnADayIsReported() {
            givenLink(DurationAmount.of(6, TimeUnit.HOUR));

            TimeFinding finding = onlyFinding(TimeValidationContext.EDITOR);

            assertThat(finding.code()).isEqualTo(TimeCheck.DURATION_ROUNDS_TO_ZERO);
            assertThat(finding.convertedPeriods()).isZero();
            assertThat(finding.elementType()).isEqualTo(TimeElementType.LINK);
            assertThat(finding.field()).isEqualTo("leadTime");
        }

        @Test
        @DisplayName("it is an error on import and a warning in the editor")
        void severityDependsOnContext() {
            // The one rule whose severity is contextual: during import the remedy is
            // to fix the file before anything is stored; in the editor the user may be mid-edit and
            // is entitled to a half-built network.
            givenLink(DurationAmount.of(6, TimeUnit.HOUR));

            assertThat(onlyFinding(TimeValidationContext.IMPORT).severity())
                    .isEqualTo(TimeSeverity.ERROR);
            assertThat(onlyFinding(TimeValidationContext.EDITOR).severity())
                    .isEqualTo(TimeSeverity.WARNING);
        }

        @Test
        @DisplayName("a zero duration is not reported — nothing was lost")
        void zeroIsNotAFinding() {
            // A lead time of 0 converts to 0 periods, but the user asked for instantaneous and got
            // it. Reporting it would make every default-valued link a warning.
            givenLink(DurationAmount.zero(TimeUnit.DAY));
            assertThat(report(TimeValidationContext.EDITOR).findings()).isEmpty();
        }

        @Test
        @DisplayName("a duration that fits exactly is not reported")
        void exactFitIsNotAFinding() {
            givenLink(DurationAmount.of(3, TimeUnit.DAY));
            assertThat(report(TimeValidationContext.EDITOR).findings()).isEmpty();
        }
    }

    // ------------------------------------------------ DURATION_ROUNDING_ERROR

    @Nested
    @DisplayName("DURATION_ROUNDING_ERROR")
    class RoundingError {

        @Test
        @DisplayName("10 h rounding up to 1 day is +140%")
        void tenHoursUpIsOneHundredAndFortyPercent() {
            // The canonical worked example. Under UP, 10 h becomes a full day: (24-10)/10 = +140%.
            network.setRoundingPolicy(RoundingPolicy.UP);
            givenLink(DurationAmount.of(10, TimeUnit.HOUR));

            TimeFinding finding = onlyFinding(TimeValidationContext.EDITOR);

            assertThat(finding.code()).isEqualTo(TimeCheck.DURATION_ROUNDING_ERROR);
            assertThat(finding.convertedPeriods()).isEqualTo(1);
            assertThat(finding.errorPercent()).isCloseTo(140d, within(0.5));
            assertThat(finding.severity()).isEqualTo(TimeSeverity.WARNING);
        }

        @Test
        @DisplayName("always a warning, even on import")
        void isAlwaysAWarning() {
            // Unlike a duration that rounds to zero: the value is still there to be corrected.
            network.setRoundingPolicy(RoundingPolicy.UP);
            givenLink(DurationAmount.of(10, TimeUnit.HOUR));

            assertThat(onlyFinding(TimeValidationContext.IMPORT).severity())
                    .isEqualTo(TimeSeverity.WARNING);
        }

        @Test
        @DisplayName("under 10% error is not reported")
        void withinToleranceIsSilent() {
            // 25 h on a 1-day period: rounds to 1 day, 4% short of what was asked for.
            givenLink(DurationAmount.of(25, TimeUnit.HOUR));
            assertThat(report(TimeValidationContext.EDITOR).findings()).isEmpty();
        }

        @Test
        @DisplayName("rounding to zero wins — the two are mutually exclusive")
        void doesNotDoubleReportWithRowOne() {
            // Under NEAREST, 10 h rounds to 0, which is already 100% wrong. Reporting both would
            // list the same element twice in the banner for one problem.
            givenLink(DurationAmount.of(10, TimeUnit.HOUR));

            assertThat(onlyFinding(TimeValidationContext.EDITOR).code())
                    .isEqualTo(TimeCheck.DURATION_ROUNDS_TO_ZERO);
        }
    }

    // -------------------------------------------------------- PERIOD_TOO_FINE

    @Nested
    @DisplayName("PERIOD_TOO_FINE")
    class PeriodTooFine {

        @Test
        @DisplayName("a 6-month lead time on a 1-second period costs millions of steps")
        void aVeryFinePeriodIsReported() {
            network.setPeriodLength(DurationAmount.of(1, TimeUnit.SECOND));
            givenLink(DurationAmount.of(6, TimeUnit.MONTH));

            assertThat(report(TimeValidationContext.EDITOR).findings())
                    .extracting(TimeFinding::code)
                    .contains(TimeCheck.PERIOD_TOO_FINE);
        }

        @Test
        @DisplayName("exactly at the 1000x limit is not reported")
        void atTheLimitIsSilent() {
            // 1000 hours against a 1-hour period is 1000 periods — the limit is "more than".
            network.setPeriodLength(DurationAmount.of(1, TimeUnit.HOUR));
            network.setHorizonPeriods(2000);
            givenLink(DurationAmount.of(1000, TimeUnit.HOUR));

            assertThat(report(TimeValidationContext.EDITOR).findings())
                    .extracting(TimeFinding::code)
                    .doesNotContain(TimeCheck.PERIOD_TOO_FINE);
        }

        @Test
        @DisplayName("a well-matched period produces nothing at all")
        void aSensiblePeriodIsSilent() {
            givenLink(DurationAmount.of(2, TimeUnit.DAY));
            assertThat(report(TimeValidationContext.EDITOR).findings()).isEmpty();
        }
    }

    // -------------------------------------------------- EVENT_EXCEEDS_HORIZON

    @Nested
    @DisplayName("EVENT_EXCEEDS_HORIZON")
    class EventExceedsHorizon {

        @Test
        @DisplayName("an event ending past the horizon is an error")
        void eventPastTheHorizonIsAnError() {
            network.setHorizonPeriods(10);
            givenEvent(DurationAmount.of(8, TimeUnit.DAY), DurationAmount.of(5, TimeUnit.DAY));

            assertThat(report(TimeValidationContext.EDITOR).findings())
                    .filteredOn(f -> f.code() == TimeCheck.EVENT_EXCEEDS_HORIZON)
                    .singleElement()
                    .satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(TimeSeverity.ERROR);
                        assertThat(f.elementType()).isEqualTo(TimeElementType.DISRUPTION_EVENT);
                    });
        }

        @Test
        @DisplayName("an event finishing inside the horizon is fine")
        void eventInsideTheHorizonIsSilent() {
            network.setHorizonPeriods(52);
            givenEvent(DurationAmount.of(8, TimeUnit.DAY), DurationAmount.of(5, TimeUnit.DAY));

            assertThat(report(TimeValidationContext.EDITOR).findings())
                    .extracting(TimeFinding::code)
                    .doesNotContain(TimeCheck.EVENT_EXCEEDS_HORIZON);
        }

        @Test
        @DisplayName("without a scenario the check cannot fire")
        void noScenarioMeansNoHorizonFinding() {
            network.setHorizonPeriods(1);

            TimeValidationReport report =
                    service.validate(NETWORK_ID, null, OWNER_ID, TimeValidationContext.EDITOR);

            assertThat(report.scenarioId()).isNull();
            assertThat(report.findings())
                    .extracting(TimeFinding::code)
                    .doesNotContain(TimeCheck.EVENT_EXCEEDS_HORIZON);
        }
    }

    // ------------------------------------------------------------- the report itself

    @Nested
    @DisplayName("the report")
    class Report {

        @Test
        @DisplayName("counts errors and warnings separately and lists the worst first")
        void ordersAndCounts() {
            network.setHorizonPeriods(2);
            givenLink(DurationAmount.of(6, TimeUnit.HOUR));                       // warning (editor)
            givenEvent(DurationAmount.of(8, TimeUnit.DAY),
                    DurationAmount.of(5, TimeUnit.DAY));                          // error

            TimeValidationReport report = report(TimeValidationContext.EDITOR);

            assertThat(report.errorCount()).isEqualTo(1);
            assertThat(report.warningCount()).isGreaterThanOrEqualTo(1);
            assertThat(report.findings().get(0).severity()).isEqualTo(TimeSeverity.ERROR);
            assertThat(report.findings()).hasSize(report.errorCount() + report.warningCount());
        }

        @Test
        @DisplayName("a clean network reports nothing and suggests nothing to fix")
        void cleanNetworkIsEmpty() {
            givenLink(DurationAmount.of(1, TimeUnit.DAY));

            TimeValidationReport report = report(TimeValidationContext.EDITOR);

            assertThat(report.findings()).isEmpty();
            assertThat(report.errorCount()).isZero();
            assertThat(report.warningCount()).isZero();
            assertThat(report.networkId()).isEqualTo(NETWORK_ID);
        }

        @Test
        @DisplayName("suggestPeriod proposes a period that keeps a 6 h lead time intact")
        void suggestsAWorkablePeriod() {
            givenLink(DurationAmount.of(6, TimeUnit.HOUR));

            Optional<DurationAmount> suggestion =
                    service.suggestPeriod(NETWORK_ID, null, OWNER_ID);

            assertThat(suggestion).isPresent();
            // Whatever it proposes must actually solve the problem it was asked about.
            DurationAmount period = suggestion.orElseThrow();
            assertThat(DurationAmount.of(6, TimeUnit.HOUR)
                    .inPeriods(period, RoundingPolicy.NEAREST))
                    .isPositive();
            assertThat(period.getSeconds()).isLessThanOrEqualTo(6 * 3600L);
        }

        @Test
        @DisplayName("suggestPeriod has nothing to say about a network with no durations")
        void noDurationsMeansNoSuggestion() {
            assertThat(service.suggestPeriod(NETWORK_ID, null, OWNER_ID)).isEmpty();
        }
    }

    // ---------------------------------------------------------------------- helpers

    private void givenLink(DurationAmount leadTime) {
        Node source = node("SUP-1", NodeType.SUPPLIER, 1L);
        Node target = node("DC-1", NodeType.DC, 2L);
        Link link = new Link(network, source, target);
        ReflectionTestUtils.setField(link, "id", 10L);
        link.setLeadTime(leadTime);
        when(links.findByNetworkId(any())).thenReturn(List.of(link));
    }

    private Node node(String name, NodeType type, long id) {
        Node node = new Node(network, name, type);
        ReflectionTestUtils.setField(node, "id", id);
        return node;
    }

    private void givenEvent(DurationAmount startOffset, DurationAmount duration) {
        DisruptionScenario scenario = new DisruptionScenario(project, "Port closure");
        ReflectionTestUtils.setField(scenario, "id", SCENARIO_ID);

        // Null targetRegion: a NODE event is addressed by its id, and carrying both halves is what
        // ck_event_target and EVENT_TARGET_INVALID forbid (V5__event_region_target.sql).
        DisruptionEvent event = new DisruptionEvent(scenario, DisruptionTargetType.NODE, 1L, null,
                startOffset, duration, 1.0);
        ReflectionTestUtils.setField(event, "id", 20L);
        scenario.addEvent(event);

        when(scenarios.findWithEventsById(SCENARIO_ID)).thenReturn(Optional.of(scenario));
    }

    private TimeValidationReport report(TimeValidationContext context) {
        Long scenarioId = scenarios.findWithEventsById(SCENARIO_ID).isPresent() ? SCENARIO_ID : null;
        return service.validate(NETWORK_ID, scenarioId, OWNER_ID, context);
    }

    private TimeFinding onlyFinding(TimeValidationContext context) {
        List<TimeFinding> findings = report(context).findings();
        assertThat(findings).hasSize(1);
        return findings.get(0);
    }
}
