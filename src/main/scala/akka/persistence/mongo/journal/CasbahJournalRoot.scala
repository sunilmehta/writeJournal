/**
  *  Copyright (C) 2015-2016 Duncan DeVore. <https://github.com/ironfish/>
  */
package akka.persistence.mongo.journal

import akka.actor.ActorLogging
import akka.persistence.PersistentRepr
import akka.persistence.mongo.{CasbahCommon, CasbahJournalCommon, CasbahRoot}
import com.mongodb.casbah.Imports._

import scala.collection.immutable
import scala.util.{Failure, Success, Try}
import java.util.Date

import akka.persistence.journal.Tagged

object CasbahJournalRoot {

  import CasbahCommon._

  def dbObjectToPersistentRepr(dbObject: DBObject, f: (DBObject, String) =>
      Try[PersistentRepr]): Option[PersistentRepr] = {

    if (dbObject.as[String](markerKey) == "D") return None
    f(dbObject, messageKey) match {
      case Success(pr) =>
        Some(pr)
      case Failure(error) =>
        None
    }
  }

  def persistentReprToDBObjectExecute(collection: MongoCollection, persistentRepr: PersistentRepr, f: PersistentRepr => Try[Array[Byte]])
    (implicit rejectNonSerializableObjects: Boolean): Try[DBObject] = {

    val errorMsg: String = "Unable to serialize payload for"
    val pidMsg: String = s"$persistenceIdKey: ${persistentRepr.persistenceId}"
    val snrMsg: String = s"$sequenceNrKey: ${persistentRepr.sequenceNr}"

    def marker(): String = if (persistentRepr.deleted) "D" else ""
    def nextSeqNr(): Long = getNextSequenceNrExecute(collection, persistentRepr)
    def toDBObject(data: Array[Byte]): DBObject = {
      val builder = MongoDBObject.newBuilder
      val tags = persistentRepr.payload match {
        case Tagged(payload, ts) =>
          ( ts)
        case _ =>
          val tag = Set("domain_object")
          ( tag)
      }
      builder += persistenceIdKey -> persistentRepr.persistenceId
      builder += sequenceNrKey -> nextSeqNr()
      builder += markerKey -> marker()
      builder += messageKey -> data
      builder += timestampKey -> new Date()

      builder += tagsKeys -> tags
      builder.result()
    }

    f(persistentRepr) match {
      case Failure(error) if rejectNonSerializableObjects =>
        Failure(new Exception(s"$errorMsg $pidMsg, $snrMsg", error))
      case Failure(error) =>
        Success(toDBObject(Array.empty[Byte]))
      case Success(value) =>
        Success(toDBObject(value))
    }
  }

  def deleteToExecute(collection: MongoCollection, concern: WriteConcern, persistenceId: String,
    toSequenceNr: Long, f: PersistentRepr => Try[Array[Byte]]): Unit = {

    val sequenceNbr = highestSequenceNrExecute(collection, persistenceId)
    collection.remove(MongoDBObject(
      persistenceIdKey -> persistenceId,
      sequenceNrKey  -> MongoDBObject(lteKey -> toSequenceNr)), concern)
    if (toSequenceNr >= sequenceNbr) {
      val retainHighestSequenceNbr = PersistentRepr("D", sequenceNbr, persistenceId, deleted = true)
      val dbObject: DBObject =
        persistentReprToDBObjectExecute(collection, retainHighestSequenceNbr, f)(rejectNonSerializableObjects = false).get
      persistExecute(collection, immutable.Seq(dbObject))
    }
  }

  def getNextSequenceNrExecute(collection: MongoCollection, persistentRepr: PersistentRepr): Long = {
    val sequenceNbr = highestSequenceNrExecute(collection, persistentRepr.persistenceId)
    if (persistentRepr.deleted) persistentRepr.sequenceNr else sequenceNbr+1
  }
  def highestSequenceNrExecute(collection: MongoCollection, persistenceId: String): Long = {
    val cursor: MongoCursor = collection
      .find(MongoDBObject(persistenceIdKey -> persistenceId))
      .sort(MongoDBObject(sequenceNrKey -> -1)).limit(1)
    if (cursor.hasNext) cursor.next().getAs[Long](sequenceNrKey).get else 0L
  }


  def persistExecute(collection: MongoCollection, objects: immutable.Seq[DBObject]): WriteResult = {
    collection.insert(objects:_ *)
  }

  def replayCursorExecute(collection: MongoCollection, persistenceId: String, fromSequenceNr: Long,
    toSequenceNr: Long, maxNumberOfMessages: Int, f: (DBObject, String) =>
    Try[PersistentRepr]): Iterator[PersistentRepr] = {

    val cursor: MongoCursor = collection.find(MongoDBObject(
      persistenceIdKey -> persistenceId,
      sequenceNrKey  -> MongoDBObject(gteKey -> fromSequenceNr, lteKey -> toSequenceNr)))
      .sort(MongoDBObject(
        persistenceIdKey -> 1,
        sequenceNrKey -> 1))
      .limit(maxNumberOfMessages)

    cursor.flatMap(dbObject => dbObjectToPersistentRepr(dbObject, f))
  }
}

trait CasbahJournalRoot extends CasbahRoot
  with CasbahJournalCommon { mixin : ActorLogging =>

  import CasbahJournalRoot._
  import CasbahCommon._

  private val replayDispatcherKey: String = "replay-dispatcher"
  protected lazy val replayDispatcherId: String = config.getString(replayDispatcherKey)

  override protected def initialize(): Unit = {
    val indexOne: MongoDBObject = MongoDBObject(persistenceIdKey -> 1, sequenceNrKey -> 1)
    val indexTwo: MongoDBObject = MongoDBObject(sequenceNrKey -> 1)
    val indexThree: MongoDBObject = MongoDBObject(tagsKeys -> 1, timestampKey -> 1)
    ensure(indexOne, indexOptions)(mongoCollection)
    ensure(indexTwo)(mongoCollection)
    ensure(indexThree)(mongoCollection)
  }

  protected def persistentReprToDBObject(persistentRepr: PersistentRepr)
    (implicit rejectNonSerializableObjects: Boolean): Try[DBObject] =
    persistentReprToDBObjectExecute(mongoCollection, persistentRepr, toBytes)

  protected def deleteTo(collection: MongoCollection,concern: WriteConcern, persistenceId: String,
      toSequenceNr: Long): Unit =
    deleteToExecute(collection, concern, persistenceId, toSequenceNr, toBytes)

  def replayCursor(collection: MongoCollection, persistenceId: String, fromSequenceNr: Long,
      toSequenceNr: Long, maxNumberOfMessages: Int): Iterator[PersistentRepr] =
    replayCursorExecute(collection, persistenceId, fromSequenceNr, toSequenceNr, maxNumberOfMessages,
      fromBytes[PersistentRepr])
}
