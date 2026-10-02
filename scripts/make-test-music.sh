#!/usr/bin/env bash
# Generates a small tagged MP3 library (artists, albums, genres, track numbers, embedded art)
# and pushes it to the running emulator/device.  Requires ffmpeg.
set -euo pipefail
cd "$(dirname "$0")"
OUT="$(mktemp -d)"
n=0
while IFS='|' read -r artist album genre year cover freq tracks; do
  ffmpeg -loglevel error -y -f lavfi -i "$cover" -frames:v 1 "$OUT/cover_$n.jpg"
  dir="$OUT/Music/$artist/$album"; mkdir -p "$dir"
  IFS=',' read -ra T <<< "$tracks"
  i=1
  for t in "${T[@]}"; do
    f=$(python3 -c "print(round($freq*(1.122**($i%5)),2))")
    dur=$((45 + (i*13)%40))
    ffmpeg -loglevel error -y \
      -f lavfi -i "aevalsrc='0.25*sin(2*PI*$f*t)*(0.6+0.4*sin(2*PI*0.5*t))+0.15*sin(2*PI*$f*1.5*t)+0.1*sin(2*PI*$f*1.25*t)':s=44100:d=$dur" \
      -i "$OUT/cover_$n.jpg" -map 0:a -map 1:v -c:a libmp3lame -b:a 128k -c:v mjpeg -id3v2_version 3 -disposition:v attached_pic \
      -metadata title="$t" -metadata artist="$artist" -metadata album_artist="$artist" -metadata album="$album" \
      -metadata genre="$genre" -metadata date="$year" -metadata track="$i/${#T[@]}" \
      "$dir/$(printf '%02d' $i) - $t.mp3" </dev/null
    i=$((i+1))
  done
  n=$((n+1))
done < test-albums.txt
adb push "$OUT/Music/." /sdcard/Music/
adb shell 'find /sdcard/Music -name "*.mp3" | while read f; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null; done'
echo "Pushed $(find "$OUT/Music" -name '*.mp3' | wc -l | tr -d ' ') tracks."
