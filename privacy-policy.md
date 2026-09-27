# Privacy Policy

**Effective Date:** 2026-09-27

**Mobitool** is a free multitool by vasmarfas ("we," "our," or "us"). It is published as native
applications for Android, iOS, Windows, macOS and Linux and, together with the personal website
vasmarfas, as a web app at vasmarfas.com and vasmarfas.ru (together, the "App"). This Privacy Policy
explains what the App does with your information.

## 1. What we collect

The App has no accounts, no sign-in, no advertising and no server of ours that receives data from
you. It does collect usage statistics, crash reports and startup timings through third-party
services. The collection starts with the App and there is no switch inside it that stops it.

The App sends these events and nothing else:

- `app_open` and `screen_view`: the App was started, a screen was opened (`screen_name` is a tab
  such as `tools` or `settings`, or `tool_` followed by the tool identifier).
- `tool_open`, `tool_action`, `result_copy`, `tool_error`: a tool was opened (with the place it was
  opened from: search, a category, the home screen, a link to the website that opened the Android
  app), its main button was pressed, a result was copied, the tool showed an error. The parameters
  are the tool and category identifiers.
- `tool_pin`, `tool_unpin`, `home_remove`, `home_remove_cancel`, `home_add_tools`,
  `home_section_toggle`: a tool was added to or removed from the home screen (favourites on the
  website), a home screen section was folded or unfolded.
- `search`, `search_empty`, `catalog_filter`: the catalogue was searched, with the number of
  results, or filtered by a category.
- `onboarding_start`, `onboarding_step`, `onboarding_back`, `onboarding_skip`, `onboarding_role`,
  `onboarding_interest`, `onboarding_tool_toggle`, `onboarding_complete`: the questions shown on the
  first start of the apps. Which step was reached, which of the fixed answers were picked, which
  proposed tools were kept and how many seconds it took.
- `settings_change`: the language, theme, accent colour, dynamic colour or table width was changed,
  with the new value.
- `link_open` and `support_open`: a link on the profile, résumé or settings page was opened (the
  kind of link and the part of the page it sits in, not its address).
- `scroll_depth`: a page was scrolled past 25, 50, 75 or 100 percent, with the name of the screen.
- `ui_click` (website, Yandex.Metrica only): a button, link or tab was pressed on a page of the site
  outside the tools, with the screen and the text of that element as the page shows it.

Event parameters contain only identifiers matching `[a-z0-9_-]` from the App's own catalogue and
counters. There are two exceptions. The catalogue search query is sent as `search_term`, but only when
it looks like a tool name: 2 to 30 letters, spaces and hyphens with at most four digits. A query
with an e-mail, a phone number, an IP address or a link in it fails this check and is not sent. And
`ui_click` carries the label of the pressed element, which is text of the site itself: text fields
and tool screens are never read for it. Nothing you type into a tool, such as addresses, domains,
passwords, text or numbers, is ever part of an event.

The App also attaches these properties to the analytics installation: the answer to the first-start
question about what you do (`role`, one or two of ten fixed values or `mixed` for more), whether the
questions were answered or skipped, the number of chosen interests and of tools on the home screen in
ranges such as `11-20`, the interface language and the theme.

The statistics are collected and processed by their operators, not by us:

- **Google, Firebase Analytics** (Android, iOS) and **Firebase Analytics for the web** (vasmarfas.com only,
  vasmarfas.ru does not load it). Collects an app instance identifier, device model, OS and application
  version, the country derived from the IP address, and session data. On vasmarfas.com it also records
  the address and title of the page, sets the `_ga` and `_ga_*` cookies and keeps its installation data
  in the browser's IndexedDB. On iOS the build carries no advertising identifier support: the IDFA is not
  read, the identifier for vendor is not collected and no App Tracking Transparency prompt is shown.
  https://policies.google.com/privacy
- **Google, Firebase Crashlytics** (Android, iOS). On a crash it sends the stack trace, the device model,
  the OS and application version and an installation identifier.
- **Google, Firebase Performance Monitoring** (Android). Measures startup time, screen rendering, memory
  and CPU use. For every HTTP request the App makes it records the address without the query string, the
  response code, the size and the duration. The network tools make such requests too, so a domain or an
  IP address you look up with them can reach these reports as part of the request address.
- **Yandex, Yandex.Metrica** (website, both domains, and the Windows, macOS and Linux apps). On the
  website it sets its own cookie and collects the IP address, user agent, the pages and screens visited,
  the referrer and the events listed above. Session replay (Webvisor) and the click map are turned off.
  The desktop apps send the same screens and events to `mc.yandex.ru` over the Metrica Measurement
  Protocol. Instead of a cookie they send a random number created on the first start and kept with the
  App's settings, the user agent names the App version and the operating system, and Metrica sees the IP
  address the request comes from. https://yandex.com/legal/metrica_termsofuse/

## 2. Data stored on your device

The App keeps a small amount of data locally so that it works the way you left it:

- settings: language, theme and accent colour
- the tools on the home screen (favourites on the website), recently opened tools and the folded
  home screen sections
- the answers to the first-start questions, so that asking again starts from them
- data you enter into tools that offer to remember it: saved Wake-on-LAN devices, world clock
  zones, counters, the scratchpad, countdown events, the running stopwatch and timer, workout plans,
  calibration values, speed test sources, custom DNS resolvers and HTTP requests you save
- the last 20 HTTP requests, kept without their bodies, form fields, credentials and a login written into
  the address, and with the values of headers and query parameters whose names mention auth, cookie, key,
  token, secret, pass, session or signature left empty
- cached copies of the profile and résumé files (`profile.json`, `resume.json`) that describe the
  site's content
- a cached copy of currency exchange rates

On Android this lives in the App's private storage, on iOS in the App's user defaults, on desktop in
the user's preferences store, and in the browser in `localStorage` of the site's origin. None of it
is synced or sent anywhere, except the random number the desktop apps send to Yandex.Metrica as
described in section 1. Uninstalling the App, or clearing the site's data in the browser, removes
it.

Photo, video, audio and document tools read only the files you pick and process them on the device.
The results are saved where you choose, and nothing is uploaded.

## 3. Network requests

Apart from the analytics traffic described in section 1, the App makes network requests only when you
open a tool that needs them or explicitly start an action. Each request goes directly from your
device to the third party named below. We operate none of these services and receive nothing from
them.

- **Profile refresh.** On start the App downloads the latest `profile.json` and `resume.json` so that
  the site's text can be updated without a release. The apps and vasmarfas.com take them from
  `raw.githubusercontent.com` (the project's public repository), vasmarfas.ru from the same site at
  `vasmarfas.ru/content/`. The requests contain no personal data.
- **Project and article numbers.** On start the App asks `api.github.com` for the star count of each
  listed repository, and when the profile or projects page is open it asks `habr.com` for the view
  count of each listed article. Both are repeated at most once every six hours and contain no
  personal data.
- **DNS lookup, public DNS benchmark.** Queries go to the resolvers you pick in the tool (Cloudflare,
  Google, Quad9, AdGuard, Mullvad, DNS4EU, Comss, OpenDNS, Control D and Yandex are listed) or to any
  server you enter. The resolver sees the domain name you typed.
- **Whois / RDAP.** The domain, IP address or AS number you typed is sent to `rdap.org` and the
  registry it redirects to, and, where sockets are available, to whois servers on port 43.
- **IP info.** `ipwho.is` (with `ipapi.co` as a fallback) receives the IP address you typed. When you
  ask for your own address, the request itself reveals your public IP to that service, and
  `api.ipify.org` or `api6.ipify.org` is asked for the address of the other IP version. The network
  owner comes from `rdap.org` and the regional registry it redirects to, the host name from a reverse
  DNS query to `cloudflare-dns.com`. A host name you typed is resolved there too.
- **MAC address lookup.** The vendor is looked up in the IEEE registry that ships with the app and the
  site, nothing leaves the device for that. When you press "Check online" in the apps, the first nine hex
  digits of the address go to `api.maclookup.app` and `api.macvendors.com`: they name the block, the rest
  of the address stays on the device. The website does not make these requests.
- **Speed test.** Random data is downloaded from and uploaded to the source you pick:
  `speed.cloudflare.com`, the Yandex Internetometer at `yandex.ru/internet` (apps only), an
  OpenSpeedTest server of your own or a file URL you enter. The uploaded bytes carry no information.
- **HTTP request.** The request you compose is sent to the URL you entered, exactly as written.
- **Ping (HTTP mode), TLS certificate.** These connect to the host you entered.
- **Currency converter.** Exchange rates are fetched from `open.er-api.com`. The request contains no
  personal data.
- **Crypto converter.** Coin prices are fetched from `api.coingecko.com`. The request contains no
  personal data.
- **Have I Been Pwned check.** Only the first five characters of the SHA-1 hash of the password
  you typed are sent to `api.pwnedpasswords.com`. The password itself never leaves your device.

Tools that work with your local network (ping, traceroute, port scanner, LAN scanner,
Wake-on-LAN, device discovery, network interfaces) talk only to the addresses you specify or to
devices on your own network. Buttons such as "Open in OpenStreetMap" open that page in your browser.

## 4. Device permissions and sensors

Some tools use device capabilities, always after you open the tool and, where the platform
requires it, after you grant the permission:

- **Location** (Android, iOS, browser). The GPS tool shows your coordinates and speed on screen. On
  Android the same permission is required by the system to read the Wi-Fi network name. The
  location is not stored or transmitted.
- **Motion and environment sensors.** Compass, level, accelerometer, magnetometer, light,
  barometer and pedometer read the sensors and display the values. Nothing is recorded beyond the
  short history shown in the chart.
- **Microphone.** The sound meter, the spectrum analyzer and the tuner analyse the audio input on
  the fly. No audio is recorded or saved.
- **Camera** (Android, iOS, browser). The QR code and barcode scanner shows the camera picture on screen
  and reads the codes in it on the device. No image is stored or transmitted. A picture you pick for
  the scanner is read the same way.
- **Camera flash.** The torch tool toggles the LED without taking any image.
- **Vibration.** Used by the vibration test and by a few tools as haptic feedback.
- **Network state.** Used to show the connection details in the network interfaces tool.

## 5. Third-party services

The App uses the analytics services listed in section 1, plus the services listed in section 3,
which you trigger yourself. There are no others. The source code is
public on GitHub under a source-available license, so you can verify this yourself.

## 6. Children's privacy

The App is not directed at children and asks nobody, including children under 13, for personal
information. Google and Yandex receive the device and session data listed in section 1 regardless of
the user's age.

## 7. Your rights

We hold no personal data about you ourselves: we have no server, no accounts and no access to the
analytics raw data beyond the aggregated reports in the Firebase and Yandex.Metrica
consoles, which carry no name, e-mail or any other detail that identifies you.

The App has no switch that stops the collection. In the browser you can block the counters with an
extension, or clear the site's cookies, `localStorage` and IndexedDB, which removes the Yandex.Metrica
identifier and, on vasmarfas.com, the Google Analytics one (the `_ga` cookies). On Android the
analytics identifier belongs to the installation: clearing the App's data or reinstalling it starts a
new one. The App does not read the Android advertising identifier. Events that
were already sent are stored by Google and Yandex under their own terms, and you can request access
or deletion from them directly through the links in section 1.

## 8. Changes to this policy

We may update this Privacy Policy from time to time. Material changes will be reflected by a new
Effective Date at the top of this document and published together with the release that introduces
them.

## 9. Contact

**Developer:** vasmarfas
**Contact:** https://github.com/vasmarfas/mobitool/issues
