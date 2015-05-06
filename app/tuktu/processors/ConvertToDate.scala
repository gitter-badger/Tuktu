package tuktu.processors

import java.text.SimpleDateFormat
import java.util.Locale

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import tuktu.api.BaseProcessor
import tuktu.api.DataPacket
import tuktu.api.utils

/**
 * Converts a String formatted Date, to an actual Java Date object.
 * By default can convert a Java Date toString formatting back to an actual Date object.
 */
class ConvertToDate(resultName: String) extends BaseProcessor(resultName) {
    var field: String = _
    var formatter: SimpleDateFormat = _
    
    override def initialize(config: JsObject) = {
        // The field containing the date
        field = (config \ "field").as[String]
        val format = (config \ "format").asOpt[String].getOrElse("EEE MMM dd HH:mm:ss zzz yyyy")
        val locale = (config \ "locale").asOpt[String].getOrElse("US")
        formatter = new SimpleDateFormat(format, Locale.forLanguageTag(locale))
    }
    
    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => {
        Future {
            new DataPacket(for (datum <- data.data) yield {              
              val dateField = utils.evaluateTuktuString(datum(field).toString, datum)
              val dateAsString = datum(field) match {
                case g: String => g
                case g: JsString => g.value
                case g: Any => g.toString
              }
              datum + (field -> formatter.parse(dateAsString))
            })
        }
    })
}