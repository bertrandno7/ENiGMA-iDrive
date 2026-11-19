# ENiGMA CAM v0.9 - Playback recovery
Android camera diagnostic companion. HiMobileCam camera Wi-Fi, recording browsing, and local saving.
RTSP now tries UDP (with built-in automatic TCP failover), then explicitly TCP on errors or 18-second stall.
A 200 DESCRIBE reply alone is never considered successful video playback. First rendered frame must arrive.
Direct recording playback offers Download and Play Locally after decode/buffer failure.
This beta is built by GitHub Actions with a NEW Android debug signing identity. You may need to uninstall the earlier APK before installation. Existing gallery videos remain on the phone; app-local imported URI preferences won't survive uninstall.
Physical dashcam RTP/RTSP compatibility and Android 17 playback are NOT verified.
