#!/usr/bin/env python3
"""Proxy TCP qui coupe le transfert du compagnon à un moment choisi.

Le TLS reste de bout en bout : le proxy ne voit que l'ordre des connexions, une par requête.

    adb forward tcp:18766 tcp:8766
    scripts/proxy_coupures.py --cut 2:1000     # 2e connexion coupée après 1000 octets de réponse
    scripts/proxy_coupures.py --cut 3:0        # 3e connexion coupée sans transmettre de réponse
    scripts/proxy_coupures.py --drop 3         # 3e connexion coupée sans transmettre la requête
    scripts/proxy_coupures.py --delay 0.05     # ralentit, pour tuer l'app en plein transfert

Sur l'appareil, adresse de l'Atoto : 10.0.2.2 (la machine, vue de l'émulateur).
Ordre des connexions : status, samples, ack, trips, refuels, aggregates, summary.
"""
import argparse
import socket
import threading
import time


def pump(src, dst, limit=None, delay=0.0):
    sent = 0
    try:
        while limit is None or sent < limit:
            size = 4096 if limit is None else min(4096, limit - sent)
            data = src.recv(size)
            if not data:
                break
            if delay:
                time.sleep(delay)
            dst.sendall(data)
            sent += len(data)
    except OSError:
        pass
    for sock in (src, dst):
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--target", type=int, default=18766)
    parser.add_argument("--cut", action="append", default=[], metavar="N:OCTETS")
    parser.add_argument("--drop", action="append", type=int, default=[], metavar="N")
    parser.add_argument("--delay", type=float, default=0.0)
    args = parser.parse_args()
    cuts = {int(c.split(":")[0]): int(c.split(":")[1]) for c in args.cut}

    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", args.port))
    server.listen(16)
    count = 0
    while True:
        client, _ = server.accept()
        count += 1
        if count in args.drop:
            print(f"connexion {count} refusée", flush=True)
            client.close()
            continue
        limit = cuts.get(count)
        print(f"connexion {count}" + (f" coupée après {limit} octets" if limit is not None else ""), flush=True)
        upstream = socket.create_connection(("127.0.0.1", args.target))
        threading.Thread(target=pump, args=(client, upstream), daemon=True).start()
        threading.Thread(target=pump, args=(upstream, client, limit, args.delay), daemon=True).start()


if __name__ == "__main__":
    main()
