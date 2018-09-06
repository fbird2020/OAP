package org.apache.spark.network

import org.apache.spark.network.shuffle.ShuffleClient

abstract class TransferService extends ShuffleClient {
  def init(blockDataManager: BlockDataManager): Unit

  def close(): Unit

  def hostname: String

  def port: Int
}
