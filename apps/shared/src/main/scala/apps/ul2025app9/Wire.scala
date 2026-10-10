package apps
package ul2025app9

import cs214.webapp.*
import ujson.{Str, Value}

import scala.util.{Failure, Success, Try}

object Wire extends AppWire[Event, View]:
  override object eventFormat extends WireFormat[Event]:
    override def encode(event: Event): Value = 
      val encoded = event match
        case Event.Drew =>
          ujson.Obj("type" -> ujson.Str("Drew"))
        case Event.PlayerPicked(uid) => 
          require(uid.nonEmpty, "PlayerPicked userId cannot be empty")
          ujson.Obj(
            "type" -> ujson.Str("PlayerPicked"),
            "userId" -> ujson.Str(uid)
          )
        case Event.CardClicked(cardId) =>
          require(cardId >= 0, "CardClicked cardId must be non-negative")
          ujson.Obj(
            "type" -> ujson.Str("CardClicked"),
            "cardId" -> ujson.Num(cardId)
          )
        case Event.Inserted(position) =>
          require(position >= 0, "Inserted position must be non-negative")
          ujson.Obj(
            "type" -> ujson.Str("Inserted"),
            "position" -> ujson.Num(position)
          )
      require(encoded.obj.contains("type"), "Encoded event must contain type")
      encoded
    override def decode(json: Value): Try[Event] = Try {
      val tpe = json("type").str
      require(tpe.nonEmpty, "Event type cannot be empty")
      tpe match
        case "Drew" =>
          Event.Drew
        case "CardClicked" =>
          val id = json("cardId").num.toInt
          require(id >= 0, "cardId must be non-negative")
          Event.CardClicked(id)
        case "Inserted" =>
          val pos = json("position").num.toInt
          require(pos >= 0, "position must be non-negative")
          Event.Inserted(pos)
        case "PlayerPicked" =>
          val uid = json("userId").str
          require(uid.nonEmpty, "userId cannot be empty")
          Event.PlayerPicked(uid)
        case other =>
          throw IllegalArgumentException(s"Unknown event type: $other")
    }
    
  override object viewFormat extends WireFormat[View]:
    override def encode(view: View): Value = 
      require(view.leftToDraw >= 0, "leftToDraw cannot be negative")
      require(view.turnsLeft >= 0, "turnsLeft cannot be negative")
      require(view.playerTurn.nonEmpty, "playerTurn cannot be empty")
      require(view.others.forall(_.player.nonEmpty), "player id cannot be empty")
      require(view.others.forall(_.cardsLeft >= 0), "cardsLeft cannot be negative")
      val encoded = ujson.Obj(
        "hand" -> ujson.Arr(
          view.hand.map(card =>
            ujson.Str(card.toString)
            )*
        ),
        "other" -> ujson.Arr(
          view.others.map(pv => 
            ujson.Obj(
              "player" ->  ujson.Str(pv.player),
              "cardsLeft" -> pv.cardsLeft,
              "alive" -> ujson.Bool(pv.alive)
            )
            )*
        ),
        "state" -> (
          view.state match
            case StateView.Playing => 
              ujson.Obj("type" -> ujson.Str("Playing"))
            case StateView.GivingCard(giver) => 
              ujson.Obj(
                "type" -> ujson.Str("GivingCard"),
                "giver" -> ujson.Str(giver)
              )
            case StateView.Waiting(time) =>
              ujson.Obj(
                "type" -> ujson.Str("Waiting"),
                "time" -> ujson.Num(time)
              )
            case StateView.Inserting =>
              ujson.Obj("type" -> ujson.Str("Inserting"))
            case StateView.PickingPlayer => 
              ujson.Obj("type" -> ujson.Str("PickingPlayer"))
            case StateView.ViewingCards(cards, time) => 
              ujson.Obj(
                "type" -> ujson.Str("ViewingCards"),
                "time" -> ujson.Num(time),
                "cards" -> ujson.Arr(
                  cards.map(card =>
                    ujson.Str(card.toString)
                    )*
                  )
              )
            case StateView.Finished(leaderboard) => 
              ujson.Obj(
                "type" -> ujson.Str("Finished"),
                "leaderboard" -> ujson.Arr(leaderboard.map(lb => ujson.Str(lb))*)
              )
            case StateView.Paused => 
              ujson.Obj(
                "type" -> ujson.Str("Paused"),
              )
        ),
        "leftToDraw" -> view.leftToDraw,
        "lastPlayed" -> ujson.Str(view.lastPlayed.toString),
        "log" ->  ujson.Arr(
          view.log.map(l => 
            ujson.Str(l)
            )*
        ),
        "playerTurn" -> ujson.Str(view.playerTurn),
        "turnsLeft" -> ujson.Num(view.turnsLeft),
        "alive" -> ujson.Bool(view.alive)


      )
      require(encoded.obj.contains("hand"), "Encoded view missing hand")
      require(encoded.obj.contains("state"), "Encoded view missing state")
      encoded
    override def decode(json: Value): Try[View] = Try{
      val matchRe = """Match\s*\(?\s*(\d+)\s*\)?""".r
      def parseCard(s: String): Card = s match
        case "Bomb"    => Card.Bomb
        case "Defuser" => Card.Defuser
        case "Shuffle" => Card.Shuffle
        case "Nope"    => Card.Nope
        case "Skip"    => Card.Skip
        case "Attack"  => Card.Attack
        case "Forecast" => Card.Forecast
        case "Favour" => Card.Favour
        case "Blank"   => Card.Blank
        case matchRe(id) => Card.Match(id.toInt)
        case other     => throw IllegalArgumentException(s"Unknown card: $other")

      val result = View(
        json("hand").arr.map(v => 
          parseCard(v.str)
        ).toVector,
        json("other").arr.map(v => 
          PlayerView(
            v("player").str, 
            v("cardsLeft").num.toInt,
            v("alive").bool
          )
          ).toVector, 
        json("state")("type").str match
          case "Playing" => StateView.Playing
          case "Inserting" => StateView.Inserting
          case "GivingCard" => StateView.GivingCard(json("state")("giver").str)
          case "PickingPlayer" => StateView.PickingPlayer
          case "Paused" => StateView.Paused
          case "ViewingCards" => StateView.ViewingCards(
            json("state")("cards").arr.map(v => 
              parseCard(v.str)
              ).toVector,
            json("state")("time").num.toInt
          )
          case "Waiting" => StateView.Waiting(json("state")("time").num.toInt)
          case "Finished" => StateView.Finished(
            json("state")("leaderboard").arr.map(lb => 
              lb.str
            ).toVector
          )
          , 
        json("leftToDraw").num.toInt, 
        parseCard(json("lastPlayed").str), 
        json("log").arr.map(l => l.str).toVector,
        json("playerTurn").str,
        json("turnsLeft").num.toInt,
        json("alive").bool
      )
      require(result.leftToDraw >= 0, "leftToDraw cannot be negative")
      require(result.turnsLeft >= 0, "turnsLeft cannot be negative")
      require(result.playerTurn.nonEmpty, "playerTurn cannot be empty")
      require(result.others.forall(_.cardsLeft >= 0), "cardsLeft cannot be negative")
      require(result.others.forall(_.player.nonEmpty), "player id cannot be empty")
      result
    }
