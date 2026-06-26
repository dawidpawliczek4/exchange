package com.dawidpawliczek.app.adapter.kafka

import com.dawidpawliczek.contracts.Topics
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfig {
    @Bean
    fun commandsTopic() =
        TopicBuilder
            .name(Topics.COMMANDS)
            .partitions(1)
            .replicas(1)
            .build()

    @Bean
    fun tradesTopic() =
        TopicBuilder
            .name(Topics.TRADES)
            .partitions(1)
            .replicas(1)
            .build()
}
