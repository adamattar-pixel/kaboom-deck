package apps.ul2025app9

import cs214.webapp.UserId

object Card:
  def toEmoji(c: Card): String =
    c match
      case Bomb => "💣"
      case Defuser => "✂"
      case Shuffle => "♻"
      case Nope => "⛔"
      case Skip => "⏩"
      case Attack => "⚔"
      case Forecast => "👁️"
      case Favour => "❤"
      case Blank => ""
      case Match(id) => id.toString

object Deck:
  def empty: Vector[Card] = Vector()

type Deck = Vector[Card]

enum Card:
  case Bomb
  case Defuser
  case Shuffle
  case Nope
  case Skip
  case Attack
  case Forecast
  case Favour
  case Blank
  case Match(id: Int)
  
  override def toString: String =
    this match
      case Bomb => "Bomb"
      case Defuser => "Defuser"
      case Shuffle => "Shuffle"
      case Nope => "Nope"
      case Skip => "Skip"
      case Attack => "Attack"
      case Forecast => "Forecast"
      case Favour => "Favour"
      case Blank => "Blank"
      case Match(id) => String.format("Match (%d)", id)

// start State
enum Phase:
  /** User picks cards to play or draws */
  case Playing
  /** Transition in render */
  case Paused
  /** Waiting for a possible cancel, optional target for picking events */
  case Waiting(msLeft: Int, targ: Option[UserId] = None)
  /** User must defuse within a certain timeframe */
  case Defusing(msLeft: Int)
  /** User is viewing something */
  case Viewing(msLeft: Int)
  /** User is picking a player */
  case Picking
  /** User is giving a card away */
  case Giving(uid: UserId)
  /** User decides where to insert bomb */
  case Inserting
  /** Display scoreboard at the end of game */
  case Done

case class Cancel(cancellable: Boolean, previous: Option[State])

object Cancel:
  def uncancellable = Cancel(false, None)

case class Turn(turnsLeft: Int, cumulative: Boolean)

case class State(
           players: Vector[UserId], // head is current player
           hands: Map[UserId, Vector[Card]],
           draw: Deck, // head is top of the pile
           discard: Deck, // head is the most recent card
           turn: Turn,
           cancel: Cancel,
           died: Vector[UserId], // head is most recently died
           phase: Phase,
           log: Vector[String] // head is most recent
           ):

  override def toString: String = {
    val playersFormatted = players.zipWithIndex.map { case (pid, index) =>
      val handStr = hands.getOrElse(pid, Vector.empty).mkString(", ")
      val marker = if (index == 0) " <--- [CURRENT PLAYER]" else ""
      s"  ${index + 1}. $pid: [$handStr]$marker"
    }.mkString("\n")

    def indent(text: String, indentation: String = "    "): String = {
      text.linesIterator.map(line => s"$indentation$line").mkString("\n")
    }

    val logFormatted = if (log.isEmpty) "  (Empty)" else log.reverse.map(l => s"  > $l").mkString("\n")
    val cancelFormatted = indent(cancel.toString, "    ")

    s"""
       |========================= GAME STATE =========================
       |PHASE:      $phase
       |TURNS LEFT: $turn
       |CANCEL:     $cancelFormatted
       |
       |--- PLAYERS ---
       |$playersFormatted
       |
       |--- TABLE ---
       |Draw Pile:    [${draw.mkString(", ")}] (Top is First)
       |Discard Pile: [${discard.mkString(", ")}] (Top is First)
       |Graveyard:    [${died.mkString(", ")}]
       |
       |--- LOG ---
       |$logFormatted
       |==============================================================
       |""".stripMargin
  }
// end State

// start Event
enum Event:
  /** User drew a card */
  case Drew
  /** User clicked on a card */
  case CardClicked(cardId: Int)
  /** User chose an insertion position */
  case Inserted(position: Int)
  /** User picked a player */
  case PlayerPicked(uid: UserId)
// end Event

// start View
case class View(
          hand: Deck, // my hand
          others: Vector[PlayerView], // info about other players (head = left player)
          state: StateView, // my current state
          leftToDraw: Int, // how many cards left in the draw pile
          lastPlayed: Card, // last played card (head of discard)
          log: Vector[String], // every actions played since the start (head is most recent)
          playerTurn: UserId, // whose turn it is
          turnsLeft: Int, // how many turns left for the current player
          alive: Boolean 
          )

case class PlayerView(player: UserId, cardsLeft: Int, alive: Boolean)

enum StateView:
  /** Game is ongoing, may or may not have to act */
  case Playing
  /** Game is paused during a transition, no one may act */
  case Paused
  /** Game is ongoing, a timer is ticking */
  case Waiting(msLeft: Int)
  /** Game is ongoing, waiting for player insert */
  case Inserting
  /** Game is ongoing, displaying cards to the player */
  case ViewingCards(cards: Deck, msLeft: Int)
  /** Game is ongoing, picking a player */
  case PickingPlayer
  case GivingCard(giver: UserId)
  /** Game is finished */
  case Finished(leaderboard: Vector[UserId])
// end View
