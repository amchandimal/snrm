package com.snrm.scenario;

import com.snrm.common.AuditableEntity;
import com.snrm.common.DurationAmount;
import com.snrm.common.TimeUnit;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * One disruption within a scenario: what is struck, when, how hard, and how it recovers
 * ({@code DISRUPTION_EVENT}).
 *
 * <p>{@link #getSeverity()} is a capacity-availability multiplier reduction in [0,1] — 1.0 takes
 * the target fully offline for the duration. {@link #getProbability()} is the chance the event
 * occurs at all in a given replication, so a scenario describes a distribution of futures rather
 * than a single one.
 *
 * <p>{@link #getTargetId()} is a deliberately unconstrained reference: it points at
 * {@code node.id} or {@code link.id} depending on {@link #getTargetType()}, and no foreign key can
 * express that. Resolution and dangling-reference checks belong to scenario validation —
 * {@link DisruptionScenarioService}, which is where the network the event is authored against is
 * known. A {@link DisruptionTargetType#REGION} event names its region in
 * {@link #getTargetRegion()} instead; exactly one of the two halves is set, which
 * {@code ck_event_target} enforces.
 *
 * <p><strong>Timing is absolute, not period-counted (FR-13).</strong> A scenario is scoped
 * to the project so the same events can be replayed against every variant, but variants
 * need not share a period length — an event stated as "period 4" would silently mean a different
 * moment in a network stepping in hours than in one stepping in weeks, and the comparison would be
 * measuring the units. {@link #getStartOffset()} is therefore a real duration from the start of the
 * run and {@link #getDuration()} a real length, each discretised against whichever network the run
 * evaluates.
 */
@Entity
@Table(name = "disruption_event")
public class DisruptionEvent extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id", nullable = false)
    private DisruptionScenario scenario;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false,
            columnDefinition = "ENUM('NODE','LINK','REGION')")
    private DisruptionTargetType targetType;

    /**
     * {@code node.id} or {@code link.id} per {@link #getTargetType()}; null for
     * {@link DisruptionTargetType#REGION}, which names a region instead.
     */
    @Column(name = "target_id")
    private Long targetId;

    /**
     * The {@code node.region} tag a {@link DisruptionTargetType#REGION} event strikes; null for the
     * two id-based types ({@code V5__event_region_target.sql}).
     *
     * <p>Not resolved to a node set here. Which nodes carry the tag is a question about a network,
     * and a project-scoped scenario has none — the same tag names a different set in every
     * configuration variant, which is what makes replaying one scenario across variants worth
     * doing.
     */
    @Column(name = "target_region", length = 60)
    private String targetRegion;

    /**
     * How far into the run the disruption begins, measured from period 0. Replaced the
     * period-counting {@code start_period} column in V3.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value",
                    column = @Column(name = "start_offset_value", nullable = false)),
            @AttributeOverride(name = "unit",
                    column = @Column(name = "start_offset_unit", nullable = false,
                            columnDefinition = TimeUnit.COLUMN_DEFINITION)),
            @AttributeOverride(name = "seconds",
                    column = @Column(name = "start_offset_seconds", nullable = false))
    })
    private DurationAmount startOffset = DurationAmount.zero(TimeUnit.DAY);

    /** How long the disruption lasts; also parameterises the recovery profile. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "value",
                    column = @Column(name = "duration_value", nullable = false)),
            @AttributeOverride(name = "unit",
                    column = @Column(name = "duration_unit", nullable = false,
                            columnDefinition = TimeUnit.COLUMN_DEFINITION)),
            @AttributeOverride(name = "seconds",
                    column = @Column(name = "duration_seconds", nullable = false))
    })
    private DurationAmount duration = DurationAmount.zero(TimeUnit.DAY);

    /** Capacity-availability multiplier reduction in [0,1]. */
    @Column(name = "severity", nullable = false)
    private double severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_profile", nullable = false,
            columnDefinition = "ENUM('STEP','LINEAR','EXPONENTIAL')")
    private RecoveryProfileType recoveryProfile = RecoveryProfileType.STEP;

    /** Probability the event occurs in a given replication; 1.0 makes it deterministic. */
    @Column(name = "probability", nullable = false)
    private double probability = 1.0;

    protected DisruptionEvent() {
        // for JPA
    }

    public DisruptionEvent(DisruptionScenario scenario, DisruptionTargetType targetType, Long targetId,
            String targetRegion, DurationAmount startOffset, DurationAmount duration,
            double severity) {
        this.scenario = scenario;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetRegion = targetRegion;
        this.startOffset = startOffset;
        this.duration = duration;
        this.severity = severity;
    }

    /**
     * A copy of this event under another scenario, for {@code POST /scenarios/{id}/duplicate}.
     *
     * <p>The two {@link DurationAmount}s are copied rather than shared: an embeddable is owned by
     * exactly one entity, and handing the copy the original's instance is how two rows end up
     * editing one timing (see that class).
     */
    DisruptionEvent copyInto(DisruptionScenario target) {
        DisruptionEvent copy = new DisruptionEvent(target, targetType, targetId, targetRegion,
                startOffset.copy(), duration.copy(), severity);
        copy.recoveryProfile = recoveryProfile;
        copy.probability = probability;
        return copy;
    }

    /**
     * Keeps both derived second-counts in step with their values and units; see
     * {@link DurationAmount} on why the callback cannot live on the embeddable.
     */
    @PrePersist
    @PreUpdate
    private void refreshDerivedSeconds() {
        DurationAmount.refresh(startOffset);
        DurationAmount.refresh(duration);
    }

    public Long getId() {
        return id;
    }

    public DisruptionScenario getScenario() {
        return scenario;
    }

    void setScenario(DisruptionScenario scenario) {
        this.scenario = scenario;
    }

    public DisruptionTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(DisruptionTargetType targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getTargetRegion() {
        return targetRegion;
    }

    public void setTargetRegion(String targetRegion) {
        this.targetRegion = targetRegion;
    }

    public DurationAmount getStartOffset() {
        return startOffset;
    }

    public void setStartOffset(DurationAmount startOffset) {
        this.startOffset = startOffset;
    }

    public DurationAmount getDuration() {
        return duration;
    }

    public void setDuration(DurationAmount duration) {
        this.duration = duration;
    }

    public double getSeverity() {
        return severity;
    }

    public void setSeverity(double severity) {
        this.severity = severity;
    }

    public RecoveryProfileType getRecoveryProfile() {
        return recoveryProfile;
    }

    public void setRecoveryProfile(RecoveryProfileType recoveryProfile) {
        this.recoveryProfile = recoveryProfile;
    }

    public double getProbability() {
        return probability;
    }

    public void setProbability(double probability) {
        this.probability = probability;
    }
}
