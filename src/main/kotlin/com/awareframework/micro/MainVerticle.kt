package com.awareframework.micro

import com.mitchellbosecke.pebble.PebbleEngine
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.OpenOptions
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.PemTrustOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.templ.pebble.PebbleTemplateEngine
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLPool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Tuple
import io.vertx.core.CompositeFuture


class MainVerticle : AbstractVerticle() {
  private lateinit var mySQLClient: MySQLPool

  private val logger = KotlinLogging.logger {}

  private lateinit var parameters: JsonObject
  private lateinit var httpServer: HttpServer

  override fun start(startPromise: Promise<Void>) {

    logger.info { "AWARE Micro initializing..." }

    val serverOptions = HttpServerOptions()
        .setMaxWebSocketMessageSize(1024 * 1024 * 20)
    logger.info { "Server options initialized with increased limits" }

    val pebbleEngine = PebbleTemplateEngine.create(vertx, PebbleEngine.Builder().cacheActive(false).build())
    logger.info { "Pebble engine created" }

    val eventBus = vertx.eventBus()
    logger.info { "EventBus initialized" }

    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create().setBodyLimit(1024 * 1024 * 50))
    logger.info { "BodyHandler and router set up" }

    router.route("/cache/*").handler(StaticHandler.create("cache"))
    logger.info { "StaticHandler set up for /cache/*" }

    router.route().handler {
      logger.info { "Processing ${it.request().scheme()} ${it.request().method()} : ${it.request().path()} with the following data ${it.request().params().toList()}" }
      it.next()
    }

    router.route().failureHandler { context ->
      val failure = context.failure()
      logger.error(failure) { "Unhandled exception occurred" }
      context.response().setStatusCode(500).end("Internal Server Error")
    }
  

    val configJsonFile = ConfigStoreOptions()
      .setType("file")
      .setFormat("json")
      .setConfig(JsonObject().put("path", "./aware-config.json"))
    logger.info { "Config store options initialized" }

    val configRetrieverOptions = ConfigRetrieverOptions()
      .setScanPeriod(5000)
      .addStore(configJsonFile)
    logger.info { "Config retriever options initialized" }

    val configReader = ConfigRetriever.create(vertx, configRetrieverOptions)
    logger.info { "Config retriever created" }
    configReader.getConfig { config ->

      if (config.succeeded() && config.result().containsKey("server")) {
        logger.info { "Configuration successfully retrieved" }
        parameters = config.result()
        val serverConfig = parameters.getJsonObject("server")
        val study = parameters.getJsonObject("study")
        logger.info { "Server and study configuration loaded" }

        initDatabase()

        // HttpServerOptions.host is the host to listen on. So using |server_host|, not |external_server_host| here.
        // See also: https://vertx.io/docs/4.3.3/apidocs/io/vertx/core/net/NetServerOptions.html#DEFAULT_HOST
        serverOptions.host = serverConfig.getString("server_host")
        logger.info { "Server host set to ${serverConfig.getString("server_host")}" }


        /**
         * Generate QRCode to join the study using Google's Chart API
         */
        router.route(HttpMethod.GET, "/:studyNumber/:studyKey").handler { route ->
        logger.info {"GET received /:studyNumber/:studyKey"}
          if (validRoute(
              study,
              route.request().getParam("studyNumber").toInt(),
              route.request().getParam("studyKey")
            )
          ) {
            vertx.fileSystem().delete("./cache/qrcode.png") {
              if (it.succeeded()) logger.info { "Cleared old qrcode..." }
            }
            vertx.fileSystem().open(
              "./cache/qrcode.png",
              OpenOptions().setTruncateExisting(true).setCreate(true).setWrite(true)
            ) { write ->
              if (write.succeeded()) {
                val asyncQrcode = write.result()
                val webClientOptions = WebClientOptions()
                  .setKeepAlive(true)
                  .setPipelining(true)
                  .setFollowRedirects(true)
                  .setSsl(true)
                  .setTrustAll(true)

                val client = WebClient.create(vertx, webClientOptions)
                val serverURL =
                  "${getExternalServerHost(serverConfig)}:${getExternalServerPort(serverConfig)}/index.php/${study.getInteger(
                    "study_number"
                  )}/${study.getString("study_key")}"

                logger.info { "URL encoded for the QRCode is: $serverURL" }

                client.get(
                  443, "qrcode.tec-it.com",
                  "/API/QRCode?size=small&data=$serverURL"
                )
                  .`as`(BodyCodec.pipe(asyncQrcode, true))
                  .send { request ->
                    if (request.succeeded()) {
                      pebbleEngine.render(JsonObject().put("studyURL", serverURL), "templates/qrcode.peb") { pebble ->
                        if (pebble.succeeded()) {
                          route.response().statusCode = 200
                          route.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html").end(pebble.result())
                        }
                      }
                    } else {
                      logger.error(request.cause()) { "QRCode creation failed." }
                    }
                  }
              }
            }
          }
        }

        /**
         * This route is called:
         * - when joining the study, returns the JSON with all the settings from the study. Can be called from apps using Aware.joinStudy(URL) or client's QRCode scanner
         * - when checking study status with the study_check=1.
         */
        router.route(HttpMethod.POST, "/index.php/:studyNumber/:studyKey").handler { route ->
        logger.info {
            "/index.php/:studyNumber/:studyKey POST"
          }
          if (validRoute(
              study,
              route.request().getParam("studyNumber").toInt(),
              route.request().getParam("studyKey")
            )
          ) {
            if (route.request().getFormAttribute("study_check") == "1") {
              val status = JsonObject()
              status.put("status", study.getBoolean("study_active"))
              status.put(
                "config",
                "[]"
              ) //NOTE: if we send the configuration, it will keep reapplying the settings on legacy clients. Sending empty JsonArray (i.e., no changes)
              route.response().end(JsonArray().add(status).encode())
              route.next()
            } else {
              logger.info { "Study configuration: ${getStudyConfig().encodePrettily()}" }
              route.response().end(getStudyConfig().encode())
            }
          } else {
            route.response().statusCode = 401
            route.response().end()
          }
        }

        /**
         * Legacy: this will be hit by legacy client to retrieve the study information. It retuns JsonObject with (defined also in aware-config.json on AWARE Micro):
        {
        "study_key" : "studyKey",
        "study_number" : 1,
        "study_name" : "AWARE Micro demo study",
        "study_description" : "This is a demo study to test AWARE Micro",
        "researcher_first" : "First Name",
        "researcher_last" : "Last Name",
        "researcher_contact" : "your@email.com"
        }
         */
        router.route(HttpMethod.GET, "/index.php/webservice/client_get_study_info/:studyKey").handler { route ->
          if (route.request().getParam("studyKey") == study.getString("study_key")) {
            route.response().end(study.encode())
          } else {
            route.response().statusCode = 401
            route.response().end()
          }
        }

        router.route(HttpMethod.POST, "/index.php/:studyNumber/:studyKey/:table/:operation").handler { route ->
          // Log the incoming request
          logger.info { "Incoming POST request: studyNumber=${route.request().getParam("studyNumber")}, studyKey=${route.request().getParam("studyKey")}, table=${route.request().getParam("table")}, operation=${route.request().getParam("operation")}" }

          // Validate the route
          if (validRoute(
              study,
              route.request().getParam("studyNumber").toInt(),
              route.request().getParam("studyKey")
            )
          ) {
            logger.info { "Route validation successful for studyNumber=${route.request().getParam("studyNumber")} and studyKey=${route.request().getParam("studyKey")}" }

            when (route.request().getParam("operation")) {
              "create_table" -> {
                logger.info { "Operation: create_table for table=${route.request().getParam("table")}" }
                // Commented the following line as we merged with insert. Only here so that legacy client thinks all is ok
                // eventBus.publish("createTable", route.request().getParam("table"))
                route.response().statusCode = 200
                route.response().end()
              }
              "insert" -> {
                val table = route.request().getParam("table")
                val deviceId = route.request().getFormAttribute("device_id")
                val data = route.request().getFormAttribute("data")

                logger.info { "Operation: insert. Table=$table, DeviceId=$deviceId, Data=$data" }

                eventBus.publish(
                  "insertData",
                  JsonObject()
                    .put("table", table)
                    .put("device_id", deviceId)
                    .put("data", data)
                )
                route.response().statusCode = 200
                route.response().end()
                logger.info { "Insert operation published to EventBus for table=$table, deviceId=$deviceId" }
              }
              "update" -> {
                val table = route.request().getParam("table")
                val deviceId = route.request().getFormAttribute("device_id")
                val data = route.request().getFormAttribute("data")

                logger.info { "Operation: update. Table=$table, DeviceId=$deviceId, Data=$data" }

                eventBus.publish(
                  "updateData",
                  JsonObject()
                    .put("table", table)
                    .put("device_id", deviceId)
                    .put("data", data)
                )
                route.response().statusCode = 200
                route.response().end()
                logger.info { "Update operation published to EventBus for table=$table, deviceId=$deviceId" }
              }
              "delete" -> {
                val table = route.request().getParam("table")
                val deviceId = route.request().getFormAttribute("device_id")
                val data = route.request().getFormAttribute("data")

                logger.info { "Operation: delete. Table=$table, DeviceId=$deviceId, Data=$data" }

                eventBus.publish(
                  "deleteData",
                  JsonObject()
                    .put("table", table)
                    .put("device_id", deviceId)
                    .put("data", data)
                )
                route.response().statusCode = 200
                route.response().end()
                logger.info { "Delete operation published to EventBus for table=$table, deviceId=$deviceId" }
              }
              "query" -> {
                val table = route.request().getParam("table")
                val deviceId = route.request().getFormAttribute("device_id")
                val start = route.request().getFormAttribute("start").toDouble()
                val end = route.request().getFormAttribute("end").toDouble()

                logger.info { "Operation: query. Table=$table, DeviceId=$deviceId, Start=$start, End=$end" }

                val requestData = JsonObject()
                  .put("table", table)
                  .put("device_id", deviceId)
                  .put("start", start)
                  .put("end", end)

                eventBus.request<JsonArray>("getData", requestData) { response ->
                  if (response.succeeded()) {
                    logger.info { "Query successful for table=$table, deviceId=$deviceId. Result=${response.result().body()}" }
                    route.response().statusCode = 200
                    route.response().end(response.result().body().encode())
                  } else {
                    logger.error(response.cause()) { "Query failed for table=$table, deviceId=$deviceId" }
                    route.response().statusCode = 401
                    route.response().end()
                  }
                }
              }
              else -> {
                logger.warn { "Unsupported operation: ${route.request().getParam("operation")}" }
                route.response().statusCode = 401
                route.response().end()
              }
            }
          } else {
            logger.warn { "Route validation failed for studyNumber=${route.request().getParam("studyNumber")} and studyKey=${route.request().getParam("studyKey")}" }
            route.response().statusCode = 401
            route.response().end()
          }
        }



router.route().last().handler { ctx ->
    logger.warn { "Unhandled request: ${ctx.request().method()} ${ctx.request().path()} with data ${ctx.request().params().toList()}" }
    ctx.response().setStatusCode(404).end("Not Found")
}

router.route(HttpMethod.POST, "/").handler { ctx ->
    val body = ctx.bodyAsString
    val params = ctx.request().params()
    val deviceId = params.get("device_id")

    // 日志记录
    logger.info { "Processing POST / request with device_id: $deviceId" }

    if (deviceId != null) {
        // 返回 study 配置
        val config = getStudyConfig()
        logger.info { "Returning study configuration for device_id: $deviceId - ${config.encodePrettily()}" }

        ctx.response()
            .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .setStatusCode(200)
            .end(config.encode())
    } else {
        // 没提供 device_id 的error out
        logger.warn { "POST / request missing device_id parameter" }
        ctx.response()
            .setStatusCode(400)
            .end("Missing required parameter: device_id")
    }
}

router.route(HttpMethod.POST, "/:table/:operation").handler { route ->
    val table = route.request().getParam("table")
    val operation = route.request().getParam("operation")

    logger.info { "Matched POST request!: table=$table, operation=$operation" }
    logger.info { "Operation extracted from request: $operation" }





    when (operation) {
        "insert" -> {
          logger.info { "Received INSERT operation" }
    val table = route.request().getParam("table")
    val dataJson = route.request().getFormAttribute("data")
    logger.info { "Received insert request: table=$table, dataJson=$dataJson" }

    if (dataJson != null) {
        logger.info { "Raw data received for table `$table`: $dataJson" }

        try {
            val records = JsonArray(dataJson)
            logger.info { "Parsed data for table `$table`: ${records.encodePrettily()}" }
            val insertFutures = records.map { record ->
                val recordJson = record as JsonObject
                val columns = recordJson.fieldNames().joinToString(", ") { "`$it`" }
                val placeholders = recordJson.fieldNames().joinToString(", ") { "?" }
                val values = recordJson.fieldNames().map { recordJson.getValue(it) }

                val insertQuery = "INSERT INTO `$table` ($columns) VALUES ($placeholders)"

                logger.info { "Generated SQL for table `$table`: $insertQuery with values: $values" }

                mySQLClient.preparedQuery(insertQuery)
                    .execute(Tuple.wrap(values))
            }

            // 等待所有插入完成
            CompositeFuture.all(insertFutures)
                .onSuccess {
                    logger.info { "Successfully inserted data into table `$table`" }
                    route.response().setStatusCode(200).end("Insert successful")
                }
                .onFailure { error ->
                    logger.error(error) { "Insert operation failed for table=$table" }
                    route.response().setStatusCode(500).end("Insert failed")
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse `data` for insert operation in table `$table`" }
            route.response().setStatusCode(400).end("Invalid data format")
        }
    } else {
        logger.warn { "No data provided for insert operation in table `$table`" }
        route.response().setStatusCode(400).end("No data provided")
    }
}



        "create_table" -> {
            val fields = route.request().getFormAttribute("fields") ?: ""
            logger.info { "Create table operation: Table=$table, Fields=$fields" }

            if (fields.isNotEmpty()) {
                val fixedFields = fields
                    .replace("autoincrement", "AUTO_INCREMENT")
                    .replace("real", "DOUBLE")
                    .replace("text default ''", "TEXT")
                    .replace("trigger", "`trigger`")

                val createTableQuery = "CREATE TABLE IF NOT EXISTS `$table` ($fixedFields)"
                mySQLClient.query(createTableQuery)
                    .execute()
                    .onSuccess {
                        logger.info { "Table `$table` created successfully" }
                        route.response().setStatusCode(200).end("Table created successfully")
                    }
                    .onFailure { error ->
                        logger.error(error) { "Failed to create table `$table`" }
                        route.response().setStatusCode(500).end("Failed to create table")
                    }
            } else {
                logger.warn { "No fields provided for creating table `$table`" }
                route.response().setStatusCode(400).end("No fields provided")
            }
        }



        else -> {
            logger.warn { "Unsupported operation: $operation for table=$table" }
            route.response().setStatusCode(400).end("Unsupported operation")
        }
    }

}


        /**
         * Default route, landing page of the server
         */
        router.route(HttpMethod.GET, "/").handler { route ->
        logger.info {
            "/ GET"
          }
          route.response().putHeader("content-type", "text/html").end(
            "Hello from AWARE Micro!<br/>Join study: <a href=\"${getExternalServerHost(serverConfig)}:${getExternalServerPort(serverConfig)}/${study.getInteger(
              "study_number"
            )}/${study.getString("study_key")}\">HERE</a>"
          )
        }

        //Use SSL
        if (serverConfig.getString("path_fullchain_pem").isNotEmpty() && serverConfig.getString("path_key_pem").isNotEmpty()) {
          serverOptions.pemTrustOptions = PemTrustOptions().addCertPath(serverConfig.getString("path_fullchain_pem"))
          serverOptions.pemKeyCertOptions = PemKeyCertOptions()
            .setCertPath(serverConfig.getString("path_fullchain_pem"))
            .setKeyPath(serverConfig.getString("path_key_pem"))
          serverOptions.isSsl = true
        }

        httpServer = vertx.createHttpServer(serverOptions)
          .requestHandler(router)
          .listen(serverConfig.getInteger("server_port")) { server ->
            if (server.succeeded()) {
              when (serverConfig.getString("database_engine")) {
                "mysql" -> {
                  vertx.deployVerticle("com.awareframework.micro.MySQLVerticle")
                }
                "postgres" -> {
                  vertx.deployVerticle("com.awareframework.micro.PostgresVerticle")
                }
                else -> {
                  logger.info { "Not storing data into a database engine: mysql, postgres" }
                }
              }

              vertx.deployVerticle("com.awareframework.micro.WebsocketVerticle")

              logger.info { "AWARE Micro API at ${getExternalServerHost(serverConfig)}:${getExternalServerPort(serverConfig)}" }
              logger.info { "Serving study config: ${getStudyConfig()}" }
              startPromise.complete()
            } else {
              logger.error(server.cause()) { "AWARE Micro initialisation failed!" }
              startPromise.fail(server.cause())
            }
          }

        configReader.listen { change ->
          val newConfig = change.newConfiguration
          httpServer.close()

          val newServerConfig = newConfig.getJsonObject("server")
          val newServerOptions = HttpServerOptions()

          if (newServerConfig.getString("path_fullchain_pem").isNotEmpty() && newServerConfig.getString("path_key_pem").isNotEmpty()) {
            newServerOptions.pemTrustOptions =
              PemTrustOptions().addCertPath(newServerConfig.getString("path_fullchain_pem"))

            newServerOptions.pemKeyCertOptions = PemKeyCertOptions()
              .setCertPath(newServerConfig.getString("path_fullchain_pem"))
              .setKeyPath(newServerConfig.getString("path_key_pem"))
            newServerOptions.isSsl = true
          }

          httpServer = vertx.createHttpServer(newServerOptions)
            .requestHandler(router)
            .listen(newServerConfig.getInteger("server_port")) { server ->
              if (server.succeeded()) {
                when (newServerConfig.getString("database_engine")) {
                  "mysql" -> {
                    vertx.undeploy("com.awareframework.micro.MySQLVerticle")
                    vertx.deployVerticle("com.awareframework.micro.MySQLVerticle")
                  }
                  "postgres" -> {
                    vertx.undeploy("com.awareframework.micro.PostgresVerticle")
                    vertx.deployVerticle("com.awareframework.micro.PostgresVerticle")
                  }
                  else -> {
                    logger.info { "Not storing data into a database engine: mysql, postgres" }
                  }
                }

                vertx.undeploy("com.awareframework.micro.WebsocketVerticle")
                vertx.deployVerticle("com.awareframework.micro.WebsocketVerticle")

                logger.info { "AWARE Micro API at ${getExternalServerHost(newServerConfig)}:${getExternalServerPort(newServerConfig)}" }

              } else {
                logger.error(server.cause()) { "AWARE Micro initialisation failed!" }
              }
            }
        }

      } else { //this is a fresh instance, no server created yet.

        val configFile = JsonObject()

        //infrastructure info
        val server = JsonObject()
        server.put("database_engine", "mysql") //[mysql, postgres]
        server.put("database_host", "localhost")
        server.put("database_name", "studyDatabase")
        server.put("database_user", "databaseUser")
        server.put("database_pwd", "databasePassword")
        server.put("database_port", 3306)
        server.put("server_host", "http://localhost")
        server.put("server_port", 8080)
        server.put("websocket_port", 8081)
        server.put("path_fullchain_pem", "")
        server.put("path_key_pem", "")
        configFile.put("server", server)

        //study info
        val study = JsonObject()
        study.put("study_key", "4lph4num3ric")
        study.put("study_number", 1)
        study.put("study_name", "AWARE Micro demo study")
        study.put("study_active", true)
        study.put("study_start", System.currentTimeMillis())
        study.put("study_description", "This is a demo study to test AWARE Micro")
        study.put("researcher_first", "First Name")
        study.put("researcher_last", "Last Name")
        study.put("researcher_contact", "your@email.com")
        configFile.put("study", study)

        //AWARE framework settings from both sensors and plugins
        val sensors =
          getSensors("https://raw.githubusercontent.com/denzilferreira/aware-client/master/aware-core/src/main/res/xml/aware_preferences.xml")

        configFile.put("sensors", sensors)

        val pluginsList = HashMap<String, String>()
        pluginsList["com.aware.plugin.ambient_noise"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.ambient_noise/master/com.aware.plugin.ambient_noise/src/main/res/xml/preferences_ambient_noise.xml"
        pluginsList["com.aware.plugin.contacts_list"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.contacts_list/master/com.aware.plugin.contacts_list/src/main/res/xml/preferences_contacts_list.xml"
        pluginsList["com.aware.plugin.device_usage"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.device_usage/master/com.aware.plugin.device_usage/src/main/res/xml/preferences_device_usage.xml"
        pluginsList["com.aware.plugin.esm.scheduler"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.esm.scheduler/master/com.aware.plugin.esm.scheduler/src/main/res/xml/preferences_esm_scheduler.xml"
        pluginsList["com.aware.plugin.fitbit"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.fitbit/master/com.aware.plugin.fitbit/src/main/res/xml/preferences_fitbit.xml"
        pluginsList["com.aware.plugin.google.activity_recognition"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.google.activity_recognition/master/com.aware.plugin.google.activity_recognition/src/main/res/xml/preferences_activity_recog.xml"
        pluginsList["com.aware.plugin.google.auth"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.google.auth/master/com.aware.plugin.google.auth/src/main/res/xml/preferences_google_auth.xml"
        pluginsList["com.aware.plugin.google.fused_location"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.google.fused_location/master/com.aware.plugin.google.fused_location/src/main/res/xml/preferences_fused_location.xml"
        pluginsList["com.aware.plugin.openweather"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.openweather/master/com.aware.plugin.openweather/src/main/res/xml/preferences_openweather.xml"
        pluginsList["com.aware.plugin.sensortag"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.sensortag/master/com.aware.plugin.sensortag/src/main/res/xml/preferences_sensortag.xml"
        pluginsList["com.aware.plugin.sentimental"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.sentimental/master/com.aware.plugin.sentimental/src/main/res/xml/preferences_sentimental.xml"
        pluginsList["com.aware.plugin.studentlife.audio_final"] =
          "https://raw.githubusercontent.com/denzilferreira/com.aware.plugin.studentlife.audio_final/master/com.aware.plugin.studentlife.audio/src/main/res/xml/preferences_conversations.xml"

        val plugins = getPlugins(pluginsList)
        configFile.put("plugins", plugins)

        vertx.fileSystem().writeFile("./aware-config.json", Buffer.buffer(configFile.encodePrettily())) { result ->
          if (result.succeeded()) {
            logger.info { "You can now configure your server by editing the aware-config.json that was automatically created. You can now stop this instance (press Ctrl+C)" }
          } else {
            logger.error(result.cause()) { "Failed to create aware-config.json." }
          }
        }
      }
    }
  }
  private fun initDatabase() {
    val dbConfig = parameters.getJsonObject("server")
    val connectOptions = MySQLConnectOptions()
        .setPort(dbConfig.getInteger("database_port"))
        .setHost(dbConfig.getString("database_host"))
        .setDatabase(dbConfig.getString("database_name"))
        .setUser(dbConfig.getString("database_user"))
        .setPassword(dbConfig.getString("database_pwd"))

    val poolOptions = PoolOptions().setMaxSize(5)

    mySQLClient = MySQLPool.pool(vertx, connectOptions, poolOptions)
    logger.info { "MySQL client initialized" }
}




  /**
   * Check valid study key and number
   */
  fun validRoute(studyInfo: JsonObject, studyNumber: Int, studyKey: String): Boolean {
    logger.info {
            "CHECKING valid route"
          }
    logger.info {studyNumber == studyInfo.getInteger("study_number") && studyKey == studyInfo.getString("study_key")}
    return studyNumber == studyInfo.getInteger("study_number") && studyKey == studyInfo.getString("study_key")
  }

  

  fun getStudyConfig(): JsonArray {
    val serverConfig = parameters.getJsonObject("server")
    //println("Server config: ${serverConfig.encodePrettily()}")

    val study = parameters.getJsonObject("study")
    //println("Study info: ${study.encodePrettily()}")

    val sensors = JsonArray()
    val plugins = JsonArray()

    val awareSensors = parameters.getJsonArray("sensors")
    for (i in 0 until awareSensors.size()) {
      val awareSensor = awareSensors.getJsonObject(i)
      val sensorSettings = awareSensor.getJsonArray("settings")
      for (j in 0 until sensorSettings.size()) {
        val setting = sensorSettings.getJsonObject(j)

        val awareSetting = JsonObject()
        awareSetting.put("setting", setting.getString("setting"))

        when (setting.getString("setting")) {
          "status_webservice" -> awareSetting.put("value", "true")
          "webservice_server" -> awareSetting.put(
            "value",
            "${getExternalServerHost(serverConfig)}:${getExternalServerPort(serverConfig)}/index.php/${study.getInteger(
              "study_number"
            )}/${study.getString("study_key")}"
          )
          else -> awareSetting.put("value", setting.getString("defaultValue"))
        }
        sensors.add(awareSetting)
      }
    }

    var awareSetting = JsonObject()
    awareSetting.put("setting", "study_id")
    awareSetting.put("value", study.getString("study_key"))
    sensors.add(awareSetting)

    awareSetting = JsonObject()
    awareSetting.put("setting", "study_start")
    awareSetting.put("value", study.getDouble("study_start"))
    sensors.add(awareSetting)

    val awarePlugins = parameters.getJsonArray("plugins")
    for (i in 0 until awarePlugins.size()) {
      val awarePlugin = awarePlugins.getJsonObject(i)
      val pluginSettings = awarePlugin.getJsonArray("settings")

      val pluginOutput = JsonObject()
      pluginOutput.put("plugin", awarePlugin.getString("package_name"))

      val pluginSettingsOutput = JsonArray()
      for (j in 0 until pluginSettings.size()) {
        val setting = pluginSettings.getJsonObject(j)
        val settingOutput = JsonObject()
        settingOutput.put("setting", setting.getString("setting"))
        settingOutput.put("value", setting.getString("defaultValue"))
        pluginSettingsOutput.add(settingOutput)
      }
      pluginOutput.put("settings", pluginSettingsOutput)

      plugins.add(pluginOutput)
    }

    val schedulers = parameters.getJsonArray("schedulers")

    val output = JsonArray()
    output.add(JsonObject().put("sensors", sensors).put("plugins", plugins))
    if (schedulers != null) {
      output.getJsonObject(0).put("schedulers", schedulers)
    }
    return output
  }

  /**
   * This parses the aware-client xml file to retrieve all possible settings for a study
   */
  fun getSensors(xmlUrl: String): JsonArray {
    val sensors = JsonArray()
    val awarePreferences = URL(xmlUrl).openStream()

    val docFactory = DocumentBuilderFactory.newInstance()
    val docBuilder = docFactory.newDocumentBuilder()
    val doc = docBuilder.parse(awarePreferences)
    val docRoot = doc.getElementsByTagName("PreferenceScreen")

    for (i in 1..docRoot.length) {
      val child = docRoot.item(i)
      if (child != null) {

        val sensor = JsonObject()
        if (child.attributes.getNamedItem("android:key") != null)
          sensor.put("sensor", child.attributes.getNamedItem("android:key").nodeValue)
        if (child.attributes.getNamedItem("android:title") != null)
          sensor.put("title", child.attributes.getNamedItem("android:title").nodeValue)
        if (child.attributes.getNamedItem("android:icon") != null)
          sensor.put("icon", getSensorIcon(child.attributes.getNamedItem("android:icon").nodeValue))
        if (child.attributes.getNamedItem("android:summary") != null)
          sensor.put("summary", child.attributes.getNamedItem("android:summary").nodeValue)

        val settings = JsonArray()
        val subChildren = child.childNodes
        for (j in 0..subChildren.length) {
          val subChild = subChildren.item(j)
          if (subChild != null && subChild.nodeName.contains("Preference")) {
            val setting = JsonObject()
            if (subChild.attributes.getNamedItem("android:key") != null)
              setting.put("setting", subChild.attributes.getNamedItem("android:key").nodeValue)
            if (subChild.attributes.getNamedItem("android:title") != null)
              setting.put("title", subChild.attributes.getNamedItem("android:title").nodeValue)
            if (subChild.attributes.getNamedItem("android:defaultValue") != null)
              setting.put("defaultValue", subChild.attributes.getNamedItem("android:defaultValue").nodeValue)
            if (subChild.attributes.getNamedItem("android:summary") != null && subChild.attributes.getNamedItem("android:summary").nodeValue != "%s")
              setting.put("summary", subChild.attributes.getNamedItem("android:summary").nodeValue)

            if (setting.containsKey("defaultValue"))
              settings.add(setting)
          }
        }
        sensor.put("settings", settings)
        sensors.add(sensor)
      }
    }
    return sensors
  }

  /**
   * This retrieves asynchronously the icons for each sensor from the client source code
   */
  private fun getSensorIcon(drawableId: String): String {
    val icon = drawableId.substring(drawableId.indexOf('/') + 1)
    val downloadUrl = "/denzilferreira/aware-client/raw/master/aware-core/src/main/res/drawable/*.png"

    vertx.fileSystem().mkdir("./cache") { cacheFolder ->
      if (cacheFolder.succeeded()) {
        logger.info { "Created cache folder" }
      }
    }

    vertx.fileSystem().exists("./cache/$icon.png") { iconResult ->
      if (!iconResult.result()) {
        vertx.fileSystem().open("./cache/$icon.png", OpenOptions().setCreate(true).setWrite(true)) { writeFile ->
          if (writeFile.succeeded()) {

            logger.info { "Downloading $icon.png" }

            val asyncFile = writeFile.result()
            val webClientOptions = WebClientOptions()
              .setKeepAlive(true)
              .setPipelining(true)
              .setFollowRedirects(true)
              .setSsl(true)
              .setTrustAll(true)

            val client = WebClient.create(vertx, webClientOptions)
            client.get(443, "github.com", downloadUrl.replace("*", icon))
              .`as`(BodyCodec.pipe(asyncFile, true))
              .send { request ->
                if (request.succeeded()) {
                  val iconFile = request.result()
                  logger.info { "Cached $icon.png: ${iconFile.statusCode() == 200}" }
                }
              }
          } else {
            logger.error(writeFile.cause()) { "Unable to create file." }
          }
        }
      }
    }

    return "$icon.png"
  }

  /**
   * This parses a list of plugins' xml to retrieve plugins' settings
   */
  private fun getPlugins(xmlUrls: HashMap<String, String>): JsonArray {
    val plugins = JsonArray()

    for (pluginUrl in xmlUrls) {
      val pluginPreferences = URL(pluginUrl.value).openStream()

      val docFactory = DocumentBuilderFactory.newInstance()
      val docBuilder = docFactory.newDocumentBuilder()
      val doc = docBuilder.parse(pluginPreferences)
      val docRoot = doc.getElementsByTagName("PreferenceScreen")

      for (i in 0..docRoot.length) {
        val child = docRoot.item(i)
        if (child != null) {

          val plugin = JsonObject()
          plugin.put("package_name", pluginUrl.key)

          if (child.attributes.getNamedItem("android:key") != null)
            plugin.put("plugin", child.attributes.getNamedItem("android:key").nodeValue)
          if (child.attributes.getNamedItem("android:icon") != null)
            plugin.put("icon", child.attributes.getNamedItem("android:icon").nodeValue)
          if (child.attributes.getNamedItem("android:summary") != null)
            plugin.put("summary", child.attributes.getNamedItem("android:summary").nodeValue)

          val settings = JsonArray()
          val subChildren = child.childNodes
          for (j in 0..subChildren.length) {
            val subChild = subChildren.item(j)
            if (subChild != null && subChild.nodeName.contains("Preference")) {
              val setting = JsonObject()
              if (subChild.attributes.getNamedItem("android:key") != null)
                setting.put("setting", subChild.attributes.getNamedItem("android:key").nodeValue)
              if (subChild.attributes.getNamedItem("android:title") != null)
                setting.put("title", subChild.attributes.getNamedItem("android:title").nodeValue)
              if (subChild.attributes.getNamedItem("android:defaultValue") != null)
                setting.put("defaultValue", subChild.attributes.getNamedItem("android:defaultValue").nodeValue)
              if (subChild.attributes.getNamedItem("android:summary") != null && subChild.attributes.getNamedItem("android:summary").nodeValue != "%s")
                setting.put("summary", subChild.attributes.getNamedItem("android:summary").nodeValue)

              if (setting.containsKey("defaultValue"))
                settings.add(setting)
            }
          }
          plugin.put("settings", settings)
          plugins.add(plugin)
        }
      }
    }
    return plugins
  }

  private fun getExternalServerHost(serverConfig: JsonObject): String {
    if (serverConfig.containsKey("external_server_host")) {
      return serverConfig.getString("external_server_host")
    }
    return serverConfig.getString("server_host")
  }

  private fun getExternalServerPort(serverConfig: JsonObject): Int {
    if (serverConfig.containsKey("external_server_port")) {
      return serverConfig.getInteger("external_server_port")
    }
    return serverConfig.getInteger("server_port")
  }
}
