#!/usr/bin/env python3
"""ENiGMA iDrive desktop test receiver.
Receives raw H.264 from Android sender over TCP on a trusted private LAN.
Not a BMW receiver. Do not expose over the Internet.
"""
import argparse
import socket
import struct
import subprocess

def exact(client, n):
    pieces = []
    while n:
        part = client.recv(n)
        if not part:
            raise ConnectionError("Peer disconnected")
        pieces.append(part)
        n -= len(part)
    return b"".join(pieces)

def run():
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5567)
    parser.add_argument("--out", default="enigma.h264")
    parser.add_argument("--play", action="store_true")
    args = parser.parse_args()

    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((args.listen, args.port))
        server.listen(1)
        print("Listening on", args.listen, args.port, flush=True)
        while True:
            client, addr = server.accept()
            print("Client:", addr, flush=True)
            player = None
            try:
                with client:
                    if exact(client, 8) != b"ENIGMA01":
                        raise ValueError("Bad protocol header")
                    width, height, fps = struct.unpack("!III", exact(client, 12))
                    if not(1 <= width <= 4096 and 1 <= height <= 4096 and 1 <= fps <= 120):
                        raise ValueError("Invalid video dimensions/fps")
                    print(f"{width}x{height} {fps}fps", flush=True)
                    if args.play:
                        player = subprocess.Popen(
                            ["ffplay", "-loglevel", "warning", "-flags", "low_delay",
                             "-framedrop", "-fflags", "nobuffer", "-f", "h264", "-i", "-"],
                            stdin=subprocess.PIPE)
                        sink = player.stdin
                    else:
                        sink = open(args.out, "wb")
                    try:
                        count = 0
                        while True:
                            size = struct.unpack("!I", exact(client, 4))[0]
                            flags = exact(client, 1)[0]
                            pts = struct.unpack("!Q", exact(client, 8))[0]
                            if size > 4000000:
                                raise ValueError("Oversize packet")
                            payload = exact(client, size)
                            sink.write(payload)
                            count += 1
                            if count % 150 == 0:
                                print(f"packets={count} pts={pts} flags={flags}", flush=True)
                    finally:
                        sink.close()
            except (ConnectionError, OSError, ValueError) as err:
                print("Stream ended:", err, flush=True)
            finally:
                if player is not None:
                    try:
                        player.terminate()
                    except OSError:
                        pass

if __name__ == "__main__":
    run()
