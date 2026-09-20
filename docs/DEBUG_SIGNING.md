# Debug Signing

PokeRPG Player uses a canonical local debug keystore so debug APKs built from
different development environments retain the same signing identity.

Expected local path:

    keystores/pokerpg-debug.keystore

The keystore itself is intentionally NOT stored in Git.

Expected alias:

    androiddebugkey

Expected certificate SHA-256:

    E1:EA:46:4F:3B:9E:DA:66:C0:76:D8:A6:F3:C7:30:97:
    A4:5C:50:E0:E0:20:87:A3:2F:73:EE:53:C4:97:DD:DA

The debug build configuration is defined in app/build.gradle.kts.

Do not commit production signing material, private keys, passwords, or release
keystores to this repository.
