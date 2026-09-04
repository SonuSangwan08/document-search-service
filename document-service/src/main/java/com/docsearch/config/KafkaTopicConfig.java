package com.docsearch.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Explicitly provisions the outbox topic's partition count rather than
 * relying on Kafka's auto-create-on-first-use default (1 partition on this
 * cluster). Confirmed live (docs/SUBMISSION.md §2 Scalability) that without
 * this, the topic auto-created with a single partition, which silently
 * capped indexing parallelism at 1 no matter how document-indexer's consumer
 * concurrency or replica count were configured - Kafka can never assign more
 * active consumers than there are partitions. 3 partitions matches
 * document-indexer's {@code concurrency: 3} listener setting, and is also
 * what {@code docker compose up --scale document-indexer=3} needs to
 * actually distribute work across replicas instead of 2 of the 3 sitting
 * idle. Spring Kafka's {@link org.springframework.kafka.core.KafkaAdmin}
 * applies this on startup via {@code createPartitions}, which can raise an
 * existing topic's partition count but never lower it - safe to redeploy.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic documentIndexEventsTopic(AppProperties properties) {
        return TopicBuilder.name(properties.kafka().documentEventsTopic())
                .partitions(3)
                .replicas(1)
                .build();
    }
}
