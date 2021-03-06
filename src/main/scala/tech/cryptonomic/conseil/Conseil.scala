package tech.cryptonomic.conseil

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directive
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import cats.effect.{ContextShift, IO}
import cats.implicits._
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import tech.cryptonomic.conseil.config.{
  ConseilAppConfig,
  MetadataConfiguration,
  NautilusCloudConfiguration,
  Security,
  ServerConfiguration,
  VerboseOutput
}
import tech.cryptonomic.conseil.config.Platforms.PlatformsConfiguration
import tech.cryptonomic.conseil.config.Security.SecurityApi
import tech.cryptonomic.conseil.directives.EnableCORSDirectives
import tech.cryptonomic.conseil.io.MainOutputs.ConseilOutput
import tech.cryptonomic.conseil.metadata.{AttributeValuesCacheConfiguration, MetadataService, UnitTransformation}
import tech.cryptonomic.conseil.routes._
import tech.cryptonomic.conseil.tezos.{ApiOperations, MetadataCaching, TezosPlatformDiscoveryOperations}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}
import scala.util.{Failure, Success, Try}
import tech.cryptonomic.conseil.util.Retry.retry
import scala.language.postfixOps

object Conseil
    extends App
    with LazyLogging
    with EnableCORSDirectives
    with ConseilAppConfig
    with FailFastCirceSupport
    with ConseilOutput {

  loadApplicationConfiguration(args) match {
    case Left(errors) =>
    //nothing to do
    case Right((server, platforms, securityApi, failFast, verbose, metadataOverrides, nautilusCloud)) =>
      implicit val system: ActorSystem = ActorSystem("conseil-system")
      implicit val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext: ExecutionContextExecutor = system.dispatcher
      lazy val api = new ApiOperations

      val retries = if (failFast.on) Some(0) else None

      val serverBinding =
        retry(maxRetry = retries, deadline = Some(server.startupDeadline fromNow))(
          initServices(api, server, platforms, metadataOverrides, nautilusCloud).get
        ).andThen {
          case Failure(error) =>
            logger.error(
              "The server was not started correctly, I failed to create the required Metadata service",
              error
            )
            Await.ready(system.terminate(), 10.seconds)
        }.flatMap(
          runServer(_, api, server, platforms, metadataOverrides, securityApi, verbose)
        )

      sys.addShutdownHook {
        serverBinding
          .flatMap(_.unbind().andThen { case _ => logger.info("Server stopped...") })
          .andThen {
            case _ => system.terminate()
          }
          .onComplete(_ => logger.info("We're done here, nothing else to see"))
      }

  }

  /** Reads configuration to setup the fundamental application services
    * @param server configuration needed for the http server
    * @param platforms configuration regarding the exposed blockchains available
    * @param metadataOverrides rules for customizing metadata presentation to the client
    * @param nautilusCloud defines the rules to update nautilus configuration (e.g. api key updates)
    * @return a metadata services object or a failed result
    */
  def initServices(
      apiOperations: ApiOperations,
      server: ServerConfiguration,
      platforms: PlatformsConfiguration,
      metadataOverrides: MetadataConfiguration,
      nautilusCloud: Option[NautilusCloudConfiguration]
  )(implicit executionContext: ExecutionContext, system: ActorSystem, mat: ActorMaterializer): Try[MetadataService] = {

    nautilusCloud.foreach { ncc =>
      system.scheduler.schedule(ncc.delay, ncc.interval)(Security.updateKeys(ncc))
    }

    // This part is a temporary middle ground between current implementation and moving code to use IO
    implicit val contextShift: ContextShift[IO] = IO.contextShift(executionContext)
    val metadataCaching = MetadataCaching.empty[IO].unsafeRunSync()

    lazy val transformation = new UnitTransformation(metadataOverrides)
    lazy val cacheOverrides = new AttributeValuesCacheConfiguration(metadataOverrides)

    lazy val tezosPlatformDiscoveryOperations =
      TezosPlatformDiscoveryOperations(
        apiOperations,
        metadataCaching,
        cacheOverrides,
        server.cacheTTL,
        server.highCardinalityLimit
      )

    tezosPlatformDiscoveryOperations.init().onComplete {
      case Failure(exception) => logger.error("Pre-caching metadata failed", exception)
      case Success(_) => logger.info("Pre-caching successful!")
    }

    tezosPlatformDiscoveryOperations.initAttributesCache.onComplete {
      case Failure(exception) => logger.error("Pre-caching attributes failed", exception)
      case Success(_) => logger.info("Pre-caching attributes successful!")
    }

    // this val is not lazy to force to fetch metadata and trigger logging at the start of the application
    Try(new MetadataService(platforms, transformation, cacheOverrides, tezosPlatformDiscoveryOperations))
  }

  /** Starts the web server
    * @param metadataService the metadata information to build the querying functionality
    * @param server configuration needed for the http server
    * @param platforms configuration regarding the exposed blockchains available
    * @param securityApi configuration to set cryptographic libraries usage
    * @param verbose flag to state if the server should log a more detailed configuration setup upon startup
    */
  def runServer(
      metadataService: MetadataService,
      apiOperations: ApiOperations,
      server: ServerConfiguration,
      platforms: PlatformsConfiguration,
      metadataOverrides: MetadataConfiguration,
      securityApi: SecurityApi,
      verbose: VerboseOutput
  )(implicit executionContext: ExecutionContext, system: ActorSystem, mat: ActorMaterializer) = {
    val tezosDispatcher = system.dispatchers.lookup("akka.tezos-dispatcher")

    lazy val platformDiscovery = PlatformDiscovery(metadataService)
    lazy val data = Data(metadataService, server, metadataOverrides, apiOperations)(tezosDispatcher)

    val validateApiKey: Directive[Tuple1[String]] = optionalHeaderValueByName("apikey").tflatMap[Tuple1[String]] {
      apiKeyTuple =>
        val apiKey = apiKeyTuple match {
          case Tuple1(key) => key
          case _ => None
        }

        onComplete(securityApi.validateApiKey(apiKey)).flatMap {
          case Success(true) => provide(apiKey.getOrElse(""))
          case _ =>
            complete((Unauthorized, apiKey.fold("Missing API key") { _ =>
              "Incorrect API key"
            }))
        }
    }

    val route = concat(
      pathPrefix("docs") {
        pathEndOrSingleSlash {
          getFromResource("web/index.html")
        }
      },
      pathPrefix("swagger-ui") {
        getFromResourceDirectory("web/swagger-ui/")
      },
      Docs.route,
      cors() {
        enableCORS {
          concat(
            validateApiKey { _ =>
              concat(
                logRequest("Conseil", Logging.DebugLevel) {
                  AppInfo.route
                },
                logRequest("Metadata Route", Logging.DebugLevel) {
                  platformDiscovery.route
                },
                logRequest("Data Route", Logging.DebugLevel) {
                  data.getRoute ~ data.postRoute
                }
              )
            },
            options {
              // Support for CORS pre-flight checks.
              complete("Supported methods : GET and POST.")
            }
          )
        }
      }
    )

    val bindingFuture = Http().bindAndHandle(route, server.hostname, server.port)
    displayInfo(server)
    if (verbose.on) displayConfiguration(platforms)

    bindingFuture
  }

}
