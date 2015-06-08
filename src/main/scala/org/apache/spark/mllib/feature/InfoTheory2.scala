/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.feature

import breeze.linalg._
import breeze.numerics._
import breeze.linalg.{DenseVector => BDV, SparseVector => BSV, Vector => BV, DenseMatrix => BDM}
import scala.collection.immutable.HashMap
import scala.collection.mutable
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.SparkException
import scala.collection.immutable.TreeMap

/**
 * Information Theory function and distributed primitives.
 */

class InfoTheory2 extends Serializable {
  
  protected def computeFrequency(data: Map[Long, Byte], nInstances: Long) = {
    val tmp = data.values.groupBy(l => l).map(t => (t._1, t._2.size.toLong))
    tmp.get(0) match {
      case Some(_) => tmp
      case None => 
        val lastElem = (0: Byte, nInstances - tmp.values.sum) 
        tmp + lastElem
    }
  }
  
  protected def computeMutualInfo(
      data: RDD[(Int, BDM[Long])],
      yProb: BDV[Float],
      nInstances: Long) = {    
    
    val byProb = data.context.broadcast(yProb)       
    data.mapValues({ m =>
      var mi = 0.0d
      // Aggregate by row (x)
      val xProb = sum(m(*, ::)).map(_.toFloat / nInstances)
      for(i <- 0 until m.rows){
        for(j <- 0 until m.cols){
          val pxy = m(i, j).toFloat / nInstances
          val py = byProb.value(j); val px = xProb(i)
          if(pxy != 0 && px != 0 && py != 0) // To avoid NaNs
            mi += pxy * (math.log(pxy / (px * py)) / math.log(2))
        }
      } 
      mi        
    })  
  }
  
  protected def computeConditionalMutualInfo(
      data: RDD[(Int, BDV[BDM[Long]])],
      varY: Int,
      varZ: Int,
      marginalProb: RDD[(Int, BDV[Float])],
      jointProb: RDD[(Int, BDM[Float])],
      n: Long) = {

    val sc = data.context
    val yProb = sc.broadcast(marginalProb.lookup(varY)(0))
    val zProb = sc.broadcast(marginalProb.lookup(varZ)(0))
    val yzProb = sc.broadcast(jointProb.lookup(varY)(0))    

    data.mapValues({ m =>
      var cmi = 0.0d; var mi = 0.0d
      // Aggregate matrices by row (X)
      val aggX = m.map(h1 => sum(h1(*, ::)).toDenseVector)
      // Use the previous variable to sum up and so obtaining X accumulators 
      val xProb = aggX.reduce(_ + _).apply(0).map(_.toFloat / n)
      // Aggregate all matrices in Z to obtain the unique XY matrix
      val xyProb = m.reduce(_ + _).apply(0).map(_.toFloat / n)  
      val xzProb = aggX.map(_.map(_.toFloat / n))
      
      for(z <- 0 until m.length){
        for(x <- 0 until m(z).rows){
          for(y <- 0 until m(z).cols) {
            val pz = zProb.value(z)
            val pxyz = (m(z)(x, y).toFloat / n) / pz
            val pxz = xzProb(z)(x) / pz
            val pyz = yzProb.value(y, z) / pz
            if(pxz != 0 && pyz != 0 && pxyz != 0)
              cmi += pz * pxyz * (math.log(pxyz / (pxz * pyz)) / math.log(2))
            if (z == 0) { // Do MI computations only once
              val px = xProb(x)
              val pxy = xyProb(x, y)
              val py = yProb.value(y)
              if(pxy != 0 && px != 0 && py != 0)
                mi += pxy * (math.log(pxy / (px * py)) / math.log(2))
            }
          }            
        }
      } 
      (mi, cmi)        
    })  
  }
  
}

class InfoTheorySparse (
    val data: RDD[(Int, Map[Long, Byte])], 
    fixedFeat: Int,
    val nInstances: Long,      
    val nFeatures: Int) extends InfoTheory2 with Serializable {
    
  // Store counter for future iterations
  //val counterByFeat = data.context.broadcast(classCounter)
  val counterByFeat = {
    val counterByKey: Map[Int, Int] = data.mapValues(
        v => if(v.valuesIterator.isEmpty) 1 else v.valuesIterator.max + 1)
      .collectAsMap()
      .toMap
    data.context.broadcast(counterByKey)
  }
  
  // Broadcast Y vector
  val fixedVal = data.filter({ case (k, _) => k == fixedFeat}).mapValues({ m =>
    m match {
      case tm: TreeMap[Long, Byte] => tm.valuesIterator.toArray
      case hm: HashMap[Long, Byte] => 
        val arr = Array.fill[Byte](nInstances.toInt)(0)
        hm.map({ case (k, v) => arr(k.toInt) = v })
        arr
    }
  })
  
  val fixedCol = (fixedFeat, data.context.broadcast(data.lookup(fixedFeat)(0)))
  val fixedColHistogram = computeFrequency(fixedCol._2.value, nInstances)
  
  val (marginalProb, jointProb, relevances) = {
    val histograms = computeHistogramsSparse(data, 
      fixedCol, fixedColHistogram, nInstances)
    val jointTable = histograms.mapValues(_.map(_.toFloat / nInstances)).cache()
    val marginalTable = jointTable.mapValues(h => sum(h(*, ::)).toDenseVector).cache()
    
    // Remove output feature from the computations
    val label = nFeatures - 1 
    val fdata = histograms.filter{case (k, _) => k != label}
    val marginalY = marginalTable.lookup(fixedFeat)(0)
    val relevances = computeMutualInfo(fdata, marginalY, nInstances).cache()
    (marginalTable, jointTable, relevances)
  }
    
  def getRelevances(varY: Int) = relevances
  
  def getRedundancies(
      varX: Seq[Int],
      varY: Int) = {    
    
    // Pre-requisites
    require(varX.size > 0)
    
    // Prepare Y and Z vector
    val ycol = data.lookup(varY)(0)
    val (varZ, zcol) = fixedCol

    // Broadcast variables
    val filterData = {
      val sc = data.context
      // A boolean vector that indicates the variables involved on this computation
      val fselected = Array.ofDim[Boolean](nFeatures)
      fselected(varY) = true; fselected(varZ) = true
      varX.map(fselected(_) = true)
      val bFeatSelected = sc.broadcast(fselected)
      // Filter data by these variables
      data.filter({ case (k, _) => bFeatSelected.value(k)})
    }   

    // Compute conditional histograms for all variables
    // Then, we remove those not present in X set
    val histograms3d = computeConditionalHistogramsSparse(
        filterData, (varY, ycol), nInstances)
        .filter{case (k, _) => k != varZ && k != varY}
      
    // Compute CMI and MI for all X variables
    computeConditionalMutualInfo(histograms3d, varY, varZ, 
        marginalProb, jointProb, nInstances)
 }
    
  private def computeHistogramsSparse(
      filterData:  RDD[(Int, Map[Long, Byte])],
      ycol: (Int, Broadcast[Map[Long, Byte]]),
      yhist: Map[Byte, Long],
      nInstances: Long) = {
    
    val maxSize = 256
    val bycol = ycol._2
    val counter = counterByFeat
    val ys = counter.value.getOrElse(ycol._1, maxSize).toInt
      
    filterData.map({ case (feat, xcol) =>        
      val result = BDM.zeros[Long](
          counter.value.getOrElse(feat, maxSize).toInt, ys)
      
      val histCls = mutable.HashMap.empty ++= yhist // clone
      for ( (inst, x) <- xcol){     
        val y = bycol.value.getOrElse(inst, 0: Byte)     
        histCls += y -> (histCls(y) - 1)
        result(x, y) += 1
      }
      // Zeros count
      histCls.foreach({ case (c, q) => result(0, c) += q })
      feat -> result
    })
  }
  
  private def computeConditionalHistogramsSparse(
    filterData: RDD[(Int, Map[Long, Byte])],
    ycol: (Int, Map[Long, Byte]),
    nInstances: Long) = {
    
      val bycol = filterData.context.broadcast(ycol._2)
      val bzcol = fixedCol._2
      val counter = counterByFeat
      val zhist = fixedColHistogram
      val ys = counter.value.getOrElse(ycol._1, 256)
      val zs = counter.value.getOrElse(fixedCol._1, 256)
      
      filterData.map({ case (feat, xcol) =>        
        val result = BDV.fill[BDM[Long]](zs){
          BDM.zeros[Long](counter.value.getOrElse(feat, 256), ys)
        }
        
        // Elements appearing in X
        val histCls = mutable.HashMap.empty ++= zhist // clone
        for ( (inst, x) <- xcol){     
          val y = bycol.value.getOrElse(inst, 0: Byte)
          val z = bzcol.value.getOrElse(inst, 0: Byte)          
          histCls += z -> (histCls(z) - 1)
          result(z)(x, y) += 1
        }
        
        // The remaining elements in Y but not in X
        for( (inst, y) <- bycol.value) {
         xcol.get(inst) match {
           case Some(_) => /* Do nothing */
           case None =>
             val z = bzcol.value.getOrElse(inst, 0: Byte)          
             histCls += z -> (histCls(z) - 1)
             result(z)(0, y) += 1
         }
        }
        
        // Zeros count
        histCls.foreach({ case (c, q) => result(c)(0, 0) += q })
        feat -> result
    })
  }
}

class InfoTheoryDense (
    val data: RDD[(Int, (Int, Array[Byte]))], 
    fixedFeat: Int,
    val nInstances: Long,      
    val nFeatures: Int) extends InfoTheory2 with Serializable {
    
  // Store counter for future iterations
  //val counterByFeat = data.context.broadcast(classCounter)
  val counterByFeat = {
      val counter = data.mapValues({ case (_, v) => v.max + 1})
          .reduceByKey((m1, m2) => if(m1 > m2) m1 else m2)
          .collectAsMap()
          .toMap
      data.context.broadcast(counter)
  }
  
  // Broadcast Y vector
  val fixedCol = {
    val yvals = data.lookup(fixedFeat)
    var ycol = Array.ofDim[Array[Byte]](yvals.length)
    yvals.foreach({ case (b, v) => ycol(b) = v })
    fixedFeat -> data.context.broadcast(ycol)
  }
  
  val (marginalProb, jointProb, relevances) = {
    val histograms = computeHistograms(data, fixedCol, nFeatures)
    val jointTable = histograms.mapValues(_.map(_.toFloat / nInstances)).cache()
    val marginalTable = jointTable.mapValues(h => sum(h(*, ::)).toDenseVector).cache()
    
    // Remove output feature from the computations
    val fdata = histograms.filter{case (k, _) => k != fixedFeat}
    val marginalY = marginalTable.lookup(fixedFeat)(0)
    val relevances = computeMutualInfo(fdata, marginalY, nInstances).cache()
    (marginalTable, jointTable, relevances)
  }
    
  def getRelevances(varY: Int) = relevances
  
  def getRedundancies(
      varX: Seq[Int],
      varY: Int) = {    
    
    // Pre-requisites
    require(varX.size > 0)

    // Broadcast variables
    val filterData = {
      val sc = data.context
      // A boolean vector that indicates the variables involved on this computation
      val fselected = Array.ofDim[Boolean](nFeatures)
      fselected(varY) = true; fselected(fixedCol._1) = true
      varX.map(fselected(_) = true)
      val bFeatSelected = sc.broadcast(fselected)
      // Filter data by these variables
      data.filter({ case (k, _) => bFeatSelected.value(k)})
    }
    
    // Prepare Y and Z vector
    val yvals = data.lookup(varY)
    val ycol = Array.ofDim[Array[Byte]](yvals.length)
    yvals.foreach({ case (b, v) => ycol(b) = v })
    val (varZ, _) = fixedCol

    // Compute conditional histograms for all variables
    // Then, we remove those not present in X set
    val histograms3d = computeConditionalHistograms(
        filterData, (varY, ycol), fixedCol, nInstances)
        .filter{case (k, _) => k != varZ && k != varY}
      
    // Compute CMI and MI for all X variables
    computeConditionalMutualInfo(histograms3d, varY, varZ, 
        marginalProb, jointProb, nInstances)
 }
    
  private def computeHistograms(
      data:  RDD[(Int, (Int, Array[Byte]))],
      ycol: (Int, Broadcast[Array[Array[Byte]]]),
      nFeatures: Long) = {
    
    val maxSize = 256 
    //val bycol = data.context.broadcast(ycol._2)
    val bycol = ycol._2
    val counter = counterByFeat 
    val ys = counter.value.getOrElse(ycol._1, maxSize).toInt
      
    data.mapPartitions({ it =>
      var result = Map.empty[Int, BDM[Long]]
      for((feat, (block, arr)) <- it) {
        val m = result.getOrElse(feat, 
            BDM.zeros[Long](counter.value.getOrElse(feat, maxSize).toInt, ys)) 
        for(i <- 0 until arr.length) 
          m(arr(i), bycol.value(block)(i)) += 1
        result += feat -> m
      }
      result.toIterator
    }).reduceByKey(_ + _)
  }
  
  private def computeConditionalHistograms(
    data: RDD[(Int, (Int, Array[Byte]))],
    ycol: (Int, Array[Array[Byte]]),
    zcol: (Int, Broadcast[Array[Array[Byte]]]),
    nFeatures: Long) = {
    
      val bycol = data.context.broadcast(ycol._2)
      //val bzcol = data.context.broadcast(zcol._2)
      val bzcol = zcol._2
      val bcounter = counterByFeat
      val ys = counterByFeat.value.getOrElse(ycol._1, 256)
      val zs = counterByFeat.value.getOrElse(zcol._1, 256)
      
      data.mapPartitions({ it =>
        var result = Map.empty[Int, BDV[BDM[Long]]]
        for((feat, (block, arr)) <- it) {
          // We create a vector (z) of matrices (x,y) to represent a 3-dim matrix
          val m = result.getOrElse(feat, 
              BDV.fill[BDM[Long]](zs){BDM.zeros[Long](bcounter.value.getOrElse(feat, 256), ys)})
          for(i <- 0 until arr.length){
            val y = bycol.value(block)(i)
            val z = bzcol.value(block)(i)
            m(z)(arr(i), y) += 1
          }
          result += feat -> m
        }
        result.toIterator
      }).reduceByKey(_ + _)
  }
}

object InfoTheory2 {
  
  def initializeSparse(data: RDD[(Int, Map[Long, Byte])], 
    fixedFeat: Int,
    nInstances: Long,      
    nFeatures: Int) = {
      new InfoTheorySparse(data, fixedFeat, nInstances, nFeatures)
  }
  
  def initializeDense(data: RDD[(Int, (Int, Array[Byte]))], 
    fixedFeat: Int,
    nInstances: Long,      
    nFeatures: Int) = {
      new InfoTheoryDense(data, fixedFeat, nInstances, nFeatures)
  }
  
}
