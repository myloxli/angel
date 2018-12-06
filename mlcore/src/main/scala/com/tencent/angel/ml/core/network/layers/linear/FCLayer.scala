/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017-2018 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in 
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */


package com.tencent.angel.ml.core.network.layers.linear


import com.tencent.angel.ml.core.network.TransFunc
import com.tencent.angel.ml.core.network.Graph
import com.tencent.angel.ml.math2.matrix._
import com.tencent.angel.ml.math2.ufuncs.Ufuncs
import com.tencent.angel.ml.math2.utils.{MatrixUtils, RowType}
import com.tencent.angel.ml.core.network.layers._
import com.tencent.angel.ml.core.network.layers.verge.Embedding
import com.tencent.angel.ml.core.network.variable.{MatVariable, VecVariable}
import com.tencent.angel.ml.core.optimizer.Optimizer
import com.tencent.angel.ml.core.utils.{Callback, MLException}
import org.apache.commons.logging.LogFactory

import scala.language.implicitConversions

class FCLayer(name: String, outputDim: Int, inputLayer: Layer, transFunc: TransFunc, override val optimizer: Optimizer
             )(implicit graph: Graph) extends LinearLayer(name, outputDim, inputLayer)(graph) with Trainable {
  private val LOG = LogFactory.getLog(classOf[FCLayer])

  graph.addTrainable(this)

  private val weight: MatVariable = graph.provider.getMatVariable(s"${name}_weight", outputDim,
    inputLayer.outputDim, optimizer.numSlot, inIPLayer = false)
  private val bias: VecVariable = graph.provider.getVecVariable(s"${name}_bias", outputDim,
    optimizer.numSlot, inIPLayer = false)

  @transient var forward: Matrix = _
  @transient var backward: Matrix = _
  @transient var output: Matrix = _
  @transient var gradOutput: Matrix = _
  @transient var ipOutputCache: Matrix = _

  override def calOutput(): Matrix = {
    val start = System.currentTimeMillis()
    status match {
      case STATUS.Null =>
        inputLayer match {
          case ipLayer: Embedding => // from embedding layer
            ipLayer.calOutput() match {
              case mat: RBCompIntDoubleMatrix =>
                ipOutputCache = MatrixUtils.rbCompDense2Blas(mat)
                forward = ipOutputCache.dot(weight).add(bias)
              case mat: RBCompIntFloatMatrix =>
                ipOutputCache = MatrixUtils.rbCompDense2Blas(mat)
                forward = ipOutputCache.dot(weight).add(bias)
            }
          case ipLayer => // from other dense layer
            forward = ipLayer.calOutput().dot(weight).add(bias)
        }
        output = transFunc(forward)
        status = STATUS.Forward
      case _ =>
    }

    val end = System.currentTimeMillis()
    //    println(s"FCLayer($name) calOutput = ${end - start} ms")
    output
  }

  def calGradOutput(): Matrix = {
    val start = System.currentTimeMillis()
    status match {
      case STATUS.Forward =>
        //        println(s"the status in FCLayer($name)-calGradOutput is ${status.toString}")
        val gradTemp = gatherGrad()
        backward = transFunc.calGrad(output, gradTemp)

        gradOutput = inputLayer match {
          case ipLayer: Embedding =>
            graph.modelType match {
              case RowType.T_DOUBLE_DENSE =>
                MatrixUtils.blas2RBCompDense(
                  Ufuncs.dot(backward, false, weight, true).asInstanceOf[BlasDoubleMatrix],
                  ipLayer.numFactors)
              case RowType.T_FLOAT_DENSE =>
                MatrixUtils.blas2RBCompDense(
                  Ufuncs.dot(backward, false, weight, true).asInstanceOf[BlasFloatMatrix],
                  ipLayer.numFactors
                )
            }
          case _ => Ufuncs.dot(backward, false, weight, true)
        }

        status = STATUS.Backward
      case _ =>
    }
    val end = System.currentTimeMillis()
    //    println(s"FCLayer($name) calGradOutput = ${end - start} ms")
    gradOutput
  }

  override def pullParams(epoch: Int): Unit = {
    weight.pullParams(epoch)
    bias.pullParams(epoch)
  }

  override def pushGradient(): Unit = {
    status match {
      case STATUS.Backward =>
        weight.pushGrads(inputLayer.calOutput(), backward)
        bias.pushGrads(backward, optimizer.lr)
        status = STATUS.Gradient
      case _ =>
    }
  }

  override def update[T](epoch: Int, batchSize: Int)(callback: Callback[T]): Callback[T] = {
    status match {
      case STATUS.Gradient =>
        val future = weight.update[T](optimizer, epoch, batchSize)
        callback.setFuture(future)
        bias.update[T](optimizer, epoch, batchSize)
        status = STATUS.Update

        callback
      case _ => throw MLException("STATUS Error, please calculate Gradient frist!")
    }
  }

  override def init(taskFlag: Int): Unit = {
    weight.init(taskFlag, mean = 0.0, stddev = 0.000001)
    bias.init(taskFlag, mean = 0.0, stddev = 0.000001)
  }

  override def toString: String = {
    s"FCLayer name=$name outputDim=$outputDim optimizer=$optimizer transFunc=${transFunc.getClass.getSimpleName}"
  }
}
