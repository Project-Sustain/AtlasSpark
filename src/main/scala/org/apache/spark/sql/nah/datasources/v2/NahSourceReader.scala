package org.apache.spark.sql.nah.datasources.v2

import io.blackpine.geohash.Geohash
import io.blackpine.hdfs_comm.ipc.rpc.RpcClient
import io.blackpine.nah.spark.sql.util.Converter

import org.apache.hadoop.hdfs.protocol.proto.{ClientNamenodeProtocolProtos, HdfsProtos}
import org.apache.hadoop.fs.FileStatus

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.{GreaterThan, GreaterThanOrEqual, LessThan, LessThanOrEqual}
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.sources.v2.reader.{DataSourceReader, InputPartition, SupportsPushDownFilters, SupportsPushDownRequiredColumns}
import org.apache.spark.sql.types.StructType

import java.lang.Thread
import java.util.{ArrayList, List}
import java.util.concurrent.ArrayBlockingQueue

import scala.collection.JavaConversions._
import scala.util.control.Breaks._

class NahSourceReader(fileMap: Map[String, Seq[FileStatus]],
    dataSchema: StructType, fileFormat: String, formatFields: Map[String, String], 
    queryThreads: Int, maxPartitionBytes: Long) extends DataSourceReader
    with SupportsPushDownFilters with SupportsPushDownRequiredColumns {
  private var requiredSchema = {
    val schema = StructType(dataSchema)
    schema
  }

  private var filters: Array[Filter] = Array()

  override def readSchema: StructType = requiredSchema

  override def planInputPartitions
      : List[InputPartition[InternalRow]] = {
    // compile nah filter query
    val latitude = getLatitudeFeature
    val longitude = getLongitudeFeature
    var minLat= -java.lang.Double.MAX_VALUE
    var maxLat= java.lang.Double.MAX_VALUE
    var minLong= -java.lang.Double.MAX_VALUE
    var maxLong= java.lang.Double.MAX_VALUE

    // process filters
    for (filter <- filters) {
      filter match {
        case GreaterThan(a, b) => {
          if (a == latitude) {
            minLat= scala.math.max(minLat, Converter.toDouble(b))
          } else if (a == longitude) {
            minLong= scala.math.max(minLong, Converter.toDouble(b))
          }
        }
        case GreaterThanOrEqual(a, b) => {
          if (a == latitude) {
            minLat= scala.math.max(minLat, Converter.toDouble(b))
          } else if (a == longitude) {
            minLong= scala.math.max(minLong, Converter.toDouble(b))
          }
        }
        case LessThan(a, b) => {
          if (a == latitude) {
            maxLat= scala.math.min(maxLat, Converter.toDouble(b))
          } else if (a == longitude) {
            maxLong= scala.math.min(maxLong, Converter.toDouble(b))
          }
        }
        case LessThanOrEqual(a, b) => {
          if (a == latitude) {
            maxLat= scala.math.min(maxLat, Converter.toDouble(b))
          } else if (a == longitude) {
            maxLong= scala.math.min(maxLong, Converter.toDouble(b))
          }
        }
        case _ => println("TODO - support filter: " + filter)
      }
    }

    //println("(" + minLat + " " + maxLat
    //  + " " + minLong + " " + maxLong + ")")

    // calculate bounding geohash
    val geohashBound1 = Geohash.encode16(minLat, minLong, 6)
    val geohashBound2 = Geohash.encode16(minLat, maxLong, 6)
    val geohashBound3 = Geohash.encode16(maxLat, minLong, 6)
    val geohashBound4 = Geohash.encode16(maxLat, maxLong, 6)

    //println(geohashBound1 + " : " + geohashBound2
    //  + " : " + geohashBound3 + " : " + geohashBound3)

    var count = 0;
    breakable {
      while (true) {
        if (geohashBound1(count) == geohashBound2(count)
            && geohashBound1(count) == geohashBound3(count)
            && geohashBound1(count) == geohashBound4(count)) {
          count += 1
        } else {
          break
        }
      }
    }

    val geohashBound = geohashBound1.substring(0, count)
    //println("BOUNDING GEOHASH: '" + geohashBound + "'")
    //val nahQuery = nahQueryExpressions.mkString("&")
 
    var nahQuery = "";
    if (!geohashBound.isEmpty) {
      nahQuery += "g=" + geohashBound
    }

    // submit requests within threads
    val inputQueue = new ArrayBlockingQueue[(String, Int, String, Long)](4096)
    val outputQueue = new ArrayBlockingQueue[Any](128)

    val threads: List[Thread] = new ArrayList()
    for (i <- 1 to queryThreads) {
      val thread = new Thread() {
        override def run() {
          breakable { while (true) {
            // retrieve next file
            val (ipAddress, port, filename, length) = inputQueue.take()
            if (filename.isEmpty) {
              break
            }

            // send GetFileInfo request
            val gblRpcClient = new RpcClient(ipAddress, port, "ATLAS-SPARK",
              "org.apache.hadoop.hdfs.protocol.ClientProtocol")
            val gblRequest = ClientNamenodeProtocolProtos
              .GetBlockLocationsRequestProto.newBuilder()
                .setSrc(filename)
                .setOffset(0)
                .setLength(length)
                .build

            val gblIn = gblRpcClient.send("getBlockLocations", gblRequest)
            val gblResponse = ClientNamenodeProtocolProtos
              .GetBlockLocationsResponseProto.parseDelimitedFrom(gblIn)
            gblIn.close
            gblRpcClient.close

            // process block locations
            val lbsProto = gblResponse.getLocations
            outputQueue.put(lbsProto)
          } }

          outputQueue.put("")
        }
      }

      thread.start()
      threads.append(thread)
    }

    // push paths down pipeline
    for ((id, files) <- this.fileMap) {
      // parse ipAddress, port
      val idFields = id.split(":")
      val (ipAddress, port) = (idFields(0), idFields(1).toInt)

      for (file <- files) {
        var path = file.getPath.toString
        if (!nahQuery.isEmpty) {
          path += ("+" + nahQuery)
        }

        inputQueue.put((ipAddress, port, path, file.getLen))
      }
    }

    for (i <- 1 to queryThreads) {
      inputQueue.put(("", 0, "", 0))
    }

    // compute partitions
    val partitions: List[InputPartition[InternalRow]] = new ArrayList()

    var poisonCount = 0
    while (poisonCount < queryThreads) {
      val result = outputQueue.take()
      if (!result.isInstanceOf[HdfsProtos.LocatedBlocksProto]) {
        poisonCount += 1
      } else {
        val lbsProto = result.asInstanceOf[HdfsProtos.LocatedBlocksProto]

        for (lbProto <- lbsProto.getBlocksList) {
          // parse block addresses
          val locations = lbProto.getLocsList
            .map(_.getId.getIpAddr).toArray
          val ports = lbProto.getLocsList
            .map(_.getId.getXferPort).toArray

          var offset = 0l
          val blockLength = lbProto.getB.getNumBytes
          while (offset < blockLength) {
            val length = scala.math.min(maxPartitionBytes,
              blockLength - offset)
            val lookahead = scala.math.min(1024,
              blockLength - (offset + length))

            // initialize block partition
            partitions += new NahPartition(dataSchema,
              requiredSchema, lbProto.getB.getBlockId, offset,
              length, offset == 0, lookahead, locations, ports)

            offset += length
          }
        }
      }
    }

    partitions
  }

  override def pruneColumns(requiredSchema: StructType) = {
    this.requiredSchema = requiredSchema
  }

  override def pushFilters(filters: Array[Filter]): Array[Filter] = {
    // parse out filters applicable to the nah file system
    val (validFilters, rest) = filters.partition(isValidFilter(_))
    this.filters = validFilters

    rest
  }

  override def pushedFilters: Array[Filter] = {
    this.filters
  }

  private def getLatitudeFeature(): String = {
    fileFormat match {
      case "CsvPoint" => {
        val index = formatFields("latitude_index").toInt
        dataSchema.fieldNames(index)
      }
      case _ => null
    }
  }

  private def getLongitudeFeature(): String = {
    fileFormat match {
      case "CsvPoint" => {
        val index = formatFields("longitude_index").toInt
        dataSchema.fieldNames(index)
      }
      case _ => null
    }
  }

  private def isValidFilter(filter: Filter): Boolean = {
    val latitude = getLatitudeFeature
    val longitude = getLongitudeFeature
    
    filter match {
      case GreaterThan(x, _) => x == latitude || x == longitude
      case GreaterThanOrEqual(x, _) =>  x == latitude || x == longitude
      case LessThan(x, _) =>  x == latitude || x == longitude
      case LessThanOrEqual(x, _) =>  x == latitude || x == longitude
      case _ => false
    }
  }
}
