# mijn.3park

<img src="store/icon.svg" alt="mijn.3park app-icoon" width="96" align="right"/>

Een onofficiële Android-client voor **[mijn.2park.nl](https://mijn.2park.nl)**,
een Nederlandse parkeerdienst. Je logt in met je bestaande 2Park-account, kiest
een product en zet het parkeren voor een kenteken aan of uit, met een blijvende
melding zolang het parkeren actief is.

## Functies

- **Inloggen** met je mijn.2park.nl-gegevens (versleuteld op het toestel
  opgeslagen).
- **Parkeren starten / stoppen** voor elk kenteken, met een doorlopende melding.
- **Parkeren tot je stopt**: de app houdt het parkeren actief over middernacht
  heen, of stel een expliciete eindtijd in.
- **Opgeslagen kentekens**: gebruik kentekens die op je 2Park-account zijn
  opgeslagen (met namen), of onthoud ze lokaal; kentekens met een naam
  toevoegen, bewerken en verwijderen in de app.
- **Vergunningsproducten (vast kenteken)** worden alleen-lezen en altijd-actief
  getoond.
- **Saldo & opwaarderen** voor prepaid-producten (de betaling opent in je
  browser).
- **Geschiedenis & transacties**: bekijk eerdere parkeeracties en
  saldomutaties.
- **Instellingen**: kies een standaardproduct en een licht/donker/systeemthema.

## Techniek

- Kotlin, coroutines, OkHttp, Material 3 (dynamische kleuren).
- Praat rechtstreeks met de niet-gedocumenteerde JSON-endpoints van
  mijn.2park.nl (geen eigen backend).
- Gebouwd met Gradle (Kotlin DSL); zie `app/build.gradle.kts`.
- Minimale SDK 23.

Zie [`AGENTS.md`](AGENTS.md) voor uitgebreide notities over de 2Park-API, de
eigenaardigheden ervan en de architectuur van de app.

## Disclaimer

> mijn.3park is een onofficiële app en is niet verbonden aan mijn.2park.nl. Het
> is een soloproject, gemaakt omdat de officiële mijn.2park.nl-interface
> onhandig is in gebruik. Er worden geen gegevens naar derden gestuurd. De app
> maakt rechtstreeks verbinding met 2Park en je gegevens blijven alleen op dit
> toestel.
