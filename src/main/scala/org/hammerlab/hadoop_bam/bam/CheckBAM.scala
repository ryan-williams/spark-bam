package org.hammerlab.hadoop_bam.bam

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

import caseapp._
import grizzled.slf4j.Logging
import org.apache.hadoop.fs.{ Path ⇒ HPath }
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.{ SparkConf, SparkContext }
import org.hammerlab.genomics.reference.NumLoci
import org.hammerlab.hadoop_bam.Monoid.{ mzero ⇒ zero }
import org.hammerlab.hadoop_bam.MonoidSyntax._
import org.hammerlab.hadoop_bam.bam.Error.{ ErrorCount, ErrorFlags, numNonZeroFields, toCounts }
import org.hammerlab.hadoop_bam.bgzf.Pos
import org.hammerlab.hadoop_bam.bgzf.block
import org.hammerlab.hadoop_bam.bgzf.hadoop.BytesInputFormat.RANGES_KEY
import org.hammerlab.hadoop_bam.bgzf.hadoop.{ BytesInputFormat, CompressedBlock }
import org.hammerlab.iterator.{ HeadOptionIterator, SimpleBufferedIterator }
import org.hammerlab.magic.rdd.keyed.FilterKeysRDD._
import org.hammerlab.magic.rdd.keyed.ReduceByKeyRDD._
import org.hammerlab.magic.rdd.sliding.SlidingRDD._
import org.hammerlab.magic.rdd.sort.SortRDD._
import org.seqdoop.hadoop_bam.util.SAMHeaderReader.readSAMHeaderFrom
import org.seqdoop.hadoop_bam.util.WrapSeekable

import scala.collection.JavaConverters._
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.math.ceil
import scala.reflect.ClassTag

case class CheckBAMArgs(@ExtraName("k") blocksFile: String,
                        @ExtraName("r") recordsFile: String,
                        @ExtraName("b") bamFile: String,
                        @ExtraName("n") numBlocks: Option[Int] = None,
                        @ExtraName("w") blocksWhitelist: Option[String] = None,
                        @ExtraName("p") blocksPerPartition: Int = 20)

sealed trait Call

sealed trait True extends Call
sealed trait False extends Call

case object TruePositive extends True
case object FalsePositive extends False
case class TrueNegative(error: ErrorFlags) extends True
case class FalseNegative(error: ErrorFlags) extends False

case class Result(numPositions: Long,
                  calls: RDD[(Pos, Call)],
                  numFalseCalls: Long,
                  falseCalls: RDD[(Pos, False)],
                  countsByNonZeroFields: Array[(Int, (ErrorCount, ErrorCount))])

object CheckBAM
  extends CaseApp[CheckBAMArgs]
    with Logging {

  implicit def CanBuildArrayFromSeq[T, U: ClassTag] =
    new CanBuildFrom[Seq[T], U, Array[U]] {
      def apply(from: Seq[T]): mutable.Builder[U, Array[U]] = mutable.ArrayBuilder.make[U]()
      def apply(): mutable.Builder[U, Array[U]] = mutable.ArrayBuilder.make[U]()
    }

  def processBlock(block: Long,
                   usize: Int,
                   offsets: Vector[Int],
                   nextPosOpt: Option[Pos],
                   bytes: Array[Byte],
                   contigLengths: Map[Int, NumLoci]): Iterator[(Pos, Call)] = {
    val buf = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)

    val expectedPoss =
      (
        offsets
          .iterator
          .map(offset ⇒ Pos(block, offset)) ++
            nextPosOpt.iterator
      )
      .buffered

    new SimpleBufferedIterator[(Pos, Call)] {
      var up = 0
      override protected def _advance: Option[(Pos, Call)] =
        if (up >= usize)
          None
        else {
          val pos = Pos(block, up)
          buf.position(up)

          while (expectedPoss.hasNext && pos > expectedPoss.head) {
            expectedPoss.next
          }

          val expectPositive = expectedPoss.headOption.contains(pos)

          Some(
            pos → (
              Guesser.guess(buf, contigLengths) match {
                case Some(error) ⇒
                  if (expectPositive) {
                    FalseNegative(error)
                  } else {
                    TrueNegative(error)
                  }
                case None ⇒
                  if (expectPositive)
                    TruePositive
                  else
                    FalsePositive
              }
            )
          )
      }

      override protected def postNext(): Unit = {
        up += 1
      }
    }
  }

  override def run(args: CheckBAMArgs, remainingArgs: RemainingArgs): Unit = {

    val sparkConf = new SparkConf()

    val sc = new SparkContext(sparkConf)
    val path = new HPath(args.bamFile)

    val Result(
      numPositions,
      calls,
      numFalseCalls,
      falseCalls,
      countsByNonZeroFields
    ) =
      run(sc, path, args)

    val falseCallsSample =
      if (numFalseCalls > 1000)
        falseCalls.take(1000)
      else
        falseCalls.collect()

    numFalseCalls match {
      case 0 ⇒
        println(s"$numPositions calls, no errors!")
      case _ ⇒
        println(s"$numPositions calls, $falseCallsSample errors; first 1000:")
        println(
          falseCallsSample
            .mkString(
              "\t",
              "\n\t",
              if (numFalseCalls > 1000)
                "\n…\n"
              else
                "\n"
            )
        )
    }
  }

  def run(sc: SparkContext, path: HPath, args: CheckBAMArgs): Result = {

    val conf = sc.hadoopConfiguration

    val ss = WrapSeekable.openPath(conf, path)

    val contigLengths =
      readSAMHeaderFrom(ss, conf)
        .getSequenceDictionary
        .getSequences
        .asScala
        .map(
          seq ⇒
            seq.getSequenceIndex →
              NumLoci(seq.getSequenceLength)
        )
        .toMap

    val fs = path.getFileSystem(conf)

    val lastBlock = fs.getFileStatus(path).getLen - 28

    val blockSizes: RDD[(Long, Int)] =
      sc
        .textFile(args.blocksFile)
        .map(
          _.split(",") match {
            case Array(pos, usize) ⇒
              pos.toLong → usize.toInt
            case a ⇒
              throw new IllegalArgumentException(s"Bad block line: ${a.mkString(",")}")
          }
        )

    val blocksAndSuccessors =
      blockSizes
        .keys
        .sliding2Opt
        .mapValues(_.getOrElse(lastBlock))

    val blockWhitelistOpt: Option[Broadcast[Set[Long]]] =
      (args.numBlocks, args.blocksWhitelist) match {
        case (Some(_), Some(_)) ⇒
          throw new Exception("Specify num blocks xor blocks whitelist")
        case (Some(numBlocks), _) ⇒
          Some(
            sc.broadcast(
              blockSizes
                .keys
                .take(numBlocks)
                .toSet
            )
          )
        case (_, Some(blocksWhitelist)) ⇒
          Some(
            sc.broadcast(
              blocksWhitelist
                .split(",")
                .map(_.toLong)
                .toSet
            )
          )
        case _ ⇒
          None
      }

    def filterBlocks[T: ClassTag](rdd: RDD[(Long, T)]): RDD[(Long, T)] =
      blockWhitelistOpt match {
        case Some(blockWhitelist) ⇒
          rdd.filterKeys(blockWhitelist)
        case None ⇒
          rdd
      }

    val recordsRDD: RDD[(Long, Int)] =
      sc
        .textFile(args.recordsFile)
        .map(
          _.split(",") match {
            case Array(a, b) ⇒
              (a.toLong, b.toInt)
            case a ⇒
              throw new IllegalArgumentException(s"Bad record-pos line: ${a.mkString(",")}")
          }
        )

    val blockNextFirstOffsets: RDD[(Long, Option[Pos])] =
      recordsRDD
        .minByKey()
        .sortByKey()
        .sliding2Opt
        .map {
          case ((block, _), nextBlockOpt) ⇒
            block →
              nextBlockOpt
                .map {
                  case (nextBlock, nextOffset) ⇒
                    Pos(nextBlock, nextOffset)
                }
        }

    val blockOffsetsRDD: RDD[(Long, Vector[Int])] =
      recordsRDD
        .groupByKey()
        .mapValues(
          _
            .toVector
            .sorted
        )

    val blockDataRDD: RDD[(Long, (Int, Vector[Int], Option[Pos]))] =
      filterBlocks(blockSizes)
        .cogroup(
          filterBlocks(blockOffsetsRDD),
          filterBlocks(blockNextFirstOffsets)
        )
        .flatMap {
          case (block, (usizes, offsetss, nextPosOpts)) ⇒
            (usizes.size, offsetss.size, nextPosOpts.size) match {
              case (1, 1, 1) ⇒
                Some(
                  block →
                    (
                      usizes.head,
                      offsetss.head,
                      nextPosOpts.head
                    )
                )
              case _ ⇒
                logger.warn(
                  s"Suspicious join sizes at $block: ${usizes.size} ${offsetss.size} ${nextPosOpts.size}"
                )
                None
            }
        }

    val blocksRefrenced =
      blockDataRDD
        .flatMap {
          case (block, (_, _, nextPosOpt)) ⇒
            Iterator(block) ++ nextPosOpt.map(_.blockPos)
        }
        .distinct
        .sort()

    val referenceBlocksWithEnds =
      blocksRefrenced
        .keyBy(x ⇒ x)
        .join(blocksAndSuccessors)
        .values
        .flatMap(x ⇒ Iterator(x._1, x._2))
        .distinct
        .sort()
        .collect

    val numPartitions =
      ceil(
        referenceBlocksWithEnds.length * 1.0 / args.blocksPerPartition
      )
      .toInt

    val blocksStr = referenceBlocksWithEnds.mkString(",")

    conf.set(RANGES_KEY, blocksStr)

    val blockBytesRDD: RDD[(Long, Array[Byte])] =
      sc
        .newAPIHadoopFile(
          args.bamFile,
          classOf[BytesInputFormat],
          classOf[Long],
          classOf[CompressedBlock]
        )
        .sliding(4, includePartial = true)  // Get each BGZF block and 3 that follow it
        .map {
          bytess ⇒
            bytess
            .head
            ._1 →
              block.Stream(
                new ByteArrayInputStream(
                  bytess
                    .flatMap(_._2.bytes)
                )
              )
              .flatMap(_.bytes)
              .toArray
        }

    val calls =
      blockDataRDD
        .leftOuterJoin(blockBytesRDD, numPartitions)
        .flatMap {
          case (
            block,
            (
              (
                usize,
                offsets,
                nextPosOpt
              ),
              Some(bytes)
            )
          ) ⇒
            processBlock(
              block,
              usize,
              offsets,
              nextPosOpt,
              bytes,
              contigLengths
            )
          case (block, (dataOpt, bytesOpt)) ⇒
            throw new Exception(
              s"Missing data or bytes for block $block: $dataOpt $bytesOpt"
            )
        }
        .sortByKey()

    val numCalls = calls.count

    val falseCalls =
      calls.flatMap {
        case (pos, f: False) ⇒ Some(pos → f)
        case _ ⇒ None
      }

    val numFalseCalls = falseCalls.count

    val trueNegativesByNumFields: Array[(Int, ErrorCount)] =
      calls
        .flatMap {
          _._2 match {
            case TrueNegative(error) ⇒ Some(toCounts(error))
            case _ ⇒ None
          }
        }
        .map(
          counts ⇒
            numNonZeroFields(counts) →
              counts
        )
        .reduceByKey(_ |+| _, 20)
        .collect()
        .sortBy(_._1)

    val trueNegativesByNumFieldsCumulative =
      trueNegativesByNumFields
        .scanLeft(
          0 → zero[ErrorCount]
        ) {
          (soFar, next) ⇒
            val (_, countSoFar) = soFar
            val (numNonZeroFields, count) = next
            numNonZeroFields → (countSoFar |+| count)
        }
        .drop(1)

    val countsByNonZeroFields =
      for {
        ((numNonZeroFields, counts), (_, cumulativeCounts)) ←
          trueNegativesByNumFields
            .zip(
              trueNegativesByNumFieldsCumulative
            )
      } yield
        numNonZeroFields → (counts, cumulativeCounts)

    Result(
      numCalls,
      calls,
      numFalseCalls,
      falseCalls,
      countsByNonZeroFields
    )
  }
}
