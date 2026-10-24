package xyz.avasilev.taskcomm.execution.internal;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import xyz.avasilev.taskcomm.execution.api.Execution;
import xyz.avasilev.taskcomm.execution.api.ExecutionService;

/**
 * {@link ExecutionService} поверх {@link ExecutionRepository}. Тонкий по решению
 * docs/adr/0110-execution-composition-reads-in-subsystems.md: только находит дело, состав дела
 * читают сервисы подсистем.
 */
@RequiredArgsConstructor
public class DefaultExecutionService implements ExecutionService {

    private final ExecutionRepository executions;

    @Override
    public Optional<Execution> find(final UUID executionId) {
        return this.executions.find(executionId);
    }
}
