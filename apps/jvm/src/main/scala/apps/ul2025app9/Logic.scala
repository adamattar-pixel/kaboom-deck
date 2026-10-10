package apps
package ul2025app9

import apps.ul2025app9.Logic.TRANSITION_PAUSE_MS
import cs214.webapp.Action.{Pause, Render}
import cs214.webapp.{Action, AppInfo, AppWire, IllegalMoveException, NotYourTurnException, Tick, UserId}
import cs214.webapp.server.ClockDrivenStateMachine

import scala.util.Try
import scala.util.Random

object Logic:
  private val STARTING_HAND_SIZE = 7 // not counting extra defusers
  private val WAIT_TIME_MS = 3000
  private val DEFUSE_TIME_MS = 5000
  private val VIEWING_TIME_MS = 4000
  private val TRANSITION_PAUSE_MS = 1000
  private val VIEWING_CARD_COUNT = 3
  private val RNG = Random()

  private lazy val DECK: Deck = // Deck WITHOUT BOMBS and ONLY EXTRA DEFUSERS
    Deck.empty ++
      Vector.fill(5)(Card.Nope) ++
      Vector.fill(4)(Card.Shuffle) ++
      Vector.fill(2)(Card.Defuser) ++
      Vector.fill(4)(Card.Skip) ++
      Vector.fill(4)(Card.Attack) ++
      Vector.fill(5)(Card.Forecast) ++
      Vector.fill(4)(Card.Favour) ++
      Vector.fill(4)(Card.Match(1)) ++
      Vector.fill(4)(Card.Match(2)) ++
      Vector.fill(4)(Card.Match(3)) ++
      Vector.fill(4)(Card.Match(4)) ++
      Vector.fill(4)(Card.Match(5))

  private def shuffleDeal(players: Seq[UserId], deck: Deck): (Map[UserId, Deck], Deck) =
    require(players.length > 1, "Not enough players!")
    require(players.length <= 4, "Too many players!")
    require(deck.length >= STARTING_HAND_SIZE * players.length, "Not enough cards in deck!")
    
    val shuffled = RNG.shuffle(deck)
    val (dealt, remaining) = players.foldRight((Map[UserId, Deck](), shuffled))
      ((uid, tup) => (tup._1 + (uid -> tup._2.take(STARTING_HAND_SIZE)), tup._2.drop(STARTING_HAND_SIZE)))
    val withDefuser = dealt.map((k, v) => k -> v.appended(Card.Defuser))
    val withBombs = RNG.shuffle(remaining ++ Vector.fill(players.length - 1)(Card.Bomb))
    
    (withDefuser, withBombs).ensuring(withDefuser.forall(t => t._2.length == STARTING_HAND_SIZE + 1)
      && withBombs.count(_.equals(Card.Bomb)) == players.length - 1)

// start Logic
class Logic extends ClockDrivenStateMachine[Event, State, View]:

  override val clockPeriodMs: Int = 100 // one tick every 100ms
  override val clockDrivenWire: AppWire[Event, View] = ul2025app9.Wire

  /** Returns the given vector with the next user at the head, and the rest of the users correctly ordered.
   * Users in the ignore field are ignored (but still appear in the output) */
  private def nextUsers(all: Vector[UserId], ignore: Vector[UserId]): Vector[UserId] =
    require(all.nonEmpty, "Vector of users must be non-empty!")
    require(!all.forall(ignore.contains(_)), "Cannot ignore every user!")
    val result = if all.count(!ignore.contains(_)) == 1 then all
    else
      val next = all.tail.dropWhile(ignore.contains(_))(0)
      val nextId = all.indexOf(next)
      all.drop(nextId) ++ all.take(nextId)

    result.ensuring(res =>
      !ignore.contains(res.head) || all.forall(ignore.contains), "Next user should not be in ignored list"
    )

  override val appInfo: AppInfo = AppInfo(
    id = "ul2025app9",
    name = "Kaboom Deck",
    description = "Players try to avoid drawing bombs by playing action cards. Last player alive wins!",
    year = 2025
  )

  override def init(clients: Seq[UserId]): State =
    val (hands, drawpile) = Logic.shuffleDeal(clients.toVector, Logic.DECK)
    State(
      clients.toVector,
      hands,
      drawpile,
      Deck.empty,
      Turn(1, false),
      Cancel.uncancellable,
      Vector(),
      Phase.Playing,
      Vector(String.format("Start of the game. %s's turn to play.", clients.head))
    )

  override def transition(state: State)(userId: UserId, tickOrEvent: Either[Tick, Event]): Try[Seq[Action[State]]] =

    // HELPERS

    def transferCard(st: State, from: UserId, to: UserId, cardId: Int): State =
      require(from != to, "Giver and receiver cannot be the same person")

      st.copy(
        hands = st.hands
        + (from -> st.hands(from).patch(cardId, Nil, 1))
        + (to -> (st.hands(from)(cardId) +: st.hands(to)))
      )

    def useCard(st: State, player: UserId, cardId: Int, card: Card): State =
        require(st.hands(player)(cardId) == card, "Played card does not match")

        st.copy(
          hands = st.hands + (player -> st.hands(player).patch(cardId, Nil, 1)),
          discard = card +: st.discard
        )

    def getLastPlayed(discard: Deck): Card =
      require(!discard.forall(_ == Card.Nope), "Every card in the discard pile cannot be a cancel card")

      discard.dropWhile(_ == Card.Nope).head


    def cancelAction(cancelState: State): Seq[Action[State]] =
      val withNope = cancelState.copy( // State right after cancelling
        cancel = Cancel.uncancellable,
        phase = Phase.Paused,
        log = String.format("%s cancelled that action!", userId) +: state.log
      )

      state.cancel.previous match
        case Some(previous) =>
          val withCancelled = previous.copy( // State to cancel to
            hands = withNope.hands,
            discard = Card.Nope +: state.discard,
            cancel = Cancel(true, Option(cancelState)),
            log = String.format("Back to %s's turn.", previous.players.head) +: withNope.log
          )
          Seq(
            Render(withNope),
            Pause(TRANSITION_PAUSE_MS),
            Render(withCancelled)
          )
        case None => throw AssertionError("Previous state is empty")

    def isWithinBounds(cardId: Int): Unit = {
      if cardId < 0 || cardId >= state.hands(userId).length then throw IllegalMoveException("Selected card is out of bound")
    }

    def advanceTurn(forceSkip: Boolean, nextTurnCount: Int, base: State, cumulative: Boolean): Seq[Action[State]] =
      val nextPlayers = nextUsers(base.players, base.died)
      val withNext = if forceSkip || base.turn.turnsLeft == 1 then
        val totalTurns = nextTurnCount + (if cumulative && base.turn.cumulative then base.turn.turnsLeft else 0)
        base.copy(
          players = nextPlayers,
          turn = Turn(totalTurns, cumulative || nextTurnCount > 1),
          log = (if totalTurns == 1 then String.format("%s's turn now.", nextPlayers.head) else String.format("%s plays %d turns now.", nextPlayers.head, totalTurns)) +: base.log
        )
      else
        base.copy(
          turn = Turn(turnsLeft = base.turn.turnsLeft - 1, base.turn.cumulative),
          log = String.format("%s plays again.", base.players.head) +: base.log
        )

      Seq(Render(withNext))

    // HANDLERS

    def handleWaitTick(t: Int, maybeOther: Option[UserId]) = {
      if t - clockPeriodMs <= 0 then
        val played = getLastPlayed(state.discard)
        played match
          case Card.Shuffle =>
            val withShuffled = state.copy(
              draw = Logic.RNG.shuffle(state.draw),
              cancel = Cancel.uncancellable,
              phase = Phase.Playing,
              log = String.format("The deck was shuffled. Still %s's turn.", state.players.head) +: state.log
            )
            Seq(Render(withShuffled))

          case Card.Forecast =>
            val withForecast = state.copy(
              cancel = Cancel.uncancellable,
              phase = Phase.Viewing(Logic.VIEWING_TIME_MS),
              log = String.format("%s is viewing the next %d cards!", state.players.head, Logic.VIEWING_CARD_COUNT) +: state.log
            )

            Seq(Render(withForecast))

          case Card.Match(_) =>
            maybeOther match
              case Some(other) =>
                if state.hands(other).isEmpty then Seq(Render(state.copy(
                  cancel = Cancel.uncancellable,
                  phase = Phase.Playing,
                  log = String.format("%s had no card to give! Back to %s's turn.", other, state.players.head) +: state.log
                )))
                else
                  val randomId = Random.nextInt(state.hands(other).length)
                  val randomCard = state.hands(other)(randomId)
                  val withMatch = transferCard(state, other, state.players.head, randomId).copy(
                    cancel = Cancel.uncancellable,
                    phase = Phase.Playing,
                    log = String.format("%s received a random card from %s! Back to their turn.", state.players.head, other) +: state.log
                  )

                  Seq(Render(withMatch))
              case None => throw AssertionError("Missing picked player")

          case _ => throw AssertionError("Unsupported card")
      else
        Seq(Render(state.copy(phase = Phase.Waiting(t - clockPeriodMs, maybeOther))))
    }

    def handleDefuseTick(t: Int) = {
      if t - clockPeriodMs <= 0 then
        val withExplosion = state.copy(
          died = state.players.head +: state.died,
          cancel = Cancel.uncancellable,
          phase = Phase.Paused,
          log = String.format("%s EXPLODED!!!", state.players.head) +: state.log
        )

        val nextPlayers = nextUsers(state.players, state.died)
        val withNext = if withExplosion.died.length < withExplosion.players.length - 1
        then
          withExplosion.copy(
            players = nextPlayers,
            turn = Turn(1, false),
            phase = Phase.Playing,
            log = String.format("%s's turn now.", nextPlayers.head) +: withExplosion.log
          )
        else
          withExplosion.copy(
            players = nextPlayers,
            turn = Turn(1, false),
            phase = Phase.Done,
            log = String.format("Game ended! %s is the winner!", nextPlayers.head) +: withExplosion.log
          )

        Seq(
          Render(withExplosion),
          Pause(TRANSITION_PAUSE_MS),
          Render(withNext)
        )
      else
        Seq(Render(state.copy(phase = Phase.Defusing(t - clockPeriodMs))))
    }

    def handleViewTick(t: Int) = {
      if t - clockPeriodMs <= 0 then
        Seq(Render(state.copy(
          phase = Phase.Playing,
          log = String.format("%s finished viewing, back to their turn.", state.players.head) +: state.log
        )))
      else
        Seq(Render(state.copy(phase = Phase.Viewing(t - clockPeriodMs))))
    }

    def handleDraw = {
      if state.players.head != userId then throw NotYourTurnException()
      require(state.draw.nonEmpty, "Cannot draw from empty pile")

      val drawn = state.draw.head
      if drawn == Card.Bomb then
        val withBomb = state.copy(
          draw = state.draw.tail,
          discard = Card.Bomb +: state.discard,
          cancel = Cancel.uncancellable,
          phase = Phase.Defusing(Logic.DEFUSE_TIME_MS),
          log = String.format("%s drew a bomb and must defuse it!", userId) +: state.log
        )

        Seq(Render(withBomb))
      else
        advanceTurn(false, 1, state.copy(
          hands = state.hands + (userId -> (drawn +: state.hands(userId))),
          draw = state.draw.tail,
          cancel = Cancel.uncancellable,
          log = String.format("%s drew a card.", userId) +: state.log
        ), false)
    }

    def handleWaiting(event: Event) = {
      if state.players.head == userId then throw IllegalMoveException("Cannot act now")
      event match
        case Event.CardClicked(cardId) =>
          isWithinBounds(cardId)
          val card = state.hands(userId)(cardId)
          if card != Card.Nope then throw IllegalMoveException("You may only play cancel cards here")
          if !state.cancel.cancellable then throw IllegalMoveException("This action cannot be cancelled")

          val cancelState = useCard(state, userId, cardId, Card.Nope).copy(
            phase = state.phase match
              case Phase.Waiting(_, maybeOther) => Phase.Waiting(Logic.WAIT_TIME_MS, maybeOther)
              case _ => throw AssertionError("Not in waiting phase")
          )

          cancelAction(cancelState)

        case _ => throw IllegalMoveException("Illegal action")
    }

    def handleDefusing(event: Event) = {
      if state.players.head != userId then throw NotYourTurnException()
      event match
        case Event.CardClicked(cardId) =>
          isWithinBounds(cardId)
          val card = state.hands(userId)(cardId)
          if card != Card.Defuser then throw IllegalMoveException("You may only defuse with a defuser")

          val withDefuse = useCard(state, userId, cardId, Card.Defuser).copy(
            cancel = Cancel.uncancellable,
            phase = Phase.Paused,
            log = String.format("%s defused the bomb!", userId) +: state.log
          )
          val withInserting = withDefuse.copy(
            phase = Phase.Inserting,
            log = String.format("%s is inserting the bomb back into the draw!", userId) +: withDefuse.log
          )

          Seq(
            Render(withDefuse),
            Pause(TRANSITION_PAUSE_MS),
            Render(withInserting)
          )
        case _ => throw IllegalMoveException("Illegal action")
    }

    def handleInserting(event: Event) = {
      if state.players.head != userId then throw NotYourTurnException()
      event match
        case Event.Inserted(pos) =>
          if pos < 0 || pos > state.draw.length then throw IllegalMoveException("Insert position is out of bounds of the draw pile")
          val withInsert = state.copy(
            draw = state.draw.patch(pos, Vector(Card.Bomb), 0),
            cancel = Cancel.uncancellable,
            phase = Phase.Paused,
            log = String.format("%s inserted a bomb back into the draw!", userId) +: state.log
          )

          Seq(
            Render(withInsert),
            Pause(TRANSITION_PAUSE_MS)) ++ advanceTurn(false, 1, withInsert.copy(phase = Phase.Playing), false)
        case _ => throw IllegalMoveException("Must choose a position to insert")
    }

    def handlePicked(other: UserId) = {
      if state.players.head != userId then throw NotYourTurnException()
      if other == userId then throw IllegalMoveException("Cannot pick yourself")
      if state.died.contains(other) then throw IllegalMoveException("Cannot pick a dead player")

      val played = getLastPlayed(state.discard)
      played match
        case Card.Favour => Seq(Render(state.copy(
          phase = Phase.Giving(other),
          log = String.format("%s picked %s. They must give them a card.", state.players.head, other) +: state.log
        )))
        case Card.Match(_) => Seq(Render(state.copy(
          phase = Phase.Waiting(Logic.WAIT_TIME_MS, Some(other)),
          log = String.format("%s picked %s. They will get a random card from them...", state.players.head, other) +: state.log
        )))
        case _ => throw AssertionError("Invalid card played before picking")
    }

    def handleGiving(cardId: Int) = {
      val withGiven = transferCard(state, userId, state.players.head, cardId).copy(
        cancel = Cancel.uncancellable,
        phase = Phase.Playing,
        log = String.format("%s gave a card to %s! Back to their turn.", userId, state.players.head) +: state.log
      )

      Seq(Render(withGiven))
    }

    def handleShuffle(cardId: Int) = {
      if state.players.head != userId then throw NotYourTurnException()
      val cancelState = useCard(state, userId, cardId, Card.Shuffle)

      val withWaiting = cancelState.copy(
        phase = Phase.Waiting(Logic.WAIT_TIME_MS),
        cancel = Cancel(true, Option(cancelState)),
        log = String.format("%s played a shuffle card!", userId) +: state.log
      )

      Seq(Render(withWaiting))
    }

    def handleNope(cardId: Int) = {
      if !state.cancel.cancellable then throw IllegalMoveException("Cannot cancel at the moment!")
      if state.cancel.previous.isEmpty then throw AssertionError("Previous state is empty!")

      val cancelState = useCard(state, userId, cardId, Card.Nope)

      cancelAction(cancelState)
    }

    def handleSkip(cardId: Int) = {
      if state.players.head != userId then throw NotYourTurnException()
      val cancelState = useCard(state, userId, cardId, Card.Skip)

      advanceTurn(false, 1, cancelState.copy(
        cancel = Cancel(true, Option(cancelState)),
        log = String.format("%s played a skip card!", userId) +: state.log
      ), false)
    }

    def handleAttack(cardId: Int) = {
      if state.players.head != userId then throw NotYourTurnException()
      val cancelState = useCard(state, userId, cardId, Card.Attack)

      advanceTurn(true, 2, cancelState.copy(
        cancel = Cancel(true, Option(cancelState)),
        log = String.format("%s played an attack card!", userId) +: state.log
      ), true)
    }

    def handleForecast(cardId: Int) = {
      if state.players.head != userId then throw NotYourTurnException()
      val cancelState = useCard(state, userId, cardId, Card.Forecast)

      val withWaiting = cancelState.copy(
        phase = Phase.Waiting(Logic.WAIT_TIME_MS),
        cancel = Cancel(true, Option(cancelState)),
        log = String.format("%s played a forecast card!", userId) +: state.log
      )

      Seq(Render(withWaiting))
    }

    def handleFavour(cardId: Int) = {
      if state.players.head != userId then throw NotYourTurnException()
      val cancelState = useCard(state, userId, cardId, Card.Favour)

      val withFavour = cancelState.copy(
        phase = Phase.Picking,
        cancel = Cancel(true, Option(cancelState)),
        log = String.format("%s played a favour card! They are picking a player...", userId) +: state.log
      )
      
      Seq(Render(withFavour))
    }

    def handleMatch(cardId: Int, matchId: Int) = {
      if state.players.head != userId then throw NotYourTurnException()
      val withoutCard = state.hands(userId).patch(cardId, Nil, 1)
      val withIndices = withoutCard.indices.map(i => i -> withoutCard(i)).toVector
      val matching = withIndices.filter(
        (_, c) => c match
          case Card.Match(i) => i == matchId
          case _ => false
      )
      if matching.isEmpty then throw IllegalMoveException("No matching pair")
      else
        val cancelState = state.copy(
          hands = state.hands + (userId -> withIndices.patch(matching.head._1, Nil, 1).map((_, c) => c)),
          discard = Vector.fill(2)(Card.Match(matchId)) ++ state.discard
        )

        val withMatch = cancelState.copy(
          phase = Phase.Picking,
          cancel = Cancel(true, Option(cancelState)),
          log = String.format("%s played a matching pair of %ds! They are picking a player...", userId, matchId) +: state.log
        )

        Seq(Render(withMatch))
    }

    // TRANSITION LOGIC

    Try:
      tickOrEvent match
        case Left(_) =>
          state.phase match
            case Phase.Waiting(t, oTarg) => handleWaitTick(t, oTarg)
            case Phase.Defusing(t) => handleDefuseTick(t)
            case Phase.Viewing(t) => handleViewTick(t)
            case Phase.Giving(giver) =>
              if state.hands(giver).isEmpty then
                Seq(Render(state.copy(
                  phase = Phase.Playing,
                  cancel = Cancel.uncancellable,
                  log = String.format("%s has no card left to give! Back to %s's turn", giver, state.players.head) +: state.log
                )))
              else Seq()
            case _ => Seq()
        case Right(event) =>
          if state.died.contains(userId) then throw IllegalMoveException("Dead players cannot act!")
          state.phase match
            case Phase.Playing =>
              event match
                case Event.Drew => handleDraw
                case Event.CardClicked(cardId) =>
                  isWithinBounds(cardId)
                  state.hands(userId)(cardId) match
                    case Card.Shuffle => handleShuffle(cardId)
                    case Card.Nope => handleNope(cardId)
                    case Card.Skip => handleSkip(cardId)
                    case Card.Attack => handleAttack(cardId)
                    case Card.Forecast => handleForecast(cardId)
                    case Card.Favour => handleFavour(cardId)
                    case Card.Match(id) => handleMatch(cardId, id)
                    case _ => throw IllegalMoveException("Can't play this card now")
                case _ => throw IllegalMoveException("Illegal action")
            case Phase.Waiting(t, _) => handleWaiting(event)
            case Phase.Defusing(t) => handleDefusing(event)
            case Phase.Viewing(t) => Seq()
            case Phase.Picking =>
              event match
                case Event.CardClicked(cardId) =>
                  isWithinBounds(cardId)
                  if state.players.head != userId && state.hands(userId)(cardId) == Card.Nope then
                    handleNope(cardId)
                  else throw IllegalMoveException("Can't play this card now")
                case Event.PlayerPicked(p) => handlePicked(p)
                case _ => throw IllegalMoveException("Illegal action")
            case Phase.Giving(giver) =>
              event match
                case Event.CardClicked(cardId) =>
                  isWithinBounds(cardId)
                  if state.players.head != userId && state.hands(userId)(cardId) == Card.Nope then
                    handleNope(cardId)
                  else if giver == userId then
                    handleGiving(cardId)
                  else throw IllegalMoveException("Can't play this card now")
                case _ => throw IllegalMoveException("Illegal action")
            case Phase.Inserting => handleInserting(event)
            case Phase.Paused => Seq()
            case Phase.Done => Seq()

  override def project(state: State)(userId: UserId): View =
    val hand = state.hands(userId)
    val id = state.players.indexOf(userId)
    val others = (state.players.drop(id) ++ state.players.take(id))
      .drop(1)
      .map(uid => PlayerView(uid,
        state.hands(uid).length,
        !state.died.contains(uid)
      ))
    val alive = !state.died.contains(userId)
    val leftToDraw = state.draw.length
    val lastPlayed = if state.discard.isEmpty then Card.Blank else state.discard.head
    val playerTurn = state.players.head
    val turnsLeft = state.turn.turnsLeft

    val log = state.phase match
      case Phase.Waiting(t, _) => String.format("Time left to cancel (%.1fs)", t.toFloat / 1000) +: state.log
      case Phase.Defusing(t) => String.format("Time left to defuse (%.1fs)", t.toFloat / 1000) +: state.log
      case Phase.Viewing(t) => String.format("Time left to view (%.1fs)", t.toFloat / 1000) +: state.log
      case _ => state.log

    val stateView = state.phase match
      case Phase.Playing => StateView.Playing
      case Phase.Waiting(t, _) => StateView.Waiting(t)
      case Phase.Defusing(t) => StateView.Waiting(t)
      case Phase.Viewing(t) => StateView.ViewingCards(state.draw.take(Logic.VIEWING_CARD_COUNT), t)
      case Phase.Inserting => if state.players.head == userId then StateView.Inserting else StateView.Playing
      case Phase.Done => StateView.Finished(state.players.filter(!state.died.contains(_)).head +: state.died)
      case Phase.Paused => StateView.Paused
      case Phase.Picking => StateView.PickingPlayer
      case Phase.Giving(giver) => StateView.GivingCard(giver)

    View(hand, others, stateView, leftToDraw, lastPlayed, log, playerTurn, turnsLeft, alive)

// end Logic
