# PrivateReel | Android
Personal, offline media reel viewer for manually selected photos and videos.

This is an **independent branch** of the `bokdog08/Video` repository. Its existing `main` branch and MultiView Studio app are unchanged.

## Source
`PrivateReel.tar.br` contains the Android app sources, Gradle build files, manifest and resources. The archive is Brotli-compressed tar. The CI workflow unpacks it before building.

To restore it locally:
```bash
python -m pip install brotli
python -c "import brotli;open('PrivateReel.tar','wb').write(brotli.decompress(open('PrivateReel.tar.br','rb').read()))"
mkdir PrivateReel
tar -xf PrivateReel.tar -C PrivateReel
```

## APK
Open **Actions → Build PrivateReel APK** and select the latest successful run. Under **Artifacts**, download `PrivateReel-debug-APK`, unzip it and install `app-debug.apk`.

Debug APKs are for sideloading, not Play Store release. No personal photos or videos are included. The application does not request network permission.
