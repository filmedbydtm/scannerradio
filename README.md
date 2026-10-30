# Detroit Scanner Filter (Android)

Listens to Broadcastify scanner feeds (for example Detroit Police and Detroit Fire), turns each
transmission into text on the phone, and only plays the calls you want.

## How it works

1. **Capture:** each feed's MP3 stream is decoded and split into transmissions with a squelch gate.
2. **Speech-to-text:** each transmission is transcribed offline with [Vosk](https://alphacephei.com/vosk/)
   (a one-time ~40 MB model download from inside the app).
3. **Filter:** the text is checked against the rules (editable in the app):

   | Order | Rule | Result |
   |---|---|---|
   | 1 | Under 2 seconds | never played |
   | 2 | 7+ spoken digits in a row (phone number) | never played |
   | 3 | `NEVER` categories (shift change, login, hang-ups, supervisor/sergeant requests, child, mental health, ...) | never played |
   | 4 | `PLAY+` categories (accidents, ShotSpotter, shots fired, fires, DDC, DUI, dogs, GSW, pursuits, narcotics, use of force) | played unedited, even if EMS is mentioned |
   | 5 | `MEDICAL` | never played |
   | 6 | `PLAY` categories (Green Light, gas stations, party/liquor stores) | played unedited |
   | 7 | `REDACT` categories (intoxicated, B&E, A&B, FA, noise, unknown problem, descriptions, missing persons) | numbers, months and dates of birth are bleeped; low priority; deleted after 5 minutes; dropped if the audio is too unclear to edit |
   | 8 | Anything else | ignored |

4. **Play:** calls play oldest first with a 3-second pause. When the queue backs up (oldest waiting
   call is over 60 s old), catch-up mode drops anything over 5 minutes old and plays priority calls
   before edited ones.

## Setup on the phone

1. Install `ScannerFilter.apk` (allow "install unknown apps" for your browser or file manager).
2. Tap **Download speech model**.
3. Under **Feeds**, add one line per feed: `Detroit Police | 13671` (a feed number, feed page link, or stream URL all work). Detroit Police Dispatch (13671) and Detroit Fire (18629) are filled in by default on a fresh install.
4. Tap **Start** (it saves your settings first).

If you hear nothing, turn on **Test mode**: it plays every transmission so you can confirm the feed and
sound work. The status area shows each feed's connection state, data rate and audio level.

The **Activity** list shows every decision (queued, blocked, ignored, with the transcript) so you can
tune the keyword lists. Raise the squelch level if static triggers false calls.

## Limits

- Speech recognition on radio audio is imperfect. A misheard word can let a call through or block
  one, and a misheard number may not get bleeped. Check the Activity list and adjust keywords.
- In edited calls every spoken number is bleeped, including unit numbers.
- Some radio talkgroups are encrypted and will not be on Broadcastify at all.
- Broadcastify's terms cover how its feeds may be used. Read them before rebroadcasting audio.

## Building

GitHub Actions builds the APK on every push to `main` (`.github/workflows/build-apk.yml`) and
attaches it to a release named **latest**. Locally: `./gradlew assembleRelease` with the Android SDK
installed.
