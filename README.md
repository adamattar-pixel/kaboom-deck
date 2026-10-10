# Kaboom Deck: a multiplayer card game webapp

**A real-time, 2 to 4 player browser card game written entirely in Scala 3, with the game server on the JVM and the client compiled to JavaScript with Scala.js.**

Built as the "Unguided Lab" project of **CS-214 (Software Construction), EPFL, 2025**, by a team of three. It is an unofficial, educational recreation of a classic bomb-defusing card game.

## The game

Players take turns drawing cards. Draw a bomb and you explode, unless you hold a defuser. The last player alive wins.

- On your turn you may play any number of cards, then you must end the turn by drawing (or by playing Attack or Skip).
- **Bomb:** drawing one eliminates you unless you play a **Defuser**, which lets you secretly reinsert the bomb anywhere in the draw pile.
- **Attack:** ends your turn without drawing and forces the next player to take two turns (attacks stack).
- **Skip:** ends your turn without drawing.
- **Favour:** pick a player, who must give you a card of their choice.
- **Nope:** cancels any action except a bomb or a defuser, and can itself be Noped.
- **Shuffle:** shuffles the draw pile.
- **Forecast:** secretly shows you the next three cards.
- **Match:** play a pair to steal a random card from a player.

The UI shows opponents' card counts, a live action log, countdowns for the Nope window and for defusing, and a final scoreboard with the elimination order.

## Architecture

The project is an sbt cross-project (`JVM`, `JS`, `shared`) built around a pure state machine.

```
apps/
  shared/   Types and wire format used by both sides
            types.scala   Card, Phase, State, Event, View, StateView
            Wire.scala    JSON encoding and decoding of events and views (ujson)
  jvm/      Server side
            Logic.scala   The game: a clock-driven state machine
            Tests.scala   Test suite
  js/       Client side (Scala.js)
            HtmlUI.scala  Rendering with Scalatags, user input, notifications
mockups/    Hand-drawn UI mockups and the draw.io finite state machine
```

Key design points:

- **Single source of truth on the server.** `Logic` extends `ClockDrivenStateMachine[Event, State, View]`. Its `transition` function takes the current `State`, the acting user, and either a user `Event` or a clock `Tick`, and returns the resulting actions. Timed phases (Nope window, defusing, forecast viewing) are driven by ticks.
- **Explicit game phases.** The state machine moves through `Playing`, `Paused`, `Waiting` (Nope window), `Defusing`, `Viewing`, `Picking`, `Giving`, `Inserting` and `Done`. The finite state machine is drawn in [`mockups/fsm.drawio`](mockups/fsm.drawio).
- **Per-player views.** `project` turns the full `State` into a `View` for one player: your own hand in full, but only the card counts of the others, and the contents of the draw pile are never sent to clients.
- **Immutable, testable logic.** The state is a set of immutable case classes and enums, so every transition can be tested without a browser or a network. The test suite covers initial setup, turn flow, each card's effect, cancellation chains, eliminations, and the wire format round trip.
- **Shared types.** Because the client and the server are both Scala, the same `Event` and `View` types are used on both sides and only the JSON codecs in `Wire.scala` sit in between.

## Tech stack

Scala 3.7.2, sbt 1.11.6, Scala.js 1.20.1, ujson, Scalatags, scala-js-dom, WebSockets (through the course framework), toolkit-test.

## Running it

> **Heads up:** the project depends on the CS-214 course framework (`webapp-lib`, hosted on EPFL's GitLab). sbt fetches it automatically from its public Git repository the first time you build, so the first build needs network access.

From the project root:

```bash
sbt --client
```

then, inside the sbt shell:

```bash
run
```

Open `http://localhost:8080` in a browser, select the app, add 2 to 4 players, and join as yourself. Share the join code with friends.

Other commands: `test` runs the test suite, `packageForDeployment` builds a single fat jar.

## Team

- **Arthur**: game logic, back-end, integration
- **Adam Attar** and **Saad Amraoui**: front-end, UI, testing

## Credits

The game concept comes from *Exploding Kittens* by Exploding Kittens, Inc. This project is an unofficial educational recreation and is not affiliated with or endorsed by them. Card artwork was generated with AI tools for this project. The course framework is provided by the CS-214 teaching staff.
