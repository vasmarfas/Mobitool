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
- Wi-Fi coverage maps: the floor plan picture you chose, reduced to 1600 pixels, and for every measured
  spot its place on the plan, the signal of the networks heard there, latency and speed
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

Photo, video, audio and document tools and the email header analyzer read only the files you pick and process them on the device.
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
  server you enter. The resolver sees the domain name you typed. In the apps you choose the protocol:
  UDP or TCP on port 53 go unencrypted, so the network between you and the resolver sees the name too,
  DNS over TLS and DNS over QUIC (port 853) and DNS over HTTPS are encrypted.
- **DMARC analyzer.** The domain you typed, its parent domains when it has no record of its own and
  the authorization records of report addresses on other domains are looked up over DNS over HTTPS at
  `cloudflare-dns.com`. Reports you open or paste are read on the device. Only when you press "Look up
  host names" are the sender IP addresses from the report sent to `cloudflare-dns.com` as reverse DNS
  queries.
- **SPF checker.** The domain you typed, the domains its record names in include and redirect, and their
  A, AAAA and MX records are looked up over DNS over HTTPS at `cloudflare-dns.com`. When you check an IP
  address against a record with an `exists` mechanism, a name built from that address goes to the same
  resolver.
- **DKIM checker.** The domain and selector you typed, or 13 common selectors when the field is empty,
  are looked up as `<selector>._domainkey.<domain>` over DNS over HTTPS at `cloudflare-dns.com`.
- **Connection check.** The tool pings `1.1.1.1` and `8.8.8.8` (falling back to TCP port 443), resolves
  `one.one.one.one` through the system resolver and over DNS over HTTPS at `cloudflare-dns.com`, fetches
  `connectivitycheck.gstatic.com/generate_204` over plain HTTP to detect a sign-in page,
  `www.cloudflare.com/cdn-cgi/trace` to test HTTPS and compare the clock, and `api6.ipify.org` to test IPv6.
  In the browser `1.1.1.1/cdn-cgi/trace` stands in for the ping. These services see your public IP address,
  as with any request. The router gets pings and an SSDP query on the local network.
- **Site availability check.** The domain you typed is resolved by the system resolver and over DNS over
  HTTPS at `cloudflare-dns.com` and `dns.google`, the app connects to the site on ports 443 and 80 and requests
  its home page. The domain also goes to `api.globalping.io`, whose probes open the site from other networks;
  the country for two of them comes from `www.cloudflare.com/cdn-cgi/trace`.
- **Public IP and NAT check.** The outside address is asked from several services at once, so a VPN that wraps only
  part of the traffic shows up: `ipv4-internet.yandex.net`, `ip.beget.ru` (apps only), `1.1.1.1/cdn-cgi/trace`,
  `api.ipify.org` and `api6.ipify.org`. STUN requests go to `stun.l.google.com`, `stun.cloudflare.com`, `stun.sipnet.ru` and
  `stun.zadarma.com`, in the browser through WebRTC. Each address found is looked up at `stat.ripe.net` for its network owner. In the apps the
  router is asked for its outside address over UPnP and NAT-PMP, and pings with a short TTL towards `1.1.1.1` show
  the first hops of the route. These services see your public IP address, as with any request.
- **Cellular signal.** The cell list comes from the operating system and stays on the device. The modem tab
  asks the modem address you entered in your local network for its signal page.
- **Wi-Fi analyzer.** Scanning reads the list of networks from the operating system, nothing leaves the
  device for that. A spot on a coverage map pings the router, or `1.1.1.1/cdn-cgi/trace` in the browser, and
  with "Measure speed" on downloads random data from `speed.cloudflare.com` for three seconds.
- **Whois / RDAP.** The domain, IP address or AS number you typed is sent to `rdap.org` and the
  registry it redirects to, and, where sockets are available, to whois servers on port 43. An IP address or AS
  number also goes to `stat.ripe.net` for its routing.
- **Exact time.** The apps send NTP requests to `ntp1.vniiftri.ru`, `time.google.com`, `time.cloudflare.com`,
  `time.apple.com`, `pool.ntp.org` and `time.windows.com`, the website reads the time from
  `www.cloudflare.com/cdn-cgi/trace`. A request carries only the device's current time.
- **TCP and UDP client.** What you typed is sent to the host and port you entered, exactly as written.
- **File transfer.** In the apps, while the tool is open, the device announces itself on the local network to the
  multicast group `224.0.0.167` on port 53317, as the LocalSend protocol does: its name, model and a random identifier.
  It answers devices that announce themselves the same way, and a refresh of the device list asks every address of
  the local /24 network on port 53317. Files and text go straight to the device you pick and arrive the same way, over
  HTTPS when that device uses it (its certificate is checked against the fingerprint it announced) and over plain HTTP
  otherwise. The browser link serves its page only under a random path. Over the internet the two devices first swap,
  through `ppng.io` or the piping server you set, encrypted notes with their names and the addresses they can be
  reached at: local addresses, public IPv6 addresses, a port the app asks your router to open over NAT-PMP or UPnP
  while the transfer waits, closed again when it ends, and the outside address of a UDP socket, learned from the STUN
  servers listed above. To find each other when the relay cannot be reached, both devices also join the BitTorrent
  DHT as read-only nodes, starting from `router.bittorrent.com`, `dht.transmissionbt.com`, `router.utorrent.com` and
  `dht.libtorrent.org`: each one announces that UDP socket under a hash made from the code and looks up the other
  side's hash. DHT nodes see the IP address, the port and the hash, which tells nothing about the files or the code.
  The devices then send each other probes sealed with the key until one gets through. If a direct connection opens,
  the files go straight between the devices; otherwise the relay passes the stream without storing it. With encryption on, the code on screen is the key
  (PBKDF2 and ChaCha20-Poly1305) and never leaves the two devices, so the relay sees only ciphertext, its size and the
  IP addresses of both sides. With encryption off, the relay passes the file as it is to whoever opens the link.
  Received files are saved to Download/Mobitool on Android, to the Downloads folder on a computer, to the app's folder
  in Files on iOS and to the browser's downloads on the website.
- **IP info.** `ipwho.is` (with `ipapi.co` as a fallback) receives the IP address you typed. When you
  ask for your own address, the request itself reveals your public IP to that service, and
  `api.ipify.org` or `api6.ipify.org` is asked for the address of the other IP version. The network
  owner comes from `rdap.org` and the regional registry it redirects to, the host name from a reverse
  DNS query to `cloudflare-dns.com`. A host name you typed is resolved there too.
- **MAC address lookup.** The vendor is looked up in the IEEE registry that ships with the app and the
  site, nothing leaves the device for that. When you press "Check online" in the apps, the first nine hex
  digits of the address go to `api.maclookup.app` and `api.macvendors.com`: they name the block, the rest
  of the address stays on the device. The website does not make these requests.
- **Speed test.** Random data is downloaded from and uploaded to the source you pick, and while it runs
  small requests to the same source measure the latency under load:
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
  Android and iOS the same permission is required by the system to read the Wi-Fi network name; on Android, in precise
  form, to list nearby Wi-Fi networks in the Wi-Fi analyzer and mobile cells in the cellular tool, which never
  read the location itself. The
  location is not stored or transmitted.
- **Bluetooth** (Android, iOS, Windows, macOS, Linux). The Bluetooth radar receives the advertising packets of nearby devices and
  shows them on screen: names, makers, signal strength. On Android 12 and newer the permission is declared as never
  used for location. Nothing is stored or transmitted. File transfer over Bluetooth hands the files to Android's own
  Bluetooth sharing.
- **Nearby Wi-Fi devices** (Android 13 and newer, location on older versions). Needed only to start the phone's own
  hotspot for a file transfer. The network name and password are shown on screen and dropped when the hotspot stops.
- **NFC** (Android). While the file transfer screen shows a link, the phone answers as an NFC tag carrying that link,
  so another phone held to it opens the link. Nothing else is sent over NFC.
- **Local network** (iOS). File transfer, device discovery and the LAN tools reach devices on your network.
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
- **Network state.** Used to show the connection details in the network interfaces tool and the connection check.

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
