package com.intel.sparkColumnarPlugin.expression

import scala.collection.immutable.List
import scala.collection.JavaConverters._
import org.apache.arrow.gandiva.expression._
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.types._

trait ColumnarAggregateExpressionBase extends ColumnarExpression with Logging {
  def requiredColNum: Int = 1
  def setInputFields(fieldList: List[Field]): Unit = {}
  def doColumnarCodeGen_ext(args: Object): (TreeNode, TreeNode) = {
    throw new UnsupportedOperationException(s"ColumnarAggregateExpressionBase doColumnarCodeGen_ext is a abstract function.")
  }
}

class ColumnarUniqueAggregateExpression(aggrFieldList: List[Field]) extends ColumnarAggregateExpressionBase with Logging {

  override def doColumnarCodeGen_ext(args: Object): (TreeNode, TreeNode) = {
    val (keyFieldList, inputFieldList, resultType, resultField) =
      args.asInstanceOf[(List[Field], List[Field], ArrowType, Field)]
    val funcName = "action_unique"
    val inputNode = {
      // if keyList has keys, we need to do groupby by these keys.
      var inputFieldNode = 
        keyFieldList.map({field => TreeBuilder.makeField(field)}).asJava
      val encodeNode = TreeBuilder.makeFunction(
        "encodeArray",
        inputFieldNode,
        resultType/*this arg won't be used*/)
      inputFieldNode = 
        (encodeNode :: inputFieldList.map({field => TreeBuilder.makeField(field)})).asJava
      val groupByFuncNode = TreeBuilder.makeFunction(
        "splitArrayListWithAction",
        inputFieldNode,
        resultType/*this arg won't be used*/)
      List(groupByFuncNode) ::: aggrFieldList.map(field => TreeBuilder.makeField(field))
    }
    logInfo(s"${funcName}(${inputNode})")
    val aggregateNode = TreeBuilder.makeFunction(
        funcName,
        inputNode.asJava,
        resultType)
    (aggregateNode, null)
  }
}

class ColumnarAggregateExpression(
    aggregateFunction: AggregateFunction,
    mode: AggregateMode,
    isDistinct: Boolean,
    resultId: ExprId)
    extends AggregateExpression(aggregateFunction, mode, isDistinct, resultId)
    with ColumnarAggregateExpressionBase
    with Logging {

  var aggrFieldList: List[Field] = _
  val (funcName, argSize) = mode match {
    case Partial => 
      aggregateFunction.prettyName match {
        case "avg" => ("sum_count", 1)
        case "count" => {
          if (aggregateFunction.children(0).isInstanceOf[Literal]) {
            (s"countLiteral_${aggregateFunction.children(0)}", 0)
          } else {
            ("count", 1)
          }
        }
        case other => (aggregateFunction.prettyName, 1)
      }
    case Final =>
      aggregateFunction.prettyName match {
        case "count" => ("sum", 1)
        case "avg" => ("avgByCount", 2)
        case other => (aggregateFunction.prettyName, 1)
      }
    case _ =>
      throw new UnsupportedOperationException("doesn't support this mode")
  }

  val finalFuncName = funcName match {
    case "count" => "sum"
    case other => other
  }
  logInfo(s"funcName is $funcName, finalFuncName is $finalFuncName, mode is $mode")

  override def requiredColNum: Int = argSize
  override def setInputFields(fieldList: List[Field]): Unit = {
    aggrFieldList = fieldList
  }

  override def doColumnarCodeGen_ext(args: Object): (TreeNode, TreeNode) = {
    val (keyFieldList, inputFieldList, resultType, resultField) =
      args.asInstanceOf[(List[Field], List[Field], ArrowType, Field)]
    val (aggregateNode, finalFuncNode) = if (keyFieldList.isEmpty != true) {
      // if keyList has keys, we need to do groupby by these keys.
      var inputFieldNode = 
        keyFieldList.map({field => TreeBuilder.makeField(field)}).asJava
      val encodeNode = TreeBuilder.makeFunction(
        "encodeArray",
        inputFieldNode,
        resultType/*this arg won't be used*/)
      inputFieldNode = 
        (encodeNode :: inputFieldList.map({field => TreeBuilder.makeField(field)})).asJava
      val groupByFuncNode = TreeBuilder.makeFunction(
        "splitArrayListWithAction",
        inputFieldNode,
        resultType/*this arg won't be used*/)
      val inputAggrFieldNode = 
        List(groupByFuncNode) ::: aggrFieldList.map(field => TreeBuilder.makeField(field))
      val aggregateFuncName = "action_" + funcName
      logInfo(s"${aggregateFuncName}(${inputAggrFieldNode})")
      (TreeBuilder.makeFunction(
        aggregateFuncName,
        inputAggrFieldNode.asJava,
        resultType), null)
    } else {
      val inputFieldNode = 
        aggrFieldList.map(field => TreeBuilder.makeField(field))
      logInfo(s"${funcName}(${inputFieldNode})")
      (TreeBuilder.makeFunction(
        funcName,
        inputFieldNode.asJava,
        resultType),
      TreeBuilder.makeFunction(
        finalFuncName,
        inputFieldNode.asJava,
        resultType))
    }
    (aggregateNode, finalFuncNode)
  }
}
