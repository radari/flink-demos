/*
 * Copyright 2015 Data Artisans GmbH
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

package com.dataartisans.flink.example.eventpattern

import java.util.Properties

import _root_.kafka.consumer.ConsumerConfig
import com.dataartisans.flink.example.eventpattern.kafka.EventDeSerializer
import com.dataartisans.flink.util.PerformanceCounter

import org.apache.flink.api.common.functions.RichFlatMapFunction
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.checkpoint.Checkpointed
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.connectors.kafka.api.persistent.PersistentKafkaSource
import org.apache.flink.util.Collector

import scala.collection.mutable

/**
 * Demo streaming program that receives (or generates) a stream of events and evaluates
 * a state machine (per originating IP address) to validate that the events follow
 * the state machine's rules.
 */
object StreamingDemo {

  def main(args: Array[String]): Unit = {

    val checkpointInterval = args(0).toInt

    // create the environment to create streams and configure execution
    val env = StreamExecutionEnvironment.getExecutionEnvironment
//    env.setParallelism(4)
    
    // this statement enables the checkpointing mechanism with an interval of 1 sec
    env.enableCheckpointing(checkpointInterval)
    
    // create a data stream from the generator
    val stream = env.addSource(new EventsGeneratorSource(true))
    
    // data stream from kafka topic.
//    val props = new Properties()
//    props.put("zookeeper.connect", "localhost:2181")
//    props.put("group.id", "flink-demo-group")
//    props.put("auto.commit.enable", "false")
//    
//    val stream = env.addSource(new PersistentKafkaSource[Event]("flink-demo-topic", 
//                                                                new EventDeSerializer(),
//                                                                new ConsumerConfig(props)))

    stream
      // partition on the address to make sure equal addresses
      // end up in the same state machine flatMap function
      .partitionByHash("sourceAddress")
      
      // the function that evaluates the state machine over the sequence of events
      .flatMap(new StateMachineMapper(checkpointInterval))
      
      // output to standard-out
      .addSink(new SinkFunction[(Alert, Long)] {
      override def invoke(in: (Alert, Long)): Unit = {
        //Unused for throughput, can be used for latency
      }
    })
    
    // trigger program execution
    env.execute()
  }
}

/**
 * The function that maintains the per-IP-address state machines and verifies that the
 * events are consistent with the current state of the state machine. If the event is not
 * consistent with the current state, the function produces an alert.
 */
class StateMachineMapper(checkpointInterval : Int) extends RichFlatMapFunction[(Event, Long), (Alert, Long)] with Checkpointed[mutable.HashMap[Int, State]] {
  
  private[this] val states = new mutable.HashMap[Int, State]()

  @transient
  var pCounter: PerformanceCounter = null

  @throws(classOf[Exception])
  override def open(parameters: Configuration) {
    pCounter = new PerformanceCounter("pc", 1000, 1000, 30000,  "/home/mbalassi/flink-state-perf-" +
      checkpointInterval + "-" + getRuntimeContext.getIndexOfThisSubtask + ".csv")
  }

  override def flatMap(tuple: (Event, Long), out: Collector[(Alert, Long)]): Unit = {

    pCounter.count()

    val t = tuple._1

    // get and remove the current state
    val state = states.remove(t.sourceAddress).getOrElse(InitialState)

    val nextState = state.transition(t.event)
    if (nextState == InvalidTransition) {
      // Output is unused for cluster measurements
      //out.collect(Alert(t.sourceAddress, state, t.event))
    } 
    else if (!nextState.terminal) {
      states.put(t.sourceAddress, nextState)
    }
  }

  /**
   * Draws a snapshot of the function's state.
   * 
   * @param checkpointId The ID of the checkpoint.
   * @param timestamp The timestamp when the checkpoint was instantiated.
   * @return The state to be snapshotted, here the hash map of state machines.
   */
  override def snapshotState(checkpointId: Long, timestamp: Long): mutable.HashMap[Int, State] = {
    states
  }

  /**
   * Restores the state.
   * 
   * @param state The state to be restored.
   */
  override def restoreState(state: mutable.HashMap[Int, State]): Unit = {
    states ++= state
  }
}
