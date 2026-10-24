package xyz.avasilev.taskcomm.task.internal;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Основной поллер задач и выделенные (docs/adr/0063-claim-ordering-and-pollers.md): какой поллер
 * отвечает за тип задачи. Основной выделенных типов не берёт. Тип, названный в двух выделенных
 * поллерах, — падение при старте.
 *
 * @param main основной поллер
 * @param dedicated выделенные поллеры
 */
public record PollerLayout(PollerSettings main, List<PollerSettings> dedicated) {

    /**
     * Проверяет раскладку и копирует список.
     *
     * @throws TaskcommConfigurationException если тип назван в двух поллерах или основной поллер
     *     оказался в списке выделенных
     */
    public PollerLayout {
        Objects.requireNonNull(main, "main");
        dedicated = List.copyOf(dedicated);
        if (!PollerSettings.MAIN.equals(main.name())) {
            throw new TaskcommConfigurationException("Основной поллер задач обязан называться " + PollerSettings.MAIN);
        }
        final Map<String, String> owners = new HashMap<>();
        for (final PollerSettings poller : dedicated) {
            if (PollerSettings.MAIN.equals(poller.name())) {
                throw new TaskcommConfigurationException(
                        "Имя " + PollerSettings.MAIN + " занято основным поллером задач, выделенному оно не годится.");
            }
            for (final String type : poller.types()) {
                final String previous = owners.putIfAbsent(type, poller.name());
                if (previous != null) {
                    throw new TaskcommConfigurationException("Тип задачи '" + type + "' назван в двух поллерах: '"
                            + previous + "' и '" + poller.name() + "'. Тип обслуживает ровно один поллер.");
                }
            }
        }
    }

    /**
     * Поллер, отвечающий за тип задачи.
     *
     * @param taskType тип задачи
     * @return выделенный поллер этого типа, иначе основной
     */
    public PollerSettings pollerOf(final String taskType) {
        return this.dedicated.stream()
                .filter(poller -> poller.types().contains(taskType))
                .findFirst()
                .orElse(this.main);
    }

    /**
     * Шаг дедлайна, по которому считается {@code claim_order_at} строк этого типа: шаг поллера,
     * который их захватывает.
     *
     * @param taskType тип задачи
     * @return шаг дедлайна
     */
    public Duration deadlineStep(final String taskType) {
        return this.pollerOf(taskType).deadlineStep();
    }

    /**
     * Какие из известных типов захватывает поллер: выделенный — свои, основной — все прочие.
     *
     * @param poller поллер из этой раскладки
     * @param known типы, для которых есть процессор
     * @return типы, которые поллер вправе захватывать
     */
    public Set<String> claimableTypes(final PollerSettings poller, final Set<String> known) {
        final Set<String> result = new HashSet<>(known);
        if (PollerSettings.MAIN.equals(poller.name())) {
            this.dedicated.forEach(other -> result.removeAll(other.types()));
        } else {
            result.retainAll(poller.types());
        }
        return Set.copyOf(result);
    }
}
