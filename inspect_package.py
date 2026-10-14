"""本地检查指定小程序代码包中的接口调用，不读取账号存储。"""
from pathlib import Path
import hashlib
import struct

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def unpack(source, app_id, destination):
    blob = Path(source).read_bytes()
    if blob[:6] == b"V1MMWX":
        key = hashlib.pbkdf2_hmac("sha1", app_id.encode(), b"saltiest", 1000, 32)
        decoder = Cipher(algorithms.AES(key), modes.CBC(b"the iv: 16 bytes")).decryptor()
        prefix = decoder.update(blob[6:1030]) + decoder.finalize()
        padding = prefix[-1]
        if 1 <= padding <= 16 and prefix[-padding:] == bytes([padding]) * padding:
            prefix = prefix[:-padding]
        blob = prefix + bytes(value ^ ord(app_id[-2]) for value in blob[1030:])
    if blob[0] != 0xBE or blob[13] != 0xED:
        raise ValueError("无法识别解包后的代码包头")
    count = struct.unpack_from(">I", blob, 14)[0]
    offset = 18
    root = Path(destination).resolve()
    root.mkdir(parents=True, exist_ok=True)
    for _ in range(count):
        length = struct.unpack_from(">I", blob, offset)[0]
        offset += 4
        name = blob[offset:offset + length].decode("utf-8")
        offset += length
        start, size = struct.unpack_from(">II", blob, offset)
        offset += 8
        path = (root / name.lstrip("/")).resolve()
        if not path.is_relative_to(root):
            raise ValueError("包内存在越界文件路径")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(blob[start:start + size])
    print(f"已检查代码包，文件数 {count}。")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("--app-id", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    unpack(args.source, args.app_id, args.output)
