# Настройки: свойства, порядок разрешения, проверки при старте

> Правила проекта, вынесенные из [CLAUDE.md](../../CLAUDE.md) по теме: настройки, `application.yml`, аннотации настроек на процессоре, проверки при старте.
> Приоритет у них тот же, что у CLAUDE.md; обоснования — в [ADR](../adr/README.md).

## Свойства конфигурации

Префикс — **`taskcomm.*`**, без reverse-domain (так же, как `springdoc.*`, `resilience4j.*`).

Наборы **свои у каждой подсистемы**, общего корня нет ([ADR 0060](../adr/0060-retry-and-lease-configuration.md)):
общий корень стал бы первым шагом к общему коду, который запрещает
[ADR 0010](../adr/0010-separate-engines.md).

```yaml
taskcomm:
  enabled: true
  task:
    enabled: true
    poll: { interval: 1s, batch-size: 100, workers: 8,
            claim-order: deadline, deadline-step: 1m,   # deadline | priority | quota
            fifo-share: 0.1 }               # доля FIFO у quota, строго между 0 и 1
    lease: { duration: 60s, heartbeat-interval: 20s, max-execution-time: 30m }
    retry: { max-attempts: 3, initial-delay: 5s, multiplier: 2.0, max-delay: 1h, jitter: 0.2 }
    types:                          # ключ — taskType
      price-update:
        priority: 50
        lease: { max-execution-time: 30m }
        retry: { max-attempts: 5 }
        phases:                     # PROCESS | EXPAND | COLLECT
          expand:
            lease: { max-execution-time: 2h }
    pollers:                        # дополнительные к основному; не заданное берётся у основного
      urgent: { types: [order-urgent], workers: 4, claim-order: priority }
  communication:
    enabled: true
    poll: { interval: 1s, batch-size: 100, workers: 8,
            claim-order: deadline, deadline-step: 1m }   # deadline | priority | quota
    lease: { duration: 60s, heartbeat-interval: 20s, max-execution-time: 5m }
    retry: { max-attempts: 5, initial-delay: 10s, multiplier: 2.0, max-delay: 1h, jitter: 0.2 }
    response-timeout: 1h            # срок ожидания асинхронного ответа, потом повторная отправка
    circuit-breaker:                # только исходящие; выключен по умолчанию
      enabled: false
      window: 60s
      minimum-calls: 10
      failure-rate-threshold: 0.5
      wait: { initial-delay: 30s, multiplier: 2.0, max-delay: 10m, jitter: 0.2 }
      probe: { calls: 3, timeout: 1m }
    systems:                        # ключ — имя внешней системы
      supplier-b:
        priority: 50
        rate-limit-per-instance: 10
        retry: { max-attempts: 8 }
        response-timeout: 4h
        failure-statuses: [5xx, 429]  # исход попытки, а не только размыкатель
        circuit-breaker: { enabled: true }
    pollers:                        # дополнительные к основному, по именам внешних систем
      supplier-b: { systems: [supplier-b], workers: 2 }
  search:
    remove-undeclared-fields: false
  results:
    discard: after   # never | immediately | after
    discard-after: 7d
  logging:
    mdc-prefix: tc.                 # префикс ключей MDC: tc.executionId, tc.taskId, ...
  metrics:
    interval: 30s                   # такт снимка метрик на всю схему
  rest:
    enabled: false                  # выключен по умолчанию: пути надо закрыть самому
    base-path: /api/taskcomm
    search-window: 30d              # окно чтения истории по времени создания дела
  schema:
    initialize: never               # never | embedded | always; для тестов — always
    partitions:                     # один набор на всю схему
      manage: true                  # false — партиции ведёт потребитель, стартер только проверяет
      period: month                 # day | week | month; выбирается один раз
      create-ahead: 3
      interval: 1h
      drop: { enabled: false, retention: 180d }
```

**Числа здесь — отправная точка, а не рекомендация.** Их надо перепроверить нагрузкой на этапах
3–4; сейчас они выбраны правдоподобно, а не выверенно.

- Все длительности — формат `Duration` Spring Boot (`1s`, `30m`, `7d`).
- Ключ переопределения у задач — `taskType`, у коммуникаций — **имя внешней системы**: по нему же
  ищется процессор. Слова `types` у коммуникаций нет, `communicationType` отвергнут
  ([ADR 0052](../adr/0052-communication-transport-contracts.md)).
- `retry` и `lease` действуют **на все виды попыток** — `PROCESS`, `EXPAND`, `COLLECT`. Раскрытие
  и сборка повторяются под теми же настройками, что обработка. У коммуникаций так же —
  `PROCESS` и `HANDLE_RESPONSE`.
- `poll` и `workers` **не переопределяются по типу задачи**: свой такт и свой пул на тип — это
  отдельная очередь под каждый тип, и заводится она выделенным поллером, а не переопределением.
- **Захват — не больше свободных воркеров пула поллера**, `batch-size` — только верхняя граница
  ([ADR 0083](../adr/0083-claim-limited-by-free-workers.md)). Захваченное сверх воркеров ждало бы
  в памяти: держало аренду от свободных экземпляров, тратило предел обработки и портило
  `started_at`. Захват вернул сколько просили — следующий сразу при освобождении воркера; меньше —
  по такту `poll.interval`. Такт — пауза для пустой очереди, а не потолок пропускной способности.

## Порядок разрешения настроек

Настройки приходят из двух источников: **аннотация на процессоре** — значение по умолчанию
от автора, который знает профиль своей фазы, и **`application.yml`** — переопределение для
эксплуатации ([ADR 0061](../adr/0061-processor-configuration-annotations.md)).

**Пять уровней, от сильного к слабому:**

1. `yaml`: тип и фаза
2. `yaml`: тип
3. аннотация на методе
4. аннотация на классе
5. глобальное значение по умолчанию подсистемы

**Любое значение из `yaml` сильнее любой аннотации** — правило по источнику, а не по специфичности.
Обратный порядок («побеждает более специфичное») отвергнут как ловушка: эксплуатация правила бы
конфиг во время инцидента, а он молча не применялся бы к фазе с аннотацией на методе.

Фазы есть и в `yaml` именно поэтому: иначе переопределение по типу либо не доставало бы до фазы,
либо накрывало все фазы разом — включая `process`, которому положены секунды.

Аннотация задаёт всё, что и `yaml` на уровне типа, **и на классе, и на методе**; метод перекрывает
класс. `poll` и `workers` аннотацией не задаются никогда. **Приоритет — только на уровне типа**:
аннотация на классе и `yaml` типа или системы; на методе и в `phases.*` он молча ничего не делал бы —
приоритет свойство сущности, а не попытки, — поэтому там это падение при старте
([ADR 0084](../adr/0084-priority-only-per-type.md)).

Состав `@TaskProcessorSettings` — девять плоских атрибутов, «не задано» — ноль или пустая строка;
сокращения `@TaskPriority` (только на классе), `@TaskLease`, `@TaskRetry` связаны с ней через
`@AliasFor`; несколько аннотаций на одном месте складываются, один атрибут дважды — падение при
старте ([ADR 0113](../adr/0113-task-processor-settings-attributes.md)).

Аннотаций **две, по одной на подсистему** ([ADR 0066](../adr/0066-processor-settings-annotation-names.md)):
`@TaskProcessorSettings` в `task.api` и `@CommunicationProcessorSettings` в `communication.api`.
Общая на обе подсистемы запрещена тем же правилом, что и общий код.

**Стартер обязан печатать при старте итоговое значение по каждому типу и фазе и уровень, с которого
оно взято.** Это не удобство: пять уровней без такой печати делают вопрос «почему у меня предел
тридцать минут» вопросом без ответа.

## Проверки настроек при старте

Граница проходит по вопросу «способна ли система вообще работать с этим значением».

**Падение при старте:** `max-attempts < 1`; `initial-delay > max-delay`; `multiplier < 1`;
`jitter` вне `(0, 1]` — **включая ноль**, потому что джиттер объявлен обязательным
([ADR 0009](../adr/0009-retry-and-progress.md)) и настройка не должна выключать обязательное;
`max-execution-time < heartbeat-interval`; `heartbeat-interval >= duration` — по каждому итоговому
значению `duration` после разрешения по пяти уровням, то есть по каждому типу или системе и фазе;
сообщение называет тип, фазу, уровень и рекомендуемый интервал
([ADR 0103](../adr/0103-heartbeat-not-shorter-than-lease-fails.md)); `priority <= 0`; приоритет в аннотации на методе или
в `yaml` на уровне фазы ([ADR 0084](../adr/0084-priority-only-per-type.md)); один тип задачи назван в двух поллерах; `fifo-share`
вне `(0, 1)` ([ADR 0115](../adr/0115-quota-fifo-share-by-lottery.md)).
У размыкателя ([ADR 0065](../adr/0065-communication-circuit-breaker.md)): `failure-rate-threshold`
вне `(0, 1]`; `minimum-calls < 1`; `probe.calls < 1`; `window` не положительное; для `wait` — те же
правила, что для `retry`, включая запрет нулевого джиттера. `response-timeout` не положительный
([ADR 0086](../adr/0086-async-response-in-outbound.md)). Аннотация настроек не своей
подсистемы на процессоре — `@TaskProcessorSettings` на процессоре коммуникаций и наоборот
([ADR 0066](../adr/0066-processor-settings-annotation-names.md)). У партиций
([ADR 0080](../adr/0080-partition-configuration.md)): `create-ahead < 1`; `interval`
или `retention` не положительные; `drop.enabled` при `manage: false`; границы существующих
партиций не совпадают с `period` (при `manage: true`); нет партиции на `now()`, и создать её
не удалось. `DataSource` или менеджер транзакций не выбран однозначно: нет помеченного
`@TaskcommDataSource` / `@TaskcommTransactionManager`, кандидатов несколько и ни одного
`@Primary` — в сообщении список кандидатов; у выбранного менеджера виден его `DataSource`,
и это не `DataSource` стартера ([ADR 0094](../adr/0094-creation-always-in-transaction.md)).
Больше одного бина с одним квалификатором точки замены — `@TaskcommPayloadMapper`,
`@TaskcommScheduler`, `@TaskcommWorkerThreadFactory`, `@TaskcommWorkerTaskDecorator`
([ADR 0096](../adr/0096-no-foreign-type-beans.md)). `rest.search-window` не положительный
([ADR 0101](../adr/0101-active-partition-horizon.md)). `metrics.interval` не положительный
([ADR 0102](../adr/0102-schema-wide-metrics-snapshot.md)).

**Предупреждение:** `heartbeat-interval > duration / 3` (но меньше `duration`) — громко
и с рекомендуемым значением, тоже по каждому итоговому `duration`;
`duration <= poll.interval`; `probe.timeout` меньше предела обработки
системы; список исключений размыкателя задан у системы с выключенным размыкателем; запас партиций
меньше `create-ahead` (и на каждом такте, пока не восполнен); `interval` партиций больше одного
периода; `DataSource` выбранного менеджера транзакций не виден (JTA) — громко, с просьбой
проверить соответствие вручную; у планировщика под `@TaskcommScheduler` видно число потоков,
и оно меньше двух — громко: на нём идут heartbeat и такты поллеров, и занятый планировщик
задерживает продление аренды ([ADR 0096](../adr/0096-no-foreign-type-beans.md)).

