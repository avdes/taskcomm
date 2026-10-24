package xyz.avasilev.taskcomm.execution.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Операции над самим делом. Состав дела — задачи и коммуникации — читается сервисами подсистем:
 * агрегат пассивен, и сервис дела не знает их типов
 * (docs/adr/0110-execution-composition-reads-in-subsystems.md).
 */
public interface ExecutionService {

    /**
     * Находит дело по идентификатору — например, взятому из лога ({@code tc.executionId}), чтобы
     * получить {@link ExecutionRef} для создания или чтения.
     *
     * <p>Ключа партиционирования в запросе нет, поэтому поиск обходит все партиции таблицы дел.
     * Для разбора и редких вызовов это приемлемо; в горячем пути держите {@link ExecutionRef}
     * у себя.
     *
     * @param executionId идентификатор дела
     * @return дело или пусто, если его нет — например, оно удалено вместе с партицией
     */
    Optional<Execution> find(UUID executionId);
}
