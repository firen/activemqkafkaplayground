import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.*
import javax.jms.*
import spark.Spark.*

/**
 * Created by firen on 21/02/17.
 */

data class Fixture(val id: String, val feedId: String)

fun main(args: Array<String>) {
    //init dependencies
    //cache
    val fixtureCache = HashMap<String, Fixture>()
    //jackson mapper
    val mapper = jacksonObjectMapper()
    //rest api
    get("/hello") {req, res -> "Hello World"}
    get("/cache") {req, res -> mapper.writeValueAsString(fixtureCache)}
    //activemq connection, session, destinations, consumer and producer
    val conn = connectActivemq()
    val sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)
    val entryDest = sess.createQueue("TEST")
    val exitDest = sess.createQueue("DEST")
    val con = sess.createConsumer(entryDest)
    val producer = sess.createProducer(exitDest)
    //kafka connection
    val topics = ArrayList<String>(0)
    topics.add("testTopic")
    val kafkaConsumer = KafkaConsumer<String, String>(properties())
    kafkaConsumer.subscribe(topics)

    //business logic
    while (true) {
        val poll: ConsumerRecords<String, String> = kafkaConsumer.poll(0)
        poll.forEach { e ->
            updateCache(e, fixtureCache, mapper)
        }
        val receivedMessage = con.receive(1000)
        handleMessage(fixtureCache, producer, receivedMessage, sess)
        Thread.`yield`()
    }
    //TODO how to close connections on SIGINT?
    kafkaConsumer.close()
    conn.close()
}

private fun handleMessage(fixtureCache: HashMap<String, Fixture>, producer: MessageProducer, receivedMessage: Message?, sess: Session) {
    if (receivedMessage != null) {
        println("activemq: $receivedMessage")
        val id = (receivedMessage as TextMessage).text
        println("cached values: ${fixtureCache.size}")
        val fixtureToSend = fixtureCache[id]
        println("sending to entryDest : $fixtureToSend")
        producer.send(sess.createTextMessage(fixtureToSend.toString()))
    }
}

private fun updateCache(e: ConsumerRecord<String, String>, fixtureCache: HashMap<String, Fixture>, mapper: ObjectMapper) {
    run {
        println("""
                kafka key: ${e.key()} value: ${e.value()}
                ---
            """.trimIndent())
        try {
            val fixture = mapper.readValue<Fixture>(e.value(), Fixture::class.java)
            println("Real json: $fixture")
            fixtureCache.put(fixture.id, fixture)
        } catch (ex: Exception) {
            println("not json $ex")
        }
    }
}

private fun connectActivemq(): Connection {
    val connFactory: ActiveMQConnectionFactory = ActiveMQConnectionFactory()
    val conn = connFactory.createConnection()
    conn.start()
    return conn
}

private fun properties(): Properties {
    val properties = Properties()
    properties.put("bootstrap.servers", "127.0.0.1:9092")
    properties.put("key.deserializer", StringDeserializer::class.java)
    properties.put("value.deserializer", StringDeserializer::class.java)
    properties.put("group.id", "router-group")
    properties.put("enable.auto.commit", "false")
    return properties
}