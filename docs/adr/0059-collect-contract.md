# 0059. Контракт сборки: параметры типа, состав потока, `expand` без результата

- **Дата:** 2026-08-31
- **Статус:** принято

## Контекст

[0048](0048-collect-phase.md) ввёл фазу сборки и объявил, что `process`, `expand` и `collect`
возвращают значение. Сигнатур он при этом не выписал, и когда дошло до них, вылезли три вещи разом.

**Первая — взрыв параметров типа.** [0007](0007-processor-contracts.md) зафиксировал
`SimpleTaskProcessor<P>`, `UnitTaskProcessor<P, U>`, `BatchTaskProcessor<P, B, U>`, потому что
нагрузки уровней разного типа. Результаты уровней — тоже разного типа: результат обработки одного
товара и свод по всей задаче не имеют между собой ничего общего. Прямое добавление даёт **шесть**
параметров у батчевого контракта.

**Вторая — состав потока.** Нигде не было сказано, какие дети попадают в поток, который получает
`collect`: только успешные, или пропущенные тоже, или все терминальные. От ответа зависит и запрос,
и то, что вообще может собрать процессор.

**Третья — `expand`.** В наборе результатов нет слота под результат раскрытия, а 0048 обещал
значение и от него тоже.

## Рассмотренные варианты

### Параметры типа

#### A. Один тип результата на процессор — отвергнуто

`<P, B, U, R>`: все три уровня возвращают одно и то же. Подпись терпима.

Отвергнуто тем же доводом, по которому у уровней разные нагрузки ([0007](0007-processor-contracts.md)):
результат подзадачи и свод задачи разной природы, и принуждение к одному типу заставило бы
потребителя заводить объединяющий тип-пустышку.

#### B. Не типизировать результат — отвергнуто

`JsonNode` или `Map`, ноль новых параметров, подписи остаются как есть.

Отвергнуто: механизм восстановления типа из дженерика уже существует и ничего не стоит
([0012](0012-payload-storage.md)). Отказ от него не упрощает ничего, а типобезопасность теряет —
причём в том самом коде потребителя, ради которого стартер и пишется.

#### C. Отдельный opt-in интерфейс сборки — отвергнуто

Базовые три контракта остаются без результатов, желающий реализует дополнительный интерфейс.
Большинство не платит ничем.

Отвергнуто: параметров у подключившегося всё равно шесть, а запечатанная иерархия
([0007](0007-processor-contracts.md)) усложняется вдвое — воркеру приходится разбирать не только
контракт, но и наличие надстройки. Цена больше выигрыша.

#### D. Полный набор — выбрано

### Состав потока

#### E. Только успешные дети — отвергнуто

Простейшее. Отвергнуто: процессор не сможет назвать в сводке ни одного упавшего объекта —
а сводка при `WARNING` ([0009](0009-retry-and-progress.md)) именно за этим и нужна. Отличить
«пропущено» от «упало» тоже было бы нечем, не спросив базу отдельно.

#### F. Успешные и пропущенные — отвергнуто

Лучше, но упавшие по-прежнему невидимы, а это главный сценарий разбора.

#### G. Все терминальные, каждый с признаком — выбрано

### Результат раскрытия

#### H. Седьмой параметр под результат раскрытия — отвергнуто

Сигнатура 0048 сохраняется целиком.

Отвергнуто по чтению. У батча последовательность попыток — `EXPAND`, затем работа детей, затем
`COLLECT`. Правило [0058](0058-task-result-storage.md) «результат сущности есть результат её
последней попытки» в окне между ними вернуло бы результат раскрытия, то есть **значение другого
типа**. Правило перестаёт быть однозначным не в углу, а в обычном ходе работы. Чинится оно только
уточнением «последняя попытка нужного вида», после чего спрашивающий обязан знать, какой именно
результат он спрашивает.

#### I. Возвращает, но не хранится — отвергнуто

Метод обещает то, чего не делает. Ровно тот сорт тихой ловушки, который в этом проекте отвергают.

#### J. `expand` и `expandBatch` остаются `void` — выбрано

Раскрытие уже отдаёт свой результат — детей, через `Emitter` ([0008](0008-expansion-emitter.md)).
Второй канал вывода у одного метода не нужен.

## Решение

### Параметры типа: полный набор, буква совпадает с уровнем

```java
sealed interface TaskProcessor<T> permits FlatTaskProcessor, UnitTaskProcessor, BatchTaskProcessor

FlatTaskProcessor<T, RT>
UnitTaskProcessor<T, U, RU, RT>
BatchTaskProcessor<T, B, U, RU, RB, RT>
```

**`Task<P>` переименовывается в `Task<T>`**: T, B и U читаются как Task, Batch и Unit, а `R`
с тем же суффиксом — результат этого уровня. Без этого шесть букв не читаются вовсе.

Тем же правилом переименовываются коммуникации: **`Communication<C>`** и
**`CommunicationProcessor<C, R>`**. Подсистемы независимы ([0010](0010-separate-engines.md)),
но правило именования — одно на проект.

### Сигнатуры

```java
public non-sealed interface BatchTaskProcessor<T, B, U, RU, RB, RT> extends TaskProcessor<T> {

    void expand(Task<T> task, Emitter<B> batches, ProcessingContext context);
    void expandBatch(TaskBatch<B> batch, Emitter<U> units, ProcessingContext context);

    RU process(TaskUnit<U> unit, ProcessingContext context);

    RB collectBatch(TaskBatch<B> batch, Stream<CollectedUnit<U, RU>> units, ProcessingContext context);
    RT collect(Task<T> task, Stream<CollectedBatch<B, RB>> batches, ProcessingContext context);
}
```

`ProcessingContext` идёт **параметром** каждого метода, а не полем процессора: так он и показан
в примере [0056](0056-execution-ref.md), и иначе `executionRef()` неоткуда взять. Все пять методов
выполняются под арендой, значит всем пяти нужны и `skip(reason)`, и признак «продолжать ли».

Имена **парные, а не перегрузка**: `collect` и `collectBatch` по образцу уже принятых
`expand` и `expandBatch`. Перегрузка различала бы методы только типом первого параметра, а читатель
разбирает их по имени.

`Void` в параметре результата означает «не хранить»: метод вернёт `null`, а `null` не хранится
([0058](0058-task-result-storage.md)). Отдельного переключателя не нужно.

### Состав потока

В поток идут **все терминальные дети**, каждый обёрткой:

```java
record CollectedUnit<U, RU>(
        UUID id, U payload, RU result, TaskUnitStatus status, String statusDescription) {}

record CollectedBatch<B, RB>(
        UUID id, B payload, RB result, TaskStatus status, String statusDescription) {}
```

`result` пуст у неуспешных и у тех, кто вернул `null`. `id` и нагрузка нужны, чтобы процессор мог
назвать конкретный бизнес-объект — своего бизнес-ключа у стартера нет
([0032](0032-no-business-key.md)), и без нагрузки сводка получилась бы безымянной.
`statusDescription` даёт причину для `SKIPPED` и `CANCELLED`.

Поток остаётся ленивым: у батча миллион подзадач, и материализовать его нельзя
([0048](0048-collect-phase.md)).

### Пример

Шесть параметров типа читаются тяжело, поэтому пример обязан стоять рядом с контрактом —
и в документации для потребителя тоже:

```java
@Component
@RequiredArgsConstructor
public class PriceSyncProcessor implements BatchTaskProcessor<
        SyncParams,      // T  — параметры задачи: период и список поставщиков
        SupplierBatch,   // B  — параметры группы: один поставщик
        ItemPrice,       // U  — один товар
        ItemOutcome,     // RU — что вышло с товаром
        SupplierReport,  // RB — свод по поставщику
        SyncReport> {    // RT — свод по задаче

    private final PriceClient priceClient;
    private final Catalog catalog;

    @Override
    public String taskType() {
        return "price-sync";
    }

    @Override
    public void expandBatch(
            final TaskBatch<SupplierBatch> batch,
            final Emitter<ItemPrice> units,
            final ProcessingContext context) {
        this.priceClient.fetchCatalog(batch.payload().supplier())
                .forEach(item -> units.emit(new ItemPrice(item.sku(), item.price())));
    }

    @Override
    public ItemOutcome process(final TaskUnit<ItemPrice> unit, final ProcessingContext context) {
        final var applied = this.catalog.applyPrice(unit.payload());
        return new ItemOutcome(applied.sku(), applied.oldPrice(), applied.newPrice());
    }

    @Override
    public SupplierReport collectBatch(
            final TaskBatch<SupplierBatch> batch,
            final Stream<CollectedUnit<ItemPrice, ItemOutcome>> units,
            final ProcessingContext context) {
        final var failed = new ArrayList<String>();
        final var changed = new AtomicInteger();
        units.forEach(unit -> {
            if (unit.status() == TaskUnitStatus.SUCCEEDED) {
                changed.incrementAndGet();
            } else if (unit.status() == TaskUnitStatus.FAILED) {
                failed.add(unit.payload().sku() + ": " + unit.statusDescription());
            }
        });
        return new SupplierReport(batch.payload().supplier(), changed.get(), failed);
    }
}
```

## Последствия

- **Шесть параметров типа — самая тяжёлая подпись в публичном API.** Это принятая цена
  типобезопасности, и её надо признавать вслух, а не выдавать за удобство. Без комментария
  к каждому параметру, как в примере выше, реализация нечитаема.
- **Изменяется часть [0048](0048-collect-phase.md)**: значение возвращают не все три метода,
  а два — `process` и `collect`. Довод 0048 о том, что результат это данные, а не исход, остаётся
  в силе; он просто не относится к раскрытию, у которого канал вывода уже есть.
- **Элемент потока — обёртка, а не результат.** Значит `collect` видит статусы детей и может
  собрать свод при `WARNING`, но и обязан их разбирать: поток больше не «список того, что
  получилось».
- **Нагрузка ребёнка тянется через весь поток.** У подзадачи это самая объёмная колонка, и миллион
  нагрузок пройдёт через сборку. Цена принята ради того, чтобы сводка могла назвать объект;
  требование ленивости от этого становится жёстче, а не мягче.
- **Переименование параметров типа затрагивает и коммуникации**, включая только что принятые
  [0052](0052-communication-transport-contracts.md) и [0055](0055-communication-exchange-storage.md).
  Кода пока нет, правка текстовая; после релиза это была бы мажорная версия.
- Добавление четвёртого контракта задач теперь дороже: у него будет свой набор параметров
  результата, и запечатанная иерархия заставит разобрать его везде.

## Связанные решения

- [0048](0048-collect-phase.md) — фаза сборки; здесь меняется её часть про `expand`.
- [0007](0007-processor-contracts.md) — запечатанная иерархия и параметры типа, которые здесь
  расширяются и переименовываются.
- [0058](0058-task-result-storage.md) — где эти результаты лежат и правило про `null`.
- [0008](0008-expansion-emitter.md) — `Emitter`, из-за которого раскрытию нечего возвращать.
- [0032](0032-no-business-key.md) — почему в обёртке нужна нагрузка, а не бизнес-ключ.
- [0009](0009-retry-and-progress.md) — `WARNING`, ради которого в поток идут неуспешные дети.
- [0012](0012-payload-storage.md) — восстановление типов результата из дженерика.
- [0056](0056-execution-ref.md) — `ProcessingContext` параметром метода и `executionRef()` в нём.
