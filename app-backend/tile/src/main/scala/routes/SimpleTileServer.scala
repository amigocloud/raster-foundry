package com.azavea.rf.tile.image

import java.util.UUID

import cats.data.OptionT
import com.azavea.rf.database.Database
import com.azavea.rf.database.tables.{Scenes, ScenesToProjects}
import com.azavea.rf.datamodel.{MosaicDefinition, Scene}
import geotrellis.raster.MultibandTile
import geotrellis.raster.histogram.Histogram
import geotrellis.spark.io._
import geotrellis.spark.io.AttributeStore._
import geotrellis.raster.io.json._
import geotrellis.raster._
import geotrellis.raster.histogram._
import geotrellis.spark.io.s3.{S3AttributeStore, S3CollectionLayerReader, S3InputFormat, S3ValueReader}
import geotrellis.spark.{LayerId, SpatialKey, TileLayerMetadata}
import geotrellis.vector.Extent

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import spray.json.DefaultJsonProtocol._
import geotrellis.spark.io._
import geotrellis.raster._
import geotrellis.raster.render._
import geotrellis.raster.histogram._
import geotrellis.raster.io._
import geotrellis.vector.io._
import geotrellis.spark.io._
import geotrellis.spark._
import geotrellis.spark.tiling._
import geotrellis.proj4._
import geotrellis.spark.io.s3.{S3AttributeStore, S3CollectionLayerReader, S3InputFormat, S3ValueReader}
import com.github.blemale.scaffeine.{Scaffeine, Cache => ScaffeineCache}
import geotrellis.vector.Extent
import com.typesafe.scalalogging.LazyLogging
import spray.json.DefaultJsonProtocol._
import cats.implicits._
import com.azavea.rf.common.cache.kryo.KryoMemcachedClient

import scala.concurrent.ExecutionContext.Implicits.global
import net.spy.memcached.MemcachedClient

import scalacache._
import memcached._
import scalacache.serialization.Codec
import geotrellis.spark.util.KryoSerializer

//class MultibandTileCodec extends Codec[MultibandTile, Array[Byte]] {
//
//  def serialize(value: MultibandTile): Array[Byte] = {
//    value.
//  }
//  def deserialize(data: Array[Byte]): MultibandTile = ???
//}




/**
  * Created by cbrown on 7/1/17.
  */
object SimpleTileServer {


  def timef[R](block: => R): R = {
    val t0 = System.nanoTime()
    val result = block    // call-by-name
    val t1 = System.nanoTime()
    println("Elapsed time colorcorrecting: " + (t1 - t0) + "ns")
    result
  }

  def timecc[R](block: => R): R = {
    val t0 = System.nanoTime()
    val result = block    // call-by-name
    val t1 = System.nanoTime()
    println("get cc: " + (t1 - t0) + "ns")
    result
  }
  def timehist[R](block: => R): R = {
    val t0 = System.nanoTime()
    val result = block    // call-by-name
    val t1 = System.nanoTime()
    println("get hist: " + (t1 - t0) + "ns")
    result
  }
  def timetile[R](block: => R): R = {
    val t0 = System.nanoTime()
    val result = block    // call-by-name
    val t1 = System.nanoTime()
    println("get tile: " + (t1 - t0) + "ns")
    result
  }


  def getProjectScenes(projectId: UUID): Future[Seq[Scene]] = ???

  def getTileLayerMetadata(id: UUID, zoom: Int): Future[Option[TileLayerMetadata[SpatialKey]]] = ???

  val memcachedClient = KryoMemcachedClient.DEFAULT

  implicit val scalaCache = ScalaCache(MemcachedCache(memcachedClient))

  def getLayerAttributeStore(scene: Scene.WithRelated): Option[AttributeStore] = {
    scene.ingestLocation match {
      case Some(ingestLocation) => {
        for (result <- S3InputFormat.S3UrlRx.findFirstMatchIn(ingestLocation)) yield {
          val bucket = result.group("bucket")
          val prefix = result.group("prefix")
          S3AttributeStore(bucket, prefix)
        }
      }
      case _ => None
    }
  }

  def getTileForExtent(scene: Scene, zoom: Int, extent: Extent): OptionT[Future, MultibandTile] = ???

  def getTileForXY(scene: Scene.WithRelated, z: Int, x: Int, y: Int): Future[Option[MultibandTile]] = {
    val tileKey = s"scene-${scene.id}-z-${z}-x-${x}-y-${y}"
    val cachedTile = get[MultibandTile, Array[Byte]](tileKey)
    cachedTile.map { t =>
      t match {
        case Some(tile) => {
          println(s"GOT FROM CACHE: ${tileKey}")
          Option(tile)
        }
        case _ => {
          val spatialKey = SpatialKey(x, y)
          val attributeStoreOption = getLayerAttributeStore(scene)
          val layerId = getSceneLayerId(scene, z)
          attributeStoreOption match {
            case Some(attributeStore) => {
              val reader = new S3ValueReader(attributeStore).reader[SpatialKey, MultibandTile](layerId)
              Try {
                reader.read(spatialKey)
              } match {
                case Success(tile) => {
                  put[MultibandTile, Array[Byte]](tileKey)(tile)
                  Option(tile)
                }
                case _ => None
              }
            }
            case _ => None
          }
        }
      }
    }
  }

  def getSceneLayerId(scene: Scene.WithRelated, zoom: Int): LayerId = {
    LayerId(scene.id.toString, zoom)
  }

  def getLayerHistogram(scene: Scene.WithRelated, zoom: Int): Future[Option[Array[Histogram[Double]]]] = {
    val key = s"hist-${scene.id}-zoom-${zoom}"
    val futureHist = get[Array[Histogram[Double]], Array[Byte]](key)

    futureHist.map { maybeHist =>
      maybeHist match {
        case Some(h) => {
          println(s"Got Hist Cache: ${scene.id} zoom-${zoom}")
          Option(h)
        }
        case _ => {
          val attributeStoreOption = getLayerAttributeStore(scene)
          val layerId = getSceneLayerId(scene, 0)
          attributeStoreOption match {
            case Some(attributeStore) => {
              val x = attributeStore.read[Array[Histogram[Double]]](layerId, "histogram")
              put[Array[Histogram[Double]], Array[Byte]](key)(x)
              Some(x)
            }
            case _ => None
          }
        }
      }
    }
  }

  def getMosaic(projectId: UUID, zoom: Int, col: Int, row: Int)
    (implicit database: Database): Future[MultibandTile] = {

    val futureScenes = Scenes.listProjectScenes(projectId)
    val x = futureScenes.map { scenes =>
      val tiles = scenes
        .map { scene =>
          for {
            colorCorrectParamsOpt <- ScenesToProjects.getColorCorrectParams2(projectId, scene.id)
            optTile <- getTileForXY(scene, zoom, col, row)
            optHist <- getLayerHistogram(scene, zoom)
          } yield {
            for {
              cc <- timecc {colorCorrectParamsOpt}
              tile <- timetile {optTile}
              hist <- timehist {optHist}
            } yield {
              timef {
                cc.colorCorrect(tile, hist)
              }
            }
          }
        }
      val z = Future.sequence(tiles)
      z.map {y =>
        val a = y.flatten
        a.reduce(_ merge _)
      }
    }
    x.flatten
  }
}
