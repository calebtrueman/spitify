#!/usr/bin/env python3
"""Serve generated audio slowly, with deliberately wrong format labels, for iOS tests."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import subprocess
import time
from urllib.parse import parse_qs, urlparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--directory", default="../work/stream-fixture")
parser.add_argument("--port", type=int, default=18952)
parser.add_argument("--generate", action="store_true", help="Generate 30-second tones using ffmpeg")
args = parser.parse_args()
root = Path(args.directory).expanduser().resolve()
root.mkdir(parents=True, exist_ok=True)
if args.generate:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        parser.error("--generate needs ffmpeg on PATH")
    for extension, codec in [("ogg", "libopus"), ("mp3", "libmp3lame"), ("flac", "flac"), ("m4a", "aac")]:
        output = root / ("stream." + extension)
        if output.exists():
            continue
        command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-n", "-f", "lavfi", "-i",
                   "sine=frequency=440:sample_rate=48000:duration=30", "-ac", "2", "-c:a", codec]
        if extension == "m4a":
            command += ["-movflags", "+faststart"]
        subprocess.run(command + [str(output)], check=True)


class Handler(BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.serve(False)

    def do_GET(self):
        self.serve(True)

    def log_message(self, *_):
        pass

    def serve(self, body):
        parsed = urlparse(self.path)
        path = root / Path(parsed.path).name
        if not path.is_file():
            self.send_error(404)
            return
        data = path.read_bytes()
        query = parse_qs(parsed.query)
        start, end, code = 0, len(data) - 1, 200
        ranged = "ranges" in query
        if ranged:
            start = int(self.headers.get("Range", "bytes=0-").split("=")[1].split("-")[0])
            end, code = min(end, start + 65535), 206
        chunked = "chunked" in query and body
        self.send_response(code)
        self.send_header("Content-Type", "audio/flac")  # The decoder must trust bytes, not this label.
        if ranged:
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
        if chunked:
            self.send_header("Transfer-Encoding", "chunked")
        else:
            self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        if not body:
            return
        step = max(1024, len(data) // 100)
        try:
            for offset in range(start, end + 1, step):
                block = data[offset:min(offset + step, end + 1)]
                self.wfile.write(f"{len(block):x}\r\n".encode() + block + b"\r\n" if chunked else block)
                self.wfile.flush()
                time.sleep(0.06)
            if chunked:
                self.wfile.write(b"0\r\n\r\n")
        except (BrokenPipeError, ConnectionResetError):
            pass


print(f"Audio fixture: http://127.0.0.1:{args.port} from {root}", flush=True)
ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
