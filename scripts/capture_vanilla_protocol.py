#!/usr/bin/env python3
"""Capture the configuration packets emitted by an official Minecraft server."""

import argparse
import json
import socket
import struct
import subprocess
import tempfile
import threading
import time
import uuid
from pathlib import Path


def encode_var_int(value: int) -> bytes:
    output = bytearray()
    value &= 0xFFFFFFFF

    while value & ~0x7F:
        output.append(value & 0x7F | 0x80)
        value >>= 7

    output.append(value)
    return bytes(output)


def encode_string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return encode_var_int(len(encoded)) + encoded


def read_exact(connection: socket.socket, length: int) -> bytes:
    output = bytearray()

    while len(output) < length:
        chunk = connection.recv(length - len(output))

        if not chunk:
            raise EOFError("Connection closed")

        output.extend(chunk)

    return bytes(output)


def read_var_int_stream(connection: socket.socket) -> int:
    result = 0

    for position in range(5):
        value = read_exact(connection, 1)[0]
        result |= (value & 0x7F) << position * 7

        if not value & 0x80:
            return result

    raise ValueError("VarInt is too large")


def read_var_int(data: bytes, offset: int) -> tuple[int, int]:
    result = 0

    for position in range(5):
        value = data[offset]
        offset += 1
        result |= (value & 0x7F) << position * 7

        if not value & 0x80:
            return result, offset

    raise ValueError("VarInt is too large")


def read_string(data: bytes, offset: int) -> tuple[str, int]:
    length, offset = read_var_int(data, offset)
    end = offset + length
    return data[offset:end].decode("utf-8"), end


def send_packet(connection: socket.socket, packet_id: int, payload: bytes = b"") -> None:
    packet = encode_var_int(packet_id) + payload
    connection.sendall(encode_var_int(len(packet)) + packet)


def read_packet(connection: socket.socket) -> tuple[int, bytes]:
    length = read_var_int_stream(connection)
    packet = read_exact(connection, length)
    packet_id, offset = read_var_int(packet, 0)
    return packet_id, packet[offset:]


def parse_registry(payload: bytes) -> dict:
    registry, offset = read_string(payload, 0)
    count, offset = read_var_int(payload, offset)
    entries = []

    for _ in range(count):
        identifier, offset = read_string(payload, offset)
        has_data = payload[offset] != 0
        offset += 1

        if has_data:
            raise ValueError(f"Registry {registry} unexpectedly contains inline NBT")

        entries.append(identifier)

    if offset != len(payload):
        raise ValueError(f"Registry {registry} has {len(payload) - offset} trailing bytes")

    return {"registry": registry, "entries": entries}


def parse_tags(payload: bytes) -> dict[str, list[dict]]:
    registry_count, offset = read_var_int(payload, 0)
    registries = {}

    for _ in range(registry_count):
        registry, offset = read_string(payload, offset)
        tag_count, offset = read_var_int(payload, offset)
        tags = []

        for _ in range(tag_count):
            identifier, offset = read_string(payload, offset)
            entry_count, offset = read_var_int(payload, offset)
            entries = []

            for _ in range(entry_count):
                entry, offset = read_var_int(payload, offset)
                entries.append(entry)

            tags.append({"identifier": identifier, "entries": entries})

        registries[registry] = tags

    if offset != len(payload):
        raise ValueError(f"Tag update has {len(payload) - offset} trailing bytes")

    return registries


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def stream_server_output(process: subprocess.Popen, ready: threading.Event) -> None:
    for line in process.stdout:
        print(line, end="")

        if "Done (" in line:
            ready.set()


def capture(server_jar: Path, output_file: Path, tags_output_file: Path | None) -> None:
    port = free_port()

    with tempfile.TemporaryDirectory(prefix="llama-protocol-") as directory:
        server_directory = Path(directory)
        (server_directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (server_directory / "server.properties").write_text(
            "online-mode=false\n"
            "network-compression-threshold=-1\n"
            "enforce-secure-profile=false\n"
            "white-list=false\n"
            "pause-when-empty-seconds=-1\n"
            f"server-port={port}\n",
            encoding="utf-8",
        )

        process = subprocess.Popen(
            ["java", "-jar", str(server_jar), "nogui"],
            cwd=server_directory,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        ready = threading.Event()
        output_thread = threading.Thread(target=stream_server_output, args=(process, ready), daemon=True)
        output_thread.start()

        try:
            if not ready.wait(60):
                raise TimeoutError("Vanilla server did not start within 60 seconds")

            registries, tags = capture_connection(port)
            output_file.parent.mkdir(parents=True, exist_ok=True)
            output_file.write_text(json.dumps(registries, indent=2) + "\n", encoding="utf-8")

            if tags_output_file is not None:
                tags_output_file.parent.mkdir(parents=True, exist_ok=True)
                tags_output_file.write_text(json.dumps(tags, indent=2) + "\n", encoding="utf-8")
        finally:
            if process.poll() is None:
                process.stdin.write("stop\n")
                process.stdin.flush()
                process.wait(timeout=30)


def capture_connection(port: int) -> tuple[list[dict], dict[str, list[dict]]]:
    with socket.create_connection(("127.0.0.1", port), timeout=10) as connection:
        connection.settimeout(20)
        handshake = encode_var_int(777) + encode_string("localhost") + struct.pack(">H", port) + encode_var_int(2)
        send_packet(connection, 0, handshake)
        send_packet(connection, 0, encode_string("LlamaCapture") + uuid.uuid4().bytes)

        login_packet_id, _ = read_packet(connection)

        if login_packet_id != 2:
            raise ValueError(f"Expected login finished, received packet {login_packet_id}")

        send_packet(connection, 3)
        registries = []
        tags = {}

        while True:
            packet_id, payload = read_packet(connection)

            if packet_id == 15:
                send_packet(connection, 7, payload)
                continue

            if packet_id == 7:
                registries.append(parse_registry(payload))
                continue

            if packet_id == 14:
                tags = parse_tags(payload)
                continue

            if packet_id == 3:
                send_packet(connection, 3)
                break

        connection.settimeout(0.5)
        play_packets = []

        try:
            while len(play_packets) < 256:
                packet_id, payload = read_packet(connection)
                play_packets.append(packet_id)

                if packet_id == 50:
                    send_packet(connection, 44)

                if packet_id == 73:
                    teleport_id, offset = read_var_int(payload, 0)
                    position = payload[offset:offset + 24]
                    rotation = payload[offset + 48:offset + 56]
                    send_packet(connection, 0, encode_var_int(teleport_id) + position + rotation)
        except (EOFError, socket.timeout):
            pass

        print("Captured play packet IDs:", play_packets)
        return registries, tags


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("server_jar", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--tags-output", type=Path)
    arguments = parser.parse_args()
    tags_output = arguments.tags_output.resolve() if arguments.tags_output is not None else None
    capture(arguments.server_jar.resolve(), arguments.output.resolve(), tags_output)


if __name__ == "__main__":
    main()
