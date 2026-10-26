--liquibase formatted sql

-- Схема taskcomm 2.0.0 (docs/adr/0109-schema-as-formatted-sql.md).
--
-- Один и тот же файл служит Liquibase-changelog (разметка changeSet'ов — комментарии ниже),
-- голым DDL для других инструментов и скриптом taskcomm.schema.initialize. Выпущенный файл
-- не правится: новая версия схемы — новый файл. Схема в SQL не указывается — таблицы ложатся
-- в схему соединения. Операторы создания идемпотентны, в текстах комментариев нет точки с запятой.
--
-- Партиции здесь не создаются: девять партиционированных таблиц получают партиции от стартера
-- при старте и на такте обслуживания, либо от потребителя при taskcomm.schema.partitions.manage: false.

--changeset taskcomm:2.0.0-01-tc_execution
CREATE TABLE IF NOT EXISTS tc_execution (
    id         uuid        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT tc_execution_pk PRIMARY KEY (created_at, id)
) PARTITION BY RANGE (created_at);

COMMENT ON TABLE tc_execution IS 'Дело: набор связанных задач и коммуникаций вокруг одного повода. Пассивный агрегат без статуса и счётчиков, не завершается и не отменяется';
COMMENT ON COLUMN tc_execution.id IS 'Идентификатор дела, UUIDv7';
COMMENT ON COLUMN tc_execution.created_at IS 'Время создания дела по часам базы. Ключ партиционирования этой таблицы и источник execution_created_at во всех подчинённых таблицах';
COMMENT ON COLUMN tc_execution.updated_at IS 'Время последнего изменения строки. Проставляет приложение, триггеров нет';

--changeset taskcomm:2.0.0-02-tc_task
CREATE TABLE IF NOT EXISTS tc_task (
    id                         uuid        NOT NULL,
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL,
    execution_id               uuid        NOT NULL,
    execution_created_at       timestamptz NOT NULL,
    task_type                  text        NOT NULL,
    payload                    jsonb       NOT NULL,
    status                     text        NOT NULL,
    status_description         text,
    contract_shape             text,
    phase                      text,
    priority                   smallint    NOT NULL DEFAULT 100,
    next_attempt_at            timestamptz NOT NULL,
    claim_order_at             timestamptz NOT NULL,
    retry_cycle_started_at     timestamptz NOT NULL,
    cancel_requested_at        timestamptz,
    child_results_discarded_at timestamptz,
    origin_type                text,
    origin_id                  uuid,
    origin_attempt_id          uuid,
    origin_relation            text,
    CONSTRAINT tc_task_pk PRIMARY KEY (execution_created_at, id),
    CONSTRAINT tc_task_status_check CHECK (status IN (
        'PENDING', 'RUNNING', 'AWAITING_CHILDREN', 'SUCCEEDED', 'WARNING', 'FAILED', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT tc_task_contract_shape_check CHECK (contract_shape IN ('FLAT', 'UNIT', 'BATCH')),
    CONSTRAINT tc_task_phase_check CHECK (phase IN ('PROCESS', 'EXPAND', 'COLLECT')),
    CONSTRAINT tc_task_shape_with_phase_check CHECK ((contract_shape IS NULL) = (phase IS NULL)),
    CONSTRAINT tc_task_priority_check CHECK (priority > 0),
    CONSTRAINT tc_task_origin_type_check CHECK (origin_type IN ('TASK', 'BATCH', 'UNIT', 'COMMUNICATION')),
    CONSTRAINT tc_task_origin_relation_check CHECK (origin_relation IN ('SPAWNED', 'RESPONSE')),
    CONSTRAINT tc_task_origin_together_check CHECK (
        (origin_type IS NULL) = (origin_id IS NULL)
        AND (origin_type IS NULL) = (origin_relation IS NULL)
        AND (origin_attempt_id IS NULL OR origin_type IS NOT NULL))
) PARTITION BY RANGE (execution_created_at);

CREATE INDEX IF NOT EXISTS tc_task_claim_deadline_idx ON tc_task (status, task_type, claim_order_at)
    WHERE status IN ('PENDING', 'RUNNING', 'AWAITING_CHILDREN');
CREATE INDEX IF NOT EXISTS tc_task_claim_priority_idx ON tc_task (status, task_type, priority, next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING', 'AWAITING_CHILDREN');
CREATE INDEX IF NOT EXISTS tc_task_claim_fifo_idx ON tc_task (status, task_type, next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING', 'AWAITING_CHILDREN');
CREATE INDEX IF NOT EXISTS tc_task_execution_idx ON tc_task (execution_id);
CREATE INDEX IF NOT EXISTS tc_task_results_discard_idx ON tc_task (updated_at)
    WHERE child_results_discarded_at IS NULL AND phase = 'COLLECT' AND status IN ('SUCCEEDED', 'WARNING', 'SKIPPED');

COMMENT ON TABLE tc_task IS 'Задача: работа над множеством бизнес-объектов потребителя. Корень иерархии задача, группы, подзадачи. Повтор идёт на той же строке, история захватов лежит в tc_task_attempt';
COMMENT ON COLUMN tc_task.id IS 'Идентификатор задачи, UUIDv7';
COMMENT ON COLUMN tc_task.created_at IS 'Время создания самой задачи. Законно расходится с execution_created_at: задачу можно создать в давно открытом деле';
COMMENT ON COLUMN tc_task.updated_at IS 'Время последнего изменения состояния. Проставляет приложение. Heartbeat его не трогает, а у задачи в AWAITING_CHILDREN оно стоит, пока работают дети, поэтому признаком зависания не служит';
COMMENT ON COLUMN tc_task.execution_id IS 'Дело, которому принадлежит задача. Обязательно: иначе строка лежала бы в партиции дела, не принадлежа ему';
COMMENT ON COLUMN tc_task.execution_created_at IS 'Время создания дела, копия tc_execution.created_at. Ключ партиционирования: части одного дела не разрываются между партициями. Не время чего-либо в самой задаче';
COMMENT ON COLUMN tc_task.task_type IS 'Тип задачи. По нему ищется процессор потребителя. Задачи типа без процессора не захватываются и ждут его возвращения';
COMMENT ON COLUMN tc_task.payload IS 'Параметры задачи в JSON. Java-тип восстанавливается из дженерика процессора, имя класса не хранится';
COMMENT ON COLUMN tc_task.status IS 'Статус задачи целиком. Ждёт повтора или отложена - это PENDING с next_attempt_at в будущем, отдельного статуса нет';
COMMENT ON COLUMN tc_task.status_description IS 'Причина терминального статуса самой задачи - общая для SKIPPED и CANCELLED, отличает отмену движком от отмены человеком. Причина каждой попытки лежит в её строке';
COMMENT ON COLUMN tc_task.contract_shape IS 'Контракт процессора, по которому задачу начали выполнять: FLAT, UNIT или BATCH. Пуст до первого захвата. Не совпал с текущим процессором - задача отменяется';
COMMENT ON COLUMN tc_task.phase IS 'Вид текущей или следующей попытки, у терминальной задачи - последней: PROCESS, EXPAND или COLLECT. Статус отвечает, где задача в цикле попытки, фаза - какая это работа. Пуст до первого захвата, ставится вместе с contract_shape';
COMMENT ON COLUMN tc_task.priority IS 'Приоритет. Шкала по возрастанию: МЕНЬШЕ ЗНАЧИТ ВАЖНЕЕ, единица важнее сотни. Наследуется группами и подзадачами, ручной перезапуск его не меняет';
COMMENT ON COLUMN tc_task.next_attempt_at IS 'Не раньше какого времени задачу можно захватить: время повтора после backoff или отсрочка при постановке. Вместе с отсутствием попыток означает отложенный запуск';
COMMENT ON COLUMN tc_task.claim_order_at IS 'Ключ порядка захвата в форме отметки времени: next_attempt_at плюс priority, умноженный на шаг дедлайна поллера. НЕ время захвата и не время какого-либо события. Пересчитывается везде, где пишется next_attempt_at';
COMMENT ON COLUMN tc_task.retry_cycle_started_at IS 'Начало текущего цикла повторов. Число попыток в лимите считается по строкам tc_task_attempt начиная с этой отметки. Ручной перезапуск сдвигает её на сейчас, попытки при этом не удаляются';
COMMENT ON COLUMN tc_task.cancel_requested_at IS 'Факт, а не статус: здесь вызвали отмену. Ставится только на той сущности, где вызвана отмена, вниз не копируется - потомки читают флаги предков. Не снимается никогда';
COMMENT ON COLUMN tc_task.child_results_discarded_at IS 'Факт удаления результатов детей из tc_task_result после сборки. Помечается на родителе, а не на каждом ребёнке, и снимает результаты детей всех попыток раскрытия. Пуст - результаты не удалялись';
COMMENT ON COLUMN tc_task.origin_type IS 'Указатель на создателя: тип сущности, в обработке которой создана задача - TASK, BATCH, UNIT или COMMUNICATION. Пустое значение - норма: у задач, созданных кодом потребителя вне процессора, указателя нет';
COMMENT ON COLUMN tc_task.origin_id IS 'Указатель на создателя: идентификатор сущности-создателя. Создатель всегда лежит в той же партиции, что и задача. Пустое значение - норма для корневой задачи';
COMMENT ON COLUMN tc_task.origin_attempt_id IS 'Указатель на создателя: попытка создателя, в ходе которой создана задача. Пустое значение - норма для корневой задачи';
COMMENT ON COLUMN tc_task.origin_relation IS 'Указатель на создателя: вид связи. SPAWNED - создана вызовом из процессора. Пустое значение - норма: корневая задача или связь с чужим делом, которая не хранится';

--changeset taskcomm:2.0.0-03-tc_task_batch
CREATE TABLE IF NOT EXISTS tc_task_batch (
    id                         uuid        NOT NULL,
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL,
    execution_id               uuid        NOT NULL,
    execution_created_at       timestamptz NOT NULL,
    task_id                    uuid        NOT NULL,
    parent_attempt_id          uuid        NOT NULL,
    task_type                  text        NOT NULL,
    payload                    jsonb       NOT NULL,
    status                     text        NOT NULL,
    status_description         text,
    phase                      text        NOT NULL,
    priority                   smallint    NOT NULL DEFAULT 100,
    next_attempt_at            timestamptz NOT NULL,
    claim_order_at             timestamptz NOT NULL,
    retry_cycle_started_at     timestamptz NOT NULL,
    cancel_requested_at        timestamptz,
    child_results_discarded_at timestamptz,
    CONSTRAINT tc_task_batch_pk PRIMARY KEY (execution_created_at, id),
    CONSTRAINT tc_task_batch_status_check CHECK (status IN (
        'AWAITING_PARENT', 'PENDING', 'RUNNING', 'AWAITING_CHILDREN', 'SUCCEEDED', 'WARNING', 'FAILED',
        'CANCELLED', 'SKIPPED', 'ABANDONED')),
    CONSTRAINT tc_task_batch_phase_check CHECK (phase IN ('PROCESS', 'EXPAND', 'COLLECT')),
    CONSTRAINT tc_task_batch_priority_check CHECK (priority > 0)
) PARTITION BY RANGE (execution_created_at);

CREATE INDEX IF NOT EXISTS tc_task_batch_claim_deadline_idx ON tc_task_batch (status, task_type, claim_order_at)
    WHERE status IN ('PENDING', 'RUNNING', 'AWAITING_CHILDREN');
CREATE INDEX IF NOT EXISTS tc_task_batch_claim_priority_idx ON tc_task_batch (status, task_type, priority, next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING', 'AWAITING_CHILDREN');
CREATE INDEX IF NOT EXISTS tc_task_batch_claim_fifo_idx ON tc_task_batch (status, task_type, next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING', 'AWAITING_CHILDREN');
CREATE INDEX IF NOT EXISTS tc_task_batch_awaiting_parent_idx ON tc_task_batch (parent_attempt_id)
    WHERE status = 'AWAITING_PARENT';
CREATE INDEX IF NOT EXISTS tc_task_batch_parent_attempt_idx ON tc_task_batch (parent_attempt_id, status);
CREATE INDEX IF NOT EXISTS tc_task_batch_collect_idx ON tc_task_batch (parent_attempt_id, id);
CREATE INDEX IF NOT EXISTS tc_task_batch_execution_idx ON tc_task_batch (execution_id);
CREATE INDEX IF NOT EXISTS tc_task_batch_results_discard_idx ON tc_task_batch (updated_at)
    WHERE child_results_discarded_at IS NULL AND phase = 'COLLECT' AND status IN ('SUCCEEDED', 'WARNING', 'SKIPPED');

COMMENT ON TABLE tc_task_batch IS 'Группа подзадач внутри задачи. Рождается только в раскрытии задачи и принадлежит породившей её попытке раскрытия, а не задаче';
COMMENT ON COLUMN tc_task_batch.id IS 'Идентификатор группы, UUIDv7';
COMMENT ON COLUMN tc_task_batch.created_at IS 'Время создания самой группы. Законно расходится с execution_created_at: группа создаётся раскрытием, иногда спустя долгое время после создания дела';
COMMENT ON COLUMN tc_task_batch.updated_at IS 'Время последнего изменения состояния. Проставляет приложение. Heartbeat его не трогает';
COMMENT ON COLUMN tc_task_batch.execution_id IS 'Дело, которому принадлежит группа, - то же, что у её задачи';
COMMENT ON COLUMN tc_task_batch.execution_created_at IS 'Время создания дела, копия tc_execution.created_at. Ключ партиционирования: группа лежит в партиции своего дела. Не время чего-либо в самой группе';
COMMENT ON COLUMN tc_task_batch.task_id IS 'Задача, к которой относится группа. Денормализована ради публичного API и фильтров REST, признаком принадлежности текущему набору детей не служит - для этого parent_attempt_id';
COMMENT ON COLUMN tc_task_batch.parent_attempt_id IS 'Попытка раскрытия задачи в tc_task_attempt, породившая группу. Дети принадлежат попытке, а не задаче: у задачи может быть несколько успешных раскрытий со своими наборами детей';
COMMENT ON COLUMN tc_task_batch.task_type IS 'Тип задачи, копия tc_task.task_type. Денормализован не для удобства: на нём строятся частичные индексы поиска по нагрузке';
COMMENT ON COLUMN tc_task_batch.payload IS 'Параметры группы в JSON. Собранные при раскрытии данные сюда не кладутся - они материализуются в нагрузку каждой подзадачи';
COMMENT ON COLUMN tc_task_batch.status IS 'Статус группы целиком. AWAITING_PARENT - породившая попытка раскрытия ещё не закрыта успехом, группа не захватывается. ABANDONED - дитя неуспешной попытки раскрытия, работа не начиналась и не начнётся';
COMMENT ON COLUMN tc_task_batch.status_description IS 'Причина терминального статуса самой группы - общая для SKIPPED, CANCELLED и ABANDONED. Причина каждой попытки лежит в её строке';
COMMENT ON COLUMN tc_task_batch.phase IS 'Вид текущей или следующей попытки, у терминальной группы - последней. Группа рождается с EXPAND, после успешного раскрытия - COLLECT';
COMMENT ON COLUMN tc_task_batch.priority IS 'Приоритет, унаследованный от задачи. Шкала по возрастанию: МЕНЬШЕ ЗНАЧИТ ВАЖНЕЕ';
COMMENT ON COLUMN tc_task_batch.next_attempt_at IS 'Не раньше какого времени группу можно захватить: время повтора после backoff или запуска сборки';
COMMENT ON COLUMN tc_task_batch.claim_order_at IS 'Ключ порядка захвата в форме отметки времени: next_attempt_at плюс priority, умноженный на шаг дедлайна поллера. НЕ время захвата и не время какого-либо события';
COMMENT ON COLUMN tc_task_batch.retry_cycle_started_at IS 'Начало текущего цикла повторов. Число попыток в лимите считается по строкам tc_task_attempt начиная с этой отметки. Ручной перезапуск сдвигает её на сейчас';
COMMENT ON COLUMN tc_task_batch.cancel_requested_at IS 'Факт, а не статус: здесь вызвали отмену. Ставится только там, где вызвана отмена, вниз не копируется. Отмену задачи группа узнаёт по флагу задачи, а не по своему';
COMMENT ON COLUMN tc_task_batch.child_results_discarded_at IS 'Факт удаления результатов подзадач группы из tc_task_result после сборки. Помечается на группе, а не на каждой подзадаче. Пуст - результаты не удалялись';

--changeset taskcomm:2.0.0-04-tc_task_unit
CREATE TABLE IF NOT EXISTS tc_task_unit (
    id                     uuid        NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    execution_id           uuid        NOT NULL,
    execution_created_at   timestamptz NOT NULL,
    task_id                uuid        NOT NULL,
    batch_id               uuid,
    parent_attempt_id      uuid        NOT NULL,
    task_type              text        NOT NULL,
    payload                jsonb       NOT NULL,
    status                 text        NOT NULL,
    status_description     text,
    priority               smallint    NOT NULL DEFAULT 100,
    next_attempt_at        timestamptz NOT NULL,
    claim_order_at         timestamptz NOT NULL,
    retry_cycle_started_at timestamptz NOT NULL,
    cancel_requested_at    timestamptz,
    CONSTRAINT tc_task_unit_pk PRIMARY KEY (execution_created_at, id),
    CONSTRAINT tc_task_unit_status_check CHECK (status IN (
        'AWAITING_PARENT', 'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED', 'ABANDONED')),
    CONSTRAINT tc_task_unit_priority_check CHECK (priority > 0)
) PARTITION BY RANGE (execution_created_at);

CREATE INDEX IF NOT EXISTS tc_task_unit_claim_deadline_idx ON tc_task_unit (status, task_type, claim_order_at)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX IF NOT EXISTS tc_task_unit_claim_priority_idx ON tc_task_unit (status, task_type, priority, next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX IF NOT EXISTS tc_task_unit_claim_fifo_idx ON tc_task_unit (status, task_type, next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX IF NOT EXISTS tc_task_unit_awaiting_parent_idx ON tc_task_unit (parent_attempt_id)
    WHERE status = 'AWAITING_PARENT';
CREATE INDEX IF NOT EXISTS tc_task_unit_parent_attempt_idx ON tc_task_unit (parent_attempt_id, status);
CREATE INDEX IF NOT EXISTS tc_task_unit_collect_idx ON tc_task_unit (parent_attempt_id, id);
CREATE INDEX IF NOT EXISTS tc_task_unit_execution_idx ON tc_task_unit (execution_id);

COMMENT ON TABLE tc_task_unit IS 'Подзадача: обработка одного бизнес-объекта. Минимальная единица работы и повтора. Самодостаточна: её повтор не требует ни группы, ни задачи';
COMMENT ON COLUMN tc_task_unit.id IS 'Идентификатор подзадачи, UUIDv7';
COMMENT ON COLUMN tc_task_unit.created_at IS 'Время создания самой подзадачи. Законно расходится с execution_created_at: подзадача создаётся раскрытием, иногда спустя долгое время после создания дела';
COMMENT ON COLUMN tc_task_unit.updated_at IS 'Время последнего изменения состояния. Проставляет приложение. Heartbeat его не трогает';
COMMENT ON COLUMN tc_task_unit.execution_id IS 'Дело, которому принадлежит подзадача, - то же, что у её задачи';
COMMENT ON COLUMN tc_task_unit.execution_created_at IS 'Время создания дела, копия tc_execution.created_at. Ключ партиционирования: подзадача лежит в партиции своего дела. Не время чего-либо в самой подзадаче';
COMMENT ON COLUMN tc_task_unit.task_id IS 'Задача, к которой относится подзадача. Денормализована ради публичного API и фильтров REST, признаком принадлежности текущему набору детей не служит';
COMMENT ON COLUMN tc_task_unit.batch_id IS 'Группа, к которой относится подзадача. Пусто у задач с подзадачами без групп. Денормализована ради публичного API и фильтров REST';
COMMENT ON COLUMN tc_task_unit.parent_attempt_id IS 'Попытка раскрытия в tc_task_attempt, породившая подзадачу, - задачи или группы. Прогресс родителя считается по детям одной попытки раскрытия';
COMMENT ON COLUMN tc_task_unit.task_type IS 'Тип задачи, копия tc_task.task_type. Денормализован не для удобства: на нём строятся частичные индексы поиска по нагрузке';
COMMENT ON COLUMN tc_task_unit.payload IS 'Бизнес-объект подзадачи в JSON, включая данные, собранные при раскрытии группы. Java-тип восстанавливается из дженерика процессора';
COMMENT ON COLUMN tc_task_unit.status IS 'Статус подзадачи. AWAITING_PARENT - породившая попытка раскрытия ещё не закрыта успехом, подзадача не захватывается, но незавершена. ABANDONED - дитя неуспешной попытки раскрытия';
COMMENT ON COLUMN tc_task_unit.status_description IS 'Причина терминального статуса подзадачи - общая для SKIPPED, CANCELLED и ABANDONED. Причина каждой попытки лежит в её строке';
COMMENT ON COLUMN tc_task_unit.priority IS 'Приоритет, унаследованный от родителя. Шкала по возрастанию: МЕНЬШЕ ЗНАЧИТ ВАЖНЕЕ';
COMMENT ON COLUMN tc_task_unit.next_attempt_at IS 'Не раньше какого времени подзадачу можно захватить: время повтора после backoff';
COMMENT ON COLUMN tc_task_unit.claim_order_at IS 'Ключ порядка захвата в форме отметки времени: next_attempt_at плюс priority, умноженный на шаг дедлайна поллера. НЕ время захвата и не время какого-либо события';
COMMENT ON COLUMN tc_task_unit.retry_cycle_started_at IS 'Начало текущего цикла повторов. Число попыток в лимите считается по строкам tc_task_attempt начиная с этой отметки. Ручной перезапуск сдвигает её на сейчас';
COMMENT ON COLUMN tc_task_unit.cancel_requested_at IS 'Факт, а не статус: здесь вызвали отмену. Ставится только там, где вызвана отмена, вниз не копируется. Отмену предков подзадача узнаёт по их флагам';

--changeset taskcomm:2.0.0-05-tc_communication
CREATE TABLE IF NOT EXISTS tc_communication (
    id                     uuid        NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    execution_id           uuid        NOT NULL,
    execution_created_at   timestamptz NOT NULL,
    direction              text        NOT NULL,
    source                 text,
    destination            text,
    request_payload        jsonb,
    contract_shape         text,
    phase                  text        NOT NULL,
    status                 text        NOT NULL,
    status_description     text,
    priority               smallint    NOT NULL DEFAULT 100,
    next_attempt_at        timestamptz NOT NULL,
    claim_order_at         timestamptz NOT NULL,
    retry_cycle_started_at timestamptz NOT NULL,
    cancel_requested_at    timestamptz,
    origin_type            text,
    origin_id              uuid,
    origin_attempt_id      uuid,
    origin_relation        text,
    CONSTRAINT tc_communication_pk PRIMARY KEY (execution_created_at, id),
    CONSTRAINT tc_communication_direction_check CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT tc_communication_external_side_check CHECK (
        (direction = 'INBOUND' AND source IS NOT NULL AND destination IS NULL)
        OR (direction = 'OUTBOUND' AND destination IS NOT NULL AND source IS NULL)),
    CONSTRAINT tc_communication_contract_shape_check CHECK (contract_shape IN ('REST', 'SOAP', 'KAFKA', 'RAW')),
    CONSTRAINT tc_communication_phase_check CHECK (phase IN ('PROCESS', 'HANDLE_RESPONSE')),
    CONSTRAINT tc_communication_status_check CHECK (status IN (
        'PENDING', 'RUNNING', 'AWAITING_RESPONSE', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT tc_communication_priority_check CHECK (priority > 0),
    CONSTRAINT tc_communication_origin_type_check CHECK (origin_type IN ('TASK', 'BATCH', 'UNIT', 'COMMUNICATION')),
    CONSTRAINT tc_communication_origin_relation_check CHECK (origin_relation IN ('SPAWNED', 'RESPONSE')),
    CONSTRAINT tc_communication_origin_together_check CHECK (
        (origin_type IS NULL) = (origin_id IS NULL)
        AND (origin_type IS NULL) = (origin_relation IS NULL)
        AND (origin_attempt_id IS NULL OR origin_type IS NOT NULL))
) PARTITION BY RANGE (execution_created_at);

CREATE INDEX IF NOT EXISTS tc_communication_claim_deadline_idx ON tc_communication (status, claim_order_at)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX IF NOT EXISTS tc_communication_claim_priority_idx ON tc_communication (status, priority, next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX IF NOT EXISTS tc_communication_claim_fifo_idx ON tc_communication (status, next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX IF NOT EXISTS tc_communication_awaiting_response_idx ON tc_communication (next_attempt_at)
    WHERE status = 'AWAITING_RESPONSE';
CREATE INDEX IF NOT EXISTS tc_communication_execution_idx ON tc_communication (execution_id);

COMMENT ON TABLE tc_communication IS 'Коммуникация с внешней системой. Атомарна: на части не делится, повтор применяется к ней целиком. Обмен каждой попытки лежит в tc_communication_attempt';
COMMENT ON COLUMN tc_communication.id IS 'Идентификатор коммуникации, UUIDv7';
COMMENT ON COLUMN tc_communication.created_at IS 'Время создания самой коммуникации. Законно расходится с execution_created_at: коммуникацию можно создать в давно открытом деле';
COMMENT ON COLUMN tc_communication.updated_at IS 'Время последнего изменения состояния. Проставляет приложение. Heartbeat его не трогает';
COMMENT ON COLUMN tc_communication.execution_id IS 'Дело, которому принадлежит коммуникация';
COMMENT ON COLUMN tc_communication.execution_created_at IS 'Время создания дела, копия tc_execution.created_at. Ключ партиционирования: старые партиции остаются записываемыми, ответ через год ляжет в партицию своего дела. Не время чего-либо в самой коммуникации';
COMMENT ON COLUMN tc_communication.direction IS 'Направление: INBOUND - входящая, создана потребителем по сообщению извне, OUTBOUND - исходящая. Определяет, какая из колонок source и destination заполнена';
COMMENT ON COLUMN tc_communication.source IS 'Внешняя система-отправитель входящей коммуникации. По ней ищется процессор. У исходящей пусто';
COMMENT ON COLUMN tc_communication.destination IS 'Внешняя система-получатель исходящей коммуникации. По ней ищется процессор. У входящей пусто';
COMMENT ON COLUMN tc_communication.request_payload IS 'Доменная форма исходного запроса в JSON - у входящей разобранное сообщение, у исходящей то, что нужно отправить. Пусто у входящей с неразбираемым телом, сырое тело лежит в попытке. Не путать с одноимённой колонкой попытки, где лежит колбэк';
COMMENT ON COLUMN tc_communication.contract_shape IS 'Транспортный контракт процессора: REST, SOAP, KAFKA или RAW. У входящей ставится при создании, когда выбран кодек, у исходящей - при первом захвате. Не совпал с текущим процессором - коммуникация отменяется';
COMMENT ON COLUMN tc_communication.phase IS 'Вид текущей или следующей попытки, у терминальной коммуникации - последней: PROCESS - обработка и отправка, HANDLE_RESPONSE - обработка асинхронного ответа. Рождается с PROCESS';
COMMENT ON COLUMN tc_communication.status IS 'Статус коммуникации. AWAITING_RESPONSE - отправка успешна, ждём колбэка, аренды нет и захватывать нечего, но коммуникация незавершена';
COMMENT ON COLUMN tc_communication.status_description IS 'Причина терминального статуса самой коммуникации - общая для SKIPPED и CANCELLED, в том числе дубликат по ключу. Причина каждой попытки лежит в её строке';
COMMENT ON COLUMN tc_communication.priority IS 'Приоритет. Шкала по возрастанию: МЕНЬШЕ ЗНАЧИТ ВАЖНЕЕ, единица важнее сотни';
COMMENT ON COLUMN tc_communication.next_attempt_at IS 'Не раньше какого времени коммуникацию можно захватить. В AWAITING_RESPONSE - срок ожидания ответа: истёк - повторная отправка';
COMMENT ON COLUMN tc_communication.claim_order_at IS 'Ключ порядка захвата в форме отметки времени: next_attempt_at плюс priority, умноженный на шаг дедлайна поллера. НЕ время захвата и не время какого-либо события';
COMMENT ON COLUMN tc_communication.retry_cycle_started_at IS 'Начало текущего цикла повторов. Число попыток в лимите считается по строкам tc_communication_attempt начиная с этой отметки. Сдвигается ручным перезапуском и переходом в HANDLE_RESPONSE';
COMMENT ON COLUMN tc_communication.cancel_requested_at IS 'Факт, а не статус: здесь вызвали отмену. Не снимается никогда';
COMMENT ON COLUMN tc_communication.origin_type IS 'Указатель на создателя: тип сущности, в обработке которой создана коммуникация, - TASK, BATCH, UNIT или COMMUNICATION. Пустое значение - норма: у коммуникаций, созданных кодом потребителя вне процессора, указателя нет';
COMMENT ON COLUMN tc_communication.origin_id IS 'Указатель на создателя: идентификатор сущности-создателя. Создатель всегда лежит в той же партиции. Пустое значение - норма для корневой коммуникации';
COMMENT ON COLUMN tc_communication.origin_attempt_id IS 'Указатель на создателя: попытка создателя, в ходе которой создана коммуникация. Пустое значение - норма для корневой коммуникации и для колбэка, за которым попытки нет';
COMMENT ON COLUMN tc_communication.origin_relation IS 'Указатель на создателя: вид связи. SPAWNED - создана вызовом из процессора, RESPONSE - колбэк, нашедший якорь по ключу корреляции, но не дописанный в него. Пустое значение - норма';

--changeset taskcomm:2.0.0-06-tc_task_attempt
CREATE TABLE IF NOT EXISTS tc_task_attempt (
    id                   uuid        NOT NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    execution_created_at timestamptz NOT NULL,
    level                text        NOT NULL,
    target_id            uuid        NOT NULL,
    phase                text        NOT NULL,
    locked_by            text        NOT NULL,
    locked_until         timestamptz NOT NULL,
    lease_token          uuid        NOT NULL,
    lease_renewals       integer     NOT NULL DEFAULT 0,
    started_at           timestamptz NOT NULL,
    finished_at          timestamptz,
    status               text,
    status_description   text,
    CONSTRAINT tc_task_attempt_pk PRIMARY KEY (execution_created_at, id),
    CONSTRAINT tc_task_attempt_level_check CHECK (level IN ('TASK', 'BATCH', 'UNIT')),
    CONSTRAINT tc_task_attempt_phase_check CHECK (phase IN ('PROCESS', 'EXPAND', 'COLLECT')),
    CONSTRAINT tc_task_attempt_status_check CHECK (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED', 'LEASE_LOST')),
    CONSTRAINT tc_task_attempt_finished_with_status_check CHECK ((finished_at IS NULL) = (status IS NULL)),
    CONSTRAINT tc_task_attempt_lease_renewals_check CHECK (lease_renewals >= 0)
) PARTITION BY RANGE (execution_created_at);

CREATE UNIQUE INDEX IF NOT EXISTS tc_task_attempt_open_uq ON tc_task_attempt (execution_created_at, target_id)
    WHERE finished_at IS NULL;
CREATE INDEX IF NOT EXISTS tc_task_attempt_expired_lease_idx ON tc_task_attempt (locked_until)
    WHERE finished_at IS NULL;
CREATE INDEX IF NOT EXISTS tc_task_attempt_target_idx ON tc_task_attempt (level, target_id, started_at);

COMMENT ON TABLE tc_task_attempt IS 'Попытки задач, групп и подзадач - по строке на каждый захват, успешный или нет. Не журнал, а узел иерархии: здесь живут аренда и дети раскрытия. Строка вставляется при захвате';
COMMENT ON COLUMN tc_task_attempt.id IS 'Идентификатор попытки, UUIDv7. На него ссылаются parent_attempt_id детей раскрытия и tc_task_result.attempt_id';
COMMENT ON COLUMN tc_task_attempt.created_at IS 'Время вставки строки - совпадает со started_at, потому что строка вставляется при захвате';
COMMENT ON COLUMN tc_task_attempt.updated_at IS 'Время последнего изменения состояния попытки. Проставляет приложение. Продление аренды его не трогает';
COMMENT ON COLUMN tc_task_attempt.execution_created_at IS 'Время создания дела сущности, копия её execution_created_at. Ключ партиционирования: попытки дропаются вместе с партицией своего дела. Не время самой попытки';
COMMENT ON COLUMN tc_task_attempt.level IS 'Уровень сущности, чья это попытка: TASK, BATCH или UNIT. Вместе с target_id указывает на строку tc_task, tc_task_batch или tc_task_unit';
COMMENT ON COLUMN tc_task_attempt.target_id IS 'Идентификатор сущности, чья это попытка. Таблица определяется по level';
COMMENT ON COLUMN tc_task_attempt.phase IS 'Вид попытки: PROCESS - обработка, EXPAND - раскрытие, COLLECT - сборка. Читается так же, как имя метода процессора';
COMMENT ON COLUMN tc_task_attempt.locked_by IS 'Экземпляр приложения, захвативший работу. Для проверки владения не используется - для этого lease_token';
COMMENT ON COLUMN tc_task_attempt.locked_until IS 'До какого времени действует аренда. Продлевается heartbeat. Истекла у открытой попытки - переподхват закрывает её исходом LEASE_LOST';
COMMENT ON COLUMN tc_task_attempt.lease_token IS 'Токен аренды, новый на каждый захват. Входит в условие продления и завершения вместе с finished_at IS NULL: одного токена мало, опоздавший воркер пишет в свою прежнюю строку';
COMMENT ON COLUMN tc_task_attempt.lease_renewals IS 'Сколько раз аренда продлена. Предел обработки задан временем, а внутри считается этим счётчиком: дошли до предела - продление прекращается. Новая попытка - новая строка, обнулять не нужно';
COMMENT ON COLUMN tc_task_attempt.started_at IS 'Время захвата, то есть начала попытки. По наибольшему started_at выбирается последняя попытка и текущий результат сущности';
COMMENT ON COLUMN tc_task_attempt.finished_at IS 'Время закрытия попытки. Пусто - попытка открыта, второй открытой у сущности быть не может';
COMMENT ON COLUMN tc_task_attempt.status IS 'Исход попытки: SUCCEEDED, FAILED, SKIPPED или LEASE_LOST - аренда истекла и попытку закрыл переподхват. Пусто, пока попытка открыта. Не путать со статусом сущности';
COMMENT ON COLUMN tc_task_attempt.status_description IS 'Причина исхода этой попытки: текст исключения, причина пропуска, превышен предел обработки или экземпляр не продлил аренду';

--changeset taskcomm:2.0.0-07-tc_communication_attempt
CREATE TABLE IF NOT EXISTS tc_communication_attempt (
    id                   uuid        NOT NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    execution_created_at timestamptz NOT NULL,
    communication_id     uuid        NOT NULL,
    phase                text        NOT NULL,
    locked_by            text,
    locked_until         timestamptz,
    lease_token          uuid,
    lease_renewals       integer     NOT NULL DEFAULT 0,
    started_at           timestamptz,
    finished_at          timestamptz,
    status               text,
    status_description   text,
    request_id           text,
    request_at           timestamptz,
    request_headers      jsonb,
    request_body         text,
    request_payload      jsonb,
    response_id          text,
    response_at          timestamptz,
    response_status      smallint,
    response_headers     jsonb,
    response_body        text,
    response_payload     jsonb,
    CONSTRAINT tc_communication_attempt_pk PRIMARY KEY (execution_created_at, id),
    CONSTRAINT tc_communication_attempt_phase_check CHECK (phase IN ('PROCESS', 'HANDLE_RESPONSE')),
    CONSTRAINT tc_communication_attempt_status_check CHECK (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED', 'LEASE_LOST')),
    CONSTRAINT tc_communication_attempt_finished_with_status_check CHECK ((finished_at IS NULL) = (status IS NULL)),
    CONSTRAINT tc_communication_attempt_lease_together_check CHECK (
        (locked_by IS NULL) = (lease_token IS NULL) AND (locked_by IS NULL) = (locked_until IS NULL)),
    CONSTRAINT tc_communication_attempt_lease_renewals_check CHECK (lease_renewals >= 0)
) PARTITION BY RANGE (execution_created_at);

CREATE UNIQUE INDEX IF NOT EXISTS tc_communication_attempt_open_uq
    ON tc_communication_attempt (execution_created_at, communication_id, phase)
    WHERE finished_at IS NULL;
CREATE INDEX IF NOT EXISTS tc_communication_attempt_communication_idx ON tc_communication_attempt (communication_id);
CREATE INDEX IF NOT EXISTS tc_communication_attempt_request_id_idx ON tc_communication_attempt (request_id);

COMMENT ON TABLE tc_communication_attempt IS 'Попытки коммуникаций вместе с обменом. Строка создаётся заранее, открытой и незахваченной, - вместе с коммуникацией или при закрытии предыдущей неуспешной. Захват проставляет в неё аренду';
COMMENT ON COLUMN tc_communication_attempt.id IS 'Идентификатор попытки, UUIDv7';
COMMENT ON COLUMN tc_communication_attempt.created_at IS 'Время создания строки. Не время захвата: строка создаётся заранее, захват отмечен в started_at';
COMMENT ON COLUMN tc_communication_attempt.updated_at IS 'Время последнего изменения состояния попытки. Проставляет приложение. Продление аренды его не трогает';
COMMENT ON COLUMN tc_communication_attempt.execution_created_at IS 'Время создания дела коммуникации, копия её execution_created_at. Ключ партиционирования: попытки дропаются вместе с партицией своего дела. Не время самой попытки';
COMMENT ON COLUMN tc_communication_attempt.communication_id IS 'Коммуникация, чья это попытка';
COMMENT ON COLUMN tc_communication_attempt.phase IS 'Вид попытки: PROCESS - обработка и отправка, HANDLE_RESPONSE - обработка асинхронного ответа. Открытых попыток не больше одной на фазу';
COMMENT ON COLUMN tc_communication_attempt.locked_by IS 'Экземпляр приложения, захвативший попытку. Пусто, пока попытку не захватили. Для проверки владения не используется - для этого lease_token';
COMMENT ON COLUMN tc_communication_attempt.locked_until IS 'До какого времени действует аренда. Пусто, пока попытку не захватили. Истекла - попытка закрывается исходом LEASE_LOST и новую аренду не получает';
COMMENT ON COLUMN tc_communication_attempt.lease_token IS 'Токен аренды, проставляется при захвате. Входит в условие продления и завершения вместе с finished_at IS NULL';
COMMENT ON COLUMN tc_communication_attempt.lease_renewals IS 'Сколько раз аренда продлена. Предел обработки задан временем, а внутри считается этим счётчиком';
COMMENT ON COLUMN tc_communication_attempt.started_at IS 'Время захвата, то есть начала работы над попыткой. Пусто, пока попытку не захватили';
COMMENT ON COLUMN tc_communication_attempt.finished_at IS 'Время закрытия попытки. Пусто - попытка открыта';
COMMENT ON COLUMN tc_communication_attempt.status IS 'Исход попытки: SUCCEEDED, FAILED, SKIPPED или LEASE_LOST - аренда истекла. Пусто, пока попытка открыта. Отказной статус ответа делает попытку FAILED, даже если процессор вернулся нормально';
COMMENT ON COLUMN tc_communication_attempt.status_description IS 'Причина исхода этой попытки: текст исключения, отказной ответ, дубликат, превышен предел обработки';
COMMENT ON COLUMN tc_communication_attempt.request_id IS 'Идентификатор запроса в протоколе внешней системы. Смысл зависит от направления: у входящей и в попытке HANDLE_RESPONSE запрос - то, что прислали нам, у исходящей - то, что отправили мы. Индекс - для разбора и REST, не для захвата';
COMMENT ON COLUMN tc_communication_attempt.request_at IS 'Время запроса, как оно стоит в самом сообщении. Не время захвата: между захватом и отправкой процессор строит запрос и ждёт ограничитель скорости';
COMMENT ON COLUMN tc_communication_attempt.request_headers IS 'Заголовки запроса, пары ключ-значение';
COMMENT ON COLUMN tc_communication_attempt.request_body IS 'Сырое тело запроса как есть, текстом. Двоичные форматы лежат в base64 - это не ошибка кодировки. У повторов входящей и HANDLE_RESPONSE копируется из прежней попытки';
COMMENT ON COLUMN tc_communication_attempt.request_payload IS 'Доменная форма колбэка в JSON. Заполнена только в попытках HANDLE_RESPONSE. Не путать с tc_communication.request_payload - там исходный запрос';
COMMENT ON COLUMN tc_communication_attempt.response_id IS 'Идентификатор ответа, по нему ответ ищут в логах внешней системы. У входящей и в HANDLE_RESPONSE ответ - то, что ответили мы, у исходящей - то, что ответили нам. Пустая половина ответа - нормальное состояние';
COMMENT ON COLUMN tc_communication_attempt.response_at IS 'Время ответного сообщения, как оно стоит в самом сообщении. Не время закрытия попытки';
COMMENT ON COLUMN tc_communication_attempt.response_status IS 'Код ответа. Осмыслен только у REST и SOAP, у брокеров пуст';
COMMENT ON COLUMN tc_communication_attempt.response_headers IS 'Заголовки ответа, пары ключ-значение';
COMMENT ON COLUMN tc_communication_attempt.response_body IS 'Сырое тело ответа как есть, текстом: внешняя система отвечает чем угодно, от JSON до HTML балансировщика. Двоичные форматы лежат в base64';
COMMENT ON COLUMN tc_communication_attempt.response_payload IS 'Доменная форма синхронного ответа в JSON. Пусто, если ответа нет или он не разобран';

--changeset taskcomm:2.0.0-08-tc_task_result
CREATE TABLE IF NOT EXISTS tc_task_result (
    id                   uuid        NOT NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    execution_created_at timestamptz NOT NULL,
    attempt_id           uuid        NOT NULL,
    level                text        NOT NULL,
    target_id            uuid        NOT NULL,
    result               jsonb       NOT NULL,
    CONSTRAINT tc_task_result_pk PRIMARY KEY (execution_created_at, id),
    CONSTRAINT tc_task_result_level_check CHECK (level IN ('TASK', 'BATCH', 'UNIT'))
) PARTITION BY RANGE (execution_created_at);

CREATE UNIQUE INDEX IF NOT EXISTS tc_task_result_attempt_uq ON tc_task_result (execution_created_at, attempt_id);
CREATE INDEX IF NOT EXISTS tc_task_result_target_idx ON tc_task_result (execution_created_at, level, target_id);

COMMENT ON TABLE tc_task_result IS 'Результаты, которые вернули process и collect, - строка на попытку. Пишется, только если метод вернулся нормально и вернул не null. Единственная партиционированная таблица со своей очисткой по taskcomm.results.discard';
COMMENT ON COLUMN tc_task_result.id IS 'Идентификатор строки результата, UUIDv7';
COMMENT ON COLUMN tc_task_result.created_at IS 'Время записи результата - в той же транзакции, что закрытие попытки';
COMMENT ON COLUMN tc_task_result.updated_at IS 'Время последнего изменения строки. Проставляет приложение';
COMMENT ON COLUMN tc_task_result.execution_created_at IS 'Время создания дела сущности. Ключ партиционирования: результат дропается вместе с партицией своего дела. Не время самого результата';
COMMENT ON COLUMN tc_task_result.attempt_id IS 'Попытка в tc_task_attempt, вернувшая результат. Одна строка на попытку';
COMMENT ON COLUMN tc_task_result.level IS 'Уровень сущности: TASK, BATCH или UNIT. Денормализован из попытки, чтобы сборка не соединялась с tc_task_attempt на миллионе подзадач';
COMMENT ON COLUMN tc_task_result.target_id IS 'Идентификатор сущности. Денормализован из попытки по той же причине, что level. Текущий результат сущности - результат её последней попытки по started_at, если та успешна';
COMMENT ON COLUMN tc_task_result.result IS 'Результат в JSON. Не бывает пустым: отсутствие результата выражается отсутствием строки. Java-тип восстанавливается из дженерика процессора';

--changeset taskcomm:2.0.0-09-tc_communication_dedup
CREATE TABLE IF NOT EXISTS tc_communication_dedup (
    id                   uuid        NOT NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    execution_created_at timestamptz NOT NULL,
    communication_id     uuid        NOT NULL,
    direction            text        NOT NULL,
    external_system      text        NOT NULL,
    subject              text,
    dedup_key            text        NOT NULL,
    finished_at          timestamptz,
    succeeded_at         timestamptz,
    CONSTRAINT tc_communication_dedup_pk PRIMARY KEY (execution_created_at, id),
    CONSTRAINT tc_communication_dedup_direction_check CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT tc_communication_dedup_succeeded_finished_check CHECK (succeeded_at IS NULL OR finished_at IS NOT NULL)
) PARTITION BY RANGE (execution_created_at);

CREATE INDEX IF NOT EXISTS tc_communication_dedup_history_idx
    ON tc_communication_dedup (direction, external_system, dedup_key)
    WHERE subject IS NULL;
CREATE INDEX IF NOT EXISTS tc_communication_dedup_last_succeeded_idx
    ON tc_communication_dedup (direction, external_system, subject, succeeded_at)
    WHERE succeeded_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS tc_communication_dedup_unfinished_idx
    ON tc_communication_dedup (direction, external_system, subject, dedup_key)
    WHERE finished_at IS NULL;

COMMENT ON TABLE tc_communication_dedup IS 'Ключи дедупликации коммуникаций. Глобальность держит не уникальный индекс, а поиск по всем партициям под advisory-блокировкой. Ключи дубликатов не хранятся. Горизонт дедупликации - глубина хранения партиций';
COMMENT ON COLUMN tc_communication_dedup.id IS 'Идентификатор строки ключа, UUIDv7';
COMMENT ON COLUMN tc_communication_dedup.created_at IS 'Время регистрации ключа';
COMMENT ON COLUMN tc_communication_dedup.updated_at IS 'Время последнего изменения строки. Проставляет приложение';
COMMENT ON COLUMN tc_communication_dedup.execution_created_at IS 'Время создания дела своей коммуникации. Ключ партиционирования: ключ уходит вместе с партицией коммуникации. Времени чего-либо в самом ключе не означает';
COMMENT ON COLUMN tc_communication_dedup.communication_id IS 'Коммуникация, которой принадлежит ключ';
COMMENT ON COLUMN tc_communication_dedup.direction IS 'Направление коммуникации, часть пространства ключа: ключ входящей и исходящей в ту же систему не сталкиваются';
COMMENT ON COLUMN tc_communication_dedup.external_system IS 'Внешняя система, часть пространства ключа: source у входящей, destination у исходящей';
COMMENT ON COLUMN tc_communication_dedup.subject IS 'Предмет ключа по последней успешной - например, товар. Пусто у ключа по всей истории';
COMMENT ON COLUMN tc_communication_dedup.dedup_key IS 'Значение ключа: заголовок, ключ сообщения, хеш нагрузки - формат знает только потребитель';
COMMENT ON COLUMN tc_communication_dedup.finished_at IS 'Коммуникация стала терминальной. Ставится в той же транзакции, ручной перезапуск снимает. Пусто - незавершена';
COMMENT ON COLUMN tc_communication_dedup.succeeded_at IS 'Коммуникация успешна. Ставится вместе с finished_at, ручной перезапуск снимает. Пусто у неуспешных и незавершённых';

--changeset taskcomm:2.0.0-10-tc_communication_correlation
CREATE TABLE IF NOT EXISTS tc_communication_correlation (
    id                   uuid        NOT NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    external_system      text        NOT NULL,
    correlation_id       text        NOT NULL,
    communication_id     uuid        NOT NULL,
    execution_id         uuid        NOT NULL,
    execution_created_at timestamptz NOT NULL,
    CONSTRAINT tc_communication_correlation_pk PRIMARY KEY (id),
    CONSTRAINT tc_communication_correlation_key_uq UNIQUE (external_system, correlation_id)
);

CREATE INDEX IF NOT EXISTS tc_communication_correlation_execution_created_at_idx
    ON tc_communication_correlation (execution_created_at);

COMMENT ON TABLE tc_communication_correlation IS 'Реестр ключей корреляции: по чужому ключу асинхронный ответ находит исходную коммуникацию или её дело. Не партиционирован - иначе уникальность ключа пропала бы. После удаления партиции чистится пачками';
COMMENT ON COLUMN tc_communication_correlation.id IS 'Идентификатор записи, UUIDv7';
COMMENT ON COLUMN tc_communication_correlation.created_at IS 'Время регистрации ключа';
COMMENT ON COLUMN tc_communication_correlation.updated_at IS 'Время последнего изменения строки. Проставляет приложение';
COMMENT ON COLUMN tc_communication_correlation.external_system IS 'Внешняя система, от которой ожидается колбэк с этим ключом. По умолчанию внешняя сторона якоря, при регистрации можно указать явно';
COMMENT ON COLUMN tc_communication_correlation.correlation_id IS 'Ключ корреляции - чужой номер. Уникален в пределах системы: у разных систем номера совпадают. Первая регистрация выигрывает';
COMMENT ON COLUMN tc_communication_correlation.communication_id IS 'Коммуникация-якорь, зарегистрировавшая ключ';
COMMENT ON COLUMN tc_communication_correlation.execution_id IS 'Дело якоря - туда попадает колбэк, если в сам якорь его дописать нельзя';
COMMENT ON COLUMN tc_communication_correlation.execution_created_at IS 'Время создания дела якоря. Ключ отсечения партиций при чтении якоря и при очистке реестра после удаления партиции, а не время чего-либо в самой записи';

--changeset taskcomm:2.0.0-11-tc_searchable_field
CREATE TABLE IF NOT EXISTS tc_searchable_field (
    id                uuid        NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    level             text        NOT NULL,
    task_type         text        NOT NULL,
    field_name        text        NOT NULL,
    field_type        text        NOT NULL,
    json_path         text        NOT NULL,
    field_title       text,
    field_description text,
    last_declared_at  timestamptz NOT NULL,
    CONSTRAINT tc_searchable_field_pk PRIMARY KEY (id),
    CONSTRAINT tc_searchable_field_level_check CHECK (level IN ('TASK', 'BATCH', 'UNIT')),
    CONSTRAINT tc_searchable_field_field_type_check CHECK (field_type IN ('STRING', 'NUMBER')),
    CONSTRAINT tc_searchable_field_signature_uq UNIQUE (level, task_type, field_name, field_type, json_path)
);

COMMENT ON TABLE tc_searchable_field IS 'Реестр полей поиска по нагрузке - белый список, объявленный процессорами. Живёт в базе, а не в коде: поиск по истории работает и без живого процессора. Запись отождествляется сигнатурой объявления';
COMMENT ON COLUMN tc_searchable_field.id IS 'Идентификатор записи, UUIDv7';
COMMENT ON COLUMN tc_searchable_field.created_at IS 'Время первого объявления этой сигнатуры';
COMMENT ON COLUMN tc_searchable_field.updated_at IS 'Время последнего изменения строки. Проставляет приложение';
COMMENT ON COLUMN tc_searchable_field.level IS 'Уровень, чья нагрузка ищется: TASK, BATCH или UNIT';
COMMENT ON COLUMN tc_searchable_field.task_type IS 'Тип задачи, объявивший поле. Частичные индексы поиска строятся с условием по нему';
COMMENT ON COLUMN tc_searchable_field.field_name IS 'Имя поля в запросе поиска. У одного имени может быть несколько строк - запрос разворачивается в OR по всем';
COMMENT ON COLUMN tc_searchable_field.field_type IS 'Тип поля: STRING или NUMBER. Задаёт вид сравнения в SQL и выражение индекса. Смена типа - новая запись, а не правка прежней';
COMMENT ON COLUMN tc_searchable_field.json_path IS 'Путь к значению в нагрузке. Смена пути - новая запись: старые задачи остаются находимыми по прежнему пути';
COMMENT ON COLUMN tc_searchable_field.field_title IS 'Название поля для интерфейса';
COMMENT ON COLUMN tc_searchable_field.field_description IS 'Описание поля для интерфейса';
COMMENT ON COLUMN tc_searchable_field.last_declared_at IS 'Когда сигнатуру последний раз объявил живой процессор. Запись, которую больше никто не объявляет, всё равно обслуживает поиск по истории';

--changeset taskcomm:2.0.0-12-tc_partition_horizon
CREATE TABLE IF NOT EXISTS tc_partition_horizon (
    id          uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    active_from timestamptz NOT NULL,
    CONSTRAINT tc_partition_horizon_pk PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS tc_partition_horizon_single_row_uq ON tc_partition_horizon ((true));

INSERT INTO tc_partition_horizon (id, created_at, updated_at, active_from)
SELECT '01900000-0000-7000-8000-000000000000', now(), now(), '-infinity'
WHERE NOT EXISTS (SELECT 1 FROM tc_partition_horizon);

COMMENT ON TABLE tc_partition_horizon IS 'Граница активных партиций - справочник из одной строки, заведённой вместе с таблицей. Запросы, ищущие незавершённую работу, не открывают партиции ниже границы. Двигает границу стартер';
COMMENT ON COLUMN tc_partition_horizon.id IS 'Постоянный идентификатор единственной строки, заведённой вместе с таблицей';
COMMENT ON COLUMN tc_partition_horizon.created_at IS 'Время заведения строки';
COMMENT ON COLUMN tc_partition_horizon.updated_at IS 'Время последнего сдвига границы. Проставляет приложение';
COMMENT ON COLUMN tc_partition_horizon.active_from IS 'Нижняя граница партиций, где МОЖЕТ лежать незавершённая работа: ниже неё незавершённой работы нет, выше - не обязательно есть. Не время последнего изменения. Такт сдвигает её вперёд, запись в старую партицию опускает назад. -infinity - граница ещё не рассчитана, активны все партиции';
