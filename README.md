# Mobitool

A personal site and a free multitool in one app: the site is vasmarfas, the app is Mobitool. One Kotlin
Multiplatform / Compose Multiplatform codebase builds the site on WebAssembly
([vasmarfas.com](https://vasmarfas.com), [vasmarfas.ru](https://vasmarfas.ru)) and native apps for Android,
iOS, Windows, macOS on Apple Silicon and Linux.

**Русский:** [`README.ru.md`](README.ru.md) · **Support:** [`SUPPORT.md`](SUPPORT.md) ·
**Privacy:** [`privacy-policy.md`](privacy-policy.md) · **Terms:** [`terms.md`](terms.md)

## What is inside

A profile page with contacts, projects, articles and a CV, and a catalogue of offline tools in 18
categories with search and recents. In the apps the first tab is Home. On first start the app asks
what you do and what you are into and puts the matching tools there. The site keeps favourites
instead. Anything that can be computed locally runs everywhere. Network tools and sensors work where
the OS provides them, and the catalogue shows which platforms each tool runs on.

| Category | Examples |
|---|---|
| Measure and sensors | ruler, bubble level, compass, protractor, GPS and speedometer, light meter, pedometer, metal detector, accelerometer, barometer |
| Calculators | scientific, percentages, fractions with the working shown, renovation (wallpaper, paint, laminate, tiles), tire size |
| Converters | units, cooking measures, clothing sizes, numbers to words, Roman numerals, number bases |
| Money | currencies, cryptocurrencies, loans, discounts and VAT, tips, expense splitting, unit price, compound interest, fuel cost, electricity cost |
| Photo, video and audio | image converter and compressor, photo editor, video converter, multitrack video editor, audio converter and editor, GIF maker, photo metadata, frame from video |
| Documents and PDF | PDF editor (editing the page's own text and pictures, signatures, forms, highlights, redaction, watermark, page numbers, password), document converter (DOCX, ODT, EPUB, FB2, HTML, Markdown, RTF), images to PDF, merging, page editing, PDF to images and text, removing a password, ZIP |
| Time | stopwatch, timer with Pomodoro, chess clock, world clock, date and age calculators, event countdowns, calendar, sunrise and sunset, working hours, Unix time |
| Everyday | dice, decision wheel, team splitter, tally counter, notepad, number checker (cards, IBAN, INN, SNILS, OGRN, bank accounts, IMEI, ISBN, EAN, VIN) |
| Text | counters, case, diff, find and replace, transliteration, keyboard layout, cleanup, mojibake fixer, line tools, lorem ipsum, Morse, phonetic alphabet, ciphers, Unicode, ASCII |
| Sport and health | workout timer and builder, breathing exercise, BMI, body metrics, calorie burn, pace, water, macros, sleep, heart-rate zones, one-rep max, blood pressure, caffeine, blood alcohol, VO2 max, HbA1c, reaction time |
| Sound | sound meter, tone and noise generators, metronome, spectrum analyzer, tuner |
| Color and design | QR and barcodes, color codes and palettes, a palette from a photo with an eyedropper, gradients, contrast, Material 3 scheme, typography, CSS units, aspect ratio, paper sizes |
| Security | password generator and strength, breach check (HIBP k-anonymity), TOTP, random data, checksum comparison |
| Device | device info, screen test, battery, display, touch and keyboard testers, screen light and torch, vibration, benchmarks, stress test, clipboard |
| Developer | JSON, Base64, hashes and HMAC, UUID, programmer calculator, floating-point inspector, regex tester and builder, URL and punycode, JWT, Markdown, CSV, cron, HTTP codes, escaping, chmod, semver, MIME |
| Network | speed test, my IP, ping (ICMP/TCP/HTTP), DNS over HTTPS and UDP, whois/RDAP, traceroute, port and LAN scanners, Wake-on-LAN, subnet calculator and splitter, HTTP requests, TLS certificates, public DNS, interfaces and Wi-Fi, UPnP/Bonjour, MAC lookup, port reference, data size and transfer time |
| Electronics | Ohm's law, resistor color code, LED resistor, voltage divider, battery life, wire gauge |
| 3D printing | print and resin cost, filament picker and table of 51 plastics, filament length and weight, time estimate, layer and nozzle, E-steps and flow, temperature tower, shrinkage, G-code cheat sheet |

Texts come in Russian and English and switch instantly. The profile content is data rather than code:
`profile.json` and `resume.json` ship inside the app and are refreshed from the network at startup,
so the site and installed apps pick up changes without a release.

## Stack

Kotlin 2.4, Compose Multiplatform 1.13 (alpha), Material 3 Expressive (`MaterialExpressiveTheme`,
`MotionScheme`, `WideNavigationRail`, `ShortNavigationBar`, wavy indicators), navigation-compose
bound to browser history, Ktor 3, kotlinx-serialization, kotlinx-datetime, material-kolor (scheme
from a seed colour, dynamic colours on Android 12+), qrose (QR and barcodes). The web target is
`wasmJs` only.

## License

The source is available under [PolyForm Strict License 1.0.0](LICENSE) with the additions in
[NOTICE.md](NOTICE.md). This is a personal site and a personal app: the code is published so it can
be read and audited, not reused. Reading it, building it for yourself and using the official builds
are fine; modifying and distributing it are not. Pull requests are still welcome, and forking to
prepare one is the normal way to do that. Third-party components are listed in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
