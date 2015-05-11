package chess

import chess.format.pgn.San
import format.pgn.{ Parser, Reader, Tag }
import scalaz.Validation.FlatMap._

case class Replay(setup: Game, moves: List[Move], state: Game) {

  lazy val chronoMoves = moves.reverse

  def addMove(move: Move) = copy(
    moves = move.applyVariantEffect :: moves,
    state = state(move))

  def moveAtPly(ply: Int): Option[Move] = chronoMoves lift (ply - 1)
}

object Replay {

  def apply(game: Game) = new Replay(game, Nil, game)

  def apply(
    moveStrs: List[String],
    initialFen: Option[String],
    variant: chess.variant.Variant): Valid[Replay] =
    moveStrs.some.filter(_.nonEmpty) toValid "[replay] pgn is empty" flatMap { nonEmptyMoves =>
      Reader.moves(
        nonEmptyMoves,
        List(
          initialFen map { fen => Tag(_.FEN, fen) },
          variant.some.filterNot(_.standard) map { v => Tag(_.Variant, v.name) }
        ).flatten)
    }

  private def recursiveGames(game: Game, sans: List[San]): Valid[List[Game]] =
    sans match {
      case Nil => success(Nil)
      case san :: rest => san(game.situation) flatMap { move =>
        val newGame = game(move)
        recursiveGames(newGame, rest) map { newGame :: _ }
      }
    }

  def games(
    moveStrs: List[String],
    initialFen: Option[String],
    variant: chess.variant.Variant): Valid[List[Game]] =
    Parser.moves(moveStrs, variant) flatMap { moves =>
      val game = Game(variant.some, initialFen)
      recursiveGames(game, moves) map { game :: _ }
    }

  def gameStream(
    moveStrs: List[String],
    initialFen: Option[String],
    variant: chess.variant.Variant): Stream[Game] = {
    def mk(g: Game, moves: Stream[San]): Stream[Game] = moves match {
      case san #:: rest => san(g.situation) match {
        case scalaz.Success(move) =>
          val newGame = g(move)
          newGame #:: mk(newGame, rest)
        case _ => Stream.empty[Game]
      }
      case _ => Stream.empty[Game]
    }
    val init = Game(variant.some, initialFen)
    mk(init, Parser.moveStream(moveStrs, variant))
  }

  private def recursiveBoards(sit: Situation, sans: List[San]): Valid[List[Board]] =
    sans match {
      case Nil => success(Nil)
      case san :: rest => san(sit) flatMap { move =>
        val after = move.afterWithLastMove
        recursiveBoards(Situation(after, !sit.color), rest) map { after :: _ }
      }
    }

  def boards(
    moveStrs: List[String],
    initialFen: Option[String],
    variant: chess.variant.Variant,
    color: Color = White): Valid[List[Board]] = {
    val sit = {
      initialFen.flatMap(format.Forsyth.<<) | Situation(chess.variant.Standard)
    }.copy(color = color) withVariant variant
    Parser.moves(moveStrs, sit.board.variant) flatMap { moves =>
      recursiveBoards(sit, moves) map { sit.board :: _ }
    }
  }
}
