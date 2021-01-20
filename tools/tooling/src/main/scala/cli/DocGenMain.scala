/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

// Adapted from:
// https://github.com/scopt/scopt/blob/v4.0.0-RC2/shared/src/main/scala/scopt/ORunner.scala
//
// MIT License:
// https://github.com/scopt/scopt/blob/v4.0.0-RC2/LICENSE.md
//
// Pretending to be part of scopt to access private members
package scopt

import collection.mutable.ListBuffer
import scala.collection.immutable.{ Seq => ISeq }
import scopt.OptionDef._
import scopt.OptionDefKind._
import akka.cli.cloudflow.{ Options, OptionsParser }

object DocGenMain extends App {

  val options =
    (new OptionDef[Unit, Options](OptionDefKind.ProgramName, "kubectl-cloudflow")
      .text("kubectl-cloudflow")) :: OptionsParser.optionParser.toList

  def heads: ISeq[OptionDef[_, Options]] = options.filter { _.kind == Head }
  def arguments: ISeq[OptionDef[_, Options]] = options.filter { _.kind == Arg }
  def commands: ISeq[OptionDef[_, Options]] = options.filter { _.kind == Cmd }
  def programNames: ISeq[OptionDef[_, Options]] = options.filter { _.kind == ProgramName }
  def programName: String = programNames.headOption match {
    case Some(option: OptionDef[_, Options]) => option.desc
    case _                                   => ""
  }

  def optionsForRender: List[OptionDef[_, Options]] = {
    val unsorted = options.filter { o =>
      o.kind != Head &&
      o.kind != Check &&
      o.kind != ProgramName &&
      !o.isHidden
    }
    val (remaining, sorted) = unsorted.partition { _.hasParent } match {
      case (p, np) => (ListBuffer() ++ p, ListBuffer() ++ np)
    }
    var continue = true
    while (!remaining.isEmpty && continue) {
      continue = false
      for {
        parent <- sorted
      } {
        val childrenOfThisParent = remaining.filter { _.getParentId == Some(parent.id) }
        if (childrenOfThisParent.nonEmpty) {
          remaining --= childrenOfThisParent
          sorted.insertAll((sorted.indexOf(parent)) + 1, childrenOfThisParent)
          continue = true
        }
      }
    }
    sorted.toList
  }

  def itemUsage(value: OptionDef[_, Options]): String =
    value.kind match {
      case ProgramName         => value.desc
      case Head | Note | Check => "= kubectl-cloudflow" // It contains the version as well
      case Cmd =>
        NL + s"== Command: $value" + NL + commandExample(Some(value)) + NL + value.desc
      case Arg => WW + value.name + NLTB + value.desc
      case Opt if value.read.arity == 2 =>
        WW + value.shortOpt
          .map { o =>
            "-" + o + ":" + value.keyValueString + " | "
          }
          .getOrElse { "" } +
        value.fullName + ":" + value.keyValueString + NLTB + value.desc
      case Opt if value.read.arity == 1 =>
        WW + value.shortOpt
          .map { o =>
            "-" + o + " " + value.valueString + " | "
          }
          .getOrElse { "" } +
        value.fullName + " " + value.valueString + NLTB + value.desc
      case Opt | OptHelp | OptVersion =>
        WW + value.shortOpt
          .map { o =>
            "-" + o + " | "
          }
          .getOrElse { "" } +
        value.fullName + NLTB + value.desc
    }

  lazy val header = heads.map { itemUsage }.mkString(NL)

  def usageColumn1(value: OptionDef[_, Options]): String =
    value.kind match {
      case ProgramName         => value.desc
      case Head | Note | Check => ""
      case Cmd =>
        NL + s"== Command: $value" + NL + commandExample(Some(value)) + NL
      case Arg => WW + value.name
      case Opt if value.read.arity == 2 =>
        WW + value.shortOpt
          .map { o =>
            "-" + o + ", "
          }
          .getOrElse { "" } +
        value.fullName + ":" + value.keyValueString
      case Opt if value.read.arity == 1 =>
        WW + value.shortOpt
          .map { o =>
            "-" + o + ", "
          }
          .getOrElse { "" } +
        value.fullName + " " + value.valueString
      case Opt | OptHelp | OptVersion =>
        WW + value.shortOpt
          .map { o =>
            "-" + o + ", "
          }
          .getOrElse { "" } +
        value.fullName
    }

  def usageTwoColumn(value: OptionDef[_, Options], col1Length: Int): String = {
    def spaceToDesc(str: String, description: String) = {
      if (description.isEmpty) str
      else {
        if ((str.length + WW.length) <= col1Length)
          str + (" " * (col1Length - str.length)) + "``" + description
        else str + NL + (" " * col1Length) + "``" + description
      }
    }
    value.kind match {
      case ProgramName                  => value.desc
      case Head | Note | Check          => NL // Skip "Available Commands:" printed in the command line
      case Cmd                          => usageColumn1(value) + NL + "=== Synopsis" + NL + value.desc + NL
      case Arg                          => " * ``" + (spaceToDesc(usageColumn1(value), value.desc))
      case Opt if value.read.arity == 2 => " * ``" + (spaceToDesc(usageColumn1(value), value.desc))
      case Opt if value.read.arity == 1 => " * ``" + (spaceToDesc(usageColumn1(value), value.desc))
      case Opt | OptHelp | OptVersion   => " * ``" + (spaceToDesc(usageColumn1(value), value.desc))
    }
  }

  def renderTwoColumnsUsage: String = {
    val xs = optionsForRender
    val descriptions = {
      val col1Len = math.min(column1MaxLength, xs.map { x =>
        usageColumn1(x).length + WW.length
      } match {
        case Nil  => 0
        case list => list.max
      })
      xs.map { x =>
        usageTwoColumn(x, col1Len)
      }
    }
    (if (header == "") "" else header + NL) +
    (usageExample match {
      case "" => ""
      case x  => "== Usage: " + NL + x + NLNL
    }) +
    descriptions.mkString(NL)
  }

  def commandExample(cmd: Option[OptionDef[_, Options]]): String = {
    def commandName(cmd: OptionDef[_, Options]): String =
      cmd.getParentId
        .map { x =>
          commands.find { _.id == x }.map { commandName }.getOrElse { "" } + " "
        }
        .getOrElse { "" } + cmd.name

    val text = new ListBuffer[String]()
    text += cmd.map { commandName }.getOrElse(programName)
    val parentId = cmd.map { _.id }
    val cs = commands.filter { c =>
      c.getParentId == parentId && !c.isHidden
    }
    if (cs.nonEmpty) text += cs.map { _.name }.mkString("[ ", " | ", " ]")
    val os = options.toSeq.filter {
      case x =>
        (x.kind == Opt || x.kind == OptVersion || x.kind == OptHelp) &&
        x.getParentId == parentId
    }
    val as = arguments.filter { _.getParentId == parentId }
    if (os.nonEmpty) text += "[options]"
    if (cs.exists { case x => arguments.exists { _.getParentId == Some(x.id) } })
      text += "<args>..."
    else if (as.nonEmpty) text ++= as.map { _.argName }
    text.mkString("[source,bash]\n----\n", " ", "\n----")
  }

  def usageExample: String = commandExample(None)

  println(renderTwoColumnsUsage)
  import sys.process._
  (s"echo '${renderTwoColumnsUsage}'".#>(new java.io.File("cloudflow.adoc"))).!
}
