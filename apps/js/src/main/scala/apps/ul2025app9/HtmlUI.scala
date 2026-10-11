package apps
package ul2025app9

import cs214.webapp.*
import cs214.webapp.client.*
import cs214.webapp.client.graphics.WebClientAppInstance
import org.scalajs.dom
import scalatags.JsDom.all.*
import cs214.webapp.client.graphics.IncrementalUI
import org.scalajs.dom.html

import scala.scalajs.js.annotation.JSExportTopLevel

// Point d'entrée de l'application web côté client
// Cette classe est exportée vers JavaScript pour permettre l'initialisation
@JSExportTopLevel("ul2025app9")
object UI extends WSClientApp:
  def appId: String = "ul2025app9"
  def uiId: String = "html"

  // Méthode d'initialisation appelée lors du chargement de l'application
  // Crée une nouvelle instance de l'interface utilisateur
  def init(userId: UserId, sendMessage: ujson.Value => Unit, target: dom.Element): ClientAppInstance =
    UIInstance(userId, sendMessage, target)

// Instance principale de l'interface utilisateur
// Gère l'état du rendu et les interactions avec le serveur
class UIInstance(userId: UserId, sendMessage: ujson.Value => Unit, target: dom.Element)
    extends WebClientAppInstance[Event, View](userId, sendMessage, target), IncrementalUI:

  override val wire = ul2025app9.Wire
  
  // État local pour le champ texte d'insertion de bombe
  private var insertIdxText: String = ""
  
  // Chargement de la feuille de style CSS personnalisée
  val linkT = dom.document.createElement("link").asInstanceOf[dom.html.Link]
  linkT.rel = "stylesheet"
  linkT.href = "/static/kittens.css"
  dom.document.head.appendChild(linkT)
  
  // Conteneur pour les notifications toast (messages temporaires en haut à droite)
  private val toastContainer: html.Div = {
    val d = dom.document.createElement("div").asInstanceOf[html.Div]
    d.className = "toast-stack"
    dom.document.body.appendChild(d)
    d
  }
  
  // Gestion de l'historique des logs pour détecter les nouveaux messages
  private var lastLogSnapshot: Vector[String] = Vector.empty
  
  // File d'attente des toasts avec leur ID unique
  private var toastQueue: Vector[(Int, String)] = Vector.empty
  private var toastCounter: Int = 0

  // === Ressources audio ===
  // Son joué au début d'une partie
  private val startAudio: html.Audio =
    val a = dom.document.createElement("audio").asInstanceOf[html.Audio]
    a.src = "/static/ul2025app9/start.mp3"
    a.preload = "auto"
    a

  // Son joué quand un joueur est éliminé
  private val eliminatedAudio: html.Audio =
    val a = dom.document.createElement("audio").asInstanceOf[html.Audio]
    a.src = "/static/ul2025app9/eliminated.mp3"
    a.preload = "auto"
    a

  // Son joué à la fin de la partie
  private val endAudio: html.Audio =
    val a = dom.document.createElement("audio").asInstanceOf[html.Audio]
    a.src = "/static/ul2025app9/end.mp3"
    a.preload = "auto"
    a

  // === Images splash (écrans d'annonce) ===
  // Image affichée au début de la partie avec animation de zoom
  private val startImage: html.Image =
    val img = dom.document.createElement("img").asInstanceOf[html.Image]
    img.src = "/static/ul2025app9/start.png"
    img.className = "start-splash hidden"
    dom.document.body.appendChild(img)
    img

  // Image affichée à la fin de la partie
  private val endImage: html.Image =
    val e = dom.document.createElement("img").asInstanceOf[html.Image]
    e.src = "/static/ul2025app9/end.png"
    e.className = "start-splash hidden"
    dom.document.body.appendChild(e)
    e

  // Affiche l'écran de fin avec animation (zoom + hold + fade)
  private def showEndSplash(): Unit =
    endImage.classList.remove("hidden")
    endImage.classList.remove("start-splash--fade")
    val _ = endImage.offsetWidth // Force un reflow pour redémarrer l'animation CSS
    endImage.classList.add("start-splash--anim")
    // Après 800ms d'animation + 1000ms de maintien -> commence le fade de 2000ms
    dom.window.setTimeout(() => endImage.classList.add("start-splash--fade"), 800 + 1000)
    dom.window.setTimeout(() => endImage.className = "start-splash hidden", 800 + 1000 + 2000)

  // Affiche l'écran de démarrage avec animation
  private def showStartSplash(): Unit =
    startImage.classList.remove("hidden")
    startImage.classList.remove("start-splash--fade")
    val _ = startImage.offsetWidth // Force un reflow
    startImage.classList.add("start-splash--anim")
    // Timing identique à showEndSplash
    dom.window.setTimeout(() => startImage.classList.add("start-splash--fade"), 800 + 1000)
    dom.window.setTimeout(() => startImage.className = "start-splash hidden", 800 + 1000 + 2000)

  // === Suivi d'état pour les déclencheurs audio ===
  private var startPlayed: Boolean = false
  private var lastOthersAliveCount: Option[Int] = None
  private var lastWasFinished: Boolean = false
  private var turnCounterBaseline: Int = 1
  private var lastTurnOwner: Option[UserId] = None

  // Vérifie les conditions pour déclencher les effets audio et visuels
  private def checkAudioTriggers(view: View): Unit =
    // Jouer le son de démarrage une seule fois lors du passage à "Playing"
    view.state match
      case StateView.Playing =>
        if !startPlayed then
          startAudio.currentTime = 0
          startAudio.play()
          showStartSplash()
          startPlayed = true
      case _ => ()

    // Détecter l'élimination d'un joueur (nombre de joueurs vivants diminue)
    val othersAlive = view.others.count(_.alive)
    lastOthersAliveCount match
      case Some(prev) if othersAlive < prev =>
        eliminatedAudio.currentTime = 0
        eliminatedAudio.play()
      case _ => ()
    lastOthersAliveCount = Some(othersAlive)

    // Jouer le son de fin une seule fois lors du passage à "Finished"
    view.state match
      case StateView.Finished(_) =>
        if !lastWasFinished then
          endAudio.currentTime = 0
          endAudio.play()
          showEndSplash()
        lastWasFinished = true
        // Reset pour permettre un nouveau démarrage si la partie recommence
        startPlayed = false
      case _ => lastWasFinished = false

  // Snapshot pour détecter les changements de carte au centre et déclencher l'animation
  private var lastPlayedSnapshot: Option[Card] = None

  // === Méthodes d'envoi d'événements au serveur ===
  
  // Envoie la sélection d'un joueur (pour la carte Favour)
  private def sendPlayerSelection(uid : UserId): Unit = 
    sendEvent(Event.PlayerPicked(uid))
  
  // Envoie l'index d'insertion d'une bombe désamorcée
  private def sendInsertBomb(idx: Int): Unit =
    require(idx >= 0, "Index must be non-negative")
    sendEvent(Event.Inserted(idx))
  
  // Envoie un clic sur une carte de la main
  private def sendCardClick(idx: Int): Unit =
    require(idx >= 0, "Index must be non-negative")
    sendEvent(Event.CardClicked(idx))

  // Envoie l'action de piocher une carte
  private def sendDraw(): Unit =
    sendEvent(Event.Drew)

  // Méthode principale de rendu appelée à chaque mise à jour de la vue
  def render(userId: UserId, view: View): Frag =
    updateNotifications(view.log)  // Met à jour les toasts avec les nouveaux logs
    checkAudioTriggers(view)        // Vérifie si des sons doivent être joués
    frag(
      h2(b("Kaboom Deck: "), "Avoid the bombs and play your cards!"),
      div(
        cls := "game-wrapper",
        renderView(view)
      )
    )

  // Affiche un compteur circulaire de tours restants lorsqu'une attaque impose plusieurs tours
  private def renderTurnCounter(view: View): Option[Frag] =
    val isFinished = view.state match
      case StateView.Finished(_) => true
      case _ => false

    if view.playerTurn != userId || isFinished then
      lastTurnOwner = None
      turnCounterBaseline = 1
      None
    else
      // rafraîchit la baseline quand le tour change ou quand plusieurs tours sont ajoutés
      if lastTurnOwner.contains(userId) then
        if view.turnsLeft > turnCounterBaseline then
          turnCounterBaseline = view.turnsLeft
      else
        lastTurnOwner = Some(userId)
        turnCounterBaseline = math.max(1, view.turnsLeft)

      val baseline = math.max(1, turnCounterBaseline)
      val remaining = math.max(1, view.turnsLeft)

      if baseline <= 1 && remaining <= 1 then None
      else
        val clamped = math.max(0.0, math.min(1.0, remaining.toDouble / baseline.toDouble))
        val deg = clamped * 360.0
        Some(
          div(
            cls := "turn-counter",
            style := s"--turn-progress: ${deg}deg;",
            div(
              cls := "turn-counter-ring",
              span(cls := "turn-counter-number", s"$remaining")
            )
          )
        )

  // Rendu de la vue principale (différent selon l'état du jeu)
  private def renderView(view: View): Frag =
    view.state match
      case StateView.Finished(leaderboard) =>
        // Affiche l'écran de fin avec le classement
        frag(
          div(
            cls := "leaderboard-wrapper",
            renderFinished(leaderboard)
          ),
          div(
            cls := "floating-log",
            renderLog(view.log)
          )
        )
      case _ =>
        // Affiche l'interface de jeu normale
        frag(
          div(
            cls := "main-container",
            div(
              cls := "centered-content",
              renderOthers(view.others),  // Affiche les autres joueurs

              div(
                cls := "game-center-piles",
                renderDrawPile(view.leftToDraw, view),  // Pile de pioche
                renderLastPlayed(view.lastPlayed)        // Dernière carte jouée
              ),

              div(
                cls := "hand-and-draw-container",
                div(
                  cls := "hand-stack",
                  // Panneaux contextuels selon l'état du jeu
                  renderInsertPanel(view).getOrElse(frag()),
                  renderAskFavour(view).getOrElse(frag()),
                  renderGivingPrompt(view).getOrElse(frag()),
                  renderViewingCards(view).getOrElse(frag()),
                  renderHand(view.hand, view.alive)  // Main du joueur
                ),
                div(
                  cls := "draw-column",
                  renderTurnCounter(view).getOrElse(frag()),
                  renderDrawButton(view)  // Bouton de pioche
                )
              )
            )
          ),
          div(
            cls := "floating-log",
            renderLog(view.log)  // Historique des actions
          )
        )

  // Met à jour les notifications toast avec les nouveaux messages du log
  private def updateNotifications(log: Vector[String]): Unit =
    // Récupère seulement les nouveaux messages depuis le dernier snapshot
    val newEntries =
      lastLogSnapshot.headOption match
        case Some(prevHead) => log.takeWhile(_ != prevHead)
        case None => log.take(1)

    if newEntries.nonEmpty then
      val ordered = newEntries.reverse  // Remet dans l'ordre chronologique
      // Assigne un ID unique à chaque message
      val withIds = ordered.map { msg =>
        toastCounter += 1
        (toastCounter, msg)
      }
      // Programme la suppression automatique après 4 secondes
      withIds.foreach { case (id, _) =>
        dom.window.setTimeout(() => removeToast(id), 4000)
      }
      // Garde maximum 3 toasts affichés
      toastQueue = (withIds ++ toastQueue).take(3)
      renderToastStack()

    lastLogSnapshot = log

  // Retire un toast de la file d'attente
  private def removeToast(id: Int): Unit =
    toastQueue = toastQueue.filterNot(_._1 == id)
    renderToastStack()

  // Affiche tous les toasts actuellement dans la file
  private def renderToastStack(): Unit =
    // Vide le conteneur
    while toastContainer.firstChild != null do toastContainer.removeChild(toastContainer.firstChild)
    // Ajoute chaque toast
    toastQueue.foreach { case (id, msg) =>
      val toast = div(
        cls := "toast",
        attr("data-id") := id.toString,
        span(cls := "toast-dot"),      // Point coloré
        span(cls := "toast-text", msg)  // Texte du message
      ).render
      toastContainer.appendChild(toast)
    }

  // Rendu de l'écran de fin avec le classement
  private def renderFinished(leaderboard: Vector[UserId]): Frag =
    val top = leaderboard.take(4)  // Affiche les 4 premiers
    val total = math.max(1, top.length)
    div(
      cls := "leaderboard-screen",
      div(
        cls := "leaderboard-panel",
        div(
          cls := "leaderboard-header",
          span(cls := "leaderboard-line"),
          span(cls := "leaderboard-title", "Leaderboard"),
          span(cls := "leaderboard-line")
        ),
        span(cls := "leaderboard-subtitle", "Final ranking"),
        div(
          cls := "leaderboard-rows",
          if top.isEmpty then
            div(cls := "leaderboard-empty", "Aucun résultat pour l'instant.")
          else
            top.zipWithIndex.map { case (uid, idx) => renderLeaderboardRow(uid, idx, total) }
        )
      )
    )

  // Rendu d'une ligne individuelle du classement
  private def renderLeaderboardRow(uid: UserId, idx: Int, total: Int): Frag =
    require(idx >= 0, "Index must be non-negative")
    require(total > 0, "Total must be positive")
    
    // Classes CSS selon la position
    val medalClass = idx match
      case 0 => "gold"
      case 1 => "silver"
      case 2 => "bronze"
      case _ => "standard"

    val rowClass = idx match
      case 0 => "first"
      case 1 => "second"
      case 2 => "third"
      case _ => "rest"

    // Emoji de médaille pour les 3 premiers
    val medalLabel = idx match
      case 0 => "🥇"
      case 1 => "🥈"
      case 2 => "🥉"
      case _ => s"${idx + 1}"

    // Initiale du joueur pour l'avatar
    val initials = uid.toString.headOption.map(_.toString).getOrElse("?")

    div(
      cls := s"leaderboard-row $rowClass",
      span(cls := s"leaderboard-rank $medalClass", medalLabel),
      span(cls := "leaderboard-avatar", initials),
      div(
        cls := "leaderboard-main",
        span(cls := "leaderboard-name", uid.toString)
      ),
      span(cls := "leaderboard-score", s"${idx + 1}/$total")
    )

  // Rendu de la pile de pioche avec les boutons contextuels
  private def renderDrawPile(count: Int, view: View): Frag =
    require(count >= 0, "Count must be non-negative")
    
    val nopeBtn = renderNopeCountdown(view)
    val defuseBtn = renderDefuseCountdown(view)
    val forecastCountdown = renderForecastCountdown(view)
    div(
      cls := "draw-pile-container",
      // Empile jusqu'à 5 cartes avec offset et rotation pour effet 3D
      (0 until Math.min(count, 5)).map { i =>
        val offset = i * 0.5
        val rotation = i * 0.5 - 1
        div(
          cls := "draw-pile-card",
          style := s"transform: translate(${offset}px, ${offset}px) rotate(${rotation}deg); z-index: ${i};"
        )
      },
      // Carte du dessus affichant le nombre total
      div(
        cls := "draw-pile-card top-card",
        div(
          cls := "draw-pile-count",
          b(s"${count}")
        )
      ),
      // Boutons contextuels si applicables
      forecastCountdown.getOrElse(frag()),
      nopeBtn.getOrElse(frag()),
      defuseBtn.getOrElse(frag())
    )

  // Rendu de la main du joueur
  private def renderHand(hand: Vector[Card], alive: Boolean): Frag =
    div(
      cls := s"hand${if !alive then " dead" else ""}",
      hand.zipWithIndex.map { case (card, idx) =>
        val inner: Frag = card match
          case Card.Match(id) =>
            // Carte Match avec emoji épée et ID
            div(
              cls := "match-card",
              span(cls := "match-emoji", "⚔️"),
              span(cls := "match-id", s"$id")
            )
          case _ =>
            // Autres cartes : utilise une image de fond basée sur le type
            val cardType = card.toString.toLowerCase.replaceAll("[^a-z0-9\\-]", "")
            div(
              cls := "card-image",
              style := s"background-image: url('/static/ul2025app9/${cardType}.png'); background-size: cover; background-position: center; width: 100%; height: 100%;"
            )

        div(
          cls := s"card${if !alive then " dead" else ""}",
          onclick := (() => if alive then sendCardClick(idx)),
          inner
        )
      }
    )

  // Rendu des cartes d'un autre joueur
  private def renderOtherPlayerCards(
      count: Int,
      isStackHorizontal: Boolean,
      label: Option[String],
      labelOnRight: Boolean,
      positionClass: String,
      alive: Boolean
  ): Frag = {
    require(count >= 0, "Count must be non-negative")
    
    val offset = if (isStackHorizontal) 20 else 18
    val translation: String = if (isStackHorizontal) "translateX" else "translateY"
    // Rotation selon la position (gauche = 90°, droite = -90°)
    val rotationDeg = positionClass match {
      case "player-left"  => 90
      case "player-right" => -90
      case _              => 0
    }

    div(
      cls := (if (isStackHorizontal) "other-cards-horizontal-stack" else "other-cards-vertical-stack") + (if !alive then " dead" else ""),
      (0 until count).map { i =>
        // Affiche le nom du joueur sur la première carte
        val nameFrag = if (i == 0 && label.isDefined)
          div(cls := s"other-card-name ${if labelOnRight then "right" else "left"}${if !alive then " dead" else ""}", label.get)
        else frag()

        frag(
          nameFrag,
          div(
            cls := s"other-card${if !alive then " dead" else ""}",
            style := s"transform: ${translation}(${i * offset}px) rotate(${rotationDeg}deg)${if !alive then " translateY(12px)" else ""}; transform-origin: center;",
          )
        )
       }
     )
   }

  // Rendu de la zone des autres joueurs
  private def renderOthers(players: Vector[PlayerView]): Frag = {
    val numPlayers = players.size
    val playerFragments = players.zipWithIndex.map { case (p, idx) =>
     // Détermine la position de chaque joueur selon leur nombre
     val positionClass = numPlayers match {
       case 1 => "player-top"
       case 2 => if (idx == 0) "player-left" else "player-right"
       case 3 => idx match {
         case 0 => "player-top"
         case 1 => "player-left"
         case 2 => "player-right"
         case _ => ""
       }
       case _ => ""
     }
 
     val isStackHorizontal = positionClass == "player-top"
     val labelText = p.player
     val labelOnRight = positionClass == "player-left"
 
     div(
      cls := "player-container " + positionClass,
      renderOtherPlayerCards(p.cardsLeft, isStackHorizontal, Some(labelText), labelOnRight, positionClass, p.alive)
     )
   }
    div(
     cls := "players-zone",
     playerFragments
   )
 }

  // Rendu de la dernière carte jouée (pile de défausse)
  private def renderLastPlayed(last: Card): Frag =
    // Détecte si la carte a changé pour déclencher l'animation
    val playAnim = lastPlayedSnapshot match
      case Some(prev) if prev == last =>
        false  // Même carte, pas d'animation
      case _ =>
        // Nouvelle carte : on mémorise et on active l'animation
        lastPlayedSnapshot = Some(last)
        true

    val inner: Frag = last match
      case Card.Match(id) =>
        div(
          cls := ("match-card" + (if playAnim then " played" else "")),
          span(cls := "match-emoji", "⚔️"),
          span(cls := "match-id", s"$id")
        )
      case _ =>
        val cardType = last.toString.toLowerCase.replaceAll("[^a-z0-9\\-]", "")
        div(
          cls := ("card-image" + (if playAnim then " played" else "")),
          style := s"background-image: url('/static/ul2025app9/${cardType}.png'); background-size: cover; background-position: center; width: 100%; height: 100%;"
        )

    div(
      cls := "last-played",
      div(
        cls := "discard-card",
        inner
      )
    )

  // Rendu du journal de jeu (historique des actions)
  private def renderLog(log: Vector[String]): Frag =
    div(
      cls := "log",
      h3("Game Log"),
      log.reverse.map(l => p(l))  // Affiche en ordre inverse (plus récent en haut)
    )

  // Rendu du bouton de pioche (affiché seulement pendant le tour du joueur)
  private def renderDrawButton(v: View): Frag =
    if (v.state == StateView.Playing && v.playerTurn == userId) then
      button(
        cls := "draw-button",
        "Draw Card",
        onclick := (() => sendDraw())
      )
    else
      frag()

  // Rendu du panneau d'insertion de bombe (quand le joueur doit replacer une bombe désamorcée)
  private def renderInsertPanel(v: View): Option[Frag] =
    v.state match
      case StateView.Inserting if v.playerTurn == userId =>
        if insertIdxText.isEmpty then insertIdxText = "0"
        val maxIdx = v.leftToDraw
        // Gestion du changement de valeur dans le champ
        val onInputChange = (e: dom.Event) => {
          insertIdxText = e.target.asInstanceOf[dom.html.Input].value
        }
        // Envoi de l'index au serveur
        val onSubmit = () => {
          insertIdxText.toIntOption
            .foreach(sendInsertBomb)
        }
        Some(
          div(
            cls := "insert-panel",
            span(cls := "insert-title", "Insert the bomb"),
            span(cls := "insert-help", s"Index 0 (for the top) to $maxIdx"),
            div(
              cls := "insert-input-row",
              input(
                cls := "insert-input",
                `type` := "number",
                min := "0",
                max := s"$maxIdx",
                step := "1",
                value := insertIdxText,
                placeholder := s"0-$maxIdx",
                oninput := onInputChange
              ),
              button(
                cls := "insert-btn",
                onclick := (() => onSubmit()),
                "Insert"
              )
            )
          )
        )
      case _ =>
        insertIdxText = ""
        None

  // Rendu du bouton de désamorçage avec compte à rebours
  private def renderDefuseCountdown(v: View): Option[Frag] =
    v.state match
      case StateView.Waiting(msLeft)
          if v.lastPlayed == Card.Bomb && v.playerTurn == userId && v.hand.contains(Card.Defuser) =>
        require(msLeft >= 0, "Time left must be non-negative")
        
        val firstDefuseIdx = v.hand.indexWhere(_ == Card.Defuser)
        val defuseWindowMs = 5000.0  // Fenêtre de 5 secondes
        val percent = math.max(0.0, math.min(100.0, msLeft.toDouble / defuseWindowMs * 100.0))
        Some(
          button(
            cls := "defuse-bomb",
            onclick := (() => sendCardClick(firstDefuseIdx)),
            div(
              cls := "defuse-bomb-bar",
              style := s"width: ${percent}%;"  // Barre de progression
            ),
            span(cls := "defuse-bomb-label", "Defuse Bomb"),
            span(cls := "defuse-bomb-time", f"${msLeft / 1000.0}%.1fs")
          )
        )
      case _ => None

  // Rendu du bouton Nope avec compte à rebours
  private def renderNopeCountdown(v: View): Option[Frag] =
    v.state match
      case StateView.Waiting(msLeft)
          if v.lastPlayed != Card.Defuser && v.lastPlayed != Card.Bomb && v.hand.contains(Card.Nope) && v.playerTurn != userId =>
        require(msLeft >= 0, "Time left must be non-negative")
        
        val firstNopeIdx = v.hand.indexWhere(_ == Card.Nope)
        val cancelWindowMs = 5000.0  // Fenêtre de 5 secondes
        val percent = math.max(0.0, math.min(100.0, msLeft.toDouble / cancelWindowMs * 100.0))
        Some(
          button(
            cls := "put-nope",
            onclick := (() => sendCardClick(firstNopeIdx)),
            div(
              cls := "put-nope-bar",
              style := s"width: ${percent}%;"  // Barre de progression
            ),
            span(cls := "put-nope-label", "Play Nope"),
            span(cls := "put-nope-time", f"${msLeft / 1000.0}%.1fs")
          )
        )
      case _ => None

  // Rendu des cartes visionnées (carte Forecast ou See the Future)
  private def renderViewingCards(v: View): Option[Frag] =
    v.state match
      case StateView.ViewingCards(cards, msLeft) if v.playerTurn == userId =>
        require(msLeft >= 0, "Time left must be non-negative")
        
        val viewingWindowMs = 5000.0  // Fenêtre de 5 secondes
        val percent = math.max(0.0, math.min(100.0, msLeft.toDouble / viewingWindowMs * 100.0))
        Some(
          frag(
            // Compte à rebours
            div(
              cls := "viewing-countdown",
              div(
                cls := "viewing-countdown-bar",
                style := s"width: ${percent}%;"
              ),
              span(cls := "viewing-countdown-label", "Temps pour regarder"),
              span(cls := "viewing-countdown-time", f"${msLeft / 1000.0}%.1fs")
            ),
            // Affichage des cartes en éventail
            div(
              cls := "viewing-cards",
              cards.zipWithIndex.map { case (card, idx) =>
                val offset = idx * 18
                val inner: Frag = card match
                  case Card.Match(id) =>
                    div(
                      cls := "match-card",
                      span(cls := "match-emoji", "⚔️"),
                      span(cls := "match-id", s"$id")
                    )
                  case _ =>
                    val cardType = card.toString.toLowerCase.replaceAll("[^a-z0-9\\-]", "")
                    div(
                      cls := "card-image",
                      style := s"background-image: url('/static/ul2025app9/${cardType}.png'); background-size: cover; background-position: center; width: 100%; height: 100%;"
                    )

                div(
                  cls := "viewing-card",
                  style := s"transform: translateX(${offset}px) rotate(${idx * 1.5 - 3}deg); z-index: ${20 + idx};",
                  inner
                )
              }
            )
          )
        )
      case _ => None

  // Rendu du sélecteur de joueur (pour la carte Favour)
  private def renderAskFavour(v: View): Option[Frag] =
    v.state match
      case StateView.PickingPlayer if v.playerTurn == userId =>
        val eligible = v.others.filter(_.alive)  // Seulement les joueurs vivants
        Some(
          div(
            cls := "favour-picker",
            span(cls := "favour-picker-title", "Choose who gives you a card"),
            div(
              cls := "favour-picker-buttons",
              eligible.map { p =>
                button(
                  cls := "favour-picker-btn",
                  onclick := (() => sendPlayerSelection(p.player)),
                  p.player
                )
              }
            )
          )
        )
      case _ => None

  // Rendu du message indiquant qu'on doit donner une carte
  private def renderGivingPrompt(v: View): Option[Frag] =
    v.state match
      case StateView.GivingCard(giver) if giver == userId =>
        Some(
          div(
            cls := "giving-prompt",
            span(cls := "giving-prompt-title", "You must give a card"),
            span(cls := "giving-prompt-sub", s"Click a card to give it to ${v.playerTurn}, or play Nope to cancel.")
          )
        )
      case _ => None

  // Rendu du compte à rebours pour la résolution de la carte Forecast
  private def renderForecastCountdown(v: View): Option[Frag] =
    v.state match
      case StateView.Waiting(msLeft)
          if v.lastPlayed == Card.Forecast && v.playerTurn == userId =>
        require(msLeft >= 0, "Time left must be non-negative")
        
        val forecastWindowMs = 5000.0  // Fenêtre de 5 secondes
        val percent = math.max(0.0, math.min(100.0, msLeft.toDouble / forecastWindowMs * 100.0))
        Some(
          div(
            cls := "forecast-countdown",
            div(
              cls := "forecast-countdown-bar",
              style := s"width: ${percent}%;"  // Barre de progression
            ),
            span(cls := "forecast-countdown-label", "Forecast resolving"),
            span(cls := "forecast-countdown-time", f"${msLeft / 1000.0}%.1fs")
          )
        )
      case _ => None
