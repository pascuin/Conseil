package tech.cryptonomic.conseil.config

import pureconfig.error.ConfigReaderFailures
import pureconfig.generic.ProductHint
import pureconfig.{CamelCase, ConfigFieldMapping, loadConfig}
import pureconfig.generic.auto._
import tech.cryptonomic.conseil.config.Platforms._
import tech.cryptonomic.conseil.util.ConfigUtil.Pureconfig.loadAkkaStreamingClientConfig

/** wraps all configuration needed to run Lorre */
trait LorreAppConfig {
  import LorreAppConfig._

  private val argsParser = new scopt.OptionParser[ArgumentsConfig]("lorre") {
    arg[String]("network").required().action( (x, c) =>
      c.copy(networks = c.networks :+ x) ).text("which network to use")

    opt[Int]("depth")
      .action((depth, c) => c.copy(d = depth))
      .validate(it => if (it >= -1) success else failure("Value <depth> must be >= -1") )
      .text("how many blocks to synchronize starting with head (use -1 for everything and 0 for only new ones)")

    help("help").text("prints this usage text")
  }

  private case class ArgumentsConfig(private val d: Int = -1, networks: Seq[String] = Seq()) {
    def depth: Depth = {
      d match {
        case -1 => Everything
        case 0 => Newest
        case n: Int => Custom(n)
      }
    }
  }

  /** Reads all configuration upstart, will print all errors encountered during loading */
  protected def loadApplicationConfiguration(commandLineArgs: Array[String]): Either[ConfigReaderFailures, CombinedConfiguration] = {
    //applies convention to uses CamelCase when reading config fields
    implicit def hint[T] = ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))

    val loadedConf = for {
      parsedCommandLineArgs <- readArgs(commandLineArgs)
      (network, depth) = parsedCommandLineArgs
      lorre <- loadConfig[LorreConfiguration](namespace = "lorre")
      nodeRequests <- loadConfig[NetworkCallsConfiguration]("")
      node <- loadConfig[TezosNodeConfiguration](namespace = s"platforms.tezos.$network.node")
      streamingClient <- loadAkkaStreamingClientConfig(namespace = "akka.tezos-streaming-client")
      fetching <- loadConfig[BatchFetchConfiguration](namespace = "batchedFetches")
    } yield CombinedConfiguration(lorre, TezosConfiguration(network, depth, node), nodeRequests, streamingClient, fetching)

    //something went wrong
    loadedConf.left.foreach {
      failures =>
        printConfigurationError(context = "Lorre application", failures.toList.mkString("\n\n"))
    }
    loadedConf
  }

  /** Use the pureconfig convention to handle configuration from the command line */
  protected def readArgs(args: Array[String]): Either[ConfigReaderFailures, (String, Depth)] = {

    val config: ArgumentsConfig = argsParser.parse(args, ArgumentsConfig()).getOrElse(sys.exit(1))
    val network = config.networks.head
    val depth = config.depth

    Right(network, depth)
  }
}

object LorreAppConfig {

  /** Collects all different aspects involved for Lorre */
  final case class CombinedConfiguration(
    lorre: LorreConfiguration,
    tezos: TezosConfiguration,
    nodeRequests: NetworkCallsConfiguration,
    streamingClientPool: HttpStreamingConfiguration,
    batching: BatchFetchConfiguration
  )

}