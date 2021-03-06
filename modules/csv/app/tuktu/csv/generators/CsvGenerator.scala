package tuktu.csv.generators

import scala.io.Codec
import akka.actor._
import au.com.bytecode.opencsv.CSVReader
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee._
import play.api.libs.json.JsValue
import tuktu.api._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Reads a CSV file in batches of lines
 */
class BatchedCSVReader(parentActor: ActorRef, fileName: String, encoding: String, hasHeaders: Boolean, givenHeaders: List[String],
                       separator: Char, quote: Char, escape: Char, batchSize: Integer, flattened: Boolean, resultName: String,
                       startLine: Option[Int], endLine: Option[Int], ignoreErrors: Boolean, backOffInterval: Int, backOffAmount: Int) extends Actor with ActorLogging {
    case class ReadLinePacket()

    var batch = collection.mutable.Queue[Map[String, Any]]()
    var headers: Option[List[String]] = _
    var lineOffset = 0
    var reader: CSVReader = _

    def receive() = {
        case ip: InitPacket => {
            // Open CSV file for reading
            reader = new CSVReader(tuktu.api.file.genericReader(fileName)(Codec(encoding)), separator, quote, escape)
            // See if we need to fetch headers
            headers = {
                if (hasHeaders) Some(reader.readNext.toList)
                else {
                    if (givenHeaders.nonEmpty) Some(givenHeaders)
                    else None
                }
            }
            // Drop first startLine lines
            while (lineOffset < startLine.getOrElse(0) && reader.readNext != null) lineOffset += 1

            // Start processing
            self ! new ReadLinePacket
        }
        case sp: StopPacket => {
            if (batch.nonEmpty) {
                // Send data
                parentActor ! batch.toList

                // Clear batch
                batch.clear
            }

            // Close CSV reader, kill parent and self
            reader.close
            parentActor ! sp
        }
        case pkt: ReadLinePacket => {
            // Check if we need to backoff
            if (lineOffset != 0 && lineOffset % backOffInterval == 0) Thread.sleep(backOffAmount)
            
            val (nextRead, doRead): (Array[String], Boolean) = {
                if (ignoreErrors) {
                    try {
                        (reader.readNext, true)
                    }
                    catch {
                        case e: Exception => {
                            // Ignore this erroneous line and continue with the next
                            (Array(), false)
                        }
                    }
                }
                else (reader.readNext, true)
            }
            if (doRead) {
                nextRead match {
                    case null => self ! new StopPacket // EOF, stop processing
                    case line: Array[String] => {
                        // Have we reached endLine yet or not
                        if (endLine.map(endLine => lineOffset <= endLine).getOrElse(true)) {
                            // Add line to our batch
                            batch += {
                                headers match {
                                    case Some(hdrs) => flattened match {
                                        case false => Map(resultName -> hdrs.zip(line).toMap)
                                        case true  => hdrs.zip(line).toMap
                                    }
                                    case None => Map(resultName -> line.toList)
                                }
                            }
        
                            // Flush if we have to
                            if (batch.size == batchSize) {
                                // Send data
                                parentActor ! batch.toList
        
                                // Clear batch
                                batch.clear
                            }
        
                            // Continue with next line
                            lineOffset += 1
                            self ! pkt
                        } else
                            // Stop reading
                            self ! new StopPacket
                    }
                }
            }
            else
                self ! pkt
        }
    }
}

class CSVGenerator(resultName: String, processors: List[Enumeratee[DataPacket, DataPacket]], senderActor: Option[ActorRef]) extends BaseGenerator(resultName, processors, senderActor) {
    var csvGenActor: ActorRef = _

    override def receive() = {
        case dpp: DecreasePressurePacket => decBP
        case bpp: BackPressurePacket => backoff
        case config: JsValue => {
            // Get filename
            val fileName = (config \ "filename").as[String]
            val hasHeaders = (config \ "has_headers").asOpt[Boolean].getOrElse(false)
            val headersGiven = (config \ "predef_headers").asOpt[List[String]].getOrElse(List())
            val flattened = (config \ "flattened").asOpt[Boolean].getOrElse(false)
            val separator = (config \ "separator").asOpt[String].getOrElse(";").head
            val quote = (config \ "quote").asOpt[String].getOrElse("\"").head
            val escape = (config \ "escape").asOpt[String].getOrElse("\\").head
            val encoding = (config \ "encoding").asOpt[String].getOrElse("utf-8")
            val startLine = (config \ "start_line").asOpt[Int]
            val endLine = (config \ "end_line").asOpt[Int]
            val batchSize = (config \ "batch_size").asOpt[Int].getOrElse(1000)
            val batched = (config \ "batched").asOpt[Boolean].getOrElse(false)
            val ignoreErrors = (config \ "ignore_error_lines").asOpt[Boolean].getOrElse(false)
            val backOffInterval = (config \ "backoff_interval").asOpt[Int].getOrElse(1000)
            val backOffAmount = (config \ "backoff_amount").asOpt[Int].getOrElse(10)

            if (batched) {
                // Batched uses batching actor
                csvGenActor = Akka.system.actorOf(Props(classOf[BatchedCSVReader], self, fileName, encoding, hasHeaders, headersGiven,
                    separator, quote, escape, batchSize, flattened, resultName, startLine, endLine, ignoreErrors, backOffInterval, backOffAmount))
                csvGenActor ! new InitPacket
            } else {
                // Make separate enumerators, for each processor
                processors.foreach(processor => {
                    // Use Iteratee lib for proper back pressure handling
                    val reader = new CSVReader(tuktu.api.file.genericReader(fileName)(Codec(encoding)), separator, quote, escape)
                    // Headers
                    val headers: Option[List[String]] = {
                        if (hasHeaders) Some(reader.readNext.toList)
                        else {
                            if (headersGiven.nonEmpty) Some(headersGiven)
                            else None
                        }
                    }
    
                    val fileStream: Enumerator[Array[String]] = Enumerator.generateM[Array[String]] {
                        Future { Option(reader.readNext) }
                    }.andThen(Enumerator.eof)
    
                    // onEOF close the reader and send StopPacket
                    val onEOF = Enumeratee.onEOF[Map[String, Any]](() => {
                        reader.close
                        self ! new StopPacket
                    })
                    // Zip the lines with the header
                    val zipEnumeratee: Enumeratee[Array[String], Map[String, Any]] = Enumeratee.mapM[Array[String]](line => Future {
                        headers match {
                            case Some(hdrs) => flattened match {
                                case false => Map(resultName -> hdrs.zip(line).toMap)
                                case true  => hdrs.zip(line).toMap
                            }
                            case None => Map(resultName -> line.toList)
                        }
                    })
                    // If endLine is defined, take at most endLine - startLine + 1 lines
                    val endEnumeratee = endLine match {
                        case None          => zipEnumeratee compose onEOF
                        case Some(endLine) => Enumeratee.take[Array[String]](endLine - math.max(startLine.getOrElse(0), 0) + 1) compose zipEnumeratee compose onEOF
                    }
                    // If startLine is positive, drop that many lines
                    val startEnumeratee = startLine match {
                        case None            => endEnumeratee
                        case Some(startLine) => Enumeratee.drop[Array[String]](startLine) compose endEnumeratee
                    }
                    
                    // Stream it all together
                    fileStream |>> (startEnumeratee compose Enumeratee.mapM(data => Future {
                        DataPacket(List(data))
                    }) compose processor) &>> sinkIteratee
                })
            }
        }
        case sp: StopPacket => {
            Option(csvGenActor) collect { case actor => actor ! PoisonPill }
            cleanup(false)
        }
        case ip: InitPacket               => setup
        case data: List[Map[String, Any]] => channel.push(new DataPacket(data))
    }
}