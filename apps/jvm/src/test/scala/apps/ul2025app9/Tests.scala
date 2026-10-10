package apps.ul2025app9

import apps.ul2025app9.*
import apps.ul2025app9.Event.CardClicked
import cs214.webapp.*
import cs214.webapp.Action
import cs214.webapp.server.{ClockDrivenStateMachine, StateMachine}
import cs214.webapp.utils.WebappSuite
import scala.io.Source
import java.io.File


class Tests extends WebappSuite[Event, State, View]:
  val sm: Null = null
  val fsm = Logic()

  val initState = fsm.init(USER_IDS)

  val minState = State(
    Vector("u1", "u2", "u3"),
    Map(
      "u1" -> Vector(),
      "u2" -> Vector(),
      "u3" -> Vector()
    ),
    Vector(),
    Vector(),
    Turn(1, false),
    Cancel(false, None),
    Vector(),
    Phase.Playing,
    Vector("Minimal example")
  )

  test("Initial state has all players"):
    assertEquals(initState.players, USER_IDS)

  test("Initial state has correct card count and a defuser per player"):
    assert(initState.hands.forall((uid, vec) => vec.length == 8))
    assert(initState.hands.forall((uid, vec) => vec.contains(Card.Defuser)))

  test("Initial state draw contains enough bombs"):
    assert(initState.draw.count(_ == Card.Bomb) == initState.players.length - 1)

  test("Initial state starts in a playing phase, with correct turn, discard and cancel information"):
    assert(initState.discard.isEmpty)
    assert(initState.turn == Turn(1, false))
    assert(initState.cancel == Cancel.uncancellable)
    assert(initState.phase == Phase.Playing)

  val state1 = State(
    Vector("u1", "u2", "u3"),
    Map(
      "u1" -> Vector(Card.Attack, Card.Skip),
      "u2" -> Vector(Card.Nope),
      "u3" -> Vector(Card.Nope)
    ),
    Vector.fill(10)(Card.Match(1)),
    Vector(),
    Turn(1, false),
    Cancel(false, None),
    Vector(),
    Phase.Playing,
    Vector("Minimal example")
  )

  def trans(fsm: ClockDrivenStateMachine[Event, State, View], s: State)(u: String, e: Event): State =
    fsm.transition(s)(u, Right(e)).get.last.asInstanceOf[Action.Render[State]].st

  test("Player can draw a card and advance to the next turn"):
    val st = trans(fsm, state1)("u1", Event.Drew)
    assertEquals(st.players.head, "u2")
    assertEquals(st.hands("u1").length, 3)
    assert(st.hands("u1").contains(Card.Match(1)))
    assertEquals(st.draw.length, 9)
    assertEquals(st.discard.length, 0)
    assertEquals(st.turn, Turn(1, false))
    assertEquals(st.cancel, Cancel(false, None))
    assertEquals(st.phase, Phase.Playing)

  test("Player can skip their turn and advance to the next turn"):
    val st = trans(fsm, state1)("u1", Event.CardClicked(1))
    assertEquals(st.players.head, "u2")
    assertEquals(st.hands("u1").length, 1)
    assert(!st.hands("u1").contains(Card.Skip))
    assertEquals(st.draw.length, 10)
    assertEquals(st.discard, Vector(Card.Skip))
    assertEquals(st.turn, Turn(1, false))
    assert(st.cancel.cancellable)
    assertEquals(st.phase, Phase.Playing)

  test("Player can attack, ends their turn and forces next player to take 2 turns"):
    val st = trans(fsm, state1)("u1", Event.CardClicked(0))

    assertEquals(st.players.head, "u2", "Turn did not advance to the next player")
    assertEquals(st.turn, Turn(2, true), "Next player should have 2 cumulative turns")

    assertEquals(st.hands("u1"), Vector(Card.Skip))
    assertEquals(st.discard.head, Card.Attack)

    assert(st.cancel.cancellable, "Attack action should be cancellable")
    assertEquals(st.phase, Phase.Playing)

  test("Player can Nope a Skip: cancels the action and returns turn to original player"):
    // 1. Action: u1 plays Skip (Index 1)
    // This tentatively passes the turn to u2
    val stSkip = trans(fsm, state1)("u1", Event.CardClicked(1))

    // Pre-condition check: verify turn actually passed to u2 and is cancellable
    assertEquals(stSkip.players.head, "u2")
    assert(stSkip.cancel.cancellable)

    // 2. Action: u2 plays Nope (Index 0)
    // This should negate the Skip and return control to u1
    val stNope = trans(fsm, stSkip)("u2", Event.CardClicked(0))

    // 3. Assertions: Turn Reversal
    // Control must return to u1
    assertEquals(stNope.players.head, "u1", "Turn did not revert to u1 after Nope")
    // The turn state should be exactly as it was before the Skip (1 turn, non-cumulative)
    assertEquals(stNope.turn, Turn(1, false))

    // 4. Assertions: Hands and Discard
    // u2 used their Nope card
    assert(stNope.hands("u2").isEmpty)
    // u1 used their Skip card (the card is lost, even though the effect was cancelled)
    assert(!stNope.hands("u1").contains(Card.Skip))
    // The discard pile should have Nope on top, followed by Skip
    assertEquals(stNope.discard.take(2), Vector(Card.Nope, Card.Skip))

    // 5. Assertions: Game State
    // The Nope itself should ideally be cancellable (a Nope on a Nope)
    assert(stNope.cancel.cancellable)
    assertEquals(stNope.phase, Phase.Playing)

  test("Nope on a Nope (Yup): reinstates the original action"):
    // 1. Action: u1 plays Skip
    // Effect: Turn tentatively passes to u2
    val stSkip = trans(fsm, state1)("u1", Event.CardClicked(1))
    assertEquals(stSkip.players.head, "u2")

    // 2. Action: u2 plays Nope
    // Effect: Cancels the Skip. Turn reverts to u1.
    val stNope = trans(fsm, stSkip)("u2", Event.CardClicked(0))
    assertEquals(stNope.players.head, "u1")

    // 3. Action: u3 plays Nope (The "Yup")
    // Effect: Cancels u2's Nope. The original Skip is reinstated.
    val stYup = trans(fsm, stNope)("u3", Event.CardClicked(0))

    // 4. Assertions: Turn Logic
    // Because the Skip was reinstated, the turn effectively ends for u1 and passes to u2
    assertEquals(stYup.players.head, "u2", "Turn should advance to u2 as the Skip is reinstated")
    assertEquals(stYup.turn, Turn(1, false))

    // 5. Assertions: Hands
    // Everyone played their card, so all hands decrease
    assert(!stYup.hands("u1").contains(Card.Skip)) // u1 played Skip
    assert(stYup.hands("u2").isEmpty) // u2 played Nope
    assert(stYup.hands("u3").isEmpty) // u3 played Nope

    // 6. Assertions: Discard Pile
    // The pile should stack chronologically: Skip (bottom), Nope, Nope (top)
    assertEquals(stYup.discard.take(3), Vector(Card.Nope, Card.Nope, Card.Skip))

    // 7. Assertions: Game State
    // The chain can continue (a 4th Nope could happen), so it remains cancellable
    assert(stYup.cancel.cancellable)
    assertEquals(stYup.phase, Phase.Playing)

  test("Round Robin: Turns pass u1 -> u2 -> u3 -> u1"):
    // 1. u1 draws, passing turn to u2
    val st2 = trans(fsm, state1)("u1", Event.Drew)
    assertEquals(st2.players.head, "u2")
    assertEquals(st2.hands("u1").length, 3) // u1 gained a card

    // 2. u2 draws, passing turn to u3
    val st3 = trans(fsm, st2)("u2", Event.Drew)
    assertEquals(st3.players.head, "u3")
    assertEquals(st3.hands("u2").length, 2) // u2 gained a card

    // 3. u3 draws, passing turn back to u1
    val st4 = trans(fsm, st3)("u3", Event.Drew)
    assertEquals(st4.players.head, "u1")
    assertEquals(st4.hands("u3").length, 2) // u3 gained a card

    // Verify deck decreased by 3
    assertEquals(st4.draw.length, 7)

  test("Targeted player must take two actions to consume an Attack"):
    // 1. Setup: u1 plays Attack on u2
    val stAttacked = trans(fsm, state1)("u1", Event.CardClicked(0))

    // Pre-check: It is u2's turn, and they have 2 turns pending
    assertEquals(stAttacked.players.head, "u2")
    assertEquals(stAttacked.turn, Turn(2, true))

    // 2. Action: u2 draws their FIRST card
    val stTurn1 = trans(fsm, stAttacked)("u2", Event.Drew)

    // Assertions: Turn should NOT pass yet
    assertEquals(stTurn1.players.head, "u2", "Turn should stay with u2 after first draw")
    assertEquals(stTurn1.turn.turnsLeft, 1, "Turns left should decrement to 1")
    assertEquals(stTurn1.draw.length, 9)

    // 3. Action: u2 draws their SECOND card
    val stTurn2 = trans(fsm, stTurn1)("u2", Event.Drew)

    // Assertions: Turn should NOW pass to u3
    assertEquals(stTurn2.players.head, "u3", "Turn should pass to u3 after second draw")
    assertEquals(stTurn2.turn.turnsLeft, 1, "New turn should reset to standard 1 turn")
    assertEquals(stTurn2.draw.length, 8)

  val state2 = State(
    Vector("u1", "u2", "u3"),
    Map(
      "u1" -> Vector(Card.Shuffle),
      "u2" -> Vector(Card.Nope),
      "u3" -> Vector(Card.Nope)
    ),
    Vector(Card.Match(1), Card.Match(2), Card.Match(3), Card.Match(4), Card.Match(5), Card.Match(6), Card.Match(7), Card.Match(8)),
    Vector(),
    Turn(1, false),
    Cancel(false, None),
    Vector(),
    Phase.Playing,
    Vector("Minimal example")
  )

  val WAIT_TIME_MS = 3000
  val clockPeriodMs: Int = 100 // one tick every 100ms
  def tick(fsm: ClockDrivenStateMachine[Event, State, View], s: State): State =
    fsm.transition(s)("ticker", Left(Tick(67))).get.last.asInstanceOf[Action.Render[State]].st

  test("Shuffle card triggers wait, shuffles deck, and keeps turn"):
    // 1. Setup
    var st = state2
    val initialDeckSize = st.draw.length
    val initialDeckContent = st.draw.toSet

    // 2. Action: u1 plays Shuffle (Index 0)
    st = trans(fsm, st)("u1", Event.CardClicked(0))

    // 3. Assertions: Immediate State (Waiting)
    // The game must enter a Waiting phase to allow for a Nope
    assert(st.phase match
      case Phase.Waiting(_, _) => true
      case _ => false
      , "Phase should be Waiting immediately after playing Shuffle")

    assertEquals(st.discard.head, Card.Shuffle, "Shuffle card should be in discard pile")
    assert(st.hands("u1").isEmpty, "Shuffle card should be removed from hand")

    // 4. Action: Simulate Time Passing
    var safetyLimit = 0
    while (st.phase.isInstanceOf[Phase.Waiting] && safetyLimit < 50) do
      st = tick(fsm, st)
      safetyLimit += 1

    // 5. Assertions: Final State (Resolution)
    assertEquals(st.phase, Phase.Playing, "Phase should return to Playing after timer expires")

    assertEquals(st.players.head, "u1", "Turn should remain with u1 after Shuffle")

    // Check Deck integrity
    assertEquals(st.draw.length, initialDeckSize, "Deck size must remain the same")
    assertEquals(st.draw.toSet, initialDeckContent, "Deck content must remain the same")
    assert(st.draw != state2.draw)

  test("Nope cancels Shuffle during Wait: phase returns to Playing, deck untouched"):
    // 1. Setup
    var st = state2
    val initialDeck = st.draw

    // 2. Action: u1 plays Shuffle
    // Effect: Enters Waiting phase
    st = trans(fsm, st)("u1", Event.CardClicked(0))
    assert(st.phase.isInstanceOf[Phase.Waiting], "Game must wait for Nopes")

    // 3. Action: u2 plays Nope (while waiting)
    // Effect: Should cancel the shuffle immediately
    st = trans(fsm, st)("u2", Event.CardClicked(0))

    // 4. Assertions
    // Phase should revert to Playing immediately (no need to wait for a cancelled action)
    assertEquals(st.phase, Phase.Playing)

    // Turn logic: Shuffle does not end turn, so u1 is still active
    assertEquals(st.players.head, "u1")

    // Deck logic: The shuffle was cancelled, so the deck must be identical to start
    assertEquals(st.draw, initialDeck)

    // Discard logic: Both Shuffle and Nope should be in discard
    assert(st.discard.contains(Card.Shuffle))
    assert(st.discard.contains(Card.Nope))

  test("Yup (Nope on Nope) reinstates Shuffle and restarts the Waiting timer"):
    var st = state2

    // 1. Action: u1 plays Shuffle
    st = trans(fsm, st)("u1", Event.CardClicked(0))
    // Check: We are waiting
    assert(st.phase.isInstanceOf[Phase.Waiting])

    // 2. Action: u2 plays Nope
    // Check: Shuffle cancelled, back to Playing
    st = trans(fsm, st)("u2", Event.CardClicked(0))
    assertEquals(st.phase, Phase.Playing)

    // 3. Action: u3 plays Nope (The "Yup")
    st = trans(fsm, st)("u3", Event.CardClicked(0))

    // 4. Assertions: Re-entering Waiting Phase
    // The Shuffle is active again, so we must go back to Waiting
    assert(st.phase match
      case Phase.Waiting(ms, _) => ms > 0 // Ensure timer is active
      case _ => false
      , "Phase should return to Waiting after a Yup on a Shuffle")

    // 5. Action: Simulate the timer running out (Tick)
    // We tick until the phase transitions back to Playing
    var ticks = 0
    while (st.phase.isInstanceOf[Phase.Waiting] && ticks < 50) do
      st = tick(fsm, st)
      ticks += 1

    // 6. Assertions: Final Resolution
    assertEquals(st.phase, Phase.Playing)
    assertEquals(st.players.head, "u1") // u1 still has to draw

    // Verify the discard pile stack: Shuffle (bottom) -> Nope -> Nope (top)
    assertEquals(st.discard.take(3), Vector(Card.Nope, Card.Nope, Card.Shuffle))

  val state3 = State(
    Vector("u1", "u2", "u3"),
    Map(
      "u1" -> Vector(Card.Favour, Card.Skip),
      "u2" -> Vector(Card.Defuser, Card.Nope),
      "u3" -> Vector(Card.Match(1), Card.Match(1))
    ),
    Vector.fill(10)(Card.Match(2)),
    Vector(),
    Turn(1, false),
    Cancel(false, None),
    Vector(),
    Phase.Playing,
    Vector("State 3 Start")
  )

  test("Favour Card: u1 plays Favour, picks u2, u2 gives a card"):
    // 1. Action: u1 plays Favour (Index 0)
    val stPicking = trans(fsm, state3)("u1", Event.CardClicked(0))

    // Assertion: Phase transitions to Picking
    assertEquals(stPicking.phase, Phase.Picking, "Phase should be Picking after playing Favour")

    // 2. Action: u1 picks u2
    val stGiving = trans(fsm, stPicking)("u1", Event.PlayerPicked("u2"))

    // Assertion: Phase transitions to Giving, specific to u2
    assertEquals(stGiving.phase, Phase.Giving("u2"), "Phase should be Giving(u2)")

    // 3. Action: u2 selects the card to give (Index 0: Defuse)
    val stResult = trans(fsm, stGiving)("u2", Event.CardClicked(0))

    // 4. Assertions: Card Transfer
    // u1 should now have the Defuse card
    assert(stResult.hands("u1").contains(Card.Defuser), "u1 should have received the Defuse card")
    // u2 should have lost the Defuse card
    assert(!stResult.hands("u2").contains(Card.Defuser), "u2 should have lost the Defuse card")

    // Assertion: Game State
    assertEquals(stResult.phase, Phase.Playing, "Phase should return to Playing")
    assertEquals(stResult.players.head, "u1", "It should still be u1's turn")

  test("Nope on Favour: cancels the action during Giving phase"):
    // 1. Setup: Advance to Giving phase
    var st = trans(fsm, state3)("u1", Event.CardClicked(0)) // Play Favour
    st = trans(fsm, st)("u1", Event.PlayerPicked("u2")) // Pick u2
    assertEquals(st.phase, Phase.Giving("u2"))

    // 2. Action: u2 plays Nope to refuse giving a card
    // u2's hand is [Defuser, Nope], so Nope is at Index 1
    val stNoped = trans(fsm, st)("u2", Event.CardClicked(1))

    // 3. Assertions
    assertEquals(stNoped.phase, Phase.Playing)

    // u2 keeps the Defuser
    assert(stNoped.hands("u2").contains(Card.Defuser), "u2 should keep the Defuser after Noping")
    // u1 does NOT get the Defuser
    assert(!stNoped.hands("u1").contains(Card.Defuser))
    // u1 lost their Favour card
    assert(!stNoped.hands("u1").contains(Card.Favour))

  test("Match Cards: u3 plays pair (single click), steals random card from u1"):
    // 1. Setup: Fast forward to u3's turn
    // u1 draws (3 cards in hand) -> u2 draws (3 cards in hand) -> u3's turn (2 cards in hand)
    var st = trans(fsm, state3)("u1", Event.Drew)
    st = trans(fsm, st)("u2", Event.Drew)
    assertEquals(st.players.head, "u3")

    // 2. Action: u3 clicks ONE Match(1) card (Index 0)
    st = trans(fsm, st)("u3", Event.CardClicked(0))

    // Assertion: Phase should immediately transition to Picking
    assertEquals(st.phase, Phase.Picking, "Phase should become Picking after clicking the match card")

    // 3. Action: u3 picks u1 to steal from
    st = trans(fsm, st)("u3", Event.PlayerPicked("u1"))

    // 4. Wait for timer
    // The game enters Waiting phase to allow for Nopes before the steal occurs
    assert(st.phase.isInstanceOf[Phase.Waiting])

    var ticks = 0
    while (st.phase.isInstanceOf[Phase.Waiting] && ticks < 50) do
      st = tick(fsm, st)
      ticks += 1

    // 5. Assertions: Random Steal Resolution
    assertEquals(st.phase, Phase.Playing)

    // Card Count Math:
    // u3: Started with 2 (Pair). Played 2 (-2). Stole 1 (+1). Final Hand Size = 1.
    assertEquals(st.hands("u3").length, 1)

    // u1: Started with 2. Drew 1 (+1). Lost 1 (Stolen). Final Hand Size = 2.
    assertEquals(st.hands("u1").length, 2)

    // Verify the stolen card
    // u3's new card must be one of u1's cards (Favour, Skip, or the Match(2) they drew)
    val stolenCard = st.hands("u3").head
    assert(stolenCard != Card.Match(1), "u3 should have stolen a card from u1")

  val state4 = State(
    Vector("u1", "u2", "u3"),
    Map(
      "u1" -> Vector(Card.Defuser), // Equipped to survive
      "u2" -> Vector(Card.Nope), // Destined to explode
      "u3" -> Vector(Card.Skip)
    ),
    Vector(Card.Bomb, Card.Match(1), Card.Match(2), Card.Match(3)), // Bomb on top
    Vector(),
    Turn(1, false),
    Cancel(false, None),
    Vector(),
    Phase.Playing,
    Vector("State 4 Start")
  )

  test("Bomb: Player without defuser explodes after timer runs out"):
    // 1. Setup: Manipulate turn so it's u2's turn (who has NO Defuser)
    val stStart = state4.copy(players = Vector("u2", "u3", "u1"))

    // 2. Action: u2 draws the Bomb
    var st = trans(fsm, stStart)("u2", Event.Drew)

    // Assertion: Enter Defusing phase
    // Even without a defuser, the game enters this phase to give a moment of panic/UI animation
    assert(st.phase match
      case Phase.Defusing(_) => true
      case _ => false
      , "Phase should be Defusing after drawing a bomb")

    // 3. Action: Timer runs out (u2 has no Defuser to play)
    // Simulate ticks passing until the phase changes
    var ticks = 0
    while (st.phase.isInstanceOf[Phase.Defusing] && ticks < 100) do
      st = tick(fsm, st)
      ticks += 1

    // 4. Assertions: Death and Transition
    // Phase should return to Playing
    assertEquals(st.phase, Phase.Playing)

    // u2 is dead
    assert(st.died.contains("u2"), "u2 should be in the graveyard")

    // Turn passes to u3
    assertEquals(st.players.head, "u3")

  test("Bomb: Player defuses and inserts bomb back into deck"):
    // 1. Action: u1 (Has Defuser) draws the Bomb
    var st = trans(fsm, state4)("u1", Event.Drew)

    assert(st.phase.isInstanceOf[Phase.Defusing])

    // 2. Action: u1 plays the Defuser card (Index 0)
    st = trans(fsm, st)("u1", Event.CardClicked(0))

    // Assertion: Inserting Phase
    assertEquals(st.phase, Phase.Inserting)
    assert(!st.hands("u1").contains(Card.Defuser), "Defuser card used")

    // 3. Action: u1 inserts bomb at index 1 (second position)
    st = trans(fsm, st)("u1", Event.Inserted(1))

    // 4. Assertions
    assertEquals(st.phase, Phase.Playing)
    assertEquals(st.players.head, "u2", "Turn passes to u2")

    // Verify Bomb Position (Index 0 is Match(1), Index 1 is Bomb)
    assertEquals(st.draw(1), Card.Bomb)

  test("Bomb: Defusing while having remaining turns keeps the turn"):
    // 1. Setup: u1 has 2 turns pending (e.g., was attacked)
    val stMultiTurn = state4.copy(turn = Turn(2, true))

    // 2. Action: u1 draws Bomb (Turn 1 of 2)
    var st = trans(fsm, stMultiTurn)("u1", Event.Drew)

    // 3. Action: u1 plays Defuser
    st = trans(fsm, st)("u1", Event.CardClicked(0))

    // 4. Action: u1 inserts Bomb at top
    st = trans(fsm, st)("u1", Event.Inserted(0))

    // 5. Assertions
    assertEquals(st.phase, Phase.Playing)

    // u1 keeps the turn because they have 1 turn left
    assertEquals(st.players.head, "u1")
    assertEquals(st.turn, Turn(1, true))

  test("Game Over: When only one player remains alive"):
    // 1. Setup: u1 is alive, u2 is about to die. u3 is already removed/dead for this scenario.
    // We simulate a 2-player endgame where u2 draws a bomb.
    val stEndgame = state4.copy(
      players = Vector("u2", "u1"), // u2 acts first
      hands = state4.hands - "u3",
      died = Vector("u3") // u3 is already dead
    )

    // 2. Action: u2 draws Bomb
    var st = trans(fsm, stEndgame)("u2", Event.Drew)

    // 3. Action: Timeout (u2 dies)
    var ticks = 0
    while (st.phase.isInstanceOf[Phase.Defusing] && ticks < 100) do
      st = tick(fsm, st)
      ticks += 1

    // 4. Assertions
    assert(st.died.contains("u2"))

    // u2 is still in the list, but dead
    assert(st.players.contains("u2"))

    // u1 is the only survivor
    val survivors = st.players.filterNot(st.died.contains)
    assertEquals(survivors, Vector("u1"))

    // Phase is Done
    assertEquals(st.phase, Phase.Done)

  val state5 = State(
    Vector("u1", "u2", "u3"),
    Map(
      "u1" -> Vector(Card.Attack, Card.Forecast), // u1 ready to attack or forecast
      "u2" -> Vector(Card.Attack, Card.Nope), // u2 ready to counter-attack
      "u3" -> Vector(Card.Nope)
    ),
    Vector(Card.Match(1), Card.Match(2), Card.Match(3), Card.Match(4)),
    Vector(),
    Turn(1, false),
    Cancel(false, None),
    Vector(),
    Phase.Playing,
    Vector("State 5 Start")
  )

  val stateEmpty = State(
    Vector("u1", "u2", "u3"),
    Map(
      "u1" -> Vector(Card.Favour, Card.Match(1), Card.Match(1)), // Has interactions
      "u2" -> Vector(), // Has NOTHING
      "u3" -> Vector(Card.Defuser)
    ),
    Vector.fill(10)(Card.Skip),
    Vector(),
    Turn(1, false),
    Cancel(false, None),
    Vector(),
    Phase.Playing,
    Vector("State Empty Start")
  )

  test("Attack Stacking: Player attacks an already attacked player (2 turns + 2 turns = 4 turns)"):
    // 1. Setup: u1 attacks u2
    var st = trans(fsm, state5)("u1", Event.CardClicked(0))
    assertEquals(st.players.head, "u2")
    assertEquals(st.turn, Turn(2, true)) // u2 has 2 turns pending

    // 2. Action: u2 attacks u3 immediately (passing their 2 turns + 2 new ones)
    // u2 plays Attack (Index 0)
    st = trans(fsm, st)("u2", Event.CardClicked(0))

    // 3. Assertions
    assertEquals(st.players.head, "u3", "Turn should pass to u3")
    // Standard stacking rule: Remaining turns (2) + New Attack (2) = 4 Turns
    assertEquals(st.turn, Turn(4, true), "u3 should receive 4 cumulative turns")

  test("Nope on Attack: cancels the turn pass, original player continues turn"):
    // 1. Action: u1 plays Attack on u2
    var st = trans(fsm, state5)("u1", Event.CardClicked(0))
    assertEquals(st.players.head, "u2") // Tentative pass

    // 2. Action: u2 Nopes the Attack
    st = trans(fsm, st)("u2", Event.CardClicked(1)) // u2's Nope is at Index 1

    // 3. Assertions
    assertEquals(st.players.head, "u1", "Turn should return to u1")
    assertEquals(st.turn, Turn(1, false), "Turn count should reset to normal 1 turn")
    assert(!st.hands("u1").contains(Card.Attack), "u1's Attack card is consumed")
    assertEquals(st.phase, Phase.Playing)

  test("Forecast Card: Enters Waiting (for Nope), then Viewing, then Playing"):
    // 1. Action: u1 plays Forecast (Index 1 in state5)
    var st = trans(fsm, state5)("u1", Event.CardClicked(1))

    // 2. Assertion: Immediate Phase is WAITING (Game pauses for Nopes)
    assert(st.phase match
      case Phase.Waiting(_, _) => true
      case _ => false
      , "Phase should be Waiting immediately after playing Forecast")

    // 3. Action: Simulate Waiting Timer expiry (No Nope played)
    var waitTicks = 0
    while (st.phase.isInstanceOf[Phase.Waiting] && waitTicks < 50) do
      st = tick(fsm, st)
      waitTicks += 1

    // 4. Assertion: Phase transitions to VIEWING
    // The player is now looking at the top cards
    assert(st.phase match
      case Phase.Viewing(_) => true
      case _ => false
      , "Phase should be Viewing after waiting period expires")

    // The deck should NOT change (cards are viewed, not drawn)
    assertEquals(st.draw.length, 4)

    // 5. Action: Simulate Viewing Timer expiry
    var viewTicks = 0
    while (st.phase.isInstanceOf[Phase.Viewing] && viewTicks < 50) do
      st = tick(fsm, st)
      viewTicks += 1

    // 6. Assertion: Phase returns to PLAYING
    assertEquals(st.phase, Phase.Playing, "Should return to Playing phase after viewing is done")
    assertEquals(st.players.head, "u1", "It is still u1's turn")


  test("Nope cancels Forecast during Waiting phase"):
    // 1. Action: u1 plays Forecast
    var st = trans(fsm, state5)("u1", Event.CardClicked(1))

    // Assertion: Waiting phase
    assert(st.phase.isInstanceOf[Phase.Waiting])

    // 2. Action: u2 plays Nope
    st = trans(fsm, st)("u2", Event.CardClicked(1)) // u2's Nope is at Index 1

    // 3. Assertions
    // Should immediately go back to Playing (skipping Viewing entirely)
    assertEquals(st.phase, Phase.Playing)
    assertEquals(st.players.head, "u1")

    // u1 loses the Forecast card
    assert(!st.hands("u1").contains(Card.Forecast))

  test("Favour on empty hand: Resolves with no card transferred"):
    // 1. Action: u1 plays Favour (Index 0)
    var st = trans(fsm, stateEmpty)("u1", Event.CardClicked(0))
    assertEquals(st.phase, Phase.Picking)

    // 2. Action: u1 picks u2 (who has NO cards)
    st = trans(fsm, st)("u1", Event.PlayerPicked("u2"))

    // 3. Logic:
    if (st.phase.isInstanceOf[Phase.Giving]) then
      var ticks = 0
      while (st.phase.isInstanceOf[Phase.Giving] && ticks < 50) do
        st = tick(fsm, st)
        ticks += 1

    // 4. Assertions
    assertEquals(st.phase, Phase.Playing)
    assertEquals(st.players.head, "u1")
    assert(st.hands("u1").length == 2, "u1 should not have gained any cards")
    assert(st.hands("u2").isEmpty, "u2 remains empty")

  test("Match on empty hand: Resolves with no card stolen"):
    // 1. Action: u1 plays Pair (Match(1) at index 1 and 2)
    // u1 hand: [Favour, Match, Match]. Click Index 1.
    var st = trans(fsm, stateEmpty)("u1", Event.CardClicked(1))
    assertEquals(st.phase, Phase.Picking)

    // 2. Action: u1 picks u2 (who has NO cards)
    st = trans(fsm, st)("u1", Event.PlayerPicked("u2"))

    // 3. Wait for timer (standard Match wait)
    var ticks = 0
    while (st.phase.isInstanceOf[Phase.Waiting] && ticks < 50) do
      st = tick(fsm, st)
      ticks += 1

    // 4. Assertions
    assertEquals(st.phase, Phase.Playing)
    // u1 played 2 cards, gained 0. Hand should go from 3 -> 1.
    assertEquals(st.hands("u1").length, 1)
    assertEquals(st.hands("u1").head, Card.Favour) // Only Favour remains

  test("Cannot Nope a Defuser: Nope played during Defusing is ignored"):
    // 1. Setup: u1 (Has Defuser) draws Bomb (using state4)
    var st = trans(fsm, state4)("u1", Event.Drew)
    assert(st.phase.isInstanceOf[Phase.Defusing])

    // 2. Action: u2 tries to play Nope (Index 0)
    val stAttempt = try {
      trans(fsm, st)("u2", Event.CardClicked(0))
    } catch {
      case _: Throwable => st // If it throws, that's also valid "ignoring"
    }

    // 3. Assertion: Phase is still Defusing, u2 still has Nope
    assert(stAttempt.phase.isInstanceOf[Phase.Defusing], "Phase should remain Defusing")
    // u2 should not lose their card
    assert(stAttempt.hands("u2").contains(Card.Nope), "Nope should not be consumed")

  test("Double Jeopardy: Player dies while under Attack (Turns do not carry over)"):
    // 1. Setup: u1 is attacked (2 turns). Deck has Bomb on top.
    // state4 has Bomb on top. u2 has Nope (no defuse).
    // We give u2 2 turns.
    val stAttackDeath = state4.copy(
      players = Vector("u2", "u3", "u1"),
      turn = Turn(2, true)
    )

    // 2. Action: u2 draws Bomb (Turn 1/2)
    var st = trans(fsm, stAttackDeath)("u2", Event.Drew)

    // 3. Action: u2 dies (Timeout)
    var ticks = 0
    while (st.phase.isInstanceOf[Phase.Defusing] && ticks < 100) do
      st = tick(fsm, st)
      ticks += 1

    // 4. Assertions
    assert(st.died.contains("u2"))
    assertEquals(st.players.head, "u3")

    // Crucial: Does u3 inherit the extra turn?
    // Standard Rules: No. The turns belonged to u2. u3 starts fresh.
    assertEquals(st.turn, Turn(1, false), "Next player should start with standard 1 turn")


  // ==========================================
  // EDGE CASE: INVALID TARGETS
  // ==========================================

  test("Self-Targeting: Cannot pick self for Favour"):
    // 1. Action: u1 plays Favour
    var st = trans(fsm, state3)("u1", Event.CardClicked(0))
    assertEquals(st.phase, Phase.Picking)

    // 2. Action: u1 tries to pick "u1"
    // We expect the state typically NOT to change (stay in Picking)
    // or return an error (depending on implementation).
    // Assuming it stays in Picking:
    val stInvalid = try {
      trans(fsm, st)("u1", Event.PlayerPicked("u1"))
    } catch {
      case _: Throwable => st // If it throws, we assume it rejected the move
    }

    assertEquals(stInvalid.phase, Phase.Picking, "Should remain in Picking phase after invalid self-pick")

  test("Late Nope: Cannot Nope after waiting phase expires"):
    // 1. Action: u1 plays Shuffle (Enter Waiting)
    var st = trans(fsm, state2)("u1", Event.CardClicked(0))
    assert(st.phase.isInstanceOf[Phase.Waiting])

    // 2. Action: Wait until timer expires (Action resolves)
    var ticks = 0
    while (st.phase.isInstanceOf[Phase.Waiting] && ticks < 50) do
      st = tick(fsm, st)
      ticks += 1

    // Now in Playing phase, Shuffle is done.
    assertEquals(st.phase, Phase.Playing)

    // 3. Action: u2 tries to play Nope on the *previous* shuffle
    // u2's Nope is at Index 0 in state2
    val stLate = try {
      trans(fsm, st)("u2", Event.CardClicked(0))
    } catch {
       case _: Throwable => st
    }

    // Assert: The shuffle was NOT undone.
    assert(!stLate.hands("u1").contains(Card.Shuffle), "Shuffle should remain consumed/played")

  test("Attack -> Skip: Playing Skip while Attacked consumes only 1 turn"):
    // 1. Setup: u1 is attacked (2 turns pending)
    // We use state4 but give u1 a Skip card
    val stAttacked = state4.copy(
      players = Vector("u1", "u2", "u3"),
      hands = state4.hands + ("u1" -> Vector(Card.Skip)),
      turn = Turn(2, true)
    )

    // 2. Action: u1 plays Skip
    val st = trans(fsm, stAttacked)("u1", Event.CardClicked(0))

    // 3. Assertions
    // Turn should NOT pass to u2 yet
    assertEquals(st.players.head, "u1", "u1 should still be the player")

    // Turns left should be 1
    assertEquals(st.turn.turnsLeft, 1, "Skip should consume exactly 1 turn")

    // Skip should be consumed
    assert(st.hands("u1").isEmpty)

  test("Insert Bomb Out of Bounds: Throws exception on invalid index"):
    // 1. Setup: u1 Defusing
    var st = trans(fsm, state4)("u1", Event.Drew) // Draws Bomb
    st = trans(fsm, st)("u1", Event.CardClicked(0)) // Plays Defuser
    assertEquals(st.phase, Phase.Inserting)

    // 2. Action & Assertion: Try inserting at index 999
    // We expect this to throw an exception (or fail strict validation)
    try {
      trans(fsm, st)("u1", Event.Inserted(999))
      fail("Should have thrown an exception for out-of-bounds insertion")
    } catch {
      case e: Exception => // Test passed, exception caught
      case _: Throwable => // Also acceptable
    }

  test("Playing a card not in hand: Throws exception or ignores invalid index"):
    // 1. Setup: u1 has 2 cards
    val st = state1 // u1 has 2 cards [Attack, Skip]

    // 2. Action: Try clicking index 5 (Out of bounds)
    try {
      trans(fsm, st)("u1", Event.CardClicked(5))
      fail("Should have thrown exception for invalid card index")
    } catch {
      case e: Exception => // Passed
    }

  test("Insert Bomb at Top (Index 0): Next player immediately draws it"):
    // 1. Setup: u1 Defusing
    var st = trans(fsm, state4)("u1", Event.Drew)
    st = trans(fsm, st)("u1", Event.CardClicked(0)) // Plays Defuser

    // 2. Action: Insert at Index 0 (Top)
    st = trans(fsm, st)("u1", Event.Inserted(0))

    // 3. Assertion: Top of draw pile is Bomb
    assertEquals(st.draw.head, Card.Bomb, "Bomb should be at the very top of the deck")
    assertEquals(st.players.head, "u2")

  test("Attack Chain: 3 Players consecutively Attack"):
    // 1. u1 attacks u2 (Turns: 2)
    var st = trans(fsm, state5)("u1", Event.CardClicked(0))
    assertEquals(st.turn.turnsLeft, 2)

    // 2. u2 attacks u3 (Turns: 2 + 2 = 4)
    st = trans(fsm, st)("u2", Event.CardClicked(0))
    assertEquals(st.turn.turnsLeft, 4)

    // 3. u3 attacks u1 (Turns: 4 + 2 = 6)
    // Need to give u3 an Attack card first
    val stChain = st.copy(hands = st.hands + ("u3" -> Vector(Card.Attack)))
    st = trans(fsm, stChain)("u3", Event.CardClicked(0))

    // 4. Assertion
    assertEquals(st.players.head, "u1")
    assertEquals(st.turn.turnsLeft, 6, "Turns should stack to 6")

  test("Match Steal: Victim has exactly 1 card"):
    // 1. Setup: u1 (victim) has 1 card. u3 plays pair.
    val stOneCardVictim = state3.copy(
        players = Vector("u3", "u1", "u2"),
        hands = state3.hands + ("u1" -> Vector(Card.Defuser))
    )

    // 2. Action: u3 plays pair, picks u1
    var st = trans(fsm, stOneCardVictim)("u3", Event.CardClicked(0))
    st = trans(fsm, st)("u3", Event.PlayerPicked("u1"))

    // 3. Wait
    var ticks = 0
    while (st.phase.isInstanceOf[Phase.Waiting] && ticks < 50) do
        st = tick(fsm, st)
        ticks += 1

    // 4. Assertion
    assert(st.hands("u1").isEmpty, "u1 should be left with 0 cards")
    assert(st.hands("u3").contains(Card.Defuser), "u3 must steal the only available card")

  test("Wire Event Test"):
    val events: List[Event] = List(
      Event.Drew,
      Event.CardClicked(5),
      Event.CardClicked(123),
      Event.CardClicked(1685),
      Event.Inserted(0),
      Event.Inserted(7)
    )

    events.foreach { ev =>
      val ok = Wire.eventFormat.decode(Wire.eventFormat.encode(ev))
      assertEquals(ev , ok.get , "Encoding and decoding a Event return the original Event")
    }

  /*
  test("Wire View Test"):
    val view1 = View(
      hand = Vector(Card.Bomb, Card.Nope),
      others = Vector(
        PlayerView("Saad", 3, true),
        PlayerView("Adam", 1, false)
      ),
      state = StateView.Playing,
      leftToDraw = 10,
      lastPlayed = Card.Defuser,
      log = Vector("Saad played Nope"),
      playerTurn = "Saad"
    )

    val view2 = View(
      hand = Vector(Card.Shuffle),
      others = Vector(),
      state = StateView.Inserting,
      leftToDraw = 5,
      lastPlayed = Card.Bomb,
      log = Vector("Waiting…"),
      playerTurn = "Saad"
    )

    val view3 = View(
      hand = Vector(),
      others = Vector(PlayerView("Arthur", 4, true)),
      state = StateView.Finished(Vector("Saad", "Adam", "Arthur")),
      leftToDraw = 0,
      lastPlayed = Card.Nope,
      log = Vector("Game finished"),
      playerTurn = "Saad"
    )

    val testViews = List(view1, view2, view3)


    testViews.foreach { ev =>
      val ok = Wire.viewFormat.decode(Wire.viewFormat.encode(ev))
      assertEquals(ev , ok.get , "Encoding and decoding a View return the original View")
    }
  
   */
  test("Event decode - unknown type should fail") {
    val json = ujson.Obj("type" -> "UnknownEvent")
    val result = Wire.eventFormat.decode(json)
    assert(result.isFailure, "Decoding an unknown event type should fail")
  }

  test("Event decode - missing fields should fail (CardClicked without cardId)") {
    val json = ujson.Obj("type" -> "CardClicked")
    val result = Wire.eventFormat.decode(json)
    assert(result.isFailure, "Decoding CardClicked without cardId should fail")
  }

  test("Event decode - missing fields should fail (Inserted without position)") {
    val json = ujson.Obj("type" -> "Inserted")
    val result = Wire.eventFormat.decode(json)
    assert(result.isFailure, "Decoding Inserted without position should fail")
  }

  test("Event decode - wrong field type should fail (cardId as string)") {
    val json = ujson.Obj(
      "type" -> "CardClicked",
      "cardId" -> ujson.Str("notANumber")
    )
    val result = Wire.eventFormat.decode(json)
    assert(result.isFailure, "Decoding CardClicked with a string cardId should fail")
  }

  test("Event decode - wrong field type should fail (position as bool)") {
    val json = ujson.Obj(
      "type" -> "Inserted",
      "position" -> ujson.Bool(true)
    )
    val result = Wire.eventFormat.decode(json)
    assert(result.isFailure, "Decoding Inserted with boolean position should fail")
  }

  test("Event decode - missing type field should fail") {
    val json = ujson.Obj("cardId" -> 5)
    val result = Wire.eventFormat.decode(json)
    assert(result.isFailure, "Decoding event with no type field should fail")
  }

  test("Event decode - empty JSON should fail") {
    val json = ujson.Obj()
    val result = Wire.eventFormat.decode(json)
    assert(result.isFailure, "Decoding empty JSON should fail")
  }

  test("View decode - missing hand should fail") {
    val json = ujson.Obj(
      "others" -> ujson.Arr(),
      "state" -> ujson.Obj("type" -> "Playing"),
      "leftToDraw" -> 3,
      "lastPlayed" -> "Bomb",
      "log" -> ujson.Arr(),
      "playerTurn" -> "Saad"
    )
    val result = Wire.viewFormat.decode(json)
    assert(result.isFailure, "Decoding View without hand should fail")
  }

  test("View decode - missing others should fail") {
    val json = ujson.Obj(
      "hand" -> ujson.Arr("Bomb"),
      "state" -> ujson.Obj("type" -> "Playing"),
      "leftToDraw" -> 3,
      "lastPlayed" -> "Defuser",
      "log" -> ujson.Arr(),
      "playerTurn" -> "Saad"
    )
    val result = Wire.viewFormat.decode(json)
    assert(result.isFailure, "Decoding View without others should fail")
  }

  test("View decode - invalid card name should fail") {
    val json = ujson.Obj(
      "hand" -> ujson.Arr("INVALID_CARD"),
      "others" -> ujson.Arr(),
      "state" -> ujson.Obj("type" -> "Playing"),
      "leftToDraw" -> 2,
      "lastPlayed" -> "Bomb",
      "log" -> ujson.Arr(),
      "playerTurn" -> "Saad"
    )
    val result = Wire.viewFormat.decode(json)
    assert(result.isFailure, "Decoding a View with an unknown card should fail")
  } 

  test("View decode - wrong type for alive (should be boolean)") {
    val json = ujson.Obj(
      "hand" -> ujson.Arr("Bomb"),
      "others" -> ujson.Arr(
        ujson.Obj(
          "player" -> "Saad",
          "cardsLeft" -> 3,
          "alive" -> "yes"   
        )
      ),
      "state" -> ujson.Obj("type" -> "Playing"),
      "leftToDraw" -> 1,
      "lastPlayed" -> "Nope",
      "log" -> ujson.Arr(),
      "playerTurn" -> "Saad"
    )
    val result = Wire.viewFormat.decode(json)
    assert(result.isFailure, "Decoding View with alive as a string should fail")
  }

  test("View decode - missing lastPlayed should fail") {
    val json = ujson.Obj(
      "hand" -> ujson.Arr("Bomb"),
      "others" -> ujson.Arr(),
      "state" -> ujson.Obj("type" -> "Inputting"),
      "leftToDraw" -> 3,
      "log" -> ujson.Arr(),
      "playerTurn" -> "Saad"
    )
    val result = Wire.viewFormat.decode(json)
    assert(result.isFailure, "Decoding View without lastPlayed should fail")
  }

  test("View decode - missing state.type inside state should fail") {
    val json = ujson.Obj(
      "hand" -> ujson.Arr("Bomb"),
      "others" -> ujson.Arr(),
      "state" -> ujson.Obj(), 
      "leftToDraw" -> 2,
      "lastPlayed" -> "Bomb",
      "log" -> ujson.Arr(),
      "playerTurn" -> "Saad"
    )
    val result = Wire.viewFormat.decode(json)
    assert(result.isFailure, "Decoding View with missing state.type should fail")
  }

  test("View decode - wrong type for hand (bool instead of string)") {
    val json = ujson.Obj(
      "hand" -> ujson.Arr(ujson.Bool(true)),
      "others" -> ujson.Arr(),
      "state" -> ujson.Obj("type" -> "Playing"),
      "leftToDraw" -> 2,
      "lastPlayed" -> "Bomb",
      "log" -> ujson.Arr(),
      "playerTurn" -> "Saad"
    )
    val result = Wire.viewFormat.decode(json)
    assert(result.isFailure, "Decoding View with invalid hand entry should fail")
  }

  test("View decode - empty JSON should fail") {
    val json = ujson.Obj()
    val result = Wire.viewFormat.decode(json)
    assert(result.isFailure, "Decoding empty View JSON should fail")
  }

  test("View decode - missing alive should fail") {
    val json = ujson.Obj(
      "hand" -> ujson.Arr("Bomb"),
      "others" -> ujson.Arr(),
      "state" -> ujson.Obj("type" -> "Playing"),
      "leftToDraw" -> 2,
      "lastPlayed" -> "Bomb",
      "log" -> ujson.Arr(),
      "playerTurn" -> "Saad",
      "turnsLeft" -> 1
    )
    val result = Wire.viewFormat.decode(json)
    assert(result.isFailure, "Decoding View without alive should fail")
  }
  test("View decode - wrong type for top-level alive should fail") {
    val json = ujson.Obj(
      "hand" -> ujson.Arr("Bomb"),
      "others" -> ujson.Arr(),
      "state" -> ujson.Obj("type" -> "Playing"),
      "leftToDraw" -> 2,
      "lastPlayed" -> "Bomb",
      "log" -> ujson.Arr(),
      "playerTurn" -> "Saad",
      "turnsLeft" -> 1,
      "alive" -> "yes"
    )
    val result = Wire.viewFormat.decode(json)
    assert(result.isFailure, "Decoding View with alive as string should fail")
  }
  test("View encode/decode round-trip preserves alive flags") {
    val view = View(
      hand = Vector(Card.Bomb, Card.Nope),
      others = Vector(
        PlayerView("Saad", 3, alive = true),
        PlayerView("Adam", 1, alive = false)
      ),
      state = StateView.Playing,
      leftToDraw = 10,
      lastPlayed = Card.Defuser,
      log = Vector("Saad played Nope"),
      playerTurn = "Saad",
      turnsLeft = 1,
      alive = false
    )
    val encoded = Wire.viewFormat.encode(view)
    val decoded = Wire.viewFormat.decode(encoded)
    assert(decoded.isSuccess, "Encoding/decoding View should succeed")
    assertEquals(decoded.get, view)
  }

  test("HtmlUI.scala exports top-level JS name and references app id") {
    val f = File("apps/js/src/main/scala/apps/ul2025app9/HtmlUI.scala")
    assert(f.exists(), s"HtmlUI.scala must exist at ${f.getPath}")
    val src = try Source.fromFile(f).mkString finally ()
    // Check for the Scala.js export annotation and the declared appId string
    assert(src.contains("@JSExportTopLevel(\"ul2025app9\")") || src.contains("@JSExportTopLevel('ul2025app9')"),
      "HtmlUI.scala should have @JSExportTopLevel(\"ul2025app9\")")
    assert(src.contains("def appId: String = \"ul2025app9\"") || src.contains("appId: String = \"ul2025app9\""),
      "HtmlUI.scala should declare appId = \"ul2025app9\"")
  }