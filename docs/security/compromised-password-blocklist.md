# Compromised-password blocklist

The bundled offline blocklist derives from SecLists release 2026.1, file `Passwords/Common-Credentials/10k-most-common.txt`. SecLists is distributed under the MIT license. The pinned source SHA-256 is `4adb3f0afb4a10cf19ebe48d8c69a46f934bbc8d77c694c210564f9583e7f4ba`.

The tracked resource contains no plaintext passwords. Each non-comment line is the lowercase hexadecimal SHA-256 digest of one UTF-8 source line, sorted and deduplicated. Startup validates the version header, line format, ordering, uniqueness, and minimum coverage; invitation acceptance fails closed if validation fails.

To review an update, change the pinned SecLists release and expected source checksum in `scripts/generate-compromised-password-blocklist.ps1`, verify the upstream license and source contents, run the script, inspect the provenance header and diff, then run the complete backend verification. Updating the list is a reviewed security change, not an automatic network operation at application runtime.
