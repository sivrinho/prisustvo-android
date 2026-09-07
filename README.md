# Prisustvo – Android aplikacija (MVP)

Jednostavna offline aplikacija za nastavnika. Svi podaci se čuvaju lokalno u Room/SQLite bazi na telefonu; aplikaciji ne treba internet, registracija ni server.

## Šta trenutno radi

- kreiranje više razreda / odjeljenja
- unos predmeta i školske godine
- unos i brisanje učenika
- 36 nastavnih tjedana
- automatski datum pri prvom otvaranju tjedna + ručna promjena datuma
- brzo dugme **Označi sve prisutne**
- pojedinačno označavanje **+ prisutan** / **− odsutan**
- za odsutnog: **Odsutan / Opravdano / Neopravdano**
- automatsko spremanje svakog klika
- podaci ostaju lokalno na telefonu nakon zatvaranja aplikacije

## Build APK-a

GitHub Actions workflow automatski builda debug APK nakon push-a na `main`. APK se preuzima iz **Actions → Build Android APK → Artifacts → Prisustvo-APK**.

## Tehnologije

- Kotlin
- Jetpack Compose
- Room / SQLite
- minSdk 26
- compileSdk / targetSdk 37
