// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.

package ducttape.syntax

import java.io.File
import scala.util.parsing.input.Position
import ducttape.syntax.AbstractSyntaxTree.ASTType
import scala.collection.LinearSeq

/**
 * Each element of ref has (file, line, col, untilLine)
 */
class FileFormatException(val msg: String, val refs: Seq[(File, Int, Int, Int)]) extends Exception(FileFormatException.formatMessage(msg, refs)) {

  def this(msg: String, file: File, line: Int, col: Int) = this(msg, List( (file, line, col, line) ))

  def this(msg: String, file: File, pos: Position) = this(msg, List( (file, pos.line, pos.column, pos.line) ))

  // require iterable instead of Seq to get around erasure
  def this(msg: String, refs: Iterable[(File, Position)]) = this(msg, (for( (f,p) <- refs) yield (f, p.line, p.column, p.line)).toList )

    // require list instead of Seq to get around erasure
  def this(msg: String, refs: LinearSeq[(File, Position, Int)]) = this(msg, for( (f,p,until) <- refs) yield (f, p.line, p.column, until) )

  // require LinearSeq instead of Seq to get around erasure
  // Note: For ASTTypes, we use endPos to capture the last line that a long block might cover (e.g. TaskHeaders)
  def this(msg: String, refs: List[ASTType]) = this(msg, for(t <- refs) yield (t.declaringFile, t.pos.line, t.pos.column, t.endPos.line))

  def this(msg: String, ref: ASTType) = this(msg, List(ref))
}

object FileFormatException {

  def formatMessage(msg: String, refs: Seq[(File, Int, Int, Int)]) : String = {

    val s = new StringBuilder()

    s.append(msg)

    refs.foreach({ ref =>
      s.append('\n')
      s.append(" at file ")
      s.append(ref._1)
      s.append(" from line ")
      s.append(ref._2)
      s.append(" (column ")
      s.append(ref._3)
      s.append(") to line ")
      s.append(ref._4)
    })

    return s.toString()
  }

}
