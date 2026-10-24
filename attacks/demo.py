#!/usr/bin/env python3
"""Harmless blocking IPv4 lab traffic and temporary-directory execution."""
import argparse
import ipaddress
import json
import shutil
import signal
import socket
import subprocess
import tempfile
from pathlib import Path


def expired(signum, frame):
    raise TimeoutError('Demo timed out')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    for mode in ('serve', 'connect'):
        command = sub.add_parser(mode)
        command.add_argument('address', type=ipaddress.IPv4Address)
        command.add_argument('--port', type=int, default=2222)
        command.add_argument('--timeout', type=int, default=30)
    sub.add_parser('tmp-exec')
    args = parser.parse_args()
    if args.mode == 'tmp-exec':
        with tempfile.TemporaryDirectory(prefix='ids-demo-', dir='/tmp') as directory:
            binary = Path(directory) / 'demo-true'
            shutil.copy2('/usr/bin/true', binary)
            subprocess.run([str(binary)], check=True)
            print(json.dumps({'executed': str(binary), 'expected_rule': 'exec-from-tmp'}))
        return
    if not 1 <= args.port <= 65535 or not 1 <= args.timeout <= 300:
        parser.error('port must be 1..65535 and timeout 1..300 seconds')
    signal.signal(signal.SIGALRM, expired)
    signal.alarm(args.timeout)  # Keep sockets blocking: Python socket timeouts use nonblocking connect.
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as connection:
        if args.mode == 'serve':
            connection.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            connection.bind((str(args.address), args.port))
            connection.listen(1)
            print(json.dumps({'listening': connection.getsockname()}), flush=True)
            accepted, _ = connection.accept()
            with accepted:
                print(json.dumps({'local': accepted.getsockname(), 'remote': accepted.getpeername()}), flush=True)
                accepted.recv(128)
        else:
            connection.connect((str(args.address), args.port))
            print(json.dumps({'local': connection.getsockname(), 'remote': connection.getpeername()}), flush=True)
            connection.sendall(b'cross-node-ids-demo\n')
    signal.alarm(0)


if __name__ == '__main__':
    main()
