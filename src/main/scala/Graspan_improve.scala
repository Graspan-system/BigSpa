/**
  * Created by cycy on 2018/2/3.
  */

import java.util
import java.util.Scanner
import java.util.concurrent.Executors

import org.apache.hadoop.conf.Configuration
import org.apache.spark.{HashPartitioner, SparkConf, SparkContext}
import org.apache.spark.rdd.RDD
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.hbase._
import org.apache.hadoop.hbase.client.{HBaseAdmin, HTable}
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapred.TableOutputFormat
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark.storage.StorageLevel
import utils.{Graspan_OP, HBase_OP, Para, deleteDir}

object Graspan_improve extends Para{

  def main(args: Array[String]): Unit = {
    var t0=System.nanoTime():Double
    var t1=System.nanoTime():Double
    var islocal: Boolean = true
    var master: String = "local"

    var input_grammar: String = "data/test_grammar"
    var input_graph:String="data/test_graph"
    var output: String = "data/result/" //除去ip地址
    var hbase_output:String="data/result/hbase/hbhfile/"
    var defaultpar:Int=352
    var smallpar:Int=96
    var step_interval:Int=8
    var newedges_interval:Int=40000000

    var openBloomFilter:Boolean=false
    var edges_totalnum:Int=1
    var error_rate:Double=0.1

    var htable_name:String="edges"
    var queryHBase_interval:Int=2
    var HRegion_splitnum:Int=36

    var is_complete_loop:Boolean=false
    var max_complete_loop_turn:Int=5
    var max_delta:Int=10000


    for (arg <- args) {
      val argname = arg.split(",")(0)
      val argvalue = arg.split(",")(1)
      argname match {
        case "islocal" => islocal = argvalue.toBoolean
        case "master" => master = argvalue

        case "input_grammar" => input_grammar = argvalue
        case "input_graph"=>input_graph=argvalue
        case "output" => output = argvalue
        case "hbase_output"=>hbase_output=argvalue
        case "smallpar"=>smallpar=argvalue.toInt
        case "defaultpar"=>defaultpar=argvalue.toInt
        case "newedges_interval"=>newedges_interval=argvalue.toInt

        case "openBloomFilter"=>openBloomFilter=argvalue.toBoolean
        case "edges_totalnum"=>edges_totalnum=argvalue.toInt
        case "error_rate"=>error_rate=argvalue.toDouble

        case "htable_name"=>htable_name=argvalue
        case "queryHBase_interval"=>queryHBase_interval=argvalue.toInt
        case "HRegion_splitnum"=>HRegion_splitnum=argvalue.toInt

        case "is_complete_loop"=>is_complete_loop=argvalue.toBoolean
        case "max_complete_loop_turn"=>max_complete_loop_turn=argvalue.toInt
        case "max_delta"=>max_delta=argvalue.toInt

        case "step_interval"=>step_interval=argvalue.toInt
        case _ => {}
      }
    }

    /**
      * 输出参数设置
      */



    /**
      * Spark 设置
      */
    val conf = new SparkConf().set("spark.kryoserializer.buffer.max", "1024")
    if (islocal) {
      //test location can be adjusted or not
      conf.setAppName("Graspan")
      System.setProperty("hadoop.home.dir", "F:/hadoop-2.6.0/")
      conf.setMaster("local")
    }
    val sc = new SparkContext(conf)
    //    try {
    println("------------Spark and HBase settings--------------------------------")
    println("spark.driver.memory:          \t" + conf.get("spark.driver.memory"))
    println("spark.executor.memory:        \t" + conf.get("spark.executor.memory"))
    println("spark.executor.cores:         \t" + conf.get("spark.executor.cores"))
    //      println("spark.storage.memoryFraction: \t" + conf.get("spark.storage.memoryFraction"))
    //      println("spark.shuffle.memoryFraction: \t" + conf.get("spark.shuffle.memoryFraction"))
    println("default partition num: \t" + defaultpar)
    println("samll partition num:  \t" + smallpar)
    println("queryHBase_interval:  \t" + queryHBase_interval)
    println("HRegion_splitnum:     \t" + HRegion_splitnum)
    println("--------------------------------------------------------------------")
    println
    /**
      * Grammar相关设置
      */
    val grammar_origin = sc.textFile(input_grammar).map(s => s.split("\\s+")).collect().toList
    val (symbol_Map, symbol_num, symbol_num_bitsize, loop, directadd, grammar) = Graspan_OP.processGrammar(grammar_origin,
      input_grammar)
    println("------------Grammar INFO--------------------------------------------")
    println("input grammar:      \t" + input_grammar.split("/").last)
    println("symbol_num:         \t" + symbol_num)
    println("symbol_num_bitsize: \t" + symbol_num_bitsize)
    println("symbol_Map:         \t")
    symbol_Map.foreach(s => println("                    \t" + s._2 + "\t->\t" + s._1))
    println
    println("loop:               \t")
    loop.foreach(s => println("                    \t" + s))
    println
    println("directadd:          \t")
    directadd.foreach(s => println("                    \t" + s._1 + "\t->\t" + s._2))
    println
    println("grammar_clean:      \t")
    grammar.foreach(s => println("                    \t" + s._1._1 + "\t+\t" + s._1._2 + "\t->\t" + s._2))
    println("---------------------------------------------------------------------")
    println

    /**
      * Graph相关设置
      */
    val (graph, nodes_num_bitsize, nodes_totalnum) = Graspan_OP.processGraph(sc, input_graph, input_grammar, symbol_Map, loop,
      directadd, defaultpar)

    println("------------Graph INFO--------------------------------------------")
    println("input graph:        \t" + input_graph.split("/").last)
    println("processed edges:    \t" + graph.count())
    println("nodes_totoalnum:    \t" + nodes_totalnum)
    println("nodes_num_bitsize:  \t" + nodes_num_bitsize)
    println("------------------------------------------------------------------")
    println
    val htable_nodes_interval: Int = nodes_totalnum / HRegion_splitnum + 1
    val (htable_split_Map, default_split) = HBase_OP.createHBase_Table(htable_name, HRegion_splitnum)

    /**
      * 原边集存入Hbase
      */
    //    println("graph Partitions: "+graph.partitions.length)
    deleteDir.deletedir(islocal, master, hbase_output)
    HBase_OP.updateHbase(graph, nodes_num_bitsize, symbol_num_bitsize, htable_name, hbase_output,
      htable_split_Map, htable_nodes_interval, default_split)

    deleteDir.deletedir(islocal, master, output)

    var oldedges: RDD[(VertexId, ((Array[Array[Int]],Array[Array[Int]]),(Array[Array[Int]],Array[Array[Int]])))] =
      graph.flatMap(s => Array((s._1,Array(Array(s._1,s._2,s._3))), (s._2, Array(Array(s._1,s._2,s._3)))))
        .reduceByKey((x,y)=>(x ++ y))
        .repartition(defaultpar)
        .mapPartitions(s=>{
          s.map(u=>{
            val flag=u._1
            (u._1,((Array[Array[Int]](),Array[Array[Int]]()),(u._2.filter(x=>x(1)==flag).map(x=>Array(x(2),x(0))),
              u._2.filter(x=>x(0)==flag).map(x=>Array(x(2),x(1))))))
          })
        })
        .persist(StorageLevel.MEMORY_ONLY_SER)

    var step = 0
    var change_par=true
    var continue: Boolean = true
    var newnum: Long = graph.count()
    /**
      * 开始迭代
      */
    while (continue) {
      t0 = System.nanoTime(): Double
      step += 1
      println("\n************During step " + step + "************")
      /**
        * 计算
        */
      val new_edges_str = oldedges
        .mapPartitionsWithIndex((index, s) =>
          Graspan_OP.computeInPartition_completely_flat_java_Array(step,
            index, s,
            symbol_num,grammar,
            nodes_num_bitsize,
            symbol_num_bitsize, directadd, is_complete_loop, max_complete_loop_turn, max_delta, htable_name, htable_split_Map,
            htable_nodes_interval, queryHBase_interval, default_split)).persist(StorageLevel.MEMORY_ONLY_SER)
      /**
        * 记录各分区情况
        */
      val par_INFO = new_edges_str.map(s=>s._2)
      deleteDir.deletedir(islocal, master, output + "/par_INFO/step" + step)
      par_INFO.repartition(1).saveAsTextFile(output + "/par_INFO/step" + step)
      val coarest_num=new_edges_str.map(s=>s._3).sum
      println("coarest_num:                    \t"+coarest_num.toLong)
      /**
        * 新边去重
        */
      val t0_distinct=System.nanoTime():Double
      val newedges = new_edges_str.flatMap(s=>s._1).mapPartitions(s=>s.map(_.toVector)).distinct
        .mapPartitions(s=>s.map(_.toArray)).persist(StorageLevel.MEMORY_ONLY_SER)
      newnum = newedges.count()
      println("newedges:                       \t" + newnum)
      println("distinct take time:             \t" + ((System.nanoTime()-t0_distinct)/1000000000.0).formatted("%" +
        ".3f")+" secs")
      println("compute take time:              \t" + ((System.nanoTime()-t0)/1000000000.0).formatted("%.3f")+" secs")
      //        for(i<-symbol_Map){
      //          val symbol=i._1
      //          val symbol_num=i._2
      //          println(symbol+":                      \t"+newedges_removedup.filter(s=>s._3==symbol_num).count())
      //        }
      //        println("V:                              \t"+newedges_removedup.filter(s=>s._3==4).count())
      //        println("MAs:                            \t"+newedges_removedup.filter(s=>s._3==7).count())
      //        println("AMs:                            \t"+newedges_removedup.filter(s=>s._3==6).count())
      new_edges_str.unpersist()

      /**
        * 更新旧边和新边
        */
      oldedges = {
        if(newnum>=100000&&step<=40&&step%step_interval==1&&change_par==false){
          change_par=true
          (oldedges cogroup
            newedges.flatMap(s=>(Array((s(0),Array(s)),(s(1),Array(s))))).reduceByKey(_ ++ _) )
            .mapPartitions(s =>{
              s.map(x=>{
                val flag=x._1
                val old_part=x._2._1.headOption.getOrElse(((Array[Array[Int]](),Array[Array[Int]]()),(Array[Array[Int]]
                  (),Array[Array[Int]]())))
                val new_part=x._2._2.headOption.getOrElse(Array[Array[Int]]())
                (flag,((old_part._1._1 ++ old_part._2._1,old_part._1._2 ++ old_part._2._2),(new_part.filter(u=>u(1)
                  ==flag).map(u=>Array(u(2),u(0))),new_part.filter(u=>u(0)==flag).map(u=>Array(u(2),u(1))))))
              })
            })
            .repartition((step/step_interval+1)*defaultpar)
            .persist(StorageLevel.MEMORY_ONLY_SER)
        }
        else{
          change_par=false
          (oldedges cogroup
            newedges.flatMap(s=>(Array((s(0),Array(s)),(s(1),Array(s))))).reduceByKey(_ ++ _) )
            .mapPartitions(s =>{
              s.map(x=>{
                val flag=x._1
                val old_part=x._2._1.headOption.getOrElse(((Array[Array[Int]](),Array[Array[Int]]()),(Array[Array[Int]]
                  (),Array[Array[Int]]())))
                val new_part=x._2._2.headOption.getOrElse(Array[Array[Int]]())
                (flag,((old_part._1._1 ++ old_part._2._1,old_part._1._2 ++ old_part._2._2),(new_part.filter(u=>u(1)
                  ==flag).map(u=>Array(u(2),u(0))),new_part.filter(u=>u(0)==flag).map(u=>Array(u(2),u(1))))))
              })
            })
//            .repartition((step/10+1)*defaultpar)
            .persist(StorageLevel.MEMORY_ONLY_SER)
        }
      }


      /**
        * Update HBase
        */
      val t0_hb = System.nanoTime(): Double
      deleteDir.deletedir(islocal, master, hbase_output)
      HBase_OP.updateHbase_java_flat(newedges, nodes_num_bitsize, symbol_num_bitsize, htable_name,
        hbase_output,
        htable_split_Map, htable_nodes_interval, default_split)
      val t1_hb = System.nanoTime(): Double
      println("update Hbase take time:         \t" + ((t1_hb - t0_hb) / 1000000000.0).formatted("%.3f") + " sec")
      t1 = System.nanoTime(): Double
      println("*step: step " + step + " take time: \t " + ((t1 - t0) / 1000000000.0).formatted("%.3f") + " sec")
      println
      continue = newnum != 0
      val scan = new Scanner(System.in)
      scan.next()

    }
    println("final edges count:             \t" + oldedges.map(s => (s._2._1._1.length)).sum().toLong)
    sc.stop();
  }

}


