<!-- placeholders -->
app: [app name]
developer: [developer]
contact: [contact e-mail]
country: [country]
date: [date]
deletion-url: [deletion page]
<!-- always -->
# Privacy Policy

**Effective date:** {date}
<!-- if app !site -->
This Privacy Policy explains what information the {app} mobile app (the "Service") collects, why, and what you can do about it. The Service is developed by {developer} ("we", "us").
<!-- if site !app -->
This Privacy Policy explains what information the {app} website (the "Service") collects, why, and what you can do about it. The website is run by {developer} ("we", "us").
<!-- if both -->
This Privacy Policy explains what information the {app} mobile app and the {app} website (together, the "Service") collect, why, and what you can do about it. The Service is developed by {developer} ("we", "us").
<!-- if nothing -->
## Information we collect

The Service does not collect personal information. It has no accounts, no analytics, no advertising and no crash reporting, and nothing you enter into it is sent to us.
<!-- if nothing app -->
Settings and other data you create in the app are stored only on your device and are removed when you uninstall the app.
<!-- if nothing site -->
The website sets no tracking cookies. The hosting provider may keep standard server logs with the IP address, the browser, the requested page and the time, which are used for security and troubleshooting only.
<!-- if !nothing -->
## Information we collect

We collect only the information described below and use it only for the purposes stated next to it.
<!-- if analytics -->
### Usage statistics

To understand how the Service is used and what to improve, we collect usage statistics: which screens are opened and which features are used, the start and length of sessions, the device model, the operating system and its version, the app version, the language and the country or region derived from the IP address. The statistics are tied to a random identifier of the installation, not to your name or e-mail, and we see them as aggregated reports.
<!-- if analytics site -->
On the website the analytics services use cookies and similar browser storage to tell visits apart. You can block or delete them in the browser settings, and the website keeps working without them.
<!-- if crashes -->
### Crash reports

When the Service crashes or runs into a serious error, it sends a report with the stack trace, the device model, the operating system and app version, the state of the device at the time of the error, such as free memory and orientation, and a random installation identifier. We use the reports only to find and fix errors.
<!-- if ads -->
### Advertising

The Service shows ads. To choose, show and measure them, the advertising networks collect the advertising identifier of the device (the Android advertising ID or Apple's IDFA), the IP address and the approximate location derived from it, information about the device and the app, and your interactions with the ads.
<!-- if ads app !children -->
On iOS the advertising identifier is used only if you allow tracking when the system asks. On Android you can reset the advertising ID or delete it in the system settings. Without the identifier you still see ads, but they are not based on your interests.
<!-- if ads children -->
Because the Service is directed at children, ads are requested as child-directed: they are not personalized, are not based on past activity and are limited to ad networks certified for apps for children.
<!-- if accounts -->
### Account

When you create an account, we store your e-mail address, your password in a protected form or a sign-in token from the provider you chose, such as Google or Apple, the account identifier and the data you save in the Service. We use them to sign you in, to keep your data across devices, to restore access and to send you messages about the account and the Service. We do not send advertising e-mails unless you agreed to receive them.
<!-- if location -->
### Location

If you allow it, the Service uses the location of your device for the features that need it. The location is used while you use such a feature, and you can withdraw the permission at any time in the system settings. The rest of the Service keeps working without it.
<!-- if camera -->
### Camera and photos

If you allow it, the Service uses the camera and the photos you pick for the features you use them in. Pictures are processed on your device and leave it only when you send them yourself, for example when you attach a photo to your account or share it. The Service does not scan your photo library.
<!-- if purchases -->
### Purchases

Payments are processed by Google Play, the App Store or another payment provider. We do not receive your card number or other payment details. We get a confirmation of the purchase with the product, the price, the time and an order identifier, and use it to give you access to what you paid for, to restore purchases and to handle refunds.
<!-- if sdk -->
## Third-party services

The Service uses the services listed below. Each of them processes data under its own privacy policy.
<!-- if sdk:firebase-analytics -->
- **Google Analytics for Firebase** (Google LLC) collects the usage statistics described above with an app instance identifier and, on Android, the advertising ID. It derives the approximate location from the IP address and records in-app purchases on its own. [Google Privacy Policy](https://policies.google.com/privacy), [how Google uses information from sites or apps that use its services](https://policies.google.com/technologies/partner-sites).
<!-- if sdk:google-analytics -->
- **Google Analytics** (Google LLC) collects the pages you visit, events on them, the browser and device, the referrer and the approximate location derived from the IP address, which itself is not stored. It sets the `_ga` and `_ga_*` cookies for two years. [Google Privacy Policy](https://policies.google.com/privacy), [how Google uses information from sites or apps that use its services](https://policies.google.com/technologies/partner-sites).
<!-- if sdk:appmetrica -->
- **AppMetrica** (YANDEX LLC) collects the usage statistics and crash reports described above with the AppMetrica device identifier and the advertising identifier, records in-app purchases and keeps the IP address to determine the region. Yandex processes this data on our behalf. [Yandex Metrica and AppMetrica terms of use](https://yandex.com/legal/metrica_termsofuse/), [Yandex Privacy Policy](https://yandex.com/legal/confidential/).
<!-- if sdk:yandex-metrica -->
- **Yandex Metrica** (YANDEX LLC) collects the pages you visit, events on them, the IP address, the browser and device and the referrer and sets its own cookies, such as `_ym_uid` for one year. Yandex processes this data on our behalf. You can block the counter with a browser extension that blocks ads and trackers. [Yandex Metrica and AppMetrica terms of use](https://yandex.com/legal/metrica_termsofuse/), [Yandex Privacy Policy](https://yandex.com/legal/confidential/).
<!-- if sdk:crashlytics -->
- **Firebase Crashlytics** (Google LLC) collects the crash reports described above with a Crashlytics installation identifier and keeps them for 90 days. [Firebase privacy and security](https://firebase.google.com/support/privacy), [Google Privacy Policy](https://policies.google.com/privacy).
<!-- if sdk:sentry -->
- **Sentry** (Functional Software, Inc.) collects the error reports described above. With the settings we use it does not send IP addresses or other personal data. [Sentry Privacy Policy](https://sentry.io/privacy/).
<!-- if sdk:admob -->
- **Google AdMob** (Google LLC) shows ads. Google and its partners collect the advertising identifier and the app set ID, the IP address, from which they estimate the general location, information about the device and the app's performance and your interactions with the ads, and may use cookies and similar identifiers for this. [Google Privacy Policy](https://policies.google.com/privacy), [how Google uses information from sites or apps that use its services](https://policies.google.com/technologies/partner-sites).
<!-- if sdk:play-billing -->
- **Google Play Billing** (Google LLC) processes purchases in the Android app. Google handles the payment, and we receive the order identifier, the product and the state of the purchase. [Google Privacy Policy](https://policies.google.com/privacy), [Google Payments privacy notice](https://payments.google.com/payments/apis-secure/get_legal_document?ldo=0&ldt=privacynotice).
<!-- if sdk:app-store -->
- **App Store in-app purchases** (Apple Inc.) process purchases in the iOS app. Apple handles the payment, and we receive the transaction identifier and the product. [Apple Privacy Policy](https://www.apple.com/legal/privacy/en-ww/).
<!-- if !nothing -->
## Why we process it
<!-- if accounts|purchases|location|camera -->
- To provide the features you use and to keep your account and purchases, which is needed to perform our agreement with you.
<!-- if crashes -->
- To find and fix errors, which is our legitimate interest in a Service that works.
<!-- if analytics -->
- To understand how the Service is used and what to improve, which is our legitimate interest, or your consent where the law requires it.
<!-- if ads -->
- To show ads that pay for the Service, on the basis of your consent where the law requires it and of our legitimate interest otherwise.
<!-- if !nothing -->
We do not sell personal information and do not use it to make automated decisions that have legal or similarly significant effects on you.

## Who receives it

We share data only with the services named in this policy and with the providers that host and run the Service for us, to the extent they need it, and with public authorities when the law requires it. If the Service passes to another owner, the data passes with it under the terms of this policy.
<!-- if country !nothing -->
We are based in {country}. The services we use may store and process data in other countries under the safeguards described in their policies.
<!-- if !nothing -->
## How long we keep it
<!-- if analytics|crashes|ads -->
Statistics, reports and advertising data are kept for the retention periods set in the services that collect them and are then deleted or anonymized.
<!-- if accounts -->
Account data is kept while the account exists. After the account is deleted, its data is removed within 30 days, except what the law requires us to keep longer, such as payment records.
<!-- if purchases !accounts -->
Purchase records are kept for as long as accounting and tax law require.
<!-- if location|camera !accounts -->
Location, photos and pictures from the camera are not stored by us.
<!-- always -->
## Children
<!-- if !children -->
The Service is not directed at children under 13, and we do not knowingly collect personal information from them. If you believe a child has given us personal information, write to {contact} and we will delete it.
<!-- if children -->
The Service is meant for an audience that includes children under 13. We do not knowingly collect personal information from them beyond what the law allows without the consent of a parent, do not show them personalized ads and ask for no more information than a feature needs. A parent or guardian can ask us what we know about their child and have it deleted by writing to {contact}.
<!-- if children accounts -->
A child under 13 can create an account only with the consent of a parent or guardian.
<!-- if nothing -->
## Your rights

We hold no personal information about you, so there is nothing to show, correct or delete. If you have a question about this policy, write to {contact}.
<!-- if !nothing -->
## Your rights and deleting data

You can ask us what data we hold about you, get a copy of it, have it corrected or deleted, object to its processing and withdraw a consent you gave. Write to {contact}, and we will answer within 30 days. Depending on where you live, these rights come from the GDPR in the EU and the UK, the CCPA in California or Federal Law No. 152-FZ in Russia, and you can also complain to your data protection authority.
<!-- if in-app-deletion -->
You can delete your account and the data linked to it in the app itself, in the account settings.
<!-- if deletion-url -->
You can also ask to delete your account and its data without the app, on this page: {deletion-url}
<!-- if analytics|crashes|ads -->
Statistics, crash reports and advertising data are tied to a random identifier rather than to your name or e-mail, so we cannot find them by your name.
<!-- if analytics|crashes|ads app -->
Reinstalling the app starts a new identifier.
<!-- if analytics|ads site -->
Clearing the website's cookies in the browser starts a new identifier.
<!-- if !nothing -->
## Security

Data is sent over encrypted connections, and access to it is limited to the people who need it to run the Service. No way of sending or storing data is completely secure. If a breach affects your data, we will notify you as the law requires.
<!-- always -->
## Changes to this policy

We may update this policy. The new version is published at the same address with a new effective date. If the changes are significant, we will tell you in the Service before they take effect.

## Contact

**Developer:** {developer}

**Contact:** {contact}
<!-- if country -->
**Country:** {country}
