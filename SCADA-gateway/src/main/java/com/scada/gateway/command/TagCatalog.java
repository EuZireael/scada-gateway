package com.scada.gateway.command;

import com.scada.gateway.model.entity.TagEntity;

/**
 * Порт «справочник тегов» для {@link CommandService} (инверсия зависимостей): команда
 * находит тег по внутреннему id или по имени канала. Живыми кэшами тегов владеет
 * OpcUaClientServiceDB (он и реализует этот интерфейс); в тестах подменяется моком.
 * Так CommandService зависит от узкого контракта, а не от god-класса. null = не найден.
 */
public interface TagCatalog {
    TagEntity byId(Long id);
    TagEntity byName(String name);
}
