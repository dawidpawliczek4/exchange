package com.dawidpawliczek.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ExchangeApplication

fun main(args: Array<String>) {
    runApplication<ExchangeApplication>(*args)
}
