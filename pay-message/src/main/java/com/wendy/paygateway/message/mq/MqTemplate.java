package com.wendy.paygateway.message.mq;

import java.util.function.Consumer;

/**
 * MQ abstraction. The local sandbox uses the in-memory {@link SimulatedRabbitBroker}; moving to a
 * real RabbitMQ only takes another implementation (send -&gt; rabbitTemplate.convertAndSend,
 * subscribe -&gt; @RabbitListener) and leaves business code untouched.
 */
public interface MqTemplate {

    /** Publish a message. */
    void send(String topic, String messageBody);

    /** Subscribe to a topic. */
    void subscribe(String topic, Consumer<String> handler);
}
