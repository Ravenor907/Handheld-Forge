"""Integrity (SHA256) and authenticity (cosign signature) checks.

The checksum catches a corrupted download. The signature, checked against the
public key pinned in this repo, catches a tampered one: the checksum alone
cannot, because it comes from the same server as the ISO.
"""

from __future__ import annotations

import base64
import hashlib
import re
from collections.abc import Callable
from pathlib import Path

CHUNK = 4 * 1024 * 1024
_HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")

ProgressFn = Callable[[int, int], None]  # (done_bytes, total_bytes)


class VerificationError(Exception):
    pass


def sha256_file(path: Path, progress: ProgressFn | None = None) -> str:
    path = Path(path)
    total = path.stat().st_size
    h = hashlib.sha256()
    done = 0
    with path.open("rb") as f:
        while chunk := f.read(CHUNK):
            h.update(chunk)
            done += len(chunk)
            if progress:
                progress(done, total)
    return h.hexdigest()


def parse_checksum(text: str, filename: str) -> str:
    """Pull the hash for `filename` out of sha256sum-style output.

    Accepts "<hash>  <name>", "<hash> *<name>", or a bare hash on its own.
    """
    lines = [ln.strip() for ln in text.strip().splitlines() if ln.strip()]
    for ln in lines:
        parts = ln.split()
        if len(parts) >= 2 and _HEX64.match(parts[0]):
            name = parts[1].lstrip("*").rsplit("/", 1)[-1]
            if name == filename:
                return parts[0].lower()
    if len(lines) == 1 and _HEX64.match(lines[0]):
        return lines[0].lower()
    raise VerificationError(f"No checksum for {filename} in checksum file")


def verify_signature(sha256_hex: str, signature_b64: bytes | str, public_key_pem: bytes) -> None:
    """Verify a `cosign sign-blob --key` signature against a precomputed SHA256.

    cosign key-based blob signatures are base64 DER ECDSA over SHA256 of the
    file, so the digest we already computed is all we need: no second 8 GB pass.
    """
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec, utils

    key = serialization.load_pem_public_key(public_key_pem)
    if not isinstance(key, ec.EllipticCurvePublicKey):
        raise VerificationError("Pinned public key is not an ECDSA key")
    if isinstance(signature_b64, str):
        signature_b64 = signature_b64.encode()
    try:
        sig = base64.b64decode(signature_b64.strip(), validate=True)
    except ValueError as e:
        raise VerificationError("Signature file is not valid base64") from e
    try:
        key.verify(sig, bytes.fromhex(sha256_hex), ec.ECDSA(utils.Prehashed(hashes.SHA256())))
    except InvalidSignature as e:
        raise VerificationError("Signature does NOT match the pinned key. Refusing this ISO.") from e
