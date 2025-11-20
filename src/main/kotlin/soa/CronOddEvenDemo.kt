@file:Suppress("WildcardImport", "NoWildcardImports", "MagicNumber")

package soa

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.integration.annotation.Gateway
import org.springframework.integration.annotation.MessagingGateway
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.integration.config.EnableIntegration
import org.springframework.integration.dsl.IntegrationFlow
import org.springframework.integration.dsl.MessageChannels
import org.springframework.integration.dsl.Pollers
import org.springframework.integration.dsl.PublishSubscribeChannelSpec
import org.springframework.integration.dsl.integrationFlow
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private val logger = LoggerFactory.getLogger("soa.CronOddEvenDemo")

@SpringBootApplication
@EnableIntegration
@EnableScheduling
class IntegrationApplication(
    private val sendNumber: SendNumber,
) {
    @Bean
    fun integerSource(): AtomicInteger = AtomicInteger()

    // Cambio: PublishSubscribe en lugar de direct (pero comportamiento similar)
    @Bean
    fun evenChannel(): PublishSubscribeChannelSpec<*> = MessageChannels.publishSubscribe()

    @Bean
    fun numberChannel(): PublishSubscribeChannelSpec<*> = MessageChannels.publishSubscribe()

    // Cambio: oddChannel también como PublishSubscribe para mantener coherencia
    @Bean
    fun oddChannel(): PublishSubscribeChannelSpec<*> = MessageChannels.publishSubscribe()

    // Flujo principal: genera números y los envía a numberChannel
    @Bean
    fun myFlow(integerSource: AtomicInteger): IntegrationFlow =
        integrationFlow(
            source = { integerSource.getAndIncrement() },
            options = { poller(Pollers.fixedRate(100)) },
        ) {
            transform { num: Int ->
                logger.info("📥 Source generated number: {}", num)
                num
            }
            // Diferencia: usamos channel() en lugar de route inline
            channel("numberChannel")
        }

    // RESTAURADO: Router separado que lee de numberChannel
    @Bean
    fun distributionFlow(): IntegrationFlow =
        integrationFlow("numberChannel") {
            route { p: Int ->
                val channel = if (p % 2 == 0) "evenChannel" else "oddChannel"
                logger.info("🔀 Router: {} → {}", p, channel)
                channel
            }
        }

    @Bean
    fun evenFlow(): IntegrationFlow =
        integrationFlow("evenChannel") {
            transform { obj: Int ->
                logger.info("  ⚙️  Even Transformer: {} → 'Number {}'", obj, obj)
                "Number $obj"
            }
            handle { p ->
                logger.info("  ✅ Even Handler: Processed [{}]", p.payload)
            }
        }

    // SIN FILTRO: todos los impares pasan directamente
    @Bean
    fun oddFlow(): IntegrationFlow =
        integrationFlow("oddChannel") {
            transform { obj: Int ->
                logger.info("  ⚙️  Odd Transformer: {} → 'Number {}'", obj, obj)
                "Number $obj"
            }
            handle { p ->
                logger.info("  ✅ Odd Handler: Processed [{}]", p.payload)
            }
        }

    @Bean
    fun discarded(): IntegrationFlow =
        integrationFlow("discardChannel") {
            handle { p ->
                logger.info("  🗑️  Discard Handler: [{}]", p.payload)
            }
        }

    @Scheduled(fixedRate = 1000)
    fun sendNumber() {
        val number = -Random.nextInt(100)
        logger.info("🚀 Gateway injecting: {}", number)
        sendNumber.sendNumber(number)
    }
}

// RESTAURADO: ServiceActivator activo
@Component
class SomeService {
    @ServiceActivator(inputChannel = "oddChannel")
    fun handle(p: Any) {
        logger.info("  🔧 Service Activator: Received [{}] (type: {})", p, p.javaClass.simpleName)
    }
}

@MessagingGateway
interface SendNumber {
    @Gateway(requestChannel = "numberChannel")
    fun sendNumber(number: Int)
}

fun main() {
    runApplication<IntegrationApplication>()
}
