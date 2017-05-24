package org.hammerlab.hadoop_bam.bgzf.block

import java.io.{ IOException, InputStream }
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

case class Header(size: Int, compressedSize: Int)

object Header {

  val EXPECTED_HEADER_SIZE = 18

  def apply(ch: SeekableByteChannel)(implicit buf: ByteBuffer): Header = {
    buf.limit(EXPECTED_HEADER_SIZE)
    val headerBytesRead = ch.read(buf)
    if (headerBytesRead != EXPECTED_HEADER_SIZE) {
      throw new IOException(s"Expected $EXPECTED_HEADER_SIZE header bytes, got $headerBytesRead")
    }

    implicit val arr = buf.array
    val header = apply()
    buf.clear()
    ch.position(ch.position() + header.size - EXPECTED_HEADER_SIZE)

    header
  }

  def apply(is: InputStream)(implicit buffer: Array[Byte]): Header = {

    val headerBytesRead = is.read(buffer, 0, EXPECTED_HEADER_SIZE)
    if (headerBytesRead != EXPECTED_HEADER_SIZE) {
      throw new IOException(s"Expected $EXPECTED_HEADER_SIZE header bytes, got $headerBytesRead")
    }

    val header = apply()
    is.skip(header.size - EXPECTED_HEADER_SIZE)

    header
  }

  def apply(offset: Int = 0)(implicit bytes: Array[Byte]): Header = make(offset, bytes.length)
  def make(offset: Int, length: Int)(implicit bytes: Array[Byte]): Header = {

    def check(idx: Int, expected: Byte): Unit = {
      val actual = bytes(idx)
      if (actual != expected)
        throw HeaderParseException(
          idx,
          actual,
          expected
        )
    }

    check(0,  31)
    check(1, 139.toByte)
    check(2,   8)
    check(3,   4)

    val xlen = getShort(10)

    val extraHeaderBytes = xlen - 6
    val actualHeaderSize = EXPECTED_HEADER_SIZE + extraHeaderBytes

    check(12, 66)
    check(13, 67)
    check(14,  2)

    val compressedSize = getShort(16) + 1

    Header(
      actualHeaderSize,
      compressedSize
    )
  }

  def getShort(idx: Int)(implicit buffer: Array[Byte]): Int =
    (buffer(idx) & 0xff) |
      ((buffer(idx + 1) & 0xff) << 8)
}
