package com.scada.gateway.ha;

/**
 * Событие Spring: экземпляр стал активным ({@code active=true}) или ушёл в резерв.
 * Публикует {@link LeaderElector}; слушают включатель консьюмера команд и журнал.
 *
 * @param active     новая роль
 * @param instanceId имя этого экземпляра
 * @param reason     почему сменилась роль — для журнала и лога
 */
public record LeadershipChanged(boolean active, String instanceId, String reason) {
}
