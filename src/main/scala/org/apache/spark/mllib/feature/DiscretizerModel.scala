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

import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg._
import org.apache.spark.annotation.Experimental

/**
 * Generic discretizer model that transform data given a list of thresholds by feature.
 * @param thresholds Thresholds defined by feature (both must be sorted)
 *  
 * Note: checking the second sorting condition can be much more time-consuming. 
 * We omit this condition.
 */

@Experimental
class DiscretizerModel (val thresholds: Array[Array[Float]]) extends VectorTransformer {
  
  //require(isSorted(thresholds.map(_._1)), "Array has to be sorted asc")
  
  /*protected def isSorted(array: Array[Int]): Boolean = {
    var i = 1
    while (i < array.length) {
      if (array(i) < array(i-1)) return false
      i += 1
    }
    true
  }
  
  /**
   * Discretizes values for a single example using thresholds.
   *
   * @param data Vector.
   * @return Discretized vector (with bins from 1 to n).
   */
  override def transform(data: Vector) = {
    data match {
      case v: SparseVector =>
        var newValues = Array.empty[Double]
        var j = 0
        for (i <- 0 until v.indices.length){
          val tmpj = thresholds.indexWhere({case (idx, _) => v.indices(i) == idx}, j)
          if (tmpj != -1) {
            newValues = assignDiscreteValue(v(i), thresholds(tmpj)._2).toDouble +: newValues
            j = tmpj
          } else {                  
            newValues = v(i) +: newValues
          }
        }
        // the `index` array inside sparse vector object will not be changed
        Vectors.sparse(v.size, v.indices, newValues.reverse)
        
        case v: DenseVector =>
          var newValues = Array.empty[Double]
          var j = 0
          for (i <- 0 until v.values.length){
            val tmpj = thresholds.indexWhere({case (idx, _) => i == idx}, j)
            if (tmpj != -1) {
              newValues = assignDiscreteValue(v.values(i), thresholds(tmpj)._2).toDouble +: newValues
              j = tmpj
            } else {                  
              newValues = v.values(i) +: newValues
            }
          }          
          Vectors.dense(newValues.reverse)
    }    
  }*/
  
  override def transform(data: Vector) = {
    data match {
      case v: SparseVector =>
        val newValues = for (i <- 0 until v.indices.length) 
          yield assignDiscreteValue(v.values(i), thresholds(v.indices(i))).toDouble
        
        // the `index` array inside sparse vector object will not be changed,
        // so we can re-use it to save memory.
        Vectors.sparse(v.size, v.indices, newValues.toArray)
        
        case v: DenseVector =>
          val newValues = for (i <- 0 until v.values.length)
            yield assignDiscreteValue(v(i), thresholds(i)).toDouble         
          Vectors.dense(newValues.toArray)
    }    
  }
  
  /**
   * Discretizes values in a given dataset using thresholds.
   *
   * @param data RDD with continuous-valued vectors.
   * @return RDD with discretized data (bins from 1 to n).
   */
  override def transform(data: RDD[Vector]) = {
    val bc_thresholds = data.context.broadcast(thresholds)    
    data.map {
      case v: SparseVector =>
        val newValues = for (i <- 0 until v.indices.length) 
          yield assignDiscreteValue(v.values(i), bc_thresholds.value(v.indices(i))).toDouble
        
        // the `index` array inside sparse vector object will not be changed,
        // so we can re-use it to save memory.
        Vectors.sparse(v.size, v.indices, newValues.toArray)
        
        case v: DenseVector =>
          val newValues = for (i <- 0 until v.values.length)
            yield assignDiscreteValue(v(i), bc_thresholds.value(i)).toDouble         
          Vectors.dense(newValues.toArray)
    }    
  }

  /**
   * Discretizes a value with a set of intervals.
   *
   * @param value Value to be discretized
   * @param thresholds Thresholds used to assign a discrete value
   * 
   * Note: The last threshold must be always Positive Infinity
   */
  private def assignDiscreteValue(value: Double, thresholds: Seq[Float]) = {
    if(thresholds.isEmpty) value else thresholds.indexWhere{value <= _} + 1
  }

}