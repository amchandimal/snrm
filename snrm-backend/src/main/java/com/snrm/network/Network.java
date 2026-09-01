package com.snrm.network;

import com.snrm.common.AuditableEntity;
import com.snrm.common.DurationAmount;
import com.snrm.common.RoundingPolicy;
import com.snrm.common.TimeUnit;
import com.snrm.project.Project;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * One configuration of the supply network: the aggregate root over its nodes and links
 * ({@code NETWORK}).
 *
 * <p><strong>Immutability.</strong> Once a simulation run has been evaluated against a
 * network, that network is frozen — its metric results and time series are only meaningful next to
 * the exact structure that produced them. Edits fork a new {@link ConfigurationVariant} that
 * materialises as a fresh {@code Network} row. The rule is enforced by
 * {@link NetworkMutationGuard} at the service layer, deliberately not by a database trigger, so
 * the caller gets an actionable {@link NetworkImmutableException} instead of a constraint error.
 *
 * <p>{@link #getVersion()} is the domain variant number within {@code (project, name)}, <em>not</em>
 * a JPA optimistic-lock version — it must never be annotated {@code @Version}. The baseline is
 * version 1; each fork takes the next number under the same name.
 *
 * <p><strong>The network owns the clock (FR-13).</strong> {@link #getPeriodLength()},
 * {@link #getHorizonPeriods()} and {@link #getRoundingPolicy()} together define the discrete-time
 * grid every simulation over this network steps on. Durations elsewhere in the model —
 * lead times, processing times, event windows — are stated in their own units and discretised
 * against this grid, which is why the rounding policy is a property of the network rather than of
 * each duration: two variants compared side by side must discretise identically or the
 * comparison measures the rounding, not the configuration.
 */
@Entity
@Table(name = "network")
public class Network extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /** Variant generation within {@code (project, name)}; see the class Javadoc — not {@code @Version}. */
    @Column(name = "version", nullable = false)
    private int version = 1;

    /**
     * True for the network the project's variants are compared against.
     * Mapped without a {@code columnDefinition} on purpose: MySQL reports {@code BOOLEAN} as
     * {@code BIT}, which is what Hibernate expects, and pinning it to {@code BOOLEAN} would break
     * {@code ddl-auto=validate}.
     */
    @Column(name = "is_baseline", nullable = false)
    private boolean baseline;

    /**
     * Length of one simulation period — the grid every duration in this network is discretised
     * against. One day unless the user says otherwise, which is what V3 backfilled
     * every pre-existing network to.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value",
                    column = @Column(name = "period_length_value", nullable = false)),
            @AttributeOverride(name = "unit",
                    column = @Column(name = "period_length_unit", nullable = false,
                            columnDefinition = TimeUnit.COLUMN_DEFINITION)),
            @AttributeOverride(name = "seconds",
                    column = @Column(name = "period_length_seconds", nullable = false))
    })
    private DurationAmount periodLength = DurationAmount.of(1, TimeUnit.DAY);

    /** How many periods a run over this network covers; the finite horizon. */
    @Column(name = "horizon_periods", nullable = false)
    private int horizonPeriods = 52;

    /** How a duration that does not divide evenly into {@link #getPeriodLength()} is rounded. */
    @Enumerated(EnumType.STRING)
    @Column(name = "rounding_policy", nullable = false,
            columnDefinition = RoundingPolicy.COLUMN_DEFINITION)
    private RoundingPolicy roundingPolicy = RoundingPolicy.NEAREST;

    @OneToMany(mappedBy = "network", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Node> nodes = new ArrayList<>();

    @OneToMany(mappedBy = "network", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Link> links = new ArrayList<>();

    protected Network() {
        // for JPA
    }

    public Network(Project project, String name, int version, boolean baseline) {
        this.project = project;
        this.name = name;
        this.version = version;
        this.baseline = baseline;
    }

    /**
     * Keeps {@code period_length_seconds} in step with the value and unit.
     *
     * <p>The callback belongs here rather than on {@link DurationAmount} because JPA does not fire
     * lifecycle events on embeddable types — see that class for the full note.
     */
    @PrePersist
    @PreUpdate
    private void refreshDerivedSeconds() {
        DurationAmount.refresh(periodLength);
    }

    /** Adds a node and keeps both sides of the association consistent. */
    public void addNode(Node node) {
        nodes.add(node);
        node.setNetwork(this);
    }

    /** Adds a link and keeps both sides of the association consistent. */
    public void addLink(Link link) {
        links.add(link);
        link.setNetwork(this);
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public boolean isBaseline() {
        return baseline;
    }

    public void setBaseline(boolean baseline) {
        this.baseline = baseline;
    }

    public DurationAmount getPeriodLength() {
        return periodLength;
    }

    public void setPeriodLength(DurationAmount periodLength) {
        this.periodLength = periodLength;
    }

    /**
     * The unit this network's period is stated in — the default unit for a duration or rate the
     * client sends without one.
     *
     * <p>Falls back to {@link TimeUnit#DAY}, which is what V3 backfilled every network to, if the
     * period length is somehow absent; the column is not nullable, so that is a belt-and-braces
     * answer rather than a case that occurs.
     */
    public TimeUnit periodUnit() {
        return periodLength == null || periodLength.getUnit() == null
                ? TimeUnit.DAY
                : periodLength.getUnit();
    }

    public int getHorizonPeriods() {
        return horizonPeriods;
    }

    public void setHorizonPeriods(int horizonPeriods) {
        this.horizonPeriods = horizonPeriods;
    }

    public RoundingPolicy getRoundingPolicy() {
        return roundingPolicy;
    }

    public void setRoundingPolicy(RoundingPolicy roundingPolicy) {
        this.roundingPolicy = roundingPolicy;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public List<Link> getLinks() {
        return links;
    }
}
