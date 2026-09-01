package com.snrm.network;

import com.snrm.common.TimeValueMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * {@link Network} → {@link NetworkDto} (JPA entities never cross the API).
 *
 * <p>Takes the {@code editable} flag as a second argument rather than reading it from the entity,
 * because it is not on the entity: it is the answer {@link NetworkMutationGuard} gives for this
 * network right now. Passing it in keeps the mapper free of the repository query the
 * guard runs, which is what lets a caller that already knows the answer avoid asking twice.
 *
 * <p>No write direction. Creating a network assigns a version number and cloning copies a whole
 * object graph — neither is a field-by-field copy, and both live in {@link NetworkService}.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = TimeValueMapper.class)
public interface NetworkMapper {

    @Mapping(target = "id", source = "network.id")
    @Mapping(target = "projectId", source = "network.project.id")
    @Mapping(target = "name", source = "network.name")
    @Mapping(target = "version", source = "network.version")
    @Mapping(target = "baseline", source = "network.baseline")
    @Mapping(target = "editable", source = "editable")
    @Mapping(target = "periodLength", source = "network.periodLength")
    @Mapping(target = "horizonPeriods", source = "network.horizonPeriods")
    @Mapping(target = "roundingPolicy", source = "network.roundingPolicy")
    @Mapping(target = "createdAt", source = "network.createdAt")
    @Mapping(target = "updatedAt", source = "network.updatedAt")
    NetworkDto toDto(Network network, boolean editable);
}
