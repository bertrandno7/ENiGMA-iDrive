# ENiGMA iDrive

Experimental, phone-side hardware-encoded H.264 streaming research for BMW NBT Evo. This is **not** a native Android Auto solution and **does not** install Android apps on a BMW head unit.

A signed phone-side preview APK was supplied in the ChatGPT conversation. The `app/` source aims to stream a live, continuously encoded Android display to a trusted laptop for parked bench testing. A compatible native QNX receiver is **not yet available or verified** for NBTevo_M17365I.

## Build
Android Studio (SDK 35, JDK 17), or GitHub Actions → **Android APK** → artifact `ENiGMA-iDrive-debug`.

## Safety
Do not rely on this prototype for live navigation or modify factory head-unit software. Preserve factory reversing camera, PDC and warning priority. Never stream personal phone content across untrusted networks.

See upstream native Android Auto research: https://github.com/Sq00ky/Supra-iDrive6-Android-Auto (independent, noncommercial licensing applies).
