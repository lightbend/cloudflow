# Publishing to Sonatype

## Prerequisites

* You need to have `pgp` installed. For MacOS, do a `brew install pgp`. In Ubuntu you can do `sudo apt install gnupg`. Or install from [GnuPG](https://www.gnupg.org/download/). Check the following after install:

```
$ gpg --version
gpg (GnuPG) 2.2.17
libgcrypt 1.8.5
Copyright (C) 2019 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Home: /Users/debasishghosh/.gnupg
Supported algorithms:
Pubkey: RSA, ELG, DSA, ECDH, ECDSA, EDDSA
Cipher: IDEA, 3DES, CAST5, BLOWFISH, AES, AES192, AES256, TWOFISH,
        CAMELLIA128, CAMELLIA192, CAMELLIA256
Hash: SHA1, RIPEMD160, SHA256, SHA384, SHA512, SHA224
Compression: Uncompressed, ZIP, ZLIB, BZIP2
```

* Once you have `pgp` installed, generate a key pair and distribute the keys to public key servers. Follow the instructions in this [section](https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html#step+1%3A+PGP+Signatures) of the sbt document.

* Create a file `gpg.sbt` under `~/.sbt/1.0/plugins` containing the following line:

```
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.0")
```

* Create a file named `sonatype.credentials` under `~/.sbt` containing your Sonatype credentials:

```
$ cat sonatype.credentials
realm=Sonatype Nexus Repository Manager
host=oss.sonatype.org
user=<your-sonatype-username>
password=<your-sonatype-password>
```

* Create a file named `sonatype.sbt` under `~/.sbt/1.0` containing the following:

```
$ cat sonatype.sbt
credentials += Credentials(Path.userHome / ".sbt" / "sonatype.credentials")
```

## Publishing a signed build

Just do an `sbt publishSigned` or `sbt publishLocalSigned` from `cloudflow/core`. The `build.sbt` is set up for this. Just a few notes:

* Once you do a `publishSigned` or `publishLocalSigned` you may be prompted for entering a passphrase to unlock your secret PGP code. I noticed that this prompt often gets garbled with the stdout of `sbt`. I am not sure if it's a Mac thing or it happens for some combination of versions of `sbt` and `gpg`. It happens on my machine.
* Once the publish goes through, login to [sonatype UI](https://oss.sonatype.org) using your Sonatype credentials. You will be able to see all artifacts in a Staging Repository. Check if all contents look ok.
* Once you have verified all contents of the staging repository, close the repository using the close button in the UI. Closing the staging repository will start a process to validate all contents and check for compliance with Maven Central. If all checks are ok, the staging repository will be closed successfully.
* The next step is to do the release using the release button. This will release all artifacts to the Sonatype release repository. The released artifacts are now ready to be transferred to Maven Central. This may take some time and will be synced with Maven Central the next time this sync process runs.

