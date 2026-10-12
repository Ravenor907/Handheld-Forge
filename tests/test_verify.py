import base64
import hashlib
import tempfile
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from handheld_forge.verify import VerificationError, parse_checksum, sha256_file, verify_signature


def cosign_like_signature(data: bytes):
    """Sign the way `cosign sign-blob --key` does: base64 DER ECDSA P-256 / SHA256."""
    key = ec.generate_private_key(ec.SECP256R1())
    sig = base64.b64encode(key.sign(data, ec.ECDSA(hashes.SHA256())))
    pem = key.public_key().public_bytes(serialization.Encoding.PEM,
                                        serialization.PublicFormat.SubjectPublicKeyInfo)
    return sig, pem


class ChecksumTests(unittest.TestCase):
    H = "a" * 64

    def test_sha256sum_format(self):
        self.assertEqual(parse_checksum(f"{self.H}  bazzite.iso\n", "bazzite.iso"), self.H)

    def test_binary_marker_and_path(self):
        self.assertEqual(parse_checksum(f"{self.H} */upload/bazzite.iso", "bazzite.iso"), self.H)

    def test_picks_right_file(self):
        text = f"{'b' * 64}  other.iso\n{self.H}  bazzite.iso\n"
        self.assertEqual(parse_checksum(text, "bazzite.iso"), self.H)

    def test_bare_hash(self):
        self.assertEqual(parse_checksum(self.H.upper(), "x.iso"), self.H)

    def test_missing(self):
        with self.assertRaises(VerificationError):
            parse_checksum(f"{self.H}  other.iso", "bazzite.iso")

    def test_garbage(self):
        with self.assertRaises(VerificationError):
            parse_checksum("<html>404</html>", "bazzite.iso")

    def test_sha256_file(self):
        data = b"handheld" * 1_000_000
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "f.iso"
            p.write_bytes(data)
            seen = []
            self.assertEqual(sha256_file(p, lambda a, b: seen.append((a, b))),
                             hashlib.sha256(data).hexdigest())
            self.assertEqual(seen[-1], (len(data), len(data)))


class SignatureTests(unittest.TestCase):
    def test_valid(self):
        data = b"iso contents"
        sig, pem = cosign_like_signature(data)
        verify_signature(hashlib.sha256(data).hexdigest(), sig, pem)

    def test_tampered(self):
        sig, pem = cosign_like_signature(b"iso contents")
        with self.assertRaises(VerificationError):
            verify_signature(hashlib.sha256(b"evil contents").hexdigest(), sig, pem)

    def test_wrong_key(self):
        data = b"iso contents"
        sig, _ = cosign_like_signature(data)
        _, other_pem = cosign_like_signature(data)
        with self.assertRaises(VerificationError):
            verify_signature(hashlib.sha256(data).hexdigest(), sig, other_pem)

    def test_not_base64(self):
        _, pem = cosign_like_signature(b"x")
        with self.assertRaises(VerificationError):
            verify_signature("0" * 64, b"!!not base64!!", pem)

    def test_pinned_bazzite_key_loads(self):
        from handheld_forge.config import get_distro
        pem = get_distro("bazzite-deck").public_key.read_bytes()
        key = serialization.load_pem_public_key(pem)
        self.assertIsInstance(key, ec.EllipticCurvePublicKey)


if __name__ == "__main__":
    unittest.main()
