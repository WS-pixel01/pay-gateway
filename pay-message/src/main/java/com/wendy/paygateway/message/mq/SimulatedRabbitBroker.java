package com.wendy.paygateway.message.mq;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * In-memory RabbitMQ simulator: one queue and one consumer thread per topic. When a handler
 * throws, the delivery is redelivered with backoff up to 3 times and then dead-lettered, matching
 * the nack/requeue behaviour of a real broker.
 */
@Slf4j
@Component
public class SimulatedRabbitBroker implements MqTemplate {

    private static final int MAX_DELIVERY = 3;

    private final Map<String, LinkedBlockingQueue<Delivery>> queues = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService consumerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mq-consumer");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mq-retry");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean running = true;

    @Override
    public void send(String topic, String messageBody) {
        queue(topic).offer(new Delivery(messageBody, new AtomicInteger(1)));
        log.debug("[MQ] published topic={} body={}", topic, messageBody);
    }

    @Override
    public void subscribe(String topic, Consumer<String> handler) {
        handlers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(handler);
        startConsumer(topic);
        log.info("[MQ] subscribed topic={}", topic);
    }

    private LinkedBlockingQueue<Delivery> queue(String topic) {
        return queues.computeIfAbsent(topic, t -> new LinkedBlockingQueue<>());
    }

    private void startConsumer(String topic) {
        consumerPool.submit(() -> {
            LinkedBlockingQueue<Delivery> queue = queue(topic);
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Delivery delivery = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (delivery == null) {
                        continue;
                    }
                    dispatch(topic, delivery);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.error("[MQ] consumer thread error topic={}", topic, e);
                }
            }
        });
    }

    private void dispatch(String topic, Delivery delivery) {
        for (Consumer<String> handler : handlers.getOrDefault(topic, List.of())) {
            try {
                handler.accept(delivery.body());
            } catch (Exception e) {
                int times = delivery.deliveryCount().get();
                if (times >= MAX_DELIVERY) {
                    log.error("[MQ] consume failed {} times, dead-lettering topic={} body={} err={}",
                            times, topic, delivery.body(), e.getMessage());
                    continue;
                }
                delivery.deliveryCount().incrementAndGet();
                long delaySeconds = (long) Math.pow(2, times);
                log.warn("[MQ] consume failed, redelivery {} in {}s topic={} err={}",
                        times + 1, delaySeconds, topic, e.getMessage());
                retryScheduler.schedule(() -> queue(topic).offer(delivery), delaySeconds, TimeUnit.SECONDS);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        consumerPool.shutdownNow();
        retryScheduler.shutdownNow();
    }

    private record Delivery(String body, AtomicInteger deliveryCount) {
    }
}
