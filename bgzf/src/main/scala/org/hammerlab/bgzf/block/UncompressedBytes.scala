package org.hammerlab.bgzf.block

import java.io.Closeable

import hammerlab.iterator._
import org.hammerlab.bgzf.Pos
import org.hammerlab.channel.{ ByteChannel, SeekableByteChannel }

/**
 * [[Iterator]] of bgzf-decompressed bytes from a [[Stream]] of [[Block]]s.
 * @tparam BlockStream underlying [[Block]]-[[Stream]] type (basically: seekable or not?).
 */
trait UncompressedBytesI[BlockStream <: StreamI]
  extends SimpleIterator[Byte]
    with Closeable {
  def blockStream: BlockStream
  val uncompressedBytes = blockStream.level
  def curBlock: Option[Block] = uncompressedBytes.cur
  def curPos: Option[Pos] = curBlock.map(_.pos)

  private var _stopAt: Option[Pos] = None
  def stopAt(pos: Pos): Unit = {
    _stopAt = Some(pos)
  }

  def reset(): Unit = {
    _stopAt = None
  }

  // TODO: allow smart skipping with seek, block by block

  override protected def _advance: Option[Byte] =
    if (
      _stopAt
        .exists(
          stopAt ⇒
            curPos
              .exists(
                stopAt <= _
              )
        )
    )
      None
    else
      uncompressedBytes.nextOption

  override def close(): Unit = {
    blockStream.close()
  }
}

/**
 * Non-seekable [[UncompressedBytesI]]
 */
case class UncompressedBytes(blockStream: Stream)
  extends UncompressedBytesI[Stream]

object UncompressedBytes {
  def apply(compressedBytes: ByteChannel): UncompressedBytes = UncompressedBytes(Stream(compressedBytes))
}

/**
 * Seekable [[UncompressedBytesI]]
 */
case class SeekableUncompressedBytes(blockStream: SeekableStream)
  extends UncompressedBytesI[SeekableStream] {
  def seek(pos: Pos): Unit = {
    blockStream.seek(pos.blockPos)
    uncompressedBytes.reset()
    clear()
    curBlock
      .foreach {
        block ⇒
          block.idx = pos.offset
      }

  }
}

object SeekableUncompressedBytes {
  def apply(ch: SeekableByteChannel): SeekableUncompressedBytes =
    SeekableUncompressedBytes(
      SeekableStream(
        ch
      )
    )
}
