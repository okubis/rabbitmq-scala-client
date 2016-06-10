package com.avast.clients.rabbitmq

import java.nio.file.{Path, Paths}
import java.time.Duration
import java.util
import java.util.concurrent.ScheduledExecutorService

import com.avast.clients.rabbitmq.RabbitMQChannelFactory.ServerChannel
import com.avast.clients.rabbitmq.api.{RabbitMQConsumer, RabbitMQProducer}
import com.avast.metrics.api.Monitor
import com.avast.utils2.errorhandling.FutureTimeouter
import com.rabbitmq.client.AMQP
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object RabbitMQClientFactory extends LazyLogging {

  private[rabbitmq] final val ProducerRootConfigKey = "ffRabbitMQProducerDefaults"
  private[rabbitmq] final val ProducerDefaultConfig = ConfigFactory.defaultReference().getConfig(ProducerRootConfigKey)

  private[rabbitmq] final val ConsumerRootConfigKey = "ffRabbitMQConsumerDefaults"
  private[rabbitmq] final val ConsumerDefaultConfig = ConfigFactory.defaultReference().getConfig(ConsumerRootConfigKey)

  private[rabbitmq] final val ConsumerBindingRootConfigKey = "ffRabbitMQConsumerBindingDefaults"
  private[rabbitmq] final val ConsumerBindingDefaultConfig = ConfigFactory.defaultReference().getConfig(ConsumerBindingRootConfigKey)

  private implicit final val JavaDurationReader: ValueReader[Duration] = new ValueReader[Duration] {
    override def read(config: Config, path: String): Duration = config.getDuration(path)
  }

  private implicit final val JavaPathReader: ValueReader[Path] = new ValueReader[Path] {
    override def read(config: Config, path: String): Path = Paths.get(config.getString(path))
  }

  object Producer {
    /** Creates new instance of producer, using the passed configuration.
      *
      * @param providedConfig The configuration.
      * @param channelFactory See [[RabbitMQChannelFactory]].
      * @param monitor        Monitor for metrics.
      */
    def fromConfig(providedConfig: Config, channelFactory: RabbitMQChannelFactory, monitor: Monitor): RabbitMQProducer = {
      val producerConfig = providedConfig.wrapped.as[ProducerConfig]("root")

      val channel = channelFactory.createChannel()

      prepareProducer(producerConfig, channel, monitor)
    }
  }

  object Consumer {
    /** Creates new instance of consumer, using the passed configuration.
      *
      * @param providedConfig           The configuration.
      * @param channelFactory           See [[RabbitMQChannelFactory]].
      * @param monitor                  Monitor for metrics.
      * @param scheduledExecutorService [[ScheduledExecutorService]] used for timeouting tasks (after specified timeout).
      * @param readAction               Action executed for each delivered message. The action has to return `Future[Boolean]`, where the `Boolean` means
      *                                 "should the delivery be marked as done?". You should never return a failed future.
      * @param ec                       [[ExecutionContext]] used for callbacks.
      */
    def fromConfig(providedConfig: Config,
                   channelFactory: RabbitMQChannelFactory,
                   monitor: Monitor,
                   scheduledExecutorService: ScheduledExecutorService = FutureTimeouter.Implicits.DefaultScheduledExecutor)
                  (readAction: Delivery => Future[Boolean])
                  (implicit ec: ExecutionContext): RabbitMQConsumer = {

      val mergedConfig = providedConfig.withFallback(ConsumerDefaultConfig)

      // merge consumer binding defaults
      val updatedConfig = {
        val updated = mergedConfig.as[Seq[Config]]("bindings").map { bindConfig =>
          bindConfig.withFallback(ConsumerBindingDefaultConfig).root()
        }

        import scala.collection.JavaConverters._

        mergedConfig.withValue("bindings", ConfigValueFactory.fromIterable(updated.asJava))
      }

      val consumerConfig = updatedConfig.wrapped.as[ConsumerConfig]("root")

      val channel = channelFactory.createChannel()

      prepareConsumer(consumerConfig, readAction, channel, monitor, scheduledExecutorService)
    }
  }

  private def prepareProducer(producerConfig: ProducerConfig, channel: ServerChannel, monitor: Monitor): DefaultRabbitMQProducer = {
    import producerConfig._

    // auto declare of exchange
    // parse it only if it's needed
    if (declare.getBoolean("enabled")) {
      val d = declare.wrapped.as[AutoDeclareExchange]("root")

      declareExchange(exchange, channel, d)
    }

    new DefaultRabbitMQProducer(producerConfig.name, exchange, channel, monitor)
  }

  private def declareExchange(name: String, channel: ServerChannel, autoDeclareExchange: AutoDeclareExchange): Unit = {
    import autoDeclareExchange._

    if (enabled) {
      logger.info(s"Declaring exchange '$name' of type ${`type`}")
      channel.exchangeDeclare(name, `type`, durable, autoDelete, new util.HashMap())
    }
    ()
  }

  private def prepareConsumer(consumerConfig: ConsumerConfig,
                              readAction: (Delivery) => Future[Boolean],
                              channel: ServerChannel,
                              monitor: Monitor,
                              scheduledExecutor: ScheduledExecutorService)(implicit ec: ExecutionContext): RabbitMQConsumer = {

    // auto declare exchanges
    consumerConfig.bindings.foreach { bind =>
      import bind.exchange._

      // parse it only if it's needed
      if (declare.getBoolean("enabled")) {
        val d = declare.wrapped.as[AutoDeclareExchange]("root")

        declareExchange(name, channel, d)
      }
    }

    // auto declare queue
    {
      import consumerConfig.declare._
      import consumerConfig.queueName

      if (enabled) {
        logger.info(s"Declaring queue '$queueName'")
        channel.queueDeclare(queueName, durable, exclusive, autoDelete, new util.HashMap())
      }
    }

    // set prefetch size (per consumer)
    channel.basicQos(consumerConfig.prefetchCount)

    // auto bind
    bindQueues(channel, consumerConfig)

    prepareConsumer(consumerConfig, channel, readAction, monitor, scheduledExecutor)
  }

  private def bindQueues(channel: ServerChannel, consumerConfig: ConsumerConfig): Unit = {
    import consumerConfig.queueName

    consumerConfig.bindings.foreach { bind =>
      import bind._
      val exchangeName = bind.exchange.name

      if (routingKeys.nonEmpty) {
        routingKeys.foreach { routingKey =>
          bindTo(channel, queueName)(exchangeName, routingKey)
        }
      } else {
        // binding without routing key, possibly to fanout exchange

        bindTo(channel, queueName)(exchangeName, "")
      }
    }
  }

  private def bindTo(channel: ServerChannel, queueName: String)(exchangeName: String, routingKey: String): AMQP.Queue.BindOk = {
    logger.info(s"Binding $exchangeName($routingKey) -> '$queueName'")
    channel.queueBind(queueName, exchangeName, routingKey)
  }

  private def prepareConsumer(consumerConfig: ConsumerConfig,
                              channel: ServerChannel,
                              readAction: (Delivery) => Future[Boolean],
                              monitor: Monitor,
                              scheduledExecutor: ScheduledExecutorService)
                             (implicit ec: ExecutionContext): RabbitMQConsumer = {
    import FutureTimeouter._
    import consumerConfig._

    val consumer = new DefaultRabbitMQConsumer(name, channel, monitor, bindTo(channel, queueName))({ delivery =>
      try {
        readAction(delivery)
          .timeoutAfter(processTimeout)(ec, scheduledExecutor)
          .recover {
            case NonFatal(e) =>
              logger.warn("Error while executing callback, will be redelivered", e)
              false
          }
      } catch {
        case NonFatal(e) =>
          logger.error("Error while executing callback, will be redelivered", e)
          Future.successful(false)
      }
    })

    channel.basicConsume(queueName, false, consumer)

    consumer
  }

  implicit class WrapConfig(val c: Config) extends AnyVal {
    def wrapped: Config = {
      // we need to wrap it with one level, to be able to parse it with Ficus
      ConfigFactory.empty()
        .withValue("root", c.withFallback(ProducerDefaultConfig).root())
    }
  }

}


case class ConsumerConfig(queueName: String,
                          processTimeout: Duration,
                          prefetchCount: Int,
                          declare: AutoDeclareQueue,
                          bindings: Seq[AutoBindQueue],
                          name: String)

case class AutoDeclareQueue(enabled: Boolean, durable: Boolean, exclusive: Boolean, autoDelete: Boolean)

case class AutoBindQueue(exchange: BindExchange, routingKeys: Seq[String])

case class BindExchange(name: String, declare: Config)

case class ProducerConfig(exchange: String, declare: Config, name: String)

case class AutoDeclareExchange(enabled: Boolean, `type`: String, durable: Boolean, autoDelete: Boolean)
