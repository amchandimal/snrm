package com.snrm.scenario;

import com.snrm.common.TimeValueMapper;
import com.snrm.common.TimeValues;
import org.mapstruct.BeanMapping;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

import java.util.List;
import java.util.Objects;

/**
 * {@link DisruptionScenario} and {@link DisruptionEvent} ↔ DTO mapping.
 *
 * <p>One mapper for both because they are one aggregate: a scenario read carries its events, and no
 * caller wants an event without knowing whose it is.
 *
 * <p><strong>Two read directions for a scenario</strong>, differing only in whether the events come
 * with it. {@link #toSummaryDto} is the sidebar list, which wants a row per scenario and
 * not every bar of every timeline; {@link #toDto} is the single-scenario read the timeline is built
 * from. Both fill {@code eventCount}, so a list row and the scenario it opens agree about how many
 * bars there are.
 *
 * <p><strong>The write direction never touches the target or the timing.</strong> Everything about
 * <em>what</em> an event strikes and <em>when</em> is resolved against a network by
 * {@link DisruptionScenarioService} before this mapper runs — a mapper that could set
 * {@code targetId} would route around the check that the id belongs to that network, and one that
 * could set {@code startOffset} would route around the horizon check. What is left for
 * {@link #applyAttributes} is the three numbers that need no context: severity, recovery profile and
 * probability. The service writes the durations through {@link #applyTiming} once the pair has
 * passed.
 *
 * <p>A scenario's own three fields are set by the service rather than mapped, for the same kind of
 * reason: the name is trimmed and checked against {@code uq_scenario}, an omitted replication count
 * means the 100 rather than nothing, and a null seed is a value ("draw one per run")
 * rather than an omission. None of the three is a copy.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = TimeValueMapper.class)
public interface DisruptionScenarioMapper {

    // ------------------------------------------------------------------- scenarios

    /**
     * A scenario with its events, in timeline order.
     *
     * <p>The order is the entity's: {@link DisruptionScenario} sorts its collection by
     * {@code startOffset.seconds}, the derived column — the only ordering that is correct once two
     * events in one scenario are written in different units.
     */
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "eventCount", expression = "java(scenario.getEvents().size())")
    DisruptionScenarioDto toDto(DisruptionScenario scenario);

    /** A scenario without its events, for the list. */
    @Named("summary")
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "eventCount", expression = "java(scenario.getEvents().size())")
    @Mapping(target = "events", ignore = true)
    DisruptionScenarioDto toSummaryDto(DisruptionScenario scenario);

    @IterableMapping(qualifiedByName = "summary")
    List<DisruptionScenarioDto> toSummaryDtoList(List<DisruptionScenario> scenarios);

    // ---------------------------------------------------------------------- events

    @Mapping(target = "scenarioId", source = "scenario.id")
    DisruptionEventDto toDto(DisruptionEvent event);

    List<DisruptionEventDto> toEventDtoList(List<DisruptionEvent> events);

    /**
     * The context-free attributes of an event: severity, recovery profile, probability.
     *
     * <p>{@code IGNORE} rather than {@code SET_TO_NULL} for the two optional ones — an omitted
     * {@code recoveryProfile} or {@code probability} keeps what the entity has, which on a create is
     * the field default (STEP, 1.0) and on a replace is the previous value. Both columns are
     * {@code NOT NULL}, so clearing them is not something this API can express; {@code severity} is
     * required and always present.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
            unmappedTargetPolicy = ReportingPolicy.IGNORE)
    @Mapping(target = "targetType", ignore = true)
    @Mapping(target = "targetId", ignore = true)
    @Mapping(target = "targetRegion", ignore = true)
    @Mapping(target = "startOffset", ignore = true)
    @Mapping(target = "duration", ignore = true)
    void applyAttributes(DisruptionEventRequest request, @MappingTarget DisruptionEvent event);

    /**
     * The unit-bearing pair, written into the entity's own embeddables.
     *
     * <p>{@link TimeValues#patch} rather than {@code replace}: both fields are {@code @NotNull} on
     * the request — a bar with no position and no length is not a partially specified event, it is
     * not an event — so the "omitted means the field's empty form in the network's period unit" rule
     * of {@link TimeValues} has nothing to apply to, and there is no fallback unit to invent.
     * Writing into the existing instance rather than replacing it is what keeps one embeddable owned
     * by one row.
     *
     * <p>Called by the service after the window has passed the horizon check, rather than from an
     * {@code @AfterMapping} hook, so a rejected event never reaches the entity at all.
     */
    default void applyTiming(DisruptionEventRequest request, DisruptionEvent event) {
        Objects.requireNonNull(request.startOffset(), "startOffset");
        Objects.requireNonNull(request.duration(), "duration");
        event.setStartOffset(TimeValues.patch(event.getStartOffset(), request.startOffset()));
        event.setDuration(TimeValues.patch(event.getDuration(), request.duration()));
    }
}
