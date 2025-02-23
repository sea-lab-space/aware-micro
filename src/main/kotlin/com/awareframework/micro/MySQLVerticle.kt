package com.awareframework.micro

import io.github.oshai.kotlinlogging.KotlinLogging
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.PemTrustOptions
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.mysqlclient.MySQLPool
import io.vertx.mysqlclient.SslMode
import io.vertx.sqlclient.PoolOptions
import java.util.stream.Collectors
import java.util.stream.StreamSupport

class MySQLVerticle : AbstractVerticle() {

  private val logger = KotlinLogging.logger {}

  private lateinit var parameters: JsonObject
  private lateinit var sqlClient: MySQLPool

  override fun start(startPromise: Promise<Void>?) {
    super.start(startPromise)

    val configStore =
            ConfigStoreOptions()
                    .setType("file")
                    .setFormat("json")
                    .setConfig(JsonObject().put("path", "aware-config.json"))

    val configRetrieverOptions = ConfigRetrieverOptions().addStore(configStore).setScanPeriod(5000)

    val eventBus = vertx.eventBus()

    val configReader = ConfigRetriever.create(vertx, configRetrieverOptions)
    configReader.getConfig { config ->
      if (config.succeeded() && config.result().containsKey("server")) {
        parameters = config.result()
        val serverConfig = parameters.getJsonObject("server")

        // https://vertx.io/docs/4.3.3/apidocs/io/vertx/mysqlclient/MySQLConnectOptions.html
        val connectOptions =
                MySQLConnectOptions()
                        .setHost(serverConfig.getString("database_host"))
                        .setPort(serverConfig.getInteger("database_port"))
                        .setDatabase(serverConfig.getString("database_name"))
                        .setUser(serverConfig.getString("database_user"))
                        .setPassword(serverConfig.getString("database_pwd"))
        setDatabaseSslMode(serverConfig, connectOptions)

        val poolOptions = PoolOptions().setMaxSize(5)

        // Create the client pool
        sqlClient = MySQLPool.pool(vertx, connectOptions, poolOptions)

        eventBus.consumer<JsonObject>("insertData") { receivedMessage ->
          val postData = receivedMessage.body()
          insertData(
                  device_id = postData.getString("device_id"),
                  table = postData.getString("table"),
                  data = JsonArray(postData.getString("data"))
          )
        }

        eventBus.consumer<JsonObject>("updateData") { receivedMessage ->
          val postData = receivedMessage.body()
          updateData(
                  device_id = postData.getString("device_id"),
                  table = postData.getString("table"),
                  data = JsonArray(postData.getString("data"))
          )
        }

        eventBus.consumer<JsonObject>("deleteData") { receivedMessage ->
          val postData = receivedMessage.body()
          deleteData(
                  device_id = postData.getString("device_id"),
                  table = postData.getString("table"),
                  data = JsonArray(postData.getString("data"))
          )
        }

        eventBus.consumer<JsonObject>("getData") { receivedMessage ->
          val postData = receivedMessage.body()
          getData(
                          device_id = postData.getString("device_id"),
                          table = postData.getString("table"),
                          start = postData.getDouble("start"),
                          end = postData.getDouble("end")
                          // https://access.redhat.com/documentation/ja-jp/red_hat_build_of_eclipse_vert.x/4.0/html/eclipse_vert.x_4.0_migration_guide/changes-in-handlers_changes-in-common-components
                          )
                  .onComplete { response -> receivedMessage.reply(response.result()) }
        }
      }
    }
  }

  // Fetch data from the database and return results as JsonArray
  fun getData(device_id: String, table: String, start: Double, end: Double): Future<JsonArray> {

    val dataPromise: Promise<JsonArray> = Promise.promise()

    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        // https://access.redhat.com/documentation/ja-jp/red_hat_build_of_eclipse_vert.x/4.0/html/eclipse_vert.x_4.0_migration_guide/changes-in-vertx-jdbc-client_changes-in-client-components#running_queries_on_managed_connections
        connection
                .query(
                        "SELECT * FROM $table WHERE device_id = '$device_id' AND timestamp between $start AND $end ORDER BY timestamp ASC"
                )
                .execute()
                .onFailure { e ->
                  logger.error(e) { "Failed to retrieve data." }
                  connection.close()
                  dataPromise.fail(e.message)
                }
                .onSuccess { rows ->
                  logger.info { "$device_id : retrieved ${rows.size()} records from $table" }
                  connection.close()
                  dataPromise.complete(
                          JsonArray(
                                  StreamSupport.stream(rows.spliterator(), false)
                                          .map { row -> row.toJson() }
                                          .collect(Collectors.toList())
                          )
                  )
                }
      }
    }

    return dataPromise.future()
  }

  fun updateData(device_id: String, table: String, data: JsonArray) {
    if (data.isEmpty()) return // Exit if the input data array is empty

    // Extract all possible keys from the JSON data to determine the columns that need to be updated
    val columnNames = mutableSetOf<String>()
    for (i in 0 until data.size()) {
      columnNames.addAll(data.getJsonObject(i).fieldNames())
    }

    // Establish a connection to the database
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()

        for (i in 0 until data.size()) {
          val entry = data.getJsonObject(i)

          // Construct the SET clause dynamically by mapping column names to their corresponding
          // values
          val setClause =
                  columnNames.joinToString(", ") { key ->
                    "`$key` = ${entry.getValue(key)?.toString()?.let { "'$it'" } ?: "NULL"}"
                  }

          // Retrieve the timestamp from the entry to use it as the WHERE condition
          val timestamp = entry.getDouble("timestamp")

          // Construct the UPDATE SQL query
          val updateQuery =
                  """
                    UPDATE `$table` 
                    SET $setClause 
                    WHERE `device_id` = '$device_id' AND `timestamp` = $timestamp
                """.trimIndent()

          // Execute the update query
          connection
                  .query(updateQuery)
                  .execute()
                  .onFailure { e ->
                    logger.error(e) { "Failed to update $table for device_id: $device_id" }
                    connection.close()
                  }
                  .onSuccess {
                    logger.info { "$device_id updated $table: ${entry.encode()}" }
                    connection.close()
                  }
        }
      } else {
        // Log an error if the database connection could not be established
        logger.error(connectionResult.cause()) { "Failed to establish connection." }
      }
    }
  }

  fun deleteData(device_id: String, table: String, data: JsonArray) {
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connection = connectionResult.result()
        val timestamps = mutableListOf<Double>()
        for (i in 0 until data.size()) {
          val entry = data.getJsonObject(i)
          timestamps.add(entry.getDouble("timestamp"))
        }

        val deleteBatch =
                "DELETE from '$table' WHERE device_id = '$device_id' AND timestamp in (${timestamps.stream().map(Any::toString).collect(
            Collectors.joining(",")
          )})"
        connection
                .query(deleteBatch)
                .execute()
                .onFailure { e ->
                  logger.error(e) { "Failed to process delete batch." }
                  connection.close()
                }
                .onSuccess { _ ->
                  logger.info { "$device_id deleted from $table: ${data.size()} records" }
                  connection.close()
                }
      } else {
        logger.error(connectionResult.cause()) { "Failed to establish connection." }
      }
    }
  }

  /** Create a database table if it doesn't exist */
  fun createTable(table: String, data: JsonArray): Future<Boolean> {
    val promise = Promise.promise<Boolean>()

    // If data is empty, fail immediately because there's no way to determine the table structure
    if (data.isEmpty()) {
      promise.fail("No data available to determine table structure.")
      return promise.future()
    }

    // Extract all unique keys from the JSON array to determine the column names
    val columnNames = mutableSetOf<String>()
    for (i in 0 until data.size()) {
      columnNames.addAll(
              data.getJsonObject(i).fieldNames()
      ) // Collect all keys as potential columns
    }

    // Establish a connection to the database
    sqlClient.getConnection { connectionResult ->
      if (connectionResult.succeeded()) {
        val connect = connectionResult.result()

        // 1. Check if the table already exists
        val checkTableQuery = "SHOW TABLES LIKE '$table'"
        connect.query(checkTableQuery)
                .execute()
                .onSuccess { rows ->
                  if (rows.size() > 0) {
                    // The table already exists, so no need to create it again
                    logger.info { "Table $table already exists." }
                    promise.complete(true)
                    connect.close()
                  } else {
                    // 2. Construct the CREATE TABLE query dynamically based on extracted columns
                    val createTableQuery =
                            """
                            CREATE TABLE `$table` (
                                `_id` INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                                ${columnNames.joinToString(", ") { "`$it` TEXT NULL" }}
                            )
                        """.trimIndent()

                    // Execute the CREATE TABLE query
                    connect.query(createTableQuery)
                            .execute()
                            .onFailure { e ->
                              logger.error(e) { "Failed to create table: $table" }
                              promise.fail(e.message)
                              connect.close()
                            }
                            .onSuccess {
                              logger.info { "Created table \"$table\" successfully." }
                              promise.complete(true)
                              connect.close()
                            }
                  }
                }
                .onFailure { e ->
                  // Log error if checking table existence fails
                  logger.error(e) { "Failed to check table existence: $table" }
                  promise.fail(e.message)
                  connect.close()
                }
      } else {
        // Log error if the database connection fails
        logger.error(connectionResult.cause()) { "Failed to connect to database." }
        promise.fail(connectionResult.cause().message)
      }
    }

    return promise.future()
  }

  /** Insert batch of data into database table */
  fun insertData(table: String, device_id: String, data: JsonArray) {
    if (data.isEmpty()) return // Exit if there is no data to insert

    // Extract all keys from the JSON array to determine the column names
    val columnNames = mutableSetOf<String>()
    for (i in 0 until data.size()) {
      columnNames.addAll(data.getJsonObject(i).fieldNames()) // Collect all unique keys
    }

    // Ensure the table exists before inserting data
    createTable(table, data)
            .onSuccess {
              // Establish a connection to the database
              sqlClient.getConnection { connectionResult ->
                if (connectionResult.succeeded()) {
                  val connection = connectionResult.result()
                  val rows = data.size()
                  val values = mutableListOf<String>()

                  for (i in 0 until data.size()) {
                    val entry = data.getJsonObject(i)

                    // Extract values for each key while handling NULL values
                    val valueList =
                            columnNames.map { key ->
                              entry.getValue(key)?.toString()?.let { "'$it'" } ?: "NULL"
                            }

                    values.add("(${valueList.joinToString(", ")})")
                  }

                  // Construct the INSERT SQL statement dynamically
                  val insertQuery =
                          """
                        INSERT INTO `$table` (${columnNames.joinToString(", ")}) 
                        VALUES ${values.joinToString(", ")}
                    """.trimIndent()

                  // Execute the INSERT query
                  connection
                          .query(insertQuery)
                          .execute()
                          .onFailure { e ->
                            logger.error(e) { "Failed to insert batch into $table." }
                            connection.close()
                          }
                          .onSuccess {
                            logger.info { "Inserted $rows records into $table." }
                            connection.close()
                          }
                }
              }
            }
            .onFailure { e -> logger.error(e) { "Failed to create table." } }
  }

  override fun stop() {
    super.stop()
    logger.info { "AWARE Micro: MySQL client shutdown" }
    sqlClient.close()
  }

  private fun setDatabaseSslMode(serverConfig: JsonObject, options: MySQLConnectOptions) {
    val sslMode = serverConfig.getString("database_ssl_mode")
    when (sslMode) {
      null, "", "disable", "disabled" -> {
        options.setSslMode(SslMode.DISABLED)
      }
      "prefer", "preferred" -> {
        options.setSslMode(SslMode.PREFERRED)
        if (serverConfig.containsKey("database_ssl_path_ca_cert_pem")) {
          options.setPemTrustOptions(
                  PemTrustOptions()
                          .addCertPath(serverConfig.getString("database_ssl_path_ca_cert_pem"))
          )
          if (serverConfig.containsKey("database_ssl_path_client_key_pem")) {
            options.setPemKeyCertOptions(
                    PemKeyCertOptions()
                            .setKeyPath(serverConfig.getString("database_ssl_path_client_key_pem"))
                            .setCertPath(
                                    serverConfig.getString("database_ssl_path_client_cert_pem")
                            )
            )
          }
        }
      }
    }
  }
}
