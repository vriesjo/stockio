package nl.stockio

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class McpApplication

fun main(args: Array<String>) {
    SpringApplication.run(McpApplication::class.java, *args)
}
