package commands

import scala.io.Codec
import java.nio.charset.CodingErrorAction
import scala.io.Source
import utils.AnsiEscapeCodes

abstract class Placeholder(val baseValue: String, val fixedWidth: Option[Int] = None) {
  private val prefix = fixedWidth.map(width => s"%<($width,trunc)").getOrElse("")
  val value = prefix + baseValue
}

object Placeholders {
  object Separator extends Placeholder("-")
  object Hash extends Placeholder("%h")

  abstract class Subject(width: Option[Int]) extends Placeholder("%s", width)
  object UnlimitedSubject extends Subject(None)
  case class FixedWidthSubject(width: Int) extends Subject(Some(width))

  abstract class AuthorName(width: Option[Int]) extends Placeholder("<%an>", width)
  object UnlimitedAuthorName extends AuthorName(None)
  case class FixedWidthAuthorName(width: Int) extends AuthorName(Some(width))

  abstract class CommitDate(width: Option[Int]) extends Placeholder("(%cs)", width)
  object UnlimitedCommitDate extends CommitDate(None)
  case class FixedWidthCommitDate(width: Int) extends CommitDate(Some(width))

  object RefNames extends Placeholder("%D")
}

trait CommitInfo {
  def color: Int
  def backgroundColor: Option[Int] = None
  def inverted: Boolean = false
  def underlined: Boolean = false
  def bold: Boolean = false
  def placeholder: Placeholder
  def value: String = ""
  def withText(text: String): CommitInfo

  private val ansi = AnsiEscapeCodes

  private def invertEscape = if (inverted) ansi.invert else ""
  private def underlineEscape = if (underlined) ansi.underline else ""
  private def boldEscape = if (bold) ansi.bold else ""

  private def colorEscape = ansi.color(color)

  private def backgroundEscape = backgroundColor.map(ansi.background).getOrElse("")

  override def toString(): String = {
    backgroundEscape + boldEscape + underlineEscape + invertEscape + colorEscape + value + ansi.reset
  }
}


case class Separator(color: Int = 15, placeholder: Placeholder = Placeholders.Separator, override val value: String = "") extends CommitInfo {
  def withText(text: String): Separator = copy(value=text)
}
case class Hash(color: Int = 1, placeholder: Placeholder = Placeholders.Hash, override val value: String = "") extends CommitInfo {
  def withText(text: String): Hash = copy(value=text)
}
case class Subject(color: Int = 15, placeholder: Placeholder = Placeholders.UnlimitedSubject, override val value: String = "") extends CommitInfo {
  def withText(text: String): Subject = copy(value=text)
}
case class AuthorName(color: Int = 66, placeholder: Placeholder = Placeholders.UnlimitedAuthorName, override val value: String = "") extends CommitInfo {
  def withText(text: String): AuthorName = copy(value=text)
}
case class CommitDate(color: Int = 237, placeholder: Placeholder = Placeholders.UnlimitedCommitDate, override val value: String = "") extends CommitInfo {
  def withText(text: String): CommitDate = copy(value=text)
}
case class RefNames(color: Int = 3, placeholder: Placeholder = Placeholders.RefNames, override val value: String = "", override val inverted: Boolean = true) extends CommitInfo {
  def withText(text: String): RefNames = copy(value=text)

  def getBranches = {
    val branches = value.split(',').map { b =>
      var tempB = (b.trim, Vector.empty[String])
      if (tempB._1.contains("HEAD -> ")) tempB = (tempB._1.replace("HEAD -> ", "").trim, tempB._2 :+ "{HEAD}")

      if (tempB._1.contains("origin/")) tempB = (tempB._1.replace("origin/", "").trim, tempB._2 :+ "{origin}")
      else if (tempB._1.contains("tag:")) tempB = (tempB._1.replace("tag:", "").trim, tempB._2 :+ "{tag}")
      else tempB = (tempB._1, tempB._2 :+ "{local}")

      tempB
    }.groupBy(_._1).mapValues(_.flatMap(_._2))

    branches.map { case (bName, bNotes)  =>
      val (prefix, name, sufix) =
        if (bNotes.contains("{HEAD}")) {
          ("{HEAD} ", bName, bNotes.filterNot( _ == "{HEAD}"))
        } else {
          ("", bName, bNotes)
        }
      withText(" " + prefix + bName + " " + sufix.mkString("") + " ")
    }
  }
}

object GitLogGraph {
  private val p = Placeholders

  def simpleFormat(subjectColor: Int, authorNameColor: Int, commitDateColor: Int): Vector[CommitInfo] = Vector(
    Hash(),
    Separator(),
    Subject(color = subjectColor),
    CommitDate(color = commitDateColor),
    AuthorName(color = authorNameColor),
    RefNames()
  )

  def fixedWidthFormat(subjectColor: Int,
                       authorNameColor: Int,
                       commitDateColor: Int)(subjectWidth: Int,
                                             authorNameWidth: Int,
                                             commitDateWidth: Int): Vector[CommitInfo] = Vector(
    Hash(),
    Separator(),
    Subject(placeholder = Placeholders.FixedWidthSubject(subjectWidth), color = subjectColor),
    CommitDate(placeholder = Placeholders.FixedWidthCommitDate(commitDateWidth), color = commitDateColor),
    AuthorName(placeholder = Placeholders.FixedWidthAuthorName(authorNameWidth), color = authorNameColor),
    RefNames()
  )
}

case class GitLogGraph(private val format: Vector[CommitInfo], args: Seq[String]) {
  val gitCommand = Seq(
    "git",
    "log",
    "--graph",
    //"--pretty=\"format:\\%Cred%h%Creset -%C(yellow)%d%Creset %s %Cgreen(%cr) %C(bold blue)<%an>%Creset\"",
    //"--pretty=format:%Cred%h%Creset - %s %Cgreen(%cr) %C(bold blue)<%an>%Creset \u001b[7m%C(yellow)%D%Creset",
    //"--pretty=format:\u001b[4m%Cred%h%Creset - %s \u001b[38;5;66m[%an]%Creset \u001b[38;5;237m(%cr)%Creset \u001b[4m\u001b[7m%C(yellow)% D%Creset",
    //  "--pretty=format:\u001b[4m%Cred%h%Creset - %s \u001b[38;5;66m[%an]%Creset \u001b[38;5;237m(%cr)%Creset \u001b[4m\u001b[7m%C(yellow)% D%Creset",
    "--pretty=format:" + format.map(_.placeholder.value).mkString("\u0008"),
    //"--pretty=format:%h -%d %s (%cr) <%an>",
    "--abbrev-commit",
    "--color"
  ) ++ args

  private val proc = new ProcessBuilder(gitCommand: _*).start()
  private implicit val codec = Codec("UTF-8")
  codec.onMalformedInput(CodingErrorAction.REPLACE)
  codec.onUnmappableCharacter(CodingErrorAction.REPLACE)
  val out = Source.fromInputStream(proc.getInputStream).getLines.toArray
  val errorOut = Source.fromInputStream(proc.getErrorStream()).getLines.toArray
  proc.waitFor
  val failed = proc.exitValue() != 0

  val msgFormat = format

  def parseMessage(msg: String) = {
    val parts = msg.split('\u0008')
    //require(parts.size == msgFormat.size, "Text and format size doesn't match")
    parts.view.zip(msgFormat).map { case (part, formatItem) => formatItem.withText(part) }.toVector
  }

}
