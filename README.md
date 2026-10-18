# Custom Music Discs

A Fabric mod for **Minecraft 26.3+** that lets players burn their own songs onto music discs. Burned discs
behave like vanilla discs: they play in a normal jukebox, everyone nearby hears them, and hoppers, comparators,
eject and the "Now Playing" message all work.

## How to use it

1. Craft a **Blank Music Disc**: 4 black dye around 1 amethyst shard.
2. Craft a **Disc Burner**: 5 iron ingots around a jukebox, with 3 redstone along the bottom row.

   ```
   I I I
   I J I
   R R R
   ```
3. Open the Disc Burner and put a blank disc in the left slot.
4. **Drag an audio file onto the game window**, or press **Browse...** to pick one. Browse opens your computer's
   file dialog. If that can't open, it switches to a file browser inside the game. You can also choose the
   in-game browser in the settings.
   MP3, FLAC, M4A/AAC, WAV, OGG and OPUS all work (anything ffmpeg can read).
5. Type a **song title** and an **artist**. If the file has tags, these are filled in for you.
6. Optional: click **Clean** to switch it to **Explicit** (see below).
7. Press **Burn**. The song is compressed and uploaded, and the finished disc appears in the right slot.
8. Put the disc in any jukebox.

### Albums

Pick **several files** in Browse (or drag several onto the window) to burn a whole album onto one disc. Tracks
are ordered by their disc and track number tags, then by file name. Album title and artist come from the tags.
Hover the "N files" line to see the track list. In a jukebox the tracks play one after another, and the tooltip
lists them.

### Shuffle

For albums, click **In order** to switch it to **Shuffle** before burning. You can change it later by sneaking
and using the disc while holding it. Each time the disc starts, it picks a new random order. Everyone nearby
hears the same order.

### Explicit discs

Click **Clean** to switch it to **Explicit** when burning. Explicit discs show a red "Explicit" line in their
tooltip. Any player can turn on **Explicit Discs: Muted** with the button at the bottom of the **pause menu**,
or in the settings (the **Discs...** button in the pause menu, or **`/customdiscs`**). They won't hear those
discs, and they'll see "Explicit disc muted" instead. It's a per-player setting. Other players still hear the
disc, and it only works if whoever burned the disc marked it.

## About MP3 and Ogg Vorbis

Minecraft's sound engine can only play **Ogg Vorbis**, not MP3. Every song is converted to compressed
**Ogg Vorbis**, a lossy format like MP3. At the same bitrate it is the same size or smaller and sounds as good or
better. Lossless audio would be far too slow to send over relayed connections like Essential or e4mc.

Songs are converted to **mono, 96 kbps** by default (about 0.7 MB per minute). Mono is required: Minecraft only
places mono sounds in the world, so a stereo disc would be heard at full volume everywhere instead of coming from
the jukebox. Vanilla discs are mono for the same reason.

## ffmpeg

The conversion runs on **your** computer, so only the small compressed file is sent over the network. It
uses [ffmpeg](https://ffmpeg.org/):

- **Windows and macOS:** if ffmpeg isn't found, the Disc Burner shows a **Download ffmpeg** button. It saves a
  copy to `.minecraft/custom_discs/ffmpeg/`. Windows uses
  [BtbN's builds](https://github.com/BtbN/FFmpeg-Builds); macOS uses [evermeet.cx](https://evermeet.cx/ffmpeg/),
  which is an Intel build and runs on Apple Silicon through Rosetta 2.
- **Linux:** install it with your package manager (`sudo apt install ffmpeg`, `sudo dnf install ffmpeg`,
  `sudo pacman -S ffmpeg`, ...).
- Any other copy works too: put it on your `PATH`, or set `ffmpegPath` in `config/custom_discs-client.json`.

Only the player who burns a disc needs ffmpeg. Everyone else just downloads the finished file.

## Multiplayer (Essential, e4mc, dedicated servers)

The mod must be installed on the **server** (or the host, for Essential/e4mc) **and on every client**, together
with [Fabric API](https://modrinth.com/mod/fabric-api).

- **Uploads and downloads go in 30 KB chunks.** Each chunk is acknowledged before more are sent, so a slow relay
  never gets flooded and normal gameplay packets keep flowing.
- **Downloads are lazy.** A client downloads a song only the first time it needs to play it. The song is then
  cached in `.minecraft/custom_discs/cache/`.
- **Tracks are identified by their SHA-256 hash.** Identical uploads share one file on the server. A client
  never downloads the same song twice. The player who burned a disc already has it.
- **Late joiners hear the right part of the song.** If you walk into range or log in in the middle of a song,
  playback starts at the same spot as for everyone else.

The server stores songs in `<world>/data/custom_discs/<hash>.ogg`. A disc only stores the titles, artist,
lengths and hashes, so discs are tiny and can be copied, stored in chests or moved around freely.

## Configuration

`config/custom_discs-server.json` (server or singleplayer host):

| Setting | Default | Meaning |
|---|---|---|
| `maxDurationSeconds` | 600 | Longest single track. |
| `maxDiscDurationSeconds` | 4800 | Longest whole disc, all album tracks together (max 7200). |
| `maxTracksPerDisc` | 30 | Most tracks on one album disc (max 64). |
| `maxFileSizeMb` | 12 | Largest compressed file for one track. |
| `maxBitrateKbps` | 128 | Bitrate cap. Clients use the lower of this and their own setting. |
| `opsOnly` | false | Only operators can burn discs. Permission mods can use the node `custom_discs:burn` either way. |
| `burnCooldownSeconds` | 10 | Wait time between burns for each player. |
| `transferWindowChunks` | 4 | How many 30 KB chunks may be in flight per download. Lower it for very slow relays. |
| `deleteUnusedTracksAfterDays` | 0 | Delete stored songs nobody has burned, played or downloaded for this many days. 0 = never. Songs on discs sitting in chests count as unused, so only turn this on if that is okay. |

`config/custom_discs-client.json` (or open the settings in game: the **Discs...** button in the pause menu, or
`/customdiscs`):

| Setting | Default | Meaning |
|---|---|---|
| `maxCacheSizeMb` | 512 | Size limit of the download cache. The least recently played songs are removed first. |
| `ffmpegPath` | `""` | Full path to ffmpeg, if it isn't found automatically. |
| `bitrateKbps` | 96 | Bitrate for songs you burn (32-320, capped by the server). |
| `prefillFromTags` | true | Fill in Title and Artist from the file's tags. |
| `muteExplicitDiscs` | false | Don't play discs marked explicit. Also toggled from the pause menu. |
| `useSystemFileDialog` | true | Browse opens the system file dialog. Set it to false to always use the in-game browser. |

## Building

You need **JDK 25**.

```sh
./gradlew build        # jar in build/libs/
./gradlew runClient    # test client
./gradlew runServer    # test dedicated server
```

Versions are in `gradle.properties` and match the Fabric example mod for 26.3. To update them, check
<https://fabricmc.net/develop>.

## How it works

- `custom_discs:burned` (`data/custom_discs/jukebox_song/burned.json`) is one shared jukebox song entry that
  every burned disc points to. Vanilla handles inserting, ejecting, hoppers and the comparator signal through it.
  The entry's own sound is silent.
- A mixin at the start of `JukeboxBlockEntity.tick` stops the song when the last track ends (from the disc's
  `custom_discs:disc` component). It also tells `JukeboxTracker` the jukebox is still playing. The tracker
  sends play/stop packets to players as they come into or leave range, and a jukebox that stops ticking
  (ejected, broken, unloaded) counts as stopped.
- On the client, `JukeboxSounds` works out every tick which track should be playing from the start time, the
  track lengths and the shuffle order. The shuffle order is seeded from the jukebox position and start time, so
  everyone hears the same thing. `CustomDiscSoundInstance` plays the cached file at the jukebox, with linear falloff to 64
  blocks like a vanilla disc. It uses Fabric's custom audio stream hook and Minecraft's own Ogg decoder, and it
  seeks forward for late joiners.
- The server never trusts the client's numbers. It checks the SHA-256, parses the Ogg file itself (it must be
  a single mono Vorbis stream) and measures the length from the file.

## Download

Every push is built on GitHub Actions. Open the repository's **Actions** tab, pick the latest green **Build**
run, and download the **custom-discs-mod** artifact. It is a zip with the mod jar inside. Put the jar in your
`mods` folder together with Fabric API.
