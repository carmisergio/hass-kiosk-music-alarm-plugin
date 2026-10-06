# Music Alarm

A [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that wakes you up to a random song from a Jellyfin playlist.

> [!CAUTION]
> This project is vibe coded slop I couldn't be bothered to write. Use at your own risk.

Kiosk Satellite alarms play a sound file from the kiosk's sounds folder. Every night this plugin picks a random song from your playlist, has Jellyfin convert it to MP3 and saves it in that folder under one fixed name. Point an alarm's tone at that name and it rings with a different song each day.

The song is on the kiosk before the alarm rings, so:

- The alarm plays it on the Android alarm stream, with the alarm volume, ease in, sunrise, Snooze and Stop working as usual.
- The alarm still rings if Jellyfin or the network is down at ring time. A failed refresh keeps the previous song.

## Setup

1. In Jellyfin, create an API key under **Dashboard, API Keys**.
2. Install the plugin, enable it and fill in the settings below.
3. Wait for the status to say **Wake song: …**, or run **Pick a new song now**.
4. Open the alarm's details, tap **Alarm tone** and choose `wake.mp3` (or the name you set). You can also set it as the default **Alarm tone** under **Settings, Alarms**.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Server address | | The Jellyfin server, for example `http://jellyfin:8096`. |
| API key | | A Jellyfin API key. Kiosk Satellite stores plugin settings in plain text, so use a key made for this plugin. |
| User | | The Jellyfin user who owns the playlist, by name or ID. Playlists belong to a user, so set this if the playlist is not found. |
| Playlist | | The playlist name or item ID. An exact name match wins, otherwise the name must match one playlist. |
| Sound file name | `wake.mp3` | The file written to the sounds folder. Must end in `.mp3`. |
| Avoid repeats | on | Skip the last ten songs when the playlist has others. |
| Nightly refresh hour | `3` | The hour, in the kiosk's time zone, when a new song is picked. |

A new song is also picked when the plugin starts and the file is missing, a few seconds after the Jellyfin settings or the file name change, and from the **Pick a new song now** command. Kiosk Satellite can run that command from a gesture, the kiosk drawer or an ESPHome button in Home Assistant.

## Home Assistant

With the kiosk's ESPHome connection set up, the **Wake song** text sensor shows the current song as `Artist – Title`.

## How it finds the sounds folder

Kiosk Satellite keeps alarm sounds in `Android/data/me.jxl.kiosk_satellite/files/sounds` on the primary external storage. The plugin SDK has no file API, so the plugin rebuilds that path from `Environment.getExternalStorageDirectory()`, which Android derives from the same volume as the app's own files folder. The plugin runs inside the app, so it needs no storage permission.

The `files` part of the path belongs to Kiosk Satellite. If a future version moves it, the plugin reports **Kiosk Satellite's files folder is missing** instead of writing somewhere else.

The plugin also keeps `.music-alarm-state` in the sounds folder, holding the current song and the recently played IDs. Kiosk Satellite does not list it as a tone.

## Requirements

- Kiosk Satellite with plugin SDK 1.
- Jellyfin 10.9 or newer.

## Build

Install Python 3, JDK 17 or newer and an Android SDK with a platform and build-tools, then:

```sh
python3 tools/test.py
python3 tools/build.py
```

The package lands in `dist/`. Install it for testing with **Plugin Manager, Developer Tools, Install from ZIP**. Publishing a GitHub release runs `.github/workflows/build.yml`, which builds and attaches the files Kiosk Satellite needs to install from the repository.

## License

[Apache-2.0](LICENSE). The SDK interfaces under `sdk/` and the build tools under `tools/` come from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) under the same license.
