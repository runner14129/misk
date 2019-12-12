package misk.vitess

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.Volume
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.async.ResultCallbackTemplate
import com.github.dockerjava.netty.NettyDockerCmdExecFactory
import com.squareup.moshi.Moshi
import com.zaxxer.hikari.util.DriverDataSource
import misk.backoff.DontRetryException
import misk.backoff.ExponentialBackoff
import misk.backoff.retry
import misk.environment.Environment
import misk.jdbc.DataSourceConfig
import misk.jdbc.DataSourceType
import misk.jdbc.uniqueInt
import misk.moshi.adapter
import misk.resources.ResourceLoader
import mu.KotlinLogging
import okio.buffer
import okio.source
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.reflect.KClass
import kotlin.streams.toList

class Table
class Keyspace(val sharded: Boolean, val tables: Map<String, Table>) {
  // Defaulting to 2 shards for sharded keyspaces,
  // maybe this should be configurable at some point?
  fun shardCount() = if (sharded) 2 else 1
}

class VitessCluster(
  val name: String,
  resourceLoader: ResourceLoader,
  val config: DataSourceConfig,
  val moshi: Moshi = Moshi.Builder().build()
) {
  val schemaDir: Path
  val configDir: Path

  init {
    if (config.vitess_schema_dir != null) {
      schemaDir = Paths.get(config.vitess_schema_dir)
      DockerVitessCluster.logger.warn {
        "vitess_schema_dir is deprecated, use vitess_schema_resource_root instead"
      }
      check(Files.isDirectory(schemaDir)) {
        "can't find directory $schemaDir"
      }
    } else {
      val root = config.vitess_schema_resource_root
          ?: throw IllegalStateException("vitess_schema_resource_root must be specified")
      val hasVschema = resourceLoader.walk(config.vitess_schema_resource_root)
          .any { it.endsWith("vschema.json") }
      check(hasVschema) {
        "schema root not valid, does not contain any vschema.json: ${config.vitess_schema_resource_root}"
      }
      // We can't use Files::createTempDirectory because it creates a directory under the path
      // /var/folders that is not possible to mount in Docker
      schemaDir = Paths.get(
          "/tmp/vitess_schema_${System.currentTimeMillis()}")
      Files.createDirectories(schemaDir)
      resourceLoader.copyTo(root, schemaDir)
      Runtime.getRuntime().addShutdownHook(
          thread(start = false) {
            schemaDir.toFile().deleteRecursively()
          })
    }

    // Copy out all the resources from the current package
    // We use the my.cnf configuration file to configure MySQL (e.g. the default time zone)
    configDir =
        Paths.get("/tmp/vitess_conf_${System.currentTimeMillis()}")
    Files.createDirectories(configDir)
    resourceLoader.copyTo("classpath:/misk/vitess", configDir)
    Runtime.getRuntime().addShutdownHook(
        thread(start = false) {
          configDir.toFile().deleteRecursively()
        })
  }

  val keyspaceAdapter = moshi.adapter<Keyspace>()

  fun keyspaces(): Map<String, Keyspace> {
    val keyspaceDirs = Files.list(schemaDir)
        .toList().filter { Files.isDirectory(it) }
    return keyspaceDirs.associateBy(
        { it.fileName.toString() },
        { keyspaceAdapter.fromJson(it.resolve("vschema.json").source().buffer())!! })
  }

  /**
   * Connect to vtgate.
   */
  fun openVtgateConnection() = dataSource().connection

  /**
   * Connect to the underlying MySQL database, bypassing Vitess entirely.
   */
  fun openMysqlConnection() = mysqlDataSource().connection

  private fun dataSource(): DriverDataSource {
    val jdbcUrl = config.withDefaults().buildJdbcUrl(
        Environment.TESTING)
    return DriverDataSource(
        jdbcUrl, config.type.driverClassName, Properties(),
        config.username, config.password)
  }

  private fun mysqlConfig() =
      DataSourceConfig(
          type = DataSourceType.MYSQL,
          host = "127.0.0.1",
          username = "vt_dba",
          port = mysqlPort
      )

  private fun mysqlDataSource(): DriverDataSource {
    val config = mysqlConfig()
    val jdbcUrl = config.buildJdbcUrl(Environment.TESTING)
    return DriverDataSource(
        jdbcUrl, config.type.driverClassName, Properties(),
        config.username, config.password)
  }

  val httpPort = 27000
  val grpcPort = httpPort + 1
  val mysqlPort = httpPort + 2
  val vtgateMysqlPort = httpPort + 3
}

class DockerVitessCluster(
  val name: String,
  val moshi: Moshi,
  val resourceLoader: ResourceLoader,
  val config: DataSourceConfig,
  val docker: DockerClient
) : DatabaseServer {
  val cluster: VitessCluster

  private var containerId: String? = null

  private var isRunning = false
  private var stopContainerOnExit = true
  private var startupFailure: Exception? = null

  init {
    cluster = VitessCluster(
        name = name,
        resourceLoader = resourceLoader,
        config = config,
        moshi = moshi)
  }

  override fun start() {
    val startupFailure = this.startupFailure
    if (startupFailure != null) {
      throw startupFailure
    }
    if (isRunning) {
      return
    }

    isRunning = true
    try {
      doStart()
    } catch (e: Exception) {
      this.startupFailure = e
      throw e
    }
  }

  companion object {
    val logger = KotlinLogging.logger {}

    const val VITESS_SHA = "f3254d1287e526ce6cc1906b38862e4550bfdc110b44ef6e32905dc3ca2fb9bd"
    const val VITESS_IMAGE = "vitess/base@sha256:$VITESS_SHA"
    const val CONTAINER_NAME_PREFIX = "misk-vitess-testing"

    fun pullImage() {
      if (imagePulled.get()) {
        return
      }

      synchronized(this) {
        if (imagePulled.get()) {
          return
        }

        if (runCommand(
                "docker images --digests | grep -q $VITESS_SHA || docker pull $VITESS_IMAGE") != 0) {
          logger.warn("Failed to pull Vitess docker image. Proceeding regardless.")
        }
        imagePulled.set(true)
      }
    }

    private val imagePulled = AtomicBoolean()

    /**
     * A helper method to start the Vitess cluster outside of the dev server or test process, to
     * enable rapid iteration. This should be called directly a `main()` function, for example:
     *
     * MyAppVitessDaemon.kt:
     *
     *  fun main() {
     *    val config = MiskConfig.load<MyAppConfig>("myapp", Environment.TESTING)
     *    startVitessDaemon(MyAppDb::class, config.data_source_clusters.values.first().writer)
     *  }
     *
     */
    fun startVitessDaemon(
      /** The same qualifier passed into [HibernateModule], used to uniquely name the container */
      qualifier: KClass<out Annotation>,
      /** Config for the Vitess cluster */
      config: DataSourceConfig
    ) {
      val docker: DockerClient = DockerClientBuilder.getInstance()
          .withDockerCmdExecFactory(NettyDockerCmdExecFactory())
          .build()
      val moshi = Moshi.Builder().build()
      val dockerCluster =
          DockerVitessCluster(
              name = qualifier.simpleName!!,
              config = config,
              resourceLoader = ResourceLoader.SYSTEM,
              moshi = moshi,
              docker = docker)
      Runtime.getRuntime().addShutdownHook(Thread {
        dockerCluster.stop()
      })
      dockerCluster.start()
    }
  }

  override fun pullImage() {
    DockerVitessCluster.pullImage()
  }

  private fun doStart() {
    val keyspaces = cluster.keyspaces()
    val keyspacesArg = keyspaces.keys.map { it }.joinToString(",")
    val shardCounts = keyspaces.values.map { it.shardCount() }.joinToString(",")

    val schemaVolume =
        Volume("/vt/src/vitess.io/vitess/schema")
    val confVolume = Volume(
        "/vt/src/vitess.io/vitess/config/miskcnf")
    val httpPort =
        ExposedPort.tcp(cluster.httpPort)
    if (cluster.config.type == DataSourceType.VITESS) {
      if (cluster.config.port != null && cluster.config.port != cluster.grpcPort) {
        throw RuntimeException(
            "Config port ${cluster.config.port} has to match Vitess Docker container: ${cluster.grpcPort}")
      }
    }
    if (cluster.config.type == DataSourceType.VITESS_MYSQL) {
      if (cluster.config.port != null && cluster.config.port != cluster.vtgateMysqlPort) {
        throw RuntimeException(
            "Config port ${cluster.config.port} has to match Vitess Docker container: ${cluster.grpcPort}")
      }
    }
    val grpcPort =
        ExposedPort.tcp(cluster.grpcPort)
    val mysqlPort =
        ExposedPort.tcp(cluster.mysqlPort)
    val vtgateMysqlPort =
        ExposedPort.tcp(cluster.vtgateMysqlPort)
    val ports = Ports()
    ports.bind(grpcPort,
        Ports.Binding.bindPort(grpcPort.port))
    ports.bind(httpPort,
        Ports.Binding.bindPort(httpPort.port))
    ports.bind(mysqlPort,
        Ports.Binding.bindPort(mysqlPort.port))
    ports.bind(vtgateMysqlPort,
        Ports.Binding.bindPort(
            vtgateMysqlPort.port))

    val cmd = arrayOf(
        "/vt/bin/vttestserver",
        "-alsologtostderr",
        "-port=" + httpPort.port,
        "-web_dir=web/vtctld/app",
        "-web_dir2=web/vtctld2/app",
        "-mysql_bind_host=0.0.0.0",
        "-data_dir=/vt/vtdataroot",
        "-schema_dir=schema",
        // Increase the transaction timeout so you can have a breakpoint
        // inside a transaction without it timing out
        "-queryserver-config-transaction-timeout=${Duration.ofHours(
            24).toMillis()}",
        "-extra_my_cnf=" +
            listOf(
                "/vt/src/vitess.io/vitess/config/mycnf/rbr.cnf",
                "/vt/src/vitess.io/vitess/config/miskcnf/misk.cnf"
            ).joinToString(":"),
        "-keyspaces=$keyspacesArg",
        "-num_shards=$shardCounts"
    )

    val containerName = "$CONTAINER_NAME_PREFIX-${cluster.name}"

    val runningContainer = docker.listContainersCmd()
        .withNameFilter(listOf(containerName))
        .withLimit(1)
        .exec()
        .firstOrNull()
    if (runningContainer != null) {
      if (runningContainer.state != "running") {
        logger.info("Existing Vitess cluster named $containerName found in " +
            "state ${runningContainer.state}, force removing and restarting")
        docker.removeContainerCmd(runningContainer.id).withForce(true).exec()
      } else {
        logger.info("Using existing Vitess cluster named $containerName")
        stopContainerOnExit = false
        containerId = runningContainer.id
      }
    }

    if (containerId == null) {
      logger.info(
          "Starting Vitess cluster with command: ${cmd.joinToString(" ")}")
      containerId = docker.createContainerCmd(VITESS_IMAGE)
          .withCmd(cmd.toList())
          .withVolumes(schemaVolume, confVolume)
          .withBinds(
              Bind(
                  cluster.schemaDir.toAbsolutePath().toString(), schemaVolume),
              Bind(
                  cluster.configDir.toAbsolutePath().toString(), confVolume)
          )
          .withExposedPorts(httpPort, grpcPort, mysqlPort, vtgateMysqlPort)
          .withPortBindings(ports)
          .withTty(true)
          .withName(containerName)
          .exec().id!!
      val containerId = containerId!!
      docker.startContainerCmd(containerId).exec()
      docker.logContainerCmd(containerId)
          .withStdErr(true)
          .withStdOut(true)
          .withFollowStream(true)
          .withSince(0)
          .exec(LogContainerResultCallback())
          .awaitStarted()
    }
    logger.info("Started Vitess with container id $containerId")

    waitUntilHealthy()

    grantExternalAccessToDbaUser()

    turnOnGeneralLog()
  }

  private fun waitUntilHealthy() {
    try {
      retry(20, ExponentialBackoff(
          Duration.ofSeconds(1),
          Duration.ofSeconds(5))) {
        cluster.openVtgateConnection().use { c ->
          try {
            val result =
                c.createStatement().executeQuery("SELECT 1 FROM dual").uniqueInt()
            check(result == 1)
          } catch (e: Exception) {
            val message = e.message
            if (message?.contains("table dual not found") == true) {
              throw DontRetryException(
                  "Something is wrong with your vschema and unfortunately vtcombo does not " +
                      "currently have good error reporting on this. Please inspect the logs or your " +
                      "vschema to see if you can find the error.")
            } else {
              throw e
            }
          }
        }
      }
    } catch (e: DontRetryException) {
      throw Exception(e.message)
    } catch (e: Exception) {
      throw Exception("Vitess cluster failed to start up in time", e)
    }
  }

  /**
   * Grants external access to the vt_dba user so that we can access the general_log file.
   */
  private fun grantExternalAccessToDbaUser() {
    val exec = docker.execCreateCmd(containerId!!)
        .withAttachStderr(true)
        .withAttachStdout(true)
        .withCmd("mysql",
            "-S", "/vt/vtdataroot/vt_0000000001/mysql.sock",
            "-u", "root",
            "mysql",
            "-e",
            "grant all on *.* to 'vt_dba'@'%'; grant REPLICATION CLIENT, REPLICATION SLAVE on *.* to 'vt_app'@'%'")
        .exec()

    docker.execStartCmd(exec.id)
        .exec(LogContainerResultCallback())
        .awaitCompletion()

    val exitCode = docker.inspectExecCmd(exec.id).exec().exitCode
    if (exitCode != 0) {
      throw RuntimeException("Command failed, see log for details")
    }
  }

  /**
   * Turn on MySQL general_log so that we can inspect it in the VitessScaleSafetyChecks detectors.
   */
  private fun turnOnGeneralLog() {
    cluster.openMysqlConnection().use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("SET GLOBAL log_output = 'TABLE'")
        statement.execute("SET GLOBAL general_log = 1")
      }
    }
  }

  override fun stop() {
    if (!isRunning) {
      return
    }
    isRunning = false

    if (!stopContainerOnExit) {
      return
    }

    docker.removeContainerCmd(containerId!!).withForce(true).withRemoveVolumes(true).exec()
    logger.info("Killed Vitess cluster with container id $containerId")
  }

  class LogContainerResultCallback : ResultCallbackTemplate<LogContainerResultCallback, Frame>() {
    override fun onNext(item: Frame) {
      logger.info(String(item.payload).trim())
    }
  }
}
