package com.dawidpawliczek.app.order

import com.dawidpawliczek.contracts.PlaceOrderCommand
import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.WireCodec
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class OrderCommandPublisher(
    private val kafka: KafkaTemplate<String, ByteArray>,
) {
    fun publish(command: PlaceOrderCommand) {
        kafka.send(Topics.COMMANDS, WireCodec.encode(command))
    }
}
