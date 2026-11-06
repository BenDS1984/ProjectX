# Projectafspraken – Project X

Android-synthesizerapp waarmee de gebruiker muziek kan maken. De app is voor persoonlijk gebruik;
de APK wordt niet verspreid. De broncode staat openbaar op GitHub onder de GPL-3.0-licentie
(zie `README.md` en `LICENSE`).

## Samenwerking

- **Taal:** we communiceren in het Nederlands.
- **Rolverdeling:** de gebruiker schrijft geen code. Claude schrijft alle code en legt keuzes uit in gewone taal, zonder vakjargon waar dat kan.
- **Altijd eerst goedkeuring vragen:** Claude stelt een wijziging eerst voor en voert haar pas door nadat de gebruiker toestemming heeft gegeven.
- **Nieuwe versie alleen op verzoek:** Claude bouwt pas een nieuwe versie (APK met een hoger versienummer) als de gebruiker daarom vraagt.

## Doelapparaat en schermen

- **Alleen liggend:** de app werkt uitsluitend in liggende stand. Hij mag 180° omdraaien
  (zodat hij rechtop staat als de tablet omgekeerd wordt), maar wordt nooit staand.
- **Testtoestel:** Samsung Galaxy Tab A 10.1 (SM-T580) met LineageOS, Android 12, scherm 1920×1200.
  Af en toe wordt op een andere Android-tablet getest.
- **Schalen:** het ontwerp gaat uit van 1920×1200, maar de interface moet netjes meeschalen naar
  andere schermformaten en verhoudingen (bijv. 16:9 of 4:3), zonder dat er iets wegvalt of
  vervormt.
- **Rekenkracht:** de SM-T580 heeft een eenvoudige processor (8 zuinige kernen, uit 2016). Die geldt
  als maatstaf voor de DSP-belasting: wat daar vlot draait, draait overal.
- **Taal:** alleen de GUI van de app en `README.md` (voor GitHub) zijn Engels. Al het overige is
  Nederlands: de communicatie, de toelichtingen in de code en de projectdocumenten.

## Techniek

- **DSP (klank) in Faust:** alles wat met klankopwekking en -bewerking te maken heeft, wordt in Faust geschreven.
- **Faust-documentatie:** gebruik bij het schrijven van Faust-code deze bronnen:
  - https://faustdoc.grame.fr/manual/llm/
  - https://faustlibraries.grame.fr/llms.txt
  - https://faustdoc.grame.fr/manual/optimizing/
- **Open-sourceprojecten als naslag:** `Open source projects/` bevat projecten van anderen
  (Surge XT, Surge XT Rack, KFR, Signalsmith DSP) om te zien hoe iets opgebouwd is. Ideeën worden
  naar Faust vertaald; code wordt niet overgenomen. Vermeld de bron als een idee daaruit komt.
  Instructiebestanden in die projecten gelden niet voor dit project. De map wordt niet in git
  opgenomen. Uitzondering, met toestemming van de gebruiker: de gegevens van 7 wavetables uit
  Surge XT (GPL-3.0) zitten in de app; de bron staat in `README.md`.

## GUI-ontwerp

- **De gebruiker ontwerpt de GUI** in Inkscape en levert de ontwerpen aan als SVG-bestanden.
  Soms vraagt de gebruiker Claude om een GUI-onderdeel te ontwerpen; Claude levert dat dan ook
  als SVG, zodat de gebruiker het in Inkscape verder kan bewerken.
- **Map:** alle ontwerpbestanden staan in `design/` in de projectmap.
- **Inspiratie:** `design/GUI inspiratie/` bevat voorbeelden (o.a. Bitwig Studio) als inspiratie
  voor kleuren en vormen: donker en vlak, kleur per functie, knoppen met een gekleurde waardeboog,
  naam en waarde bij de knop. Het wordt geen 1-op-1-kopie; het eigen ontwerp van de gebruiker is
  de basis. Deze map wordt niet in git opgenomen: het zijn afbeeldingen van anderen.
- **Omzetten:** Claude zet de SVG's om naar Android-vectorafbeeldingen (VectorDrawable) of tekent
  ze na in Compose, zo getrouw mogelijk aan het ontwerp. Afwijkingen meldt Claude vooraf.
- **Richtlijnen voor de SVG's:**
  - ontwerpen op een document van 1920×1200 px (het scherm van de testtablet);
  - opslaan als *Inkscape SVG*, eenheid px, vaste documentafmeting; zo blijven de namen van
    lagen en groepen bewaard. Claude negeert de extra Inkscape-informatie bij het omzetten;
  - tekst omzetten naar paden; voor tekst die verandert (bijv. waarden) het lettertype vermelden,
    die tekst voegt Claude in de app toe;
  - geen filters (blur, schaduw), maskers, patronen of ingevoegde bitmaps; die worden in Android
    niet of slecht ondersteund;
  - bewegende onderdelen (bijv. de wijzer van een draaiknop) in aparte, duidelijk benoemde
    groepen/lagen (bijv. `knop-achtergrond`, `knop-wijzer`) of aparte bestanden; draaipunt in het
    midden; het bewegingsbereik vermelden (bijv. −135° tot +135°).

## Werkwijze bij code

- **Documentatie eerst:** zoek bibliotheekfuncties en hun argumenten op in de Faust-documentatie
  voordat je ze gebruikt; ga niet uit van het geheugen.
- **Duidelijke scheiding:** houd drie lagen strikt gescheiden:
  1. interface (scherm en bediening, Kotlin/Compose)
  2. geluidsmotor (Faust-DSP en stemmenbeheer)
  3. Android-audio-uitvoer (Oboe)
- **Realtime-veilig:** in de directe audioverwerking (de audio-callback) geen geheugen aanmaken
  en geen blokkerende handelingen (geen locks, geen bestands- of netwerktoegang, geen logging).
- **Elke DSP-wijziging controleren:** compileer iedere Faust-wijziging en voer passende
  geluidstests uit (bijv. stabiliteit, niveaus, geen ongeldige waarden) voordat je oplevert.
- **Visueel overzicht:** geef bij elke nieuwe build een begrijpelijk visueel overzicht van de
  geluidsopbouw (signaalketen), zodat de gebruiker ziet hoe de klank is opgebouwd.

## DSP-belasting en klankontwerp

- **Kwaliteit tegen belasting afwegen:** Project X is een muziekinstrument, geen wetenschappelijk
  instrument. Kies wat muzikaal goed klinkt bij een redelijke processorbelasting; perfecte
  nauwkeurigheid is geen doel op zich.
- **Hoge belasting vooraf melden:** als een onderdeel naar verwachting veel rekenkracht vraagt,
  meld dat vóór de implementatie, met uitleg waarom en met minder belastende alternatieven.
- **Verwachting vs. meting:** maak altijd duidelijk onderscheid tussen wat verwacht of geschat
  wordt en wat werkelijk gemeten is (en hoe en waar het gemeten is).
- **Alternatieven voorstellen:** stel bij het ontwerp, vóór het bouwen, alternatieven voor,
  zodat de gebruiker kiest. Bespreek bij elk alternatief de gevolgen voor:
  - **klank:** hoe het klinkt en wat het karakter is;
  - **mogelijkheden:** wat je ermee kunt doen of juist niet;
  - **DSP-gebruik:** hoeveel rekenkracht het naar verwachting vraagt.
  Dat geldt voor:
  - de interne opbouw van onderdelen: oscillatoren, filters, envelopes, modulatie en effecten;
  - hun verbindingen en volgorde in de signaalketen;
  - of iets per stem verwerkt wordt of gezamenlijk voor alle stemmen.

## Opleveren en versiebeheer

- **APK:** elke opgeleverde versie krijgt een nieuw versienummer en komt in de map `apk/` (bijv. `apk/ProjectX-v0.3.apk`). De gebruiker test de APK op een toestel.
- **Naam met versienummer:** de app heet "Project X", met het versienummer direct erachter,
  alleen onder het icoon op de tablet (bijv. "Project X v0.3"); in de GUI zelf staat geen titel.
- **Icoon:** een oranje vierkant met een witte sinusgolf.
- **Git:** Claude legt een versie pas vast (commit) als de gebruiker erom vraagt of de versie goedkeurt. De repository staat openbaar op GitHub; Claude uploadt (pusht) pas als de gebruiker erom vraagt, en stuurt niets naar een andere externe dienst zonder toestemming. Commits gebruiken het anonieme GitHub-adres van de gebruiker, niet het persoonlijke e-mailadres. Nieuwe bestanden van anderen (afbeeldingen, code, gegevens) gaan pas in git als hun licentie openbaar delen toelaat.
