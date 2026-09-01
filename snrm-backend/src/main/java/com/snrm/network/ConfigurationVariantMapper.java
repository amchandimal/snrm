package com.snrm.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

/**
 * {@link ConfigurationVariant} → {@link ConfigurationVariantDto}.
 *
 * <p>An abstract class rather than an interface because it needs the application's
 * {@link ObjectMapper}: {@code lever_changes_json} is stored as opaque JSON text so the persistence
 * layer stays agnostic of the lever vocabulary, while the API exposes it as real JSON
 * rather than a string containing JSON. {@link #toJsonNode(String)} is picked up automatically as
 * the {@code String → JsonNode} conversion; {@link #toJsonString(JsonNode)} is the write direction,
 * called by {@link NetworkService} when a clone is requested.
 *
 * <p>The cloned network arrives as an already-mapped {@link NetworkDto} because building one needs
 * the {@code editable} flag from {@link NetworkMutationGuard}, which a mapper has no business
 * querying.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public abstract class ConfigurationVariantMapper {

    @Autowired
    protected ObjectMapper objectMapper;

    @Mapping(target = "id", source = "variant.id")
    @Mapping(target = "projectId", source = "variant.project.id")
    @Mapping(target = "baseNetworkId", source = "variant.baseNetwork.id")
    @Mapping(target = "network", source = "network")
    @Mapping(target = "generatedBy", source = "variant.generatedBy")
    @Mapping(target = "leverChanges", source = "variant.leverChangesJson")
    @Mapping(target = "createdAt", source = "variant.createdAt")
    @Mapping(target = "updatedAt", source = "variant.updatedAt")
    public abstract ConfigurationVariantDto toDto(ConfigurationVariant variant, NetworkDto network);

    /**
     * Parses the stored diff back into JSON for the response.
     *
     * <p>Unparseable text is returned as null rather than thrown: the column is written only by
     * this application through {@link #toJsonString}, and MySQL validates {@code JSON} columns on
     * write, so the case is unreachable — but failing a read of a whole variant over a cosmetic
     * field would be the wrong trade.
     */
    protected JsonNode toJsonNode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /**
     * Serialises a client-supplied diff for storage.
     *
     * @return the JSON text, or null when there is no diff to record
     */
    public String toJsonString(JsonNode leverChanges) {
        return leverChanges == null || leverChanges.isNull() ? null : leverChanges.toString();
    }
}
