package com.snrm.metrics;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * {@link MetricResult} → {@link MetricResultDto} (JPA entities never cross the API).
 *
 * <p>Takes {@code scopeName} as a second argument rather than reading it from the entity, because it
 * is not on the entity and should not be: {@code metric_result} stores a {@code scope_id}, and
 * denormalising the element's name into a results table would leave it to go stale the moment a node
 * is renamed. The name comes from the snapshot the value was computed on, which is the only place
 * that knows it (see {@link MetricValue#scopeName()}).
 *
 * <p>No write direction. A metric row is produced by a calculator, never posted by a client.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MetricResultMapper {

    @Mapping(target = "id", source = "result.id")
    @Mapping(target = "networkId", source = "result.network.id")
    @Mapping(target = "runId", source = "result.run.id")
    @Mapping(target = "metricCode", source = "result.metricCode")
    @Mapping(target = "scope", source = "result.scope")
    @Mapping(target = "scopeId", source = "result.scopeId")
    @Mapping(target = "scopeName", source = "scopeName")
    @Mapping(target = "value", source = "result.value")
    @Mapping(target = "ciLow", source = "result.ciLow")
    @Mapping(target = "ciHigh", source = "result.ciHigh")
    @Mapping(target = "displayUnit", source = "result.displayUnit")
    MetricResultDto toDto(MetricResult result, String scopeName);
}
