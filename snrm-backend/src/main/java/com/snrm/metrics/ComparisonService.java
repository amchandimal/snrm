package com.snrm.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.snrm.common.DurationDto;
import com.snrm.common.TimeUnit;
import com.snrm.network.ConfigurationVariant;
import com.snrm.network.ConfigurationVariantRepository;
import com.snrm.network.Network;
import com.snrm.network.NetworkRepository;
import com.snrm.project.Project;
import com.snrm.project.ProjectRepository;
import com.snrm.simulation.SimulationRun;
import com.snrm.simulation.SimulationRunRepository;
import com.snrm.simulation.SimulationStatus;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The cross-variant metric matrix behind the comparison view (FR-10).
 *
 * <blockquote>{@code GET /projects/{id}/comparison?networkIds=…} — "Cross-variant metric matrix for
 * the comparison view."</blockquote>
 *
 * <p>This is the second class in {@code com.snrm.metrics} to touch a repository, after
 * {@link TopologicalMetricsService}, and for the same reason: it is a persistence edge, not a
 * calculator. Nothing here computes a metric. It reads values that were computed elsewhere, aligns
 * them into a table, and answers the three questions a table of numbers cannot answer about itself —
 * which cell wins, what unit the time-valued rows are in, and what is uneven about the comparison.
 * The engine isolation is unaffected: no calculator sees any of this.
 *
 * <h2>Which run each column reads from</h2>
 *
 * <p>A network can have many runs, and a matrix has one column per network, so a choice is
 * unavoidable. The rule is the most recent {@code DONE} run — narrowed to one scenario when the
 * request names one, which is the form a researcher actually wants: comparing variant A under a
 * plant outage against variant B under a port closure measures the scenarios, and the lever change
 * gets the credit. When the request does not pin a scenario and the chosen runs turn out to disagree
 * about it, that is not refused but reported, as {@link ComparisonNoteDto#MIXED_SCENARIOS}.
 *
 * <p>{@link #compareRuns} sidesteps the choice instead of making it: the caller names the runs, one
 * column each (FR-17). That is the only form that can put two runs of <em>one</em> network side by
 * side — baseline versus disruption, or one network under two scenarios — which the network-keyed
 * rule structurally cannot express.
 *
 * <p>A network with no completed run keeps its column and fills only the topological half of the
 * suite. That is a state worth comparing in — structural metrics are computed on save, so
 * a variant can be judged structurally before an hour of Monte Carlo is spent on it — and it is
 * reported as {@link ComparisonNoteDto#NO_RUN} so an empty half-column is never mistaken for zeros.
 *
 * <h2>The common unit</h2>
 *
 * <blockquote>"Comparison across configurations converts to a common unit before display, which
 * matters once variants of the same network are compared after a period change."</blockquote>
 *
 * <p>A time-valued metric is stored as a count of <em>this network's</em> periods, so
 * two columns whose networks step differently hold numbers that are not on the same scale. The
 * conversion runs through seconds, which is the only common denominator the unit set has:
 *
 * <pre>
 *   seconds       = value × periodSeconds(network)
 *   commonValue   = seconds / secondsOf(commonUnit)
 * </pre>
 *
 * <p><strong>{@code periodSeconds}, not {@code secondsOf(periodUnit)}.</strong> A network stepping
 * in 2 DAY has a {@code display_unit} of {@code DAY}, and a {@code TTR} of 14 periods on it is 28
 * days, not 14. Reading the unit without the period <em>value</em> is the one arithmetic slip this
 * conversion exists to prevent, and it is why the raw period count travels beside the converted
 * value in {@link ComparisonCellDto#periods()} rather than being thrown away.
 *
 * <p>The common unit is the <strong>finest</strong> period unit among the compared variants. A
 * coarser one would push an hourly variant's recovery time into fractions of a day; the finest keeps
 * every value at or above the resolution the model that produced it actually had.
 */
@Service
public class ComparisonService {

    private final ProjectRepository projects;
    private final NetworkRepository networks;
    private final ConfigurationVariantRepository variants;
    private final SimulationRunRepository runs;
    private final MetricResultRepository metricResults;
    private final MetricCalculatorRegistry registry;
    private final ObjectMapper json;

    ComparisonService(ProjectRepository projects, NetworkRepository networks,
            ConfigurationVariantRepository variants, SimulationRunRepository runs,
            MetricResultRepository metricResults, MetricCalculatorRegistry registry,
            ObjectMapper json) {
        this.projects = projects;
        this.networks = networks;
        this.variants = variants;
        this.runs = runs;
        this.metricResults = metricResults;
        this.registry = registry;
        this.json = json;
    }

    /**
     * Builds the matrix.
     *
     * @param projectId  the project every compared network must belong to
     * @param ownerId    the calling researcher
     * @param networkIds the configurations to compare, in the order they should appear as columns;
     *                   empty or null compares every network in the project
     * @param scenarioId pin every column to this disruption scenario, or null to take each
     *                   network's most recent completed run whatever it applied
     * @throws EntityNotFoundException if the project is not the caller's, or a named network is not
     *                                 in it
     */
    @Transactional(readOnly = true)
    public ComparisonMatrixDto compare(long projectId, long ownerId, List<Long> networkIds,
            Long scenarioId) {

        Project project = requireProject(projectId, ownerId);
        List<Network> columns = resolveColumns(project, networkIds);

        // One read for every value in the matrix; partitioned below by whether the row belongs to
        // the network (topological) or to a run (simulated).
        List<Long> ids = columns.stream().map(Network::getId).toList();
        List<MetricResult> rows = metricResults.findNetworkScopedByNetworkIds(ids);

        List<Column> resolved = new ArrayList<>(columns.size());
        for (Network network : columns) {
            resolved.add(resolveColumn(network, scenarioId, rows));
        }
        return assemble(projectId, resolved);
    }

    /**
     * The run-keyed form: one column per <em>run</em> rather than per network (FR-17).
     *
     * <p>The network-keyed form above cannot express two questions a researcher legitimately asks:
     * a network's baseline run beside its disruption run — which is how a run from the editor is
     * read — and one network under two different scenarios.
     * Both are two runs of one network, and a matrix keyed by network gives that network one column.
     *
     * <p>Everything downstream of column resolution is shared with {@link #compare}: the same
     * {@link #assemble}, the same unit conversion, the same best-in-row, the same notes. Only which
     * runs feed the columns differs — which is what keeps the two forms from ever disagreeing about
     * what a cell means. {@code MIXED_SCENARIOS} fires on a baseline-versus-disruption pair,
     * deliberately: that comparison measures <em>only</em> the scenario, and the note is the row of
     * text that says so.
     *
     * @param projectId the project every compared run's network must belong to
     * @param ownerId   the calling researcher
     * @param runIds    the runs to compare, in the order they should appear as columns
     * @throws EntityNotFoundException if the project is not the caller's, or a named run is not on
     *                                 a network of this project
     * @throws RunNotComparableException if a named run is not {@code DONE} — its column would be
     *                                 empty air, and the caller's remedy is to wait
     */
    @Transactional(readOnly = true)
    public ComparisonMatrixDto compareRuns(long projectId, long ownerId, List<Long> runIds) {
        requireProject(projectId, ownerId);
        List<SimulationRun> selected = resolveRuns(projectId, ownerId, runIds);

        List<Long> ids = selected.stream().map(run -> run.getNetwork().getId()).distinct().toList();
        List<MetricResult> rows = metricResults.findNetworkScopedByNetworkIds(ids);

        List<Column> resolved = new ArrayList<>(selected.size());
        for (SimulationRun run : selected) {
            resolved.add(columnOf(run.getNetwork(), run, rows));
        }
        return assemble(projectId, resolved);
    }

    /** Everything after column resolution — one implementation for both selectors. */
    private ComparisonMatrixDto assemble(long projectId, List<Column> resolved) {
        TimeUnit commonUnit = finestPeriodUnit(resolved);
        List<ComparisonRowDto> matrix = buildRows(resolved, commonUnit);

        boolean mixedTimeBases = mixedTimeBases(resolved);
        boolean mixedScenarios = mixedScenarios(resolved);
        boolean anyTimeValued = matrix.stream().anyMatch(ComparisonRowDto::timeValued);

        return new ComparisonMatrixDto(
                projectId,
                resolved.stream().map(Column::dto).toList(),
                matrix,
                anyTimeValued ? commonUnit : null,
                mixedTimeBases,
                mixedScenarios,
                notes(resolved, matrix, commonUnit, mixedTimeBases, mixedScenarios));
    }

    // ------------------------------------------------------------------------- columns

    /**
     * One column, resolved: the network, the run its simulated half came from, and its values by
     * metric code.
     *
     * @param periodSeconds this network's period in seconds — the conversion factor for every
     *                      time-valued value in the column
     */
    private record Column(Network network, SimulationRun run, ComparisonVariantDto dto,
            double periodSeconds, Map<String, MetricResult> values) {
    }

    /** The network-keyed rule: this network's most recent {@code DONE} run, optionally pinned. */
    private Column resolveColumn(Network network, Long scenarioId, List<MetricResult> allRows) {
        Optional<SimulationRun> run = scenarioId == null
                ? runs.findFirstByNetworkIdAndStatusOrderByIdDesc(network.getId(),
                        SimulationStatus.DONE)
                : runs.findFirstByNetworkIdAndScenarioIdAndStatusOrderByIdDesc(network.getId(),
                        scenarioId, SimulationStatus.DONE);
        return columnOf(network, run.orElse(null), allRows);
    }

    /** One column over a decided (network, run) pair — shared by both selectors. */
    private Column columnOf(Network network, SimulationRun run, List<MetricResult> allRows) {
        Long runId = run == null ? null : run.getId();

        // Later rows win, and the two halves cannot collide: a code is produced by exactly one
        // calculator (the registry refuses duplicates) and a calculator is of exactly one kind, so
        // no metric appears both with and without a run.
        Map<String, MetricResult> values = new LinkedHashMap<>();
        for (MetricResult row : allRows) {
            if (!row.getNetwork().getId().equals(network.getId())) {
                continue;
            }
            Long rowRunId = row.getRun() == null ? null : row.getRun().getId();
            if (rowRunId == null || rowRunId.equals(runId)) {
                values.put(row.getMetricCode(), row);
            }
        }

        ConfigurationVariant variant = variants.findByNetworkId(network.getId()).orElse(null);
        DurationDto periodLength = DurationDto.of(network.getPeriodLength().getValue(),
                network.getPeriodLength().getUnit());

        ComparisonVariantDto dto = new ComparisonVariantDto(
                network.getId(),
                network.getName(),
                network.getVersion(),
                network.isBaseline(),
                variant == null ? null : variant.getBaseNetwork().getId(),
                variant == null ? null : variant.getGeneratedBy(),
                variant == null ? null : leverChanges(variant.getLeverChangesJson()),
                runId,
                // Null on a column whose run is a baseline run (FR-17) as well as on one with no
                // run at all; the DTO's scenarioName doc says which reading applies.
                run == null || run.getScenario() == null ? null : run.getScenario().getId(),
                run == null || run.getScenario() == null ? null : run.getScenario().getName(),
                run == null ? null : run.getFinishedAt(),
                periodLength,
                network.getHorizonPeriods());

        double periodSeconds = network.getPeriodLength().getUnit()
                .secondsOf(network.getPeriodLength().getValue());
        return new Column(network, run, dto, periodSeconds, values);
    }

    // ---------------------------------------------------------------------------- rows

    /**
     * The matrix body, in suite order.
     *
     * <p>Codes the registry knows come first, in the order it emits them; a code held in
     * the database that no calculator on this classpath claims — a metric since removed, or a row
     * written by a later build — is appended rather than dropped, because {@code metric_code} is
     * opaque and a value nobody can describe is still a value somebody stored.
     */
    private List<ComparisonRowDto> buildRows(List<Column> columns, TimeUnit commonUnit) {
        Set<String> present = new LinkedHashSet<>();
        for (String code : registry.codesInSuiteOrder()) {
            if (columns.stream().anyMatch(column -> column.values().containsKey(code))) {
                present.add(code);
            }
        }
        for (Column column : columns) {
            present.addAll(column.values().keySet());
        }

        List<ComparisonRowDto> rows = new ArrayList<>(present.size());
        for (String code : present) {
            Optional<MetricCalculator> calculator = registry.find(code);
            MetricDirection direction = calculator.map(MetricCalculator::direction)
                    .orElse(MetricDirection.NEUTRAL);
            boolean timeValued = calculator.map(MetricCalculator::timeValued).orElse(false);

            List<ComparisonCellDto> cells = new ArrayList<>(columns.size());
            for (Column column : columns) {
                cells.add(cellOf(column, code, timeValued, commonUnit));
            }
            rows.add(new ComparisonRowDto(code, direction, timeValued,
                    timeValued ? commonUnit : null, markBest(cells, direction)));
        }
        return rows;
    }

    /** One variant's value for one metric, converted where the metric counts periods. */
    private ComparisonCellDto cellOf(Column column, String code, boolean timeValued,
            TimeUnit commonUnit) {

        MetricResult row = column.values().get(code);
        if (row == null) {
            return null;
        }
        if (!timeValued || commonUnit == null) {
            return ComparisonCellDto.plain(row.getValue(), row.getCiLow(), row.getCiHigh());
        }
        double factor = column.periodSeconds() / commonUnit.secondsOf();
        return ComparisonCellDto.timed(
                row.getValue() * factor,
                row.getCiLow() == null ? null : row.getCiLow() * factor,
                row.getCiHigh() == null ? null : row.getCiHigh() * factor,
                row.getValue());
    }

    /**
     * Marks the winning cell, or every cell tied for it.
     *
     * <p>Ties are marked rather than broken. A suite full of bounded ratios produces them often —
     * two variants that both hold a fill rate of 1.0 are genuinely equal on that row — and picking
     * the leftmost would make the highlighting a function of the order the columns were requested
     * in.
     *
     * <p>The comparison is on the <em>converted</em> value, which for a time-valued row is the only
     * one on a common scale. Comparing raw period counts across variants with different clocks would
     * rank a 14-period recovery on a daily network above a 20-period recovery on an hourly one.
     *
     * <p><strong>A {@code NEUTRAL} row is returned untouched, and that is the whole treatment it
     * gets.</strong> Its cells are present, populated and comparable by eye; none of them is marked.
     * {@code DENSITY}, {@code AVG_PATH} and {@code CLUSTERING} were the original three, and
     * {@code AVG_INVENTORY} and {@code AVG_PIPELINE} joined them on the same reasoning — leaner versus
     * more buffered is the trade-off under study rather than a ranking, and highlighting either end
     * would put a claim on screen that nothing in the suite supports. Nothing in this
     * method needed changing for them: the direction comes from the calculator, which is exactly why
     * it lives there.
     */
    private static List<ComparisonCellDto> markBest(List<ComparisonCellDto> cells,
            MetricDirection direction) {

        if (!direction.isRanked()) {
            return cells;
        }
        Double best = null;
        for (ComparisonCellDto cell : cells) {
            if (cell == null || !Double.isFinite(cell.value())) {
                continue;
            }
            if (best == null || direction.isBetter(cell.value(), best)) {
                best = cell.value();
            }
        }
        if (best == null) {
            return cells;
        }
        final double winner = best;
        List<ComparisonCellDto> marked = new ArrayList<>(cells.size());
        for (ComparisonCellDto cell : cells) {
            marked.add(cell != null && cell.value() == winner ? cell.asBest() : cell);
        }
        return marked;
    }

    // ------------------------------------------------------------------------ the clock

    /**
     * The finest period unit among the compared variants — the unit every time-valued row is stated
     * in.
     *
     * <p>Finest rather than coarsest, and rather than the baseline's: converting into a coarser unit
     * than a variant's own turns its values into fractions and reports a resolution the model never
     * had. Null only when there are no columns at all.
     */
    private static TimeUnit finestPeriodUnit(List<Column> columns) {
        TimeUnit finest = null;
        for (Column column : columns) {
            TimeUnit unit = column.network().getPeriodLength().getUnit();
            if (finest == null || unit.secondsOf() < finest.secondsOf()) {
                finest = unit;
            }
        }
        return finest;
    }

    /** Whether the columns disagree about how long a period is. */
    private static boolean mixedTimeBases(List<Column> columns) {
        return columns.stream().mapToDouble(Column::periodSeconds).distinct().count() > 1;
    }

    /**
     * Whether the runs behind the columns applied different scenarios.
     *
     * <p>A baseline run counts as its own "scenario" here — id 0, which no real row holds. That is
     * the honest reading: a baseline column beside a disruption column is measuring the scenario as
     * well as the configuration, which is exactly what the note exists to say. For the run-keyed
     * baseline-versus-disruption comparison of FR-17 the note therefore fires, correctly — that
     * comparison measures <em>only</em> the scenario, and the reader should hold that fact.
     */
    private static boolean mixedScenarios(List<Column> columns) {
        return columns.stream()
                .map(Column::run)
                .filter(run -> run != null)
                .map(run -> run.getScenario() == null ? 0L : run.getScenario().getId())
                .distinct()
                .count() > 1;
    }

    // ---------------------------------------------------------------------------- notes

    private static List<ComparisonNoteDto> notes(List<Column> columns, List<ComparisonRowDto> rows,
            TimeUnit commonUnit, boolean mixedTimeBases, boolean mixedScenarios) {

        List<ComparisonNoteDto> notes = new ArrayList<>();

        if (mixedTimeBases) {
            String clocks = columns.stream()
                    .map(column -> "%s v%d steps in %s".formatted(column.dto().name(),
                            column.dto().version(), format(column.dto().periodLength())))
                    .distinct()
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
            notes.add(new ComparisonNoteDto(ComparisonNoteDto.MIXED_TIME_BASES,
                    ("These configurations do not share a period length (%s). Time-valued metrics "
                            + "have been converted to %s so the row is comparable, but the models "
                            + "differ in what they can resolve.")
                            .formatted(clocks, commonUnit)));
        }
        if (mixedScenarios) {
            String scenarios = columns.stream()
                    .filter(column -> column.run() != null)
                    // A baseline run applied no scenario, and the note has to be able to say so —
                    // filtering it out would report "different scenarios (Plant outage)" with no
                    // second name in sight.
                    .map(column -> StringUtils.hasText(column.dto().scenarioName())
                            ? column.dto().scenarioName()
                            : "baseline (no scenario)")
                    .distinct()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            notes.add(new ComparisonNoteDto(ComparisonNoteDto.MIXED_SCENARIOS,
                    ("The runs behind these columns applied different disruption scenarios (%s), so "
                            + "the comparison measures the scenarios as well as the configurations. "
                            + "Add ?scenarioId= to pin every column to one.").formatted(scenarios)));
        }

        List<String> withoutRun = columns.stream()
                .filter(column -> column.run() == null)
                .map(column -> "%s v%d".formatted(column.dto().name(), column.dto().version()))
                .toList();
        if (!withoutRun.isEmpty()) {
            notes.add(new ComparisonNoteDto(ComparisonNoteDto.NO_RUN,
                    ("No completed simulation run for %s, so only the topological half of the suite "
                            + "is filled in for %s. The empty cells are unmeasured, not zero.")
                            .formatted(String.join(", ", withoutRun),
                                    withoutRun.size() == 1 ? "it" : "them")));
        }

        boolean partial = rows.stream().anyMatch(row -> {
            long filled = row.cells().stream().filter(cell -> cell != null).count();
            return filled > 0 && filled < row.cells().size();
        });
        if (partial && withoutRun.isEmpty()) {
            notes.add(new ComparisonNoteDto(ComparisonNoteDto.PARTIAL_SUITE,
                    "Some metrics are missing from some columns. A metric that was undefined for a "
                            + "run produces no row rather than a zero — TTR has nothing to report "
                            + "if no replication was disrupted."));
        }
        return notes;
    }

    // ---------------------------------------------------------------------- resolution

    private Project requireProject(long projectId, long ownerId) {
        return projects.findById(projectId)
                .filter(project -> project.getOwnerId() == ownerId)
                .orElseThrow(() -> new EntityNotFoundException("No project with id " + projectId));
    }

    /**
     * The columns, in the order the request named them.
     *
     * <p>Requested order is kept deliberately: a researcher who asks for the baseline first is
     * building a table to read left to right, and re-sorting it would be the tool overriding a
     * presentational decision it was given. Duplicates collapse, since a column repeated is a column.
     *
     * @throws EntityNotFoundException if a named network is not in this project — including one that
     *                                 exists elsewhere, which is the same answer as missing
     *                                 ({@code NetworkLookup} makes the same choice)
     */
    private List<Network> resolveColumns(Project project, List<Long> networkIds) {
        List<Network> inProject = networks.findByProjectIdOrderByNameAscVersionAsc(project.getId());
        if (networkIds == null || networkIds.isEmpty()) {
            return inProject;
        }
        Map<Long, Network> byId = new HashMap<>();
        for (Network network : inProject) {
            byId.put(network.getId(), network);
        }
        List<Network> columns = new ArrayList<>(networkIds.size());
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : networkIds) {
            if (id == null || !seen.add(id)) {
                continue;
            }
            Network network = byId.get(id);
            if (network == null) {
                throw new EntityNotFoundException(
                        "No network with id %d in project %d".formatted(id, project.getId()));
            }
            columns.add(network);
        }
        return columns;
    }

    /**
     * The runs of the run-keyed form, in the order the request named them.
     *
     * <p>The same reading {@link #resolveColumns} takes: requested order kept, duplicates collapsed,
     * and "not yours" indistinguishable from "does not exist". A run that is not
     * {@code DONE} is refused rather than seated as an empty column — unlike a network with no run,
     * which has a topological half to show, a half-finished run has nothing yet and will have
     * something soon, which is a 409 shape rather than a 404 one.
     */
    private List<SimulationRun> resolveRuns(long projectId, long ownerId, List<Long> runIds) {
        List<SimulationRun> selected = new ArrayList<>(runIds.size());
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : runIds) {
            if (id == null || !seen.add(id)) {
                continue;
            }
            SimulationRun run = runs.findById(id)
                    .filter(found -> found.getNetwork().getProject().getOwnerId() == ownerId)
                    .filter(found -> found.getNetwork().getProject().getId() == projectId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No simulation run with id %d in project %d".formatted(id, projectId)));
            if (run.getStatus() != SimulationStatus.DONE) {
                throw new RunNotComparableException(run.getId(), run.getStatus());
            }
            selected.add(run);
        }
        if (selected.isEmpty()) {
            throw new EntityNotFoundException("runIds named no run at all");
        }
        return selected;
    }

    /**
     * The stored lever diff as real JSON.
     *
     * <p>Unparseable text yields null rather than failing the comparison, following
     * {@code ConfigurationVariantMapper}: the column is written only by this application and MySQL
     * validates it on write, so the case is unreachable — and losing a whole matrix over one
     * annotation would be the wrong trade.
     */
    private JsonNode leverChanges(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static String format(DurationDto duration) {
        double value = duration.value();
        String number = value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
        return number + " " + duration.unit();
    }
}
