package com.coffeeorder.infra.outbox;

import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import com.coffeeorder.domain.outbox.service.OrderEventPublisher;
import com.coffeeorder.domain.outbox.service.OutboxBackoffPolicy;
import com.coffeeorder.domain.outbox.service.OutboxDeliveryCoordinator;
import com.coffeeorder.domain.outbox.service.OutboxDeliveryWorker;
import com.coffeeorder.global.observability.OperationalMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxDeliveryProperties.class)
@ConditionalOnProperty(
        name = "outbox.delivery.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxDeliveryConfiguration {

    @Bean
    OrderEventPublisher orderEventPublisher(
            OutboxDeliveryProperties properties, MeterRegistry meterRegistry) {
        return new HttpOrderEventPublisherAdapter(properties, meterRegistry);
    }

    @Bean
    OutboxDeliveryCoordinator outboxDeliveryCoordinator(
            OutboxDeliveryRepository repository,
            OrderEventPublisher publisher,
            Clock clock,
            OutboxDeliveryProperties properties,
            PlatformTransactionManager transactionManager,
            OperationalMetrics metrics) {
        return new OutboxDeliveryCoordinator(
                repository,
                publisher,
                clock,
                properties.lease(),
                new OutboxBackoffPolicy(),
                () -> ThreadLocalRandom.current().nextDouble(0.8, 1.2),
                transactionManager,
                metrics);
    }

    @Bean
    OutboxDeliveryWorker outboxDeliveryWorker(
            OutboxDeliveryCoordinator coordinator, OutboxDeliveryProperties properties) {
        return new OutboxDeliveryWorker(coordinator, properties.workerId());
    }

    @Bean("outboxDeliveryExecutor")
    Executor outboxDeliveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("outbox-delivery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }

    @Bean
    OutboxDeliveryRunner outboxDeliveryRunner(
            OutboxDeliveryWorker worker,
            @Qualifier("outboxDeliveryExecutor") Executor executor,
            OutboxDeliveryProperties properties) {
        return new OutboxDeliveryRunner(worker, executor, properties.afterCommitWakeupEnabled());
    }
}
