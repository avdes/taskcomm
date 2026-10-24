package xyz.avasilev.taskcomm.schema.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;

/**
 * Синхронная проверка и подготовка схемы при создании контекста: сначала схема соответствует
 * версии, затем партиции на текущий период и граница активных партиций. Всё, что работает с
 * таблицами стартера — такт партиций, поллеры, — создаётся после этого бина.
 *
 * <p>Бин объявляется зависящим от инициализации базы: {@code taskcomm.schema.initialize},
 * {@code spring.sql.init} и Liquibase потребителя успевают отработать до проверки.
 */
@RequiredArgsConstructor
public class SchemaStartup implements InitializingBean {

    private final SchemaVerifier verifier;
    private final PartitionMaintenance maintenance;

    @Override
    public void afterPropertiesSet() {
        this.verifier.verify();
        this.maintenance.startup();
    }
}
