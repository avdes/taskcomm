package xyz.avasilev.taskcomm.autoconfigure;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Выбор чужих бинов, которыми пользуется стартер: {@code DataSource} и менеджер транзакций
 * по квалификаторам выбора (docs/adr/0094-creation-always-in-transaction.md), планировщик
 * по точке замены (docs/adr/0096-no-foreign-type-beans.md).
 *
 * <p>Выбранное не регистрируется бином — это был бы бин чужого типа. Каждая автоконфигурация
 * вызывает выбор сама и получает один и тот же результат.
 */
@Slf4j
final class TaskcommBeanSelection {

    private TaskcommBeanSelection() {}

    /**
     * {@code DataSource} стартера: помеченный {@link TaskcommDataSource}, иначе единственный
     * или {@code @Primary}.
     *
     * @param qualified бины под {@link TaskcommDataSource}
     * @param all все бины {@code DataSource}
     * @param beanFactory фабрика для перечня кандидатов в сообщении
     * @return выбранный {@code DataSource}
     * @throws TaskcommConfigurationException если выбор неоднозначен или выбирать не из чего
     */
    static DataSource dataSource(
            final ObjectProvider<DataSource> qualified,
            final ObjectProvider<DataSource> all,
            final ListableBeanFactory beanFactory) {
        return select(qualified, all, beanFactory, DataSource.class, "@TaskcommDataSource");
    }

    /**
     * Менеджер транзакций стартера: помеченный {@link TaskcommTransactionManager}, иначе
     * единственный или {@code @Primary}. Если у менеджера виден его {@code DataSource}, он обязан
     * совпадать с {@code DataSource} стартера; не виден (JTA) — громкое предупреждение.
     *
     * @param qualified бины под {@link TaskcommTransactionManager}
     * @param all все менеджеры транзакций
     * @param beanFactory фабрика для перечня кандидатов в сообщении
     * @param dataSource {@code DataSource} стартера
     * @return выбранный менеджер
     * @throws TaskcommConfigurationException если выбор неоднозначен, выбирать не из чего или
     *     менеджер работает с другим {@code DataSource}
     */
    static PlatformTransactionManager transactionManager(
            final ObjectProvider<PlatformTransactionManager> qualified,
            final ObjectProvider<PlatformTransactionManager> all,
            final ListableBeanFactory beanFactory,
            final DataSource dataSource) {
        final PlatformTransactionManager manager =
                select(qualified, all, beanFactory, PlatformTransactionManager.class, "@TaskcommTransactionManager");
        final BeanWrapperImpl wrapper = new BeanWrapperImpl(manager);
        if (!wrapper.isReadableProperty("dataSource")) {
            log.warn(
                    "У менеджера транзакций {} не виден DataSource (вероятно, JTA). Проверьте вручную, что он "
                            + "управляет транзакциями DataSource, с которым работает taskcomm: иначе создание задач "
                            + "не будет атомарным с бизнес-данными.",
                    manager.getClass().getName());
            return manager;
        }
        if (!Objects.equals(wrapper.getPropertyValue("dataSource"), dataSource)) {
            throw new TaskcommConfigurationException(
                    "Менеджер транзакций " + manager.getClass().getName()
                            + " работает не с тем DataSource, что выбран для taskcomm. Пометьте подходящий менеджер "
                            + "@TaskcommTransactionManager или подходящий DataSource @TaskcommDataSource.");
        }
        return manager;
    }

    /**
     * Планировщик под {@link TaskcommScheduler}, если потребитель его объявил.
     *
     * @param qualified бины под {@link TaskcommScheduler}
     * @return планировщик потребителя или {@code null} — тогда стартер заводит свой
     * @throws TaskcommConfigurationException если помеченных бинов больше одного
     */
    static TaskScheduler scheduler(final ObjectProvider<TaskScheduler> qualified) {
        return replacement(qualified, TaskScheduler.class, "@TaskcommScheduler");
    }

    /**
     * Бин потребителя под квалификатором точки замены, если он объявлен
     * (docs/adr/0096-no-foreign-type-beans.md).
     *
     * @param qualified бины под квалификатором
     * @param type тип бина — для сообщения
     * @param qualifier имя квалификатора — для сообщения
     * @param <T> тип бина
     * @return бин потребителя или {@code null} — тогда стартер берёт своё
     * @throws TaskcommConfigurationException если помеченных бинов больше одного
     */
    static <T> T replacement(final ObjectProvider<T> qualified, final Class<T> type, final String qualifier) {
        final List<T> beans = qualified.orderedStream().toList();
        if (beans.size() > 1) {
            throw new TaskcommConfigurationException(
                    "Бинов " + type.getSimpleName() + " под " + qualifier + " " + beans.size() + ", а допустим один.");
        }
        return beans.isEmpty() ? null : beans.getFirst();
    }

    private static <T> T select(
            final ObjectProvider<T> qualified,
            final ObjectProvider<T> all,
            final ListableBeanFactory beanFactory,
            final Class<T> type,
            final String qualifier) {
        final List<T> marked = qualified.orderedStream().toList();
        if (marked.size() > 1) {
            throw new TaskcommConfigurationException(
                    "Бинов " + type.getSimpleName() + " под " + qualifier + " " + marked.size() + ", а допустим один.");
        }
        if (marked.size() == 1) {
            return marked.getFirst();
        }
        final T unique = all.getIfUnique();
        if (unique != null) {
            return unique;
        }
        final String[] candidates = beanFactory.getBeanNamesForType(type);
        if (candidates.length == 0) {
            throw new TaskcommConfigurationException(
                    "taskcomm нужен бин " + type.getSimpleName() + ", а в контексте нет ни одного.");
        }
        throw new TaskcommConfigurationException("taskcomm не может выбрать " + type.getSimpleName() + " из "
                + Arrays.toString(candidates) + ": ни один не помечен " + qualifier + " или @Primary.");
    }
}
