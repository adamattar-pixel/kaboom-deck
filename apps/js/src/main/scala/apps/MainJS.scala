package apps

import cs214.webapp.client.ApplicationJS
import org.scalajs.dom

object MainJS extends ApplicationJS:
  @main def main() =
    // Feuille de style du catalogue : titre texte "Kaboom Deck" à la place de l'image de l'application
    val catalogCss = dom.document.createElement("link").asInstanceOf[dom.html.Link]
    catalogCss.rel = "stylesheet"
    catalogCss.href = "/static/catalog.css"
    dom.document.head.appendChild(catalogCss)
    this.start()
