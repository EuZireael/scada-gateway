package com.scada.gateway.ha;

/**
 * Роль экземпляра в паре горячего резервирования (см. {@link LeaderElector}).
 *
 * <p>Активный экземпляр — единственный, кто говорит с внешним миром: публикует телеметрию,
 * события и алармы в Kafka, пишет историю и исполняет команды. Резервный делает всё то же
 * самое внутри себя (держит соединения с ПЛК, опрашивает, следит за качеством), но наружу
 * молчит — поэтому при переключении он выдаёт свежие значения уже на первом цикле.
 */
public interface Leadership {

    /** Экземпляр без резервирования: всегда активный (поведение шлюза до HA). */
    Leadership ALWAYS_ACTIVE = new Leadership() {
        @Override public boolean isActive() { return true; }
        @Override public boolean isHaEnabled() { return false; }
        @Override public String instanceId() { return "single"; }
    };

    /** true — этот экземпляр активный: публикует в Kafka и исполняет команды. */
    boolean isActive();

    /** Включено ли резервирование (gateway.ha.enabled). */
    boolean isHaEnabled();

    /** Имя экземпляра в паре (gateway.ha.instance-id, по умолчанию — имя хоста). */
    String instanceId();
}
