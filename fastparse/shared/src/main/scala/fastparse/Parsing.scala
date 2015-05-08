package fastparse

import scala.annotation.{switch, tailrec}
import scala.collection.{BitSet, mutable}
import acyclic.file
import Utils._
/**
 * Result of a parse, whether successful or failed
 */
sealed trait Result[+T]

object Result{
  case class Frame(index: Int, parser: Parser[_])
  trait Success[T] extends Result[T]{
    /**
     * The result of this parse
     */
    def value: T
    /**
     * The index where the parse completed; may be less than
     * the length of input
     */
    def index: Int
    /**
     * Whether or not this parse encountered a Cut
     */
    def cut: Boolean
  }
  object Success{
    case class Mutable[T](var value: T, var index: Int, var cut: Boolean = false) extends Success[T]{
      override def toString = s"Success($value, $index)"
    }
    def unapply[T](x: Result[T]) = x match{
      case s: Success[T] => Some((s.value, s.index))
      case _ => None
    }
  }

  trait Failure extends Result[Nothing]{
    /**
     * The input string for the failed parse. Useful so the [[Failure]]
     * object can pretty-print snippet
     */
    def input: String

    /**
     * The entire stack trace where the parse failed, containing every
     * parser in the stack and the index where the parser was used, excluding
     * the final parser and index where the parse failed
     */
    def fullStack: List[Frame]
    /**
     * A slimmed down version of [[fullStack]], this only includes named
     * [[Parser.Rule]] objects as well as the final Parser (whether named or not)
     * and index where the parse failed for easier reading.
     */
    def stack: List[Frame]

    /**
     * The index in the parse where this parse failed
     */
    def index: Int

    /**
     * The deepest parser in the parse which failed
     */
    def parser: Parser[_]

    /**
     * A one-line snippet that tells you what the state of the
     * parser was when it failed
     */
    def trace: String
    /**
     * A longer version of [[trace]], which shows more context
     * for every stack frame
     */
    def verboseTrace: String

    /**
     * Whether or not this parse encountered a Cut
     */
    def cut: Boolean
  }
  object Failure {
    def unapply[T](x: Result[T]) = x match{
      case s: Failure => Some((s.parser, s.index))
      case _ => None
    }
    case class Mutable(var input: String,
                       var fullStack: List[Frame],
                       var index: Int,
                       var parser: Parser[_],
                       var cut: Boolean) extends Failure {

      def stack = fullStack.collect {
        case f@Frame(i, p: Parser.Rule[_]) => f
        case f@Frame(i, p: Parser.Sequence[_, _, _]) if p.cut => f
      } :+ Frame(index, parser)

      def verboseTrace = {
        val body =
          for (Frame(index, p) <- stack)
            yield s"$index\t...${literalize(input.slice(index, index + 5))}\t$p"
        body.mkString("\n")
      }

      def trace = {
        val body =
          for (Frame(index, p) <- stack)
            yield s"$p:$index"

        body.mkString(" / ") + " ..." + literalize(input.slice(index, index + 10))
      }

      override def toString = s"Failure($trace, $cut)"
    }

  }
}
import Result._

/**
 * Things which get passed through the entire parse run, but almost never
 * get changed in the process.
 *
 * @param input The string that is currently being parsed
 * @param logDepth
 * @param trace
 */
case class ParseCtx(input: String, logDepth: Int, trace: Boolean){
  val failure = Failure.Mutable(input, Nil, 0, null, false)
  val success = Success.Mutable(null, 0, false)
}


/**
 * A single, self-contained, immutable parser. The primary method is
 * `parse`, which returns a [[T]] on success and a stack trace on failure.
 *
 * Some small optimizations are performed in-line: collapsing [[Parser.Either]]
 * cells into large ones and collapsing [[Parser.Sequence]] cells into
 * [[Parser.Sequence.Flat]]s. These optimizations together appear to make
 * things faster but any 10%, whether or not you activate tracing
 *
 * Collapsed, Trace   Timings
 * all        true    87 /97 /94
 * all        false   112/111/104
 * either     true    89 /84 /81
 * either     false   97 /102/97
 * none       true    84 /79 /80
 * none       false   96 /99 /97
 */
trait Parser[+T] extends ParserApi[T]{
  /**
   * Parses the given `input` starting from the given `index`
   *
   * @param input The string we want to parse
   *
   * @param index The index in the string to start from. By default parsing
   *              starts from the beginning of a string, but you can start
   *              from halfway through the string if you want.
   *
   * @param trace Whether or not you want a full trace of any error messages
   *              that appear. Without it, you only get the single deepest
   *              parser in the call-stack when it failed, and its index. With
   *              `trace`, you get every parser all the way to the top, though
   *              this comes with a ~20-40% slowdown.
   */
  def parse(input: String, index: Int = 0, trace: Boolean = true): Result[T] = {
    parseRec(ParseCtx(input, 0, trace), index)
  }
}

trait ParserApi[+T]{ this: Parser[T] =>
  /**
   * Parses the given `input` starting from the given `index` and `logDepth`
   */
  def parseRec(cfg: ParseCtx, index: Int): Result[T]

  /**
   * Wraps this in a [[Parser.Logged]]. This prints out information where a parser
   * was tried and its result, which is useful for debugging
   */
  def log(msg: String)(implicit output: Logger) = Parser.Logged(this, msg, output.f)
  /**
   * Repeats this parser 0 or more times
   */
  def rep[R](implicit ev: Implicits.Repeater[T, R]): Parser[R] = Parser.Repeat(this, 0, Parser.Pass)
  /**
   * Repeats this parser 1 or more times
   */
  def rep1[R](implicit ev: Implicits.Repeater[T, R]): Parser[R] = Parser.Repeat(this, 1, Parser.Pass)
  /**
   * Repeats this parser 0 or more times, with a delimiter
   */
  def rep[R](delimiter: Parser[_])(implicit ev: Implicits.Repeater[T, R]): Parser[R] = Parser.Repeat(this, 0, delimiter)
  /**
   * Repeats this parser 1 or more times, with a delimiter
   */
  def rep1[R](delimiter: Parser[_])(implicit ev: Implicits.Repeater[T, R]): Parser[R] = Parser.Repeat(this, 1, delimiter)

  /**
   * Parses using this or the parser `p`
   */
  def |[V >: T](p: Parser[V]): Parser[V] = Parser.Either[V](Parser.Either.flatten(Vector(this, p)):_*)

  /**
   * Parses using this followed by the parser `p`
   */
  def ~[V, R](p: Parser[V])(implicit ev: Implicits.Sequencer[T, V, R]): Parser[R] =
    Parser.Sequence.flatten(Parser.Sequence(this, p, cut=false).asInstanceOf[Parser.Sequence[R, R, R]])
  /**
   * Parses using this followed by the parser `p`, performing a Cut if
   * this parses successfully. That means that if `p` fails to parse, the
   * parse will fail immediately and not backtrack past this success.
   *
   * This lets you greatly narrow the error position by avoiding unwanted
   * backtracking.
   */
  def ~![V, R](p: Parser[V])(implicit ev: Implicits.Sequencer[T, V, R]): Parser[R] =
    Parser.Sequence.flatten(Parser.Sequence(this, p, cut=true).asInstanceOf[Parser.Sequence[R, R, R]])

  /**
   * Parses this, optionally
   */
  def ?[R](implicit ev: Implicits.Optioner[T, R]) = Parser.Optional(this)

  /**
   * Wraps this in a [[Parser.Not]] for negative lookaheal
   */
  def unary_! = Parser.Not(this)

  /**
   * Used to capture the text parsed by this as a `String`
   */
  def ! = Parser.Capturing(this)

  /**
   * Transforms the result of this Parser with the given function
   */
  def map[V](f: T => V): Parser[V] = Parser.Mapper(this, f)

  protected def fail(f: Failure.Mutable, index: Int, cut: Boolean = false) = {
    f.index = index
    f.cut = cut
    f.fullStack = Nil
    f.parser = this
    f
  }

  protected def failMore(f: Failure.Mutable, index: Int, trace: Boolean, cut: Boolean = false) = {
    if (trace) f.fullStack = new ::(new Result.Frame(index, this), f.fullStack)
    f.cut = f.cut | cut
    f
  }
  protected def success[T](s: Success.Mutable[_], value: T, index: Int, cut: Boolean) = {
    val s1 = s.asInstanceOf[Success.Mutable[T]]
    s1.value = value
    s1.index = index
    s1.cut = cut
    s1
  }
}
object Parser{

  /**
   * Applies a transformation [[f]] to the result of [[p]]
   */
  case class Mapper[T, V](p: Parser[T], f: T => V) extends Parser[V]{
    def parseRec(cfg: ParseCtx, index: Int) = {
      p.parseRec(cfg, index) match{
        case s: Success.Mutable[T] => success(s, f(s.value), s.index, s.cut)
        case f: Failure.Mutable => failMore(f, index, cfg.trace)
      }
    }
    override def toString = p.toString
  }
  /**
   * A parser that always succeeds, consuming no input
   */
  case object Pass extends Parser[Unit]{
    def parseRec(cfg: ParseCtx, index: Int) = success(cfg.success, (), index, false)
  }

  /**
   * A parser that always fails immediately
   */
  case object Fail extends Parser[Nothing]{
    def parseRec(cfg: ParseCtx, index: Int) = fail(cfg.failure, index)
  }

  /**
   * Captures the string parsed by the given parser [[p]].
   */
  case class Capturing(p: Parser[_]) extends Parser[String]{
    def parseRec(cfg: ParseCtx, index: Int) = {
      p.parseRec(cfg, index) match {
        case s: Success.Mutable[_] => success(cfg.success, cfg.input.substring(index, s.index), s.index, s.cut)
        case f: Failure.Mutable => f
      }
    }
    override def toString = p.toString
  }
  /**
   * Succeeds, consuming a single character
   */
  case object AnyChar extends Parser[Unit]{
    def parseRec(cfg: ParseCtx, index: Int) = {
      val input = cfg.input
      if (index >= input.length) fail(cfg.failure, index)
      else success(cfg.success, input(index), index+1, false)
    }
  }

  /**
   * Succeeds if at the start of the input, consuming no input
   */
  case object Start extends Parser[Unit]{
    def parseRec(cfg: ParseCtx, index: Int) = {
      if (index == 0) success(cfg.success, (), index, false)
      else fail(cfg.failure, index)
    }
  }
  /**
   * Succeeds if at the end of the input, consuming no input
   */
  case object End extends Parser[Unit]{
    def parseRec(cfg: ParseCtx, index: Int) = {
      if (index == cfg.input.length) success(cfg.success, (), index, false)
      else fail(cfg.failure, index)
    }
  }

  /**
   * Workaround https://github.com/scala-js/scala-js/issues/1603
   * by implementing startsWith myself
   */
  def startsWith(src: String, prefix: String, offset: Int) = {
    val max = prefix.length
    @tailrec def rec(i: Int): Boolean = {
      if (i >= prefix.length) true
      else if (i + offset >= src.length) false
      else if (src.charAt(i + offset) != prefix.charAt(i)) false
      else rec(i + 1)
    }
    rec(0)
  }
  /**
   * Parses a literal `String`
   */
  case class Literal(s: String) extends Parser[Unit]{
    def parseRec(cfg: ParseCtx, index: Int) = {

      if (startsWith(cfg.input, s, index)) success(cfg.success, (), index + s.length, false)
      else fail(cfg.failure, index)
    }
    override def toString = literalize(s).toString
  }

  /**
   * Parses a single character
   */
  case class CharLiteral(c: Char) extends Parser[Unit]{
    def parseRec(cfg: ParseCtx, index: Int) = {
      val input = cfg.input
      if (index >= input.length) fail(cfg.failure, index)
      else if (input(index) == c) success(cfg.success, c.toString, index + 1, false)
      else fail(cfg.failure, index)
    }
    override def toString = literalize(c.toString).toString
  }


  /**
   * Wraps a parser and prints out the indices where it starts
   * and ends, together with its result
   */
  case class Logged[+T](p: Parser[T], msg: String, output: String => Unit) extends Parser[T]{
    def parseRec(cfg: ParseCtx, index: Int) = {
      val indent = "  " * cfg.logDepth
      output(indent + "+" + msg + ":" + index)
      val res = p.parseRec(cfg.copy(logDepth = cfg.logDepth+1), index)
      output(indent + "-" + msg + ":" + index + ":" + res)
      res
    }
  }


  /**
   * A top-level, named parser. Lazily evaluates the wrapped parser
   * [[p]] only when `parse` is called to allow for circular
   * dependencies between parsers.
   */
  case class Rule[+T](name: String, p: () => Parser[T]) extends Parser[T]{
    lazy val pCached = p()
    def parseRec(cfg: ParseCtx, index: Int) = {
      pCached.parseRec(cfg, index) match{
        case f: Failure.Mutable => failMore(f, index, cfg.trace)
        case s => s
      }
    }
    override def toString = name
  }

  /**
   * Wraps another parser, succeeding/failing identically
   * but consuming no input
   */
  case class Lookahead(p: Parser[_]) extends Parser[Unit]{
    def parseRec(cfg: ParseCtx, index: Int) = {
      p.parseRec(cfg, index) match{
        case s: Success.Mutable[_] => success(cfg.success, (), index, false)
        case f: Failure.Mutable => failMore(f, index, cfg.trace)
      }
    }
    override def toString = s"&($p)"
  }
  /**
   * Wraps another parser, succeeding it it fails and failing
   * if it succeeds. Neither case consumes any input
   */
  case class Not(p: Parser[_]) extends Parser[Unit]{
    def parseRec(cfg: ParseCtx, index: Int) = {
      val res0 = p.parseRec(cfg, index)
      val res = res0 match{
        case s: Success.Mutable[_] => fail(cfg.failure, s.index)
        case f: Failure.Mutable => success(cfg.success, (), index, false)
      }
      res
    }
    override def toString = s"!($p)"
  }


  /**
   * Wraps a parser and succeeds with `Some` if [[p]] succeeds,
   * and succeeds with `None` if [[p]] fails.
   */
    case class Optional[+T, R](p: Parser[T])
                              (implicit ev: Implicits.Optioner[T, R]) extends Parser[R]{

    def parseRec(cfg: ParseCtx, index: Int) = {
      p.parseRec(cfg, index) match{
        case s: Success.Mutable[_] => success(cfg.success, ev.some(s.value), s.index, s.cut)
        case f: Failure.Mutable if f.cut => failMore(f, index, cfg.trace)
        case _ => success(cfg.success, ev.none, index, false)
      }
    }
    override def toString = s"$p.?"
  }


  /**
   * Contains an optimized version of [[Sequence]] called [[Sequence.Flat]] that
   * combines a tree of [[Sequence]] nodes from the left, into a single
   * tail-recursive function working over a `Vector` of their contents.
   *
   * Intentionally completely type-unsafe internally, using casting all
   * over the place, because it's near impossible to make the variable-length
   * heterogenous-typed list type-safe without going crazy. If constructed by
   * `flatten`-ing out a [[Sequence]], the types are checked when the [[Sequence]]
   * was constructed, so it's still safe.
   */
  object Sequence{

    /**
     * The contents of a [[Sequence]] node, minus the left subtree.
     */
    case class Chain[R](p: Parser[R], cut: Boolean)(val ev: Implicits.Sequencer[R, R, R])
    case class Flat[R](p0: Parser[R],
                       ps: Vector[Chain[R]]) extends Parser[R] {
      def parseRec(cfg: ParseCtx, index: Int): Result[R] = {
        /**
         * Given
         *
         * A ~ B ~ C ~ D
         *
         * Perform the following iterations:
         *
         * rB = evB(pA, pB)
         * rC = evC(rB, pC)
         * rD = evD(rC, pD)
         * return rD
         */
        @tailrec def rec(r1: R, rIndex: Int, rCut: Boolean, vIndex: Int): Result[R] = {
          if (vIndex >= ps.length) success(cfg.success, r1, rIndex, rCut)
          else {
            val c = ps(vIndex)
            c.p.parseRec(cfg, rIndex) match {
              case f: Failure.Mutable => failMore(f, rIndex, cfg.trace, cut = c.cut | f.cut | rCut)
              case res2: Success.Mutable[R] => rec(
                c.ev(r1, res2.value), res2.index, c.cut | res2.cut | rCut,
                vIndex + 1
              )
            }
          }
        }
        p0.parseRec(cfg, index) match{
          case f: Failure.Mutable => failMore(f, index, cfg.trace, cut = f.cut)
          case s: Success.Mutable[R] => rec(s.value, s.index, s.cut, 0)
        }
      }
      override def toString = {

        val rhs = for(c <- ps) yield {
          " ~" + (if (c.cut) "!" else "") + " " + c.p
        }
        s"($p0${rhs.mkString})"
      }
    }

    /**
     * The types here are all lies. It's ok, just trust the
     * code to do the right thing!
     *
     *
     * A ~ B ~ C ~ D
     * ((A ~ B) ~ C) ~ D
     */
    def flatten[R](s: Sequence[R, R, R]): Flat[R] = {
      def rec(s: Sequence[R, R, R]): Flat[R] = {
        val ev2 = s.ev2.asInstanceOf[Implicits.Sequencer[R, R, R]]
        s.p1 match{
          case f: Flat[R] =>
            f.copy(ps = f.ps :+ Chain[R](s.p2, s.cut)(ev2))
          case p: Sequence[R, R, R] =>
            val res = rec(p)
            res.copy(ps = res.ps :+ Chain[R](s.p2, s.cut)(ev2))
          case p => Flat(p, Vector(Chain[R](s.p2, s.cut)(ev2)))
        }
      }
      rec(s)
    }
  }
  
  /**
   * Parsers two things in a row, returning a tuple of the two
   * results if both things succeed
   */
  case class Sequence[+T1, +T2, R](p1: Parser[T1], p2: Parser[T2], cut: Boolean)
                                  (implicit ev: Implicits.Sequencer[T1, T2, R]) extends Parser[R]{
    def ev2: Implicits.Sequencer[_, _, _] = ev
    def parseRec(cfg: ParseCtx, index: Int) = {
      p1.parseRec(cfg, index) match{
        case f: Failure.Mutable => failMore(f, index, cfg.trace, cut = f.cut)
        case s1: Success.Mutable[_] =>
          val value1 = s1.value
          val cut1 = s1.cut
//          if (cut) println("CUT! " + this + ":" + s1.index)
          p2.parseRec(cfg, s1.index) match{
          case f: Failure.Mutable => failMore(f, index, cfg.trace, cut = cut | f.cut | cut1)
          case s2: Success.Mutable[_] => success(cfg.success, ev(value1, s2.value), s2.index, s2.cut | cut1 | cut)
        }
      }
    }

    override def toString = {
      def rec(p: Parser[_]): String = p match {
        case p: Sequence[_, _, _] =>
          val op = if(cut) "~!" else "~"
          rec(p.p1) + " " + op + " " + rec(p.p2)
        case p => p.toString
      }
      "(" + rec(this) + ")"
    }
  }


  /**
   * Repeats the parser over and over. Succeeds with a `Seq` of results
   * if there are more than [[min]] successful parses. uses the [[delimiter]]
   * between parses and discards its results
   */
  case class Repeat[T, +R](p: Parser[T], min: Int, delimiter: Parser[_])
                          (implicit ev: Implicits.Repeater[T, R]) extends Parser[R]{
    def parseRec(cfg: ParseCtx, index: Int) = {
      @tailrec def rec(index: Int,
                       del: Parser[_],
                       lastFailure: Failure.Mutable,
                       acc: ev.Acc,
                       cut: Boolean,
                       count: Int): Result[R] = {
        del.parseRec(cfg, index) match{
          case f1: Failure.Mutable =>
            if (f1.cut) failMore(f1, index, cfg.trace, true)
            else passIfMin(cut, f1, index, ev.result(acc), count)

          case s1: Success.Mutable[_] =>
            val cut1 = s1.cut
            val index1 = s1.index
            p.parseRec(cfg, index1) match{
              case f2: Failure.Mutable =>
                if (f2.cut | cut1) failMore(f2, index1, cfg.trace, true)
                else passIfMin(cut | s1.cut, f2, index, ev.result(acc), count)

              case s2: Success.Mutable[T] =>
                ev.accumulate(s2.value, acc)
                rec(s2.index, delimiter, lastFailure, acc, cut1 | s2.cut, count + 1)
            }
        }
      }

      def passIfMin(cut: Boolean, lastFailure: Failure.Mutable, finalIndex: Int, acc: R, count: Int) = {
        if (count >= min) success(cfg.success, acc, finalIndex, cut)
        else failMore(lastFailure, index, cfg.trace, cut)
      }
      rec(index, Pass, null, ev.initial, false, 0)
    }

    override def toString = {
      p + ".rep" + (if (min == 0) "" else min) + (if (delimiter == Pass) "" else s"($delimiter)")
    }
  }

  object Either{
    def flatten[T](p: Vector[Parser[T]]): Vector[Parser[T]] = p.flatMap{
      case Either(ps@_*) => ps
      case p => Vector(p)
    }
  }
  /**
   * Parses using one parser or the other, if the first one fails. Returns
   * the first one that succeeds and fails if both fail
   */
  case class Either[T](ps: Parser[T]*) extends Parser[T]{
    private[this] val ps0 = ps.toArray
    private[this] val n = ps0.length
    def parseRec(cfg: ParseCtx, index: Int) = {
      @tailrec def rec(parserIndex: Int): Result[T] = {
        if (parserIndex >= n) fail(cfg.failure, index)
        else ps0(parserIndex).parseRec(cfg, index) match {
          case s: Success.Mutable[_] => s
          case f: Failure.Mutable if f.cut => failMore(f, index, cfg.trace)
          case _ => rec(parserIndex + 1)
        }
      }
      rec(0)
    }
    override def toString = {
      def rec(p: Parser[_]): String = p match {
        case p: Either[_] => p.ps.map(rec).mkString(" | ")
        case p => p.toString
      }
      "(" + rec(this) + ")"
    }
  }

}
