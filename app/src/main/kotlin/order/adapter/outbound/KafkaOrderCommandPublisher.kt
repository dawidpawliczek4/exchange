package com.dawidpawliczek.app.order.adapter.outbound

import com.dawidpawliczek.app.order.application.port.outbound.OrderCommandPublisher
import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.command.CommandCodec
import com.dawidpawliczek.contracts.command.OrderCommand
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class KafkaOrderCommandPublisher(
    private val kafka: KafkaTemplate<String, ByteArray>,
) : OrderCommandPublisher {
    override fun publish(command: OrderCommand) {
        kafka.send(Topics.COMMANDS, CommandCodec.encode(command))
    }
}
