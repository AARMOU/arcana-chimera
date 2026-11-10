#!/usr/bin/env python3
"""Fail-closed preparation/verification for a LOCAL Chimera Google Play AAB.

Nothing uploads, publishes, retrieves secrets, or changes the donor repository.
The caller supplies the ORIGINAL upload key and Play Console upload-certificate
SHA-256 supplied from the correct Play Console UPLOAD certificate through
private build configuration. Never put signing keys or passwords in Git.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

EXPECTED_PACKAGE = "com.arcanatable"
EXPECTED_ALIAS = "arcana-upload"
GRADLE_MARKER = "// CHIMERA PLAY SIGNED RELEASE OVERLAY"
MIN_VERSION_CODE = 143  # Build 142 in the pinned donor. Play Console may require higher.


def fingerprint(value):
    digits = re.sub(r"[^0-9a-fA-F]", "", value or "").upper()
    if len(digits) != 64:
        raise ValueError("Expected the 64-digit SHA-256 of the UPLOAD certificate from Play Console")
    return digits


def read_certificate_sha256(text):
    match = re.search(r"SHA256:\s*([0-9A-Fa-f:]{64,95})", text)
    if not match:
        raise ValueError("Certificate SHA-256 not found in keytool output")
    return fingerprint(match.group(1))


def require_signing_environment():
    required = (
        "CHIMERA_UPLOAD_KEYSTORE", "CHIMERA_UPLOAD_STORE_PASSWORD",
        "CHIMERA_UPLOAD_KEY_PASSWORD", "CHIMERA_UPLOAD_ALIAS",
        "CHIMERA_EXPECTED_UPLOAD_CERT_SHA256",
    )
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise ValueError("Required private signing environment is missing: " + ", ".join(missing))
    if os.environ["CHIMERA_UPLOAD_ALIAS"] != EXPECTED_ALIAS:
        raise ValueError("Wrong alias: use the original Play upload key, not a newly generated key")
    store = Path(os.environ["CHIMERA_UPLOAD_KEYSTORE"]).expanduser().resolve()
    if not store.is_file() or store.stat().st_size == 0:
        raise ValueError("Original upload keystore file is missing or empty")
    # Fail closed: owner is still identifying the UPLOAD certificate. Do not
    # assume the separate Play APP SIGNING fingerprint is interchangeable.
    return store, fingerprint(os.environ["CHIMERA_EXPECTED_UPLOAD_CERT_SHA256"])


def run_checked(command):
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode:
        # Never echo external tool output; it might contain paths or credential metadata.
        raise RuntimeError(command[0] + " failed (exit " + str(result.returncode) + ")")
    return result.stdout + "\n" + result.stderr


def check_keystore(store, expected):
    output = run_checked([
        "keytool", "-list", "-v", "-keystore", str(store),
        "-alias", EXPECTED_ALIAS,
        "-storepass:env", "CHIMERA_UPLOAD_STORE_PASSWORD",
    ])
    actual = read_certificate_sha256(output)
    if actual != expected:
        raise ValueError("Upload certificate does not match the SHA-256 from Play Console")
    return actual


def validate_version(version_code):
    if version_code < MIN_VERSION_CODE or version_code > 2100000000:
        raise ValueError("versionCode must be at least 143 and above every Play track's existing code")
    return version_code


def prepare_gradle(gradle_file, version_code, version_name):
    validate_version(version_code)
    if not re.fullmatch(r"[a-zA-Z0-9._+-]{1,80}", version_name):
        raise ValueError("Unsafe versionName")
    content = gradle_file.read_text(encoding="utf-8")
    if GRADLE_MARKER in content:
        raise ValueError("Play signing overlay already added; refusing duplicate")
    if 'applicationId = "org.arcanachimera.arcanaforge"' not in content:
        raise ValueError("This is not the expected Chimera-staged donor Gradle project")
    if "targetSdk = 36" not in content:
        raise ValueError("Play requires targetSdk 36 or higher as of August 2026; audit donor configuration")
    overlay = f"""
{GRADLE_MARKER}
// This affects release bundles only; normal debug builds remain the separate Chimera package.
// Required secrets are read at Gradle runtime, never copied into source or Gradle arguments.
android {{
    defaultConfig {{
        applicationId = "{EXPECTED_PACKAGE}"
        versionCode = {version_code}
        versionName = "{version_name}"
        manifestPlaceholders["appLabel"] = "Arcana Table"
        buildConfigField("boolean", "STUDIO_ENABLED", "true")
    }}
    signingConfigs {{
        create("chimeraPlay") {{
            storeFile = file(System.getenv("CHIMERA_UPLOAD_KEYSTORE") ?: error("Upload keystore missing"))
            storePassword = System.getenv("CHIMERA_UPLOAD_STORE_PASSWORD") ?: error("Store password missing")
            keyAlias = System.getenv("CHIMERA_UPLOAD_ALIAS") ?: error("Upload key alias missing")
            keyPassword = System.getenv("CHIMERA_UPLOAD_KEY_PASSWORD") ?: error("Key password missing")
        }}
    }}
    buildTypes {{
        getByName("release") {{
            signingConfig = signingConfigs.getByName("chimeraPlay")
        }}
    }}
}}
"""
    gradle_file.write_text(content + overlay, encoding="utf-8")


def verify_bundle(bundle, expected):
    if not bundle.is_file() or bundle.suffix != ".aab" or bundle.stat().st_size == 0:
        raise ValueError("A nonempty release .aab is required")
    signature = run_checked(["jarsigner", "-verify", "-verbose", str(bundle)])
    if "jar verified" not in signature.lower():
        raise ValueError("AAB is not JAR-signature verified")
    certificate = run_checked(["keytool", "-printcert", "-jarfile", str(bundle)])
    actual = read_certificate_sha256(certificate)
    if actual != expected:
        raise ValueError("Signed bundle certificate differs from Play Console upload certificate")
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("preflight", "prepare", "verify"))
    parser.add_argument("--gradle", type=Path)
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--version-name")
    args = parser.parse_args()
    try:
        store, expected = require_signing_environment()
        check_keystore(store, expected)
        if args.action == "prepare":
            if not args.gradle or args.version_code is None or not args.version_name:
                raise ValueError("prepare needs --gradle, --version-code, --version-name")
            prepare_gradle(args.gradle, args.version_code, args.version_name)
        elif args.action == "verify":
            if not args.bundle:
                raise ValueError("verify needs --bundle")
            verify_bundle(args.bundle, expected)
        print("PASS: original Play upload certificate matched; " + args.action + " complete")
    except (ValueError, RuntimeError, OSError) as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
