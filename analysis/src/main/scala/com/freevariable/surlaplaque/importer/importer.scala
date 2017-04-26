/*
 * This file is a part of the "sur la plaque" toolkit for cycling
 * data analytics and visualization.
 *
 * Copyright (c) 2013--2014 William C. Benton and Red Hat, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.freevariable.surlaplaque.importer;

import com.freevariable.surlaplaque.data._

import scala.util.{Try, Success}
import scala.xml.XML

import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

object extract {
    
    def tupleFromTrackpoint(tp: scala.xml.Node, f: Option[String] = None) = Trackpoint(timestamp(tp), latlong(tp), alt(tp), watts(tp), speed=speed(tp), cadence=cadence(tp), heartrate=heartrate(tp), distance=distance(tp), activity=f )

    def timestamp(tp: scala.xml.Node) = (tp \ "Time").text

    def latlong(tp: scala.xml.Node) = {
        val lat = (tp \\ "LatitudeDegrees").text.toDouble
        val lon = (tp \\ "LongitudeDegrees").text.toDouble
        new Coordinates(lat, lon)
    }

    def alt(tp: scala.xml.Node) = (tp \ "AltitudeMeters").text.toDouble

    def watts(tp: scala.xml.Node) = (tp \\ "Watts").text match {
        case "" => 0.0
        case x: String => x.toDouble
    }

    def speed(tp: scala.xml.Node) = optionalDouble("Speed")(tp)
    def cadence(tp: scala.xml.Node) = optionalDouble("Cadence")(tp)
    def heartrate(tp: scala.xml.Node) = optionalDouble("HeartRateBpm")(tp)
    def distance(tp: scala.xml.Node) = optionalDouble("DistanceMeters")(tp)
    
    private def optionalDouble(field: String) = {
      def odhelper(tp: scala.xml.Node) = (tp \\ field).text match {
	case "" => None
	case x: String => Try(x.toDouble).toOption
      }
      odhelper _
    }

    def trackpointDataFromFile(tcx: String) = {
        val tcxTree = XML.loadFile(tcx)
        val (successes, failures) = (tcxTree \\ "Trackpoint").map(x => Try(extract.tupleFromTrackpoint(x, Some(tcx)))).partition(_.isSuccess)
        if (failures.size > 0) {
          Console.println("warning: encountered " + failures.size + s" failures processing file $tcx")
        }
        for (tp <- successes) yield tp.get
    }
}

object TCX2CSV {
    def main(args: Array[String]) {
        for (file <- args.toList) 
          for (tp @ Trackpoint(timestamp, Coordinates(lat, lon), alt, watts, speed, distance, heartrate, cadence, Some(file)) <- extract.trackpointDataFromFile(file))
                Console.println("%s,%f,%f,%f,%f".format(tp.timestring, lat, lon, alt, watts))
    }
}

object TCX2Json {
   import java.io._
   import com.freevariable.surlaplaque.app.SLP.expandArgs
   
   def outputFile = sys.env.get("TCX2J_OUTPUT_FILE") match {
       case Some("--") => new PrintWriter(System.err)
       case Some(filename) => new PrintWriter(new File(filename))
       case None => new PrintWriter(new File("slp.json"))
   }
    
    def main(args: Array[String]) {
       val processedArgs = expandArgs(args)
       
       val tuples = processedArgs.toList.flatMap((file => 
          for (tp @ Trackpoint(timestamp, Coordinates(lat, lon), alt, watts, speed, distance, heartrate, cadence, Some(file)) <- extract.trackpointDataFromFile(file))
             yield ("timestamp" -> tp.timestring) ~ ("lat" -> lat) ~ ("lon" -> lon) ~ ("alt" -> alt) ~ ("watts" -> watts)
             )
             )
       val out = outputFile
       out.println(pretty(tuples))
       out.close
    }
}

object TCX2Parquet {
  import java.io._
  import com.freevariable.surlaplaque.app.SLP.expandArgs
  import org.apache.spark.sql.SparkSession

   def outputFile = sys.env.get("TCX2J_OUTPUT_FILE") match {
       case Some(filename) => filename
       case None => "slp.parquet"
   }
    
    def main(args: Array[String]) {
      val processedArgs = expandArgs(args)
      val spark = SparkSession.builder.master("local[*]").getOrCreate()
      import spark.implicits._
      
      val tuples = spark.sparkContext.parallelize(processedArgs.toList).flatMap((file => extract.trackpointDataFromFile(file))).toDF()

      tuples.write.parquet(outputFile)
      spark.stop
    }  
}
