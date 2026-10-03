package com.aierp.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate) {

        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        kafkaTemplate,
                        (record, exception) ->
                                new TopicPartition(
                                        record.topic() + ".DLT",
                                        record.partition()
                                )
                );

        /*
         * Retry three times with a 2-second delay.
         *
         * Example:
         *
         * Attempt 1
         *    ↓
         * wait 2 sec
         * Attempt 2
         *    ↓
         * wait 2 sec
         * Attempt 3
         *    ↓
         * wait 2 sec
         * Attempt 4
         *    ↓
         * DLT
         */
        FixedBackOff backOff =
                new FixedBackOff(
                        2_000L,
                        3L
                );

        return new DefaultErrorHandler(
                recoverer,
                backOff
        );
    }
}