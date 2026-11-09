"""Certificado propio (autofirmado) para HTTPS. La app lo reconoce por su huella SHA-256."""

import datetime
import hashlib
import ipaddress
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID


def asegurar(datos: Path) -> tuple[Path, Path, str]:
    """Devuelve (cert.pem, clave.pem, huella). Lo crea la primera vez."""
    cert_p, clave_p = datos / "cert.pem", datos / "clave.pem"
    if not (cert_p.exists() and clave_p.exists()):
        clave = ec.generate_private_key(ec.SECP256R1())
        nombre = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Rite puente")])
        ahora = datetime.datetime.now(datetime.timezone.utc)
        cert = (
            x509.CertificateBuilder()
            .subject_name(nombre)
            .issuer_name(nombre)
            .public_key(clave.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(ahora - datetime.timedelta(days=1))
            .not_valid_after(ahora + datetime.timedelta(days=3650))
            .add_extension(
                x509.SubjectAlternativeName([x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]),
                critical=False,
            )
            .sign(clave, hashes.SHA256())
        )
        clave_p.write_bytes(
            clave.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
        )
        cert_p.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return cert_p, clave_p, huella(cert_p)


def huella(cert_pem: Path) -> str:
    cert = x509.load_pem_x509_certificate(cert_pem.read_bytes())
    return hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
