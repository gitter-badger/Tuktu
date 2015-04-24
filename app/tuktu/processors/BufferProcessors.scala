package tuktu.processors

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.JsValue
import play.api.libs.concurrent.Akka
import play.api.Play.current
import tuktu.api._
import play.api.libs.json.JsObject
import play.api.cache.Cache

/**
 * This actor is used to buffer stuff in
 */
class BufferActor(remoteGenerator: ActorRef) extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    var buffer = collection.mutable.ListBuffer[Map[String, Any]]()
    
    def receive() = {
        case "release" => {
            // Create datapacket and clear buffer
            val dp = new DataPacket(buffer.toList.asInstanceOf[List[Map[String, Any]]])
            buffer.clear
            
            // Push forward to remote generator
            remoteGenerator ! dp
            
            sender ! "ok"
        }
        case sp: StopPacket => {
            remoteGenerator ! sp
            self ! PoisonPill
        }
        case item: Map[String, Any] => {
            buffer += item
            sender ! "ok"
        }
    }
}

/**
 * Buffers datapackets until we have a specific amount of them
 */
class SizeBufferProcessor(genActor: ActorRef, resultName: String) extends BufferProcessor(genActor, resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    var maxSize = -1
    var curCount = 0
    
    // Set up the buffering actor
    val bufferActor = Akka.system.actorOf(Props(classOf[BufferActor], genActor))
    
    override def initialize(config: JsObject) = {
        maxSize = (config \ "size").as[Int]
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => {
        // Iterate over our data and add to the buffer
        val fut = Future.sequence(for (datum <- data.data) yield bufferActor ? datum)
        
        // Wait for all of them to finish
        Await.result(fut, 30 seconds)
        
        // Increase counter
        curCount += 1
        
        // See if we need to release
        if (curCount == maxSize) {
            // Send the relase but forget the result
            curCount = 0
            bufferActor ! "release"
        }
        
        Future {data}
    }) compose Enumeratee.onEOF(() => {
        Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        bufferActor ! new StopPacket
    })
}

/**
 * Buffers datapackets for a given amount of time and then releases the buffer for processing
 */
class TimeBufferProcessor(genActor: ActorRef, resultName: String) extends BufferProcessor(genActor, resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    var interval = -1
    var cancellable: Cancellable = null
    
    // Set up the buffering actor
    val bufferActor = Akka.system.actorOf(Props(classOf[BufferActor], genActor))
    
    override def initialize(config: JsObject) = {
        interval = (config \ "interval").as[Int]
        
        // Schedule periodic release
        cancellable =
            Akka.system.scheduler.schedule(interval milliseconds,
            interval milliseconds,
            bufferActor,
            "release"
        )
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => {
        // Iterate over our data and add to the buffer
        data.data.foreach(datum => bufferActor ! datum)
        
        Future {data}
    }) compose Enumeratee.onEOF(() => {
        cancellable.cancel
        Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        bufferActor ! new StopPacket
    })
}

/**
 * Buffers until EOF (end of data stream) is found
 */
class EOFBufferProcessor(genActor: ActorRef, resultName: String) extends BufferProcessor(genActor, resultName) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    
    // Set up the buffering actor
    val bufferActor = Akka.system.actorOf(Props(classOf[BufferActor], genActor))
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.map((data: DataPacket) => {
        // Iterate over our data and add to the buffer
        val fut = Future.sequence(for (datum <- data.data) yield bufferActor ? datum)
        
        // Wait for all of them to finish
        Await.result(fut, Cache.getAs[Int]("timeout").getOrElse(5) * 2 seconds)
        data
    }) compose Enumeratee.onEOF(() => {
        Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        bufferActor ! new StopPacket
    })
}