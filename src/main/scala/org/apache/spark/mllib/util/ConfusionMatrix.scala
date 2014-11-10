package org.apache.spark.mllib.util

import org.jblas.DoubleMatrix
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.classification.ClassificationModel
import scala.collection.mutable
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.regression.LabeledPoint

trait ConfusionMatrix extends Serializable {

  def precision(label: Double): Double

  def recall(label: Double): Double

  def precision: Array[(Double, Double)]

  def recall: Array[(Double, Double)]
  
  def specificity: Array[(Double, Double)]

  def fValue(label: Double): Double

  def fValue: Array[(Double, Double)]

  def fValueWithBeta(Double: Double, beta: Double): Double

  def fValueWithBeta(beta: Double): Array[(Double, Double)]

  def accuracy: Double

}

trait ConfusionMatrixWithDict extends Serializable {

  def precision(label: String): Double

  def recall(label: String): Double

  def precision: Array[(String, Double)]

  def recall: Array[(String, Double)]
  
  def specificity: Array[(String, Double)]

  def fValue(label: String): Double

  def fValue: Array[(String, Double)]

  def fValueWithBeta(label: String, beta: Double): Double

  def fValueWithBeta(beta: Double): Array[(String, Double)]

  def accuracy: Double

}

class ConfusionMatrixImpl(val dataAndPreds: RDD[(Double, Double)]) 
  extends ConfusionMatrix{

  val result = dataAndPreds.map(t => ((t._1, t._2), 1)).aggregate(
      mutable.Map.empty[(Double, Double), Long])(
    {
      (combiner, point) =>
        val count = combiner.getOrElse(point._1, 0L)
        combiner(point._1) = count + 1
        combiner
    }, { (lhs, rhs) =>
      for ((key, value) <- rhs) {
        val rcount = value
        val lcount = lhs.getOrElse(key, 0L)
        lhs(key) = (rcount + lcount)
      }
      lhs
    })

  val lablelIndexMap = dataAndPreds.map(_._1).distinct.collect.zipWithIndex.toMap
  	print(lablelIndexMap.toString)
  var innerMatrix = DoubleMatrix.zeros(lablelIndexMap.size, lablelIndexMap.size)
  
  result.foreach {
    case (key, value) =>
      val row = lablelIndexMap(key._1)
      val col = lablelIndexMap(key._2)
      innerMatrix.put(row, col, value)
  }

  private val colSum = innerMatrix.columnSums().toArray()
  private val rowSum = innerMatrix.rowSums().toArray()
  
  def this (points: RDD[LabeledPoint], 
        model: ClassificationModel) = {
	    this(ConfusionMatrix.predictData(points, model))
  }

  def precision(label: Double): Double = {
    if (!lablelIndexMap.contains(label)) {
      throw new RuntimeException("No such label:" + label +
        ", the availabel labels are following:" + lablelIndexMap.map(_._1).mkString(","))
    }
    val index = lablelIndexMap(label)
    val correct = innerMatrix.get(index, index)
    val all = rowSum(index)
    correct / all
  }

  def recall(label: Double): Double = {
    if (!lablelIndexMap.contains(label)) {
      throw new RuntimeException("No such label:" + label +
        ", the availabel labels are following:" + lablelIndexMap.map(_._1).mkString(","))
    }
    val index = lablelIndexMap(label)
    val correct = innerMatrix.get(index, index)
    val all = colSum(index)
    correct / all
  }
  
  def specificity(label: Double): Double = {
	if (!lablelIndexMap.contains(label)) {
      throw new RuntimeException("No such label:" + label +
        ", the availabel labels are following:" + lablelIndexMap.map(_._1).mkString(","))
    }
	
	val diagonalSum = lablelIndexMap.map(e => {
		val (auxLabel, colIndex) = (e._1, e._2)
		if(auxLabel != label) (innerMatrix.get(colIndex, colIndex), colSum(colIndex)) else (0.0, 0.0)
      })
    val (incorrect, all) = (diagonalSum.map(_._1).sum, diagonalSum.map(_._2).sum)
    incorrect / all
  }
  
  def specificity: Array[(Double, Double)] = {
    lablelIndexMap.map(e => {
      val label = e._1
      (label, specificity(label))
    }).toArray
  }

  def precision: Array[(Double, Double)] = {
    lablelIndexMap.map(e => {
      val label = e._1
      (label, precision(label))
    }).toArray
  }

  def recall: Array[(Double, Double)] = {
    lablelIndexMap.map(e => {
      val label = e._1
      (label, recall(label))
    }).toArray
  }

  def fValue(label: Double): Double = fValueWithBeta(label, 1.0)

  def fValue: Array[(Double, Double)] = fValueWithBeta(1.0)

  def fValueWithBeta(label: Double, beta: Double): Double = {
    val p = precision(label)
    val r = recall(label)
    (1 + Math.pow(beta, 2)) * p * r / (Math.pow(beta, 2) * p + r)
  }

  def fValueWithBeta(beta: Double): Array[(Double, Double)] = {
    lablelIndexMap.map(e => {
      val label = e._1
      (label, fValueWithBeta(label, beta))
    }).toArray
  }

  def accuracy: Double = {
    val correct = lablelIndexMap.map(e => innerMatrix.get(e._2, e._2)).sum
    val total = colSum.sum
    correct / total
  }

  override def toString: String = {
    val sb = new StringBuffer();
    val maxLabelLength = lablelIndexMap.map(_._1.toString.length()).max
    sb.append("Confusion Matrix, row=true, column=predicted  accuracy=" + accuracy + "\n")
    val maxValueLength = innerMatrix.max().toString.length()
    val cellLength = (if (maxLabelLength > maxValueLength) maxLabelLength else maxValueLength) + 1

    def format(value: String) = String.format("%1$-" + cellLength + "s\t", value)
    
    sb.append(format(""))
    lablelIndexMap.foreach(e=>{
      sb.append(format(e._1.toString))
    })
    sb.append("\n")
    
    lablelIndexMap.foreach(e => {
      val rowLabel = e._1
      val rowIndex = e._2
      sb.append(format(rowLabel.toString))
      lablelIndexMap.foreach(e2 => {
        val label = e2._1
        val colIndex = e2._2
        sb.append(format(innerMatrix.get(rowIndex, colIndex).toInt.toString))
      })
      sb.append("\n")
    })
    sb.toString
  }
}

class ConfusionMatrixWithDictImpl(val data: RDD[(Double, Double)], val dict: Map[String, Double]) 
	extends ConfusionMatrixWithDict {

  val doubleConfusionMatrix = new ConfusionMatrixImpl(data)
  
  def this (points: RDD[LabeledPoint], 
        model: ClassificationModel, dict: Map[String, Double]) = {
	    this(ConfusionMatrix.predictData(points, model), dict)
  }

  def precision(label: String): Double = {
    if (!dict.contains(label)) {
      throw new RuntimeException("No such label:" + label +
        ", the availabel labels are following:" + dict.map(_._1).mkString(","))
    }
    doubleConfusionMatrix.precision(dict(label))
  }

  def recall(label: String): Double = {
    if (!dict.contains(label)) {
      throw new RuntimeException("No such label:" + label +
        ", the availabel labels are following:" + dict.map(_._1).mkString(","))
    }
    doubleConfusionMatrix.recall(dict(label))
  }

  def specificity(label: String): Double = {
    if (!dict.contains(label)) {
      throw new RuntimeException("No such label:" + label +
        ", the availabel labels are following:" + dict.map(_._1).mkString(","))
    }
    doubleConfusionMatrix.specificity(dict(label))
  }
  
  def precision: Array[(String, Double)] = {
    dict.map(e => {
      (e._1, precision(e._1))
    }).toArray
  }

  def recall: Array[(String, Double)] = {
    dict.map(e => {
      (e._1, recall(e._1))
    }).toArray
  }
  
  def specificity: Array[(String, Double)] = {
    dict.map(e => {
      (e._1, specificity(e._1))
    }).toArray
  }

  def fValue(label: String): Double = {
    if (!dict.contains(label)) {
      throw new RuntimeException("No such label:" + label +
        ", the availabel labels are following:" + dict.map(_._1).mkString(","))
    }
    doubleConfusionMatrix.fValue(dict(label))
  }

  def fValue: Array[(String, Double)] = {
    dict.map(e => {
      (e._1, fValue(e._1))
    }).toArray
  }

  def fValueWithBeta(label: String, beta: Double): Double = {
    if (!dict.contains(label)) {
      throw new RuntimeException("No such label:" + label +
        ", the availabel labels are following:" + dict.map(_._1).mkString(","))
    }
    doubleConfusionMatrix.fValueWithBeta(dict(label), beta)
  }

  def fValueWithBeta(beta: Double): Array[(String, Double)] = {
    dict.map(e => {
      (e._1, fValueWithBeta(e._1, beta))
    }).toArray
  }

  def accuracy: Double = doubleConfusionMatrix.accuracy

    override def toString: String = {
    val sb = new StringBuffer();
    val maxLabelLength = dict.map(_._1.toString.length()).max
    sb.append("Confusion Matrix, row=true, column=predicted  accuracy=" + accuracy + "\n")
    val maxValueLength = doubleConfusionMatrix.innerMatrix.max().toString.length()
    val cellLength = (if (maxLabelLength > maxValueLength) maxLabelLength else maxValueLength) + 1

    def format(value: String) = String.format("%1$-" + cellLength + "s\t", value)
    
    sb.append(format(""))
    dict.foreach(e=>{
      sb.append(format(e._1.toString))
    })
    sb.append("\n")
    
    dict.foreach(e => {
      val rowLabel = e._1
      val rowIndex = doubleConfusionMatrix.lablelIndexMap(e._2)
      sb.append(format(rowLabel.toString))
      dict.foreach(e2 => {
        val label = e2._1
        val colIndex = doubleConfusionMatrix.lablelIndexMap(e2._2)
        sb.append(format(doubleConfusionMatrix.innerMatrix.get(rowIndex, colIndex).toInt.toString))
      })
      sb.append("\n")
    })
    sb.toString
  }
  
}

object ConfusionMatrix {

  def apply(data: RDD[LabeledPoint], model: ClassificationModel): ConfusionMatrix = {
    new ConfusionMatrixImpl(data, model)
  }
  
  def apply(dataAndPreds: RDD[(Double, Double)]): ConfusionMatrix = {
    new ConfusionMatrixImpl(dataAndPreds)
  }

  def apply(data: RDD[LabeledPoint], model: ClassificationModel, dict: Map[String, Double]): ConfusionMatrixWithDict = {
    new ConfusionMatrixWithDictImpl(data, model, dict)
  }
  
  def apply(data: RDD[(Double, Double)], dict: Map[String, Double]): ConfusionMatrixWithDict = {
    new ConfusionMatrixWithDictImpl(data, dict)
  }
  
  private[util] def predictData (points: RDD[LabeledPoint], 
        model: ClassificationModel): RDD[(Double, Double)] = {
	  val result = points.map {
	    case point =>
	      (point.label, model.predict(point.features))
	    }
	  result
  }

}