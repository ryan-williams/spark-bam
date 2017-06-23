package org.hammerlab.io

sealed abstract class Size(scale: Long) {
  def bytes: Long = value * scale
  def value: Int

  override def toString: String =
    s"$value${getClass.getSimpleName}"
}

case class  B(value: Int) extends Size(1L << 0)
case class KB(value: Int) extends Size(1L << 10)
case class MB(value: Int) extends Size(1L << 20)
case class GB(value: Int) extends Size(1L << 30)
case class TB(value: Int) extends Size(1L << 40)
case class PB(value: Int) extends Size(1L << 50)
case class EB(value: Int) extends Size(1L << 60)

object Size {
  val re = """^(\d+)([KMGTPE]?)B?$""".r
  def apply(bytesStr: String): Size = {
    re.findFirstMatchIn(bytesStr.toUpperCase) match {
      case Some(m) ⇒
        val num = m.group(1).toInt
        Option(m.group(2)) match {
          case Some("K") ⇒ KB(num)
          case Some("M") ⇒ MB(num)
          case Some("G") ⇒ GB(num)
          case Some("T") ⇒
            if (num < (8 << 20))
              TB(num)
            else
              throw SizeOverflowException(bytesStr)
          case Some("P") ⇒
            if (num < (8 << 10))
              PB(num)
            else
              throw SizeOverflowException(bytesStr)
          case Some("E") ⇒
            if (num < 8)
              EB(num)
            else
              throw SizeOverflowException(bytesStr)
          case Some("") | None ⇒ B(num)
        }
      case None ⇒
        throw BadSizeString(bytesStr)
    }
  }

  implicit def unwrapSize(size: Size): Long = size.bytes
}

case class BadSizeString(str: String)
  extends IllegalArgumentException(str)

case class SizeOverflowException(str: String)
  extends IllegalArgumentException(str)
