package com.intel.sparkColumnarPlugin

import com.intel.sparkColumnarPlugin.execution._

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.aggregate.HashAggregateExec
import org.apache.spark.sql.execution.datasources.v2.BatchScanExec
import org.apache.spark.sql.execution.exchange.ShuffleExchangeExec
import org.apache.spark.sql.execution.joins.ShuffledHashJoinExec
import org.apache.spark.sql.{SparkSession, SparkSessionExtensions}

case class ColumnarOverrides() extends Rule[SparkPlan] {

  def replaceWithColumnarPlan(plan: SparkPlan): SparkPlan = plan match {
    case plan: BatchScanExec =>
      logWarning(s"Columnar Processing for ${plan.getClass} is currently supported.")
      new ColumnarBatchScanExec(plan.output, plan.scan)
    case plan: ProjectExec =>
      logWarning(s"Columnar Processing for ${plan.getClass} is currently supported.")
      val columnarPlan = replaceWithColumnarPlan(plan.child)
      val res = if (!columnarPlan.isInstanceOf[ColumnarConditionProjectExec]) {
        new ColumnarConditionProjectExec(null, columnarPlan)
      } else {
        columnarPlan
      }
      res.asInstanceOf[ColumnarConditionProjectExec].addProjExprs(plan.projectList)
      res
    case plan: FilterExec =>
      logWarning(s"Columnar Processing for ${plan.getClass} is currently supported.")
      new ColumnarConditionProjectExec(plan.condition, replaceWithColumnarPlan(plan.child))
    case plan: HashAggregateExec =>
      logWarning(s"Columnar Processing for ${plan.getClass} is currently supported.")
      new ColumnarHashAggregateExec(
        plan.requiredChildDistributionExpressions,
        plan.groupingExpressions,
        plan.aggregateExpressions,
        plan.aggregateAttributes,
        plan.initialInputBufferOffset,
        plan.resultExpressions,
        replaceWithColumnarPlan(plan.child))
    case plan: SortExec =>
      logWarning(s"Columnar Processing for ${plan.getClass} is currently supported.")
      new ColumnarSortExec(
        plan.sortOrder,
        plan.global,
        replaceWithColumnarPlan(plan.child),
        plan.testSpillFrequency)
    /*case plan: ShuffleExchangeExec =>
      logWarning(s"Columnar Processing for ${plan.getClass} is currently supported.")
      new ColumnarShuffleExchangeExec(
        plan.outputPartitioning,
        replaceWithColumnarPlan(plan.child),
        plan.canChangeNumPartitions)
    case plan: ShuffledHashJoinExec =>
      logWarning(s"Columnar Processing for ${plan.getClass} is currently supported.")
      new ColumnarShuffledHashJoinExec(
        plan.leftKeys,
        plan.rightKeys,
        plan.joinType,
        plan.buildSide,
        plan.condition,
        plan.left,
        plan.right)*/
    case p =>
      logWarning(s"Columnar Processing for ${p.getClass} is not currently supported.")
      p.withNewChildren(p.children.map(replaceWithColumnarPlan))
  }

  def apply(plan: SparkPlan): SparkPlan = {
    replaceWithColumnarPlan(plan)
  }
}

case class ColumnarOverrideRules(session: SparkSession) extends ColumnarRule with Logging {
  def columnarEnabled =
    session.sqlContext.getConf("org.apache.spark.example.columnar.enabled", "true").trim.toBoolean
  val overrides = ColumnarOverrides()

  override def preColumnarTransitions: Rule[SparkPlan] = plan => {
    if (columnarEnabled) {
      overrides(plan)
    } else {
      plan
    }
  }
}

/**
 * Extension point to enable columnar processing.
 *
 * To run with columnar set spark.sql.extensions to com.intel.sparkColumnarPlugin.ColumnarPlugin
 */
class ColumnarPlugin extends Function1[SparkSessionExtensions, Unit] with Logging {
  override def apply(extensions: SparkSessionExtensions): Unit = {
    logWarning(
      "Installing extensions to enable columnar CPU support." +
        " To disable this set `org.apache.spark.example.columnar.enabled` to false")
    extensions.injectColumnar((session) => ColumnarOverrideRules(session))
  }
}
