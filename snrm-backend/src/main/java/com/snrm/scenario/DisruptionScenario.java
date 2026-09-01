package com.snrm.scenario;

import com.snrm.common.AuditableEntity;
import com.snrm.project.Project;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * A named set of disruption events plus the Monte Carlo settings used to evaluate them
 * ({@code DISRUPTION_SCENARIO}).
 *
 * <p>Scoped to the project rather than to a network, so the same scenario can be replayed against
 * every configuration variant — which is what makes the comparison view meaningful.
 */
@Entity
@Table(name = "disruption_scenario")
public class DisruptionScenario extends AuditableEntity {

    /** Default replication count. */
    public static final int DEFAULT_REPLICATIONS = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "num_replications", nullable = false)
    private int numReplications = DEFAULT_REPLICATIONS;

    /**
     * Base RNG seed. {@code null} means "draw a fresh seed per run"; either way the seed actually
     * used is recorded in {@code simulation_run.params_json}, which is what reproducibility
     * depends on.
     */
    @Column(name = "seed")
    private Long seed;

    // Ordered by the derived second-count rather than by the stated value, which is the only
    // ordering that is correct across events written in different units. Over
    // ix_event_window.
    @OneToMany(mappedBy = "scenario", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startOffset.seconds ASC")
    private List<DisruptionEvent> events = new ArrayList<>();

    protected DisruptionScenario() {
        // for JPA
    }

    public DisruptionScenario(Project project, String name) {
        this.project = project;
        this.name = name;
    }

    /** Adds an event and keeps both sides of the association consistent. */
    public void addEvent(DisruptionEvent event) {
        events.add(event);
        event.setScenario(this);
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

    public int getNumReplications() {
        return numReplications;
    }

    public void setNumReplications(int numReplications) {
        this.numReplications = numReplications;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public List<DisruptionEvent> getEvents() {
        return events;
    }
}
